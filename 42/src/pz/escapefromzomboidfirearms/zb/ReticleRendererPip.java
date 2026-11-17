package pz.escapefromzomboidfirearms.zb;

import se.krka.kahlua.vm.KahluaTable;

/** Our overlay stage. Only an immutable profile travels with the queued scope request. */
public final class ReticleRendererPip {
    private static final ReticleProfile.Cache cache = new ReticleProfile.Cache();
    private static boolean installed;
    // Bound and consumed on the render thread, never read from live Lua there.
    private static ReticleProfile drawing;
    private static String drawingKey;
    /** The brightness dial of the same bound request; 1 is the authored Tarkov illumination. */
    private static float drawingBrightness = 1f;
    /** A String, or the last draw's values (Drawn), turned into text only when QA reads info(). */
    private static volatile Object info = "ready=false";
    private static volatile long draws;

    /**
     * Performance pass 23.10.2026: the drawn scope frame's info values; the line (selection key, blend weight, float
     * texts) was built every magnified ADS frame. hud is ReticleRendererHud's info of that same draw.
     */
    private record Drawn(String key, ReticleProfile profile, float fov, ReticleGeometry.Aperture aperture,
                         ReticleGeometry.Aperture mark, Object hud, float brightness) {
        @Override public String toString() {
            return "ready=true key=" + ReticleProfile.selectionKey(key) + " profile=" + profile.modeId()
                    + " fov=" + fov + " blend=" + weight(profile, fov) + " brightness=" + brightness
                    + " radius=" + aperture.radius() + " roll=" + mark.roll() + " reflectedLens=" + aperture.reflected()
                    + " " + hud;
        }
    }

    private ReticleRendererPip() { }

    static ReticleProfile capture(String key, KahluaTable request) {
        if (!installed) {
            ScopePipStages.overlay = ReticleRendererPip::draw;
            installed = true;
        }
        return cache.capture(ReticleProfile.selectionKey(key), table(request, "reticleInventory"),
                table(request, "reticleItem"), table(request, "reticleMode"));
    }

    private static KahluaTable table(KahluaTable t, String key) {
        return t.rawget(key) instanceof KahluaTable row ? row : null;
    }

    static void bind(String key, ReticleProfile profile, float brightness) {
        drawingKey = key;
        drawing = profile;
        drawingBrightness = brightness;
    }

    private static void draw(ScopePipStages.Frame frame) throws Throwable {
        ReticleProfile profile = drawing;
        float brightness = drawingBrightness;
        drawing = null;
        drawingBrightness = 1f;
        if (profile == null || !frame.key.equals(drawingKey)) return;
        ReticleGeometry.Aperture aperture = ReticleGeometry.Aperture.fromGL(frame.lens, frame.viewport, frame.boundary == null);
        ReticleGeometry.Aperture mark = new ReticleGeometry.Aperture(frame.opticalOrigin[0]-frame.viewport[0],
                frame.viewport[3]-frame.opticalOrigin[1]+frame.viewport[1], aperture.ux(), aperture.uy(),
                aperture.vx(), aperture.vy(), aperture.round()).unmirrored();
        float[] boundary = frame.boundary == null ? null : frame.boundary.clone();
        if (boundary != null) for (int i = 1; i < boundary.length; i += 2) boundary[i] = -boundary[i];
        mark = ReticleRendererHud.mark(frame.key, profile, aperture, mark, boundary);
        if (ReticleRenderer.draw(profile, frame.fov, aperture, mark, brightness, false, boundary, sceneDepth(), frame.lens[6],
                new float[] {frame.depthSlope[0], -frame.depthSlope[1]})) {
            IronSightProbe.reticle(frame.key, frame.weapon, mark.x(), mark.y(), frame.viewport[2], frame.viewport[3]);
            draws++;
            info = new Drawn(frame.key, profile, frame.fov, aperture, mark, ReticleRendererHud.state(), brightness);
        } else {
            info = "ready=false key=" + ReticleProfile.selectionKey(frame.key);
        }
    }

    static int sceneDepth() throws Throwable {
        int depth = (int) ViewpointPipBridge.get("Targets.depth", ViewpointPipBridge.get("WorldRenderer.targets", null));
        return VpShaderPackCompat.sceneDepth(depth);
    }

    static float weight(ReticleProfile profile, float fov) {
        for (ReticleProfile.Layer layer : profile.layers()) {
            if (layer.mesh() != null) return ReticleGeometry.blendWeight(layer.blendCurve(), profile.referenceFov(),
                    profile.minFov(), ReticleGeometry.effectiveFov(profile, fov));
        }
        return 0f;
    }

    public static String info() { return info + " draws=" + draws + " captures=" + cache.captures; }
}
