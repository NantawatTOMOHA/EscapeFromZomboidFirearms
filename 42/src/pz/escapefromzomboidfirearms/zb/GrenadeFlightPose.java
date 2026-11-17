package pz.escapefromzomboidfirearms.zb;

import java.util.WeakHashMap;
import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Patch;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.inventory.types.HandWeapon;

/** Final animated launcher frame, captured on the main thread after drawing.
 * No Viewpoint code or live render objects cross the Lua/network boundary.
 */
@Exposer.LuaClass
public final class GrenadeFlightPose {
    private static final WeakHashMap<IsoPlayer, Request> requests = new WeakHashMap<>();
    private static String reason = "not-drawn";
    private static boolean failed;
    private static final long MAX_AGE_NS = 250_000_000L;

    private static final class Request {
        HandWeapon gun;
        String signature, channel;
        double[] muzzle, bore;
        Drawn drawn;
        String capture = "awaiting-draw";
    }

    /** Animation may advance before the next draw. Keep the published pose independent of it. */
    private static final class Drawn {
        final double[] snapshot;
        final long time, worldFrame, generation, frameNumber;
        final int view;
        final Object frame, scene;

        Drawn(double[] snapshot, long time, long worldFrame, long generation, int view, Object frame) throws IllegalAccessException {
            this.snapshot = snapshot.clone(); this.time = time; this.worldFrame = worldFrame;
            this.generation = generation; this.view = view; this.frame = frame;
            frameNumber = ViewpointBridge.frameNumber(frame); scene = ViewpointBridge.sceneOf(frame);
        }
    }

    private GrenadeFlightPose() { }

    public static void set(IsoPlayer player, HandWeapon gun, KahluaTable geometry) {
        if (player == null) return;
        if (gun == null || geometry == null) { requests.remove(player); return; }
        try {
            String signature = (String) geometry.rawget("signature");
            if (signature == null || signature.length() > 16384) throw new IllegalArgumentException("geometry identity");
            Request old = requests.get(player);
            if (old != null && old.gun == gun && old.signature.equals(signature)) return;
            Request r = new Request(); r.gun = gun; r.signature = signature;
            r.channel = String.valueOf(geometry.rawget("channel"));
            r.muzzle = vector(geometry, "muzzle"); r.bore = vector(geometry, "bore");
            requests.put(player, r);
        } catch (Throwable t) { requests.remove(player); reason = "invalid-geometry"; }
    }

