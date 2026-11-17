package pz.escapefromzomboidfirearms.zb;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Select indices only. Positions, UVs, normals, bounds and scale are never edited. */
final class LensGlassGeometry {
    private LensGlassGeometry() {
    }

    static int[] filter(ByteBuffer vertices, int stride, int uv, int[] indices, LensGlassData.Row row) {
        return filter(vertices, stride, uv, -1, indices, row, null);
    }

    static int[] filter(ByteBuffer vertices, int stride, int uv, int position, int[] indices,
                        LensGlassData.Row row, LensGlassChannels.Channel channel) {
        int removed = channel == null ? row.removed() : channel.removed();
        boolean sliced = channel != null && channel.axis() >= 0;
        if (stride <= 0 || uv < 0 || uv + 8 > stride || vertices.limit() % stride != 0
                || indices.length != row.triangles() * 3
                || sliced && (channel.axis() > 2 || position < 0 || position + 12 > stride || !Double.isFinite(channel.split()))) {
            throw new IllegalArgumentException("Lens source layout/count changed: " + row.key()
                    + " expectedIndices=" + row.triangles() * 3 + " actualIndices=" + indices.length
                    + " stride=" + stride + " uvOffset=" + uv + " vertexBytes=" + vertices.limit());
        }
        int count = vertices.limit() / stride;
        int[] kept = new int[indices.length];
        int[] tileTriangles = new int[row.columns() * row.columns()];
        int[] first = new int[tileTriangles.length];
        Arrays.fill(first, -1);
        int size = 0;
        for (int i = 0; i < indices.length; i += 3) {
            double u = 0, v = 0;
            for (int k = 0; k < 3; k++) {
                int index = indices[i + k];
                if (index < 0 || index >= count) throw new IllegalArgumentException("Lens index out of range");
                float x = vertices.getFloat(index * stride + uv), y = vertices.getFloat(index * stride + uv + 4);
                if (!Float.isFinite(x) || !Float.isFinite(y)) throw new IllegalArgumentException("Nonfinite lens UV");
                u += x / 3.;
                v += y / 3.;
            }
            int columns = row.columns();
            // Degenerate authored UV triangles can lie on the outer atlas edge.
            // Clamp only float roundoff there, never wrap into a different tile.
            if (u >= -1e-7 && u <= 1.0000001) u = Math.max(0, Math.min(Math.nextDown(1.), u));
            if (v >= -1e-7 && v <= 1.0000001) v = Math.max(0, Math.min(Math.nextDown(1.), v));
            // PZ ImportedStaticMesh already flips Assimp V before VBO upload.
            // Both that packed UV0 and our atlas tile numbering are top-down.
            // An exact internal V edge retains its pre-flip tile ownership:
            // floor on Assimp V becomes ceil-minus-one after PZ's flip.
            int x = (int) Math.floor(u * columns), y = v == 0 ? 0 : (int) Math.ceil(v * columns) - 1;
            if (x < 0 || x >= columns || y < 0 || y >= columns) {
                throw new IllegalArgumentException("Lens UV outside the source atlas: " + row.key() + " " + u + "," + v);
            }
            int tile = y * columns + x;
            tileTriangles[tile]++;
            if (first[tile] < 0) first[tile] = i;
            boolean omit = (row.tiles() & (1 << tile)) != 0 && removed > 0;
            if (omit && sliced) {
                Boolean above = null;
                for (int k = 0; k < 3; k++) {
                    float p = vertices.getFloat(indices[i+k] * stride + position + channel.axis() * 4);
                    if (!Float.isFinite(p) || p == channel.split()) throw new IllegalArgumentException("Invalid lens separation");
                    boolean side = p > channel.split();
                    if (above != null && above != side) throw new IllegalArgumentException("Lens triangle crosses source separation");
                    above = side;
                }
                omit = above == channel.above();
            }
            if (!omit) {
                kept[size++] = indices[i];
                kept[size++] = indices[i + 1];
                kept[size++] = indices[i + 2];
            }
        }
        if (size != (row.triangles() - removed) * 3 || size == 0
                || !Arrays.equals(tileTriangles, row.tileTriangles())) {
            StringBuilder detail = new StringBuilder("Lens surface count changed: ").append(row.key())
                    .append(" keptTriangles expected=").append(row.triangles() - removed).append(" actual=").append(size / 3)
                    .append(" removedTriangles expected=").append(removed).append(" actual=").append((indices.length - size) / 3)
                    .append(" tileTriangles expected=").append(Arrays.toString(row.tileTriangles()))
                    .append(" actual=").append(Arrays.toString(tileTriangles)).append(" firstTiles=[");
            int samples = 0;
            for (int tile = 0; tile < first.length && samples < 3; tile++) {
                if (tile < row.tileTriangles().length && tileTriangles[tile] == row.tileTriangles()[tile]) continue;
                if (samples++ > 0) detail.append("; ");
                detail.append("tile=").append(tile);
                if (first[tile] >= 0) sample(detail, vertices, stride, uv, indices, first[tile]);
                else detail.append(" no triangles");
            }
            if (samples == 0) {
                for (int tile = 0; tile < first.length; tile++) {
                    if (first[tile] < 0) continue;
                    detail.append("tile=").append(tile);
                    sample(detail, vertices, stride, uv, indices, first[tile]);
                    break;
                }
            }
            throw new IllegalArgumentException(detail.append(']').toString());
        }
        return Arrays.copyOf(kept, size);
    }

    private static void sample(StringBuilder out, ByteBuffer vertices, int stride, int uv, int[] indices, int offset) {
        out.append(" triangle=").append(offset / 3).append(" UV=[");
        for (int k = 0; k < 3; k++) {
            if (k > 0) out.append(';');
            int start = indices[offset + k] * stride + uv;
            out.append(vertices.getFloat(start)).append(',').append(vertices.getFloat(start + 4));
        }
        out.append(']');
    }
}
