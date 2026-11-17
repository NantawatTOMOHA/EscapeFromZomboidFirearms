package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.core.math.PZMath;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.core.textures.Texture;
import zombie.iso.IsoCell;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoWorld;
import zombie.iso.Vector2;
import zombie.iso.Vector3;
import zombie.iso.weather.ClimateManager;
import zombie.vehicles.BaseVehicle;

/**
 * The heat buffer of a perspective camera in Viewpoint's view (VP_W5_T): the
 * scope camera of a thermal scope, or the frame's own camera for a thermal
 * sight seen at 1x or a head device. Red = thermal input / HeatRenderer.SCALE,
 * the hottest wins (GL_MAX), as the isometric buffer (HeatRenderer), so the
 * thermal image reads both alike. Green (the scope camera only): the veil of the
 * glows out of its focus at the objective, added up (FB5). The warm things are collected on the main
 * thread in the scene space of the frame (as ViewpointPass primitives) and
 * drawn on the render thread against that camera and its depth:
 *   heat volumes  a capsule (a vehicle: its box) round a warm thing; a pixel
 *                 takes its heat when its visible surface, rebuilt from the
 *                 camera's depth, lies inside - the very silhouette of the
 *                 model Viewpoint drew there, hidden by whatever stands in
 *                 front of it (no line of sight test, no isometric cone).
 *                 Characters: capsules along their animated bones; corpses: a
 *                 lying body; stoves, fireplaces, generators: a column;
 *                 vehicles: VehicleHeat's field over their body; the hot parts
 *                 of a weapon (MuzzleFx HotNodes): capsules along the zones;
 *   glows         what has no surface - muzzle jets, hot propellant gas,
 *                 sparks, smoke trails, blasts: camera-facing quads of the
 *                 halo with a soft depth test; fires: the procedural flame and
 *                 gas columns of tw_heat_fire standing up towards the camera,
 *                 and the burning patch and warm ground on the floor.
 * The inputs are those of the isometric image (TW/OpticView, HeatSources,
 * VehicleHeat, MuzzleFx): Tarkov has none of these sources itself.
 */
public final class VpHeat {
    /** Primitive kinds (the shader's too); VEIL: the out-of-focus share of a glow, added up in green. */
    static final int VOLUME = 0, GLOW = 1, FIRE = 2, VEHICLE = 3, VEIL = 4;
    /** Volume: a(3), b(3), radius, input / SCALE, floor and ceiling (scene y): only surfaces between them. */
    static final int VOLUME_STRIDE = 10;
    /** Glow: centre(3), radius, input / SCALE, depth softness. */
    static final int GLOW_STRIDE = 6;
    /** Fire: base(3), top(3) (a floor patch: top = NaN), width or radius, input / SCALE, seed, part (tw_heat_fire). */
    static final int FIRE_STRIDE = 10;
    /**
     * Vehicle (scene space): engine(4), exhaust(4), wheels(16), axis(dx, dz, length, 0), level(4), box centre(3), forward(3),
     * half length, right(3), half width, half height, the ground's y (surfaces just above it are the ground's).
     */
    static final int VEHICLE_STRIDE = 45;
    static final int MAX_VOLUMES = 4096, MAX_GLOWS = 2048, MAX_FIRES = 512;
    /** A body: 1.75 m; a level is 2.449 m (TW/LaserGeometry). */
    static final float BODY = 1.75f, LEVEL = (float) ViewpointBridge.LEVEL;
    /** Surfaces this close above the floor under a body keep the floor's own temperature (scene units). */
    static final float FLOOR_GAP = 0.04f;
    /** Performance pass 23.10.2026: the wheel uniforms' names, built once (concatenated per vehicle run every frame). */
    static final String[] WHEELS = {"Wheels[0]", "Wheels[1]", "Wheels[2]", "Wheels[3]"};

    /**
     * Bones of a PZ character and the capsules between them (radius in metres;
     * an empty second name: the first bone's capsule continues along the bone
     * from the previous name in the row to it, by the length given).
     */
    static final String[] BONES = {"Bip01_Pelvis", "Bip01_Spine1", "Bip01_Neck", "Bip01_Head",
            "Bip01_L_UpperArm", "Bip01_L_Forearm", "Bip01_L_Hand", "Bip01_R_UpperArm", "Bip01_R_Forearm", "Bip01_R_Hand",
            "Bip01_L_Thigh", "Bip01_L_Calf", "Bip01_L_Foot", "Bip01_R_Thigh", "Bip01_R_Calf", "Bip01_R_Foot"};
    /** from, to (indices into BONES), radius; to < 0: from extended by -to/100 m along (from - previous) where previous = the row's third number. */
    static final float[][] SEGMENTS = {
            {0, 1, .18f}, {1, 2, .19f}, {2, 3, .07f}, {3, -20, .12f, 2},
            {2, 4, .08f}, {4, 5, .065f}, {5, 6, .055f}, {6, -12, .05f, 5},
            {2, 7, .08f}, {7, 8, .065f}, {8, 9, .055f}, {9, -12, .05f, 8},
            {0, 10, .13f}, {10, 11, .09f}, {11, 12, .065f}, {12, -14, .055f, 11},
            {0, 13, .13f}, {13, 14, .09f}, {14, 15, .065f}, {15, -14, .055f, 14}};

    /** The warm things of one frame (main thread fills, render thread reads; never changed after close). */
    static final class Frame {
        Object scene;
        final java.util.IdentityHashMap<IsoGameCharacter, Float> modelInputs = new java.util.IdentityHashMap<>();
        float[] v = new float[VOLUME_STRIDE * 128], g = new float[GLOW_STRIDE * 64], fi = new float[FIRE_STRIDE * 16];
        int nVolumes, nGlows, nFires;
        final ArrayList<float[]> vehicles = new ArrayList<>();
        int characters, boned, corpses, fireSources, stoves, muzzle;

        void volume(float[] a, float[] b, float radius, float input, float floor, float ceiling) {
            if (nVolumes >= MAX_VOLUMES || input <= 0f || radius <= 0f) {
                return;
            }
            if ((nVolumes + 1) * VOLUME_STRIDE > v.length) {
                v = java.util.Arrays.copyOf(v, v.length * 2);
            }
            int o = nVolumes++ * VOLUME_STRIDE;
            v[o] = a[0]; v[o + 1] = a[1]; v[o + 2] = a[2];
            v[o + 3] = b[0]; v[o + 4] = b[1]; v[o + 5] = b[2];
            v[o + 6] = radius;
            v[o + 7] = Math.min(1f, input / HeatRenderer.SCALE);
            v[o + 8] = floor;
            v[o + 9] = ceiling;
        }

