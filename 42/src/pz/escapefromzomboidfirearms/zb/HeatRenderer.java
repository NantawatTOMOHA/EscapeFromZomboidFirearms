package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayDeque;

import org.escapefromzomboidfirearms.realgl.RealGL;

import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.core.Core;
import zombie.core.opengl.MatrixStack;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.VBORenderer;
import zombie.core.textures.Texture;
import zombie.core.skinnedmodel.DeadBodyAtlas;
import zombie.core.textures.TextureFBO;
import zombie.iso.IsoCamera;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.Vector2;
import zombie.iso.weather.ClimateManager;

/**
 * The heat buffer of the thermal image: red = thermal input / 4 of warm things
 * at each world-frame texel, the hottest wins (GL_MAX). Warm things are set
 * from Lua (TWZB.setHeat): characters by object (their pose and position are
 * read live every frame) and fixed heat sources by position. Drawn as heat
 * silhouettes; ModelHeat adds the real animated model of each warm character
 * when it renders (VehicleHeat the model of each vehicle), and a corpse is its
 * own image from the game's corpse atlas (DeadBodyAtlas: the pose the player
 * sees), in the colour of its temperature. Shots and explosions add their
 * heat through MuzzleFx.heat.
 */
public final class HeatRenderer {
    private static final int GL_QUADS = 7;
    /** Texture index of a quad drawn with its own texture (Frame.custom). */
    private static final int CUSTOM = 3;
    /** Texture index of a fire quad (procedural, tw_heat_fire). */
    private static final int FIRE = 4;
    /** Floats per quad: 4 x (x, y, u, v), input / SCALE, seed, part (tw_heat_fire). */
    private static final int STRIDE = 19;
    /** Inputs above this are clamped: the buffer stores input / SCALE. */
    public static final float SCALE = 4f;
    private static final float LEVEL = 2.44949f;
    private static final float HEIGHT = 1.75f / LEVEL;

    /** Warm things per local player, set from Lua on the main thread. */
    static final Object[][] objects = new Object[4][];
    static final float[][] values = new float[4][];

    private static final ArrayDeque<Frame> pool = new ArrayDeque<>();
    private static Texture body, prone, halo;
    // render thread
    private static TextureFBO heat;
    private static ShaderProgram program, fireProgram;

    /** Render thread: the heat programs, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (program == null) {
            program = ShaderWarmup.made(ShaderProgram.createShaderProgram("tw_heat", false, false, true));
            fireProgram = ShaderWarmup.made(ShaderProgram.createShaderProgram("tw_heat_fire", false, false, true));
        }
    }
    private static final long START = System.nanoTime();

    public static volatile long quads;
    /** Corpses drawn as their atlas image (QA). */
    public static volatile long corpses;
    /** Fires drawn as flames, hot gas and burning floor (QA: their flame quads). */
    public static volatile long fires;
    /** QA: corpses in view (main thread), of them with an atlas image, and atlas images not ready at drawing. */
    public static volatile long corpsesSeen, corpsesAtlas, corpsesNotReady;

    private HeatRenderer() {
    }

    /** Quads of one frame in world units of each player's view. */
    static final class Frame {
        final float[][] data = new float[4][];
        final int[] count = new int[4];
        final int[] textures = new int[4 * 512];
        final Object[] owners = new Object[4 * 512];
        /** The texture of a quad of its own (texture index CUSTOM): a corpse atlas image. */
        final Texture[] custom = new Texture[4 * 512];

        void add(int p, int texture, float[] q) {
            add(p, texture, q, null);
        }

        void add(int p, int texture, float[] q, Object owner) {
            add(p, texture, q, owner, null);
        }

