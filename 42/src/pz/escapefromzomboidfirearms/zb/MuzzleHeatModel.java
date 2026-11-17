package pz.escapefromzomboidfirearms.zb;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.escapefromzomboidfirearms.realgl.RealGL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import zombie.characters.IsoGameCharacter;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.ShaderProgram;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;
import zombie.scripting.objects.ModelScript;

/**
 * The visible heat of a hot weapon on its own surface, as Tarkov's HotObject shows it (WeaponOverHeatSystem,
 * the weapon shaders' USEHEAT): every pixel of the renderer a HotObject drives gets
 * lerp(_HeatColor1, _HeatColor2, w) x w x HeatVisibleFactor x the specular term, with
 * w = max(0, 1 - |(p - _HeatCenter) / _HeatSize|) x ((T - 30) / 10)^2 and p the pixel's object-space
 * position. The HeatBounds of a barrel are centred on its muzzle, a muzzle device's on its mount: half of
 * each lies beyond its part, where Tarkov has no pixel to light. A flat quad over the bounds glowed in the
 * air there and lay over the gun as a band (user's report of 21.10.2026: "the barrel heat is applied
 * crookedly onto the model and even extends beyond the barrel itself"). Here the hot part's own mesh is
 * drawn again with that term (tw_muzzle_heat, tw_vp_muzzle_heat): per HotObject a box matrix q
 * (tools/muzzle_fx.py hot_geometry: the part's exported mesh space -> (p - _HeatCenter) / _HeatSize, node
 * scale and pistol roll included) on its own sub-mesh (its atlas rectangle), additive, over the very
 * surface just drawn and only where it is seen:
 *   isometric  right after PZ drew the character (ModelHeat.afterModel, ModelSlotRenderData.render): its
 *              rigid weapon models again with the same transforms and depth (GL_LEQUAL, pulled DEPTH_PULL
 *              toward the camera), the texture's cut-outs left out, the character's fade kept;
 *   Viewpoint  in the mod's pass of its frame (ViewpointPass.submit, HDR or post): the frame's captured
 *              model records of the weapon (ModelDraws: the matrices and the terrain lift Viewpoint drew
 *              them with, so its carried first-person prop too), kept where they are the surface its depth
 *              saw.
 * A renderer whose shader or interface fails draws the lit zone along the bore instead (MuzzleFx: a quad /
 * ribbon from a to b), never nothing. The thermal image keeps its own warmth over the same lit zone
 * (MuzzleFx.heat, VpHeat).
 * Isometric scopes: a night vision or thermal scope shows its own image only inside its field mask
 * (OpticRenderer: the cone on the floor, plus MuzzleFx's field buffer for what stands above it). The hot
 * parts of a weapon standing in a scope's field are drawn a second time, alone, into that buffer (seeded
 * before the characters, kept by MuzzleFx.Drawer.drawField), so the scope shows its own rendering of the
 * hot metal there, as it did for the flat glow.
 * Isometric night: the heat is emissive here as in Tarkov (no scene light, only the character's fade), but
 * the game's screen grade (screen.frag screenWorld, its weather shader) maps x to 1.2 x - 0.08: below 0.0667
 * all is black, so on a night-black surface a glow lost 0.08 of the 1.2 x it keeps by day. Before the glow
 * the surface under it is raised to that black point (GL_MAX: it stays black after the grade, a brighter one
 * is untouched), so the glow comes out of the grade as by day. Not while a scope shows the frame: its image
 * is made from the world frame and keeps the plain glow.
 */
public final class MuzzleHeatModel {
    /**
     * Per box: the three rows of q, then c1 with the specular term, c2 with VisibleHeatAlpha x HeatVisibleFactor,
     * and the atlas rectangle (u0, v0, u1, v1) of the HotObject's own sub-mesh: PZ merges an item's renderers
     * into one mesh, each keeps its own atlas cell, so only the renderer the HotObject drives glows (Tarkov's
     * property block per renderer).
     */
    static final int VEC4_PER_BOX = 6, BOX_FLOATS = VEC4_PER_BOX * 4, MAX_BOXES = 8;
    /** Isometric: NDC pulled toward the camera over the part's own depth (about 0.1 mm at the model camera's scale). */
    static final float DEPTH_PULL = 1e-5f;
    /** Viewpoint: the same surface as its depth, metres of view distance: fixed and per metre of distance. */
    static final float TOLERANCE = .01f, TOLERANCE_PER_METRE = .004f;
    /** The game's screen grade, contrast(x, 1.2) about 0.4 (screen.frag): x below this comes out black. */
    static final float BLACK_POINT = 0.4f - 0.4f / 1.2f;

