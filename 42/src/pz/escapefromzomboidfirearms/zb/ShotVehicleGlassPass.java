package pz.escapefromzomboidfirearms.zb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import me.zed_0xff.zombie_buddy.Patch;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;
import zombie.AttackType;
import zombie.ai.states.SwipeStatePlayer;
import zombie.characters.IsoGameCharacter;
import zombie.core.physics.Transform;
import zombie.core.physics.WorldSimulation;
import zombie.gameStates.ChooseGameInfo;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoWorld;
import zombie.network.GameServer;
import zombie.network.fields.hit.HitInfo;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

/**
 * W10 glasspass (item 1, the player's decision of 27.10.2026: "let it fly on"). PZ's own ranged shot - isometric with
 * the mod's ballistics off, and every shot on a Host server - flies on through vehicle glass. Client JAR; it acts where
 * PZ builds the hit list (SP, an MP client), never on a server and never for a shot the mod's owner path claimed.
 *
 * PZ ends a ranged attack at the first vehicle in its hit list (CombatManager.attackCollisionCheck: processHit, then
 * break), and a vehicle nearer than the target also takes the weapon's only hit slot (calculateHitInfoList, MaxHitCount).
 * Here the shot's own line (the muzzle and its direction, as CombatManager.fireWeapon draws the tracer) is traced through
 * every vehicle it meets against the owner path's own vehicle geometry (ShotContactsPz.pieces: the measured panes of
 * intact windows, the greenhouse cabin, the split box shells, door, engine and tyre proxies; a broken, removed or open
 * pane is an opening):
 *   - its first contact is glass: the vehicle leaves the hit list, so it neither stops the round nor takes the hit slot;
 *     the round crosses every glass contact in a row and the intact panes among them break when it is fired
 *     (ShotVehicleGlass.breakPane: native VehicleWindow.damage, which an MP client sends to the server as vanilla's
 *     "vehicle damageWindow" command);
 *   - its first non-glass contact after that (a door panel, the cabin, a shell) stops the round there: targets past it
 *     leave the list (a target inside the vehicle, before it, stays); with none, the round leaves through glass or an
 *     opening and the targets behind stay;
 *   - its first contact is the body: nothing changes (PZ's processHit, the pane rule of ShotVehicleGlassNative).
 * Damage behind glass (the coordinator's rule B): each intact pane crossed multiplies PZ's damage once by the owner
 * path's own loss at that contact - TarkovPenetration.degradation of the round's Tarkov flight speed at the pane's
 * distance (TarkovPenetration.prepareImpact re-applies it at every contact; Tarkov's Glass preset itself takes nothing).
 * The round is the weapon's ammo through its Tarkov profile (ShotOwner.actualAmmo); a round without one keeps 1.0.
 */
public final class ShotVehicleGlassPass {
    private ShotVehicleGlassPass() {}

    private static volatile boolean qaLegacy, qaTrace;
    /** QA (A/B in one run): true restores the wave's rule, the first vehicle in the hit list stops the round. */
    public static void qaLegacy(boolean on) { qaLegacy=on; }
    /** QA: console lines of an attack's hit list (PZ's targets, Bullet's arrays, this rule's decisions, damage). */
    public static void qaTrace(boolean on) { qaTrace=on; }

    /** A glass contact of the line: where (fraction of the line), its window part (null: fixed glass no part owns), and
     * whether it is only an opening (a broken, removed or open pane: nothing to break, nothing lost). */
    record Pane(double fraction,VehiclePart part,boolean opening) { }
    /** Where the shot's line meets one vehicle: its first contact (glass or not, where), where the round stops in it
     * (infinite: it leaves through glass or an opening) and the glass it crosses before that. */
    record Crossing(BaseVehicle vehicle,boolean glassIn,double enter,double stop,List<Pane> panes,boolean listed) {
        boolean leaves() { return stop==Double.POSITIVE_INFINITY; }
    }
    /** One attack (attackCollisionCheck) of this thread: its crossings and the damage factor of each target. */
    static final class Attack {
        final IsoGameCharacter owner;
        final List<Crossing> crossings=new ArrayList<>();
        final Map<Object,Float> factors=new IdentityHashMap<>();
        double nearest=Double.POSITIVE_INFINITY,lineMetres;
        double[] a,b;
        boolean traced;
        Attack(IsoGameCharacter owner) { this.owner=owner; }
    }
    private static final ThreadLocal<Attack> ATTACK=new ThreadLocal<>();
    private static long attacks,passed,stopped,broken,errors;
    private static String lastError="";

