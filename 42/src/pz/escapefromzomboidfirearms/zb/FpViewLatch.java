package pz.escapefromzomboidfirearms.zb;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.textures.Texture;

/**
 * Draw-time hooks of the first-person view in Viewpoint (FB2, 17.10.2026; items 3, 8, 13; W9 item 4).
 *
 *   latch      (item 13: "the weapon shakes strongly while aiming; optics jerk when the camera
 *              turns") The pose layer (FpPose) places the weapon at the eye on the main thread
 *              with Look.yaw/pitch of that animation update; Viewpoint snapshots the frame after
 *              it, but draws it on the render thread with the look read again right before
 *              drawing (Look.readBeforeDrawing, the user's mouse.readBeforeDrawing=true) or
 *              already advanced by the next update. Every turn of the mouse between the two put
 *              the weapon, its glass and the sight picture off the view by that turn (at 60 fps
 *              a 180 deg/s turn is 3 degrees: the sight leaves the screen centre and comes back
 *              unevenly with the frame timing). Here, on the render thread at the start of the
 *              frame's world pass (WorldRenderer.begin), the frame's own records (ModelDraws
 *              SELF: the local body, its weapon and our lens glass) are carried from the camera
 *              the pose used to the camera of this drawing - one rigid transform about the eye,
 *              C = view(draw)^-1 * view(pose), both from Viewpoint's own Camera.eye/direction
 *              at the two looks of that frame. Nothing moves when the looks agree. A previous
 *              matrix (motion vectors) gets the transform of the frame drawn before only when it
 *              is bit for bit that frame's captured matrix: the main thread may copy it before or
 *              after that drawing carried it, and a carried copy must not turn twice. Other
 *              render-thread users of the weapon's capture-time frame (the scope picture) read
 *              the same transform from correction(scene).
 *   near band  (item 8; W9 item 4, 26-27.10.2026: "long weapons like the SKS show the inside of
 *              the body when the aim is entered") Viewpoint's world near plane is 0.05 scene
 *              units (5.77 cm). Clamping fragments nearer than 5.77 cm onto z_win = 0 collapses
 *              outer covers and inner moving parts onto a single depth value, where ModelBatches
 *              orders opaque draws by System.identityHashCode(Texture) and ModelPass.begin()
 *              resets GL_LESS - making any tie-breaker order-dependent across JVM launches.
 *              Instead, when the near-band policy is active (first person with our weapon, not
 *              PiP, outside ADS or adsClampSafe for both iron/built-in sights and clamp-safe
 *              optics), SELF records are masked out of Viewpoint's main gbuffer/glass draw (so
 *              all world/vehicle models keep untouched near=0.05 and glDepthRange(0, 1)), then
 *              drawn in two hardware-clipped bands with GL_LESS:
 *                Band A (d >= 0.05): frame.viewProjection + glDepthRange(NEAR_BAND_SLICE, 1.0),
 *                  keeping world occlusion and deferred lighting within < 1.6 mm at 80 cm;
 *                Band B (0.0005 <= d <= 0.05002, 0.58..57.76 mm): jitter-preserving perspective
 *                  projection [NEAR_BAND_NEAR, NEAR_BAND_FAR] * frame.view + glDepthRange(0.0,
 *                  NEAR_BAND_SLICE), mapping the near band monotonically across 32,768 24-bit
 *                  depth levels in front of Band A so the nearest surface to the eye always wins
 *                  regardless of mesh, attachment or texture draw order.
 *              When adsClampSafe is false (an eyecup scope in steady ADS, e.g. Zeus-Pro), the
 *              policy is off and Viewpoint's 0.05 near plane clips the eyecup cleanly.
 *   crosshair  (item 3) Viewpoint's crosshair dot is hidden while the pose aims down sights
 *              (FpAdsCamera.aiming) at the exit of its per-frame View.crosshairSnapshot.
 *   free look  (bug wave 324, the user's item 2, 24.10.2026) A pose of Viewpoint's free look or of its way
 *              back (FpFreeLook) holds the weapon where it points in the world, not at the view: its frame
 *              is drawn without the view's turn since the pose (it is no lag of the camera there), carried by
 *              the eye's walk only (the frame's camera at the pose's own look); counted in freeLooks.
 *
 * Viewpoint 0.1.7 draws blended records made inside WorldRenderer.begin (ModelBlend), so there the latch runs at the exit of
 * that blend (carryBlended) and the bands read the records of the pass; on 0.1.6 the pass's records are the captured ones.
 * Every Viewpoint member used is in MEMBERS / NEAR_MEMBERS (exact type and static flag, checked once);
 * one missing or changed turns only that feature off (one log line), the bridge stays on. A frame
 * whose data does not match (a record reused, an unproven matrix block, a turn over the limit)
 * is drawn untouched. tools/check_view_latch.py and tools/check_fp_near.py check the math and ABI.
 */
public final class FpViewLatch {
    static final String[][] MEMBERS = {
        {"viewpoint.input.Look", "readBeforeDrawing", "()V", "static"},
        {"viewpoint.render.WorldRenderer", "begin", "(Lviewpoint/render/SceneData;Lorg/joml/Matrix4f;FFF)Z|(Lviewpoint/render/SceneData;JFLorg/joml/Matrix4f;FFF)Z", "static"},
        {"viewpoint.render.ModelPass", "gbuffer", "(Lviewpoint/render/FrameContext;)V", ""},
        {"viewpoint.render.ModelPass", "glass", "(Lviewpoint/render/FrameContext;Lviewpoint/render/ShadowPass;)V|(Lviewpoint/render/FrameContext;Lviewpoint/render/ShadowPass;Lviewpoint/render/GlassWaterPass;)V", ""},
        {"viewpoint.core.View", "crosshairSnapshot", "(Z)V", "static"},
        {"viewpoint.core.View", "crosshairShown", "Z", "static"},
        {"viewpoint.render.SceneData", "models", "Lviewpoint/render/ModelDraws;", ""},
        {"viewpoint.render.ModelDraws", "values", "[F", ""},
        {"viewpoint.render.ModelDraws", "flags", "[I", ""},
        {"viewpoint.render.ModelDraws", "count", "I", ""},
        {"viewpoint.render.ModelDraws", "VALUES", "I", "static"},
        {"viewpoint.render.ModelDraws", "SELF", "I", "static"},
        {"viewpoint.render.ModelDraws", "PREVIOUS", "I", "static"},
        {"viewpoint.render.ModelDraws", "SPHERE", "I", "static"},
    };