        void add(int p, int texture, float[] q, Object owner, Texture own) {
            float[] d = data[p];
            int n = count[p];
            if (d == null || (n + 1) * STRIDE > d.length) {
                float[] grown = new float[Math.max(STRIDE * 64, (d == null ? 0 : d.length) * 2)];
                if (d != null) {
                    System.arraycopy(d, 0, grown, 0, n * STRIDE);
                }
                data[p] = d = grown;
            }
            if (n >= 512) {
                return;
            }
            System.arraycopy(q, 0, d, n * STRIDE, STRIDE);
            textures[p * 512 + n] = texture;
            owners[p * 512 + n] = owner;
            custom[p * 512 + n] = own;
            count[p] = n + 1;
        }
    }

    /** Lua: list of {obj=character, input=value} or {x=,y=,z=,input=,radius=}. */
    public static void set(int player, KahluaTable list) {
        if (player < 0 || player > 3) {
            return;
        }
        if (list == null) {
            objects[player] = null;
            values[player] = null;
            ModelHeat.publish(player, null, null);
            return;
        }
        int n = list.len();
        Object[] o = new Object[n];
        float[] v = new float[n * 5];
        for (int i = 0; i < n; i++) {
            Object e = list.rawget(i + 1);
            if (!(e instanceof KahluaTable t)) {
                continue;
            }
            Object obj = t.rawget("obj");
            o[i] = obj instanceof IsoGameCharacter ? obj : null;
            v[i * 5] = OpticState.num(t, "input", 1f);
            v[i * 5 + 1] = OpticState.num(t, "x", 0f);
            v[i * 5 + 2] = OpticState.num(t, "y", 0f);
            v[i * 5 + 3] = OpticState.num(t, "z", 0f);
            v[i * 5 + 4] = OpticState.num(t, "radius", 0.6f);
        }
        objects[player] = o;
        values[player] = v;
        ModelHeat.publish(player, o, v);
    }

    private static boolean call(IsoGameCharacter c, int which) {
        try {
            return switch (which) {
                case 0 -> c.isProne() || c.isKnockedDown() || c.isOnFloor() || c instanceof zombie.characters.IsoZombie z && z.isCrawling();
                case 1 -> c.isSitOnGround();
                default -> c.isSneaking();
            };
        } catch (Throwable t) {
            return false;
        }
    }

