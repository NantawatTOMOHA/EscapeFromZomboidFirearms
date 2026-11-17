package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.escapefromzomboidfirearms.realgl.RealGL;

import zombie.GameTime;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.ShaderProgram;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureFBO;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;
import zombie.iso.Vector2;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;

/**
 * Vehicles in the thermal image as their own models: every vehicle the game
 * renders while it is in a player's thermal view is drawn again into the heat
 * buffer (tw_heat_vehicle, depth-tested like the characters), with a heat field
 * over the model: the engine bay, the warmth it carries along the body, the
 * exhaust at the rear and the tyres. A cold vehicle is drawn too: it hides the
 * heat behind it, as metal and glass do.
 * Each part warms towards its running temperature while the engine runs (the
 * tyres with speed) and cools after it stops, exponentially on game time
 * (e-folding times below, game hours); the state of a vehicle is kept while
 * the session lasts (by its database id), so one parked a while ago is still
 * warm when seen again. A vehicle first seen with its engine running is taken
 * as warm, one seen stopped as cold.
 * Not Tarkov values (Tarkov has no vehicles): inputs on the scale of the
 * bodies (TW/OpticView: living 2, zombie 1.4; fire 3.5).
 */
public final class VehicleHeat {
    public static final float ENGINE = 2.4f, EXHAUST = 3.0f, TYRES = 1.4f, BODY = 1.0f;
    static final float ENGINE_WARM = 0.2f, ENGINE_COOL = 1.2f;
    static final float EXHAUST_WARM = 0.05f, EXHAUST_COOL = 0.35f;
    static final float TYRES_WARM = 0.15f, TYRES_COOL = 0.4f;
    /** Tyres reach their running temperature at this speed (km/h). */
    static final float TYRE_SPEED = 60f;
    /** Below this warmth everywhere a vehicle is cold (and its state is dropped). */
    static final float WARM = 0.02f;
    /** Heights above the ground (m): engine bay centre and exhaust. */
    static final float ENGINE_HEIGHT = 0.8f, EXHAUST_HEIGHT = 0.3f;
    static final float LEVEL = 2.44949f;
    static final int PARAMS = 32;
    /** Performance pass 23.10.2026: the wheel uniforms' names, built once (concatenated per drawn vehicle every frame). */
    private static final String[] WHEELS = {"Wheels[0]", "Wheels[1]", "Wheels[2]", "Wheels[3]"};

    static final class State {
        double hours = Double.NaN;
        float engine, exhaust, tyres;
    }

    private static final HashMap<Integer, State> bySql = new HashMap<>();
    private static final WeakHashMap<BaseVehicle, State> byObject = new WeakHashMap<>();
    /** Vehicles in each player's thermal view (refreshed with the heat sources). */
    static final ArrayList<ArrayList<BaseVehicle>> selected = new ArrayList<>();
    /** Each player's vehicles to draw this frame -> heat field (main thread publishes). */
    @SuppressWarnings("unchecked")
    static volatile Map<Object, float[]>[] views = new Map[4];

    // render thread
    static Shader skinned, rigid;

