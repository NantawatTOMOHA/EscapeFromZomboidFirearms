package pz.escapefromzomboidfirearms.zb;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;

/**
 * Tarkov's main ADS camera FOV, over the optional Viewpoint API. Source: local vanilla
 * ProceduralWeaponAnimation (U+E017) and camera manager U+F124, client 0.16.9.5.40743:
 * irons/collimators use user vFOV minus 15 degrees; an OpticSight uses 35 degrees,
 * independently of its secondary camera's magnification (including optical 1x).
 * No Viewpoint code or calibration is used. The existing pose owns eye/sight alignment
 * and the raise/lower clock; this layer reads its ADS progress, never changes that pose.
 * Returning the adapted FOV before projection also keeps the main scene, glass and PIP
 * ocular projection together. The secondary scope camera retains its source FOV.
 * The user's share of Tarkov's narrowing (FB2 item 9, 17.10.2026: "strongly reduce" it; at
 * Viewpoint's vFOV 90 Tarkov's optic 35 degrees was a 55 degree squeeze) scales the delta:
 * 0 keeps the user FOV, 1 is Tarkov's own camera. While the pose aims, Viewpoint's crosshair
 * dot is hidden (FB2 item 3, FpViewLatch.Crosshair). FB3 (user's item 3, 18.10.2026: "make the
 * narrowing configurable"): irons/collimators and optical sights have separate shares.
 */
public final class FpAdsCamera {
    static final double IRON_DELTA = 50, IRON_MIN_FOV = 35, OPTIC_FOV = 35;
    /** Default share of ADS narrowing: 30 % for irons/collimators (0.30 x 50 = 15 deg, W11 item 16) and 25 % for optics (FB3 item 3). */
    static final double NARROWING = 0.25, NARROWING_IRON = 0.30;
    static final long STALE_NS = 500_000_000L;
    public static volatile boolean enabled = true;
    /** 0..1 of the 50 deg narrowing for irons/collimators (floored at 35 deg); the mod option AdsNarrowIron sets it (TW_AdsCamera.lua). */
    public static volatile double narrowing = NARROWING_IRON;
    /** 0..1 of Tarkov's narrowing for an OpticSight; the mod option AdsNarrowOptic sets it (TW_AdsCamera.lua). */
    public static volatile double narrowingOptic = NARROWING;
    /** Hide Viewpoint's crosshair while aiming down sights (mod option AdsHideCrosshair). */
    public static volatile boolean hideCrosshair = true;
    public static volatile long calls, changed, errors;
    public static volatile String lastError = "";

    private FpAdsCamera() { }

    /** Full (100 %) ADS FOV for the sight channel: 35 deg for optics, max(35, base - 50) for irons/collimators, never wider than base. */
    static double tarkov(double base, boolean optical) {
        return Math.min(base, optical ? OPTIC_FOV : Math.max(IRON_MIN_FOV, base - IRON_DELTA));
    }

    /** The ADS FOV shown: the user's share of the narrowing from the base (per sight kind). */
    static double target(double base, boolean optical) {
        double k = optical ? narrowingOptic : narrowing;
        double def = optical ? NARROWING : NARROWING_IRON;
        k = Double.isFinite(k) ? Math.max(0, Math.min(1, k)) : def;
        if (optical) return base - k * (base - tarkov(base, true));
        return Math.max(Math.min(base, IRON_MIN_FOV), base - k * IRON_DELTA);
    }

    /**
     * Whether the local pose aims down sights now (the trigger to aim, or any visible ADS
     * weight): Viewpoint's crosshair dot is hidden then. Any thread; reads published state only.
     */
    public static boolean aiming(IsoGameCharacter c) {
        return hideCrosshair && enabled && adsState(c) > 0;
    }

