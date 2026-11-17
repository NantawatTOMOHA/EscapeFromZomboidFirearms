package pz.escapefromzomboidfirearms.zb;

import java.nio.FloatBuffer;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;

/**
 * Our independent optical scope camera, then a lens-only composite over the
 * completed Viewpoint world image. Input is the current TWSight channel, not
 * another mod's optic profile. All GL work is a GenericDrawer on the render
 * thread. The camera stays at the selected source lens, along its sight axis.
 */
public final class ScopePip {
    private static volatile Request request;
    private static volatile boolean enabled = true;
    /**
     * Player report of 24.10.2026 (a black scope and dead collimators): one failure of the picture latched the whole
     * Viewpoint scope bridge off (ViewpointPipBridge.fail), and the collimators, the lens glass, the rangefinder, the iron
     * probe and the thermal/night-vision sensors went with it for the session. A failure of the picture itself, with
     * Viewpoint's state put back (ViewpointPipBridge.restored), now turns off this picture alone (the reason, printed
     * once); the sight falls back to the main image's glass. Null: on.
     */
    private static volatile String pictureOff;
    /** The scope world's smallest picture under the main view's pipeline (ViewpointPipBridge.minimumSize), QA. */
    private static volatile int minimumSize = 1;
    /** QA (A/B in one run): false - the eyepiece's size alone, as before the player report of 24.10.2026. */
    static volatile boolean floor = true;
    /** QA: the eyepiece's size replaced by this many pixels (a 1080p eyepiece on a 4K QA screen); 0 - the eyepiece's own. */
    static volatile int qaSize;
    private static volatile float resolutionScale = .5f;
    private static volatile int interval = 1;
    /**
     * The picture's size follows the eyepiece (ScopePipGeometry.lensSize) under the option's share of the screen: it
     * grows once a larger size held GROW_SETTLE frames, shrinks once one at least two steps smaller held SHRINK_SETTLE
     * frames - the targets are reallocated after the raise into ADS settles, not through it or the sway. QA: false - the
     * option's share as before.
     */
    static volatile boolean lensFit = true;
    /**
     * The user's choice (23.10.2026, "take the final size at once"): the picture starts at the eyepiece's size at full
     * ADS (finalSize: the sight's own eye point, the ADS FOV), not at the cap shrunk 30 frames later - one reallocation
     * of the targets on a new sight instead of a frame at the cap and a second one in ADS. The rules above stay for an
     * eyepiece drawn larger than predicted; it is never shrunk below the prediction. QA: false - as before.
     */
    static volatile boolean predictFit = true;
    /**
     * The user's request (23.10.2026): the scope's second world built ahead of the first raise (ViewpointPipBridge.prewarm)
     * on a Viewpoint frame with the main targets and shadow maps, not in the raise. QA: false - in the first raise, as
     * before (set before the world starts).
     */
    static volatile boolean prewarm = true;
    private static volatile boolean prewarmed;
    private static final TextureDraw.GenericDrawer PREWARM = new TextureDraw.GenericDrawer() {
        @Override
        public void render() {
            if (prewarmed) {
                return;
            }
            try (ScopePipGL saved = new ScopePipGL()) {
                prewarmed = ViewpointPipBridge.prewarm();
            } catch (Throwable t) {
                // Not built ahead: the first raise builds it, as before.
                prewarmed = true;
                System.out.println("[TWZB] scope picture prewarm skipped: " + t);
            }
        }

        @Override
        public void postRender() {
        }
    };
    /** QA (A/B in one run): the picture read through the Catmull-Rom spline of tw_pip_composite (false: one bilinear tap). */
    static volatile boolean sharpPicture = true;
    static final int GROW_SETTLE = 6, SHRINK_SETTLE = 30;
    private static int fitSize, fitCandidate, fitFrames, fitPredicted;
    /** finalSize's inputs of fitPredicted: the channel key, cap, viewport and the ADS FOV it was made for. */
    private static String fitKey = "";
    private static int fitCap, fitWidth, fitHeight;
    private static double fitFov = Double.NaN;
    private static volatile long resizes;
    private static volatile Image image;
    private static volatile Request drawnWindow;
    private static volatile float[] disk;
    private static volatile String drawnKey = "";
    private static volatile String reason = "idle";
    /**
     * Performance pass 23.10.2026: the inputs of the drawn frame's camera diagnostics (copies, as ScopePipCamera takes);
     * the ScopePipCamera itself - matrix inversions and a dozen vectors - is built when QA reads info(), not every frame.
     */
    private record CameraInputs(ScopePipWeaponSnapshot snapshot, long frame, Matrix4f view, Matrix4f mainView,
                                Vector3f fieldEye, boolean debug) {
        ScopePipCamera camera() { return new ScopePipCamera(snapshot, frame, view, mainView, fieldEye, debug); }
    }
    private static volatile CameraInputs cameraInfo;
    private static final ScopePipGL.Uniforms glassUniform = new ScopePipGL.Uniforms(GLCompat.elements("GlassBoundary", 64));
    private static volatile float debugFov;
    private static volatile boolean legacyImage;
    private static volatile double[] watch;
    private static volatile String motionWitness = "";
    private static volatile long frames, rendered, skipped, clipped, composited, errors, gpuSamples;
    private static volatile double millis, averageMillis, gpuMillis;
    private static long lastSnapshot = Long.MIN_VALUE;
    private static int texture, fbo, size, vao, sampler, depthSampler;
    private static TwProgram composite;

    /** Render thread: the composite program, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (composite == null) {
            composite = ShaderWarmup.made(TwProgram.create("tw_pip_composite"));
        }
    }

    /** Render thread (ShaderWarmup): the picture's programs, the composite and the history's, and the camera zoom's eyepiece. */
    static void warm() {
        shaders();
        history.shaders();
        ScopePipZoom.shaders();
    }
    private static int[] timerQueries;
    private static boolean timerPending;
    private static final ScopePipHistory history = new ScopePipHistory();

