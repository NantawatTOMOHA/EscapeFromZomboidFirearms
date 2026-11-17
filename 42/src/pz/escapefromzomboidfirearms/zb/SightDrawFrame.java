package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.ModelInstance;

/** The local prop's drawn frame and raw bone, paired in one completed VP capture.
 * VP may carry a prop with its rendered body's adjustment after our bone pose.
 * Inverting that measured adjustment keeps the channel eye at the real camera.
 * No source eye, bone, VP matrix or vertex is written here.
 * FB2-M: also captured in VP's third person (the device LCD drawn on the model there); the eye, the
 * drawing's latch and the scope picture use first-person pairs only (Pair.firstPerson). */
public final class SightDrawFrame {
    static final String[][] MEMBERS = {
        {"viewpoint.models.ModelCapture", "capture", "(Lviewpoint/core/Frame;Lzombie/core/skinnedmodel/model/ModelSlotRenderData;Lzombie/characters/IsoGameCharacter;ZZLviewpoint/models/ModelCapture$Pose;FFFF)Z", "static"},
        {"viewpoint.models.LegReach", "carrying", "(Lzombie/core/skinnedmodel/animation/AnimationPlayer;Ljava/lang/String;)Lorg/joml/Matrix4f;", "static"},
        {"viewpoint.render.ModelDraws", "add", "(Lzombie/core/skinnedmodel/model/ModelMesh;Lzombie/core/skinnedmodel/model/ModelInstance;Lzombie/core/textures/Texture;Lorg/joml/Matrix4f;IIII)I", ""},
        {"viewpoint.render.ModelDraws", "VALUES", "I", "static"},
        {"viewpoint.render.ModelDraws", "values", "[F", ""},
        {"viewpoint.render.ModelDraws", "instances", "[Lzombie/core/skinnedmodel/model/ModelInstance;", ""},
        {"viewpoint.render.ModelDraws", "count", "I", ""},
        {"viewpoint.render.SceneData", "models", "Lviewpoint/render/ModelDraws;", ""},
        {"viewpoint.models.ModelCapture", "place", "Lorg/joml/Matrix4f;", "static"},
    };
    private static boolean checked;
    private static volatile boolean ready;
    private static Field values, instances, count, models, placement;
    private static Method carrying;
    private static int stride;
    /** The matrix block once proven unique in a record; a later ambiguous record (a copy of the
     * same matrix beside it, e.g. a still frame) is read at this offset when it matches there. */
    private static int provenOffset = -1;
    private static final Map<Object, Observation> observations = new IdentityHashMap<>();
    private static final Pair[] frames = new Pair[3];
    private static volatile Pair latest;
    private static volatile String status = "awaiting-local-capture";
    /** QA (FB5 item 4, A/B in one run): false restores FB4's eye held where the camera was at the capture. */
    public static volatile boolean carryWalk = true;

    private record Observation(ModelInstance instance, int index, int offset, float[] matrix) { }

    static final class Pair {
        final IsoPlayer player;
        final Object weapon, scene;
        final ModelInstance instance;
        final AnimationPlayer animation;
        final long number, worldFrame;
        final Matrix4f root, inverseCarry;
        final Matrix4f sceneToModel;
        final Vector3f sceneEye;
        final float compositionDelta;
        final boolean firstPerson;
        /** FB4 item 4: the character's placement this capture was drawn with (x, y tiles, z levels, rendered angle). */
        final double x, y, z, angle;
        Pair(IsoPlayer player, Object scene, long number, Matrix4f root, Matrix4f inverseCarry,
             Matrix4f nativePlace, Vector3f sceneEye, float compositionDelta, boolean firstPerson) {
            this.player = player;
            this.firstPerson = firstPerson;
            weapon = player.getPrimaryHandItem();
            instance = player.primaryHandModel;
            animation = instance.animPlayer;
            x = player.getX();
            y = player.getY();
            z = player.getZ();
            angle = animation.getRenderedAngle();
            this.scene = scene; this.number = number;
            worldFrame = FpRecoil.worldFrame();
            this.root = new Matrix4f(root);
            this.inverseCarry = new Matrix4f(inverseCarry);
            sceneToModel = sceneToModel(nativePlace, inverseCarry);
            this.sceneEye = new Vector3f(sceneEye);
            this.compositionDelta = compositionDelta;
        }
    }

    private SightDrawFrame() { }

