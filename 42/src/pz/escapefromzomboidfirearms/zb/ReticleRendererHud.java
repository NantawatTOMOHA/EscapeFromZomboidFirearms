package pz.escapefromzomboidfirearms.zb;

import java.util.Locale;
import static pz.escapefromzomboidfirearms.zb.ReticleGeometry.Aperture;
import static pz.escapefromzomboidfirearms.zb.ReticleProfile.V2;

/** Source OpticSight HUD projector. Only the explicitly unresolved runtime bindings use a bbox fit. */
public final class ReticleRendererHud {
    /** A String, or the last thermal mark's values (Fitted), turned into text when QA reads info(). */
    private static volatile Object info = "hudReady=false hudPath=inactive";
    private ReticleRendererHud() { }

    /** Performance pass 23.10.2026: a thermal HUD mark's info values; its String.format ran every thermal scope frame. */
    private record Fitted(boolean fits, ReticleRendererHudData.Row row, float scale) {
        @Override public String toString() {
            return "hudReady=" + fits + " hudPath=" + row.path() + " hudBinding=" + row.binding()
                    + " hudKey=" + row.key() + String.format(Locale.ROOT, " hudScale=%.8f", scale);
        }
    }

    static ReticleRendererHudData.Row row(String key, String mode) {
        String[] parts = key == null ? new String[0] : key.split(":", -1);
        if (parts.length < 6) return null;
        return ReticleRendererHudData.ROWS.get(parts[parts.length - 4] + ":" + mode);
    }

    public static Aperture mark(String key, ReticleProfile profile, Aperture clip, Aperture ordinary, float[] boundary) {
        if (!"thermal".equals(profile.kind())) {
            info = "hudReady=false hudPath=not-thermal";
            return ordinary;
        }
        ReticleRendererHudData.Row row = row(key, profile.modeId());
        if (row == null) {
            info = "hudReady=false hudPath=unknown-source";
            return ordinary;
        }
        Aperture sourceClip = clip.reflected() ? clip
                : new Aperture(clip.x(), clip.y(), -clip.ux(), -clip.uy(), clip.vx(), clip.vy(), clip.round());
        V2 center = sourceClip.point(row.center()[0], row.center()[1]);
        Aperture source = new Aperture(center.x(), center.y(),
                sourceClip.ux()*row.u()[0] + sourceClip.vx()*row.u()[1], sourceClip.uy()*row.u()[0] + sourceClip.vy()*row.u()[1],
                sourceClip.ux()*row.v()[0] + sourceClip.vx()*row.v()[1], sourceClip.uy()*row.v()[0] + sourceClip.vy()*row.v()[1], false).unmirrored();
        float scale = "fallback_fit_bbox".equals(row.path()) ? Math.min(fitScale(row.bbox(), clip, source, boundary),
                fitScale(row.bbox(), sourceClip, source, row.sourceBoundary())) : 1f;
        Aperture result = new Aperture(source.x(), source.y(), source.ux()*scale, source.uy()*scale,
                source.vx()*scale, source.vy()*scale, false);
        boolean fits = fits(row.bbox(), clip, result, boundary) && fits(row.bbox(), sourceClip, result, row.sourceBoundary());
        info = new Fitted(fits, row, scale);
        return result;
    }

    public static boolean fits(float[] bbox, Aperture clip, Aperture mark, float[] boundary) {
        for (float x : new float[] {bbox[0], bbox[2]}) for (float y : new float[] {bbox[1], bbox[3]}) {
            V2 screen = mark.point(x, y), local = clip.local(screen.x(), screen.y());
            if (!contains(local.x(), local.y(), clip.round(), boundary)) return false;
        }
        return true;
    }

    /** Exact half-plane containment; the source inventory verifies convex single-loop screens. */
    static boolean contains(float x, float y, boolean round, float[] boundary) {
        if (boundary == null) return round ? x*x+y*y <= 1.000002f : Math.max(Math.abs(x), Math.abs(y)) <= 1.000001f;
        double orientation = orientation(boundary);
        for (int i = 0; i < boundary.length; i += 2) {
            int j = (i+2) % boundary.length;
            double cross = (boundary[j]-boundary[i])*(y-boundary[i+1]) - (boundary[j+1]-boundary[i+1])*(x-boundary[i]);
            if (cross*orientation < -1e-6) return false;
        }
        return true;
    }

    private static double orientation(float[] boundary) {
        double area = 0;
        for (int i = 0; i < boundary.length; i += 2) {
            int j = (i+2) % boundary.length;
            area += boundary[i]*boundary[j+1] - boundary[j]*boundary[i+1];
        }
        return Math.signum(area);
    }

    /** Largest uniform shrink about the authored centre; no translation, FOV, UV or pixel change. */
    static float fitScale(float[] bbox, Aperture clip, Aperture source, float[] boundary) {
        V2 center = clip.local(source.x(), source.y());
        if (!contains(center.x(), center.y(), clip.round(), boundary)) return 1f; // explicit hudReady=false
        // Account for the float screen-coordinate additions and inverse affine
        // map. This is a numerical error bound, not an authored layout margin.
        double extent = Math.max(Math.abs(source.x()) + Math.abs(source.ux()) + Math.abs(source.vx()),
                Math.abs(source.y()) + Math.abs(source.uy()) + Math.abs(source.vy()));
        double pixels = 8 * Math.ulp((float) extent);
        double det = Math.abs((double) clip.ux()*clip.vy() - (double) clip.uy()*clip.vx());
        double errorX = pixels*(Math.abs(clip.vy()) + Math.abs(clip.vx()))/det + 8*Math.ulp(1f);
        double errorY = pixels*(Math.abs(clip.uy()) + Math.abs(clip.ux()))/det + 8*Math.ulp(1f);
        double scale = 1;
        for (float x : new float[] {bbox[0], bbox[2]}) for (float y : new float[] {bbox[1], bbox[3]}) {
            V2 screen = source.point(x, y), p = clip.local(screen.x(), screen.y());
            double dx = p.x()-center.x(), dy = p.y()-center.y();
            if (boundary != null) {
                double sign = orientation(boundary);
                for (int i = 0; i < boundary.length; i += 2) {
                    int j = (i+2) % boundary.length;
                    double nx = sign*(boundary[j+1]-boundary[i+1]), ny = -sign*(boundary[j]-boundary[i]);
                    double travel = nx*dx + ny*dy;
                    if (travel > 0) scale = Math.min(scale,
                            (nx*(boundary[i]-center.x()) + ny*(boundary[i+1]-center.y())
                                    - Math.abs(nx)*errorX - Math.abs(ny)*errorY) / travel);
                }
            } else if (clip.round()) {
                double a = dx*dx + dy*dy, b = center.x()*dx + center.y()*dy;
                double radius = 1 - Math.hypot(errorX, errorY);
                double c = center.x()*center.x() + center.y()*center.y() - radius*radius;
                if (a > 0) scale = Math.min(scale, (-b + Math.sqrt(b*b-a*c))/a);
            } else {
                if (dx != 0) scale = Math.min(scale, (Math.copySign(1-errorX, dx)-center.x())/dx);
                if (dy != 0) scale = Math.min(scale, (Math.copySign(1-errorY, dy)-center.y())/dy);
            }
        }
        return Math.nextDown((float) Math.max(1e-6, Math.min(1, scale)));
    }

    public static String info() { return String.valueOf(info); }

    /** The current info, still unformatted (ReticleRendererPip keeps it with its own draw's values). */
    static Object state() { return info; }
}