    private ScopePip() {
    }

    @me.zed_0xff.zombie_buddy.Exposer.LuaClass(name = "TWFB2BPipQA")
    public static final class QA {
        private QA() { }
        public static boolean legacyImage(boolean on) {
            if (!zombie.Lua.LuaManager.GlobalObject.getDebug()) return false;
            ScopePip.legacyImage = on;
            return true;
        }
        public static boolean watch(double x, double y, double z) {
            if (!zombie.Lua.LuaManager.GlobalObject.getDebug() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return false;
            ScopePip.watch = new double[] {x, y, z};
            return true;
        }
        public static boolean stop() {
            if (!zombie.Lua.LuaManager.GlobalObject.getDebug()) return false;
            legacyImage = false; watch = null; motionWitness = "";
            return true;
        }
        /** The next picture frame throws before the scope world (a failure of the picture alone). */
        public static boolean failOnce() {
            if (!zombie.Lua.LuaManager.GlobalObject.getDebug()) return false;
            qaThrow = true;
            return true;
        }
        /** The picture back on after a failure (QA only; a player's session keeps it off). */
        public static boolean pictureOn() {
            if (!zombie.Lua.LuaManager.GlobalObject.getDebug()) return false;
            pictureOff = null;
            return true;
        }
        public static String picture() {
            return pictureOff == null ? "on" : pictureOff;
        }
        public static boolean floor(boolean on) {
            if (!zombie.Lua.LuaManager.GlobalObject.getDebug()) return false;
            ScopePip.floor = on;
            return true;
        }
        public static boolean size(double pixels) {
            if (!zombie.Lua.LuaManager.GlobalObject.getDebug() || !(pixels >= 0 && pixels <= 2048)) return false;
            qaSize = (int) pixels;
            return true;
        }
    }

    private static volatile boolean qaThrow;

    public static void options(boolean on, double scale, int every) {
        enabled = on;
        resolutionScale = Double.isFinite(scale) ? (float) Math.max(.25, Math.min(1, scale)) : .5f;
        interval = every == 4 ? 4 : every == 2 ? 2 : 1;
        if (!on) {
            clear("option off");
        }
    }

    /** W10 nopip2: the resolution option's share of the screen (kept while the picture is off, as the camera zoom's sensor picture takes it). */
    static float resolution() {
        return resolutionScale;
    }

    /** QA only; zero restores the selected Tarkov camera. No foreign camera is modified. */
    public static void debugFov(double degrees) {
        debugFov = Double.isFinite(degrees) && degrees > 0 && degrees < 179 ? (float) degrees : 0;
        history.invalidate();
    }

    static boolean diagnostics() { return debugFov > 0 || Probe.enabled || ScopePipDump.wanted(); }

    public static void set(IsoPlayer player, KahluaTable value) {
        if (!enabled || player == null || value == null || !player.isLocalPlayer() || player.getPlayerNum() != 0
                || player.isDead() || !HipFireInput.aimIntent(player)) {
            clear("inactive");
            return;
        }
        if (pictureOff != null) {
            clear("picture off");
            return;
        }
        try {
            String kind = String.valueOf(value.rawget("kind"));
            double mag = number(value, "magnification"), fov = number(value, "fov");
            if (!ScopePipGeometry.eligible(kind, mag, fov)) {
                clear("non-optical channel or unknown FOV");
                return;
            }
            request = new Request(player, value, (float) fov);
            reason = "requested";
        } catch (IllegalArgumentException t) {
            // Unproven/missing source geometry is not a compatibility failure.
            clear("source geometry unavailable");
        } catch (Throwable t) {
            fail("request", t);
        }
    }

