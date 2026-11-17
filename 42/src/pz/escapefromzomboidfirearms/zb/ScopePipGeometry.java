package pz.escapefromzomboidfirearms.zb;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** CPU geometry of a scope camera and its aperture; no Viewpoint or GL calls. */
public final class ScopePipGeometry {
    private ScopePipGeometry() {
    }

    public static boolean eligible(String kind, double magnification, double fov) {
        // Electronic 1x displays still need their authored camera image: the
        // weapon housing behind a screen is not a view through the world.
        boolean channel = "optic".equals(kind) || "thermal".equals(kind) || "nv".equals(kind);
        return channel && Double.isFinite(magnification) && magnification > 0
                && Double.isFinite(fov) && fov > 0 && fov < 179;
    }

    /** WeaponFrame.world: origin and three model axes, with PZ z in levels. */
    static Vector3f world(double[] weapon, float[] p) {
        return new Vector3f((float) (weapon[0] + weapon[3] * p[0] + weapon[6] * p[1] + weapon[9] * p[2]),
                (float) (weapon[1] + weapon[4] * p[0] + weapon[7] * p[1] + weapon[10] * p[2]),
                (float) (weapon[2] + weapon[5] * p[0] + weapon[8] * p[1] + weapon[11] * p[2]));
    }

    static Vector3f direction(double[] weapon, float[] p) {
        return new Vector3f((float) -(weapon[3] * p[0] + weapon[6] * p[1] + weapon[9] * p[2]),
                (float) ((weapon[5] * p[0] + weapon[8] * p[1] + weapon[11] * p[2]) * ViewpointBridge.LEVEL),
                (float) -(weapon[4] * p[0] + weapon[7] * p[1] + weapon[10] * p[2])).normalize();
    }

    static Vector3f scene(Vector3f p, float[] origin, float lift) {
        return new Vector3f(origin[0] - p.x, (float) ((p.z - origin[2]) * ViewpointBridge.LEVEL) + lift, origin[1] - p.y);
    }

    /** Camera forward/up are directions, independent of the ocular plane normal. */
    public static Matrix4f camera(Vector3f eye, Vector3f forward, Vector3f up) {
        if (!eye.isFinite() || !forward.isFinite() || !up.isFinite()
                || forward.lengthSquared() < 1e-10f || up.lengthSquared() < 1e-10f
                || new Vector3f(forward).cross(up).lengthSquared() < 1e-10f) {
            throw new IllegalArgumentException("degenerate scope camera");
        }
        return new Matrix4f().setLookAt(eye, new Vector3f(eye).add(new Vector3f(forward).normalize()), up);
    }

    /** Margin before the sight's front end kept in the picture, scene units (0.5 cm; Viewpoint's 0.05 is 5.8 cm). */
    static final float FRONT_MARGIN = 0.0043f;

    /**
     * FB7 item 5: the scope picture's near plane at the active sight's front end. The camera stands on the eyepiece,
     * so its own tube, the mount ahead of it and a top sight on that mount (RMR on a Geissele ROF-90) were drawn in the
     * picture. Tarkov's optic camera sees the world beyond the objective; the barrel and front sight ahead of it stay
     * (FB4 item 15). Without a front point, or one behind the camera, Viewpoint's near plane is kept.
     */
    public static float frontNear(float near, Vector3f camera, Vector3f front, Vector3f forward) {
        if (front == null || camera == null || forward == null || !front.isFinite() || !camera.isFinite()) {
            return near;
        }
        float depth = new Vector3f(front).sub(camera).dot(new Vector3f(forward).normalize()) - FRONT_MARGIN;
        return Float.isFinite(depth) ? Math.max(near, depth) : near;
    }

    public static Matrix4f projection(float fov, float near, float far) {
        if (!Float.isFinite(fov) || fov <= 0 || fov >= 179 || near <= 0 || far <= near) {
            throw new IllegalArgumentException("invalid scope frustum");
        }
        return new Matrix4f().setPerspective((float) Math.toRadians(fov), 1f, near, far);
    }

    /** NDC (y up), or null when behind the camera. Panini, when used, follows this. */
    public static float[] project(Matrix4f vp, Vector3f p) {
        Vector4f c = new Vector4f(p, 1f).mul(vp);
        if (!c.isFinite() || c.w <= 1e-5f || c.z < -c.w) {
            return null;
        }
        return new float[] {c.x / c.w, c.y / c.w, c.z / c.w};
    }

