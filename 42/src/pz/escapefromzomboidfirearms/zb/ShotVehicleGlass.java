package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import org.joml.Vector3f;
import pz.escapefromzomboidfirearms.zb.ShotContacts.Face;
import pz.escapefromzomboidfirearms.zb.ShotContacts.Hit;
import pz.escapefromzomboidfirearms.zb.ShotContacts.Shape;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehicleWindow;

/**
 * Vehicle glass where the vehicle draws it (W10 carglass, user's item "car windows cannot be shot through").
 * ShotVehicleGlassData holds the window triangles of every vanilla vehicle mesh, found by its mask texture with the
 * native zone of each window part (tools/vehicle_glass.py). For a script whose model file, mask, model script mesh and
 * scale match that data, the shot owner traces the real panes (Tarkov Glass) instead of the script-area proxies, and the
 * box shell is open wherever a pane lies behind it (its projection along the face normal). Other scripts (mods, the two
 * vanilla multi-mesh "WithDoors" cars) keep the proxies. A pane a round crossed breaks natively (VehicleWindow.damage:
 * item removed, smash sound, broken glass, alarm, part sync to the clients). Server-safe: the dedicated server traces
 * the same panes; the vanilla shot path's pane is ShotVehicleGlassNative (client JAR).
 */
public final class ShotVehicleGlass {
    private ShotVehicleGlass() {}
    /** Window part of each native zone, in BaseVehicle.doWindowDamage's order (Rear before Middle). */
    private static final String[][] OWNERS={{"WindowFrontLeft"},{"WindowFrontRight"},{"WindowRearLeft","WindowMiddleLeft"},
            {"WindowRearRight","WindowMiddleRight"},{"Windshield"},{"WindshieldRear"}};
    /** A pane faces a shell face when its outward normal leans this much towards the face's outward axis. */
    private static final float FACING=0.2f;
    private static final float EPS=1e-4f;

    /** One pane: native zone, triangles in vehicle-local cells (9 floats each), area-weighted outward normal. */
    public record Pane(String zone,float[] tris,Vec3 normal) {
        public Shape shape() { return new Triangles(tris); }
    }
    /** A script's measured glass and greenhouse (exterior roof, pillars, sills around the glass) in vehicle-local cells;
     * key names the data model (file|mask). */
    public record Measured(String key,List<Pane> panes,float[] body,Vec3 min,Vec3 max,Map<Float,Shape> cabins) {
        Measured(String key,List<Pane> panes,float[] body) {
            this(key,panes,body,bound(panes,body,-1),bound(panes,body,1),new java.util.concurrent.ConcurrentHashMap<>());
        }
        /** The cabin above the chassis split as the mesh draws it: the greenhouse clipped at the split. It replaces the
         * script box's cabin sides, ends and roof, which stand off sloped and leaning glass. */
        public Shape cabin(float split) { return cabins.computeIfAbsent(split,y->new Triangles(clipAbove(body,y))); }
        /** The pane of a window part, or null when the mesh draws none for it (that part keeps its proxy). */
        public Pane pane(String part,java.util.Set<String> windows) {
            String zone=zone(part,windows);
            if (zone!=null) for (Pane p:panes) if (p.zone().equals(zone)) return p;
            return null;
        }
        /** Panes no window part of this vehicle owns: fixed glass (vanilla SmallCar02 rear quarters). */
        public List<Pane> fixed(java.util.Set<String> windows) {
            List<Pane> out=new ArrayList<>();
            for (Pane p:panes) if (owner(p.zone(),windows)==null) out.add(p);
            return out;
        }
    }
    /** Lowest (sign -1) or highest (sign 1) corner of all panes and the greenhouse. */
    static Vec3 bound(List<Pane> panes,float[] body,int sign) {
        float[] b={sign*-Float.MAX_VALUE,sign*-Float.MAX_VALUE,sign*-Float.MAX_VALUE};
        List<float[]> all=new ArrayList<>();for (Pane p:panes) all.add(p.tris());all.add(body);
        for (float[] t:all) for (int i=0;i<t.length;i++) b[i%3]=sign>0?Math.max(b[i%3],t[i]):Math.min(b[i%3],t[i]);
        return new Vec3(b[0],b[1],b[2]);
    }
    static String zone(String part,java.util.Set<String> windows) {
        for (int i=0;i<OWNERS.length;i++) if (part.equals(owner(ShotVehicleGlassData.ZONES[i],windows))) return ShotVehicleGlassData.ZONES[i];
        return null;
    }
    static String owner(String zone,java.util.Set<String> windows) {
        for (int i=0;i<OWNERS.length;i++) if (ShotVehicleGlassData.ZONES[i].equals(zone))
            for (String part:OWNERS[i]) if (windows.contains(part)) return part;
        return null;
    }

