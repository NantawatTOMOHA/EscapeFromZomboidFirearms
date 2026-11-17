package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.model.ModelInstance;
import me.zed_0xff.zombie_buddy.Patch;

/** Opt-in QA of the actual captured rigid meshes and the current VP camera.
 * No pose, camera, model, vertex or render state is changed by this probe. */
public final class IronSightProbe {
    static final String[][] MEMBERS = {
        {"viewpoint.render.ModelDraws", "add", "(Lzombie/core/skinnedmodel/model/ModelMesh;Lzombie/core/skinnedmodel/model/ModelInstance;Lzombie/core/textures/Texture;Lorg/joml/Matrix4f;IIII)I", ""},
        {"viewpoint.render.ModelDraws", "VALUES", "I", "static"},
        {"viewpoint.render.ModelDraws", "values", "[F", ""},
        {"viewpoint.render.ModelDraws", "instances", "[Lzombie/core/skinnedmodel/model/ModelInstance;", ""},
        {"viewpoint.render.ModelDraws", "count", "I", ""},
        {"viewpoint.render.SceneData", "models", "Lviewpoint/render/ModelDraws;", ""},
        {"viewpoint.render.WorldRenderer", "drawWeather", "(Lviewpoint/render/SceneData;FFF)V", "static"},
    };
    private static volatile Request request;
    private static volatile Mark mark;
    private static volatile String info = "ready=false reason=off";
    private static boolean checked, ready;
    private static Field values, instances, count, models;
    private static int stride;
    private static final Map<Object, List<Observation>> frames = new IdentityHashMap<>();
    private static final Map<Object, BoneEye> bones = new IdentityHashMap<>();

    private record Target(String role, String mesh, Vector3f point) { }
    private record Request(IsoPlayer player, Object weapon, ModelInstance root, String key, String selection, List<Target> targets) { }
    private record Observation(Request request, Target target, ModelInstance instance, int index, int offset) { }
    private record Mark(Request request, float x, float y, int width, int height, double moa, long at) { }
    private record BoneEye(Request request, Vector3f rigid, Vector3f point, float baseLift, float eyeLift) { }

    private IronSightProbe() { }

    private static synchronized boolean detect() {
        if (checked) return ready && ViewpointBridge.ready();
        checked = true;
        if (!ViewpointBridge.detect() || !ViewpointPipBridge.detect()) return false;
        try {
            List<java.lang.reflect.Member> found = new ArrayList<>();
            for (String[] member : MEMBERS) found.add(ViewpointBridge.resolve(member, IronSightProbe.class.getClassLoader()));
            if (found.contains(null)) throw new NoSuchFieldException("iron probe ABI");
            stride = ((Field) found.get(1)).getInt(null);
            values = (Field) found.get(2); instances = (Field) found.get(3);
            count = (Field) found.get(4); models = (Field) found.get(5);
            if (stride < 16 || stride > 4096) throw new IllegalStateException("unbounded model record");
            ready = true;
        } catch (Throwable error) {
            ViewpointBridge.fail("iron probe ABI", error);
        }
        return ready;
    }

    /** QA only. Null clears every captured reference; disabled by default. */
    public static synchronized void set(IsoPlayer player, KahluaTable table) {
        request = null; mark = null; frames.clear(); bones.clear(); info = "ready=false reason=off";
        if (player == null || table == null) return;
        try {
            if (!player.isLocalPlayer() || player.getPlayerNum() != 0 || !detect()) return;
            Object raw = table.rawget("targets");
            if (!(raw instanceof KahluaTable points) || points.len() < 2 || points.len() > 8)
                throw new IllegalArgumentException("two to eight probe targets required");
            List<Target> targets = new ArrayList<>();
            for (int i = 1; i <= points.len(); i++) {
                KahluaTable point = (KahluaTable) points.rawget(i);
                String role = (String) point.rawget("role"), mesh = (String) point.rawget("mesh");
                KahluaTable p = (KahluaTable) point.rawget("point");
                Vector3f v = new Vector3f(((Number) p.rawget(1)).floatValue(), ((Number) p.rawget(2)).floatValue(),
                        ((Number) p.rawget(3)).floatValue());
                if (role == null || !role.matches("[A-Za-z]+") || mesh == null || !mesh.startsWith("TW/") || !v.isFinite())
                    throw new IllegalArgumentException("invalid probe point");
                targets.add(new Target(role, mesh, v));
            }
            request = new Request(player, player.getPrimaryHandItem(), player.primaryHandModel,
                    String.valueOf(table.rawget("key")), String.valueOf(table.rawget("selection")), List.copyOf(targets));
            info = "ready=false reason=awaiting-drawn-frame";
        } catch (Throwable error) {
            info = "ready=false reason=request-rejected detail=" + error.getClass().getSimpleName();
        }
    }

