package pz.escapefromzomboidfirearms.zb;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Optical image coordinates are independent of the physical aperture's centre.
 * The ellipse remains the clip mask; the channel ray defines image UV 0.5,0.5. */
public final class ScopePipComposite {
    private ScopePipComposite() { }

    /** Panini-corrected NDC to the main viewport's GL pixel coordinates. */
    public static float[] origin(float[] axis, int[] viewport) {
        if (axis == null || axis.length < 2 || viewport == null || viewport.length < 4
                || viewport[2] <= 0 || viewport[3] <= 0 || !Float.isFinite(axis[0]) || !Float.isFinite(axis[1])) return null;
        return new float[] {viewport[0] + (axis[0]+1)*viewport[2]/2f, viewport[1] + (axis[1]+1)*viewport[3]/2f};
    }

    /** Electronic panels keep a fixed camera image; optical lenses retain ray parallax. */
    public static float[] pictureOrigin(float[] lens, float[] axis, int[] viewport, boolean screen) {
        return screen ? new float[] {lens[0], lens[1]} : origin(axis, viewport);
    }

    /** The ray origin in the unchanged physical ellipse's local coordinates. */
    public static float[] offset(float[] lens, float[] origin) {
        if (lens == null || lens.length < 7 || origin == null || origin.length != 2)
            throw new IllegalArgumentException("optical image origin");
        float determinant = lens[2]*lens[5] - lens[3]*lens[4];
        float x = origin[0]-lens[0], y = origin[1]-lens[1];
        float u = (lens[5]*x - lens[4]*y)/determinant;
        float v = (-lens[3]*x + lens[2]*y)/determinant;
        if (!Float.isFinite(determinant) || Math.abs(determinant) < 1e-6 || !Float.isFinite(u) || !Float.isFinite(v))
            throw new IllegalArgumentException("degenerate optical aperture");
        return new float[] {u, v};
    }

    /** Physical glass axes may be reflected. Camera pixels use camera right/up,
     * independently of the authored boundary's winding and local axes. */
    public static float[] imageBasis(Matrix4f cameraView, Vector3f ocular, Vector3f edgeX, Vector3f edgeY) {
        Vector3f x = new Vector3f(edgeX).sub(ocular).normalize();
        Vector3f y = new Vector3f(edgeY).sub(ocular).normalize();
        cameraView.transformDirection(x);
        cameraView.transformDirection(y);
        float det = x.x * y.y - y.x * x.y;
        if (!x.isFinite() || !y.isFinite() || Math.abs(det) < 1e-5f)
            throw new IllegalArgumentException("camera image basis");
        return new float[] {x.x, y.x, x.y, y.y};
    }

    /** Ordered authored boundary, projected into the same physical aperture basis. */
    public static float[] boundary(float[] lens, float[][] projected, int[] viewport) {
        if (projected == null || projected.length < 3 || projected.length > 64) return null;
        float[] result = new float[projected.length * 2];
        for (int i = 0; i < projected.length; i++) {
            float[] pixel = origin(projected[i], viewport);
            if (pixel == null) return null;
            float[] local = offset(lens, pixel);
            result[i * 2] = local[0]; result[i * 2 + 1] = local[1];
        }
        return result;
    }
}
