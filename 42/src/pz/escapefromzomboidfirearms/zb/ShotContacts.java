package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import pz.escapefromzomboidfirearms.zb.ShotSnapshot.*;
import pz.escapefromzomboidfirearms.zb.ShotPoseSnapshot.Pz;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;

/** Read-only swept contacts, in metres relative to the immutable muzzle. No flight,
 * inventory, camera, damage, penetration or render dependencies. B3 stops at the
 * first contact; unmeasured obstacles must be represented conservatively by World.
 */
public final class ShotContacts implements Contacts {
    private static final double EPS = 1e-9;
    public interface World {
        boolean ready();
        List<Collider> colliders(ShotSnapshot shot, Segment segment) throws Exception;
    }
    public interface Shape {
        Hit intersect(Vec3 from, Vec3 to);
        default boolean contains(Vec3 point) { return intersect(point,point)!=null; }
    }
    /** Several mesh-fitted capsules can name the same anatomical part. Their
     * union is one tissue volume, not repeated entry damage on overlapping bones. */
    public record Union(List<Shape> shapes) implements Shape {
        public Union { shapes=List.copyOf(shapes); }
        @Override public boolean contains(Vec3 point) { return shapes.stream().anyMatch(s->s.contains(point)); }
        @Override public Hit intersect(Vec3 from,Vec3 to) {
            Hit nearest=null;
            for (Shape shape:shapes) {
                Hit hit=shape.intersect(from,to);
                if (hit!=null && (nearest==null || hit.fraction<nearest.fraction)) nearest=hit;
            }
            return nearest;
        }
    }
    public record Hit(double fraction, Vec3 normal) {
        public Hit {
            if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1) throw new IllegalArgumentException("Fraction");
            Objects.requireNonNull(normal);
        }
    }
    public record Collider(String target, TargetKind kind, Shape shape, int ragdollPart,
            int bodyPart, String material, String proof) {
        public Collider {
            ShotSnapshot.text(target); Objects.requireNonNull(kind); Objects.requireNonNull(shape);
            ShotSnapshot.text(material); ShotSnapshot.text(proof);
            if (kind == TargetKind.CHARACTER && (ragdollPart < 0 || bodyPart < 0)) {
                throw new IllegalArgumentException("Character requires explicit native zones");
            }
        }
    }
    /** Infinite plane. A coplanar segment touches at its beginning. */
    public record Plane(Vec3 point, Vec3 normal) implements Shape {
        public Plane { Objects.requireNonNull(point); ShotPoseSnapshot.unit(normal); }
        @Override public Hit intersect(Vec3 from, Vec3 to) {
            double a = dot(sub(from, point), normal), d = dot(sub(to, from), normal);
            if (Math.abs(d) < EPS) return Math.abs(a) < EPS ? new Hit(0, normal) : null;
            double t = -a/d;
            return in(t) ? new Hit(clamp(t), d > 0 ? mul(normal, -1) : normal) : null;
        }
    }
    /** Closed AABB, including tangent and zero-length segments. */
    public record Box(Vec3 min, Vec3 max) implements Shape {
        public Box {
            Objects.requireNonNull(min); Objects.requireNonNull(max);
            for (int i=0;i<3;i++) if (get(min,i)>get(max,i)) throw new IllegalArgumentException("Box bounds");
        }
        @Override public Hit intersect(Vec3 from, Vec3 to) {
            double enter=0, leave=1;
            Vec3 n = opposing(from,to);
            for (int i=0;i<3;i++) {
                double p=get(from,i), d=get(to,i)-p, lo=get(min,i), hi=get(max,i);
                if (Math.abs(d)<EPS) { if (p<lo-EPS || p>hi+EPS) return null; continue; }
                double a=(lo-p)/d, b=(hi-p)/d;
                if (a>b) { double swap=a;a=b;b=swap; }
                if (a>enter) { enter=a; n=axis(i,d>0?-1:1); }
                leave=Math.min(leave,b);
                if (enter>leave+EPS) return null;
            }
            return in(enter) && leave>=-EPS ? new Hit(clamp(enter),n) : null;
        }
    }
    /** Bounded, zero-thickness native cell face: no invented wall thickness. */
    public record Face(int axis, float coordinate, Vec3 min, Vec3 max) implements Shape {
        public Face {
            if (axis<0 || axis>2 || !Float.isFinite(coordinate)) throw new IllegalArgumentException("Face");
            new Box(min,max);
        }
        @Override public Hit intersect(Vec3 from, Vec3 to) {
            double d=get(to,axis)-get(from,axis);
            if (Math.abs(d)<EPS) {
                if (Math.abs(get(from,axis)-coordinate)>EPS) return null;
                return new Box(min,max).intersect(from,to);
            }
            double t=(coordinate-get(from,axis))/d;
            if (!in(t)) return null;
            Vec3 p=lerp(from,to,clamp(t));
            for (int i=0;i<3;i++) if (get(p,i)<get(min,i)-1e-6 || get(p,i)>get(max,i)+1e-6) return null;
            return new Hit(clamp(t),ShotContacts.axis(axis,d>0?-1:1));
        }
    }
    /** Capsule fitted to the original PZ body, transformed from its named bone each trace. */
    public record Capsule(Vec3 a, Vec3 b, float radius) implements Shape {
        public Capsule {
            Objects.requireNonNull(a); Objects.requireNonNull(b);
            if (!Float.isFinite(radius) || radius<=0) throw new IllegalArgumentException("Capsule radius");
        }
        @Override public Hit intersect(Vec3 from, Vec3 to) {
            Vec3 ba=sub(b,a), oa=sub(from,a), d=sub(to,from);
            double baba=dot(ba,ba), dd=dot(d,d);
            double h=baba<EPS?0:Math.clamp(dot(oa,ba)/baba,0,1);
            Vec3 q=sub(from,add(a,mul(ba,h)));
            double rr=(double)radius*radius;
            if (dot(q,q)<=rr+EPS) return new Hit(0,normal(q,opposing(from,to)));
            if (dd<EPS) return null;
            double best=Double.POSITIVE_INFINITY;
            if (baba>EPS) {
                double bd=dot(ba,d), bo=dot(ba,oa), od=dot(oa,d), oo=dot(oa,oa);
                double aa=baba*dd-bd*bd, bb=baba*od-bo*bd;
                double cc=baba*oo-bo*bo-rr*baba;
                double disc=bb*bb-aa*cc;
                if (aa>EPS && disc>=-EPS) {
                    double t=(-bb-Math.sqrt(Math.max(0,disc)))/aa;
                    double along=bo+t*bd;
                    if (in(t) && along>=0 && along<=baba) best=clamp(t);
                }
            }
            best=Math.min(best,sphere(from,d,a,radius));
            best=Math.min(best,sphere(from,d,b,radius));
            if (!Double.isFinite(best)) return null;
            Vec3 p=lerp(from,to,best);
            h=baba<EPS?0:Math.clamp(dot(sub(p,a),ba)/baba,0,1);
            return new Hit(best,normal(sub(p,add(a,mul(ba,h))),opposing(from,to)));
        }
    }
    /** A checked native intersection already expressed as a segment fraction. */
    public record Fixed(Hit hit) implements Shape {
        public Fixed { Objects.requireNonNull(hit); }
        @Override public Hit intersect(Vec3 from, Vec3 to) { return hit; }
    }
    private final World world;
    public static Face subFace(Face face,double u0,double u1,double v0,double v1) {
        Vec3 lo=face.min(),hi=face.max();
        if (face.axis()==0) {
            return new Face(0,face.coordinate(),
                    new Vec3(face.coordinate(),lerp1(lo.y(),hi.y(),v0),lerp1(lo.z(),hi.z(),u0)),
                    new Vec3(face.coordinate(),lerp1(lo.y(),hi.y(),v1),lerp1(lo.z(),hi.z(),u1)));
        }
        if (face.axis()==2) {
            return new Face(2,face.coordinate(),
                    new Vec3(lerp1(lo.x(),hi.x(),u0),lerp1(lo.y(),hi.y(),v0),face.coordinate()),
                    new Vec3(lerp1(lo.x(),hi.x(),u1),lerp1(lo.y(),hi.y(),v1),face.coordinate()));
        }
        return new Face(1,face.coordinate(),
                new Vec3(lerp1(lo.x(),hi.x(),u0),face.coordinate(),lerp1(lo.z(),hi.z(),v0)),
                new Vec3(lerp1(lo.x(),hi.x(),u1),face.coordinate(),lerp1(lo.z(),hi.z(),v1)));
    }
    private static float lerp1(float a,float b,double t) { return (float)(a+((double)b-a)*t); }
    public static void addWindowFrame(List<Collider> out,String target,Face boundary,boolean fullWindow,String framePreset) {
        double u0=fullWindow?0.04:0.06,u1=fullWindow?0.96:0.94,v0=fullWindow?0.10:0.27,v1=fullWindow?0.90:0.82;
        String material=framePreset==null?"unmeasured-window-frame":"window-frame|b5="+framePreset;
        String proof=framePreset==null?"conservative:window-surround-frame-untyped":"PZ.window-surround/sill-lintel-jambs";
        out.add(new Collider(target,TargetKind.WORLD,subFace(boundary,0,1,0,v0),-1,-1,material,proof));
        out.add(new Collider(target,TargetKind.WORLD,subFace(boundary,0,1,v1,1),-1,-1,material,proof));
        out.add(new Collider(target,TargetKind.WORLD,subFace(boundary,0,u0,v0,v1),-1,-1,material,proof));
        out.add(new Collider(target,TargetKind.WORLD,subFace(boundary,u1,1,v0,v1),-1,-1,material,proof));
    }
    public static void addWindowAperture(List<Collider> out,String target,Face boundary,boolean fullWindow,
            boolean open,boolean smashed,boolean glassRemoved,boolean destroyed,boolean barricaded,String barricadePreset) {
        double u0=fullWindow?0.04:0.06,u1=fullWindow?0.96:0.94,v0=fullWindow?0.10:0.27,v1=fullWindow?0.90:0.82;
        Face opening=subFace(boundary,u0,u1,v0,v1);
        if (barricaded) {
            String mat=barricadePreset==null?"barricade":"barricade|b5="+barricadePreset;
            out.add(new Collider(target,TargetKind.WORLD,opening,-1,-1,mat,"PZ.window-state/cell-face"));
            return;
        }
        if (!open && !smashed && !glassRemoved && !destroyed) {
            out.add(new Collider(target,TargetKind.WORLD,opening,-1,-1,"glass","PZ.window-state/cell-face"));
        }
    }
    public static Collider doorLeaf(String target,Face northFace,Face westFace,boolean north,boolean open,double topFraction) {
        Face base=(north^open)?northFace:westFace;
        Face bounded=topFraction>=1?base:subFace(base,0,1,0,topFraction);
        return new Collider(target,TargetKind.WORLD,bounded,-1,-1,"door",
                "PZ.door-state/"+(open?"rotated-leaf":"closed-face"));
    }
    public static Collider window(String target,Face face,boolean open,boolean smashed,boolean destroyed,boolean barricaded) {
        boolean pane=!open && !smashed && !destroyed;
        String material=barricaded?"barricade":pane?"glass":"unmeasured-window-frame";
        return new Collider(target,TargetKind.WORLD,face,-1,-1,material,
                pane || barricaded?"PZ.window-state/cell-face":"conservative:open-or-broken-frame-unmeasured");
    }
    public static Collider door(String target,Face northFace,Face westFace,boolean north,boolean open) {
        return new Collider(target,TargetKind.WORLD,(north^open)?northFace:westFace,-1,-1,"door",
                "PZ.door-state/"+(open?"rotated-leaf":"closed-face"));
    }
    public ShotContacts(World world) { this.world=Objects.requireNonNull(world); }
    @Override public boolean ready() { return world.ready(); }
    @Override public List<Contact> trace(ShotSnapshot shot, Segment segment) throws Exception {
        validate(shot,segment);
        if (!ready()) throw new IllegalStateException("Contacts unavailable");
        List<Contact> contacts=ordered(segment,world.colliders(shot,segment));
        return contacts.isEmpty()?List.of():List.of(contacts.getFirst());
    }
    public static String volume(Contact contact) { return contact.key().target()+"/zone/"+contact.bodyPart(); }
    @Override public List<Contact> sweep(ShotSnapshot shot, Segment segment, java.util.Set<String> occupied) throws Exception {
        validate(shot,segment);
        if (!ready()) throw new IllegalStateException("Contacts unavailable");
        ArrayList<Contact> result=new ArrayList<>();
        var grouped=new java.util.LinkedHashMap<String,List<Collider>>();
        for (Collider c:world.colliders(shot,segment)) grouped.computeIfAbsent(c.target+"/zone/"+c.bodyPart,k->new ArrayList<>()).add(c);
        for (List<Collider> group:grouped.values()) {
            Collider c=group.getFirst();
            Shape shape=group.size()==1?c.shape:new Union(group.stream().map(Collider::shape).toList());
            String key=c.target+"/zone/"+c.bodyPart;
            boolean inside=occupied.contains(key);
            if (inside && !shape.contains(segment.from())) { occupied.remove(key);inside=false; }
            Hit hit;
            if (inside) {
                if (shape.contains(segment.to())) continue;
                // World exits do not represent another material entry. Tissue
                // exits use B5's backward BodyPartCollider route, without damage.
                if (c.kind!=TargetKind.CHARACTER) continue;
                Hit reverse=shape.intersect(segment.to(),segment.from());
                if (reverse==null) throw new IllegalStateException("Missing occupied volume exit");
                hit=new Hit(1-reverse.fraction,mul(reverse.normal,-1));
            } else hit=shape.intersect(segment.from(),segment.to());
            if (hit==null) continue;
            result.add(new Contact(new ImpactKey(segment.pellet(),0,c.target),c.kind,(float)hit.fraction,
                    lerp(segment.from(),segment.to(),hit.fraction),hit.normal,c.ragdollPart,c.bodyPart,c.material,c.proof,null,!inside));
        }
        result.sort(ORDER);
        return result.isEmpty()?List.of():List.of(result.getFirst());
    }
    public static void validate(ShotSnapshot shot, Segment segment) {
        Objects.requireNonNull(shot); Objects.requireNonNull(segment);
        if (!segment.pellet().shot().equals(shot.id()) || segment.pellet().index()>=shot.ammo().projectileCount()
                || segment.t1()>shot.ammo().lifeSeconds()) throw new IllegalArgumentException("Foreign/expired segment");
    }
    /** Diagnostic ordering, before B3's first-obstacle stop. One entry per target. */
    public static List<Contact> ordered(Segment segment, List<Collider> colliders) {
        HashMap<String,Contact> nearest=new HashMap<>();
        for (Collider c:colliders) {
            Hit h=c.shape.intersect(segment.from(),segment.to());
            if (h==null) continue;
            // Contact ordinal is zero: B3 has no penetration/continuation through contacts.
            Contact contact=new Contact(new ImpactKey(segment.pellet(),0,c.target),c.kind,(float)h.fraction,
                    lerp(segment.from(),segment.to(),h.fraction),h.normal,c.ragdollPart,c.bodyPart,c.material,c.proof);
            nearest.merge(c.target,contact,(a,b)->ORDER.compare(a,b)<=0?a:b);
        }
        ArrayList<Contact> result=new ArrayList<>(nearest.values()); result.sort(ORDER);
        return List.copyOf(result);
    }
    public static final Comparator<Contact> ORDER=Comparator.comparingDouble(Contact::fraction)
            .thenComparingInt(c->c.kind()==TargetKind.CHARACTER?1:0)
            .thenComparing(c->c.key().target()).thenComparingInt(Contact::ragdollPart)
            .thenComparingInt(Contact::bodyPart).thenComparing(Contact::proof);

    public record Cell(int x, int y, int z, double entry) {}
    /** Exact supercover of a PZ segment, with boundary neighbours, floors and corners.
     * Bounded enumeration is small for B4's 0.01 s steps. Oversized input fails,
     * rather than skipping cells; world coordinates stay double until subtraction.
     */
    public static List<Cell> cells(Pz from, Pz to) {
        double[] a={from.x(),from.y(),from.z()}, b={to.x(),to.y(),to.z()};
        int[] lo=new int[3], hi=new int[3]; long count=1;
        for (int i=0;i<3;i++) {
            double mn=Math.min(a[i],b[i]), mx=Math.max(a[i],b[i]);
            if (mn<Integer.MIN_VALUE+2.0 || mx>Integer.MAX_VALUE-2.0) throw new IllegalArgumentException("Grid range");
            lo[i]=(int)Math.floor(mn)-1;hi[i]=(int)Math.floor(mx);
            count*=((long)hi[i]-lo[i]+1);
            if (count>4096) throw new IllegalArgumentException("Segment exceeds trace cell budget");
        }
        ArrayList<Cell> out=new ArrayList<>();
        for (int x=lo[0];x<=hi[0];x++) for (int y=lo[1];y<=hi[1];y++) for (int z=lo[2];z<=hi[2];z++) {
            int[] c={x,y,z}; double enter=0,leave=1;
            for (int i=0;i<3;i++) {
                double d=b[i]-a[i];
                if (Math.abs(d)<EPS) { if (a[i]<c[i]-EPS || a[i]>c[i]+1+EPS) leave=-1; }
                else {
                    double t0=(c[i]-a[i])/d,t1=(c[i]+1-a[i])/d;
                    enter=Math.max(enter,Math.min(t0,t1)); leave=Math.min(leave,Math.max(t0,t1));
                }
            }
            if (enter<=leave+EPS && in(enter)) out.add(new Cell(x,y,z,clamp(enter)));
        }
        out.sort(Comparator.comparingDouble(Cell::entry).thenComparingInt(Cell::x).thenComparingInt(Cell::y).thenComparingInt(Cell::z));
        return List.copyOf(out);
    }
    private static double sphere(Vec3 from,Vec3 d,Vec3 centre,double r) {
        Vec3 o=sub(from,centre);double aa=dot(d,d),bb=dot(o,d),cc=dot(o,o)-r*r;
        double disc=bb*bb-aa*cc;
        if (disc< -EPS) return Double.POSITIVE_INFINITY;
        double t=(-bb-Math.sqrt(Math.max(0,disc)))/aa;
        return in(t)?clamp(t):Double.POSITIVE_INFINITY;
    }
    static double get(Vec3 v,int axis) { return axis==0?v.x():axis==1?v.y():v.z(); }
    static Vec3 axis(int i,double s) { return new Vec3(i==0?(float)s:0,i==1?(float)s:0,i==2?(float)s:0); }
    static Vec3 sub(Vec3 a,Vec3 b) { return new Vec3(a.x()-b.x(),a.y()-b.y(),a.z()-b.z()); }
    static Vec3 add(Vec3 a,Vec3 b) { return new Vec3(a.x()+b.x(),a.y()+b.y(),a.z()+b.z()); }
    static Vec3 mul(Vec3 a,double f) { return new Vec3((float)(a.x()*f),(float)(a.y()*f),(float)(a.z()*f)); }
    static double dot(Vec3 a,Vec3 b) { return (double)a.x()*b.x()+(double)a.y()*b.y()+(double)a.z()*b.z(); }
    static Vec3 lerp(Vec3 a,Vec3 b,double t) {
        return new Vec3((float)(a.x()+((double)b.x()-a.x())*t),(float)(a.y()+((double)b.y()-a.y())*t),(float)(a.z()+((double)b.z()-a.z())*t));
    }
    static Vec3 normal(Vec3 n,Vec3 fallback) { double l=Math.sqrt(dot(n,n));return l<EPS?fallback:mul(n,1/l); }
    static Vec3 opposing(Vec3 a,Vec3 b) { return normal(sub(a,b),new Vec3(0,1,0)); }
    private static boolean in(double t) { return Double.isFinite(t) && t>=-EPS && t<=1+EPS; }
    private static double clamp(double t) { return Math.clamp(t,0,1); }
}
