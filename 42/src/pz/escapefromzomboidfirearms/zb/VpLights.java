package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;

import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoWorld;

/**
 * Tarkov's point lights in Viewpoint's lighting (VP_W6_L; optional, only
 * through ViewpointBridge's checked members). Viewpoint shows only its own
 * lights: a temporary IsoLightSource of PZ (the isometric light of MuzzleFx)
 * never reaches its frame. The scene of its frame carries up to MAX_TORCHES
 * point-light records of TORCH_STRIDE floats, rebuilt every snapshot (its own
 * muzzle flashes first, then other players' torches and headlights):
 *   0-2   position in scene space
 *   3     reach (scene units): the light falls linearly to nothing there
 *   4-6   direction, 7 the cosine of the cone's edge (-1: all around)
 *   8-10  colour in Viewpoint's light unit - PZ's cell light, the unit of its
 *         daylight and of a PZ torch's colour x strength - added to the light
 *         of the surface it reaches
 * Record 0 alone gets Viewpoint's cube shadow, and only when its own muzzle
 * flash put it there (SceneData.muzzleShadow). Ours are appended after
 * Viewpoint's and never take it: Tarkov's muzzle, explosion and back-blast
 * lights cast no shadows (LightShadows.None in effects.bundle and MuzzleLight).
 *
 * Every frame (main thread, MuzzleFx.afterWorld: after Viewpoint's snapshot,
 * before its render state is handed over) the live Tarkov lights of MuzzleFx
 * are appended with their own curve:
 *   muzzle      MuzzleLight: colour, intensity x LightIntensityCurve over the
 *               shot's ShotLength, range random in Range.x..y per shot; only
 *               with jets (a suppressor has none), as in the isometric view
 *   explosion   the point light of smallgrenade_expl, for BLAST_LIGHT_SECONDS
 *   back blast  the RShG-2 pivot lights at their place in the effect, as long
 * reach = the radius of the isometric light (tiles), colour = the Tarkov colour
 * x intensity / INTENSITY_UNIT. A shot of a Tarkov weapon replaces Viewpoint's
 * own muzzle light (ViewpointBridge.keepFlash gives it the camera kick only);
 * loaded before Viewpoint, its advice already lit the shot: then no muzzle
 * light of ours.
 *
 * Smoke in Viewpoint is lit as Tarkov lights it (Particles/Smoke Distorted,
 * disassembled vertex program: max(ambient, _Ambient) + the vertex lights,
 * attenuated by distance, no normal term), with Viewpoint's light in place of
 * Unity's: the ambient is the light Viewpoint gives a model in the particle's
 * square (OwnedLight.modelLightAt), the vertex lights are this frame's records.
 *
 * Viewpoint's own flashlight shines from the bone of the hand that holds the
 * light item - for a weapon light the grip, behind the hand on the handguard,
 * which it then lights from a few centimetres - along the camera. flashLens
 * moves that origin to the lens of the viewer's visible Tarkov weapon light
 * (TW/LightRender) and its direction to the weapon's bore (VpFlashAim).
 *
 * Errors here are errors in a use of Viewpoint: the whole bridge goes off.
 */
public final class VpLights {
    static final int MUZZLE = 0, BLAST = 1, PIVOT = 2;
    /** Floats per record and where its fields are (Viewpoint's torch record). */
    static final int STRIDE = 11, REACH = 3, DIR = 4, CONE = 7, COLOUR = 8;
    /**
     * Tarkov light intensity per light unit of Viewpoint: the conversion of the
     * isometric explosion and back-blast lights (MuzzleFx: min(1, intensity / 3));
     * Viewpoint lights in HDR, so no cap.
     */
    static final float INTENSITY_UNIT = 3f;
    /** A light this weak is off (Tarkov's MuzzleLight: intensity > 0.001). */
    static final float LEAST = 0.001f;
    /** MuzzleLight's colour when the data has none. */
    static final float[] MUZZLE_COLOUR = {1f, 0.65f, 0.45f};