    public static void attackEnter(IsoGameCharacter owner,HandWeapon weapon) {
        if (GameServer.server || owner==null || weapon==null || !weapon.isAimedFirearm()) return;
        ATTACK.set(new Attack(owner));
    }
    /** The panes the fired round crossed break, up to the nearest target it was sent to. */
    public static void attackExit(IsoGameCharacter owner) {
        Attack attack=ATTACK.get();
        if (attack==null || attack.owner!=owner) return;
        ATTACK.remove();
        if (attack.crossings.isEmpty()) return;
        try {
            attacks++;
            for (Crossing c:attack.crossings) for (Pane pane:c.panes()) if (!pane.opening()) breakAt(attack,pane.part(),pane.fraction());
        } catch (RuntimeException | LinkageError e) { error(e); }
    }
    private static void breakAt(Attack attack,VehiclePart pane,double t) {
        if (pane==null || t>attack.nearest || !ShotVehicleGlass.intact(pane)) return;
        boolean done=ShotVehicleGlass.breakPane(pane);
        if (done) broken++;
        if (qaTrace) log("break "+pane.getVehicle().getScript().getFullName()+" "+pane.getId()+" at="+fmt(t*attack.lineMetres)+"m broken="+done);
    }

    /** OnEnter of CombatManager.processBallisticsTargets: the list as calculateHitListWeapon made it (QA trace). */
    public static void targetsEnter(IsoGameCharacter owner,List<HitInfo> list) {
        if (!qaTrace || ATTACK.get()==null) return;
        log("targets-in "+describe(list));
    }

    /** OnExit of CombatManager.processBallisticsTargets: the list after Bullet's validation, before the hit slots. */
    public static void targets(IsoGameCharacter owner,HandWeapon weapon,List<HitInfo> list) {
        if (GameServer.server || owner==null || weapon==null || list==null || !weapon.isAimedFirearm()) return;
        Attack attack=ATTACK.get();
        if (attack!=null && attack.owner!=owner) attack=null;
        try {
            if (attack!=null && qaTrace) log("targets-out "+describe(list)+" bullet="+bullet(owner));
            // Only an attack: the reticle's hit list of every aimed frame keeps PZ's own (no tracing per frame).
            if (attack==null || qaLegacy || ShotOwner.suppress(owner,null)) return;
            apply(owner,weapon,list,attack);
            if (attack!=null && qaTrace) log("targets-rule "+describe(list));
        } catch (RuntimeException | LinkageError e) { error(e); }
    }

    /** OnExit of CombatManager.applyRangeHitLocationDamage: PZ's damage of a target behind glass. */
    public static float damage(IsoGameCharacter target,float damage) {
        Attack attack=ATTACK.get();
        if (attack==null || target==null) return damage;
        Float factor=attack.factors.get(target);
        if (factor==null) return damage;
        if (qaTrace) log("damage target="+target.getClass().getSimpleName()+"#"+target.getID()+" pz="+fmt(damage)+" factor="+factor+" after="+fmt(damage*factor));
        return damage*factor;
    }

