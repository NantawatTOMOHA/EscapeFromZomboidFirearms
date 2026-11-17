package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.nio.FloatBuffer;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector4f;
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

import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.textures.Texture;

/**
 * See-through weapon and hands in Viewpoint's first person (FB3, the user's item 4, 18.10.2026:
 * "an optional switch - a semi-transparent and blurred weapon and hands while aiming and in High
 * Ready, except the front sight of iron sights, which stays in focus while aiming; as in real life,
 * where the brain cuts off the objects covering the field of view").
 *
 *   fade   Every model record of a Viewpoint scene carries a screen-door fade (lo, hi): a fragment
 *          is dropped where the frame's noise is below lo or at/above hi, and the noise changes
 *          every frame, so Viewpoint's temporal anti-aliasing turns a kept share into translucency
 *          with the real scene behind (the scene stays in the G-buffer). At the start of the world
 *          pass (WorldRenderer.begin, after FpViewLatch) the local player's own records (SELF: the
 *          body with its arms, the weapon and its parts; never glass) get (0, share) while the
 *          effect is on, one share for the whole model (the user's follow-ups the same day: "the
 *          sight model itself must be transparent too, only its reticle or mark is not"; "the option
 *          sets the transparency of the whole model"). Kept opaque while aiming through irons: the
 *          part of the front sight (the irons' mark). An optic's or collimator's reticle is drawn
 *          apart - in its picture, on its glass - where neither fade nor blur reach. A captured
 *          frame drawn again gets its own values back first.
 *   blur   At the exit of WorldRenderer.drawWeather (its HDR target at the render resolution,
 *          before fog, bloom, tone mapping and anti-aliasing, and before our own effects of the
 *          frame): a pixel nearer than the far end of the weapon (Viewpoint's depth) shows the
 *          weapon or the hands this frame - it takes the mean of the near pixels around it (a
 *          defocused near object); a pixel of the scene around them blends toward that mean by the
 *          share of near pixels found (the soft edge of a defocused outline). The front sight's
 *          post keeps a sharp circle while aiming through irons.
 *
 * The effect follows the pose: its visible aiming (FpAdsCamera.weight) or its High Ready
 * (FpPose highW), in first person only. Every Viewpoint member used is in MEMBERS (exact type,
 * checked once with ViewpointBridge.resolve); a missing or changed one leaves this off with one
 * log line. No Viewpoint code, shader or data is copied: only its record layout is read.
 */
public final class SeeThrough {
    static final String[][] MEMBERS = {
        {"viewpoint.render.SceneData", "models", "Lviewpoint/render/ModelDraws;", ""},
        {"viewpoint.render.ModelDraws", "values", "[F", ""},
        {"viewpoint.render.ModelDraws", "flags", "[I", ""},
        {"viewpoint.render.ModelDraws", "count", "I", ""},
        {"viewpoint.render.ModelDraws", "instances", "[Lzombie/core/skinnedmodel/model/ModelInstance;", ""},
        {"viewpoint.render.ModelDraws", "VALUES", "I", "static"},
        {"viewpoint.render.ModelDraws", "SELF", "I", "static"},
        {"viewpoint.render.ModelDraws", "GLASS", "I", "static"},
        {"viewpoint.render.ModelDraws", "FADE", "I", "static"},
        {"viewpoint.render.ModelDraws", "SPHERE", "I", "static"},
        {"viewpoint.render.SceneData", "projection", "Lorg/joml/Matrix4f;", ""},
    };
    /** The blur radius option is in pixels of a 1080-line image; other heights scale it. */
    static final float REFERENCE_HEIGHT = 1080f;
    /** A sight's circle kept sharp: this many post widths around the apex, at least MIN_SHARP pixels (1080 lines). */
    static final float SHARP_WIDTHS = 6f, MIN_SHARP = 10f;
    /** The soft edge: a scene pixel next to the weapon moves toward it by this times the share of near samples. */
    static final float HALO = 0.5f;
    /** Pixels nearer than the weapon's far end times this are the weapon's and the hands'. */
    static final float NEAR_MARGIN = 1.04f;