    /**
     * Called after ModelDraws.add; all calls from advice are public. Performance pass 23.10.2026: without a QA request
     * (always, outside QA) every model draw returns here without taking the class lock.
     */
    public static void added(Object draws, ModelInstance instance, Matrix4f transform, int index) {
        if (request == null) return;
        observe(draws, instance, transform, index);
    }

    private static synchronized void observe(Object draws, ModelInstance instance, Matrix4f transform, int index) {
        Request r = request;
        if (r == null || !ready || !ViewpointBridge.ready() || instance == null || instance.modelScript == null
                || !LensGlass.inPrimaryTree(instance, r.root)) return;
        try {
            if (r.player.getPrimaryHandItem() != r.weapon) return;
            String mesh = instance.modelScript.getMeshName();
            for (Target target : r.targets) if (target.mesh.equals(mesh)) {
                float[] data = (float[]) values.get(draws);
                float[] matrix = transform.get(new float[16]);
                int offset = matrixOffset(data, stride, index, matrix);
                if (offset < 0) { info = "ready=false reason=matrix-layout-unproven"; return; }
                List<Observation> list = frames.computeIfAbsent(draws, k -> new ArrayList<>());
                // Reused triple-buffer scenes must never retain a prior frame/fixture.
                list.removeIf(o -> o.request != r || o.index == index && o.target.role.equals(target.role));
                if (list.size() >= 64) list.clear();
                list.add(new Observation(r, target, instance, index, offset));
            }
        } catch (Throwable error) {
            info = "ready=false reason=capture-failed detail=" + error.getClass().getSimpleName();
        }
    }

    /** Infer and verify the matrix block against the actual add argument.
     * No foreign record offsets or layout constants are copied into this mod. */
    public static int matrixOffset(float[] data, int stride, int index, float[] matrix) {
        if (data == null || matrix == null || matrix.length != 16 || index < 0 || stride < 16
                || (long) index * stride + stride > data.length) return -1;
        int base = index * stride, found = -1;
        for (int offset = 0; offset <= stride - 16; offset++) {
            boolean same = true;
            for (int i = 0; i < 16 && same; i++) same = Float.floatToIntBits(data[base + offset + i]) == Float.floatToIntBits(matrix[i]);
            if (same) { if (found >= 0) return -1; found = offset; }
        }
        return found;
    }

    /** The record's block at a known offset equals the add argument bit for bit. */
    public static boolean matrixAt(float[] data, int stride, int index, int offset, float[] matrix) {
        if (data == null || matrix == null || matrix.length != 16 || index < 0 || stride < 16 || offset < 0
                || offset > stride - 16 || (long) index * stride + stride > data.length) return false;
        int base = index * stride + offset;
        for (int i = 0; i < 16; i++) if (Float.floatToIntBits(data[base + i]) != Float.floatToIntBits(matrix[i])) return false;
        return true;
    }

