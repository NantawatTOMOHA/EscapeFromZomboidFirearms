package pz.escapefromzomboidfirearms.zb;

import java.util.IdentityHashMap;
import java.util.Map;

import org.escapefromzomboidfirearms.realgl.RealGL;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelInstanceRenderDataList;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;
import zombie.iso.IsoCamera;

/**
 * Warm characters in the thermal image as their own animated models: every
 * character the game renders while it is in a player's thermal view is drawn
 * again, in one colour (its thermal input), into the heat buffer, depth-tested
 * against a copy of the world depth taken after the tiles and before any
 * character (walls in front hide it, as in the frame itself).
 * The engine skips characters the viewer cannot see in the dark; while a
 * thermal view is on, the warm characters of that view (in its cone, in line
 * of sight, chosen in Lua) are rendered anyway. Rendering only: their alpha
 * fields, which the game's AI and update rate read, are never written.
 * Vehicles go the same way through VehicleHeat: those of a thermal view are
 * rendered in the dark too (a parked car with its lights off is otherwise
 * skipped, and its heat with it).
 */
public final class ModelHeat {
    /** Warm characters of each player's thermal view -> thermal input (main thread publishes). */
    public static volatile Map<Object, Float>[] warm = newWarm();
    /** Any thermal view on. */
    public static volatile boolean active;

    // render thread
    public static boolean prepared;
    static Shader skinned, rigid;

    /** Render thread: the heat model shaders, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (skinned == null) {
            skinned = ShaderWarmup.made(new Shader("tw_heat_model", false, false));
            rigid = ShaderWarmup.made(new Shader("tw_heat_model", true, false));
        }
    }
    static final IdentityHashMap<Object, Boolean> modelled = new IdentityHashMap<>();
    public static volatile long models;
    public static volatile int errors;
    public static volatile String lastError = "";
    private static boolean drawing;

    private ModelHeat() {
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Float>[] newWarm() {
        return new Map[4];
    }

    static void publish(int player, Object[] objects, float[] values) {
        Map<Object, Float>[] next = warm.clone();
        if (objects == null) {
            next[player] = null;
        } else {
            IdentityHashMap<Object, Float> m = new IdentityHashMap<>();
            for (int i = 0; i < objects.length; i++) {
                if (objects[i] != null) {
                    m.put(objects[i], values[i * 5]);
                }
            }
            next[player] = m;
        }
        warm = next;
        boolean any = false;
        for (Map<Object, Float> m : next) {
            any |= m != null && !m.isEmpty();
        }
        active = any;
    }

    public static Float warmth(Object character, int player) {
        if (player < 0 || player > 3) {
            return null;
        }
        Map<Object, Float> m = warm[player];
        return m == null ? null : m.get(character);
    }

    /** Warm in any local player's thermal view (worker threads do not know the viewer). */
    public static boolean warmAny(Object character) {
        for (Map<Object, Float> m : warm) {
            if (m != null && m.containsKey(character)) {
                return true;
            }
        }
        return false;
    }

    // Advice bodies are inlined into game classes of other packages: they only
    // call these public static methods.
    // In Viewpoint's perspective view no thermal image is drawn (OpticRenderer) and Viewpoint
    // snapshots every character through ModelSlotRenderData.init itself: a character the
    // viewer cannot see stays as the game left it (no visibility leak).
    public static boolean visible(Object self, int player) {
        // Map lookups before the reflective perspective() read: every invisible character render passes here.
        if (self instanceof IsoGameCharacter) {
            return active && warmth(self, player) != null && !ViewpointBridge.perspective();
        }
        return VehicleHeat.shown(self, player) && !ViewpointBridge.perspective();
    }

    public static void opaque(ModelSlotRenderData self) {
        if (self.alpha >= 0.01f || ViewpointBridge.perspective()) {
            return;
        }
        if (self.character != null ? active && warmAny(self.character) : VehicleHeat.shownAny(self.object)) {
            self.alpha = 1f;
        }
    }

    /** World frames rendered (main thread, FBORenderChunkManager.endFrame), any player. */
    public static volatile long worldFrames;

