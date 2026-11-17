package pz.escapefromzomboidfirearms.zb;

import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.LosUtil;
import zombie.iso.Vector2;
import zombie.iso.objects.IsoBarbecue;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireManager;
import zombie.iso.objects.IsoFireplace;
import zombie.iso.objects.IsoGenerator;
import zombie.iso.objects.IsoStove;
import zombie.network.id.ObjectIDType;
import zombie.vehicles.BaseVehicle;

/**
 * Heat that is not a living character, for the thermal image: fires, lit
 * stoves, fireplaces and barbecues, running generators, warm vehicles (drawn
 * as their models with VehicleHeat; a round spot at the engine only when the
 * model was not drawn), and corpses cooling from the moment of death (drawn as
 * their own image from the game's corpse atlas). Collected on the main thread
 * for each thermal view, in its cone and range, in line of sight (walls, glass
 * and closed doors stop a thermal image), renewed every REFRESH_MS.
 * Thermal inputs are of the same scale as the bodies (TW/OpticView: living 2,
 * zombie 1.4); not Tarkov values: Tarkov has none of these sources.
 */
public final class HeatSources {
    public static final long REFRESH_MS = 200;
    public static final float FIRE = 3.5f, BURNING = 3.2f, STOVE = 2.6f, GENERATOR = 2.0f;
    public static final float CORPSE_LIVING = 2.0f, CORPSE_ZOMBIE = 1.4f, CORPSE_FLOOR = 0.25f;
    /** Cooling of a corpse towards the surroundings: e-folding time in game hours. */
    public static final float CORPSE_HOURS = 2.5f;

    /**
     * x, y, z, radius, input, kind, angle. Kinds: 0 round, 1 corpse (angle: its
     * heading), 2 fire and 3 burning character (radius: the fire's size 0..1;
     * angle: 1 under a roof, 0 in the open).
     */
    static final float[][] sources = new float[4][];
    /** The object of each source (a vehicle, a corpse) or null. */
    static final Object[][] owners = new Object[4][256];
    static final int[] counts = new int[4];
    static final long[] refreshed = new long[4];
    /**
     * Sources of the last refresh by kind (QA): fire, burning, stove, generator,
     * engine (warm vehicles in view), corpse; then running vehicles seen and
     * those in the view cone.
     */
    static final int[][] kinds = new int[4][8];

    private HeatSources() {
    }

    static String counts(int p) {
        if (p < 0 || p > 3) {
            return "";
        }
        int[] k = kinds[p];
        return "fire=" + k[0] + " burning=" + k[1] + " stove=" + k[2] + " generator=" + k[3] + " engine=" + k[4] + " corpse=" + k[5]
                + " running=" + k[6] + " runningInView=" + k[7];
    }

    static boolean inView(OpticState.View v, float x, float y, float z, float range) {
        if (Math.abs(z - v.pz) > 1.5f) {
            return false;
        }
        float dx = x - v.px, dy = y - v.py;
        float d = (float) Math.hypot(dx, dy);
        if (d > range) {
            return false;
        }
        if (d < 0.75f) {
            return true;
        }
        double a = Math.atan2(dy, dx) - v.base;
        a = Math.abs(Math.atan2(Math.sin(a), Math.cos(a)));
        return a <= v.half + v.edge;
    }

    static boolean clear(IsoCell cell, OpticState.View v, float x, float y, float z) {
        LosUtil.TestResults r = LosUtil.lineClear(cell, (int) v.px, (int) v.py, (int) v.pz, (int) x, (int) y, (int) z, false);
        return r == LosUtil.TestResults.Clear || r == LosUtil.TestResults.ClearThroughOpenDoor;
    }

    private static int add(float[] out, int n, float x, float y, float z, float radius, float input, int kind, float angle) {
        return add(out, null, n, x, y, z, radius, input, kind, angle, null);
    }

    private static int add(float[] out, Object[] own, int n, float x, float y, float z, float radius, float input, int kind, float angle, Object owner) {
        if ((n + 1) * 7 > out.length) {
            return n;
        }
        if (own != null) {
            own[n] = owner;
        }
        int k = n * 7;
        out[k] = x;
        out[k + 1] = y;
        out[k + 2] = z;
        out[k + 3] = radius;
        out[k + 4] = input;
        out[k + 5] = kind;
        out[k + 6] = angle;
        return n + 1;
    }

