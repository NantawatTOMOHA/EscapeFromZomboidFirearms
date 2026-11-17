package pz.escapefromzomboidfirearms.zb;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import org.escapefromzomboidfirearms.realgl.RealGL;

import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.ShaderHelper;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.MatrixStack;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.VBORenderer;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureFBO;
import zombie.iso.IsoCamera;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoLightSource;
import zombie.iso.IsoWorld;
import zombie.iso.weather.ClimateManager;

/**
 * Tarkov muzzle effects of a shot, in place of the vanilla muzzle flash of a
 * Tarkov weapon (EffectsManager.startMuzzleFlash, see PatchMuzzle). The values
 * are the Tarkov prefab data (TW/Data/MuzzleFx, tools/muzzle_fx.py); which
 * nodes of an assembly play is chosen in Lua (TW/MuzzleFx, Tarkov's
 * MuzzleManager.UpdateJetsAndFumes) and set per item (setup). Per shot, as
 * MuzzleManager.Shot:
 *   jets    the flash: Custom/Additive (disassembled) - camera-facing quads of
 *           the 2x2 atlas along the bore, cut by a window that runs along the
 *           jet over ShotLength (MoveCurve, JetLightCurve), additive
 *   fumes   smoke puffs of the shared Fume system (Particles/Smoke Distorted:
 *           lit by the scene, noise-distorted texture, alpha blend; size and
 *           colour over lifetime, speed limit dampen 0.2 per 30 Hz frame)
 *   sparks  streaks of the closed-form trajectory of Custom/SimpleParticleMoveAdd
 *   smoke   the trail from the bore after a series of shots (MuzzleSmoke at a
 *           fixed 60 Hz step; Custom/MuzzleSmoke ribbon)
 *   light   a temporary light with Tarkov's MuzzleLight colour and range, only
 *           when the weapon has jets (a suppressor: none); in Viewpoint's view
 *           a point light of its frame with Tarkov's curve (VpLights)
 * A launcher setup (underbarrel mode) plays MuzzleManager.LauncherShot: its
 * fumes only. Drawn into the world frame after IsoWorld.render (as the mod's
 * lasers), so the night vision image (made from the world frame) shows them;
 * the thermal image gets their heat (heat, HeatRenderer). Explosions of the
 * Gunworks ExplosionFX (TW/MuzzleFx) get the Tarkov explosion light and heat.
 *
 * PZ adaptations: positions are in tiles (1 tile = 1 m, height in tiles), a
 * Tarkov metre is scale * |model axis| tiles so effects keep the size of the
 * weapon model (the mod's model scale); no depth test (drawn over the world,
 * like the lasers) - a shooter the viewer does not see is faded by its alpha;
 * smoke is lit by the light of its square (PZ light, as every world sprite);
 * the smoke drifts with the PZ wind (Tarkov: WindZone external forces).
 */
public final class MuzzleFx {
    private static final int GL_QUADS = 7;
    private static final float LEVEL = 2.44949f;
    /** Screen view units per tile across the screen at tile scale 1 (IsoUtils: 32 x sqrt 2). */
    private static final float PX = 45.254834f;
    /** MuzzleSmoke runs at this fixed step (its Slerp/drag factors are per frame). */
    private static final float SMOKE_STEP = 1f / 60f;
    /** Light life of the vanilla muzzle flash (LightingJNI: frames at the locked rate). */
    private static final int LIGHT_LIFE = 6;
    /** Smoke drift at full wind, tiles per second (own choice; Tarkov's wind zones are per map). */
    private static final float WIND_DRIFT = 2.0f;
    /** Fume heat in the thermal image: hot gas that cools (own choice, Tarkov has no thermal fume). */
    private static final float FUME_HEAT = 3.2f, FUME_COOL = 0.15f;
    private static final float JET_HEAT = 4f, SPARK_HEAT = 4f, TRAIL_HEAT = 1.2f;
    /** Thermal input of a part at Tarkov's thermal cap (_Temperature.y = 1, a body's): a living body (TW/OpticView LIVING). */
    private static final float PART_HEAT = 2f;
    /** Overheat below which a weapon's parts neither glow nor warm visibly (both stay under one step of 8 bits). */
    static final float HOT_MIN = 5f;
    /** Explosion heat: fireball, rising hot gas, warm ground (own choice; see docs/REPORT_RU.md). */
    private static final float BLAST_CORE = 4f, BLAST_GAS = 2.6f, BLAST_GROUND = 1.3f;
    static final float BLAST_LIGHT_SECONDS = 0.5f;
    private static final int MAX_PUFFS = 600, MAX_SPARKS = 400;
    /**
     * The user's choice (23.10.2026): at most 200 smoke puffs alive (the shared Fume system and the smoke kind of the
     * pivot systems; Tarkov's Fume caps 100). The flash's additive and streak particles keep the shared pool, so a long
     * burst's smoke never takes the next shot's flash. Only a long burst reaches it: its cloud stops thickening there.
     */
    static final int MAX_SMOKE = 200;
    /** QA (A/B in one run): the smoke limit in force (MAX_SMOKE; 600 - the shared pool alone, as before). */
    static volatile int smokeLimit = MAX_SMOKE;
    /** Main thread: the smoke puffs in puffs (smoke(e)). */
    static int smokePuffs;
    /**
     * The user's option "Smoke of shots" (bug wave 324, 24.10.2026; TW_MuzzleFx through TWZB.muzzleSmoke): off, a shot
     * emits no smoke - no fume puffs, no smoke kind of the pivot systems, no barrel smoke trail - and so no hot gas in
     * the thermal image. The flash, sparks, light and barrel heat stay; smoke already in the air fades as usual.
     */
    static volatile boolean smokeOn = true;

    /** Local viewer options; disabling a category stops its creation and simulation for every shooter. */
    static volatile boolean flashOn = true, sparksOn = true, lightOn = true, heatOn = true;

    public static void effects(boolean flash, boolean spark, boolean light, boolean heat) {
        flashOn = flash;
        sparksOn = spark;
        lightOn = light;
        heatOn = heat;
        puffs.removeIf(e -> e.sys != null && e.sys.kind != PSystem.SMOKE && !particleOn(e.sys.kind));
        if (!spark) sparks.clear();
        if (!light) VpLights.clearShotLights();
        if (!heat) heats.clear();
        for (Shooter shooter : shooters.values()) {
            if (!flash) shooter.shotEnd = -1;
            if (!heat) { shooter.overheat = 0; shooter.hotSetup = null; }
        }
        if (!heat) MuzzleHeatModel.publish(shooters);
    }

    static boolean particleOn(int kind) {
        return kind == PSystem.SMOKE ? smokeOn : kind == PSystem.STREAK ? sparksOn : flashOn;
    }

    /** Viewpoint's own flash light must obey the same choice in either mod load order. */
    public static boolean suppressLight(IsoGameCharacter character) {
        return !lightOn && character != null && setups.containsKey(character.getPrimaryHandItem());
    }

    public static volatile boolean enabled = true;
    // QA counters
    public static volatile long shots, vanillaShots, launcherShots, framesMissing, jetsDrawn, puffsEmitted, sparksEmitted,
            trailsStarted, lights, explosions, quadsDrawn, heatQuads, drawers, errors, fieldQuads, fieldPasses, heatShots, hotQuads, hotHeatQuads,
            vpSkipped;
    public static volatile String lastError = "";
    public static volatile float lastScale;
    /** QA: world-frame view position of the last drawn jet quad and of its shooter's feet (IsoUtils units). */
    public static volatile float jetViewX, jetViewY, shooterViewX, shooterViewY;
    public static volatile long pivotParticles;
    /** QA: read back a pixel under the first drawn quad before and after drawing (probeResult). */
    public static volatile boolean qaProbe;
    public static volatile String probeResult = "";
    static String blendState = "", QA_STATE = "";
    public static volatile long vpFrames, vpQuads, vpBuildNanos, vpMaxBuildNanos;

    private MuzzleFx() {
    }

    // ------------------------------------------------------------------ data

    static float num(Object v, float def) {
        return v instanceof Double d ? d.floatValue() : def;
    }

    static float num(KahluaTable t, String key, float def) {
        return t == null ? def : num(t.rawget(key), def);
    }

    static KahluaTable tab(KahluaTable t, String key) {
        Object v = t == null ? null : t.rawget(key);
        return v instanceof KahluaTable k ? k : null;
    }

    static KahluaTable tab(KahluaTable t, int index) {
        Object v = t == null ? null : t.rawget(index);
        return v instanceof KahluaTable k ? k : null;
    }

