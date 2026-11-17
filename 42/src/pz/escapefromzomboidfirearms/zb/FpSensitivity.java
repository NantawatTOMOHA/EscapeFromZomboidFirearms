package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;

/**
 * Tarkov's mouse sensitivity for Viewpoint's first person (W10 user item 9, 27.10.2026: "add sensitivity
 * options and a separate sensitivity in the sight depending on magnification"). The Lua side owns every
 * number (TW/Sensitivity.lua: Tarkov's formula from the extracted AimSensitivity data, TW_Sensitivity.lua:
 * the three mod options) and hands this class the one multiplier the look takes now; here it is applied to
 * Viewpoint's own mouse delta.
 *
 * Where the delta is made (installed Viewpoint.jar, javap -p -c viewpoint.input.Look, `read`):
 * {@code yaw = wrap(yaw + dx * 0.0022 * SPEED.get()); pitch = clamp(pitch - dy * 0.0022 * SPEED.get())}
 * where SPEED is Viewpoint's own "Controls/Mouse/Sensitivity" live setting (mouse.sensitivity, 0.1..4,
 * default 1, "1 is the mod's own speed"), read once per mouse read and nowhere else in the first-person
 * path: Look.zoom is 1 there and Look.aimScale only reaches the game pad, so today a mouse turn is the same
 * at the hip and through a 4x sight. Scaling that one getter is exactly Tarkov's rotation multiplier and
 * nothing else - the pad, the turn-back easing, free look and the third person are untouched, and the
 * user's Viewpoint setting is never written (its getter's answer is scaled, the stored value is not). With
 * the factor exactly 1 the answer is the original value, bit for bit: the defaults at the hip are today's
 * look.
 *
 * One state row per local player (the look itself is global, as Viewpoint's is): the view is the local
 * player's, so only that row is ever read (IsoCamera.frameState.camCharacter, or the only row when the
 * camera character is not set). A row older than STALE_NS is ignored: a weapon drop, death, a menu or a tick
 * that stopped leave today's look. No game setting, item modData, save field or network state is written,
 * and nothing is sent: in multiplayer every client scales only its own look.
 *
 * Its own member table (as FpAimRing/FpFreeLook): a changed setting or getter turns only this layer off,
 * with one log line, and the bridge stays on.
 */
public final class FpSensitivity {
    static final String[][] MEMBERS = {
        // the advice's own target, checked with its exact signature and static flag before it may scale anything
        {"viewpoint.platform.LiveSettings$Number", "get", "()F", ""},
        // Viewpoint's own mouse-speed setting: the one field its first-person mouse read multiplies by
        {"viewpoint.input.Look", "SPEED", "Lviewpoint/platform/LiveSettings$Number;", "static"},
    };

    /** A row older than this is today's look (a Lua tick runs every frame; a pause or a menu stops it). */
    static final long STALE_NS = 500_000_000L;
    /** The largest factor applied: the Lua sliders are narrower, this only guards a bad number. */
    static final double MAX_FACTOR = 10;

    /** QA (A/B in one run): false - Viewpoint's look is exactly today's. */
    public static volatile boolean enabled = true;
    public static volatile long reads, scaled, errors;
    public static volatile String lastError = "";
    private static volatile boolean ready;
    private static boolean checked;
    private static volatile String status = "unchecked";
    /** Viewpoint's mouse.sensitivity setting object (one instance for the session) and its guarded getter. */
    private static volatile Object speed;
    private static volatile Method getter;

    static final class Player {
        volatile Object item;
        volatile double factor = 1;
        volatile long stamp;
    }

    static final ConcurrentHashMap<IsoGameCharacter, Player> players = new ConcurrentHashMap<>();

    private FpSensitivity() {
    }

    /** Once: the advice target and the setting it scales, with their exact signatures; false keeps today's look. */
    static synchronized boolean detect() {
        if (checked) {
            return ready && ViewpointBridge.ready();
        }
        if (!ViewpointBridge.ready() && !ViewpointBridge.detect()) {
            return false;
        }
        checked = true;
        try {
            Member get = ViewpointBridge.resolve(MEMBERS[0], FpSensitivity.class.getClassLoader());
            Member field = ViewpointBridge.resolve(MEMBERS[1], FpSensitivity.class.getClassLoader());
            Object found = field == null ? null : ((Field) field).get(null);
            if (get == null || found == null || !MEMBERS[0][0].equals(found.getClass().getName())) {
                status = "member " + (get == null ? MEMBERS[0][0] + "." + MEMBERS[0][1] : MEMBERS[1][0] + "." + MEMBERS[1][1])
                        + " missing or changed";
                System.out.println("[TWZB] sensitivity: " + status + ", the sensitivity options are off (bridge stays on)");
                return false;
            }
            getter = (Method) get;
            speed = found;
            ready = true;
            status = "on";
        } catch (Throwable t) {
            status = "lookup failed: " + t;
            System.out.println("[TWZB] sensitivity: " + status + ", the sensitivity options are off");
        }
        return ready;
    }

