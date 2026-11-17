package pz.escapefromzomboidfirearms.zb;

import java.nio.FloatBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.*;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.core.SpriteRenderer;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;

/**
 * Original Tarkov LCD glyphs on the captured device plane, with world occlusion. Under them the
 * display panel of the device's rangefinder mode (Tarkov shows that mode's own panel, an empty LCD;
 * the model carries the off mode's panel), drawn opaque on the panel's own triangles.
 */
public final class ReticleRendererRangefinder {
    private record Request(IsoPlayer player, Object weapon, float[] vertices, float[] color, Texture texture, long at) { }
    private static volatile Request request, panel;
    private static TwProgram program;

    /** Render thread: the rangefinder program, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (program == null) program = ShaderWarmup.made(TwProgram.create("tw_pip_rangefinder"));
    }
    private static int vao, vbo, sampler;
    /** Performance pass 23.10.2026: render thread, one vertex buffer reused by every draw (it was created per draw, twice a frame). */
    private static FloatBuffer points;

    /** Performance pass 23.10.2026: a drawn box's values; the QA text (String.format) is made when info() is read. */
    private record Box(int[] viewport, float x0, float y0, float x1, float y1, float us, float vs) {
        @Override public String toString() {
            return String.format(java.util.Locale.ROOT, "%.0f,%.0f,%.0f,%.0f atlasScale=%.4f,%.4f",
                    viewport[0] + (x0 * .5f + .5f) * viewport[2], viewport[1] + (1f - (y1 * .5f + .5f)) * viewport[3],
                    viewport[0] + (x1 * .5f + .5f) * viewport[2], viewport[1] + (1f - (y0 * .5f + .5f)) * viewport[3], us, vs);
        }
    }
    public static volatile long draws, panelDraws, skipped, errors;
    private static volatile String lastError = "";

    private ReticleRendererRangefinder() { }

    public static void set(IsoPlayer player, Object weapon, KahluaTable vertices, KahluaTable color, String atlas) {
        if (player == null || !player.isLocalPlayer() || player.getPlayerNum() != 0
                || player.getPrimaryHandItem() != weapon || vertices == null || color == null
                || !"TW_RangefinderDigits".equals(atlas)) return;
        int n = vertices.len();
        if (n <= 0 || n > 32 * 24 * 5 || n % 30 != 0) return;
        float[] points = new float[n], rgb = new float[3];
        for (int i = 0; i < n; i++) {
            if (!(vertices.rawget(i + 1) instanceof Number value) || !Float.isFinite(value.floatValue())) return;
            points[i] = value.floatValue();
        }
        for (int i = 0; i < 3; i++) {
            if (!(color.rawget(i + 1) instanceof Number value) || !Float.isFinite(value.floatValue())) return;
            rgb[i] = Math.max(0, Math.min(1, value.floatValue()));
        }
        Texture texture = Texture.getSharedTexture("media/textures/" + atlas + ".png");
        request = new Request(player, weapon, points, rgb, texture, System.currentTimeMillis());
    }

    /** The mode's display panel (x, y, z, u, v per vertex, whole triangles), sent with the digits. */
    public static void setPanel(IsoPlayer player, Object weapon, KahluaTable vertices, String atlas) {
        if (player == null || !player.isLocalPlayer() || player.getPlayerNum() != 0
                || player.getPrimaryHandItem() != weapon || vertices == null
                || !"TW_RangefinderPanel".equals(atlas)) return;
        int n = vertices.len();
        if (n <= 0 || n > 15 * 256 || n % 15 != 0) return;
        float[] points = new float[n];
        for (int i = 0; i < n; i++) {
            if (!(vertices.rawget(i + 1) instanceof Number value) || !Float.isFinite(value.floatValue())) return;
            points[i] = value.floatValue();
        }
        Texture texture = Texture.getSharedTexture("media/textures/" + atlas + ".png");
        panel = new Request(player, weapon, points, null, texture, System.currentTimeMillis());
    }

    /** Main thread, after the VP world composite was queued: first or third person (FB2-M, the device on the model there too). */
    static void queue() {
        Request r = request;
        int view = ViewpointBridge.state();
        if (r == null || System.currentTimeMillis() - r.at > 250 || r.player.isDead()
                || r.player.getPrimaryHandItem() != r.weapon || view != 1 && view != 2) return;
        try {
            if (!ViewpointPipBridge.detect()) return;
            Object frame = ViewpointBridge.frameNow();
            if (frame == null) return;
            SightDrawFrame.Pair pair = SightDrawFrame.frame(SpriteRenderer.instance.getMainStateIndex(),
                    ViewpointBridge.sceneOf(frame), ViewpointBridge.frameNumber(frame));
            if (pair == null || pair.player != r.player || pair.weapon != r.weapon) { skipped++; return; }
            Matrix4f root = new Matrix4f(pair.root);
            Object scene = pair.scene;
            Request p = panel;
            Request under = p != null && p.player == r.player && p.weapon == r.weapon && Math.abs(p.at - r.at) <= 250 ? p : null;
            MetalFrame.queue(new TextureDraw.GenericDrawer() {
                @Override public void render() {
                    // performance pass 23.10.2026: draw() changes only the light boundary's set (ScopePipGL.reticle)
                    try (ScopePipGL saved = ScopePipGL.reticle()) {
                        if (!ViewpointBridge.ready() || ViewpointBridge.currentScene() != scene) return;
                        Matrix4f correction = FpViewLatch.correction(scene);
                        Matrix4f placed = correction == null ? root : correction.mul(root);
                        Matrix4f projection = (Matrix4f) ViewpointPipBridge.get("SceneData.projection", scene);
                        if (under != null && draw(under, placed, saved.viewport, projection)) panelDraws++;
                        if (draw(r, placed, saved.viewport, projection)) draws++;
                    } catch (Throwable error) {
                        errors++;
                        String message = error.toString();
                        if (!message.equals(lastError)) { lastError = message; System.out.println("[TWZB] rangefinder LCD " + message); }
                    }
                }
            });
        } catch (Throwable error) {
            errors++;
            ViewpointBridge.fail("rangefinder LCD", error);
        }
    }