    public static void afterTiles() {
        worldFrames++;
        int p = IsoCamera.frameState.playerIndex;
        if (p >= 0 && p <= 3) {
            // A scope sees hot weapons: their parts go into its field buffer with the characters (MuzzleHeatModel).
            MuzzleHeatModel.afterTiles(p, IsoCamera.getScreenWidth(p) * (p % 2), p >= 2 ? IsoCamera.getScreenHeight(p) : 0,
                    IsoCamera.getScreenWidth(p), IsoCamera.getScreenHeight(p));
        }
        if (p < 0 || p > 3 || ((!active || warm[p] == null) && !VehicleHeat.any(p))) {
            return;
        }
        DepthCopy d = new DepthCopy();
        d.vx = IsoCamera.getScreenWidth(p) * (p % 2);
        d.vy = p >= 2 ? IsoCamera.getScreenHeight(p) : 0;
        d.w = IsoCamera.getScreenWidth(p);
        d.h = IsoCamera.getScreenHeight(p);
        SpriteRenderer.instance.drawGeneric(d);
    }

    public static void afterModel(ModelSlotRenderData self) {
        // The visible heat of a hot weapon on its own surface, into the world frame (MuzzleHeatModel).
        MuzzleHeatModel.iso(self);
        if (!prepared) {
            return;
        }
        if (active && self.character != null) {
            draw(self);
        } else if (self.character == null) {
            VehicleHeat.draw(self);
        }
    }

    /**
     * The framebuffers actually bound for drawing and reading, and
     * TextureFBO.lastID. A pipeline may bind its own targets past
     * TextureFBO's record (seen with the pzopt optimiser); restoring the
     * record instead of the binding sent the rest of the game's world pass
     * elsewhere and the world frame stopped changing while a thermal view was
     * on. Every pass into the heat buffer restores exactly what it found.
     */
    static int[] bindings() {
        return new int[] {RealGL.drawFramebuffer(), RealGL.readFramebuffer(), TextureFBO.lastID};
    }

    static void restore(int[] b) {
        RealGL.bindDrawFramebuffer(b[0]);
        RealGL.bindReadFramebuffer(b[1]);
        TextureFBO.lastID = b[2];
    }

    static void fail(Throwable t) {
        errors++;
        String m = t.toString();
        if (!m.equals(lastError)) {
            lastError = m;
            System.out.println("[TWZB] model heat " + m);
        }
    }

    // --- visibility (render only) -------------------------------------------------

    /** IsoObject.isAlphaZero(int): a warm character of the viewer's thermal view is not invisible. */
    @Patch(className = "zombie.iso.IsoObject", methodName = "isAlphaZero")
    public static class Visible {
        @Patch.OnExit
        public static void exit(@Patch.This Object self, @Patch.Argument(0) int player,
                                @Patch.Return(readOnly = false) boolean zero) {
            if (zero && ModelHeat.visible(self, player)) {
                zero = false;
            }
        }
    }

    /** ModelSlotRenderData.init (may run on a worker thread): drawn opaque, not skipped by Model.DrawChar. */
    @Patch(className = "zombie.core.skinnedmodel.model.ModelSlotRenderData", methodName = "init")
    public static class Opaque {
        @Patch.OnExit
        public static void exit(@Patch.This ModelSlotRenderData self) {
            ModelHeat.opaque(self);
        }
    }

    // --- depth copy -------------------------------------------------------------------

    /** After the chunk composite (tile depth complete, no character yet): copy depth, clear heat. */
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderChunkManager", methodName = "endFrame")
    public static class AfterTiles {
        @Patch.OnExit
        public static void exit() {
            ModelHeat.afterTiles();
        }
    }

    static final class DepthCopy extends TextureDraw.GenericDrawer {
        int vx, vy, w, h;

        @Override
        public void render() {
            // The world pass draws into whatever is bound now: its depth is read from there.
            int[] b = bindings();
            boolean scissor = RealGL.glIsEnabled(RealGL.GL_SCISSOR_TEST);
            try {
                TextureFBO world = Core.getInstance().getOffscreenBuffer();
                if (world == null) {
                    return;
                }
                TextureFBO heat = HeatRenderer.ensure(world.getWidth(), world.getHeight());
                RealGL.glDisable(RealGL.GL_SCISSOR_TEST);
                RealGL.bindReadFramebuffer(b[0]);
                RealGL.bindDrawFramebuffer(heat.getBufferId());
                // A context that cannot copy depth: no model silhouettes this frame, the heat quads stand in (HeatRenderer).
                if (!RealGL.blitDepth(vx, vy, vx + w, vy + h)) {
                    return;
                }
                RealGL.glPushAttrib(RealGL.GL_COLOR_BUFFER_BIT | RealGL.GL_SCISSOR_BIT);
                RealGL.glEnable(RealGL.GL_SCISSOR_TEST);
                RealGL.glScissor(vx, vy, w, h);
                RealGL.glColorMask(true, true, true, true);
                RealGL.glClearColor(0f, 0f, 0f, 0f);
                RealGL.glClear(RealGL.GL_COLOR_BUFFER_BIT);
                RealGL.glPopAttrib();
                prepared = true;
                modelled.clear();
            } catch (Throwable t) {
                fail(t);
            } finally {
                restore(b);
                if (scissor) {
                    RealGL.glEnable(RealGL.GL_SCISSOR_TEST);
                } else {
                    RealGL.glDisable(RealGL.GL_SCISSOR_TEST);
                }
                GLStateRenderThread.restore();
            }
        }
    }