    private static synchronized boolean detect() {
        if (checked) return ready && ViewpointBridge.ready();
        checked = true;
        if (!ViewpointBridge.detect()) return false;
        try {
            java.lang.reflect.Member[] found = new java.lang.reflect.Member[MEMBERS.length];
            for (int i = 0; i < found.length; i++) {
                found[i] = ViewpointBridge.resolve(MEMBERS[i], SightDrawFrame.class.getClassLoader());
                if (found[i] == null) throw new NoSuchFieldException(MEMBERS[i][0] + "." + MEMBERS[i][1]);
            }
            carrying = (Method) found[1];
            stride = ((Field) found[3]).getInt(null);
            values = (Field) found[4]; instances = (Field) found[5];
            count = (Field) found[6]; models = (Field) found[7];
            placement = (Field) found[8];
            if (stride < 16 || stride > 4096) throw new IllegalStateException("unbounded model record");
            ready = true;
        } catch (Throwable error) {
            ViewpointBridge.fail("sight drawn frame ABI", error);
        }
        return ready;
    }

    /** VP draws the world: first (1) or third person (2). */
    private static boolean drawnView() {
        int view = ViewpointBridge.state();
        return view == 1 || view == 2;
    }

    private static IsoPlayer player() {
        IsoPlayer p = IsoPlayer.getInstance();
        ModelInstance root = p == null ? null : p.primaryHandModel;
        String mesh = root == null || root.modelScript == null ? null : root.modelScript.getMeshName();
        return p != null && p.isLocalPlayer() && p.getPlayerNum() == 0 && root != null && root.modelScript != null
                && mesh != null && mesh.startsWith("TW/") ? p : null;
    }

    /**
     * Advice target: remember only the actual local root and verify its matrix block. Performance pass 23.10.2026:
     * every other model draw (all characters, parts, items) is rejected before the class lock and the mesh-name test -
     * only the local player's primary-hand root can pass player() and the instance test below.
     */
    public static void added(Object draws, ModelInstance instance, Matrix4f transform, int index) {
        IsoPlayer local = IsoPlayer.getInstance();
        if (local == null || instance == null || instance != local.primaryHandModel) return;
        observe(draws, instance, transform, index);
    }

    private static synchronized void observe(Object draws, ModelInstance instance, Matrix4f transform, int index) {
        IsoPlayer p = player();
        if (p == null || instance != p.primaryHandModel || !detect() || !drawnView()) return;
        try {
            float[] data = (float[]) values.get(draws), matrix = transform.get(new float[16]);
            int offset = IronSightProbe.matrixOffset(data, stride, index, matrix);
            if (offset >= 0) provenOffset = offset;
            else if (provenOffset >= 0 && IronSightProbe.matrixAt(data, stride, index, provenOffset, matrix)) offset = provenOffset;
            if (offset < 0) {
                latest = null;
                status = "root-layout-unproven";
                ViewpointBridge.miss("sight drawn root", "model matrix layout unproven");
                return;
            }
            if (observations.size() > 8) observations.clear();
            observations.put(draws, new Observation(instance, index, offset, matrix));
        } catch (Throwable error) {
            latest = null;
            ViewpointBridge.fail("sight drawn root", error);
        }
    }