    /** A hot weapon in a character's hands this frame (immutable). */
    static final class Glow {
        final ModelInstance root;
        /** ((T - 30) / 10)^2 of the weapon's temperature (MuzzleFx.heatGlow). */
        final float level;
        /** Template -> packed boxes of its mesh (TW/template). */
        final Map<String, float[]> boxes;

        Glow(ModelInstance root, float level, Map<String, float[]> boxes) {
            this.root = root;
            this.level = level;
            this.boxes = boxes;
        }
    }

    private static final Map<IsoGameCharacter, Glow> NO_CHARACTERS = new IdentityHashMap<>();
    private static final Map<ModelInstance, Glow> NO_ROOTS = new IdentityHashMap<>();
    /** Published by the main thread every frame (MuzzleFx.simulate), read on the render thread; never changed after. */
    static volatile Map<IsoGameCharacter, Glow> byCharacter = NO_CHARACTERS;
    static volatile Map<ModelInstance, Glow> byRoot = NO_ROOTS;

    /** QA (TWMuzzleHeatQA.surface): false draws the lit-zone fallback in both renderers. */
    static volatile boolean surface = true;
    /** A renderer's shader or interface failed: the lit-zone fallback for the session (TWMuzzleHeatQA.reset). */
    static volatile boolean isoFailed, vpFailed;
    public static volatile long isoDraws, vpDraws, published, errors, scopeDraws, seeds, lifts;
    public static volatile String lastError = "";
    /** Main thread (afterTiles): per player, the hot weapons standing in its scope's field and their share of it, or null. */
    @SuppressWarnings("unchecked")
    static volatile Map<IsoGameCharacter, Float>[] scopeShares = new Map[4];
    /** Render thread: this player's field buffer was cleared for the hot parts before the characters (Seed), until its field pass or SeedEnd. */
    static final boolean[] seeded = new boolean[4];

    private MuzzleHeatModel() {
    }

    static boolean isoSurface() {
        return surface && !isoFailed;
    }

    static boolean vpSurface() {
        // Under the Metal shim VertexBufferObject.Draw is on the real context, not in Viewpoint's frame: the glow quads stand in.
        return surface && !vpFailed && !GLCompat.metalPath();
    }

    // ------------------------------------------------------------------ main thread

    /** MuzzleFx.simulate: the hot weapons in hands now (overheat at least MuzzleFx.HOT_MIN, boxes to draw). */
    static void publish(Map<IsoGameCharacter, MuzzleFx.Shooter> shooters) {
        IdentityHashMap<IsoGameCharacter, Glow> characters = null;
        IdentityHashMap<ModelInstance, Glow> roots = null;
        for (var entry : shooters.entrySet()) {
            IsoGameCharacter c = entry.getKey();
            MuzzleFx.Shooter s = entry.getValue();
            MuzzleFx.Setup hot = s == null ? null : s.hotSetup;
            ModelInstance root = c == null ? null : c.primaryHandModel;
            if (hot == null || root == null || hot.boxes.isEmpty()) {
                continue;
            }
            float level = MuzzleFx.heatGlow(s.overheat);
            if (level <= 1e-4f) {
                continue;
            }
            if (characters == null) {
                characters = new IdentityHashMap<>();
                roots = new IdentityHashMap<>();
            }
            Glow g = new Glow(root, level, hot.boxes);
            characters.put(c, g);
            roots.put(root, g);
        }
        byCharacter = characters == null ? NO_CHARACTERS : characters;
        byRoot = roots == null ? NO_ROOTS : roots;
        if (characters != null) {
            published++;
        }
    }

