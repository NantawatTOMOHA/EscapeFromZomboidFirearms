package pz.escapefromzomboidfirearms.zb;

import org.joml.Vector3f;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.textures.MultiTextureFBO2;
import zombie.core.textures.Texture;
import zombie.iso.IsoCamera;

/**
 * Night vision and thermal images in Viewpoint's view (VP_W5_T), all optional
 * and only with the Viewpoint bridge on (no new Viewpoint member: the existing
 * guarded ones of ViewpointBridge and the scope picture of ScopePip):
 *   scope    a night vision or thermal scope channel (TWSight) seen through
 *            our own scope picture (ScopePip): the sensor stage turns the
 *            picture into the device's image with the very values of the
 *            isometric image (TW/OpticView javaView, OpticState) - Tarkov's
 *            thermal and night vision formulas, palettes, noise, pixelation,
 *            bloom, aberration, the sensor's frame rate; the heat comes from
 *            a heat buffer of the scope camera (VpHeat). The scene stage
 *            draws our lasers, light beams and muzzle effects into a freshly
 *            rendered picture (ViewpointPass.drawScope);
 *            W10 nopip2: in the camera zoom (ScopePicture 2, 3: no second render)
 *            the same stage runs over the zoomed main frame inside the eyepiece
 *            (ScopePipZoom.sense, VpSensorGL.zoomFrame); the main frame then
 *            shows what the channel's picture shows (scopeShow) and keeps the
 *            channel's heat;
 *   lens     a night vision or thermal sight seen at 1x (no second view):
 *            the same image inside its eyepiece, from the frame itself
 *            (LensDrawer, right after the scope picture's turn);
 *   head     night vision goggles (the game's flag, Night Vision Framework)
 *            or a head thermal device: the whole view through the Tarkov
 *            device's image and eyepiece mask (HeadVision.lua), drawn over
 *            the screen after the game composited the frame and before the
 *            interface (HeadDrawer, queued by OpticRenderer.queue).
 * Infrared: lasers and lights submit their IR primitives as such
 * (TWZB.vpSensorMark); the main frame shows them only through the viewer's
 * night vision, a night vision scope always, a thermal one shows no light at
 * all. Every error turns the sensors off (one log line); Viewpoint's view and
 * the plain scope picture go on without them.
 */
public final class VpSensor {
    /** Isolated native QA only: irreversible until the game is restarted. */
    @me.zed_0xff.zombie_buddy.Exposer.LuaClass(name = "TWFB1BSensorQA")
    public static final class QA {
        private QA() {
        }

        public static boolean qaDisableBridge() {
            if (!LuaManager.GlobalObject.getDebug()) {
                return false;
            }
            ViewpointBridge.fail("qa", new IllegalStateException("QA bridge off"));
            return true;
        }
    }

    /** Classes of the pass primitives (ViewpointPass.Prim.cls) and their bits. */
    static final int CLS_VISIBLE = 0, CLS_IR = 1, CLS_MUZZLE = 2;
    static final int SHOW_VISIBLE = 1, SHOW_IR = 2, SHOW_MUZZLE = 4, SHOW_ALL = 7;
    /**
     * The Viewpoint members the sensors read, all through ViewpointBridge (its guarded tables) or
     * ScopePip (ViewpointPipBridge): no member of their own, no advice. tools/check_viewpoint_sensor.py
     * checks each against those tables and the installed Viewpoint.jar.
     */
    static final String[][] DEPENDS = {
        {"viewpoint.core.View", "enabled", "Z", "static"},
        {"viewpoint.core.View", "thirdPerson", "Z", "static"},
        {"viewpoint.core.Frame", "camX", "F", ""},
        {"viewpoint.core.Frame", "camY", "F", ""},
        {"viewpoint.core.Frame", "camZ", "F", ""},
        {"viewpoint.core.Frame", "scene", "Lviewpoint/render/SceneData;", ""},
        {"viewpoint.render.SceneData", "projection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.SceneData", "relief", "Lviewpoint/render/FarRelief$Lift;", ""},
        {"viewpoint.render.FarRelief$Lift", "scene", "(DD)F", ""},
        {"viewpoint.SceneDrawer", "viewProjection", "Lorg/joml/Matrix4f;", "static"},
        {"viewpoint.SceneDrawer", "view", "Lorg/joml/Matrix4f;", "static"},
        {"viewpoint.render.WorldRenderer", "frame", "Lviewpoint/render/FrameContext;", "static"},
        {"viewpoint.render.WorldRenderer", "targets", "Lviewpoint/render/Targets;", "static"},
        {"viewpoint.render.Targets", "depth", "I", ""},
        {"viewpoint.render.FrameContext", "scene", "Lviewpoint/render/SceneData;", ""},
        {"viewpoint.render.FrameContext", "width", "I", ""},
        {"viewpoint.render.FrameContext", "height", "I", ""},
    };