    /** Advice target: the frame, root, raw bone and carried bone belong to this capture. */
    public static synchronized void captured(Object frame, IsoGameCharacter character, boolean completed) {
        IsoPlayer p = player();
        if (p == null || character != p || !detect() || !drawnView()) return;
        latest = null;
        boolean first = ViewpointBridge.state() == 1;
        try {
            Object scene = ViewpointBridge.sceneOf(frame), draws = models.get(scene);
            // the look this frame's skeleton was posed with, for the drawing's latch (FB2 item 13; first person only)
            FpViewLatch.captured(completed && first ? frame : null, scene, p);
            Observation observed = observations.remove(draws);
            if (!completed || observed == null || observed.instance != p.primaryHandModel) {
                status = "missing-local-root";
                return;
            }
            ModelInstance[] live = (ModelInstance[]) instances.get(draws); float[] data = (float[]) values.get(draws);
            if (observed.index < 0 || observed.index >= count.getInt(draws) || (live[observed.index] != observed.instance && (live[observed.index] != null || !IronSightProbe.matrixAt(data, stride, observed.index, observed.offset, observed.matrix))))
                throw new ViewpointBridge.DataMiss("root record identity changed");
            Matrix4f root = new Matrix4f().set(data, observed.index * stride + observed.offset);
            ModelInstance instance = observed.instance;
            if (instance.animPlayer == null || instance.parentBone < 0 || instance.parentBoneName == null) {
                status = "missing-prop-bone";
                return;
            }
            org.lwjgl.util.vector.Matrix4f bone = new org.lwjgl.util.vector.Matrix4f();
            instance.animPlayer.getBoneModelTransform(instance.parentBone, bone);
            Matrix4f raw = PZMath.convertMatrix(bone, new Matrix4f()).transpose();
            Object result = carrying.invoke(null, instance.animPlayer, instance.parentBoneName);
            if (!(result instanceof Matrix4f adjustment) || !raw.isFinite()) throw new ViewpointBridge.DataMiss("missing carried adjustment");
            // carrying supplies D, not D * raw. The actual prop bone is already
            // in the native attachment transform; including raw here feeds the
            // posed weapon back into its own eye on every following frame.
            Matrix4f nativePlace = new Matrix4f((Matrix4f) placement.get(null));
            Matrix4f predicted = new Matrix4f(nativePlace).mul(adjustment).mul(raw);
            float[] origin = new float[6];
            ViewpointBridge.origin(frame, origin);
            Pair pair = new Pair(p, scene, ViewpointBridge.frameNumber(frame), root, inverseCarry(adjustment), nativePlace,
                    new Vector3f(origin[3], origin[4], origin[5]), matrixDelta(root, predicted), first);
            if (FpJitterProbe.on && first) {
                FpJitterProbe.capture(frame, scene, pair.number, observed.index * stride + observed.offset, root, adjustment);
            }
            int index = SpriteRenderer.instance.getMainStateIndex();
            if (index >= 0 && index < frames.length) frames[index] = pair;
            if (first) {
                latest = pair;
                // FB5 item 4: the eye this skeleton was posed for, in this capture's scene - the drawing's latch carries
                // the posed eye onto its real camera, whatever the eye's prediction missed (a step of the walk, head bob)
                Vector3f posed = poseEye(pair, p);
                FpViewLatch.eye(scene, pair.number, posed);
                if (FpJitterProbe.on) {
                    FpJitterProbe.eye(frame, pair.number, posed == null ? null : new float[] {posed.x, posed.y, posed.z},
                            pair.x, pair.y, pair.z, pair.angle);
                }
            }
            status = "paired";
        } catch (Throwable error) {
            ViewpointBridge.fail("sight drawn pair", error);
        }
    }

    /**
     * FB4 (19.10.2026, user's item 11: "fire and smoke of a shot do not come out of the barrel", strafing at the hip):
     * Viewpoint draws the local prop as nativePlace * D * bone, D its carried adjustment of the rendered body (walking,
     * leg reach); the muzzle effects placed the bone alone, off the drawn barrel by D while moving. D of the latest
     * first-person capture of this character (model space), or null when there is none of the last frames.
     */
    public static Matrix4f carried(IsoGameCharacter c) {
        Pair p = latest;
        if (p == null || c == null || c != p.player || !p.firstPerson || FpRecoil.worldFrame() - p.worldFrame > 2) return null;
        Matrix4f d = new Matrix4f(p.inverseCarry).invert();
        return d.isFinite() ? d : null;
    }

    /** QA: D of the latest capture - its shift (model units) and turn (degrees), or "none". */
    public static String carryInfo() {
        Pair p = latest;
        if (p == null) return "none";
        Matrix4f d = new Matrix4f(p.inverseCarry).invert();
        float turn = (float) Math.toDegrees(d.getRotation(new org.joml.AxisAngle4f()).angle);
        return String.format(Locale.ROOT, "shift=%.4f,%.4f,%.4f turnDeg=%.3f age=%d first=%b", d.m30(), d.m31(), d.m32(), turn,
                FpRecoil.worldFrame() - p.worldFrame, p.firstPerson);
    }

    /** Independent matrix algebra; inputs are never modified or retained. */
    public static Matrix4f inverseCarry(Matrix4fc adjustment) {
        if (adjustment == null || !adjustment.isFinite() || Math.abs(adjustment.determinant()-1) > 1e-4
                || Math.abs(adjustment.m03()) > 1e-6 || Math.abs(adjustment.m13()) > 1e-6
                || Math.abs(adjustment.m23()) > 1e-6 || Math.abs(adjustment.m33()-1) > 1e-6)
            throw new ViewpointBridge.DataMiss("invalid carried adjustment");
        Matrix4f inverse = new Matrix4f(adjustment).invert();
        if (!inverse.isFinite()) throw new ViewpointBridge.DataMiss("nonfinite carried inverse");
        return inverse;
    }