    /** Main thread: the heat quads of this frame from live positions. */
    static Frame capture(OpticRenderer.Slot[] slots) {
        Frame f;
        synchronized (pool) {
            f = pool.isEmpty() ? new Frame() : pool.pop();
        }
        if (body == null) {
            body = OpticRenderer.texture("TW_HeatBody", 32);
            prone = OpticRenderer.texture("TW_HeatBodyProne", 32);
            halo = OpticRenderer.texture("TW_HeatHalo", 32);
        }
        float ts = Core.tileScale;
        float[] q = new float[STRIDE];
        // The way the wind blows (WorldFlares drift by (sin, cos) of its angle), 0..1.
        ClimateManager climate = ClimateManager.getInstance();
        float wind = climate == null ? 0f : climate.getWindIntensity();
        float windAngle = climate == null ? 0f : climate.getWindAngleRadians();
        float windX = (float) Math.sin(windAngle) * wind, windY = (float) Math.cos(windAngle) * wind;
        Vector2 dir = new Vector2();
        HeatSources.refresh(slots, System.currentTimeMillis(), 60f);
        VehicleHeat.publish(slots);
        for (int p = 0; p < 4; p++) {
            f.count[p] = 0;
            OpticState.View view = slots[p].view;
            Object[] o = objects[p];
            float[] v = values[p];
            if (view == null || view.kind != OpticState.THERMAL) {
                continue;
            }
            float offX = IsoCamera.getOffX(p), offY = IsoCamera.getOffY(p);
            for (int i = 0; o != null && v != null && i < o.length; i++) {
                float input = v[i * 5];
                if (o[i] instanceof IsoGameCharacter c) {
                    if (c.isDead()) {
                        continue;
                    }
                    float x = c.getX(), y = c.getY(), z = c.getZ();
                    c.getForwardDirection(dir);
                    float fx = dir.x, fy = dir.y;
                    if (c instanceof zombie.characters.animals.IsoAnimal a) {
                        float size = Math.max(0.4f, Math.min(2f, a.getAnimalSize()));
                        blob(f, p, q, x, y, z, 0.55f * size, input, ts, offX, offY);
                    } else if (call(c, 0)) {
                        lying(f, p, q, x, y, z, fx, fy, input, ts, offX, offY, c);
                    } else {
                        float height = HEIGHT * (call(c, 1) ? 0.55f : call(c, 2) ? 0.8f : 1f);
                        standing(f, p, q, x, y, z, fx, fy, height, input, ts, offX, offY, c);
                    }
                } else {
                    blob(f, p, q, v[i * 5 + 1], v[i * 5 + 2], v[i * 5 + 3], v[i * 5 + 4], input, ts, offX, offY);
                }
            }
            float[] src = HeatSources.sources[p];
            Object[] own = HeatSources.owners[p];
            for (int i = 0; src != null && i < HeatSources.counts[p]; i++) {
                int k = i * 7;
                Object owner = own[i];
                if (src[k + 5] >= 2f) {
                    // A burning character carries its flames: its position now, not at the last refresh.
                    float fx = src[k], fy = src[k + 1], fz = src[k + 2];
                    if (owner instanceof IsoGameCharacter burning) {
                        fx = burning.getX();
                        fy = burning.getY();
                        fz = burning.getZ();
                    }
                    fire(f, p, q, fx, fy, fz, src[k + 3], src[k + 4], src[k + 6] > 0f, src[k + 5] == 3f, windX, windY, ts, offX, offY);
                } else if (src[k + 5] == 1f) {
                    // a corpse: its atlas image, else flat on the floor along its angle
                    corpsesSeen++;
                    if (!(owner instanceof IsoDeadBody body) || !corpse(f, p, q, body, src[k + 4], ts, offX, offY)) {
                        lying(f, p, q, src[k], src[k + 1], src[k + 2], (float) Math.cos(src[k + 6]), (float) Math.sin(src[k + 6]),
                                src[k + 4], ts, offX, offY, null);
                    }
                } else {
                    blob(f, p, q, src[k], src[k + 1], src[k + 2], src[k + 3], src[k + 4], ts, offX, offY, owner);
                }
            }
            // Shots and explosions: flash, hot gas, sparks, barrel smoke, fireball (MuzzleFx).
            MuzzleFx.heat(f, p, q, view, ts, offX, offY);
        }
        return f;
    }

    /** A round heat quad (the halo texture) centred at (cx, cy) in view units, rx by ry. */
    static void glow(Frame f, int p, float[] q, float cx, float cy, float rx, float ry, float input) {
        if (input <= 0f || rx <= 0f || ry <= 0f) {
            return;
        }
        corners(q, cx - rx, cy - ry, cx + rx, cy - ry, cx + rx, cy + ry, cx - rx, cy + ry, input);
        f.add(p, 2, q);
    }

    /** A round heat quad flat on the floor, r tiles around (x, y) at level z. */
    static void glowFlat(Frame f, int p, float[] q, float x, float y, float z, float r, float input, float ts, float offX, float offY) {
        if (input <= 0f) {
            return;
        }
        float cz = z + 0.01f;
        corners(q, sx(x - r, y - r, ts, offX), sy(x - r, y - r, cz, ts, offY), sx(x + r, y - r, ts, offX), sy(x + r, y - r, cz, ts, offY),
                sx(x + r, y + r, ts, offX), sy(x + r, y + r, cz, ts, offY), sx(x - r, y + r, ts, offX), sy(x - r, y + r, cz, ts, offY), input);
        f.add(p, 2, q);
    }