    /** Render thread: the vehicle heat shaders, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (skinned == null) {
            skinned = ShaderWarmup.made(new Shader("tw_heat_vehicle", false, false));
            rigid = ShaderWarmup.made(new Shader("tw_heat_vehicle", true, false));
        }
    }
    public static volatile long drawn;
    public static volatile int errors;
    public static volatile String lastError = "";
    private static final Vector4f clip = new Vector4f();

    static {
        for (int i = 0; i < 4; i++) {
            selected.add(new ArrayList<>());
        }
    }

    private VehicleHeat() {
    }

    // --- warmth on game time (main thread) ---------------------------------------------

    static State state(BaseVehicle v) {
        int id = v.getSqlId();
        if (id > 0) {
            return bySql.computeIfAbsent(id, k -> new State());
        }
        return byObject.computeIfAbsent(v, k -> new State());
    }

    static float relax(float value, float target, float tau, double hours) {
        return (float) (target + (value - target) * Math.exp(-hours / tau));
    }

    /** Main thread: every loaded vehicle's warmth up to the world age `hours`. */
    static void update(IsoCell cell, double hours) {
        for (BaseVehicle v : new ArrayList<>(cell.getVehicles())) {
            if (v == null || v.getScript() == null) {
                continue;
            }
            State s = state(v);
            boolean running = v.isEngineRunning();
            float tyres = Math.min(1f, Math.abs(v.getCurrentSpeedKmHour()) / TYRE_SPEED);
            double dt = hours - s.hours;
            if (Double.isNaN(s.hours) || dt < 0) {
                // First seen, or the clock was set back (a debug jump): its present state.
                s.engine = running ? 1f : 0f;
                s.exhaust = s.engine;
                s.tyres = tyres;
            } else if (dt > 0) {
                s.engine = relax(s.engine, running ? 1f : 0f, running ? ENGINE_WARM : ENGINE_COOL, dt);
                s.exhaust = relax(s.exhaust, running ? 1f : 0f, running ? EXHAUST_WARM : EXHAUST_COOL, dt);
                s.tyres = relax(s.tyres, tyres, tyres > s.tyres ? TYRES_WARM : TYRES_COOL, dt);
            }
            s.hours = hours;
        }
        // Long cold vehicles need no state: seen again they start cold anyway.
        for (Iterator<State> it = bySql.values().iterator(); it.hasNext(); ) {
            State s = it.next();
            if (hours - s.hours > 1 && s.engine < WARM && s.exhaust < WARM && s.tyres < WARM) {
                it.remove();
            }
        }
    }

    private static long ticked;

    /** Main thread, every frame (OpticRenderer.queue): the warmth of all loaded vehicles, 4 times a second. */
    static void tick() {
        long now = System.currentTimeMillis();
        if (now - ticked < 250) {
            return;
        }
        ticked = now;
        try {
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
            if (cell != null && GameTime.getInstance() != null) {
                update(cell, GameTime.getInstance().getWorldAgeHours());
            }
        } catch (Throwable t) {
            fail(t);
        }
    }

    static float warmth(BaseVehicle v) {
        State s = state(v);
        return Math.max(s.engine, Math.max(s.exhaust, s.tyres));
    }

    /** Main thread, with the heat sources: the vehicles of a thermal view. */
    static void select(int p, OpticState.View view, IsoCell cell, float range) {
        ArrayList<BaseVehicle> list = selected.get(p);
        list.clear();
        if (view == null || cell == null) {
            return;
        }
        for (BaseVehicle v : new ArrayList<>(cell.getVehicles())) {
            if (v != null && v.getScript() != null && HeatSources.inView(view, v.getX(), v.getY(), v.getZ(), range + 3f)) {
                list.add(v);
            }
        }
    }

    /**
     * Viewpoint's perspective thermal view (VpHeat, main thread): the warm
     * vehicles within range tiles of (x, y). A cold one needs no drawing there:
     * the scene's depth already hides what is behind it.
     */
    static void around(float x, float y, float range, ArrayList<BaseVehicle> out) {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
        if (cell == null) {
            return;
        }
        for (BaseVehicle v : new ArrayList<>(cell.getVehicles())) {
            if (v == null || v.getScript() == null) {
                continue;
            }
            float dx = v.getX() - x, dy = v.getY() - y;
            if (dx * dx + dy * dy <= (range + 3f) * (range + 3f) && warmth(v) > WARM) {
                out.add(v);
            }
        }
    }