    /** Guarded members for the two-band monotonic SELF depth pass; a mismatch turns only the near-band pass off. */
    static final String[][] NEAR_MEMBERS = {
        {"viewpoint.render.ModelDraws", "COLOUR", "I", "static"},
        {"viewpoint.render.ModelDraws", "GLASS", "I", "static"},
        {"viewpoint.render.ModelDraws", "material", "[I", ""},
        {"viewpoint.render.FrameContext", "scene", "Lviewpoint/render/SceneData;", ""},
        {"viewpoint.render.FrameContext", "view", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.FrameContext", "jitteredProjection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.FrameContext", "viewProjection", "Lorg/joml/Matrix4f;", ""},
        {"viewpoint.render.ModelPass", "gbuffer", "Lviewpoint/platform/GlProgram;", ""},
        {"viewpoint.render.ModelPass", "glass", "Lviewpoint/platform/GlProgram;", ""},
        {"viewpoint.render.ModelPass", "targets", "Lviewpoint/render/Targets;", ""},
        {"viewpoint.render.ModelPass", "batches", "Lviewpoint/render/ModelBatches;", ""},
        {"viewpoint.render.ModelPass", "runs", "Lviewpoint/render/ModelBatches$State;", ""},
        {"viewpoint.render.ModelPass", "begin", "()V", ""},
        {"viewpoint.render.ModelPass", "end", "()V", ""},
        {"viewpoint.render.Targets", "velocity", "(Z)V", ""},
        {"viewpoint.render.ShadowPass", "bind", "(Z)V", ""},
        {"viewpoint.platform.GlProgram", "use", "()V", ""},
        {"viewpoint.platform.GlProgram", "set", "(Ljava/lang/String;Lorg/joml/Matrix4f;)V", ""},
        {"viewpoint.render.ModelBatches", "order", "[I", ""},
        {"viewpoint.render.ModelBatches", "keys", "[J", ""},
        {"viewpoint.render.ModelBatches", "rank", "[I", ""},
        {"viewpoint.render.ModelBatches", "count", "I", ""},
        {"viewpoint.render.ModelBatches", "inOrder", "([IILviewpoint/render/ModelBatches$Filter;Lviewpoint/render/ModelBatches$State;)I", ""},
        {"viewpoint.render.ModelBatches$Filter", "take", "(I)Z", ""},
        // the records this pass draws: the scene's captured ones on 0.1.6, ModelBlend's on 0.1.7
        {"viewpoint.render.ModelPass", "draws", "Lviewpoint/render/ModelDraws;", ""},
    };

    /**
     * Viewpoint 0.1.7 (ViewpointBridge.blended): what the latch reads of the blended frame. A mismatch turns the latch off
     * there; Viewpoint's own ArmsTurn then turns the arms.
     */
    static final String[][] BLEND_MEMBERS = {
        {"viewpoint.render.ModelDraws", "MOTION", "I", "static"},
        {"viewpoint.render.ModelDraws", "STAND", "I", "static"},
        {"viewpoint.render.ModelDraws", "UPRIGHT", "I", "static"},
        {"viewpoint.render.ModelDraws", "instances", "[Lzombie/core/skinnedmodel/model/ModelInstance;", ""},
        {"viewpoint.render.FrameContext", "eyeX", "F", ""},
        {"viewpoint.render.FrameContext", "eyeY", "F", ""},
        {"viewpoint.render.FrameContext", "eyeZ", "F", ""},
    };

    /**
     * W11 camera2 (B), optional: with a Minecraft/Iris pack Viewpoint draws SELF itself (ModelPass.iris inside
     * IrisGeometry.models) and IrisFrame.opaque reads the pick right after; SELF is drawn after that read through
     * Viewpoint's own entities() wrapper of the ENTITIES program. A mismatch leaves Viewpoint's own Iris draw as before.
     */
    static final String[][] IRIS_PICK_MEMBERS = {
        {"viewpoint.render.IrisGeometry", "entities", "(Lviewpoint/render/IrisPipeline;Ljava/util/Map;Lviewpoint/render/FrameContext;Lviewpoint/render/ShadowPass;Lviewpoint/render/IrisProgram;ZLjava/lang/Runnable;)V", ""},
        {"viewpoint.render.IrisPipeline", "programs", "Lviewpoint/render/IrisPrograms;", ""},
        {"viewpoint.render.IrisPrograms", "draws", "Ljava/util/Map;", ""},
        {"viewpoint.render.IrisProgram", "gl", "Lviewpoint/platform/GlProgram;", ""},
        {"viewpoint.platform.GlProgram", "setInt", "(Ljava/lang/String;I)V", ""},
        {"viewpoint.platform.GlProgram", "set", "(Ljava/lang/String;FF)V", ""},
    };

    /** Optional QA-only members for one-shot [TW-NEAR-DUMP] mesh/texture order logging. */
    static final String[][] QA_MEMBERS = {
        {"viewpoint.render.ModelPass", "draws", "Lviewpoint/render/ModelDraws;", ""},
        {"viewpoint.render.ModelDraws", "meshes", "[Lzombie/core/skinnedmodel/model/ModelMesh;", ""},
        {"viewpoint.render.ModelDraws", "instances", "[Lzombie/core/skinnedmodel/model/ModelInstance;", ""},
        {"viewpoint.render.ModelDraws", "cull", "[I", ""},
        {"viewpoint.render.ModelMeshes$Held", "id", "I", ""},
        {"viewpoint.render.ModelMeshes$Held", "layout", "Lviewpoint/render/ModelMeshes$Layout;", ""},
        {"viewpoint.render.ModelMeshes$Layout", "index", "I", ""},
    };

    /** QA only, the meshes and textures of a pass: on the pass on 0.1.6, on its DrawSources on 0.1.7. */
    static final String[][] QA_SOURCES = {
        {"viewpoint.render.ModelPass", "held", "[Lviewpoint/render/ModelMeshes$Held;", ""},
        {"viewpoint.render.ModelPass", "drawn", "[Lzombie/core/textures/Texture;", ""},
    };
    static final String[][] QA_BLEND_SOURCES = {
        {"viewpoint.render.ModelPass", "sources", "Lviewpoint/render/DrawSources;", ""},
        {"viewpoint.render.DrawSources", "held", "[Lviewpoint/render/ModelMeshes$Held;", ""},
        {"viewpoint.render.DrawSources", "drawn", "[Lzombie/core/textures/Texture;", ""},
    };

    /** Viewpoint's world frustum planes (WorldRenderer.NEAR / FAR, scene units). */
    public static final float WORLD_NEAR = 0.05f, WORLD_FAR = 400.0f;
    /** Near-band SELF frustum planes (scene units: 0.58 mm .. 57.76 mm, 23 um seam overlap past WORLD_NEAR). */
    public static final float NEAR_BAND_NEAR = 0.0005f, NEAR_BAND_FAR = 0.05002f;
    /** Window-depth slice [0, NEAR_BAND_SLICE] reserved for SELF near-band geometry (32,768 24-bit integer levels). */
    public static final double NEAR_BAND_SLICE = 1.0 / 512.0;
    /** Maximum 24-bit unsigned normalized depth integer in GL_DEPTH24_STENCIL8. */
    public static final int DEPTH24_MAX = (1 << 24) - 1;

    /**
     * A look difference over this (radians) is a cut (teleport, view switch), not a turn within a frame. FB4 item 4
     * ("the weapon teleports on sharp turns"): 25 degrees left fast flicks uncarried - a drawing a frame or two after
     * the pose reads the mouse again, so a 1500 deg/s flick at 40 fps is over 25 degrees, and that frame showed the
     * weapon where the view was before. The own body and weapon turn rigidly about the eye, so any real turn is
     * carried; only a near half-turn is taken for a cut.
     */
    static final double LIMIT = Math.toRadians(150);
    /** The draw look of this render frame is fresh for this long. */
    static final long LOOK_NS = 100_000_000L;
    static final int HISTORY = 256;

    /** FB5 item 4: a gap between the posed eye and the drawing's camera over this (scene units, ~35 cm) is a cut. */
    static final double EYE_LIMIT = 0.3;

    public static volatile boolean enabled = true, clamp = true;
    /** QA (FB5 item 4, A/B in one run): false carries the look's turn only, as before. */
    public static volatile boolean eyeLatch = true;
    /**
     * W9-adsnear2: true (default) runs the draw-order-independent two-band monotonic SELF depth pass so the nearest
     * surface to the eye wins across [0.58 mm, 400]; false falls back to the legacy single-pass GL_DEPTH_CLAMP at
     * z_win = 0 (QA A/B comparison and frame-time benchmark).
     */
    public static volatile boolean nearestWins = true;
    /**
     * QA (W11 camera, A/B in one run): true draws SELF at the exit of ModelPass.gbuffer as before, so MousePick.read
     * (drawWorld, after far.gbuffer) samples the weapon's depth; false (default) draws it at that read's exit.
     */
    public static volatile boolean qaSelfInPick;
    /** QA (W11 camera, A/B in one run): true skips the vehicle check in layer() as before. */
    public static volatile boolean qaIgnoreVehicleLayer;
    public static volatile long draws, latched, records, previousCarried, cuts, misses, clamps, adsClamps, hidden, errors, eyeShifts, eyeCuts;
    public static volatile long bandPasses, nearDraws;
    /**
     * W11 camera2: where SELF reached the gbuffer - at the exit of ModelPass.gbuffer (before the read advice ran once,
     * or qaSelfInPick), at the exit of the MousePick.read that follows it in drawWorld, by the fallback at the entry of
     * IndirectPass.draw (the first reader after the read), or never (missed: a later pass found it still pending).
     */
    public static volatile long selfAtGbuffer, selfAtRead, selfByFallback, selfMissed, selfRebinds;
    /** QA (W11 camera2): one readback of the gbuffer's depth/stencil at nine points at the entry of IndirectPass.draw. */
    public static volatile boolean qaGbufferProbe;
    public static volatile String qaGbufferProbeResult = "";
    /** Bug wave 324: drawings of a free-look pose (FpFreeLook), not turned by the view. */
    public static volatile long freeLooks;
    public static volatile double maxDeg, lastYawDeg, lastPitchDeg, sumDeg, lastShiftCm, maxShiftCm, sumShiftCm;
    /** Render thread: the eye gap the last correction carried (scene units). */
    private static double shifted;
    public static volatile String status = "unchecked", lastError = "";
    public static volatile String nearStatus = "unchecked", lastNearError = "";

    private static boolean checked, nearChecked;
    private static volatile boolean ready, nearReady;
    private static Field models, values, flags, count, shown;
    private static int stride, self, previous, sphere;
    // Viewpoint 0.1.7 (BLEND_MEMBERS)
    private static int motionBit, standAt, uprightValue;
    private static Field mdInstances, fcEyeX, fcEyeY, fcEyeZ;

    // Guarded near-band reflection handles
    private static int colourBit, glassBit;
    private static Field mdMaterial, fcScene, fcView, fcJitteredProj, fcViewProj;
    private static Field mpGbufferProg, mpGlassProg, mpTargets, mpBatches, mpRuns, mpDraws;
    private static Field mbOrder, mbKeys, mbRank, mbCount;
    private static MethodHandle mpBegin, mpEnd, targetsVelocity, shadowBind, progUse, progSetMat4, mbInOrder;
    private static Object acceptAllFilter;
    /** Render thread: SELF waiting for the exit of MousePick.read (its frame, pass, matrix, bands); see deferSelf. */
    private static Object pendingFrame, pendingPass;
    private static Matrix4f pendingViewProj;
    private static int pendingFar, pendingNear;
    private static boolean pendingNearBand;
    /** Set by the first MousePick.read exit: the advice is installed, so SELF can wait for it. */
    private static volatile boolean readHooked;
    /** Render thread, QA probe only: the targets of the last ModelPass.gbuffer. */
    private static Object lastGbufferTargets;
    /** W11 camera2 (B): the Iris members (IRIS_PICK_MEMBERS), the ENTITIES draw key, and SELF of the Iris frame waiting for its pick. */
    private static boolean irisChecked, irisReady;
    private static Method irisEntities, glSetInt, glSet2;
    private static Field irisPrograms, irisDraws, irisProgramGl;
    private static Object irisEntitiesKey;
    private static Object irisGeometry, irisPipeline, irisValues, irisFrame, irisShadows, irisPass, irisDrawsObj;
    private static int irisMasked, irisSelfCount;
    private static int[] irisMaskedRecs = new int[64], irisSelf = new int[64];
    public static volatile long irisSelfAtRead, irisSelfMissed;

    /**
     * The look the pose used for the skeleton of a captured frame (main thread -> render thread), and (FB5 item 4) the
     * eye it posed for in the frame's scene space, or null: then the frame's own camera at that look stands for it.
     */
    record Pose(Object frame, long number, float yaw, float pitch, float[] eye, boolean free) { }

    /** The pose published for a scene (ScopePipZoom.afterWorld, main thread), or null. */
    static Pose pose(Object scene) {
        synchronized (poses) {
            return poses.get(scene);
        }
    }

    /** What one drawing changed in a scene's records: restored if the same frame is drawn again. */
    static final class Applied {
        long number;
        int[] index = new int[0];
        float[] saved = new float[0];
        int n;
        final Matrix4f c = new Matrix4f();
    }

    private static final Map<Object, Pose> poses = new IdentityHashMap<>();
    private static final Map<Object, Applied> applied = new IdentityHashMap<>();
    private static final Map<Object, Matrix4f> published = new IdentityHashMap<>();
    // render thread only
    private static float drawYaw, drawPitch;
    private static long drawAt;
    private static boolean clampedByUs;
    /** The GL depth function our legacy clamp found, restored with it (-1: not ours). */
    private static int prevDepthFunc = -1;
    /** Render-thread scratch state for the active gbuffer / glass two-band pass. */
    private static boolean gbufferTwoBandActive, gbufferNearBandActive, glassTwoBandActive;
    private static int[] maskedGbufferRecs = new int[64], maskedGlassRecs = new int[32];
    private static int maskedGbufferCount, maskedGlassCount;
    private static int[] selfOrdered = new int[64], glassOrdered = new int[32];
    private static int selfOrderedCount, glassOrderedCount;
    private static int[] bandFarRecs = new int[64], bandNearRecs = new int[64];
    private static final Matrix4f nearProjScratch = new Matrix4f(), nearViewProjScratch = new Matrix4f();
    private static final FrustumIntersection farFrustum = new FrustumIntersection(), nearFrustum = new FrustumIntersection();
    /** Saved legacy ModelBatches order/rank when qaOrderMode != 0 under nearestWins == false. */
    private static boolean legacyOrderSaved;
    private static int[] legacySavedPos = new int[64], legacySavedRec = new int[64];
    private static int legacySavedCount;

    /** The frame latched last: its captured (not carried) matrices and its transform. */
    private static long priorNumber = -1;
    private static int priorN;
    private static float[] prior = new float[0];
    private static final Matrix4f priorC = new Matrix4f();
    private static final float[] history = new float[HISTORY * 3];
    private static int historyAt;

    private FpViewLatch() { }

    static synchronized boolean detect() {
        if (checked) {
            return ready && ViewpointBridge.ready();
        }
        if (!ViewpointBridge.ready() && !ViewpointBridge.detect()) {
            return false;
        }
        checked = true;
        try {
            ClassLoader cl = FpViewLatch.class.getClassLoader();
            Member[] found = new Member[MEMBERS.length];
            for (int i = 0; i < found.length; i++) {
                found[i] = ViewpointBridge.resolve(MEMBERS[i], cl);
                if (found[i] == null) {
                    status = "member " + MEMBERS[i][0] + "." + MEMBERS[i][1] + " " + MEMBERS[i][2] + " missing or changed";
                    System.out.println("[TWZB] Viewpoint view latch: " + status + ", latch/clamp/crosshair off (bridge stays on)");
                    return false;
                }
            }
            shown = (Field) found[5];
            models = (Field) found[6];
            values = (Field) found[7];
            flags = (Field) found[8];
            count = (Field) found[9];
            stride = ((Field) found[10]).getInt(null);
            self = ((Field) found[11]).getInt(null);
            previous = ((Field) found[12]).getInt(null);
            sphere = ((Field) found[13]).getInt(null);
            if (stride < 16 || stride > 4096 || previous < 16 || previous + 16 > stride || sphere < 16 || sphere + 4 > stride || self == 0) {
                status = "unexpected record layout stride=" + stride + " previous=" + previous + " sphere=" + sphere;
                System.out.println("[TWZB] Viewpoint view latch: " + status + ", off (bridge stays on)");
                return false;
            }
            if (ViewpointBridge.blended() && !detectBlend(cl)) {
                return false;
            }
            ready = true;
            status = "on";
            detectNear(cl);
        } catch (Throwable t) {
            status = "lookup failed: " + t;
            System.out.println("[TWZB] Viewpoint view latch: " + status + ", off (bridge stays on)");
            return false;
        }
        return true;
    }

    private static boolean detectBlend(ClassLoader cl) throws Throwable {
        Member[] b = new Member[BLEND_MEMBERS.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = ViewpointBridge.resolve(BLEND_MEMBERS[i], cl);
            if (b[i] == null) {
                status = "member " + BLEND_MEMBERS[i][0] + "." + BLEND_MEMBERS[i][1] + " " + BLEND_MEMBERS[i][2] + " missing or changed";
                System.out.println("[TWZB] Viewpoint view latch: " + status + ", latch/clamp/crosshair off (bridge stays on)");
                return false;
            }
        }
        motionBit = ((Field) b[0]).getInt(null);
        standAt = ((Field) b[1]).getInt(null);
        uprightValue = ((Field) b[2]).getInt(null);
        mdInstances = (Field) b[3];
        fcEyeX = (Field) b[4];
        fcEyeY = (Field) b[5];
        fcEyeZ = (Field) b[6];
        return true;
    }

    static synchronized boolean detectNear(ClassLoader cl) {
        if (nearChecked) {
            return nearReady;
        }
        nearChecked = true;
        try {
            Member[] nf = new Member[NEAR_MEMBERS.length];
            for (int i = 0; i < nf.length; i++) {
                nf[i] = ViewpointBridge.resolve(NEAR_MEMBERS[i], cl);
                if (nf[i] == null) {
                    nearStatus = "member " + NEAR_MEMBERS[i][0] + "." + NEAR_MEMBERS[i][1] + " " + NEAR_MEMBERS[i][2] + " missing or changed";
                    System.out.println("[TWZB] Viewpoint near-band depth: " + nearStatus + ", two-band pass off (latch/crosshair/bridge stay on)");
                    return false;
                }
            }
            colourBit = ((Field) nf[0]).getInt(null);
            glassBit = ((Field) nf[1]).getInt(null);
            mdMaterial = (Field) nf[2];
            fcScene = (Field) nf[3];
            fcView = (Field) nf[4];
            fcJitteredProj = (Field) nf[5];
            fcViewProj = (Field) nf[6];
            mpGbufferProg = (Field) nf[7];
            mpGlassProg = (Field) nf[8];
            mpTargets = (Field) nf[9];
            mpBatches = (Field) nf[10];
            mpRuns = (Field) nf[11];
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            mpBegin = lookup.unreflect((Method) nf[12]).asType(MethodType.methodType(void.class, Object.class));
            mpEnd = lookup.unreflect((Method) nf[13]).asType(MethodType.methodType(void.class, Object.class));
            targetsVelocity = lookup.unreflect((Method) nf[14]).asType(MethodType.methodType(void.class, Object.class, boolean.class));
            shadowBind = lookup.unreflect((Method) nf[15]).asType(MethodType.methodType(void.class, Object.class, boolean.class));
            progUse = lookup.unreflect((Method) nf[16]).asType(MethodType.methodType(void.class, Object.class));
            progSetMat4 = lookup.unreflect((Method) nf[17]).asType(MethodType.methodType(void.class, Object.class, String.class, Matrix4f.class));
            mbOrder = (Field) nf[18];
            mbKeys = (Field) nf[19];
            mbRank = (Field) nf[20];
            mbCount = (Field) nf[21];
            mbInOrder = lookup.unreflect((Method) nf[22]).asType(
                    MethodType.methodType(int.class, Object.class, int[].class, int.class, Object.class, Object.class));
            Class<?> filterClass = ((Method) nf[23]).getDeclaringClass();
            acceptAllFilter = Proxy.newProxyInstance(cl, new Class<?>[] {filterClass}, (proxy, method, args) -> Boolean.TRUE);
            mpDraws = (Field) nf[24];
            if (colourBit == 0 || glassBit == 0) {
                nearStatus = "unexpected flag bits colour=" + colourBit + " glass=" + glassBit;
                System.out.println("[TWZB] Viewpoint near-band depth: " + nearStatus + ", two-band pass off (latch/crosshair/bridge stay on)");
                return false;
            }
            nearReady = true;
            nearStatus = "on";
        } catch (Throwable t) {
            nearStatus = "lookup failed: " + t;
            System.out.println("[TWZB] Viewpoint near-band depth: " + nearStatus + ", two-band pass off (latch/crosshair/bridge stay on)");
            return false;
        }
        return true;
    }

    static void error(String where, Throwable t) {
        if (t instanceof ViewpointBridge.DataMiss) {
            misses++;
            ViewpointBridge.miss("view latch " + where, t.getMessage());
            return;
        }
        errors++;
        String message = where + ": " + t;
        if (!message.equals(lastError)) {
            lastError = message;
            System.out.println("[TWZB] Viewpoint view latch " + message + ", latch/clamp/crosshair off (bridge stays on)");
        }
        ready = false;
        status = "off after error: " + message;
    }

    static void nearError(String where, Throwable t) {
        if (t instanceof ViewpointBridge.DataMiss) {
            misses++;
            ViewpointBridge.miss("near-band " + where, t.getMessage());
            return;
        }
        errors++;
        String message = where + ": " + t;
        if (!message.equals(lastNearError)) {
            lastNearError = message;
            System.out.println("[TWZB] Viewpoint near-band depth " + message + ", two-band pass off (latch/crosshair/bridge stay on)");
        }
        nearReady = false;
        nearStatus = "off after error: " + message;
    }

    /** The latch carries the frame's own records at their drawing (ScopePipZoom's narrowing rides on it). */
    static boolean carries() {
        return ready && enabled;
    }

    /** The local player holds our weapon in the first-person pose layer now (any thread, on foot only). */
    static boolean layer() {
        IsoPlayer p = IsoPlayer.getInstance();
        FpPose.Request r = p == null ? null : FpPose.requests.get(p);
        return r != null && FpPose.enabled && r.allowed && !r.spent && r.fpW > 1e-4 && r.eyeValid && r.eye != null
                && (qaIgnoreVehicleLayer || (p.getVehicle() == null && !p.isSeatedInVehicle()))
                && p.getPrimaryHandItem() == r.item && System.nanoTime() - r.at < FpAdsCamera.STALE_NS;
    }

    /**
     * W11 camera2: the local player holds our weapon on foot (it has a pose request), also on a frame the layer does not
     * draw it (the pose still weightless or older than STALE_NS after a hitch): Viewpoint then draws it itself, and the
     * crosshair pick must not read it either.
     */
    static boolean held() {
        IsoPlayer p = IsoPlayer.getInstance();
        FpPose.Request r = p == null ? null : FpPose.requests.get(p);
        return r != null && FpPose.enabled && p.getPrimaryHandItem() == r.item && p.getVehicle() == null && !p.isSeatedInVehicle();
    }

    // ------------------------------------------------------------------ pure near-band depth math (tested offline)

    /**
     * Copies {@code baseProjection} (including Viewpoint's TAA sub-pixel jitter in m20/m21) and replaces only its
     * near/far clip planes with {@code [near, far]} (standard OpenGL [-1, 1] clip control, m23 = -1).
     */
    public static Matrix4f nearProjection(Matrix4f baseProjection, float near, float far, Matrix4f dest) {
        if (baseProjection == null || dest == null || !(near > 0f) || !(far > near) || !Float.isFinite(far)) {
            return null;
        }
        dest.set(baseProjection);
        dest.m22(-(far + near) / (far - near));
        dest.m32(-2.0f * far * near / (far - near));
        return dest.isFinite() ? dest : null;
    }

    /** Standard OpenGL [0, 1] window depth for eye-space forward distance {@code d} in {@code [near, far]} mapped to {@code [r0, r1]}. */
    public static double bandWindowDepth(double d, double near, double far, double r0, double r1) {
        if (!Double.isFinite(d) || d < near || d > far || !(far > near)) {
            return Double.NaN;
        }
        double ndc = (far + near) / (far - near) - (2.0 * far * near) / ((far - near) * d);
        return r0 + (r1 - r0) * (0.5 * ndc + 0.5);
    }

    /**
     * Effective window depth [0, 1] written for a SELF fragment at eye-space forward distance {@code eyeDepthScene}
     * (scene units) when the two-band monotonic pass is active; {@code NaN} if hardware-clipped (< NEAR_BAND_NEAR or > WORLD_FAR).
     */
    public static double selfWindowDepth(double eyeDepthScene) {
        if (!Double.isFinite(eyeDepthScene) || eyeDepthScene < NEAR_BAND_NEAR || eyeDepthScene > WORLD_FAR) {
            return Double.NaN;
        }
        double zNear = eyeDepthScene <= NEAR_BAND_FAR
                ? bandWindowDepth(eyeDepthScene, NEAR_BAND_NEAR, NEAR_BAND_FAR, 0.0, NEAR_BAND_SLICE)
                : Double.POSITIVE_INFINITY;
        double zFar = eyeDepthScene >= WORLD_NEAR
                ? bandWindowDepth(eyeDepthScene, WORLD_NEAR, WORLD_FAR, NEAR_BAND_SLICE, 1.0)
                : Double.POSITIVE_INFINITY;
        double best = Math.min(zNear, zFar);
        return Double.isFinite(best) ? best : Double.NaN;
    }

    /** Quantizes a [0, 1] window depth to the 24-bit integer stored in GL_DEPTH24_STENCIL8 ([0, 16,777,215]). */
    public static int depth24(double windowDepth) {
        if (!Double.isFinite(windowDepth)) {
            return -1;
        }
        double clamped = Math.max(0.0, Math.min(1.0, windowDepth));
        return (int) Math.round(clamped * DEPTH24_MAX);
    }

    /** Legacy single-pass GL_DEPTH_CLAMP 24-bit depth (every fragment with 0 < d <= WORLD_NEAR collapses to 0). */
    public static int legacyClampedDepth24(double eyeDepthScene) {
        if (!Double.isFinite(eyeDepthScene) || eyeDepthScene <= 0.0 || eyeDepthScene > WORLD_FAR) {
            return -1;
        }
        if (eyeDepthScene <= WORLD_NEAR) {
            return 0;
        }
        return depth24(bandWindowDepth(eyeDepthScene, WORLD_NEAR, WORLD_FAR, 0.0, 1.0));
    }

    // ------------------------------------------------------------------ main thread

    /** SightDrawFrame.captured, the local capture of a frame completed: remember the look the skeleton was posed with. */
    static void captured(Object frame, Object scene, IsoGameCharacter c) {
        if (!enabled || frame == null || scene == null || !detect()) {
            return;
        }
        FpPose.Request r = FpPose.requests.get(c);
        double[] eye = r == null ? null : r.eye;
        try {
            Pose pose = !layer() || eye == null || eye.length < 5 ? null
                    : new Pose(frame, ViewpointBridge.frameNumber(frame), (float) eye[3], (float) eye[4], null, FpFreeLook.free(eye));
            synchronized (poses) {
                if (pose == null) {
                    poses.remove(scene);
                } else {
                    poses.put(scene, pose);
                }
            }
        } catch (Throwable t) {
            error("capture", t);
        }
    }

    /**
     * SightDrawFrame.captured, after its pair of the same capture (FB5 item 4): the eye the pose layer posed this
     * skeleton for, scene space; null leaves the frame's own camera standing for it (the former latch, a turn only).
     */
    static void eye(Object scene, long number, Vector3f eye) {
        if (scene == null) {
            return;
        }
        synchronized (poses) {
            Pose p = poses.get(scene);
            if (p != null && p.number == number) {
                poses.put(scene, new Pose(p.frame, p.number, p.yaw, p.pitch, eye == null ? null : new float[] {eye.x, eye.y, eye.z}, p.free));
            }
        }
    }

    // ------------------------------------------------------------------ render thread

    /** Exit of Look.readBeforeDrawing (render thread, first thing of SceneDrawer.drawFrame): the look this drawing uses. */
    public static void lookRead() {
        if (!ready) {
            return;
        }
        try {
            float[] look = new float[2];
            ViewpointBridge.look(look);
            drawYaw = look[0];
            drawPitch = look[1];
            drawAt = System.nanoTime();
        } catch (Throwable t) {
            error("look", t);
        }
    }

    /**
     * Entry of WorldRenderer.begin (render thread): carry the frame's own records to the drawing's
     * camera, then their fade (FB3 item 4: the see-through weapon, SeeThrough) on the carried records.
     */
    public static void begin(Object scene) {
        latchBegin(scene);
        // Viewpoint 0.1.7: the records drawn are made inside this method, the fade goes on them there (blendExit)
        if (!ViewpointBridge.blended()) {
            SeeThrough.begin(scene);
        }
    }

    static void latchBegin(Object scene) {
        selfMissed();
        irisMissed();
        if (clampedByUs) {
            clampOff();
        }
        if (!ready || scene == null) {
            return;
        }
        draws++;
        if (ViewpointBridge.blended()) {
            synchronized (published) {
                published.remove(scene);
            }
            return;
        }
        try {
            Applied a;
            synchronized (published) {
                published.remove(scene);
                a = applied.get(scene);
            }
            Object rec = models.get(scene);
            if (rec == null) {
                return;
            }
            float[] data = (float[]) values.get(rec);
            if (a != null) {
                restore(a, data, scene);
            }
            if (ViewpointBridge.state() != 1) {
                return;
            }
            Pose pose;
            synchronized (poses) {
                pose = poses.get(scene);
            }
            if (!enabled) {
                if (FpJitterProbe.on && pose != null) {
                    FpJitterProbe.draw(pose.number, 4, 0, 0, pose.frame, scene, drawYaw, drawPitch, data);
                }
                return;
            }
            if (pose == null || ViewpointBridge.frameNumber(pose.frame) != pose.number) {
                if (FpJitterProbe.on) {
                    FpJitterProbe.draw(pose == null ? -1 : pose.number, pose == null ? 0 : 3, 0, 0, null, scene, drawYaw, drawPitch, data);
                }
                return;
            }
            if (System.nanoTime() - drawAt > LOOK_NS) {
                lookRead();
            }
            // bug wave 324: a free-look pose is drawn at its own look (no turn), only the eye's walk is carried
            float yawD = pose.free ? pose.yaw : drawYaw, pitchD = pose.free ? pose.pitch : drawPitch;
            if (pose.free) {
                freeLooks++;
            }
            float dy = (float) Math.IEEEremainder(yawD - pose.yaw, 2 * Math.PI), dp = pitchD - pose.pitch;
            if (Math.abs(dy) > LIMIT || Math.abs(dp) > LIMIT) {
                cuts++;
                if (FpJitterProbe.on) {
                    FpJitterProbe.draw(pose.number, 2, dy, dp, pose.frame, scene, drawYaw, drawPitch, data);
                }
                return;
            }
            Matrix4f c = correction(pose.frame, pose.yaw, pose.pitch, yawD, pitchD, eyeLatch ? pose.eye : null);
            if (c == null) {
                return;
            }
            if (pose.eye != null && eyeLatch) {
                eyeShifts++;
                lastShiftCm = shifted * TarkovBallistics.METRES_PER_CELL * 100;
                maxShiftCm = Math.max(maxShiftCm, lastShiftCm);
                sumShiftCm += lastShiftCm;
            }
            int n = apply(rec, data, c, a == null ? (a = new Applied()) : a, pose.number);
            // 30.10.2026 (the player: by magnification the weapon model was gone): the camera zoom by magnification
            // keeps the weapon and the hands at their aimed size - the carried records narrowed about the drawing
            // camera's axis by the shown zoom (ScopePipZoom.narrowing); saved above, so restore undoes it with C. The
            // published C stays rigid: the lens geometry and the reticle are the zoomed world's.
            float k = ScopePipZoom.narrowFor(scene, pose.number);
            if (k > 1) {
                float[] ed = new float[3], fd = new float[3];
                // 02.11.2026: with the frame's projection shifted onto the sight's axis (ScopePipZoom.axisShift) the
                // carried records are sheared back by the same shift, so they stay where the aimed view draws them.
                float[] shear = ScopePipZoom.shearFor(scene, pose.number);
                Matrix4f w = ViewpointBridge.camera(pose.frame, yawD, pitchD, ed, fd)
                        ? ScopePipZoom.narrowing(ed, fd, k, shear == null ? 0 : shear[0], shear == null ? 0 : shear[1]) : null;
                if (w != null) {
                    narrow(data, a, w);
                }
            }
            a.number = pose.number;
            a.c.set(c);
            synchronized (published) {
                applied.put(scene, a);
                published.put(scene, new Matrix4f(c));
            }
            latched++;
            records += n;
            if (FpJitterProbe.on) {
                FpJitterProbe.draw(pose.number, 1, dy, dp, pose.frame, scene, drawYaw, drawPitch, data);
            }
            double deg = Math.toDegrees(Math.hypot(dy, dp));
            lastYawDeg = Math.toDegrees(dy);
            lastPitchDeg = Math.toDegrees(dp);
            maxDeg = Math.max(maxDeg, deg);
            sumDeg += deg;
            int h = historyAt++ % HISTORY * 3;
            history[h] = pose.number;
            history[h + 1] = (float) lastYawDeg;
            history[h + 2] = (float) lastPitchDeg;
        } catch (Throwable t) {
            error("begin", t);
        }
    }

    // ------------------------------------------------------------------ Viewpoint 0.1.7: the blended frame

    /** The camera each recent step's records were posed for (render thread): the eye and the view direction, by step number. */
    private static final int STEPS = 8;
    private static final long[] stepNumber = new long[STEPS];
    /** Each step's own records relative to its camera (16 floats each), by instance. */
    private static final float[][] stepRel = new float[STEPS][64 * 16];
    private static final Object[][] stepKeys = new Object[STEPS][64];
    private static final int[] stepCount = new int[STEPS];
    private static Pose blendPose;
    private static float blendYaw, blendPitch;
    public static volatile long blendedFrames, blendedLerps;

    /**
     * Entry of ModelBlend.drawn (0.1.7, render thread): does the latch carry this frame's own records? Then the arms take no
     * turn to the newest mouse: ArmsTurn turns them about the shoulders, and the sights would leave the view by the
     * difference. The drawing's look is the one lookRead read for this drawing.
     */
    public static boolean blendEnter(Object raw, long number) {
        blendPose = null;
        if (!ready || !enabled || ViewpointPipBridge.picture()) {
            return false;
        }
        try {
            Object scene = ViewpointBridge.currentScene();
            if (scene == null || models.get(scene) != raw || ViewpointBridge.state() != 1) {
                return false;
            }
            Pose pose;
            synchronized (poses) {
                pose = poses.get(scene);
            }
            if (pose == null || pose.number != number || ViewpointBridge.frameNumber(pose.frame) != number) {
                if (FpJitterProbe.on) {
                    FpJitterProbe.draw(pose == null ? -1 : pose.number, pose == null ? 0 : 3, 0, 0, null, scene, drawYaw, drawPitch, (float[]) values.get(raw));
                }
                return false;
            }
            if (System.nanoTime() - drawAt > LOOK_NS) {
                lookRead();
            }
            float yawD = pose.free ? pose.yaw : drawYaw, pitchD = pose.free ? pose.pitch : drawPitch;
            float dy = (float) Math.IEEEremainder(yawD - pose.yaw, 2 * Math.PI), dp = pitchD - pose.pitch;
            if (Math.abs(dy) > LIMIT || Math.abs(dp) > LIMIT) {
                cuts++;
                if (FpJitterProbe.on) {
                    FpJitterProbe.draw(pose.number, 2, dy, dp, pose.frame, scene, drawYaw, drawPitch, (float[]) values.get(raw));
                }
                return false;
            }
            blendPose = pose;
            blendYaw = yawD;
            blendPitch = pitchD;
            return true;
        } catch (Throwable t) {
            error("blend enter", t);
            return false;
        }
    }

    /**
     * Exit of ModelBlend.drawn (0.1.7, render thread), the records not uploaded yet: the own records of the frame as this
     * drawing shows them, then their fade (SeeThrough). raw and drawn are the frame's captured records and their blend.
     */
    public static void blendExit(Object raw, Object drawn, long number, float blend) {
        if (ViewpointPipBridge.picture()) {
            return;
        }
        try {
            Object scene = ViewpointBridge.currentScene();
            VpBlend.drawing(scene, drawn);
            Pose pose = blendPose;
            blendPose = null;
            if (pose != null) {
                carryBlended(scene, pose, raw, drawn, number, blend);
            } else if (FpJitterProbe.on && !enabled && ready) {
                Pose off;
                synchronized (poses) {
                    off = poses.get(scene);
                }
                FpJitterProbe.draw(number, 4, 0, 0, off == null ? null : off.frame, scene, drawYaw, drawPitch, (float[]) values.get(drawn));
            }
            SeeThrough.begin(scene, drawn);
        } catch (Throwable t) {
            error("blend exit", t);
        }
    }

    /**
     * The same rigid carry as latchBegin, for a frame whose records are drawn between the game's steps. Each own record is
     * held relative to the camera it was posed for: at this drawing's place between the last two steps it is the blend of
     * the two steps' placements relative to their cameras, and that stays fixed to the drawing's camera. So the weapon does
     * not trail the view, its parts stay on it (a rigid part blends as its parent does), and its own motion against the view
     * (recoil, sway, the raise) stays smooth between steps. The drawn camera is the look of this drawing at the frame's
     * carried eye, without the shot's camera additive, as in latchBegin. Viewpoint's previous matrices (the last drawn ones,
     * ModelBlend.motion) stay: with the weapon fixed to the view they give it no motion. They are for its motion vectors, not
     * a placement: ModelDraws.motion moves them by the step's change of FarRelief.sink, so the steps' placements are the ones
     * kept here (stepRel), or the weapon jumps by that change at every step while walking.
     */
    private static void carryBlended(Object scene, Pose pose, Object rawDraws, Object drawnDraws, long number, float blend) throws Throwable {
        float[] raw = (float[]) values.get(rawDraws), out = (float[]) values.get(drawnDraws);
        int[] f = (int[]) flags.get(rawDraws);
        int total = count.getInt(rawDraws);
        ModelInstance[] inst = (ModelInstance[]) mdInstances.get(rawDraws);
        if (total < 0 || total > f.length || total > inst.length || (long) total * stride > raw.length || (long) total * stride > out.length) {
            throw new ViewpointBridge.DataMiss("record arrays shorter than their count");
        }
        float[] ep = new float[3], fp = new float[3], ed = new float[3], fd = new float[3], e0 = new float[3], f0 = new float[3];
        if (!ViewpointBridge.camera(pose.frame, pose.yaw, pose.pitch, ep, fp)
                || !ViewpointBridge.camera(pose.frame, blendYaw, blendPitch, ed, fd)
                || !ViewpointBridge.camera(pose.frame, drawYaw, drawPitch, e0, f0)) {
            return;
        }
        // the frame's eye carried between steps: the drawn eye against the frame's own camera at the drawing's look
        Object context = ViewpointBridge.frameContext();
        ed[0] += fcEyeX.getFloat(context) - e0[0];
        ed[1] += fcEyeY.getFloat(context) - e0[1];
        ed[2] += fcEyeZ.getFloat(context) - e0[2];
        shifted = 0;
        if (eyeLatch && pose.eye != null && finite(pose.eye)) {
            double dx = ep[0] - pose.eye[0], dy = ep[1] - pose.eye[1], dz = ep[2] - pose.eye[2], d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d <= EYE_LIMIT) {
                System.arraycopy(pose.eye, 0, ep, 0, 3);
                shifted = d;
                eyeShifts++;
                lastShiftCm = shifted * TarkovBallistics.METRES_PER_CELL * 100;
                maxShiftCm = Math.max(maxShiftCm, lastShiftCm);
                sumShiftCm += lastShiftCm;
            } else {
                eyeCuts++;
            }
        }
        Matrix4f c = between(ep, fp, ed, fd);
        if (c == null) {
            return;
        }
        Matrix4f toScene = lookAt(ed, fd).invertAffine(), posed = lookAt(ep, fp);
        // This step's camera and own records relative to it, kept for the drawings of the next two steps
        int at = (int) (number & (STEPS - 1));
        boolean fresh = stepNumber[at] != number;
        if (fresh) {
            stepNumber[at] = number;
            stepCount[at] = 0;
        }
        // Where this drawing stands between the steps: t of the newest over the one before; below 0, of the one before that
        float t = Math.max(-1f, Math.min(1f, blend));
        int was = (int) ((number - 1) & (STEPS - 1)), older = (int) ((number - 2) & (STEPS - 1));
        boolean hasLast = t < 1f && stepNumber[was] == number - 1;
        boolean hasOlder = t < 0f && stepNumber[older] == number - 2;
        IsoPlayer p = IsoPlayer.getInstance();
        ModelInstance weapon = p == null ? null : p.primaryHandModel;
        Matrix4f m = new Matrix4f(), a = new Matrix4f();
        Vector3f s = new Vector3f();
        Matrix4f held = null;
        int n = 0;
        for (int i = 0; i < total; i++) {
            if ((f[i] & self) == 0) {
                continue;
            }
            int o = i * stride;
            float[] keep = null;
            int keepAt = 0;
            if (fresh) {
                int kept = stepCount[at]++;
                if (kept >= stepKeys[at].length) {
                    stepKeys[at] = Arrays.copyOf(stepKeys[at], kept * 2);
                    stepRel[at] = Arrays.copyOf(stepRel[at], kept * 2 * 16);
                }
                stepKeys[at][kept] = inst[i];
                keep = stepRel[at];
                keepAt = kept * 16;
            }
            int lastAt = hasLast && (f[i] & motionBit) != 0 && inst[i] != null ? keptAt(was, inst[i], n) : -1;
            int oldAt = hasOlder && inst[i] != null ? keptAt(older, inst[i], n) : -1;
            boolean lerped = carryRecord(raw, out, o, sphere, standAt, uprightValue, posed, lastAt >= 0 ? stepRel[was] : null, lastAt,
                    toScene, c, t, oldAt >= 0 ? stepRel[older] : null, oldAt, keep, keepAt, m, a, s);
            if (lerped) {
                blendedLerps++;
            }
            if (inst[i] == weapon && weapon != null && held == null) {
                if (FpJitterProbe.on) {
                    posed.mul(a.set(raw, o), a);
                    float px = a.m30(), py = a.m31(), pz = a.m32();
                    lookAt(ed, fd).mul(m, a);
                    FpJitterProbe.blend(number, t, px, py, pz, a.m30(), a.m31(), a.m32());
                }
                // what this drawing did to the weapon's captured record: its readers (the scope's lens, the sight marks) follow it
                held = new Matrix4f(m).mul(a.set(raw, o).invert());
                if (!held.isFinite()) {
                    held = null;
                }
            }
            n++;
        }
        float k = ScopePipZoom.narrowFor(scene, pose.number);
        if (k > 1) {
            float[] shear = ScopePipZoom.shearFor(scene, pose.number);
            Matrix4f w = ScopePipZoom.narrowing(ed, fd, k, shear == null ? 0 : shear[0], shear == null ? 0 : shear[1]);
            if (w != null) {
                for (int i = 0; i < total; i++) {
                    if ((f[i] & self) == 0) {
                        continue;
                    }
                    int o = i * stride;
                    m.set(out, o);
                    w.mul(m, m).get(out, o);
                    w.transformPosition(s.set(out[o + sphere], out[o + sphere + 1], out[o + sphere + 2]));
                    out[o + sphere] = s.x;
                    out[o + sphere + 1] = s.y;
                    out[o + sphere + 2] = s.z;
                }
            }
        }
        synchronized (published) {
            published.put(scene, held != null ? held : new Matrix4f(c));
        }
        latched++;
        records += n;
        blendedFrames++;
        float dy = (float) Math.IEEEremainder(blendYaw - pose.yaw, 2 * Math.PI), dp = blendPitch - pose.pitch;
        if (FpJitterProbe.on) {
            FpJitterProbe.draw(pose.number, 1, dy, dp, pose.frame, scene, drawYaw, drawPitch, out);
        }
        double deg = Math.toDegrees(Math.hypot(dy, dp));
        lastYawDeg = Math.toDegrees(dy);
        lastPitchDeg = Math.toDegrees(dp);
        maxDeg = Math.max(maxDeg, deg);
        sumDeg += deg;
        int h = historyAt++ % HISTORY * 3;
        history[h] = pose.number;
        history[h + 1] = (float) lastYawDeg;
        history[h + 2] = (float) lastPitchDeg;
    }