        void glow(float[] c, float radius, float input, float soft) {
            if (nGlows >= MAX_GLOWS || input <= 0f || !(radius > 0f)) {
                return;
            }
            if ((nGlows + 1) * GLOW_STRIDE > g.length) {
                g = java.util.Arrays.copyOf(g, g.length * 2);
            }
            int o = nGlows++ * GLOW_STRIDE;
            g[o] = c[0]; g[o + 1] = c[1]; g[o + 2] = c[2];
            g[o + 3] = radius;
            g[o + 4] = Math.min(1f, input / HeatRenderer.SCALE);
            g[o + 5] = soft;
        }

        void fire(float[] base, float[] top, float width, float input, float seed, float part) {
            if (nFires >= MAX_FIRES || input <= 0f) {
                return;
            }
            if ((nFires + 1) * FIRE_STRIDE > fi.length) {
                fi = java.util.Arrays.copyOf(fi, fi.length * 2);
            }
            int o = nFires++ * FIRE_STRIDE;
            fi[o] = base[0]; fi[o + 1] = base[1]; fi[o + 2] = base[2];
            fi[o + 3] = top == null ? Float.NaN : top[0]; fi[o + 4] = top == null ? Float.NaN : top[1]; fi[o + 5] = top == null ? Float.NaN : top[2];
            fi[o + 6] = width;
            fi[o + 7] = Math.min(1f, input / HeatRenderer.SCALE);
            fi[o + 8] = seed;
            fi[o + 9] = part;
        }

        int primitives() {
            return nVolumes + nGlows + nFires + vehicles.size() + modelInputs.size();
        }
    }

    // QA counters
    public static volatile long frames, captures, volumesDrawn, glowsDrawn, veilsDrawn, firesDrawn, vehiclesDrawn, errors;
    public static volatile String lastError = "";
    public static volatile double captureMillis;

    private VpHeat() {
    }

    // ------------------------------------------------------------------ main thread

    private static final org.lwjgl.util.vector.Matrix4f BONE = new org.lwjgl.util.vector.Matrix4f();
    private static final Matrix4f W = new Matrix4f();
    private static final Vector3 V = new Vector3(0, 0, 0);

    /**
     * The warm things of the closed batch's frame around the viewer (main
     * thread, ViewpointPass.close, after the muzzle effects moved this frame).
     * living / zombie / animal: the thermal inputs of TW/OpticView; range in tiles.
     */
    // The warm things near the viewer, renewed every HeatSources.REFRESH_MS as the isometric
    // sources (main thread); their positions and poses are read live every frame.
    private static long refreshed;
    private static final ArrayList<IsoGameCharacter> warm = new ArrayList<>();
    private static float[] warmInput = new float[64];
    private static final float[] src = new float[7 * 256];
    private static final Object[] own = new Object[256];
    private static int sourceCount;
    private static final ArrayList<BaseVehicle> vehicles = new ArrayList<>();

    /** A new game: no warm things of the old world are kept. */
    static void reset() {
        refreshed = 0;
        warm.clear();
        vehicles.clear();
        sourceCount = 0;
        java.util.Arrays.fill(own, null);
    }

    static void refresh(IsoCell cell, IsoPlayer viewer, float living, float zombie, float animal, float range) {
        float vx = viewer.getX(), vy = viewer.getY(), vz = viewer.getZ();
        warm.clear();
        for (IsoMovingObject o : new ArrayList<>(cell.getObjectList())) {
            if (!(o instanceof IsoGameCharacter ch) || ch.isDead() || ch != viewer && ch.isInvisible()) {
                continue;
            }
            float input = ch instanceof IsoZombie ? zombie : ch instanceof zombie.characters.animals.IsoAnimal ? animal : living;
            if (input <= 0f || !near(ch.getX(), ch.getY(), ch.getZ(), vx, vy, vz, range)) {
                continue;
            }
            if (warm.size() >= warmInput.length) {
                warmInput = java.util.Arrays.copyOf(warmInput, warmInput.length * 2);
            }
            warmInput[warm.size()] = input;
            warm.add(ch);
        }
        sourceCount = HeatSources.around(vx, vy, vz, range, src, own);
        vehicles.clear();
        VehicleHeat.around(vx, vy, range, vehicles);
    }

    static Frame capture(ViewpointPass.Batch b, IsoPlayer viewer, float living, float zombie, float animal, float range) {
        long start = System.nanoTime();
        Frame f = new Frame();
        f.scene = b.scene;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
        if (cell == null || viewer == null) {
            return f;
        }
        long now = System.currentTimeMillis();
        if (now - refreshed >= HeatSources.REFRESH_MS || now < refreshed) {
            refreshed = now;
            refresh(cell, viewer, living, zombie, animal, range);
        }
        float vx = viewer.getX(), vy = viewer.getY(), vz = viewer.getZ();
        float[] a = new float[3], c = new float[3];
        ModelHeatViewpoint.prune(now);
        for (int i = 0; i < warm.size(); i++) {
            IsoGameCharacter ch = warm.get(i);
            if (ch.isDead()) {
                continue;
            }
            try {
                // The model mask draws the character's own surface; the capsule body stands in where
                // the mask left a record of it out (another mod's model) or is off.
                // Under the Metal shim the mask's model draws (VertexBufferObject.Draw) are on the real context: the capsule stands in.
                if (ModelHeatViewpoint.legacy || GLCompat.metalPath() || ModelHeatViewpoint.capsule(ch, now)) body(f, b, ch, warmInput[i]);
                if (!ModelHeatViewpoint.legacy && !ModelHeatViewpoint.off && !GLCompat.metalPath()) f.modelInputs.put(ch, warmInput[i]);
                f.characters++;
            } catch (Throwable t) {
                fail("body", t);
            }
        }
        int n = sourceCount;
        ClimateManager climate = ClimateManager.getInstance();
        float wind = climate == null ? 0f : climate.getWindIntensity();
        float windAngle = climate == null ? 0f : climate.getWindAngleRadians();
        float windX = (float) Math.sin(windAngle) * wind, windY = (float) Math.cos(windAngle) * wind;
        for (int i = 0; i < n; i++) {
            int k = i * 7;
            float x = src[k], y = src[k + 1], z = src[k + 2], kind = src[k + 5];
            if (kind >= 2f) {
                if (own[i] instanceof IsoGameCharacter burning) {
                    x = burning.getX();
                    y = burning.getY();
                    z = burning.getZ();
                }
                fire(f, b, x, y, z, src[k + 3], src[k + 4], src[k + 6] > 0f, kind == 3f, windX, windY);
                f.fireSources++;
            } else if (kind == 1f) {
                // a corpse: a body lying along its heading, its own floor left out
                float ang = src[k + 6], cx = (float) Math.cos(ang), cy = (float) Math.sin(ang);
                b.scene(x - cx * BODY * .42f, y - cy * BODY * .42f, z + .15f / LEVEL, a, 0);
                b.scene(x + cx * BODY * .42f, y + cy * BODY * .42f, z + .15f / LEVEL, c, 0);
                float floor = a[1] - .15f + FLOOR_GAP;
                f.volume(a, c, .3f, src[k + 4], floor, floor + .8f);
                f.corpses++;
            } else if (own[i] == null) {
                // stove, fireplace, barbecue, generator: a column over its square
                b.scene(x, y, z + .1f / LEVEL, a, 0);
                b.scene(x, y, z + 1f / LEVEL, c, 0);
                f.volume(a, c, .55f, src[k + 4], a[1] - .1f + FLOOR_GAP, c[1] + .6f);
                f.stoves++;
            }
        }
        for (BaseVehicle vehicle : vehicles) {
            try {
                float[] field = VehicleHeat.field(vehicle);
                if (field != null) {
                    f.vehicles.add(vehicle(b, vehicle, field));
                }
            } catch (Throwable t) {
                fail("vehicle", t);
            }
        }
        try {
            muzzle(f, b, vx, vy, vz, range, windX, windY);
        } catch (Throwable t) {
            fail("muzzle", t);
        }
        captures++;
        captureMillis = (System.nanoTime() - start) / 1e6;
        return f;
    }