    /**
     * The heat field of a vehicle now (world: x, y in tiles, z in levels):
     * engine (x, y, z, radius), exhaust (x, y, z, radius), 4 wheels (x, y, z,
     * radius), axis (forward x, y, length, 0), level (engine, exhaust, tyres,
     * wheel count). Null when the vehicle has no usable geometry.
     */
    static float[] field(BaseVehicle v) {
        VehicleScript script = v.getScript();
        if (script == null) {
            return null;
        }
        State s = state(v);
        float[] out = new float[PARAMS];
        float cx = v.getX(), cy = v.getY();
        float ground = (float) Math.floor(v.getZ() + 0.01f);
        // The body's own axes on the floor: getWorldPos of the local axes.
        Vector3f o = v.getWorldPos(0f, 0f, 0f, new Vector3f());
        Vector3f z = v.getWorldPos(0f, 0f, 1f, new Vector3f());
        float fx = z.x - o.x, fy = z.y - o.y;
        float n = (float) Math.hypot(fx, fy);
        if (!(n > 0.1f) || Math.abs(o.x - cx) > 6 || Math.abs(o.y - cy) > 6) {
            return null; // physics not set up yet (seen: positions far away)
        }
        fx /= n;
        fy /= n;
        float rx = -fy, ry = fx;
        Vector3f extents = script.getExtents();
        Vector3f com = script.getCenterOfMassOffset();
        float length = Math.max(2f, Math.min(14f, extents.z));
        // Front: where the front wheels are (else where the engine is).
        float front = 0;
        for (int i = 0; i < script.getWheelCount(); i++) {
            VehicleScript.Wheel w = script.getWheel(i);
            if (w != null && w.front) {
                front += Math.signum(w.offset.z - com.z);
            }
        }
        Vector2 engine = v.getAreaCenter("Engine");
        float ex, ey, er = 0.9f;
        if (engine != null && Math.abs(engine.x - cx) < 6 && Math.abs(engine.y - cy) < 6) {
            ex = engine.x;
            ey = engine.y;
            for (int i = 0; i < script.getAreaCount(); i++) {
                VehicleScript.Area a = script.getArea(i);
                if (a != null && "Engine".equals(a.id)) {
                    er = Math.max(0.6f, Math.min(1.6f, 0.5f * Math.max(a.w, a.h)));
                }
            }
            if (front == 0) {
                front = Math.signum((ex - cx) * fx + (ey - cy) * fy);
            }
        } else {
            float sign = front == 0 ? 1 : Math.signum(front);
            ex = cx + fx * sign * length * 0.32f;
            ey = cy + fy * sign * length * 0.32f;
        }
        float sign = front == 0 ? 1 : Math.signum(front);
        out[0] = ex;
        out[1] = ey;
        out[2] = ground + ENGINE_HEIGHT / LEVEL;
        out[3] = er;
        // Exhaust: the rear end, low, a little to one side.
        float back = -sign * (length * 0.5f - 0.15f);
        out[4] = cx + fx * back + rx * extents.x * 0.25f;
        out[5] = cy + fy * back + ry * extents.x * 0.25f;
        out[6] = ground + EXHAUST_HEIGHT / LEVEL;
        out[7] = 0.35f;
        int wheels = 0;
        for (int i = 0; i < script.getWheelCount() && wheels < 4; i++) {
            VehicleScript.Wheel w = script.getWheel(i);
            if (w == null) {
                continue;
            }
            float along = w.offset.z - com.z, across = w.offset.x - com.x;
            int k = 8 + wheels * 4;
            out[k] = cx + fx * along + rx * across;
            out[k + 1] = cy + fy * along + ry * across;
            out[k + 2] = ground + Math.max(0.2f, w.radius) / LEVEL;
            out[k + 3] = Math.max(0.3f, w.radius * 1.1f);
            wheels++;
        }
        out[24] = fx * sign;
        out[25] = fy * sign;
        out[26] = length;
        out[28] = s.engine;
        out[29] = s.exhaust;
        out[30] = s.tyres;
        out[31] = wheels;
        return out;
    }