    private static int keptAt(int step, Object key, int hint) {
        int count = stepCount[step];
        Object[] keys = stepKeys[step];
        if (hint < count && keys[hint] == key) {
            return hint * 16;
        }
        for (int i = 0; i < count; i++) {
            if (keys[i] == key) {
                return i * 16;
            }
        }
        return -1;
    }

    static Matrix4f lookAt(float[] e, float[] f) {
        return new Matrix4f().setLookAt(e[0], e[1], e[2], e[0] + f[0], e[1] + f[1], e[2] + f[2], 0, 1, 0);
    }

    /**
     * Pure: one own record as a blended frame draws it. Relative to the camera it was posed for (posed x captured), kept in
     * keep when given. With last (at lastAt, what keep held at the step before): the blend of that placement and this one, t
     * the share of the newest step; t below 0 goes on towards the step before that (prior at priorAt, relative to its camera),
     * or stays on the step before without it. A record that jumped between the steps (over 1 scene unit relative
     * to the camera) is not blended. toScene takes the result back to the scene from the drawing's camera; out's matrix, sphere
     * centre and upright stand follow, c (the rigid carry of this step) for the sphere; m is left holding the drawn matrix.
     * True when it was blended.
     */
    public static boolean carryRecord(float[] raw, float[] out, int o, int sphere, int stand, int upright,
            Matrix4f posed, float[] last, int lastAt, Matrix4f toScene, Matrix4f c, float t, float[] prior, int priorAt, float[] keep, int keepAt,
            Matrix4f m, Matrix4f a, Vector3f s) {
        posed.mul(a.set(raw, o), m);
        if (keep != null) {
            m.get(keep, keepAt);
        }
        boolean lerped = false;
        if (last != null && t < 1f) {
            a.set(last, lastAt);
            float dx = m.m30() - a.m30(), dy = m.m31() - a.m31(), dz = m.m32() - a.m32();
            if (dx * dx + dy * dy + dz * dz <= 1f) {
                if (t >= 0f) {
                    a.lerp(m, t, m);
                } else if (prior != null) {
                    a.lerp(m.set(prior, priorAt), -t, m);
                } else {
                    m.set(a);
                }
                lerped = true;
            }
        }
        toScene.mul(m, m);
        m.get(out, o);
        c.transformPosition(s.set(raw[o + sphere], raw[o + sphere + 1], raw[o + sphere + 2]));
        out[o + sphere] = s.x;
        out[o + sphere + 1] = s.y;
        out[o + sphere + 2] = s.z;
        if (raw[o + stand + 3] == upright) {
            out[o + stand] = raw[o + stand] + m.m30() - raw[o + 12];
            out[o + stand + 1] = raw[o + stand + 1] + m.m31() - raw[o + 13];
            out[o + stand + 2] = raw[o + stand + 2] + m.m32() - raw[o + 14];
        }
        return lerped;
    }