    static void release(Frame f) {
        if (f == null) {
            return;
        }
        java.util.Arrays.fill(f.custom, null);
        java.util.Arrays.fill(f.owners, null);
        synchronized (pool) {
            pool.push(f);
        }
    }

    // World units of the view (IsoUtils.X/YToScreen minus the camera offset).
    private static float sx(float x, float y, float ts, float offX) {
        return (x - y) * 32f * ts - offX;
    }

    private static float sy(float x, float y, float z, float ts, float offY) {
        return (x + y) * 16f * ts - z * 96f * ts - offY;
    }

    private static void corners(float[] q, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, float value) {
        q[0] = x0; q[1] = y0; q[2] = 0; q[3] = 0;
        q[4] = x1; q[5] = y1; q[6] = 1; q[7] = 0;
        q[8] = x2; q[9] = y2; q[10] = 1; q[11] = 1;
        q[12] = x3; q[13] = y3; q[14] = 0; q[15] = 1;
        q[16] = Math.min(1f, value / SCALE);
        q[17] = 0f;
        q[18] = 0f;
    }

    /** A metre across the screen at (x, y, z), in view units. */
    private static float metre(float x, float y, float z, float ts, float offX, float offY) {
        float bx = sx(x, y, ts, offX), by = sy(x, y, z, ts, offY);
        return (float) Math.hypot(sx(x + 0.70711f, y - 0.70711f, ts, offX) - bx, sy(x + 0.70711f, y - 0.70711f, z, ts, offY) - by);
    }

    /**
     * A fire as a thermal camera sees it (tw_heat_fire): flame tongues standing
     * on the burning square, the hot gas above them leaning with the wind (in
     * the open) or stopped and spread under the ceiling (under a roof), and the
     * burning patch and the warm ground around the base. size 0..1 from the
     * fire's light; a burning character (body) carries its flames from the feet
     * to above the head. Shapes are ours: Tarkov has no fires in its thermal
     * image data, the game's fire is a sprite.
     */
    private static void fire(Frame f, int p, float[] q, float x, float y, float z, float size, float input, boolean ceiling, boolean body,
                             float windX, float windY, float ts, float offX, float offY) {
        float seed = (float) (((x * 0.1373 + y * 0.3119 + z * 0.71) % 1.0 + 1.0) % 1.0);
        float m = metre(x, y, z, ts, offX, offY);
        float room = 2.3f;
        float flame = body ? 2.1f : 1.0f + 1.6f * size;
        float gas = body ? 3.0f : 2.5f + 2.5f * size;
        if (ceiling) {
            flame = Math.min(flame, room * 0.85f);
            gas = room;
        }
        float flameW = (body ? 0.8f : 0.9f + 0.5f * size) * m;
        float gasW = (ceiling ? 2.6f + 2.0f * size : body ? 1.4f : 1.6f + 1.6f * size) * m;
        // Lean of the top in tiles: flames a little, the gas column more (none under a ceiling).
        float lean = ceiling ? 0f : 0.8f;
        upright(f, p, q, x, y, z, x + windX * 0.35f * flame, y + windY * 0.35f * flame, z + flame / LEVEL, flameW, input, seed, 0f, ts, offX, offY);
        fires++;
        float gz = z + 0.35f * flame / LEVEL;
        upright(f, p, q, x, y, gz, x + windX * lean * gas, y + windY * lean * gas, z + gas / LEVEL, gasW, input * 0.5f, seed,
                ceiling ? 0.5f : 0.25f, ts, offX, offY);
        if (!body) {
            floor(f, p, q, x, y, z, 0.5f + 0.3f * size, input * 0.9f, seed, 0.75f, ts, offX, offY);
            floor(f, p, q, x, y, z, 1.3f + 0.8f * size, input * 0.35f, seed, 1f, ts, offX, offY);
        }
    }