    static float[] floats(KahluaTable t) {
        int n = t == null ? 0 : t.len();
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = num(t.rawget(i + 1), 0f);
        }
        return out;
    }

    /** Unity AnimationCurve: Hermite keys (time, value, in, out tangents), clamped outside. */
    static final class Curve {
        final float[] t, v, in, out;

        Curve(KahluaTable keys) {
            int n = keys == null ? 0 : keys.len();
            t = new float[n];
            v = new float[n];
            in = new float[n];
            out = new float[n];
            for (int i = 0; i < n; i++) {
                float[] k = floats(tab(keys, i + 1));
                t[i] = k[0];
                v[i] = k[1];
                in[i] = k[2];
                out[i] = k[3];
            }
        }

        float eval(float x) {
            int n = t.length;
            if (n == 0) {
                return 1f;
            }
            if (x <= t[0]) {
                return v[0];
            }
            if (x >= t[n - 1]) {
                return v[n - 1];
            }
            for (int i = 0; i < n - 1; i++) {
                if (x <= t[i + 1]) {
                    float d = t[i + 1] - t[i];
                    if (d <= 0) {
                        return v[i + 1];
                    }
                    float s = (x - t[i]) / d, s2 = s * s, s3 = s2 * s;
                    float o = out[i], ii = in[i + 1];
                    if (Float.isInfinite(o) || Float.isInfinite(ii)) {
                        return v[i];
                    }
                    return (2 * s3 - 3 * s2 + 1) * v[i] + (s3 - 2 * s2 + s) * d * o + (-2 * s3 + 3 * s2) * v[i + 1] + (s3 - s2) * d * ii;
                }
            }
            return v[n - 1];
        }

        float duration() {
            return t.length == 0 ? 0f : t[t.length - 1];
        }
    }

    /** Unity Gradient (blend or fixed keys). */
    static final class Gradient {
        final float[] ct, at, a;
        final float[][] c;
        final boolean fixed;

        Gradient(KahluaTable g) {
            KahluaTable colours = tab(g, "colours"), alphas = tab(g, "alphas");
            int nc = colours == null ? 0 : colours.len(), na = alphas == null ? 0 : alphas.len();
            ct = new float[nc];
            c = new float[nc][];
            for (int i = 0; i < nc; i++) {
                KahluaTable k = tab(colours, i + 1);
                ct[i] = num(k.rawget(1), 0f);
                c[i] = floats(tab(k, 2));
            }
            at = new float[na];
            a = new float[na];
            for (int i = 0; i < na; i++) {
                KahluaTable k = tab(alphas, i + 1);
                at[i] = num(k.rawget(1), 0f);
                a[i] = num(k.rawget(2), 1f);
            }
            fixed = Boolean.TRUE.equals(g == null ? null : g.rawget("fixed"));
        }

        // Performance pass 23.10.2026: the key index and the blend of a time as two passes over the keys (a handful),
        // no int[1] per evaluation; the same index and the same arithmetic as the former key(times, x, lo).
        private int lo(float[] times, float x) {
            int n = times.length;
            if (n == 0 || x <= times[0]) {
                return 0;
            }
            for (int i = 0; i < n - 1; i++) {
                if (x <= times[i + 1]) {
                    return fixed ? i + 1 : i;
                }
            }
            return n - 1;
        }

        private float blend(float[] times, float x) {
            int n = times.length;
            if (n == 0 || x <= times[0]) {
                return 0f;
            }
            for (int i = 0; i < n - 1; i++) {
                if (x <= times[i + 1]) {
                    if (fixed) {
                        return 0f;
                    }
                    float d = times[i + 1] - times[i];
                    return d <= 0 ? 1f : (x - times[i]) / d;
                }
            }
            return 0f;
        }

        void eval(float x, float[] rgba) {
            if (c.length == 0) {
                rgba[0] = rgba[1] = rgba[2] = 1f;
            } else {
                float s = blend(ct, x);
                int lo = lo(ct, x);
                float[] p = c[lo], q = c[Math.min(c.length - 1, lo + 1)];
                for (int k = 0; k < 3; k++) {
                    rgba[k] = p[k] + (q[k] - p[k]) * s;
                }
            }
            if (a.length == 0) {
                rgba[3] = 1f;
            } else {
                float s = blend(at, x);
                int lo = lo(at, x);
                rgba[3] = a[lo] + (a[Math.min(a.length - 1, lo + 1)] - a[lo]) * s;
            }
        }
    }

    /** Unity MinMaxCurve: 0 constant, 1 curve, 2 two curves, 3 two constants. */
    static final class MinMax {
        final int mode;
        final float scalar, minScalar;
        final Curve max, min;

        MinMax(KahluaTable t) {
            mode = (int) num(t, "mode", 0f);
            scalar = num(t, "scalar", 1f);
            minScalar = num(t, "minScalar", 0f);
            max = new Curve(tab(t, "max"));
            min = new Curve(tab(t, "min"));
        }

        float eval(float x, float r) {
            return switch (mode) {
                case 0 -> scalar;
                case 1 -> scalar * max.eval(x);
                case 2 -> scalar * (min.eval(x) * (1 - r) + max.eval(x) * r);
                default -> minScalar + (scalar - minScalar) * r;
            };
        }
    }

    /** Unity MinMaxGradient: 0 colour, 1 gradient, 2 two colours, 3 two gradients. */
    static final class MinMaxGradient {
        final int mode;
        final float[] maxColor, minColor;
        final Gradient max, min;
        private final float[] scratch = new float[4];

        MinMaxGradient(KahluaTable t) {
            mode = (int) num(t, "mode", 0f);
            maxColor = floats(tab(t, "maxColor"));
            minColor = floats(tab(t, "minColor"));
            max = new Gradient(tab(t, "max"));
            min = new Gradient(tab(t, "min"));
        }

        void eval(float x, float r, float[] out) {
            switch (mode) {
                case 0 -> System.arraycopy(maxColor, 0, out, 0, 4);
                case 1 -> max.eval(x, out);
                case 2 -> {
                    for (int k = 0; k < 4; k++) {
                        out[k] = minColor[k] * (1 - r) + maxColor[k] * r;
                    }
                }
                default -> {
                    // (performance pass 23.10.2026: a scratch row; evaluated on the main thread only)
                    float[] o = scratch;
                    min.eval(x, o);
                    max.eval(x, out);
                    for (int k = 0; k < 4; k++) {
                        out[k] = o[k] * (1 - r) + out[k] * r;
                    }
                }
            }
        }
    }

    static final class Jet {
        float[] pos, size, rsx, rsy, axis, norm;
        float extra, chance;
    }

    static final class Fume {
        float startPos, radius, conus, sizesRnd, size, speed, life;
        Curve sizes, speeds, lifeTimes;
        Gradient colour;
        int countMin, countRange;
    }

    static final class Sparks {
        float startPos, radius, conus, timeShift;
        int countMin, countRange;
        float[] life, speed, gravity, drag;
        float[] emission, size;
    }

    static final class Strand {
        String texture;
        float width, uvY, diffusion, startFade, endFade, end, alpha;
        float[] color;
    }

    static final class Smoke {
        float smokeEnd, brake, drag, gravity, velocity, turbDensity, turbIntensity, diffByVel, speedMult, strength, length, lengthRnd, perShot, drop;
        Curve speedTurbDensity, speedTurbStrength, speedSmokeStrength, speedStartDiffusion, smoke;
        Strand strand;
    }

    static final class Material {
        String texture;
        float[] tint;
        float hdr, invWindow, phase;
    }

    static final class Manager {
        Material mat;
        int ax, ay;
        Curve move, light, lamp;
        float shot, range0, range1, lampIntensity;
        float[] lampColour;
    }

    /**
     * A Unity particle system of a MuzzleParticlePivot effect (RShG-2): start
     * values, cone/sphere shape, bursts, size/colour/rotation over lifetime, speed
     * limit dampen, texture sheet; drawn as lit smoke, additive glow or an
     * additive stretched streak (by the family of its Tarkov shader).
     */
    static final class PSystem {
        static final int SMOKE = 0, ADD = 1, STREAK = 2;
        int kind;
        String texture;
        float[] pos, rot, tint;
        MinMax lifetime, speed, size, startRotation, gravity, delay, sizeOL, rotOL, frameOT, startFrame;
        MinMaxGradient colour, colourOL;
        boolean shapeOn;
        int shapeType, tilesX, tilesY, cycles, max;
        float angle, radius, thickness, dampen, simSpeed, strength, distortion, animation, lengthScale, velocityScale;
        float[] burstTime, burstInterval;
        MinMax[] burstCount;
        int[] burstCycles;
        /** Main thread (build): its smoke run parameters, kept while distortion and animation are (runParam). */
        float[] runParam;
    }

    static final class Pivot {
        PSystem[] systems;
        float[][] lights;   // x, y, z (container), range, r, g, b, intensity
    }

    static final class Data {
        Pivot[] pivots = new Pivot[2];
        float scale;
        Jet[] jets;
        Fume[] fumes;
        Sparks[] sparks;
        Smoke[] smokes;
        Strand[] strands;
        Material[] materials;
        final Map<String, Manager> managers = new HashMap<>();
        MinMax fumeSize, fumeRotation;
        MinMaxGradient fumeColour;
        float fumeDampen, fumeDistortion, fumeSpeed, fumeAmbient;
        float[] fumeTint;
        /** Main thread (build): the fume run parameters (runParam). */
        float[] fumeParam;
        String fumeTexture, noiseTexture, sparkTexture;
        float[] sparkTint;
        float sparkSize, sparkHdr;
        float blastRange, blastIntensity;
        float[] blastColour;
        // Overheat (globals.json config.Overheat) and the HotObject glow mask
        float ohMax = 200f, ohMin, ohCoolCoef = 6f, heatVisible = 1f, heatThermal = 1f;
        String hotTexture;
        /** AmmoTemplate.HeatFactor by the PZ item type of the round (and the calibre's aliases). */
        final Map<String, Float> ammoHeat = new HashMap<>();
    }

    static volatile Data data;

    static <T> T at(T[] list, Object index) {
        int i = (int) num(index, 0f);
        return list != null && i >= 1 && i <= list.length ? list[i - 1] : null;
    }

    /** Lua: the whole TW/Data/MuzzleFx table (once per game start). */
    public static void setData(KahluaTable t) {
        try {
            Data d = new Data();
            d.scale = num(t, "scale", 0.578f);
            KahluaTable list = tab(t, "jets");
            d.jets = new Jet[list == null ? 0 : list.len()];
            for (int i = 0; i < d.jets.length; i++) {
                KahluaTable e = tab(list, i + 1);
                Jet j = new Jet();
                KahluaTable parts = tab(e, "particles");
                int n = parts.len();
                j.pos = new float[n];
                j.size = new float[n];
                j.rsx = new float[n];
                j.rsy = new float[n];
                j.axis = new float[n];
                j.norm = new float[n];
                float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
                for (int k = 0; k < n; k++) {
                    float[] p = floats(tab(parts, k + 1));
                    j.pos[k] = p[0];
                    j.size[k] = p[1];
                    j.rsx[k] = p[2];
                    j.rsy[k] = p[3];
                    j.axis[k] = p[4];
                    lo = Math.min(lo, p[0]);
                    hi = Math.max(hi, p[0]);
                }
                // MuzzleJet: posNorm = (Position - min) / (max - min)
                for (int k = 0; k < n; k++) {
                    j.norm[k] = hi > lo ? (j.pos[k] - lo) / (hi - lo) : 0f;
                }
                float[] b = floats(tab(e, "bounds"));
                j.extra = b[2] - b[1];
                j.chance = num(e, "chance", 1f);
                d.jets[i] = j;
            }
            list = tab(t, "fumes");
            d.fumes = new Fume[list == null ? 0 : list.len()];
            for (int i = 0; i < d.fumes.length; i++) {
                KahluaTable e = tab(list, i + 1);
                Fume f = new Fume();
                f.startPos = num(e, "startPos", 0f);
                f.radius = num(e, "emitterRadius", 0f);
                f.conus = num(e, "conus", 1f);
                f.sizesRnd = num(e, "sizesRnd", 0f);
                f.size = num(e, "size", 1f);
                f.speed = num(e, "speed", 1f);
                f.life = num(e, "lifeTime", 1f);
                f.sizes = new Curve(tab(e, "sizes"));
                f.speeds = new Curve(tab(e, "speeds"));
                f.lifeTimes = new Curve(tab(e, "lifeTimes"));
                f.colour = new Gradient(tab(e, "colour"));
                f.countMin = (int) num(e, "countMin", 1f);
                f.countRange = (int) num(e, "countRange", 0f);
                d.fumes[i] = f;
            }
            list = tab(t, "sparks");
            d.sparks = new Sparks[list == null ? 0 : list.len()];
            for (int i = 0; i < d.sparks.length; i++) {
                KahluaTable e = tab(list, i + 1);
                Sparks s = new Sparks();
                s.startPos = num(e, "startPos", 0f);
                s.radius = num(e, "emitterRadius", 0f);
                s.conus = num(e, "conus", 1f);
                s.timeShift = num(e, "timeShift", 0f);
                s.countMin = (int) num(e, "countMin", 0f);
                s.countRange = (int) num(e, "countRange", 0f);
                s.life = floats(tab(e, "lifetime"));
                s.speed = floats(tab(e, "speed"));
                s.gravity = floats(tab(e, "gravity"));
                // MuzzleSparks.DragCurve.RecalcVals: (1 - curve)^4, clamped to 0..0.999.
                s.drag = floats(tab(e, "dragCurve"));
                for (int k = 0; k < s.drag.length; k++) {
                    float v = 1f - s.drag[k];
                    s.drag[k] = Math.max(0f, Math.min(0.999f, v * v * v * v));
                }
                s.emission = floats(tab(e, "emission"));
                s.size = floats(tab(e, "size"));
                d.sparks[i] = s;
            }
            list = tab(t, "strands");
            d.strands = new Strand[list == null ? 0 : list.len()];
            for (int i = 0; i < d.strands.length; i++) {
                KahluaTable e = tab(list, i + 1);
                Strand s = new Strand();
                s.texture = String.valueOf(e.rawget("texture"));
                s.width = num(e, "width", 0.005f);
                s.uvY = num(e, "uvY", 0.03f);
                s.diffusion = num(e, "diffusion", 60f);
                s.startFade = num(e, "startFade", 0.0003f);
                s.endFade = num(e, "endFade", 0.05f);
                s.end = num(e, "end", 0.03f);
                s.alpha = num(e, "alpha", 1f);
                s.color = floats(tab(e, "color"));
                d.strands[i] = s;
            }
            list = tab(t, "smokes");
            d.smokes = new Smoke[list == null ? 0 : list.len()];
            for (int i = 0; i < d.smokes.length; i++) {
                KahluaTable e = tab(list, i + 1);
                Smoke s = new Smoke();
                s.smokeEnd = num(e, "smokeEnd", 0.03f);
                s.brake = num(e, "brakeDistance", 0.01f);
                s.drag = num(e, "dragValue", 0.95f);
                s.gravity = num(e, "gravity", -1f);
                s.velocity = num(e, "smokeVelocity", 0.4f);
                s.turbDensity = num(e, "turbulenceDensity", 0.4f);
                s.turbIntensity = num(e, "turbulenceIntensity", 0.1f);
                s.diffByVel = num(e, "smokeDiffusionBySmokeVelocity", 32f);
                s.speedMult = num(e, "muzzleSpeedMultiplier", 1f);
                s.strength = num(e, "smokeStrength", 0.3f);
                s.length = num(e, "smokeLength", 20f);
                s.lengthRnd = num(e, "smokeLengthRandomness", 0f);
                s.perShot = num(e, "smokeIncreasingByShot", 0.4f);
                s.drop = num(e, "shotFactorDropTime", 0.5f);
                s.speedTurbDensity = new Curve(tab(e, "speedTurbulenceDensity"));
                s.speedTurbStrength = new Curve(tab(e, "speedTurbulenceStrength"));
                s.speedSmokeStrength = new Curve(tab(e, "speedSmokeStrength"));
                s.speedStartDiffusion = new Curve(tab(e, "speedStartDiffusion"));
                s.smoke = new Curve(tab(e, "smoke"));
                s.strand = at(d.strands, e.rawget("strand"));
                d.smokes[i] = s;
            }
            list = tab(t, "materials");
            d.materials = new Material[list == null ? 0 : list.len()];
            for (int i = 0; i < d.materials.length; i++) {
                KahluaTable e = tab(list, i + 1);
                Material m = new Material();
                m.texture = String.valueOf(e.rawget("texture"));
                m.tint = floats(tab(e, "tint"));
                m.hdr = num(e, "hdr", 1f);
                m.invWindow = num(e, "invWindow", 1f);
                m.phase = num(e, "phase", 0f);
                d.materials[i] = m;
            }
            KahluaTable weapons = tab(t, "weapons");
            if (weapons != null) {
                var it = weapons.iterator();
                while (it.advance()) {
                    if (!(it.getValue() instanceof KahluaTable e)) {
                        continue;
                    }
                    Manager m = new Manager();
                    m.mat = at(d.materials, e.rawget("mat"));
                    float[] atlas = floats(tab(e, "atlas"));
                    m.ax = atlas.length > 0 ? Math.max(1, (int) atlas[0]) : 2;
                    m.ay = atlas.length > 1 ? Math.max(1, (int) atlas[1]) : 2;
                    m.move = new Curve(tab(e, "move"));
                    m.light = new Curve(tab(e, "light"));
                    m.shot = num(e, "shot", 0.1f);
                    KahluaTable lamp = tab(e, "lamp");
                    float[] range = floats(tab(lamp, "range"));
                    m.range0 = range.length > 0 ? range[0] : 6f;
                    m.range1 = range.length > 1 ? range[1] : 12f;
                    m.lamp = new Curve(tab(lamp, "curve"));
                    m.lampColour = floats(tab(lamp, "colour"));
                    m.lampIntensity = num(lamp, "intensity", 2f);
                    d.managers.put(String.valueOf(it.getKey()), m);
                }
            }
            KahluaTable fume = tab(t, "fume");
            d.fumeSize = new MinMax(tab(fume, "size"));
            d.fumeRotation = new MinMax(tab(fume, "rotation"));
            d.fumeColour = new MinMaxGradient(tab(fume, "colour"));
            d.fumeDampen = num(fume, "dampen", 0f);
            d.fumeTint = floats(tab(fume, "tint"));
            d.fumeDistortion = num(fume, "distortion", 0f);
            d.fumeSpeed = num(fume, "speed", 0f);
            d.fumeAmbient = num(fume, "ambient", 0f);
            d.fumeTexture = String.valueOf(fume.rawget("texture"));
            d.noiseTexture = String.valueOf(fume.rawget("noise"));
            KahluaTable spark = tab(t, "spark");
            d.sparkTexture = String.valueOf(spark.rawget("texture"));
            d.sparkTint = floats(tab(spark, "tint"));
            d.sparkSize = num(spark, "size", 0.04f);
            d.sparkHdr = num(spark, "hdr", 1f);
            KahluaTable blast = tab(t, "explosion");
            d.blastRange = num(blast, "range", 6f);
            d.blastIntensity = num(blast, "intensity", 1f);
            d.blastColour = floats(tab(blast, "colour"));
            KahluaTable pivots = tab(t, "pivots");
            for (int i = 0; i < d.pivots.length; i++) {
                KahluaTable pv = tab(pivots, "p" + i);
                if (pv != null) {
                    d.pivots[i] = pivot(pv);
                }
            }
            KahluaTable oh = tab(t, "overheat");
            if (oh != null) {
                d.ohMax = num(oh, "max", 200f);
                d.ohMin = num(oh, "min", 0f);
                d.ohCoolCoef = num(oh, "coolCoef", 6f);
                d.heatVisible = num(oh, "visible", 1f);
                d.heatThermal = num(oh, "thermal", 1f);
            }
            KahluaTable hot = tab(t, "hot");
            d.hotTexture = hot == null ? null : String.valueOf(hot.rawget("texture"));
            KahluaTable ammo = tab(t, "ammoHeat");
            if (ammo != null) {
                var it = ammo.iterator();
                while (it.advance()) {
                    d.ammoHeat.put(String.valueOf(it.getKey()), num(it.getValue(), 1f));
                }
            }
            data = d;
        } catch (Throwable e) {
            fail("data", e);
            return;
        }
        try {
            warm(data);
        } catch (Throwable e) {
            fail("textures", e);
        }
    }

    /**
     * Performance pass 23.10.2026: every image of the data requested once at load through texture() - the very path,
     * flags and cache the drawing uses - so the file lookup and decode no longer start in the first shot's frame (a
     * missing entry, "null", is left to the drawing as before).
     */
    static void warm(Data d) {
        if (d == null) {
            return;
        }
        ArrayList<String> names = new ArrayList<>();
        for (Material m : d.materials) {
            names.add(m.texture);
        }
        for (Strand s : d.strands) {
            names.add(s.texture);
        }
        for (Pivot pv : d.pivots) {
            for (int i = 0; pv != null && i < pv.systems.length; i++) {
                names.add(pv.systems[i].texture);
            }
        }
        names.add(d.fumeTexture);
        names.add(d.noiseTexture);
        names.add(d.sparkTexture);
        names.add(d.hotTexture);
        for (String name : names) {
            if (name != null && !"null".equals(name)) {
                texture(name);
            }
        }
    }

    static MinMax minMax(KahluaTable t) {
        return t == null ? null : new MinMax(t);
    }

    static Pivot pivot(KahluaTable pv) {
        Pivot p = new Pivot();
        KahluaTable list = tab(pv, "systems");
        int n = list == null ? 0 : list.len();
        p.systems = new PSystem[n];
        for (int i = 0; i < n; i++) {
            KahluaTable e = tab(list, i + 1);
            PSystem s = new PSystem();
            String kind = String.valueOf(e.rawget("kind"));
            s.kind = "smoke".equals(kind) ? PSystem.SMOKE : "streak".equals(kind) ? PSystem.STREAK : PSystem.ADD;
            s.texture = String.valueOf(e.rawget("texture"));
            s.pos = floats(tab(e, "position"));
            s.rot = floats(tab(e, "rotation"));
            s.tint = floats(tab(e, "tint"));
            s.lifetime = minMax(tab(e, "lifetime"));
            s.speed = minMax(tab(e, "speed"));
            s.size = minMax(tab(e, "size"));
            s.startRotation = minMax(tab(e, "startRotation"));
            s.gravity = minMax(tab(e, "gravity"));
            s.delay = minMax(tab(e, "delay"));
            s.sizeOL = minMax(tab(e, "sizeOverLifetime"));
            s.rotOL = minMax(tab(e, "rotationOverLifetime"));
            s.colour = new MinMaxGradient(tab(e, "colour"));
            KahluaTable col = tab(e, "colourOverLifetime");
            s.colourOL = col == null ? null : new MinMaxGradient(col);
            KahluaTable shape = tab(e, "shape");
            s.shapeOn = Boolean.TRUE.equals(shape.rawget("enabled"));
            s.shapeType = (int) num(shape, "type", 4f);
            s.angle = num(shape, "angle", 25f);
            s.radius = num(shape, "radius", 0f);
            s.thickness = num(shape, "radiusThickness", 1f);
            s.dampen = num(e, "dampen", 0f);
            s.simSpeed = num(e, "simSpeed", 1f);
            s.strength = num(e, "strength", 1f);
            s.distortion = num(e, "distortion", 0f);
            s.animation = num(e, "animation", 0f);
            s.lengthScale = num(e, "lengthScale", 2f);
            s.velocityScale = num(e, "velocityScale", 0f);
            s.max = (int) num(e, "max", 1000f);
            KahluaTable sheet = tab(e, "sheet");
            if (sheet != null) {
                s.tilesX = Math.max(1, (int) num(sheet, "tilesX", 1f));
                s.tilesY = Math.max(1, (int) num(sheet, "tilesY", 1f));
                s.cycles = Math.max(1, (int) num(sheet, "cycles", 1f));
                s.frameOT = minMax(tab(sheet, "frameOverTime"));
                s.startFrame = minMax(tab(sheet, "startFrame"));
            }
            KahluaTable bursts = tab(e, "bursts");
            int b = bursts == null ? 0 : bursts.len();
            s.burstTime = new float[b];
            s.burstInterval = new float[b];
            s.burstCount = new MinMax[b];
            s.burstCycles = new int[b];
            for (int k = 0; k < b; k++) {
                KahluaTable br = tab(bursts, k + 1);
                s.burstTime[k] = num(br, "time", 0f);
                s.burstInterval[k] = num(br, "interval", 0.01f);
                s.burstCount[k] = minMax(tab(br, "count"));
                s.burstCycles[k] = Math.max(1, (int) num(br, "cycles", 1f));
            }
            p.systems[i] = s;
        }
        KahluaTable lights = tab(pv, "lights");
        int nl = lights == null ? 0 : lights.len();
        p.lights = new float[nl][];
        for (int i = 0; i < nl; i++) {
            KahluaTable l = tab(lights, i + 1);
            float[] pos = floats(tab(l, "position")), col = floats(tab(l, "colour"));
            p.lights[i] = new float[] {pos[0], pos[1], pos[2], num(l, "range", 3f), col[0], col[1], col[2], num(l, "intensity", 1f)};
        }
        return p;
    }

    // ------------------------------------------------------------------ setups

    /** One effect node of an assembly, in the root model space (12 values, rows x/y/z of [X Y Z T]). */
    static final class Node {
        final float[] m = new float[12];
        Jet jet;
        Fume fume;
        Sparks sparks;
        Smoke smoke;
        boolean extractor;
        /** MuzzleParticlePivot: the CommonSystems effect it plays (Data.pivots), else -1. */
        int pivot = -1;
        // MuzzleJet.UpdateOrCreateMesh randomness, fixed per assembly
        float common;
        float[] g, vx, vy, cu, cv;
    }

    static final class Setup {
        Manager mgr;
        Node[] nodes;
        boolean launcher;
        float chance;
        boolean jets;
        // Overheat (Weapon.GetShotOverheat / GetCurrentOverheat): heat of a shot without its
        // round, CoolFactorGun, the parts' cooling (CoolFactorGunMods x every part x ModCoolFactor)
        boolean heat;
        float heatShot, heatCool, heatMods = 1f;
        HotNode[] hot = new HotNode[0];
        /** The visible glow on the model (MuzzleHeatModel): item template -> its boxes (MuzzleHeatModel.BOX_FLOATS each). */
        Map<String, float[]> boxes = new HashMap<>();
    }

    /**
     * A HotObject of the assembly in the root model space: the zone along the bore
     * where its HeatBounds light its own mesh (tools/muzzle_fx.py hot_geometry: a to b,
     * radius half), for the thermal image and the fallback glow; VisibleHeatAlpha, the
     * material's heat colours and its specular term (see the USEHEAT formula). The glow
     * itself is drawn on the part's mesh (MuzzleHeatModel, Setup.boxes).
     */
    static final class HotNode {
        final float[] a = new float[3], b = new float[3];
        float half, visible, spec;
        float[] c1, c2;
    }

    /** A weapon's overheat after its last shot (Weapon.MalfState.LastShotOverheat / LastShotTime). */
    static final class Heat {
        float last;
        double at;
    }

    static final Map<Object, Heat> heats = new WeakHashMap<>();

    static final Map<Object, Setup> setups = new WeakHashMap<>();
    static final java.util.Random random = new java.util.Random();

    /** Lua: the effect nodes of an item's assembly ({m=, jet=, fume=, sparks=, smoke=, x=}), or an empty list. */
    public static void setup(Object item, String weaponTpl, KahluaTable list, boolean launcher) {
        Data d = data;
        if (item == null) {
            return;
        }
        if (d == null || list == null) {
            setups.remove(item);
            return;
        }
        Manager mgr = d.managers.get(weaponTpl);
        int n = list.len();
        ArrayList<Node> nodes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            KahluaTable e = tab(list, i + 1);
            if (e == null) {
                continue;
            }
            Node node = new Node();
            float[] m = floats(tab(e, "m"));
            if (m.length != 12) {
                continue;
            }
            System.arraycopy(m, 0, node.m, 0, 12);
            node.jet = launcher ? null : at(d.jets, e.rawget("jet"));
            node.fume = at(d.fumes, e.rawget("fume"));
            node.sparks = launcher ? null : at(d.sparks, e.rawget("sparks"));
            node.smoke = launcher ? null : at(d.smokes, e.rawget("smoke"));
            node.extractor = Boolean.TRUE.equals(e.rawget("x"));
            int pv = (int) num(e.rawget("pivot"), -1f);
            node.pivot = !launcher && pv >= 0 && pv < d.pivots.length && d.pivots[pv] != null ? pv : -1;
            if (node.jet != null || node.fume != null || node.sparks != null || node.smoke != null || node.pivot >= 0) {
                nodes.add(node);
            }
        }
        // Performance pass 23.10.2026: the same nodes for the same manager keep the item's setup - its jet randomness
        // (fixed per assembly) and, on the next shot, its smoke trails (Shooter.trailSetup); TW/MuzzleFx sends again
        // only a changed signature, which may still leave the nodes as they were (a hot part changed: setHeat).
        Setup old = setups.get(item);
        if (old != null && old.mgr == mgr && old.launcher == launcher && sameNodes(old.nodes, nodes)) {
            setupsKept++;
            return;
        }
        Setup s = new Setup();
        s.mgr = mgr;
        s.launcher = launcher;
        // The randomness in the order the nodes came (a node without a jet draws none), as before.
        for (Node node : nodes) {
            if (node.jet != null && s.mgr != null) {
                int k = node.jet.pos.length;
                node.common = (int) (random.nextFloat() * 255f) / 255f;
                node.g = new float[k];
                node.vx = new float[k];
                node.vy = new float[k];
                node.cu = new float[k];
                node.cv = new float[k];
                float cellX = 1f / s.mgr.ax, cellY = 1f / s.mgr.ay;
                for (int q = 0; q < k; q++) {
                    double a = random.nextDouble() * Math.PI * 2;
                    node.vx[q] = (float) Math.cos(a) * node.jet.size[q];
                    node.vy[q] = (float) Math.sin(a) * node.jet.size[q];
                    node.g[q] = (127 + random.nextInt(128)) / 255f;
                    node.cu[q] = (random.nextInt(510) - 255) * cellX;
                    node.cv[q] = (random.nextInt(510) - 255) * cellY;
                }
                s.chance += node.jet.chance;
                s.jets = true;
            }
        }
        s.nodes = nodes.toArray(new Node[0]);
        setups.put(item, s);
    }

    /** QA: setups kept for the same nodes (performance pass 23.10.2026). */
    public static volatile long setupsKept;

    /** The same effect nodes in the same order: frames bit for bit and the same profiles. */
    static boolean sameNodes(Node[] old, ArrayList<Node> nodes) {
        if (old == null || old.length != nodes.size()) {
            return false;
        }
        for (int i = 0; i < old.length; i++) {
            Node a = old[i], b = nodes.get(i);
            if (a.jet != b.jet || a.fume != b.fume || a.sparks != b.sparks || a.smoke != b.smoke || a.extractor != b.extractor
                    || a.pivot != b.pivot || !java.util.Arrays.equals(a.m, b.m)) {
                return false;
            }
        }
        return true;
    }

    public static void clear(Object item) {
        if (item != null) {
            setups.remove(item);
        }
    }

    /**
     * Lua (after setup): the assembly's overheat factors and hot objects
     * ({shot=, cool=, mods=, hot={{m=, a=, b=, r=, q=, u=, t=, v=, c1=, c2=, s=}}}), or nil
     * when the weapon does not overheat: m the node frame in the root model space,
     * a, b its lit zone node-local, r its radius, q its box matrix in the mesh space
     * of its item t (TW/t), u its own sub-mesh's atlas rectangle there.
     */
    public static void setHeat(Object item, KahluaTable t) {
        Setup s = item == null ? null : setups.get(item);
        if (s == null) {
            return;
        }
        if (t == null) {
            s.heat = false;
            s.hot = new HotNode[0];
            return;
        }
        s.heat = true;
        s.heatShot = num(t, "shot", 0f);
        s.heatCool = num(t, "cool", 0f);
        s.heatMods = num(t, "mods", 1f);
        KahluaTable list = tab(t, "hot");
        int n = list == null ? 0 : list.len();
        ArrayList<HotNode> hot = new ArrayList<>();
        Map<String, ArrayList<float[]>> boxes = new HashMap<>();
        float visibleFactor = data == null ? 1f : data.heatVisible;
        for (int i = 0; i < n; i++) {
            KahluaTable e = tab(list, i + 1);
            float[] m = e == null ? null : floats(tab(e, "m"));
            float[] a = e == null ? null : floats(tab(e, "a")), b = e == null ? null : floats(tab(e, "b"));
            if (m == null || m.length != 12 || a == null || a.length < 3 || b == null || b.length < 3) {
                continue;
            }
            HotNode h = new HotNode();
            // The lit zone is node-local in PZ model units: the full node frame places it, its own scale
            // included (HeatBounds are in the mesh's object space; a scaled node keeps its true size).
            for (int r = 0; r < 3; r++) {
                h.a[r] = m[r * 4] * a[0] + m[r * 4 + 1] * a[1] + m[r * 4 + 2] * a[2] + m[r * 4 + 3];
                h.b[r] = m[r * 4] * b[0] + m[r * 4 + 1] * b[1] + m[r * 4 + 2] * b[2] + m[r * 4 + 3];
            }
            h.half = num(e, "r", 0f);
            h.visible = num(e, "v", 0f);
            h.spec = num(e, "s", 0.5f);
            h.c1 = floats(tab(e, "c1"));
            h.c2 = floats(tab(e, "c2"));
            if (h.c1.length < 3 || h.c2.length < 3) {
                continue;
            }
            hot.add(h);
            // The glow on the part's own mesh: q in the mesh space of its item (TW/t), u the atlas rectangle
            // of the HotObject's own sub-mesh in that merged mesh; shown where visible.
            float[] q = floats(tab(e, "q")), u = floats(tab(e, "u"));
            Object template = e.rawget("t");
            if (h.visible > 0f && q.length == 12 && template != null) {
                float[] box = new float[MuzzleHeatModel.BOX_FLOATS];
                System.arraycopy(q, 0, box, 0, 12);
                box[12] = h.c1[0]; box[13] = h.c1[1]; box[14] = h.c1[2]; box[15] = h.spec;
                box[16] = h.c2[0]; box[17] = h.c2[1]; box[18] = h.c2[2]; box[19] = h.visible * visibleFactor;
                float[] rect = u.length == 4 ? u : new float[] {-1f, -1f, 2f, 2f};
                System.arraycopy(rect, 0, box, 20, 4);
                boxes.computeIfAbsent(String.valueOf(template), k -> new ArrayList<>()).add(box);
            }
        }
        s.hot = hot.toArray(new HotNode[0]);
        Map<String, float[]> packed = new HashMap<>();
        for (var entry : boxes.entrySet()) {
            ArrayList<float[]> rows = entry.getValue();
            int count = Math.min(MuzzleHeatModel.MAX_BOXES, rows.size());
            float[] all = new float[count * MuzzleHeatModel.BOX_FLOATS];
            for (int i = 0; i < count; i++) {
                System.arraycopy(rows.get(i), 0, all, i * MuzzleHeatModel.BOX_FLOATS, MuzzleHeatModel.BOX_FLOATS);
            }
            packed.put(entry.getKey(), all);
        }
        s.boxes = packed;
    }

    /** Weapon.GetCurrentOverheat: the overheat now, cooling linearly from the last shot. */
    static float overheat(Setup s, Heat h, double now) {
        Data d = data;
        if (!heatOn || d == null || s == null || !s.heat || h == null || h.last <= 0f) {
            return 0f;
        }
        double last = h.last;
        double denom = last - Math.pow(last / d.ohMax + d.ohCoolCoef * 0.0338 + 0.2925, 9.667 + d.ohCoolCoef * 0.0668);
        if (denom <= 1e-4) {
            return d.ohMin;
        }
        double v = last - (now - h.at) * last * s.heatCool * s.heatMods / denom;
        return (float) Math.max(d.ohMin, Math.min(d.ohMax, v));
    }

    /** Weapon.OnShot: the overheat after a shot, AmmoTemplate.HeatFactor of the weapon's round. */
    static void heatShot(Object item, Setup s) {
        Data d = data;
        if (!heatOn || d == null || !s.heat || s.launcher) {
            return;
        }
        float ammo = 1f;
        if (item instanceof zombie.inventory.types.HandWeapon w && w.getAmmoType() != null) {
            Float f = d.ammoHeat.get(w.getAmmoType().getItemKey());
            ammo = f == null ? 1f : f;
        }
        Heat h = heats.computeIfAbsent(item, k -> new Heat());
        float now = overheat(s, h, time);
        h.last = Math.max(d.ohMin, Math.min(d.ohMax, now + s.heatShot * ammo));
        h.at = time;
        heatShots++;
    }

    /** HotObject: the share of the USEHEAT glow (max(0, (_HeatTemp - 30) / 10))^2 at an overheat (ConvertHeat2Celsio). */
    static float heatGlow(float overheat) {
        float celsius = 10f * overheat / 220f + 30f;
        float t = Math.max(0f, (celsius - 30f) * 0.1f);
        return t * t;
    }

    // ------------------------------------------------------------------ live state

    /** A shooter's flash and barrel smoke. */
    static final class Shooter {
        final WeakReference<IsoGameCharacter> who;
        Setup setup;
        double shotEnd = -1;
        final float[] rv = new float[4], rot = new float[4];
        float cellU, cellV;
        final float[] frame = new float[12];
        boolean framed;
        float k = 1f;
        Trail[] trails;
        Setup trailSetup;
        // the hot weapon in the hands now: its setup and overheat (simulate)
        Setup hotSetup;
        float overheat;

        Shooter(IsoGameCharacter c) {
            who = new WeakReference<>(c);
        }
    }

    static final class Puff {
        float x, y, h, vx, vy, vh, size, life, age, rot, spin, r, g, b, a, rnd;
        /** FB4 item 11: where the shooter stood at emission - Viewpoint lifts it by the relief there (ViewpointPass.anchor). */
        float ax = Float.NaN, ay = Float.NaN;
        /** A particle of a pivot effect's system (null: the shared Fume system). */
        PSystem sys;
        float gravity, frame, k = 1f;
        WeakReference<IsoGameCharacter> owner;
        int ownerPlayer = -1;
        /** Main thread (buildVp): its squared scene distance this frame, the sort key. */
        double vpDistance;
    }

    static final class Spark {
        float x, y, h, vx, vy, vh, gravity, lnDrag, drag, life, emission, width, k;
        float ax = Float.NaN, ay = Float.NaN;
        double born;
        WeakReference<IsoGameCharacter> owner;
        int ownerPlayer = -1;
    }

    /** No trail starts while the smoke option is off (smokeOn). */
    static final Trail[] NO_TRAILS = new Trail[0];

    /** MuzzleSmoke of one smoke node of a shooter. */
    static final class Trail {
        final Smoke smoke;
        final int node;
        float factor, strength, speed, invLen, turbPhase, acc;
        int count, yuv;
        boolean on;
        final float[] pos = new float[3], prev = new float[3], vel = new float[3];
        boolean placed;
        final ArrayList<Seg> segs = new ArrayList<>();   // index 0 = First (at the muzzle)

        Trail(Smoke smoke, int node) {
            this.smoke = smoke;
            this.node = node;
        }
    }

    static final class Seg {
        boolean skip;
        final float[] p = new float[3], v = new float[3], turb = new float[3];
        float alpha, diffusion, yuv;
    }

    static final class Blast {
        float x, y, h;
        double born;
        float life = 90;
        boolean explosionFx;
    }

    static final Map<IsoGameCharacter, Shooter> shooters = new WeakHashMap<>();
    static final ArrayList<Puff> puffs = new ArrayList<>();
    static final ArrayList<Spark> sparks = new ArrayList<>();
    static final ArrayList<Blast> blasts = new ArrayList<>();
    static double time, simTime;
    static long lastNanos;

    static float frand() {
        return random.nextFloat();
    }

    /** Random unit vector (Tarkov: a table of normalized vectors). */
    static void unit(float[] out) {
        double z = random.nextDouble() * 2 - 1, a = random.nextDouble() * Math.PI * 2, r = Math.sqrt(1 - z * z);
        out[0] = (float) (r * Math.cos(a));
        out[1] = (float) (r * Math.sin(a));
        out[2] = (float) z;
    }

    /** Tarkov Int(start, range): start + |rnd| % range. */
    static int count(int start, int range) {
        return range < 1 ? start : start + random.nextInt(range);
    }

    /** World point (x, y tiles, height tiles) of a model point through a frame. */
    static void place(float[] f, float mx, float my, float mz, float[] out) {
        out[0] = f[0] + f[3] * mx + f[6] * my + f[9] * mz;
        out[1] = f[1] + f[4] * mx + f[7] * my + f[10] * mz;
        out[2] = (f[2] + f[5] * mx + f[8] * my + f[11] * mz) * LEVEL;
    }

    /** World direction (tiles, isotropic, normalized) of a model direction. */
    static void direct(float[] f, float mx, float my, float mz, float[] out) {
        float x = f[3] * mx + f[6] * my + f[9] * mz, y = f[4] * mx + f[7] * my + f[10] * mz, z = (f[5] * mx + f[8] * my + f[11] * mz) * LEVEL;
        float n = (float) Math.sqrt(x * x + y * y + z * z);
        if (n < 1e-9f) {
            n = 1f;
        }
        out[0] = x / n;
        out[1] = y / n;
        out[2] = z / n;
    }

    /** FB4 item 11: the local first-person prop as Viewpoint draws it (its carried adjustment, SightDrawFrame.carried); QA can turn it off. */
    static volatile boolean carry = true;
    static volatile long carried;

    static boolean frame(IsoGameCharacter c, Shooter s) {
        org.joml.Matrix4f adjust = carry && ViewpointBridge.state() == 1 ? SightDrawFrame.carried(c) : null;
        if (!WeaponFrame.world(c, adjust)) {
            return false;
        }
        if (adjust != null) {
            carried++;
        }
        for (int i = 0; i < 12; i++) {
            s.frame[i] = (float) WeaponFrame.F[i];
        }
        s.framed = true;
        float ax = s.frame[3], ay = s.frame[4], az = s.frame[5] * LEVEL;
        Data d = data;
        s.k = (d == null ? 0.578f : d.scale) * (float) Math.sqrt(ax * ax + ay * ay + az * az);
        lastScale = s.k;
        return true;
    }

    /**
     * FB8 item 4 (22.10.2026, "the tracer does not fly out of the barrel"): the bore exit of the assembly in the hands
     * (x, y tiles, z levels) through the same drawn frame as the flash - the forward-firing effect node of the last
     * muzzle part (barrel, flash hider, brake or suppressor) furthest along the bore; false without a setup or frame.
     */
    static boolean boreExit(IsoGameCharacter c, double[] out) {
        Object item = c == null ? null : c.getPrimaryHandItem();
        Setup s = item == null ? null : setups.get(item);
        if (s == null || s.nodes.length == 0) {
            return false;
        }
        Node best = null;
        for (boolean forwardOnly : new boolean[] {true, false}) {
            for (Node n : s.nodes) {
                // local +Y of the node is its emission direction; the bore is the model +Y
                if (n.extractor || forwardOnly && n.m[5] < .7f) {
                    continue;
                }
                if (best == null || n.m[7] > best.m[7]) {
                    best = n;
                }
            }
            if (best != null) {
                break;
            }
        }
        org.joml.Matrix4f adjust = carry && ViewpointBridge.state() == 1 ? SightDrawFrame.carried(c) : null;
        if (best == null || !WeaponFrame.world(c, adjust)) {
            return false;
        }
        double[] f = WeaponFrame.F;
        float mx = best.m[3], my = best.m[7], mz = best.m[11];
        for (int i = 0; i < 3; i++) {
            out[i] = f[i] + f[3 + i] * mx + f[6 + i] * my + f[9 + i] * mz;
        }
        return Double.isFinite(out[0]) && Double.isFinite(out[1]) && Double.isFinite(out[2]);
    }

    /**
     * EffectsManager.startMuzzleFlash, main thread: true when the shot was
     * played here (the vanilla flash, its model and light are skipped).
     */
    public static boolean shot(IsoGameCharacter c) {
        try {
            Data d = data;
            if (!enabled || d == null || c == null) {
                return false;
            }
            Object item = c.getPrimaryHandItem();
            Setup s = item == null ? null : setups.get(item);
            if (s == null || s.nodes.length == 0 || s.mgr == null && !s.launcher) {
                vanillaShots++;
                return false;
            }
            advanceClock();
            shots++;
            if (!flashOn && !sparksOn && !lightOn && !heatOn && !smokeOn) return true;
            Shooter sh = shooters.computeIfAbsent(c, Shooter::new);
            sh.setup = s;
            heatShot(item, s);
            if (s.launcher) {
                launcherShots++;
            }
            if (!frame(c, sh)) {
                framesMissing++;
                return true;
            }
            float[] p = new float[3], dir = new float[3], rnd = new float[3];
            int viewer = viewer(c);
            if (flashOn && s.jets) {
                // MuzzleJet.RandomizeMaterial
                for (int i = 0; i < 4; i++) {
                    sh.rv[i] = (1f + frand()) * 878788.8f;
                }
                float rx = 0.5f - frand(), ry = 0.5f - frand(), rn = (float) Math.sqrt(rx * rx + ry * ry);
                if (rn < 1e-6f) {
                    rx = 1f;
                    ry = 0f;
                    rn = 1f;
                }
                rx /= rn;
                ry /= rn;
                sh.rot[0] = rx;
                sh.rot[1] = ry;
                sh.rot[2] = -ry;
                sh.rot[3] = rx;
                sh.cellU = (random.nextInt(510) - 255) / (float) s.mgr.ax;
                sh.cellV = (random.nextInt(510) - 255) / (float) s.mgr.ay;
                sh.shotEnd = time + s.mgr.shot;
            }
            if (sh.trailSetup != s) {
                sh.trailSetup = s;
                ArrayList<Trail> list = new ArrayList<>();
                for (int i = 0; i < s.nodes.length; i++) {
                    if (s.nodes[i].smoke != null) {
                        list.add(new Trail(s.nodes[i].smoke, i));
                    }
                }
                sh.trails = list.toArray(new Trail[0]);
            }
            float k = sh.k;
            for (int i = 0; i < s.nodes.length; i++) {
                Node n = s.nodes[i];
                float[] m = n.m;
                place(sh.frame, m[3], m[7], m[11], p);
                // The emission direction: -up of the Unity node = local +Y of its PZ frame.
                direct(sh.frame, m[1], m[5], m[9], dir);
                if (sparksOn && n.sparks != null) {
                    Sparks sp = n.sparks;
                    int count = count(sp.countMin, sp.countRange);
                    for (int q = 0; q < count && sparks.size() < MAX_SPARKS; q++) {
                        Spark e = new Spark();
                        e.x = p[0] + dir[0] * sp.startPos * k;
                        e.y = p[1] + dir[1] * sp.startPos * k;
                        e.h = p[2] + dir[2] * sp.startPos * k;
                        if (sp.radius > 1e-6f) {
                            unit(rnd);
                            e.x += rnd[0] * sp.radius * k;
                            e.y += rnd[1] * sp.radius * k;
                            e.h += rnd[2] * sp.radius * k;
                        }
                        unit(rnd);
                        float vx = dir[0] + rnd[0] * sp.conus, vy = dir[1] + rnd[1] * sp.conus, vh = dir[2] + rnd[2] * sp.conus;
                        float vn = (float) Math.sqrt(vx * vx + vy * vy + vh * vh);
                        int idx = random.nextInt(Math.max(1, sp.speed.length));
                        float speed = sp.speed[idx] * k / Math.max(1e-6f, vn);
                        e.vx = vx * speed;
                        e.vy = vy * speed;
                        e.vh = vh * speed;
                        e.life = Math.max(1e-3f, sp.life[idx]);
                        e.gravity = sp.gravity[idx] * k;
                        e.drag = Math.max(1e-12f, sp.drag[idx]);
                        e.lnDrag = (float) Math.log(e.drag);
                        e.emission = sp.emission[idx] / 255f;
                        e.width = d.sparkSize * sp.size[idx] / 255f * k;
                        e.k = k;
                        e.born = time + sp.timeShift;
                        e.owner = new WeakReference<>(c);
                        e.ownerPlayer = viewer;
                        e.ax = c.getX();
                        e.ay = c.getY();
                        sparks.add(e);
                        sparksEmitted++;
                    }
                }
                if (n.fume != null) {
                    emitFume(d, n.fume, p, dir, k, c, viewer);
                }
                if (n.pivot >= 0) {
                    emitPivot(d.pivots[n.pivot], sh.frame, m, k, c, viewer);
                }
            }
            for (Trail t : smokeOn ? sh.trails : NO_TRAILS) {
                // MuzzleSmoke.Shot
                t.factor = Math.min(t.factor + t.smoke.perShot, 1f);
                if (t.factor >= 1f) {
                    freeSegs(t.segs);
                    t.strength = 0f;
                    t.on = true;
                    t.placed = false;   // the smoothed muzzle restarts at the muzzle
                    t.count = 1;
                    t.invLen = 1f / (t.smoke.length + frand() * t.smoke.lengthRnd);
                    trailsStarted++;
                }
            }
            if (lightOn && !s.launcher && s.chance > 0f) {
                Node first = s.nodes[0];
                for (Node n : s.nodes) {
                    if (n.jet != null) {
                        first = n;
                        break;
                    }
                }
                place(sh.frame, first.m[3], first.m[7], first.m[11], p);
                light(c, p, s.mgr);
            }
            return true;
        } catch (Throwable e) {
            fail("shot", e);
            return false;
        }
    }

    /** q (x, y, z, w) applied to v. */
    static void rotate(float[] q, float[] v, float[] out) {
        float qx = q[0], qy = q[1], qz = q[2], qw = q[3];
        float tx = 2 * (qy * v[2] - qz * v[1]), ty = 2 * (qz * v[0] - qx * v[2]), tz = 2 * (qx * v[1] - qy * v[0]);
        out[0] = v[0] + qw * tx + (qy * tz - qz * ty);
        out[1] = v[1] + qw * ty + (qz * tx - qx * tz);
        out[2] = v[2] + qw * tz + (qx * ty - qy * tx);
    }

    /** A Unity-local vector of a node to the world (tiles): PZ basis (-x, -y, z), the node's frame, the weapon frame. */
    static void nodeVector(float[] frame, float[] m, float ux, float uy, float uz, float[] out) {
        float px = -ux, py = -uy, pz = uz;
        float mx = m[0] * px + m[1] * py + m[2] * pz, my = m[4] * px + m[5] * py + m[6] * pz, mz = m[8] * px + m[9] * py + m[10] * pz;
        out[0] = frame[3] * mx + frame[6] * my + frame[9] * mz;
        out[1] = frame[4] * mx + frame[7] * my + frame[10] * mz;
        out[2] = (frame[5] * mx + frame[8] * my + frame[11] * mz) * LEVEL;
    }

    /**
     * MuzzleParticlePivot.Play: the CommonSystems effect at the pivot node (its
     * root takes the node's position and rotation, its systems simulate in
     * world space). Each system emits its bursts as Unity does - count, start
     * lifetime/speed/size/rotation/colour, cone or sphere shape around the
     * system's local +Z - and its light shines while the effect burns.
     */
    static void emitPivot(Pivot pv, float[] frame, float[] m, float k, IsoGameCharacter c, int viewer) {
        float[] origin = new float[3], v = new float[3], w = new float[3], dirL = new float[3], posL = new float[3];
        place(frame, m[3], m[7], m[11], origin);
        float scale = data == null ? 0.578f : data.scale;
        for (PSystem s : pv.systems) {
            if (!particleOn(s.kind)) continue;
            for (int b = 0; b < s.burstCount.length; b++) {
                for (int cycle = 0; cycle < s.burstCycles[b]; cycle++) {
                    int count = Math.round(s.burstCount[b].eval(0f, frand()));
                    for (int q = 0; q < count && puffs.size() < MAX_PUFFS && (s.kind != PSystem.SMOKE || smokeOn && smokePuffs < smokeLimit); q++) {
                        // Shape: cone (4) from its base disc, the direction tilted with the distance from the axis;
                        // sphere (0) any direction; off: from the origin along +Z.
                        if (s.shapeOn && s.shapeType == 4) {
                            float rho = (float) Math.sqrt(1 - s.thickness * frand());
                            double phi = frand() * Math.PI * 2;
                            float tan = (float) Math.tan(Math.toRadians(Math.min(s.angle, 89.9f)));
                            dirL[0] = rho * (float) Math.cos(phi) * tan;
                            dirL[1] = rho * (float) Math.sin(phi) * tan;
                            dirL[2] = 1f;
                            posL[0] = rho * (float) Math.cos(phi) * s.radius;
                            posL[1] = rho * (float) Math.sin(phi) * s.radius;
                            posL[2] = 0f;
                        } else if (s.shapeOn && s.shapeType == 0) {
                            unit(dirL);
                            float r = s.radius * (1 - s.thickness * frand());
                            posL[0] = dirL[0] * r;
                            posL[1] = dirL[1] * r;
                            posL[2] = dirL[2] * r;
                        } else {
                            dirL[0] = 0f;
                            dirL[1] = 0f;
                            dirL[2] = 1f;
                            posL[0] = posL[1] = posL[2] = 0f;
                        }
                        float dn = (float) Math.sqrt(dirL[0] * dirL[0] + dirL[1] * dirL[1] + dirL[2] * dirL[2]);
                        for (int a = 0; a < 3; a++) {
                            dirL[a] /= dn;
                        }
                        // The system's place in the effect, then the node and the weapon.
                        rotate(s.rot, posL, v);
                        nodeVector(frame, m, s.pos[0] + v[0], s.pos[1] + v[1], s.pos[2] + v[2], w);
                        Puff e = new Puff();
                        e.sys = s;
                        e.k = k;
                        float toTiles = k / Math.max(1e-6f, scale * axisLength(frame));
                        e.x = origin[0] + w[0] * scale * toTiles;
                        e.y = origin[1] + w[1] * scale * toTiles;
                        e.h = origin[2] + w[2] * scale * toTiles;
                        rotate(s.rot, dirL, v);
                        nodeVector(frame, m, v[0], v[1], v[2], w);
                        float wn = (float) Math.sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
                        float speed = s.speed.eval(0f, frand()) * k / Math.max(1e-6f, wn);
                        e.vx = w[0] * speed;
                        e.vy = w[1] * speed;
                        e.vh = w[2] * speed;
                        e.life = Math.max(1e-3f, s.lifetime.eval(0f, frand()));
                        e.size = s.size.eval(0f, frand()) * k;
                        e.rot = s.startRotation.eval(0f, frand());
                        e.rnd = frand();
                        e.spin = s.rotOL == null ? 0f : s.rotOL.eval(0f, frand());
                        e.gravity = s.gravity.eval(0f, frand()) * 9.81f * k;
                        float[] col = new float[4];
                        s.colour.eval(0f, frand(), col);
                        e.r = col[0];
                        e.g = col[1];
                        e.b = col[2];
                        e.a = col[3];
                        e.frame = s.startFrame == null ? 0f : s.startFrame.eval(0f, frand());
                        // A start delay or a later burst: born that much later (negative age).
                        e.age = -(s.delay.eval(0f, frand()) + s.burstTime[b] + cycle * s.burstInterval[b]);
                        e.owner = c == null ? null : new WeakReference<>(c);
                        e.ownerPlayer = viewer;
                        anchor(e, c);
                        puffs.add(e);
                        if (smoke(e)) {
                            smokePuffs++;
                        }
                        puffsEmitted++;
                        pivotParticles++;
                    }
                }
            }
        }
        if (!lightOn) return;
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        boolean vp = VpLights.takes();
        for (float[] l : pv.lights) {
            if (cell == null && !vp) {
                break;
            }
            nodeVector(frame, m, l[0], l[1], l[2], w);
            float toTiles = k / Math.max(1e-6f, scale * axisLength(frame));
            float x = origin[0] + w[0] * scale * toTiles, y = origin[1] + w[1] * scale * toTiles, h = origin[2] + w[2] * scale * toTiles;
            if (vp) {
                // Viewpoint shows no IsoLightSource: the same light (radius l[3] x k) as its point light.
                VpLights.pivot(x, y, h, l[4], l[5], l[6], l[7], l[3] * k, BLAST_LIGHT_SECONDS);
                continue;
            }
            float s = Math.min(1f, l[7] / 3f);
            IsoLightSource light = new IsoLightSource((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(h / LEVEL),
                    l[4] * s, l[5] * s, l[6] * s, Math.max(1, Math.round(l[3] * k)), Math.round(BLAST_LIGHT_SECONDS * 60f));
            cell.getLamppostPositions().add(light);
            lights++;
        }
    }

    /** Tiles per model unit of a weapon frame. */
    static float axisLength(float[] f) {
        float ax = f[3], ay = f[4], az = f[5] * LEVEL;
        return (float) Math.sqrt(ax * ax + ay * ay + az * az);
    }

    static void emitFume(Data d, Fume f, float[] p, float[] dir, float k, IsoGameCharacter c, int viewer) {
        float[] rnd = new float[3];
        int count = count(f.countMin, f.countRange);
        for (int q = 0; q < count && smokeOn && puffs.size() < MAX_PUFFS && smokePuffs < smokeLimit; q++) {
            Puff e = new Puff();
            e.x = p[0] + dir[0] * f.startPos * k;
            e.y = p[1] + dir[1] * f.startPos * k;
            e.h = p[2] + dir[2] * f.startPos * k;
            if (f.radius > 1e-6f) {
                unit(rnd);
                e.x += rnd[0] * f.radius * k;
                e.y += rnd[1] * f.radius * k;
                e.h += rnd[2] * f.radius * k;
            }
            float t = frand();
            unit(rnd);
            float vx = dir[0] + rnd[0] * f.conus, vy = dir[1] + rnd[1] * f.conus, vh = dir[2] + rnd[2] * f.conus;
            float vn = (float) Math.sqrt(vx * vx + vy * vy + vh * vh);
            float speed = f.speeds.eval(t) * f.speed * k / Math.max(1e-6f, vn);
            e.vx = vx * speed;
            e.vy = vy * speed;
            e.vh = vh * speed;
            e.size = Math.max(0f, (f.sizes.eval(t) + (frand() * 2 - 1) * f.sizesRnd) * f.size) * k;
            e.life = Math.max(1e-3f, f.lifeTimes.eval(t) * f.life);
            float[] col = new float[4];
            f.colour.eval(t, col);
            e.r = col[0];
            e.g = col[1];
            e.b = col[2];
            e.a = col[3];
            e.rot = (float) (random.nextDouble() * Math.PI * 2);
            e.rnd = frand();
            e.spin = d.fumeRotation.eval(0f, frand());
            e.owner = c == null ? null : new WeakReference<>(c);
            e.ownerPlayer = viewer;
            anchor(e, c);
            puffs.add(e);
            smokePuffs++;
            puffsEmitted++;
        }
    }

    /** A smoke puff (MAX_SMOKE): of the shared Fume system or of a pivot system's smoke kind. */
    static boolean smoke(Puff e) {
        return e.sys == null || e.sys.kind == PSystem.SMOKE;
    }

    static void anchor(Puff e, IsoGameCharacter c) {
        if (c != null) {
            e.ax = c.getX();
            e.ay = c.getY();
        }
    }

    /** QA: the relief anchor on (default) or off (each point lifted at its own place, as before FB4). */
    static volatile boolean anchored = true;

    /** The relief anchor of a primitive in Viewpoint's pass: the shooter's place, else the point's own (FB4 item 11). */
    static void vpAnchor(float ax, float ay) {
        if (!anchored || Float.isNaN(ax)) {
            ViewpointPass.anchorOff();
        } else {
            ViewpointPass.anchor(ax, ay);
        }
    }

    /**
     * Tarkov MuzzleLight at the vanilla level: colour (1, 0.65, 0.45), range Range.x..y, the flash's lifetime.
     * In Viewpoint's view (which shows no IsoLightSource) it is a point light of its frame with Tarkov's curve (VpLights).
     */
    static void light(IsoGameCharacter c, float[] p, Manager m) {
        if (VpLights.takes()) {
            VpLights.muzzle(p[0], p[1], p[2], m);
            return;
        }
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        if (cell == null) {
            return;
        }
        // EffectsManager.startMuzzleFlash: the night level, at least the darkness of the room, at least 0.2.
        float level = GameTime.getInstance().getNight() * 0.8f;
        IsoGridSquare sq = c.getCurrentSquare();
        if (c.isInARoom() && sq != null) {
            level = Math.max(level, 1f - sq.getLightLevel(IsoCamera.frameState.playerIndex));
        }
        level = Math.max(level, 0.2f) * 0.8f;
        float[] col = m.lampColour.length >= 3 ? m.lampColour : new float[] {1f, 0.65f, 0.45f};
        int radius = Math.round(m.range0 + frand() * (m.range1 - m.range0));
        IsoLightSource l = new IsoLightSource((int) Math.floor(p[0]), (int) Math.floor(p[1]), (int) Math.floor(p[2] / LEVEL),
                level * col[0], level * col[1], level * col[2], radius, LIGHT_LIFE);
        cell.getLamppostPositions().add(l);
        lights++;
    }

    /** Lua (TW/MuzzleFx, Gunworks ExplosionFX): an explosion at (x, y, z level). */
    public static void explosion(float x, float y, float z) {
        try {
            Data d = data;
            advanceClock();
            Blast b = new Blast();
            b.x = x;
            b.y = y;
            b.h = z * LEVEL;
            b.born = time;
            blasts.add(b);
            explosions++;
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
            if (d != null && d.blastColour.length >= 3 && VpLights.takes()) {
                // Viewpoint shows no IsoLightSource: the same light as its point light.
                VpLights.blast(x, y, b.h, d.blastColour, d.blastIntensity, d.blastRange, BLAST_LIGHT_SECONDS);
            } else if (cell != null && d != null && d.blastColour.length >= 3) {
                float s = Math.min(1f, d.blastIntensity / 3f);
                IsoLightSource l = new IsoLightSource((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z),
                        d.blastColour[0] * s, d.blastColour[1] * s, d.blastColour[2] * s, Math.max(1, Math.round(d.blastRange)),
                        Math.round(BLAST_LIGHT_SECONDS * 60f));
                cell.getLamppostPositions().add(l);
                lights++;
            }
        } catch (Throwable e) {
            fail("explosion", e);
        }
    }

    static double advanceClock() {
        long now = System.nanoTime();
        double dt = lastNanos == 0 ? 0 : (now - lastNanos) / 1e9;
        lastNanos = now;
        if (GameTime.isGamePaused()) {
            dt = 0;
        }
        dt = Math.max(0, Math.min(0.1, dt));
        time += dt;
        return dt;
    }

    // ------------------------------------------------------------------ simulation (main thread)

    static float windX, windY;

    static void simulate(float dt) {
        Data d = data;
        ClimateManager climate = ClimateManager.getInstance();
        float wind = climate == null ? 0f : climate.getWindIntensity();
        float angle = climate == null ? 0f : climate.getWindAngleRadians();
        // The way WorldFlares and the thermal gas drift (HeatRenderer): (sin, cos) of the angle.
        windX = (float) Math.sin(angle) * wind * WIND_DRIFT;
        windY = (float) Math.cos(angle) * wind * WIND_DRIFT;
        if (d != null && dt > 0) {
            float keepFume = (float) Math.pow(1f - d.fumeDampen, dt * 30f);
            for (int i = puffs.size() - 1; i >= 0; i--) {
                Puff e = puffs.get(i);
                PSystem s = e.sys;
                float step = s == null ? dt : dt * s.simSpeed;
                if (e.age < 0f) {
                    e.age += step;      // a later burst or a start delay: not born yet
                    continue;
                }
                e.age += step;
                if (e.age >= e.life) {
                    if (smoke(e)) {
                        smokePuffs--;
                    }
                    puffs.set(i, puffs.get(puffs.size() - 1));
                    puffs.remove(puffs.size() - 1);
                    continue;
                }
                // ClampVelocity: limit 0, dampen of the excess per 30 Hz frame.
                float keep = s == null ? keepFume : s.dampen > 0f ? (float) Math.pow(1f - s.dampen, step * 30f) : 1f;
                e.vx *= keep;
                e.vy *= keep;
                e.vh *= keep;
                e.vh -= e.gravity * step;
                // Smoke drifts with the wind (Tarkov: WindZone external forces); glow and sparks do not.
                boolean smoke = s == null || s.kind == PSystem.SMOKE;
                e.x += (e.vx + (smoke ? windX : 0f)) * step;
                e.y += (e.vy + (smoke ? windY : 0f)) * step;
                e.h += e.vh * step;
                e.rot += e.spin * step;
            }
        }
        for (int i = sparks.size() - 1; i >= 0; i--) {
            Spark e = sparks.get(i);
            if (time - e.born > e.life + 0.05) {
                sparks.set(i, sparks.get(sparks.size() - 1));
                sparks.remove(sparks.size() - 1);
            }
        }
        for (int i = blasts.size() - 1; i >= 0; i--) {
            if (time - blasts.get(i).born > blasts.get(i).life) {
                blasts.remove(i);
            }
        }
        // Flashes and smoke trails follow the weapon: its frame every frame.
        var it = shooters.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            IsoGameCharacter c = entry.getKey();
            Shooter s = entry.getValue();
            // MuzzleManager updates a MuzzleSmoke only while it is enabled (a trail runs);
            // its shot factor waits, undecayed, for the next shot otherwise.
            boolean trails = false, waiting = false;
            if (s.trails != null) {
                for (Trail t : s.trails) {
                    trails |= t.on;
                    waiting |= t.factor > 0;
                }
            }
            // The weapon in the hands, while hot: its parts glow and warm (every frame).
            Object item = c == null ? null : c.getPrimaryHandItem();
            Setup hs = item == null ? null : setups.get(item);
            s.overheat = heatOn && hs != null && hs.heat && hs.hot.length > 0 ? overheat(hs, heats.get(item), time) : 0f;
            s.hotSetup = s.overheat >= HOT_MIN ? hs : null;
            if (!jetActive(s) && !trails && s.hotSetup == null) {
                if (time - s.shotEnd > 30 && !waiting) {
                    it.remove();
                }
                continue;
            }
            if (c == null || !frame(c, s)) {
                s.framed = false;
            }
            if (trails && dt > 0 && s.trailSetup != null) {
                for (Trail t : s.trails) {
                    t.acc += dt;
                    while (t.on && t.acc >= SMOKE_STEP) {
                        t.acc -= SMOKE_STEP;
                        step(s, t);
                    }
                    if (!t.on) {
                        t.acc = 0;
                    }
                }
            }
        }
        // The hot weapons in hands of this frame for the glow on their own surface (render thread).
        MuzzleHeatModel.publish(shooters);
    }

    /** Main thread (step): the smoothed muzzle's target (performance pass 23.10.2026: one scratch row). */
    static final float[] STEP_MUZZLE = new float[3];

    /** MuzzleSmoke.LateUpdateValues at the fixed step, in Tarkov metres scaled to tiles. */
    static void step(Shooter s, Trail t) {
        Smoke sm = t.smoke;
        float dt = SMOKE_STEP, k = s.k;
        float[] muzzle = STEP_MUZZLE;
        if (s.framed) {
            float[] m = s.trailSetup.nodes[t.node].m;
            place(s.frame, m[3], m[7], m[11], muzzle);
        } else {
            System.arraycopy(t.pos, 0, muzzle, 0, 3);
        }
        if (!t.placed) {
            System.arraycopy(muzzle, 0, t.pos, 0, 3);
            System.arraycopy(muzzle, 0, t.prev, 0, 3);
            t.placed = true;
        }
        t.factor = Math.max(0f, t.factor - dt * sm.drop);
        System.arraycopy(t.pos, 0, t.prev, 0, 3);
        float speedNow = 0;
        for (int a = 0; a < 3; a++) {
            t.pos[a] += (muzzle[a] - t.pos[a]) * 0.125f;
            t.vel[a] += ((t.pos[a] - t.prev[a]) / dt - t.vel[a]) / 32f;
            speedNow += t.vel[a] * t.vel[a];
        }
        // Speeds of the curves are Tarkov m/s.
        t.speed += ((float) Math.sqrt(speedNow) / Math.max(1e-6f, k) * sm.speedMult - t.speed) * 0.25f;
        float num = t.count * t.invLen;
        t.strength = num >= 1f ? 0f : sm.smoke.eval(num) * sm.strength;
        ArrayList<Seg> segs = t.segs;
        float brake2 = sm.brake * k * sm.brake * k;
        if (t.strength > 0.001f) {
            if (segs.isEmpty()) {
                addSeg(t, false);
                addSeg(t, true);
            } else {
                System.arraycopy(t.pos, 0, segs.get(0).p, 0, 3);
            }
            if (segs.size() >= 2 && dist2(segs.get(0).p, segs.get(1).p) > brake2) {
                Seg v = segs.get(0);
                t.count++;
                t.turbPhase += sm.speedTurbDensity.eval(t.speed) * sm.turbDensity;
                // Turbulence across the view: cross(velocity + up 0.1, camera forward).
                float lx = t.vel[0] / k, ly = t.vel[1] / k, lh = t.vel[2] / k + 0.1f;
                float fx = -0.5f, fy = -0.5f, fh = -0.7071f;
                float cx = ly * fh - lh * fy, cy = lh * fx - lx * fh, ch = lx * fy - ly * fx;
                float cn = (float) Math.sqrt(cx * cx + cy * cy + ch * ch);
                float amount = noise(t.turbPhase) * sm.turbIntensity * k / Math.max(1e-6f, cn);
                v.turb[0] = cx * amount;
                v.turb[1] = cy * amount;
                v.turb[2] = ch * amount;
                float strength = sm.speedTurbStrength.eval(t.speed);
                for (int a = 0; a < 3; a++) {
                    v.v[a] += (t.vel[a] + v.turb[a] * strength) * sm.velocity;
                }
                v.diffusion = sm.speedStartDiffusion.eval(t.speed) * sm.smokeEnd;
                v.skip = false;
                addSeg(t, true);
            }
        } else {
            if (segs.size() >= 2 && segs.get(0).skip && dist2(segs.get(0).p, segs.get(1).p) > brake2) {
                segs.get(0).skip = false;
            }
        }
        if (!segs.isEmpty()) {
            if (segs.size() == 2) {
                if (segs.get(0).diffusion >= sm.smokeEnd) {
                    freeSegs(segs);
                }
            } else if (segs.size() > 2 && segs.get(segs.size() - 2).diffusion >= sm.smokeEnd) {
                freeSeg(segs.remove(segs.size() - 1));
            }
        } else {
            // MuzzleSmoke.Clear: nothing left, disabled until the next start.
            t.on = false;
            t.strength = 0f;
            return;
        }
        // Update: gravity (negative: rises), drag per frame, diffusion by the travelled distance.
        float lift = sm.gravity * dt * k;
        for (Seg v : segs) {
            if (v.skip) {
                continue;
            }
            v.v[2] -= lift;
            float dx = 0, d2 = 0;
            for (int a = 0; a < 3; a++) {
                v.v[a] *= sm.drag;
                dx = (v.v[a] + v.turb[a] + (a == 0 ? windX * 0.5f : a == 1 ? windY * 0.5f : 0f)) * dt;
                v.p[a] += dx;
                float m = dx / k;
                d2 += m * m;
            }
            v.diffusion += d2 * sm.diffByVel;
        }
    }

    /**
     * Performance pass 23.10.2026: segments dropped by the trail (main thread only, nothing else holds them) are
     * reused, reset to a new one's values, instead of a new segment and its three rows per 60 Hz step.
     */
    static final java.util.ArrayDeque<Seg> SEG_POOL = new java.util.ArrayDeque<>();

    static void freeSeg(Seg v) {
        if (v != null && SEG_POOL.size() < 512) {
            SEG_POOL.push(v);
        }
    }

    static void freeSegs(ArrayList<Seg> segs) {
        for (int i = 0; i < segs.size(); i++) {
            freeSeg(segs.get(i));
        }
        segs.clear();
    }

    static Seg newSeg() {
        Seg v = SEG_POOL.poll();
        if (v == null) {
            return new Seg();
        }
        v.skip = false;
        for (int a = 0; a < 3; a++) {
            v.p[a] = 0f;
            v.v[a] = 0f;
            v.turb[a] = 0f;
        }
        v.alpha = v.diffusion = v.yuv = 0f;
        return v;
    }

    static void addSeg(Trail t, boolean skip) {
        Seg v = newSeg();
        System.arraycopy(t.pos, 0, v.p, 0, 3);
        v.alpha = Math.max(0f, Math.min(1f, t.strength * t.smoke.speedSmokeStrength.eval(t.speed)));
        v.alpha = (int) (v.alpha * 255f) / 255f;
        v.yuv = t.yuv++;
        v.skip = skip;
        t.segs.add(0, v);
    }

    static float dist2(float[] a, float[] b) {
        float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return x * x + y * y + z * z;
    }

    /** MuzzleSmoke turbulence: a smooth value noise of Tarkov's integer hash. */
    static float noise(float t) {
        int n = (int) Math.floor(t);
        float a = hash(n + 8324234) * 0.001f, b = hash(n + 1 + 8324234) * 0.001f;
        float s = t - n;
        s = s * s * (3f - 2f * s);
        return a + (b - a) * s;
    }

    static int hash(int input) {
        input ^= input << 7;
        input ^= input >> 3;
        int abs = input < 0 ? -input : input;
        return -1000 + abs % 2000;
    }

    // ------------------------------------------------------------------ drawing

    /** IsoWorld.render has drawn player p's world frame (main thread): advance and queue the effects. */
    public static void afterWorld() {
        try (PerfProbe.Scope effectsPerf = PerfProbe.cpu(PerfProbe.FX_UPDATE)) {
            int p = IsoCamera.frameState.playerIndex;
            advanceClock();
            double dt = time - simTime;
            simTime = time;
            if (dt > 0) {
                lastFrameDt = dt;
            }
            simulate((float) dt);
            if (ViewpointPass.accepting() && ViewpointBridge.perspective()) {
                // The Tarkov lights of this Viewpoint frame (its snapshot is done), before the smoke they light.
                try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.LIGHTS)) {
                    VpLights.frame(ViewpointPass.open);
                } catch (Throwable e) {
                    ViewpointPass.fail("lights", e);
                }
            }
            ExplosionFx.afterWorld(p);
            if (data == null || p < 0 || p > 3 || puffs.isEmpty() && sparks.isEmpty() && !anyActive()) {
                return;
            }
            // Same live simulation and emitters; the pass owns projection, clipping and depth.
            if (ViewpointBridge.perspective()) {
                if (ViewpointPass.accepting()) {
                    long start = ViewpointPass.profile ? System.nanoTime() : 0;
                    try {
                        buildVp(p);
                        vpFrames++;
                    } catch (Throwable e) {
                        ViewpointPass.fail("muzzle frame", e);
                    }
                    if (start != 0) {
                        long ns = System.nanoTime() - start;
                        vpBuildNanos += ns;
                        vpMaxBuildNanos = Math.max(vpMaxBuildNanos, ns);
                    }
                } else {
                    vpSkipped++;
                }
                return;
            }
            Drawer drawer;
            try {
                drawer = build(p);
            } finally {
                endSquares();
            }
            if (drawer != null) {
                SpriteRenderer.instance.drawGeneric(drawer);
                drawers++;
            }
        } catch (Throwable e) {
            fail("frame", e);
        }
    }

    static boolean anyActive() {
        for (Shooter s : shooters.values()) {
            if (jetActive(s) || s.hotSetup != null) {
                return true;
            }
            if (s.trails != null) {
                for (Trail t : s.trails) {
                    if (!t.segs.isEmpty()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Quads of one frame: x, y, u, v per corner, r, g, b, a per corner, then z (33 floats). */
    static final class Quads {
        float[] q = new float[STRIDE * 64];
        int n;
        // Per quad: its share of a scope's field (see field()); set before its quads are added.
        float[] fw = new float[64];
        float field;
        int fieldCount;
        final ArrayList<String> textures = new ArrayList<>();
        final ArrayList<Integer> starts = new ArrayList<>();
        final ArrayList<Integer> kinds = new ArrayList<>();
        final ArrayList<float[]> params = new ArrayList<>();

        void begin(int kind, String texture, float[] param) {
            kinds.add(kind);
            textures.add(texture);
            starts.add(n);
            params.add(param);
        }

        float[] next() {
            if ((n + 1) * STRIDE > q.length) {
                q = java.util.Arrays.copyOf(q, q.length * 2);
            }
            if (n + 1 > fw.length) {
                fw = java.util.Arrays.copyOf(fw, fw.length * 2);
            }
            fw[n] = field;
            fieldCount += field > 0f ? 1 : 0;
            n++;
            return q;
        }

        void clear() {
            n = 0;
            field = 0f;
            fieldCount = 0;
            textures.clear();
            starts.clear();
            kinds.clear();
            params.clear();
        }
    }

    static final int KIND_SMOKE = 0, KIND_JET = 1, KIND_SPARK = 2, KIND_STRAND = 3, KIND_MARKER = 4;
    /** Floats per quad; the last one is the z of every corner (a puff's distortion, see quad). */
    static final int STRIDE = 33;

    static void corner(float[] q, int base, int c, float x, float y, float u, float v, float r, float g, float b, float a) {
        int o = base + c * 4;
        q[o] = x;
        q[o + 1] = y;
        q[o + 2] = u;
        q[o + 3] = v;
        int col = base + 16 + c * 4;
        q[col] = r;
        q[col + 1] = g;
        q[col + 2] = b;
        q[col + 3] = a;
    }

    static float sx(float x, float y, float ts, float offX) {
        return (x - y) * 32f * ts - offX;
    }

    static float sy(float x, float y, float h, float ts, float offY) {
        return (x + y) * 16f * ts - h / LEVEL * 96f * ts - offY;
    }

    /** The local player index of a character, or -1. */
    static int viewer(IsoGameCharacter c) {
        return c instanceof IsoPlayer pl && pl.isLocalPlayer() ? pl.getIndex() : -1;
    }

    static float visibility(IsoGameCharacter c, float x, float y, float h, int p) {
        if (viewer(c) == p) {
            return 1f;
        }
        if (c != null && c.getCurrentSquare() != null) {
            return Math.max(0f, Math.min(1f, c.getAlpha(p)));
        }
        return visibility((WeakReference<IsoGameCharacter>) null, -1, x, y, h, p);
    }

    static float visibility(WeakReference<IsoGameCharacter> owner, int ownerPlayer, float x, float y, float h, int p) {
        if (ownerPlayer == p) {
            return 1f;
        }
        IsoGameCharacter c = owner == null ? null : owner.get();
        if (c != null && c.getCurrentSquare() != null) {
            return Math.max(0f, Math.min(1f, c.getAlpha(p)));
        }
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        IsoGridSquare sq = cell == null ? null : cell.getGridSquare((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(h / LEVEL));
        return sq != null && sq.isCanSee(p) ? 1f : 0f;
    }

    static final float[] light = new float[3];

    /**
     * Performance pass 23.10.2026: while build() runs (main thread, no world update in between) the square at the
     * same coordinates of the same cell is the same object; puffs sorted by depth and trail points share squares.
     * Its light is still read live from it.
     */
    static boolean squareCache, squareCached;
    static int squareX, squareY, squareZ;
    static IsoCell squareCell;
    static IsoGridSquare squareAt;

    static void lightAt(float x, float y, float h, int p) {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(h / LEVEL);
        IsoGridSquare sq;
        if (squareCache && squareCached && cell == squareCell && ix == squareX && iy == squareY && iz == squareZ) {
            sq = squareAt;
        } else {
            sq = cell == null ? null : cell.getGridSquare(ix, iy, iz);
            if (squareCache) {
                squareCell = cell;
                squareX = ix;
                squareY = iy;
                squareZ = iz;
                squareAt = sq;
                squareCached = true;
            }
        }
        ColorInfo c = sq == null ? null : sq.getLightInfo(p);
        if (c == null) {
            light[0] = light[1] = light[2] = 0.5f;
        } else {
            light[0] = Math.min(1f, c.r);
            light[1] = Math.min(1f, c.g);
            light[2] = Math.min(1f, c.b);
        }
    }

    /**
     * The light of smoke in Viewpoint's view: Tarkov's (Particles/Smoke Distorted:
     * max(ambient, _Ambient) + point lights) with Viewpoint's light of the square
     * and this frame's point lights (VpLights.smoke), in the unit its scene is lit
     * in; PZ's light of the square only where Viewpoint has none (or the Tarkov
     * lights are off). PZ's square light is not Viewpoint's: far brighter at night.
     */
    static void vpLightAt(float x, float y, float h, int p, float ambient) {
        if (!VpLights.smoke(x, y, h, ambient, light)) {
            lightAt(x, y, h, p);
        }
    }

    /**
     * Scopes: the share of player p's scope field (night vision, thermal) an
     * effect at (x, y) stands in. The field is the cone on the floor, but an
     * effect stands above it (the flash at the muzzle, smoke, sparks): its
     * point on the floor decides, with the cone's soft edge. The viewer's own
     * weapon fires in front of the scope: its effects near the apex count
     * whole (the apex is the body, the muzzle beside the axis).
     */
    static float field(int p, float x, float y, int ownerPlayer) {
        OpticState.View v = p >= 0 && p < 4 ? OpticState.views[p] : null;
        if (v == null || v.kind == 0) {
            return 0f;
        }
        float dx = x - v.px, dy = y - v.py, dist = (float) Math.sqrt(dx * dx + dy * dy);
        float w;
        if (dist < 0.3f) {
            w = 1f;
        } else {
            double a = Math.abs(((Math.atan2(dy, dx) - v.base + Math.PI) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI) - Math.PI);
            w = 1f - smooth(v.half, v.half + v.edge, (float) a);
        }
        if (ownerPlayer == p) {
            w = Math.max(w, 1f - smooth(OWN_NEAR, OWN_FAR, dist));
        }
        return w;
    }

    /** The viewer's own effects count whole within OWN_NEAR of the apex, none past OWN_FAR (tiles). */
    static final float OWN_NEAR = 1.5f, OWN_FAR = 2.5f;

    static float smooth(float e0, float e1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - e0) / Math.max(1e-6f, e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    /** Render thread: the field buffer (world-frame texels) and the frame stamp it was last drawn for, per player. */
    static TextureFBO fieldFbo;
    static final int[] fieldDrawn = new int[4];

    /** Render thread: the field buffer's texture, or null before the first one. */
    static Texture fieldTexture() {
        return fieldFbo == null ? null : (Texture) fieldFbo.getTexture();
    }

    /** Render thread: the field buffer at the size of the world frame (made or remade), or null without that frame. */
    static TextureFBO fieldBuffer() {
        zombie.core.textures.MultiTextureFBO2 buffer = Core.getInstance().offscreenBuffer;
        Texture world = buffer == null ? null : (Texture) buffer.getTexture(0);
        if (world == null) {
            return null;
        }
        int whw = world.getWidthHW(), hhw = world.getHeightHW();
        if (fieldFbo == null || fieldFbo.getWidth() != whw || fieldFbo.getHeight() != hhw) {
            if (fieldFbo != null) {
                fieldFbo.destroy();
            }
            fieldFbo = new TextureFBO(new Texture(whw, hhw, 16), false);
        }
        return fieldFbo;
    }

    /** Main thread (build): the run parameters of a pivot system's or the fume's smoke, kept while their values are. */
    static float[] runParam(float[] kept, float distortion, float animation) {
        return kept != null && kept[0] == distortion && kept[1] == animation ? kept : new float[] {distortion, animation};
    }

    static final float[] COL = new float[4];

    /** The end of a build's shared square lookups (lightAt); its caller runs it whatever happened. */
    static void endSquares() {
        squareCache = false;
        squareCached = false;
        squareAt = null;
        squareCell = null;
    }

    /**
     * Performance pass 23.10.2026: a pooled drawer (Drawer.take; its quads keep their grown arrays), given back at once
     * when there is nothing to draw; consecutive lookups of the same square share it while building (lightAt; the
     * caller ends that with endSquares).
     */
    static Drawer build(int p) {
        Data d = data;
        Drawer dr = Drawer.take();
        squareCache = true;
        squareCached = false;
        dr.player = p;
        dr.zoom = Core.getInstance().getZoom(p);
        dr.w = IsoCamera.getScreenWidth(p);
        dr.h = IsoCamera.getScreenHeight(p);
        dr.time = (float) (time % 1000.0);
        float ts = Core.tileScale, offX = IsoCamera.getOffX(p), offY = IsoCamera.getOffY(p);
        float px = PX * ts;
        Quads qs = dr.quads;
        float[] col = COL;
        // Smoke puffs (alpha blend, lit), back to front by screen depth.
        if (!puffs.isEmpty()) {
            puffs.sort((a, b) -> Float.compare(a.x + a.y, b.x + b.y));
            // The pivot effects' systems, one run each (own texture, curves and blend).
            for (Pivot pv : d.pivots) {
                for (int si = 0; pv != null && si < pv.systems.length; si++) {
                    PSystem s = pv.systems[si];
                    boolean any = false;
                    for (Puff e : puffs) {
                        if (e.sys == s && e.age >= 0f) {
                            any = true;
                            break;
                        }
                    }
                    if (!any) {
                        continue;
                    }
                    if (s.kind == PSystem.SMOKE) {
                        qs.begin(KIND_SMOKE, s.texture, s.runParam = runParam(s.runParam, s.distortion, s.animation));
                    } else {
                        qs.begin(KIND_SPARK, s.texture, null);
                    }
                    for (Puff e : puffs) {
                        if (e.sys == s && e.age >= 0f) {
                            qs.field = field(p, e.x, e.y, e.ownerPlayer);
                            systemQuad(qs, e, s, p, ts, offX, offY, px, col, dr.zoom);
                        }
                    }
                }
            }
            qs.begin(KIND_SMOKE, d.fumeTexture, d.fumeParam = runParam(d.fumeParam, d.fumeDistortion, d.fumeSpeed));
            for (Puff e : puffs) {
                if (e.sys != null) {
                    continue;
                }
                float vis = visibility(e.owner, e.ownerPlayer, e.x, e.y, e.h, p);
                if (vis <= 0.001f) {
                    continue;
                }
                float n = e.age / e.life;
                float size = e.size * d.fumeSize.eval(n, e.rnd);
                d.fumeColour.eval(n, e.rnd, col);
                float a = e.a * col[3] * (d.fumeTint.length > 3 ? d.fumeTint[3] : 1f) * vis;
                if (a <= 0.002f || size <= 0) {
                    continue;
                }
                lightAt(e.x, e.y, e.h, p);
                float bright = e.r * col[0];
                float r = light[0] * bright * d.fumeTint[0], g = light[1] * bright * d.fumeTint[1], b = light[2] * bright * d.fumeTint[2];
                // vertex G (distortion) of Particles/Smoke Distorted rides in the free z
                float distortion = e.g * col[1];
                float cx = sx(e.x, e.y, ts, offX), cy = sy(e.x, e.y, e.h, ts, offY);
                float half = size * 0.5f * px, cs = (float) Math.cos(e.rot) * half, sn = (float) Math.sin(e.rot) * half;
                qs.field = field(p, e.x, e.y, e.ownerPlayer);
                float[] q = qs.next();
                int o = (qs.n - 1) * STRIDE;
                // texture up = screen up (GL v 0 is the image's top row)
                corner(q, o, 0, cx - cs + sn, cy - sn - cs, 0, 0, r, g, b, a);
                corner(q, o, 1, cx + cs + sn, cy + sn - cs, 1, 0, r, g, b, a);
                corner(q, o, 2, cx + cs - sn, cy + sn + cs, 1, 1, r, g, b, a);
                corner(q, o, 3, cx - cs - sn, cy - sn + cs, 0, 1, r, g, b, a);
                q[o + 32] = distortion;
            }
        }
        // Barrel smoke trails (alpha blend).
        for (var entry : shooters.entrySet()) {
            Shooter s = entry.getValue();
            if (s.trails == null) {
                continue;
            }
            for (Trail t : s.trails) {
                if (t.segs.size() < 2 || t.smoke.strand == null) {
                    continue;
                }
                IsoGameCharacter c = entry.getKey();
                float[] f0 = t.segs.get(0).p;
                float vis = visibility(c, f0[0], f0[1], f0[2], p);
                if (vis <= 0.001f) {
                    continue;
                }
                qs.field = field(p, f0[0], f0[1], viewer(c));
                strand(qs, t, s.k, vis, p, ts, offX, offY, px);
            }
        }
        // Jets (additive).
        for (var entry : shooters.entrySet()) {
            Shooter s = entry.getValue();
            if (!jetActive(s) || !s.framed || s.setup == null || s.setup.mgr == null || !s.setup.jets) {
                continue;
            }
            IsoGameCharacter c = entry.getKey();
            float vis = visibility(c, s.frame[0], s.frame[1], s.frame[2] * LEVEL, p);
            if (vis > 0.001f) {
                shooterViewX = sx(c.getX(), c.getY(), ts, offX);
                shooterViewY = sy(c.getX(), c.getY(), c.getZ() * LEVEL, ts, offY);
                qs.field = field(p, s.frame[0], s.frame[1], viewer(c));
                jets(qs, s, vis, ts, offX, offY, px);
            }
        }
        // Hot parts: their glow is drawn on their own mesh (MuzzleHeatModel.iso); this flat glow over the
        // lit zone along the bore only stands in when that path failed or QA asks for it.
        if (d.hotTexture != null && !MuzzleHeatModel.isoSurface()) {
            boolean begun = false;
            float[] a = new float[3], b = new float[3], ctr = new float[3];
            for (var entry : shooters.entrySet()) {
                Shooter s = entry.getValue();
                if (s.hotSetup == null || !s.framed) {
                    continue;
                }
                IsoGameCharacter c = entry.getKey();
                float vis = visibility(c, s.frame[0], s.frame[1], s.frame[2] * LEVEL, p);
                // On the side of a part w = (1 - |(p - c) / size|) x glow is about 0.5 x glow (the mask
                // carries the fall to the ends of the lit zone).
                float w = 0.5f * heatGlow(s.overheat);
                if (vis <= 0.001f || w <= 1e-4f) {
                    continue;
                }
                float unit = (float) Math.sqrt(s.frame[3] * s.frame[3] + s.frame[4] * s.frame[4] + s.frame[5] * s.frame[5] * LEVEL * LEVEL);
                for (HotNode h : s.hotSetup.hot) {
                    float k = w * d.heatVisible * h.visible * h.spec * vis;
                    float r = (h.c1[0] + (h.c2[0] - h.c1[0]) * w) * k, g = (h.c1[1] + (h.c2[1] - h.c1[1]) * w) * k,
                            bl = (h.c1[2] + (h.c2[2] - h.c1[2]) * w) * k;
                    if (Math.max(r, Math.max(g, bl)) < 0.002f) {
                        continue;
                    }
                    if (!begun) {
                        qs.begin(KIND_SPARK, d.hotTexture, null);
                        begun = true;
                    }
                    place(s.frame, h.a[0], h.a[1], h.a[2], a);
                    place(s.frame, h.b[0], h.b[1], h.b[2], b);
                    place(s.frame, (h.a[0] + h.b[0]) * 0.5f, (h.a[1] + h.b[1]) * 0.5f, (h.a[2] + h.b[2]) * 0.5f, ctr);
                    float x0 = sx(a[0], a[1], ts, offX), y0 = sy(a[0], a[1], a[2], ts, offY);
                    float x1 = sx(b[0], b[1], ts, offX), y1 = sy(b[0], b[1], b[2], ts, offY);
                    float halfW = Math.max(h.half * unit * px, 0.5f * dr.zoom);
                    float dx = x1 - x0, dy = y1 - y0, dn = (float) Math.sqrt(dx * dx + dy * dy);
                    if (dn < 2 * halfW) {
                        // seen end-on: at least a round spot as wide as the part
                        float ux = dn < 1e-4f ? 1f : dx / dn, uy = dn < 1e-4f ? 0f : dy / dn, mx = (x0 + x1) * 0.5f, my = (y0 + y1) * 0.5f;
                        x0 = mx - ux * halfW;
                        y0 = my - uy * halfW;
                        x1 = mx + ux * halfW;
                        y1 = my + uy * halfW;
                        dx = x1 - x0;
                        dy = y1 - y0;
                        dn = 2 * halfW;
                    }
                    float nx = -dy / dn * halfW, ny = dx / dn * halfW;
                    qs.field = field(p, ctr[0], ctr[1], viewer(c));
                    float[] q = qs.next();
                    int o = (qs.n - 1) * STRIDE;
                    q[o + 32] = 0f;
                    corner(q, o, 0, x0 - nx, y0 - ny, 0, 0, r, g, bl, 1);
                    corner(q, o, 1, x1 - nx, y1 - ny, 1, 0, r, g, bl, 1);
                    corner(q, o, 2, x1 + nx, y1 + ny, 1, 1, r, g, bl, 1);
                    corner(q, o, 3, x0 + nx, y0 + ny, 0, 1, r, g, bl, 1);
                    hotQuads++;
                }
            }
        }
        // Sparks (additive streaks).
        if (!sparks.isEmpty()) {
            qs.begin(KIND_SPARK, d.sparkTexture, null);
            float frameDt = Math.max(1f / 30f, (float) lastFrameDt);
            float[] a = new float[3], b = new float[3];
            for (Spark e : sparks) {
                double age = time - e.born;
                if (age <= 0 || age >= e.life) {
                    continue;
                }
                float vis = visibility(e.owner, e.ownerPlayer, e.x, e.y, e.h, p);
                if (vis <= 0.001f) {
                    continue;
                }
                float head = (float) age, tail = (float) Math.max(0, age - frameDt);
                sparkAt(e, head, b);
                sparkAt(e, tail, a);
                float fh = head / e.life, ft = tail / e.life;
                float eh = 4 * fh * (1 - fh) * e.emission * vis, et = 4 * ft * (1 - ft) * e.emission * vis;
                float x0 = sx(a[0], a[1], ts, offX), y0 = sy(a[0], a[1], a[2], ts, offY);
                float x1 = sx(b[0], b[1], ts, offX), y1 = sy(b[0], b[1], b[2], ts, offY);
                float dx = x1 - x0, dy = y1 - y0, dn = (float) Math.sqrt(dx * dx + dy * dy);
                if (dn < 1e-4f) {
                    dx = 1;
                    dy = 0;
                    dn = 1;
                }
                // At least a 1-pixel line (in world-frame pixels) with the same total light.
                float halfW = e.width * px, minHalf = 0.5f * dr.zoom;
                float energy = halfW < minHalf ? halfW / minHalf : 1f;
                halfW = Math.max(halfW, minHalf);
                float nx = -dy / dn * halfW, ny = dx / dn * halfW;
                float hdr = d.sparkHdr * energy;
                float[] tint = d.sparkTint;
                qs.field = field(p, b[0], b[1], e.ownerPlayer);
                float[] q = qs.next();
                int o = (qs.n - 1) * STRIDE;
                q[o + 32] = 0f;
                corner(q, o, 0, x0 - nx, y0 - ny, 0, 0, tint[0] * et * hdr, tint[1] * et * hdr, tint[2] * et * hdr, 1);
                corner(q, o, 1, x1 - nx, y1 - ny, 1, 0, tint[0] * eh * hdr, tint[1] * eh * hdr, tint[2] * eh * hdr, 1);
                corner(q, o, 2, x1 + nx, y1 + ny, 1, 1, tint[0] * eh * hdr, tint[1] * eh * hdr, tint[2] * eh * hdr, 1);
                corner(q, o, 3, x0 + nx, y0 + ny, 0, 1, tint[0] * et * hdr, tint[1] * et * hdr, tint[2] * et * hdr, 1);
            }
        }
        // The hot weapons a scope of this player sees: their parts were drawn into its field buffer with the
        // characters (MuzzleHeatModel, seeded before them); the field pass keeps them for the scope composite.
        dr.surfaceField = MuzzleHeatModel.scopeShares[p] != null;
        if (qs.n == 0 && !dr.surfaceField) {
            // nothing to draw: the drawer goes back at once, never queued
            qs.clear();
            dr.runTextures.clear();
            Drawer.release(dr);
            return null;
        }
        if (qs.fieldCount > 0 || dr.surfaceField) {
            // A scope of this player sees some of them: the field buffer is drawn too.
            OpticState.View v = OpticState.views[p];
            zombie.core.textures.MultiTextureFBO2 buffer = Core.getInstance().offscreenBuffer;
            dr.world = buffer == null ? null : buffer.getTexture(0);
            dr.fieldThermal = v != null && v.kind == OpticState.THERMAL;
            dr.stamp = IsoCamera.frameState.frameCount;
            fieldQuads += qs.fieldCount;
        }
        if (qaProbe) {
            // the centre of the first quad of the frame (corner 0 and 2 average)
            // a jet quad when there is one, else the first quad
            int run = qs.kinds.indexOf(KIND_JET);
            int o = (run >= 0 ? qs.starts.get(run) : 0) * STRIDE;
            dr.probe = new float[] {(qs.q[o] + qs.q[o + 8]) * 0.5f, (qs.q[o + 1] + qs.q[o + 9]) * 0.5f, run >= 0 ? 1f : 0f};
        }
        dr.resolve();
        return dr;
    }

    static double lastFrameDt;

    // ------------------------------------------------------------------ Viewpoint (main thread)

    static final float[] VP_UV = {0, 0, 1, 0, 1, 1, 0, 1};

    static float[] vpColour(float r, float g, float b, float a) {
        return new float[] {r, g, b, a, r, g, b, a, r, g, b, a, r, g, b, a};
    }

    /**
     * Performance pass 23.10.2026: the per-primitive arrays of the Viewpoint build are scratch rows of the main
     * thread - ViewpointPass.muzzle reads the centres and copies offsets, uv, colours and widths into its quad
     * before it returns (a change to that contract must give these back their own arrays).
     */
    static final float[] VP_CENTRES = new float[12], VP_OFFSETS = new float[8], VP_WIDTHS = new float[2], VP_RGBA = new float[16],
            VP_UV_SCRATCH = new float[8], VP_STRAND_UV = new float[8], VP_END_A = new float[3], VP_END_B = new float[3];
    static final float[] JET_CORNERS = new float[8], JET_UV = new float[8];

    static float[] vpColourInto(float[] out, float r, float g, float b, float a) {
        for (int v = 0; v < 4; v++) {
            out[v * 4] = r;
            out[v * 4 + 1] = g;
            out[v * 4 + 2] = b;
            out[v * 4 + 3] = a;
        }
        return out;
    }

    static float[] vpCentres(float x, float y, float h) {
        float[] c = VP_CENTRES;
        for (int v = 0; v < 4; v++) {
            c[v * 3] = x;
            c[v * 3 + 1] = y;
            c[v * 3 + 2] = h;
        }
        return c;
    }

    static void vpBillboard(float x, float y, float h, float size, float rot, float[] uv, float[] rgba,
                            String texture, int kind, float distortion, float scroll) {
        float cs = (float) Math.cos(rot) * size * 0.5f, sn = (float) Math.sin(rot) * size * 0.5f;
        float[] o = VP_OFFSETS;
        o[0] = -cs+sn; o[1] = sn+cs; o[2] = cs+sn; o[3] = -sn+cs; o[4] = cs-sn; o[5] = -sn-cs; o[6] = -cs-sn; o[7] = sn-cs;
        vpQuad(vpCentres(x, y, h), o, uv, rgba, null, false, texture, kind, distortion, scroll);
    }

    static void vpRibbon(float[] a, float[] b, float halfA, float halfB, float[] uv, float[] rgba, String texture,
                         int kind, boolean endCap) {
        float[] c = VP_CENTRES;
        c[0] = a[0]; c[1] = a[1]; c[2] = a[2]; c[3] = b[0]; c[4] = b[1]; c[5] = b[2];
        c[6] = b[0]; c[7] = b[1]; c[8] = b[2]; c[9] = a[0]; c[10] = a[1]; c[11] = a[2];
        VP_WIDTHS[0] = halfA;
        VP_WIDTHS[1] = halfB;
        vpQuad(c, null, uv, rgba, VP_WIDTHS, endCap, texture, kind, 0f, 0f);
    }

    static void vpQuad(float[] centres, float[] offsets, float[] uv, float[] rgba, float[] widths, boolean endCap,
                       String texture, int kind, float distortion, float scroll) {
        if (ViewpointPass.muzzle(centres, offsets, uv, rgba, widths, endCap, texture,
                kind == KIND_SMOKE ? data.noiseTexture : null, kind, distortion, scroll)) {
            vpQuads++;
        }
    }

    /** No PZ isometric visibility/alpha here: VP's depth hides walls and the weapon in both views, including MP. */
    static void buildVp(int player) {
        squareCache = true;
        squareCached = false;
        try {
            buildVpAnchored(player);
        } finally {
            ViewpointPass.anchorOff();
            VP_ORDER.clear();
            endSquares();
        }
    }

    /**
     * Performance pass 23.10.2026: the puffs far to near by their distance of this frame, kept on each puff: the same
     * stable sort of the same copy by the same keys as with a map of boxed distances, in one reused list.
     */
    static final ArrayList<Puff> VP_ORDER = new ArrayList<>();
    static final java.util.Comparator<Puff> FAR_FIRST = (a, b) -> Double.compare(b.vpDistance, a.vpDistance);

    static void buildVpAnchored(int player) {
        Data d = data;
        float[] col = COL;
        ArrayList<Puff> ordered = VP_ORDER;
        ordered.clear();
        ordered.addAll(puffs);
        for (Puff e : ordered) {
            e.vpDistance = ViewpointPass.distance(e.x, e.y, e.h);
        }
        ordered.sort(FAR_FIRST);
        for (Puff e : ordered) {
            if (e.age < 0f || e.age >= e.life) {
                continue;
            }
            vpAnchor(e.ax, e.ay);
            if (e.sys != null) {
                systemQuad(null, e, e.sys, player, 0, 0, 0, 0, col, 1, true);
                continue;
            }
            float n = e.age / e.life, size = e.size * d.fumeSize.eval(n, e.rnd);
            d.fumeColour.eval(n, e.rnd, col);
            float alpha = e.a * col[3] * (d.fumeTint.length > 3 ? d.fumeTint[3] : 1f);
            if (size <= 0f || alpha <= 0.002f) {
                continue;
            }
            vpLightAt(e.x, e.y, e.h, player, d.fumeAmbient);
            float bright = e.r * col[0];
            vpBillboard(e.x, e.y, e.h, size, e.rot, VP_UV,
                    vpColourInto(VP_RGBA, light[0] * bright * d.fumeTint[0], light[1] * bright * d.fumeTint[1], light[2] * bright * d.fumeTint[2], alpha),
                    d.fumeTexture, KIND_SMOKE, e.g * col[1] * d.fumeDistortion, (float) (time % 1000) * d.fumeSpeed);
        }
        boolean marked = false;
        for (Shooter s : shooters.values()) {
            // the effects at the weapon: lifted by the relief where its shooter's drawn model stands
            IsoGameCharacter who = s.who.get();
            vpAnchor(who == null ? Float.NaN : who.getX(), who == null ? Float.NaN : who.getY());
            if (s.trails != null) {
                for (Trail t : s.trails) {
                    vpStrand(t, s.k, player);
                }
            }
            if (jetActive(s) && s.framed && s.setup != null && s.setup.mgr != null && s.setup.jets) {
                jets(null, s, 1, 0, 0, 0, 0, true);
            }
            if (ViewpointPass.muzzleMarker && !marked && s.framed && s.setup != null && s.setup.nodes.length > 0) {
                float[] n = s.setup.nodes[0].m, centre = new float[3];
                place(s.frame, n[3], n[7], n[11], centre);
                // QA only: 20 Tarkov centimetres, through the normal muzzle geometry and depth path.
                vpBillboard(centre[0], centre[1], centre[2], 0.2f * s.k, 0, VP_UV,
                        vpColour(1, 0, 1, 1), null, KIND_MARKER, 0, 0);
                marked = true;
            }
            // The glow on the hot parts' own mesh is drawn from the frame's model records (MuzzleHeatModel.vp);
            // this ribbon over the lit zone along the bore only stands in when that path failed or QA asks.
            if (s.hotSetup != null && s.framed && d.hotTexture != null && !MuzzleHeatModel.vpSurface()) {
                float w = 0.5f * heatGlow(s.overheat);
                float[] a = VP_END_A, b = VP_END_B;
                for (HotNode h : s.hotSetup.hot) {
                    float k = w * d.heatVisible * h.visible * h.spec;
                    float r = (h.c1[0] + (h.c2[0] - h.c1[0]) * w) * k;
                    float g = (h.c1[1] + (h.c2[1] - h.c1[1]) * w) * k;
                    float bl = (h.c1[2] + (h.c2[2] - h.c1[2]) * w) * k;
                    if (Math.max(r, Math.max(g, bl)) < 0.002f) {
                        continue;
                    }
                    place(s.frame, h.a[0], h.a[1], h.a[2], a);
                    place(s.frame, h.b[0], h.b[1], h.b[2], b);
                    float half = h.half * axisLength(s.frame);
                    vpRibbon(a, b, half, half, VP_UV, vpColourInto(VP_RGBA, r, g, bl, 1), d.hotTexture, KIND_SPARK, true);
                    hotQuads++;
                }
            }
        }
        float frameDt = Math.max(1f / 30f, (float) lastFrameDt);
        float[] a = VP_END_A, b = VP_END_B;
        for (Spark e : sparks) {
            float head = (float) (time - e.born), tail = Math.max(0, head - frameDt);
            if (head <= 0 || head >= e.life) {
                continue;
            }
            vpAnchor(e.ax, e.ay);
            sparkAt(e, tail, a); sparkAt(e, head, b);
            float ft = tail / e.life, fh = head / e.life;
            float et = 4*ft*(1-ft)*e.emission*d.sparkHdr, eh = 4*fh*(1-fh)*e.emission*d.sparkHdr;
            float[] rgba = vpColourInto(VP_RGBA, d.sparkTint[0]*et, d.sparkTint[1]*et, d.sparkTint[2]*et, 1);
            for (int i = 1; i <= 2; i++) {
                for (int c = 0; c < 3; c++) { rgba[i*4+c] = d.sparkTint[c]*eh; }
            }
            vpRibbon(a, b, e.width, e.width, VP_UV, rgba, d.sparkTexture, KIND_SPARK, false);
        }
    }

    /** The corners of a strand segment's ends: a at 0 and 3, b at 1 and 2. */
    static final int[][] STRAND_CORNERS = {{0, 3}, {1, 2}};

    static void vpStrand(Trail t, float scale, int player) {
        Strand st = t.smoke.strand;
        if (st == null || t.segs.size() < 2) {
            return;
        }
        float[] cl = st.color.length >= 3 ? st.color : WHITE;
        // Performance pass 23.10.2026: a segment's far end is the next one's near end - its light (a function of
        // its place within this frame) is taken once; scratch rows instead of six arrays per segment.
        Seg lit = null;
        float litR = 0, litG = 0, litB = 0;
        float[] rgba = VP_RGBA, uv = VP_STRAND_UV;
        for (int i = 0; i + 1 < t.segs.size(); i++) {
            Seg a = t.segs.get(i), b = t.segs.get(i+1);
            if (b.skip) { continue; }
            float halfA = 0, halfB = 0;
            for (int end = 0; end < 2; end++) {
                Seg s = end == 0 ? a : b;
                float dd = s.diffusion;
                float half = (st.width + dd*dd*st.diffusion) * scale;
                if (end == 0) { halfA = half; } else { halfB = half; }
                float alpha = Math.min(1f, Math.max(0f, dd / Math.max(1e-6f, st.startFade))) * s.alpha
                        * Math.min(1f, Math.max(0f, (st.end-dd) / Math.max(1e-6f, st.endFade))) * st.alpha;
                if (s != lit) {
                    vpLightAt(s.p[0], s.p[1], s.p[2], player, 0f);
                    lit = s;
                    litR = light[0];
                    litG = light[1];
                    litB = light[2];
                }
                for (int v : STRAND_CORNERS[end]) {
                    rgba[v*4] = litR*cl[0];
                    rgba[v*4+1] = litG*cl[1];
                    rgba[v*4+2] = litB*cl[2];
                    rgba[v*4+3] = alpha;
                }
            }
            float va = a.yuv*st.uvY, vb = b.yuv*st.uvY;
            uv[0] = 0; uv[1] = va; uv[2] = 0; uv[3] = vb; uv[4] = 1; uv[5] = vb; uv[6] = 1; uv[7] = va;
            vpRibbon(a.p, b.p, halfA, halfB, uv, rgba, st.texture, KIND_STRAND, false);
        }
    }

    public static String vpProfile() {
        return "muzzleFrames=" + vpFrames + " muzzleQuads=" + vpQuads + " buildNs=" + vpBuildNanos + " maxBuildNs=" + vpMaxBuildNanos;
    }

    /**
     * One particle of a pivot effect's system: size and colour over lifetime on
     * its start values; smoke lit by its square (Particles/Smoke Distorted
     * family), glow additive (texture x tint x strength x colour x alpha; sheet
     * frame by frameOverTime), a stretched billboard along its velocity
     * (length = size x lengthScale + speed x velocityScale).
     */
    static void systemQuad(Quads qs, Puff e, PSystem s, int p, float ts, float offX, float offY, float px, float[] col, float zoom) {
        systemQuad(qs, e, s, p, ts, offX, offY, px, col, zoom, false);
    }

    static void systemQuad(Quads qs, Puff e, PSystem s, int p, float ts, float offX, float offY, float px, float[] col, float zoom, boolean vp) {
        float vis = vp ? 1f : visibility(e.owner, e.ownerPlayer, e.x, e.y, e.h, p);
        if (vis <= 0.001f) {
            return;
        }
        float n = Math.min(1f, e.age / e.life);
        float size = e.size * (s.sizeOL == null ? 1f : s.sizeOL.eval(n, e.rnd));
        float cr = e.r, cg = e.g, cb = e.b, ca = e.a;
        if (s.colourOL != null) {
            s.colourOL.eval(n, e.rnd, col);
            cr *= col[0];
            cg *= col[1];
            cb *= col[2];
            ca *= col[3];
        }
        if (size <= 0f || ca <= 0.002f) {
            return;
        }
        float[] t = s.tint;
        float r, g, b, a, z = 0f;
        if (s.kind == PSystem.SMOKE) {
            if (vp) {
                vpLightAt(e.x, e.y, e.h, p, 0f);
            } else {
                lightAt(e.x, e.y, e.h, p);
            }
            r = light[0] * cr * t[0];
            g = light[1] * cr * t[1];
            b = light[2] * cr * t[2];
            a = ca * t[3] * vis;
            z = cg;
        } else {
            float k = s.strength * ca * vis;
            r = t[0] * cr * k;
            g = t[1] * cg * k;
            b = t[2] * cb * k;
            a = 1f;
        }
        float u0 = 0f, v0 = 0f, u1 = 1f, v1 = 1f;
        if (s.frameOT != null) {
            int tiles = s.tilesX * s.tilesY;
            float f = frac(n * s.cycles);
            int idx = (int) Math.floor(s.frameOT.eval(f, e.rnd) * tiles + e.frame);
            idx = Math.max(0, Math.min(tiles - 1, idx));
            u0 = (idx % s.tilesX) / (float) s.tilesX;
            v0 = (idx / s.tilesX) / (float) s.tilesY;
            u1 = u0 + 1f / s.tilesX;
            v1 = v0 + 1f / s.tilesY;
        }
        if (vp) {
            // (performance pass 23.10.2026: scratch rows, see VP_CENTRES)
            float[] uv = VP_UV_SCRATCH;
            uv[0] = u0; uv[1] = v0; uv[2] = u1; uv[3] = v0; uv[4] = u1; uv[5] = v1; uv[6] = u0; uv[7] = v1;
            float[] rgba = vpColourInto(VP_RGBA, r, g, b, a);
            int kind = s.kind == PSystem.SMOKE ? KIND_SMOKE : KIND_SPARK;
            if (s.kind == PSystem.STREAK) {
                float speed = (float) Math.sqrt(e.vx*e.vx + e.vy*e.vy + e.vh*e.vh);
                float length = size*s.lengthScale + speed*s.velocityScale;
                float k = length / Math.max(1e-6f, speed);
                float[] from = VP_END_A, to = VP_END_B;
                from[0] = e.x-e.vx*k; from[1] = e.y-e.vy*k; from[2] = e.h-e.vh*k;
                to[0] = e.x; to[1] = e.y; to[2] = e.h;
                vpRibbon(from, to, size*0.5f, size*0.5f, uv, rgba, s.texture, kind, false);
            } else {
                vpBillboard(e.x, e.y, e.h, size, e.rot, uv, rgba, s.texture, kind, z*s.distortion, (float) (time % 1000)*s.animation);
            }
            return;
        }
        float cx = sx(e.x, e.y, ts, offX), cy = sy(e.x, e.y, e.h, ts, offY);
        float[] q = qs.next();
        int o = (qs.n - 1) * STRIDE;
        q[o + 32] = z;
        if (s.kind == PSystem.STREAK) {
            // Stretched billboard: from the particle back along its screen velocity.
            float speed = (float) Math.sqrt(e.vx * e.vx + e.vy * e.vy + e.vh * e.vh);
            float ex = e.x - e.vx * 0.05f, ey = e.y - e.vy * 0.05f, eh = e.h - e.vh * 0.05f;
            float dx = cx - sx(ex, ey, ts, offX), dy = cy - sy(ex, ey, eh, ts, offY);
            float dn = (float) Math.sqrt(dx * dx + dy * dy);
            if (dn < 1e-4f) {
                dx = 1f;
                dy = 0f;
                dn = 1f;
            }
            dx /= dn;
            dy /= dn;
            float length = (size * s.lengthScale + speed * s.velocityScale) * px;
            float half = Math.max(size * 0.5f * px, 0.5f * zoom);
            float energy = Math.min(1f, size * 0.5f * px / Math.max(1e-6f, half));
            r *= energy;
            g *= energy;
            b *= energy;
            float nx = -dy * half, ny = dx * half, bx = cx - dx * length, by = cy - dy * length;
            corner(q, o, 0, bx - nx, by - ny, u0, v0, r, g, b, a);
            corner(q, o, 1, cx - nx, cy - ny, u1, v0, r, g, b, a);
            corner(q, o, 2, cx + nx, cy + ny, u1, v1, r, g, b, a);
            corner(q, o, 3, bx + nx, by + ny, u0, v1, r, g, b, a);
            return;
        }
        float half = size * 0.5f * px, cs = (float) Math.cos(e.rot) * half, sn = (float) Math.sin(e.rot) * half;
        corner(q, o, 0, cx - cs + sn, cy - sn - cs, u0, v0, r, g, b, a);
        corner(q, o, 1, cx + cs + sn, cy + sn - cs, u1, v0, r, g, b, a);
        corner(q, o, 2, cx + cs - sn, cy + sn + cs, u1, v1, r, g, b, a);
        corner(q, o, 3, cx - cs - sn, cy - sn + cs, u0, v1, r, g, b, a);
    }

    /** Custom/SimpleParticleMoveAdd: p0 + v (drag^t - 1) / ln drag - (0, g t^2, 0). */
    static void sparkAt(Spark e, float t, float[] out) {
        float f = (float) ((Math.pow(e.drag, t) - 1) / e.lnDrag);
        out[0] = e.x + e.vx * f;
        out[1] = e.y + e.vy * f;
        out[2] = e.h + e.vh * f - e.gravity * t * t;
    }

    /** Custom/Additive jet quads of one shooter at the shot's time t. */
    static void jets(Quads qs, Shooter s, float vis, float ts, float offX, float offY, float px) {
        jets(qs, s, vis, ts, offX, offY, px, false);
    }

    static void jets(Quads qs, Shooter s, float vis, float ts, float offX, float offY, float px, boolean vp) {
        Manager m = s.setup.mgr;
        Material mat = m.mat;
        if (mat == null) {
            return;
        }
        float t = jetPhase(s);
        float svx = 1f - m.light.eval(t), svy = m.move.eval(t);
        float scale = data.scale;
        float[] rv = s.rv;
        float cellX = 1f / m.ax, cellY = 1f / m.ay;
        float[] w = new float[3];
        if (!vp) { qs.begin(KIND_JET, mat.texture, null); }
        float hdr = mat.hdr * vis;
        for (Node n : s.setup.nodes) {
            Jet j = n.jet;
            if (j == null || n.g == null) {
                continue;
            }
            float[] nm = n.m;
            float bx = nm[3], by = nm[7], bz = nm[11];
            float ax = nm[1], ay = nm[5], az = nm[9];
            float an = (float) Math.sqrt(ax * ax + ay * ay + az * az);
            ax /= an;
            ay /= an;
            az /= an;
            float R = n.common;
            for (int i = 0; i < j.pos.length; i++) {
                float G = n.g[i], B = j.norm[i];
                // perpendicular A from the jet's random R and the shot's RandomVals (yzx)
                float aX = frac(R * rv[1]) - 0.5f, aY = frac(R * rv[2]) - 0.5f, aZ = frac(R * rv[0]) - 0.5f;
                float pAx = ay * aY - aX * az, pAy = az * aZ - aY * ax, pAz = ax * aX - aZ * ay;
                float fr = frac(R * rv[2]), fg = frac(G * rv[2]);
                float lenShift = fr * (j.extra * B) + (fg - 0.5f) * j.rsy[i];
                float thr = Math.abs((fr - 0.5f) * mat.phase + B - 2f * svy + 0.5f) * mat.invWindow + svx;
                if (thr >= 1f) {
                    continue;   // the texture alpha cannot exceed it: nothing drawn
                }
                float bX = frac(G * rv[2]) - 0.5f, bY = frac(G * rv[3]) - 0.5f, bZ = frac(G * rv[1]) - 0.5f;
                float pBx = ay * bY - bX * az, pBy = az * bZ - bY * ax, pBz = ax * bX - bZ * ay;
                float along = (j.pos[i] + lenShift) * scale, shiftA = j.axis[i] * scale, shiftB = j.rsx[i] * scale;
                float mx = bx + ax * along + pAx * shiftA + pBx * shiftB;
                float my = by + ay * along + pAy * shiftA + pBy * shiftB;
                float mz = bz + az * along + pAz * shiftA + pBz * shiftB;
                place(s.frame, mx, my, mz, w);
                float cx = sx(w[0], w[1], ts, offX), cy = sy(w[0], w[1], w[2], ts, offY);
                jetViewX = cx;
                jetViewY = cy;
                // corners: uv1 = (v, -v⊥, -v, v⊥), v⊥ = (v.y, -v.x), turned by RotMx; metres -> view units
                // (performance pass 23.10.2026: corner and uv pairs in scratch rows of the main thread, same values)
                float vx = n.vx[i], vy = n.vy[i];
                float[] c = JET_CORNERS, uv = JET_UV;
                c[0] = vx; c[1] = vy; c[2] = -vy; c[3] = vx; c[4] = -vx; c[5] = -vy; c[6] = vy; c[7] = -vx;
                float u0 = frac(n.cu[i] + s.cellU), v0 = frac(n.cv[i] + s.cellV);
                // Unity UV (bottom-up) to GL (top-down): cell [v0, v0+cellY] -> [1-v0-cellY, 1-v0]
                float gv0 = 1f - v0 - cellY, gv1 = 1f - v0;
                uv[0] = u0; uv[1] = gv0; uv[2] = u0; uv[3] = gv1; uv[4] = u0 + cellX; uv[5] = gv1; uv[6] = u0 + cellX; uv[7] = gv0;
                if (vp) {
                    float[] offsets = VP_OFFSETS;
                    for (int v = 0; v < 4; v++) {
                        offsets[v*2] = (c[v*2]*s.rot[0] + c[v*2+1]*s.rot[2])*s.k;
                        offsets[v*2+1] = (c[v*2]*s.rot[1] + c[v*2+1]*s.rot[3])*s.k;
                    }
                    vpQuad(vpCentres(w[0], w[1], w[2]), offsets, uv,
                            vpColourInto(VP_RGBA, mat.tint[0]*hdr, mat.tint[1]*hdr, mat.tint[2]*hdr, thr), null, false, mat.texture, KIND_JET, 0, 0);
                    jetsDrawn++;
                    continue;
                }
                float k = s.k * px;
                float[] q = qs.next();
                int o = (qs.n - 1) * STRIDE;
                q[o + 32] = 0f;
                for (int cIdx = 0; cIdx < 4; cIdx++) {
                    float ox = c[cIdx * 2] * s.rot[0] + c[cIdx * 2 + 1] * s.rot[2];
                    float oy = c[cIdx * 2] * s.rot[1] + c[cIdx * 2 + 1] * s.rot[3];
                    corner(q, o, cIdx, cx + ox * k, cy - oy * k, uv[cIdx * 2], uv[cIdx * 2 + 1], mat.tint[0] * hdr, mat.tint[1] * hdr, mat.tint[2] * hdr, thr);
                }
                jetsDrawn++;
            }
        }
    }

    /**
     * QA only: a fixed phase of the flash (0..1 of ShotLength) for the latest shot
     * of each shooter, so a screenshot (a 4K capture stops the game for about a
     * second, the flash lasts 0.12 s) shows a given moment; negative: off.
     */
    public static volatile float qaPhase = -1f;

    static boolean jetActive(Shooter s) {
        if (!flashOn) return false;
        if (qaPhase >= 0f) {
            return s.shotEnd > 0 && time - s.shotEnd < 30;
        }
        return time <= s.shotEnd;
    }

    static float jetPhase(Shooter s) {
        if (qaPhase >= 0f) {
            return Math.min(1f, qaPhase);
        }
        float shot = s.setup != null && s.setup.mgr != null ? s.setup.mgr.shot : 0.1f;
        return 1f - (float) ((s.shotEnd - time) / shot);
    }

    static float frac(float v) {
        return (float) (v - Math.floor(v));
    }

    /** Main thread (strand): screen x, y, half width, alpha, normal x, y, light r, g, b per segment. */
    static final float[][] strandAll = new float[9][64];
    static final float[] WHITE = {1, 1, 1};

    /** Custom/MuzzleSmoke ribbon of one trail. */
    static void strand(Quads qs, Trail t, float k, float vis, int p, float ts, float offX, float offY, float px) {
        Strand st = t.smoke.strand;
        ArrayList<Seg> segs = t.segs;
        int n = segs.size();
        // Performance pass 23.10.2026: scratch rows (main thread), grown as needed; every row is written before read.
        if (strandAll[0].length < n) {
            int size = Math.max(n, strandAll[0].length * 2);
            for (int r = 0; r < strandAll.length; r++) {
                strandAll[r] = new float[size];
            }
        }
        float[] sxs = strandAll[0], sys = strandAll[1], half = strandAll[2], alpha = strandAll[3], dirx = strandAll[4], diry = strandAll[5];
        float[] lr = strandAll[6], lg = strandAll[7], lb = strandAll[8];
        for (int i = 0; i < n; i++) {
            Seg v = segs.get(i);
            sxs[i] = sx(v.p[0], v.p[1], ts, offX);
            sys[i] = sy(v.p[0], v.p[1], v.p[2], ts, offY);
            float dd = v.diffusion;
            half[i] = (st.width + dd * dd * st.diffusion) * k * px;
            alpha[i] = Math.min(1f, Math.max(0f, dd / Math.max(1e-6f, st.startFade))) * v.alpha
                    * Math.min(1f, Math.max(0f, (st.end - dd) / Math.max(1e-6f, st.endFade))) * st.alpha * vis;
            lightAt(v.p[0], v.p[1], v.p[2], p);
            lr[i] = light[0];
            lg[i] = light[1];
            lb[i] = light[2];
        }
        for (int i = 0; i < n; i++) {
            float dx, dy;
            if (i == 0) {
                dx = sxs[n - 1] - sxs[0];
                dy = sys[n - 1] - sys[0];
            } else if (i < n - 1) {
                dx = sxs[i + 1] - sxs[i - 1];
                dy = sys[i + 1] - sys[i - 1];
            } else {
                dx = sxs[i] - sxs[i - 1];
                dy = sys[i] - sys[i - 1];
            }
            float dn = (float) Math.sqrt(dx * dx + dy * dy);
            if (dn < 1e-5f) {
                dx = 0;
                dy = -1;
                dn = 1;
            }
            dirx[i] = -dy / dn;
            diry[i] = dx / dn;
        }
        qs.begin(KIND_STRAND, st.texture, null);
        float[] cl = st.color.length >= 3 ? st.color : WHITE;
        for (int i = 0; i < n - 1; i++) {
            int a = i, b = i + 1;
            float va = segs.get(a).yuv * st.uvY, vb = segs.get(b).yuv * st.uvY;
            float[] q = qs.next();
            int o = (qs.n - 1) * STRIDE;
            q[o + 32] = 0f;
            corner(q, o, 0, sxs[a] - dirx[a] * half[a], sys[a] - diry[a] * half[a], 0, va, lr[a] * cl[0], lg[a] * cl[1], lb[a] * cl[2], alpha[a]);
            corner(q, o, 1, sxs[b] - dirx[b] * half[b], sys[b] - diry[b] * half[b], 0, vb, lr[b] * cl[0], lg[b] * cl[1], lb[b] * cl[2], alpha[b]);
            corner(q, o, 2, sxs[b] + dirx[b] * half[b], sys[b] + diry[b] * half[b], 1, vb, lr[b] * cl[0], lg[b] * cl[1], lb[b] * cl[2], alpha[b]);
            corner(q, o, 3, sxs[a] + dirx[a] * half[a], sys[a] + diry[a] * half[a], 1, va, lr[a] * cl[0], lg[a] * cl[1], lb[a] * cl[2], alpha[a]);
        }
    }

    // ------------------------------------------------------------------ heat (thermal image)

    /**
     * HeatRenderer.capture, main thread: the heat of this player's view. Hot
     * flash quads, cooling gas of the puffs, sparks, the warm trail and the
     * explosions (fireball, rising hot gas, warm ground).
     */
    static void heat(HeatRenderer.Frame f, int p, float[] q, OpticState.View view, float ts, float offX, float offY) {
        Data d = data;
        if (d == null || !enabled) {
            return;
        }
        try {
            float px = PX * ts;
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
            for (var entry : shooters.entrySet()) {
                Shooter s = entry.getValue();
                IsoGameCharacter c = entry.getKey();
                // Performance pass 23.10.2026: a shooter with nothing below to draw (no flash, hot part or trail segment)
                // skips the view and line-of-sight tests (both pure) it used to run every frame.
                boolean any = jetActive(s) && s.framed && s.setup != null && s.setup.jets && s.setup.mgr != null
                        || s.hotSetup != null && s.framed;
                if (!any && s.trails != null) {
                    for (Trail t : s.trails) {
                        if (!t.segs.isEmpty()) {
                            any = true;
                            break;
                        }
                    }
                }
                if (!any || c == null || !HeatSources.inView(view, c.getX(), c.getY(), c.getZ(), 60f)
                        || cell != null && !HeatSources.clear(cell, view, c.getX(), c.getY(), c.getZ())) {
                    continue;
                }
                if (jetActive(s) && s.framed && s.setup != null && s.setup.jets && s.setup.mgr != null) {
                    float t = jetPhase(s);
                    float glow = s.setup.mgr.light.eval(t);
                    float[] w = new float[3];
                    for (Node n : s.setup.nodes) {
                        if (n.jet == null) {
                            continue;
                        }
                        float[] nm = n.m;
                        float an = (float) Math.sqrt(nm[1] * nm[1] + nm[5] * nm[5] + nm[9] * nm[9]);
                        for (int i = 0; i < n.jet.pos.length; i++) {
                            float along = n.jet.pos[i] * d.scale;
                            place(s.frame, nm[3] + nm[1] / an * along, nm[7] + nm[5] / an * along, nm[11] + nm[9] / an * along, w);
                            float r = n.jet.size[i] * s.k * px;
                            HeatRenderer.glow(f, p, q, sx(w[0], w[1], ts, offX), sy(w[0], w[1], w[2], ts, offY), r, r, JET_HEAT * glow);
                            heatQuads++;
                        }
                    }
                }
                if (s.hotSetup != null && s.framed) {
                    // USEHEAT in thermal vision: the glow x 10 (red: 1 for both heat colours),
                    // capped at _Temperature.y (1, as a body's): on the scale of a living body.
                    float w = 0.5f * heatGlow(s.overheat);
                    float unit = (float) Math.sqrt(s.frame[3] * s.frame[3] + s.frame[4] * s.frame[4] + s.frame[5] * s.frame[5] * LEVEL * LEVEL);
                    float[] a = new float[3];
                    for (HotNode h : s.hotSetup.hot) {
                        float input = Math.min(1f, 10f * w * h.spec * d.heatVisible) * PART_HEAT;
                        if (input <= 0.01f) {
                            continue;
                        }
                        float dx = h.b[0] - h.a[0], dy = h.b[1] - h.a[1], dz = h.b[2] - h.a[2];
                        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                        // soft blobs half a width apart along the lit zone: a continuous warm body that ends at the muzzle
                        int blobs = Math.max(1, Math.min(16, Math.round(len / Math.max(1e-6f, h.half))));
                        float r = Math.max(h.half, len / blobs) * unit * px;
                        for (int i = 0; i < blobs; i++) {
                            float along = (i + 0.5f) / blobs;
                            place(s.frame, h.a[0] + dx * along, h.a[1] + dy * along, h.a[2] + dz * along, a);
                            HeatRenderer.glow(f, p, q, sx(a[0], a[1], ts, offX), sy(a[0], a[1], a[2], ts, offY), r, r, input);
                            hotHeatQuads++;
                        }
                    }
                }
                if (s.trails != null) {
                    for (Trail t : s.trails) {
                        for (int i = 0; i < t.segs.size(); i += 2) {
                            Seg v = t.segs.get(i);
                            float r = (t.smoke.strand == null ? 0.02f : t.smoke.strand.width + v.diffusion * v.diffusion * t.smoke.strand.diffusion) * s.k * px * 2f;
                            HeatRenderer.glow(f, p, q, sx(v.p[0], v.p[1], ts, offX), sy(v.p[0], v.p[1], v.p[2], ts, offY), r, r, TRAIL_HEAT * v.alpha * 4f);
                            heatQuads++;
                        }
                    }
                }
            }
            for (Puff e : puffs) {
                if (e.age < 0f) {
                    continue;
                }
                PSystem s = e.sys;
                float n = Math.min(1f, e.age / e.life);
                float input, r;
                if (s == null || s.kind == PSystem.SMOKE) {
                    // hot propellant gas that cools in a fraction of a second
                    input = FUME_HEAT * (float) Math.exp(-e.age / FUME_COOL) * Math.min(1f, e.a * (s == null ? 8f : 1f));
                    r = e.size * (s == null ? d.fumeSize.eval(n, e.rnd) : s.sizeOL == null ? 1f : s.sizeOL.eval(n, e.rnd)) * 0.5f * px;
                } else {
                    // flame, glow and sparks of a pivot effect: hot while they shine
                    float alpha = e.a;
                    if (s.colourOL != null) {
                        float[] c = new float[4];
                        s.colourOL.eval(n, e.rnd, c);
                        alpha *= c[3];
                    }
                    input = JET_HEAT * Math.min(1f, alpha);
                    r = Math.max(e.size * (s.sizeOL == null ? 1f : s.sizeOL.eval(n, e.rnd)) * 0.5f * px, 1.5f);
                }
                if (input < 0.15f || !HeatSources.inView(view, e.x, e.y, e.h / LEVEL, 60f)) {
                    continue;
                }
                HeatRenderer.glow(f, p, q, sx(e.x, e.y, ts, offX), sy(e.x, e.y, e.h, ts, offY), r, r, input);
                heatQuads++;
            }
            float[] a = new float[3];
            for (Spark e : sparks) {
                double age = time - e.born;
                if (age <= 0 || age >= e.life) {
                    continue;
                }
                sparkAt(e, (float) age, a);
                float fl = (float) (age / e.life);
                float r = Math.max(e.width * px * 4f, 1.5f);
                HeatRenderer.glow(f, p, q, sx(a[0], a[1], ts, offX), sy(a[0], a[1], a[2], ts, offY), r, r, SPARK_HEAT * 4 * fl * (1 - fl));
                heatQuads++;
            }
            for (Blast b : blasts) {
                float age = (float) (time - b.born);
                float z = b.h / LEVEL;
                if (!HeatSources.inView(view, b.x, b.y, z, 60f) || cell != null && !HeatSources.clear(cell, view, b.x, b.y, z)) {
                    continue;
                }
                float metre = px;
                // fireball: grows to 1.6 m in a tenth of a second, fades within a second
                float core = 1.6f * (1f - (float) Math.exp(-age / 0.05f));
                float coreHeat = BLAST_CORE * (float) Math.exp(-age / 0.35f);
                if (coreHeat > 0.1f) {
                    HeatRenderer.glow(f, p, q, sx(b.x, b.y, ts, offX), sy(b.x, b.y, b.h + 0.8f, ts, offY), core * metre, core * metre, coreHeat);
                    heatQuads++;
                }
                // hot gas: rises and spreads over three seconds
                float gasHeat = BLAST_GAS * (float) Math.exp(-age / 1.2f);
                if (gasHeat > 0.1f) {
                    float rise = Math.min(4f, 1.2f * age + 0.8f), spread = 1.2f + 0.8f * Math.min(age, 3f);
                    HeatRenderer.glow(f, p, q, sx(b.x + windX * age * 0.3f, b.y + windY * age * 0.3f, ts, offX), sy(b.x, b.y, b.h + rise, ts, offY),
                            spread * metre, spread * metre * 0.9f, gasHeat);
                    heatQuads++;
                }
                // the ground where it burst stays warm for a minute
                float groundHeat = BLAST_GROUND * (float) Math.exp(-age / 30f);
                if (groundHeat > 0.1f) {
                    float r = 1.4f;
                    HeatRenderer.glowFlat(f, p, q, b.x, b.y, b.h / LEVEL, r, groundHeat, ts, offX, offY);
                    heatQuads++;
                }
            }
        } catch (Throwable e) {
            fail("heat", e);
        }
    }

    // ------------------------------------------------------------------ render thread

    static ShaderProgram jetProgram, smokeProgram, addProgram;

    /** Render thread: the effect programs, at the first use or ahead of it (ShaderWarmup). */
    static void shaders() {
        if (jetProgram == null) {
            jetProgram = ShaderWarmup.made(ShaderProgram.createShaderProgram("tw_muzzle_jet", false, false, true));
            smokeProgram = ShaderWarmup.made(ShaderProgram.createShaderProgram("tw_muzzle_smoke", false, false, true));
            addProgram = ShaderWarmup.made(ShaderProgram.createShaderProgram("tw_muzzle_add", false, false, true));
        }
    }
    static final Map<String, Texture> textures = new HashMap<>();

    static String texturePath(String name) {
        return name == null || name.isEmpty() ? null : "media/textures/TW_Muzzle/" + name + ".png";
    }

    static Texture texture(String name) {
        String path = texturePath(name);
        if (path == null) {
            return null;
        }
        Texture t = textures.get(name);
        if (t == null) {
            t = Texture.getSharedTexture(path, 0);
            if (t != null) {
                textures.put(name, t);
            }
        }
        return t;
    }

    static final class Drawer extends TextureDraw.GenericDrawer {
        int player, w, h, stamp;
        float zoom, time;
        // the field pass: the world frame (its size), thermal (hot things only), the hot parts drawn into it
        // with the characters this frame (MuzzleHeatModel): kept, not cleared
        Texture world;
        boolean fieldThermal, surfaceField;
        final Quads quads = new Quads();
        float[] probe;
        Texture noise;
        final ArrayList<Texture> runTextures = new ArrayList<>();

        void resolve() {
            runTextures.clear();
            for (String name : quads.textures) {
                runTextures.add(name == null ? null : texture(name));
            }
            noise = data == null ? null : texture(data.noiseTexture);
        }

        @Override
        public void render() {
            int prevProgram = RealGL.glGetInteger(RealGL.GL_CURRENT_PROGRAM);
            RealGL.glPushAttrib(RealGL.GL_COLOR_BUFFER_BIT | RealGL.GL_DEPTH_BUFFER_BIT | RealGL.GL_ENABLE_BIT | RealGL.GL_TEXTURE_BIT
                    | RealGL.GL_SCISSOR_BIT | RealGL.GL_STENCIL_BUFFER_BIT);
            MatrixStack projection = Core.getInstance().projectionMatrixStack;
            MatrixStack modelView = Core.getInstance().modelViewMatrixStack;
            try {
                draw(projection, modelView);
                if ((quads.fieldCount > 0 || surfaceField) && world != null && world.isReady()) {
                    drawField(projection, modelView);
                }
            } catch (Throwable t) {
                fail("render", t);
            } finally {
                RealGL.glActiveTexture(RealGL.GL_TEXTURE1);
                RealGL.glBindTexture(RealGL.GL_TEXTURE_2D, 0);
                RealGL.glActiveTexture(RealGL.GL_TEXTURE0);
                RealGL.glColorMask(true, true, true, true);
                RealGL.glPopAttrib();
                RealGL.glBlendEquation(RealGL.GL_FUNC_ADD);
                Texture.lastTextureID = -1;
                ShaderHelper.glUseProgramObjectARB(prevProgram);
                SpriteRenderer.ringBuffer.restoreBoundTextures = true;
                SpriteRenderer.ringBuffer.restoreVbos = true;
                GLStateRenderThread.restore();
            }
        }

        private void draw(MatrixStack projection, MatrixStack modelView) {
            shaders();
            if (!jetProgram.isCompiled() || !smokeProgram.isCompiled() || !addProgram.isCompiled()) {
                throw new IllegalStateException("muzzle shaders did not compile (tw_muzzle_jet/tw_muzzle_smoke/tw_muzzle_add)");
            }
            // The world pass may leave depth, stencil (cutaways), scissor and alpha tests on: none
            // apply to these quads (drawn over the world, as the lasers).
            // Performance pass 23.10.2026: that state is read (5 GL queries) only for the QA probe, its one reader.
            boolean probing = qaProbe && probe != null;
            if (probing) {
                QA_STATE = "depth=" + RealGL.glIsEnabled(RealGL.GL_DEPTH_TEST) + " stencil=" + RealGL.glIsEnabled(RealGL.GL_STENCIL_TEST)
                        + " scissor=" + RealGL.glIsEnabled(RealGL.GL_SCISSOR_TEST) + " alphaTest=" + RealGL.glIsEnabled(RealGL.GL_ALPHA_TEST)
                        + " alphaFunc=" + RealGL.glGetInteger(RealGL.GL_ALPHA_TEST_FUNC) + "/" + RealGL.glGetFloat(RealGL.GL_ALPHA_TEST_REF);
            }
            // The legacy alpha test (compatibility profile) also applies to shader output: the world
            // pass leaves it on, and it would discard the additive quads (alpha unused) and faint smoke.
            RealGL.glDisable(RealGL.GL_ALPHA_TEST);
            RealGL.glDisable(RealGL.GL_DEPTH_TEST);
            RealGL.glDisable(RealGL.GL_STENCIL_TEST);
            RealGL.glDisable(RealGL.GL_SCISSOR_TEST);
            RealGL.glDisable(RealGL.GL_CULL_FACE);
            RealGL.glEnable(RealGL.GL_BLEND);
            RealGL.glBlendEquation(RealGL.GL_FUNC_ADD);
            // Tarkov writes colour only (ColorMask RGB): the world frame's alpha stays.
            RealGL.glColorMask(true, true, true, false);
            VBORenderer vbo = VBORenderer.getInstance();
            // A global depth test override of VBORenderer would turn the depth test back on per run.
            vbo.setDepthTestForAllRuns(Boolean.FALSE);
            projection.push(projection.alloc().setOrtho2D(0, w * zoom, h * zoom, 0));
            modelView.push(modelView.alloc().identity());
            // VBORenderer uploads this matrix to a program whose last one differs; each program
            // also gets it here, so a run never depends on that record.
            org.joml.Matrix4f mvp = MVP.setOrtho2D(0, w * zoom, h * zoom, 0);
            int[] vp = null;
            int probeX = 0, probeY = 0, readFrom = -1;
            java.nio.ByteBuffer before = null;
            if (probing) {
                // QA: the bound target, its viewport and a pixel at the first quad before and after drawing.
                vp = new int[4];
                RealGL.glGetIntegerv(RealGL.GL_VIEWPORT, vp);
                probeX = vp[0] + Math.round(probe[0] / (w * zoom) * vp[2]);
                probeY = vp[1] + Math.round((1f - probe[1] / (h * zoom)) * vp[3]);
                before = org.lwjgl.BufferUtils.createByteBuffer(4);
                readFrom = RealGL.readFramebuffer();
                RealGL.bindReadFramebuffer(RealGL.drawFramebuffer());
                RealGL.glReadPixels(probeX, probeY, 1, 1, RealGL.GL_RGBA, RealGL.GL_UNSIGNED_BYTE, before);
            }
            try {
                Quads qs = quads;
                for (int run = 0; run < qs.kinds.size(); run++) {
                    int start = qs.starts.get(run), end = run + 1 < qs.starts.size() ? qs.starts.get(run + 1) : qs.n;
                    Texture tex = runTextures.get(run);
                    if (end <= start || tex == null || !tex.isReady()) {
                        continue;
                    }
                    int kind = qs.kinds.get(run);
                    if (kind == KIND_JET || kind == KIND_SPARK) {
                        RealGL.glBlendFunc(RealGL.GL_ONE, RealGL.GL_ONE);
                    } else {
                        RealGL.glBlendFunc(RealGL.GL_SRC_ALPHA, RealGL.GL_ONE_MINUS_SRC_ALPHA);
                    }
                    run(vbo, mvp, run, start, end, tex, false);
                    quadsDrawn += end - start;
                    if (before != null && kind == KIND_JET && blendState.isEmpty()) {
                        java.nio.ByteBuffer mask = org.lwjgl.BufferUtils.createByteBuffer(16);
                        RealGL.glGetBooleanv(RealGL.GL_COLOR_WRITEMASK, mask);
                        blendState = QA_STATE + " blend=" + RealGL.glIsEnabled(RealGL.GL_BLEND) + " src=" + RealGL.glGetInteger(RealGL.GL_BLEND_SRC_RGB)
                                + " dst=" + RealGL.glGetInteger(RealGL.GL_BLEND_DST_RGB) + " eq=" + RealGL.glGetInteger(RealGL.GL_BLEND_EQUATION)
                                + " mask=" + mask.get(0) + mask.get(1) + mask.get(2) + mask.get(3) + " jetQuads=" + (end - start)
                                + " tex=" + tex.getWidth() + "x" + tex.getHeight();
                    }
                }
                String texInfo = "";
                if (before != null) {
                    // QA: the jet texture's own pixels (mean alpha).
                    Texture jt = null;
                    for (int run = 0; run < qs.kinds.size(); run++) {
                        if (qs.kinds.get(run) == KIND_JET) {
                            jt = runTextures.get(run);
                        }
                    }
                    if (jt != null && jt.isReady()) {
                        int tw = jt.getWidthHW(), th = jt.getHeightHW();
                        java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(tw * th * 4);
                        RealGL.glActiveTexture(RealGL.GL_TEXTURE0);
                        RealGL.glBindTexture(RealGL.GL_TEXTURE_2D, jt.getID());
                        RealGL.glGetTexImage(RealGL.GL_TEXTURE_2D, 0, RealGL.GL_RGBA, RealGL.GL_UNSIGNED_BYTE, px);
                        long sumA = 0, sumR = 0;
                        for (int i = 0; i < tw * th; i++) {
                            sumR += px.get(i * 4) & 255;
                            sumA += px.get(i * 4 + 3) & 255;
                        }
                        texInfo = String.format(java.util.Locale.ROOT, " jetTex=%dx%d id=%d meanR=%.1f meanA=%.1f uv=%.3f..%.3f,%.3f..%.3f", tw, th, jt.getID(),
                                sumR / (double) (tw * th), sumA / (double) (tw * th), jt.getXStart(), jt.getXEnd(), jt.getYStart(), jt.getYEnd());
                        Texture.lastTextureID = -1;
                    }
                    java.nio.ByteBuffer after = org.lwjgl.BufferUtils.createByteBuffer(4);
                    RealGL.glReadPixels(probeX, probeY, 1, 1, RealGL.GL_RGBA, RealGL.GL_UNSIGNED_BYTE, after);
                    int fbo = RealGL.drawFramebuffer();
                    zombie.core.textures.TextureFBO off = Core.getInstance().getOffscreenBuffer();
                    RealGL.bindReadFramebuffer(readFrom);
                    probeResult = String.format(java.util.Locale.ROOT, "jet=%s fbo=%d offscreen=%d viewport=%d,%d,%d,%d px=%d,%d before=%d,%d,%d after=%d,%d,%d view=%.0fx%.0f %s",
                            probe.length > 2 && probe[2] > 0, fbo, off == null ? -1 : off.getBufferId(), vp[0], vp[1], vp[2], vp[3], probeX, probeY,
                            before.get(0) & 255, before.get(1) & 255, before.get(2) & 255, after.get(0) & 255, after.get(1) & 255, after.get(2) & 255,
                            w * zoom, h * zoom, blendState) + texInfo;
                    blendState = "";
                }
            } finally {
                vbo.setDepthTestForAllRuns(null);
                projection.pop();
                modelView.pop();
            }
        }

        /**
         * One run of quads [start, end) with its program and texture. For the
         * field buffer only the quads in a scope's field, their light (additive)
         * or opacity (smoke) times their share of it.
         */
        private void run(VBORenderer vbo, org.joml.Matrix4f mvp, int run, int start, int end, Texture tex, boolean field) {
            Quads qs = quads;
            int kind = qs.kinds.get(run);
            ShaderProgram program = kind == KIND_JET ? jetProgram : kind == KIND_SPARK ? addProgram : smokeProgram;
            program.Start();
            program.setValue("ModelViewProjection", mvp);
            program.setSamplerUnit("DIFFUSE", 0);
            if (kind == KIND_SMOKE || kind == KIND_STRAND) {
                float[] param = qs.params.get(run);
                program.setSamplerUnit("NoiseTex", 1);
                // Particles/Smoke Distorted: _Time.x (t/20) x _AnimationSpeed scrolls the noise.
                program.setValue("Scroll", kind == KIND_SMOKE && param != null ? time / 20f * param[1] : 0f);
                program.setValue("Distortion", kind == KIND_SMOKE && param != null ? param[0] : 0f);
                RealGL.glActiveTexture(RealGL.GL_TEXTURE1);
                RealGL.glBindTexture(RealGL.GL_TEXTURE_2D, noise != null && noise.isReady() ? noise.getID() : 0);
                RealGL.glActiveTexture(RealGL.GL_TEXTURE0);
            }
            program.End();
            // TextureID.bind skips a texture it believes bound (Texture.lastTextureID): the world
            // pass binds its own without that record, so the run would sample a stale texture.
            Texture.lastTextureID = -1;
            RealGL.glActiveTexture(RealGL.GL_TEXTURE0);
            vbo.startRun(vbo.formatPositionColorUv);
            vbo.setMode(GL_QUADS);
            vbo.setDepthTest(false);
            vbo.setTextureID(tex.getTextureId());
            vbo.setMinMagFilters(RealGL.GL_LINEAR, RealGL.GL_LINEAR);
            vbo.setClampST(kind == KIND_STRAND ? RealGL.GL_REPEAT : RealGL.GL_CLAMP_TO_EDGE, kind == KIND_STRAND ? RealGL.GL_REPEAT : RealGL.GL_CLAMP_TO_EDGE);
            vbo.setShaderProgram(program);
            float[] q = qs.q;
            boolean additive = kind == KIND_JET || kind == KIND_SPARK;
            for (int i = start; i < end; i++) {
                if (!field) {
                    quad(vbo, q, i * STRIDE, 1f, 1f);
                } else if (qs.fw[i] > 0f) {
                    quad(vbo, q, i * STRIDE, additive ? qs.fw[i] : 1f, additive ? 1f : qs.fw[i]);
                }
            }
            vbo.endRun();
            vbo.flush();
        }

        private static void quad(VBORenderer vbo, float[] q, int o, float rgb, float alpha) {
            float z = q[o + 32];
            for (int c = 0; c < 4; c++) {
                int p = o + c * 4, col = o + 16 + c * 4;
                vbo.addElement(q[p], q[p + 1], z, q[p + 2], q[p + 3], q[col] * rgb, q[col + 1] * rgb, q[col + 2] * rgb, q[col + 3] * alpha);
            }
        }

        /**
         * The field buffer of the player's scope: what of the effects stands in
         * its field, drawn alone on black in the world frame's texels and its
         * viewport. The scope composite takes it into its mask (light: the
         * colour, smoke: the alpha), so the scope image shows them though they
         * stand above the cone on the floor. A thermal scope takes the hot ones
         * (flash, sparks, pivot fire) only: it sees through smoke.
         */
        private void drawField(MatrixStack projection, MatrixStack modelView) {
            // The hot parts the characters' pass put in (MuzzleHeatModel seeded it this frame) stay.
            boolean keep = surfaceField && MuzzleHeatModel.takeSeed(player);
            if (fieldBuffer() == null) {
                return;
            }
            int prevFbo = RealGL.drawFramebuffer();
            int prevLast = TextureFBO.lastID;
            int[] vp = new int[4];
            RealGL.glGetIntegerv(RealGL.GL_VIEWPORT, vp);
            RealGL.bindFramebuffer(fieldFbo.getBufferId());
            VBORenderer vbo = VBORenderer.getInstance();
            try {
                RealGL.glViewport(vp[0], vp[1], vp[2], vp[3]);
                if (!keep) {
                    RealGL.glEnable(RealGL.GL_SCISSOR_TEST);
                    RealGL.glScissor(vp[0], vp[1], vp[2], vp[3]);
                    RealGL.glColorMask(true, true, true, true);
                    RealGL.glClearColor(0f, 0f, 0f, 0f);
                    RealGL.glClear(RealGL.GL_COLOR_BUFFER_BIT);
                }
                RealGL.glDisable(RealGL.GL_SCISSOR_TEST);
                RealGL.glDisable(RealGL.GL_ALPHA_TEST);
                RealGL.glDisable(RealGL.GL_DEPTH_TEST);
                RealGL.glDisable(RealGL.GL_STENCIL_TEST);
                RealGL.glEnable(RealGL.GL_BLEND);
                RealGL.glBlendEquation(RealGL.GL_FUNC_ADD);
                vbo.setDepthTestForAllRuns(Boolean.FALSE);
                projection.push(projection.alloc().setOrtho2D(0, w * zoom, h * zoom, 0));
                modelView.push(modelView.alloc().identity());
                org.joml.Matrix4f mvp = MVP.setOrtho2D(0, w * zoom, h * zoom, 0);
                try {
                    Quads qs = quads;
                    for (int run = 0; run < qs.kinds.size(); run++) {
                        int start = qs.starts.get(run), end = run + 1 < qs.starts.size() ? qs.starts.get(run + 1) : qs.n;
                        Texture tex = runTextures.get(run);
                        int kind = qs.kinds.get(run);
                        boolean additive = kind == KIND_JET || kind == KIND_SPARK;
                        if (end <= start || tex == null || !tex.isReady() || fieldThermal && !additive) {
                            continue;
                        }
                        if (additive) {
                            // light: the colour; the alpha keeps the smoke's coverage
                            RealGL.glColorMask(true, true, true, false);
                            RealGL.glBlendFunc(RealGL.GL_ONE, RealGL.GL_ONE);
                        } else {
                            RealGL.glColorMask(true, true, true, true);
                            RealGL.glBlendFuncSeparate(RealGL.GL_SRC_ALPHA, RealGL.GL_ONE_MINUS_SRC_ALPHA, RealGL.GL_ONE, RealGL.GL_ONE_MINUS_SRC_ALPHA);
                        }
                        run(vbo, mvp, run, start, end, tex, true);
                    }
                } finally {
                    vbo.setDepthTestForAllRuns(null);
                    projection.pop();
                    modelView.pop();
                }
                fieldDrawn[player] = stamp;
                fieldPasses++;
            } finally {
                RealGL.bindFramebuffer(prevFbo);
                TextureFBO.lastID = prevLast;
                RealGL.glViewport(vp[0], vp[1], vp[2], vp[3]);
                RealGL.glColorMask(true, true, true, false);
            }
        }

        @Override
        public void postRender() {
            quads.clear();
            runTextures.clear();
            world = null;
            // TextureDraw.postRender calls this once its state is done with the drawer (and drops it): free again.
            release(this);
        }

        /** Render thread: the orthographic frame of the runs (setValue uploads it at once). */
        static final org.joml.Matrix4f MVP = new org.joml.Matrix4f();
        /**
         * Performance pass 23.10.2026: drawers (their quads keep their grown arrays) come back here from postRender;
         * a drawer the renderer drops without postRender is simply not reused. Only free drawers are taken.
         */
        static final java.util.concurrent.ConcurrentLinkedQueue<Drawer> FREE = new java.util.concurrent.ConcurrentLinkedQueue<>();
        static final java.util.concurrent.atomic.AtomicInteger freeCount = new java.util.concurrent.atomic.AtomicInteger();

        /** In FREE now: a second release of the same drawer is ignored, never handed out twice. */
        final java.util.concurrent.atomic.AtomicBoolean pooled = new java.util.concurrent.atomic.AtomicBoolean();

        static Drawer take() {
            Drawer d = FREE.poll();
            if (d == null) {
                return new Drawer();
            }
            freeCount.decrementAndGet();
            d.pooled.set(false);
            // as a new one: what build sets only on some paths
            d.world = null;
            d.fieldThermal = false;
            d.surfaceField = false;
            d.stamp = 0;
            d.probe = null;
            d.noise = null;
            return d;
        }

        static void release(Drawer d) {
            if (!d.pooled.compareAndSet(false, true)) {
                return;
            }
            if (freeCount.incrementAndGet() <= 8) {
                FREE.offer(d);
            } else {
                freeCount.decrementAndGet();
            }
        }
    }

    static void fail(String where, Throwable t) {
        errors++;
        String message = where + ": " + t;
        if (!message.equals(lastError)) {
            lastError = message;
            System.out.println("[TWZB] muzzle " + message);
            t.printStackTrace(System.out);
        }
    }

    /** QA: the overheat of an item now (0 when it does not overheat or has not fired). */
    public static float overheatOf(Object item) {
        Setup s = item == null ? null : setups.get(item);
        return overheat(s, heats.get(item), time);
    }

    /** QA: set an item's overheat as after a shot now (captures at a known temperature). */
    public static void setOverheat(Object item, float value) {
        if (item == null) {
            return;
        }
        Heat h = heats.computeIfAbsent(item, k -> new Heat());
        h.last = value;
        h.at = time;
    }

    /**
     * QA: an item's overheat setup, "shot= cool= mods= hot= visible= boxes= meshes= front= back= muzzle= span=", or nil:
     * front / back the forward-most and rearmost ends of the lit zones along the bore (root model units, +y forward),
     * muzzle the forward-most effect node of the assembly (its muzzle), span the longest lit zone.
     */
    public static String heatOf(Object item) {
        Setup s = item == null ? null : setups.get(item);
        if (s == null || !s.heat) {
            return null;
        }
        int visible = 0, boxes = 0;
        float front = Float.NaN, back = Float.NaN, muzzle = Float.NaN, span = 0f;
        for (HotNode h : s.hot) {
            visible += h.visible > 0 ? 1 : 0;
            float hi = Math.max(h.a[1], h.b[1]), lo = Math.min(h.a[1], h.b[1]);
            front = Float.isNaN(front) ? hi : Math.max(front, hi);
            back = Float.isNaN(back) ? lo : Math.min(back, lo);
            span = Math.max(span, hi - lo);
        }
        for (float[] b : s.boxes.values()) {
            boxes += b.length / MuzzleHeatModel.BOX_FLOATS;
        }
        for (Node n : s.nodes == null ? new Node[0] : s.nodes) {
            if (!n.extractor) {
                muzzle = Float.isNaN(muzzle) ? n.m[7] : Math.max(muzzle, n.m[7]);
            }
        }
        return String.format(java.util.Locale.ROOT, "shot=%.5f cool=%.5f mods=%.5f hot=%d visible=%d boxes=%d meshes=%d front=%.4f back=%.4f muzzle=%.4f span=%.4f",
                s.heatShot, s.heatCool, s.heatMods, s.hot.length, visible, boxes, s.boxes.size(), front, back, muzzle, span);
    }

    /** QA: "shots= vanilla= launcher= noFrame= puffs= sparks= trails= jets= lights= explosions= quads= heat= drawers= errors= live=". */
    public static String stats() {
        return "shots=" + shots + " vanilla=" + vanillaShots + " launcher=" + launcherShots + " noFrame=" + framesMissing + " carried=" + carried + " puffs=" + puffsEmitted
                + " sparks=" + sparksEmitted + " trails=" + trailsStarted + " jets=" + jetsDrawn + " lights=" + lights + " explosions=" + explosions
                + " quads=" + quadsDrawn + " fieldQuads=" + fieldQuads + " fieldPasses=" + fieldPasses + " fieldComposites=" + OpticRenderer.fieldComposites + " heatShots=" + heatShots + " hotQuads=" + hotQuads
                + " hotHeat=" + hotHeatQuads + " heat=" + heatQuads + " drawers=" + drawers + " vpSkipped=" + vpSkipped + " errors=" + errors + " livePuffs=" + puffs.size() + " smokePuffs=" + smokePuffs
                + " liveSparks=" + sparks.size() + " setups=" + setups.size() + " scale=" + lastScale + " pivot=" + pivotParticles
                + " flashOn=" + flashOn + " sparksOn=" + sparksOn + " lightOn=" + lightOn + " heatOn=" + heatOn + " smokeOn=" + smokeOn
                + " " + vpProfile() + " " + MuzzleHeatModel.stats()
                + String.format(java.util.Locale.ROOT, " jetView=%.1f,%.1f shooterView=%.1f,%.1f", jetViewX, jetViewY, shooterViewX, shooterViewY)
                + (probeResult.isEmpty() ? "" : " probe[" + probeResult + "]")
                + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }

    /** QA: the effect nodes of an item's setup, "nodes= jets= fumes= sparks= smokes= launcher= chance=", or nil. */
    public static String describe(Object item) {
        Setup s = item == null ? null : setups.get(item);
        if (s == null) {
            return null;
        }
        int j = 0, f = 0, sp = 0, sm = 0;
        for (Node n : s.nodes) {
            j += n.jet != null ? 1 : 0;
            f += n.fume != null ? 1 : 0;
            sp += n.sparks != null ? 1 : 0;
            sm += n.smoke != null ? 1 : 0;
        }
        return "nodes=" + s.nodes.length + " jets=" + j + " fumes=" + f + " sparks=" + sp + " smokes=" + sm + " launcher=" + s.launcher
                + " chance=" + s.chance + " manager=" + (s.mgr != null);
    }
}