    /** Pair the existing bone/world conversion with the completed main-frame
     * scene, without changing the bones or any captured model matrix. */
    public static synchronized void afterWorld() {
        Request r = request;
        if (r == null || !ready || !ViewpointBridge.ready() || r.player.getPrimaryHandItem() != r.weapon) return;
        try {
            Object frame = ViewpointBridge.frameNow();
            if (frame == null || !WeaponFrame.world(r.player)) return;
            double[] weapon = WeaponFrame.F.clone();
            Target eye = r.targets.stream().filter(t -> t.role.equals("eye")).findFirst().orElse(null);
            if (eye == null) return;
            float[] origin = new float[6];
            Object relief = ViewpointBridge.origin(frame, origin);
            Vector3f world = ScopePipGeometry.world(weapon, new float[] {eye.point.x, eye.point.y, eye.point.z});
            float baseLift = ViewpointBridge.lift(relief, r.player.getX(), r.player.getY());
            float eyeLift = ViewpointBridge.lift(relief, world.x, world.y);
            if (bones.size() > 8) bones.clear();
            bones.put(ViewpointBridge.sceneOf(frame), new BoneEye(r,
                    ScopePipGeometry.scene(world, origin, baseLift), ScopePipGeometry.scene(world, origin, eyeLift), baseLift, eyeLift));
        } catch (Throwable ignored) { /* An absent auxiliary sample is explicit below. */ }
    }

