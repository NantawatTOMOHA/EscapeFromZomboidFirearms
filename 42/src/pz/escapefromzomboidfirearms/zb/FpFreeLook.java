package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Member;
import java.util.Locale;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;

/**
 * Viewpoint's free look in the first-person pose layer (bug wave 324, the user's item 2, 24.10.2026: "Viewpoint's free
 * look key does not work with our mod, the free look does not turn on" - tried on V, NUMPAD1 and MOUSE5).
 *
 * Viewpoint's FreeLook (keys.freeLook held, default MOUSE4; first person on foot, the mouse captured, no free camera, not
 * aiming) keeps the body at Look.yaw of the press (its anchor) and turns only the view, in first person at most
 * camera.freeLookAngle each way; let go, it turns the view back to the anchor (Look.turnTo, 0.08 s). Nothing of ours
 * blocked it, but the pose layer built the weapon's frame from the camera (Look.yaw/pitch, FpPose.eye), the upper body
 * turned to that weapon (FpPoseReach.turn) and the drawing latch carried the body to the drawn look: weapon, arms and chest
 * followed the view, and with our weapon the free look looked like an ordinary turn.
 *
 * As in Tarkov the weapon now stays where it pointed while only the head turns: with the key held the frame is the
 * anchor's yaw and the pitch of the press (eased there from the frame before, no jump), its eye the camera's place at that
 * yaw (ViewpointBridge.Eye.at: Camera.eye's level circle); recoil, sway, the ADS layer and the reach corrections are laid
 * on that frame as on the camera's. Let go, an axis whose gap to the view closes (Viewpoint turning the view back) waits,
 * any other follows the view by TAU_S at most RATE (3 degrees a 60 Hz frame), and within END on both axes the frame is
 * the camera's again (FpPose's former path, bit for bit). FpViewLatch carries poses of a free look or its way back by
 * the eye's walk only, never by the view's turn; the hip shot takes the drawn weapon frame (ShotOwner,
 * boreSource=drawn-weapon-frame), so it flies along the weapon. Third person, the isometric view and other characters
 * are not touched (the layer runs only in first person).
 *
 * QA only (debug): qaHold renews Viewpoint's physical state of one key (KeyInput.physicallyDown, the read of its free
 * look) for at most QA_TTL_NS - Viewpoint's own FreeLook then runs its gate and anchor as for the user's key.
 */
public final class FpFreeLook {
    /** The QA advice's target, checked once with its exact type and static flag before the QA hold is admitted. */
    static final String[][] MEMBERS = {
        {"viewpoint.platform.KeyInput", "physicallyDown", "(I)Z", "static"},
    };
    static final int CAMERA = 0, HELD = 1, BACK = 2;
    static final String[] MODES = {"camera", "held", "back"};
    /** The way back follows the view with Viewpoint's own turn-back time constant (Look.TURN_TAU)... */
    static final double TAU_S = 0.08;
    /** ...at most this fast (3 degrees a 60 Hz frame: no jump), radians per second. */
    static final double RATE = Math.toRadians(180);
    /** Within this of the view on both axes the frame is the camera's again (the last step, radians). */
    static final double END = Math.toRadians(0.1);
    /** An axis's gap to the view smaller than the update before by more than this: the view comes back, the frame waits. */
    static final double CLOSING = Math.toRadians(0.01);
    /** An update's time counts at most this much (a stall's next update moves the frame no more than a 20 Hz one). */
    static final double MAX_DT = 0.05;
    /**
     * No update for this long (the layer off, another view, a menu): the next one starts afresh - at the anchor while the
     * free look is held, else at the camera. QA 24.10.2026 (freelook-1): at 0.5 s a 4K screenshot's stall (~1 s) reset a
     * held free look, the frame restarted at the view and swung back to the body over 0.4 s; a stall now keeps the state.
     */
    static final long RESET_NS = 5_000_000_000L;
    public static final long QA_TTL_NS = 250_000_000L;

    /** QA (A/B in one run): false - the weapon's frame is the camera's during a free look, as before. */
    public static volatile boolean enabled = true;
    public static volatile long updates, heldUpdates, backUpdates, presses, releases, ends, resets;
    /** The frame's largest turn within one update outside the camera's frame (degrees; continuity of the way back). */
    public static volatile double maxStepDeg;

    /** One request's free look: the weapon frame's look (radians), the mode, the pitch kept, the gaps of the update before. */
    static final class State {
        int mode = CAMERA;
        boolean seen;
        double yaw, pitch, holdPitch, gapYaw, gapPitch, stepDeg;
        long at;
    }

    private FpFreeLook() {
    }

    static double wrap(double a) {
        return Math.IEEEremainder(a, 2 * Math.PI);
    }