    /** The shot's own line: the muzzle and its direction (CombatManager.fireWeapon's tracer), 12 cells past the farthest
     * target or vehicle PZ listed for this shot (more than any vehicle's length; PZ's MaxRange is never read here). */
    static double[][] line(IsoGameCharacter owner,List<HitInfo> list) {
        var controller=owner.getBallisticsController();
        if (controller==null) return null;
        var start=new zombie.iso.Vector3();var dir=new zombie.iso.Vector3();
        controller.calculateMuzzlePosition(start,dir);
        double length=Math.sqrt(dir.x*dir.x+dir.y*dir.y+dir.z*dir.z);
        if (!(Double.isFinite(length) && length>1e-6) || !Float.isFinite(start.x) || !Float.isFinite(start.y) || !Float.isFinite(start.z)) return null;
        double reach=12;
        for (int i=0;i<list.size();i++) {
            HitInfo hit=list.get(i);
            if (hit!=null) reach=Math.max(reach,12+Math.hypot(hit.x-start.x,hit.y-start.y));
        }
        return new double[][]{{start.x,start.y,start.z},{start.x+dir.x/length*reach,start.y+dir.y/length*reach,start.z+dir.z/length*reach}};
    }
    static void apply(IsoGameCharacter owner,HandWeapon weapon,List<HitInfo> list,Attack attack) {
        var sim=WorldSimulation.instance;var cell=IsoWorld.instance==null?null:IsoWorld.instance.currentCell;
        if (attack!=null) attack.traced=true;
        if (sim==null || cell==null || cell.getVehicles()==null || cell.getVehicles().isEmpty()) return;
        double[][] line=line(owner,list);
        if (line==null) return;
        double[] a=line[0],b=line[1];
        List<Crossing> crossings=new ArrayList<>();
        for (BaseVehicle v:cell.getVehicles()) {
            if (v==null || v.getScript()==null || !near(v,a,b)) continue;
            Crossing c=cross(v,a,b,listed(list,v));
            if (c!=null) crossings.add(c);
        }
        if (crossings.isEmpty()) return;
        crossings.sort((x,y)->Double.compare(x.enter(),y.enter()));
        double metres=Math.sqrt(sq(b[0]-a[0])+sq(b[1]-a[1])+sq((b[2]-a[2])*LEVEL))*TarkovBallistics.METRES_PER_CELL;
        // A vehicle the line enters through glass leaves the list; one entered through its body stays PZ's (processHit,
        // the end of the attack), and so does everything past it. Glass in, then a body contact: the round stops there.
        double stop=Double.POSITIVE_INFINITY;
        List<Crossing> glass=new ArrayList<>();
        for (Crossing c:crossings) {
            if (!c.glassIn()) { if (attack!=null && qaTrace) log("vehicle "+name(c)+" body in at="+fmt(c.enter()*metres)+"m listed="+c.listed());break; }
            glass.add(c);
            if (attack!=null && qaTrace) {
                StringBuilder panes=new StringBuilder();
                for (Pane pane:c.panes()) panes.append(panes.isEmpty()?"":" ").append(part(pane.part())).append(pane.opening()?"(open)":"").append('@').append(fmt(pane.fraction()*metres)).append('m');
                log("vehicle "+name(c)+" glass in at="+fmt(c.enter()*metres)+"m panes=["+panes+"] "+(c.leaves()?"leaves":"stops at="+fmt(c.stop()*metres)+"m")+" listed="+c.listed());
            }
            if (!c.leaves()) { stop=c.stop();break; }
        }
        if (attack!=null) { attack.crossings.clear();attack.crossings.addAll(glass);attack.lineMetres=metres;attack.a=a;attack.b=b; }
        if (glass.isEmpty()) return;
        String ammo=ammo(weapon);
        Map<IsoGameCharacter,int[]> pellets=attack!=null && weapon.isRangeFalloff()?pellets(owner,list,glass,a,b):Map.of();
        for (int i=list.size()-1;i>=0;i--) {
            HitInfo hit=list.get(i);
            Object object=hit==null?null:hit.getObject();
            if (object instanceof BaseVehicle v) { for (Crossing c:glass) if (c.vehicle()==v) { list.remove(i);break; } continue; }
            if (!(object instanceof IsoGameCharacter character)) continue;
            double t=along(a,b,hit.x,hit.y,hit.z);
            if (t>stop) { list.remove(i);if (attack!=null) stopped++;continue; } // past the body panel the round left through
            if (attack==null) continue;
            float factor=1;
            for (Crossing c:glass) for (Pane pane:c.panes()) if (!pane.opening() && t>pane.fraction()) factor*=glassFactor(ammo,pane.fraction()*metres);
            if (t>glass.getFirst().enter()) passed++;
            if (factor!=1) attack.factors.put(character,factor);
        }
        // Shotgun pellets PZ's Bullet stopped on the vehicle and that left it through glass: one more hit each on the
        // target behind they reach, as PZ counts its pellets (an aimed target always takes at least one).
        for (var e:pellets.entrySet()) {
            int direct=e.getValue()[0],through=e.getValue()[1],extra=Math.max(1,direct+through)-Math.max(1,direct);
            HitInfo first=null;
            for (int i=0;i<list.size() && first==null;i++) if (list.get(i)!=null && list.get(i).getObject()==e.getKey()) first=list.get(i);
            if (first==null) continue;
            for (int k=0;k<extra;k++) list.add(new HitInfo().init(first));
            if (qaTrace) log("pellets target="+e.getKey().getClass().getSimpleName()+"#"+e.getKey().getID()+" direct="+direct+" throughGlass="+through+" added="+extra);
        }
    }
    /**
     * Pellets of a falloff weapon (PZ's Bullet spread data: the first thing each pellet hit) whose first hit is a vehicle
     * the shot passes through glass: each pellet's own line from the muzzle through its hit point is traced through that
     * vehicle; one that enters and leaves through glass reaches the first listed character it passes within that
     * character's own width (IsoMovingObject.getWidth) and height. Per character: {PZ's own pellets, pellets through glass}.
     */
    static Map<IsoGameCharacter,int[]> pellets(IsoGameCharacter owner,List<HitInfo> list,List<Crossing> glass,double[] a,double[] b) {
        Map<IsoGameCharacter,int[]> out=new IdentityHashMap<>();
        var controller=owner.getBallisticsController();
        if (controller==null) return out;
        List<IsoGameCharacter> characters=new ArrayList<>();
        for (int i=0;i<list.size();i++) {
            HitInfo hit=list.get(i);
            if (hit!=null && hit.getObject() instanceof IsoGameCharacter c && !out.containsKey(c)) {
                characters.add(c);out.put(c,new int[]{controller.spreadCount(c.getID()),0});
            }
        }
        float[] data=controller.getBallisticsSpreadData();int n=controller.getNumberOfSpreadData();
        double reach=Math.sqrt(sq(b[0]-a[0])+sq(b[1]-a[1])+sq((b[2]-a[2])*LEVEL));
        for (int i=0;i<n;i++) {
            Crossing vehicle=null;
            for (Crossing c:glass) if (c.vehicle().getId()==(int)data[i*4]) vehicle=c;
            if (vehicle==null) continue;
            double ex=data[i*4+1],ey=data[i*4+3],ez=data[i*4+2]/2.44949f;
            double l=Math.sqrt(sq(ex-a[0])+sq(ey-a[1])+sq((ez-a[2])*LEVEL));
            if (!(l>1e-6)) continue;
            double[] far={a[0]+(ex-a[0])*reach/l,a[1]+(ey-a[1])*reach/l,a[2]+(ez-a[2])*reach/l};
            Crossing c=cross(vehicle.vehicle(),a,far,true);
            if (c==null || !c.glassIn() || !c.leaves()) continue;
            double exit=c.panes().getLast().fraction();
            IsoGameCharacter best=null;double bestT=Double.POSITIVE_INFINITY;
            for (IsoGameCharacter ch:characters) {
                double t=along(a,far,ch.getX(),ch.getY(),ch.getZ());
                if (t<=exit || t>=bestT) continue;
                double px=a[0]+(far[0]-a[0])*t,py=a[1]+(far[1]-a[1])*t,pz=a[2]+(far[2]-a[2])*t;
                double top=ch.isProne()?PRONE_LEVELS:STANDING_LEVELS;
                if (sq(px-ch.getX())+sq(py-ch.getY())<=sq(ch.getWidth()) && pz>=ch.getZ() && pz<=ch.getZ()+top) { best=ch;bestT=t; }
            }
            if (best!=null) out.get(best)[1]++;
        }
        out.values().removeIf(v->v[1]==0);
        return out;
    }
    /** A character's height for a pellet past glass: the adult stature of tools/model_config.py (ADULT_STATURE_METRES,
     * the mean of 1.755 and 1.618 m) in levels; a prone one, a quarter of it. */
    static final double STANDING_LEVELS=(1.755+1.618)/2/TarkovBallistics.METRES_PER_LEVEL,PRONE_LEVELS=STANDING_LEVELS/4;
    /** OnExit of calculateHitInfoList: the nearest target the round was sent to bounds the panes it breaks. */
    public static void selected(IsoGameCharacter owner) {
        Attack attack=ATTACK.get();
        if (attack==null || attack.owner!=owner) return;
        try {
            var list=owner.getHitInfoList();
            if (qaTrace) log("selected "+describe(list));
            // No list for processBallisticsTargets (nothing PZ could target): the round still crosses the glass on its line.
            if (!attack.traced && !qaLegacy && !ShotOwner.suppress(owner,null) && owner.getPrimaryHandItem() instanceof HandWeapon weapon
                    && weapon.isAimedFirearm()) apply(owner,weapon,new ArrayList<>(1),attack);
            if (attack.crossings.isEmpty()) return;
            for (int i=0;i<list.size();i++) {
                HitInfo hit=list.get(i);
                if (hit!=null && hit.getObject() instanceof IsoGameCharacter) attack.nearest=Math.min(attack.nearest,along(attack.a,attack.b,hit.x,hit.y,hit.z));
            }
        } catch (RuntimeException | LinkageError e) { error(e); }
    }