    private static final Map<String,ShotVehicleGlassData.Model> MODELS=new HashMap<>();
    static { for (var m:ShotVehicleGlassData.MODELS) MODELS.put(m.file()+"|"+m.mask(),m); }
    private static final Map<VehicleScript,Measured> CACHE=new WeakHashMap<>();
    private static final Measured NONE=new Measured("",List.of(),new float[0]);
    private static Boolean api;
    private static String apiFailure;

    /** The native members the measured path reads; missing ones leave every vehicle on the proxies (logged once). */
    static synchronized boolean api() {
        if (api!=null) return api;
        try {
            Class<?> script=VehicleScript.class,model=Class.forName("zombie.scripting.objects.VehicleScript$Model");
            Class<?> skin=Class.forName("zombie.scripting.objects.VehicleScript$Skin");
            Class<?> manager=Class.forName("zombie.scripting.ScriptManager"),modelScript=Class.forName("zombie.scripting.objects.ModelScript");
            boolean ok=script.getMethod("getModel").getReturnType()==model && model.getMethod("getFile").getReturnType()==String.class
                    && model.getMethod("getRotate").getReturnType()==Vector3f.class && script.getMethod("getTextures").getReturnType()==skin
                    && skin.getField("textureMask").getType()==String.class && manager.getField("instance").getType()==manager
                    && manager.getMethod("getModelScript",String.class).getReturnType()==modelScript
                    && modelScript.getMethod("getMeshName").getReturnType()==String.class && modelScript.getField("scale").getType()==float.class
                    && VehicleWindow.class.getMethod("isHittable").getReturnType()==boolean.class
                    && VehicleWindow.class.getMethod("getHealth").getReturnType()==int.class
                    && VehicleWindow.class.getMethod("damage",int.class).getReturnType()==void.class;
            if (!ok) apiFailure="signature";
            api=ok;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { apiFailure=String.valueOf(e);api=false; }
        if (!api) System.out.println("[TW-SHOT] vehicle glass: native ABI missing ("+apiFailure+"); vehicle windows keep script-area proxies");
        return api;
    }

    /** The measured glass of a script, or null (unknown model or mask, changed model script, rotated model). */
    public static Measured measured(VehicleScript script) {
        if (script==null || !api()) return null;
        synchronized (CACHE) { Measured m=CACHE.get(script);if (m!=null) return m==NONE?null:m; }
        Measured m=build(script);
        synchronized (CACHE) { CACHE.put(script,m==null?NONE:m); }
        return m;
    }
    private static Measured build(VehicleScript script) {
        var model=script.getModel();var skin=script.getTextures();
        if (model==null || skin==null) return null;
        var data=MODELS.get(model.getFile()+"|"+skin.textureMask);
        if (data==null) return null; // not a vanilla model and mask: the script-area proxies, as before
        Vector3f rotate=model.getRotate(),offset=script.getModelOffset();
        var modelScript=zombie.scripting.ScriptManager.instance.getModelScript(model.getFile());
        float scale=script.getModelScale();
        String refused=rotate!=null && (rotate.x!=0 || rotate.y!=0 || rotate.z!=0)?"rotated model"
                :offset==null || !Float.isFinite(offset.x) || !Float.isFinite(offset.y) || !Float.isFinite(offset.z)?"model offset"
                :!(Float.isFinite(scale) && scale>0)?"model scale"
                :modelScript==null?"no model script "+model.getFile()
                :!data.mesh().equals(modelScript.getMeshName()) || modelScript.scale!=data.meshScale()
                ?"model script "+modelScript.getMeshName()+" x"+modelScript.scale+" is not the measured "+data.mesh()+" x"+data.meshScale():null;
        if (refused!=null) {
            // A vanilla model a mod changed: its glass is not the measured one. Said once per script.
            System.out.println("[TW-SHOT] vehicle glass: "+script.getFullName()+" keeps script-area proxies ("+refused+")");
            return null;
        }
        List<float[]> zones=new ArrayList<>();List<String> codes=new ArrayList<>();
        double[] centre=new double[3];int points=0;
        for (int z=0;z<ShotVehicleGlassData.ZONES.length;z++) {
            if (data.zones()[z]==null) continue;
            float[] tris=decode(data.zones()[z],scale,offset);
            zones.add(tris);codes.add(ShotVehicleGlassData.ZONES[z]);
            for (int i=0;i<tris.length;i++) centre[i%3]+=tris[i];
            points+=tris.length/3;
        }
        Vec3 middle=new Vec3((float)(centre[0]/points),(float)(centre[1]/points),(float)(centre[2]/points));
        List<Pane> panes=new ArrayList<>();
        for (int i=0;i<zones.size();i++) panes.add(new Pane(codes.get(i),zones.get(i),outward(zones.get(i),middle)));
        return new Measured(data.file()+"|"+data.mask(),List.copyOf(panes),decode(ShotVehicleGlassData.BODY[data.body()],scale,offset));
    }
    /** Base64 little-endian int16 (1e-4 script units) to vehicle-local cells: times the model scale, plus its offset. */
    static float[] decode(String text,float scale,Vector3f offset) {
        java.nio.ByteBuffer bytes=java.nio.ByteBuffer.wrap(java.util.Base64.getDecoder().decode(text)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[] out=new float[bytes.remaining()/2];
        for (int i=0;i<out.length;i++) out[i]=bytes.getShort()*1e-4f*scale+(i%3==0?offset.x:i%3==1?offset.y:offset.z);
        return out;
    }
    /** The part of each triangle at or above y (Sutherland-Hodgman against one plane: 0, 1 or 2 triangles). */
    static float[] clipAbove(float[] t,float y) {
        float[] out=new float[t.length*2];int n=0;
        float[] poly=new float[12];
        for (int i=0;i<t.length;i+=9) {
            int m=0;
            for (int k=0;k<3;k++) {
                int a=i+3*k,b=i+3*((k+1)%3);
                boolean ain=t[a+1]>=y,bin=t[b+1]>=y;
                if (ain) { poly[m++]=t[a];poly[m++]=t[a+1];poly[m++]=t[a+2]; }
                if (ain!=bin) {
                    float f=(y-t[a+1])/(t[b+1]-t[a+1]);
                    poly[m++]=t[a]+f*(t[b]-t[a]);poly[m++]=y;poly[m++]=t[a+2]+f*(t[b+2]-t[a+2]);
                }
            }
            for (int k=3;k+3<=m-3;k+=3) {
                System.arraycopy(poly,0,out,n,3);System.arraycopy(poly,k,out,n+3,3);System.arraycopy(poly,k+3,out,n+6,3);n+=9;
            }
        }
        return java.util.Arrays.copyOf(out,n);
    }
    /** Area-weighted normal of a pane, each triangle turned away from the middle of all the vehicle's glass first:
     * double-sided panes (both windings) would otherwise cancel to noise. */
    static Vec3 outward(float[] t,Vec3 middle) {
        double cx=0,cy=0,cz=0;
        for (int i=0;i<t.length;i+=3) { cx+=t[i];cy+=t[i+1];cz+=t[i+2]; }
        int n=t.length/3;
        double dx=cx/n-middle.x(),dy=cy/n-middle.y(),dz=cz/n-middle.z(),nx=0,ny=0,nz=0;
        for (int i=0;i<t.length;i+=9) {
            double ax=t[i+3]-t[i],ay=t[i+4]-t[i+1],az=t[i+5]-t[i+2],bx=t[i+6]-t[i],by=t[i+7]-t[i+1],bz=t[i+8]-t[i+2];
            double x=ay*bz-az*by,y=az*bx-ax*bz,z=ax*by-ay*bx,sign=x*dx+y*dy+z*dz<0?-1:1;
            nx+=sign*x;ny+=sign*y;nz+=sign*z;
        }
        double l=Math.sqrt(nx*nx+ny*ny+nz*nz);
        return l<1e-12?new Vec3(0,1,0):new Vec3((float)(nx/l),(float)(ny/l),(float)(nz/l));
    }
    /** A window pane is there to hit: installed, whole, closed, in a closed installed door (an open door took it away). */
    public static boolean intact(VehiclePart part) {
        VehicleWindow window=part.getWindow();VehiclePart parent=part.getParent();
        return window!=null && ShotContactsPz.vehicleInstalled(part) && part.getCondition()>0 && !window.isDestroyed()
                && !window.isOpen() && (parent==null || ShotContactsPz.vehicleInstalled(parent)
                && (parent.getDoor()==null || !parent.getDoor().isOpen()));
    }

    /**
     * A box shell face open where a pane lies behind it; outward is +1 for a face on the high side of its axis. The box
     * stands off sloped glass (a windshield under the box's roof, side glass leaning in): the air in front of a pane is
     * its projection on the face along the face's axis and along the pane's own normal, both opened.
     */
    public static Shape shell(Face face,int outward,List<Face> apertures,Measured glass) {
        if (glass==null) return new ShotContactsPz.VehicleShell(face,apertures);
        int a=face.axis(),u=a==0?2:0,v=a==1?2:1;
        List<float[]> holes=new ArrayList<>();
        for (Pane p:glass.panes()) {
            float n=a==0?p.normal().x():a==1?p.normal().y():p.normal().z();
            if (n*outward<FACING) continue;
            float[] t=p.tris();double centre=0;
            for (int i=a;i<t.length;i+=3) centre+=t[i];
            if (outward*(centre/(t.length/3)-face.coordinate())>0) continue;
            float[] axis=new float[t.length/3*2],normal=new float[t.length/3*2];
            for (int i=0,j=0;i<t.length;i+=3,j+=2) {
                axis[j]=t[i+u];axis[j+1]=t[i+v];
                float k=(face.coordinate()-t[i+a])/n;
                normal[j]=t[i+u]+k*(u==0?p.normal().x():p.normal().z());
                normal[j+1]=t[i+v]+k*(v==1?p.normal().y():p.normal().z());
            }
            holes.add(axis);holes.add(normal);
        }
        return holes.isEmpty()?new ShotContactsPz.VehicleShell(face,apertures):new GlassShell(face,apertures,a,u,v,List.copyOf(holes));
    }
    record GlassShell(Face face,List<Face> apertures,int axis,int u,int v,List<float[]> holes) implements Shape {
        @Override public Hit intersect(Vec3 from,Vec3 to) {
            Hit hit=new ShotContactsPz.VehicleShell(face,apertures).intersect(from,to);
            if (hit==null) return null;
            Vec3 p=from.plus(to.minus(from).times((float)hit.fraction()));
            return covered(holes,get(p,u),get(p,v))?null:hit;
        }
    }
    static float get(Vec3 p,int axis) { return axis==0?p.x():axis==1?p.y():p.z(); }
    /** Point (pu,pv) inside one of the flattened triangles (6 floats each), with a crack-closing tolerance. */
    static boolean covered(List<float[]> holes,float pu,float pv) {
        for (float[] t:holes) for (int i=0;i<t.length;i+=6) {
            double bu=t[i+2]-t[i],bv=t[i+3]-t[i+1],cu=t[i+4]-t[i],cv=t[i+5]-t[i+1],qu=pu-t[i],qv=pv-t[i+1];
            double d=bu*cv-bv*cu;
            if (Math.abs(d)<1e-12) continue;
            double lb=(qu*cv-qv*cu)/d,lc=(bu*qv-bv*qu)/d;
            if (lb>=-1e-3 && lc>=-1e-3 && lb+lc<=1+1e-3) return true;
        }
        return false;
    }
    /** Zero-thickness triangles (a pane, the greenhouse): the nearest one the segment crosses (Moller-Trumbore), normal
     * against the segment. */
    record Triangles(float[] tris) implements Shape {
        @Override public Hit intersect(Vec3 from,Vec3 to) {
            double dx=to.x()-from.x(),dy=to.y()-from.y(),dz=to.z()-from.z();
            if (dx*dx+dy*dy+dz*dz<1e-18) return contains(from)?new Hit(0,new Vec3(0,1,0)):null;
            double best=Double.POSITIVE_INFINITY;double bx=0,by=1,bz=0;
            for (int i=0;i<tris.length;i+=9) {
                double e1x=tris[i+3]-tris[i],e1y=tris[i+4]-tris[i+1],e1z=tris[i+5]-tris[i+2];
                double e2x=tris[i+6]-tris[i],e2y=tris[i+7]-tris[i+1],e2z=tris[i+8]-tris[i+2];
                double px=dy*e2z-dz*e2y,py=dz*e2x-dx*e2z,pz=dx*e2y-dy*e2x,det=e1x*px+e1y*py+e1z*pz;
                if (Math.abs(det)<1e-14) continue;
                double tx=from.x()-tris[i],ty=from.y()-tris[i+1],tz=from.z()-tris[i+2];
                double b1=(tx*px+ty*py+tz*pz)/det;
                if (b1<-1e-6 || b1>1+1e-6) continue;
                double qx=ty*e1z-tz*e1y,qy=tz*e1x-tx*e1z,qz=tx*e1y-ty*e1x;
                double b2=(dx*qx+dy*qy+dz*qz)/det;
                if (b2<-1e-6 || b1+b2>1+1e-6) continue;
                double t=(e2x*qx+e2y*qy+e2z*qz)/det;
                if (t<-1e-9 || t>1+1e-9 || t>=best) continue;
                best=t;bx=e1y*e2z-e1z*e2y;by=e1z*e2x-e1x*e2z;bz=e1x*e2y-e1y*e2x;
            }
            if (!Double.isFinite(best)) return null;
            if (bx*dx+by*dy+bz*dz>0) { bx=-bx;by=-by;bz=-bz; }
            double l=Math.sqrt(bx*bx+by*by+bz*bz);
            return new Hit(Math.clamp(best,0,1),new Vec3((float)(bx/l),(float)(by/l),(float)(bz/l)));
        }
        /** On a triangle within 1e-4 cells (the contact point a round continues from). */
        @Override public boolean contains(Vec3 p) {
            for (int i=0;i<tris.length;i+=9) {
                double e1x=tris[i+3]-tris[i],e1y=tris[i+4]-tris[i+1],e1z=tris[i+5]-tris[i+2];
                double e2x=tris[i+6]-tris[i],e2y=tris[i+7]-tris[i+1],e2z=tris[i+8]-tris[i+2];
                double nx=e1y*e2z-e1z*e2y,ny=e1z*e2x-e1x*e2z,nz=e1x*e2y-e1y*e2x,nn=nx*nx+ny*ny+nz*nz;
                if (nn<1e-18) continue;
                double wx=p.x()-tris[i],wy=p.y()-tris[i+1],wz=p.z()-tris[i+2],dist=(wx*nx+wy*ny+wz*nz)/Math.sqrt(nn);
                if (Math.abs(dist)>EPS) continue;
                double d11=e1x*e1x+e1y*e1y+e1z*e1z,d12=e1x*e2x+e1y*e2y+e1z*e2z,d22=e2x*e2x+e2y*e2y+e2z*e2z;
                double w1=wx*e1x+wy*e1y+wz*e1z,w2=wx*e2x+wy*e2y+wz*e2z,den=d11*d22-d12*d12;
                double s=(d22*w1-d12*w2)/den,r=(d11*w2-d12*w1)/den;
                if (s>=-1e-3 && r>=-1e-3 && s+r<=1+1e-3) return true;
            }
            return false;
        }
    }

    /** The pane a round crossed breaks: native damage of its whole health (SP or the server; the server syncs the part). */
    public static boolean breakPane(VehiclePart part) {
        VehicleWindow window=part==null?null:part.getWindow();
        if (window==null || !window.isHittable()) return false;
        window.damage(window.getHealth());
        return window.isDestroyed();
    }

    private static final ThreadLocal<int[]> OWNED=ThreadLocal.withInitial(()->new int[1]);
    /** A native vehicle hit of the shot owner's own contact (ShotDamagePz): the vanilla-path advice leaves it alone. */
    public static boolean owned(BooleanSupplier hit) {
        int[] depth=OWNED.get();depth[0]++;
        try { return hit.getAsBoolean(); } finally { depth[0]--; }
    }
    /** Depth of the shot owner's own native vehicle hits on this thread (ShotVehicleGlassNative leaves those alone). */
    public static boolean ownedHit() { return OWNED.get()[0]>0; }
}