    /** A fire quad standing from (bx, by, bz) to (tx, ty, tz), w wide on the screen (a parallelogram). */
    private static void upright(Frame f, int p, float[] q, float bx, float by, float bz, float tx, float ty, float tz, float w,
                                float input, float seed, float part, float ts, float offX, float offY) {
        float x0 = sx(bx, by, ts, offX), y0 = sy(bx, by, bz, ts, offY);
        float x1 = sx(tx, ty, ts, offX), y1 = sy(tx, ty, tz, ts, offY);
        corners(q, x1 - w / 2, y1, x1 + w / 2, y1, x0 + w / 2, y0, x0 - w / 2, y0, input);
        q[17] = seed;
        q[18] = part;
        f.add(p, FIRE, q);
    }

    /** A fire quad flat on the floor, r tiles around (x, y). */
    private static void floor(Frame f, int p, float[] q, float x, float y, float z, float r, float input, float seed, float part,
                              float ts, float offX, float offY) {
        float cz = z + 0.01f;
        corners(q, sx(x - r, y - r, ts, offX), sy(x - r, y - r, cz, ts, offY), sx(x + r, y - r, ts, offX), sy(x + r, y - r, cz, ts, offY),
                sx(x + r, y + r, ts, offX), sy(x + r, y + r, cz, ts, offY), sx(x - r, y + r, ts, offX), sy(x - r, y + r, cz, ts, offY), input);
        q[17] = seed;
        q[18] = part;
        f.add(p, FIRE, q);
    }

    /** A standing body: a billboard from the feet to the head, shoulders across the screen. */
    private static void standing(Frame f, int p, float[] q, float x, float y, float z, float fx, float fy, float height,
                                 float input, float ts, float offX, float offY, Object owner) {
        float bx = sx(x, y, ts, offX), by = sy(x, y, z, ts, offY);
        float ty = sy(x, y, z + height, ts, offY);
        float metre = (float) Math.hypot(sx(x + 0.70711f, y - 0.70711f, ts, offX) - bx, sy(x + 0.70711f, y - 0.70711f, z, ts, offY) - by);
        float facing = Math.abs((fx + fy) * 0.70711f);
        float w = 0.875f * metre * (0.6f + 0.4f * facing);
        float h = by - ty;
        corners(q, bx - w / 2, ty, bx + w / 2, ty, bx + w / 2, by, bx - w / 2, by, input);
        f.add(p, 0, q, owner);
    }

    /** A lying body flat on the floor, head along the facing. */
    private static void lying(Frame f, int p, float[] q, float x, float y, float z, float fx, float fy, float input,
                              float ts, float offX, float offY, Object owner) {
        float L = 1.75f * 0.5f, W = 0.875f * 0.5f, nx = -fy, ny = fx, cz = z + 0.02f;
        float ax = x - fx * L - nx * W, ay = y - fy * L - ny * W;
        float bx = x + fx * L - nx * W, by = y + fy * L - ny * W;
        float cx = x + fx * L + nx * W, cy = y + fy * L + ny * W;
        float dx = x - fx * L + nx * W, dy = y - fy * L + ny * W;
        corners(q, sx(ax, ay, ts, offX), sy(ax, ay, cz, ts, offY), sx(bx, by, ts, offX), sy(bx, by, cz, ts, offY),
                sx(cx, cy, ts, offX), sy(cx, cy, cz, ts, offY), sx(dx, dy, ts, offX), sy(dx, dy, cz, ts, offY), input);
        f.add(p, 1, q, owner);
    }

    /** A round heat source (fire, stove, engine; animals) of the given radius in tiles. */
    private static void blob(Frame f, int p, float[] q, float x, float y, float z, float radius, float input,
                             float ts, float offX, float offY) {
        blob(f, p, q, x, y, z, radius, input, ts, offX, offY, null);
    }