    /**
     * The local pose's ADS now, independent of the options: 0 outside, 2 when the trigger to aim is held,
     * else 1 while any ADS weight is visible (FpViewLatch keeps Viewpoint's near plane while it is above 0).
     */
    static int adsState(IsoGameCharacter c) {
        if (c == null) {
            return 0;
        }
        Player p = players.get(c);
        FpPose.Request r = FpPose.requests.get(c);
        long now = System.nanoTime();
        if (p == null || r == null || !FpPose.enabled || !r.allowed || r.spent || c.getPrimaryHandItem() != p.item
                || now - p.stamp > STALE_NS || r.at == 0 || now - r.at > STALE_NS || r.fpW <= 1e-4) {
            return 0;
        }
        return r.aiming ? 2 : weight(r.ads, Math.max(r.obstacle, r.highW), r.pullUntil, r.fpW) > 0.01 ? 1 : 0;
    }

    static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /** The pose's actual ADS visibility, including its obstacle/high-ready suppression and body/action fade. */
    static double weight(double ads, double obstacle, double pullUntil, double fp) {
        double fade = obstacle <= 0 ? 0 : pullUntil > 1e-6 ? smooth(obstacle / pullUntil) : 1;
        return smooth(ads) * (1 - fade) * Math.max(0, Math.min(1, fp));
    }

    /**
     * Pure channel transition. ADS progress comes from the pose; changing optic/iron or user
     * settings retargets from the value being shown, including during a preceding transition.
     * No weapon/channel identity resets the curve. Repeated calls at one pose timestamp
     * advance it by zero; a pause or a gap over 0.25 s does not fast-forward it.
     */
    static final class Track {
        double clock, from, to, at, duration;
        boolean started;

        double value() {
            double u = duration > 0 ? smooth((clock - at) / duration) : 1;
            return from + (to - from) * u;
        }

        double sample(double base, boolean optical, double weight, double seconds, double dt) {
            if (Double.isFinite(dt) && dt >= 0 && dt <= 0.25) {
                clock += dt;
            }
            double desired = base - target(base, optical), current = value();
            if (!started) {
                started = true;
                from = to = desired;
            } else if (desired != to) {
                from = current;
                to = desired;
                at = clock;
                duration = Math.max(0.02, seconds);
            }
            return base - Math.max(0, Math.min(1, weight)) * value();
        }
    }

    static final class Player {
        volatile Object item;
        volatile boolean optical, paused;
        volatile long stamp;
        final Track track = new Track();
        long poseAt;
        double base, fov, ads, weight, seconds;
    }

    static final ConcurrentHashMap<IsoGameCharacter, Player> players = new ConcurrentHashMap<>();

    /** Every tick from our own sight selector. No game setting, item modData or network state is written. */
    static void state(IsoGameCharacter c, Object item, boolean optical, boolean on, boolean paused) {
        if (!(c instanceof IsoPlayer p) || !p.isLocalPlayer() || p.isDead() || item == null || !on
                || c.getPrimaryHandItem() != item) {
            off(c);
            return;
        }
        Player s = players.computeIfAbsent(c, k -> new Player());
        s.item = item;
        s.optical = optical;
        s.paused = paused;
        s.stamp = System.nanoTime();
    }

    static void off(IsoGameCharacter c) {
        if (c != null) {
            players.remove(c);
        }
    }

    /** Viewpoint's untouched return is the user setting, in radians; never feed our previous result back as the base. */
    public static float fov(boolean thirdPerson, float original) {
        if (thirdPerson || !enabled || players.isEmpty()) {
            return original;
        }
        if (!ViewpointBridge.ready() && !ViewpointBridge.detect() || ViewpointBridge.state() != 1) {
            return original;
        }
        try {
            ScopePipZoom.begin();
            return forPlayer(IsoCamera.frameState.camCharacter, original, System.nanoTime());
        } catch (Throwable t) {
            errors++;
            lastError = t.toString();
            ViewpointBridge.fail("ADS camera", t);
            return original;
        }
    }

