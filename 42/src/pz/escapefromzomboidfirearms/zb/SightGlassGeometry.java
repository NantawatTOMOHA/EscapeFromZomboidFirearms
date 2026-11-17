package pz.escapefromzomboidfirearms.zb;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Pure unmagnified sight geometry. A channel direction has no eye translation/parallax. */
public final class SightGlassGeometry {
    private static final float TANGENT_STEP = .01f;
    private SightGlassGeometry() { }

    public static boolean eligible(String kind, double magnification) {
        return "collimator".equals(kind) || "optic".equals(kind) && Double.isFinite(magnification)
                && magnification > 0 && magnification <= 1.0001;
    }

    public static float[] infinity(Matrix4f matrix, Vector3f direction) {
        Vector4f p = new Vector4f(direction, 0f).mul(matrix);
        if (!p.isFinite() || p.w <= .00001f) return null;
        return new float[] {p.x / p.w, p.y / p.w, p.z / p.w};
    }

    public static ReticleGeometry.Aperture mark(ReticleGeometry.Aperture glass, float[] axis, int[] viewport) {
        if (axis == null) return null;
        return new ReticleGeometry.Aperture((axis[0] + 1f) * viewport[2] / 2f,
                (1f - axis[1]) * viewport[3] / 2f, glass.ux(), glass.uy(), glass.vx(), glass.vy(), glass.round()).unmirrored();
    }

    /** Pixel axes for a unit tangent, from the active VP projection, independent of glass size or eye distance. */
    public static ReticleGeometry.Aperture angular(Matrix4f matrix, Vector3f forward, Vector3f up, int[] viewport) {
        return angular(angularRays(matrix, forward, up), viewport);
    }

    public static float[][] angularRays(Matrix4f matrix, Vector3f forward, Vector3f up) {
        Vector3f f = new Vector3f(forward).normalize();
        Vector3f right = new Vector3f(f).cross(up).normalize();
        Vector3f vertical = new Vector3f(right).cross(f).normalize();
        return new float[][] {infinity(matrix, f), infinity(matrix, new Vector3f(f).fma(TANGENT_STEP, right)),
                infinity(matrix, new Vector3f(f).fma(TANGENT_STEP, vertical))};
    }

    /** Callers can apply the guarded VP Panini.toScreen interface to the three projected rays first. */
    public static ReticleGeometry.Aperture angular(float[][] rays, int[] viewport) {
        float[] center = rays[0], x = rays[1], y = rays[2];
        if (center == null || x == null || y == null) return null;
        return new ReticleGeometry.Aperture((center[0] + 1f) * viewport[2] / 2f,
                (1f - center[1]) * viewport[3] / 2f,
                (x[0] - center[0]) * viewport[2] / (2f * TANGENT_STEP), -(x[1] - center[1]) * viewport[3] / (2f * TANGENT_STEP),
                -(y[0] - center[0]) * viewport[2] / (2f * TANGENT_STEP), (y[1] - center[1]) * viewport[3] / (2f * TANGENT_STEP), false).unmirrored();
    }
}
