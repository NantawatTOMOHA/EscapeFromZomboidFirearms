package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Member;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Viewpoint's own flashlight along the bore of our weapon (FB4-A, user report 19.10.2026, item 20: in
 * Viewpoint the flashlight follows the weapon itself, not the view). Viewpoint lights its scene with the
 * held torch from FlashShadow.aim (render thread; the light's uniforms, its shadow map and its bounce
 * then read the same lens and direction): from the hand - VpLights.flashLens moves that origin to the
 * lens of our weapon light - toward a point Tuning.flashAim ahead of the camera, so a lowered weapon lit
 * where the camera looked. TW/LightRender hands over the bore of the animated weapon in the frame (the
 * first-person pose, recoil and actions included) with the lens; at the exit of aim the direction of that
 * frame is replaced by it. Only a frame whose flashlight is ours at the lens (VpLights.move) carries a
 * direction: a vanilla flashlight, another light in the hands and the isometric view keep Viewpoint's own.
 * While an action hides that weapon (a magazine top-off) the frame's flashlight is off (off, item 14).
 * Its own member table: a changed Viewpoint turns only this redirect off (one log line).
 */
public final class VpFlashAim {
    static final String[][] MEMBERS = {
        {"viewpoint.render.FlashShadow", "aim", "(Lviewpoint/render/FrameContext;)Z", ""},
        {"viewpoint.render.FlashShadow", "map", "Lviewpoint/render/SpotShadows$MapView;", ""},
        {"viewpoint.render.SpotShadows$MapView", "dir", "[F", ""},
    };
    /** Frames kept with their direction: the main thread runs ahead of the render thread. */
    static final int KEPT = 8;

    private static boolean checked;
    private static volatile boolean ready;
    private static Field map, dir;
    private static final ViewpointPass.Batch[] batches = new ViewpointPass.Batch[KEPT];
    private static final float[] dirs = new float[KEPT * 3];
    private static int next;
    public static volatile long set, aimed, kept, hidden, errors;
    public static volatile String status = "unchecked", last = "";

    private VpFlashAim() { }

    static synchronized boolean detect() {
        if (checked) {
            return ready && ViewpointBridge.ready();
        }
        if (!ViewpointBridge.ready() && !ViewpointBridge.detect()) {
            return false;
        }
        checked = true;
        try {
            ClassLoader cl = VpFlashAim.class.getClassLoader();
            Member[] found = new Member[MEMBERS.length];
            for (int i = 0; i < MEMBERS.length; i++) {
                found[i] = ViewpointBridge.resolve(MEMBERS[i], cl);
                if (found[i] == null) {
                    status = "member " + MEMBERS[i][0] + "." + MEMBERS[i][1] + " " + MEMBERS[i][2] + " missing or changed";
                    System.out.println("[TWZB] Viewpoint flashlight aim: " + status + ", the light follows the view (bridge stays on)");
                    return false;
                }
            }
            map = (Field) found[1];
            dir = (Field) found[2];
            ready = true;
            status = "on";
        } catch (Throwable t) {
            status = "lookup failed: " + t;
            System.out.println("[TWZB] Viewpoint flashlight aim: " + status + ", the light follows the view (bridge stays on)");
        }
        return ready;
    }

    /**
     * Main thread (VpLights.move): frame b's flashlight is ours at the lens, the weapon's bore is (dx, dy
     * tiles, dz levels). Scene space as ViewpointPass.Batch.scene: (-x, z x LEVEL, -y); a direction takes
     * no relief lift.
     */
    static void set(ViewpointPass.Batch b, double dx, double dy, double dz) {
        double sx = -dx, sy = dz * ViewpointBridge.LEVEL, sz = -dy, n = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (b == null || !(n > 1e-6) || !ready && !detect()) {
            return;
        }
        synchronized (batches) {
            int i = slot(b);
            if (i < 0) {
                i = next;
                next = (next + 1) % KEPT;
                batches[i] = b;
            }
            dirs[i * 3] = (float) (sx / n);
            dirs[i * 3 + 1] = (float) (sy / n);
            dirs[i * 3 + 2] = (float) (sz / n);
        }
        set++;
    }

    /**
     * Main thread (VpLights.hide): Viewpoint's flashlight of this frame off (SpotDraws.flashOn, written by its
     * snapshot and read only by FlashShadow.aim): the weapon whose light it is is hidden by an action.
     */
    static void off(Object spots) throws IllegalAccessException {
        ViewpointBridge.field("viewpoint.render.SpotDraws.flashOn").setBoolean(spots, false);
        hidden++;
    }

    private static int slot(ViewpointPass.Batch b) {
        for (int i = 0; i < KEPT; i++) {
            if (batches[i] == b) {
                return i;
            }
        }
        return -1;
    }

    /** Render thread, exit of FlashShadow.aim: the light is on and its frame's direction is ours - replace it. */
    public static void aim(Object shadow, boolean on) {
        if (!on || !ready) {
            return;
        }
        try {
            apply(ViewpointPass.batchOf(ViewpointBridge.currentScene()), shadow);
        } catch (Throwable t) {
            errors++;
            String message = "aim: " + t;
            if (!message.equals(last)) {
                last = message;
                System.out.println("[TWZB] Viewpoint flashlight aim " + message + ", the light follows the view this frame");
            }
        }
    }

    /** Frame b's direction, when it carries ours, into the flashlight's map view (shadow: FlashShadow); true when replaced. */
    static boolean apply(ViewpointPass.Batch b, Object shadow) throws IllegalAccessException {
        float x, y, z;
        synchronized (batches) {
            int i = b == null ? -1 : slot(b);
            if (i < 0) {
                kept++;
                return false;
            }
            x = dirs[i * 3];
            y = dirs[i * 3 + 1];
            z = dirs[i * 3 + 2];
        }
        float[] d = (float[]) dir.get(map.get(shadow));
        d[0] = x;
        d[1] = y;
        d[2] = z;
        aimed++;
        return true;
    }

    /** QA: "status= set= aimed= kept= hidden= errors=". */
    public static String info() {
        return "status=" + status + " set=" + set + " aimed=" + aimed + " kept=" + kept + " hidden=" + hidden + " errors=" + errors;
    }

    @Patch(className = "viewpoint.render.FlashShadow", methodName = "aim")
    public static class Aim {
        @Patch.OnExit
        public static void exit(@Patch.This Object shadow, @Patch.Return boolean on) {
            VpFlashAim.aim(shadow, on);
        }
    }
}