    /** The template of a mod model instance (mesh TW/template; a moving-parts weapon body TW/Moving/template_body), or null. */
    static String template(ModelInstance instance) {
        ModelScript script = instance == null ? null : instance.modelScript;
        String mesh = script == null ? null : script.getMeshName();
        if (mesh == null || !mesh.startsWith("TW/")) {
            return null;
        }
        mesh = mesh.substring(3);
        if (mesh.startsWith("Moving/")) {
            // MovingWeaponParts: the weapon's body without its moving parts, in the root model's own frame.
            return mesh.endsWith("_body") ? mesh.substring(7, mesh.length() - 5) : null;
        }
        return mesh.indexOf('/') < 0 ? mesh : null;
    }

    /** The hot weapon an instance belongs to (itself or a parent within 32 links), or null. */
    static Glow owner(ModelInstance instance, Map<ModelInstance, Glow> hot) {
        for (int depth = 0; instance != null && depth < 32; depth++, instance = instance.parent) {
            Glow g = hot.get(instance);
            if (g != null) {
                return g;
            }
        }
        return null;
    }

    /** Main thread: the hot weapons in player p's night vision or thermal scope field, with their share, or null. */
    static Map<IsoGameCharacter, Float> scopeField(int p) {
        Map<IsoGameCharacter, Glow> hot = byCharacter;
        if (p < 0 || p > 3 || hot.isEmpty() || !isoSurface() || ViewpointBridge.perspective()) {
            return null;
        }
        OpticState.View v = OpticState.views[p];
        if (v == null || v.kind == 0) {
            return null;
        }
        IdentityHashMap<IsoGameCharacter, Float> shares = null;
        for (IsoGameCharacter c : hot.keySet()) {
            float w = MuzzleFx.field(p, c.getX(), c.getY(), MuzzleFx.viewer(c));
            if (w > 0f) {
                if (shares == null) {
                    shares = new IdentityHashMap<>();
                }
                shares.put(c, w);
            }
        }
        return shares;
    }

    /**
     * Main thread, the world's tiles done and no character drawn yet (ModelHeat.afterTiles): when a scope of
     * player p sees hot weapons, its field buffer is cleared now for their hot parts (MuzzleFx.build then draws
     * the field pass that keeps them). vx, vy, w, h: the player's part of the world frame.
     */
    static void afterTiles(int p, int vx, int vy, int w, int h) {
        if (p < 0 || p > 3) {
            return;
        }
        Map<IsoGameCharacter, Float> shares = scopeField(p);
        boolean ended = shares == null && scopeShares[p] != null;
        @SuppressWarnings("unchecked")
        Map<IsoGameCharacter, Float>[] next = scopeShares.clone();
        next[p] = shares;
        scopeShares = next;
        if (shares != null) {
            Seed seed = new Seed();
            seed.player = p;
            seed.vx = vx;
            seed.vy = vy;
            seed.w = w;
            seed.h = h;
            SpriteRenderer.instance.drawGeneric(seed);
        } else if (ended) {
            SeedEnd end = new SeedEnd();
            end.player = p;
            SpriteRenderer.instance.drawGeneric(end);
        }
    }

    /** Render thread: the player's field buffer, cleared before the characters; its hot parts go in (drawIso). */
    static final class Seed extends TextureDraw.GenericDrawer {
        int player, vx, vy, w, h;

        @Override
        public void render() {
            int[] b = ModelHeat.bindings();
            boolean scissor = RealGL.glIsEnabled(RealGL.GL_SCISSOR_TEST);
            try {
                TextureFBO field = MuzzleFx.fieldBuffer();
                if (field == null) {
                    return;
                }
                RealGL.bindFramebuffer(field.getBufferId());
                RealGL.glPushAttrib(RealGL.GL_COLOR_BUFFER_BIT | RealGL.GL_SCISSOR_BIT);
                RealGL.glEnable(RealGL.GL_SCISSOR_TEST);
                RealGL.glScissor(vx, vy, w, h);
                RealGL.glColorMask(true, true, true, true);
                RealGL.glClearColor(0f, 0f, 0f, 0f);
                RealGL.glClear(RealGL.GL_COLOR_BUFFER_BIT);
                RealGL.glPopAttrib();
                seeded[player] = true;
                seeds++;
            } catch (Throwable t) {
                fail("scope field", t);
            } finally {
                ModelHeat.restore(b);
                if (scissor) {
                    RealGL.glEnable(RealGL.GL_SCISSOR_TEST);
                } else {
                    RealGL.glDisable(RealGL.GL_SCISSOR_TEST);
                }
                GLStateRenderThread.restore();
            }
        }
    }

