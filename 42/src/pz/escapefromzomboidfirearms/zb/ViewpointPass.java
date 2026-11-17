package pz.escapefromzomboidfirearms.zb;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL43;

import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * The mod's perspective pass in the Viewpoint frame (VP_R1_RENDER_RU.md 3.4).
 * One batch per Viewpoint frame, opened on the exit of IsoWorld.render
 * (PatchMuzzle.AfterWorld) right after Viewpoint queued its SceneDrawer for
 * that frame, drawn on the render thread by one of two paths:
 *   HDR (preferred)  the exit of WorldRenderer.drawWeather inside Viewpoint's
 *                    frame (PatchViewpoint): into its HDR target
 *                    (WorldRenderer.bindTarget) at the render resolution, with
 *                    the frame's TAA-jittered matrix; Viewpoint's fog, bloom,
 *                    exposure, tone mapping, anti-aliasing and upscaling then
 *                    act on our effects as on the scene;
 *   post (fallback)  the batch's own GenericDrawer right after the SceneDrawer:
 *                    over Viewpoint's finished image in the world frame buffer
 *                    (the output resolution, the matrix without jitter); used
 *                    when the HDR path did not draw the batch (the advice not
 *                    applied, a pass without drawWeather, or switched off).
 * Either way the output is before the screen composite and the UI, so it is in
 * the frame without HUD that Viewpoint's frame generation reads.
 *
 * Primitives come in PZ world coordinates (x, y tiles, z levels) on the main
 * thread between that point and the screen composite (OnPostRender:
 * TW/LaserRender, TW/LightRender; later the muzzle effects) and go into the
 * scene space of that very frame at once, as Viewpoint places its own lights:
 *   sx = camX - x,  sy = (z - camZ) * sqrt 6 + lift(x, y),  sz = camY - y
 * (lift: the frame's relief, SceneData.relief). On the render thread they are
 * projected on the CPU with the frame's matrix, clipped at the near plane, and
 * drawn as screen triangles that carry 1 / view distance (UVs over it:
 * perspective correct). The fragment shader compares that distance with
 * Viewpoint's scene depth (WorldRenderer.targets.depth: render resolution,
 * linearised with the frame's projection) and fades the fragment within a
 * softness instead of a hard test (TAA jitter in the post path; our traces are
 * not exactly the scene). Depth is never written. Drawing is raw GL (own
 * vertex array and buffer, own samplers); every state touched is saved and
 * restored, inside Viewpoint's frame without PZ's state cache.
 *
 * Kinds: strips (polylines with a width in pixels and in the world and a soft
 * profile across), sprites (camera-facing quads of a world radius with a
 * smallest size in pixels) and world quads (oriented, textured); alpha or
 * additive blending; pixel sizes are given for 1080 lines.
 *
 * Every primitive also has a class (VpSensor, VP_W5_T): visible light, infrared
 * (submitted between TWZB.vpSensorMark(1) and (0)) or a muzzle effect. A batch
 * shows the classes VpSensor chose for the main frame (infrared only through
 * night vision); drawScope draws the same primitives into the scope picture of
 * the frame with the scope camera, its depth and the scope channel's choice.
 */
public final class ViewpointPass {
    public static final int ALPHA = 0, ADD = 1;
    static final int MODE_PLAIN = 0, MODE_TEXTURE = 1, MODE_RIBBON = 2;
    static final int MODE_MUZZLE_MARKER = 3 + MuzzleFx.KIND_MARKER;
    /** Nearest view distance drawn, scene units (Viewpoint's near plane: 0.05). */
    static final float NEAR = 0.06f;
    /** Pixel sizes of the primitives are for this many output lines. */
    static final float REFERENCE_LINES = 1080f;
    /** Thinnest strip and smallest sprite rasterised (pixels); thinner ones keep their light as alpha. */
    static final float MIN_STRIP_PX = 1.25f, MIN_SPRITE_PX = 0.75f;
    /** Floats per vertex: x, y (output pixels, y down), 1/w, u/w, v/w, r, g, b, a. */
    static final int STRIDE = 9;

    // main thread
    static Batch open;
    /** The batch opened last (QA reads its relief between frames). */
    static volatile Batch lastOpened;
    static long lastFrame = Long.MIN_VALUE;
    static final Map<String, Texture> textures = new HashMap<>();
    /** The batch of each of Viewpoint's frame scenes (FP keeps three frames, one per render state). */
    private static final Map<Object, Batch> byScene = new IdentityHashMap<>();

    /** Class of the primitives submitted now (main thread, TWZB.vpSensorMark): VpSensor.CLS_VISIBLE or CLS_IR. */
    static volatile int mark = VpSensor.CLS_VISIBLE;

    /** QA: the HDR path on (off: always the post pass) and the colour scale of our effects in it. */
    public static volatile boolean hdrEnabled = true;
    public static volatile float hdrGain = 1f;

    // QA counters
    public static volatile long batches, stale, prims, dropped, drawn, hdrDrawn, postDrawn, triangles, runs, depthless, errors;
    public static volatile String lastError = "";
    /** A String, or the last draw's Targets, turned into text by stats(). */
    public static volatile Object lastTargets = "";
    public static volatile long muzzleRuns, muzzleMissing, muzzlePending;
    /** QA only: sample the selected frame's real geometry; no GL readbacks or per-frame logging. */
    static volatile boolean probeEnabled, muzzleMarker;
    static volatile long probeGeneration;
    static volatile String muzzleProbe = "awaiting", laserProbe = "awaiting", markerProbe = "awaiting";

    // render thread
    static TwProgram program;
    static TwProgram muzzleProgram;

    /** Render thread: the pass program, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (program == null) {
            program = ShaderWarmup.made(TwProgram.create("tw_vp_pass"));
        }
    }

    /** Render thread: the muzzle program, at the first use or ahead of it (ShaderWarmup). */
    static void muzzleShaders() {
        if (muzzleProgram == null) {
            muzzleProgram = ShaderWarmup.made(TwProgram.create("tw_vp_muzzle"));
        }
    }
    static int vao, vbo, linearSampler, nearestSampler, repeatSampler;
    static FloatBuffer buffer;
    /** Enable with -Dtw.vp.profile=true; CPU submission/draw time, not a GPU timer. */
    public static volatile boolean profile = Boolean.getBoolean("tw.vp.profile");
    public static volatile long timedFrames, drawNanos, maxDrawNanos;
    static long logFrames, logNanos, logMax;

    private ViewpointPass() {
    }

    // ------------------------------------------------------------------ main thread

    /** IsoWorld.render has run (main thread): open the batch of this Viewpoint frame, or none. */
    public static void afterWorld() {
        open = null;
        mark = VpSensor.CLS_VISIBLE;
        try {
            Object frame = ViewpointBridge.frameNow();
            if (frame == null) {
                return;
            }
            long number = ViewpointBridge.frameNumber(frame);
            if (number == lastFrame) {
                // Viewpoint did not snapshot a frame for this render state: nothing of its to draw over.
                stale++;
                return;
            }
            lastFrame = number;
            Batch b = new Batch();
            b.frame = frame;
            b.scene = ViewpointBridge.sceneOf(frame);
            b.relief = ViewpointBridge.origin(frame, b.origin);
            synchronized (byScene) {
                byScene.put(b.scene, b);
            }
            open = b;
            lastOpened = b;
            SpriteRenderer.instance.drawGeneric(b);
            ScopePip.afterWorld();
            VpSensor.afterWorld(b);
            ImpactFx.afterWorld();
            batches++;
        } catch (Throwable t) {
            fail("frame", t);
        }
    }

    /**
     * The exit of WorldRenderer.drawWeather (render thread, PatchViewpoint): the
     * batch of the scene Viewpoint draws now into its HDR target. The render
     * state of that frame was handed over by the main thread: the batch is final.
     */
    public static void afterWeather() {
        // FB3 item 4: the defocus of the own weapon and hands first, our effects of the frame over it
        SeeThrough.afterWeather();
        try {
            TpAimDot.afterWeather();
        } catch (Throwable ignored) {
        }
        if (!hdrEnabled || !ViewpointBridge.ready() || VpShaderPackCompat.isIrisDrawn()) {
            return;
        }
        try {
            Object scene = ViewpointBridge.currentScene();
            Batch b;
            synchronized (byScene) {
                b = scene == null ? null : byScene.get(scene);
            }
            if (b == null || b.frame == null) {
                return;
            }
            b.seal();
            if (b.isEmpty() && !MuzzleHeatModel.vpPending()) {
                b.hdr = true;
                return;
            }
            int[] fbo = {GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)};
            int[] viewport = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            try {
                ViewpointBridge.bindTarget();
                draw(b, true);
            } finally {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo[0]);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo[1]);
                GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            }
            b.hdr = true;
            hdrDrawn++;
        } catch (Throwable t) {
            fail("hdr", t);
        }
    }

    /** The screen composite was queued (main thread): this frame takes no more primitives. */
    public static void close() {
        Batch b = open;
        open = null;
        if (b != null) {
            b.seal();
            VpSensor.close(b);
        }
    }

    /** A batch is open: primitives submitted now are drawn in this Viewpoint frame. */
    public static boolean accepting() {
        return open != null;
    }

    /** The eye and the view direction of the open frame in PZ coordinates: x, y, z (levels), dx, dy, dz (levels per unit), or false. */
    static boolean eye(double[] out) {
        Batch b = open;
        float[] dir = new float[3];
        if (b == null || !ViewpointBridge.lookDirection(dir)) {
            return false;
        }
        float[] o = b.origin;
        double x = o[0] - o[3], y = o[1] - o[5];
        out[0] = x;
        out[1] = y;
        out[2] = o[2] + (o[4] - ViewpointBridge.lift(b.relief, x, y)) / ViewpointBridge.LEVEL;
        out[3] = -dir[0];
        out[4] = -dir[2];
        out[5] = dir[1] / ViewpointBridge.LEVEL;
        return true;
    }

    static Texture texture(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Texture t = textures.get(name);
        if (t == null && !textures.containsKey(name)) {
            t = Texture.getSharedTexture("media/textures/" + name + ".png", 0);
            textures.put(name, t);
        }
        return t;
    }

    static boolean add(Prim p) {
        Batch b = open;
        p.cls = p.mode >= 3 ? VpSensor.CLS_MUZZLE : mark;
        if (b == null || !b.add(p)) {
            dropped++;
            return false;
        }
        prims++;
        return true;
    }

    static void colour(Prim p, float r, float g, float b, float a, int blend, float soft) {
        p.r = r;
        p.g = g;
        p.b = b;
        p.a = a;
        p.blend = blend == ADD ? ADD : ALPHA;
        p.soft = Math.max(1e-3f, soft);
    }

    /**
     * A polyline of n points (x, y, z levels, alpha each) with a soft profile
     * across: half width = max(widthPx at 1080 lines, worldWidth in scene
     * units at the point's distance) / 2.
     */
    public static boolean strip(double[] xyza, int n, float r, float g, float b, float a, float widthPx, float worldWidth, int blend, float soft) {
        Batch bt = open;
        if (bt == null || n < 2) {
            dropped++;
            return false;
        }
        Strip s = new Strip();
        s.n = n;
        s.pts = new float[n * 3];
        s.alpha = new float[n];
        for (int i = 0; i < n; i++) {
            bt.scene(xyza[i * 4], xyza[i * 4 + 1], xyza[i * 4 + 2], s.pts, i * 3);
            s.alpha[i] = (float) xyza[i * 4 + 3];
        }
        straighten(bt, xyza, n, s.pts);
        s.mode = MODE_RIBBON;
        s.widthPx = widthPx;
        s.worldWidth = worldWidth;
        colour(s, r, g, b, a, blend, soft);
        boolean added = add(s);
        if (added && bt.probing && bt.probeLaser == null) {
            s.probeWorld = new double[] {xyza[0], xyza[1], xyza[2]};
            bt.probeLaser = s;
        }
        return added;
    }

    /**
     * A strip is a straight line in PZ space (a laser beam): Viewpoint's relief lift (Batch.scene)
     * at its two ends only, linear between them by the distance along it. Lifted point by point, a
     * long beam followed the far relief and bent like a hook (FB4-A, user report 19.10.2026,
     * item 16); now it stays straight and still ends on its lifted spot. Two points are untouched.
     * Performance pass 23.10.2026: the distances along the beam in a scratch (main thread: strip, the lights' QA).
     */
    static void straighten(Batch bt, double[] xyza, int n, float[] pts) {
        if (n < 3) {
            return;
        }
        int e = (n - 1) * 4;
        float l0 = pts[1] - (float) ((xyza[2] - bt.origin[2]) * ViewpointBridge.LEVEL);
        float l1 = pts[(n - 1) * 3 + 1] - (float) ((xyza[e + 2] - bt.origin[2]) * ViewpointBridge.LEVEL);
        if (alongScratch.length < n) alongScratch = new double[n];
        double[] along = alongScratch;
        along[0] = 0; // as the fresh array's first element
        for (int i = 1; i < n; i++) {
            double dx = xyza[i * 4] - xyza[i * 4 - 4], dy = xyza[i * 4 + 1] - xyza[i * 4 - 3];
            double dz = (xyza[i * 4 + 2] - xyza[i * 4 - 2]) * ViewpointBridge.LEVEL;
            along[i] = along[i - 1] + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        for (int i = 1; i < n - 1; i++) {
            double t = along[n - 1] > 1e-9 ? along[i] / along[n - 1] : (double) i / (n - 1);
            pts[i * 3 + 1] = (float) ((xyza[i * 4 + 2] - bt.origin[2]) * ViewpointBridge.LEVEL + l0 + (l1 - l0) * t);
        }
    }

    /**
     * A camera-facing quad of a world radius (scene units, at least minPx at
     * 1080 lines), moved bias scene units toward the camera (a spot on a
     * surface stays in front of it).
     */
    public static boolean sprite(double x, double y, double z, float radius, float minPx, float r, float g, float b, float a,
                                 String texture, int blend, float soft, float bias) {
        Batch bt = open;
        if (bt == null) {
            dropped++;
            return false;
        }
        Sprite s = new Sprite();
        bt.scene(x, y, z, s.p, 0);
        s.radius = radius;
        s.minPx = minPx;
        s.bias = bias;
        s.texture = texture(texture);
        s.mode = s.texture == null ? MODE_PLAIN : MODE_TEXTURE;
        colour(s, r, g, b, a, blend, soft);
        return add(s);
    }

    /** An oriented world quad: 4 corners (x, y, z levels) in order around it, UVs (0,0) (1,0) (1,1) (0,1), an alpha per corner. */
    public static boolean quad(double[] corners, float r, float g, float b, float[] alpha, String texture, int blend, float soft, float bias) {
        Batch bt = open;
        if (bt == null) {
            dropped++;
            return false;
        }
        Quad q = new Quad();
        for (int i = 0; i < 4; i++) {
            bt.scene(corners[i * 3], corners[i * 3 + 1], corners[i * 3 + 2], q.p, i * 3);
            q.alpha[i] = alpha[i];
        }
        q.bias = bias;
        q.texture = texture(texture);
        q.mode = q.texture == null ? MODE_PLAIN : MODE_TEXTURE;
        colour(q, r, g, b, 1f, blend, soft);
        return add(q);
    }

    /**
     * A muzzle quad in this frame: four world centres (height in tiles, as in
     * MuzzleFx), world-sized offsets in the camera plane, atlas UVs and RGBA
     * per corner. A ribbon uses the first two centres as its endpoints and
     * two world half widths. Geometry is expanded on the render thread with
     * the actual camera, then clipped with UV and colour at the near plane.
     */
    /**
     * FB4 (19.10.2026, the user's item 11: "fire and smoke of a shot do not come out of the barrel" - the flash about
     * 8 cm above the front sight, standing at a brick wall): every primitive point is lifted by Viewpoint's relief at its
     * own place, but the shooter's drawn model (and its weapon) by the relief where the shooter stands; with the muzzle
     * over another relief height than the shooter's square (a kerb, the pavement along a wall) the effects sat that much
     * above or below the barrel. While set (main thread, MuzzleFx.buildVp), points take the lift at this anchor instead
     * (NaN: at their own place).
     */
    static double anchorX = Double.NaN, anchorY = Double.NaN;

    static void anchor(double x, double y) {
        anchorX = x;
        anchorY = y;
    }

    static void anchorOff() {
        anchorX = anchorY = Double.NaN;
    }

    /** QA: Viewpoint's relief lift (scene units) at a world point in the frame opened last, or NaN without one. */
    static double liftNow(double x, double y) {
        Batch b = lastOpened;
        return b == null ? Double.NaN : ViewpointBridge.lift(b.relief, x, y);
    }

    static boolean muzzle(float[] centres, float[] offsets, float[] uv, float[] rgba, float[] widths, boolean endCap,
                          String texture, String noise, int kind, float distortion, float scroll) {
        Batch bt = open;
        if (bt == null) {
            dropped++;
            return false;
        }
        Quad q = new Quad();
        for (int i = 0; i < 4; i++) {
            bt.scene(centres[i * 3], centres[i * 3 + 1], centres[i * 3 + 2] / ViewpointBridge.LEVEL, q.p, i * 3);
            q.alpha[i] = rgba[i * 4 + 3];
        }
        q.offsets = offsets == null ? null : offsets.clone();
        q.uv = uv.clone();
        q.colours = rgba.clone();
        q.widths = widths == null ? null : widths.clone();
        q.endCap = endCap;
        // HeatBounds surround an opaque part: move its centre to the visible
        // surface before the soft depth test, still hidden by intervening walls.
        q.bias = endCap && widths != null ? Math.max(widths[0], widths[1]) + 0.025f : 0f;
        // MuzzleFx data stores bare names in TW_Muzzle, unlike the Lua laser/light names.
        // Use its resolver/cache for both images; a missing diffuse used to skip every muzzle run.
        q.texture = MuzzleFx.texture(texture);
        q.noise = MuzzleFx.texture(noise);
        q.mode = 3 + kind;
        q.distortion = distortion;
        q.scroll = scroll;
        colour(q, 1, 1, 1, 1, kind == MuzzleFx.KIND_SMOKE || kind == MuzzleFx.KIND_STRAND || kind == MuzzleFx.KIND_MARKER ? ALPHA : ADD, 0.025f);
        boolean added = add(q);
        if (added && bt.probing && (kind == MuzzleFx.KIND_MARKER || bt.probeMuzzle == null
                || kind == MuzzleFx.KIND_JET && bt.probeMuzzle.mode != 3 + MuzzleFx.KIND_JET)) {
            q.probeWorld = new double[] {centres[0], centres[1], centres[2] / ViewpointBridge.LEVEL};
            q.probeTexture = MuzzleFx.texturePath(texture);
            q.probeNoise = MuzzleFx.texturePath(noise);
            q.probePhase = MuzzleFx.qaPhase;
            if (kind == MuzzleFx.KIND_MARKER) {
                bt.probeMarker = q;
            } else {
                bt.probeMuzzle = q;
            }
        }
        return added;
    }

    private static double[] alongScratch = new double[16];

    /** Squared scene distance for main-thread particle sorting; no isometric alpha/visibility gate. */
    static double distance(float x, float y, float height) {
        Batch bt = open;
        if (bt == null) {
            return 0;
        }
        float[] p = new float[3];
        bt.scene(x, y, height / ViewpointBridge.LEVEL, p, 0);
        double dx = p[0] - bt.origin[3], dy = p[1] - bt.origin[4], dz = p[2] - bt.origin[5];
        return dx * dx + dy * dy + dz * dz;
    }

    /** An error in the pass: as any error with Viewpoint, the whole bridge goes off (ViewpointBridge.fail). */
    static void fail(String where, Throwable t) {
        errors++;
        String message = where + ": " + t;
        if (!message.equals(lastError)) {
            lastError = message;
            System.out.println("[TWZB] viewpoint pass " + message);
            t.printStackTrace(System.out);
        }
        ViewpointBridge.fail("pass " + where, t);
    }

    /** QA: "batches= stale= prims= dropped= drawn= hdr= post= triangles= runs= depthless= errors= hdrOn= gain= targets=". */
    public static String stats() {
        return "batches=" + batches + " stale=" + stale + " prims=" + prims + " dropped=" + dropped + " drawn=" + drawn + " hdr=" + hdrDrawn
                + " post=" + postDrawn + " triangles=" + triangles + " runs=" + runs + " depthless=" + depthless + " errors=" + errors
                + " hdrOn=" + hdrEnabled + " gain=" + hdrGain + " targets=" + lastTargets
                + " muzzleRuns=" + muzzleRuns + " muzzleMissing=" + muzzleMissing + " muzzlePending=" + muzzlePending
                + " profile=" + profile + " timedFrames=" + timedFrames + " drawNs=" + drawNanos + " maxDrawNs=" + maxDrawNanos
                + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }

    // ------------------------------------------------------------------ primitives

    static void probing(boolean on) {
        probeEnabled = false;
        probeGeneration++;
        muzzleProbe = laserProbe = markerProbe = "awaiting";
        probeEnabled = on;
    }

    static String probe() {
        return "enabled=" + probeEnabled + " marker=" + muzzleMarker + " | muzzle " + muzzleProbe
                + " | laser " + laserProbe + " | marker " + markerProbe;
    }

    static void record(Batch bt, Prim pr, Proj p, Out out, int start, boolean hdr) {
        if (!bt.probing || !probeEnabled || bt.probeToken != probeGeneration) {
            return;
        }
        if (pr == bt.probeMuzzle) { muzzleProbe = sample(bt, pr, p, out, start, hdr); }
        if (pr == bt.probeLaser) { laserProbe = sample(bt, pr, p, out, start, hdr); }
        if (pr == bt.probeMarker) { markerProbe = sample(bt, pr, p, out, start, hdr); }
    }

    /** The centre and the emitted bounds, with the very matrix/viewport used by this draw. */
    static String sample(Batch bt, Prim pr, Proj p, Out out, int start, boolean hdr) {
        float[] point = pr instanceof Quad q ? q.p : ((Strip) pr).pts;
        float[] clip = new float[4];
        p.clip(point[0], point[1], point[2], clip, 0);
        float x = p.sx(clip[0], clip[3]), y = p.sy(clip[1], clip[3]);
        float width = 0, height = 0;
        if (pr instanceof Quad q) {
            if (q.offsets != null) {
                float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = maxX;
                for (int i = 0; i < 4; i++) {
                    minX = Math.min(minX, q.offsets[i * 2]); maxX = Math.max(maxX, q.offsets[i * 2]);
                    minY = Math.min(minY, q.offsets[i * 2 + 1]); maxY = Math.max(maxY, q.offsets[i * 2 + 1]);
                }
                width = maxX - minX; height = maxY - minY;
            } else if (q.widths != null) {
                width = 2f * Math.max(q.widths[0], q.widths[1]);
                height = (float) Math.sqrt(Math.pow(q.p[3] - q.p[0], 2) + Math.pow(q.p[4] - q.p[1], 2) + Math.pow(q.p[5] - q.p[2], 2));
            }
        } else if (pr instanceof Strip s) {
            width = s.worldWidth;
            height = (float) Math.sqrt(Math.pow(s.pts[3] - s.pts[0], 2) + Math.pow(s.pts[4] - s.pts[1], 2) + Math.pow(s.pts[5] - s.pts[2], 2));
        }
        float minX = Float.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = maxX;
        for (int i = start; i < out.n; i++) {
            int at = i * STRIDE;
            minX = Math.min(minX, out.v[at]); maxX = Math.max(maxX, out.v[at]);
            minY = Math.min(minY, out.v[at + 1]); maxY = Math.max(maxY, out.v[at + 1]);
        }
        boolean emitted = out.n > start, front = clip[3] >= NEAR;
        float r = pr.r, g = pr.g, b = pr.b, alpha = pr.a;
        if (emitted) {
            int at = start * STRIDE;
            r = out.v[at + 5]; g = out.v[at + 6]; b = out.v[at + 7]; alpha = out.v[at + 8];
        } else if (pr instanceof Quad q) {
            alpha *= q.alpha[0];
            if (q.colours != null) { r *= q.colours[0]; g *= q.colours[1]; b *= q.colours[2]; }
        } else if (pr instanceof Strip s) {
            alpha *= s.alpha[0];
        }
        String texture = pr.mode == MODE_MUZZLE_MARKER || pr.mode == MODE_PLAIN || pr.mode == MODE_RIBBON ? "procedural"
                : pr.texture == null ? "missing" : pr.texture.isReady() ? "ready" : "pending";
        String noise = pr.probeNoise == null ? "none" : pr.noise == null ? "missing" : pr.noise.isReady() ? "ready" : "pending";
        double[] w = pr.probeWorld;
        return String.format(java.util.Locale.ROOT,
                "frame=%d path=%s mode=%d phase=%.3f world=(%.6f,%.6f,%.6f levels) heightTiles=%.6f scene=(%.6f,%.6f,%.6f)"
                + " clip=(%.6f,%.6f,%.6f,%.6f) pixel=(%.2f,%.2f) viewport=%.0fx%.0f sceneSize=(%.6f,%.6f)"
                + " pixels=(%.2f,%.2f) rgb=(%.4f,%.4f,%.4f) alpha=%.6f vertices=%d front=%s inViewport=%s"
                + " texture=%s:%s noise=%s:%s soft=%.4f",
                bt.probeFrame, hdr ? "hdr" : "post", pr.mode, pr.probePhase, w[0], w[1], w[2], w[2] * ViewpointBridge.LEVEL, point[0], point[1], point[2],
                clip[0], clip[1], clip[2], clip[3], x, y, p.vw, p.vh, width, height, emitted ? maxX - minX : 0f, emitted ? maxY - minY : 0f,
                r, g, b, alpha, out.n - start, front, front && x >= 0 && y >= 0 && x <= p.vw && y <= p.vh,
                texture, pr.probeTexture == null ? "-" : pr.probeTexture, noise, pr.probeNoise == null ? "-" : pr.probeNoise, pr.soft);
    }

    abstract static class Prim {
        int blend, mode;
        /** VpSensor.CLS_VISIBLE, CLS_IR or CLS_MUZZLE. */
        int cls;
        float soft, r, g, b, a;
        Texture texture;
        Texture noise;
        float distortion, scroll;
        double[] probeWorld;
        String probeTexture, probeNoise;
        float probePhase = -1f;

        abstract void emit(Proj p, Out out);

        boolean sameRun(Prim o) {
            return o != null && o.blend == blend && o.mode == mode && o.texture == texture && o.soft == soft
                    && o.noise == noise && o.distortion == distortion && o.scroll == scroll;
        }
    }

    static final class Strip extends Prim {
        float[] pts, alpha;
        int n;
        float widthPx, worldWidth;

        // Performance pass 23.10.2026 (render thread; emit never nests): scratch for the clip coordinates, the chain and
        // its sides, grown as needed - every element read is written first in the same emit, as with the fresh arrays.
        private static float[] clipScratch = new float[64], chainScratch = new float[64], sideScratch = new float[96];

        @Override
        void emit(Proj p, Out out) {
            if (clipScratch.length < n * 4) clipScratch = new float[n * 4];
            if (chainScratch.length < (n + 1) * 4) chainScratch = new float[(n + 1) * 4];
            float[] c = clipScratch;
            for (int i = 0; i < n; i++) {
                p.clip(pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2], c, i * 4);
            }
            // Runs of points in front of the near plane: x, y, w, alpha.
            float[] chain = chainScratch;
            int k = 0;
            for (int i = 0; i + 1 < n; i++) {
                float w0 = c[i * 4 + 3], w1 = c[i * 4 + 7];
                boolean in0 = w0 >= NEAR, in1 = w1 >= NEAR;
                if (!in0 && !in1) {
                    k = ribbon(p, out, chain, k);
                } else if (in0 && in1) {
                    if (k == 0) {
                        k = push(p, chain, k, c, i, 0f, alpha);
                    }
                    k = push(p, chain, k, c, i + 1, 0f, alpha);
                } else if (in0) {
                    if (k == 0) {
                        k = push(p, chain, k, c, i, 0f, alpha);
                    }
                    k = push(p, chain, k, c, i, (NEAR - w0) / (w1 - w0), alpha);
                    k = ribbon(p, out, chain, k);
                } else {
                    k = ribbon(p, out, chain, k);
                    k = push(p, chain, k, c, i, (NEAR - w0) / (w1 - w0), alpha);
                    k = push(p, chain, k, c, i + 1, 0f, alpha);
                }
            }
            ribbon(p, out, chain, k);
        }

        /** Point i of the clip coordinates, or t of the way to point i + 1. */
        static int push(Proj p, float[] chain, int k, float[] c, int i, float t, float[] alpha) {
            int from = i * 4, to = from + 4;
            float cx = c[from], cy = c[from + 1], cw = c[from + 3], al = alpha[i];
            if (t > 0f) {
                cx += (c[to] - cx) * t;
                cy += (c[to + 1] - cy) * t;
                cw += (c[to + 3] - cw) * t;
                al += (alpha[i + 1] - al) * t;
            }
            int o = k * 4;
            chain[o] = p.sx(cx, cw);
            chain[o + 1] = p.sy(cy, cw);
            chain[o + 2] = Math.max(NEAR, cw);
            chain[o + 3] = al;
            return k + 1;
        }

        int ribbon(Proj p, Out out, float[] chain, int m) {
            if (m < 2) {
                return 0;
            }
            if (sideScratch.length < m * 6) sideScratch = new float[m * 6];
            float[] side = sideScratch;
            float nx = 0f, ny = 1f;
            for (int j = 0; j < m; j++) {
                int prev = Math.max(0, j - 1) * 4, next = Math.min(m - 1, j + 1) * 4, o = j * 4;
                float dx = chain[next] - chain[prev], dy = chain[next + 1] - chain[prev + 1];
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len > 1e-4f) {
                    nx = -dy / len;
                    ny = dx / len;
                }
                float w = chain[o + 2];
                float half = 0.5f * Math.max(widthPx * p.ref, worldWidth * p.focal / w);
                float keep = 1f;
                if (half * 2f < MIN_STRIP_PX) {
                    keep = half * 2f / MIN_STRIP_PX;
                    half = MIN_STRIP_PX * 0.5f;
                }
                int s = j * 6;
                side[s] = chain[o] + nx * half;
                side[s + 1] = chain[o + 1] + ny * half;
                side[s + 2] = chain[o] - nx * half;
                side[s + 3] = chain[o + 1] - ny * half;
                side[s + 4] = 1f / w;
                side[s + 5] = a * chain[o + 3] * keep;
            }
            for (int j = 0; j + 1 < m; j++) {
                int s = j * 6, t = s + 6;
                out.vertex(side[s], side[s + 1], side[s + 4], 0f, 0f, this, side[s + 5]);
                out.vertex(side[s + 2], side[s + 3], side[s + 4], 0f, 1f, this, side[s + 5]);
                out.vertex(side[t], side[t + 1], side[t + 4], 0f, 0f, this, side[t + 5]);
                out.vertex(side[s + 2], side[s + 3], side[s + 4], 0f, 1f, this, side[s + 5]);
                out.vertex(side[t + 2], side[t + 3], side[t + 4], 0f, 1f, this, side[t + 5]);
                out.vertex(side[t], side[t + 1], side[t + 4], 0f, 0f, this, side[t + 5]);
            }
            return 0;
        }
    }

    static final class Sprite extends Prim {
        final float[] p = new float[3];
        float radius, minPx, bias;

        /** Performance pass 23.10.2026: render thread scratch (the point moved toward the camera, its clip coordinates). */
        private static final float[] moved = new float[3], clipped = new float[4];

        @Override
        void emit(Proj pr, Out out) {
            float[] q = moved;
            q[0] = p[0]; q[1] = p[1]; q[2] = p[2];
            pr.toward(q, 0, bias);
            float[] c = clipped;
            pr.clip(q[0], q[1], q[2], c, 0);
            float w = c[3];
            if (w < NEAR) {
                return;
            }
            float cx = pr.sx(c[0], w), cy = pr.sy(c[1], w);
            float rad = Math.max(minPx * pr.ref, radius * pr.focal / w), al = a;
            if (rad < MIN_SPRITE_PX) {
                al *= (rad / MIN_SPRITE_PX) * (rad / MIN_SPRITE_PX);
                rad = MIN_SPRITE_PX;
            }
            if (cx + rad < 0 || cy + rad < 0 || cx - rad > pr.vw || cy - rad > pr.vh) {
                return;
            }
            float iw = 1f / w;
            out.vertex(cx - rad, cy - rad, iw, 0f, 0f, this, al);
            out.vertex(cx + rad, cy - rad, iw, 1f, 0f, this, al);
            out.vertex(cx + rad, cy + rad, iw, 1f, 1f, this, al);
            out.vertex(cx - rad, cy - rad, iw, 0f, 0f, this, al);
            out.vertex(cx + rad, cy + rad, iw, 1f, 1f, this, al);
            out.vertex(cx - rad, cy + rad, iw, 0f, 1f, this, al);
        }
    }

    static final class Quad extends Prim {
        final float[] p = new float[12], alpha = new float[4];
        float[] offsets, uv, colours, widths;
        boolean endCap;
        float bias;
        static final float[] U = {0f, 1f, 1f, 0f}, V = {0f, 0f, 1f, 1f};
        /**
         * Performance pass 23.10.2026: render thread scratch of one emit (emit never nests): the corners moved toward
         * the camera, their clip coordinates, the energy and screen plane per corner, the clipped polygon. Each is
         * written before it is read, as the arrays allocated per quad were.
         */
        private static final float[] movedCorners = new float[12], clipCorners = new float[16], energyScratch = new float[4],
                planeScratch = new float[8], halfScratch = new float[2], polyScratch = new float[8 * 9];

        @Override
        void emit(Proj pr, Out out) {
            float[] q = movedCorners;
            System.arraycopy(p, 0, q, 0, 12);
            float[] c = clipCorners;
            for (int i = 0; i < 4; i++) {
                pr.toward(q, i * 3, bias);
                pr.clip(q[i * 3], q[i * 3 + 1], q[i * 3 + 2], c, i * 4);
            }
            float[] plane = offsets;
            float[] energy = energyScratch;
            energy[0] = energy[1] = energy[2] = energy[3] = 1;
            if (widths != null) {
                plane = planeScratch;
                // Direction in screen space, valid even when an endpoint crosses the near plane.
                float dx = c[4] * c[3] - c[0] * c[7], dy = -(c[5] * c[3] - c[1] * c[7]);
                dx *= pr.vw; dy *= pr.vh;
                float len = (float) Math.sqrt(dx * dx + dy * dy);
                if (len < 1e-8f) { dx = 1; dy = 0; len = 1; }
                dx /= len; dy /= len;
                float[] half = halfScratch;
                half[0] = widths[0];
                half[1] = widths[1];
                for (int i = 0; i < 4; i++) {
                    int end = i == 1 || i == 2 ? 1 : 0;
                    float w = Math.max(NEAR, c[i * 4 + 3]);
                    float least = MIN_STRIP_PX * 0.5f * w / pr.focal;
                    float h = Math.max(half[end], least);
                    energy[i] = Math.min(1f, half[end] / least);
                    float side = i < 2 ? -1f : 1f;
                    plane[i * 2] = -dy * h * side;
                    plane[i * 2 + 1] = -dx * h * side;
                    // A heat zone seen end-on still has the cross-section of the part.
                    float screenLength = (float) Math.hypot(c[4] / Math.max(NEAR, c[7]) - c[0] / Math.max(NEAR, c[3]),
                            (c[5] / Math.max(NEAR, c[7]) - c[1] / Math.max(NEAR, c[3])) * pr.vh / pr.vw) * pr.vw * 0.5f;
                    if (endCap && screenLength < 2f * h * pr.focal / w) {
                        float reach = (end == 0 ? -1f : 1f) * (h - screenLength * 0.5f * w / pr.focal);
                        plane[i * 2] += dx * reach;
                        plane[i * 2 + 1] -= dy * reach;
                    }
                }
            }
            if (plane != null) {
                for (int i = 0; i < 4; i++) {
                    c[i * 4] += plane[i * 2] * 2f * pr.focal / pr.vw;
                    c[i * 4 + 1] += plane[i * 2 + 1] * 2f * pr.focal / pr.vh;
                }
            }
            // Clipped polygon: x, y, w, u, v, r, g, b, alpha. All attributes follow the clip.
            float[] poly = polyScratch;
            int m = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + 1) % 4;
                float wi = c[i * 4 + 3], wj = c[j * 4 + 3];
                if (wi >= NEAR) {
                    m = corner(pr, poly, m, c, energy, i, i, 0f);
                }
                if ((wi >= NEAR) != (wj >= NEAR)) {
                    m = corner(pr, poly, m, c, energy, i, j, (NEAR - wi) / (wj - wi));
                }
            }
            for (int k = 1; k + 1 < m; k++) {
                put(out, poly, 0);
                put(out, poly, k);
                put(out, poly, k + 1);
            }
        }

        int corner(Proj pr, float[] poly, int m, float[] c, float[] energy, int i, int j, float t) {
            float cx = c[i * 4] + (c[j * 4] - c[i * 4]) * t, cy = c[i * 4 + 1] + (c[j * 4 + 1] - c[i * 4 + 1]) * t;
            float cw = Math.max(NEAR, c[i * 4 + 3] + (c[j * 4 + 3] - c[i * 4 + 3]) * t);
            int o = m * 9;
            poly[o] = pr.sx(cx, cw);
            poly[o + 1] = pr.sy(cy, cw);
            poly[o + 2] = cw;
            float ui = uv == null ? U[i] : uv[i * 2], uj = uv == null ? U[j] : uv[j * 2];
            float vi = uv == null ? V[i] : uv[i * 2 + 1], vj = uv == null ? V[j] : uv[j * 2 + 1];
            poly[o + 3] = ui + (uj - ui) * t;
            poly[o + 4] = vi + (vj - vi) * t;
            for (int k = 0; k < 3; k++) {
                float ci = colours == null ? 1f : colours[i * 4 + k] * (blend == ADD ? energy[i] : 1f);
                float cj = colours == null ? 1f : colours[j * 4 + k] * (blend == ADD ? energy[j] : 1f);
                poly[o + 5 + k] = ci + (cj - ci) * t;
            }
            float ai = alpha[i] * (blend == ALPHA ? energy[i] : 1f), aj = alpha[j] * (blend == ALPHA ? energy[j] : 1f);
            poly[o + 8] = ai + (aj - ai) * t;
            return m + 1;
        }

        void put(Out out, float[] poly, int k) {
            int o = k * 9;
            out.vertex(poly[o], poly[o + 1], 1f / poly[o + 2], poly[o + 3], poly[o + 4], this, a * poly[o + 8]);
            int v = (out.n - 1) * STRIDE;
            out.v[v + 5] *= poly[o + 5];
            out.v[v + 6] *= poly[o + 6];
            out.v[v + 7] *= poly[o + 7];
        }
    }

    /** The frame's projection to output pixels and the camera, render thread. */
    static final class Proj {
        final float[] m = new float[16];
        float camX, camY, camZ, vw, vh, focal, ref;

        Proj(org.joml.Matrix4f vp, org.joml.Matrix4f view, org.joml.Matrix4f projection, int[] viewport) {
            float[] v = {vp.m00(), vp.m01(), vp.m02(), vp.m03(), vp.m10(), vp.m11(), vp.m12(), vp.m13(),
                    vp.m20(), vp.m21(), vp.m22(), vp.m23(), vp.m30(), vp.m31(), vp.m32(), vp.m33()};
            System.arraycopy(v, 0, m, 0, 16);
            // The camera: -R^T t of the (affine) view matrix.
            float t0 = view.m30(), t1 = view.m31(), t2 = view.m32();
            camX = -(view.m00() * t0 + view.m01() * t1 + view.m02() * t2);
            camY = -(view.m10() * t0 + view.m11() * t1 + view.m12() * t2);
            camZ = -(view.m20() * t0 + view.m21() * t1 + view.m22() * t2);
            vw = viewport[2];
            vh = viewport[3];
            focal = projection.m11() * vh * 0.5f;
            ref = vh / REFERENCE_LINES;
        }

        /** Clip coordinates of a scene point (column-major m): x, y, z, w into c[o..o + 3]. */
        void clip(float x, float y, float z, float[] c, int o) {
            c[o] = m[0] * x + m[4] * y + m[8] * z + m[12];
            c[o + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
            c[o + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
            c[o + 3] = m[3] * x + m[7] * y + m[11] * z + m[15];
        }

        float sx(float cx, float cw) {
            return (cx / cw * 0.5f + 0.5f) * vw;
        }

        float sy(float cy, float cw) {
            return (0.5f - cy / cw * 0.5f) * vh;
        }

        /** Moves the point q[o..o + 2] bias units toward the camera (along its view ray: same pixel, nearer). */
        void toward(float[] q, int o, float bias) {
            if (bias <= 0f) {
                return;
            }
            float dx = camX - q[o], dy = camY - q[o + 1], dz = camZ - q[o + 2];
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < 1e-4f) {
                return;
            }
            float k = Math.min(bias, d * 0.5f) / d;
            q[o] += dx * k;
            q[o + 1] += dy * k;
            q[o + 2] += dz * k;
        }
    }

    /** Screen vertices and the runs of one state (blend, mode, texture, softness). */
    static final class Out {
        float[] v = new float[STRIDE * 384];
        int n;
        final ArrayList<Prim> keys = new ArrayList<>();
        /** Performance pass 23.10.2026: the first vertex of each run (keys.size() of them) as ints, not boxed. */
        int[] starts = new int[64];

        void begin(Prim p) {
            if (keys.isEmpty() || !p.sameRun(keys.get(keys.size() - 1))) {
                if (keys.size() == starts.length) starts = java.util.Arrays.copyOf(starts, starts.length * 2);
                starts[keys.size()] = n;
                keys.add(p);
            }
        }

        /** Empty again for the next draw, its arrays kept (render thread; the batch's primitives are not kept). */
        Out reset() {
            n = 0;
            keys.clear();
            return this;
        }

        void vertex(float x, float y, float invW, float u, float t, Prim p, float alpha) {
            if ((n + 1) * STRIDE > v.length) {
                v = java.util.Arrays.copyOf(v, v.length * 2);
            }
            int o = n * STRIDE;
            v[o] = x;
            v[o + 1] = y;
            v[o + 2] = invW;
            v[o + 3] = u * invW;
            v[o + 4] = t * invW;
            v[o + 5] = Math.min(1f, p.r);
            v[o + 6] = Math.min(1f, p.g);
            v[o + 7] = Math.min(1f, p.b);
            v[o + 8] = Math.max(0f, Math.min(1f, alpha));
            n++;
        }
    }

    // ------------------------------------------------------------------ the drawer


    /** One Viewpoint frame's primitives: filled on the main thread, drawn on the render thread. */
    static final class Batch extends TextureDraw.GenericDrawer {
        final float[] origin = new float[6];
        Object frame, scene, relief;
        /** Drawn by the HDR path (render thread): the post pass leaves it. */
        volatile boolean hdr;
        /** Classes the main frame shows (bits 1 << VpSensor.CLS_*), set when the batch closes. */
        volatile int show = VpSensor.SHOW_ALL;
        /** The warm things of this frame for a thermal view (VpHeat.Frame), or null; set when the batch closes. */
        volatile VpHeat.Frame heat;
        private final ArrayList<Prim> list = new ArrayList<>();
        private boolean sealed;
        /** The drawers of this frame that run inside it under the Metal shim (MetalFrame); null: they have run. */
        private ArrayList<MetalFrame.Tail> tails = new ArrayList<>();
        final boolean probing = probeEnabled;
        final long probeToken = probeGeneration, probeFrame = lastFrame;
        Quad probeMuzzle, probeMarker;
        Strip probeLaser;

        synchronized boolean add(Prim p) {
            if (sealed) {
                return false;
            }
            list.add(p);
            return true;
        }

        synchronized void seal() {
            sealed = true;
        }

        /** Main thread: false once the frame's exit has run its drawers. */
        synchronized boolean addTail(MetalFrame.Tail tail) {
            if (tails == null) {
                return false;
            }
            tails.add(tail);
            return true;
        }

        /** Render thread, at the frame's exit: the drawers, in the order they were queued. */
        synchronized List<MetalFrame.Tail> takeTails() {
            List<MetalFrame.Tail> taken = tails == null ? List.of() : tails;
            tails = null;
            return taken;
        }

        synchronized boolean isEmpty() {
            return list.isEmpty();
        }

        /** A PZ world point (x, y tiles, z levels) in this frame's scene space into out[o..o + 2]. */
        void scene(double x, double y, double z, float[] out, int o) {
            out[o] = (float) (origin[0] - x);
            double ax = Double.isNaN(anchorX) ? x : anchorX, ay = Double.isNaN(anchorX) ? y : anchorY;
            out[o + 1] = (float) ((z - origin[2]) * ViewpointBridge.LEVEL + ViewpointBridge.lift(relief, ax, ay));
            out[o + 2] = (float) (origin[1] - y);
        }

        /** The post pass (fallback), right after Viewpoint's SceneDrawer; under the Metal shim from inside its frame (MetalFrame). */
        @Override
        public void render() {
            if (!GLCompat.metalPath()) {
                post();
            }
        }

        void post() {
            seal();
            if (hdr || isEmpty() && !MuzzleHeatModel.vpPending() || !ViewpointBridge.ready()) {
                return;
            }
            try {
                draw(this, false);
                postDrawn++;
            } catch (Throwable t) {
                fail("post", t);
            }
        }

        @Override
        public void postRender() {
            synchronized (this) {
                list.clear();
            }
            synchronized (byScene) {
                if (byScene.get(scene) == this) {
                    byScene.remove(scene);
                }
            }
            frame = null;
            scene = null;
            relief = null;
            heat = null;
        }
    }

    /** Render thread: the batch of a frame's scene (Viewpoint's FrameContext.scene now), or null. */
    static Batch batchOf(Object scene) {
        synchronized (byScene) {
            return scene == null ? null : byScene.get(scene);
        }
    }

    /**
     * Draws a batch into the bound target: hdr - Viewpoint's HDR target inside
     * its frame (the jittered matrix, our colour scale, no PZ state cache);
     * otherwise the world frame buffer after Viewpoint's frame.
     */
    static void draw(Batch bt, boolean hdr) throws IllegalAccessException {
        long start = profile ? System.nanoTime() : 0;
        try (PerfProbe.Scope perf = PerfProbe.render(PerfProbe.EFFECTS)) {
            drawBatch(bt, hdr);
        } finally {
            if (start != 0) {
                long ns = System.nanoTime() - start;
                timedFrames++; drawNanos += ns; maxDrawNanos = Math.max(maxDrawNanos, ns);
                logFrames++; logNanos += ns; logMax = Math.max(logMax, ns);
                if (logFrames >= 120) {
                    System.out.println("[TWZB] viewpoint pass profile path=" + (hdr ? "hdr" : "post") + " frames=" + logFrames
                            + " meanNs=" + logNanos / logFrames + " maxNs=" + logMax + " | " + MuzzleFx.vpProfile());
                    logFrames = logNanos = logMax = 0;
                }
            }
        }
    }

    static void drawBatch(Batch bt, boolean hdr) throws IllegalAccessException {
        org.joml.Matrix4f vpm = hdr ? ViewpointBridge.jitteredViewProjection() : ViewpointBridge.viewProjection();
        org.joml.Matrix4f view = ViewpointBridge.view(), projection = ViewpointBridge.projection(bt.frame);
        if (vpm == null || view == null || projection == null) {
            return;
        }
        int[] t = new int[8];
        ViewpointBridge.targets(t);
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        if (viewport[2] <= 0 || viewport[3] <= 0) {
            return;
        }
        Proj p = new Proj(vpm, view, projection, viewport);
        Out out = batchOut.reset();
        ArrayList<Prim> list;
        synchronized (bt) {
            list = bt.list;
        }
        int show = bt.show;
        for (Prim pr : list) {
            if (!shown(pr, show)) {
                continue;
            }
            out.begin(pr);
            int start = out.n;
            pr.emit(p, out);
            if (bt.probing) { record(bt, pr, p, out, start, hdr); }
        }
        lastTargets = new Targets(hdr, t, viewport[2], viewport[3]);
        // The glow of hot weapons on their own surface (MuzzleHeatModel) is drawn even without primitives.
        boolean heat = MuzzleHeatModel.vpPending();
        if (out.n == 0 && !heat) {
            return;
        }
        // render width / depth texture width: the render target may be larger than what was drawn
        float sx = t[3] > 0 ? t[1] / (float) t[3] : 1f, sy = t[4] > 0 ? t[2] / (float) t[4] : 1f;
        submit(out, p, viewport, t[0] != 0 && t[1] > 0 && t[2] > 0 ? t[0] : 0, sx, sy, projection, hdr ? hdrGain : 1f, !hdr,
                heat ? bt.scene : null, vpm);
        drawn++;
    }

    /**
     * The scope picture of this frame (render thread, ScopePipStages scene
     * stage, only when it was rendered anew): the batch of the scene Viewpoint
     * drew it from, its primitives of the classes in show, projected with the
     * scope camera and depth-tested against the scope's own depth, into the
     * scope image (LDR, after its tone mapping: as the post pass). The caller
     * (ScopePip) saved and restores the GL state. False: nothing to draw.
     */
    static boolean drawScope(ScopePipStages.Frame f, Batch bt, int show) throws IllegalAccessException {
        if (bt == null || f.texture == 0 || f.size <= 0) {
            return false;
        }
        int[] viewport = {0, 0, f.size, f.size};
        org.joml.Matrix4f vpm = new org.joml.Matrix4f(f.projection).mul(f.view);
        Proj p = new Proj(vpm, f.view, f.projection, viewport);
        Out out = scopeOut.reset();
        ArrayList<Prim> list;
        synchronized (bt) {
            list = bt.list;
        }
        for (Prim pr : list) {
            if (!shown(pr, show)) {
                continue;
            }
            out.begin(pr);
            pr.emit(p, out);
        }
        if (out.n == 0) {
            return false;
        }
        if (scopeFbo == 0) {
            scopeFbo = GL30.glGenFramebuffers();
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, scopeFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, f.texture, 0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("scope picture not drawable");
        }
        GL11.glViewport(0, 0, f.size, f.size);
        // The scope's depth is drawn at the picture's size; its texture may be larger.
        float scale = 1f;
        if (f.depth != 0) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, f.depth);
            int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            scale = width > 0 ? f.size / (float) width : 1f;
        }
        submit(out, p, viewport, f.depth, scale, scale, f.projection, 1f, false, null, null);
        scopeDrawn++;
        return true;
    }

    /** A primitive of a class in show (bits 1 << VpSensor.CLS_*). */
    static boolean shown(Prim p, int show) {
        return (show & 1 << p.cls) != 0;
    }

    static int scopeFbo;
    public static volatile long scopeDrawn;
    /** Performance pass 23.10.2026: render thread, one vertex list per kind of draw, reused (was 14 KB and boxed run starts each draw). */
    private static final Out batchOut = new Out(), scopeOut = new Out();
    /** The ortho matrix of the screen vertices (setOrtho2D writes all of it): one, not one per run. */
    private static final org.joml.Matrix4f ortho = new org.joml.Matrix4f();

    /** Performance pass 23.10.2026: the last draw's targets (QA, stats()), turned into text when read; it was built every draw. */
    private record Targets(boolean hdr, int[] t, int width, int height) {
        Targets {
            t = t.clone();
        }

        @Override public String toString() {
            return "path=" + (hdr ? "hdr" : "post") + " depth=" + t[0] + " render=" + t[1] + "x" + t[2] + " depthTex=" + t[3] + "x" + t[4]
                    + " output=" + t[5] + "x" + t[6] + " viewport=" + width + "x" + height + (t[7] != 0 ? " panini=on" : "");
        }
    }

    /**
     * The GL part of a draw: the screen vertices of out into the bound target
     * and viewport, with the soft test against depth (0: none) at
     * depthScale of its texture, the projection's linearisation and a colour
     * gain. restorePz: after Viewpoint's frame PZ draws on (its own state
     * cache is restored too). heatScene: the frame's scene whose hot weapons
     * get their glow on their own surface first (MuzzleHeatModel.vp, with the
     * frame's matrix heatVpm), or null.
     */
    static void submit(Out out, Proj p, int[] viewport, int depth, float sx, float sy, org.joml.Matrix4f projection, float gain,
                       boolean restorePz, Object heatScene, org.joml.Matrix4f heatVpm) {
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        int prevTex2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), prevSampler2 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), prevSampler1 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), prevSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_TEXTURE_BIT
                | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT | GL11.GL_POLYGON_BIT);
        try {
            shaders();
            if (!program.isCompiled()) {
                throw new IllegalStateException("viewpoint pass shader did not compile (tw_vp_pass)");
            }
            if (vao == 0) {
                vao = GL30.glGenVertexArrays();
                vbo = GL15.glGenBuffers();
                linearSampler = GL33.glGenSamplers();
                nearestSampler = GL33.glGenSamplers();
                repeatSampler = GL33.glGenSamplers();
                for (int s : new int[] {linearSampler, nearestSampler, repeatSampler}) {
                    int filter = s == nearestSampler ? GL11.GL_NEAREST : GL11.GL_LINEAR;
                    GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_MIN_FILTER, filter);
                    GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_MAG_FILTER, filter);
                    GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_WRAP_S, s == repeatSampler ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE);
                    GL33.glSamplerParameteri(s, GL11.GL_TEXTURE_WRAP_T, s == repeatSampler ? GL11.GL_REPEAT : GL12.GL_CLAMP_TO_EDGE);
                }
            }
            // Viewpoint's depth: a plain 2D depth-stencil texture at the render resolution; read only.
            boolean depthOn = depth != 0;
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
            if (depthOn) {
                // GL_DEPTH_STENCIL_TEXTURE_MODE is GL 4.3 / ARB_stencil_texturing: without it a depth-stencil texture reads depth
                depthOn = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE) == GL11.GL_NONE
                        && (!GLCompat.stencilTexturing()
                        || GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL43.GL_DEPTH_STENCIL_TEXTURE_MODE) != GL11.GL_STENCIL_INDEX);
            }
            GL33.glBindSampler(1, nearestSampler);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL33.glBindSampler(0, linearSampler);
            if (!depthOn) {
                depthless++;
            }
            // No depth test or write of the GL, no culling; colour only (the target's alpha stays).
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            GL11.glColorMask(true, true, true, false);
            if (heatScene != null && heatVpm != null) {
                // Under the frame's effects (smoke over the hot barrel): the hot parts' own surface, additive.
                MuzzleHeatModel.vp(heatScene, heatVpm, projection, depthOn ? depth : 0, sx, sy, viewport, gain);
            }
            if (out.n == 0) {
                return;
            }
            program.Start();
            program.setValue("ModelViewProjection", ortho.setOrtho2D(0, p.vw, p.vh, 0));
            program.setSamplerUnit("DIFFUSE", 0);
            program.setSamplerUnit("SceneDepth", 1);
            program.setVector4("Viewport", viewport[0], viewport[1], viewport[2], viewport[3]);
            program.setVector2("DepthScale", sx, sy);
            // view distance = m32 / (ndc z + m22) of the frame projection (the jitter moves x, y only)
            program.setVector2("DepthLinear", projection.m32(), projection.m22());
            program.setValue("Gain", gain);
            if (buffer == null || buffer.capacity() < out.n * STRIDE) {
                buffer = BufferUtils.createFloatBuffer(Math.max(out.n * STRIDE, STRIDE * 1024));
            }
            buffer.clear();
            buffer.put(out.v, 0, out.n * STRIDE).flip();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
            int bytes = STRIDE * 4;
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, bytes, 0L);
            GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, bytes, 20L);
            GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, bytes, 12L);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glEnableVertexAttribArray(2);
            for (int run = 0; run < out.keys.size(); run++) {
                int start = out.starts[run], end = run + 1 < out.keys.size() ? out.starts[run + 1] : out.n;
                if (end <= start) {
                    continue;
                }
                Prim key = out.keys.get(run);
                Texture tex = key.texture != null && key.texture.isReady() ? key.texture : null;
                int mode = key.mode == MODE_TEXTURE && tex == null ? MODE_PLAIN : key.mode;
                boolean muzzle = mode >= 3;
                if (muzzle && mode != MODE_MUZZLE_MARKER && tex == null) {
                    if (key.texture == null) { muzzleMissing++; } else { muzzlePending++; }
                    continue;
                }
                TwProgram active = program;
                if (muzzle) {
                    muzzleShaders();
                    if (!muzzleProgram.isCompiled()) {
                        throw new IllegalStateException("viewpoint muzzle shader did not compile (tw_vp_muzzle)");
                    }
                    active = muzzleProgram;
                    active.Start();
                    active.setValue("ModelViewProjection", ortho.setOrtho2D(0, p.vw, p.vh, 0));
                    active.setSamplerUnit("DIFFUSE", 0);
                    active.setSamplerUnit("SceneDepth", 1);
                    active.setSamplerUnit("NoiseTex", 2);
                    active.setVector4("Viewport", viewport[0], viewport[1], viewport[2], viewport[3]);
                    active.setVector2("DepthScale", sx, sy);
                    active.setVector2("DepthLinear", projection.m32(), projection.m22());
                    active.setValue("Gain", gain);
                    boolean noiseOn = key.noise != null && key.noise.isReady();
                    active.setVector2("Smoke", noiseOn ? key.distortion : 0f, key.scroll);
                    GL13.glActiveTexture(GL13.GL_TEXTURE2);
                    GL33.glBindSampler(2, repeatSampler);
                    GL11.glBindTexture(GL11.GL_TEXTURE_2D, noiseOn ? key.noise.getID() : 0);
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);
                } else {
                    program.Start();
                }
                GL33.glBindSampler(0, mode == 3 + MuzzleFx.KIND_STRAND ? repeatSampler : linearSampler);
                if (key.blend == ADD) {
                    GL11.glBlendFunc(muzzle ? GL11.GL_ONE : GL11.GL_SRC_ALPHA, GL11.GL_ONE);
                } else {
                    GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
                }
                active.setVector4("Params", muzzle ? mode - 3 : mode, key.soft, depthOn ? 1f : 0f, 0f);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex == null ? 0 : tex.getID());
                GL11.glDrawArrays(GL11.GL_TRIANGLES, start, end - start);
                runs++;
                if (muzzle) { muzzleRuns++; }
            }
            triangles += out.n / 3;
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE2);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex2);
            GL33.glBindSampler(2, prevSampler2);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex1);
            GL33.glBindSampler(1, prevSampler1);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
            GL33.glBindSampler(0, prevSampler0);
            GL30.glBindVertexArray(prevVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevBuffer);
            GL11.glPopAttrib();
            GL13.glActiveTexture(prevActive);
            Texture.lastTextureID = -1;
            GLCompat.useProgram(prevProgram);
            if (restorePz) {
                // After Viewpoint's frame PZ draws on: its own state cache is restored too.
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                SpriteRenderer.ringBuffer.restoreBoundTextures = true;
                SpriteRenderer.ringBuffer.restoreVbos = true;
                GLStateRenderThread.restore();
            }
        }
    }
}
