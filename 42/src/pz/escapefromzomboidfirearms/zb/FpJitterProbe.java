package pz.escapefromzomboidfirearms.zb;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * QA only (FB4, 19.10.2026, user's item 4: "the sight still trembles while aiming at objects and the
 * weapon teleports on sharp turns"): where the drawn weapon leaves its place in the camera. Off unless
 * TWZB.jitterProbe(true); three rings, each the last N events of one thread:
 *
 *   pose     FpPose.eye, main thread, every animation update of the local layer: the eye frame used
 *            (Viewpoint frame number, look, the character's rendered angle, the eye in model space
 *            before and after SightDrawFrame's carried-adjustment correction)
 *   capture  SightDrawFrame.captured, main thread, every completed local first-person capture: the
 *            weapon root as Viewpoint recorded it, Viewpoint's carried adjustment D of the prop bone,
 *            and the root in the frame's own camera at the look of that moment
 *   draw     FpViewLatch.begin, render thread, every world pass: the latch's decision (latched / cut /
 *            no pose), the turn it carried and the weapon record in the camera of this drawing
 *
 * Positions are camera space (scene units: +x right, +y up, -z ahead); angles degrees.
 */
public final class FpJitterProbe {
    static final int N = 512;
    public static volatile boolean on;

    // pose ring: number, angleDeg, yawDeg, pitchDeg, pre(x,y,z), post(x,y,z)
    static final double[] pose = new double[N * 10];
    static int poseAt;
    // capture ring: number, root(x,y,z) camera, D translation(x,y,z), D yaw, D pitch, look yaw
    static final double[] capture = new double[N * 10];
    static int captureAt;
    // draw ring: number, kind (0 none, 1 latched, 2 cut, 3 stale), dyDeg, dpDeg, weapon camera (x,y,z), drawYawDeg
    static final double[] draw = new double[N * 8];
    static int drawAt;
    // eye ring (FB5 item 4): number, the frame's camera at the capture's look (scene x,y,z), the posed eye (scene x,y,z;
    // NaN without one), the character (x, y tiles, z levels), rendered angle degrees, world frame
    static final double[] eye = new double[N * 12];
    static int eyeAt;
    // reach ring (FB5 item 4): world frame, the weapon (Prop1) in the eye frame before the reach corrections (cm x,y,z) and
    // after them (cm x,y,z), the high-ready pull (cm), the slide back (cm), body turn and pitch (degrees), support hand miss (cm)
    static final double[] reach = new double[N * 12];
    static int reachAt;
    // blend ring (Viewpoint 0.1.7): number, the drawing's t, the weapon in this step's posed camera (x,y,z), the weapon as this
    // drawing shows it in its camera (x,y,z), nanoseconds of the drawing
    static final double[] blend = new double[N * 9];
    static int blendAt;
    /** The weapon root's record of a scene's capture: {index * stride + matrix offset}. */
    private static final Map<Object, Integer> roots = new IdentityHashMap<>();

    private FpJitterProbe() { }

    public static synchronized void reset() {
        poseAt = captureAt = drawAt = eyeAt = reachAt = blendAt = 0;
    }

    /** FB5 item 4: one first-person pose of the local layer - the weapon before and after the reach corrections (eye frame). */
    static synchronized void reach(double[] before, double[] after, double pullCm, double slideCm, double turn, double pitch, double missCm) {
        if (!on) {
            return;
        }
        int k = Math.floorMod(reachAt++, N) * 12;
        reach[k] = FpRecoil.worldFrame();
        for (int i = 0; i < 3; i++) {
            reach[k + 1 + i] = before == null ? Double.NaN : before[3 + 4 * i] / GripIK.CM;
            reach[k + 4 + i] = after == null ? Double.NaN : after[3 + 4 * i] / GripIK.CM;
        }
        reach[k + 7] = pullCm;
        reach[k + 8] = slideCm;
        reach[k + 9] = Math.toDegrees(turn);
        reach[k + 10] = Math.toDegrees(pitch);
        reach[k + 11] = missCm;
    }

    /** FB5 item 4: a first-person capture's camera (at the look of the moment), the posed eye and the character's placement. */
    static synchronized void eye(Object frame, long number, float[] posed, double x, double y, double z, double angle) {
        if (!on) {
            return;
        }
        int k = Math.floorMod(eyeAt++, N) * 12;
        eye[k] = number;
        float[] look = new float[2], e = {Float.NaN, Float.NaN, Float.NaN}, f = new float[3];
        try {
            ViewpointBridge.look(look);
            ViewpointBridge.camera(frame, look[0], look[1], e, f);
        } catch (Throwable t) {
            // diagnostics only
        }
        for (int i = 0; i < 3; i++) {
            eye[k + 1 + i] = e[i];
            eye[k + 4 + i] = posed == null ? Double.NaN : posed[i];
        }
        eye[k + 7] = x;
        eye[k + 8] = y;
        eye[k + 9] = z;
        eye[k + 10] = Math.toDegrees(angle);
        eye[k + 11] = FpRecoil.worldFrame();
    }

    static synchronized void pose(long number, double angle, double yaw, double pitch, double[] pre, double[] post) {
        if (!on) {
            return;
        }
        int k = Math.floorMod(poseAt++, N) * 10;
        pose[k] = number;
        pose[k + 1] = Math.toDegrees(angle);
        pose[k + 2] = Math.toDegrees(yaw);
        pose[k + 3] = Math.toDegrees(pitch);
        for (int i = 0; i < 3; i++) {
            pose[k + 4 + i] = pre == null ? Double.NaN : pre[3 + 4 * i];
            pose[k + 7 + i] = post == null ? Double.NaN : post[3 + 4 * i];
        }
    }

    static synchronized void capture(Object frame, Object scene, long number, int rootAt, Matrix4fc root, Matrix4fc adjustment) {
        if (!on) {
            return;
        }
        if (roots.size() > 8) {
            roots.clear();
        }
        roots.put(scene, rootAt);
        int k = Math.floorMod(captureAt++, N) * 10;
        capture[k] = number;
        float[] look = new float[2];
        Vector3f p = new Vector3f(Float.NaN, Float.NaN, Float.NaN);
        try {
            ViewpointBridge.look(look);
            Matrix4f view = view(frame, look[0], look[1]);
            if (view != null) {
                root.getTranslation(p);
                view.transformPosition(p);
            }
        } catch (Throwable t) {
            // diagnostics only
        }
        capture[k + 1] = p.x;
        capture[k + 2] = p.y;
        capture[k + 3] = p.z;
        capture[k + 4] = adjustment.m30();
        capture[k + 5] = adjustment.m31();
        capture[k + 6] = adjustment.m32();
        capture[k + 7] = Math.toDegrees(Math.atan2(adjustment.m20(), adjustment.m00()));
        capture[k + 8] = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, adjustment.m21()))));
        capture[k + 9] = Math.toDegrees(look[0]);
    }

    /** kind: 0 no pose for the scene, 1 latched, 2 cut over the limit, 3 frame reused or stale. */
    static synchronized void draw(long number, int kind, double dy, double dp, Object frame, Object scene, float drawYaw, float drawPitch,
                                  float[] data) {
        if (!on) {
            return;
        }
        Integer at = roots.get(scene);
        int offset = at == null || data == null || at + 16 > data.length ? -1 : at;
        int k = Math.floorMod(drawAt++, N) * 8;
        draw[k] = number;
        draw[k + 1] = kind;
        draw[k + 2] = Math.toDegrees(dy);
        draw[k + 3] = Math.toDegrees(dp);
        Vector3f p = new Vector3f(Float.NaN, Float.NaN, Float.NaN);
        try {
            // Viewpoint 0.1.7 draws between the game's steps: the drawn view itself, its eye carried from the frame's
            Matrix4f view = frame == null || data == null || offset < 0 ? null
                    : ViewpointBridge.blended() ? ViewpointBridge.view() : view(frame, drawYaw, drawPitch);
            if (view != null) {
                p.set(data[offset + 12], data[offset + 13], data[offset + 14]);
                view.transformPosition(p);
            }
        } catch (Throwable t) {
            // diagnostics only
        }
        draw[k + 4] = p.x;
        draw[k + 5] = p.y;
        draw[k + 6] = p.z;
        draw[k + 7] = Math.toDegrees(drawYaw);
    }

    /** One drawing of a blended frame (FpViewLatch.carryBlended). */
    static synchronized void blend(long number, float t, float px, float py, float pz, float dx, float dy, float dz) {
        if (!on) {
            return;
        }
        int k = Math.floorMod(blendAt++, N) * 9;
        blend[k] = number;
        blend[k + 1] = t;
        blend[k + 2] = px;
        blend[k + 3] = py;
        blend[k + 4] = pz;
        blend[k + 5] = dx;
        blend[k + 6] = dy;
        blend[k + 7] = dz;
        blend[k + 8] = System.nanoTime() / 1e6;
    }

    /** Viewpoint's view matrix of a frame at a look (scene -> camera), or null. */
    static Matrix4f view(Object frame, float yaw, float pitch) throws Throwable {
        float[] e = new float[3], f = new float[3];
        if (!ViewpointBridge.camera(frame, yaw, pitch, e, f)) {
            return null;
        }
        return new Matrix4f().setLookAt(e[0], e[1], e[2], e[0] + f[0], e[1] + f[1], e[2] + f[2], 0, 1, 0);
    }

    /** The last n events of a ring, oldest first, one per line: "<ring> v0 v1 ...". */
    public static synchronized String dump(String ring, int n) {
        double[] a;
        int at, w;
        switch (ring) {
            case "pose" -> { a = pose; at = poseAt; w = 10; }
            case "capture" -> { a = capture; at = captureAt; w = 10; }
            case "eye" -> { a = eye; at = eyeAt; w = 12; }
            case "reach" -> { a = reach; at = reachAt; w = 12; }
            case "blend" -> { a = blend; at = blendAt; w = 9; }
            default -> { a = draw; at = drawAt; w = 8; }
        }
        int m = Math.min(Math.min(n, N), at);
        StringBuilder b = new StringBuilder();
        for (int i = at - m; i < at; i++) {
            int k = Math.floorMod(i, N) * w;
            b.append(ring);
            for (int j = 0; j < w; j++) {
                b.append(' ').append(String.format(Locale.ROOT, "%.5f", a[k + j]));
            }
            b.append('\n');
        }
        return b.toString();
    }
}