    /** As above; skipped when its owner (a vehicle) was drawn as a model this frame. */
    private static void blob(Frame f, int p, float[] q, float x, float y, float z, float radius, float input,
                             float ts, float offX, float offY, Object owner) {
        float cx = sx(x, y, ts, offX), cy = sy(x, y, z + 0.15f, ts, offY);
        float r = radius * 32f * ts * 1.41421f;
        corners(q, cx - r, cy - r * 0.7f, cx + r, cy - r * 0.7f, cx + r, cy + r * 0.5f, cx - r, cy + r * 0.5f, input);
        f.add(p, 2, q, owner);
    }

    /**
     * A corpse as the game draws it: its image in the corpse atlas
     * (IsoDeadBody.render -> DeadBodyAtlas.BodyTexture: centred on the body's
     * screen point less the entry's offset), its alpha the silhouette.
     */
    private static boolean corpse(Frame f, int p, float[] q, IsoDeadBody body, float input, float ts, float offX, float offY) {
        DeadBodyAtlas.BodyTexture atlas = body.getAtlasTexture();
        Texture tex = atlas == null ? null : atlas.getTexture();
        if (tex == null) {
            return false;
        }
        float x = body.getX(), y = body.getY(), z = body.getZ();
        float left = atlas.getRenderX(sx(x, y, ts, offX)), top = atlas.getRenderY(sy(x, y, z, ts, offY));
        float w = atlas.getRenderWidth(), h = atlas.getRenderHeight();
        corners(q, left, top, left + w, top, left + w, top + h, left, top + h, input);
        float u0 = tex.getXStart(), v0 = tex.getYStart(), u1 = tex.getXEnd(), v1 = tex.getYEnd();
        q[2] = u0;
        q[3] = v0;
        q[6] = u1;
        q[7] = v0;
        q[10] = u1;
        q[11] = v1;
        q[14] = u0;
        q[15] = v1;
        f.add(p, CUSTOM, q, null, tex);
        corpsesAtlas++;
        return true;
    }

    /** Render thread: the heat buffer (world-frame size, own depth for the model silhouettes). */
    public static TextureFBO ensure(int w, int h) {
        if (heat == null || heat.getWidth() != w || heat.getHeight() != h) {
            if (heat != null) {
                heat.destroy();
            }
            heat = new TextureFBO(new Texture(w, h, 16), true);
            ModelHeat.prepared = false;
        }
        return heat;
    }

    static TextureFBO buffer() {
        return heat;
    }