    /**
     * Render thread, in the frame's order, before its characters: no scope of this player sees a hot weapon any more.
     * The field pass takes a seed only when it comes: MuzzleFx.afterWorld queues no drawer in the frame the last weapon
     * goes cold with nothing else to draw, nor in a frame turned to Viewpoint's view. A seed left behind would have every
     * later frame draw the glow as under a scope, without the black point raised (see the class).
     */
    static final class SeedEnd extends TextureDraw.GenericDrawer {
        int player;

        @Override
        public void render() {
            seeded[player] = false;
        }
    }

    /** Render thread (MuzzleFx.Drawer.drawField): the player's field buffer holds this frame's hot parts; once. */
    static boolean takeSeed(int player) {
        if (player < 0 || player > 3 || !seeded[player]) {
            return false;
        }
        seeded[player] = false;
        return true;
    }

    /** The game's screen grade is on (MultiTextureFBO2: its weather shader, screen.frag). */
    static boolean gradeActive() {
        return zombie.core.SceneShaderStore.weatherShader != null
                && zombie.debug.DebugOptions.instance.fboRenderChunk.useWeatherShader.getValue();
    }

    // ------------------------------------------------------------------ render thread

    private static Shader iso, vp;

    /** Render thread: the isometric glow's model shader, at the first use or ahead of it (ShaderWarmup). */
    static void shaderIso() {
        if (iso == null) {
            iso = ShaderWarmup.made(new Shader("tw_muzzle_heat", true, false));
        }
    }

    /** Render thread: Viewpoint's glow model shader, at the first use or ahead of it (ShaderWarmup). */
    static void shaderVp() {
        if (vp == null) {
            vp = ShaderWarmup.made(new Shader("tw_vp_muzzle_heat", true, false));
        }
    }
    private static int vao;
    private static final FloatBuffer upload = BufferUtils.createFloatBuffer(MAX_BOXES * BOX_FLOATS);
    private static final Matrix4f IDENTITY = new Matrix4f();

    static void fail(String where, Throwable t) {
        errors++;
        String m = where + ": " + t;
        if (!m.equals(lastError)) {
            lastError = m;
            System.out.println("[TWZB] muzzle heat on the model, " + where + " falls back to the lit zone: " + t);
        }
    }

    /** Render thread: the frame a mesh's transform is uploaded from (setValue uploads at once; performance pass 23.10.2026). */
    private static final Matrix4f FRAME = new Matrix4f(), MODEL = new Matrix4f();

    /** The imported transform of a mesh (identity for the mod's models, check_iron_center), as GLSL takes it. */
    static Matrix4f meshFrame(ModelMesh mesh) {
        // Stored native transforms are transposed (WeaponPartTransforms).
        return mesh.transform == null ? IDENTITY : FRAME.set(mesh.transform).transpose();
    }

    /**
     * Performance pass 23.10.2026: the location of a uniform from the program's own table (ShaderProgram fills it
     * at link from glGetActiveUniform + glGetUniformLocation and renews it on a recompile; an array is listed as
     * its element 0, whose location is the array's), else glGetUniformLocation as before - no query per draw.
     */
    static int location(ShaderProgram program, String name, String element, int type) {
        ShaderProgram.Uniform u = element == null ? null : program.getUniform(element, type, false);
        if (u == null) {
            u = program.getUniform(name, type, false);
        }
        return u != null ? u.loc : RealGL.glGetUniformLocation(program.getShaderID(), name);
    }

    /** The boxes, their count and the temperature term into the bound program. */
    static void boxes(ShaderProgram program, float[] boxes, float level) {
        int n = Math.min(MAX_BOXES, boxes.length / BOX_FLOATS);
        upload.clear();
        upload.put(boxes, 0, n * BOX_FLOATS).flip();
        int at = location(program, "Boxes", "Boxes[0]", RealGL.GL_FLOAT_VEC4);
        if (at < 0) {
            throw new IllegalStateException("heat shader without its boxes");
        }
        RealGL.glUniform4fv(at, upload);
        program.setValue("Count", (float) n);
        program.setValue("Level", level);
    }

