package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;

/**
 * FB8 item 5 (user report of 22.10.2026, "when another sight is chosen the others stop working, replaced by black
 * glass"): the marks of the collimators of the held assembly that are not aimed through (TW_SightGlass G.extras) - the
 * other sights while aiming through one, all of them at the hip - on their open glass (LensGlassSelection extras), as
 * Tarkov's collimators show their mark on the lens whatever sight is in use. Each mark lies at infinity along its sight's
 * axis, clipped to its glass: it shows only where that direction crosses the window. The selected sight keeps
 * SightGlass / ScopePip; nothing here draws a second world.
 */
public final class SightGlassExtras {
    private record Extra(ScopePip.Request geometry, float[] boundary) { }
    private static volatile List<Extra> extras = List.of();
    /** Main thread: the reticle of each sight's selection key (the shared single-entry cache stays the selected one's). */
    private static final Map<String, ReticleProfile> reticles = new HashMap<>();
    private static volatile boolean failed;
    private static volatile long draws, skipped, frames;
    private static volatile String info = "extras=0";
    private static long lastFrame = Long.MIN_VALUE;

    private SightGlassExtras() { }

    public static void set(IsoPlayer player, KahluaTable list) {
        LensGlassSelection.setExtras(failed ? null : player, failed ? null : list);
        if (failed || player == null || list == null || !player.isLocalPlayer() || player.getPlayerNum() != 0
                || player.isDead() || player.getPrimaryHandItem() == null) {
            extras = List.of();
            return;
        }
        try {
            List<Extra> out = new ArrayList<>();
            for (int i = 1; i <= Math.min(list.len(), 8); i++) {
                if (!(list.rawget(i) instanceof KahluaTable t)) continue;
                Object value = t.rawget("fov");
                float fov = value instanceof Number n ? n.floatValue() : 0f;
                String selection = ReticleProfile.selectionKey(player.getPrimaryHandItem().getID() + ":" + t.rawget("key") + ":" + fov);
                ReticleProfile reticle = reticles.get(selection);
                if (reticle == null && !reticles.containsKey(selection)) {
                    if (reticles.size() > 64) reticles.clear();
                    reticle = ReticleProfile.capture(table(t, "reticleInventory"), table(t, "reticleItem"), table(t, "reticleMode"));
                    reticles.put(selection, reticle);
                }
                if (reticle == null || reticle.layers().isEmpty()) continue;
                out.add(new Extra(new ScopePip.Request(player, t, fov, reticle), boundary(t)));
            }
            extras = List.copyOf(out);
            info = "extras=" + out.size();
        } catch (IllegalArgumentException unavailable) {
            extras = List.of();
            info = "extras=0 reason=source aperture unavailable";
        } catch (Throwable t) {
            fail("request", t);
        }
    }

    private static KahluaTable table(KahluaTable t, String key) {
        return t.rawget(key) instanceof KahluaTable row ? row : null;
    }

    private static float[] boundary(KahluaTable t) {
        if (!(t.rawget("glassBoundary") instanceof KahluaTable polygon)) return null;
        if (polygon.len() < 6 || polygon.len() > 128 || polygon.len() % 2 != 0) throw new IllegalArgumentException("Invalid glass boundary");
        float[] out = new float[polygon.len()];
        for (int i = 0; i < out.length; i++) {
            if (!(polygon.rawget(i + 1) instanceof Number n) || !Float.isFinite(n.floatValue())) throw new IllegalArgumentException("Invalid glass point");
            out[i] = n.floatValue();
        }
        return out;
    }