    /** QA (FB5, A/B in one run): false refuses the eyepiece nearer than the near plane, as before. */
    public static volatile boolean nearLens = true;

    /**
     * FB5 item 5 (20.10.2026, "firing through a thermal its picture vanishes, worst in automatic fire"): a point of the
     * eyepiece in front of the eye, nearer than the near plane too (NDC depth clamped to it). The kick back brought the
     * eyepiece inside the 5.8 cm plane for a frame or two and project() refused the lens: no picture, the cut tube seen
     * through. Null only behind the eye.
     */
    public static float[] projectLens(Matrix4f vp, Vector3f p) {
        if (!nearLens) {
            return project(vp, p);
        }
        Vector4f c = new Vector4f(p, 1f).mul(vp);
        if (!c.isFinite() || c.w <= 1e-5f) {
            return null;
        }
        return new float[] {c.x / c.w, c.y / c.w, Math.max(-1f, Math.min(1f, c.z / c.w))};
    }

    /** Center and two projected radius vectors in pixels; tilted circles remain ellipses. */
    public static float[] disk(float[] center, float[] edgeX, float[] edgeY, int[] viewport) {
        if (center == null || edgeX == null || edgeY == null) {
            return null;
        }
        float x = viewport[0] + (center[0] + 1f) * viewport[2] / 2f;
        float y = viewport[1] + (center[1] + 1f) * viewport[3] / 2f;
        float ux = (edgeX[0] - center[0]) * viewport[2] / 2f;
        float uy = (edgeX[1] - center[1]) * viewport[3] / 2f;
        float vx = (edgeY[0] - center[0]) * viewport[2] / 2f;
        float vy = (edgeY[1] - center[1]) * viewport[3] / 2f;
        float determinant = ux * vy - uy * vx;
        if (!Float.isFinite(determinant) || Math.abs(determinant) < .25f) {
            return null;
        }
        return new float[] {x, y, ux, uy, vx, vy, center[2] * .5f + .5f};
    }

    public static int resolution(int width, int height, float scale) {
        if (!Float.isFinite(scale)) {
            scale = .5f;
        }
        return Math.max(64, Math.min(2048, Math.round(Math.min(width, height) * Math.max(.25f, Math.min(1f, scale)))));
    }

    /**
     * The user's performance question (20.10.2026): the picture fills the eyepiece, its texture's width across the
     * eyepiece's larger diameter on the screen. Sampled bilinearly without mipmaps, LENS_TEXELS texels a pixel keep it
     * sharp; more only costs the second world's pixels (and aliases). In LENS_STEP pixels, at most cap.
     */
    static final float LENS_TEXELS = 1.25f;
    static final int LENS_STEP = 64;

    /** The picture's size for an eyepiece (disk(): its two radius vectors in pixels); cap without one. */
    public static int lensSize(float[] lens, int cap) {
        if (lens == null || lens.length < 6) {
            return cap;
        }
        double diameter = 2 * Math.max(Math.hypot(lens[2], lens[3]), Math.hypot(lens[4], lens[5]));
        if (!(diameter > 0) || !Double.isFinite(diameter)) {
            return cap;
        }
        int wanted = (int) Math.ceil(diameter * LENS_TEXELS / LENS_STEP) * LENS_STEP;
        return Math.max(64, Math.min(cap, wanted));
    }

    /** Window depth is affine in screen pixels on a projected planar screen. */
    public static float[] depthSlope(float[] center, float[] edgeX, float[] edgeY) {
        return new float[] {(edgeX[2] - center[2]) * .5f, (edgeY[2] - center[2]) * .5f};
    }

    static boolean update(long frame, long last, int interval, boolean changed) {
        return changed || last == Long.MIN_VALUE || frame - last >= interval || frame < last;
    }

    /** Only floating-point noise is ignored; motion and a changed scene origin invalidate cadence reuse. */
    public static boolean stationary(Matrix4f view, Vector3f origin, Matrix4f previousView, Vector3f previousOrigin) {
        return view.equals(previousView, 1e-6f) && origin.equals(previousOrigin, 1e-6f);
    }
}
