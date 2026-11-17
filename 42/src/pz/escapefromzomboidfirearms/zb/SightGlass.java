package pz.escapefromzomboidfirearms.zb;

import org.joml.Matrix4f;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;

/** Lens-only mark in the completed main Viewpoint image, without a second world render. */
public final class SightGlass {
    private record Request(ScopePip.Request geometry, float[] boundary) { }
    private static volatile Request request;
    private static volatile ScopePip.Request drawn;
    /** A String, or the last draw's values (Drawn), turned into text only when QA reads info(). */
    private static volatile Object info = "ready=false reason=idle";

    /**
     * Performance pass 23.10.2026: the values of the drawn frame's info line; the line (its selection key, blend
     * weight and float texts) was built every 1x ADS frame. toString gives the very text that was built then.
     */
    private record Drawn(boolean ready, ScopePip.Request r, ReticleGeometry.Aperture aperture, ReticleGeometry.Aperture mark,
                         int width, int height, float brightness) {
        @Override public String toString() {
            return "ready=" + ready + " key=" + ReticleProfile.selectionKey(r.key) + " profile=" + r.reticle.modeId()
                    + " fov=" + r.fov + " blend=" + ReticleRendererPip.weight(r.reticle, r.fov)
                    + " brightness=" + brightness
                    + " radius=" + aperture.radius() + " roll=" + aperture.roll()
                    + " mark=" + mark.x() + "," + mark.y() + " viewport=" + width + "x" + height;
        }
    }
    private static volatile boolean failed;
    private static volatile long draws, skipped;
    private static long lastFrame = Long.MIN_VALUE;

    private SightGlass() { }

    public static void set(IsoPlayer player, KahluaTable table) {
        if (failed || player == null || table == null || !player.isLocalPlayer() || player.getPlayerNum() != 0
                || player.isDead() || !HipFireInput.aimIntent(player)) {
            clear("inactive");
            return;
        }
        try {
            Object value = table.rawget("magnification");
            double mag = value instanceof Number n ? n.doubleValue() : Double.NaN;
            if (!SightGlassGeometry.eligible(String.valueOf(table.rawget("kind")), mag)) {
                clear("not an unmagnified optical channel");
                return;
            }
            Object fov = table.rawget("fov");
            ScopePip.Request next = new ScopePip.Request(player, table, fov instanceof Number n ? n.floatValue() : 0f);
            if (next.reticle == null || next.reticle.layers().isEmpty()) {
                clear("source reticle unavailable");
                return;
            }
            float[] boundary = null;
            if (table.rawget("glassBoundary") instanceof KahluaTable polygon) {
                if (polygon.len() < 6 || polygon.len() > 128 || polygon.len() % 2 != 0)
                    throw new IllegalArgumentException("Invalid glass boundary");
                boundary = new float[polygon.len()];
                for (int i = 0; i < boundary.length; i++) {
                    Object component = polygon.rawget(i+1);
                    if (!(component instanceof Number n) || !Float.isFinite(n.floatValue()))
                        throw new IllegalArgumentException("Invalid glass point");
                    boundary[i] = n.floatValue();
                }
            }
            request = new Request(next, boundary);
        } catch (IllegalArgumentException unavailable) {
            clear("source aperture unavailable");
        } catch (Throwable t) {
            fail("request", t);
        }
    }

    private static void clear(String reason) {
        request = null;
        drawn = null;
        info = "ready=false reason=" + reason;
    }

    /** Called at the same main-thread frame boundary as ScopePip, before its magnified-only returns. */
    public static void afterWorld() {
        try {
            Request selected = request;
            if (failed || selected == null || ViewpointBridge.state() != 1 || !ViewpointPipBridge.detect()) return;
            ScopePip.Request current = selected.geometry;
            if (!HipFireInput.aimIntent(current.player) || current.player.isDead() || current.player.getPrimaryHandItem() != current.weapon) {
                clear("aim or weapon changed");
                return;
            }
            Object frame = ViewpointBridge.frameNow();
            if (frame == null) return;
            long number = ViewpointBridge.frameNumber(frame);
            if (number == lastFrame) return;
            lastFrame = number;
            ScopePipWeaponSnapshot snapshot = ScopePipWeaponSnapshot.capture(frame, current);
            if (snapshot != null) MetalFrame.queue(new Drawer(snapshot, selected.boundary));
        } catch (Throwable t) {
            fail("capture", t);
        }
    }