    // options (TW_SeeThrough.lua, every tick)
    public static volatile boolean enabled;
    /** Opacity of the weapon and the hands at the full effect, 0.1..0.9. */
    public static volatile float opacity = 0.5f;
    /** Blur radius at the full effect, pixels at 1080 lines (0: translucency only). */
    public static volatile float blurPx = 5f;
    /** The sight of the moment (TW_SeeThrough.lua): the front sight part's mesh and post apex, the active optic's or collimator's mesh. */
    static volatile Sight sight = new Sight("", 0, 0, 0, 0, "");

    public static volatile long frames, faded, sightFaded, kept, restored, blurred, blurPixels, errors, misses;
    public static volatile float lastWeight, lastShare, lastNear, lastRadius, lastSharpX, lastSharpY, lastSharpR;
    public static volatile double blurMillis;
    public static volatile String status = "unchecked", lastError = "";

    record Sight(String front, float x, float y, float z, float width, String sight) { }

    /** What one drawing changed in a scene's records: the captured fade of each and the share written, put back before the next. */
    static final class Applied {
        int[] index = new int[0];
        float[] saved = new float[0];
        float[] written = new float[0];
        int n;
    }

    private static boolean checked;
    private static volatile boolean ready;
    private static Field models, values, flags, count, instances, projectionOf;
    private static int stride, self, glass, fade, sphere;
    // render thread only
    private static final Map<Object, Applied> applied = new IdentityHashMap<>();
    private static float frameWeight, frameAds, frameShare;
    private static Object frameScene;
    private static TwProgram program;