    /**
     * One update (pure; offline checks drive it): anchor - Viewpoint's free-look yaw (NaN released), the camera's yaw
     * and pitch, the seconds since the update before. Afterwards the state's yaw and pitch are the weapon frame's look;
     * in CAMERA exactly the camera's.
     */
    static void step(State s, double anchor, double camYaw, double camPitch, double dt) {
        boolean down = Double.isFinite(anchor);
        dt = Double.isFinite(dt) ? Math.max(0, Math.min(MAX_DT, dt)) : 0;
        if (!s.seen) {
            // the first update (a new request, after a reset): the weapon where Viewpoint keeps the body
            s.yaw = down ? anchor : camYaw;
            s.pitch = camPitch;
            s.seen = true;
        }
        double y0 = s.yaw, p0 = s.pitch;
        if (down && s.mode != HELD) {
            // a press (again on the way back too): the frame stays with the body, at the pitch it has
            s.mode = HELD;
            s.holdPitch = s.pitch;
            presses++;
        } else if (!down && s.mode == HELD) {
            // let go: Viewpoint turns the view back to the anchor (when its gate still holds); the first update waits
            s.mode = BACK;
            s.gapYaw = s.gapPitch = Double.POSITIVE_INFINITY;
            releases++;
        }
        if (s.mode == CAMERA) {
            s.yaw = camYaw;
            s.pitch = camPitch;
            s.stepDeg = 0;
            return;
        }
        boolean back = s.mode == BACK;
        double gy = wrap((back ? camYaw : anchor) - s.yaw), gp = (back ? camPitch : s.holdPitch) - s.pitch;
        boolean waitYaw = back && Math.abs(gy) < Math.abs(s.gapYaw) - CLOSING, waitPitch = back && Math.abs(gp) < Math.abs(s.gapPitch) - CLOSING;
        double k = dt <= 0 ? 0 : 1 - Math.exp(-dt / TAU_S);
        double my = waitYaw ? 0 : gy * k, mp = waitPitch ? 0 : gp * k, n = Math.hypot(my, mp), cap = RATE * dt;
        if (n > cap && n > 0) {
            my *= cap / n;
            mp *= cap / n;
        }
        s.yaw = wrap(s.yaw + my);
        s.pitch += mp;
        if (back) {
            s.gapYaw = wrap(camYaw - s.yaw);
            s.gapPitch = camPitch - s.pitch;
            if (Math.abs(s.gapYaw) < END && Math.abs(s.gapPitch) < END) {
                s.mode = CAMERA;
                s.yaw = camYaw;
                s.pitch = camPitch;
                ends++;
            }
        }
        s.stepDeg = Math.toDegrees(Math.hypot(wrap(s.yaw - y0), s.pitch - p0));
        maxStepDeg = Math.max(maxStepDeg, s.stepDeg);
    }

    /**
     * FpPose.eye, once per animation update of a request: the weapon frame's {yaw, pitch} for Viewpoint's eye e, or null
     * when it is the camera's (the former path unchanged).
     */
    static double[] look(FpPose.Request r, ViewpointBridge.Eye e, long now) {
        State s = r.freeLook;
        if (s.at != 0 && now - s.at > RESET_NS) {
            s.mode = CAMERA;
            s.seen = false;
            resets++;
        }
        double dt = s.at == 0 || !s.seen ? 0 : (now - s.at) / 1e9;
        s.at = now;
        step(s, enabled ? e.freeYaw : Double.NaN, e.yaw, e.pitch, dt);
        updates++;
        if (s.mode == HELD) {
            heldUpdates++;
        } else if (s.mode == BACK) {
            backUpdates++;
        }
        return s.mode == CAMERA ? null : new double[] {s.yaw, s.pitch};
    }

    /** FpPose.Request.eye[7] - the free look's mode of that pose (CAMERA: the camera's frame; absent in older arrays). */
    static boolean free(double[] eye) {
        return eye != null && eye.length > 7 && eye[7] != CAMERA;
    }

    // ------------------------------------------------------------------ QA

    public record Qa(int code, boolean held, long until) { }

    public static volatile Qa qa;
    private static volatile int abi; // 0 unchecked, 1 the advice target as checked, -1 changed or missing
    public static volatile long qaReads;

    /** QA (debug only): Viewpoint reads this key as physically down (held) or up for at most QA_TTL_NS; renew every tick. */
    public static boolean qaHold(int code, boolean held) {
        return qaHold(code, held, QA_TTL_NS);
    }

