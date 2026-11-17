package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import pz.escapefromzomboidfirearms.zb.ShotSnapshot.*;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Flight;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;
import pz.escapefromzomboidfirearms.zb.TarkovPenetration.Projectile;
import pz.escapefromzomboidfirearms.zb.TarkovPenetration.RandomTable;

/** LOCAL integration only. B5 owns all contact formulas; B4 owns all flight.
 * Root pellet identities survive fragmentation; contactOrdinal distinguishes
 * every branch/contact. Children never initiate another cartridge or native FX.
 */
public final class ShotPenetration {
    public static final int RANDOM_VERSION=1, CACHE_SIZE=512, MAX_CONTACTS=4096, MAX_BRANCHES=4096;
    /** Metres a warped flight may leave its straight piece (pieces), and the halvings of one step (13 m / 2^8 = 5 cm). */
    public static final float WARP_TOLERANCE=.005f;
    public static final int WARP_DEPTH=8;
    private static final long GOLDEN=0x9e3779b97f4a7c15L;
    private static final Map<String,String> BODY=Map.ofEntries(
            Map.entry("Head","HeadCommon"),Map.entry("Neck","NeckFront"),
            Map.entry("Torso_Upper","RibcageUp"),Map.entry("Torso_Lower","Pelvis"),
            Map.entry("UpperArm_L","LeftUpperArm"),Map.entry("ForeArm_L","LeftForearm"),Map.entry("Hand_L","LeftForearm"),
            Map.entry("UpperArm_R","RightUpperArm"),Map.entry("ForeArm_R","RightForearm"),Map.entry("Hand_R","RightForearm"),
            Map.entry("UpperLeg_L","LeftThigh"),Map.entry("LowerLeg_L","LeftCalf"),Map.entry("Foot_L","LeftCalf"),
            Map.entry("UpperLeg_R","RightThigh"),Map.entry("LowerLeg_R","RightCalf"),Map.entry("Foot_R","RightCalf"));
    private static final Map<String,String> DAMAGE_PART=Map.ofEntries(
            Map.entry("Head","Head"),Map.entry("Neck","Chest"),
            Map.entry("Torso_Upper","Chest"),Map.entry("Torso_Lower","Stomach"),
            Map.entry("UpperArm_L","LeftArm"),Map.entry("ForeArm_L","LeftArm"),Map.entry("Hand_L","LeftArm"),
            Map.entry("UpperArm_R","RightArm"),Map.entry("ForeArm_R","RightArm"),Map.entry("Hand_R","RightArm"),
            Map.entry("UpperLeg_L","LeftLeg"),Map.entry("LowerLeg_L","LeftLeg"),Map.entry("Foot_L","LeftLeg"),
            Map.entry("UpperLeg_R","RightLeg"),Map.entry("LowerLeg_R","RightLeg"),Map.entry("Foot_R","RightLeg"));
    private ShotPenetration() {}

