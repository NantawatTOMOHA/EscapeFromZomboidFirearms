package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import pz.escapefromzomboidfirearms.zb.ShotPoseSnapshot.Pz;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;

/** CPU-only, bounded visual contact store. Never changes a shot, damage or world. */
public final class ImpactMarks {
    public static final int MAX_PARTICLES=1024, MAX_PER_CONTACT=64, MAX_SEEN=4096, MAX_PENDING=256;
    public static final double LIFE=120, FADE=20, OFFSET_METRES=0.001;
    public static final Store STORE=new Store();
    public static volatile boolean enabled=true, failed;
    public static volatile long errors;
    public static volatile String reason="";
    private ImpactMarks() {}

    /** Cosmetic failure is isolated from both the shot owner and the VP bridge. */
    public static void contact(ShotSnapshot shot,ShotPenetration.Diagnostic diagnostic,boolean live) {
        if (!enabled || failed) return;
        try {
            var c=diagnostic.contact();
            var k=c.key();var id=k.pellet().shot();
            String key=id.session()+"/"+id.epochText()+"/"+id.shooter()+"/"+id.sequenceText()+"/"+k.pellet().index()+"/"+k.contactOrdinal();
            mark(shot.pose().muzzlePz(),c,diagnostic.incomingDirection(),diagnostic.outcome(),key,
                    shot.seed()^((long)k.pellet().index()<<32)^k.contactOrdinal(),sprite(k.target()),live && c.forwardHit(),true);
        } catch (Throwable t) { fail(t); }
    }
    /** MP live: one contact of the server's flight of a shot, the same mark on every client. */
    public static boolean remote(Pz muzzle,ShotSnapshot.Contact c,Vec3 incoming,String outcome,String key,long seed,String sprite) {
        if (!enabled || failed) return false;
        try { return mark(muzzle,c,incoming,outcome,"mp:"+key,seed,sprite,c.forwardHit(),false); }
        catch (Throwable t) { fail(t);return false; }
    }
    static Object resolveTarget(String target) {
        if (target==null) return null;
        Object object=ShotOwner.targetObject(target);
        if (object!=null) return object;
        int slash=target.indexOf('/');
        return slash>0?ShotOwner.targetObject(target.substring(0,slash)):null;
    }
    static String sprite(String target) {
        Object object=resolveTarget(target);
        return object instanceof zombie.iso.IsoObject o ? o.getSpriteName() : null;
    }
    /** The visible surface of a contact: its sprite name and the material properties it authors. */
    record Surface(String sprite,String materialType,String material) {}
    /**
     * Item 3 of wave W8 (25.10.2026, "there are no bullet holes on a big list of surfaces"): the cosmetic
     * resolution needs the surface's own authored material, but a contact only carries a class token.  A real
     * vanilla wall is an `unmeasured-opaque-edge` contact, so it has neither an object nor a sprite; the mark
     * is placed on the wall face that the cell edge belongs to.  Everything here is a read of the loaded cell.
     */
    static Surface surface(String token,String target,String sprite,Pz point,Vec3 normal) {
        Object object=resolveTarget(target);
        if (object instanceof zombie.iso.IsoObject iso) {
            try {
                var props=iso.getProperties();
                return new Surface(iso.getSpriteName(),props==null?null:props.get("MaterialType"),
                        props==null?null:props.get("Material"));
            } catch (RuntimeException ignored) { /* a removed object keeps the token default */ }
        }
        var cell=zombie.iso.IsoWorld.instance==null?null:zombie.iso.IsoWorld.instance.getCell();
        if (cell==null) return sprite==null?null:new Surface(sprite,null,null);
        if ("unmeasured-opaque-edge".equals(token) || "stairs".equals(token)) {
            // The collider of a plain wall is the west or north cell face of the wall's own square
            // (ShotContactsPz.edge); a stair envelope is the whole tile the bullet reached.
            Surface found=squareSurface(cell,(int)Math.floor(point.x()+1e-6),(int)Math.floor(point.y()+1e-6),
                    (int)Math.floor(point.z()-1e-6),token);
            if (found!=null) return found;
            found=squareSurface(cell,(int)Math.floor(point.x()-normal.x()*.02+1e-6),
                    (int)Math.floor(point.y()-normal.z()*.02+1e-6),(int)Math.floor(point.z()-1e-6),token);
            if (found!=null) return found;
        }
        return sprite==null?null:new Surface(sprite,null,null);
    }
    /** The best surface object of one square for a token: the wall flag first, then a solid surface. */
    private static Surface squareSurface(zombie.iso.IsoCell cell,int x,int y,int z,String token) {
        var square=cell.getGridSquare(x,y,z);
        if (square==null) return null;
        Surface wall=null,solid=null;
        for (int list=0;list<2;list++) {
            var objects=list==0?square.getObjects():square.getSpecialObjects();
            if (objects==null) continue;
            for (int i=0;i<objects.size();i++) {
                Object object=objects.get(i);
                if (!(object instanceof zombie.iso.IsoObject iso)) continue;
                var props=iso.getProperties();
                if (props==null) continue;
                boolean isWall=props.has("wall") || props.has(zombie.iso.SpriteDetails.IsoFlagType.WallN)
                        || props.has(zombie.iso.SpriteDetails.IsoFlagType.WallW)
                        || props.has(zombie.iso.SpriteDetails.IsoFlagType.WallSE)
                        || props.has(zombie.iso.SpriteDetails.IsoFlagType.WindowN)
                        || props.has(zombie.iso.SpriteDetails.IsoFlagType.WindowW)
                        || props.has(zombie.iso.SpriteDetails.IsoFlagType.DoorWallN)
                        || props.has(zombie.iso.SpriteDetails.IsoFlagType.DoorWallW);
                boolean isSolid=props.has(zombie.iso.SpriteDetails.IsoFlagType.solid)
                        || props.has(zombie.iso.SpriteDetails.IsoFlagType.solidtrans) || props.has("Surface");
                String name=iso.getSpriteName();
                if (name==null) continue;
                var surface=new Surface(name,props.get("MaterialType"),props.get("Material"));
                if (isWall) { if (wall==null) wall=surface; }
                else if (isSolid && solid==null) solid=surface;
                if (wall!=null && (!"stairs".equals(token) || name.contains("stairs"))) break;
            }
        }
        return wall!=null?wall:solid;
    }
    /** A bush, a hedge or a tree: the collider is a whole tile, so a hole would float off the plant. */
    static boolean soft(String sprite) {
        ImpactSurfaces.Family family=ImpactSurfaces.familyOf(sprite);
        return family!=null && ("vegetation".equals(family.kind()) || "tree".equals(family.kind()));
    }
    /** A moving vehicle does not leave a world-space hole floating in empty air behind it. */
    static boolean movingVehicle(String target) {
        Object object=resolveTarget(target);
        zombie.vehicles.BaseVehicle vehicle=object instanceof zombie.vehicles.BaseVehicle v?v
                :object instanceof zombie.vehicles.VehiclePart part?part.getVehicle():null;
        return vehicle!=null && Math.abs(vehicle.getCurrentSpeedKmHour())>0.5f;
    }
    static boolean mark(Pz muzzle,ShotSnapshot.Contact c,Vec3 incoming,String outcome,String key,long seed,String sprite,
            boolean sound,boolean publish) {
            if (c.kind()==ShotSnapshot.TargetKind.CHARACTER || c.kind()==ShotSnapshot.TargetKind.UNLOADED) return false;
            Pz point=muzzle.offset(c.point());
            String token=ShotWorldMaterials.nativeMaterial(c.material());
            String preset=ShotWorldMaterials.preset(c.material());
            Surface surface=preset==null?surface(token,c.key().target(),sprite,point,c.normal()):null;
            // The shared ballistic adapter keeps native floors conservatively untyped.
            // Cosmetics may read an explicit surface property without changing that outcome.
            if ("floor".equals(token) && Math.abs(c.normal().y())>.99) {
                double level=Math.rint(point.z());
                var cell=zombie.iso.IsoWorld.instance==null?null:zombie.iso.IsoWorld.instance.getCell();
                var square=cell==null || Math.abs(point.z()-level)>1e-5?null:
                        cell.getGridSquare((int)Math.floor(point.x()),(int)Math.floor(point.y()),(int)level);
                var floor=square==null?null:square.getFloor();
                var props=floor==null?null:floor.getProperties();
                if (props!=null && preset==null) preset=ShotWorldMaterials.lookup("floor",props.get("MaterialType"),props.get("Material"));
                if (floor!=null) {
                    point=new Pz(point.x(),point.y(),level);
                    surface=new Surface(floor.getSpriteName(),props==null?null:props.get("MaterialType"),
                            props==null?null:props.get("Material"));
                }
            }
            if (preset==null) preset=ImpactSurfaces.preset(token,surface==null?sprite:surface.sprite(),
                    surface==null?null:surface.materialType(),surface==null?null:surface.material());
            if (preset==null || ImpactData.profile(preset)==null) { STORE.unknown++;unknown(c.material(),sprite);return false; }
            String name=surface==null?sprite:surface.sprite();
            if (ShotWorldMaterials.preset(c.material())==null) fallback(name,preset);
            boolean decal=ImpactSurfaces.decal(token) && !soft(name) && !movingVehicle(c.key().target());
            if (!decal) puffs++;
            resolved(token,name,preset,decal);
            return STORE.accept(new Event(key,preset,point,c.normal(),incoming,outcome,c.forwardHit(),
                    c.kind()==ShotSnapshot.TargetKind.VEHICLE,seed,sound,decal),publish);
    }
    /** Contacts resolved to a material puff without a persistent hole (vegetation, tree and vehicle shells). */
    public static volatile long puffs;
    /** The last cosmetic resolutions (QA): token, sprite, preset, decal. */
    public record Resolution(String token,String sprite,String preset,boolean decal) {}
    public static final ArrayDeque<Resolution> LAST=new ArrayDeque<>();
    static void resolved(String token,String sprite,String preset,boolean decal) {
        synchronized (LAST) {
            if (LAST.size()>=256) LAST.removeFirst();
            LAST.addLast(new Resolution(token,sprite,preset,decal));
        }
    }
    /** The last contacts left without a mark (QA): "token:sprite". */
    public static final ArrayDeque<String> UNKNOWN=new ArrayDeque<>();
    static void unknown(String material,String sprite) {
        synchronized (UNKNOWN) {
            if (UNKNOWN.size()>=16) UNKNOWN.removeFirst();
            UNKNOWN.addLast(ShotWorldMaterials.nativeMaterial(String.valueOf(material))+":"+sprite);
        }
    }
    /** The last surfaces that got a cosmetic preset (QA): "sprite=preset". */
    public static final ArrayDeque<String> FALLBACKS=new ArrayDeque<>();
    public static volatile long fallbacks;
    /** The last surface resolved by the cosmetic rules (QA): "sprite=preset". */
    static void fallback(String sprite,String preset) {
        fallbacks++;
        synchronized (FALLBACKS) {
            if (FALLBACKS.size()>=16) FALLBACKS.removeFirst();
            FALLBACKS.addLast(sprite+"="+preset);
        }
    }
    public static void fail(Throwable t) {
        errors++;
        if (!failed) { failed=true;reason=t.toString();STORE.clear();System.out.println("[TW-IMPACT] disabled: "+reason); }
    }
    public record Event(String id,String preset,Pz point,Vec3 normal,Vec3 incoming,String outcome,
                        boolean forward,boolean vehicle,long seed,boolean sound,boolean decal) {
        public Event(String id,String preset,Pz point,Vec3 normal,Vec3 incoming,String outcome,
                boolean forward,boolean vehicle,long seed,boolean sound) {
            this(id,preset,point,normal,incoming,outcome,forward,vehicle,seed,sound,true);
        }
        public Event {
            if (id==null || id.isEmpty() || id.length()>256 || preset==null || ImpactData.profile(preset)==null
                    || point==null || normal==null || incoming==null
                    || sound && !forward || !(outcome!=null && List.of("STOP","CONTINUE","RICOCHET","FRAGMENT","UNKNOWN_STOP").contains(outcome)))
                throw new IllegalArgumentException("Unproven impact");
            unit(normal);unit(incoming);
        }
    }
    public static final class Mark {
        public final Event event;
        public final String texture;
        public final double born,halfWidth,halfLength;
        public final Vec3 tangent,bitangent;
        public final Pz center;
        Mark(Event e,double time,Random random) {
            event=e;born=time;var profile=ImpactData.profile(e.preset());
            texture=profile.decals()[random.nextInt(profile.decals().length)];
            double size=profile.min()+random.nextDouble()*(profile.max()-profile.min());
            halfWidth=size;
            double incidence=Math.abs(dot(e.incoming(),e.normal()));
            boolean ricochet=e.outcome().equals("RICOCHET");
            halfLength=size*(ricochet?1/Math.max(0.2,incidence):1);
            Vec3 along=e.incoming().minus(e.normal().times((float)dot(e.incoming(),e.normal())));
            if (dot(along,along)<1e-8) along=cross(e.normal(),Math.abs(e.normal().y())<0.9?new Vec3(0,1,0):new Vec3(1,0,0));
            Vec3 t=along.unit(), b=cross(e.normal(),t).unit();
            if (!ricochet) {
                double angle=random.nextDouble()*Math.PI*2;
                t=t.times((float)Math.cos(angle)).plus(b.times((float)Math.sin(angle))).unit();
                b=cross(e.normal(),t).unit();
            }
            tangent=t;bitangent=b;
            center=e.point().offset(e.normal().times((float)OFFSET_METRES));
            // Performance pass 23.10.2026: a mark never changes - its corners once, by the very same arithmetic.
            double[] result=new double[12];
            int[][] signs={{-1,-1},{1,-1},{1,1},{-1,1}};
            for (int i=0;i<4;i++) {
                Pz p=center.offset(tangent.times((float)(signs[i][0]*halfLength)).plus(bitangent.times((float)(signs[i][1]*halfWidth))));
                result[3*i]=p.x();result[3*i+1]=p.y();result[3*i+2]=p.z();
            }
            corners=result;
            front=ImpactFx.isoFront(e.normal());
        }
        private final double[] corners;
        /** The isometric view's own face test of its normal (ImpactFx.isoFront), fixed with it. */
        final boolean front;
        /** Main thread (ImpactFx.isometric): its decal image once the game returned one (a Texture; null: ask again). */
        Object isoTexture;
        public float alpha(double time) { return (float)Math.max(0,Math.min(1,(LIFE-(time-born))/FADE)); }
        /** All corners use the same source normal and metric-to-PZ transform. */
        public double[] corners() { return corners.clone(); }
        /** The corners themselves, read only (ImpactFx; ViewpointPass.quad reads them at once). */
        double[] cornersView() { return corners; }
    }
    public static final class Particle {
        public final Event event;
        public final ImpactData.Particle source;
        public final double born,life,size,limit;
        public final Vec3 initialVelocity;
        public Vec3 velocity;
        // Performance pass 23.10.2026: the offsets as floats, the velocity as floats inside a step loop; the same float
        // operations in the same order as the Vec3 ones, each new value checked finite as a Vec3 checks it.
        private float ox,oy,oz,px,py,pz;
        private double updated,previousTime;
        public final String texture;
        /** Main thread (ImpactFx.isometric): its image once the game returned one (a Texture; null: ask again). */
        Object isoTexture;
        Particle(Event e,ImpactData.Particle s,double time,Random random) {
            event=e;source=s;born=time;
            life=s.life()+random.nextDouble()*s.lifeRange();size=s.size()+random.nextDouble()*s.sizeRange();
            Vec3 normal=e.normal();
            Vec3 direction=new Vec3(random.nextFloat()*2-1,random.nextFloat()*2-1,random.nextFloat()*2-1);
            if (dot(direction,direction)<1e-8) direction=normal;
            direction=direction.unit();if (dot(direction,normal)<0) direction=direction.times(-1);
            velocity=direction.times(s.speed()+random.nextFloat()*s.speedRange());
            initialVelocity=velocity;limit=s.limit()+random.nextDouble()*s.limitRange();updated=previousTime=time;
            texture=s.textures()[random.nextInt(s.textures().length)];
        }
        public void advance(double time) {
            px=ox;py=oy;pz=oz;previousTime=updated;
            double remaining=Math.max(0,Math.min(time,born+life)-updated);
            if (remaining>1e-9) {
                float vx=velocity.x(),vy=velocity.y(),vz=velocity.z();
                while (remaining>1e-9) {
                    float step=(float)Math.min(1d/60,remaining);
                    // velocity.plus(new Vec3(0,-9.81f*source.gravity()*step,0))
                    float gy=-9.81f*source.gravity()*step;
                    vec(0f,gy,0f);
                    vx=vx+0f;vy=vy+gy;vz=vz+0f;vec(vx,vy,vz);
                    float speed=(float)Math.sqrt((vx*vx+vy*vy)+vz*vz);
                    if (speed>limit && source.dampen()>0) {
                        double retained=Math.pow(Math.max(0,1-source.dampen()),step*60);
                        float f=(float)((limit+(speed-limit)*retained)/speed);
                        vx=vx*f;vy=vy*f;vz=vz*f;vec(vx,vy,vz);
                    }
                    // offset.plus(velocity.times(step))
                    float sx=vx*step,sy=vy*step,sz=vz*step;vec(sx,sy,sz);
                    ox=ox+sx;oy=oy+sy;oz=oz+sz;vec(ox,oy,oz);remaining-=step;
                }
                velocity=new Vec3(vx,vy,vz);
            }
            updated=time;
        }
        /** The checks of the Vec3 constructor, in its order (TarkovBallistics.finite). */
        private static void vec(float x,float y,float z) {
            TarkovBallistics.finite(x,"vector x");TarkovBallistics.finite(y,"vector y");TarkovBallistics.finite(z,"vector z");
        }
        public Pz position(double time) {
            double[] at=new double[3];positionInto(time,at);return new Pz(at[0],at[1],at[2]);
        }
        /** position(time) into out (x, y, z levels) without a Pz: previous + (offset - previous) x t, as Pz.offset places it. */
        void positionInto(double time,double[] out) {
            float t=updated>previousTime?(float)Math.max(0,Math.min(1,(time-previousTime)/(updated-previousTime))):1;
            float dx=ox-px,dy=oy-py,dz=oz-pz;vec(dx,dy,dz);
            dx=dx*t;dy=dy*t;dz=dz*t;vec(dx,dy,dz);
            float mx=px+dx,my=py+dy,mz=pz+dz;vec(mx,my,mz);
            Pz p=event.point();
            double x=p.x()+mx/TarkovBallistics.METRES_PER_CELL,y=p.y()+mz/TarkovBallistics.METRES_PER_CELL,z=p.z()+my/TarkovBallistics.METRES_PER_LEVEL;
            ShotPoseSnapshot.finite(x);ShotPoseSnapshot.finite(y);ShotPoseSnapshot.finite(z);
            out[0]=x;out[1]=y;out[2]=z;
        }
        public float alpha(double time) { return Math.max(0,sample(source.alpha(),(time-born)/life)*source.a()); }
        public float diameter(double time) { return (float)size*Math.max(0,sample(source.scale(),(time-born)/life)); }
    }
    public static final class Store {
        public final ArrayDeque<Mark> marks=new ArrayDeque<>();
        public final ArrayDeque<Particle> particles=new ArrayDeque<>();
        private volatile boolean particlesOn=true,decalsOn=true;
        private final LinkedHashMap<String,Double> seen=new LinkedHashMap<>();
        private final ArrayDeque<Event> outbound=new ArrayDeque<>(), sounds=new ArrayDeque<>();
        public double time;
        public long accepted,duplicates,evicted,expired,unloaded,unknown,particleEvicted,queueDropped,dedupEvicted;
        public synchronized boolean accept(Event e,boolean publish) {
            if (seen.containsKey(e.id())) { duplicates++;return false; }
            // Explicit overload policy: the memory cap keeps the newest dedup ids; an id older than the whole
            // table is evicted like the oldest mark.  Dropping the *new* visual here (before this wave) made
            // sustained fire show no holes at all once 4096 contacts were alive at once.
            if (seen.size()>=MAX_SEEN) {
                var oldest=seen.keySet().iterator();
                if (oldest.hasNext()) { oldest.next();oldest.remove();dedupEvicted++; }
            }
            seen.put(e.id(),time+LIFE);accepted++;
            var profile=ImpactData.profile(e.preset());
            // These viewer settings never change the event, its sound or its relay to other players.
            // Avoid even the RNG allocation when neither visual category can use it.
            Random random=particlesOn || decalsOn?new Random(e.seed()):null;
            if (e.decal() && profile.decals().length>0) {
                if (decalsOn) {
                    if (marks.size()>=ImpactData.MAX_MARKS) { marks.removeFirst();evicted++; }
                    marks.addLast(new Mark(e,time,random));
                } else if (particlesOn) {
                    // Keep the remaining particles' seeded values identical to the default view without
                    // constructing the disabled mark's vectors, corners, texture reference or face test.
                    random.nextInt(profile.decals().length);random.nextDouble();
                    if (!e.outcome().equals("RICOCHET")) random.nextDouble();
                }
            }
            int budget=MAX_PER_CONTACT;
            if (particlesOn) for (var source:profile.particles()) {
                int count=Math.min(budget,source.count()+(source.range()>0?random.nextInt(source.range()+1):0));
                for (int i=0;i<count;i++) {
                    if (particles.size()>=MAX_PARTICLES) { particles.removeFirst();particleEvicted++; }
                    particles.addLast(new Particle(e,source,time,random));
                }
                budget-=count;
            }
            if (e.sound()) offer(sounds,e);
            if (publish) offer(outbound,e);
            return true;
        }
        /** Local visual switches. Pending audio/network events, dedup ids and the physical clock survive. */
        public synchronized void visualOptions(boolean particlesEnabled,boolean decalsEnabled) {
            particlesOn=particlesEnabled;decalsOn=decalsEnabled;
            if (!particlesOn) particles.clear();
            if (!decalsOn) marks.clear();
        }
        public boolean particlesEnabled() { return particlesOn; }
        public boolean decalsEnabled() { return decalsOn; }
        public boolean visualsEnabled() { return particlesOn || decalsOn; }
        private void offer(ArrayDeque<Event> queue,Event e) {
            if (queue.size()>=MAX_PENDING) { queue.removeFirst();queueDropped++; }
            queue.addLast(e);
        }
        public synchronized Event poll() { return outbound.pollFirst(); }
        public synchronized Event sound() { return sounds.pollFirst(); }
        public synchronized List<Mark> marks() { return List.copyOf(marks); }
        public synchronized List<Particle> particles() { return List.copyOf(particles); }
        /**
         * Performance pass 23.10.2026: the same snapshot as marks()/particles() (taken under the lock) into a reused
         * array, null after the last; a larger array when it does not fit.
         */
        public synchronized Mark[] marks(Mark[] into) { return marks.toArray(into); }
        public synchronized Particle[] particles(Particle[] into) { return particles.toArray(into); }
        public synchronized int seen() { return seen.size(); }
        public synchronized void advance(double delta,Predicate<Event> loaded) {
            if (!Double.isFinite(delta) || delta<0) throw new IllegalArgumentException("Impact clock");
            time+=delta;
            // Performance pass 23.10.2026: one test per event within this call (the particles of a contact lie
            // together; the world does not change during it).
            testing=loaded;tested=null;
            try {
                marks.removeIf(m->{ if (time-m.born>=LIFE) { expired++;return true; }
                                     if (!present(m.event)) { unloaded++;return true; }return false; });
                particles.removeIf(p->time-p.born>=p.life || !present(p.event));
                for (var p:particles) p.advance(time);
                // Entries go in as time+LIFE with a clock that never goes back (clear() empties them with it): their
                // expiries do not decrease in insertion order, so the expired ones are exactly a prefix.
                for (var it=seen.values().iterator();it.hasNext();) {
                    if (time>=it.next()) it.remove(); else break;
                }
                outbound.removeIf(e->!present(e));sounds.removeIf(e->!present(e));
            } finally { testing=null;tested=null; }
        }
        private Predicate<Event> testing;
        private Event tested;
        private boolean present;
        private boolean present(Event e) {
            if (e!=tested) { tested=e;present=testing.test(e); }
            return present;
        }
        public synchronized void clear() { marks.clear();particles.clear();seen.clear();outbound.clear();sounds.clear();time=0; }
        public synchronized String stats() {
            return "marks="+marks.size()+" particles="+particles.size()+" seen="+seen.size()+" accepted="+accepted
                    +" duplicates="+duplicates+" evicted="+evicted+" expired="+expired+" unloaded="+unloaded
                    +" unknown="+unknown+" particleEvicted="+particleEvicted+" queueDropped="+queueDropped
                    +" dedupEvicted="+dedupEvicted+" particlesOn="+particlesOn+" decalsOn="+decalsOn;
        }
    }
    public static float sample(float[] values,double t) {
        double position=Math.max(0,Math.min(values.length-1,t*(values.length-1)));
        int i=(int)position;double f=position-i;
        return (float)(values[i]*(1-f)+values[Math.min(values.length-1,i+1)]*f);
    }
    public static double dot(Vec3 a,Vec3 b) { return a.x()*b.x()+a.y()*b.y()+a.z()*b.z(); }
    public static Vec3 cross(Vec3 a,Vec3 b) { return new Vec3(a.y()*b.z()-a.z()*b.y(),a.z()*b.x()-a.x()*b.z(),a.x()*b.y()-a.y()*b.x()); }
    public static void unit(Vec3 v) { if (v==null || Math.abs(dot(v,v)-1)>0.002) throw new IllegalArgumentException("Nonunit impact direction"); }
}
