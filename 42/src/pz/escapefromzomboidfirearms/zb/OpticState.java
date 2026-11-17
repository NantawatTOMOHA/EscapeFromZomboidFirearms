package pz.escapefromzomboidfirearms.zb;

import se.krka.kahlua.vm.KahluaTable;

/**
 * What each local player sees through a Tarkov night vision or thermal scope,
 * set from Lua (TW/OpticView) on the main thread every update. The values are
 * the scope mode's own (TW/Data/Optics, read from the vanilla prefabs) plus the
 * vision cone and the player's position.
 */
public final class OpticState {
    public static final int THERMAL = 1;
    public static final int NIGHT_VISION = 2;

    /** Immutable snapshot of one player's scope image. */
    public static final class View {
        public int kind;
        // thermal: Hidden/ThermalVision values of the scope mode
        public int palette;
        public float coef, minimum, shift, depthFade, noise, fps;
        public float pixelBlocks, pixelAlpha;
        // thermal input of the PZ scene: brightness * env * gain, softly limited to envCap
        public float env, gain, envCap;
        // night vision: Hidden/NightVision values (Color, Intensity) and the amplification
        public float nvR, nvG, nvB, nvIntensity, nvGain, nvNoise;
        // scope camera post effects (ScopeEffectsData): BloomOptimized and the chromatic
        // aberration shift of the mode (thermal: ChromaticAberrationThermalShift), 0 when off
        public float bloomIntensity, bloomThreshold, bloomBlur, aberration;
        // centre of the scope lens on the floor (tiles): the aberration grows away from it
        public float lx, ly;
        // QA only: the scope image this much see-through (0..1) over the world
        public float debugMix;
        // the cone on the floor plane: apex (tiles), facing (radians), half angle, soft edge
        public float px, py, pz, base, half, edge;
    }

    public static final View[] views = new View[4];

    private OpticState() {
    }

    static float num(KahluaTable t, String key, float fallback) {
        Object v = t.rawget(key);
        return v instanceof Double d ? d.floatValue() : fallback;
    }

    /** Lua table -> snapshot; unknown or missing values keep a neutral default. */
    public static View read(KahluaTable t) {
        View v = new View();
        Object kind = t.rawget("kind");
        v.kind = "thermal".equals(kind) ? THERMAL : "nv".equals(kind) ? NIGHT_VISION : 0;
        v.palette = (int) num(t, "palette", 2);
        v.coef = num(t, "coef", 0.35f);
        v.minimum = num(t, "minimum", 0f);
        v.shift = num(t, "shift", -0.5f);
        v.depthFade = num(t, "depthFade", 0f);
        v.noise = num(t, "noise", 0f);
        v.fps = num(t, "fps", 0f);
        v.pixelBlocks = num(t, "pixelBlocks", 0f);
        v.pixelAlpha = num(t, "pixelAlpha", 0f);
        v.env = num(t, "env", 0.35f);
        v.gain = num(t, "gain", 1f);
        v.envCap = num(t, "envCap", 0f);
        v.nvR = num(t, "nvR", 0.18f);
        v.nvG = num(t, "nvG", 0.41f);
        v.nvB = num(t, "nvB", 0.18f);
        v.nvIntensity = num(t, "nvIntensity", 2f);
        v.nvGain = num(t, "nvGain", 1f);
        v.nvNoise = num(t, "nvNoise", 0f);
        v.bloomIntensity = num(t, "bloomIntensity", 0f);
        v.bloomThreshold = num(t, "bloomThreshold", 1f);
        v.bloomBlur = num(t, "bloomBlur", 0f);
        v.aberration = num(t, "aberration", 0f);
        v.debugMix = num(t, "debugMix", 0f);
        v.lx = num(t, "lx", num(t, "px", 0f));
        v.ly = num(t, "ly", num(t, "py", 0f));
        v.px = num(t, "px", 0f);
        v.py = num(t, "py", 0f);
        v.pz = num(t, "pz", 0f);
        v.base = num(t, "base", 0f);
        v.half = num(t, "half", 1.2566f);
        v.edge = num(t, "edge", 0.07f);
        return v;
    }
}
