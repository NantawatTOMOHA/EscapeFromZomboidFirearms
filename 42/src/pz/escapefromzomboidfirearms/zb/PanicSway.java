package pz.escapefromzomboidfirearms.zb;

import java.util.WeakHashMap;
import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.inventory.InventoryItem;

/** Local shooter's moodles to a frozen frame of visible/physical aim. */
@Exposer.LuaClass
public final class PanicSway {
    public record Sample(double yawDegrees, double pitchDegrees, double multiplier, int stress, int panic, long frame) { }
    public static final Sample ZERO = new Sample(0, 0, 0, 0, 0, Long.MIN_VALUE);
    private static final WeakHashMap<IsoPlayer, Local> players = new WeakHashMap<>();
    private static boolean failed;
    public static long errors;
    private static final class Local {
        final TarkovHandling.PanicMotion motion = new TarkovHandling.PanicMotion();
        Sample sample = ZERO;
        InventoryItem item;
        FpPose.Request pose;
        long steps;
    }
    private PanicSway() { }
    public static void update(IsoPlayer p, int stress, int panic, double strength, boolean on, double dt) {
        if (p == null) return;
        if (failed || !on || !p.isLocalPlayer() || p.isDead()) { players.remove(p); return; }
        if (stress < 0 || stress > 4 || panic < 0 || panic > 4 || !Double.isFinite(strength) || strength < 0 || strength > 2
                || !Double.isFinite(dt) || dt < 0 || dt > 1) return;
        Local local = players.computeIfAbsent(p, k -> new Local());
        long frame = FpRecoil.worldFrame();
        if (local.sample.frame == frame) return;
        var offset = local.motion.advance(dt, stress, panic, strength);
        local.sample = new Sample(offset.yawDegrees(), offset.pitchDegrees(), offset.multiplier(), stress, panic, frame);
        local.item = p.getPrimaryHandItem(); local.steps++;
    }
    /** Read only, in degrees: no dt, moodle changes, pose writes or second step. */
    public static Sample sample(IsoPlayer p) {
        Local local = p == null ? null : players.get(p);
        return local != null && p.isLocalPlayer() && !p.isDead() && p.getPrimaryHandItem() == local.item
                && FpPose.freshFrame(local.sample.frame, FpRecoil.worldFrame()) ? local.sample : ZERO;
    }
    public static KahluaTable snapshot(IsoPlayer p) {
        Sample s = sample(p);
        KahluaTable out = LuaManager.platform.newTable();
        out.rawset("yaw", s.yawDegrees); out.rawset("pitch", s.pitchDegrees); out.rawset("multiplier", s.multiplier);
        out.rawset("stress", (double)s.stress); out.rawset("panic", (double)s.panic); out.rawset("frame", (double)s.frame);
        Local local = p == null ? null : players.get(p);
        out.rawset("steps", local == null ? 0.0 : (double)local.steps);
        if (p != null) {
            var controller=p.getBallisticsController();
            if (controller!=null) {
                var direction=controller.getMuzzleDirection();
                out.rawset("nativeX", (double)direction.x);out.rawset("nativeY", (double)direction.y);out.rawset("nativeZ", (double)direction.z);
            }
        }
        return out;
    }
    public static void reset() { players.clear(); }
    public static void off(IsoPlayer p) { players.remove(p); }

    /** Called after final own poses. FP consumes sample() in its pose owner.
     * Iso rotates both wrist targets and the real prop; native B42 ballistics
     * reads that prop's muzzle frame, so no independent shot-angle noise exists.
     */
    public static void onModel(Object self) {
        if (failed) return;
        if (AnimOptOut.blocks(self)) return; // W8 animopt: the stress sway of the arms belongs to vanilla/other mods
        try { apply(self); }
        catch (Throwable failure) {
            failed=true;players.clear();errors++;
            System.out.println("[TW-PANIC-SWAY] pose off: "+failure);
        }
    }
    private static void apply(Object self) {
        // The held-weapon test before the reflective perspective() read (every player and animal AnimationPlayer).
        if (!(self instanceof AnimationPlayer ap) || ap.parentPlayer != null
                || !(ap.getIsoGameCharacter() instanceof IsoPlayer p)) return;
        if (!(p.getPrimaryHandItem() instanceof zombie.inventory.types.HandWeapon heldWeapon)
                || !heldWeapon.isRanged() || !heldWeapon.getFullType().startsWith("EscapeFromZomboidFirearms.")
                || ViewpointBridge.perspective()) return;
        Sample offset = sample(p);
        if (offset.multiplier == 0) return;
        var held = p.primaryHandModel;
        var sd = ap.getSkinningData();
        if (held == null || held.animPlayer != ap || sd == null || ap.boneTransforms == null || FpPose.bodyState(p)) return;
        FpPose.Skeleton s = FpPose.skeleton(sd, Math.min(ap.boneTransforms.length, ap.getModelTransformsCount()));
        int prop = held.parentBone;
        if (s == null || prop < 0 || prop >= s.n || s.parent[prop] < 0) return;
        Local local = players.get(p);
        if (local.pose == null || local.pose.item != p.getPrimaryHandItem()) {
            local.pose = new FpPose.Request(heldWeapon, LuaManager.platform.newTable());
            local.pose.mode = "iso panic";
        }
        double[][] model = new double[s.n][];
        for (int i = 0; i < s.n; i++) model[i] = GripIK.read(ap.getModelTransformAt(i), new double[16]);
        double[] pivot = GripIK.lerp(GripIK.pos(model[s.lc]), GripIK.pos(model[s.rc]), .5);
        double[] heading = FpPose.lookModel(p.getLookAngleRadians(), 0, ap.getRenderedAngle());
        double[] rightAxis = GripIK.normalize(GripIK.cross(heading, new double[]{0, 1, 0}));
        double[] turn = GripIK.mulNew(GripIK.rigid(new double[]{0, 1, 0}, Math.toRadians(offset.yawDegrees), pivot),
                GripIK.rigid(rightAxis, Math.toRadians(offset.pitchDegrees), pivot));
        double[] leftTarget = GripIK.mulNew(turn, model[s.lh]), rightTarget = GripIK.mulNew(turn, model[s.rh]);
        double[][] torso = p.isFemale() ? GripIK.TORSO_F : GripIK.TORSO_M;
        double[][] left = FpPose.arm(model, s, s.lc, s.ua, s.fa, leftTarget,
                GripIK.normalize(GripIK.sub(GripIK.pos(model[s.fa]), pivot)), torso, local.pose, 0, true, 0);
        double[][] right = FpPose.arm(model, s, s.rc, s.rua, s.rfa, rightTarget,
                GripIK.normalize(GripIK.sub(GripIK.pos(model[s.rfa]), pivot)), torso, local.pose, 1, true, 0);
        ArmNatural.solved(p, 0, left[4]);
        ArmNatural.solved(p, 1, right[4]);
        double[] weapon = GrenadeThrowPose.attach(right[3], model[s.rh], model[prop]);
        double[][] next = new double[s.n][];
        boolean[] driver = new boolean[s.n];
        int[] ids = {s.lc,s.ua,s.fa,s.lh,s.rc,s.rua,s.rfa,s.rh,prop};
        double[][] values = {left[0],left[1],left[2],left[3],right[0],right[1],right[2],right[3],weapon};
        for (int i = 0; i < ids.length; i++) { next[ids[i]] = values[i]; driver[ids[i]] = true; }
        FpPose.write(ap,s,model,next,driver);
    }
}