    /** Main thread, at the frame boundary of ScopePip.afterWorld (every frame, whatever the selected sight). */
    public static void afterWorld() {
        try {
            List<Extra> list = extras;
            if (failed || list.isEmpty() || ViewpointBridge.state() != 1 || !ViewpointPipBridge.detect()) return;
            Object frame = ViewpointBridge.frameNow();
            if (frame == null) return;
            long number = ViewpointBridge.frameNumber(frame);
            if (number == lastFrame) return;
            lastFrame = number;
            List<ScopePipWeaponSnapshot> shots = new ArrayList<>();
            List<float[]> boundaries = new ArrayList<>();
            // the sight aimed through: its own window (SightGlass) draws its mark once it is open
            ScopePip.Request open = SightGlass.lensWindow();
            String openKey = open == null ? null : ReticleProfile.selectionKey(open.key);
            for (Extra e : list) {
                ScopePip.Request r = e.geometry;
                if (r.player.isDead() || r.player.getPrimaryHandItem() != r.weapon) continue;
                if (openKey != null && open.weapon == r.weapon && openKey.equals(ReticleProfile.selectionKey(r.key))) continue;
                ScopePipWeaponSnapshot snapshot = ScopePipWeaponSnapshot.capture(frame, r, false);
                if (snapshot != null) {
                    shots.add(snapshot);
                    boundaries.add(e.boundary);
                }
            }
            if (!shots.isEmpty()) {
                frames++;
                MetalFrame.queue(new Drawer(shots, boundaries));
            }
        } catch (Throwable t) {
            fail("capture", t);
        }
    }

    private static void fail(String where, Throwable t) {
        if (!failed) {
            failed = true;
            extras = List.of();
            LensGlassSelection.setExtras(null, null);
            info = "extras=0 reason=failed";
            System.out.println("[TWZB] other sights' glass off: " + where + ": " + t);
        }
    }

    public static String info() {
        return info + " frames=" + frames + " draws=" + draws + " skipped=" + skipped + " failed=" + failed;
    }

    private static final class Drawer extends TextureDraw.GenericDrawer {
        final List<ScopePipWeaponSnapshot> shots;
        final List<float[]> boundaries;

        Drawer(List<ScopePipWeaponSnapshot> shots, List<float[]> boundaries) {
            this.shots = shots;
            this.boundaries = boundaries;
        }

        @Override
        public void render() {
            if (failed || !ViewpointBridge.ready() || !ViewpointPipBridge.ready()) return;
            PerfProbe.Scope perf = PerfProbe.render(PerfProbe.RETICLE);
            // performance pass 23.10.2026: the reticle's light boundary (ScopePipGL.reticle), every frame with a collimator
            try (ScopePipGL saved = ScopePipGL.reticle()) {
                Matrix4f matrix = ViewpointBridge.viewProjection();
                for (int i = 0; i < shots.size(); i++) {
                    ScopePipWeaponSnapshot captured = shots.get(i);
                    if (ViewpointPipBridge.currentScene() != captured.scene) { skipped++; continue; }
                    ScopePipWeaponSnapshot snapshot = captured.forRender();
                    ScopePip.Request r = snapshot.request;
                    float[] boundary = boundaries.get(i);
                    float[] disk = ScopePipGeometry.disk(ViewpointPipBridge.screen(ScopePipGeometry.projectLens(matrix, snapshot.ocular)),
                            ViewpointPipBridge.screen(ScopePipGeometry.projectLens(matrix, snapshot.edgeX)),
                            ViewpointPipBridge.screen(ScopePipGeometry.projectLens(matrix, snapshot.edgeY)), saved.viewport);
                    if (disk == null) { skipped++; continue; }
                    ReticleGeometry.Aperture aperture = ReticleGeometry.Aperture.fromGL(disk, saved.viewport, boundary == null);
                    float[][] rays = SightGlassGeometry.angularRays(matrix, snapshot.forward, snapshot.up);
                    for (int k = 0; k < rays.length; k++) rays[k] = ViewpointPipBridge.screen(rays[k]);
                    ReticleGeometry.Aperture mark = SightGlassGeometry.angular(rays, saved.viewport);
                    if (mark == null) { skipped++; continue; }
                    if (ReticleRenderer.draw(r.reticle, r.fov, aperture, mark, r.reticleBrightness, false, boundary,
                            ReticleRendererPip.sceneDepth(), disk[6])) draws++;
                    else skipped++;
                }
            } catch (Throwable t) {
                fail("render", t);
            } finally {
                PerfProbe.end(perf);
            }
        }

        @Override
        public void postRender() { }
    }
}
