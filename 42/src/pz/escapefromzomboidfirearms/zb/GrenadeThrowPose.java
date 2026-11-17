package pz.escapefromzomboidfirearms.zb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Patch;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.Model;
import zombie.gameStates.ChooseGameInfo;
import zombie.inventory.types.HandWeapon;
import zombie.iso.Vector3;

/** Original grenade hands in every local view. T1 owns every throw.
 * No firearm requests, eye calibration, torso, stance, animation bank or VP state is written.
 */
@Exposer.LuaClass
public final class GrenadeThrowPose {
    private static final ConcurrentHashMap<IsoPlayer, Request> requests = new ConcurrentHashMap<>();
    private static GrenadeThrowData data;
    private static boolean failed;
    private static String status = "unloaded";
    public static long applied, errors;

    private GrenadeThrowPose() { }

    private static final class Request {
        final IsoPlayer player;
        final HandWeapon item;
        final GrenadeThrowData.Item source;
        final FpPose.Request eye;
        String action = "", appliedAction = "";
        GrenadeThrowData.Track track;
        double time, sampled, token, appliedToken, blend;
        long stamp, frame = -1, at;
        boolean gate, released, placed, hidden, wasHidden;
        double[][] sourcePose, blendPose;
        double[] sourceGoals, blendGoals, prop, hand;
        double missCm, poseError, gripError;
        int propBone = -1, view;

        Request(IsoPlayer player, HandWeapon item, GrenadeThrowData.Item source) {
            this.player = player; this.item = item; this.source = source;
            eye = new FpPose.Request(item, LuaManager.platform.newTable());
            eye.mode = "fp grenade";
        }
    }

    private static GrenadeThrowData data() {
        if (data != null || failed) return data;
        try {
            if (zombie.characters.IsoLivingCharacter.class.getMethod("AttemptAttack", float.class).getReturnType() != boolean.class)
                throw new IllegalStateException("grenade input ABI");
            var mod = ChooseGameInfo.getModDetails("EscapeFromZomboidFirearms");
            if (mod == null) return null;
            for (String directory : new String[]{mod.getVersionDir(), mod.getDir()}) {
                if (directory == null) continue;
                Path path = Path.of(directory).resolve("media/viewpoint/TW_GrenadeThrows.bin");
                if (Files.isRegularFile(path)) {
                    data = GrenadeThrowData.load(path); status = "ready"; return data;
                }
            }
            stop(new IllegalStateException("grenade throws resource missing"));
        } catch (Throwable failure) { stop(failure); }
        return null;
    }

    public static boolean available(String digest) {
        GrenadeThrowData d = data();
        return d != null && d.digest.equals(digest) && !failed;
    }

    /** Lua renews this request each world tick. A release gate holds the exact source
     * event pose until Lua has submitted T1 once; a late frame cannot skip the key.
     */
    public static boolean set(IsoPlayer p, HandWeapon item, String tpl, String action, double seconds,
            double token, String digest, boolean gate, double blend) {
        try {
            if (!available(digest) || p == null || !p.isLocalPlayer() || p.isDead() || item == null
                    || !item.getFullType().equals("EscapeFromZomboidFirearms.HG_" + tpl)
                    || !Double.isFinite(seconds) || seconds < 0 || !Double.isFinite(token) || token < 0
                    || !Double.isFinite(blend) || blend < 0 || blend > 2) return false;
            GrenadeThrowData.Item source = data.items.get(tpl);
            GrenadeThrowData.Track track = source == null ? null : source.tracks().get(action);
            if (track == null || seconds > track.seconds + 1e-6) return false;
            Request r = requests.get(p);
            if (r == null || r.item != item) {
                clear(p); r = new Request(p, item, source); requests.put(p, r);
            }
            if (!r.action.equals(action) || r.token != token) {
                r.blendPose = r.sourcePose; r.blendGoals = r.sourceGoals;
                r.placed = false;
            }
            r.action = action; r.track = track; r.time = seconds; r.token = token;
            r.gate = gate; r.blend = blend; r.stamp = FpRecoil.worldFrame();
            return true;
        } catch (Throwable failure) { stop(failure); return false; }
    }