    /** Main thread, every frame: the heat fields of the selected vehicles. */
    static void publish(OpticRenderer.Slot[] slots) {
        @SuppressWarnings("unchecked")
        Map<Object, float[]>[] next = new Map[4];
        for (int p = 0; p < 4; p++) {
            OpticState.View view = slots[p].view;
            ArrayList<BaseVehicle> list = selected.get(p);
            if (view == null || view.kind != OpticState.THERMAL || list.isEmpty()) {
                continue;
            }
            IdentityHashMap<Object, float[]> m = new IdentityHashMap<>();
            for (BaseVehicle v : list) {
                try {
                    float[] f = field(v);
                    if (f != null) {
                        m.put(v, f);
                    }
                } catch (Throwable t) {
                    fail(t);
                }
            }
            next[p] = m.isEmpty() ? null : m;
        }
        views = next;
    }

    /** A vehicle of this player's thermal view (rendered even when the engine hides it in the dark). */
    public static boolean shown(Object v, int p) {
        Map<Object, float[]> m = p >= 0 && p < 4 ? views[p] : null;
        return m != null && v != null && m.containsKey(v);
    }

    public static boolean shownAny(Object v) {
        for (int p = 0; p < 4; p++) {
            if (shown(v, p)) {
                return true;
            }
        }
        return false;
    }

    static boolean any(int p) {
        Map<Object, float[]> m = p >= 0 && p < 4 ? views[p] : null;
        return m != null && !m.isEmpty();
    }

    static void fail(Throwable t) {
        errors++;
        String m = t.toString();
        if (!m.equals(lastError)) {
            lastError = m;
            System.out.println("[TWZB] vehicle heat " + m);
        }
    }

    /** QA: "engine= exhaust= tyres= hours= z= engineAt=x,y front=x,y length=". */
    static String describe(BaseVehicle v) {
        State s = state(v);
        float[] f = field(v);
        String where = f == null ? " field=none" : String.format(java.util.Locale.ROOT, " engineAt=%.2f,%.2f,%.2f front=%.2f,%.2f length=%.2f wheels=%d",
                f[0], f[1], f[2], f[24], f[25], f[26], (int) f[31]);
        return String.format(java.util.Locale.ROOT, "engine=%.3f exhaust=%.3f tyres=%.3f hours=%.3f z=%.3f running=%s%s",
                s.engine, s.exhaust, s.tyres, s.hours, v.getZ(), v.isEngineRunning(), where);
    }

    // --- the model in the heat buffer (render thread) -------------------------------------

    /**
     * World position of a clip-space point of this view: the iso camera is an
     * orthographic map of the world, so the camera placed at four points (its
     * origin and one tile/level along each axis) gives the affine map and its
     * inverse. Null if the camera is not the world one.
     */
    static Matrix4f clipToWorld(ModelCamera cam) {
        if (cam == null || !cam.useWorldIso) {
            return null;
        }
        float ox = cam.x, oy = cam.y, oz = cam.z;
        float[][] c = new float[4][3];
        try {
            for (int i = 0; i < 4; i++) {
                cam.x = ox + (i == 1 ? 1 : 0);
                cam.y = oy + (i == 2 ? 1 : 0);
                cam.z = oz + (i == 3 ? 1 : 0);
                cam.Begin();
                try {
                    Matrix4f mvp = new Matrix4f(Core.getInstance().projectionMatrixStack.peek()).mul(Core.getInstance().modelViewMatrixStack.peek());
                    clip.set(0, 0, 0, 1).mul(mvp);
                    c[i][0] = clip.x / clip.w;
                    c[i][1] = clip.y / clip.w;
                    c[i][2] = clip.z / clip.w;
                } finally {
                    cam.End();
                }
            }
        } finally {
            cam.x = ox;
            cam.y = oy;
            cam.z = oz;
        }
        // clip = c0 + A * (world - o); columns of A: one tile x, one tile y, one level z.
        Matrix4f a = new Matrix4f(
                c[1][0] - c[0][0], c[1][1] - c[0][1], c[1][2] - c[0][2], 0,
                c[2][0] - c[0][0], c[2][1] - c[0][1], c[2][2] - c[0][2], 0,
                c[3][0] - c[0][0], c[3][1] - c[0][1], c[3][2] - c[0][2], 0,
                c[0][0], c[0][1], c[0][2], 1);
        if (Math.abs(a.determinant3x3()) < 1e-12) {
            return null;
        }
        // world = o + A^-1 (clip - c0)
        return new Matrix4f().translation(ox, oy, oz).mul(a.invert());
    }