    /** The image values of a scope channel (Lua: TW/OpticView javaView of its mode) for the pictures of its key. */
    static final class Scope {
        /** ScopePip key prefix: item id:path:tpl:channel:mode: */
        final String prefix;
        final OpticState.View view;
        /** Pixelation blocks across the lens (the picture's height). */
        final float blocks;
        /** A 1x sight: its eyepiece in the weapon's frame (ocular, edgeX, edgeY), or null. */
        final float[][] lens;

        Scope(String prefix, OpticState.View view, float blocks, float[][] lens) {
            this.prefix = prefix;
            this.view = view;
            this.blocks = blocks;
            this.lens = lens;
        }
    }

    /** A head device: Tarkov night vision (Intensity, Color, noise) or thermal values, and its eyepiece mask. */
    static final class Head {
        final int kind;
        final OpticState.View view;
        final float intensity, r, g, b, noise, noiseScale, gainLimit, maskSize, maskAspect;
        final String mask;

        Head(int kind, OpticState.View view, float intensity, float r, float g, float b, float noise, float noiseScale, float gainLimit,
             String mask, float maskSize, float maskAspect) {
            this.kind = kind;
            this.view = view;
            this.intensity = intensity;
            this.r = r;
            this.g = g;
            this.b = b;
            this.noise = noise;
            this.noiseScale = noiseScale;
            this.gainLimit = gainLimit;
            this.mask = mask;
            this.maskSize = maskSize;
            this.maskAspect = maskAspect;
        }
    }

    static volatile Scope scope;
    /**
     * FB4 (19.10.2026, user's item 9: "switching the magnification of thermal sights ... for a second the picture is
     * without the thermal effect"): the scope picture is drawn on the render thread from the frame snapshot, a frame or
     * two behind the main thread that sets the channel's values every tick. Right after a mode switch the picture in
     * flight still has the previous mode's key, the values already the new one's: no match, and that picture went out
     * raw (the night scene with the sight's overlay). The values of the previous mode are kept briefly for such frames.
     */
    static final long PREVIOUS_NS = 500_000_000L, PREVIOUS_FRAMES = 6;
    static volatile Scope previous;
    static volatile long previousAt, previousFrame, previousUsed;
    static volatile Head head;
    /** The viewer sees infrared in the main frame (TW/Tactical viewerNVG: goggles, Night Vision Framework). */
    static volatile boolean irMain;
    /** Optic effects on (TW_Tactical OpticEffects) and the heat buffer's share of the picture's size. */
    static volatile boolean enabled = true;
    static volatile float heatScale = .5f;
    /** Thermal inputs of TW/OpticView (living, zombie, animal) and the range of warm things (tiles). */
    static volatile float living = 2f, zombie = 1.4f, animal = 1.8f, range = 100f;
    /** When Lua last sent the state: older than this (ms) and the main frame shows every class, as before. */
    static volatile long stateAt;
    static final long STATE_TIMEOUT = 2000;

    private static boolean registered;
    private static volatile boolean failed;
    private static volatile String status = "idle";
    static ViewpointPass.Batch lastBatch;

    // QA counters
    public static volatile long scopeImages, scopeHeld, scenes, lenses, lensImages, heads, heatFrames, errors;
    public static volatile double scopeMillis, lensMillis, headMillis;