    /** Public because GripIK's advice enters through this method. Only this layer
     * is disabled on failure; the ordinary FP layer and T1 retain their state.
     */
    public static void onModel(Object self) {
        if (failed || requests.isEmpty()) return;
        if (AnimOptOut.blocks(self)) return; // W8 animopt: the grenade throw pose belongs to vanilla/other mods
        try { apply((AnimationPlayer)self); }
        catch (Throwable failure) { stop(failure); }
    }

    private static void apply(AnimationPlayer ap) {
        if (ap.parentPlayer != null || !(ap.getIsoGameCharacter() instanceof IsoPlayer p)) return;
        Request r = requests.get(p);
        if (r == null) return;
        r.placed = false;
        if (!p.isLocalPlayer() || p.isDead() || p.getVehicle() != null || FpPose.bodyState(p)
                || !FpPose.freshFrame(r.stamp, FpRecoil.worldFrame())
                || p.getPrimaryHandItem() != r.item && !(r.released && p.getPrimaryHandItem() == null)) return;
        var sd = ap.getSkinningData();
        if (sd == null || ap.boneTransforms == null) return;
        FpPose.Skeleton s = FpPose.skeleton(sd, Math.min(ap.boneTransforms.length, ap.getModelTransformsCount()));
        if (s == null) return;
        double[][] model = new double[s.n][];
        for (int i = 0; i < s.n; i++) model[i] = GripIK.read(ap.getModelTransformAt(i), new double[16]);
        boolean perspective = ViewpointBridge.perspective();
        if (perspective && !ViewpointBridge.ready()) return;
        r.view = perspective ? ViewpointBridge.state() : 0;
        // Tarkov supplies only the arms. The native head/heading supplies their
        // body-space frame in iso/TP; locomotion, crouch and torso remain PZ's.
        double[] eye = r.view == 1 ? FpPose.eye(p, ap, model[s.head], r.eye)
                : FpPose.eyeFrame(GripIK.pos(model[s.head]), FpPose.lookModel(p.getLookAngleRadians(), 0, ap.getRenderedAngle()));
        if (eye == null) return;
        var held = p.primaryHandModel;
        int propBone = held == null && r.released ? r.propBone : held == null ? -1 : held.parentBone;
        if (propBone < 0 || propBone >= s.n || s.parent[propBone] < 0 || propBone == s.rh || propBone == s.lh
                || held != null && held.animPlayer != ap) return;
        GrenadeThrowData.Track track = r.track;
        double time = r.gate && track.release >= 0 ? Math.min(r.time, track.release) : r.time;
        double[][] pose = new double[3][];
        for (int i = 0; i < 3; i++) pose[i] = track.pose(i, time);
        double[] goals = track.goals(time);
        if (r.blend > 0 && time < r.blend && r.blendPose != null) {
            double w = time / r.blend;
            for (int i = 0; i < 3; i++) pose[i] = FpPose.interp(r.blendPose[i], pose[i], w);
            for (int i = 0; i < 6; i++) goals[i] = r.blendGoals[i] + (goals[i] - r.blendGoals[i]) * w;
        }
        r.sourcePose = pose; r.sourceGoals = goals.clone();
        r.eye.eyeModel = eye;
        double[] prop = GripIK.mulNew(eye, pose[0]);
        double[][] hands = new double[2][];
        for (int side = 0; side < 2; side++) {
            double[] hand = pose[side + 1].clone();
            double[] rotation = GripIK.mul3(GripIK.unitRotation(hand), data.wristAxes[p.isFemale() ? 1 : 0][side]);
            GripIK.setRotation(hand, rotation, new double[]{1, 1, 1});
            hands[side] = GripIK.mulNew(eye, hand);
            double[] goal = GripIK.point(eye, goals[side * 3], goals[side * 3 + 1], goals[side * 3 + 2]);
            for (int i = 0; i < 3; i++) goals[side * 3 + i] = goal[i];
        }
        long now = System.nanoTime();
        boolean fresh = r.at == 0 || now - r.at > FpPose.RESET_NS;
        double dt = fresh ? 0 : (now - r.at) / 1e9;
        r.at = now;
        double[][] torso = p.isFemale() ? GripIK.TORSO_F : GripIK.TORSO_M;
        double[][] left = FpPose.arm(model, s, s.lc, s.ua, s.fa, hands[0],
                FpPose.pole(goals, 0, model[s.ua], hands[0], null, 0), torso, r.eye, 0, fresh, dt);
        double[][] right = FpPose.arm(model, s, s.rc, s.rua, s.rfa, hands[1],
                FpPose.pole(goals, 3, model[s.rua], hands[1], null, 1), torso, r.eye, 1, fresh, dt);
        ArmNatural.solved(p, 0, left[4]);
        ArmNatural.solved(p, 1, right[4]);
        r.missCm = Math.max(GripIK.dist(GripIK.pos(left[3]), GripIK.pos(hands[0])),
                GripIK.dist(GripIK.pos(right[3]), GripIK.pos(hands[1]))) / GripIK.CM;
        // Keep the authored grenade-to-palm transform, attached to the SOLVED
        // wrist. A reach-limited arm must never leave the prop at an unreachable
        // source target. No source pivot, model scale or clip timing is changed.
        double[] handToProp = GripIK.mulNew(GripIK.inverse(hands[1]), prop);
        prop = attach(right[3], hands[1], prop);
        double[][] next = new double[s.n][];
        boolean[] driver = new boolean[s.n];
        int[] ids = {s.lc, s.ua, s.fa, s.lh, s.rc, s.rua, s.rfa, s.rh, propBone};
        double[][] values = {left[0], left[1], left[2], left[3], right[0], right[1], right[2], right[3], prop};
        for (int i = 0; i < ids.length; i++) { next[ids[i]] = values[i]; driver[ids[i]] = true; }
        FpPose.write(ap, s, model, next, driver);
        double[] actual = GripIK.read(ap.getModelTransformAt(propBone), new double[16]);
        r.hand = GripIK.read(ap.getModelTransformAt(s.rh), new double[16]);
        double[] actualGrip = GripIK.mulNew(GripIK.inverse(r.hand), actual);
        r.gripError = 0;
        for (int i = 0; i < 12; i++) r.gripError = Math.max(r.gripError, Math.abs(actualGrip[i] - handToProp[i]));
        r.poseError = 0;
        for (int i = 0; i < 12; i++) r.poseError = Math.max(r.poseError, Math.abs(actual[i] - prop[i]));
        r.prop = prop; r.propBone = propBone; r.sampled = time; r.appliedAction = r.action; r.appliedToken = r.token;
        r.frame = FpRecoil.worldFrame(); r.placed = true; applied++;
    }