    static final class Light {
        int kind;
        float x, y, h, reach, r, g, b, peak, life, shot;
        MuzzleFx.Curve curve;
        double born;
    }

    // main thread
    static final ArrayList<Light> lights = new ArrayList<>();
    static void clearShotLights() {
        lights.removeIf(light -> light.kind == MUZZLE || light.kind == PIVOT);
    }
    /** The records of the open frame (Viewpoint's and ours) for the smoke, and that frame's batch. */
    static float[] records = new float[STRIDE * 8];
    static int recordCount;
    static ViewpointPass.Batch recordBatch, lensBatch;
    /** A smoke particle's scene position (smoke: once per particle and frame). */
    private static final float[] at = new float[3];

    /** QA: Tarkov lights in Viewpoint on; off: its own muzzle light, PZ's light sources and smoke light as before. */
    public static volatile boolean enabled = true;
    // QA counters
    public static volatile long muzzles, blasts, pivots, frames, written, full, smokeLit, smokePz, lensMoved, lensKept, lensHidden;
    public static volatile String lastRecords = "";
    /**
     * Performance pass 23.10.2026: the last frame with records keeps its raw floats here (lastRawCount of them, 0:
     * lastRecords is current) and info() formats them - String.format per record ran every first-person frame. While
     * an explosion QA capture is armed (ExplosionFx.capture, set from OnTick before the frame's lights) the field is
     * formatted at once as before, since ExplosionFx.Capture copies it directly.
     */
    private static float[] lastRaw = new float[STRIDE * 8];
    private static int lastRawCount;

    private VpLights() {
    }

    /** Our Tarkov lights stand for Viewpoint's muzzle light (bridge on, not switched off). */
    static boolean replaces() {
        return enabled && ViewpointBridge.ready();
    }

    /** A light born now goes to Viewpoint (its view is on), not to PZ's light sources. */
    static boolean takes() {
        return replaces() && ViewpointBridge.perspective();
    }

    static void add(Light l) {
        l.born = MuzzleFx.time;
        lights.add(l);
    }

    /** MuzzleFx.light in Viewpoint's view: the shot's MuzzleLight at the flash (x, y tiles, h tiles). */
    static void muzzle(float x, float y, float h, MuzzleFx.Manager m) {
        if (!ViewpointBridge.loadedAfter()) {
            // Viewpoint's advice ran before our skip of the vanilla flash: its own light lit this shot.
            return;
        }
        Light l = new Light();
        l.kind = MUZZLE;
        l.x = x;
        l.y = y;
        l.h = h;
        float[] c = m.lampColour.length >= 3 ? m.lampColour : MUZZLE_COLOUR;
        l.r = c[0];
        l.g = c[1];
        l.b = c[2];
        l.peak = m.lampIntensity;
        // MuzzleLight: a range in Range.x..y for every shot
        l.reach = m.range0 + MuzzleFx.frand() * (m.range1 - m.range0);
        l.curve = m.lamp;
        l.shot = Math.max(1e-4f, m.shot);
        l.life = m.lamp.duration() * l.shot;
        add(l);
        muzzles++;
    }

    /** MuzzleFx.explosion in Viewpoint's view: the explosion light at (x, y tiles, h tiles). */
    static void blast(float x, float y, float h, float[] colour, float intensity, float reach, float seconds) {
        constant(BLAST, x, y, h, colour[0], colour[1], colour[2], intensity, reach, seconds);
        blasts++;
    }

    /** MuzzleFx.emitPivot in Viewpoint's view: a light of the pivot effect at (x, y tiles, h tiles). */
    static void pivot(float x, float y, float h, float r, float g, float b, float intensity, float reach, float seconds) {
        constant(PIVOT, x, y, h, r, g, b, intensity, reach, seconds);
        pivots++;
    }

