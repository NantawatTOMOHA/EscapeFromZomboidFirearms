package pz.escapefromzomboidfirearms.zb;

import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.core.SpriteRenderer;

/**
 * The isometric laser line of TW/LaserRender (M.draw) in one call (performance pass 23.10.2026): per segment two
 * projections and two renderlinef from Lua cost ~320 reflective calls a frame with a laser on. The same loop: the
 * same alpha arithmetic in doubles, the same projection (LuaManager's isoToScreenX/Y with the arguments Lua passes)
 * and the same renderlinef calls in the same order with the values Kahlua would convert to float.
 */
public final class IsoBeams {
    private IsoBeams() { }

    /** QA parity: the renderlinef arguments (before the float conversion) instead of drawing. */
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

    static double min(double a, double b) { return b < a ? b : a; } // Kahlua stdlib math.min/max
    static double max(double a, double b) { return a < b ? b : a; }

    static double number(Object o) { return o instanceof Double d ? d : Double.NaN; }

    /** points: the sample tables {x,y,z,t,visible}; result: the trace (ray.begin, ray.range, reason, transmissions). */
    public static int laser(double n, KahluaTable points, KahluaTable result, double zoom, double left, double top, double alpha,
                            double maxDistance, double fog, double r, double g, double b, double cr, double cg, double cb) {
        if (points == null || result == null) return 0;
        KahluaTable ray = result.rawget("ray") instanceof KahluaTable t ? t : null;
        if (ray == null) return 0;
        double begin = number(ray.rawget("begin")), range = number(ray.rawget("range"));
        boolean fade = "range".equals(result.rawget("reason"));
        KahluaTable transmissions = result.rawget("transmissions") instanceof KahluaTable t ? t : null;
        int count = transmissions == null ? 0 : transmissions.len();
        double[] hd = new double[count], hp = new double[count];
        for (int i = 0; i < count; i++) {
            KahluaTable h = (KahluaTable) transmissions.rawget(i + 1);
            hd[i] = number(h.rawget("distance")); hp[i] = number(h.rawget("power"));
        }
        int player = (int) n;
        SpriteRenderer renderer = SpriteRenderer.instance;
        KahluaTable last = null;
        boolean lastVisible = false, haveLast = false;
        double lx = 0, ly = 0;
        int drawn = 0;
        for (int i = 1, end = points.len(); i <= end; i++) {
            KahluaTable p = (KahluaTable) points.rawget(i);
            boolean visible = Boolean.TRUE.equals(p.rawget("visible"));
            boolean haveP = false;
            double px = 0, py = 0;
            if (visible && last != null && lastVisible) {
                double t = number(p.rawget("t"));
                // Distance from the emitter (the ray starts behind it by ray.begin).
                double d = t - begin;
                double a = alpha * max(0, 1 - d / maxDistance) * Math.exp(-d * fog * .055), power = 1;
                if (fade) a = a * min(1, max(0, (range - t) / 4));
                for (int k = 0; k < count; k++) if (hd[k] <= t) power = hp[k];
                a = min(1, a * power);
                if (!haveLast) {
                    float x = (float) number(last.rawget("x")), y = (float) number(last.rawget("y")), z = (float) number(last.rawget("z"));
                    lx = (LuaManager.GlobalObject.isoToScreenX(player, x, y, z) - left) * zoom;
                    ly = (LuaManager.GlobalObject.isoToScreenY(player, x, y, z) - top) * zoom;
                }
                float x = (float) number(p.rawget("x")), y = (float) number(p.rawget("y")), z = (float) number(p.rawget("z"));
                px = (LuaManager.GlobalObject.isoToScreenX(player, x, y, z) - left) * zoom;
                py = (LuaManager.GlobalObject.isoToScreenY(player, x, y, z) - top) * zoom;
                haveP = true;
                if (capture != null) {
                    capture.add(new double[] {lx, ly, px, py, r, g, b, a * .35, 2.6 * zoom, 2.6 * zoom});
                    capture.add(new double[] {lx, ly, px, py, cr, cg, cb, a, .9 * zoom, .9 * zoom});
                } else {
                    renderer.renderlinef(null, (float) lx, (float) ly, (float) px, (float) py, (float) r, (float) g, (float) b, (float) (a * .35),
                            (float) (2.6 * zoom), (float) (2.6 * zoom));
                    renderer.renderlinef(null, (float) lx, (float) ly, (float) px, (float) py, (float) cr, (float) cg, (float) cb, (float) a,
                            (float) (.9 * zoom), (float) (.9 * zoom));
                }
                drawn++;
            }
            last = p;
            lastVisible = visible;
            haveLast = haveP;
            lx = px;
            ly = py;
        }
        return drawn;
    }
}