    /** The HighResDepthMultiplier the game's own shader of this model draws with now (0 for live characters). */
    static float highRes(Model m) {
        Shader effect = m.effect;
        int id = effect == null ? 0 : effect.getID();
        if (id == 0) {
            return 0f;
        }
        int at = location(effect.getShaderProgram(), "HighResDepthMultiplier", null, RealGL.GL_FLOAT);
        return at < 0 ? 0f : RealGL.glGetUniformf(id, at);
    }

    /**
     * Performance pass 23.10.2026: highRes once per game program in one drawIso. The game sets that uniform
     * only through its own draws (Shader.setHighResDepthMultiplier from AnimatedModel, IsoObjectModelDrawer,
     * ItemModelRenderer and FBORenderCorpses, and AnimatedModel's property block), none of which runs inside
     * drawIso: between its parts and its scope pass the value read back cannot change. (A tracked setter is not
     * exact - the property block sets the uniform past it - so the read stays, once.)
     */
    private static int[] highIds = new int[8];
    private static float[] highValues = new float[8];
    private static int highCount;

    static float highResOnce(Model m) {
        Shader effect = m.effect;
        int id = effect == null ? 0 : effect.getID();
        for (int i = 0; i < highCount; i++) {
            if (highIds[i] == id) {
                return highValues[i];
            }
        }
        float v = highRes(m);
        if (highCount == highIds.length) {
            highIds = java.util.Arrays.copyOf(highIds, highCount * 2);
            highValues = java.util.Arrays.copyOf(highValues, highCount * 2);
        }
        highIds[highCount] = id;
        highValues[highCount++] = v;
        return v;
    }

    /** Model.DrawChar's culling of a model. */
    static void cull(ModelInstance instance) {
        ModelScript script = instance.modelScript;
        if (script != null && script.cullFace != -1) {
            if (script.cullFace == 0) {
                RealGL.glDisable(RealGL.GL_CULL_FACE);
            } else {
                RealGL.glEnable(RealGL.GL_CULL_FACE);
                RealGL.glCullFace(script.cullFace);
            }
        } else {
            RealGL.glEnable(RealGL.GL_CULL_FACE);
            RealGL.glCullFace(RealGL.GL_FRONT);
        }
    }

    /** Render thread, right after PZ drew a character's models (ModelHeat.afterModel): its hot weapon parts again. */
    public static void iso(ModelSlotRenderData s) {
        try {
            drawIso(s);
        } catch (Throwable t) {
            // inside the game's own render: never thrown on
            isoFailed = true;
            fail("isometric", t);
        }
    }