    static float forPlayer(IsoGameCharacter c, float original, long now) {
        Player p = c == null ? null : players.get(c);
        FpPose.Request r = c == null ? null : FpPose.requests.get(c);
        if (p == null || r == null || !FpPose.enabled || !r.allowed || r.spent || c.isDead() || c.getVehicle() != null
                || c.getPrimaryHandItem() != p.item || r.item != p.item || now - p.stamp > STALE_NS
                || r.at == 0 || now - r.at > STALE_NS || !r.eyeValid || r.fpW <= 1e-4) {
            return original;
        }
        if (!Float.isFinite(original) || original <= 0 || original >= Math.PI) {
            throw new IllegalArgumentException("View.fovY must return a finite angle in radians");
        }
        FpPoseData d = FpPoseData.get();
        if (d == null) {
            return original;
        }
        synchronized (p) {
            double seconds = FpRecoil.adsSeconds(c, r, FpPose.adsSeconds(d, r.ergonomics, r.weight)) * FpPose.adsTimeScale;
            double dt = p.poseAt == 0 || p.paused ? 0 : Math.max(0, (r.at - p.poseAt) / 1e9);
            p.poseAt = r.at;
            p.base = Math.toDegrees(original);
            p.ads = r.ads;
            p.weight = weight(r.ads, Math.max(r.obstacle, r.highW), r.pullUntil, r.fpW);
            p.seconds = seconds;
            p.fov = p.track.sample(p.base, p.optical, p.weight, seconds, dt);
            calls++;
            // W10 item 11: the camera-zoom picture of a magnifying optic narrows on to the sight's magnification at the
            // end of the raise (ScopePipZoom; the aimed FOV itself, exactly, while nothing is zoomed).
            double shown = ScopePipZoom.fov(c, p.fov, p.weight, dt);
            // Keep the exact original bit pattern outside ADS, including while a channel transition is running.
            if (p.weight <= 0) {
                return original;
            }
            float result = (float) Math.toRadians(shown);
            if (result != original) {
                changed++;
            }
            return result;
        }
    }

    /**
     * The main camera's vertical FOV at full ADS for this player, radians (ScopePip.finalSize); NaN when this layer
     * does not narrow it (off, or no sight state): the frame's own FOV stays then.
     */
    static double finalFov(IsoGameCharacter c) {
        Player p = c == null || !enabled ? null : players.get(c);
        if (p == null) {
            return Double.NaN;
        }
        synchronized (p) {
            return p.base > 0 && p.base < 180 ? Math.toRadians(target(p.base, p.optical)) : Double.NaN;
        }
    }

    public static String stats() {
        return "enabled=" + enabled + " narrowing=" + narrowing + " narrowingOptic=" + narrowingOptic + " hideCrosshair=" + hideCrosshair + " calls=" + calls
                + " changed=" + changed + " players=" + players.size()
                + " errors=" + errors + (lastError.isEmpty() ? "" : " last=" + lastError);
    }

    static String describe(IsoGameCharacter c) {
        Player p = c == null ? null : players.get(c);
        if (p == null) {
            return null;
        }
        synchronized (p) {
            return String.format(Locale.ROOT, "optical=%b paused=%b baseDeg=%.5f fovDeg=%.5f targetDeg=%.5f tarkovDeg=%.5f target50=%.2f target75=%.2f target90=%.2f narrowing=%.3f ads=%.5f weight=%.5f adsSeconds=%.5f",
                    p.optical, p.paused, p.base, p.fov, target(p.base, p.optical), tarkov(p.base, p.optical),
                    target(50, p.optical), target(75, p.optical), target(90, p.optical),
                    p.optical ? narrowingOptic : narrowing, p.ads, p.weight, p.seconds);
        }
    }

    /** Exact checked signature in ViewpointBridge.MEMBERS; changed or missing API disables the bridge. */
    @Patch(className = "viewpoint.core.View", methodName = "fovY")
    public static class Fov {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) boolean thirdPerson, @Patch.Return(readOnly = false) float result) {
            result = FpAdsCamera.fov(thirdPerson, result);
        }
    }
}