    /** Digits (SDF alpha in the request colour) or, without a colour, the opaque panel texture. */
    private static boolean draw(Request r, Matrix4f root, int[] viewport, Matrix4f projection) throws Throwable {
        if (r.texture == null || !r.texture.isReady()) return false;
        Matrix4f vp = ViewpointBridge.viewProjection();
        if (vp == null || projection == null) return false;
        boolean plain = r.color == null;
        if (points == null || points.capacity() < r.vertices.length) points = BufferUtils.createFloatBuffer(r.vertices.length);
        FloatBuffer points = ReticleRendererRangefinder.points;
        points.clear();
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        // The atlas UVs address the source image; PZ keeps it in a power-of-two texture.
        float us = r.texture.getWidthHW() > 0 ? r.texture.getWidth() / (float) r.texture.getWidthHW() : 1f;
        float vs = r.texture.getHeightHW() > 0 ? r.texture.getHeight() / (float) r.texture.getHeightHW() : 1f;
        for (int i = 0; i < r.vertices.length; i += 5) {
            Vector3f p = root.transformPosition(new Vector3f(r.vertices[i], r.vertices[i + 1], r.vertices[i + 2]));
            float[] projected = ScopePipGeometry.project(vp, p);
            if (projected == null) { skipped++; return false; }
            float depth = projected[2] * .5f + .5f;
            float[] screen = ViewpointPipBridge.screen(projected);
            points.put(screen[0]).put(screen[1]).put(depth).put(r.vertices[i + 3] * us).put(r.vertices[i + 4] * vs);
            x0 = Math.min(x0, screen[0]); x1 = Math.max(x1, screen[0]); y0 = Math.min(y0, screen[1]); y1 = Math.max(y1, screen[1]);
        }
        points.flip();
        // QA: the drawn glyphs' (panel's) screen box, pixels from the viewport's top left.
        Box box = new Box(new int[] {viewport[0], viewport[1], viewport[2], viewport[3]}, x0, y0, x1, y1, us, vs);
        if (plain) lastPanelBox = box; else lastBox = box;
        shaders();
        if (!program.isCompiled()) throw new IllegalStateException("rangefinder shader did not compile");
        if (vao == 0) {
            vao = GL30.glGenVertexArrays(); vbo = GL15.glGenBuffers(); sampler = GL33.glGenSamplers();
            GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, points, GL15.GL_STREAM_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 20, 0L);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 20, 12L);
        GL20.glEnableVertexAttribArray(0); GL20.glEnableVertexAttribArray(1);
        GL11.glDisable(GL11.GL_DEPTH_TEST); GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_SCISSOR_TEST); GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glEnable(GL11.GL_BLEND); GL11.glDepthMask(false); GL11.glColorMask(true, true, true, false);
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
        program.Start();
        program.setSamplerUnit("Digits", 0); program.setSamplerUnit("SceneDepth", 1);
        if (plain) program.setVector3("Color", 1f, 1f, 1f); else program.setVector3("Color", r.color[0], r.color[1], r.color[2]);
        program.setValue("Plain", plain ? 1f : 0f);
        program.setVector4("Viewport", viewport[0], viewport[1], viewport[2], viewport[3]);
        int depth = ReticleRendererPip.sceneDepth();
        program.setValue("DepthOn", depth == 0 ? 0f : 1f);
        program.setVector2("DepthLinear", projection.m32(), projection.m22());
        GL13.glActiveTexture(GL13.GL_TEXTURE0); GL11.glBindTexture(GL11.GL_TEXTURE_2D, r.texture.getID()); GL33.glBindSampler(0, sampler);
        GL13.glActiveTexture(GL13.GL_TEXTURE1); GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth); GL33.glBindSampler(1, sampler);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, r.vertices.length / 5);
        program.End();
        return true;
    }

    private static volatile Object lastBox = "none", lastPanelBox = "none";

    public static String info() {
        return "draws=" + draws + " panelDraws=" + panelDraws + " skipped=" + skipped + " errors=" + errors + " box=" + lastBox
                + " panelBox=" + lastPanelBox + " lastError=" + lastError;
    }
}