    static void drawIso(ModelSlotRenderData s) {
        if (!MuzzleFx.heatOn) return;
        Map<IsoGameCharacter, Glow> hot = byCharacter;
        if (hot.isEmpty() || !isoSurface() || s == null || s.character == null) {
            return;
        }
        Glow g = hot.get(s.character);
        // As Model.DrawChar: a slot faded out (alpha < 0.01) or not renderable draws nothing.
        if (g == null || s.IsRenderingToCard() || s.animPlayer == null || s.alpha < 0.01f || !s.canRender() || ViewpointBridge.perspective()) {
            return;
        }
        ArrayList<ModelInstanceRenderData> parts = null;
        ArrayList<float[]> boxes = null;
        for (ModelInstanceRenderData d : s.getModelData()) {
            if (d == null || d.model == null || d.modelInstance == null || !d.model.isStatic || d.model.mesh == null || !d.model.mesh.isReady()
                    || d.modelInstance.tex == null && d.model.tex == null || !LensGlass.inPrimaryTree(d.modelInstance, g.root)) {
                continue;
            }
            float[] b = g.boxes.get(template(d.modelInstance));
            if (b == null) {
                continue;
            }
            if (parts == null) {
                parts = new ArrayList<>();
                boxes = new ArrayList<>();
            }
            parts.add(d);
            boxes.add(b);
        }
        if (parts == null) {
            return;
        }
        // The full save stays (as Model.DrawChar's own per character): startCharacter binds textures, mesh.Draw sets
        // vertex arrays, cull() and the blend equation are not in GLStateRenderThread - only a query could restore them.
        RealGL.glPushClientAttrib(-1);
        RealGL.glPushAttrib(0xFFFFF);
        boolean camera = false;
        highCount = 0;
        try {
            shaderIso();
            if (!iso.getShaderProgram().isCompiled()) {
                throw new IllegalStateException("tw_muzzle_heat did not compile");
            }
            Model.CharacterModelCameraBegin(s);
            camera = true;
            // Over the part's own visible surface: its depth, nothing written; added light, the target's alpha kept.
            RealGL.glEnable(RealGL.GL_DEPTH_TEST);
            RealGL.glDepthFunc(RealGL.GL_LEQUAL);
            RealGL.glDepthMask(false);
            RealGL.glEnable(RealGL.GL_BLEND);
            RealGL.glBlendEquation(RealGL.GL_FUNC_ADD);
            RealGL.glBlendFunc(RealGL.GL_ONE, RealGL.GL_ONE);
            RealGL.glDisable(RealGL.GL_ALPHA_TEST);
            RealGL.glColorMask(true, true, true, false);
            int p = SpriteRenderer.instance.getRenderingPlayerIndex();
            boolean scope = p >= 0 && p < 4 && seeded[p];
            // The surface under the glow to the screen grade's black point first (see the class): not under a scope.
            boolean lift = !scope && gradeActive();
            for (int i = 0; i < parts.size(); i++) {
                ModelInstanceRenderData d = parts.get(i);
                cull(d.modelInstance);
                iso.Start();
                try {
                    iso.startCharacter(s, d);
                    iso.setScale(s.finalScale);
                    ShaderProgram program = iso.getShaderProgram();
                    boxes(program, boxes.get(i), g.level);
                    program.setValue("MeshFrame", meshFrame(d.model.mesh));
                    program.setValue("HighResDepthMultiplier", highResOnce(d.model));
                    program.setValue("DepthPull", DEPTH_PULL);
                    if (lift) {
                        RealGL.glBlendEquation(RealGL.GL_MAX);
                        program.setValue("Lift", BLACK_POINT);
                        d.model.mesh.Draw(iso);
                        RealGL.glBlendEquation(RealGL.GL_FUNC_ADD);
                        program.setValue("Lift", 0f);
                        lifts++;
                    }
                    d.model.mesh.Draw(iso);
                    iso.setScale(1f);
                } finally {
                    iso.End();
                }
                isoDraws++;
            }
            // A scope of this player sees this weapon: its hot parts alone into the scope's field buffer too.
            Map<IsoGameCharacter, Float> shares = scope ? scopeShares[p] : null;
            Float share = shares == null ? null : shares.get(s.character);
            TextureFBO field = share == null ? null : MuzzleFx.fieldFbo;
            if (field != null) {
                int[] bound = ModelHeat.bindings();
                try {
                    RealGL.bindDrawFramebuffer(field.getBufferId());
                    // the field buffer has no depth: the parts as they lie, their light times their share of the field
                    RealGL.glDisable(RealGL.GL_DEPTH_TEST);
                    for (int i = 0; i < parts.size(); i++) {
                        ModelInstanceRenderData d = parts.get(i);
                        cull(d.modelInstance);
                        iso.Start();
                        try {
                            iso.startCharacter(s, d);
                            iso.setScale(s.finalScale);
                            ShaderProgram program = iso.getShaderProgram();
                            boxes(program, boxes.get(i), g.level);
                            program.setValue("MeshFrame", meshFrame(d.model.mesh));
                            program.setValue("HighResDepthMultiplier", highResOnce(d.model));
                            program.setValue("Alpha", s.alpha * share);
                            program.setValue("Lift", 0f);
                            d.model.mesh.Draw(iso);
                            iso.setScale(1f);
                        } finally {
                            iso.End();
                        }
                        scopeDraws++;
                    }
                } finally {
                    ModelHeat.restore(bound);
                }
            }
        } catch (Throwable t) {
            isoFailed = true;
            fail("isometric", t);
        } finally {
            if (camera) {
                Model.CharacterModelCameraEnd();
            }
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
        }
    }