    /** Render thread, at the existing guarded main-world boundary. Reads the
     * final matrix block, including any moves after ModelDraws.add. */
    public static synchronized void drawn(Object scene) {
        Request r = request;
        if (r == null || !ready || !ViewpointBridge.ready() || ViewpointBridge.state() != 1) return;
        try {
            if (scene != ViewpointBridge.currentScene() || r.player.getPrimaryHandItem() != r.weapon) return;
            Object draws = models.get(scene);
            List<Observation> observations = frames.get(draws);
            if (observations == null) { info = "ready=false reason=missing-drawn-mesh"; return; }
            float[] data = (float[]) values.get(draws);
            ModelInstance[] live = (ModelInstance[]) instances.get(draws);
            int n = count.getInt(draws);
            Map<String, Vector3f> points = new java.util.LinkedHashMap<>();
            Matrix4f drawnRoot = null;
            for (Target target : r.targets) {
                Observation selected = null;
                for (Observation o : observations) if (o.request == r && o.target == target && o.index < n && live[o.index] == o.instance) {
                    if (selected != null) { info = "ready=false reason=ambiguous-mesh role=" + target.role; return; }
                    selected = o;
                }
                if (selected == null) { info = "ready=false reason=missing-drawn-mesh role=" + target.role; return; }
                int base = selected.index * stride + selected.offset;
                Matrix4f transform = new Matrix4f().set(data, base);
                if (target.role.equals("eye")) drawnRoot = new Matrix4f(transform);
                Vector3f point = transform.transformPosition(new Vector3f(target.point));
                if (!point.isFinite()) throw new IllegalArgumentException("nonfinite drawn point");
                points.put(target.role, point);
            }
            Object context = ViewpointPipBridge.context();
            Matrix4f vp = (Matrix4f) ViewpointPipBridge.get("FrameContext.plainViewProjection", context);
            Matrix4f view = (Matrix4f) ViewpointPipBridge.get("FrameContext.view", context);
            int[] sizes = new int[8]; ViewpointBridge.targets(sizes);
            if (vp == null || view == null || sizes[5] <= 0 || sizes[6] <= 0) return;
            StringBuilder out = new StringBuilder("ready=true key=").append(r.key)
                    .append(" width=").append(sizes[5]).append(" height=").append(sizes[6]);
            float[] actual = vp.get(new float[16]), earlier = ViewpointBridge.viewProjection().get(new float[16]);
            float delta = 0;
            for (int i = 0; i < 16; i++) delta = Math.max(delta, Math.abs(actual[i]-earlier[i]));
            out.append(String.format(Locale.ROOT, " sceneDrawerMatrixDelta=%.8f", delta));
            for (Map.Entry<String, Vector3f> entry : points.entrySet()) {
                if (entry.getKey().equals("eye")) continue;
                float[] p = ScopePipGeometry.project(vp, entry.getValue());
                if (p == null) { info = "ready=false reason=behind-camera role=" + entry.getKey(); return; }
                p = ViewpointPipBridge.screen(p);
                String role = entry.getKey().equals("axis") ? "axisPoint" : entry.getKey();
                out.append(String.format(Locale.ROOT, " %sPx=%.6f,%.6f", role, (p[0] + 1) * sizes[5] / 2,
                        (1 - p[1]) * sizes[6] / 2));
            }
            Vector3f eye = points.get("eye"), axis = points.get("axis");
            if (eye != null && axis != null) {
                Vector3f ray = new Vector3f(axis).sub(eye).normalize();
                Vector3f direction = view.transformDirection(new Vector3f(ray)).normalize();
                double angle = Math.toDegrees(Math.atan2(Math.hypot(direction.x, direction.y), -direction.z)) * 60;
                Vector3f e = view.transformPosition(new Vector3f(eye));
                out.append(String.format(Locale.ROOT, " axisMoa=%.6f eyeView=%.8f,%.8f,%.8f", angle, e.x, e.y, e.z));
                float[] mark = ViewpointPipBridge.screen(SightGlassGeometry.infinity(vp, ray));
                if (mark != null) out.append(String.format(Locale.ROOT, " axisPx=%.6f,%.6f axisOffsetPx=%.6f",
                        (mark[0] + 1) * sizes[5] / 2, (1 - mark[1]) * sizes[6] / 2,
                        Math.hypot(mark[0] * sizes[5] / 2, mark[1] * sizes[6] / 2)));
                BoneEye bone = bones.get(scene);
                if (bone != null && bone.request == r) {
                    Vector3f rigid = view.transformPosition(new Vector3f(bone.rigid));
                    Vector3f point = view.transformPosition(new Vector3f(bone.point));
                    out.append(String.format(Locale.ROOT,
                            " boneRigidEyeView=%.8f,%.8f,%.8f bonePointEyeView=%.8f,%.8f,%.8f liftBase=%.8f liftEye=%.8f capturedBoneGap=%.8f",
                            rigid.x, rigid.y, rigid.z, point.x, point.y, point.z, bone.baseLift, bone.eyeLift, eye.distance(bone.rigid)));
                } else out.append(" boneEye=unavailable");
            }
            Vector3f rear = points.get("rear"), front = points.get("front");
            if (rear != null && front != null) {
                float[] a = ScopePipGeometry.project(vp, rear), b = ScopePipGeometry.project(vp, front);
                a = ViewpointPipBridge.screen(a); b = ViewpointPipBridge.screen(b);
                double dx = (b[0] - a[0]) * sizes[5] / 2, dy = (a[1] - b[1]) * sizes[6] / 2;
                Vector3f e = new Matrix4f(view).invert().transformPosition(new Vector3f());
                Vector3f ra = new Vector3f(rear).sub(e), rb = new Vector3f(front).sub(e);
                double angle = Math.atan2(new Vector3f(ra).cross(rb).length(), ra.dot(rb));
                out.append(String.format(Locale.ROOT, " separationPx=%.6f separationMoa=%.6f dx=%.6f dy=%.6f",
                        Math.hypot(dx, dy), Math.toDegrees(angle) * 60, dx, dy));
            }
            if (points.containsKey("frontLeft") && points.containsKey("frontRight")) {
                float[] a = ViewpointPipBridge.screen(ScopePipGeometry.project(vp, points.get("frontLeft")));
                float[] b = ViewpointPipBridge.screen(ScopePipGeometry.project(vp, points.get("frontRight")));
                if (a != null && b != null) out.append(String.format(Locale.ROOT, " postWidthPx=%.6f",
                        Math.hypot((b[0]-a[0])*sizes[5]/2, (b[1]-a[1])*sizes[6]/2)));
            }
            if (points.containsKey("lens") && points.containsKey("rootLens")) {
                Vector3f a = points.get("lens"), b = points.get("rootLens");
                float[] ap = ViewpointPipBridge.screen(ScopePipGeometry.project(vp, a));
                float[] bp = ViewpointPipBridge.screen(ScopePipGeometry.project(vp, b));
                out.append(String.format(Locale.ROOT, " lensPlacementGap=%.8f lensPlacementGapPx=%.6f", a.distance(b),
                        Math.hypot((ap[0]-bp[0])*sizes[5]/2, (ap[1]-bp[1])*sizes[6]/2)));
            }
            out.append(" ").append(SightDrawFrame.info(r.player, scene, drawnRoot));
            info = out.toString();
        } catch (Throwable error) {
            info = "ready=false reason=projection-failed detail=" + error.getClass().getSimpleName();
        }
    }