    /** The same for ttlNs (at most QA_MAX_TTL_NS: a QA screenshot stalls the game up to a second before the next renewal). */
    public static boolean qaHold(int code, boolean held, long ttlNs) {
        if (!Core.debug || !qaReady() || code <= 0) {
            qa = null;
            return false;
        }
        qa = new Qa(code, held, System.nanoTime() + Math.max(0, Math.min(QA_MAX_TTL_NS, ttlNs)));
        return true;
    }

    public static final long QA_MAX_TTL_NS = 5_000_000_000L;

    public static void qaReset() {
        qa = null;
    }

    static boolean qaReady() {
        if (abi == 0) {
            Member m = ViewpointBridge.ready() || ViewpointBridge.detect() ? ViewpointBridge.resolve(MEMBERS[0], FpFreeLook.class.getClassLoader()) : null;
            abi = m == null ? -1 : 1;
            if (m == null) {
                System.out.println("[TWZB] free look QA: viewpoint.platform.KeyInput.physicallyDown (I)Z static missing or changed, QA hold off");
            }
        }
        return abi == 1;
    }

    /** The advice's answer (render or main thread, every key Viewpoint asks about): the original unless a live QA hold names it. */
    public static boolean physical(int code, boolean original) {
        Qa q = qa;
        if (q == null || q.code != code) {
            return original;
        }
        if (!Core.debug || System.nanoTime() > q.until) {
            qa = null;
            return original;
        }
        qaReads++;
        return q.held;
    }

    /**
     * QA: "view= captured= anchorDeg= cameraDeg= cameraPitchDeg= bodyDeg= aiming=" and, with the pose layer's request,
     * "mode= weaponDeg= weaponPitchDeg= poseCameraDeg= fpMode= boreDeg= borePitchDeg=" (the drawn weapon bone's bore, as the
     * shot owner takes it: WeaponFrame's model Y), then the counters. Degrees, Viewpoint's yaw (0 along +x, 90 along +y).
     */
    public static String describe(IsoGameCharacter c) {
        StringBuilder s = new StringBuilder(400);
        java.util.Formatter f = new java.util.Formatter(s, Locale.ROOT);
        Double yaw = ViewpointBridge.lookYaw(), pitch = ViewpointBridge.lookPitch();
        f.format("view=%d captured=%s anchorDeg=%.3f cameraDeg=%.3f cameraPitchDeg=%.3f", ViewpointBridge.state(), ViewpointBridge.lookCaptured(),
                Math.toDegrees(ViewpointBridge.freeLookAnchor()), yaw == null ? Double.NaN : Math.toDegrees(yaw),
                pitch == null ? Double.NaN : Math.toDegrees(pitch));
        if (c != null) {
            f.format(" bodyDeg=%.3f aiming=%b", Math.toDegrees(Math.atan2(c.getForwardDirectionY(), c.getForwardDirectionX())), c.isAiming());
            FpPose.Request r = FpPose.requests.get(c);
            double[] e = r == null ? null : r.eye;
            if (e != null && e.length > 7) {
                f.format(" mode=%s weaponDeg=%.3f weaponPitchDeg=%.3f poseCameraDeg=%.3f stepDeg=%.3f", MODES[(int) e[7]], Math.toDegrees(e[3]),
                        Math.toDegrees(e[4]), Math.toDegrees(e[5]), r.freeLook.stepDeg);
            }
            if (r != null) {
                f.format(" fpMode=%s", r.mode.replace(' ', '_'));
            }
            if (r != null && c.getPrimaryHandItem() == r.item && WeaponFrame.world(c)) {
                double[] w = WeaponFrame.F;
                f.format(" boreDeg=%.3f borePitchDeg=%.3f", Math.toDegrees(Math.atan2(w[7], w[6])),
                        Math.toDegrees(Math.atan2(w[8] * ViewpointBridge.LEVEL, Math.hypot(w[6], w[7]))));
            }
        }
        f.format(" %s", stats());
        return s.toString();
    }

    public static String stats() {
        return String.format(Locale.ROOT, "enabled=%b updates=%d held=%d back=%d presses=%d releases=%d ends=%d resets=%d maxStepDeg=%.3f qaReads=%d qa=%s",
                enabled, updates, heldUpdates, backUpdates, presses, releases, ends, resets, maxStepDeg, qaReads, qa == null ? "none" : qa.code + ":" + qa.held);
    }

    public static void reset() {
        updates = heldUpdates = backUpdates = presses = releases = ends = resets = qaReads = 0;
        maxStepDeg = 0;
    }

    /** QA only: answers Viewpoint's physical key read (its free look's) while a debug QA hold is renewed; otherwise the original. */
    @Patch(className = "viewpoint.platform.KeyInput", methodName = "physicallyDown")
    public static class Physical {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) int code, @Patch.Return(readOnly = false) boolean result) {
            result = FpFreeLook.physical(code, result);
        }
    }
}