    private static double number(KahluaTable table, String key) {
        Object v = table.rawget(key);
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    private static float[] vector(KahluaTable table, String key) {
        Object v = table.rawget(key);
        if (!(v instanceof KahluaTable t)) {
            throw new IllegalArgumentException(key);
        }
        float[] p = new float[3];
        for (int i = 0; i < 3; i++) {
            Object n = t.rawget(i + 1);
            if (!(n instanceof Number value) || !Double.isFinite(value.doubleValue())) {
                throw new IllegalArgumentException(key);
            }
            p[i] = value.floatValue();
            if (!Float.isFinite(p[i])) {
                throw new IllegalArgumentException(key);
            }
        }
        return p;
    }

    static final class Request {
        final IsoPlayer player;
        final Object weapon;
        final String key, kind, cameraSource;
        final float fov;
        final ReticleProfile reticle;
        /**
         * The player's brightness dial of this sight (user's item 14, 26.10.2026), a linear gain over the
         * authored Tarkov illumination; 1 is the untouched source brightness. Kept with the request so the
         * render thread never reads live Lua.
         */
        final float reticleBrightness;
        final float[] camera, ocular, objective, edgeX, edgeY, eye, forward, up;
        /** FB7 item 5: the active sight's front end on the line of sight; the picture's near plane is set there. */
        final float[] front;
        final float[][] boundary;
        final boolean planeDepth, screen;
        /** FB4 item 15: part templates of the active sight, its subtree and mounts (kept out of the picture). */
        final java.util.Set<String> hide;

        Request(IsoPlayer player, KahluaTable table, float fov) {
            this(player, table, fov, null);
        }

        /** FB8 item 5: a sight other than the selected one brings its own reticle (SightGlassExtras), not the shared cache's. */
        Request(IsoPlayer player, KahluaTable table, float fov, ReticleProfile own) {
            this.player = player;
            weapon = player.getPrimaryHandItem();
            this.fov = fov;
            key = player.getPrimaryHandItem().getID() + ":" + table.rawget("key") + ":" + fov;
            reticle = own != null ? own : ReticleRendererPip.capture(key, table);
            Object brightness = table.rawget("reticleBrightness");
            float level = brightness instanceof Number n ? n.floatValue() : 1f;
            if (!Float.isFinite(level) || level < 0f || level > 16f)
                throw new IllegalArgumentException("reticle brightness");
            reticleBrightness = level;
            kind = String.valueOf(table.rawget("kind"));
            camera = vector(table, "camera"); ocular = vector(table, "ocular");
            objective = table.rawget("objective") == null ? null : vector(table, "objective");
            cameraSource = table.rawget("cameraSource") instanceof String source ? source : objective == null ? "ocular" : "objective";
            front = table.rawget("front") == null ? null : vector(table, "front");
            edgeX = vector(table, "edgeX"); edgeY = vector(table, "edgeY"); eye = vector(table, "eye");
            forward = vector(table, "forward"); up = vector(table, "up");
            planeDepth = Boolean.TRUE.equals(table.rawget("planeDepth"));
            screen = Boolean.TRUE.equals(table.rawget("screen"));
            if (table.rawget("boundary") instanceof KahluaTable polygon) {
                int count = polygon.len();
                if (count < 3 || count > 64) throw new IllegalArgumentException("source boundary");
                boundary = new float[count][];
                for (int i = 0; i < count; i++) {
                    Object row = polygon.rawget(i + 1);
                    if (!(row instanceof KahluaTable point)) throw new IllegalArgumentException("source boundary point");
                    boundary[i] = new float[3];
                    for (int j = 0; j < 3; j++) {
                        Object coordinate = point.rawget(j + 1);
                        if (!(coordinate instanceof Number n) || !Float.isFinite(n.floatValue()))
                            throw new IllegalArgumentException("source boundary coordinate");
                        boundary[i][j] = n.floatValue();
                    }
                }
            } else boundary = null;
            java.util.Set<String> parts = new java.util.HashSet<>();
            if (table.rawget("hide") instanceof KahluaTable list) {
                for (int i = 1; i <= Math.min(list.len(), 64); i++) {
                    if (list.rawget(i) instanceof String tpl && !tpl.isEmpty() && tpl.length() < 64) parts.add(tpl);
                }
            }
            hide = java.util.Set.copyOf(parts);
        }
    }

    static final class Image {
        final String key;
        final long frame;
        final int texture, depth, size;
        final float fov;
        final boolean debug;
        final Matrix4f matrix, view, projection;
        final Vector3f origin;

        Image(Request request, long frame, Matrix4f view, Matrix4f projection, Vector3f origin, int depth, float fov, boolean debug) {
            key = request.key; this.frame = frame; this.fov = fov; this.debug = debug;
            this.view = new Matrix4f(view); this.projection = new Matrix4f(projection);
            matrix = new Matrix4f(projection).mul(view);
            this.origin = new Vector3f(origin);
            texture = ScopePip.texture; size = ScopePip.size; this.depth = depth;
        }
    }

    public static void clear(String why) {
        history.invalidate();
        debugFov = 0;
        ScopePipDump.cancelPending();
        request = null;
        drawnWindow = null;
        cameraInfo = null;
        disk = null;
        drawnKey = "";
        legacyImage = false; watch = null; motionWitness = "";
        reason = why;
    }

    /** The sole capture point: immediately after ViewpointPass opens this frame's batch. */
    public static void afterWorld() {
        SightGlassExtras.afterWorld();
        if (ScopePipDump.needsPoll()) {
            SpriteRenderer.instance.drawGeneric(new TextureDraw.GenericDrawer() {
                @Override public void render() { ScopePipDump.poll(); }
                @Override public void postRender() { }
            });
        }
        if (!prewarmed && prewarm && enabled && pictureOff == null && ViewpointBridge.state() == 1 && ViewpointPipBridge.detect()) {
            MetalFrame.queue(PREWARM);
        }
        Request current = request;
        if (current != null && (!HipFireInput.aimIntent(current.player) || current.player.isDead() || current.player.getPrimaryHandItem() != current.weapon)) {
            clear("aim or weapon changed");
            return;
        }
        if (!enabled || pictureOff != null || current == null || ViewpointBridge.state() != 1 || !ViewpointPipBridge.detect()) {
            // Optical 1x uses the PIP reticle too. Only the non-PIP path
            // schedules the main-image glass mark, avoiding a second reticle.
            // W10 item 11: the camera-zoom eyepiece owns its frames (no glass mark under it, also while it ramps out).
            if (!ScopePipZoom.afterWorld()) SightGlass.afterWorld();
            return;
        }
        try {
            Object frame = ViewpointBridge.frameNow();
            if (frame == null) {
                return;
            }
            long number = ViewpointBridge.frameNumber(frame);
            if (number == lastSnapshot) {
                return;
            }
            lastSnapshot = number;
            ScopePipWeaponSnapshot snapshot = ScopePipWeaponSnapshot.capture(frame, current);
            if (snapshot != null) {
                MetalFrame.queue(new Drawer(snapshot));
            }
        } catch (Throwable t) {
            fail("capture", t);
        }
    }

    private static void fail(String where, Throwable t) {
        errors++;
        clear("failed");
        image = null;
        if (!ViewpointPipBridge.restored()) {
            ViewpointPipBridge.fail(where, t);
            return;
        }
        while (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null) {
            t = t.getCause();
        }
        synchronized (ScopePip.class) {
            if (pictureOff == null) {
                pictureOff = "off: " + where + " " + t.getClass().getSimpleName() + ": " + t.getMessage();
                System.out.println("[TWZB] scope picture " + pictureOff + " (collimators, glass and sensors stay on)");
            }
        }
        clear("picture off");
    }

    /** Render-thread API for reticles and future scope image effects; never bind this texture as an output. */
    public static int texture() { Image i = image; return ready() && i != null ? i.texture : 0; }
    public static int depth() { Image i = image; return ready() && i != null ? i.depth : 0; }
    public static boolean ready() {
        Image i = image;
        Request r = request;
        return enabled && pictureOff == null && ViewpointPipBridge.ready() && ViewpointBridge.state() == 1 && r != null && i != null
                && drawnKey.equals(i.key) && disk != null;
    }
    /** Read-only glass admission: a completed image of this physical channel/mode. */
    static Request lensWindow() {
        Request r = request;
        Image i = image;
        Request d = drawnWindow;
        // Keep the last completed physical window while its next FOV frame is
        // in flight. ready() also compares transient full-FOV draw/image keys.
        return enabled && pictureOff == null && ViewpointPipBridge.ready() && ViewpointBridge.state() == 1 && disk != null
                && r != null && i != null && d != null && r.player == d.player && r.weapon == d.weapon
                && ReticleProfile.selectionKey(r.key).equals(ReticleProfile.selectionKey(d.key))
                && ReticleProfile.selectionKey(r.key).equals(ReticleProfile.selectionKey(i.key)) ? r : null;
    }
    static boolean hasWindowRequest() { return enabled && pictureOff == null && request != null; }
    public static Matrix4f matrix() { Image i = image; return i == null ? null : new Matrix4f(i.matrix); }
    public static Matrix4f view() { Image i = image; return i == null ? null : new Matrix4f(i.view); }
    public static Matrix4f projection() { Image i = image; return i == null ? null : new Matrix4f(i.projection); }
    public static float fov() { Image i = image; return i == null ? 0 : i.fov; }
    /** GL pixels (bottom-left): cx,cy, radius-axisX.xy, radius-axisY.xy, center depth [0,1]. */
    public static float[] lens() { float[] value = disk; return value == null ? null : value.clone(); }

    /** QA: the eyepiece's drawn size on the screen, " lensPx=<major>x<minor>" (diameters, pixels). */
    static String lensPixels() {
        float[] d = disk;
        if (d == null) {
            return " lensPx=0x0";
        }
        double a = 2 * Math.hypot(d[2], d[3]), b = 2 * Math.hypot(d[4], d[5]);
        return String.format(java.util.Locale.ROOT, " lensPx=%.0fx%.0f", Math.max(a, b), Math.min(a, b));
    }

    /** FB7 item 5, QA: the last scope picture's near plane (scene units; Viewpoint's own is 0.05). */
    static volatile float pictureNear;

    public static String info() {
        Image i = image;
        CameraInputs inputs = cameraInfo;
        ScopePipCamera camera = inputs == null ? null : inputs.camera();
        return ViewpointPipBridge.info() + " enabled=" + enabled + " picture=" + (pictureOff == null ? "on" : pictureOff.replace(' ', '_'))
                + " minSize=" + minimumSize + " ready=" + ready() + " reason=" + reason
                + " resolution=" + (i == null ? "0x0" : i.size + "x" + i.size) + " scale=" + resolutionScale + " interval=" + interval
                + " frames=" + frames + " rendered=" + rendered + " skipped=" + skipped + " clipped=" + clipped + " composited=" + composited + " errors=" + errors
                + lensPixels() + " lensFit=" + lensFit + " resizes=" + resizes + fitInfo() + " prewarmed=" + prewarmed
                + " gpuSamples=" + gpuSamples + " scene=" + (ScopePipStages.scene != null) + " sensor=" + (ScopePipStages.sensor != null) + " overlay=" + (ScopePipStages.overlay != null)
                + " stageErrors=" + ScopePipStages.sceneErrors + "/" + ScopePipStages.sensorErrors + "/" + ScopePipStages.overlayErrors
                + " " + history.info()
                + (camera == null ? " cameraInfo=unavailable" : camera.info())
                + " debugFov=" + debugFov + " legacyImage=" + legacyImage + motionWitness + ScopePipDump.info()
                + String.format(java.util.Locale.ROOT, " cpuMs=%.3f avgCpuMs=%.3f gpuMs=%.3f fov=%.5f near=%.5f", millis, averageMillis, gpuMillis, fov(), pictureNear);
    }

    private static final class Drawer extends TextureDraw.GenericDrawer {
        final ScopePipWeaponSnapshot snapshot;

        Drawer(ScopePipWeaponSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public void render() {
            if (!enabled || pictureOff != null || !ViewpointBridge.ready() || !ViewpointPipBridge.ready() || request == null) {
                drawnWindow = null;
                return;
            }
            boolean completed = false;
            try (ScopePipGL saved = new ScopePipGL()) {
                Probe.poll();
                if (ViewpointPipBridge.currentScene() != snapshot.scene) {
                    skipped++;
                    return;
                }
                if (qaThrow) {
                    qaThrow = false;
                    throw new IllegalStateException("QA picture failure");
                }
                ScopePipWeaponSnapshot snapshot = this.snapshot.forRender();
                frames++;
                Request r = snapshot.request;
                drawnKey = r.key;
                Matrix4f mainMatrix = ViewpointBridge.viewProjection();
                float[] center = ViewpointPipBridge.screen(ScopePipGeometry.projectLens(mainMatrix, snapshot.ocular));
                float[] edgeX = ViewpointPipBridge.screen(ScopePipGeometry.projectLens(mainMatrix, snapshot.edgeX));
                float[] edgeY = ViewpointPipBridge.screen(ScopePipGeometry.projectLens(mainMatrix, snapshot.edgeY));
                float[] lens = ScopePipGeometry.disk(center, edgeX, edgeY, saved.viewport);
                if (lens == null) {
                    disk = null; reason = "lens clipped"; skipped++; clipped++;
                    return;
                }
                float[] boundary = null;
                if (snapshot.boundary != null) {
                    float[][] projected = new float[snapshot.boundary.length][];
                    for (int i = 0; i < projected.length; i++)
                        projected[i] = ViewpointPipBridge.screen(ScopePipGeometry.projectLens(mainMatrix, snapshot.boundary[i]));
                    boundary = ScopePipComposite.boundary(lens, projected, saved.viewport);
                    if (boundary == null) { disk = null; reason = "screen boundary clipped"; skipped++; clipped++; return; }
                }
                // Existing circular magnified optics retain their original
                // centre-depth contract. New 1x screens use their tilted plane.
                float[] depthSlope = r.planeDepth ? ScopePipGeometry.depthSlope(center, edgeX, edgeY) : new float[2];
                float[] axis = SightGlassGeometry.infinity(mainMatrix, snapshot.forward);
                // Source IgnoreOpticsForCameraPlane electronic displays show a
                // fixed camera image on the panel below the mechanical aim ray.
                // Applying optical eye parallax there samples outside the image.
                float[] opticalOrigin = ScopePipComposite.pictureOrigin(lens,
                        axis == null ? null : ViewpointPipBridge.screen(axis), saved.viewport, r.screen);
                if (opticalOrigin == null) {
                    disk = null; reason = "optical axis behind camera"; skipped++;
                    return;
                }
                Object context = ViewpointPipBridge.context();
                int cap = ScopePipGeometry.resolution(saved.viewport[2], saved.viewport[3], resolutionScale);
                // The eyepiece's size (and the option's cap) never below the scope world's own smallest picture:
                // Viewpoint's reflections pyramid (ViewpointPipBridge.minimumSize, player report of 24.10.2026).
                minimumSize = floor ? ViewpointPipBridge.minimumSize() : 1;
                int eyepiece = fit(cap, lens, finalSize(snapshot, r, mainMatrix, saved.viewport, cap));
                int targetSize = Math.max(minimumSize, qaSize > 0 ? qaSize : eyepiece);
                Image previous = image;
                float override = debugFov;
                boolean debug = override > 0;
                float currentFov = debug ? override : r.fov;
                boolean changed = previous == null || !r.key.equals(previous.key) || targetSize != size
                        || previous.fov != currentFov || previous.debug != debug;
                float near = ScopePipGeometry.frontNear((float) ViewpointPipBridge.get("WorldRenderer.NEAR", null),
                        snapshot.camera, snapshot.front, snapshot.forward);
                pictureNear = near;
                float far = (float) ViewpointPipBridge.get("WorldRenderer.FAR", null);
                boolean fresh = false;
                Matrix4f view = debug ? new Matrix4f((Matrix4f) ViewpointPipBridge.get("FrameContext.view", context))
                        : ScopePipGeometry.camera(snapshot.camera, snapshot.forward, snapshot.up);
                // Reuse only a stationary camera. Moving objects can still step at the selected rate.
                changed |= interval > 1 && previous != null
                        && !ScopePipGeometry.stationary(view, snapshot.origin, previous.view, previous.origin);
                if (ScopePipGeometry.update(snapshot.number, previous == null ? Long.MIN_VALUE : previous.frame, interval, changed)) {
                    ensure(targetSize);
                    Vector3f camera = debug ? ScopePipCamera.eye(view) : snapshot.camera;
                    Matrix4f projection = ScopePipGeometry.projection(currentFov, near, far);
                    boolean timing = beginTiming();
                    long start = System.nanoTime();
                    boolean done;
                    try {
                        try (PerfProbe.Scope perf = PerfProbe.render(PerfProbe.PIP_SCENE)) {
                            ScopePipExclusion.hide = r.hide;
                            done = ViewpointPipBridge.render(snapshot.scene, view, projection, camera, size, fbo, r.player);
                        }
                        if (done) {
                            if (ScopePipDump.wanted()) {
                                ScopePipDump.capture(fbo, ViewpointPipBridge.depth(), size, projection,
                                    "key=" + r.key + " frame=" + snapshot.number + " debugFov=" + override
                                    + new ScopePipCamera(snapshot, snapshot.number, view,
                                            (Matrix4f) ViewpointPipBridge.get("FrameContext.view", context),
                                            new Vector3f((float) ViewpointPipBridge.get("FrameContext.eyeX", context),
                                                    (float) ViewpointPipBridge.get("FrameContext.eyeY", context),
                                                    (float) ViewpointPipBridge.get("FrameContext.eyeZ", context)), debug).info()
                                    + "\n" + ViewpointPipBridge.info());
                            }
                            try (PerfProbe.Scope perf = PerfProbe.render(PerfProbe.PIP_HISTORY)) {
                                history.resolve(texture, ViewpointPipBridge.depth(), fbo, size, r.key, snapshot.number,
                                        view, projection, snapshot.origin, (boolean) ViewpointPipBridge.get("SceneData.cameraCut", snapshot.scene));
                            }
                            // W8 item 12 (rain/snow missing from the scopes): Viewpoint's falling precipitation is a
                            // pass of its own frame end - after the temporal resolve and the tone mapping, over the
                            // finished picture. The scope's picture ends at PostPass.composite and this history
                            // resolve, so it is drawn here, at the picture's size and with the scope camera; a thermal
                            // camera (heat, no rain drops) keeps the picture it has.
                            if (ViewpointPipBridge.weatherKind(r.kind)) {
                                try (PerfProbe.Scope perf = PerfProbe.render(PerfProbe.PIP_WEATHER)) {
                                    ViewpointPipBridge.weather(snapshot.scene, view, projection, camera, size, fbo);
                                }
                            }
                        }
                    } finally {
                        if (timing) {
                            GL33.glQueryCounter(timerQueries[1], GL33.GL_TIMESTAMP);
                            timerPending = true;
                        }
                    }
                    if (!done) {
                        reason = "warming"; disk = null; skipped++;
                        return;
                    }
                    millis = (System.nanoTime() - start) / 1e6;
                    rendered++;
                    averageMillis += (millis - averageMillis) / Math.min(rendered, 120);
                    // The secondary depth belongs to the bank, not to the restored main targets.
                    image = new Image(r, snapshot.number, view, projection, snapshot.origin, ViewpointPipBridge.depth(), currentFov, debug);
                    fresh = true;
                } else {
                    skipped++;
                }
                Image shown = image;
                cameraInfo = new CameraInputs(snapshot, shown.frame, new Matrix4f(shown.view),
                        new Matrix4f((Matrix4f) ViewpointPipBridge.get("FrameContext.view", context)),
                        new Vector3f((float) ViewpointPipBridge.get("FrameContext.eyeX", context),
                                (float) ViewpointPipBridge.get("FrameContext.eyeY", context),
                                (float) ViewpointPipBridge.get("FrameContext.eyeZ", context)), shown.debug);
                ScopePipStages.Frame frame = new ScopePipStages.Frame(r.key, r.kind, r.weapon, shown.fov, shown.texture,
                        shown.depth, shown.size, fresh, shown.view, shown.projection, lens, saved.viewport, near, far, opticalOrigin,
                        boundary, depthSlope);
                ScopePipStages.scene(frame);
                int sensed = ScopePipStages.sensor(frame);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, saved.drawFbo);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, saved.readFbo);
                GL11.glViewport(saved.viewport[0], saved.viewport[1], saved.viewport[2], saved.viewport[3]);
                try (PerfProbe.Scope perf = PerfProbe.render(PerfProbe.PIP_COMPOSITE)) {
                    composite(snapshot, lens, context, saved.viewport, sensed != 0 ? sensed : shown.texture, shown.size,
                            frame.imageOrigin, frame.boundary, frame.depthSlope);
                }
                ReticleRendererPip.bind(r.key, r.reticle, r.reticleBrightness);
                ScopePipStages.overlay(frame);
                disk = lens;
                reason = "drawn";
                drawnWindow = r;
                completed = true;
                composited++;
            } catch (Throwable t) {
                drawnWindow = null;
                fail("render", t);
            } finally {
                if (!completed) drawnWindow = null;
            }
        }

        @Override
        public void postRender() {
        }
    }

