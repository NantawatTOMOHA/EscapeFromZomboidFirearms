package pz.escapefromzomboidfirearms.zb;

import java.util.Locale;
import java.util.Set;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.SpriteRenderer;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.textures.TextureDraw;

/**
 * W10 item 11 (27.10.2026, the player: "add a way to turn PIP off and make a plain zoom of the whole camera, as in other
 * games, for performance"): the camera-zoom picture of a magnifying optic (option ScopePicture, default the picture in
 * the eyepiece as before). No second world render at all:
 *   - the main camera narrows by the sight's own current magnification M (fixed, a discrete mode, the wheel of a
 *     variable optic) from the aimed view's FOV: tan(zoom/2) = tan(ads/2) / M (FpAdsCamera.forPlayer calls fov()).
 *     The scope picture shows the world M times the aimed view too: its camera's field (Tarkov's per-mode FOV) fills an
 *     eyepiece that subtends Tarkov's apparent field from the eye point, so both modes show one world scale at the
 *     reticle and the sight's own sensitivity (TW/Sensitivity) stays the one of the zoom the player sees;
 *   - the zoom follows the raise: the eye meets the eyepiece at ADS weight START, the view goes dark round the ocular
 *     over the first DARK of the rest, then the held weapon and the viewer's body leave the main image (the draws'
 *     colour flag of this frame's scene, on the main thread before the render thread draws it) while the camera zooms
 *     and the ocular's disk grows to the eyepiece; lowering runs it back. An in-aim switch to a channel without the zoom
 *     (a 1x mode) ramps out over MODE_SECONDS with the last channel's reticle;
 *   - the eyepiece is the ocular's own shape (its axes' ratio, or its source polygon), the field stop's shadow where the
 *     sight's axis leaves the eyepiece's centre (the picture's own field stop, FB5 item 8), and the reticle - its
 *     illumination, the player's brightness dial, FFP/SFP by the sight's FOV - on the sight's axis at infinity, in the
 *     eyepiece's space as the picture draws it (QA trueScale: at the world's angular scale instead). 30.10.2026 (the
 *     player, with a Tarkov and a S.T.A.L.K.E.R. frame: W11 nopipzoom had taken the black off both values and put the
 *     eyepiece across the screen in both): eye to the eyepiece (value 3) - the eyepiece grows from the ocular to FILL
 *     of the screen's shorter side at its centre, black outside, as in Tarkov; by magnification (value 2) - the camera
 *     zooms but does not enter the scope: the eyepiece stays the ocular as the aimed view shows it, black only on its
 *     housing ring (HOUSING), the zoomed world round it; the weapon and the hands stay in view at their aimed size
 *     (FpViewLatch narrows the frame's own records by the zoom), the sight with its mounts leaves the image.
 * Collimators and irons keep their glass marks (Lua: TW_ScopePipOptions); every optical mode zooms, its 1x too (at the
 * aimed FOV: the weapon leaves the image, the eyepiece's ring and reticle stay). W10 nopip2 (the rest of the
 * class): night vision and thermal sights - each of their modes, the 1x screens too - zoom the same way, and their
 * device's image is the sensor stage of the picture (VpSensor, VpSensorGL.scope: palette, gain, noise, pixelation,
 * bloom, aberration, the sensor's frame rate, the heat) run over the zoomed frame inside the eyepiece instead of over a
 * second render (VpSensorGL.zoomFrame); a thermal reticle keeps its screen layout (ReticleRendererHud) on the field.
 * A failure turns this layer off alone (one log line): the camera keeps the aimed FOV.
 */
public final class ScopePipZoom {
    /** ADS weight (FpAdsCamera.weight) where the eye meets the eyepiece; the zoom spans the rest up to full ADS. */
    static final double START = .75;
    /** Share of the zoom's range spent going dark round the ocular before the sight leaves the image. */
    static final double DARK = .2;
    /** An in-aim change into or out of a zoomed channel (seconds of pose time). */
    static final double MODE_SECONDS = .12;
    /** A discrete change of the magnification between two zoomed modes, time constant of its log (seconds). */
    static final double MAG_SECONDS = .06;
    /** The eyepiece's larger extent over the screen's shorter side. */
    static final float FILL = .96f;
    /**
     * Value 2: the outer radius of the black housing round the eyepiece, in eyepiece radii (the player's S.T.A.L.K.E.R.
     * frame, 30.10.2026: housing about 570 px across an eyepiece of about 450 px).
     */
    static final float HOUSING = 1.3f;
    /** A sample of fov() older than this is not this frame's (the camera did not zoom). */
    static final long STALE_NS = 1_000_000_000L;

    static volatile boolean enabled;
    /**
     * The coordinator's variant C (27.10.2026, option value 3): the picture's own field fills the eyepiece - the zoom
     * goes to tan(zoom/2) = tan(sightFov/2) / (eyepiece radius / half the screen height), stronger than M, as if the eye
     * were pressed to the scope - so every mark of the reticle (range, holdover, mil) matches the world as in the
     * picture. false: the view zooms by the sight's magnification M (value 2, the default of the camera zoom).
     */
    static volatile boolean field;
    /** QA (A/B in one run): the reticle at the world's angular scale instead of the eyepiece's space. */
    static volatile boolean trueScale;
    /** QA (A/B in one run, 02.11.2026): the zoom about the camera's axis, not the sight's (axisShift off: the code before). */
    static volatile boolean qaLegacyAxis;
    /** QA: a world point (PZ x, y, z) whose screen position each drawn frame records (an impact against the reticle). */
    static volatile double[] watch;
    private static volatile Request request;
    private static volatile Shown shown;
    private static volatile boolean failed;
    private static volatile String reason = "idle", lastError = "";
    private static volatile Object info = "drawn=false";
    private static volatile long samples, frames, drawn, skipped, excludedModels, deepened, errors;
    /** nopip2: frames whose eyepiece showed the sensor stage's image, and the size of its last picture. */
    private static volatile long sensed;
    private static volatile int sensorSize;
    private static long lastFrame = Long.MIN_VALUE;
    private static final State state = new State();
    private static final ScopePipGL.Uniforms glassUniform = new ScopePipGL.Uniforms(GLCompat.elements("GlassBoundary", 64));
    private static TwProgram mask;
    private static int vao;

    private ScopePipZoom() { }

    /** A zoomed channel of the local player: the picture's request (geometry, reticle) and its magnification. */
    record Request(ScopePip.Request geometry, double magnification, float[] shape) {
        /** nopip2: a night vision or thermal channel - its eyepiece shows the sensor stage's image of the zoomed frame. */
        boolean sensor() { return sensor(geometry.kind); }