    static void constant(int kind, float x, float y, float h, float r, float g, float b, float intensity, float reach, float seconds) {
        Light l = new Light();
        l.kind = kind;
        l.x = x;
        l.y = y;
        l.h = h;
        l.r = r;
        l.g = g;
        l.b = b;
        l.peak = intensity;
        l.reach = reach;
        l.life = seconds;
        add(l);
    }

    /** Tarkov intensity of a light at an age (seconds). */
    static float intensity(Light l, double age) {
        if (age < 0 || age > l.life) {
            return 0f;
        }
        if (l.kind == MUZZLE) {
            // MuzzleManager: t = time since the shot / ShotLength, intensity x LightIntensityCurve(t)
            float t = (float) (age / l.shot);
            return t > l.curve.duration() ? 0f : l.peak * l.curve.eval(t);
        }
        return l.peak;
    }

    /**
     * The open frame (main thread, MuzzleFx.afterWorld): our live lights after
     * Viewpoint's records of that frame, while there is room (the nearest to the
     * eye first); the frame's records are kept for the smoke.
     */
    private static boolean noRecordsSaid;

    static void frame(ViewpointPass.Batch b) throws IllegalAccessException {
        recordBatch = null;
        recordCount = 0;
        if (b == null || b.scene == null) {
            return;
        }
        Object scene = b.scene;
        // The records are optional in the bridge: without them only our lights are off.
        if (!ViewpointBridge.torchesReady()) {
            if (!noRecordsSaid) {
                noRecordsSaid = true;
                System.out.println("[TWZB] Viewpoint lights: the frame's light records missing or changed, our lights off (bridge stays on)");
            }
            return;
        }
        float[] t = ViewpointBridge.torches(scene);
        int n = ViewpointBridge.torchCount(scene), max = ViewpointBridge.maxTorches(), stride = ViewpointBridge.torchStride();
        if (stride != STRIDE || max < 1 || t == null || t.length < max * stride || n < 0 || n > max) {
            throw new IllegalStateException("torch records of " + stride + " floats, " + max + " at most, array "
                    + (t == null ? -1 : t.length) + ", count " + n);
        }
        double now = MuzzleFx.time;
        for (int i = lights.size() - 1; i >= 0; i--) {
            if (!enabled || now - lights.get(i).born > lights.get(i).life) {
                lights.remove(i);
            }
        }
        ArrayList<Light> live = new ArrayList<>(lights);
        if (live.size() > max - n) {
            // More lights than free records: the nearest to the eye first.
            float[] e = {b.origin[3], b.origin[4], b.origin[5]};
            live.sort((p, q) -> Double.compare(eyeDistance(b, p, e), eyeDistance(b, q, e)));
        }
        float[] s = new float[3];
        for (Light l : live) {
            float k = intensity(l, now - l.born) / INTENSITY_UNIT;
            if (k * Math.max(l.r, Math.max(l.g, l.b)) < LEAST) {
                continue;
            }
            if (n >= max) {
                full++;
                continue;
            }
            b.scene(l.x, l.y, l.h / ViewpointBridge.LEVEL, s, 0);
            int o = n * STRIDE;
            t[o] = s[0];
            t[o + 1] = s[1];
            t[o + 2] = s[2];
            t[o + REACH] = l.reach;
            t[o + DIR] = 0f;
            t[o + DIR + 1] = 0f;
            t[o + DIR + 2] = 0f;
            t[o + CONE] = -1f;
            t[o + COLOUR] = l.r * k;
            t[o + COLOUR + 1] = l.g * k;
            t[o + COLOUR + 2] = l.b * k;
            n++;
            written++;
        }
        ViewpointBridge.torchCount(scene, n);
        if (records.length < n * STRIDE) {
            records = new float[max * STRIDE];
        }
        System.arraycopy(t, 0, records, 0, n * STRIDE);
        recordCount = n;
        recordBatch = b;
        frames++;
        if (n > 0) {
            if (ExplosionFx.capture != null) {
                lastRecords = describe(records, n);
                lastRawCount = 0;
            } else {
                if (lastRaw.length < n * STRIDE) {
                    lastRaw = new float[max * STRIDE];
                }
                System.arraycopy(records, 0, lastRaw, 0, n * STRIDE);
                lastRawCount = n;
            }
        }
    }