    static final double LEVEL=TarkovBallistics.METRES_PER_LEVEL/TarkovBallistics.METRES_PER_CELL;
    static double sq(double v) { return v*v; }
    /** Fraction along a->b of a point's projection, z levels scaled to cells (metric). */
    static double along(double[] a,double[] b,double x,double y,double z) {
        double dx=b[0]-a[0],dy=b[1]-a[1],dz=(b[2]-a[2])*LEVEL,l2=dx*dx+dy*dy+dz*dz;
        return l2<1e-12?0:((x-a[0])*dx+(y-a[1])*dy+(z-a[2])*LEVEL*dz)/l2;
    }
    /** Broad phase: the vehicle's centre within its half diagonal plus a cell of the segment (x, y). */
    static boolean near(BaseVehicle v,double[] a,double[] b) {
        var e=v.getScript().getExtents();
        double r=Math.sqrt(e.x*e.x+e.y*e.y+e.z*e.z)/2+1+v.getScript().getCenterOfMassOffset().length();
        double dx=b[0]-a[0],dy=b[1]-a[1],l2=dx*dx+dy*dy;
        double s=l2<1e-12?0:Math.max(0,Math.min(1,((v.getX()-a[0])*dx+(v.getY()-a[1])*dy)/l2));
        return sq(v.getX()-a[0]-s*dx)+sq(v.getY()-a[1]-s*dy)<=r*r;
    }
    static boolean listed(List<HitInfo> list,BaseVehicle v) {
        for (int i=0;i<list.size();i++) if (list.get(i)!=null && list.get(i).getObject()==v) return true;
        return false;
    }
    /** The line through one vehicle, by the owner path's pieces (ShotVehicleGlassNative.contacts); null: no contact. */
    static Crossing cross(BaseVehicle v,double[] a,double[] b,boolean listed) {
        var sim=WorldSimulation.instance;
        if (sim==null || v.getParts()==null) return null;
        Transform transform=v.getWorldTransform(new Transform());
        Vec3 la=ShotVehicleGlassNative.local(transform,sim,a[0],a[1],a[2]),lb=ShotVehicleGlassNative.local(transform,sim,b[0],b[1],b[2]);
        var contacts=ShotVehicleGlassNative.contacts(v,la,lb);
        if (contacts==null || contacts.isEmpty()) return null;
        var first=contacts.getFirst();
        List<Pane> panes=new ArrayList<>();double stop=Double.POSITIVE_INFINITY;
        for (var c:contacts) {
            if (!c.glass()) { stop=c.fraction();break; }
            panes.add(new Pane(c.fraction(),c.part(),c.opening()));
        }
        return new Crossing(v,first.glass(),first.fraction(),stop,List.copyOf(panes),listed);
    }