    static double[] attach(double[] solvedHand, double[] sourceHand, double[] sourceProp) {
        return GripIK.mulNew(solvedHand, GripIK.mulNew(GripIK.inverse(sourceHand), sourceProp));
    }

    private static double[] worldPoint(Request r) {
        double[] point = GripIK.point(r.prop, r.source.emitPoint()[0], r.source.emitPoint()[1], r.source.emitPoint()[2]);
        Vector3 value = new Vector3((float)point[0], (float)point[1], (float)point[2]);
        Model.vectorToWorldCoords(r.player.getX(), r.player.getY(), r.player.getZ(), r.player.getAnimationPlayer().getRenderedAngle(), value);
        return new double[]{value.x, value.y, value.z};
    }

    /** Main-thread diagnostic pairs the exact applied event key with the public T1
     * origin, using the same scaled model bounds centre; no invented spawn offset.
     */
    public static KahluaTable snapshot(IsoPlayer p) {
        Request r = requests.get(p);
        if (r == null || failed) return null;
        try {
            KahluaTable out = LuaManager.platform.newTable();
            boolean fresh = r.placed && FpPose.freshFrame(r.frame, FpRecoil.worldFrame())
                    && r.token == r.appliedToken && r.action.equals(r.appliedAction);
            out.rawset("applied", fresh); out.rawset("action", r.appliedAction);
            out.rawset("time", r.sampled); out.rawset("requested", r.time);
            out.rawset("release", r.track.release); out.rawset("seconds", r.track.seconds);
            out.rawset("token", r.appliedToken); out.rawset("frame", (double)r.frame);
            out.rawset("clip", r.track.clip); out.rawset("handMissCm", r.missCm);
            out.rawset("poseError", r.poseError); out.rawset("released", r.released);
            out.rawset("gripError", r.gripError); out.rawset("propBone", (double)r.propBone); out.rawset("view", (double)r.view);
            if (r.hand != null) {
                Vector3 hand = new Vector3((float)r.hand[3], (float)r.hand[7], (float)r.hand[11]);
                Model.vectorToWorldCoords(p.getX(), p.getY(), p.getZ(), p.getAnimationPlayer().getRenderedAngle(), hand);
                out.rawset("handX", (double)hand.x); out.rawset("handY", (double)hand.y); out.rawset("handZ", (double)hand.z);
                if (r.prop != null && r.source != null) {
                    double[] pt = r.source.emitPoint();
                    double[] emit = GripIK.point(r.prop, pt[0], pt[1], pt[2]);
                    out.rawset("propDistCm", GripIK.dist(GripIK.pos(r.hand), emit) / GripIK.CM);
                }
            }
            if (fresh && r.prop != null && p.getPrimaryHandItem() == r.item) {
                double[] point = r.source.emitPoint();
                KahluaTable actual = HandGrenadeNative.throwPose(p, point[0], point[1], point[2]);
                if (actual != null) {
                    double[] expected = worldPoint(r);
                    for (String key : new String[]{"ox", "oy", "oz", "dx", "dy", "dz"}) out.rawset(key, actual.rawget(key));
                    out.rawset("ex", expected[0]); out.rawset("ey", expected[1]); out.rawset("ez", expected[2]);
                    boolean release = r.gate && r.track.release >= 0 && r.time >= r.track.release
                            && Math.abs(r.sampled - r.track.release) < 1e-10 && r.poseError < 2e-5 && r.gripError < 2e-5;
                    out.rawset("releaseReady", release);
                }
            }
            return out;
        } catch (Throwable failure) { stop(failure); return null; }
    }