    /** Every tick from TW_Sensitivity.lua with one of our weapons in hands: the factor the look takes now. */
    static void state(IsoGameCharacter c, Object item, double factor) {
        if (!(c instanceof IsoPlayer p) || !p.isLocalPlayer() || p.isDead() || item == null
                || c.getPrimaryHandItem() != item || !Double.isFinite(factor) || factor <= 0) {
            off(c);
            return;
        }
        Player s = players.computeIfAbsent(c, k -> new Player());
        s.item = item;
        s.factor = Math.min(MAX_FACTOR, factor);
        s.stamp = System.nanoTime();
    }

    static void off(IsoGameCharacter c) {
        if (c != null) {
            players.remove(c);
        }
    }

    /** The multiplier for a player's look now; exactly 1 when this layer is off, stale or has no row. */
    static double factor(IsoGameCharacter c) {
        if (!enabled || players.isEmpty()) {
            return 1;
        }
        Player p = c == null ? null : players.get(c);
        if (p == null && players.size() == 1) {
            p = players.values().iterator().next();
        }
        if (p == null || p.stamp == 0 || System.nanoTime() - p.stamp > STALE_NS) {
            return 1;
        }
        double f = p.factor;
        return Double.isFinite(f) && f > 0 ? Math.min(MAX_FACTOR, f) : 1;
    }

    /**
     * Exit of Viewpoint's LiveSettings number getter (every number setting reads through it): the mouse-speed
     * setting's answer is multiplied by the multiplier of the look now; another setting's answer, and the
     * mouse speed with the factor at exactly 1, is the original value.
     */
    public static float scale(Object setting, float value) {
        if (!enabled || value == 0 || players.isEmpty()) {
            return value;
        }
        if (!ready && !detect()) {
            return value;
        }
        if (setting != speed) {
            return value;
        }
        reads++;
        try {
            if (!ViewpointBridge.ready() || ViewpointBridge.state() != 1) {
                return value;
            }
            IsoGameCharacter cam = IsoCamera.frameState.camCharacter;
            if (cam != null && cam.isDead()) {
                return value;
            }
            double k = factor(cam);
            if (k == 1) {
                return value;
            }
            float result = (float) (value * k);
            if (!Float.isFinite(result) || result == 0) {
                return value;
            }
            scaled++;
            return result;
        } catch (Throwable t) {
            errors++;
            lastError = t.toString();
            return value;
        }
    }

    /** QA: the setting's own answer now (the advice runs on it), NaN without it. */
    static float speedReading() {
        if (!ready && !detect()) {
            return Float.NaN;
        }
        try {
            return (Float) getter.invoke(speed);
        } catch (Throwable t) {
            errors++;
            lastError = t.toString();
            return Float.NaN;
        }
    }

    public static String stats() {
        return String.format(Locale.ROOT, "status=%s enabled=%b players=%d reads=%d scaled=%d errors=%d%s",
                status, enabled, players.size(), reads, scaled, errors, lastError.isEmpty() ? "" : " last=" + lastError);
    }

    static String describe(IsoGameCharacter c) {
        Player p = c == null ? null : players.get(c);
        if (p == null) {
            return "factor=1 rows=" + players.size() + " " + stats();
        }
        long age = System.nanoTime() - p.stamp;
        return String.format(Locale.ROOT, "factor=%.5f applied=%.5f ageMs=%.1f stale=%b %s",
                p.factor, factor(c), age / 1e6, age > STALE_NS, stats());
    }

    public static void reset() {
        reads = scaled = errors = 0;
        lastError = "";
    }

    /** QA: Viewpoint's mouse-speed answer through the advice now (the stored value with the layer off, scaled with it on). */
    public static String probe() {
        return String.format(Locale.ROOT, "reading=%.5f %s", speedReading(), stats());
    }

    /** QA: Viewpoint's look now - yaw and pitch in radians, and whether the mouse is captured. */
    public static String look() {
        if (!ViewpointBridge.ready() && !ViewpointBridge.detect()) {
            return "yaw=nan pitch=nan captured=false";
        }
        Double yaw = ViewpointBridge.lookYaw(), pitch = ViewpointBridge.lookPitch();
        Boolean captured = ViewpointBridge.lookCaptured();
        return String.format(Locale.ROOT, "yaw=%.9f pitch=%.9f captured=%b",
                yaw == null ? Double.NaN : yaw, pitch == null ? Double.NaN : pitch, Boolean.TRUE.equals(captured));
    }

    /** Viewpoint's LiveSettings number getter: the mouse-speed answer takes the look's multiplier now. */
    @Patch(className = "viewpoint.platform.LiveSettings$Number", methodName = "get")
    public static class NumberGet {
        @Patch.OnExit
        public static void exit(@Patch.This Object setting, @Patch.Return(readOnly = false) float result) {
            result = FpSensitivity.scale(setting, result);
        }
    }
}