    static boolean near(float x, float y, float z, float vx, float vy, float vz, float range) {
        float dx = x - vx, dy = y - vy;
        return dx * dx + dy * dy <= range * range && Math.abs(z - vz) <= 3f;
    }

    /** A bone's world position (x, y tiles, z levels) into out; false without it. */
    static boolean bone(AnimationPlayer ap, int index, IsoGameCharacter c, float angle, float[] out) {
        if (index < 0) {
            return false;
        }
        // The drawn pose (ShotContactsPz.drawnBone): the local transforms of a zombie on PZ's
        // shared-skeleton path are stale.
        BONE.load(ShotContactsPz.drawnBone(ap, index));
        PZMath.convertMatrix(BONE, W).transpose();
        V.x = W.m30();
        V.y = W.m31();
        V.z = W.m32();
        Model.vectorToWorldCoords(c.getX(), c.getY(), c.getZ(), angle, V);
        out[0] = V.x;
        out[1] = V.y;
        out[2] = V.z;
        return Float.isFinite(V.x) && Float.isFinite(V.y) && Float.isFinite(V.z);
    }

    /** A living character: capsules along its animated bones, or one standing / lying capsule without them. */
    static void body(Frame f, ViewpointPass.Batch b, IsoGameCharacter c, float input) {
        float x = c.getX(), y = c.getY(), z = c.getZ();
        float[] floorPoint = new float[3];
        b.scene(x, y, z, floorPoint, 0);
        float floor = floorPoint[1] + FLOOR_GAP, ceiling = floorPoint[1] + 2.6f;
        AnimationPlayer ap = c.getAnimationPlayer();
        SkinningData sd = ap == null || !ap.isReady() ? null : ap.getSkinningData();
        if (sd != null) {
            float angle = ap.getRenderedAngle();
            float[][] world = new float[BONES.length][];
            boolean ok = true;
            for (int i = 0; i < BONES.length; i++) {
                float[] w = new float[3];
                if (bone(ap, GripIK.index(sd, BONES[i]), c, angle, w)) {
                    world[i] = w;
                }
            }
            // A pose whose pelvis or head is missing, or far from the character, is not drawn by bones.
            ok = world[0] != null && world[3] != null && Math.abs(world[0][0] - x) < 2f && Math.abs(world[0][1] - y) < 2f;
            if (ok) {
                float[] a = new float[3], e = new float[3];
                for (float[] s : SEGMENTS) {
                    float[] from = world[(int) s[0]];
                    float[] to;
                    if (s[1] >= 0) {
                        to = world[(int) s[1]];
                    } else {
                        float[] previous = world[(int) s[3]];
                        if (from == null || previous == null) {
                            continue;
                        }
                        float dx = from[0] - previous[0], dy = from[1] - previous[1], dz = (from[2] - previous[2]) * LEVEL;
                        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (len < 1e-4f) {
                            continue;
                        }
                        float reach = -s[1] / 100f / len;
                        to = new float[] {from[0] + dx * reach, from[1] + dy * reach, from[2] + dz * reach / LEVEL};
                    }
                    if (from == null || to == null) {
                        continue;
                    }
                    b.scene(from[0], from[1], from[2], a, 0);
                    b.scene(to[0], to[1], to[2], e, 0);
                    f.volume(a, e, s[2], input, floor, ceiling);
                }
                f.boned++;
                return;
            }
        }
        float[] a = new float[3], e = new float[3];
        if (pose(c, 0)) {
            Vector2 dir = new Vector2();
            c.getForwardDirection(dir);
            b.scene(x - dir.x * BODY * .42f, y - dir.y * BODY * .42f, z + .15f / LEVEL, a, 0);
            b.scene(x + dir.x * BODY * .42f, y + dir.y * BODY * .42f, z + .15f / LEVEL, e, 0);
            f.volume(a, e, .3f, input, floor, ceiling);
        } else {
            float height = BODY * (pose(c, 1) ? .55f : pose(c, 2) ? .8f : 1f);
            b.scene(x, y, z + .2f / LEVEL, a, 0);
            b.scene(x, y, z + (height - .2f) / LEVEL, e, 0);
            f.volume(a, e, .32f, input, floor, ceiling);
        }
    }

