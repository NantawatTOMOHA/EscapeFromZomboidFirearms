package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.List;
import se.krka.kahlua.vm.KahluaTable;

/** Immutable snapshot of our optics tables. Capture on the Lua thread, draw on the render thread. */
public record ReticleProfile(String modeId, String kind, String focalPlane, float referenceFov,
                             float minFov, float nearClip, List<Layer> layers) {
    private static boolean logged;

    public ReticleProfile {
        layers = List.copyOf(layers);
    }

    /** Lua-thread selection cache. A zoom tick changes FOV, never the authored profile key. */
    public static final class Cache {
        private String key;
        private ReticleProfile profile;
        public long captures;

        public ReticleProfile capture(String selected, KahluaTable inventory, KahluaTable item, KahluaTable mode) {
            if (selected == null || selected.isEmpty()) return null;
            if (!selected.equals(key)) {
                key = selected;
                profile = ReticleProfile.capture(inventory, item, mode);
                captures++;
            }
            return profile;
        }
    }

    public static String selectionKey(String frameKey) {
        int end = frameKey == null ? -1 : frameKey.lastIndexOf(':');
        return end < 0 ? "" : frameKey.substring(0, end);
    }

    public record V2(float x, float y) { }
    public record V3(float x, float y, float z) { }
    public record V4(float x, float y, float z, float w) { }
    public record Rotation(V3 x, V3 y, V3 z) {
        public V3 apply(float a, float b, float c) {
            return new V3(x.x * a + x.y * b + x.z * c,
                          y.x * a + y.y * b + y.z * c, z.x * a + z.y * b + z.z * c);
        }
    }

    /** Source material values; the shader-specific interpretation lives in Layer.projection. */
    public record Material(V4 color, V4 emission, float lightness, V4 scales, V4 shifts, V4 shiftDirection) { }
    public record Projection(String kind, float uvPerTangent, V2 uvAtAxis,
                             List<Float> angularVertices, List<Integer> angularTriangles) {
        public Projection {
            angularVertices = List.copyOf(angularVertices);
            angularTriangles = List.copyOf(angularTriangles);
        }
        public Projection(String kind, float uvPerTangent, V2 uvAtAxis) {
            this(kind, uvPerTangent, uvAtAxis, List.of(), List.of());
        }
    }
    public record Layer(String texture, String channels, String mesh, V2 uvScale, V2 uvOffset,
                        float renderScale, V3 position, Rotation rotation, Curve blendCurve,
                        Material material, Material lensMaterial, int wrapU, int wrapV,
                        Projection projection, List<V4> vertexColors, float hdr) {
        public Layer {
            vertexColors = List.copyOf(vertexColors);
        }
        public Layer(String texture, String channels, String mesh, V2 uvScale, V2 uvOffset,
                     float renderScale, V3 position, Rotation rotation, Curve blendCurve,
                     Material material, Material lensMaterial, int wrapU, int wrapV) {
            this(texture, channels, mesh, uvScale, uvOffset, renderScale, position, rotation,
                    blendCurve, material, lensMaterial, wrapU, wrapV, null, List.of(), 1f);
        }
        public boolean collimator() { return projection != null && "collimatorAngular".equals(projection.kind); }
        public boolean opticTexture() { return projection != null && "scopeTexture".equals(projection.kind); }
        public boolean crossfair() { return projection != null && "cameraMeshPostScale".equals(projection.kind); }
    }

    public record Curve(List<V2> points) {
        public Curve {
            points = List.copyOf(points);
            if (points.isEmpty()) throw new IllegalArgumentException("Empty reticle curve");
            float previous = Float.NEGATIVE_INFINITY;
            for (V2 point : points) {
                if (!Float.isFinite(point.x) || !Float.isFinite(point.y) || point.x <= previous)
                    throw new IllegalArgumentException("Invalid reticle curve");
                previous = point.x;
            }
        }

        public float at(float t) {
            if (t <= points.getFirst().x) return points.getFirst().y;
            for (int i = 1; i < points.size(); i++) {
                V2 a = points.get(i - 1), b = points.get(i);
                if (t <= b.x) return a.y + (b.y - a.y) * (t - a.x) / (b.x - a.x);
            }
            return points.getLast().y;
        }
    }

    /**
     * inventory = require('TW/Data/ViewpointOptics'); item = TWSight.current().profile;
     * mode = TWSight.current().modeProfile. No cache-index/mode-index guesses are made here.
     * Unknown or malformed data returns null, with one diagnostic and no GL calls.
     */
    public static ReticleProfile capture(KahluaTable inventory, KahluaTable item, KahluaTable mode) {
        try {
            if (number(inventory, "schemaVersion", 0f) != 1f || item == null || mode == null)
                throw new IllegalArgumentException("Missing optics schema/item/mode");
            KahluaTable camera = table(mode, "camera"), range = table(camera, "minMaxFov");
            float reference = number(camera, "fovDegrees", 0f), min = reference;
            if (range != null) {
                reference = number(range, "x", 0f);
                min = number(range, "y", 0f);
            }
            KahluaTable materials = table(inventory, "materials"), curves = table(inventory, "curves");
            KahluaTable textures = table(inventory, "textures"), source = table(mode, "reticles");
            KahluaTable lensIds = table(table(mode, "illumination"), "lensMaterials");
            // Multiple lens materials do not establish which one controls this overlay.
            Material lens = lensIds != null && lensIds.len() == 1
                    ? material(table(materials, string(lensIds.rawget(1)))) : null;
            ArrayList<Layer> layers = new ArrayList<>();
            if (source == null) throw new IllegalArgumentException("Missing reticle list");
            for (int i = 1; i <= source.len(); i++) {
                KahluaTable row = table(source, i);
                String type = string(value(row, "kind"));
                boolean mesh = "mesh".equals(type);
                if (!mesh && !"texture".equals(type)) throw new IllegalArgumentException("Unknown reticle type");
                String texture = mesh ? null : string(value(row, "texture"));
                String meshId = mesh ? string(value(row, "mesh")) : null;
                if (mesh ? meshId == null : texture == null || !texture.startsWith("media/textures/TW_Reticles/"))
                    throw new IllegalArgumentException("Missing reticle resource");
                String channels = mesh ? null : string(value(row, "channels"));
                if (!mesh && !"alpha".equals(channels) && !"rgb_and_alpha".equals(channels))
                    throw new IllegalArgumentException("Unknown reticle channels");
                KahluaTable sourceMaterial = table(materials, string(value(row, "material")));
                // Its original shader property explicitly labels _MarkTex as "Mark Texture (A)".
                // Channel variation in the PNG alone is weaker evidence than that declaration.
                if (!mesh && "CW FX/Collimator".equals(value(sourceMaterial, "shader"))) channels = "alpha";
                // A black RGB mark with varying alpha is still black in OpticSight.
                if (!mesh && "CW FX/OpticSight".equals(value(sourceMaterial, "shader"))) channels = "rgb_and_alpha";
                KahluaTable settings = table(table(textures, string(value(row, "textureId"))), "settings");
                Layer layer = new Layer(texture, channels, meshId,
                        mesh ? new V2(1f, 1f) : v2(table(row, "uvScale")),
                        mesh ? new V2(0f, 0f) : v2(table(row, "uvOffset")),
                        number(row, "renderScale", 1f), mesh ? v3(table(row, "position")) : new V3(0, 0, 0),
                        mesh ? rotation(table(row, "rotation")) : null,
                        curve(table(curves, string(value(row, "reticleBlendCurve")))),
                        material(sourceMaterial), lens,
                        wrap(number(settings, "m_WrapU", 1f)), wrap(number(settings, "m_WrapV", 1f)),
                        projection(table(row, "projection")), colors(table(row, "vertexColors")),
                        number(table(sourceMaterial, "shaderParameters"), "_HDR", 1f));
                if (layer.crossfair() && layer.vertexColors().isEmpty())
                    throw new IllegalArgumentException("Source mesh colours unavailable");
                layers.add(layer);
            }
            return new ReticleProfile(string(value(mode, "id")), string(value(mode, "kind")),
                    string(value(table(item, "focalPlane"), "kind")), reference, min,
                    number(camera, "nearClipMetres", 0.0001f), layers);
        } catch (RuntimeException error) {
            if (!logged) {
                logged = true;
                System.out.println("[TWZB] Reticle profile unavailable: " + error.getMessage());
            }
            return null;
        }
    }

    private static int wrap(float n) {
        if (n < 0 || n > 3 || n != (int) n) throw new IllegalArgumentException("Unknown texture wrap");
        return (int) n;
    }

    private static Projection projection(KahluaTable row) {
        if (row == null) return null;
        String kind = string(value(row, "kind"));
        if (!"collimatorAngular".equals(kind) && !"scopeTexture".equals(kind) && !"cameraMeshPostScale".equals(kind))
            throw new IllegalArgumentException("Unresolved reticle projection");
        float slope = number(row, "uvPerTangent", 1f);
        if (slope <= 0f) throw new IllegalArgumentException("Invalid angular reticle slope");
        ArrayList<Float> vertices = new ArrayList<>();
        ArrayList<Integer> triangles = new ArrayList<>();
        KahluaTable stream = table(row, "angularVertices"), indices = table(row, "angularTriangles");
        if (stream != null) {
            if (stream.len() % 5 != 0 || stream.len() > 1000000 || indices == null || indices.len() % 3 != 0)
                throw new IllegalArgumentException("Invalid angular source mesh");
            for (int i = 1; i <= stream.len(); i++) vertices.add(component(stream, i));
            for (int i = 1; i <= indices.len(); i++) {
                float n = component(indices, i);
                if (n < 0 || n != (int) n || n >= stream.len()/5) throw new IllegalArgumentException("Invalid angular triangle");
                triangles.add((int) n);
            }
        }
        return new Projection(kind, slope, table(row, "uvAtAxis") == null ? new V2(.5f, .5f) : v2(table(row, "uvAtAxis")), vertices, triangles);
    }

    private static List<V4> colors(KahluaTable row) {
        if (row == null) return List.of();
        KahluaTable palette = table(row, "palette"), runs = table(row, "runs");
        float expected = number(row, "vertexCount", 0f);
        if (expected < 1 || expected > 1000000 || expected != (int) expected || palette == null || runs == null)
            throw new IllegalArgumentException("Invalid source vertex colours");
        ArrayList<V4> colors = new ArrayList<>((int) expected);
        for (int i = 1; i <= runs.len(); i++) {
            V2 run = v2(table(runs, i));
            if (run.x < 1 || run.x != (int) run.x || run.y < 1 || run.y != (int) run.y || run.y > palette.len()
                    || colors.size() + run.x > expected) throw new IllegalArgumentException("Invalid colour run");
            V4 color = v4(table(palette, (int) run.y));
            if (color == null) throw new IllegalArgumentException("Missing source vertex colour");
            for (int n = 0; n < (int) run.x; n++) colors.add(color);
        }
        if (colors.size() != expected) throw new IllegalArgumentException("Incomplete source vertex colours");
        return colors;
    }

    private static Material material(KahluaTable row) {
        KahluaTable p = table(row, "properties");
        if (p == null) throw new IllegalArgumentException("Missing reticle material");
        V4 color = v4(table(p, "_Color")), emission = v4(table(p, "_EmissionColor"));
        return new Material(color == null ? new V4(1, 1, 1, 1) : color,
                emission == null ? new V4(0, 0, 0, 0) : emission,
                number(p, "_MarkLightness", Float.NaN), v4(table(p, "_Scales")),
                v4(table(p, "_Shifts")), v4(table(p, "_ShiftDirection")));
    }

    private static Curve curve(KahluaTable row) {
        if (row == null) return null;
        if (!"linear".equals(value(row, "interpolation"))) throw new IllegalArgumentException("Unknown curve interpolation");
        KahluaTable source = table(row, "points");
        ArrayList<V2> points = new ArrayList<>();
        for (int i = 1; source != null && i <= source.len(); i++) points.add(v2(table(source, i)));
        return new Curve(points);
    }

    private static Rotation rotation(KahluaTable t) {
        return new Rotation(v3(table(t, 1)), v3(table(t, 2)), v3(table(t, 3)));
    }
    private static V2 v2(KahluaTable t) { return new V2(component(t, 1), component(t, 2)); }
    private static V3 v3(KahluaTable t) { return new V3(component(t, 1), component(t, 2), component(t, 3)); }
    private static V4 v4(KahluaTable t) {
        return t == null ? null : new V4(component(t, 1), component(t, 2), component(t, 3), component(t, 4));
    }
    private static float component(KahluaTable t, int i) {
        Object value = t == null ? null : t.rawget(i);
        if (!(value instanceof Number n) || !Float.isFinite(n.floatValue()))
            throw new IllegalArgumentException("Missing/nonfinite reticle vector");
        return n.floatValue();
    }
    private static float number(KahluaTable t, String key, float fallback) {
        Object value = value(t, key);
        if (value == null) return fallback;
        if (!(value instanceof Number n) || !Float.isFinite(n.floatValue()))
            throw new IllegalArgumentException("Nonfinite reticle number: " + key);
        return n.floatValue();
    }
    private static Object value(KahluaTable t, Object key) {
        return t == null || key == null ? null : key instanceof Integer i ? t.rawget(i.intValue()) : t.rawget(key);
    }
    private static KahluaTable table(KahluaTable t, Object key) {
        return value(t, key) instanceof KahluaTable row ? row : null;
    }
    private static String string(Object value) { return value instanceof String s ? s : null; }
}
