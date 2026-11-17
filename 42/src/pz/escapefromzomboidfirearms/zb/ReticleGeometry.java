package pz.escapefromzomboidfirearms.zb;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.List;
import static pz.escapefromzomboidfirearms.zb.ReticleProfile.*;

/** Pure reticle mathematics, shared by the GL module and the offline Java probe. */
public final class ReticleGeometry {
    private ReticleGeometry() { }

    /** Pixels relative to the current viewport, origin at its top left. Rectangle rolls with the mark. */
    public record Lens(float x, float y, float halfWidth, float halfHeight, boolean circle) {
        public Lens {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(halfWidth)
                    || !Float.isFinite(halfHeight) || halfWidth <= 0 || halfHeight <= 0
                    || (circle && halfWidth != halfHeight)) throw new IllegalArgumentException("Invalid reticle lens");
        }
        public static Lens circle(float x, float y, float radius) { return new Lens(x, y, radius, radius, true); }
        public static Lens rectangle(float x, float y, float halfWidth, float halfHeight) {
            return new Lens(x, y, halfWidth, halfHeight, false);
        }
    }

    /** Affine aperture in viewport-local, top-left pixels. Axes need not be orthogonal. */
    public record Aperture(float x, float y, float ux, float uy, float vx, float vy, boolean round) {
        public Aperture {
            float determinant = ux * vy - uy * vx;
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(ux) || !Float.isFinite(uy)
                    || !Float.isFinite(vx) || !Float.isFinite(vy) || !Float.isFinite(determinant)
                    || Math.abs(determinant) < 1e-10f) throw new IllegalArgumentException("Invalid reticle aperture");
        }
        public static Aperture fromGL(float[] disk, int[] viewport, boolean round) {
            // GL's second radius points up; our mark's second coordinate points down.
            return new Aperture(disk[0] - viewport[0], viewport[3] - disk[1] + viewport[1],
                    disk[2], -disk[3], -disk[4], disk[5], round);
        }
        public V2 point(float a, float b) { return new V2(x + ux * a + vx * b, y + uy * a + vy * b); }
        public V2 local(float px, float py) {
            float dx = px - x, dy = py - y, d = ux * vy - uy * vx;
            return new V2((dx * vy - dy * vx) / d, (ux * dy - uy * dx) / d);
        }
        public boolean contains(float px, float py) {
            V2 p = local(px, py);
            return round ? p.x() * p.x() + p.y() * p.y() <= 1f : Math.max(Math.abs(p.x()), Math.abs(p.y())) <= 1f;
        }
        public float radius() { return (float) Math.sqrt(Math.abs(ux * vy - uy * vx)); }
        public float roll() { return (float) Math.atan2(uy, ux); }
        public boolean reflected() { return ux * vy - uy * vx < 0f; }
        /** PZ-to-scene changes handedness. A physical mask may reflect; a printed glyph must not. */
        public Aperture unmirrored() {
            return reflected() ? new Aperture(x, y, -ux, -uy, vx, vy, round) : this;
        }
    }

    /** Apply the same ocular affine map as the PIP composite, with no UV or Z reflection. */
    public static float[] vertices(ReticleProfile profile, Layer layer, ReticleMeshData.Mesh mesh,
                                   float fov, Aperture aperture) {
        aperture = aperture.unmirrored();
        float[] vertices = vertices(profile, layer, mesh, layer.collimator() ? 90f : fov, Lens.circle(0, 0, 1), 0);
        for (int i = 0; i < vertices.length; i += 5) {
            V2 p = aperture.point(vertices[i], vertices[i + 1]);
            vertices[i] = p.x(); vertices[i + 1] = p.y();
        }
        return vertices;
    }

    /** The extra RGBA/depth attributes retain authored colours through near-plane clipping. */
    public static float[] coloredVertices(ReticleProfile profile, Layer layer, ReticleMeshData.Mesh mesh,
                                          float fov, Aperture aperture) {
        float[] vertices = unitStream(profile, layer, mesh, fov);
        place(vertices, vertices.length, aperture, vertices);
        return vertices;
    }

    /**
     * Performance pass 23.10.2026: the unit-lens coloured stream that coloredVertices(..., Aperture) maps onto its
     * aperture. It depends only on the profile, the layer, the mesh and the FOV (90 for a collimator layer), not on the
     * aperture, so the renderer keeps it between frames (ReticleRenderer) and maps only the positions (place).
     */
    public static float[] unitStream(ReticleProfile profile, Layer layer, ReticleMeshData.Mesh mesh, float fov) {
        return stream(profile, layer, mesh, layer.collimator() ? 90f : fov, Lens.circle(0, 0, 1), 0, true);
    }

    /** coloredVertices' map of a unit stream's first n floats onto an aperture into out (may be the stream itself). */
    public static void place(float[] unit, int n, Aperture aperture, float[] out) {
        aperture = aperture.unmirrored();
        float x = aperture.x(), y = aperture.y(), ux = aperture.ux(), uy = aperture.uy(), vx = aperture.vx(), vy = aperture.vy();
        for (int i = 0; i < n; i += 9) {
            float a = unit[i], b = unit[i + 1];
            // Aperture.point, term for term (same float operations in the same order)
            out[i] = x + ux * a + vx * b;
            out[i + 1] = y + uy * a + vy * b;
            if (out != unit) System.arraycopy(unit, i + 2, out, i + 2, 7);
        }
    }

    public static float[] coloredVertices(ReticleProfile profile, Layer layer, ReticleMeshData.Mesh mesh,
                                          float fov, Lens lens, float roll) {
        return stream(profile, layer, mesh, fov, lens, roll, true);
    }

    public static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }

    public static float blendWeight(Curve curve, float maxFov, float minFov, float currentFov) {
        if (curve == null || maxFov == minFov) return 0f;
        float t = clamp01((currentFov - maxFov) / (minFov - maxFov));
        return 100f * clamp01(1f - curve.at(t));
    }

    public static float focalPixels(float fov, float halfHeight) {
        if (!Float.isFinite(fov) || fov <= 0f || fov >= 180f)
            throw new IllegalArgumentException("Reticle camera FOV unavailable");
        return halfHeight / (float) Math.tan(Math.toRadians(fov) * .5);
    }

    public static float effectiveFov(ReticleProfile profile, float fov) {
        return "sfp".equals(profile.focalPlane()) ? profile.referenceFov() : fov;
    }

    /** Shader-specific unlit colours. Texture RGB and vertex RGBA retain their own spatial masks. */
    public static V4 color(Layer layer, float illuminationLevel) {
        if (!Float.isFinite(illuminationLevel) || illuminationLevel < 0f)
            throw new IllegalArgumentException("Invalid reticle illumination");
        Material m = layer.material(), lens = layer.lensMaterial();
        if (layer.opticTexture()) return new V4(illuminationLevel, illuminationLevel, illuminationLevel, 1);
        if (layer.crossfair()) return new V4(m.color().x() * illuminationLevel, m.color().y() * illuminationLevel,
                m.color().z() * illuminationLevel, clamp01(m.color().w()));
        if (layer.collimator()) {
            float gain = Math.max(0, layer.hdr()) * illuminationLevel / 1.3f;
            return new V4(m.color().x() * gain, m.color().y() * gain, m.color().z() * gain, 1);
        }
        float lightness = m.lightness();
        if (Float.isNaN(lightness) && lens != null) lightness = lens.lightness();
        float gain = (Float.isNaN(lightness) ? 1f : Math.max(0f, lightness)) * illuminationLevel;
        V4 c = m.color(), e = m.emission();
        return new V4(Math.max(0f, c.x() * gain + e.x() * illuminationLevel),
                      Math.max(0f, c.y() * gain + e.y() * illuminationLevel),
                      Math.max(0f, c.z() * gain + e.z() * illuminationLevel), clamp01(c.w()));
    }

    /** A fresh array: the shared source buffers and their positions are never changed. */
    public static float[] deform(ReticleMeshData.Mesh mesh, float weight) {
        float[] result = new float[mesh.vertices().limit()];
        mesh.vertices().duplicate().get(result);
        if (mesh.blendShapes().isEmpty() || weight == 0f) return result;
        List<ReticleMeshData.Frame> frames = mesh.blendShapes().getFirst().frames();
        if (frames.isEmpty()) return result;
        ReticleMeshData.Frame lower = null, upper = null;
        float previous = 0f;
        for (ReticleMeshData.Frame frame : frames) {
            if (frame.weight() <= previous) throw new IllegalArgumentException("Nonpositive/unordered reticle frame weight");
            previous = frame.weight();
            if (frame.weight() >= weight) { upper = frame; break; }
            lower = frame;
        }
        if (upper == null) {
            addDeltas(result, lower, 1f);
        } else {
            float lowWeight = lower == null ? 0f : lower.weight();
            float t = clamp01((weight - lowWeight) / (upper.weight() - lowWeight));
            if (lower != null) addDeltas(result, lower, 1f - t);
            addDeltas(result, upper, t);
        }
        return result;
    }

    private static void addDeltas(float[] target, ReticleMeshData.Frame frame, float factor) {
        for (int i = 0; i < frame.indices().limit(); i++) {
            int vertex = frame.indices().get(i) * 3;
            for (int axis = 0; axis < 3; axis++) target[vertex + axis] += frame.deltas().get(i * 3 + axis) * factor;
        }
    }

    /** Triangle stream: screen X/Y, inverse depth, source U/depth and V/depth. */
    public static float[] vertices(ReticleProfile profile, Layer layer, ReticleMeshData.Mesh mesh,
                                   float fov, Lens lens, float roll) {
        return stream(profile, layer, mesh, fov, lens, roll, false);
    }

    private static float[] stream(ReticleProfile profile, Layer layer, ReticleMeshData.Mesh mesh,
                                  float fov, Lens lens, float roll, boolean colored) {
        if (!Float.isFinite(roll)) throw new IllegalArgumentException("Invalid reticle roll");
        Out out = new Out(lens, roll, colored);
        if (layer.mesh() == null) {
            if (layer.collimator() && !layer.projection().angularVertices().isEmpty()) {
                float focal = focalPixels(fov, lens.halfHeight);
                List<Float> source = layer.projection().angularVertices();
                // performance pass 23.10.2026: one scratch vertex (5 floats, as the array it replaces) for every index
                float[] vertex = new float[5];
                for (int index : layer.projection().angularTriangles()) {
                    int i = index*5;
                    vertex[0] = source.get(i)*focal; vertex[1] = source.get(i+1)*focal;
                    vertex[2] = source.get(i+2); vertex[3] = source.get(i+3); vertex[4] = source.get(i+4);
                    out.screen(vertex);
                }
                return Arrays.copyOf(out.values, out.size);
            }
            float scale = "ffp".equals(profile.focalPlane())
                    ? focalPixels(fov, 1f) / focalPixels(profile.referenceFov(), 1f) : 1f;
            float w = lens.halfWidth * scale, h = lens.halfHeight * scale;
            float cx = 0f, cy = 0f;
            if (layer.collimator()) {
                w = h = focalPixels(fov, lens.halfHeight) * .5f / layer.projection().uvPerTangent();
                cx = (0.5f - layer.projection().uvAtAxis().x()) * 2f * w;
                cy = (layer.projection().uvAtAxis().y() - .5f) * 2f * h;
            }
            float[][] corners = {{-w, -h, 1, 0, 1}, {w, -h, 1, 1, 1}, {w, h, 1, 1, 0}, {-w, h, 1, 0, 0}};
            for (float[] corner : corners) { corner[0] += cx; corner[1] += cy; }
            for (int index : new int[]{0, 1, 2, 0, 2, 3}) out.screen(corners[index]);
        } else {
            if (mesh == null) throw new IllegalArgumentException("Reticle mesh unavailable");
            float projectionFov = effectiveFov(profile, fov);
            float weight = blendWeight(layer.blendCurve(), profile.referenceFov(), profile.minFov(), projectionFov);
            float[] positions = deform(mesh, weight);
            float focal = focalPixels(projectionFov, lens.halfHeight);
            if (layer.crossfair()) focal *= layer.renderScale();
            float near = Math.max(.0001f, profile.nearClip());
            // Performance pass 23.10.2026: the camera-space rows in one flat array (stride floats each) and the clipped
            // polygon in one scratch; the same float operations in the same order as the row arrays, V3/V4 records
            // and per-triangle lists they replace (the stream is bit for bit the same).
            int stride = colored ? 9 : 5, count = mesh.vertexCount();
            float[] camera = new float[count * stride];
            if (!layer.vertexColors().isEmpty() && layer.vertexColors().size() != count)
                throw new IllegalArgumentException("Mesh vertex colour count differs");
            V3 rx = layer.rotation().x(), ry = layer.rotation().y(), rz = layer.rotation().z(), at = layer.position();
            for (int i = 0; i < count; i++) {
                float before = layer.crossfair() ? 1f : layer.renderScale();
                // Rotation.apply of the scaled position, term for term
                float a = positions[i * 3] * before, b = positions[i * 3 + 1] * before, c = positions[i * 3 + 2] * before;
                float px = rx.x() * a + rx.y() * b + rx.z() * c, py = ry.x() * a + ry.y() * b + ry.z() * c,
                        pz = rz.x() * a + rz.y() * b + rz.z() * c;
                int row = i * stride;
                camera[row] = px + at.x(); camera[row + 1] = py + at.y();
                camera[row + 2] = -(pz + at.z()); camera[row + 3] = mesh.uv0().get(i * 2); camera[row + 4] = mesh.uv0().get(i * 2 + 1);
                if (colored) {
                    if (layer.vertexColors().isEmpty()) {
                        camera[row + 5] = 1f; camera[row + 6] = 1f; camera[row + 7] = 1f; camera[row + 8] = 1f;
                    } else {
                        V4 vc = layer.vertexColors().get(i);
                        camera[row + 5] = vc.x(); camera[row + 6] = vc.y(); camera[row + 7] = vc.z(); camera[row + 8] = vc.w();
                    }
                }
            }
            float[] poly = new float[4 * stride];
            for (IntBuffer sub : mesh.submeshes()) {
                for (int i = 0; i < sub.limit(); i += 3) {
                    int m = clipNear(camera, row(sub.get(i), count, stride), row(sub.get(i + 1), count, stride),
                            row(sub.get(i + 2), count, stride), stride, near, poly);
                    for (int j = 1; j + 1 < m; j++) {
                        out.camera(poly, 0, focal);
                        out.camera(poly, j * stride, focal);
                        out.camera(poly, (j + 1) * stride, focal);
                    }
                }
            }
        }
        return Arrays.copyOf(out.values, out.size);
    }

    /** The first float of vertex index's row; an index outside the mesh fails as the row array's lookup did. */
    private static int row(int index, int count, int stride) {
        if (index < 0 || index >= count) throw new ArrayIndexOutOfBoundsException(index);
        return index * stride;
    }

    /** The triangle (rows a, b, c of rows) clipped at the near plane into poly, stride floats a vertex; its vertex count. */
    private static int clipNear(float[] rows, int a, int b, int c, int stride, float near, float[] poly) {
        int m = 0;
        for (int i = 0; i < 3; i++) {
            int p = i == 0 ? a : i == 1 ? b : c, q = i == 0 ? b : i == 1 ? c : a;
            if (rows[p + 2] >= near) {
                System.arraycopy(rows, p, poly, m * stride, stride);
                m++;
            }
            if ((rows[p + 2] >= near) != (rows[q + 2] >= near)) {
                float t = (near - rows[p + 2]) / (rows[q + 2] - rows[p + 2]);
                int o = m * stride;
                for (int j = 0; j < stride; j++) poly[o + j] = rows[p + j] + (rows[q + j] - rows[p + j]) * t;
                poly[o + 2] = near;
                m++;
            }
        }
        return m;
    }

    private static final class Out {
        float[] values = new float[5 * 256];
        int size;
        final Lens lens;
        final float cos, sin;
        final int stride;
        private final float[] projected;
        Out(Lens lens, float roll, boolean colored) {
            this.lens = lens;
            stride = colored ? 9 : 5;
            cos = (float) Math.cos(roll);
            sin = (float) Math.sin(roll);
            projected = new float[stride];
        }
        /** The camera-space vertex at p[off..off + stride) projected; one scratch of stride floats, as before per vertex. */
        void camera(float[] p, int off, float focal) {
            float w = 1f / p[off + 2];
            projected[0] = p[off] * w * focal; projected[1] = -p[off + 1] * w * focal; projected[2] = w;
            for (int i = 3; i < stride; i++) projected[i] = p[off + i] * w;
            screen(projected);
        }
        void screen(float[] p) {
            if (size + stride > values.length) values = Arrays.copyOf(values, values.length * 2);
            values[size++] = lens.x + cos * p[0] - sin * p[1];
            values[size++] = lens.y + sin * p[0] + cos * p[1];
            for (int i = 2; i < 5; i++) values[size++] = p[i];
            for (int i = 5; i < stride; i++) values[size++] = i < p.length ? p[i] : p[2];
        }
    }
}