    /** Squared scene distance of a light from the frame's eye e. */
    static double eyeDistance(ViewpointPass.Batch b, Light l, float[] e) {
        float[] s = new float[3];
        b.scene(l.x, l.y, l.h / ViewpointBridge.LEVEL, s, 0);
        double dx = s[0] - e[0], dy = s[1] - e[1], dz = s[2] - e[2];
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * The light of a smoke particle (x, y tiles, h height in tiles) in the open
     * frame into out[0..2], Viewpoint's light unit: Tarkov's max(ambient,
     * floor) + the point lights by distance. False: no light of Viewpoint there
     * (switched off, no frame, or its light of that square unknown) - the caller
     * takes PZ's, as Viewpoint lights a model with PZ's light then.
     */
    static boolean smoke(float x, float y, float h, float floor, float[] out) {
        ViewpointPass.Batch b = recordBatch;
        if (!enabled || b == null || b != ViewpointPass.open) {
            return false;
        }
        int c = ViewpointBridge.cellLight((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(h / ViewpointBridge.LEVEL));
        if (c < 0) {
            smokePz++;
            return false;
        }
        out[0] = Math.max(floor, (c & 255) / 255f);
        out[1] = Math.max(floor, (c >> 8 & 255) / 255f);
        out[2] = Math.max(floor, (c >> 16 & 255) / 255f);
        float[] s = at;
        b.scene(x, y, h / ViewpointBridge.LEVEL, s, 0);
        for (int i = 0; i < recordCount; i++) {
            int o = i * STRIDE;
            float dx = s[0] - records[o], dy = s[1] - records[o + 1], dz = s[2] - records[o + 2];
            float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz), reach = records[o + REACH];
            if (!(d < reach)) {
                continue;
            }
            float cone = records[o + CONE];
            if (cone > -1f && d > 1e-4f
                    && (dx * records[o + DIR] + dy * records[o + DIR + 1] + dz * records[o + DIR + 2]) / d < cone) {
                continue;
            }
            float f = 1f - d / reach;
            out[0] += records[o + COLOUR] * f;
            out[1] += records[o + COLOUR + 1] * f;
            out[2] += records[o + COLOUR + 2] * f;
        }
        smokeLit++;
        return true;
    }

    /**
     * TW/LightRender (main thread): the lens of the viewer's visible Tarkov
     * weapon light in this frame (x, y tiles, z levels) and the bore of the
     * animated weapon (dx, dy tiles, dz levels). When Viewpoint's own
     * flashlight is that light - on, at the held item, the Tarkov weapon in the
     * primary hand the only torch-cone light active - its origin moves from the
     * grip to the lens, if the lens is on the player's side of any wall, and it
     * shines along the bore instead of the camera (VpFlashAim; user report
     * 19.10.2026, item 20). A vanilla flashlight, another light in the hands or
     * an infrared-only light (no call) keep Viewpoint's own. Once per frame.
     */
    public static boolean flashLens(double x, double y, double z, double dx, double dy, double dz) {
        ViewpointPass.Batch b = ViewpointPass.open;
        if (!enabled || b == null || b.scene == null || b == lensBatch || !ViewpointBridge.perspective()) {
            return false;
        }
        lensBatch = b;
        try {
            IsoPlayer p = IsoPlayer.getInstance();
            return move(b, x, y, z, dx, dy, dz, p != null && ours(p) && beside(p, x, y, z));
        } catch (Throwable t) {
            ViewpointPass.fail("flash lens", t);
            return false;
        }
    }

    /** Frame b: Viewpoint's flashlight, when it is on at the held item and the light is ours (allowed), starts at the lens along the bore. */
    static boolean move(ViewpointPass.Batch b, double x, double y, double z, double dx, double dy, double dz, boolean allowed)
            throws IllegalAccessException {
        Object spots = ViewpointBridge.spots(b.scene);
        if (spots == null || !allowed || !ViewpointBridge.flashOn(spots) || !ViewpointBridge.flashAtItem(spots)) {
            lensKept++;
            return false;
        }
        float[] s = new float[3];
        b.scene(x, y, z, s, 0);
        ViewpointBridge.flashItem(spots, s);
        VpFlashAim.set(b, dx, dy, dz);
        lensMoved++;
        return true;
    }

    /**
     * TW/LightRender (main thread): the viewer's weapon is hidden by an action (TWDeviceGate: a magazine
     * top-off, unloading). Viewpoint's own flashlight, when it is that weapon's light at the held item, is
     * off for this frame: it would shine from the hand that now holds a cartridge or nothing (user report
     * 19.10.2026, item 14). The light item stays on; the next frame with the weapon drawn has it back.
     * Once per frame (with flashLens).
     */
    public static boolean flashHidden() {
        ViewpointPass.Batch b = ViewpointPass.open;
        if (!enabled || b == null || b.scene == null || b == lensBatch || !ViewpointBridge.perspective()) {
            return false;
        }
        lensBatch = b;
        try {
            IsoPlayer p = IsoPlayer.getInstance();
            return hide(b, p != null && ours(p));
        } catch (Throwable t) {
            ViewpointPass.fail("flash hidden", t);
            return false;
        }
    }

    /** Frame b: Viewpoint's flashlight, when it is on at the held item and the light is ours (allowed), is off. */
    static boolean hide(ViewpointPass.Batch b, boolean allowed) throws IllegalAccessException {
        Object spots = ViewpointBridge.spots(b.scene);
        if (spots == null || !allowed || !ViewpointBridge.flashOn(spots) || !ViewpointBridge.flashAtItem(spots)) {
            lensKept++;
            return false;
        }
        VpFlashAim.off(spots);
        lensHidden++;
        return true;
    }

    /** The lens (x, y tiles, z levels) is on p's side of any wall: p's square or an unblocked neighbour on its level. */
    static boolean beside(IsoPlayer p, double x, double y, double z) {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
        IsoGridSquare from = p.getCurrentSquare();
        IsoGridSquare to = cell == null ? null : cell.getGridSquare((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        return from != null && to != null && (to == from || to.z == from.z && Math.abs(to.x - from.x) <= 1
                && Math.abs(to.y - from.y) <= 1 && !from.isBlockedTo(to));
    }

    /**
     * Viewpoint's flashlight is the light of the Tarkov weapon in p's primary hand. Its snapshot takes
     * the torch-cone light item with the highest strength out of getActiveLightItems (the first on a
     * tie), so a weaker second torch - a pen light in the other hand, a lamp on the body - does not
     * take that light away; this check follows the same choice (W9 torch, user report 26.10.2026:
     * with any second cone light on, the flashlight kept Viewpoint's own origin at the grip).
     */
    static boolean ours(IsoPlayer p) {
        InventoryItem weapon = p.getPrimaryHandItem();
        if (weapon == null || !weapon.isTorchCone() || !"EscapeFromZomboidFirearms".equals(weapon.getModule())) {
            return false;
        }
        ArrayList<InventoryItem> items = new ArrayList<>();
        p.getActiveLightItems(items);
        InventoryItem best = null;
        for (InventoryItem item : items) {
            if (item != null && item.isTorchCone() && (best == null || item.getLightStrength() > best.getLightStrength())) {
                best = item;
            }
        }
        return best == weapon;
    }

    /**
     * QA (FB4-A item 16): a laser's points (x, y, z levels, alpha per point, as TW/LaserRender hands them to vpStrip) in
     * the open frame with its live relief: the largest distance of an inner point from the line between the two ends,
     * lifted point by point (the former strip) and as ViewpointPass.strip draws it (straightened); scene units.
     */
    public static String straightProbe(se.krka.kahlua.vm.KahluaTable points) {
        // from Lua no pass is open: the current scene's last batch (its relief lift), as VpFlashAim
        ViewpointPass.Batch b = ViewpointPass.open;
        if (b == null) {
            try {
                b = ViewpointPass.batchOf(ViewpointBridge.currentScene());
            } catch (ReflectiveOperationException | RuntimeException e) {
                b = null;
            }
        }
        int n = points == null ? 0 : points.len() / 4;
        if (b == null || n < 3) {
            return "n=" + n + " open=" + (b != null);
        }
        double[] xyza = new double[n * 4];
        for (int i = 0; i < n * 4; i++) {
            Object v = points.rawget(i + 1);
            xyza[i] = v instanceof Double d ? d : 0;
        }
        float[] lifted = new float[n * 3];
        for (int i = 0; i < n; i++) {
            b.scene(xyza[i * 4], xyza[i * 4 + 1], xyza[i * 4 + 2], lifted, i * 3);
        }
        float[] drawn = lifted.clone();
        ViewpointPass.straighten(b, xyza, n, drawn);
        return String.format(java.util.Locale.ROOT, "n=%d liftedDev=%.6f drawnDev=%.6f", n, deviation(lifted, n), deviation(drawn, n));
    }

    static double deviation(float[] p, int n) {
        double ax = p[0], ay = p[1], az = p[2], ex = p[n * 3 - 3] - ax, ey = p[n * 3 - 2] - ay, ez = p[n * 3 - 1] - az;
        double el = Math.max(1e-9, Math.sqrt(ex * ex + ey * ey + ez * ez)), worst = 0;
        for (int i = 1; i < n - 1; i++) {
            double px = p[i * 3] - ax, py = p[i * 3 + 1] - ay, pz = p[i * 3 + 2] - az;
            double cx = py * ez - pz * ey, cy = pz * ex - px * ez, cz = px * ey - py * ex;
            worst = Math.max(worst, Math.sqrt(cx * cx + cy * cy + cz * cz) / el);
        }
        return worst;
    }

    /** lastRecords, formatted now from the kept raw records when the last frame left them unformatted (main thread). */
    static String lastRecords() {
        if (lastRawCount > 0) {
            lastRecords = describe(lastRaw, lastRawCount);
            lastRawCount = 0;
        }
        return lastRecords;
    }

    static String describe(int n) {
        return describe(records, n);
    }

    static String describe(float[] records, int n) {
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int at = i * STRIDE;
            o.append(String.format(java.util.Locale.ROOT, "%s(%.2f,%.2f,%.2f r%.2f c%.2f %.3f,%.3f,%.3f)", i == 0 ? "" : " ",
                    records[at], records[at + 1], records[at + 2], records[at + REACH], records[at + CONE],
                    records[at + COLOUR], records[at + COLOUR + 1], records[at + COLOUR + 2]));
        }
        return o.toString();
    }

    /** QA: "on= live= muzzles= blasts= pivots= frames= written= full= smokeLit= smokePz= lensMoved= lensKept= lensHidden= | records". */
    public static String info() {
        return "on=" + enabled + " live=" + lights.size() + " muzzles=" + muzzles + " blasts=" + blasts + " pivots=" + pivots
                + " frames=" + frames + " written=" + written + " full=" + full + " smokeLit=" + smokeLit + " smokePz=" + smokePz
                + " lensMoved=" + lensMoved + " lensKept=" + lensKept + " lensHidden=" + lensHidden + " | last records " + lastRecords();
    }
}