    private static double[] vector(KahluaTable table, String key) {
        KahluaTable v = (KahluaTable) table.rawget(key);
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            out[i] = ((Number) v.rawget(i + 1)).doubleValue();
            if (!Double.isFinite(out[i]) || Math.abs(out[i]) > 8) throw new IllegalArgumentException(key);
        }
        return out;
    }

    /** Public because ZombieBuddy inlines this call into IsoWorld. */
    public static void afterWorld() {
        if (failed) return;
        try {
            if (!ViewpointBridge.ready() || !ViewpointBridge.perspective()) {
                for (Request r : requests.values()) { r.drawn = null; r.capture = "no-perspective"; }
                return;
            }
            Object frame = ViewpointBridge.frameNow();
            int view = ViewpointBridge.state();
            for (var entry : requests.entrySet()) {
                IsoPlayer p = entry.getKey(); Request r = entry.getValue(); r.drawn = null;
                if (frame == null || view == 0) { r.capture = "no-vp-frame"; continue; }
                if (!p.isLocalPlayer() || p.isDead()) { r.capture = "player-ineligible"; continue; }
                if (p.getPrimaryHandItem() != r.gun) { r.capture = "held-item-changed"; continue; }
                if (!WeaponFrame.world(p)) { r.capture = "no-weapon-bone"; continue; }
                long generation = FpPose.shotGeneration(p, r.gun);
                if (view == 1 && FpPose.enabled && generation == 0) { r.capture = "pose-not-applied"; continue; }
                double[] result = project(WeaponFrame.F.clone(), r.muzzle, r.bore);
                r.drawn = new Drawn(result, System.nanoTime(), FpRecoil.worldFrame(), generation, view, frame);
                r.capture = reason = "ready";
            }
        } catch (Throwable t) {
            failed = true; reason = "bridge-error:" + t.getClass().getSimpleName();
            requests.clear(); ViewpointBridge.fail("grenade-pose", t);
        }
    }

    /** Pure transform used by the offline JVM fixture. XYZ directions are metric geographic axes. */
    public static double[] project(double[] world, double[] muzzle, double[] bore) {
        double[] out = new double[6];
        for (int axis = 0; axis < 3; axis++) {
            out[axis] = world[axis];
            for (int column = 0; column < 3; column++) {
                out[axis] += world[3 + column * 3 + axis] * muzzle[column];
                out[axis + 3] += world[3 + column * 3 + axis] * bore[column];
            }
            out[axis + 3] *= axis == 2 ? TarkovBallistics.METRES_PER_LEVEL : TarkovBallistics.METRES_PER_CELL;
        }
        double length = Math.sqrt(out[3]*out[3] + out[4]*out[4] + out[5]*out[5]);
        if (!Double.isFinite(length) || length < 1e-9) throw new IllegalArgumentException("bore");
        for (int i = 0; i < 6; i++) {
            if (i >= 3) out[i] /= length;
            if (!Double.isFinite(out[i])) throw new IllegalArgumentException("frame");
        }
        return out;
    }

    private static String rejection(IsoPlayer p, HandWeapon gun, String signature) throws IllegalAccessException {
        Request r = requests.get(p);
        if (failed) return "bridge-failed";
        if (r == null) return "no-request";
        if (r.gun != gun || p.getPrimaryHandItem() != gun) return "held-item-changed";
        if (!r.signature.equals(signature)) return "channel-or-aim-changed";
        if (p.isDead() || !p.isLocalPlayer()) return "player-ineligible";
        if (!ViewpointBridge.perspective()) return "no-perspective";
        Drawn d = r.drawn;
        if (d == null) return r.capture;
        if (ViewpointBridge.state() != d.view) return "view-changed";
        long age = System.nanoTime() - d.time;
        if (age < 0 || age > MAX_AGE_NS) return "time-stale";
        if (!FpPose.freshFrame(d.worldFrame, FpRecoil.worldFrame())) return "world-frame-stale";
        if (ViewpointBridge.frameNumber(d.frame) != d.frameNumber || ViewpointBridge.sceneOf(d.frame) != d.scene) return "vp-frame-reused";
        // The drawn generation is provenance, not the next animation's generation.
        return "ready";
    }

    public static KahluaTable pose(IsoPlayer p, HandWeapon gun, String signature) {
        try {
            if (!"ready".equals(rejection(p, gun, signature))) return null;
            Drawn d = requests.get(p).drawn;
            KahluaTable t = LuaManager.platform.newTable();
            String[] keys = {"ox", "oy", "oz", "bx", "by", "bz"};
            for (int i = 0; i < keys.length; i++) t.rawset(keys[i], d.snapshot[i]);
            t.rawset("view", (double) d.view); t.rawset("frame", (double) d.frameNumber);
            KahluaTable relief = drawnRelief(d.frame, d.snapshot[0], d.snapshot[1], d.snapshot[3], d.snapshot[4]);
            if (relief != null) t.rawset("relief", relief);
            return t;
        } catch (Throwable t) { failed = true; requests.clear(); ViewpointBridge.fail("grenade-pose-read", t); return null; }
    }

    /**
     * Viewpoint's relief under a projectile launched from PZ point (x, y) along horizontal heading (hx, hy) (W10 item 14,
     * 27.10.2026: launcher rounds and hand grenades flew PZ's flat world while Viewpoint drew the ground and every model
     * lifted by FarRelief$Lift): the lift of the frame's relief relative to the launch point, as the Lua profile
     * TW/FlightRelief reads. Null without a frame or its relief; a failure costs the relief, never the launch.
     */
    static KahluaTable drawnRelief(Object frame, double x, double y, double hx, double hy) {
        try {
            Object relief = frame == null ? null : ViewpointBridge.reliefOf(frame);
            if (relief == null) return null;
            double base = ViewpointBridge.lift(relief, x, y);
            return profile((east, south) -> (ViewpointBridge.lift(relief, x + east / TarkovBallistics.METRES_PER_CELL,
                    y + south / TarkovBallistics.METRES_PER_CELL) - base) * TarkovBallistics.METRES_PER_CELL, hx, hy);
        } catch (Throwable t) {
            if (!reliefFailed) { reliefFailed = true; System.out.println("[TW-GRENADE] relief sampling failed: " + t); }
            return null;
        }
    }
    private static boolean reliefFailed;

    /**
     * The profile {s={mm...}, l={mm...}} of a lift (metres relative to the launch point at a point east, south metres from
     * it) along heading (hx, hy) to 400 m: ShotProtocol.Relief's sampler (8 m steps split to 2 mm, beyond 20 m 0.1 mrad;
     * at most 512 samples), the profile the MP bullet carries. Null for a near-vertical heading or a flat profile. Public
     * for the offline fixture.
     */
    public static KahluaTable profile(java.util.function.DoubleBinaryOperator lift, double hx, double hy) {
        ShotProtocol.Relief relief = ShotProtocol.Relief.sample(lift, new TarkovBallistics.Vec3((float) hx, 0, (float) hy), 0,
                ShotProtocol.Relief.MAX_RANGE_MILLIMETRES / 1000.0);
        int[] millimetres = relief.millimetres(); short[] lifts = relief.lifts();
        boolean flat = true;
        for (short value : lifts) flat &= value == 0;
        if (millimetres.length == 0 || flat) return null;
        KahluaTable s = LuaManager.platform.newTable(), l = LuaManager.platform.newTable(), out = LuaManager.platform.newTable();
        for (int i = 0; i < millimetres.length; i++) { s.rawset((double) i + 1, (double) millimetres[i]); l.rawset((double) i + 1, (double) lifts[i]); }
        out.rawset("s", s); out.rawset("l", l);
        return out;
    }

    /** Read-only QA/fallback detail; every gate is reported, even when an earlier one rejects. */
    public static String describe(IsoPlayer p, HandWeapon gun, String signature) {
        try {
            Request r = requests.get(p); Drawn d = r == null ? null : r.drawn;
            long world = FpRecoil.worldFrame(), now = System.nanoTime();
            return "reason=" + rejection(p, gun, signature) + " capture=" + (r == null ? "none" : r.capture)
                    + " request=" + (r != null) + " snapshot=" + (d != null)
                    + " item=" + (r != null && r.gun == gun && p.getPrimaryHandItem() == gun)
                    + " signature=" + (r != null && r.signature.equals(signature))
                    + " channel=" + (r == null ? "none" : r.channel)
                    + " view=" + ViewpointBridge.state() + "/" + (d == null ? 0 : d.view)
                    + " aiming=" + (p != null && p.isAiming())
                    + " ageMs=" + (d == null ? -1 : (now - d.time) / 1_000_000.0)
                    + " timeFresh=" + (d != null && now >= d.time && now - d.time <= MAX_AGE_NS)
                    + " world=" + world + "/" + (d == null ? -1 : d.worldFrame)
                    + " worldFresh=" + (d != null && FpPose.freshFrame(d.worldFrame, world))
                    + " vp=" + (d == null ? -1 : ViewpointBridge.frameNumber(d.frame)) + "/" + (d == null ? -1 : d.frameNumber)
                    + " vpFresh=" + (d != null && ViewpointBridge.frameNumber(d.frame) == d.frameNumber)
                    + " scene=" + (d != null && ViewpointBridge.sceneOf(d.frame) == d.scene)
                    + " poseEnabled=" + FpPose.enabled + " drawnGeneration=" + (d == null ? 0 : d.generation)
                    + " currentGeneration=" + (p == null ? 0 : FpPose.shotGeneration(p, gun))
                    + " pose={" + (p == null ? "absent" : FpPose.describe(p)) + "}";
        } catch (Throwable t) { return "reason=diagnostic-error:" + t.getClass().getSimpleName(); }
    }

    public static String status() { return "failed=" + failed + " reason=" + reason + " requests=" + requests.size(); }

    @Patch(className="zombie.iso.IsoWorld", methodName="render", strictMatch=true)
    public static class DrawnFrame {
        @Patch.OnExit
        public static void exit() { GrenadeFlightPose.afterWorld(); }
    }
}