    private VpSensor() {
    }

    // ------------------------------------------------------------------ Lua (main thread)

    static float num(KahluaTable t, String key, float def) {
        Object v = t == null ? null : t.rawget(key);
        return v instanceof Number n && Double.isFinite(n.doubleValue()) ? n.floatValue() : def;
    }

    static float[] vector(KahluaTable t, String key) {
        Object v = t == null ? null : t.rawget(key);
        if (!(v instanceof KahluaTable p)) {
            return null;
        }
        float[] out = new float[3];
        for (int i = 0; i < 3; i++) {
            Object n = p.rawget(i + 1);
            if (!(n instanceof Number d) || !Double.isFinite(d.doubleValue())) {
                return null;
            }
            out[i] = d.floatValue();
        }
        return out;
    }

    /** The scope channel's image: TW/OpticView javaView of its mode plus prefix, blocks and, at 1x, geometry; nil clears. */
    public static void setScope(KahluaTable t) {
        install();
        if (t == null) {
            scope = null;
            VpSensorGL.lensPrefix = "";
            return;
        }
        Object prefix = t.rawget("prefix");
        OpticState.View v = OpticState.read(t);
        if (!(prefix instanceof String p) || v.kind == 0) {
            scope = null;
            VpSensorGL.lensPrefix = "";
            return;
        }
        float[][] lens = null;
        Object g = t.rawget("geometry");
        if (g instanceof KahluaTable geometry) {
            float[] ocular = vector(geometry, "ocular"), edgeX = vector(geometry, "edgeX"), edgeY = vector(geometry, "edgeY");
            if (ocular != null && edgeX != null && edgeY != null) {
                lens = new float[][] {ocular, edgeX, edgeY};
            }
        }
        Scope next = new Scope(p, v, num(t, "blocks", 0f), lens), now = scope;
        if (now != null && !now.prefix.equals(p)) {
            previous = now;
            previousAt = System.nanoTime();
            previousFrame = FpRecoil.worldFrame();
        }
        scope = next;
    }

    /** The values for a scope picture of this key: the current channel's, else (right after a switch) the previous one's. */
    static Scope selectFor(String key, String kind) {
        Scope s = select(scope, key, kind);
        if (s == null && (System.nanoTime() - previousAt < PREVIOUS_NS || FpRecoil.worldFrame() - previousFrame <= PREVIOUS_FRAMES)) {
            s = select(previous, key, kind);
            if (s != null) previousUsed++;
        }
        return s;
    }

    /**
     * W10 nopip2: the sensor stage has device values for a picture of this key and kind (ScopePipZoom asks before it
     * builds the camera zoom's sensor picture); no counter is touched - VpSensor.sensor selects them again.
     */
    static boolean wants(String key, String kind) {
        if (failed || !enabled || ScopePipStages.sensor == null || !ViewpointBridge.ready()) {
            return false;
        }
        return select(scope, key, kind) != null || (System.nanoTime() - previousAt < PREVIOUS_NS
                || FpRecoil.worldFrame() - previousFrame <= PREVIOUS_FRAMES) && select(previous, key, kind) != null;
    }

    /** The head device's image ({kind=nv|thermal, ...} as TW_ViewpointSensors sends it); nil clears. */
    public static void setHead(KahluaTable t) {
        install();
        if (t == null) {
            head = null;
            return;
        }
        Object kind = t.rawget("kind"), mask = t.rawget("mask");
        int k = "thermal".equals(kind) ? OpticState.THERMAL : "nv".equals(kind) ? OpticState.NIGHT_VISION : 0;
        if (k == 0) {
            head = null;
            return;
        }
        head = new Head(k, OpticState.read(t), num(t, "intensity", 2f), num(t, "r", .33f), num(t, "g", 1f), num(t, "b", .27f),
                num(t, "noise", 0f), num(t, "noiseScale", 1f), num(t, "gainLimit", 1f), mask instanceof String m ? m : null,
                num(t, "maskSize", 1f), num(t, "maskAspect", 1f));
    }