    /** Model <- eye, row-major as FpPose uses it. Source and transform stay immutable. */
    public static double[] transformEye(Matrix4fc inverseCarry, double[] eye) {
        if (eye == null || eye.length != 16) throw new IllegalArgumentException("eye matrix");
        float[] columns = new float[16];
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++) columns[col * 4 + row] = (float) eye[row * 4 + col];
        Matrix4f m = new Matrix4f().set(columns);
        new Matrix4f(inverseCarry).mul(m, m);
        double[] result = new double[16];
        for (int row = 0; row < 4; row++) for (int col = 0; col < 4; col++) result[row * 4 + col] = m.get(col, row);
        return result;
    }

    /** Scene <- model includes native placement as well as the body adjustment.
     * Read it from the same capture instead of reconstructing a world/head pair. */
    public static Matrix4f sceneToModel(Matrix4fc nativePlace, Matrix4fc inverseAdjustment) {
        if (nativePlace == null || inverseAdjustment == null || !nativePlace.isFinite() || !inverseAdjustment.isFinite()
                || Math.abs(nativePlace.determinant()) < 1e-8 || Math.abs(nativePlace.m03()) > 1e-6
                || Math.abs(nativePlace.m13()) > 1e-6 || Math.abs(nativePlace.m23()) > 1e-6 || Math.abs(nativePlace.m33()-1) > 1e-6)
            throw new ViewpointBridge.DataMiss("invalid native model placement");
        Matrix4f map = new Matrix4f(inverseAdjustment).mul(new Matrix4f(nativePlace).invert());
        if (!map.isFinite()) throw new ViewpointBridge.DataMiss("nonfinite eye map");
        return map;
    }

    public static Vector3f modelEye(Matrix4fc map, Vector3fc sceneEye, Vector3fc sceneAhead) {
        Vector3f eye = new Vector3f(sceneEye).add(sceneAhead);
        map.transformPosition(eye);
        if (!eye.isFinite()) throw new ViewpointBridge.DataMiss("nonfinite camera eye");
        return eye;
    }

    /**
     * A model-space point of a capture's placement (x, y tiles, z levels, rendered angle) in the model space of
     * the character placed now (FB4 item 4). Pure; the inverse of FpPose.worldToModel at the capture, then it now.
     */
    public static double[] rebase(double[] q, double x0, double y0, double z0, double a0, double x1, double y1, double z1, double a1) {
        double cs = Math.cos(a0), sn = Math.sin(a0), mx = q[0] * 1.5, mz = q[2] * 1.5;
        double dx = -cs * mx - sn * mz, dy = -sn * mx + cs * mz, dz = q[1] * 1.5 / ViewpointBridge.LEVEL;
        return FpPose.worldToModel(dx + x0 - x1, dy + y0 - y1, dz + z0 - z1, a1);
    }

    /**
     * The only pose-layer integration: actual camera in the captured model frame, carried onto the character's
     * placement now. FB4 item 4 (19.10.2026, "the sight still trembles while aiming at objects"): the eye point
     * was read through the newest capture's native placement, which holds the body's angle and position of the
     * update before; PZ turns an aiming body in steps and may swing it between two angles a frame apart (QA
     * fb4kjitter: 135.31/136.56 degrees under a still look), so every update whose angle differed from that
     * capture's placed the weapon about an eye turned off the camera - the sight jumped ~1 degree each frame.
     * FB5 item 4 (20.10.2026, "the weapon shakes and jerks in the hands while walking"): that eye stayed where the
     * camera was at the capture, but the camera walks with the character - the weapon was posed a frame's walk
     * (2-8 cm at 1.5 m/s, with the frame time) behind it and jumped with every uneven frame. The eye now keeps its
     * offset from the character's position in the world: carried with the walk, not turned with the body's steps.
     */
    static double[] eye(IsoGameCharacter player, AnimationPlayer animation, double[] eye) {
        return eye(player, animation, eye, ViewpointBridge.lastEye);
    }

    /**
     * The same with the camera's offset ahead of the frame's eye taken from camera (FpPose: the eye it posed for - bug
     * wave 324, in Viewpoint's free look the camera's place along the weapon's yaw, not the view's).
     */
    static double[] eye(IsoGameCharacter player, AnimationPlayer animation, double[] eye, ViewpointBridge.Eye camera) {
        Pair pair = latest;
        if (eye == null || pair == null || !ready || !ViewpointBridge.ready() || ViewpointBridge.state() != 1
                || pair.player != player || pair.animation != animation || pair.weapon != player.getPrimaryHandItem()
                || pair.instance != player.primaryHandModel || !FpPose.freshFrame(pair.worldFrame, FpRecoil.worldFrame())) return eye;
        try {
            double[] result = transformEye(pair.inverseCarry, eye);
            if (camera != null) {
                Vector3f ahead = new Vector3f((float) -camera.aheadX, (float) (camera.aheadZ * ViewpointBridge.LEVEL), (float) -camera.aheadY);
                Vector3f point = modelEye(pair.sceneToModel, pair.sceneEye, ahead);
                double x = player.getX(), y = player.getY(), z = player.getZ();
                double[] now = carryWalk ? rebase(new double[] {point.x, point.y, point.z}, x, y, z, pair.angle, x, y, z, animation.getRenderedAngle())
                        : rebase(new double[] {point.x, point.y, point.z}, pair.x, pair.y, pair.z, pair.angle, x, y, z, animation.getRenderedAngle());
                result[3] = now[0]; result[7] = now[1]; result[11] = now[2];
            }
            return result;
        } catch (Throwable error) {
            ViewpointBridge.fail("sight eye conversion", error);
            return eye;
        }
    }

    /**
     * FB5 item 4: the pose layer's eye of the local first-person skeleton (FpPose.Request.eyeModel, model space before
     * Viewpoint's carried adjustment) in a capture's scene space, or null without a first-person pose of this weapon.
     */
    static Vector3f poseEye(Pair pair, IsoPlayer p) {
        FpPose.Request r = FpPose.requests.get(p);
        double[] e = r == null || r.item != pair.weapon || !r.eyeValid ? null : r.eyeModel;
        if (e == null || e.length != 16 || !FpViewLatch.layer()) return null;
        Vector3f v = new Matrix4f(pair.sceneToModel).invert().transformPosition(new Vector3f((float) e[3], (float) e[7], (float) e[11]));
        return v.isFinite() ? v : null;
    }

    static Pair frame(int index, Object scene, long number) {
        Pair pair = index >= 0 && index < frames.length ? frames[index] : null;
        return pair != null && pair.scene == scene && pair.number == number ? pair : null;
    }

    private static float matrixDelta(Matrix4f a, Matrix4f b) {
        float[] av = a.get(new float[16]), bv = b.get(new float[16]);
        float delta = 0;
        for (int i = 0; i < 16; i++) delta = Math.max(delta, Math.abs(av[i]-bv[i]));
        return delta;
    }

    static synchronized String info(IsoGameCharacter player, Object scene, Matrix4f drawnRoot) {
        Pair pair = null;
        for (Pair candidate : frames) if (candidate != null && candidate.player == player && candidate.scene == scene
                && (pair == null || candidate.number > pair.number)) pair = candidate;
        if (pair == null) return "drawPair=unavailable newestPair=" + status;
        float delta = drawnRoot == null ? Float.NaN : matrixDelta(pair.root, drawnRoot);
        return String.format(Locale.ROOT,
                "drawPair=paired eyeMap=native-placement drawNumber=%d inverseCarry=%.8f,%.8f,%.8f capturedRootMatrixDelta=%.8f carryCompositionDelta=%.8f", pair.number,
                pair.inverseCarry.m30(), pair.inverseCarry.m31(), pair.inverseCarry.m32(), delta, pair.compositionDelta);
    }

    @Patch(className = "viewpoint.render.ModelDraws", methodName = "add")
    public static class Add {
        @Patch.OnExit
        public static void exit(@Patch.This Object draws, @Patch.Argument(1) ModelInstance instance,
                                @Patch.Argument(3) Matrix4f transform, @Patch.Return int index) {
            SightDrawFrame.added(draws, instance, transform, index);
        }
    }

    @Patch(className = "viewpoint.models.ModelCapture", methodName = "capture", strictMatch = true)
    public static class Capture {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) Object frame, @Patch.Argument(2) IsoGameCharacter character,
                                @Patch.Return boolean completed) {
            SightDrawFrame.captured(frame, character, completed);
        }
    }
}