    /** C = view(draw)^-1 * view(pose): scene points posed for the pose's camera, seen alike from the drawing's camera. */
    static Matrix4f correction(Object frame, float yawP, float pitchP, float yawD, float pitchD) throws Throwable {
        return correction(frame, yawP, pitchP, yawD, pitchD, null);
    }

    /**
     * The same with the eye the pose layer posed for (FB5 item 4, scene space; null: the frame's own camera at the
     * pose's look). The skeleton was posed about that eye; the drawing looks from the frame's camera: a step of the
     * walk or of the head between the eye's prediction and the capture is carried as well, not only the look's turn.
     */
    static Matrix4f correction(Object frame, float yawP, float pitchP, float yawD, float pitchD, float[] poseEye) throws Throwable {
        float[] ep = new float[3], fp = new float[3], ed = new float[3], fd = new float[3];
        if (!ViewpointBridge.camera(frame, yawP, pitchP, ep, fp) || !ViewpointBridge.camera(frame, yawD, pitchD, ed, fd)) {
            return null;
        }
        shifted = 0;
        if (poseEye != null && finite(poseEye)) {
            double dx = ed[0] - poseEye[0], dy = ed[1] - poseEye[1], dz = ed[2] - poseEye[2], d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // a gap over EYE_LIMIT is a cut (a teleport, a view switch, an unrelated pose): carried as before, a turn only
            if (d <= EYE_LIMIT) {
                System.arraycopy(poseEye, 0, ep, 0, 3);
                shifted = d;
            } else {
                eyeCuts++;
            }
        }
        return between(ep, fp, ed, fd);
    }