    /**
     * Viewpoint's perspective thermal view (VpHeat, main thread): the same
     * sources round (x, y, z) - range in tiles, at most 3 levels apart - with no
     * view cone and no line of sight (the perspective heat buffer is hidden by
     * the scene's depth instead); x, y, z, radius, input, kind, angle as
     * sources[], the owner of each into own. Vehicles are VehicleHeat.around's.
     */
    static int around(float x, float y, float z, float range, float[] out, Object[] own) {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
        if (cell == null) {
            return 0;
        }
        int n = 0;
        java.util.Arrays.fill(own, null);
        try {
            // (performance pass 23.10.2026: the same snapshots as refresh takes, see snapshot)
            int count = snapshot(IsoFireManager.FireStack);
            for (int i = 0; i < count; i++) {
                IsoFire fire = (IsoFire) snap[i];
                IsoGridSquare sq = fire.getSquare();
                if (fire.smoke || sq == null || !close(sq.getX() + 0.5f, sq.getY() + 0.5f, sq.getZ(), x, y, z, range)) {
                    continue;
                }
                float size = Math.max(0f, Math.min(1f, (fire.lightRadius - 1f) / 11f));
                n = add(out, n, sq.getX() + 0.5f, sq.getY() + 0.5f, sq.getZ(), size, FIRE, 2, sq.isOutside() ? 0f : 1f);
            }
            count = snapshot(IsoFireManager.CharactersOnFire_Stack);
            for (int i = 0; i < count; i++) {
                IsoGameCharacter c = (IsoGameCharacter) snap[i];
                if (c != null && !c.isDead() && close(c.getX(), c.getY(), c.getZ(), x, y, z, range)) {
                    IsoGridSquare sq = c.getCurrentSquare();
                    n = add(out, own, n, c.getX(), c.getY(), c.getZ(), 0.3f, BURNING, 3, sq != null && !sq.isOutside() ? 1f : 0f, c);
                }
            }
            count = snapshot(cell.getProcessIsoObjects());
            for (int i = 0; i < count; i++) {
                IsoObject o = (IsoObject) snap[i];
                float input = 0;
                if (o instanceof IsoStove s && s.Activated() && !s.isMicrowave() && !s.isBroken()) {
                    input = STOVE;
                } else if (o instanceof IsoFireplace f && f.isLit()) {
                    input = STOVE;
                } else if (o instanceof IsoBarbecue b && b.isLit()) {
                    input = STOVE;
                } else if (o instanceof IsoGenerator g && g.isActivated()) {
                    input = GENERATOR;
                }
                IsoGridSquare sq = o.getSquare();
                if (input > 0 && sq != null && close(sq.getX() + 0.5f, sq.getY() + 0.5f, sq.getZ(), x, y, z, range)) {
                    n = add(out, n, sq.getX() + 0.5f, sq.getY() + 0.5f, sq.getZ(), 0.45f, input, 0, 0);
                }
            }
            double hours = GameTime.getInstance().getWorldAgeHours();
            count = snapshot(ObjectIDType.DeadBody.getObjects());
            for (int i = 0; i < count; i++) {
                Object id = snap[i];
                if (!(id instanceof IsoDeadBody body) || body.isSkeleton() || body.getSquare() == null
                        || !close(body.getX(), body.getY(), body.getZ(), x, y, z, range)) {
                    continue;
                }
                float start = body.isZombie() ? CORPSE_ZOMBIE : CORPSE_LIVING;
                float death = body.getDeathTime();
                float age = death < 0 ? 24f : (float) Math.max(0, hours - death);
                float input = CORPSE_FLOOR + (start - CORPSE_FLOOR) * (float) Math.exp(-age / CORPSE_HOURS);
                n = add(out, own, n, body.getX(), body.getY(), body.getZ(), 0.5f, input, 1, body.getAngle(), body);
            }
        } catch (Throwable t) {
            VpHeat.fail("heat sources", t);
        } finally {
            release();
        }
        return n;
    }