    private static void fail(String where, Throwable t) {
        if (!failed) {
            failed = true;
            clear("failed");
            System.out.println("[TWZB] sight glass off: " + where + ": " + t);
        }
    }

    public static String info() {
        return (request == null ? "ready=false reason=inactive" : info)
                + " draws=" + draws + " skipped=" + skipped + " failed=" + failed;
    }

    /** Completed main-view window, with continuous zoom independent of channel identity. */
    static ScopePip.Request lensWindow() {
        Request r = request;
        ScopePip.Request d = drawn;
        return !failed && !ScopePip.hasWindowRequest() && r != null && d != null && ViewpointBridge.state() == 1 && ViewpointPipBridge.ready()
                && r.geometry.player == d.player && r.geometry.weapon == d.weapon
                && ReticleProfile.selectionKey(r.geometry.key).equals(ReticleProfile.selectionKey(d.key)) ? r.geometry : null;
    }

    private static final class Drawer extends TextureDraw.GenericDrawer {
        final ScopePipWeaponSnapshot snapshot;
        final float[] boundary;
        Drawer(ScopePipWeaponSnapshot snapshot, float[] boundary) { this.snapshot = snapshot; this.boundary = boundary; }

        @Override
        public void render() {
            Request selected = request;
            ScopePip.Request r = snapshot.request;
            if (failed || selected == null || !selected.geometry.key.equals(r.key) || !ViewpointBridge.ready() || !ViewpointPipBridge.ready()) {
                drawn = null;
                return;
            }
            boolean completed = false;
            PerfProbe.Scope perf = PerfProbe.render(PerfProbe.RETICLE);
            // performance pass 23.10.2026: the reticle's light boundary (ScopePipGL.reticle), every 1x ADS frame
            try (ScopePipGL saved = ScopePipGL.reticle()) {
                if (ViewpointPipBridge.currentScene() != snapshot.scene) { skipped++; return; }
                ScopePipWeaponSnapshot snapshot = this.snapshot.forRender();
                Matrix4f matrix = ViewpointBridge.viewProjection();
                float[] disk = ScopePipGeometry.disk(ViewpointPipBridge.screen(ScopePipGeometry.projectLens(matrix, snapshot.ocular)),
                        ViewpointPipBridge.screen(ScopePipGeometry.projectLens(matrix, snapshot.edgeX)),
                        ViewpointPipBridge.screen(ScopePipGeometry.projectLens(matrix, snapshot.edgeY)), saved.viewport);
                if (disk == null) { skipped++; info = "ready=false reason=lens clipped"; return; }
                ReticleGeometry.Aperture aperture = ReticleGeometry.Aperture.fromGL(disk, saved.viewport, boundary == null);
                // Source collimation supplies a tangent extent. The physical glass is only a clip mask.
                ReticleGeometry.Aperture mark;
                if ("collimator".equals(r.reticle.kind())) {
                    float[][] rays = SightGlassGeometry.angularRays(matrix, snapshot.forward, snapshot.up);
                    for (int i = 0; i < rays.length; i++) rays[i] = ViewpointPipBridge.screen(rays[i]);
                    mark = SightGlassGeometry.angular(rays, saved.viewport);
                } else {
                    mark = SightGlassGeometry.mark(aperture,
                            ViewpointPipBridge.screen(SightGlassGeometry.infinity(matrix, snapshot.forward)), saved.viewport);
                }
                if (mark == null) { skipped++; return; }
                boolean drawn = ReticleRenderer.draw(r.reticle, r.fov, aperture, mark, r.reticleBrightness, false, boundary,
                        ReticleRendererPip.sceneDepth(), disk[6]);
                if (drawn) IronSightProbe.reticle(r.key, r.weapon, mark.x(), mark.y(), saved.viewport[2], saved.viewport[3]);
                SightGlass.drawn = drawn ? r : null;
                completed = true;
                if (drawn) draws++; else skipped++;
                info = new Drawn(drawn, r, aperture, mark, saved.viewport[2], saved.viewport[3], r.reticleBrightness);
            } catch (Throwable t) {
                fail("render", t);
            } finally {
                if (!completed) drawn = null;
                PerfProbe.end(perf);
            }
        }

        @Override
        public void postRender() { }
    }
}