    private static void uniforms(Shader shader, Matrix4f toWorld, float[] f) {
        ShaderProgram program = shader.getShaderProgram();
        shader.Start();
        // The JOML overload uploads column-major as is (the lwjgl one transposes).
        program.setValue("ClipToWorld", toWorld);
        program.setValue("Keep", 0f);
        program.setVector4("Engine", f[0], f[1], f[2], f[3]);
        program.setVector4("Exhaust", f[4], f[5], f[6], f[7]);
        for (int i = 0; i < 4; i++) {
            program.setVector4(WHEELS[i], f[8 + i * 4], f[9 + i * 4], f[10 + i * 4], f[11 + i * 4]);
        }
        program.setVector4("Axis", f[24], f[25], f[26], 0f);
        program.setVector4("Level", f[28], f[29], f[30], f[31]);
        float k = 1f / HeatRenderer.SCALE;
        program.setVector4("Peaks", ENGINE * k, EXHAUST * k, TYRES * k, BODY * k);
        shader.End();
    }

    /** Render thread, right after the game drew a vehicle: the same model into the heat buffer. */
    public static void draw(ModelSlotRenderData s) {
        if (!ModelHeat.prepared || !(s.object instanceof BaseVehicle vehicle)) {
            return;
        }
        Map<Object, float[]> view = views[Math.max(0, Math.min(3, SpriteRenderer.instance.getRenderingPlayerIndex()))];
        float[] f = view == null ? null : view.get(vehicle);
        TextureFBO heat = HeatRenderer.buffer();
        if (f == null || heat == null || s.IsRenderingToCard()) {
            return;
        }
        shaders();
        if (!skinned.getShaderProgram().isCompiled() || !rigid.getShaderProgram().isCompiled()) {
            return;
        }
        ModelCamera cam = ModelCamera.instance;
        boolean oldDepthMask = cam == null || cam.depthMask;
        int[] b = ModelHeat.bindings();
        RealGL.glPushClientAttrib(-1);
        RealGL.glPushAttrib(0xFFFFF);
        boolean bound = false;
        try {
            Matrix4f toWorld = clipToWorld(cam);
            if (toWorld == null) {
                return;
            }
            uniforms(skinned, toWorld, f);
            uniforms(rigid, toWorld, f);
            heat.startDrawing(false, false);
            bound = true;
            RealGL.glDisable(RealGL.GL_SCISSOR_TEST);
            RealGL.glDisable(RealGL.GL_BLEND);
            cam.depthMask = true;
            float oldAlpha = s.alpha;
            s.alpha = 1f;
            try {
                for (ModelInstanceRenderData d : s.getModelData()) {
                    Model m = d.model;
                    if (m == null) {
                        continue;
                    }
                    Shader old = m.effect;
                    m.effect = m.isStatic ? rigid : skinned;
                    try {
                        RealGL.glDepthMask(true);
                        d.RenderVehicle(s);
                    } finally {
                        m.effect = old;
                    }
                }
            } finally {
                s.alpha = oldAlpha;
            }
            ModelHeat.markModelled(vehicle);
            drawn++;
        } catch (Throwable t) {
            fail(t);
        } finally {
            if (cam != null) {
                cam.depthMask = oldDepthMask;
            }
            if (bound) {
                heat.endDrawing();
            }
            ModelHeat.restore(b);
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
}