    /** ViewpointPass: a frame with a hot weapon somewhere to draw (an otherwise empty batch is drawn for it). */
    static boolean vpPending() {
        return vpSurface() && !byRoot.isEmpty();
    }

    /**
     * Render thread, inside ViewpointPass.submit (its state saved and set: blending on, no depth test or write,
     * no culling, colour only; the frame's depth on unit 1, 0: none): the hot weapons' records of this frame's
     * scene, additive, with the frame's matrix (vpm: jittered on the HDR path) and depth linearisation.
     */
    static void vp(Object scene, Matrix4f vpm, Matrix4f projection, int depth, float sx, float sy, int[] viewport, float gain) {
        if (!MuzzleFx.heatOn) return;
        Map<ModelInstance, Glow> hot = byRoot;
        if (hot.isEmpty() || !vpSurface() || scene == null) {
            return;
        }
        // Performance pass 23.10.2026: the vertex array is read when ours is bound (else nothing to restore), the
        // record fields are resolved once (ViewpointPipBridge resolves them once per session) and the layout
        // values are ModelDraws' static final constants.
        int previousVao = -1;
        try {
            if (!ViewpointPipBridge.detect()) {
                throw new IllegalStateException("Viewpoint's model records are unavailable");
            }
            if (recordModels == null) {
                recordStride = (int) ViewpointPipBridge.get("ModelDraws.VALUES", null);
                recordColour = (int) ViewpointPipBridge.get("ModelDraws.COLOUR", null);
                recordStand = (int) ViewpointPipBridge.get("ModelDraws.STAND", null);
                recordUpright = (int) ViewpointPipBridge.get("ModelDraws.UPRIGHT", null);
                recordPlaced = (int) ViewpointPipBridge.get("ModelDraws.PLACED", null);
                recordInstances = ViewpointPipBridge.field("ModelDraws.instances");
                recordMeshes = ViewpointPipBridge.field("ModelDraws.meshes");
                recordValues = ViewpointPipBridge.field("ModelDraws.values");
                recordFlags = ViewpointPipBridge.field("ModelDraws.flags");
                recordCount = ViewpointPipBridge.field("ModelDraws.count");
                recordRelief = ViewpointPipBridge.field("SceneData.relief");
                recordModels = ViewpointPipBridge.field("SceneData.models");
            }
            Object data = VpBlend.records(scene, recordModels.get(scene));
            ModelInstance[] instances = (ModelInstance[]) recordInstances.get(data);
            ModelMesh[] meshes = (ModelMesh[]) recordMeshes.get(data);
            float[] values = (float[]) recordValues.get(data);
            int[] flags = (int[]) recordFlags.get(data);
            int count = recordCount.getInt(data);
            int stride = recordStride, colour = recordColour, stand = recordStand, upright = recordUpright, placed = recordPlaced;
            if (stride < 16 || stand < 16 || stand + 4 > stride) {
                throw new IllegalStateException("Viewpoint's model record layout changed");
            }
            Object lift = recordRelief.get(scene);
            ModelHeatViewpoint.Relief relief = lift == null ? null
                    : (x, z) -> (float) ViewpointPipBridge.call("FarRelief$Lift.sceneAt", lift, x, z);
            float[] l = LIFT;
            boolean begun = false;
            for (int i = 0; i < count; i++) {
                ModelInstance instance = instances[i];
                ModelMesh mesh = meshes[i];
                // The colour records only: a shadow-only record of the same mesh would add the glow twice.
                if (instance == null || mesh == null || mesh.vb == null || !mesh.vb.isStatic || (flags[i] & colour) == 0) {
                    continue;
                }
                Glow g = owner(instance, hot);
                float[] boxes = g == null ? null : g.boxes.get(template(instance));
                if (boxes == null) {
                    continue;
                }
                if (!begun) {
                    shaderVp();
                    if (!vp.getShaderProgram().isCompiled()) {
                        throw new IllegalStateException("tw_vp_muzzle_heat did not compile");
                    }
                    if (vao == 0) {
                        vao = GL30.glGenVertexArrays();
                    }
                    // The meshes' attribute bindings stay in our own vertex array, not Viewpoint's.
                    previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
                    GL30.glBindVertexArray(vao);
                    GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
                    begun = true;
                }
                int s = i * stride + stand, mode = (int) values[s + 3];
                boolean standing = mode == upright, fixed = mode == placed;
                float x = standing ? values[s] : values[i * stride + 12], z = standing ? values[s + 2] : values[i * stride + 14];
                try {
                    ModelHeatViewpoint.lift(relief, x, z, standing, fixed, l);
                } catch (Throwable t) {
                    // Drawn where its record stands, as the thermal mask does without the relief.
                    l[0] = l[1] = l[2] = 0f;
                    l[3] = 1f;
                }
                vp.Start();
                try {
                    ShaderProgram program = vp.getShaderProgram();
                    program.setValue("ViewProjection", vpm);
                    program.setValue("Model", MODEL.set(values, i * stride));
                    program.setValue("MeshFrame", meshFrame(mesh));
                    program.setVector4("Lift", l[0], l[1], l[2], l[3]);
                    program.setVector2("LiftAt", x, z);
                    program.setVector4("Viewport", viewport[0], viewport[1], viewport[2], viewport[3]);
                    program.setVector2("DepthScale", sx, sy);
                    program.setVector2("DepthLinear", projection.m32(), projection.m22());
                    program.setVector4("Tolerance", TOLERANCE, TOLERANCE_PER_METRE, depth != 0 ? 1f : 0f, 0f);
                    program.setValue("Gain", gain);
                    program.setSamplerUnit("SceneDepth", 1);
                    boxes(program, boxes, g.level);
                    mesh.vb.Draw(vp);
                } finally {
                    vp.End();
                }
                vpDraws++;
            }
        } catch (Throwable t) {
            vpFailed = true;
            fail("Viewpoint", t);
        } finally {
            if (previousVao >= 0) {
                GL30.glBindVertexArray(previousVao);
            }
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
        }
    }