    public static void setState(boolean ir, boolean on, double scale) {
        irMain = ir;
        enabled = on;
        heatScale = Double.isFinite(scale) ? (float) Math.max(.25, Math.min(1, scale)) : .5f;
        stateAt = System.currentTimeMillis();
    }

    /** Once a game: the thermal inputs; the warm things of a previous world are dropped. */
    public static void setConfig(KahluaTable t) {
        VpHeat.reset();
        lastBatch = null;
        living = num(t, "living", living);
        zombie = num(t, "zombie", zombie);
        animal = num(t, "animal", animal);
        range = Math.max(10f, Math.min(200f, num(t, "range", range)));
    }

    /** Lasers and lights should submit infrared now: a night vision scope channel or night vision on the head. */
    public static boolean irWanted() {
        Scope s = scope;
        Head h = head;
        return active() && (irMain || s != null && s.view.kind == OpticState.NIGHT_VISION || h != null && h.kind == OpticState.NIGHT_VISION);
    }

    static boolean active() {
        return enabled && !failed && ViewpointBridge.ready() && ViewpointBridge.state() > 0;
    }

    /** Read-only identity of a successfully drawn unmagnified sensor window. */
    static boolean lensWindow(String prefix) {
        Scope s = scope;
        return active() && ViewpointBridge.state() == 1 && s != null && s.lens != null
                && s.prefix.equals(prefix) && prefix.equals(VpSensorGL.lensPrefix);
    }

    // ------------------------------------------------------------------ the frame (main thread)

    /** The scope stages, once (a stage that failed stays off: ScopePipStages switches it off). */
    static synchronized void install() {
        if (registered) {
            return;
        }
        registered = true;
        ScopePipStages.scene = VpSensor::scene;
        ScopePipStages.sensor = VpSensor::sensor;
    }

    /** IsoWorld.render exit, right after the scope picture was queued: a 1x sight's eyepiece of this frame. */
    static void afterWorld(ViewpointPass.Batch b) {
        install();
        Scope s = scope;
        if (s == null || !active()) {
            return;
        }
        // The palettes and grain of the images, loaded here (main thread) for the render thread.
        VpSensorGL.textures(null);
        if (s.lens == null || ViewpointBridge.state() != 1) {
            return;
        }
        try {
            IsoPlayer player = IsoPlayer.players[0];
            if (player == null || !HipFireInput.aimIntent(player) || player.getPrimaryHandItem() == null
                    || !s.prefix.startsWith(player.getPrimaryHandItem().getID() + ":")) {
                return;
            }
            KahluaTable drawn = TWZB.weaponFrame(player);
            if (drawn == null) {
                return;
            }
            // The drawn weapon (after the pose layer), as ScopePipWeaponSnapshot places the scope's lens.
            double[] weapon = new double[12];
            double c = Math.cos(player.getLookAngleRadians()), sn = Math.sin(player.getLookAngleRadians());
            for (int i = 0; i < 12; i += 3) {
                double u = ((Number) drawn.rawget(i + 1)).doubleValue();
                double v = ((Number) drawn.rawget(i + 2)).doubleValue();
                double h = ((Number) drawn.rawget(i + 3)).doubleValue();
                weapon[i] = u * c - v * sn + (i == 0 ? player.getX() : 0);
                weapon[i + 1] = u * sn + v * c + (i == 0 ? player.getY() : 0);
                weapon[i + 2] = h / ViewpointBridge.LEVEL + (i == 0 ? player.getZ() : 0);
            }
            Vector3f[] lens = new Vector3f[3];
            for (int i = 0; i < 3; i++) {
                Vector3f p = ScopePipGeometry.world(weapon, s.lens[i]);
                lens[i] = ScopePipGeometry.scene(p, b.origin, ViewpointBridge.lift(b.relief, p.x, p.y));
            }
            MetalFrame.queue(new VpSensorGL.LensDrawer(b, s, lens));
            lenses++;
        } catch (Throwable t) {
            fail("lens", t);
        }
    }