    /** Render thread: the picture's size under cap for this eyepiece without a prediction (the rules of lensFit). */
    static int fit(int cap, float[] lens) {
        return fit(cap, lens, 0);
    }

    /** Render thread: the picture's size under cap for this eyepiece (see lensFit, predictFit; predicted 0: unknown). */
    static int fit(int cap, float[] lens, int predicted) {
        if (!lensFit) {
            return cap;
        }
        int wanted = ScopePipGeometry.lensSize(lens, cap);
        if (wanted != fitCandidate) {
            fitCandidate = wanted;
            fitFrames = 0;
        } else {
            fitFrames++;
        }
        int was = fitSize;
        if (predicted > 0 && predicted != fitPredicted) {
            fitSize = predicted;
        } else if (fitSize <= 0 || fitSize > cap) {
            fitSize = cap;
        } else if (wanted > fitSize && fitFrames >= GROW_SETTLE) {
            fitSize = wanted;
        } else if (wanted <= fitSize - 2 * ScopePipGeometry.LENS_STEP && fitFrames >= SHRINK_SETTLE && wanted >= predicted) {
            fitSize = wanted;
        }
        fitPredicted = predicted;
        if (was > 0 && fitSize != was) {
            resizes++;
        }
        return fitSize;
    }

    /**
     * Render thread: the eyepiece's picture size at full ADS (lensSize of its disk), 0 when unknown or predictFit is off.
     * The main camera then stands on the sight's eye point (Request.eye) along its axis, under the ADS FOV (FpAdsCamera:
     * the user's share of Tarkov's narrowing). The eye and the eyepiece move as one with the weapon, so the raise's
     * first frame already gives the size of its last; it is made again only for a new channel, cap, viewport or FOV.
     */
    static int finalSize(ScopePipWeaponSnapshot s, Request r, Matrix4f main, int[] viewport, int cap) {
        if (!predictFit || !lensFit) {
            return 0;
        }
        Matrix4f view = ViewpointBridge.view();
        if (main == null || view == null) {
            return 0;
        }
        double fov = FpAdsCamera.finalFov(r.player);
        if (r.key.equals(fitKey) && cap == fitCap && viewport[2] == fitWidth && viewport[3] == fitHeight
                && (Double.isNaN(fov) ? Double.isNaN(fitFov) : fov == fitFov)) {
            return fitPredicted;
        }
        fitKey = r.key; fitCap = cap; fitWidth = viewport[2]; fitHeight = viewport[3]; fitFov = fov;
        try {
            // The frame's own projection (the drawn eyepiece's), at the ADS FOV when the camera narrows there.
            Matrix4f projection = new Matrix4f(main).mul(new Matrix4f(view).invert());
            if (Double.isFinite(fov) && fov > 0 && projection.m11() > 0) {
                float k = (float) (1 / Math.tan(fov / 2)) / projection.m11();
                projection.m00(projection.m00() * k).m11(projection.m11() * k);
            }
            Matrix4f aimed = projection.mul(ScopePipGeometry.camera(s.expectedEye, s.forward, s.up));
            float[] disk = ScopePipGeometry.disk(ViewpointPipBridge.screen(ScopePipGeometry.projectLens(aimed, s.ocular)),
                    ViewpointPipBridge.screen(ScopePipGeometry.projectLens(aimed, s.edgeX)),
                    ViewpointPipBridge.screen(ScopePipGeometry.projectLens(aimed, s.edgeY)), viewport);
            return disk == null ? 0 : ScopePipGeometry.lensSize(disk, cap);
        } catch (Throwable t) {
            // No prediction (a degenerate sight frame): the rules as before; the picture itself goes on.
            return 0;
        }
    }