    /** Logical aiming origin actually submitted by our reticle renderer.
     * Source glyph geometry is kept intact; its bounding-box centre is not used. */
    public static void reticle(String key, Object weapon, float x, float y, int width, int height) {
        Request r = request;
        if (r == null || r.weapon != weapon || !ReticleProfile.selectionKey(key).equals(r.selection)) return;
        mark = null;
        if (width <= 0 || height <= 0 || !Float.isFinite(x) || !Float.isFinite(y)) return;
        try {
            float sx = 2*x/width-1, sy = 1-2*y/height;
            // Invert only the public, ABI-checked screen projection numerically.
            // This also handles Panini without copying its implementation.
            float ux = sx, uy = sy;
            for (int i = 0; i < 10; i++) {
                float[] p = ViewpointPipBridge.screen(new float[] {ux, uy, 0});
                float ex = p[0]-sx, ey = p[1]-sy;
                if (Math.hypot(ex, ey) < 1e-7) break;
                float h = .0001f;
                float[] a = ViewpointPipBridge.screen(new float[] {ux+h, uy, 0});
                float[] b = ViewpointPipBridge.screen(new float[] {ux, uy+h, 0});
                float ax = (a[0]-p[0])/h, ay = (a[1]-p[1])/h;
                float bx = (b[0]-p[0])/h, by = (b[1]-p[1])/h, d = ax*by-ay*bx;
                if (Math.abs(d) < 1e-6) return;
                ux -= (by*ex-bx*ey)/d; uy -= (ax*ey-ay*ex)/d;
                if (!Float.isFinite(ux) || !Float.isFinite(uy) || Math.abs(ux)>10 || Math.abs(uy)>10) return;
            }
            float[] p = ViewpointPipBridge.screen(new float[] {ux, uy, 0});
            if (Math.hypot(p[0]-sx, p[1]-sy) > 1e-5) return;
            Object context = ViewpointPipBridge.context();
            Matrix4f vp = (Matrix4f) ViewpointPipBridge.get("FrameContext.plainViewProjection", context);
            Matrix4f view = (Matrix4f) ViewpointPipBridge.get("FrameContext.view", context);
            Vector3f ray = new Matrix4f(vp).invert().transformProject(new Vector3f(ux, uy, 0));
            view.transformPosition(ray);
            double angle = Math.toDegrees(Math.atan2(Math.hypot(ray.x, ray.y), -ray.z))*60;
            if (Double.isFinite(angle)) mark = new Mark(r, x, y, width, height, angle, System.nanoTime());
        } catch (Throwable ignored) {
            mark = null; // A missing measurement never changes the renderer.
        }
    }

    public static String info() {
        Mark m = mark;
        return info + (m != null && m.request == request && System.nanoTime()-m.at < 1_000_000_000L ? String.format(Locale.ROOT,
                " reticlePx=%.6f,%.6f reticleOffsetPx=%.6f reticleMoa=%.6f", m.x, m.y,
                Math.hypot(m.x-m.width/2f, m.y-m.height/2f), m.moa) : " reticle=unavailable");
    }

    @Patch(className = "viewpoint.render.ModelDraws", methodName = "add")
    public static class Add {
        @Patch.OnExit
        public static void exit(@Patch.This Object draws, @Patch.Argument(1) ModelInstance instance,
                                @Patch.Argument(3) Matrix4f transform, @Patch.Return int index) {
            IronSightProbe.added(draws, instance, transform, index);
        }
    }

    @Patch(className = "viewpoint.render.WorldRenderer", methodName = "drawWeather", strictMatch = true)
    public static class Draw {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) Object scene) { IronSightProbe.drawn(scene); }
    }

    @Patch(className = "zombie.iso.IsoWorld", methodName = "render")
    public static class BoneSnapshot {
        @Patch.OnExit
        public static void exit() { IronSightProbe.afterWorld(); }
    }
}