        static boolean sensor(String kind) { return "thermal".equals(kind) || "nv".equals(kind); }
    }

    /** What fov() gave the camera of one frame (main thread): the amount 0..1 of the zoom, its magnification, FOVs in degrees. */
    record Shown(Request request, double amount, double magnification, double adsDeg, double zoomDeg, long at, boolean field) {
        double growth() { return ScopePipZoom.growth(amount); }
    }

    private static final class State {
        double mode, logM;
        Request drawing;
    }

    /** Render thread: the eyepiece mask's program, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (mask == null) mask = ShaderWarmup.made(TwProgram.create("tw_zoom_mask"));
    }

    // ------------------------------------------------------------------ the mapping (pure)

    static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /** The zoom's amount at an ADS weight: 0 below START, 1 at full ADS. */
    static double amount(double weight) {
        return Double.isFinite(weight) ? smooth((weight - START) / (1 - START)) : 0;
    }

    /** The part of the amount after the dark: the camera's zoom and the eyepiece's growth, 0..1. */
    static double growth(double amount) {
        return Double.isFinite(amount) ? Math.max(0, Math.min(1, (amount - DARK) / (1 - DARK))) : 0;
    }

    /**
     * The camera's vertical FOV in degrees for the aimed view's FOV, the sight's magnification and the zoom's growth: the
     * tangent of the half angle divided by M^growth (the same world scale as the scope picture at full growth). An
     * unmagnified or invalid input keeps the aimed FOV exactly.
     */
    static double zoomed(double adsDeg, double magnification, double growth) {
        if (!(adsDeg > 0 && adsDeg < 180) || !(magnification > 1) || !Double.isFinite(magnification) || !(growth > 0)) {
            return adsDeg;
        }
        double m = Math.pow(magnification, Math.min(1, growth));
        return Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(adsDeg) / 2) / m));
    }

    /**
     * Variant C: the magnification over the aimed view that puts the sight's own field (its camera's vertical FOV,
     * degrees) across the eyepiece, whose radius is ratio of half the screen's height (FILL at a landscape screen).
     */
    static double fieldMagnification(double adsDeg, double sightFovDeg, double ratio) {
        if (!(adsDeg > 0 && adsDeg < 180 && sightFovDeg > 0 && sightFovDeg < 180 && ratio > 0) || !Double.isFinite(ratio)) {
            return 1;
        }
        return Math.tan(Math.toRadians(adsDeg) / 2) * ratio / Math.tan(Math.toRadians(sightFovDeg) / 2);
    }

    /** The eyepiece's radius over half the screen's height (eyepiece(): FILL of the shorter side). */
    static double ratio(int width, int height) {
        return width > 0 && height > 0 ? FILL * Math.min(width, height) / (double) height : FILL;
    }

    /** The magnification the camera shows at a FOV against the aimed view's (inverse of zoomed). */
    static double shownMagnification(double adsDeg, double zoomDeg) {
        if (!(adsDeg > 0 && adsDeg < 180 && zoomDeg > 0 && zoomDeg < 180)) return 1;
        return Math.tan(Math.toRadians(adsDeg) / 2) / Math.tan(Math.toRadians(zoomDeg) / 2);
    }

    // ------------------------------------------------------------------ options and requests (Lua tick)

    public static void options(boolean on, boolean field) {
        enabled = on;
        ScopePipZoom.field = field;
        if (!on) {
            request = null;
            reason = "option off";
        }
    }

    /** The current magnified optic channel while the local player aims (TW_ScopePipOptions); nil clears. */
    public static void set(IsoPlayer player, KahluaTable value) {
        if (!enabled || failed || player == null || value == null || !player.isLocalPlayer() || player.getPlayerNum() != 0
                || player.isDead() || !HipFireInput.aimIntent(player)) {
            request = null;
            reason = failed ? "failed" : !enabled ? "option off" : "inactive";
            return;
        }
        try {
            Object m = value.rawget("magnification"), f = value.rawget("fov");
            double magnification = m instanceof Number n ? n.doubleValue() : Double.NaN;
            float fov = f instanceof Number n ? n.floatValue() : Float.NaN;
            String kind = String.valueOf(value.rawget("kind"));
            // Every optical mode, its 1x too (30.10.2026, the player: the 1x of an optic in the camera zoom showed the
            // scope's opaque 3D tube with the reticle on it - the main image has no picture in the lens).
            if (!("optic".equals(kind) || Request.sensor(kind)) || !(magnification >= 1)
                    || !Double.isFinite(magnification) || !(fov > 0 && fov < 179)) {
                request = null;
                reason = "not a zoomed optic";
                return;
            }
            ScopePip.Request geometry = new ScopePip.Request(player, value, fov);
            request = new Request(geometry, magnification, shape(geometry));
            reason = "requested";
        } catch (IllegalArgumentException unavailable) {
            request = null;
            reason = "source geometry unavailable";
        } catch (Throwable t) {
            fail("request", t);
        }
    }

    /** The eyepiece's shape: half extents (x, y) of the ocular and its source polygon in those units (or none). */
    static float[] shape(ScopePip.Request r) {
        Vector3f center = new Vector3f(r.ocular[0], r.ocular[1], r.ocular[2]);
        Vector3f x = new Vector3f(r.edgeX[0], r.edgeX[1], r.edgeX[2]).sub(center);
        Vector3f y = new Vector3f(r.edgeY[0], r.edgeY[1], r.edgeY[2]).sub(center);
        float hx = x.length(), hy = y.length();
        if (!(hx > 1e-9f) || !(hy > 1e-9f)) throw new IllegalArgumentException("ocular extent");
        int n = r.boundary == null ? 0 : r.boundary.length;
        float[] out = new float[2 + 2 * n];
        float big = Math.max(hx, hy);
        out[0] = hx / big;
        out[1] = hy / big;
        for (int i = 0; i < n; i++) {
            Vector3f p = new Vector3f(r.boundary[i][0], r.boundary[i][1], r.boundary[i][2]).sub(center);
            out[2 + 2 * i] = p.dot(x) / (hx * hx);
            out[3 + 2 * i] = p.dot(y) / (hy * hy);
        }
        return out;
    }

    // ------------------------------------------------------------------ the camera (main thread, View.fovY)

    /** FpAdsCamera.fov at its every call: this frame's zoom is decided anew (forPlayer gives it, else none). */
    static void begin() {
        shown = null;
    }

    /**
     * Inside FpAdsCamera.forPlayer under its player's lock: the camera's vertical FOV in degrees for the aimed FOV
     * (adsDeg, the user's share of Tarkov's narrowing), the pose's ADS weight and its time step (0 when paused or
     * repeated at one pose time). Returns adsDeg exactly while nothing is zoomed.
     */
    static double fov(IsoGameCharacter c, double adsDeg, double weight, double dt) {
        if (failed) {
            return adsDeg;
        }
        Request r = request;
        boolean live = enabled && r != null && r.geometry.player == c && c.getPrimaryHandItem() == r.geometry.weapon;
        double step = Double.isFinite(dt) && dt >= 0 && dt <= .25 ? dt : 0;
        synchronized (state) {
            State s = state;
            double was = s.mode;
            if (live) {
                s.drawing = r;
            }
            double target = live ? 1 : 0;
            s.mode = s.mode < target ? Math.min(target, s.mode + step / MODE_SECONDS) : Math.max(target, s.mode - step / MODE_SECONDS);
            Request d = s.drawing;
            if (d == null || s.mode <= 0 || d.geometry.player != c || c.getPrimaryHandItem() != d.geometry.weapon) {
                s.mode = 0;
                s.drawing = null;
                return adsDeg;
            }
            // A (value 2): the sight's magnification; C (value 3): the one that puts the sight's field across the eyepiece.
            double wanted = field ? fieldMagnification(adsDeg, d.geometry.fov, screenRatio()) : d.magnification;
            double logM = Math.log(Math.max(1, wanted));
            if (was <= 0) {
                s.logM = logM;
            } else {
                s.logM += (logM - s.logM) * Math.min(1, step / MAG_SECONDS);
            }
            double amount = amount(weight) * smooth(s.mode);
            if (amount <= 0) {
                return adsDeg;
            }
            double magnification = Math.exp(s.logM);
            double zoom = zoomed(adsDeg, magnification, growth(amount));
            shown = new Shown(d, amount, magnification, adsDeg, zoom, System.nanoTime(), field);
            samples++;
            return zoom;
        }
    }

    /** The screen's eyepiece ratio (main thread; the viewport the eyepiece is drawn on is the game's screen). */
    private static double screenRatio() {
        zombie.core.Core core = zombie.core.Core.getInstance();
        return ratio(core.getScreenWidth(), core.getScreenHeight());
    }

    /** This frame's zoom, or null (main thread). */
    static Shown current() {
        Shown v = shown;
        return v == null || failed || System.nanoTime() - v.at > STALE_NS ? null : v;
    }

    /** True while the camera zoom owns this frame's sight: the main-image glass mark (SightGlass) is not drawn then. */
    static boolean active() {
        return current() != null;
    }

    /**
     * nopip2: the TWSight kind ("thermal", "nv") of this frame's zoomed sensor channel, or null (main thread,
     * VpSensor.close): the main frame then shows what that channel's picture shows (VpSensor.scopeShow) and keeps its heat.
     */
    static String sensorKind() {
        Shown v = current();
        return v != null && v.request.sensor() ? v.request.geometry.kind : null;
    }

    // ------------------------------------------------------------------ the frame (main thread, IsoWorld.render exit)

    /**
     * ScopePip.afterWorld: this frame's eyepiece. Returns true when the zoom owns the frame. Once the dark is over the
     * held weapon and the viewer's body are kept out of this frame's main image (their colour flag; the shadows stay;
     * the selection is ScopePipExclusion's), and a zoom below Viewpoint's FOV floor narrows this frame's projection.
     */
    static boolean afterWorld() {
        Shown v = current();
        if (v == null || ViewpointBridge.state() != 1 || !ViewpointPipBridge.detect()) {
            return false;
        }
        try {
            Object frame = ViewpointBridge.frameNow();
            if (frame == null) {
                return true;
            }
            long number = ViewpointBridge.frameNumber(frame);
            if (number == lastFrame) {
                return true;
            }
            lastFrame = number;
            ScopePip.Request r = v.request.geometry;
            ScopePipWeaponSnapshot snapshot = ScopePipWeaponSnapshot.capture(frame, r);
            if (snapshot == null) {
                skipped++;
                reason = "weapon not drawn";
                return true;
            }
            frames++;
            // 30.10.2026 (the player: by magnification the weapon model was gone): by magnification the weapon and the
            // hands stay at their aimed size - FpViewLatch narrows the frame's own records about the view axis by the
            // shown zoom (narrowing) - and only the sight with its mounts leaves the image (the picture's hide list), so
            // the eyepiece shows the zoomed world. Eye to the eyepiece, or a sight on the weapon's root (no list):
            // the whole held weapon and the viewer's body (an empty hide list: ScopePipExclusion.belongs). The zoom's
            // field is the aimed view's over M - several times the scope camera's own (game QA run1: the M4A1's front
            // sight and rail filled the lower part of the eyepiece at 3x and 4x, where the picture shows a small post
            // or nothing) - so the eyepiece shows the world alone, as a camera zoom does.
            boolean kept = !v.field && !r.hide.isEmpty() && v.growth() > 0 && FpViewLatch.carries();
            if (v.growth() > 0) {
                excludedModels += kept ? exclude(snapshot.scene, r.player, r.hide, true) : exclude(snapshot.scene, r.player, Set.of(), false);
            }
            float k = kept ? (float) shownMagnification(v.adsDeg, v.zoomDeg) : 1f;
            Matrix4f projection = ViewpointBridge.projection(frame);
            deepen(projection, v.zoomDeg);
            narrow(snapshot.scene, number, k, k > 1 && !qaLegacyAxis ? axisShift(projection, snapshot, number, k) : null);
            MetalFrame.queue(new Drawer(snapshot, v));
        } catch (Throwable t) {
            fail("frame", t);
            return false;
        }
        return true;
    }

    /**
     * Viewpoint's first person floors the camera's FOV at CameraStep.MIN_FOV, 15 degrees (ThirdPerson.firstPerson:
     * max(MIN_FOV, View.fovY - zoom), javap of Viewpoint 0.1.6.1): a zoom below it (a 4x at an aimed FOV under about 55 degrees, a 6x
     * and up at the default) would stop there. This frame's own projection (SceneData.projection, set on the main thread
     * before this point and read by the render thread's SceneDrawer) is narrowed the rest of the way: its focal terms
     * scaled by tan(drawn/2) / tan(zoom/2). Viewpoint's culling of this frame used the wider 15 degrees (conservative).
     */
    static boolean deepen(Matrix4f projection, double zoomDeg) {
        if (projection == null || !(zoomDeg > 0 && zoomDeg < 179) || !(projection.m11() > 0)) {
            return false;
        }
        double drawn = 2 * Math.atan(1 / projection.m11());
        double wanted = Math.toRadians(zoomDeg);
        if (!(drawn > wanted * (1 + 1e-6))) {
            return false;
        }
        float k = (float) (Math.tan(drawn / 2) / Math.tan(wanted / 2));
        projection.m00(projection.m00() * k).m11(projection.m11() * k);
        deepened++;
        return true;
    }

    /**
     * The colour flag off for the viewer's models but the held weapon's parts outside hide (ScopePipExclusion.belongs);
     * sightOnly: for the held weapon's parts in hide alone (the body and the rest of the weapon stay).
     */
    static int exclude(Object scene, IsoPlayer viewer, Set<String> hide, boolean sightOnly) throws Throwable {
        Object draws = ViewpointPipBridge.get("SceneData.models", scene);
        int[] flags = (int[]) ViewpointPipBridge.get("ModelDraws.flags", draws);
        ModelInstance[] instances = (ModelInstance[]) ViewpointPipBridge.get("ModelDraws.instances", draws);
        int count = (int) ViewpointPipBridge.get("ModelDraws.count", draws);
        if (flags == null || instances == null || count < 0 || count > flags.length || count > instances.length) {
            throw new IllegalStateException("model table changed");
        }
        int colour = (int) ViewpointPipBridge.get("ModelDraws.COLOUR", null);
        if (colour == 0) {
            throw new IllegalStateException("model colour flag missing");
        }
        ModelInstance weapon = viewer.primaryHandModel;
        int n = 0;
        for (int i = 0; i < count; i++) {
            if ((flags[i] & colour) != 0 && (sightOnly ? sightPart(instances[i], weapon, hide)
                    : ScopePipExclusion.belongs(instances[i], viewer, weapon, hide))) {
                flags[i] &= ~colour;
                n++;
            }
        }
        return n;
    }

    /** A part of the held weapon whose anchor on the weapon's root is in hide (ScopePipExclusion.hidden), or its subtree. */
    static boolean sightPart(ModelInstance instance, ModelInstance weapon, Set<String> hide) {
        ModelInstance below = null;
        int up = 0;
        for (ModelInstance p = instance; p != null && weapon != null; p = p.parent) {
            if (p == weapon) {
                return below != null && ScopePipExclusion.hidden(below.attachmentNameParent, hide);
            }
            below = p;
            if (++up > 128) {
                throw new IllegalStateException("cyclic or unbounded zoom model ancestry");
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the aimed-size weapon (by magnification)

    /**
     * A frame's narrowing of its own records: the frame number, the shown zoom k and (axisShift; null: none) the shift
     * of its projection {dx, dy} in NDC with the view-space shear {sx, sy} that keeps the records where they were.
     */
    private record Narrow(long number, float k, float[] shift) { }

    private static final java.util.Map<Object, Narrow> narrows = new java.util.IdentityHashMap<>();
    private static volatile long narrowed, shifted;
    private static volatile float lastShiftX, lastShiftY;

    /** Main thread, afterWorld: this frame's zoom for its own records (k <= 1: none) and its projection's shift. */
    private static void narrow(Object scene, long number, float k, float[] shift) {
        synchronized (narrows) {
            if (k > 1 && Float.isFinite(k)) narrows.put(scene, new Narrow(number, k, shift)); else narrows.remove(scene);
        }
    }

    private static float[] shiftOf(Object scene, long number) {
        Narrow n;
        synchronized (narrows) {
            n = narrows.get(scene);
        }
        return n == null || n.number != number ? null : n.shift;
    }

    /** Render thread, FpViewLatch.latchBegin: the view-space shear {sx, sy} its records take with the zoom, or null. */
    static float[] shearFor(Object scene, long number) {
        float[] s = failed ? null : shiftOf(scene, number);
        return s == null ? null : new float[] {s[2], s[3]};
    }

    /** Render thread, the eyepiece: the NDC shift {dx, dy} of this frame's projection, or null. */
    static float[] shiftFor(Object scene, long number) {
        float[] s = failed ? null : shiftOf(scene, number);
        return s == null ? null : new float[] {s[0], s[1]};
    }

    /**
     * 02.11.2026 (the player: by magnification the glass and the reticle float apart from the weapon in the idle sway and
     * the reticle is cut at the eyepiece's edges): the camera zoomed about its own axis, so a turn of the weapon by d
     * moved the sight's axis k*d on the screen while the narrowed weapon, its eyepiece and the field stop moved d. As the
     * picture does (its world rendered along the sight's axis), the zoomed world is now centred on the sight: this
     * frame's projection is shifted in NDC so the sight's axis at infinity lands where the aimed view puts it (its zoomed
     * NDC over k) - on the eyepiece, under the reticle, where the round goes - and the carried records take the opposite
     * view-space shear (narrowing), so the weapon and the hands stay at their aimed place. The axis is the captured one
     * seen from the camera its pose was made for: FpViewLatch's correction C carries it to the drawing camera unchanged
     * on the screen. Returns {dx, dy, sx, sy}, or null (the projection left as it was).
     */
    static float[] axisShift(Matrix4f projection, ScopePipWeaponSnapshot s, long number, float k) throws Throwable {
        FpViewLatch.Pose pose = FpViewLatch.pose(s.scene);
        if (projection == null || pose == null || pose.number() != number) {
            return null;
        }
        float[] eye = new float[3], dir = new float[3];
        if (!ViewpointBridge.camera(pose.frame(), pose.yaw(), pose.pitch(), eye, dir)) {
            return null;
        }
        float[] shift = axisShift(projection, dir, s.forward, k);
        if (shift != null) {
            shifted++;
            lastShiftX = shift[0];
            lastShiftY = shift[1];
        }
        return shift;
    }

    /** Pure: the shift of axisShift for a camera looking along forward (scene up +Y); applied to projection in place. */
    static float[] axisShift(Matrix4f projection, float[] forward, Vector3f axis, float k) {
        if (!(k > 1) || !Float.isFinite(k) || !FpViewLatch.finite(forward) || FpViewLatch.horizontal(forward) < 1e-3) {
            return null;
        }
        Matrix4f view = new Matrix4f().setLookAt(0, 0, 0, forward[0], forward[1], forward[2], 0, 1, 0);
        Vector4f clip = new Matrix4f(projection).mul(view).transform(new Vector4f(axis.x, axis.y, axis.z, 0));
        if (!(clip.w > 1e-6f) || !(projection.m00() != 0) || !(projection.m11() != 0)) {
            return null;
        }
        float dx = clip.x / clip.w * (1 / k - 1), dy = clip.y / clip.w * (1 / k - 1);
        if (!Float.isFinite(dx) || !Float.isFinite(dy)) {
            return null;
        }
        shift(projection, dx, dy);
        // A view-space x + a*z moves NDC x by m00*a/m23: the shear that moves the records' NDC by -shift.
        float sx = -dx * projection.m23() / projection.m00(), sy = -dy * projection.m23() / projection.m11();
        return new float[] {dx, dy, sx, sy};
    }

    /** Pure: a matrix's clip space moved by {dx, dy} in NDC (x' = x + dx * w), in place. */
    static Matrix4f shift(Matrix4f matrix, float dx, float dy) {
        return matrix.set(new Matrix4f().m30(dx).m31(dy).mul(matrix));
    }

    /** Render thread, FpViewLatch.latchBegin: the zoom k its frame's own records are narrowed by, 1 for none. */
    static float narrowFor(Object scene, long number) {
        if (failed) {
            return 1f;
        }
        Narrow n;
        synchronized (narrows) {
            n = narrows.get(scene);
        }
        if (n == null || n.number != number) {
            return 1f;
        }
        narrowed++;
        return n.k;
    }

    /**
     * Pure: the scene-space transform that narrows points about the camera's axis (eye, forward; scene up +Y) by k, their
     * depth along it kept: under a projection k times narrower they land where the unnarrowed points land under the
     * aimed one (x/z, y/z scale by k; z, so the depth test, unchanged). null when degenerate.
     */
    static Matrix4f narrowing(float[] eye, float[] forward, float k) {
        return narrowing(eye, forward, k, 0, 0);
    }

    /** The same followed by the view-space shear x += sx z, y += sy z (axisShift's, undoing the projection's shift). */
    static Matrix4f narrowing(float[] eye, float[] forward, float k, float sx, float sy) {
        if (!(k > 1) || !Float.isFinite(k) || !FpViewLatch.finite(eye) || !FpViewLatch.finite(forward)
                || FpViewLatch.horizontal(forward) < 1e-3 || !Float.isFinite(sx) || !Float.isFinite(sy)) {
            return null;
        }
        Matrix4f view = new Matrix4f().setLookAt(eye[0], eye[1], eye[2], eye[0] + forward[0], eye[1] + forward[1],
                eye[2] + forward[2], 0, 1, 0);
        Matrix4f out = new Matrix4f(view).invertAffine().mul(new Matrix4f().m20(sx).m21(sy)).scale(1 / k, 1 / k, 1).mul(view);
        return out.isFinite() ? out : null;
    }

    private static void fail(String where, Throwable t) {
        errors++;
        lastError = where + " " + t;
        if (!failed) {
            failed = true;
            request = null;
            shown = null;
            reason = "failed";
            System.out.println("[TWZB] scope camera zoom off: " + where + ": " + t);
        }
    }

    // ------------------------------------------------------------------ the eyepiece (render thread)

    /**
     * The eyepiece now, GL pixels as ScopePip.lens(). Eye to the eyepiece (field): from the ocular's projected disk
     * (growth 0) to the full eyepiece at the viewport's centre (growth 1); null: no ocular disk and no growth yet. By
     * magnification: the ocular's disk as the aimed view shows it - this frame's disk (zoomed by shown over the aimed
     * view) scaled back about the viewport's centre - so the camera zooms without entering the scope; null: no disk.
     */
    static float[] eyepiece(float[] ocular, float[] shape, int[] viewport, double growth, boolean field, double shown) {
        float cx = viewport[0] + viewport[2] / 2f, cy = viewport[1] + viewport[3] / 2f;
        if (!field) {
            if (ocular == null) {
                return null;
            }
            float k = shown > 1 && Double.isFinite(shown) ? (float) shown : 1f;
            return new float[] {cx + (ocular[0] - cx) / k, cy + (ocular[1] - cy) / k, ocular[2] / k, ocular[3] / k,
                    ocular[4] / k, ocular[5] / k, ocular[6]};
        }
        float r = FILL * Math.min(viewport[2], viewport[3]) / 2f;
        float[] full = {cx, cy, r * shape[0], 0, 0, r * shape[1], 0};
        if (ocular == null) {
            return growth > 0 ? full : null;
        }
        float g = (float) Math.max(0, Math.min(1, growth));
        float[] out = new float[7];
        for (int i = 0; i < 6; i++) out[i] = ocular[i] + (full[i] - ocular[i]) * g;
        out[6] = ocular[6];
        return out;
    }

    private static final class Drawer extends TextureDraw.GenericDrawer {
        final ScopePipWeaponSnapshot snapshot;
        final Shown shown;

        Drawer(ScopePipWeaponSnapshot snapshot, Shown shown) {
            this.snapshot = snapshot;
            this.shown = shown;
        }

        @Override
        public void render() {
            if (failed || !ViewpointBridge.ready() || !ViewpointPipBridge.ready()) {
                return;
            }
            PerfProbe.Scope perf = PerfProbe.render(PerfProbe.RETICLE);
            // nopip2: a sensor channel's pass binds framebuffers and the units of the sensor stage - the full boundary.
            try (ScopePipGL saved = shown.request.sensor() ? new ScopePipGL() : ScopePipGL.reticle()) {
                if (ViewpointPipBridge.currentScene() != snapshot.scene) {
                    skipped++;
                    return;
                }
                ScopePipWeaponSnapshot s = snapshot.forRender();
                Request q = shown.request;
                ScopePip.Request r = q.geometry;
                int[] vp = saved.viewport;
                Matrix4f matrix = ViewpointBridge.viewProjection();
                // The eyepiece and its field stop are the narrowed weapon's: without this frame's shift (axisShift). The
                // reticle and the world are the shifted frame's.
                float[] shift = shiftFor(snapshot.scene, snapshot.number);
                Matrix4f unshifted = shift == null ? matrix : shift(new Matrix4f(matrix), -shift[0], -shift[1]);
                float[] ocular = ScopePipGeometry.disk(ViewpointPipBridge.screen(ScopePipGeometry.projectLens(unshifted, s.ocular)),
                        ViewpointPipBridge.screen(ScopePipGeometry.projectLens(unshifted, s.edgeX)),
                        ViewpointPipBridge.screen(ScopePipGeometry.projectLens(unshifted, s.edgeY)), vp);
                double g = shown.growth();
                float back = (float) shownMagnification(shown.adsDeg, shown.zoomDeg);
                float[] lens = eyepiece(ocular, q.shape, vp, g, shown.field, back);
                float[] axis = ViewpointPipBridge.screen(SightGlassGeometry.infinity(matrix, s.forward));
                if (lens == null || axis == null) {
                    skipped++;
                    reason = lens == null ? "ocular clipped" : "axis behind camera";
                    return;
                }
                float fieldX = vp[0] + (axis[0] + 1f) * vp[2] / 2f, fieldY = vp[1] + (axis[1] + 1f) * vp[3] / 2f;
                // By magnification the field stop is the eyepiece's own geometry, unzoomed with it; the reticle stays on
                // the zoomed world's axis (with axisShift the same point).
                float[] axisU = shift == null ? axis : ViewpointPipBridge.screen(SightGlassGeometry.infinity(unshifted, s.forward));
                float fieldUX = axisU == null ? fieldX : vp[0] + (axisU[0] + 1f) * vp[2] / 2f;
                float fieldUY = axisU == null ? fieldY : vp[1] + (axisU[1] + 1f) * vp[3] / 2f;
                float cx = vp[0] + vp[2] / 2f, cy = vp[1] + vp[3] / 2f;
                float stopX = shown.field ? fieldX : cx + (fieldUX - cx) / back, stopY = shown.field ? fieldY : cy + (fieldUY - cy) / back;
                float fade = (float) g;
                float dark = (float) Math.min(1, shown.amount / DARK);
                float[] boundary = q.shape.length > 2 ? java.util.Arrays.copyOfRange(q.shape, 2, q.shape.length) : null;
                boolean device = q.sensor() && sense(r, lens, boundary, vp, saved, snapshot.scene, fade);
                drawMask(lens, stopX, stopY, dark, boundary, shown.field);
                ReticleGeometry.Aperture aperture = null, mark = null, field = null;
                boolean marked = false;
                if (g > 0) {
                    aperture = ReticleGeometry.Aperture.fromGL(lens, vp, boundary == null);
                    mark = SightGlassGeometry.mark(aperture, axis, vp);
                    if (trueScale && mark != null) {
                        // The world's angular scale: the picture's field (its FOV) over the radius it spans on this frame.
                        float k = (float) (Math.tan(Math.toRadians(r.fov) / 2) / Math.tan(Math.toRadians(shown.zoomDeg) / 2)
                                * vp[3] / 2f / aperture.radius());
                        mark = new ReticleGeometry.Aperture(mark.x(), mark.y(), mark.ux() * k, mark.uy() * k,
                                mark.vx() * k, mark.vy() * k, mark.round());
                    }
                    field = mark;
                    float[] glass = boundary == null ? null : boundary.clone();
                    if (glass != null) for (int i = 1; i < glass.length; i += 2) glass[i] = -glass[i];
                    if (mark != null && q.sensor()) {
                        // nopip2: a thermal device draws its reticle as part of its screen's image (Tarkov's HUD layout,
                        // ReticleRendererHud, as the picture places it on the lens): here on the field - the eyepiece
                        // centred on the sight's axis, where the device's own picture is. Other kinds (night vision)
                        // keep the ordinary mark.
                        ReticleGeometry.Aperture hud = ReticleRendererHud.mark(r.key, r.reticle, new ReticleGeometry.Aperture(
                                mark.x(), mark.y(), aperture.ux(), aperture.uy(), aperture.vx(), aperture.vy(), aperture.round()), mark, glass);
                        ReticleRendererHudData.Row row = hud == mark ? null : ReticleRendererHud.row(r.key, r.reticle.modeId());
                        float[] at = row == null || trueScale ? null : hudCentre(mark.x(), mark.y(), aperture, row.center(), r.fov,
                                shown.zoomDeg, vp[3]);
                        // The layout's centre is off the field's (FLIR RS-32: 3.5 % of the radius below it). Its world
                        // direction is the picture's (row.center times the sight's half field): in A the eyepiece is
                        // not at the world's scale, and the layout's offset scaled with it put the aim point ~0.5 deg
                        // off the picture's (game QA run2: 0.76 deg from the axis at 2.25x).
                        mark = at == null ? hud : new ReticleGeometry.Aperture(at[0], at[1], hud.ux(), hud.uy(), hud.vx(), hud.vy(), hud.round());
                    }
                    if (mark != null) {
                        marked = ReticleRenderer.draw(r.reticle, r.fov, aperture, mark, r.reticleBrightness, false, glass, 0, lens[6]);
                        if (marked) IronSightProbe.reticle(r.key, r.weapon, mark.x(), mark.y(), vp[2], vp[3]);
                    }
                }
                // QA: the frame's own vertical FOV (its projection) and the watched point on the screen (the mark's pixels).
                Matrix4f view = ViewpointBridge.view();
                double frameFov = Double.NaN;
                if (view != null) {
                    Matrix4f projection = new Matrix4f(matrix).mul(new Matrix4f(view).invert());
                    frameFov = Math.toDegrees(2 * Math.atan(1 / projection.m11()));
                }
                // QA: the world against the reticle's space - the direction at the tangent 0.5 tan(sightFov/2) below the
                // sight's axis lands half the eyepiece's radius below the reticle's centre where every mark matches the
                // world (the picture, variant C): worldScale 1; in variant A it is 1 / (the zoom over M).
                double worldScale = Double.NaN;
                if (field != null) {
                    float t = (float) (0.5 * Math.tan(Math.toRadians(r.fov) / 2));
                    float[] below = ViewpointPipBridge.screen(SightGlassGeometry.infinity(matrix,
                            new Vector3f(s.forward).fma(-t, s.up).normalize()));
                    if (below != null) {
                        float by = (1f - below[1]) * vp[3] / 2f, bx = (below[0] + 1f) * vp[2] / 2f;
                        worldScale = Math.hypot(bx - field.x(), by - field.y()) / (0.5 * field.radius());
                    }
                }
                float[] watched = null;
                double[] point = watch;
                ViewpointPass.Batch batch = point == null ? null : ViewpointPass.batchOf(snapshot.scene);
                if (batch != null) {
                    float[] at = new float[3];
                    batch.scene(point[0], point[1], point[2], at, 0);
                    float[] ndc = ViewpointPipBridge.screen(ScopePipGeometry.project(matrix, new Vector3f(at)));
                    if (ndc != null) watched = new float[] {(ndc[0] + 1f) * vp[2] / 2f, (1f - ndc[1]) * vp[3] / 2f};
                }
                // QA: the reticle's axis against the eyepiece's centre, in eyepiece radii (they move together in the sway).
                float[] drift = field == null || aperture == null || !(aperture.radius() > 0) ? null
                        : new float[] {(field.x() - aperture.x()) / aperture.radius(), (field.y() - aperture.y()) / aperture.radius()};
                drawn++;
                reason = "drawn";
                info = new Drawn(shown, lens, fieldX, fieldY, marked, mark, vp[2], vp[3], ocular != null, frameFov, watched, worldScale,
                        device, device ? sensorSize : 0, drift, shift);
            } catch (Throwable t) {
                fail("render", t);
            } finally {
                PerfProbe.end(perf);
            }
        }

        @Override
        public void postRender() { }
    }

    /**
     * W11 nopipzoom (S1): the full-screen sensor square (GL pixels: x, y, side) centered on the viewport and covering both
     * the entire viewport and the eyepiece lens so thermal/NV sensor modes fill 100% of the screen in Modes 2 and 3.
     */
    static float[] screenBox(float[] lens, int[] viewport) {
        if (lens == null || lens.length < 6 || viewport == null || viewport.length < 4
                || viewport[2] <= 0 || viewport[3] <= 0) {
            return null;
        }
        float cx = viewport[0] + viewport[2] / 2f, cy = viewport[1] + viewport[3] / 2f;
        float hx = (float) Math.hypot(lens[2], lens[4]), hy = (float) Math.hypot(lens[3], lens[5]);
        float halfX = Math.max(viewport[2] / 2f, Math.abs(lens[0] - cx) + hx);
        float halfY = Math.max(viewport[3] / 2f, Math.abs(lens[1] - cy) + hy);
        float half = Math.max(halfX, halfY) + 1f;
        if (!(half > 1f) || !Float.isFinite(half)) {
            return null;
        }
        return new float[] {cx - half, cy - half, 2 * half};
    }

    /**
     * nopip2 + W11 nopipzoom (S1): the full-screen device image of a night vision or thermal channel before the ring and
     * the reticle. The zoomed frame inside the full-screen square (screenBox) is the sensor stage's picture
     * (VpSensorGL.zoomFrame); letterbox strips outside the rectangular viewport are filled with mirrored viewport strips
     * so automatic gain (VpSensorGL.mean) and edge filtering match the scene's luminance without dark border bias.
     */
    private static boolean sense(ScopePip.Request r, float[] lens, float[] boundary, int[] vp, ScopePipGL saved, Object scene,
            float fade) throws Throwable {
        if (!(fade > 0) || !VpSensor.wants(r.key, r.kind)) {
            return false;
        }
        float[] box = screenBox(lens, vp);
        ViewpointPass.Batch batch = ViewpointPass.batchOf(scene);
        Matrix4f view = ViewpointBridge.view();
        Matrix4f projection = batch == null || batch.frame == null ? null : ViewpointBridge.projection(batch.frame);
        if (box == null || view == null || projection == null) {
            return false;
        }
        int size = sensorSize(vp[2], vp[3], ScopePip.resolution());
        ScopePipStages.Frame frame = VpSensorGL.zoomFrame(r.key, r.kind, r.weapon, r.fov, lens, box, size, vp, saved.drawFbo,
                view, projection);
        if (frame != null && VpSensorGL.zoomColour.fbo != 0) {
            float scale = size / box[2];
            int x0 = Math.max(vp[0], (int) Math.floor(box[0])), y0 = Math.max(vp[1], (int) Math.floor(box[1]));
            int x1 = Math.min(vp[0] + vp[2], (int) Math.ceil(box[0] + box[2]));
            int y1 = Math.min(vp[1] + vp[3], (int) Math.ceil(box[1] + box[2]));
            int u0 = Math.round((x0 - box[0]) * scale), v0 = Math.round((y0 - box[1]) * scale);
            int u1 = Math.round((x1 - box[0]) * scale), v1 = Math.round((y1 - box[1]) * scale);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, saved.drawFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, VpSensorGL.zoomColour.fbo);
            if (v0 > 0) GL30.glBlitFramebuffer(x0, y1, x1, y0, u0, 0, u1, v0, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            if (v1 < size) GL30.glBlitFramebuffer(x0, y1, x1, y0, u0, v1, u1, size, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            if (u0 > 0) GL30.glBlitFramebuffer(x1, y0, x0, y1, 0, v0, u0, v1, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
            if (u1 < size) GL30.glBlitFramebuffer(x1, y0, x0, y1, u1, v0, size, v1, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        }
        int image = frame == null ? 0 : ScopePipStages.sensor(frame);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, saved.drawFbo);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, saved.readFbo);
        GL11.glViewport(vp[0], vp[1], vp[2], vp[3]);
        if (image == 0) {
            return false;
        }
        drawPicture(image, size, box, lens, boundary, fade);
        sensed++;
        sensorSize = size;
        return true;
    }

    /**
     * nopip2: the size of the camera zoom's sensor picture - the picture's own rule (ScopePipGeometry.lensSize: about 1.25
     * texels a pixel of the eyepiece, under the resolution option's share of the screen) for the full eyepiece.
     */
    static int sensorSize(int width, int height, float scale) {
        float full = FILL * Math.min(width, height) / 2f;
        return ScopePipGeometry.lensSize(new float[] {0, 0, full, 0, 0, full, 0}, ScopePipGeometry.resolution(width, height, scale));
    }

    /**
     * nopip2: where a thermal device's HUD layout centre (ReticleRendererHudData.Row.center, eyepiece-local units, x left
     * of the reflected lens, y down) lands on the screen: at the world direction the picture gives it - the field's centre
     * (the sight's axis) plus the centre times tan(sightFov/2) along the eyepiece's own axes (normalised by the half-FOV
     * axis max(|u|, |v|)) - at this frame's zoom (vertical FOV zoomDeg over a viewport height). In C that is the
     * eyepiece's own scale; in A, where the eyepiece spans more of the world, closer.
     */
    static float[] hudCentre(float x, float y, ReticleGeometry.Aperture eyepiece, float[] centre, double sightFovDeg, double zoomDeg,
            int height) {
        double lu = Math.hypot(eyepiece.ux(), eyepiece.uy()), lv = Math.hypot(eyepiece.vx(), eyepiece.vy());
        double big = Math.max(lu, lv);
        double s = Math.tan(Math.toRadians(sightFovDeg) / 2) / Math.tan(Math.toRadians(zoomDeg) / 2) * height / 2.0;
        if (!(lu > 0 && lv > 0 && s > 0) || !Double.isFinite(s)) {
            return null;
        }
        double c0 = eyepiece.reflected() ? centre[0] : -centre[0];
        return new float[] {(float) (x + s * (eyepiece.ux() / big * c0 + eyepiece.vx() / big * centre[1])),
                (float) (y + s * (eyepiece.uy() / big * c0 + eyepiece.vy() / big * centre[1]))};
    }

    /**
     * nopip2 + W11 nopipzoom (S1): the device image (its full-screen square box, GL pixels x, y, side) inside the
     * eyepiece's shape (30.10.2026: outside it is black or the naked eye's world), over the bound framebuffer. The image
     * is imageSize texels a side (the zoom frame's sensor picture).
     */
    private static void drawPicture(int image, int imageSize, float[] box, float[] lens, float[] boundary, float fade) {
        shaders();
        if (!mask.isCompiled()) {
            throw new IllegalStateException("tw_zoom_mask did not compile");
        }
        if (vao == 0) {
            vao = ScreenCorners.vertexArray();
        }
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GL11.glDepthMask(false);
        GL11.glColorMask(true, true, true, false);
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
        ScreenCorners.bind(vao);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, image);
        GL33.glBindSampler(0, 0);
        mask.Start();
        mask.setValue("PictureOn", 1f);
        mask.setSamplerUnit("Picture", 0);
        mask.setVector4("PictureBox", box[0], box[1], box[2], box[2]);
        mask.setVector2("PictureSize", imageSize, imageSize);
        mask.setVector4("Lens", lens[0], lens[1], lens[2], lens[3]);
        mask.setVector2("OtherAxis", lens[4], lens[5]);
        mask.setValue("Dark", fade);
        mask.setValue("GlassCount", boundary == null ? 0 : boundary.length / 2);
        if (boundary != null) GLCompat.uniform2(mask.locations(glassUniform), boundary);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        // the mask pass after it finds the program's uniforms as the mask alone leaves them
        mask.setValue("PictureOn", 0f);
        mask.End();
    }

    /**
     * The eyepiece mask over the bound framebuffer, dark alpha: the field stop's shadow and the vignette inside; outside
     * it black everywhere (eye to the eyepiece) or only on the housing ring out to HOUSING radii (by magnification).
     */
    private static void drawMask(float[] lens, float fieldX, float fieldY, float dark, float[] boundary, boolean field) {
        if (!(dark > 0)) {
            return;
        }
        shaders();
        if (!mask.isCompiled()) {
            throw new IllegalStateException("tw_zoom_mask did not compile");
        }
        if (vao == 0) {
            vao = ScreenCorners.vertexArray();
        }
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL30.GL_FRAMEBUFFER_SRGB);
        GL11.glDepthMask(false);
        GL11.glColorMask(true, true, true, false);
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
        ScreenCorners.bind(vao);
        mask.Start();
        mask.setVector4("Lens", lens[0], lens[1], lens[2], lens[3]);
        mask.setVector2("OtherAxis", lens[4], lens[5]);
        mask.setVector2("Field", fieldX, fieldY);
        mask.setValue("Dark", dark);
        mask.setValue("Housing", field ? 0f : HOUSING);
        mask.setValue("GlassCount", boundary == null ? 0 : boundary.length / 2);
        if (boundary != null) GLCompat.uniform2(mask.locations(glassUniform), boundary);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
        mask.End();
    }

    /** The drawn frame's values; the QA text is made when info() is read. */
    private record Drawn(Shown shown, float[] lens, float fieldX, float fieldY, boolean marked, ReticleGeometry.Aperture mark,
                         int width, int height, boolean ocular, double frameFov, float[] watched, double worldScale,
                         boolean device, int deviceSize, float[] drift, float[] shift) {
        @Override public String toString() {
            double rx = Math.hypot(lens[2], lens[3]), ry = Math.hypot(lens[4], lens[5]);
            return String.format(Locale.ROOT, "drawn=true key=%s amount=%.4f growth=%.4f mag=%.4f adsDeg=%.4f zoomDeg=%.4f"
                            + " shownMag=%.4f frameFovDeg=%.4f worldScale=%.4f variant=%s lens=%.1f,%.1f,%.1fx%.1f field=%.1f,%.1f marked=%b mark=%s viewport=%dx%d ocularDisk=%b watch=%s"
                            + " kind=%s device=%b deviceSize=%d drift=%s shift=%s legacyAxis=%b",
                    ReticleProfile.selectionKey(shown.request.geometry.key), shown.amount, shown.growth(), shown.magnification,
                    shown.adsDeg, shown.zoomDeg, shownMagnification(shown.adsDeg, shown.zoomDeg), frameFov, worldScale,
                    shown.field ? "C" : "A", lens[0], lens[1], 2 * rx, 2 * ry,
                    fieldX, fieldY, marked, mark == null ? "none" : String.format(Locale.ROOT, "%.1f,%.1f,r%.1f", mark.x(), mark.y(), mark.radius()),
                    width, height, ocular, watched == null ? "none" : String.format(Locale.ROOT, "%.1f,%.1f", watched[0], watched[1]),
                    shown.request.geometry.kind, device, deviceSize,
                    drift == null ? "none" : String.format(Locale.ROOT, "%.4f,%.4f", drift[0], drift[1]),
                    shift == null ? "none" : String.format(Locale.ROOT, "%.5f,%.5f", shift[0], shift[1]), qaLegacyAxis);
        }
    }

    public static String info() {
        Request r = request;
        Shown v = shown;
        return "enabled=" + enabled + " field=" + field + " failed=" + failed + " reason=" + reason + " trueScale=" + trueScale
                + " request=" + (r == null ? "none" : ReticleProfile.selectionKey(r.geometry.key) + "@" + String.format(Locale.ROOT, "%.4f", r.magnification))
                + " active=" + (current() != null)
                + (v == null ? "" : String.format(Locale.ROOT, " nowAmount=%.4f nowZoomDeg=%.4f nowAdsDeg=%.4f", v.amount, v.zoomDeg, v.adsDeg))
                + " samples=" + samples + " frames=" + frames + " drawnFrames=" + drawn + " skipped=" + skipped
                + " excluded=" + excludedModels + " narrowed=" + narrowed + " deepened=" + deepened + " axisShifted=" + shifted
                + String.format(Locale.ROOT, " lastShift=%.5f,%.5f", lastShiftX, lastShiftY)
                + " sensed=" + sensed + " errors=" + errors + (lastError.isEmpty() ? "" : " last=" + lastError.replace(' ', '_'))
                + " " + info;
    }

    /** QA: the layer on again after a failure (a player's session keeps it off). */
    static void reset() {
        failed = false;
        lastError = "";
        samples = frames = drawn = skipped = excludedModels = deepened = sensed = errors = narrowed = shifted = 0;
        info = "drawn=false";
    }
}