    // Render thread (vp): Viewpoint's record fields and layout constants, resolved once; the lift of a record.
    private static java.lang.reflect.Field recordModels, recordInstances, recordMeshes, recordValues, recordFlags, recordCount, recordRelief;
    private static int recordStride, recordColour, recordStand, recordUpright, recordPlaced;
    private static final float[] LIFT = new float[4];

    /**
     * QA: "heatIso= heatVp= heatScope= heatSeeds= heatLift= heatGrade= heatPublished= heatSurface= heatIsoFailed=
     * heatVpFailed= heatErrors= heatHot=" (heatGrade: the screen grade the night lift is for is on).
     */
    static String stats() {
        return "heatIso=" + isoDraws + " heatVp=" + vpDraws + " heatScope=" + scopeDraws + " heatSeeds=" + seeds + " heatLift=" + lifts
                + " heatGrade=" + (gradeActive() ? 1 : 0) + " heatPublished=" + published + " heatSurface=" + (surface ? 1 : 0)
                + " heatIsoFailed=" + (isoFailed ? 1 : 0) + " heatVpFailed=" + (vpFailed ? 1 : 0) + " heatErrors=" + errors
                + " heatHot=" + byCharacter.size();
    }

    /** Debug QA: the A/B of the heat on the model against the lit-zone fallback. */
    @me.zed_0xff.zombie_buddy.Exposer.LuaClass(name = "TWMuzzleHeatQA")
    public static final class QA {
        private QA() {
        }

        /** true: the heat on the weapon's surface (production); false: the lit-zone fallback in both renderers. The state after. */
        public static boolean surface(boolean on) {
            if (zombie.Lua.LuaManager.GlobalObject.getDebug()) {
                MuzzleHeatModel.surface = on;
            }
            return MuzzleHeatModel.surface;
        }

        /** Both renderers' failures cleared (they try the surface again); false outside debug. */
        public static boolean reset() {
            if (!zombie.Lua.LuaManager.GlobalObject.getDebug()) {
                return false;
            }
            isoFailed = vpFailed = false;
            lastError = "";
            return true;
        }

        public static String stats() {
            return MuzzleHeatModel.stats() + (lastError.isEmpty() ? "" : " heatLastError=" + lastError);
        }
    }
}