    private static float uniform(long value) {
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        return Math.min(Math.nextDown(1f),(float)(((value^(value>>>31))>>>11)*0x1.0p-53));
    }
    /** Independent, versioned project replay. Six bounded bins follow B5's
     * saved CDF; this is explicitly not Unity Random.InitState equivalence. */
    public static RandomTable randoms(TarkovPenetrationData data,long seed) {
        float[] uniform=new float[CACHE_SIZE], penetration=new float[CACHE_SIZE];
        Vec3[] directions=new Vec3[CACHE_SIZE];long state=seed;
        for (int i=0;i<CACHE_SIZE;i++) {
            uniform[i]=uniform(state+=GOLDEN);
            float choice=uniform(state+=GOLDEN);int bin=0;
            while (bin<5 && choice>=data.penetrationBinLimit(bin)) bin++;
            penetration[i]=-1f+(bin+uniform(state+=GOLDEN))/3f;
            float z=2*uniform(state+=GOLDEN)-1;
            double angle=2*Math.PI*uniform(state+=GOLDEN), radius=Math.sqrt(Math.max(0,1-z*z));
            directions[i]=new Vec3((float)(radius*Math.cos(angle)),z,(float)(radius*Math.sin(angle))).unit();
        }
        return new RandomTable(uniform,penetration,directions);
    }
    public static String bodyZone(Contact contact) {
        if (contact.kind()!=TargetKind.CHARACTER) return null;
        int slash=contact.proof().lastIndexOf('/');
        return slash<0?null:BODY.get(contact.proof().substring(slash+1));
    }
    /**
     * W11 corpses: a dead body is a contact of the round, never a target to wound. ShotContactsPz marks its colliders
     * with a corpse/ proof and a native body part; the chain resolves the same Tarkov flesh penetration a living part
     * would (stop, penetrate or fragment by the round's power), and ShotDamagePz turns the contact into blood only.
     */
    public static boolean corpse(Contact contact) {
        return contact.kind()==TargetKind.CHARACTER && contact.proof().startsWith("corpse/");
    }
    private static String damageVolume(Contact contact) {
        int slash=contact.proof().lastIndexOf('/');
        String name=slash<0?"":contact.proof().substring(slash+1),part=DAMAGE_PART.get(name);
        // Client UF16A AvoidAdditionalDamage and UF167: repeated EBodyPart
        // along the same player's ancestry has zero damage, not zero physics.
        // Tarkov zombie prefabs assign NeckFront to Head; human ones to Chest.
        if (name.equals("Neck") && contact.proof().contains("/source-body=zombie/")) part="Head";
        return part==null?ShotContacts.volume(contact):contact.key().target()+"/tarkov-body/"+part;
    }
    public record Diagnostic(Contact contact,int branch,int parent,float rootTime,String preset,String outcome,
            float speedBefore,float speedAfter,float damageBefore,float damageImpact,float damageAfter,float penetrationBefore,
            float penetrationImpact,float penetrationAfter,int ricochets,int children,Vec3 incomingDirection) {
        public String fields() {
            return "contactOrdinal="+contact.key().contactOrdinal()+" branch="+branch+" parent="+parent
                    +" material="+contact.material()+" preset="+preset+" outcome="+outcome+" forward="+contact.forwardHit()
                    +" rootTime="+rootTime+" speedBefore="+speedBefore+" speedAfter="+speedAfter
                    +" DamageBefore="+damageBefore+" DamageImpact="+damageImpact+" DamageAfter="+damageAfter
                    +" PBefore="+penetrationBefore+" PImpact="+penetrationImpact+" PAfter="+penetrationAfter
                    +" ricochets="+ricochets+" children="+children;
        }
    }
    public record Step(List<Contact> impacts,List<Diagnostic> diagnostics,int segments,boolean done) {}
    /**
     * The drawn world's height at a flight point (user's report of 27.10.2026, "bullets fly above the sight"): Viewpoint
     * lifts the ground and every model onto its far relief (FarRelief$Lift: about 0 at the camera, growing with distance),
     * while the flight and the contacts are PZ's flat world. The flight lives in the drawn world - it leaves the drawn
     * weapon and is aimed at drawn targets - so a point is traced at its flat height: the drawn height minus the lift
     * there, relative to the lift where its branch began (the muzzle, or a contact a child left from). NONE: flat.
     */
    @FunctionalInterface
    public interface Warp {
        Warp NONE=(point,origin)->point;
        Vec3 flat(Vec3 point,Vec3 origin);
    }
    /** A straight piece of a warped flight: its times and its warped ends. */
    public record Piece(float t0,float t1,Vec3 from,Vec3 to) {}
    /**
     * A flight's step as straight pieces whose quarters and middle lie on the warped flight within tolerance (drawn:
     * the drawn point at a time): one step spans 8-13 m, and a chord between its warped ends cuts under a steep rise of
     * the relief (QA 27.10.2026, Rosewood: lift 0.19 m at 15 m, 0.77 m at 20 m left holes 7-43 cm above the post). The
     * quarters catch a rise and fall that leaves the middle on the chord. Flat flights stay one piece. The chain's trace
     * and the laser's probe (ShotOwner.laserProbe) cut the same way.
     */
    public static void pieces(Warp warp,java.util.function.Function<Float,Vec3> drawn,Vec3 origin,float tolerance,
            float t0,Vec3 a,float t1,Vec3 z,int depth,List<Piece> out) {
        if (warp!=Warp.NONE && depth<WARP_DEPTH) {
            float tm=(t0+t1)/2;
            Vec3 m=warp.flat(drawn.apply(tm),origin);
            if (off(warp,drawn,origin,tolerance,t0,a,t1,z,.5f,m) || off(warp,drawn,origin,tolerance,t0,a,t1,z,.25f,null)
                    || off(warp,drawn,origin,tolerance,t0,a,t1,z,.75f,null)) {
                pieces(warp,drawn,origin,tolerance,t0,a,tm,m,depth+1,out);
                pieces(warp,drawn,origin,tolerance,tm,m,t1,z,depth+1,out);return;
            }
        }
        out.add(new Piece(t0,t1,a,z));
    }
    /** Whether the warped flight at fraction f of a piece (point, when already sampled) leaves its chord. */
    private static boolean off(Warp warp,java.util.function.Function<Float,Vec3> drawn,Vec3 origin,float tolerance,
            float t0,Vec3 a,float t1,Vec3 z,float f,Vec3 point) {
        Vec3 at=point!=null ? point : warp.flat(drawn.apply(t0+(t1-t0)*f),origin);
        return at.minus(a.plus(z.minus(a).times(f))).length()>tolerance;
    }
    private static final class Branch {
        final int pellet,id,parent;
        final Projectile projectile;
        final Flight flight;
        final float born;
        final Set<String> occupied,damaged;
        float time;
        Branch(int pellet,int id,int parent,Projectile p,float born,TarkovBallisticsData data,
                Set<String> occupied,Set<String> damaged) {
            this.pellet=pellet;this.id=id;this.parent=parent;this.projectile=p;this.born=born;time=born;
            this.occupied=new HashSet<>(occupied);this.damaged=new HashSet<>(damaged);
            flight=data.flight(p.ammo().tpl(),p.speed()/p.ammo().initialSpeed(),p.origin(),p.velocity().unit());
        }
    }
    public static final class Chain {
        public final String cacheId;
        private final ShotSnapshot shot;
        private final TarkovBallisticsData flightData;
        private final TarkovPenetrationData data;
        private final Contacts contacts;
        private final ArrayDeque<Branch> active=new ArrayDeque<>();
        private final int[] ordinals;
        private int nextBranch,contactCount,segmentOrdinal;
        private float time;
        /** The drawn world's relief under this cartridge's flight (Warp); set before the first advance. */
        public Warp warp=Warp.NONE;
        public Chain(ShotSnapshot shot,TarkovBallisticsData flightData,TarkovPenetrationData data,Contacts contacts) {
            this(shot,flightData,data,contacts,randoms(data,shot.seed()));
        }
        /** Explicit cache overload is for deterministic offline boundary fixtures. */
        public Chain(ShotSnapshot shot,TarkovBallisticsData flightData,TarkovPenetrationData data,Contacts contacts,RandomTable randoms) {
            this.shot=shot;this.flightData=flightData;this.data=data;this.contacts=contacts;
            if (!flightData.gunplaySha256.equals(data.gunplaySha256) || !flightData.inputsSha256.equals(data.inputsSha256))
                throw new IllegalArgumentException("B4/B5 provenance mismatch");
            var ammo=data.ammo(shot.actualAmmoTpl());
            if (ammo.initialSpeed()!=shot.ammo().initialSpeed() || ammo.lifeSeconds()!=shot.ammo().lifeSeconds()
                    || ammo.massGrams()!=shot.ammo().massGrams()) throw new IllegalArgumentException("B4/B5 ammo mismatch");
            this.cacheId="splitmix64-v"+RANDOM_VERSION+":"+Long.toUnsignedString(shot.seed())+":"+randoms.size();
            ordinals=new int[shot.ammo().projectileCount()];
            for (int i=0;i<shot.pelletDirections().size();i++) {
                int seed=(int)((shot.seed()+i*GOLDEN)&0x7fffffffL);
                Projectile p=TarkovPenetration.launch(ammo,shot.speedFactor(),Vec3.ZERO,shot.pelletDirections().get(i),seed,randoms);
                active.add(new Branch(i,nextBranch++,-1,p,0,flightData,Set.of(),Set.of()));
            }
        }
        public boolean done() { return active.isEmpty() || time>=shot.ammo().lifeSeconds(); }
        public float time() { return time; }
        public Step advance(float end) throws Exception {
            if (!Float.isFinite(end) || end<time || end>shot.ammo().lifeSeconds()
                    || end-time>TarkovBallistics.STEP_SECONDS+1e-5f) throw new IllegalArgumentException("B4 chain step");
            ArrayList<Contact> impacts=new ArrayList<>();ArrayList<Diagnostic> diagnostics=new ArrayList<>();
            ArrayDeque<Branch> work=new ArrayDeque<>(active);active.clear();int segments=0;
            while (!work.isEmpty()) {
                Branch b=work.removeFirst();
                if (b.time>=end) { active.add(b);continue; }
                Vec3 origin=b.projectile.origin();
                Vec3 from=warp.flat(b.flight.sample(Math.max(0,b.time-b.born)).position(),origin);
                Vec3 to=warp.flat(b.flight.sample(end-b.born).position(),origin);
                ArrayList<Piece> pieces=new ArrayList<>();
                pieces(warp,time->b.flight.sample(Math.max(0,time-b.born)).position(),origin,WARP_TOLERANCE,b.time,from,end,to,0,pieces);
                Segment segment=null;List<Contact> found=List.of();Piece piece=null;
                for (Piece p:pieces) {
                    segment=new Segment(shot.pellet(b.pellet),segmentOrdinal++,p.t0(),p.t1(),p.from(),p.to());segments++;
                    found=contacts.sweep(shot,segment,b.occupied);
                    if (!found.isEmpty()) { piece=p;break; }
                }
                if (found.isEmpty()) { b.time=end;active.add(b);continue; }
                from=piece.from();to=piece.to();
                Contact raw=found.getFirst();
                if (!raw.key().pellet().equals(segment.pellet())) throw new IllegalStateException("Foreign chain contact");
                float hitTime=piece.t0()+(piece.t1()-piece.t0())*raw.fraction();
                Vec3 velocity=b.flight.sample(Math.max(0,hitTime-b.born)).velocity();
                Projectile incoming=b.projectile.at(raw.point(),velocity);
                var prepared=TarkovPenetration.prepareImpact(data,incoming,raw.point(),raw.forwardHit());
                var impact=prepared.projectile();
                String zone=bodyZone(raw), preset=raw.kind()==TargetKind.CHARACTER?zone:ShotWorldMaterials.preset(raw.material());
                boolean known=preset!=null && raw.kind()!=TargetKind.UNLOADED;
                String outcome=known?"STOP":"UNKNOWN_STOP";List<Projectile> children=List.of();
                if (++contactCount>MAX_CONTACTS) { active.clear();work.clear();outcome="CONTACT_BUDGET_STOP"; }
                else if (known) {
                    var material=raw.kind()==TargetKind.CHARACTER?data.body(zone):data.material(preset);
                    var result=TarkovPenetration.resolveImpact(prepared,new TarkovPenetration.Contact(material,raw.point(),
                            raw.normal(),to.minus(from).unit(),raw.forwardHit(),1,false,false,false));
                    impact=result.impact();children=result.children();outcome=result.outcome().name();
                }
                int ordinal=ordinals[b.pellet]++;
                // Each root pellet/contact gets a disjoint pair of health
                // samples; contact N's light roll is not N+1's heavy roll.
                long healthIndex=((long)b.pellet<<33)+2L*ordinal;
                long healthSeed=shot.seed()+GOLDEN*healthIndex+0x3c6ef372fe94f82aL;
                float scale=impact.ammo().damage()>0?impact.damage()/impact.ammo().damage():0;
                Effect effect=new Effect(scale,impact.damage(),impact.penetration(),uniform(healthSeed),uniform(healthSeed+GOLDEN));
                Contact hit=new Contact(new ImpactKey(shot.pellet(b.pellet),ordinal,raw.key().target()),raw.kind(),raw.fraction(),
                        raw.point(),raw.normal(),raw.ragdollPart(),raw.bodyPart(),raw.material(),raw.proof(),effect,raw.forwardHit());
                String volume=ShotContacts.volume(hit);
                if (hit.forwardHit()) b.occupied.add(volume); else b.occupied.remove(volume);
                // Exits never damage. Children inherit source EBodyPart claims;
                // separate PZ capsules of one source region cannot wound twice.
                if (contactCount<=MAX_CONTACTS && hit.forwardHit()
                        && (hit.kind()!=TargetKind.CHARACTER || b.damaged.add(damageVolume(hit)))) impacts.add(hit);
                if (nextBranch+children.size()>MAX_BRANCHES) { children=List.of();outcome="BRANCH_BUDGET_STOP"; }
                Projectile first=children.isEmpty()?null:children.getFirst();
                diagnostics.add(new Diagnostic(hit,b.id,b.parent,hitTime,preset==null?"unknown":preset,outcome,
                        incoming.speed(),first==null?0:first.speed(),incoming.damage(),impact.damage(),first==null?0:first.damage(),
                        incoming.penetration(),impact.penetration(),first==null?0:first.penetration(),impact.ricochets(),children.size(),velocity.unit()));
                for (Projectile child:children) if (child.speed()>1e-5f && hitTime<shot.ammo().lifeSeconds()) {
                    work.addLast(new Branch(b.pellet,nextBranch++,b.id,child,hitTime,flightData,b.occupied,b.damaged));
                }
            }
            time=end;
            if (time>=shot.ammo().lifeSeconds()) active.clear();
            return new Step(List.copyOf(impacts),List.copyOf(diagnostics),segments,done());
        }
    }
}