    /** Render thread: the see-through program, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (program == null) {
            program = ShaderWarmup.made(TwProgram.create("tw_vp_seethrough"));
        }
    }
    private static int vao, vbo, copyTex, copyFbo, copyW, copyH, nearestSampler;
    private static FloatBuffer quad;
    private static float seed;

    private SeeThrough() { }

    static synchronized boolean detect() {
        if (checked) {
            return ready && ViewpointBridge.ready();
        }
        if (!ViewpointBridge.ready() && !ViewpointBridge.detect()) {
            return false;
        }
        checked = true;
        try {
            Member[] found = new Member[MEMBERS.length];
            for (int i = 0; i < found.length; i++) {
                found[i] = ViewpointBridge.resolve(MEMBERS[i], SeeThrough.class.getClassLoader());
                if (found[i] == null) {
                    status = "member " + MEMBERS[i][0] + "." + MEMBERS[i][1] + " " + MEMBERS[i][2] + " missing or changed";
                    System.out.println("[TWZB] Viewpoint see-through weapon: " + status + ", off (bridge stays on)");
                    return false;
                }
            }
            models = (Field) found[0];
            values = (Field) found[1];
            flags = (Field) found[2];
            count = (Field) found[3];
            instances = (Field) found[4];
            stride = ((Field) found[5]).getInt(null);
            self = ((Field) found[6]).getInt(null);
            glass = ((Field) found[7]).getInt(null);
            fade = ((Field) found[8]).getInt(null);
            sphere = ((Field) found[9]).getInt(null);
            projectionOf = (Field) found[10];
            if (stride < 32 || stride > 4096 || fade < 16 || fade + 2 > stride || sphere < 16 || sphere + 4 > stride
                    || self == 0 || glass == 0) {
                status = "unexpected record layout stride=" + stride + " fade=" + fade + " sphere=" + sphere;
                System.out.println("[TWZB] Viewpoint see-through weapon: " + status + ", off (bridge stays on)");
                return false;
            }
            ready = true;
            status = "on";
        } catch (Throwable t) {
            status = "lookup failed: " + t;
            System.out.println("[TWZB] Viewpoint see-through weapon: " + status + ", off (bridge stays on)");
            return false;
        }
        return true;
    }

    static void error(String where, Throwable t) {
        if (t instanceof ViewpointBridge.DataMiss) {
            misses++;
            ViewpointBridge.miss("see-through " + where, t.getMessage());
            return;
        }
        errors++;
        String message = where + ": " + t;
        if (!message.equals(lastError)) {
            lastError = message;
            System.out.println("[TWZB] Viewpoint see-through weapon " + message + ", off (bridge stays on)");
        }
        ready = false;
        status = "off after error: " + message;
    }

    // ------------------------------------------------------------------ options (Lua thread)

    public static void options(boolean on, double opacityShare, double blur) {
        enabled = on;
        if (Double.isFinite(opacityShare)) {
            opacity = (float) Math.max(0.1, Math.min(0.9, opacityShare));
        }
        if (Double.isFinite(blur)) {
            blurPx = (float) Math.max(0, Math.min(20, blur));
        }
    }

    /** The current sight: the front sight part's mesh and the post's apex/width in its local units (irons), the active optic's or collimator's mesh. */
    public static void sight(String front, double x, double y, double z, double width, String optic) {
        String f = front == null ? "" : front, k = optic == null ? "" : optic;
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || !Double.isFinite(width)) {
            f = "";
        }
        sight = new Sight(f, (float) x, (float) y, (float) z, (float) Math.max(0, width), k);
    }

    // ------------------------------------------------------------------ pure parts (offline checks)

    /**
     * The share of fragments kept so that, with the soft edge drawn over the scene pixels between
     * them (HALO times the share of near samples, which inside the weapon is that share), the weapon
     * shows the opacity asked: s + (1 - s) h s = target.
     */
    public static float share(float target, float halo) {
        float t = Math.max(0f, Math.min(1f, target));
        if (halo <= 1e-4f) {
            return t;
        }
        double b = 1 + halo, disc = b * b - 4 * halo * t;
        return (float) Math.max(0, Math.min(1, (b - Math.sqrt(Math.max(0, disc))) / (2 * halo)));
    }

    /** The effect's weight (0..1) from the pose: the visible aiming or the High Ready, whichever is more. */
    public static float weight(double adsVisible, double highReady, double fp) {
        double high = FpAdsCamera.smooth(highReady) * Math.max(0, Math.min(1, fp));
        double w = Math.max(Math.max(0, Math.min(1, adsVisible)), high);
        return (float) (Double.isFinite(w) ? w : 0);
    }

    /** The opacity shown at weight w: the option's at the full effect, opaque without it. */
    public static float opacityAt(float w, float option) {
        return 1f - Math.max(0f, Math.min(1f, w)) * (1f - option);
    }

    // ------------------------------------------------------------------ render thread

    /** The weight now and the aiming part of it, from the local pose (any thread; published state only). */
    static float[] poseWeight() {
        IsoPlayer p = IsoPlayer.getInstance();
        if (p == null || !FpViewLatch.layer()) {
            return null;
        }
        FpPose.Request r = FpPose.requests.get(p);
        if (r == null) {
            return null;
        }
        double ads = FpAdsCamera.weight(r.ads, Math.max(r.obstacle, r.highW), r.pullUntil, r.fpW);
        return new float[] {weight(ads, r.highW, r.fpW), (float) Math.max(0, Math.min(1, ads))};
    }

    /** Entry of WorldRenderer.begin (FpViewLatch.Begin, after the latch): the fade of the frame's own records. */
    /** drawn: the records this drawing shows when they are not the captured ones (Viewpoint 0.1.7, FpViewLatch.blendExit). */
    public static void begin(Object scene, Object drawn) {
        frameScene = null;
        frameWeight = 0;
        if (scene == null || !detect()) {
            return;
        }
        try {
            Object draws = drawn != null ? drawn : models.get(scene);
            if (draws == null) {
                return;
            }
            float[] data = (float[]) values.get(draws);
            Applied a = applied.get(scene);
            if (a != null) {
                restore(a, data);
            }
            if (!enabled || ViewpointBridge.state() != 1) {
                return;
            }
            float[] w = poseWeight();
            if (w == null || w[0] <= 1e-3f) {
                lastWeight = 0;
                return;
            }
            float s = share(opacityAt(w[0], opacity), blurPx > 0 ? HALO : 0f);
            if (s >= 0.999f) {
                return;
            }
            if (a == null) {
                a = new Applied();
                applied.put(scene, a);
            }
            frames++;
            faded += apply(draws, data, s, w[1] > 0.01f ? sight : null, a);
            frameScene = scene;
            frameWeight = w[0];
            frameAds = w[1];
            frameShare = s;
            lastWeight = w[0];
            lastShare = s;
        } catch (Throwable t) {
            error("begin", t);
        }
    }

    public static void begin(Object scene) {
        begin(scene, null);
    }

    /** Fades every own record but glass and the front sight's part; saves the captured values. Returns the records faded. */
    static int apply(Object draws, float[] data, float s, Sight keep, Applied a) throws IllegalAccessException {
        int total = count.getInt(draws);
        int[] f = (int[]) flags.get(draws);
        ModelInstance[] inst = (ModelInstance[]) instances.get(draws);
        if (total < 0 || total > f.length || total > inst.length || (long) total * stride > data.length) {
            throw new ViewpointBridge.DataMiss("record arrays shorter than their count");
        }
        String[] mesh = null;
        if (keep != null) {
            mesh = new String[total];
            for (int i = 0; i < total; i++) {
                if ((f[i] & self) != 0 && inst[i] != null && inst[i].modelScript != null) {
                    mesh[i] = inst[i].modelScript.getMeshName();
                }
            }
        }
        return fadeRecords(total, f, mesh, data, stride, fade, self, glass, s, keep, a);
    }

    /**
     * Pure: the fade (0, s) on every record flagged selfBit but not glassBit; with a sight (null: none),
     * its front sight's part keeps its value (the active optic's part is faded like the rest and
     * counted); the values replaced are saved in a. Returns the records faded.
     */
    static int fadeRecords(int total, int[] f, String[] mesh, float[] data, int stride, int fadeAt, int selfBit, int glassBit,
                           float s, Sight keep, Applied a) {
        int n = 0;
        for (int i = 0; i < total; i++) {
            if ((f[i] & selfBit) != 0) {
                n++;
            }
        }
        if (a.index.length < n) {
            a.index = new int[n];
            a.saved = new float[n * 2];
            a.written = new float[n];
        }
        a.n = 0;
        int done = 0;
        for (int i = 0; i < total; i++) {
            if ((f[i] & selfBit) == 0 || (f[i] & glassBit) != 0) {
                continue;
            }
            int role = keep == null || mesh == null ? 0 : role(mesh[i], keep);
            if (role == 1) {
                kept++;
                continue;
            }
            float share = s;
            int o = i * stride + fadeAt;
            a.saved[a.n * 2] = data[o];
            a.saved[a.n * 2 + 1] = data[o + 1];
            a.written[a.n] = share;
            a.index[a.n++] = i;
            data[o] = 0f;
            data[o + 1] = share;
            done++;
            if (role == 2) {
                sightFaded++;
            }
        }
        return done;
    }

    /** 1: the front sight's part (kept as it is), 2: the active optic's or collimator's part (faded, counted), 0: anything else. */
    static int role(String mesh, Sight keep) {
        if (mesh == null) {
            return 0;
        }
        if (!keep.front.isEmpty() && mesh.equals(keep.front)) {
            return 1;
        }
        return !keep.sight.isEmpty() && mesh.equals(keep.sight) ? 2 : 0;
    }

    static void restore(Applied a, float[] data) {
        restored += restoreRecords(a, data, stride, fade);
    }

    /** Pure: the same captured frame drawn again - the values it was captured with, where ours still stand. */
    static int restoreRecords(Applied a, float[] data, int stride, int fadeAt) {
        int back = 0;
        for (int j = 0; j < a.n; j++) {
            int o = a.index[j] * stride + fadeAt;
            if (o + 1 >= data.length) {
                break;
            }
            if (Float.floatToRawIntBits(data[o]) == Float.floatToRawIntBits(0f)
                    && Float.floatToRawIntBits(data[o + 1]) == Float.floatToRawIntBits(a.written[j])) {
                data[o] = a.saved[j * 2];
                data[o + 1] = a.saved[j * 2 + 1];
                back++;
            }
        }
        a.n = 0;
        return back;
    }

    /** The drawn records of the local weapon: the far end of its parts from the eye and their screen box. */
    static final class Extent {
        float near;
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        boolean any, whole;
        float sharpX, sharpY, sharpR;
    }

    /**
     * Pure: the weapon's records (spheres: centre and radius in scene units) seen by view and projection
     * on a viewport (w, h): the far end of the weapon from the eye (scene distance) and the box of its
     * spheres in window pixels (origin bottom-left); a sphere through the near plane takes the whole view.
     */
    public static boolean extent(float[] spheres, int n, Matrix4f view, Matrix4f projection, int w, int h, Extent out) {
        Vector4f c = new Vector4f();
        for (int i = 0; i < n; i++) {
            float x = spheres[i * 4], y = spheres[i * 4 + 1], z = spheres[i * 4 + 2], r = spheres[i * 4 + 3];
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z) || !(r >= 0) || r > 50) {
                continue;
            }
            view.transform(c.set(x, y, z, 1f));
            float depth = -c.z;
            out.near = Math.max(out.near, depth + r);
            out.any = true;
            if (depth - r <= 0.02f) {
                out.whole = true;
                continue;
            }
            Vector4f clip = projection.transform(new Vector4f(c));
            float px = clip.x / clip.w, py = clip.y / clip.w;
            float rx = Math.abs(projection.m00()) * r / depth, ry = Math.abs(projection.m11()) * r / depth;
            out.x0 = Math.min(out.x0, (px - rx + 1f) * 0.5f * w);
            out.x1 = Math.max(out.x1, (px + rx + 1f) * 0.5f * w);
            out.y0 = Math.min(out.y0, (py - ry + 1f) * 0.5f * h);
            out.y1 = Math.max(out.y1, (py + ry + 1f) * 0.5f * h);
        }
        return out.any;
    }

    private static float[] sphereScratch = new float[4 * 256];

    /** The same verified front post for native DoF; independent of the translucency switch. */
    static void frontSharp(Object scene, int w, int h, float[] out) throws IllegalAccessException {
        if (scene == null || w <= 0 || h <= 0 || !detect()) return;
        Sight s = sight;
        if (s == null || s.front.isEmpty()) return;
        IsoPlayer p = IsoPlayer.getInstance();
        ModelInstance root = p == null ? null : p.primaryHandModel;
        Matrix4f vp = ViewpointBridge.jitteredViewProjection();
        Matrix4f projection = (Matrix4f) projectionOf.get(scene);
        if (root == null || vp == null || projection == null) return;
        Object draws = VpBlend.records(scene, models.get(scene));
        if (draws == null) return;
        float[] data = (float[]) values.get(draws);
        int total = count.getInt(draws);
        int[] f = (int[]) flags.get(draws);
        ModelInstance[] inst = (ModelInstance[]) instances.get(draws);
        if (total < 0 || total > f.length || total > inst.length || (long) total * stride > data.length) return;
        for (int i = 0; i < total; i++) {
            if ((f[i] & self) == 0 || !LensGlass.inPrimaryTree(inst[i], root)
                    || inst[i] == null || inst[i].modelScript == null
                    || !s.front.equals(inst[i].modelScript.getMeshName())) continue;
            projectFront(new Matrix4f().set(data, i * stride), vp, projection, s, w, h, out);
            return;
        }
    }

    /** Pure projection: UV ellipse around the authored post, using the existing sharp-post rule. */
    static boolean projectFront(Matrix4f model, Matrix4f vp, Matrix4f projection, Sight s, int w, int h, float[] out) {
        java.util.Arrays.fill(out, 0f);
        if (w <= 0 || h <= 0) return false;
        Vector4f a = model.transform(new Vector4f(s.x, s.y, s.z, 1f));
        Vector4f b = model.transform(new Vector4f(s.x + s.width, s.y, s.z, 1f));
        float postWidth = (float) Math.sqrt((b.x-a.x)*(b.x-a.x)+(b.y-a.y)*(b.y-a.y)+(b.z-a.z)*(b.z-a.z));
        Vector4f clip = vp.transform(a);
        if (!(clip.w > 1e-4f) || !Float.isFinite(clip.x) || !Float.isFinite(clip.y) || !Float.isFinite(postWidth)) return false;
        float widthPx = postWidth * Math.abs(projection.m11()) / clip.w * 0.5f * h;
        // 14 pixels is the guarded native shader's maximum blur radius: no tap can smear the post.
        float radius = Math.max(MIN_SHARP * h / REFERENCE_HEIGHT, SHARP_WIDTHS * widthPx) + 14f;
        out[0] = (clip.x / clip.w + 1f) * 0.5f;
        out[1] = (clip.y / clip.w + 1f) * 0.5f;
        out[2] = radius / w;
        out[3] = radius / h;
        return true;
    }

    /** Exit of WorldRenderer.drawWeather (PatchViewpoint, before ViewpointPass): the blur of this frame's weapon and hands. */
    public static void afterWeather() {
        if (!ready || frameScene == null || frameWeight <= 1e-3f || blurPx <= 0f) {
            return;
        }
        long start = System.nanoTime();
        try {
            Object scene = ViewpointBridge.currentScene();
            if (scene != frameScene) {
                return;
            }
            IsoPlayer p = IsoPlayer.getInstance();
            ModelInstance root = p == null ? null : p.primaryHandModel;
            Matrix4f vp = ViewpointBridge.jitteredViewProjection(), view = ViewpointBridge.view();
            Matrix4f projection = (Matrix4f) projectionOf.get(scene);
            if (root == null || vp == null || view == null || projection == null) {
                return;
            }
            int[] t = new int[8];
            ViewpointBridge.targets(t);
            int depthTex = t[0], rw = t[1], rh = t[2];
            if (depthTex == 0 || rw <= 0 || rh <= 0) {
                return;
            }
            Object draws = VpBlend.records(scene, models.get(scene));
            float[] data = (float[]) values.get(draws);
            int total = count.getInt(draws);
            int[] f = (int[]) flags.get(draws);
            ModelInstance[] inst = (ModelInstance[]) instances.get(draws);
            if (total < 0 || total > f.length || total > inst.length || (long) total * stride > data.length) {
                throw new ViewpointBridge.DataMiss("record arrays shorter than their count");
            }
            // performance pass 23.10.2026: render thread scratch, grown as needed; extent reads only the n written spheres
            if (sphereScratch.length < total * 4) sphereScratch = new float[total * 4];
            float[] spheres = sphereScratch;
            int n = 0;
            Sight s = frameAds > 0.01f ? sight : null;
            float[] apex = null;
            float postWidth = 0;
            for (int i = 0; i < total; i++) {
                if ((f[i] & self) == 0 || (f[i] & glass) != 0 || !LensGlass.inPrimaryTree(inst[i], root)) {
                    continue;
                }
                int o = i * stride + sphere;
                System.arraycopy(data, o, spheres, n * 4, 4);
                n++;
                if (s != null && apex == null && !s.front.isEmpty() && inst[i] != null && inst[i].modelScript != null
                        && s.front.equals(inst[i].modelScript.getMeshName())) {
                    Matrix4f m = new Matrix4f().set(data, i * stride);
                    Vector4f a = m.transform(new Vector4f(s.x, s.y, s.z, 1f));
                    apex = new float[] {a.x, a.y, a.z};
                    Vector4f b = m.transform(new Vector4f(s.x + s.width, s.y, s.z, 1f));
                    postWidth = (float) Math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y) + (b.z - a.z) * (b.z - a.z));
                }
            }
            Extent e = new Extent();
            if (!extent(spheres, n, view, projection, rw, rh, e)) {
                return;
            }
            float scale = rh / REFERENCE_HEIGHT;
            float radius = Math.max(1f, frameWeight * blurPx * scale);
            if (apex != null) {
                Vector4f clip = vp.transform(new Vector4f(apex[0], apex[1], apex[2], 1f));
                if (clip.w > 1e-4f) {
                    e.sharpX = (clip.x / clip.w + 1f) * 0.5f * rw;
                    e.sharpY = (clip.y / clip.w + 1f) * 0.5f * rh;
                    float depth = clip.w;
                    float widthPx = postWidth * projection.m11() / depth * 0.5f * rh;
                    e.sharpR = Math.max(MIN_SHARP * scale, SHARP_WIDTHS * widthPx) + radius;
                }
            }
            int x0 = e.whole ? 0 : (int) Math.floor(Math.max(0, e.x0 - radius - 2)), y0 = e.whole ? 0 : (int) Math.floor(Math.max(0, e.y0 - radius - 2));
            int x1 = e.whole ? rw : (int) Math.ceil(Math.min(rw, e.x1 + radius + 2)), y1 = e.whole ? rh : (int) Math.ceil(Math.min(rh, e.y1 + radius + 2));
            if (x1 <= x0 || y1 <= y0) {
                return;
            }
            float near = e.near * NEAR_MARGIN;
            lastNear = near;
            lastRadius = radius;
            lastSharpX = e.sharpX;
            lastSharpY = e.sharpY;
            lastSharpR = e.sharpR;
            float sx = t[3] > 0 ? rw / (float) t[3] : 1f, sy = t[4] > 0 ? rh / (float) t[4] : 1f;
            draw(x0, y0, x1, y1, rw, rh, depthTex, sx, sy, projection, near, radius, e);
            blurred++;
            blurPixels += (long) (x1 - x0) * (y1 - y0);
            blurMillis = (System.nanoTime() - start) / 1e6;
        } catch (Throwable t) {
            error("blur", t);
        }
    }

    /** The GL part: copy of the HDR box, then the blur drawn back into Viewpoint's HDR target over the box. */
    static void draw(int x0, int y0, int x1, int y1, int rw, int rh, int depthTex, float sx, float sy, Matrix4f projection,
                     float near, float radius, Extent e) throws Throwable {
        int[] fbo = {GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)};
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int prevBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int prevTex1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), prevSampler1 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D), prevSampler0 = GL11.glGetInteger(GL33.GL_SAMPLER_BINDING);
        GL11.glPushAttrib(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_TEXTURE_BIT
                | GL11.GL_SCISSOR_BIT | GL11.GL_STENCIL_BUFFER_BIT | GL11.GL_POLYGON_BIT | GL11.GL_VIEWPORT_BIT);
        try {
            ViewpointBridge.bindTarget();
            int hdrFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int[] vpt = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vpt);
            if (vpt[2] <= 0 || vpt[3] <= 0) {
                return;
            }
            shaders();
            if (!program.isCompiled()) {
                throw new IllegalStateException("see-through shader did not compile (tw_vp_seethrough)");
            }
            ensure(vpt[2], vpt[3]);
            // the box of the HDR target, copied: it is read while the target is drawn into
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, hdrFbo);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFbo);
            GL30.glBlitFramebuffer(vpt[0] + x0, vpt[1] + y0, vpt[0] + x1, vpt[1] + y1, x0, y0, x1, y1, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, hdrFbo);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, hdrFbo);
            GL11.glViewport(vpt[0], vpt[1], vpt[2], vpt[3]);
            GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glEnable(GL11.GL_BLEND);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glColorMask(true, true, true, false);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            // GL_DEPTH_STENCIL_TEXTURE_MODE is GL 4.3 / ARB_stencil_texturing: without it a depth-stencil texture reads depth
            boolean depthOn = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL14.GL_TEXTURE_COMPARE_MODE) == GL11.GL_NONE
                    && (!GLCompat.stencilTexturing()
                    || GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL43.GL_DEPTH_STENCIL_TEXTURE_MODE) != GL11.GL_STENCIL_INDEX);
            if (!depthOn) {
                return;
            }
            GL33.glBindSampler(1, nearestSampler);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyTex);
            GL33.glBindSampler(0, nearestSampler);
            program.Start();
            program.setValue("ModelViewProjection", new Matrix4f().setOrtho2D(0, vpt[2], 0, vpt[3]));
            program.setSamplerUnit("Copy", 0);
            program.setSamplerUnit("SceneDepth", 1);
            program.setVector4("Viewport", vpt[0], vpt[1], vpt[2], vpt[3]);
            program.setVector2("DepthScale", sx, sy);
            // view distance = m32 / (ndc z + m22) of the frame projection (the jitter moves x, y only)
            program.setVector2("DepthLinear", projection.m32(), projection.m22());
            seed = (seed + 0.618034f) % 1f;
            program.setVector4("Blur", near, radius, HALO, seed * 64f);
            program.setVector3("Sharp", e.sharpX, e.sharpY, e.sharpR);
            quad.clear();
            quad.put(new float[] {x0, y0, x1, y0, x0, y1, x1, y1}).flip();
            GL30.glBindVertexArray(vao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STREAM_DRAW);
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
            GL20.glEnableVertexAttribArray(0);
            GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4);
        } finally {
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
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo[0]);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo[1]);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        }
    }

    /** Our copy of the HDR target (half floats), its frame buffer, the quad and the samplers. */
    static void ensure(int width, int height) {
        if (vao == 0) {
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            quad = BufferUtils.createFloatBuffer(8);
            nearestSampler = GL33.glGenSamplers();
            GL33.glSamplerParameteri(nearestSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL33.glSamplerParameteri(nearestSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL33.glSamplerParameteri(nearestSampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(nearestSampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
        if (copyTex == 0) {
            copyTex = GL11.glGenTextures();
            copyFbo = GL30.glGenFramebuffers();
        }
        if (copyW != width || copyH != height) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, copyTex);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GLCompat.texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA16F, width, height, GL11.GL_RGBA, GL11.GL_FLOAT);
            int prev = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copyFbo);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, copyTex, 0);
            int complete = GL30.glCheckFramebufferStatus(GL30.GL_DRAW_FRAMEBUFFER);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prev);
            if (complete != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("see-through copy target incomplete");
            }
            copyW = width;
            copyH = height;
        }
    }

    // ------------------------------------------------------------------ QA

    public static String stats() {
        Sight s = sight;
        return String.format(Locale.ROOT, "status=%s enabled=%b opacity=%.2f blur=%.1f frames=%d faded=%d sightFaded=%d"
                        + " kept=%d restored=%d blurred=%d pixels=%d weight=%.3f share=%.3f near=%.4f radius=%.2f"
                        + " sharp=%.1f,%.1f,%.1f blurMs=%.3f front=%s sight=%s errors=%d misses=%d%s",
                status, enabled, opacity, blurPx, frames, faded, sightFaded, kept, restored, blurred, blurPixels, lastWeight,
                lastShare, lastNear, lastRadius, lastSharpX, lastSharpY, lastSharpR, blurMillis,
                s.front.isEmpty() ? "-" : s.front, s.sight.isEmpty() ? "-" : s.sight, errors, misses, lastError.isEmpty() ? "" : " last=" + lastError);
    }

    public static void reset() {
        frames = faded = sightFaded = kept = restored = blurred = blurPixels = errors = misses = 0;
        lastWeight = lastShare = lastNear = lastRadius = lastSharpX = lastSharpY = lastSharpR = 0;
        blurMillis = 0;
    }
}