    /** 0 lying, 1 sitting, 2 crouching (as HeatRenderer: a getter that throws counts as no). */
    static boolean pose(IsoGameCharacter c, int which) {
        try {
            return switch (which) {
                case 0 -> c.isProne() || c.isKnockedDown() || c.isOnFloor() || c instanceof IsoZombie z && z.isCrawling();
                case 1 -> c.isSitOnGround();
                default -> c.isSneaking();
            };
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * A fire (HeatRenderer.fire in perspective): the flame and the hot gas as
     * columns that turn towards the camera, the burning patch and the warm
     * ground as quads on the floor. size 0..1 from its light; ceiling: under a
     * roof; body: a burning character (no floor parts).
     */
    static void fire(Frame f, ViewpointPass.Batch b, float x, float y, float z, float size, float input, boolean ceiling, boolean body,
                     float windX, float windY) {
        float seed = (float) (((x * 0.1373 + y * 0.3119 + z * 0.71) % 1.0 + 1.0) % 1.0);
        float room = 2.3f;
        float flame = body ? 2.1f : 1.0f + 1.6f * size;
        float gas = body ? 3.0f : 2.5f + 2.5f * size;
        if (ceiling) {
            flame = Math.min(flame, room * 0.85f);
            gas = room;
        }
        float flameW = body ? 0.8f : 0.9f + 0.5f * size;
        float gasW = ceiling ? 2.6f + 2.0f * size : body ? 1.4f : 1.6f + 1.6f * size;
        float lean = ceiling ? 0f : 0.8f;
        float[] base = new float[3], top = new float[3];
        b.scene(x, y, z, base, 0);
        b.scene(x + windX * 0.35f * flame, y + windY * 0.35f * flame, z + flame / LEVEL, top, 0);
        f.fire(base, top, flameW, input, seed, 0f);
        float gz = z + 0.35f * flame / LEVEL;
        b.scene(x, y, gz, base, 0);
        b.scene(x + windX * lean * gas, y + windY * lean * gas, z + gas / LEVEL, top, 0);
        f.fire(base, top, gasW, input * 0.5f, seed, ceiling ? 0.5f : 0.25f);
        if (!body) {
            b.scene(x, y, z + 0.012f, base, 0);
            f.fire(base, null, 0.5f + 0.3f * size, input * 0.9f, seed, 0.75f);
            f.fire(base, null, 1.3f + 0.8f * size, input * 0.35f, seed, 1f);
        }
    }

    /** VehicleHeat.field (PZ world) in the frame's scene space, with the body's box. */
    static float[] vehicle(ViewpointPass.Batch b, BaseVehicle v, float[] field) {
        float[] out = new float[VEHICLE_STRIDE], p = new float[3];
        for (int i = 0; i < 6; i++) {
            if (i >= 2 && i - 2 >= field[31]) {
                break;
            }
            int k = i == 0 ? 0 : i == 1 ? 4 : 8 + (i - 2) * 4;
            b.scene(field[k], field[k + 1], field[k + 2], p, 0);
            out[k] = p[0];
            out[k + 1] = p[1];
            out[k + 2] = p[2];
            out[k + 3] = field[k + 3];
        }
        // scene x = camX - x, z = camY - y: the forward axis turns with it
        out[24] = -field[24];
        out[25] = -field[25];
        out[26] = field[26];
        System.arraycopy(field, 28, out, 28, 4);
        Vector3f extents = v.getScript().getExtents();
        float ground = (float) Math.floor(v.getZ() + 0.01f);
        b.scene(v.getX(), v.getY(), ground + extents.y * 0.5f / LEVEL, p, 0);
        out[32] = p[0];
        out[33] = p[1];
        out[34] = p[2];
        float fx = out[24], fz = out[25], n = (float) Math.hypot(fx, fz);
        if (n < 1e-4f) {
            fx = 1f;
            fz = 0f;
            n = 1f;
        }
        out[35] = fx / n;
        out[36] = 0f;
        out[37] = fz / n;
        out[38] = Math.max(1f, extents.z) * 0.5f + 0.25f;
        out[39] = -fz / n;
        out[40] = 0f;
        out[41] = fx / n;
        out[42] = Math.max(0.8f, extents.x) * 0.5f + 0.25f;
        out[43] = Math.max(0.6f, extents.y) * 0.5f + 0.3f;
        b.scene(v.getX(), v.getY(), ground, p, 0);
        out[44] = p[1] + 0.06f;
        return out;
    }

    // Constants of the muzzle effects' heat (private in MuzzleFx: read once, the same values).
    private static final Map<String, Float> muzzleHeat = new HashMap<>();
    private static boolean muzzleRead, muzzleOk;

    static float muzzleConstant(String name) {
        return muzzleHeat.get(name);
    }

    static boolean readMuzzle() {
        if (!muzzleRead) {
            muzzleRead = true;
            try {
                for (String name : new String[] {"JET_HEAT", "PART_HEAT", "TRAIL_HEAT", "FUME_HEAT", "FUME_COOL", "SPARK_HEAT", "BLAST_CORE",
                        "BLAST_GAS", "BLAST_GROUND"}) {
                    Field field = MuzzleFx.class.getDeclaredField(name);
                    field.setAccessible(true);
                    muzzleHeat.put(name, field.getFloat(null));
                }
                muzzleOk = true;
            } catch (Throwable t) {
                fail("muzzle constants", t);
            }
        }
        return muzzleOk;
    }

    /** The heat of shots and explosions (MuzzleFx.heat in perspective, the same live simulation and values). */
    static void muzzle(Frame f, ViewpointPass.Batch b, float vx, float vy, float vz, float range, float windX, float windY) {
        MuzzleFx.Data d = MuzzleFx.data;
        if (d == null || !MuzzleFx.enabled || !readMuzzle()) {
            return;
        }
        float jetHeat = muzzleConstant("JET_HEAT"), partHeat = muzzleConstant("PART_HEAT"), trailHeat = muzzleConstant("TRAIL_HEAT");
        float fumeHeat = muzzleConstant("FUME_HEAT"), fumeCool = muzzleConstant("FUME_COOL"), sparkHeat = muzzleConstant("SPARK_HEAT");
        float blastCore = muzzleConstant("BLAST_CORE"), blastGas = muzzleConstant("BLAST_GAS"), blastGround = muzzleConstant("BLAST_GROUND");
        float[] w = new float[3], p = new float[3], q = new float[3];
        for (var entry : MuzzleFx.shooters.entrySet()) {
            MuzzleFx.Shooter s = entry.getValue();
            IsoGameCharacter c = entry.getKey();
            if (c == null || !near(c.getX(), c.getY(), c.getZ(), vx, vy, vz, range)) {
                continue;
            }
            if (MuzzleFx.jetActive(s) && s.framed && s.setup != null && s.setup.jets && s.setup.mgr != null) {
                float glow = s.setup.mgr.light.eval(MuzzleFx.jetPhase(s));
                for (MuzzleFx.Node n : s.setup.nodes) {
                    if (n.jet == null) {
                        continue;
                    }
                    float[] nm = n.m;
                    float an = (float) Math.sqrt(nm[1] * nm[1] + nm[5] * nm[5] + nm[9] * nm[9]);
                    for (int i = 0; i < n.jet.pos.length; i++) {
                        float along = n.jet.pos[i] * d.scale;
                        MuzzleFx.place(s.frame, nm[3] + nm[1] / an * along, nm[7] + nm[5] / an * along, nm[11] + nm[9] / an * along, w);
                        b.scene(w[0], w[1], w[2] / LEVEL, p, 0);
                        f.glow(p, n.jet.size[i] * s.k, jetHeat * glow, 0.05f);
                        f.muzzle++;
                    }
                }
            }
            if (s.hotSetup != null && s.framed) {
                float heat = 0.5f * MuzzleFx.heatGlow(s.overheat);
                float unit = (float) Math.sqrt(s.frame[3] * s.frame[3] + s.frame[4] * s.frame[4] + s.frame[5] * s.frame[5] * LEVEL * LEVEL);
                for (MuzzleFx.HotNode h : s.hotSetup.hot) {
                    float input = Math.min(1f, 10f * heat * h.spec * d.heatVisible) * partHeat;
                    if (input <= 0.01f) {
                        continue;
                    }
                    // the lit zone of its HeatBounds on its own mesh (it ends at the muzzle, tools/muzzle_fx.py hot_geometry)
                    MuzzleFx.place(s.frame, h.a[0], h.a[1], h.a[2], w);
                    b.scene(w[0], w[1], w[2] / LEVEL, p, 0);
                    MuzzleFx.place(s.frame, h.b[0], h.b[1], h.b[2], w);
                    b.scene(w[0], w[1], w[2] / LEVEL, q, 0);
                    // the part's own surface: a volume round the zone (+1 cm), floors and walls far off
                    f.volume(p, q, h.half * unit + 0.01f, input, -1e9f, 1e9f);
                    f.muzzle++;
                }
            }
            if (s.trails != null) {
                for (MuzzleFx.Trail t : s.trails) {
                    for (int i = 0; i < t.segs.size(); i += 2) {
                        MuzzleFx.Seg v = t.segs.get(i);
                        float r = (t.smoke.strand == null ? 0.02f : t.smoke.strand.width + v.diffusion * v.diffusion * t.smoke.strand.diffusion) * s.k * 2f;
                        b.scene(v.p[0], v.p[1], v.p[2] / LEVEL, p, 0);
                        f.glow(p, r, trailHeat * v.alpha * 4f, 0.1f);
                        f.muzzle++;
                    }
                }
            }
        }
        for (MuzzleFx.Puff e : MuzzleFx.puffs) {
            if (e.age < 0f || !near(e.x, e.y, e.h / LEVEL, vx, vy, vz, range)) {
                continue;
            }
            MuzzleFx.PSystem s = e.sys;
            float n = Math.min(1f, e.age / e.life);
            float input, r;
            if (s == null || s.kind == MuzzleFx.PSystem.SMOKE) {
                input = fumeHeat * (float) Math.exp(-e.age / fumeCool) * Math.min(1f, e.a * (s == null ? 8f : 1f));
                r = e.size * (s == null ? d.fumeSize.eval(n, e.rnd) : s.sizeOL == null ? 1f : s.sizeOL.eval(n, e.rnd)) * 0.5f;
            } else {
                float alpha = e.a;
                if (s.colourOL != null) {
                    float[] col = new float[4];
                    s.colourOL.eval(n, e.rnd, col);
                    alpha *= col[3];
                }
                input = jetHeat * Math.min(1f, alpha);
                r = e.size * (s.sizeOL == null ? 1f : s.sizeOL.eval(n, e.rnd)) * 0.5f;
            }
            if (input < 0.15f) {
                continue;
            }
            b.scene(e.x, e.y, e.h / LEVEL, p, 0);
            f.glow(p, Math.max(r, 0.02f), input, 0.15f);
            f.muzzle++;
        }
        double time = MuzzleFx.time;
        for (MuzzleFx.Spark e : MuzzleFx.sparks) {
            double age = time - e.born;
            if (age <= 0 || age >= e.life) {
                continue;
            }
            MuzzleFx.sparkAt(e, (float) age, w);
            float fl = (float) (age / e.life);
            b.scene(w[0], w[1], w[2] / LEVEL, p, 0);
            f.glow(p, Math.max(e.width * 4f, 0.01f), sparkHeat * 4 * fl * (1 - fl), 0.05f);
            f.muzzle++;
        }
        for (MuzzleFx.Blast bl : MuzzleFx.blasts) {
            float age = (float) (time - bl.born);
            float z = bl.h / LEVEL;
            if (!near(bl.x, bl.y, z, vx, vy, vz, range)) {
                continue;
            }
            float core = 1.6f * (1f - (float) Math.exp(-age / 0.05f));
            float coreHeat = blastCore * (float) Math.exp(-age / 0.35f);
            if (coreHeat > 0.1f) {
                b.scene(bl.x, bl.y, (bl.h + 0.8f) / LEVEL, p, 0);
                f.glow(p, core, coreHeat, 0.5f);
            }
            float gasHeat = blastGas * (float) Math.exp(-age / 1.2f);
            if (gasHeat > 0.1f) {
                float rise = Math.min(4f, 1.2f * age + 0.8f), spread = 1.2f + 0.8f * Math.min(age, 3f);
                b.scene(bl.x + windX * age * 0.3f, bl.y + windY * age * 0.3f, (bl.h + rise) / LEVEL, p, 0);
                f.glow(p, spread, gasHeat, 0.6f);
            }
            float groundHeat = blastGround * (float) Math.exp(-age / 30f);
            if (groundHeat > 0.1f) {
                // the ground where it burst: the floor's surface within 1.4 m
                b.scene(bl.x, bl.y, z, p, 0);
                f.volume(p, p, 1.4f, groundHeat, p[1] - 0.25f, p[1] + 0.25f);
            }
            f.muzzle++;
        }
    }

    /**
     * Render thread, a sensor image's heat buffer: on an error the picture goes on without heat this
     * time (the error is counted and logged once), the sensors stay on.
     */
    static int renderOrEmpty(int slot, Frame f, Matrix4f view, Matrix4f projection, int depth, float depthScaleX, float depthScaleY,
                             int width, int height) {
        try {
            return render(slot, f, view, projection, depth, depthScaleX, depthScaleY, width, height);
        } catch (Throwable t) {
            fail("render", t);
            GL20.glUseProgram(0);
            GL14.glBlendEquation(GL14.GL_FUNC_ADD);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDepthMask(false);
            return 0;
        }
    }

    static void fail(String where, Throwable t) {
        errors++;
        String m = where + ": " + VpSensor.describe(t);
        if (!m.equals(lastError)) {
            lastError = m;
            System.out.println("[TWZB] viewpoint heat " + m);
        }
    }

    // ------------------------------------------------------------------ render thread

    /** One camera's heat buffer; its depth orders the warm model surfaces (ModelHeatViewpoint). */
    static final class Target {
        int fbo, texture, depth, width, height;
    }

    /** 0: the scope camera, 1: the frame's own camera. */
    static final Target[] targets = {new Target(), new Target()};
    /**
     * FB5 item 5: the scope camera's focus distance (scene units, ~2.3 m). A glow nearer is out of its focus: the share
     * sharpness() of its heat is seen sharp (none up to NEAR_GLOW_FROM from the objective), the rest spreads over the
     * picture as a veil - VEIL_GAIN of it, added up in green; tw_vp_sensor_image moves the palette towards its hot end by
     * the veil, at most by its VEIL share. The own shot's gases stay in the picture, warmer things stay seen through them.
     */
    static final float SCOPE_FOCUS = 2.0f, NEAR_GLOW_FROM = 0.2f, VEIL_GAIN = 0.35f;
    /** QA (FB5, A/B in one run): the scope camera's focus distance in use (0: every glow sharp at its full heat, as before), the glows it veiled. */
    static volatile float scopeFocus = SCOPE_FOCUS;
    static long nearGlows;
    static TwProgram program;

    /** Render thread: the sensor heat program, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (program == null) {
            program = ShaderWarmup.made(TwProgram.create("tw_vp_sensor_heat"));
        }
    }
    static int vao, vbo, depthSampler;
    static FloatBuffer buffer;
    /** Floats per vertex: clip(4), uv + kind + input(4), a + radius(4), b + floor(4), ceiling, softness, -, -(4). */
    static final int VERTEX = 20;
    private static final long START = System.nanoTime();
    private static Texture halo;

    /** Main thread, with the capture: the halo of the glows (as HeatRenderer). */
    static void prepare() {
        if (halo == null) {
            halo = OpticRenderer.texture("TW_HeatHalo", 32);
        }
    }

    static void ensure(Target t, int width, int height) {
        if (t.texture == 0) {
            t.texture = GL11.glGenTextures();
            t.fbo = GL30.glGenFramebuffers();
            t.depth = GL30.glGenRenderbuffers();
        }
        if (t.width != width || t.height != height) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, t.texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL, 0);
            GL15.glBindBuffer(org.lwjgl.opengl.GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GLCompat.texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, t.fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, t.texture, 0);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, t.depth);
            GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, GL14.GL_DEPTH_COMPONENT24, width, height);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, 0);
            GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, t.depth);
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("heat buffer incomplete");
            }
            t.width = width;
            t.height = height;
        }
    }

    /** Vertices of one frame for one camera (CPU geometry; no GL). */
    static final class Mesh {
        float[] v = new float[VERTEX * 6 * 64];
        int n;
        final ArrayList<int[]> runs = new ArrayList<>();   // kind, first vertex, count, vehicle index
        int volumes, glows, veils, fires, vehicles;

        float[] next() {
            if ((n + 1) * VERTEX > v.length) {
                v = java.util.Arrays.copyOf(v, v.length * 2);
            }
            n++;
            return v;
        }

        void vertex(float cx, float cy, float cz, float cw, float u, float t, int kind, float input, float[] a, float radius,
                    float[] b, float floor, float ceiling, float soft) {
            float[] out = next();
            int o = (n - 1) * VERTEX;
            out[o] = cx; out[o + 1] = cy; out[o + 2] = cz; out[o + 3] = cw;
            out[o + 4] = u; out[o + 5] = t; out[o + 6] = kind; out[o + 7] = input;
            out[o + 8] = a == null ? 0 : a[0]; out[o + 9] = a == null ? 0 : a[1]; out[o + 10] = a == null ? 0 : a[2]; out[o + 11] = radius;
            out[o + 12] = b == null ? 0 : b[0]; out[o + 13] = b == null ? 0 : b[1]; out[o + 14] = b == null ? 0 : b[2]; out[o + 15] = floor;
            out[o + 16] = ceiling; out[o + 17] = soft; out[o + 18] = 0; out[o + 19] = 0;
        }

        void run(int kind, int first, int vehicle) {
            if (n > first) {
                runs.add(new int[] {kind, first, n - first, vehicle});
            }
        }

        /** The vertices of another mesh after these (its runs are not taken). */
        void append(Mesh o) {
            if ((n + o.n) * VERTEX > v.length) {
                v = java.util.Arrays.copyOf(v, Math.max((n + o.n) * VERTEX, v.length * 2));
            }
            System.arraycopy(o.v, 0, v, n * VERTEX, o.n * VERTEX);
            n += o.n;
        }
    }

    /** The near plane of the projection rectangle tests (scene units, Viewpoint's is 0.05). */
    static final float NEAR = 0.06f;

    /**
     * The screen rectangle (NDC) of a box, as two triangles at clip w = 1; the
     * whole view when some corner is behind the near plane; nothing when the box
     * is wholly behind it or off screen.
     */
    static boolean rectangle(Matrix4f vp, float[] min, float[] max, float[] ndc) {
        float x0 = Float.POSITIVE_INFINITY, y0 = x0, x1 = -x0, y1 = -x0;
        Vector4f c = new Vector4f();
        int behind = 0;
        for (int i = 0; i < 8; i++) {
            c.set((i & 1) == 0 ? min[0] : max[0], (i & 2) == 0 ? min[1] : max[1], (i & 4) == 0 ? min[2] : max[2], 1f).mul(vp);
            if (c.w < NEAR) {
                behind++;
                continue;
            }
            x0 = Math.min(x0, c.x / c.w); x1 = Math.max(x1, c.x / c.w);
            y0 = Math.min(y0, c.y / c.w); y1 = Math.max(y1, c.y / c.w);
        }
        if (behind == 8) {
            return false;
        }
        if (behind > 0) {
            ndc[0] = -1; ndc[1] = -1; ndc[2] = 1; ndc[3] = 1;
            return true;
        }
        if (x1 < -1 || y1 < -1 || x0 > 1 || y0 > 1) {
            return false;
        }
        ndc[0] = Math.max(-1, x0); ndc[1] = Math.max(-1, y0); ndc[2] = Math.min(1, x1); ndc[3] = Math.min(1, y1);
        return true;
    }

    static void quad(Mesh m, float[] r, int kind, float input, float[] a, float radius, float[] b, float floor, float ceiling, float soft) {
        float[][] corner = {{r[0], r[1]}, {r[2], r[1]}, {r[2], r[3]}, {r[0], r[1]}, {r[2], r[3]}, {r[0], r[3]}};
        for (float[] k : corner) {
            m.vertex(k[0], k[1], 0f, 1f, 0f, 0f, kind, input, a, radius, b, floor, ceiling, soft);
        }
    }

    /** The geometry of a frame's warm things for one camera (vp: scene -> clip; eye: the camera in scene space). */
    static Mesh mesh(Frame f, Matrix4f vp, Vector3f eye) {
        return mesh(f, vp, eye, 0f);
    }

    /**
     * FB5 item 5 (20.10.2026: firing through a thermal the picture vanishes, worst in automatic fire): the scope camera
     * rides the shooter's own weapon, and the glows of its own shot (jets, fumes, sparks, trails) a few tens of
     * centimetres ahead of the objective were camera-facing squares filling its narrow field at full heat - the hottest
     * wins (GL_MAX), so the black-hot palettes turned the whole picture black for every shot. A scope focused far away
     * sees such a near source out of focus, spread wide and dim, over the scene rather than instead of it: nearer than
     * focus (scene units) only sharpness() of a glow's heat stays a hot square, the rest is the same square in the VEIL
     * run (added up in green). 0: no focus, every glow sharp.
     */
    static Mesh mesh(Frame f, Matrix4f vp, Vector3f eye, float focus) {
        Mesh m = new Mesh();
        float[] r = new float[4], min = new float[3], max = new float[3], a = new float[3], b = new float[3];
        int first = m.n;
        for (int i = 0; i < f.nVolumes; i++) {
            int o = i * VOLUME_STRIDE;
            float rad = f.v[o + 6];
            for (int k = 0; k < 3; k++) {
                min[k] = Math.min(f.v[o + k], f.v[o + 3 + k]) - rad;
                max[k] = Math.max(f.v[o + k], f.v[o + 3 + k]) + rad;
                a[k] = f.v[o + k];
                b[k] = f.v[o + 3 + k];
            }
            if (rectangle(vp, min, max, r)) {
                quad(m, r, VOLUME, f.v[o + 7], a, rad, b, f.v[o + 8], f.v[o + 9], 0.25f);
                m.volumes++;
            }
        }
        m.run(VOLUME, first, -1);
        // Glows: camera-facing squares, clipped by the GPU, perspective-correct uv; the veil of the out-of-focus ones
        // gathered aside and drawn after them as its own run.
        first = m.n;
        Mesh veil = null;
        Vector3f right = new Vector3f(), up = new Vector3f(), toEye = new Vector3f(), c = new Vector3f();
        Vector4f clip = new Vector4f();
        for (int i = 0; i < f.nGlows; i++) {
            int o = i * GLOW_STRIDE;
            c.set(f.g[o], f.g[o + 1], f.g[o + 2]);
            float rad = f.g[o + 3];
            eye.sub(c, toEye);
            float d = toEye.length();
            if (d < 1e-4f) {
                continue;
            }
            float sharp = sharpness(d, focus), heat = f.g[o + 4] * sharp, spread = f.g[o + 4] * (1 - sharp) * VEIL_GAIN;
            if (sharp < 1) {
                nearGlows++;
                if (veil == null && spread > 0) {
                    veil = new Mesh();
                }
            }
            toEye.div(d);
            right.set(0, 1, 0).cross(toEye);
            if (right.lengthSquared() < 1e-6f) {
                right.set(1, 0, 0);
            }
            right.normalize(rad);
            toEye.cross(right, up).normalize(rad);
            float[][] corners = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, -1, 0, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
            for (float[] k : corners) {
                clip.set(c.x + right.x * k[0] + up.x * k[1], c.y + right.y * k[0] + up.y * k[1], c.z + right.z * k[0] + up.z * k[1], 1f).mul(vp);
                if (sharp > 0) {
                    m.vertex(clip.x, clip.y, clip.z, clip.w, k[2], k[3], GLOW, heat, null, 0f, null, 0f, 0f, f.g[o + 5]);
                }
                if (spread > 0) {
                    veil.vertex(clip.x, clip.y, clip.z, clip.w, k[2], k[3], VEIL, spread, null, 0f, null, 0f, 0f, f.g[o + 5]);
                }
            }
            if (sharp > 0) {
                m.glows++;
            }
            if (spread > 0) {
                m.veils++;
            }
        }
        m.run(GLOW, first, -1);
        if (veil != null) {
            first = m.n;
            m.append(veil);
            m.run(VEIL, first, -1);
        }
        first = m.n;
        for (int i = 0; i < f.nFires; i++) {
            int o = i * FIRE_STRIDE;
            float[] seed = {f.fi[o + 8], f.fi[o + 9], 0f};
            float[][] world;
            if (Float.isNaN(f.fi[o + 3])) {
                // a patch on the floor (scene y up)
                float x = f.fi[o], y = f.fi[o + 1], z = f.fi[o + 2], rad = f.fi[o + 6];
                world = new float[][] {{x - rad, y, z - rad, 0, 0}, {x + rad, y, z - rad, 1, 0}, {x + rad, y, z + rad, 1, 1}, {x - rad, y, z + rad, 0, 1}};
            } else {
                // a column from the base to the top, its face towards the camera; v from the top (0) to the base (1)
                Vector3f base = new Vector3f(f.fi[o], f.fi[o + 1], f.fi[o + 2]), top = new Vector3f(f.fi[o + 3], f.fi[o + 4], f.fi[o + 5]);
                Vector3f axis = new Vector3f(top).sub(base), mid = new Vector3f(base).add(top).mul(0.5f);
                Vector3f side = new Vector3f(axis).cross(eye.sub(mid, toEye));
                if (side.lengthSquared() < 1e-8f) {
                    side.set(1, 0, 0);
                }
                side.normalize(f.fi[o + 6] * 0.5f);
                world = new float[][] {{top.x - side.x, top.y - side.y, top.z - side.z, 0, 0}, {top.x + side.x, top.y + side.y, top.z + side.z, 1, 0},
                        {base.x + side.x, base.y + side.y, base.z + side.z, 1, 1}, {base.x - side.x, base.y - side.y, base.z - side.z, 0, 1}};
            }
            // A floor patch lies 3 cm over the floor: a thin depth softness keeps it on the visible floor only.
            float soft = Float.isNaN(f.fi[o + 3]) ? 0.01f : 0.3f;
            for (int k : new int[] {0, 1, 2, 0, 2, 3}) {
                clip.set(world[k][0], world[k][1], world[k][2], 1f).mul(vp);
                m.vertex(clip.x, clip.y, clip.z, clip.w, world[k][3], world[k][4], FIRE, f.fi[o + 7], seed, 0f, null, 0f, 0f, soft);
            }
            m.fires++;
        }
        m.run(FIRE, first, -1);
        for (int i = 0; i < f.vehicles.size(); i++) {
            float[] v = f.vehicles.get(i);
            float hx = Math.abs(v[35]) * v[38] + Math.abs(v[39]) * v[42], hz = Math.abs(v[37]) * v[38] + Math.abs(v[41]) * v[42];
            min[0] = v[32] - hx; max[0] = v[32] + hx;
            min[1] = v[33] - v[43]; max[1] = v[33] + v[43];
            min[2] = v[34] - hz; max[2] = v[34] + hz;
            if (rectangle(vp, min, max, r)) {
                first = m.n;
                quad(m, r, VEHICLE, 1f, null, 0f, null, 0f, 0f, 0f);
                m.run(VEHICLE, first, i);
                m.vehicles++;
            }
        }
        return m;
    }

    /** The share of a glow d away a scope camera focused at focus (0: none) sees sharp: none up to NEAR_GLOW_FROM, all from focus on. */
    static float sharpness(float d, float focus) {
        if (focus <= 0 || d >= focus) {
            return 1f;
        }
        float x = Math.max(0f, Math.min(1f, (d - NEAR_GLOW_FROM) / (focus - NEAR_GLOW_FROM)));
        return x * x * (3 - 2 * x);
    }

    /**
     * Render thread: the heat buffer of a camera (slot 0 scope, 1 frame) at
     * width x height: view / projection of the camera in the frame's scene
     * space, its depth texture read at depthScale (render size / texture
     * size). Returns its texture (empty without a frame); the caller saved and
     * restores the GL state.
     */
    static int render(int slot, Frame f, Matrix4f view, Matrix4f projection, int depth, float depthScaleX, float depthScaleY,
                      int width, int height) {
        Target t = targets[slot];
        ensure(t, width, height);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, t.fbo);
        GL11.glViewport(0, 0, width, height);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDepthMask(true);
        GL11.glColorMask(true, true, true, true);
        GL11.glClearColor(0f, 0f, 0f, 0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
        GL11.glClearDepth(1.0);
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glDepthMask(false);
        frames++;
        if (f == null || f.primitives() == 0) {
            return t.texture;
        }
        ModelHeatViewpoint.render(f, view, projection, depth, depthScaleX, depthScaleY, width, height);
        Matrix4f vp = new Matrix4f(projection).mul(view);
        Vector3f eye = new Vector3f();
        new Matrix4f(view).invert().getTranslation(eye);
        Mesh m = mesh(f, vp, eye, slot == 0 ? scopeFocus : 0f);
        if (m.n == 0) {
            return t.texture;
        }
        shaders();
        if (!program.isCompiled()) {
            throw new IllegalStateException("tw_vp_sensor_heat did not compile");
        }
        if (vao == 0) {
            vao = GL30.glGenVertexArrays();
            vbo = GL15.glGenBuffers();
            depthSampler = GL33.glGenSamplers();
            GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL33.glSamplerParameteri(depthSampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        }
        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendEquation(GL14.GL_MAX);
        GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE);
        program.Start();
        program.setValue("InvViewProjection", new Matrix4f(vp).invert());
        program.setVector4("Target", width, height, depthScaleX, depthScaleY);
        program.setVector2("DepthLinear", projection.m32(), projection.m22());
        program.setValue("Time", (float) ((System.nanoTime() - START) / 1e9 % 1000.0));
        program.setValue("DepthOn", depth != 0 ? 1f : 0f);
        program.setSamplerUnit("SceneDepth", 1);
        program.setSamplerUnit("Halo", 0);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depth);
        GL33.glBindSampler(1, depthSampler);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        GL33.glBindSampler(0, 0);
        Texture h = halo;
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, h != null && h.isReady() ? h.getID() : 0);
        program.setValue("HaloOn", h != null && h.isReady() ? 1f : 0f);
        if (buffer == null || buffer.capacity() < m.n * VERTEX) {
            buffer = BufferUtils.createFloatBuffer(Math.max(m.n * VERTEX, VERTEX * 1024));
        }
        buffer.clear();
        buffer.put(m.v, 0, m.n * VERTEX).flip();
        GL30.glBindVertexArray(vao);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STREAM_DRAW);
        for (int i = 0; i < 5; i++) {
            GL20.glVertexAttribPointer(i, 4, GL11.GL_FLOAT, false, VERTEX * 4, i * 16L);
            GL20.glEnableVertexAttribArray(i);
        }
        for (int[] run : m.runs) {
            if (run[0] == VEHICLE) {
                float[] v = f.vehicles.get(run[3]);
                program.setVector4("Engine", v[0], v[1], v[2], v[3]);
                program.setVector4("Exhaust", v[4], v[5], v[6], v[7]);
                for (int w = 0; w < 4; w++) {
                    program.setVector4(WHEELS[w], v[8 + w * 4], v[9 + w * 4], v[10 + w * 4], v[11 + w * 4]);
                }
                program.setVector4("Axis", v[24], v[25], v[26], 0f);
                program.setVector4("Level", v[28], v[29], v[30], v[31]);
                float k = 1f / HeatRenderer.SCALE;
                program.setVector4("Peaks", VehicleHeat.ENGINE * k, VehicleHeat.EXHAUST * k, VehicleHeat.TYRES * k, VehicleHeat.BODY * k);
                program.setVector4("BoxCentre", v[32], v[33], v[34], v[43]);
                program.setVector4("BoxForward", v[35], v[36], v[37], v[38]);
                program.setVector4("BoxRight", v[39], v[40], v[41], v[42]);
                program.setValue("BoxGround", v[44]);
                vehiclesDrawn++;
            }
            if (run[0] == VEIL) {
                // the veil adds up, in green only
                GL14.glBlendEquation(GL14.GL_FUNC_ADD);
                GL11.glColorMask(false, true, false, false);
            }
            GL11.glDrawArrays(GL11.GL_TRIANGLES, run[1], run[2]);
            if (run[0] == VEIL) {
                GL14.glBlendEquation(GL14.GL_MAX);
                GL11.glColorMask(true, true, true, true);
            }
        }
        volumesDrawn += m.volumes;
        glowsDrawn += m.glows;
        veilsDrawn += m.veils;
        firesDrawn += m.fires;
        GL14.glBlendEquation(GL14.GL_FUNC_ADD);
        return t.texture;
    }

    /** QA: "frames= captures= volumes= glows= fires= vehicles= captureMs= errors=". */
    static String stats() {
        return "frames=" + frames + " captures=" + captures + " volumes=" + volumesDrawn + " glows=" + glowsDrawn + " fires=" + firesDrawn
                + " nearGlows=" + nearGlows + " veils=" + veilsDrawn + " scopeFocus=" + scopeFocus
                + " modelMask=" + ModelHeatViewpoint.draws + " modelMaskErrors=" + ModelHeatViewpoint.errors
                + " modelSkin=" + ModelHeatViewpoint.lastSkin + " modelClothing=" + ModelHeatViewpoint.lastClothing
                + " sourceClothing=" + ModelHeatViewpoint.sourceClothing + " missingOwner=" + ModelHeatViewpoint.missingOwners
                + " animationOwner=" + ModelHeatViewpoint.animationOwners
                + " depthOnly=" + ModelHeatViewpoint.depthOnly
                + " modelUpright=" + ModelHeatViewpoint.lastUpright + " modelPlaced=" + ModelHeatViewpoint.lastPlaced
                + " modelEachVertex=" + ModelHeatViewpoint.lastEachVertex
                + String.format(java.util.Locale.ROOT, " modelLiftMax=%.3f", ModelHeatViewpoint.lastLiftMax)
                + " reliefErrors=" + ModelHeatViewpoint.reliefErrors
                + " modelSkipped=" + ModelHeatViewpoint.skipped + " capsules=" + ModelHeatViewpoint.capsules.size()
                + " modelMaskOff=" + ModelHeatViewpoint.off
                + (ModelHeatViewpoint.lastSkip.isEmpty() ? "" : " lastSkip=" + ModelHeatViewpoint.lastSkip)
                + (ModelHeatViewpoint.offReason.isEmpty() ? "" : " offReason=" + ModelHeatViewpoint.offReason)
                + " vehicles=" + vehiclesDrawn + String.format(java.util.Locale.ROOT, " captureMs=%.3f", captureMillis) + " errors=" + errors
                + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }
}