    /** The batch takes no more primitives (main thread, screen composite queued): what it shows, its heat. */
    static void close(ViewpointPass.Batch b) {
        lastBatch = b;
        if (System.currentTimeMillis() - stateAt > STATE_TIMEOUT || !active()) {
            b.show = SHOW_ALL;
            return;
        }
        Scope s = scope;
        Head h = head;
        boolean headThermal = h != null && h.kind == OpticState.THERMAL;
        // W10 nopip2: in the camera zoom of a night vision or thermal channel the main frame is that channel's picture -
        // it shows what the picture shows (none of our light through a thermal, infrared through night vision).
        String zoomed = ScopePipZoom.sensorKind();
        b.show = zoomed != null ? scopeShow(zoomed, mainShow(h, irMain)) : mainShow(h, irMain);
        // A thermal scope picture (drawn, or its eyepiece at 1x): ScopePip warms up before its first picture.
        // FB5 item 5: or requested - a frame whose picture was skipped (the kick of a shot) cleared ready() until the
        // next success, and the pictures drawn meanwhile had no heat at all (pale, no silhouettes) for 2-3 frames.
        // nopip2: or the camera zoom of a thermal channel (its picture is this frame).
        boolean scopeThermal = s != null && s.view.kind == OpticState.THERMAL
                && (s.lens != null || ScopePip.ready() || heatOnRequest && ScopePip.hasWindowRequest() || "thermal".equals(zoomed));
        if (scopeThermal || headThermal) {
            try {
                // A head device can be enabled before the first optical PIP.
                // Its captured model records have the same guarded interface.
                if (!ViewpointPipBridge.detect()) throw new IllegalStateException("thermal model interface unavailable");
                VpHeat.prepare();
                b.heat = VpHeat.capture(b, IsoPlayer.players[0], living, zombie, animal, range);
                heatFrames++;
            } catch (Throwable t) {
                fail("heat", t);
            }
        }
    }

    /** The screen composite was queued (OpticRenderer.queue in Viewpoint's view): the head device over it. */
    static void queueHead() {
        Head h = head;
        ViewpointPass.Batch b = lastBatch;
        if (h == null || b == null || !active()) {
            return;
        }
        try {
            MultiTextureFBO2 buffer = Core.getInstance().offscreenBuffer;
            Texture world = buffer == null || buffer.current == null ? null : buffer.getTexture(0);
            if (world == null) {
                return;
            }
            int[] rect = {IsoCamera.getScreenLeft(0), IsoCamera.getScreenTop(0), IsoCamera.getScreenWidth(0), IsoCamera.getScreenHeight(0),
                    Core.width, Core.height};
            MetalFrame.queue(new VpSensorGL.HeadDrawer(b, h, world, rect, ViewpointBridge.state() == 1));
            heads++;
        } catch (Throwable t) {
            fail("head", t);
        }
    }

    // ------------------------------------------------------------------ scope stages (render thread)

    /** Classes the main frame shows: no light through a thermal head device, infrared only through night vision. */
    static int mainShow(Head h, boolean ir) {
        if (h != null && h.kind == OpticState.THERMAL) {
            return 0;
        }
        return SHOW_VISIBLE | SHOW_MUZZLE | (ir || h != null ? SHOW_IR : 0);
    }

    /** The scope values for a picture of this ScopePip key and TWSight kind, or null (another channel or mode). */
    static Scope select(Scope s, String key, String kind) {
        if (s == null || key == null || !key.startsWith(s.prefix)) {
            return null;
        }
        return (s.view.kind == OpticState.THERMAL ? "thermal" : "nv").equals(kind) ? s : null;
    }

    /** Classes a scope picture of this kind shows: none through a thermal one, infrared through night vision. */
    static int scopeShow(String kind, int mainShow) {
        if ("thermal".equals(kind)) {
            return 0;
        }
        return SHOW_VISIBLE | SHOW_MUZZLE | ("nv".equals(kind) ? SHOW_IR : mainShow & SHOW_IR);
    }