    // The glass factor: the owner path's own contact loss (rule B), cached per round and centimetre.
    private static TarkovBallisticsData flightData;
    private static TarkovPenetrationData penetrationData;
    private static boolean dataTried;
    private static final Map<String,TarkovBallistics.Flight> FLIGHTS=new HashMap<>();
    static synchronized boolean data() {
        if (flightData!=null) return true;
        if (dataTried) return false;
        dataTried=true;
        try {
            var mod=ChooseGameInfo.getModDetails(ModLua.MOD);
            if (mod==null) return false;
            for (String root:new String[]{mod.getVersionDir(),mod.getCommonDir(),mod.getDir()}) {
                if (root==null || root.isBlank()) continue;
                Path file=Path.of(root).resolve(TarkovBallisticsData.RESOURCE);
                if (!Files.isRegularFile(file)) continue;
                var flight=TarkovBallisticsData.load(file);
                var penetration=TarkovPenetrationData.load(file.resolveSibling("TW_Penetration.bin"));
                if (!flight.gunplaySha256.equals(penetration.gunplaySha256)) throw new IllegalStateException("B4/B5 source hashes differ");
                flightData=flight;penetrationData=penetration;
                return true;
            }
        } catch (Exception | LinkageError e) { error(e); }
        return false;
    }
    /** The Tarkov ammo template of the weapon's round (ShotOwner.actualAmmo), or null. */
    static String ammo(HandWeapon weapon) {
        try {
            var type=weapon.getAmmoType();
            if (type==null || !data()) return null;
            var profile=ShotOwner.actualAmmo(flightData,type.getItemKey());
            return profile==null?null:profile.ammoTpl();
        } catch (RuntimeException | LinkageError e) { return null; }
    }
    /** Damage kept through one intact pane at this distance: 1 without a Tarkov round. */
    static synchronized float glassFactor(String tpl,double metres) {
        if (tpl==null || !data() || !(metres>0)) return 1;
        try { return glassFactor(flightData,penetrationData,FLIGHTS,tpl,(float)metres); }
        catch (RuntimeException e) { return 1; }
    }
    /**
     * TarkovPenetration.prepareImpact's degradation at a contact this far along the round's own flight (launched at its
     * Tarkov muzzle speed, level): the contact velocity is the flight's sample at that time, as ShotPenetration.Chain
     * gives it. Shared with tools/check_vehicle_glass_pass.py, which compares it with prepareImpact itself.
     */
    public static float glassFactor(TarkovBallisticsData flight,TarkovPenetrationData penetration,Map<String,TarkovBallistics.Flight> cache,
            String tpl,float metres) {
        var ammo=flight.ammo.get(tpl);
        if (ammo==null || ammo.inert()) return 1;
        var f=cache.computeIfAbsent(tpl,k->new TarkovBallistics.Flight(ammo,1f,Vec3.ZERO,new Vec3(0,0,1),flight.g1,flight.gravity));
        float time=f.atHorizontalRange(metres).sample().timeSeconds();
        float speed=f.sample(time).velocity().length();
        return TarkovPenetration.degradation(speed,TarkovBallistics.initialSpeed(ammo,1f),penetration.damageDegradationCoefficient);
    }

