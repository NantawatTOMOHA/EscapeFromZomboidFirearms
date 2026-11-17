package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * The isometric light cone of TW/LightRender (M.drawBeam) in one call (W13): per white light and frame 70 projections
 * and 24 render calls from Lua, each corner's cookie profile and Unity attenuation through Kahlua's own math.max/min.
 * The same loop: the same arithmetic in doubles in the same order (Kahlua's math.max/min are its stdlib's Lua
 * comparisons), the same projection (LuaManager's isoToScreenX/Y with the arguments Lua passes) and the same
 * SpriteRenderer.render overload (texture, 8 doubles, 16 floats, modifier) in the same order with the values Kahlua
 * would convert. TW/LightRender keeps the Lua loop for M.batch false (QA A/B) and without the Java part.
 */
@Exposer.LuaClass(name = "TWIsoLights")
public final class IsoLights {
    private IsoLights() { }

    /** Screen projection (LuaManager.GlobalObject.isoToScreenX/Y); the offline parity harness puts its own. */
    interface Projector {
        float x(int player, float x, float y, float z);
        float y(int player, float x, float y, float z);
    }

    static final Projector GAME = new Projector() {
        public float x(int player, float x, float y, float z) { return LuaManager.GlobalObject.isoToScreenX(player, x, y, z); }
        public float y(int player, float x, float y, float z) { return LuaManager.GlobalObject.isoToScreenY(player, x, y, z); }
    };

    static Projector projector = GAME;

    /** QA parity: the render arguments (8 corner doubles, then the 16 colour values before the float conversion) instead of drawing. */
    static java.util.ArrayList<double[]> capture;

    public static void capture(boolean on) { capture = on ? new java.util.ArrayList<>() : null; }

    public static KahluaTable captured() {
        KahluaTable out = LuaManager.platform.newTable();
        if (capture == null) return out;
        for (int i = 0; i < capture.size(); i++) {
            KahluaTable row = LuaManager.platform.newTable();
            double[] v = capture.get(i);
            for (int k = 0; k < v.length; k++) row.rawset(k + 1, (Object) Double.valueOf(v[k]));
            out.rawset(i + 1, row);
        }
        return out;
    }

    static double max(double a, double b) { return a < b ? b : a; } // Kahlua stdlib: (max < v) and v or max
    static double min(double a, double b) { return a > b ? b : a; } // Kahlua stdlib: (min > v) and v or min

    /** A number Lua would compute with; anything else fails where the Lua loop's arithmetic fails (a render error, as before). */
    static double number(Object o) {
        if (o instanceof Double d) return d;
        throw new IllegalArgumentException("attempt to perform arithmetic on a " + (o == null ? "nil" : o.getClass().getSimpleName()) + " value");
    }

    /** M.attenuation: Unity's built-in spot light attenuation, 1/(1+25(d/range)^2); range as Lua passes it (nil -> 10). */
    static double attenuation(double d, double range) {
        double t = d / max(.1, range);
        return 1 / (1 + 25 * t * t);
    }

    /** M.profileAt: the cookie intensity at r (0 centre .. 1 edge) from the extractor's profile, or the linear fallback. */
    static double profileAt(KahluaTable profile, double r) {
        int n = profile == null ? 0 : profile.len();
        if (profile == null || n < 2) return max(0, 1 - Math.abs(r));
        double x = max(0, min(1, Math.abs(r))) * (n - 1);
        double i = Math.floor(x);
        double a = number(profile.rawget((int) (i + 1)));
        double b = number(profile.rawget((int) min(n, i + 2)));
        return a + (b - a) * (x - i);
    }

    /**
     * rays: the rays across the beam as M.traceLight keeps them {r, dx, dy, dz, length}; (x, y, z) the lens; slices
     * M.slices; alpha the beam's; profile light.fx.profile (nil allowed); range light.range (or 10, as M.attenuation
     * takes a nil range); (r, g, b) light.color. The number of quads drawn.
     */
    public static double cone(double n, KahluaTable rays, double x, double y, double z, double zoom, double left, double top,
                              double slices, double alpha, KahluaTable profile, double range, double r, double g, double b) {
        int count = rays == null ? 0 : rays.len();
        if (count < 2) return 0;
        int player = (int) n;
        double K = slices;
        int steps = 0;
        while (steps <= K) steps++; // Lua's for k=0,K: K + 1 points for an integer K
        double[][] sx = new double[count][steps], sy = new double[count][steps];
        double[] len = new double[count], across = new double[count];
        for (int i = 0; i < count; i++) {
            KahluaTable ray = (KahluaTable) rays.rawget(i + 1);
            double length = number(ray.rawget("length"));
            double dx = number(ray.rawget("dx")), dy = number(ray.rawget("dy")), dz = number(ray.rawget("dz"));
            len[i] = length;
            across[i] = number(ray.rawget("r"));
            for (int k = 0; k < steps; k++) {
                double t = length * k / K;
                float px = (float) (x + dx * t), py = (float) (y + dy * t), pz = (float) (z + dz * t);
                sx[i][k] = (projector.x(player, px, py, pz) - left) * zoom;
                sy[i][k] = (projector.y(player, px, py, pz) - top) * zoom;
            }
        }
        SpriteRenderer renderer = capture == null ? SpriteRenderer.instance : null;
        float cr = (float) r, cg = (float) g, cb = (float) b;
        int drawn = 0;
        for (int i = 0; i < count - 1; i++) {
            double pa = profileAt(profile, across[i]), pb = profileAt(profile, across[i + 1]);
            double[] ax = sx[i], ay = sy[i], bx = sx[i + 1], by = sy[i + 1];
            double la = len[i], lb = len[i + 1];
            for (int k = 0; k <= K - 1; k++) {
                double ta0 = la * k / K, ta1 = la * (k + 1) / K;
                double tb0 = lb * k / K, tb1 = lb * (k + 1) / K;
                double a1 = alpha * pa * attenuation(ta0, range), a2 = alpha * pb * attenuation(tb0, range);
                double a3 = alpha * pb * attenuation(tb1, range), a4 = alpha * pa * attenuation(ta1, range);
                if (k == K - 1) { a3 = 0; a4 = 0; }
                if (capture != null) {
                    capture.add(new double[] {ax[k], ay[k], bx[k], by[k], bx[k + 1], by[k + 1], ax[k + 1], ay[k + 1],
                            r, g, b, a1, r, g, b, a2, r, g, b, a3, r, g, b, a4});
                } else {
                    renderer.render((Texture) null, ax[k], ay[k], bx[k], by[k], bx[k + 1], by[k + 1], ax[k + 1], ay[k + 1],
                            cr, cg, cb, (float) a1, cr, cg, cb, (float) a2, cr, cg, cb, (float) a3, cr, cg, cb, (float) a4,
                            (java.util.function.Consumer<TextureDraw>) null);
                }
                drawn++;
            }
        }
        return drawn;
    }
}