    /** T1 accepted/submitted the one public throw. Inventory and fuse remain T1's;
     * hide only the local held mesh while its network acknowledgement is pending.
     */
    public static boolean released(IsoPlayer p, double token) {
        Request r = requests.get(p);
        if (r == null || r.token != token || r.released) return false;
        r.released = true; r.gate = false;
        r.wasHidden = p.isHideWeaponModel(); r.hidden = true; p.setHideWeaponModel(true);
        return true;
    }

    public static void clear(IsoPlayer p) {
        Request r = requests.remove(p);
        if (r != null && r.hidden) { r.hidden = false; p.setHideWeaponModel(r.wasHidden); }
    }

    public static void reset() {
        for (IsoPlayer p : requests.keySet()) clear(p);
    }

    /** The original T1 item has zero native damage and its own public throw API.
     * While this FP request owns input, prevent a parallel vanilla Throw swing.
     * Remote players and every other item/view retain AttemptAttack unchanged.
     */
    public static boolean ownsAttack(Object character) {
        if (failed || !(character instanceof IsoPlayer p)) return false;
        try {
            Request r = requests.get(p);
            return r != null && p.isLocalPlayer() && !p.isDead() && p.getPrimaryHandItem() == r.item
                    && FpPose.freshFrame(r.stamp, FpRecoil.worldFrame());
        } catch (Throwable failure) { stop(failure); return false; }
    }

    @Patch(className="zombie.characters.IsoLivingCharacter", methodName="AttemptAttack", strictMatch=true)
    public static final class NativeAttack {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.This Object character, @Patch.Argument(0) float charge) {
            return GrenadeThrowPose.ownsAttack(character);
        }
    }

    private static void stop(Throwable failure) {
        if (!failed) {
            failed = true; errors++; status = failure.toString();
            try { reset(); } catch (Throwable ignored) { }
            System.out.println("[TW-GRENADE-THROW] pose off: " + status);
        }
    }

    public static String info() { return "status=" + status + " applied=" + applied + " errors=" + errors; }
}