    /**
     * Render thread, before the thermal pass: the heat silhouettes of warm
     * characters not drawn as models this frame, and the heat sources. If the
     * depth copy did not run this frame, the buffer is cleared first.
     */
    static void render(Frame f, OpticRenderer.Slot[] slots, int whw, int hhw, MatrixStack projection, MatrixStack modelView) {
        ensure(whw, hhw);
        shaders();
        int[] b = ModelHeat.bindings();
        heat.startDrawing(!ModelHeat.prepared, true);
        if (f != null && program.isCompiled()) {
            RealGL.glDisable(RealGL.GL_DEPTH_TEST);
            RealGL.glEnable(RealGL.GL_BLEND);
            RealGL.glBlendEquation(RealGL.GL_MAX);
            RealGL.glBlendFunc(RealGL.GL_ONE, RealGL.GL_ONE);
            VBORenderer vbo = VBORenderer.getInstance();
            Texture[] textures = {body, prone, halo};
            for (int p = 0; p < 4; p++) {
                OpticRenderer.Slot s = slots[p];
                int n = f.count[p];
                if (s.view == null || n == 0) {
                    continue;
                }
                RealGL.glViewport(s.vx, s.vy, s.w, s.h);
                projection.push(projection.alloc().setOrtho2D(0, s.w * s.zoom, s.h * s.zoom, 0));
                modelView.push(modelView.alloc().identity());
                float[] d = f.data[p];
                for (int t = 0; t < 3; t++) {
                    Texture tex = textures[t];
                    if (tex == null || !tex.isReady()) {
                        continue;
                    }
                    vbo.startRun(vbo.formatPositionColorUv);
                    vbo.setMode(GL_QUADS);
                    vbo.setDepthTest(false);
                    vbo.setTextureID(tex.getTextureId());
                    vbo.setShaderProgram(program);
                    int added = 0;
                    for (int i = 0; i < n; i++) {
                        if (f.textures[p * 512 + i] != t) {
                            continue;
                        }
                        Object owner = f.owners[p * 512 + i];
                        if (owner != null && ModelHeat.isModelled(owner)) {
                            continue;
                        }
                        int k = i * STRIDE;
                        vbo.addQuad(d[k], d[k + 1], d[k + 2], d[k + 3], d[k + 4], d[k + 5], d[k + 6], d[k + 7],
                                d[k + 8], d[k + 9], d[k + 10], d[k + 11], d[k + 12], d[k + 13], d[k + 14], d[k + 15],
                                0, d[k + 16], 0, 0, 1);
                        added++;
                    }
                    vbo.endRun();
                    if (added > 0) {
                        vbo.flush();
                        quads += added;
                    }
                }
                // Fires: procedural, animated by Time; the halo stands in as the bound texture.
                if (fireProgram.isCompiled() && halo != null && halo.isReady()) {
                    fireProgram.Start();
                    fireProgram.setValue("Time", (float) ((System.nanoTime() - START) / 1e9 % 1000.0));
                    fireProgram.End();
                    vbo.startRun(vbo.formatPositionColorUv);
                    vbo.setMode(GL_QUADS);
                    vbo.setDepthTest(false);
                    vbo.setTextureID(halo.getTextureId());
                    vbo.setShaderProgram(fireProgram);
                    int added = 0;
                    for (int i = 0; i < n; i++) {
                        if (f.textures[p * 512 + i] != FIRE) {
                            continue;
                        }
                        int k = i * STRIDE;
                        vbo.addQuad(d[k], d[k + 1], d[k + 2], d[k + 3], d[k + 4], d[k + 5], d[k + 6], d[k + 7],
                                d[k + 8], d[k + 9], d[k + 10], d[k + 11], d[k + 12], d[k + 13], d[k + 14], d[k + 15],
                                0, d[k + 16], d[k + 17], d[k + 18], 1);
                        added++;
                    }
                    vbo.endRun();
                    if (added > 0) {
                        vbo.flush();
                        quads += added;
                    }
                }
                // Quads with their own texture (corpse atlas images), one run each.
                for (int i = 0; i < n; i++) {
                    Texture tex = f.textures[p * 512 + i] == CUSTOM ? f.custom[p * 512 + i] : null;
                    if (tex == null) {
                        continue;
                    }
                    if (!tex.isReady()) {
                        corpsesNotReady++;
                        continue;
                    }
                    int k = i * STRIDE;
                    vbo.startRun(vbo.formatPositionColorUv);
                    vbo.setMode(GL_QUADS);
                    vbo.setDepthTest(false);
                    vbo.setTextureID(tex.getTextureId());
                    vbo.setShaderProgram(program);
                    vbo.addQuad(d[k], d[k + 1], d[k + 2], d[k + 3], d[k + 4], d[k + 5], d[k + 6], d[k + 7],
                            d[k + 8], d[k + 9], d[k + 10], d[k + 11], d[k + 12], d[k + 13], d[k + 14], d[k + 15],
                            0, d[k + 16], 0, 0, 1);
                    vbo.endRun();
                    vbo.flush();
                    quads++;
                    corpses++;
                }
                projection.pop();
                modelView.pop();
            }
            RealGL.glBlendEquation(RealGL.GL_FUNC_ADD);
        }
        heat.endDrawing();
        ModelHeat.restore(b);
    }

    /** The heat buffer texture (render thread), or null before the first frame. */
    static Texture texture() {
        return heat == null ? null : (Texture) heat.getTexture();
    }
}