    static void scene(ScopePipStages.Frame f) throws Throwable {
        if (failed || !ViewpointBridge.ready()) {
            return;
        }
        ViewpointPass.Batch b = ViewpointPass.batchOf(ViewpointBridge.currentScene());
        if (b != null && ViewpointPass.drawScope(f, b, scopeShow(f.kind, b.show))) {
            scenes++;
        }
    }

    static int sensor(ScopePipStages.Frame f) throws Throwable {
        Scope s = selectFor(f.key, f.kind);
        if (failed || !enabled || s == null || !ViewpointBridge.ready()) {
            return 0;
        }
        ViewpointPass.Batch b = ViewpointPass.batchOf(ViewpointBridge.currentScene());
        try {
            return VpSensorGL.scope(f, s, b == null ? null : b.heat);
        } catch (Throwable t) {
            fail("scope", t);
            return 0;
        }
    }

    // ------------------------------------------------------------------ errors and QA

    /** An error with its causes and first frames, for one log line. */
    static String describe(Throwable t) {
        StringBuilder m = new StringBuilder(String.valueOf(t));
        for (Throwable c = t.getCause(); c != null && c != t; c = c.getCause()) m.append(" <- ").append(c);
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        StackTraceElement[] at = root.getStackTrace();
        for (int i = 0; i < Math.min(4, at.length); i++) m.append(i == 0 ? " at " : " | ").append(at[i]);
        return m.toString();
    }

    /** Any error: the sensors off for the session, one log line; Viewpoint's view and the scope picture go on. */
    static void fail(String where, Throwable t) {
        errors++;
        if (!failed) {
            failed = true;
            status = "off: " + where + " " + describe(t);
            System.out.println("[TWZB] viewpoint sensors " + status);
        }
    }

    public static boolean failed() {
        return failed;
    }

    /** QA: the state, what was drawn and its cost. */
    /** QA (FB5, A/B in one run): false - the scope's heat waits for a drawn picture again, as before. */
    static volatile boolean heatOnRequest = true;

    /** QA: " scopeLens=cx,cy,ux,uy,vx,vy" of the last sensor frame's eyepiece (GL pixels, y up), or none. */
    static String lens(float[] l) {
        return l == null || l.length < 6 ? " scopeLens=none" : String.format(java.util.Locale.ROOT, " scopeLens=%.1f,%.1f,%.1f,%.1f,%.1f,%.1f",
                l[0], l[1], l[2], l[3], l[4], l[5]);
    }

    public static String info() {
        Scope s = scope;
        Head h = head;
        return "sensors=" + (failed ? status : enabled ? "on" : "off (option)") + " vp=" + ViewpointBridge.state()
                + " scope=" + (s == null ? "none" : (s.view.kind == OpticState.THERMAL ? "thermal" : "nv") + (s.lens != null ? "@1x" : "") + " " + s.prefix)
                + " head=" + (h == null ? "none" : (h.kind == OpticState.THERMAL ? "thermal" : "nv") + " mask=" + h.mask)
                + " irMain=" + irMain + " irWanted=" + irWanted() + " stageScene=" + (ScopePipStages.scene != null)
                + " stageSensor=" + (ScopePipStages.sensor != null) + " scopeImages=" + scopeImages + " scopeHeld=" + scopeHeld + " scopePrevious=" + previousUsed
                + " scenes=" + scenes + " scopeDrawn=" + ViewpointPass.scopeDrawn + " lenses=" + lenses + " lensImages=" + lensImages + " heads=" + heads
                + " heatFrames=" + heatFrames + " heatScale=" + heatScale
                + " zoomPictures=" + VpSensorGL.zoomPictures + " zoomDepthless=" + VpSensorGL.zoomDepthless
                + lens(VpSensorGL.scopeLens)
                + String.format(java.util.Locale.ROOT, " scopeMs=%.3f lensMs=%.3f headMs=%.3f gpuMs=%.3f gpuSamples=%d",
                        scopeMillis, lensMillis, headMillis, VpSensorGL.gpuMillis, VpSensorGL.gpuSamples)
                + " errors=" + errors + " | heat " + VpHeat.stats();
    }
}