    static boolean close(float x, float y, float z, float cx, float cy, float cz, float range) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy <= range * range && Math.abs(z - cz) <= 3f;
    }

    /**
     * Performance pass 23.10.2026: refresh took a new list of every fire, burning character, processed object and dead
     * body of the cell five times a second per thermal view; the same snapshot (c.toArray, as new ArrayList<>(c) takes
     * it) goes into one reused array, emptied after each refresh. Main thread.
     */
    private static Object[] snap = new Object[256];
    private static int snapped;

    private static int snapshot(java.util.Collection<?> c) {
        release();
        int n = c.size();
        if (n > snap.length) {
            snap = new Object[Math.max(n, snap.length * 2)];
        }
        Object[] a = c.toArray(snap);
        if (a != snap) {
            snap = a;
            n = a.length;
        }
        snapped = n;
        return n;
    }

    private static void release() {
        java.util.Arrays.fill(snap, 0, Math.min(snapped, snap.length), null);
        snapped = 0;
    }

    /** Main thread: renew the sources of each thermal view when due. */
    static void refresh(OpticRenderer.Slot[] slots, long now, float range) {
        IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.getCell();
        for (int p = 0; p < 4; p++) {
            OpticState.View v = slots[p].view;
            if (v == null || v.kind != OpticState.THERMAL || cell == null) {
                counts[p] = 0;
                VehicleHeat.select(p, null, null, range);
                continue;
            }
            if (now - refreshed[p] < REFRESH_MS && sources[p] != null) {
                continue;
            }
            refreshed[p] = now;
            float[] out = sources[p] != null ? sources[p] : new float[7 * 256];
            sources[p] = out;
            Object[] own = owners[p];
            java.util.Arrays.fill(own, null);
            int[] seen = kinds[p];
            java.util.Arrays.fill(seen, 0);
            int n = 0;
            try {
                VehicleHeat.select(p, v, cell, range);
                int count = snapshot(IsoFireManager.FireStack);
                for (int i = 0; i < count; i++) {
                    IsoFire fire = (IsoFire) snap[i];
                    IsoGridSquare sq = fire.getSquare();
                    if (fire.smoke || sq == null) {
                        continue;
                    }
                    float x = sq.getX() + 0.5f, y = sq.getY() + 0.5f, z = sq.getZ();
                    if (inView(v, x, y, z, range) && clear(cell, v, x, y, z)) {
                        // Its size from its light (IsoFire.LIGHT_RADIUS_LOW 5, MEDIUM 8, HIGH 12).
                        float size = Math.max(0f, Math.min(1f, (fire.lightRadius - 1f) / 11f));
                        n = add(out, n, x, y, z, size, FIRE, 2, sq.isOutside() ? 0f : 1f);
                        seen[0]++;
                    }
                }
                count = snapshot(IsoFireManager.CharactersOnFire_Stack);
                for (int i = 0; i < count; i++) {
                    IsoGameCharacter c = (IsoGameCharacter) snap[i];
                    if (c != null && !c.isDead() && inView(v, c.getX(), c.getY(), c.getZ(), range) && clear(cell, v, c.getX(), c.getY(), c.getZ())) {
                        IsoGridSquare sq = c.getCurrentSquare();
                        n = add(out, own, n, c.getX(), c.getY(), c.getZ(), 0.3f, BURNING, 3, sq != null && !sq.isOutside() ? 1f : 0f, c);
                        seen[1]++;
                    }
                }
                count = snapshot(cell.getProcessIsoObjects());
                for (int i = 0; i < count; i++) {
                    IsoObject o = (IsoObject) snap[i];
                    float input = 0;
                    if (o instanceof IsoStove s && s.Activated() && !s.isMicrowave() && !s.isBroken()) {
                        input = STOVE;
                    } else if (o instanceof IsoFireplace f && f.isLit()) {
                        input = STOVE;
                    } else if (o instanceof IsoBarbecue b && b.isLit()) {
                        input = STOVE;
                    } else if (o instanceof IsoGenerator g && g.isActivated()) {
                        input = GENERATOR;
                    }
                    IsoGridSquare sq = o.getSquare();
                    if (input > 0 && sq != null) {
                        float x = sq.getX() + 0.5f, y = sq.getY() + 0.5f, z = sq.getZ();
                        if (inView(v, x, y, z, range) && clear(cell, v, x, y, z)) {
                            n = add(out, n, x, y, z, 0.45f, input, 0, 0);
                            seen[input == GENERATOR ? 3 : 2]++;
                        }
                    }
                }
                // Warm vehicles: their models carry the heat (VehicleHeat); this
                // round spot at the engine stands in when a model is not drawn.
                for (BaseVehicle vehicle : VehicleHeat.selected.get(p)) {
                    if (vehicle.isEngineRunning()) {
                        seen[6]++;
                    }
                    float warmth = VehicleHeat.state(vehicle).engine;
                    // The engine area of a vehicle whose physics is not set up yet
                    // comes out far away (seen: -64000): the vehicle centre then.
                    Vector2 engine = vehicle.getAreaCenter("Engine");
                    float x = vehicle.getX(), y = vehicle.getY(), z = vehicle.getZ();
                    if (engine != null && Math.abs(engine.x - x) < 6 && Math.abs(engine.y - y) < 6) {
                        x = engine.x;
                        y = engine.y;
                    }
                    boolean visible = inView(v, x, y, z, range);
                    seen[7] += visible && vehicle.isEngineRunning() ? 1 : 0;
                    if (visible && warmth > VehicleHeat.WARM && clear(cell, v, x, y, z)) {
                        n = add(out, own, n, x, y, z, 0.8f, VehicleHeat.ENGINE * warmth, 0, 0, vehicle);
                        seen[4]++;
                    }
                }
                double hours = GameTime.getInstance().getWorldAgeHours();
                count = snapshot(ObjectIDType.DeadBody.getObjects());
                for (int i = 0; i < count; i++) {
                    Object id = snap[i];
                    if (!(id instanceof IsoDeadBody body) || body.isSkeleton() || body.getSquare() == null) {
                        continue;
                    }
                    float x = body.getX(), y = body.getY(), z = body.getZ();
                    if (!inView(v, x, y, z, range) || !clear(cell, v, x, y, z)) {
                        continue;
                    }
                    float start = body.isZombie() ? CORPSE_ZOMBIE : CORPSE_LIVING;
                    float death = body.getDeathTime();
                    float age = death < 0 ? 24f : (float) Math.max(0, hours - death);
                    float input = CORPSE_FLOOR + (start - CORPSE_FLOOR) * (float) Math.exp(-age / CORPSE_HOURS);
                    n = add(out, own, n, x, y, z, 0.5f, input, 1, body.getAngle(), body);
                    seen[5]++;
                }
            } catch (Throwable t) {
                OpticRenderer.fail("heat sources", t);
            } finally {
                release();
            }
            counts[p] = n;
        }
    }
}