    /** QA: the next picture starts as the first of a session (no size kept from an earlier sight). */
    static void resetFit() {
        fitSize = 0; fitCandidate = 0; fitFrames = 0; fitPredicted = 0;
        fitKey = ""; fitCap = 0; fitWidth = 0; fitHeight = 0; fitFov = Double.NaN;
    }

    /** QA: the fit's state, " fit=<size> wanted=<lensSize now> predicted=<finalSize, 0: none>". */
    static String fitInfo() {
        return " fit=" + fitSize + " wanted=" + fitCandidate + " predicted=" + fitPredicted + " predictFit=" + predictFit;
    }

    private static void ensure(int wanted) {
        if (texture == 0) {
            texture = GL11.glGenTextures();
            fbo = GL30.glGenFramebuffers();
            vao = ScreenCorners.vertexArray();
            sampler = GL33.glGenSamplers();
            depthSampler = GL33.glGenSamplers();
            for (int s : new int[] {sampler, depthSampler}) {
                GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
                GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
                GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_MIN_FILTER, s == sampler ? GL11.GL_LINEAR : GL11.GL_NEAREST);
                GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_MAG_FILTER, s == sampler ? GL11.GL_LINEAR : GL11.GL_NEAREST);
            }
        }
        if (size != wanted) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GLCompat.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, wanted, wanted, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("PIP output incomplete");
            }
            size = wanted;
        }
    }

    /**
     * Timestamp pairs can nest Viewpoint's own elapsed-time queries. Never wait for the GPU.
     * Performance pass 23.10.2026: only while the perf probe runs (PerfProbe.enable) - the timings are read by info()
     * alone, and the result-available poll is a query of the driver every scope frame.
     */
    private static boolean beginTiming() {
        if (!PerfProbe.enabled()) {
            timerPending = false;
            return false;
        }
        if (timerQueries == null) {
            timerQueries = new int[] {GLCompat.genQuery(), GLCompat.genQuery()};
        }
        if (timerPending) {
            if (GL15.glGetQueryObjecti(timerQueries[1], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                return false;
            }
            long start = GL33.glGetQueryObjectui64(timerQueries[0], GL15.GL_QUERY_RESULT);
            long end = GL33.glGetQueryObjectui64(timerQueries[1], GL15.GL_QUERY_RESULT);
            gpuMillis = (end - start) / 1e6;
            gpuSamples++;
            timerPending = false;
        }
        GL33.glQueryCounter(timerQueries[0], GL33.GL_TIMESTAMP);
        return true;
    }

    /** picture: the shown picture or its device image, both of pictureSize texels a side. */
    private static void composite(ScopePipWeaponSnapshot snapshot, float[] lens, Object context, int[] viewport, int picture,
            int pictureSize, float[] imageOrigin, float[] boundary, float[] depthSlope) throws Throwable {
        shaders();
        if (!composite.isCompiled()) {
            throw new IllegalStateException("tw_pip_composite did not compile");
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
        composite.Start();
        composite.setVector4("Lens", lens[0], lens[1], lens[2], lens[3]);
        composite.setVector3("OtherAxis", lens[4], lens[5], lens[6]);
        composite.setVector2("DepthSlope", depthSlope[0], depthSlope[1]);
        composite.setValue("GlassCount", boundary == null ? 0 : boundary.length / 2);
        if (boundary != null) GLCompat.uniform2(composite.locations(glassUniform), boundary);
        composite.setVector2("ImageOrigin", imageOrigin[0], imageOrigin[1]);
        float[] basis = ScopePipComposite.imageBasis(image.view, snapshot.ocular, snapshot.edgeX, snapshot.edgeY);
        boolean legacy = legacyImage;
        composite.setVector4("ImageBasis", legacy ? 1f : basis[0], legacy ? 0f : basis[1], legacy ? 0f : basis[2], legacy ? 1f : basis[3]);
        double[] marker = watch;
        if (marker != null) {
            ViewpointPass.Batch batch = ViewpointPass.batchOf(snapshot.scene);
            if (batch != null) {
                float[] position = new float[3];
                batch.scene(marker[0], marker[1], marker[2], position, 0);
                Vector3f target = new Vector3f(position);
                float[] raw = ScopePipGeometry.project(image.matrix, target);
                float[] main = ViewpointPipBridge.screen(ScopePipGeometry.project(ViewpointBridge.viewProjection(), target));
                if (raw != null && main != null) {
                    float det = basis[0]*basis[3]-basis[1]*basis[2];
                    float px = (basis[3]*raw[0]-basis[1]*raw[1])/det;
                    float py = (-basis[2]*raw[0]+basis[0]*raw[1])/det;
                    motionWitness = String.format(java.util.Locale.ROOT,
                        " witnessWorld=%.6f,%.6f,%.6f witnessSourceNdc=%.6f,%.6f witnessMainNdc=%.6f,%.6f witnessOldLocalPx=%.6f,%.6f witnessNewLocalPx=%.6f,%.6f",
                        marker[0],marker[1],marker[2],raw[0],raw[1],main[0],main[1],lens[2]*raw[0]+lens[4]*raw[1],lens[3]*raw[0]+lens[5]*raw[1],
                        lens[2]*px+lens[4]*py,lens[3]*px+lens[5]*py);
                }
            }
        }
        composite.setVector4("Viewport", viewport[0], viewport[1], viewport[2], viewport[3]);
        composite.setValue("Bilinear", sharpPicture ? 0f : 1f);
        composite.setVector2("ImageSize", pictureSize, pictureSize);
        composite.setSamplerUnit("ScopeImage", 0);
        composite.setSamplerUnit("SceneDepth", 1);
        int depth = ReticleRendererPip.sceneDepth();
        composite.setValue("DepthOn", depth != 0 ? 1f : 0f);
        if (Probe.active()) {
            Vector3f eye = new Vector3f((float) ViewpointPipBridge.get("FrameContext.eyeX", context),
                    (float) ViewpointPipBridge.get("FrameContext.eyeY", context), (float) ViewpointPipBridge.get("FrameContext.eyeZ", context));
            Vector3f delta = eye.sub(snapshot.expectedEye);
            Vector3f x = new Vector3f(snapshot.edgeX).sub(snapshot.ocular);
            Vector3f y = new Vector3f(snapshot.edgeY).sub(snapshot.ocular);
            float ex = delta.dot(x) / Math.max(1e-9f, x.lengthSquared());
            float ey = delta.dot(y) / Math.max(1e-9f, y.lengthSquared());
            Probe.picture(picture, ex, ey);
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, picture);
        GL33.glBindSampler(0, sampler);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
        GL33.glBindSampler(1, depthSampler);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    /** Opt-in QA. Three 16x16 float RGBA samples share one asynchronous PBO/fence. */
    static final class Probe {
        private static final int PIXELS = 16 * 16, VALUES = PIXELS * 4, BYTES = VALUES * Float.BYTES;
        private static volatile boolean enabled;
        private static volatile int every = 60;
        private static volatile long epoch;
        private static volatile String result = "not sampled";
        private static long seenEpoch, fence, nextFrame, sampleFrame, samples;
        private static boolean active;
        private static int pbo, readFbo, sampleSize, mask, lightFbo, outputFbo, picture, compositeFbo;
        private static FloatBuffer data;
        private static String sampleKey;
        private static float exposure, offsetX, offsetY;
        private static final int[] outputViewport = new int[4];

        static synchronized void options(boolean on, int interval) {
            int bounded = Math.max(1, Math.min(600, interval));
            if (enabled != on || every != bounded) {
                enabled = on;
                every = bounded;
                epoch++;
            }
        }

        static String info() {
            return "qa=" + enabled + " interval=" + every + " " + result;
        }

        static boolean active() {
            return active && enabled && seenEpoch == epoch;
        }

        /** Render thread only. A zero-timeout fence poll never waits for unfinished work. */
        static void poll() {
            active = false;
            if (seenEpoch != epoch) {
                if (fence != 0) {
                    GL32.glDeleteSync(fence);
                    fence = 0;
                }
                seenEpoch = epoch;
                nextFrame = 0;
            }
            if (!enabled || fence == 0) {
                return;
            }
            int state = GL32.glClientWaitSync(fence, 0, 0L);
            if (state == GL32.GL_TIMEOUT_EXPIRED) {
                return;
            }
            if (state == GL32.GL_WAIT_FAILED) {
                throw new IllegalStateException("PIP probe fence failed");
            }
            GL32.glDeleteSync(fence);
            fence = 0;
            int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
                data.clear();
                GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0L, data);
            } finally {
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
            }
            samples++;
            result = String.format(java.util.Locale.ROOT,
                    "samples=%d frame=%d key=%s size=%d exposure=%.6f light=%s output=%s image=%s"
                    + " eyeOffset=%.4f,%.4f legacyFullBlack=%s fbo=%d/%d/%d picture=%d outputViewport=%d,%d,%d,%d",
                    samples, sampleFrame, sampleKey, sampleSize, exposure, mean(0), mean(1), mean(2),
                    offsetX, offsetY, Math.hypot(offsetX, offsetY) >= 2, lightFbo, outputFbo, compositeFbo, picture,
                    outputViewport[0], outputViewport[1], outputViewport[2], outputViewport[3]);
        }

        private static String mean(int stage) {
            double[] rgba = new double[4];
            for (int p = 0; p < PIXELS; p++) {
                for (int c = 0; c < 4; c++) {
                    rgba[c] += data.get(stage * VALUES + p * 4 + c) / PIXELS;
                }
            }
            return String.format(java.util.Locale.ROOT, "[%.6f,%.6f,%.6f,%.6f]", rgba[0], rgba[1], rgba[2], rgba[3]);
        }

        static void begin(int size) {
            if (!enabled || fence != 0 || seenEpoch != epoch || frames < nextFrame) {
                return;
            }
            if (pbo == 0) {
                pbo = GL15.glGenBuffers();
                readFbo = GL30.glGenFramebuffers();
                data = BufferUtils.createFloatBuffer(3 * VALUES);
                int pack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
                try {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo);
                    GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, 3L * BYTES, GL15.GL_STREAM_READ);
                } finally {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pack);
                }
            }
            sampleKey = drawnKey;
            sampleFrame = frames;
            sampleSize = size;
            mask = 0;
            active = true;
            nextFrame = frames + every;
        }

        static void light(int framebuffer) {
            if (active()) {
                lightFbo = framebuffer;
                ScopePipGL.readCenter(framebuffer, 0, sampleSize, pbo, 0L);
                mask |= 1;
            }
        }

        static void output(int framebuffer, float value) {
            if (active()) {
                outputFbo = framebuffer;
                compositeFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, outputViewport);
                exposure = value;
                ScopePipGL.readCenter(framebuffer, 0, sampleSize, pbo, BYTES);
                mask |= 2;
            }
        }

        static void picture(int texture, float x, float y) {
            if (active()) {
                picture = texture;
                offsetX = x; offsetY = y;
                ScopePipGL.readCenter(readFbo, texture, sampleSize, pbo, 2L * BYTES);
                mask |= 4;
                if (mask != 7) {
                    throw new IllegalStateException("PIP probe stages incomplete");
                }
                fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                if (fence == 0) {
                    throw new IllegalStateException("PIP probe fence unavailable");
                }
                active = false;
            }
        }
    }
}