    static void error(Throwable e) {
        errors++;
        String text=String.valueOf(e);
        if (!text.equals(lastError)) System.out.println("[TW-GLASSPASS] kept PZ's rule for this shot: "+text);
        lastError=text;
    }
    static void log(String line) { System.out.println("[TW-GLASSPASS] "+line); }
    static String fmt(double v) { return String.format(java.util.Locale.ROOT,"%.3f",v); }
    static String name(Crossing c) { return c.vehicle().getScript().getFullName()+"#"+c.vehicle().getId(); }
    static String part(VehiclePart p) { return p==null?"fixed-glass":p.getId(); }
    static String describe(List<HitInfo> list) {
        StringBuilder s=new StringBuilder("[");
        for (int i=0;list!=null && i<list.size();i++) {
            HitInfo h=list.get(i);
            if (h==null) continue;
            Object o=h.getObject();
            String id=o instanceof BaseVehicle v?"vehicle#"+v.getId():o instanceof IsoGameCharacter c?c.getClass().getSimpleName()+"#"+c.getID():String.valueOf(o);
            s.append(i==0?"":"; ").append(id).append('@').append(fmt(h.x)).append(',').append(fmt(h.y)).append(',').append(fmt(h.z))
                    .append(" d2=").append(fmt(h.distSq)).append(" chance=").append(h.chance);
        }
        return s.append(']').toString();
    }
    /** Bullet's own arrays of this attack: muzzle-ray targets, camera targets, spread pellets (id@x,y,z). */
    static String bullet(IsoGameCharacter owner) {
        var c=owner.getBallisticsController();
        if (c==null) return "none";
        var m=new zombie.iso.Vector3();var d=new zombie.iso.Vector3();c.calculateMuzzlePosition(m,d);
        StringBuilder s=new StringBuilder("muzzle="+fmt(m.x)+","+fmt(m.y)+","+fmt(m.z)+" dir="+fmt(d.x)+","+fmt(d.y)+","+fmt(d.z)+" targets=[");
        float[] t=c.getBallisticsTargets();
        for (int i=0;i<c.getNumberOfTargets();i++) s.append(i==0?"":"; ").append((int)t[i*4]).append('@').append(fmt(t[i*4+1])).append(',').append(fmt(t[i*4+3])).append(',').append(fmt(t[i*4+2]/2.44949f));
        s.append("] camera=[");
        float[] k=c.getCameraTargets();
        for (int i=0;i<c.getNumberOfCameraTargets();i++) s.append(i==0?"":"; ").append((int)k[i*5]).append('@').append(fmt(k[i*5+1])).append(',').append(fmt(k[i*5+3])).append(',').append(fmt(k[i*5+2]/2.44949f)).append('/').append((int)k[i*5+4]);
        s.append("] spread=[");
        float[] p=c.getBallisticsSpreadData();
        for (int i=0;i<c.getNumberOfSpreadData();i++) s.append(i==0?"":"; ").append((int)p[i*4]).append('@').append(fmt(p[i*4+1])).append(',').append(fmt(p[i*4+3])).append(',').append(fmt(p[i*4+2]/2.44949f));
        return s.append(']').toString();
    }
    /** QA: the centre of a window part's largest measured triangle and the pane's outward normal (vehicle-local cells). */
    public static String qaPane(BaseVehicle vehicle,String partId) {
        ShotVehicleGlass.Measured glass=vehicle==null?null:ShotVehicleGlass.measured(vehicle.getScript());
        if (glass==null || partId==null) return null;
        java.util.Set<String> windows=new java.util.HashSet<>();var parts=vehicle.getParts();
        for (int i=0;i<parts.getPartCount();i++) { var q=parts.getPartByIndex(i);if (q.getWindow()!=null) windows.add(q.getId()); }
        ShotVehicleGlass.Pane pane=glass.pane(partId,windows);
        if (pane==null) return null;
        float[] t=pane.tris();double best=-1;Vec3 centre=null;
        for (int i=0;i<t.length;i+=9) {
            Vec3 p=new Vec3(t[i],t[i+1],t[i+2]),q=new Vec3(t[i+3],t[i+4],t[i+5]),r=new Vec3(t[i+6],t[i+7],t[i+8]);
            Vec3 u=q.minus(p),v=r.minus(p);
            double area=Math.sqrt(sq(u.y()*v.z()-u.z()*v.y())+sq(u.z()*v.x()-u.x()*v.z())+sq(u.x()*v.y()-u.y()*v.x()));
            if (area>best) { best=area;centre=p.plus(q).plus(r).times(1/3f); }
        }
        Vec3 n=pane.normal();
        return centre==null?null:fmt(centre.x())+","+fmt(centre.y())+","+fmt(centre.z())+","+fmt(n.x())+","+fmt(n.y())+","+fmt(n.z());
    }
    public static String stats() {
        return "attacks="+attacks+" passed="+passed+" stopped="+stopped+" broken="+broken+" errors="+errors+" legacy="+qaLegacy;
    }