    /** Pure: the rigid transform taking the camera at (ep, fp) onto the camera at (ed, fd), scene up +Y; null when degenerate. */
    public static Matrix4f between(float[] ep, float[] fp, float[] ed, float[] fd) {
        if (!finite(ep) || !finite(fp) || !finite(ed) || !finite(fd) || horizontal(fp) < 1e-3 || horizontal(fd) < 1e-3) {
            return null;
        }
        Matrix4f vp = new Matrix4f().setLookAt(ep[0], ep[1], ep[2], ep[0] + fp[0], ep[1] + fp[1], ep[2] + fp[2], 0, 1, 0);
        Matrix4f vd = new Matrix4f().setLookAt(ed[0], ed[1], ed[2], ed[0] + fd[0], ed[1] + fd[1], ed[2] + fd[2], 0, 1, 0);
        Matrix4f c = vd.invertAffine().mul(vp);
        return c.isFinite() ? c : null;
    }

    static boolean finite(float[] v) {
        return Float.isFinite(v[0]) && Float.isFinite(v[1]) && Float.isFinite(v[2]);
    }

    static double horizontal(float[] f) {
        double n = Math.sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]);
        return n < 1e-9 ? 0 : Math.hypot(f[0], f[2]) / n;
    }

    /** Carries every SELF record: its matrix and bounding sphere by c, a previous matrix captured
     * uncarried by the prior frame's transform. Saves what it changes. */
    static int apply(Object draws, float[] data, Matrix4f c, Applied a, long number) throws IllegalAccessException {
        int total = count.getInt(draws);
        int[] f = (int[]) flags.get(draws);
        if (total < 0 || total > f.length || (long) total * stride > data.length) {
            throw new ViewpointBridge.DataMiss("record arrays shorter than their count");
        }
        int n = 0;
        for (int i = 0; i < total; i++) {
            if ((f[i] & self) != 0) {
                n++;
            }
        }
        if (a.index.length < n) {
            a.index = new int[n];
            a.saved = new float[n * 35];
        }
        a.n = 0;
        boolean chained = priorNumber == number - 1 && priorN > 0;
        float[] originals = new float[n * 16];
        Matrix4f m = new Matrix4f();
        Vector3f s = new Vector3f();
        for (int i = 0; i < total; i++) {
            if ((f[i] & self) == 0) {
                continue;
            }
            int o = i * stride, k = a.n * 35;
            System.arraycopy(data, o, a.saved, k, 16);
            System.arraycopy(data, o + previous, a.saved, k + 16, 16);
            System.arraycopy(data, o + sphere, a.saved, k + 32, 3);
            System.arraycopy(data, o, originals, a.n * 16, 16);
            a.index[a.n++] = i;
            if (chained && priorOriginal(data, o + previous)) {
                m.set(data, o + previous);
                priorC.mul(m, m).get(data, o + previous);
                previousCarried++;
            }
            m.set(data, o);
            c.mul(m, m).get(data, o);
            c.transformPosition(s.set(data[o + sphere], data[o + sphere + 1], data[o + sphere + 2]));
            data[o + sphere] = s.x;
            data[o + sphere + 1] = s.y;
            data[o + sphere + 2] = s.z;
        }
        prior = originals;
        priorN = a.n;
        priorNumber = number;
        priorC.set(c);
        return a.n;
    }

    /** The records apply carried (a.index): their matrix, previous matrix and bounding sphere's centre by w on the left. */
    static void narrow(float[] data, Applied a, Matrix4f w) {
        Matrix4f m = new Matrix4f();
        Vector3f s = new Vector3f();
        for (int j = 0; j < a.n; j++) {
            int o = a.index[j] * stride;
            m.set(data, o);
            w.mul(m, m).get(data, o);
            m.set(data, o + previous);
            w.mul(m, m).get(data, o + previous);
            w.transformPosition(s.set(data[o + sphere], data[o + sphere + 1], data[o + sphere + 2]));
            data[o + sphere] = s.x;
            data[o + sphere + 1] = s.y;
            data[o + sphere + 2] = s.z;
        }
    }

    /** The 16 floats at off are exactly one captured matrix of the prior latched frame. */
    static boolean priorOriginal(float[] data, int off) {
        outer:
        for (int j = 0; j < priorN; j++) {
            for (int e = 0; e < 16; e++) {
                if (Float.floatToRawIntBits(prior[j * 16 + e]) != Float.floatToRawIntBits(data[off + e])) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** The same frame drawn again: its records as captured, before a new transform. */
    static void restore(Applied a, float[] data, Object scene) throws IllegalAccessException {
        Pose pose;
        synchronized (poses) {
            pose = poses.get(scene);
        }
        // a recaptured scene has fresh records: nothing of ours left in it
        if (pose != null && pose.number == a.number && ViewpointBridge.frameNumber(pose.frame) == a.number) {
            for (int j = 0; j < a.n; j++) {
                int o = a.index[j] * stride, k = j * 35;
                if (o + stride > data.length) {
                    break;
                }
                System.arraycopy(a.saved, k, data, o, 16);
                System.arraycopy(a.saved, k + 16, data, o + previous, 16);
                System.arraycopy(a.saved, k + 32, data, o + sphere, 3);
            }
        }
        a.n = 0;
        synchronized (published) {
            applied.remove(scene);
        }
    }

    /**
     * The transform this drawing gave the frame's own records (scene space), or null when none:
     * render-thread users of the weapon's capture-time frame (the scope picture, sensor screens)
     * multiply it on the left so they stay with the drawn weapon.
     */
    public static Matrix4f correction(Object scene) {
        synchronized (published) {
            Matrix4f c = published.get(scene);
            return c == null ? null : new Matrix4f(c);
        }
    }

    // ------------------------------------------------------------------ near-band SELF pass & legacy fallback

    /** Whether SELF is drawn in its own gbuffer pass (after MousePick.read, see deferSelf) though the near band is not. */
    private static boolean selfPassActive() {
        if (!ready || !clamp || clampedByUs || ViewpointPipBridge.picture() && pictureNear) {
            return false;
        }
        return ViewpointBridge.state() == 1 && (layer() || held()) && !GL11.glIsEnabled(GL32.GL_DEPTH_CLAMP);
    }

    /** Whether the near-band protection policy is active for this pass on the render thread. */
    private static boolean nearPolicyActive() {
        if (!ready || !clamp || clampedByUs || ViewpointPipBridge.picture() && pictureNear) {
            return false;
        }
        IsoPlayer p = IsoPlayer.getInstance();
        return ViewpointBridge.state() == 1 && layer() && !(FpAdsCamera.adsState(p) > 0 && !adsClampSafe(p))
                && !GL11.glIsEnabled(GL32.GL_DEPTH_CLAMP);
    }

    /**
     * W11 camera2 (items 2, 17): Viewpoint's crosshair pick (MousePick.read in WorldRenderer.drawWorld, right after
     * far.gbuffer) samples the gbuffer depth at the screen centre; SELF drawn at the exit of ModelPass.gbuffer put the
     * weapon's depth there (CrosshairAim.shown 0.05-0.6 m, the 1P<->3P hold then turned Look.yaw round). SELF now waits
     * for the exit of that read: the read sees surfaces, models, vehicles and far shells as without our weapon, and every
     * later reader the gbuffer with SELF as before (both depth-tested; SELF is the nearest in its bands). Until the read
     * advice ran once (not installed) and under qaSelfInPick SELF is drawn at once, as before.
     */
    private static boolean deferSelf(Object pass, Object frame, Matrix4f viewProj, boolean nearBand, int farN, int nearN) {
        if (!readHooked || qaSelfInPick) {
            return false;
        }
        pendingPass = pass;
        pendingFrame = frame;
        pendingViewProj = viewProj;
        pendingNearBand = nearBand;
        pendingFar = farN;
        pendingNear = nearN;
        return true;
    }

    /** Exit of MousePick.read (render thread; drawWorld and IrisFrame.opaque): the pending SELF of this frame. */
    public static void pickExit(Object frame) {
        readHooked = true;
        if (pendingFrame != null && pendingFrame == frame && drawPending()) {
            selfAtRead++;
        }
        if (irisFrame != null && irisFrame == frame && irisDrawPending()) {
            irisSelfAtRead++;
        }
    }

    /** Entry of IndirectPass.draw, the first reader of the gbuffer after the read: SELF if the read exit did not draw it. */
    public static void selfFallback(Object frame) {
        if (pendingFrame != null && pendingFrame == frame && drawPending()) {
            selfByFallback++;
        }
        if (qaGbufferProbe) {
            qaGbufferProbe = false;
            qaGbufferProbeResult = probeGbuffer();
        }
    }

    /** QA: packed GL_UNSIGNED_INT_24_8 depth/stencil of the gbuffer at the centre and eight points around it (stalls). */
    private static String probeGbuffer() {
        try {
            Object targets = lastGbufferTargets;
            int fbo = ViewpointBridge.gbufferFbo(targets);
            Field fw = ViewpointBridge.field("viewpoint.render.Targets.width");
            Field fh = ViewpointBridge.field("viewpoint.render.Targets.height");
            if (fbo <= 0 || fw == null || fh == null) {
                return "probe=unavailable";
            }
            int w = fw.getInt(targets), h = fh.getInt(targets);
            double[][] at = {{0.5, 0.5}, {0.5, 0.42}, {0.5, 0.3}, {0.62, 0.5}, {0.38, 0.5}, {0.5, 0.75}, {0.25, 0.25}, {0.75, 0.25}, {0.75, 0.8}};
            int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            java.nio.IntBuffer px = BufferUtils.createIntBuffer(1);
            StringBuilder out = new StringBuilder("probe=" + w + "x" + h + " selfMode=" + (qaSelfInPick ? "gbuffer" : "read"));
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            try {
                for (double[] a : at) {
                    int x = (int) Math.min(w - 1, Math.max(0, Math.floor(a[0] * w))), y = (int) Math.min(h - 1, Math.max(0, Math.floor(a[1] * h)));
                    px.clear();
                    GL11.glReadPixels(x, y, 1, 1, GL30.GL_DEPTH_STENCIL, GL30.GL_UNSIGNED_INT_24_8, px);
                    int v = px.get(0);
                    out.append(String.format(Locale.ROOT, " %d,%d:%d/%d", x, y, v >>> 8, v & 0xFF));
                }
            } finally {
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
            }
            return out.toString();
        } catch (Throwable t) {
            return "probe=error " + t;
        }
    }

    /** A later pass found SELF still pending: that frame was drawn without it (counted, never expected). */
    private static void selfMissed() {
        if (pendingFrame != null) {
            pendingFrame = null;
            pendingPass = null;
            selfMissed++;
        }
    }

    /** The Iris members, once; false leaves Viewpoint's own Iris draw of SELF. */
    private static synchronized boolean irisDetect() {
        if (irisChecked) {
            return irisReady;
        }
        irisChecked = true;
        try {
            ClassLoader cl = ViewpointBridge.class.getClassLoader();
            Member[] m = new Member[IRIS_PICK_MEMBERS.length];
            for (int i = 0; i < m.length; i++) {
                m[i] = ViewpointBridge.resolve(IRIS_PICK_MEMBERS[i], cl);
                if (m[i] == null) {
                    System.out.println("[TWZB] Viewpoint Iris pick: member " + IRIS_PICK_MEMBERS[i][0] + "." + IRIS_PICK_MEMBERS[i][1]
                            + " missing or changed, Viewpoint's own Iris draw of the weapon stays");
                    return false;
                }
            }
            irisEntities = (Method) m[0];
            irisPrograms = (Field) m[1];
            irisDraws = (Field) m[2];
            irisProgramGl = (Field) m[3];
            glSetInt = (Method) m[4];
            glSet2 = (Method) m[5];
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object key = Enum.valueOf((Class) Class.forName("viewpoint.render.IrisPrograms$Draw", false, cl), "ENTITIES");
            irisEntitiesKey = key;
            irisReady = true;
        } catch (Throwable t) {
            System.out.println("[TWZB] Viewpoint Iris pick: lookup failed " + t + ", Viewpoint's own Iris draw of the weapon stays");
        }
        return irisReady;
    }

    /**
     * Entry of IrisGeometry.models (render thread, an Iris frame): Viewpoint's ENTITIES ModelPass.iris would draw SELF
     * before IrisFrame.opaque reads the pick; SELF's colour bit is cleared (as gbufferEnter does) so it waits for the
     * read's exit, where irisDrawPending draws it through the same entities() wrapper.
     */
    public static void irisModelsEnter(Object geometry, Object pipeline, Object values, Object frame, Object shadows, Object pass) {
        irisMissed();
        if (!readHooked || qaSelfInPick || !ready || !nearReady || !clamp || ViewpointPipBridge.picture()
                || ViewpointBridge.state() != 1 || !(layer() || held()) || !irisDetect()) {
            return;
        }
        try {
            Object drawsObj = mpDraws.get(pass);
            Object batchesObj = mpBatches.get(pass);
            if (drawsObj == null || batchesObj == null) {
                return;
            }
            int total = count.getInt(drawsObj);
            int[] dFlags = (int[]) flags.get(drawsObj);
            int[] dMaterial = (int[]) mdMaterial.get(drawsObj);
            int[] order = (int[]) mbOrder.get(batchesObj);
            int orderedN = mbCount.getInt(batchesObj);
            if (total <= 0 || total > dFlags.length || total > dMaterial.length || orderedN < 0 || orderedN > order.length) {
                return;
            }
            int reqMask = self | colourBit, ord = 0, masked = 0;
            for (int r = 0; r < orderedN; r++) {
                int rec = order[r];
                if (rec >= 0 && rec < total && (dFlags[rec] & reqMask) == reqMask && dMaterial[rec] < 0) {
                    irisSelf = ensureInt(irisSelf, ord + 1);
                    irisSelf[ord++] = rec;
                }
            }
            if (ord == 0) {
                return;
            }
            for (int i = 0; i < total; i++) {
                if ((dFlags[i] & reqMask) == reqMask && dMaterial[i] < 0) {
                    irisMaskedRecs = ensureInt(irisMaskedRecs, masked + 1);
                    irisMaskedRecs[masked++] = i;
                    dFlags[i] &= ~colourBit;
                }
            }
            irisSelfCount = ord;
            irisMasked = masked;
            irisDrawsObj = drawsObj;
            irisGeometry = geometry;
            irisPipeline = pipeline;
            irisValues = values;
            irisShadows = shadows;
            irisPass = pass;
            irisFrame = frame;
        } catch (Throwable t) {
            irisRestore();
            irisFrame = null;
            nearError("irisModelsEnter", t);
        }
    }

    private static void irisRestore() {
        try {
            if (irisDrawsObj != null && irisMasked > 0) {
                restoreFlags((int[]) flags.get(irisDrawsObj), irisMaskedRecs, irisMasked, colourBit);
            }
        } catch (Throwable ignored) {
        }
        irisMasked = 0;
        irisDrawsObj = null;
    }

    /** SELF of an Iris frame still waiting at the next Iris frame or WorldRenderer.begin: flags back, counted. */
    private static void irisMissed() {
        if (irisFrame != null) {
            irisRestore();
            irisFrame = null;
            irisSelfMissed++;
        }
    }

    /** At the exit of IrisFrame.opaque's MousePick.read: SELF through Viewpoint's entities() with the ENTITIES program. */
    private static boolean irisDrawPending() {
        Object frame = irisFrame;
        irisFrame = null;
        irisRestore();
        try {
            Object program = ((java.util.Map<?, ?>) irisDraws.get(irisPrograms.get(irisPipeline))).get(irisEntitiesKey);
            if (program == null) {
                return false;
            }
            Object gl = irisProgramGl.get(program);
            Object pass = irisPass;
            Object batchesObj = mpBatches.get(pass), runsObj = mpRuns.get(pass);
            int n = irisSelfCount;
            int[] recs = irisSelf;
            Throwable[] failed = new Throwable[1];
            Runnable draw = () -> {
                try {
                    glSetInt.invoke(gl, "uShadowPalette", 0);
                    glSet2.invoke(gl, "uIrisGlassRange", 0.04f, 0.85f);
                    mpBegin.invokeExact(pass);
                    try {
                        int ignored = (int) mbInOrder.invokeExact(batchesObj, recs, n, acceptAllFilter, runsObj);
                    } finally {
                        mpEnd.invokeExact(pass);
                    }
                } catch (Throwable t) {
                    failed[0] = t;
                }
            };
            irisEntities.invoke(irisGeometry, irisPipeline, irisValues, frame, irisShadows, program, false, draw);
            if (failed[0] != null) {
                throw failed[0];
            }
            return true;
        } catch (Throwable t) {
            nearError("irisPickExit", t);
            return false;
        }
    }

    private static boolean drawPending() {
        Object pass = pendingPass;
        pendingFrame = null;
        pendingPass = null;
        try {
            int gbuffer = ViewpointBridge.gbufferFbo(mpTargets.get(pass));
            int bound = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            boolean rebind = gbuffer > 0 && bound != gbuffer;
            if (rebind) {
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, gbuffer);
                selfRebinds++;
            }
            try {
                drawSelfBands(pass, pendingViewProj, pendingNearBand, pendingFar, pendingNear);
            } finally {
                if (rebind) {
                    GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, bound);
                }
            }
            return true;
        } catch (Throwable t) {
            nearError("pickExit", t);
            return false;
        }
    }

    /** SELF's far + near monotonic bands into the bound gbuffer (bandFarRecs / bandNearRecs, nearViewProjScratch). */
    private static void drawSelfBands(Object pass, Matrix4f viewProj, boolean nearBand, int farN, int nearN) throws Throwable {
        Object gbufferProg = mpGbufferProg.get(pass);
        Object targetsObj = mpTargets.get(pass);
        Object batchesObj = mpBatches.get(pass);
        Object runsObj = mpRuns.get(pass);
        // W12: snapshot the world before SELF so Viewpoint DoF cannot focus on the weapon.
        try { FpDepthOfField.beforeSelf(); } catch (Throwable ignored) { }
        targetsVelocity.invokeExact(targetsObj, true);
        progUse.invokeExact(gbufferProg);
        mpBegin.invokeExact(pass);
        GL11.glDisable(GL32.GL_DEPTH_CLAMP);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        try {
            if (farN > 0) {
                progSetMat4.invokeExact(gbufferProg, "uViewProjection", viewProj);
                GL11.glDepthRange(nearBand ? NEAR_BAND_SLICE : 0.0, 1.0);
                int ignored = (int) mbInOrder.invokeExact(batchesObj, bandFarRecs, farN, acceptAllFilter, runsObj);
            }
            if (nearN > 0) {
                progSetMat4.invokeExact(gbufferProg, "uViewProjection", nearViewProjScratch);
                GL11.glDepthRange(0.0, NEAR_BAND_SLICE);
                int drawnNear = (int) mbInOrder.invokeExact(batchesObj, bandNearRecs, nearN, acceptAllFilter, runsObj);
                nearDraws += drawnNear;
            }
            try { FpDepthOfField.afterSelf(); } catch (Throwable ignored) { }
        } finally {
            GL11.glDepthRange(0.0, 1.0);
            progSetMat4.invokeExact(gbufferProg, "uViewProjection", viewProj);
            GL11.glDisable(GL11.GL_STENCIL_TEST);
            targetsVelocity.invokeExact(targetsObj, false);
            mpEnd.invokeExact(pass);
        }
    }

    /** Legacy single-pass GL_DEPTH_CLAMP entry (kept for QA A/B when nearestWins == false or if NEAR_MEMBERS is off). */
    public static void clampOn() {
        if (!nearPolicyActive()) {
            return;
        }
        try {
            GL11.glEnable(GL32.GL_DEPTH_CLAMP);
            clampedByUs = true;
            clamps++;
            int func = GL11.glGetInteger(GL11.GL_DEPTH_FUNC), want = clampDepthFunc(ironSight());
            if (func != want) {
                prevDepthFunc = func;
                GL11.glDepthFunc(want);
            }
        } catch (Throwable t) {
            error("clamp", t);
        }
    }

    /** Exit of legacy single-pass clamp: only a clamp we enabled is disabled. */
    public static void clampOff() {
        if (!clampedByUs) {
            return;
        }
        clampedByUs = false;
        try {
            if (prevDepthFunc >= 0) {
                GL11.glDepthFunc(prevDepthFunc);
                prevDepthFunc = -1;
            }
            GL11.glDisable(GL32.GL_DEPTH_CLAMP);
        } catch (Throwable t) {
            error("clamp", t);
        }
    }

    /**
     * Depth function used by the two-band monotonic SELF pass (always GL_LESS = 513 because fragments in [0.58 mm, 400]
     * carry strictly monotonic depth). Kept callable by offline gates.
     */
    static int clampDepthFunc(boolean ironSight) {
        return GL11.GL_LESS;
    }

    private static boolean ironSight() {
        IsoPlayer p = IsoPlayer.getInstance();
        FpPose.Request r = p == null ? null : FpPose.requests.get(p);
        return r != null && r.ironSight;
    }

    /**
     * FB7 item 4, W7, W9: whether near-band protection may stay on in ADS for the pose being drawn (FpPose.drawnPose):
     * true for every iron/built-in sight and for every optic whose own eyepiece aperture stays at or beyond the near
     * plane in that pose; false when an eyecup tube enters the near plane (so the 0.05 plane clips the eyecup).
     */
    static boolean adsClampSafe(IsoPlayer p) {
        boolean safe = FpPose.clampSafe(p == null ? null : FpPose.requests.get(p), FpPose.drawnPose(p));
        if (safe) {
            adsClamps++;
        }
        return safe;
    }

    /** QA (FB8 item 1, A/B in one run): false lets the main view's ADS clamp into the scope picture again, as before. */
    static volatile boolean pictureNear = true;

    private static int[] ensureInt(int[] arr, int need) {
        return arr.length >= need ? arr : Arrays.copyOf(arr, Math.max(need, arr.length * 2));
    }

    private static void restoreFlags(int[] dFlags, int[] masked, int n, int bit) {
        if (dFlags == null) {
            return;
        }
        for (int i = 0; i < n; i++) {
            int rec = masked[i];
            if (rec >= 0 && rec < dFlags.length) {
                dFlags[rec] |= bit;
            }
        }
    }

    private static boolean sphereIn(FrustumIntersection f, float[] data, int rec) {
        int s = rec * stride + sphere;
        if (s + 4 > data.length) {
            return false;
        }
        return f.testSphere(data[s], data[s + 1], data[s + 2], data[s + 3]);
    }

    /** Permutes SELF records inside each (material, layout, cull) bucket (keys >>> 50) for QA order-independence testing. */
    private static void permuteBuckets(int[] recs, long[] recKeys, int count, int mode) {
        if (mode == 0 || count <= 1) {
            return;
        }
        int s = 0;
        while (s < count) {
            long bucket = recKeys[s] >>> 50;
            int e = s + 1;
            while (e < count && (recKeys[e] >>> 50) == bucket) {
                e++;
            }
            if (e - s > 1) {
                if (mode == 1) {
                    Arrays.sort(recs, s, e);
                } else if (mode == 2) {
                    Arrays.sort(recs, s, e);
                    for (int i = s, j = e - 1; i < j; i++, j--) {
                        int t = recs[i];
                        recs[i] = recs[j];
                        recs[j] = t;
                    }
                } else if (mode == 3) {
                    for (int i = s, j = e - 1; i < j; i++, j--) {
                        int t = recs[i];
                        recs[i] = recs[j];
                        recs[j] = t;
                    }
                }
            }
            s = e;
        }
    }

    private static long[] selfOrderKeys = new long[64], glassOrderKeys = new long[32];

    /** Entry of ModelPass.gbuffer(FrameContext): when policy is active, mask SELF records out of the main draw. */
    public static void gbufferEnter(Object pass, Object frame) {
        selfMissed();
        gbufferTwoBandActive = false;
        gbufferNearBandActive = false;
        maskedGbufferCount = 0;
        selfOrderedCount = 0;
        legacyOrderSaved = false;
        legacySavedCount = 0;
        boolean wantDump = qaDumpTag != null;
        if (wantDump && ready) {
            try {
                qaEnterFuncBefore = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            } catch (Throwable ignored) { }
        }
        boolean nearActive = nearPolicyActive();
        boolean selfActive = nearActive || (nearestWins && nearReady && selfPassActive());
        if (pass == null || frame == null || !selfActive) {
            return;
        }
        if (!nearestWins || !nearReady) {
            clampOn();
            if (wantDump) {
                try {
                    qaEnterFuncAfter = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
                    qaEnterClamp = GL11.glIsEnabled(GL32.GL_DEPTH_CLAMP);
                } catch (Throwable ignored) { }
            }
            if (qaOrderMode != 0 && nearReady) {
                applyLegacyOrderOverride(pass, qaOrderMode);
            }
            return;
        }
        try {
            Object drawsObj = mpDraws.get(pass);
            Object batchesObj = mpBatches.get(pass);
            if (drawsObj == null || batchesObj == null) {
                return;
            }
            int total = count.getInt(drawsObj);
            if (total <= 0) {
                return;
            }
            int[] dFlags = (int[]) flags.get(drawsObj);
            int[] dMaterial = (int[]) mdMaterial.get(drawsObj);
            int[] order = (int[]) mbOrder.get(batchesObj);
            long[] keys = (long[]) mbKeys.get(batchesObj);
            int orderedN = mbCount.getInt(batchesObj);
            if (total > dFlags.length || total > dMaterial.length || orderedN < 0 || orderedN > order.length || orderedN > keys.length) {
                throw new ViewpointBridge.DataMiss("gbuffer record/batch arrays shorter than count");
            }
            int reqMask = self | colourBit;
            int ord = 0;
            for (int r = 0; r < orderedN; r++) {
                int rec = order[r];
                if (rec >= 0 && rec < total && (dFlags[rec] & reqMask) == reqMask && dMaterial[rec] < 0) {
                    selfOrdered = ensureInt(selfOrdered, ord + 1);
                    if (selfOrderKeys.length < ord + 1) {
                        selfOrderKeys = Arrays.copyOf(selfOrderKeys, Math.max(ord + 1, selfOrderKeys.length * 2));
                    }
                    selfOrdered[ord] = rec;
                    selfOrderKeys[ord] = keys[r];
                    ord++;
                }
            }
            if (qaOrderMode != 0 && ord > 1) {
                permuteBuckets(selfOrdered, selfOrderKeys, ord, qaOrderMode);
            }
            int masked = 0;
            for (int i = 0; i < total; i++) {
                if ((dFlags[i] & reqMask) == reqMask && dMaterial[i] < 0) {
                    maskedGbufferRecs = ensureInt(maskedGbufferRecs, masked + 1);
                    maskedGbufferRecs[masked++] = i;
                    dFlags[i] &= ~colourBit;
                }
            }
            maskedGbufferCount = masked;
            selfOrderedCount = ord;
            gbufferTwoBandActive = true;
            gbufferNearBandActive = nearActive;
            if (nearActive) {
                clamps++;
                bandPasses++;
            }
            if (wantDump) {
                qaEnterFuncAfter = GL11.GL_LESS;
                qaEnterClamp = false;
            }
        } catch (Throwable t) {
            try {
                Object drawsObj = mpDraws.get(pass);
                if (drawsObj != null) {
                    restoreFlags((int[]) flags.get(drawsObj), maskedGbufferRecs, maskedGbufferCount, colourBit);
                }
            } catch (Throwable ignored) { }
            maskedGbufferCount = 0;
            selfOrderedCount = 0;
            gbufferTwoBandActive = false;
            gbufferNearBandActive = false;
            nearError("gbufferEnter", t);
        }
    }

    /** Exit of ModelPass.gbuffer(FrameContext): restore SELF flags and draw SELF in far + near monotonic bands (or after the pick: deferSelf). */
    public static void gbufferExit(Object pass, Object frame) {
        int lastFar = 0, lastNear = 0;
        boolean usedTwoBand = gbufferNearBandActive;
        if (gbufferTwoBandActive) {
            gbufferTwoBandActive = false;
            boolean nearBand = gbufferNearBandActive;
            gbufferNearBandActive = false;
            Object drawsObj = null;
            int[] dFlags = null;
            try {
                drawsObj = pass == null ? null : mpDraws.get(pass);
                lastGbufferTargets = pass == null ? null : mpTargets.get(pass);
                if (drawsObj != null) {
                    dFlags = (int[]) flags.get(drawsObj);
                    restoreFlags(dFlags, maskedGbufferRecs, maskedGbufferCount, colourBit);
                }
                if (pass != null && frame != null && drawsObj != null && selfOrderedCount > 0) {
                    float[] dValues = (float[]) values.get(drawsObj);
                    Matrix4f viewMat = (Matrix4f) fcView.get(frame);
                    Matrix4f jitteredProj = (Matrix4f) fcJitteredProj.get(frame);
                    Matrix4f viewProj = (Matrix4f) fcViewProj.get(frame);
                    Object gbufferProg = mpGbufferProg.get(pass);
                    Object targetsObj = mpTargets.get(pass);
                    Object batchesObj = mpBatches.get(pass);
                    Object runsObj = mpRuns.get(pass);
                    Matrix4f np = nearProjection(jitteredProj, NEAR_BAND_NEAR, NEAR_BAND_FAR, nearProjScratch);
                    if (np != null && viewMat != null && viewProj != null && gbufferProg != null
                            && targetsObj != null && batchesObj != null && runsObj != null) {
                        np.mul(viewMat, nearViewProjScratch);
                        farFrustum.set(viewProj);
                        nearFrustum.set(nearViewProjScratch);
                        bandFarRecs = ensureInt(bandFarRecs, selfOrderedCount);
                        bandNearRecs = ensureInt(bandNearRecs, selfOrderedCount);
                        int farN = 0, nearN = 0;
                        for (int i = 0; i < selfOrderedCount; i++) {
                            int rec = selfOrdered[i];
                            if (sphereIn(farFrustum, dValues, rec)) {
                                bandFarRecs[farN++] = rec;
                            }
                            if (nearBand && sphereIn(nearFrustum, dValues, rec)) {
                                bandNearRecs[nearN++] = rec;
                            }
                        }
                        lastFar = farN;
                        lastNear = nearN;
                        if ((farN > 0 || nearN > 0) && !deferSelf(pass, frame, viewProj, nearBand, farN, nearN)) {
                            drawSelfBands(pass, viewProj, nearBand, farN, nearN);
                            selfAtGbuffer++;
                        }
                    }
                }
            } catch (Throwable t) {
                nearError("gbufferExit", t);
            } finally {
                restoreFlags(dFlags, maskedGbufferRecs, maskedGbufferCount, colourBit);
                maskedGbufferCount = 0;
            }
        }
        if (qaDumpTag != null && pass != null && ready && !ViewpointPipBridge.picture()) {
            captureQaDump(pass, usedTwoBand, lastFar, lastNear);
        }
        if (legacyOrderSaved && pass != null) {
            restoreLegacyOrderOverride(pass);
        }
        clampOff();
    }

    /** Entry of ModelPass.glass(FrameContext, ShadowPass): mask SELF | GLASS records when two-band policy is active. */
    public static void glassEnter(Object pass, Object frame, Object shadows) {
        selfMissed();
        glassTwoBandActive = false;
        maskedGlassCount = 0;
        glassOrderedCount = 0;
        if (pass == null || frame == null || shadows == null || !nearPolicyActive()) {
            return;
        }
        if (!nearestWins || !nearReady) {
            clampOn();
            return;
        }
        try {
            Object drawsObj = mpDraws.get(pass);
            Object batchesObj = mpBatches.get(pass);
            if (drawsObj == null || batchesObj == null) {
                return;
            }
            int total = count.getInt(drawsObj);
            if (total <= 0) {
                return;
            }
            int[] dFlags = (int[]) flags.get(drawsObj);
            int[] dMaterial = (int[]) mdMaterial.get(drawsObj);
            int[] order = (int[]) mbOrder.get(batchesObj);
            long[] keys = (long[]) mbKeys.get(batchesObj);
            int orderedN = mbCount.getInt(batchesObj);
            if (total > dFlags.length || total > dMaterial.length || orderedN < 0 || orderedN > order.length || orderedN > keys.length) {
                throw new ViewpointBridge.DataMiss("glass record/batch arrays shorter than count");
            }
            int reqMask = self | glassBit | colourBit;
            int ord = 0;
            for (int r = 0; r < orderedN; r++) {
                int rec = order[r];
                if (rec >= 0 && rec < total && (dFlags[rec] & reqMask) == reqMask && dMaterial[rec] < 0) {
                    glassOrdered = ensureInt(glassOrdered, ord + 1);
                    if (glassOrderKeys.length < ord + 1) {
                        glassOrderKeys = Arrays.copyOf(glassOrderKeys, Math.max(ord + 1, glassOrderKeys.length * 2));
                    }
                    glassOrdered[ord] = rec;
                    glassOrderKeys[ord] = keys[r];
                    ord++;
                }
            }
            if (ord == 0) {
                return;
            }
            if (qaOrderMode != 0 && ord > 1) {
                permuteBuckets(glassOrdered, glassOrderKeys, ord, qaOrderMode);
            }
            int masked = 0;
            for (int i = 0; i < total; i++) {
                if ((dFlags[i] & reqMask) == reqMask && dMaterial[i] < 0) {
                    maskedGlassRecs = ensureInt(maskedGlassRecs, masked + 1);
                    maskedGlassRecs[masked++] = i;
                    dFlags[i] &= ~colourBit;
                }
            }
            maskedGlassCount = masked;
            glassOrderedCount = ord;
            glassTwoBandActive = true;
        } catch (Throwable t) {
            try {
                Object drawsObj = mpDraws.get(pass);
                if (drawsObj != null) {
                    restoreFlags((int[]) flags.get(drawsObj), maskedGlassRecs, maskedGlassCount, colourBit);
                }
            } catch (Throwable ignored) { }
            maskedGlassCount = 0;
            glassOrderedCount = 0;
            glassTwoBandActive = false;
            nearError("glassEnter", t);
        }
    }

    /** Exit of ModelPass.glass(FrameContext, ShadowPass): restore SELF | GLASS flags and draw in far + near bands. */
    public static void glassExit(Object pass, Object frame, Object shadows) {
        if (glassTwoBandActive) {
            glassTwoBandActive = false;
            int[] dFlags = null;
            try {
                Object drawsObj = pass == null ? null : mpDraws.get(pass);
                if (drawsObj != null) {
                    dFlags = (int[]) flags.get(drawsObj);
                    restoreFlags(dFlags, maskedGlassRecs, maskedGlassCount, colourBit);
                }
                if (pass != null && frame != null && shadows != null && drawsObj != null && glassOrderedCount > 0) {
                    float[] dValues = (float[]) values.get(drawsObj);
                    Matrix4f viewMat = (Matrix4f) fcView.get(frame);
                    Matrix4f jitteredProj = (Matrix4f) fcJitteredProj.get(frame);
                    Matrix4f viewProj = (Matrix4f) fcViewProj.get(frame);
                    Object glassProg = mpGlassProg.get(pass);
                    Object batchesObj = mpBatches.get(pass);
                    Object runsObj = mpRuns.get(pass);
                    Matrix4f np = nearProjection(jitteredProj, NEAR_BAND_NEAR, NEAR_BAND_FAR, nearProjScratch);
                    if (np != null && viewMat != null && viewProj != null && glassProg != null
                            && batchesObj != null && runsObj != null) {
                        np.mul(viewMat, nearViewProjScratch);
                        farFrustum.set(viewProj);
                        nearFrustum.set(nearViewProjScratch);
                        bandFarRecs = ensureInt(bandFarRecs, glassOrderedCount);
                        bandNearRecs = ensureInt(bandNearRecs, glassOrderedCount);
                        int farN = 0, nearN = 0;
                        for (int i = 0; i < glassOrderedCount; i++) {
                            int rec = glassOrdered[i];
                            if (sphereIn(farFrustum, dValues, rec)) {
                                bandFarRecs[farN++] = rec;
                            }
                            if (sphereIn(nearFrustum, dValues, rec)) {
                                bandNearRecs[nearN++] = rec;
                            }
                        }
                        if (farN > 0 || nearN > 0) {
                            mpBegin.invokeExact(pass);
                            progUse.invokeExact(glassProg);
                            shadowBind.invokeExact(shadows, true);
                            GL11.glDepthMask(false);
                            GL11.glDisable(GL32.GL_DEPTH_CLAMP);
                            try {
                                if (farN > 0) {
                                    progSetMat4.invokeExact(glassProg, "uViewProjection", viewProj);
                                    GL11.glDepthRange(NEAR_BAND_SLICE, 1.0);
                                    int ignored = (int) mbInOrder.invokeExact(batchesObj, bandFarRecs, farN, acceptAllFilter, runsObj);
                                }
                                if (nearN > 0) {
                                    progSetMat4.invokeExact(glassProg, "uViewProjection", nearViewProjScratch);
                                    GL11.glDepthRange(0.0, NEAR_BAND_SLICE);
                                    int ignored = (int) mbInOrder.invokeExact(batchesObj, bandNearRecs, nearN, acceptAllFilter, runsObj);
                                }
                            } finally {
                                GL11.glDepthRange(0.0, 1.0);
                                progSetMat4.invokeExact(glassProg, "uViewProjection", viewProj);
                                GL11.glDepthMask(true);
                                shadowBind.invokeExact(shadows, false);
                                mpEnd.invokeExact(pass);
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                nearError("glassExit", t);
            } finally {
                restoreFlags(dFlags, maskedGlassRecs, maskedGlassCount, colourBit);
                maskedGlassCount = 0;
            }
        }
        clampOff();
    }

    // ------------------------------------------------------------------ QA-only order override & dump helpers

    static volatile int qaOrderMode;
    static volatile String qaDumpTag;
    static volatile String qaLastDump = "";
    private static boolean qaFieldsChecked, qaFieldsReady;
    private static Field qaMpDraws, qaMpHeld, qaMpDrawn, qaMpSources, qaMdMeshes, qaMdInstances, qaMdCull;
    private static Field qaHeldId, qaHeldLayout, qaLayoutIndex;
    private static int qaEnterFuncBefore, qaEnterFuncAfter;
    private static boolean qaEnterClamp;
    private static int[] qaPosTmp = new int[64], qaRecTmp = new int[64];
    private static final java.nio.DoubleBuffer qaDepthRangeBuf = BufferUtils.createDoubleBuffer(2);

    private static synchronized boolean qaEnsureFields() {
        if (qaFieldsChecked) {
            return qaFieldsReady;
        }
        qaFieldsChecked = true;
        try {
            ClassLoader cl = FpViewLatch.class.getClassLoader();
            Member[] qf = new Member[QA_MEMBERS.length];
            for (int i = 0; i < qf.length; i++) {
                qf[i] = ViewpointBridge.resolve(QA_MEMBERS[i], cl);
                if (qf[i] == null) {
                    return false;
                }
            }
            qaMpDraws = (Field) qf[0];
            qaMdMeshes = (Field) qf[1];
            qaMdInstances = (Field) qf[2];
            qaMdCull = (Field) qf[3];
            qaHeldId = (Field) qf[4];
            qaHeldLayout = (Field) qf[5];
            qaLayoutIndex = (Field) qf[6];
            String[][] sources = ViewpointBridge.blended() ? QA_BLEND_SOURCES : QA_SOURCES;
            Member[] sf = new Member[sources.length];
            for (int i = 0; i < sf.length; i++) {
                sf[i] = ViewpointBridge.resolve(sources[i], cl);
                if (sf[i] == null) {
                    return false;
                }
            }
            qaMpSources = sf.length == 3 ? (Field) sf[0] : null;
            qaMpHeld = (Field) sf[sf.length - 2];
            qaMpDrawn = (Field) sf[sf.length - 1];
            qaFieldsReady = true;
        } catch (Throwable t) {
            qaFieldsReady = false;
        }
        return qaFieldsReady;
    }

    private static void applyLegacyOrderOverride(Object pass, int mode) {
        if (!qaEnsureFields()) {
            return;
        }
        try {
            Object drawsObj = qaMpDraws.get(pass);
            Object batchesObj = mpBatches.get(pass);
            if (drawsObj == null || batchesObj == null) {
                return;
            }
            int[] dFlags = (int[]) flags.get(drawsObj);
            int[] dMaterial = (int[]) mdMaterial.get(drawsObj);
            int[] order = (int[]) mbOrder.get(batchesObj);
            int[] rank = (int[]) mbRank.get(batchesObj);
            long[] keys = (long[]) mbKeys.get(batchesObj);
            int n = mbCount.getInt(batchesObj);
            int saved = 0, s = 0;
            while (s < n) {
                long bucket = keys[s] >>> 50;
                int e = s + 1;
                while (e < n && (keys[e] >>> 50) == bucket) {
                    e++;
                }
                int m = 0;
                for (int r = s; r < e; r++) {
                    int rec = order[r];
                    if ((dFlags[rec] & (self | colourBit)) == (self | colourBit) && dMaterial[rec] < 0) {
                        qaPosTmp = ensureInt(qaPosTmp, m + 1);
                        qaRecTmp = ensureInt(qaRecTmp, m + 1);
                        qaPosTmp[m] = r;
                        qaRecTmp[m] = rec;
                        m++;
                    }
                }
                if (m > 1) {
                    legacySavedPos = ensureInt(legacySavedPos, saved + m);
                    legacySavedRec = ensureInt(legacySavedRec, saved + m);
                    for (int k = 0; k < m; k++) {
                        legacySavedPos[saved + k] = qaPosTmp[k];
                        legacySavedRec[saved + k] = qaRecTmp[k];
                    }
                    saved += m;
                    if (mode == 1) {
                        Arrays.sort(qaRecTmp, 0, m);
                    } else if (mode == 2) {
                        Arrays.sort(qaRecTmp, 0, m);
                        for (int i = 0, j = m - 1; i < j; i++, j--) {
                            int t = qaRecTmp[i];
                            qaRecTmp[i] = qaRecTmp[j];
                            qaRecTmp[j] = t;
                        }
                    } else if (mode == 3) {
                        for (int i = 0, j = m - 1; i < j; i++, j--) {
                            int t = qaRecTmp[i];
                            qaRecTmp[i] = qaRecTmp[j];
                            qaRecTmp[j] = t;
                        }
                    }
                    for (int k = 0; k < m; k++) {
                        int pos = qaPosTmp[k], rec = qaRecTmp[k];
                        order[pos] = rec;
                        rank[rec] = pos;
                    }
                }
                s = e;
            }
            if (saved > 0) {
                legacySavedCount = saved;
                legacyOrderSaved = true;
            }
        } catch (Throwable ignored) { }
    }

    private static void restoreLegacyOrderOverride(Object pass) {
        legacyOrderSaved = false;
        try {
            Object batchesObj = mpBatches.get(pass);
            if (batchesObj == null) {
                return;
            }
            int[] order = (int[]) mbOrder.get(batchesObj);
            int[] rank = (int[]) mbRank.get(batchesObj);
            for (int k = 0; k < legacySavedCount; k++) {
                int pos = legacySavedPos[k], rec = legacySavedRec[k];
                order[pos] = rec;
                rank[rec] = pos;
            }
        } catch (Throwable ignored) {
        } finally {
            legacySavedCount = 0;
        }
    }

    private static void captureQaDump(Object pass, boolean usedTwoBand, int farN, int nearN) {
        String tag = qaDumpTag;
        if (tag == null || !nearReady || !qaEnsureFields()) {
            return;
        }
        try {
            int funcExit = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            boolean clampExit = GL11.glIsEnabled(GL32.GL_DEPTH_CLAMP);
            qaDepthRangeBuf.clear();
            GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, qaDepthRangeBuf);
            double dr0 = qaDepthRangeBuf.get(0), dr1 = qaDepthRangeBuf.get(1);
            Object drawsObj = qaMpDraws.get(pass);
            Object batchesObj = mpBatches.get(pass);
            Object sourceOwner = qaMpSources != null ? qaMpSources.get(pass) : pass;
            Object[] heldArr = (Object[]) qaMpHeld.get(sourceOwner);
            Texture[] drawnArr = (Texture[]) qaMpDrawn.get(sourceOwner);
            if (drawsObj == null || batchesObj == null || heldArr == null || drawnArr == null) {
                return;
            }
            int[] dFlags = (int[]) flags.get(drawsObj);
            int[] dCull = (int[]) qaMdCull.get(drawsObj);
            int[] dMaterial = (int[]) mdMaterial.get(drawsObj);
            ModelMesh[] meshes = (ModelMesh[]) qaMdMeshes.get(drawsObj);
            ModelInstance[] instances = (ModelInstance[]) qaMdInstances.get(drawsObj);
            int[] order = (int[]) mbOrder.get(batchesObj);
            long[] keys = (long[]) mbKeys.get(batchesObj);
            int n = mbCount.getInt(batchesObj);
            StringBuilder sb = new StringBuilder(1024);
            sb.append(String.format(Locale.ROOT,
                    "tag=%s twoBand=%b farN=%d nearN=%d qaOrderMode=%d nearestWins=%b ironSight=%b funcBefore=%d funcAfter=%d funcExit=%d clampEnter=%b clampExit=%b depthRange=[%.6f,%.6f]",
                    tag, usedTwoBand, farN, nearN, qaOrderMode, nearestWins, ironSight(),
                    qaEnterFuncBefore, qaEnterFuncAfter, funcExit, qaEnterClamp, clampExit, dr0, dr1));
            int seqCount = usedTwoBand ? selfOrderedCount : n;
            int selfSeq = 0;
            for (int r = 0; r < seqCount; r++) {
                int rec = usedTwoBand ? selfOrdered[r] : order[r];
                long keyVal = usedTwoBand ? selfOrderKeys[r] : keys[r];
                if (!usedTwoBand && ((dFlags[rec] & (self | colourBit)) != (self | colourBit) || dMaterial[rec] >= 0)) {
                    continue;
                }
                ModelInstance inst = rec < instances.length ? instances[rec] : null;
                ModelMesh mesh = rec < meshes.length ? meshes[rec] : null;
                Texture tex = rec < drawnArr.length ? drawnArr[rec] : null;
                Object h = rec < heldArr.length ? heldArr[rec] : null;
                String modelName = inst != null && inst.model != null ? String.valueOf(inst.model.name) : "null";
                String attach = inst == null ? "null" : (inst.attachmentNameParent + "/" + inst.parentBoneName
                        + (inst.parent != null && inst.parent.model != null ? "<-" + inst.parent.model.name : ""));
                String meshPath = mesh != null ? String.valueOf(mesh.fullPath) : "null";
                String texName = tex != null ? String.valueOf(tex.getName()) : "null";
                int texId = System.identityHashCode(tex);
                int tex14 = texId & 16383;
                int hId = h != null ? qaHeldId.getInt(h) : -1;
                Object lay = h != null ? qaHeldLayout.get(h) : null;
                int layIdx = lay != null ? qaLayoutIndex.getInt(lay) : -1;
                sb.append(String.format(Locale.ROOT,
                        " | #%d[seq=%d,rec=%d,lay=%d,cull=%d,tex14=%d(0x%04x),texId=0x%08x,held=%d,key=0x%016x,model=%s,attach=%s,tex=%s,mesh=%s]",
                        selfSeq++, r, rec, layIdx, dCull[rec], tex14, tex14, texId, hId, keyVal, modelName, attach, texName, meshPath));
            }
            if (selfSeq > 0) {
                qaDumpTag = null;
                qaLastDump = sb.toString();
                System.out.println("[TW-NEAR-DUMP] " + qaLastDump);
            }
        } catch (Throwable t) {
            qaDumpTag = null;
            qaLastDump = "error=" + t;
        }
    }

    // ------------------------------------------------------------------ crosshair

    /** Exit of View.crosshairSnapshot: no crosshair dot while the pose aims down sights or at the hip, only while the next shot is ours (FpAimRing.hides, W10 item 16). */
    public static void crosshair() {
        if (!ready && !detect()) {
            return;
        }
        try {
            IsoPlayer p = IsoPlayer.getInstance();
            if (shown.getBoolean(null) && FpAimRing.hides(p)) {
                shown.setBoolean(null, false);
                hidden++;
            }
        } catch (Throwable t) {
            error("crosshair", t);
        }
    }

    /** QA (W11 oldpoint): Viewpoint's crosshair dot is drawn this frame - its own View.crosshairShown after every advice. */
    public static boolean crosshairShown() {
        try {
            return shown != null && shown.getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------------ QA

    public static String stats() {
        StringBuilder h = new StringBuilder();
        int n = (int) Math.min(historyAt, 12);
        for (int i = historyAt - n; i < historyAt; i++) {
            int k = Math.floorMod(i, HISTORY) * 3;
            h.append(h.length() == 0 ? "" : ";").append(String.format(Locale.ROOT, "%.0f:%.3f,%.3f", history[k], history[k + 1], history[k + 2]));
        }
        return String.format(Locale.ROOT, "status=%s nearStatus=%s enabled=%b clamp=%b nearestWins=%b draws=%d latched=%d records=%d previous=%d cuts=%d misses=%d clamps=%d adsClamps=%d"
                        + " bandPasses=%d nearDraws=%d selfAt=%d/%d/%d/%d rebinds=%d irisSelf=%d/%d hidden=%d errors=%d maxDeg=%.4f meanDeg=%.4f lastDeg=%.4f,%.4f eyeLatch=%b eyeShifts=%d eyeCuts=%d"
                        + " maxShiftCm=%.3f meanShiftCm=%.3f lastShiftCm=%.3f freeLooks=%d blended=%b blendedFrames=%d blendedLerps=%d recent=%s%s%s",
                status, nearStatus, enabled, clamp, nearestWins, draws, latched, records, previousCarried, cuts, misses, clamps, adsClamps,
                bandPasses, nearDraws, selfAtGbuffer, selfAtRead, selfByFallback, selfMissed, selfRebinds, irisSelfAtRead, irisSelfMissed, hidden, errors, maxDeg,
                latched == 0 ? 0 : sumDeg / latched, lastYawDeg, lastPitchDeg, eyeLatch, eyeShifts, eyeCuts, maxShiftCm,
                eyeShifts == 0 ? 0 : sumShiftCm / eyeShifts, lastShiftCm, freeLooks, ViewpointBridge.blended(), blendedFrames, blendedLerps, h,
                lastError.isEmpty() ? "" : " last=" + lastError,
                lastNearError.isEmpty() ? "" : " lastNear=" + lastNearError);
    }

    public static void reset() {
        draws = latched = records = previousCarried = cuts = misses = clamps = adsClamps = bandPasses = nearDraws = selfAtGbuffer = selfAtRead = selfByFallback = selfMissed = selfRebinds = irisSelfAtRead = irisSelfMissed = hidden = eyeShifts = eyeCuts = freeLooks = 0;
        blendedFrames = blendedLerps = 0;
        maxDeg = sumDeg = lastYawDeg = lastPitchDeg = lastShiftCm = maxShiftCm = sumShiftCm = 0;
        historyAt = 0;
    }

    // ------------------------------------------------------------------ advice (inlined: public members only)

    @Patch(className = "viewpoint.input.Look", methodName = "readBeforeDrawing")
    public static class LookRead {
        @Patch.OnExit
        public static void exit() {
            FpViewLatch.lookRead();
        }
    }

    @Patch(className = "viewpoint.render.WorldRenderer", methodName = "begin")
    public static class Begin {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object scene) {
            FpViewLatch.begin(scene);
        }
    }

    @Patch(className = "viewpoint.render.ModelPass", methodName = "gbuffer")
    public static class Gbuffer {
        @Patch.OnEnter
        public static void enter(@Patch.This Object pass, @Patch.Argument(0) Object frame) {
            FpViewLatch.gbufferEnter(pass, frame);
        }

        @Patch.OnExit
        public static void exit(@Patch.This Object pass, @Patch.Argument(0) Object frame) {
            FpViewLatch.gbufferExit(pass, frame);
        }
    }

    @Patch(className = "viewpoint.render.ModelPass", methodName = "glass")
    public static class Glass {
        @Patch.OnEnter
        public static void enter(@Patch.This Object pass, @Patch.Argument(0) Object frame, @Patch.Argument(1) Object shadows) {
            FpViewLatch.glassEnter(pass, frame, shadows);
        }

        @Patch.OnExit
        public static void exit(@Patch.This Object pass, @Patch.Argument(0) Object frame, @Patch.Argument(1) Object shadows) {
            FpViewLatch.glassExit(pass, frame, shadows);
        }
    }

    @Patch(className = "viewpoint.core.View", methodName = "crosshairSnapshot")
    public static class Crosshair {
        @Patch.OnExit
        public static void exit() {
            FpViewLatch.crosshair();
        }
    }
}
