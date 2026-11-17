package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Patch;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.gameStates.ChooseGameInfo;
import zombie.inventory.InventoryItemFactory;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import pz.escapefromzomboidfirearms.zb.ShotSnapshot.*;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;

/** Own thrown-item adapter. No firearm poses/input/owner state is changed. */
@Exposer.LuaClass
public final class HandGrenadeNative {
    private static TarkovBallisticsData flight;
    private static TarkovPenetrationData penetration;
    private static boolean failed;
    private static long sequence;
    private static final ArrayList<Batch> batches=new ArrayList<>();
    private static final Set<String> detonations=new HashSet<>();
    private static final WeakHashMap<IsoPlayer,Long> deafUntil=new WeakHashMap<>();
    private static final WeakHashMap<zombie.iso.objects.IsoFire,Boolean> ownSmoke=new WeakHashMap<>();
    private static Field deafPlayer;
    private static final java.util.concurrent.ConcurrentLinkedQueue<zombie.iso.objects.IsoWorldInventoryObject> oldVisuals=new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static Boolean visualSupported;
    public static long fragments,contacts,applied;

    private HandGrenadeNative() { }
    /** The held grenade has its own input/release owner; a native melee shove
     * must not steal the same press before Lua observes it. Remote actors stay native.
     */
    public static boolean ownsGrenadeAttack(Object actor) {
        if (!(actor instanceof IsoPlayer p) || !p.isLocalPlayer() || p.isDead()) return false;
        var item=p.getPrimaryHandItem();
        return item!=null && item.getFullType().startsWith("EscapeFromZomboidFirearms.HG_");
    }
    @Patch(className="zombie.characters.IsoLivingCharacter",methodName="AttemptAttack",strictMatch=true)
    public static final class GrenadeAttack {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.This Object actor,@Patch.Argument(0) float charge) {
            return HandGrenadeNative.ownsGrenadeAttack(actor);
        }
    }
    public static zombie.core.textures.Texture white() { return zombie.core.textures.Texture.getWhite(); }
    public static void smokeVision(boolean on,boolean active) { HandGrenadeSmokeSight.configure(on,active); }
    public static boolean smokeCloud(String id,double x,double y,double z,double radius) { return HandGrenadeSmokeSight.cloud(id,x,y,z,radius); }
    public static void smokeDrop(String id) { HandGrenadeSmokeSight.drop(id); }
    public static String smokeInfo() { return HandGrenadeSmokeSight.info(); }
    public static boolean smokeUnobscured(zombie.characters.IsoGameCharacter observer,zombie.iso.IsoMovingObject target) { return HandGrenadeSmokeSight.unobscured(observer,target); }
    @Patch(className="zombie.characters.IsoGameCharacter",methodName="CanSee",strictMatch=true)
    public static final class SmokePerception {
        @Patch.OnExit
        public static void exit(@Patch.This Object actor,@Patch.Argument(0) Object target,@Patch.Return(readOnly=false) boolean value) {
            value=HandGrenadeSmokeSight.perception(actor,target,value);
        }
    }
    @Patch(className="zombie.characters.IsoZombie",methodName="spotted",strictMatch=true)
    public static final class SmokeSpotting {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.This Object actor,@Patch.Argument(0) Object target,@Patch.Argument(1) boolean forced) {
            return HandGrenadeSmokeSight.spotting(actor,target);
        }
    }
    public static void stampSmoke(zombie.iso.objects.IsoFire fire) {
        HandGrenadeSmokeSight.stamp(fire);if (fire!=null) ownSmoke.put(fire,true);
    }
    /** Keep native LOS/perception voxels, with one visible cloud from the source atlas. */
    public static boolean ownsSmokeImage(Object object) {
        return MuzzleFx.data!=null && ownSmoke.containsKey(object);
    }
    @Patch(className="zombie.iso.objects.IsoFire",methodName="render")
    public static final class SmokeImage {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.This Object fire) { return HandGrenadeNative.ownsSmokeImage(fire); }
    }
    public static boolean visualType(String full) { return full!=null && full.startsWith("EscapeFromZomboidFirearms.HGV_"); }
    public static boolean visualABI() {
        if (visualSupported!=null) return visualSupported;
        try {
            var type=zombie.iso.objects.IsoWorldInventoryObject.class;
            visualSupported=type.getMethod("load",ByteBuffer.class,int.class,boolean.class).getReturnType()==void.class
                    && type.getMethod("getItem").getReturnType()==zombie.inventory.InventoryItem.class
                    && type.getMethod("removeFromWorld").getReturnType()==void.class
                    && type.getMethod("removeFromSquare").getReturnType()==void.class
                    && zombie.inventory.InventoryItem.class.getMethod("getFullType").getReturnType()==String.class;
        } catch (Throwable failure) { visualSupported=false; }
        if (!visualSupported) System.out.println("[TW-HAND-GRENADE] saved visual cleanup ABI unavailable");
        return visualSupported;
    }
    /** Deserialized local display items are retired on the next main-thread
     * tick. Persisted authority rows recreate their current visual once. */
    public static void loadedVisual(zombie.iso.objects.IsoWorldInventoryObject object) {
        try { if (visualABI() && object!=null && object.getItem()!=null && visualType(object.getItem().getFullType())) oldVisuals.add(object); }
        catch (Throwable ignored) { }
    }
    public static void retireVisuals() {
        zombie.iso.objects.IsoWorldInventoryObject object;
        while ((object=oldVisuals.poll())!=null) {
            try {
                if (object.getItem()!=null && visualType(object.getItem().getFullType())) {
                    object.removeFromWorld();object.removeFromSquare();
                }
            } catch (Throwable ignored) { }
        }
    }

    public static KahluaTable pose(IsoPlayer p) {
        if (p==null || p.isDead() || !p.isLocalPlayer()) return null;
        try {
            if (!WeaponFrame.world(p)) return null;
            double[] frame=WeaponFrame.F.clone();
            float[] look=new float[3];
            double dx,dy,dz;
            int view=0;
            if (ViewpointBridge.ready() && ViewpointBridge.perspective()) {
                if (!ViewpointBridge.lookDirection(look)) return null;
                // Camera.direction is the camera's back axis horizontally: (-cos yaw, sin pitch, -sin yaw) for a view
                // along PZ (cos yaw, sin yaw) (ViewpointPass.eye and TWImpactFx.vpSightRay negate it too). W10 item 14
                // QA: read as it was, every Viewpoint throw (and the flash's facing test) went behind the player.
                dx=-look[0];dy=-look[2];dz=look[1];view=ViewpointBridge.state();
            } else {
                var direction=p.getForwardDirection();dx=direction.x;dy=direction.y;dz=0;
            }
            double length=Math.sqrt(dx*dx+dy*dy+dz*dz);
            if (!(length>1e-9)) return null;
            KahluaTable out=LuaManager.platform.newTable();
            out.rawset("ox",frame[0]);out.rawset("oy",frame[1]);out.rawset("oz",frame[2]);
            out.rawset("dx",dx/length);out.rawset("dy",dy/length);out.rawset("dz",dz/length);
            out.rawset("view",(double)view);
            return out;
        } catch (Throwable t) { ViewpointBridge.fail("hand-grenade-pose",t);return null; }
    }
    /** The actual held model's bounds centre in the already scaled model frame.
     * This moves the spawn point with the rendered hand, without moving the pose.
     */
    public static KahluaTable throwPose(IsoPlayer p,double x,double y,double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x)>1 || Math.abs(y)>1 || Math.abs(z)>1) return null;
        KahluaTable out=pose(p);
        if (out==null) return null;
        double[] f=WeaponFrame.F.clone();
        double ox=f[0]+f[3]*x+f[6]*y+f[9]*z,oy=f[1]+f[4]*x+f[7]*y+f[10]*z;
        out.rawset("ox",ox);
        out.rawset("oy",oy);
        out.rawset("oz",f[2]+f[5]*x+f[8]*y+f[11]*z);
        // W10 item 14: the throw's free flight follows the relief Viewpoint draws (TW/FlightRelief); none in isometric.
        if (((Double)out.rawget("view"))!=0) {
            KahluaTable relief=GrenadeFlightPose.drawnRelief(ViewpointBridge.frameNow(),ox,oy,(Double)out.rawget("dx"),(Double)out.rawget("dy"));
            if (relief!=null) out.rawset("relief",relief);
        }
        return out;
    }

    private static boolean load() {
        if (flight!=null && penetration!=null) return true;
        if (failed) return false;
        try {
            var mod=ChooseGameInfo.getModDetails("EscapeFromZomboidFirearms");
            if (mod==null) return false;
            for (String dir:new String[]{mod.getVersionDir(),mod.getDir()}) {
                if (dir==null) continue;
                Path path=Path.of(dir).resolve("media/viewpoint/TW_HandGrenadeBallistics.bin");
                if (Files.isRegularFile(path)) {
                    flight=TarkovBallisticsData.load(path);
                    penetration=TarkovPenetrationData.load(path.resolveSibling("TW_HandGrenadePenetration.bin"));
                    return true;
                }
            }
        } catch (Throwable t) { failed=true;System.out.println("[TW-HAND-GRENADE] core unavailable: "+t); }
        return false;
    }

    /** Source count, source fragment ammo, the existing B6 random cache and chain. */
    public static List<Vec3> directions(TarkovPenetrationData data,long seed,int count) {
        if (count<1 || count>1024) throw new IllegalArgumentException("fragment count");
        var random=ShotPenetration.randoms(data,seed);
        ArrayList<Vec3> result=new ArrayList<>();
        for (int i=0;i<count;i++) result.add(random.direction(i));
        return List.copyOf(result);
    }

    private static final class FragmentLedger implements Ledger {
        final Set<ImpactKey> seen=new HashSet<>();
        public boolean reserve(Id id,long expiry) { return true; }
        public boolean nativeFire(Id id,int nativeByte) { return false; }
        public boolean impact(ImpactKey key) { return seen.add(key); }
        public void finish(Id id,String reason) { seen.clear(); }
    }
    private record Batch(ShotSnapshot shot,ShotPenetration.Chain chain,ShotDamage damage,boolean live) { }

    /** "live" owns fragments after admission; "native" means no custom mutation.
     * Shadow always leaves damage to the native blast. MP uses native server
     * damage until the optional dedicated adapter can observe the same bodies.
     * W11 hostauth stage 3: on a server without the mod's Java the thrower's client flies its own grenade's fragments when
     * the server asks it (client authority, TW_ShotAuthority): ShotClientDamage reports each contact for the server to check.
     */
    /** Height of the fragments' origin above the point HandGrenades reports. A grenade at rest lies on the floor and
     * reports the floor's own height (its physics rests it at the level), and a fragment starting on the floor plane meets
     * that plane at t=0 in every direction (ShotContacts.Face is two-sided): no fragment of a resting grenade ever left
     * the floor (W11 hostauth stand w11auth-11: fragments=70 contacts=70 applied=0, the ring on the thrower's client). The
     * fragments start from the grenade's emit point instead, which in every frag grenade of the catalogue lies at least
     * this far from each face of its model's bounds (TW/Data/HandGrenades emitPoint against
     * assets/hand_grenades_build.json bounds: VOG-17 0.0123 m, V40 0.0126, VOG-25 0.0129, RGN 0.0181, RGO 0.0187,
     * RGD-5 0.0194, M67 0.0201, F-1 0.0204; check_shot_client holds the value to the catalogue). */
    static final double REST_LIFT_METRES=0.0123;
    public static String startFragments(IsoPlayer shooter,String grenadeTpl,String fragmentTpl,int count,
            String id,long seed,double x,double y,double z,int mode) {
        boolean client=GameClient.client && ShotOwner.clientAuthority();
        if (mode==1 || GameClient.client && !client || shooter==null || !load() || batches.size()>=64) return "native";
        if (detonations.contains(id)) return "duplicate";
        try {
            var original=flight.ammo.get(fragmentTpl);
            var p=penetration.ammo(fragmentTpl);
            if (original==null || p.inert() || p.grenade()) return "native";
            var ammo=new TarkovBallistics.Ammo(original.tpl(),original.initialSpeed(),original.massGrams(),
                    original.diameterMillimetres(),original.ballisticCoefficient(),original.lifeSeconds(),count,false);
            var view=(HandWeapon)InventoryItemFactory.CreateItem("Base.Pistol");
            // Same native conversion already used by launcher buckshot (D5),
            // per fragment; B6 divides a cartridge budget by projectileCount.
            view.setMinDamage(count*p.damage()*0.012f);view.setMaxDamage(count*p.damage()*0.020f);
            ByteBuffer bytes=ByteBuffer.allocate(262144);view.saveWithSize(bytes,false);
            var nativeView=new NativeWeapon(IsoWorld.getWorldVersion(),Arrays.copyOf(bytes.array(),bytes.position()));
            long seq=++sequence;
            var pose=new ShotPoseSnapshot(0,System.nanoTime(),0,0,view.getID(),id,"grenade",0,0,
                    new ShotPoseSnapshot.Pz(x,y,z+REST_LIFT_METRES/TarkovBallistics.METRES_PER_LEVEL),new Vec3(0,0,1),new Vec3(0,1,0),
                    List.of(0.,0.,0.,1.,0.,0.,0.,1.,0.,0.,0.,1.),
                    new ShotPoseSnapshot.Zero(ShotPoseSnapshot.ZeroKind.NONE,fragmentTpl,0,0,false));
            var shot=new ShotSnapshot(new Id("hand-grenades",1,shooter.getOnlineID(),seq),flight.inputsSha256,seed,1,
                    0,new Weapon(view.getID(),view.getFullType(),"HG",grenadeTpl,id,seq),List.of(),fragmentTpl,
                    "Base.Bullets9mm","HG-fragments",ammo,1,ammo.initialSpeed(),0,pose,directions(penetration,seed,count),nativeView);
            var world=new ShotContactsPz(()->IsoWorld.instance.currentCell,new ShotTargets(),s->shooter,
                    reason->System.out.println("[TW-HAND-GRENADE] "+reason));
            var traces=new ShotContacts(world);
            if (!traces.ready()) return "native";
            var damage=new ShotDamage(client?new ShotClientDamage(world):new ShotDamagePz(world,penetration),new FragmentLedger(),
                    s->!GameClient.client || client,reason->System.out.println("[TW-HAND-GRENADE] "+reason));
            damage.enabled(mode==3);
            boolean live=mode==3 && damage.ready();
            var chain=new ShotPenetration.Chain(shot,flight,penetration,traces);
            if (client && live) ShotClientAuthority.grenade(shot,id);
            detonations.add(id);
            batches.add(new Batch(shot,chain,damage,live));fragments+=count;
            return live?"live":"shadow";
        } catch (Throwable t) {
            // Admission only: no fragment damage happened here.
            System.out.println("[TW-HAND-GRENADE] native fragments fallback: "+t.getClass().getSimpleName());return "native";
        }
    }

    public static void tick(double seconds) {
        if (!Double.isFinite(seconds) || seconds<0 || seconds>1) return;
        var it=batches.iterator();
        while (it.hasNext()) {
            Batch b=it.next();
            try {
                float end=Math.min(b.shot.ammo().lifeSeconds(),b.chain.time()+(float)seconds);
                while (!b.chain.done() && b.chain.time()<end) {
                    var step=b.chain.advance(Math.min(end,b.chain.time()+TarkovBallistics.STEP_SECONDS));
                    contacts+=step.impacts().size();
                    if (b.live) applied+=b.damage.commit(b.shot,step.impacts()).size();
                }
                if (b.chain.done()) { b.damage.clear();it.remove(); }
            } catch (Throwable t) {
                // Never replay or add native fragments after a partially applied batch.
                b.damage.clear();it.remove();System.out.println("[TW-HAND-GRENADE] fragment batch stopped "+t);
            }
        }
    }

    public static void deafen(IsoPlayer player,double seconds) {
        if (player!=null && player.isLocalPlayer() && Double.isFinite(seconds) && seconds>2 && seconds<=120)
            deafUntil.merge(player,System.nanoTime()+(long)(seconds*1e9),Math::max);
    }
    /** Public: called from advice in a PZ class. Traits/settings are never edited. */
    public static boolean deaf(Object parameter) {
        // Nobody deafened: no reflective read of the parameter's player on every audio update.
        if (deafUntil.isEmpty()) return false;
        try {
            if (deafPlayer==null) {
                Class<?> cls=Class.forName("zombie.audio.parameters.ParameterDeaf");
                if (cls.getMethod("calculateCurrentValue").getReturnType()!=float.class) return false;
                Field field=cls.getDeclaredField("player");
                if (field.getType()!=IsoPlayer.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) return false;
                field.setAccessible(true);deafPlayer=field;
            }
            Long end=deafUntil.get((IsoPlayer)deafPlayer.get(parameter));
            return end!=null && System.nanoTime()<end;
        } catch (Throwable t) { return false; }
    }
    @Patch(className="zombie.audio.parameters.ParameterDeaf",methodName="calculateCurrentValue",strictMatch=true)
    public static final class Hearing {
        @Patch.OnExit
        public static void exit(@Patch.This Object parameter,@Patch.Return(readOnly=false) float value) {
            if (HandGrenadeNative.deaf(parameter)) value=1;
        }
    }
    @Patch(className="zombie.iso.objects.IsoFire",methodName="TestVision",strictMatch=true)
    public static final class SmokeVision {
        @Patch.OnExit
        public static void exit(@Patch.This zombie.iso.objects.IsoFire fire,
                @Patch.Argument(0) zombie.iso.IsoGridSquare from,@Patch.Argument(1) zombie.iso.IsoGridSquare to,
                @Patch.Return(readOnly=false) zombie.iso.IsoObject.VisionResult value) {
            value=HandGrenadeSmokeSight.vision(fire,value);
        }
    }
    @Patch(className="zombie.iso.objects.IsoFire",methodName="addToWorld",strictMatch=true)
    public static final class SmokeLoad {
        @Patch.OnExit
        public static void exit(@Patch.This zombie.iso.objects.IsoFire fire) {
            if (!ModLua.present()) return; // a server without the mod: its fires are not ours to read (ModLua)
            HandGrenadeSmokeSight.loaded(fire);
        }
    }
    @Patch(className="zombie.iso.objects.IsoWorldInventoryObject",methodName="load",strictMatch=true)
    public static final class VisualLoad {
        @Patch.OnExit
        public static void exit(@Patch.This zombie.iso.objects.IsoWorldInventoryObject object,
                @Patch.Argument(0) ByteBuffer buffer,@Patch.Argument(1) int version,@Patch.Argument(2) boolean net) {
            HandGrenadeNative.loadedVisual(object);
        }
    }
    public static void reset() { batches.clear();detonations.clear();deafUntil.clear(); }
    public static String info() { return "batches="+batches.size()+" fragments="+fragments+" contacts="+contacts+" applied="+applied; }
}