    // --- the model in the heat buffer -------------------------------------------------

    @Patch(className = "zombie.core.skinnedmodel.model.ModelSlotRenderData", methodName = "render")
    public static class AfterModel {
        @Patch.OnExit
        public static void exit(@Patch.This ModelSlotRenderData self) {
            ModelHeat.afterModel(self);
        }
    }

    public static void draw(ModelSlotRenderData s) {
        TextureFBO heat = HeatRenderer.buffer();
        if (drawing || heat == null || s.character == null || s.IsRenderingToCard() || !s.canRender() || s.animPlayer == null) {
            return;
        }
        Float input = warmth(s.character, SpriteRenderer.instance.getRenderingPlayerIndex());
        if (input == null) {
            return;
        }
        shaders();
        if (!skinned.getShaderProgram().isCompiled() || !rigid.getShaderProgram().isCompiled()) {
            return;
        }
        ModelInstanceRenderDataList list = s.getModelData();
        float value = Math.min(1f, input / HeatRenderer.SCALE);
        float oldAlpha = s.alpha;
        boolean oldDepthMask = ModelCamera.instance.depthMask;
        drawing = true;
        int[] b = bindings();
        RealGL.glPushClientAttrib(-1);
        RealGL.glPushAttrib(0xFFFFF);
        boolean camera = false;
        try {
            // Body, hair and clothes are skinned and warm; rigid models on bones
            // (the weapon in hand, attached items) are cold and hide what is behind.
            skinned.Start();
            skinned.getShaderProgram().setVector4("u_color", value, 0f, 0f, 1f);
            skinned.End();
            rigid.Start();
            rigid.getShaderProgram().setVector4("u_color", 0f, 0f, 0f, 1f);
            rigid.End();
            heat.startDrawing(false, false);
            RealGL.glDisable(RealGL.GL_SCISSOR_TEST);
            RealGL.glDisable(RealGL.GL_BLEND);
            ModelCamera.instance.depthMask = true;
            s.alpha = 1f;
            Model.CharacterModelCameraBegin(s);
            camera = true;
            for (ModelInstanceRenderData d : list) {
                Model m = d.model;
                if (m == null) {
                    continue;
                }
                Shader old = m.effect;
                m.effect = m.isStatic ? rigid : skinned;
                try {
                    RealGL.glDepthMask(true);
                    d.RenderCharacter(s);
                } finally {
                    m.effect = old;
                }
            }
            modelled.put(s.character, Boolean.TRUE);
            models++;
        } catch (Throwable t) {
            fail(t);
        } finally {
            if (camera) {
                Model.CharacterModelCameraEnd();
            }
            s.alpha = oldAlpha;
            ModelCamera.instance.depthMask = oldDepthMask;
            heat.endDrawing();
            restore(b);
            RealGL.glPopAttrib();
            RealGL.glPopClientAttrib();
            Texture.lastTextureID = -1;
            RealGL.glEnable(RealGL.GL_TEXTURE_2D);
            SpriteRenderer.ringBuffer.restoreVbos = true;
            RealGL.glDisable(RealGL.GL_DEPTH_TEST);
            RealGL.glEnable(RealGL.GL_BLEND);
            RealGL.glEnable(RealGL.GL_ALPHA_TEST);
            RealGL.glAlphaFunc(RealGL.GL_GREATER, 0f);
            GLStateRenderThread.restore();
            drawing = false;
        }
    }

    /** Render thread: was this character (or vehicle) drawn as a model into the heat buffer this frame? */
    static boolean isModelled(Object character) {
        return modelled.containsKey(character);
    }

    static void markModelled(Object o) {
        modelled.put(o, Boolean.TRUE);
    }

    /** Render thread, after the scope image used the buffer. */
    static void frameDone() {
        prepared = false;
    }
}