    @Patch(className="zombie.CombatManager",methodName="attackCollisionCheck",strictMatch=true)
    public static final class Attacked {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) IsoGameCharacter owner,@Patch.Argument(1) HandWeapon weapon,
                @Patch.Argument(2) SwipeStatePlayer swipe,@Patch.Argument(3) AttackType type) {
            if (!ModLua.present()) return;
            ShotVehicleGlassPass.attackEnter(owner,weapon);
        }
        @Patch.OnExit(onThrowable=Throwable.class)
        public static void exit(@Patch.Argument(0) IsoGameCharacter owner) {
            ShotVehicleGlassPass.attackExit(owner);
        }
    }
    @Patch(className="zombie.CombatManager",methodName="processBallisticsTargets",strictMatch=true)
    public static final class Targets {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) IsoGameCharacter owner,@Patch.Argument(2) List<HitInfo> list) {
            if (!ModLua.present()) return;
            ShotVehicleGlassPass.targetsEnter(owner,list);
        }
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoGameCharacter owner,@Patch.Argument(1) HandWeapon weapon,
                @Patch.Argument(2) List<HitInfo> list) {
            if (!ModLua.present()) return;
            ShotVehicleGlassPass.targets(owner,weapon,list);
        }
    }
    @Patch(className="zombie.CombatManager",methodName="calculateHitInfoList",strictMatch=true)
    public static final class Selected {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoGameCharacter owner) {
            ShotVehicleGlassPass.selected(owner);
        }
    }
    @Patch(className="zombie.CombatManager",methodName="applyRangeHitLocationDamage",strictMatch=true)
    public static final class Damage {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoGameCharacter target,@Patch.Return(readOnly=false) float damage) {
            damage=ShotVehicleGlassPass.damage(target,damage);
        }
    }
}
