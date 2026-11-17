package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.joml.Vector3f;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.properties.PropertyContainer;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.objects.IsoBarricade;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.iso.sprite.IsoSprite;
import zombie.scripting.objects.VehicleScript;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;
import zombie.vehicles.VehicleWindow;

/**
 * The world trace of TW/LaserRay (lasers, lights, rangefinders, the thermal line of sight) in Java
 * (performance pass 23.10.2026). The Lua trace read every square along a ray through reflection, 20 times a
 * second per beam and six rays per light: tens of thousands of Java calls per trace, the frame-rate loss and
 * stutter with lasers and lights on. This is the same algorithm, line for line: the same shapes in the same
 * order, the same arithmetic in doubles, the same sort (PZ's table.sort is a TimSort with "a<b and -1 or 1",
 * reproduced in {@link #ORDER}), the same per-trace caches. TW/LaserRay keeps the Lua form for QA A/B and
 * parity (R.legacy) and when the Java part is absent.
 */
public final class WorldRay {
    static final double EPS = .0001;
    /** TW/LaserGeometry constants, pushed from Lua (rayConfig) so that they keep one source. */
    static double verticalScale = 2.44949, glassTransmission = .91, windowBottom = .27, windowTop = .86;
    public static volatile long traces, visibles, scenes, squares, nanos;

    private WorldRay() { }

    public static void config(double vertical, double glass, double bottom, double top) {
        verticalScale = vertical; glassTransmission = glass; windowBottom = bottom; windowTop = top;
    }

    public static String info() {
        return "traces=" + traces + " visibles=" + visibles + " scenes=" + scenes + " squares=" + squares
                + " ms=" + String.format(java.util.Locale.ROOT, "%.3f", nanos / 1e6);
    }

    public static void reset() { traces = visibles = scenes = squares = nanos = 0; }

    static final class Space {
        final double x, y, fx, fy;
        Space(double x, double y, double fx, double fy) { this.x = x; this.y = y; this.fx = fx; this.fy = fy; }
    }

    static final class Shape {
        final IsoObject object;
        final double[] lo, hi;
        final String kind;
        final double transmission;
        final boolean transmits;
        final Space space;
        final int level;
        boolean cylinder;
        double cx, cy, cr;
        Shape(IsoObject object, double x1, double y1, double z1, double x2, double y2, double z2, String kind, double transmission, Space space) {
            this.object = object;
            lo = new double[] {x1, y1, z1};
            hi = new double[] {x2, y2, z2};
            this.kind = kind == null ? "opaque" : kind;
            this.transmits = !Double.isNaN(transmission);
            this.transmission = transmission;
            this.space = space;
            level = (int) Math.floor(z1 + .01);
        }
    }

    /** One trace's caches (Lua's context): squares, their shapes, vehicles, sight and alpha per viewer. */
    public static final class Context {
        IsoCell cell;
        final HashMap<Long, IsoGridSquare> loaded = new HashMap<>();
        final HashMap<Long, ArrayList<Shape>> squares = new HashMap<>();
        ArrayList<Shape> vehicles;
        final HashMap<Integer, IdentityHashMap<IsoGridSquare, Boolean>> vision = new HashMap<>();
        final HashMap<Integer, IdentityHashMap<IsoObject, Boolean>> alpha = new HashMap<>();
        final HashMap<Integer, Integer> levels = new HashMap<>();

        IsoGridSquare load(IsoCell from, double x, double y, double z) {
            long key = key(x, y, z);
            if (loaded.containsKey(key)) return loaded.get(key);
            IsoGridSquare sq = from == null ? null : from.getGridSquare(x, y, z);
            loaded.put(key, sq);
            return sq;
        }
    }

    public static Context context() { return new Context(); }

    static final class Ray {
        double x, y, z, dx, dy, dz, range, begin;
    }

    /** Lua keys are "x:y:z" of floored numbers; squares stay well inside 24 bits. */
    static long key(double x, double y, double z) {
        return (((long) x & 0xFFFFFFL) << 40) | (((long) y & 0xFFFFFFL) << 16) | ((long) z & 0xFFFFL);
    }

    static double min(double a, double b) { return b < a ? b : a; }
    static double max(double a, double b) { return b > a ? b : a; }

    static double number(KahluaTable t, String key) {
        Object v = t.rawget(key);
        return v instanceof Double d ? d : Double.NaN;
    }

    /** The Lua ray table, or null when a component is missing or not finite (Lua then traces it itself). */
    static Ray ray(KahluaTable t) {
        if (t == null) return null;
        Ray r = new Ray();
        r.x = number(t, "x"); r.y = number(t, "y"); r.z = number(t, "z");
        r.dx = number(t, "dx"); r.dy = number(t, "dy"); r.dz = number(t, "dz"); r.range = number(t, "range");
        double begin = number(t, "begin");
        r.begin = Double.isFinite(begin) && begin >= 0 ? begin : 0;
        for (double v : new double[] {r.x, r.y, r.z, r.dx, r.dy, r.dz, r.range}) {
            if (!Double.isFinite(v) || Math.abs(v) > 1e7) return null;
        }
        return r;
    }

    static IsoCell cell() { return IsoWorld.instance == null ? null : IsoWorld.instance.getCell(); }

    // ------------------------------------------------------------------ shapes (R.box .. R.squareShapes)

    static void box(ArrayList<Shape> out, IsoObject obj, double x1, double y1, double z1, double x2, double y2, double z2,
                    String kind, double transmission, Space space) {
        out.add(new Shape(obj, x1, y1, z1, x2, y2, z2, kind, transmission, space));
    }

    static void cylinder(ArrayList<Shape> out, IsoObject obj, double x, double y, double z, double r, double height, String kind) {
        box(out, obj, x - r, y - r, z, x + r, y + r, z + height, kind, Double.NaN, null);
        Shape s = out.get(out.size() - 1);
        s.cylinder = true; s.cx = x; s.cy = y; s.cr = r;
    }

    static void edge(ArrayList<Shape> out, IsoObject obj, double x, double y, double z, String n, double u1, double u2,
                     double h1, double h2, String kind, double trans) {
        if (h2 <= h1 || u2 <= u1) return;
        switch (n) {
            case "N" -> box(out, obj, x + u1, y - .003, z + h1, x + u2, y + .003, z + h2, kind, trans, null);
            case "W" -> box(out, obj, x - .003, y + u1, z + h1, x + .003, y + u2, z + h2, kind, trans, null);
            case "S" -> edge(out, obj, x, y + 1, z, "N", u1, u2, h1, h2, kind, trans);
            case "E" -> edge(out, obj, x + 1, y, z, "W", u1, u2, h1, h2, kind, trans);
            default -> { }
        }
    }

    static void edge(ArrayList<Shape> out, IsoObject obj, double x, double y, double z, String n, double u1, double u2,
                     double h1, double h2, String kind) {
        edge(out, obj, x, y, z, n, u1, u2, h1, h2, kind, Double.NaN);
    }

    static void frame(ArrayList<Shape> out, IsoObject obj, double x, double y, double z, String n, boolean window) {
        double bottom = window ? windowBottom : 0, top = window ? windowTop : .96;
        edge(out, obj, x, y, z, n, 0, .055, 0, 1, "frame"); edge(out, obj, x, y, z, n, .945, 1, 0, 1, "frame");
        edge(out, obj, x, y, z, n, .055, .945, 0, bottom, "frame"); edge(out, obj, x, y, z, n, .055, .945, top, 1, "frame");
    }

    /** {direction, kind} of a window or door, or null (aperture). */
    static String[] aperture(IsoObject obj) {
        if (obj instanceof IsoWindow w) return new String[] {w.getNorth() ? "N" : "W", "window"};
        if (obj instanceof IsoDoor d) return new String[] {d.getNorth() ? "N" : "W", "door"};
        if (obj instanceof IsoThumpable t) {
            if (t.isDoor()) return new String[] {t.getNorth() ? "N" : "W", "door"};
            if (t.isWindowN()) return new String[] {"N", "window"};
            else if (t.isWindowW()) return new String[] {"W", "window"};
        }
        return null;
    }

    static void barricade(ArrayList<Shape> out, IsoBarricade obj, double x, double y, double z, String n) {
        if (obj.isMetal()) edge(out, obj, x, y, z, n, .055, .945, windowBottom, windowTop, "metal");
        else if (obj.isMetalBar()) {
            for (int i = 1; i <= 5; i++) edge(out, obj, x, y, z, n, i / 6.0 - .02, i / 6.0 + .02, windowBottom, windowTop, "bar");
        } else {
            int count = obj.getNumPlanks();
            for (int i = 1; i <= count; i++) {
                double h = windowBottom + (i - 1) * (windowTop - windowBottom) / 4;
                edge(out, obj, x, y, z, n, .055, .945, h, h + (windowTop - windowBottom) / 4, "plank");
            }
        }
    }

    static boolean flag(PropertyContainer p, IsoFlagType f) { return p != null && p.has(f); }

    static final IsoFlagType[][] EDGE_FLAGS = {
        // wall, cut, door wall, window, door, hoppable, tall hoppable, transparent, wall trans
        {IsoFlagType.WallN, IsoFlagType.cutN, IsoFlagType.DoorWallN, IsoFlagType.windowN, IsoFlagType.doorN,
                IsoFlagType.HoppableN, IsoFlagType.TallHoppableN, IsoFlagType.transparentN, IsoFlagType.WallNTrans},
        {IsoFlagType.WallW, IsoFlagType.cutW, IsoFlagType.DoorWallW, IsoFlagType.windowW, IsoFlagType.doorW,
                IsoFlagType.HoppableW, IsoFlagType.TallHoppableW, IsoFlagType.transparentW, IsoFlagType.WallWTrans}};
    static final String[] SIDES = {"N", "W"};
    static final Pattern CURTAIN = Pattern.compile("curtain([NWSE])");

    /** P.furnitureHeight (TW/LaserGeometry). */
    static double furnitureHeight(String name, PropertyContainer properties) {
        if (properties.has("CustomName")) name = name + " " + String.valueOf(properties.get("CustomName")).toLowerCase();
        if (properties.has("GroupName")) name = name + " " + String.valueOf(properties.get("GroupName")).toLowerCase();
        double surface = properties.getSurface();
        if (name.contains("shelv") || name.contains("locker") || name.contains("wardrobe")) return .82;
        if (name.contains("fridge") || name.contains("refriger")) return .72;
        if (name.contains("counter") || name.contains("desk") || name.contains("table")) return max(.29, min(.43, surface / 192));
        if (name.contains("seat") || name.contains("bed")) return .31;
        if (name.contains("crate") || name.contains("bin") || name.contains("barrel") || name.contains("trashcontainer")) return .40;
        if (surface > 0) return min(.9, max(.18, surface / 192));
        return .72;
    }

    static void squareShapes(IsoGridSquare sq, ArrayList<Shape> out, Object owner) {
        squares++;
        double x = sq.getX(), y = sq.getY(), z = sq.getZ();
        List<IsoObject> objects = sq.getObjects();
        String[] openings = new String[2];
        for (int i = 0; i < objects.size(); i++) {
            String[] a = aperture(objects.get(i));
            if (a != null) openings[a[0].equals("N") ? 0 : 1] = a[1];
        }
        IsoObject floorObject = sq.getFloor();
        if (flag(sq.getProperties(), IsoFlagType.solidfloor)) box(out, floorObject, x, y, z - .003, x + 1, y + 1, z + .003, "floor", Double.NaN, null);
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        for (int i = 0; i < objects.size(); i++) {
            IsoObject obj = objects.get(i);
            if (obj == floorObject || seen.containsKey(obj)) continue;
            seen.put(obj, Boolean.TRUE);
            PropertyContainer p = obj.getProperties();
            IsoSprite sprite = obj.getSprite();
            String name = sprite == null ? null : sprite.getName();
            name = name == null ? "" : name.toLowerCase();
            String[] a = aperture(obj);
            String n = a == null ? null : a[0], k = a == null ? null : a[1];
            if (obj instanceof IsoWindow w) {
                frame(out, obj, x, y, z, n, true);
                if (!w.IsOpen() && !w.isSmashed()) edge(out, obj, x, y, z, n, .055, .945, windowBottom, windowTop, "glass", glassTransmission);
            } else if ("door".equals(k)) {
                frame(out, obj, x, y, z, n, false);
                boolean destroyed = obj instanceof IsoDoor d ? d.isDestroyed() : ((IsoThumpable) obj).isDestroyed();
                if (!destroyed) {
                    Object direction = obj instanceof IsoDoor d ? d.getSpriteEdge(false) : ((IsoThumpable) obj).getSpriteEdge(false);
                    edge(out, obj, x, y, z, String.valueOf(direction), .02, .98, 0, .96, "door");
                }
                if (obj instanceof IsoDoor d && d.HasCurtains() != null && !d.isCurtainOpen()) {
                    edge(out, obj, x, y, z, String.valueOf(d.getSpriteEdge(false)), .02, .98, .15, .85, "curtain");
                }
            } else if ("window".equals(k)) {
                frame(out, obj, x, y, z, n, true);
            } else if (obj instanceof IsoCurtain c) {
                Matcher m = CURTAIN.matcher(String.valueOf(c.getType()));
                String direction = m.find() ? m.group(1) : (c.getNorth() ? "N" : "W");
                if (!c.IsOpen()) edge(out, obj, x, y, z, direction, .02, .98, .15, .9, "curtain");
            } else if (obj instanceof IsoBarricade b) {
                barricade(out, b, x, y, z, String.valueOf(b.getDir()));
            } else if (obj instanceof IsoTree t) {
                double radius = .045 + t.getSize() * .018;
                cylinder(out, obj, x + .5, y + .5, z, radius, 1.6, "trunk");
            } else {
                boolean isEdge = false;
                for (int side = 0; side < 2; side++) {
                    IsoFlagType[] f = EDGE_FLAGS[side];
                    String d = SIDES[side];
                    boolean wall = flag(p, f[0]) || flag(p, f[1]) || flag(p, f[2]);
                    boolean window = flag(p, f[3]), door = flag(p, f[4]);
                    boolean low = flag(p, f[5]), tall = flag(p, f[6]);
                    if (wall || window || door || low || tall) {
                        isEdge = true;
                        String opening = openings[side] != null ? openings[side] : window ? "window" : door ? "door" : null;
                        if (opening != null) frame(out, obj, x, y, z, d, opening.equals("window"));
                        else if (low || tall) {
                            double height = tall ? .95 : .34;
                            if (flag(p, f[7])) {
                                // Separate posts and rails: an opening is not a probabilistic blocker.
                                for (int j = 0; j <= 3; j++) edge(out, obj, x, y, z, d, max(0, j / 3.0 - .022), min(1, j / 3.0 + .022), 0, height, "fence");
                                for (int j = 1; j <= 3; j++) { double h = height * j / 3; edge(out, obj, x, y, z, d, 0, 1, h - .012, h + .012, "fence"); }
                            } else edge(out, obj, x, y, z, d, 0, 1, 0, height, "fence");
                        } else if (flag(p, f[8]) || flag(p, f[7])) edge(out, obj, x, y, z, d, 0, 1, 0, 1, "glass", glassTransmission);
                        else edge(out, obj, x, y, z, d, 0, 1, 0, 1, "wall");
                    }
                }
                if (flag(p, IsoFlagType.WallSE)) {
                    // Diagonal walls need a local coordinate frame along their face.
                    Space b = new Space(x + .5, y + .5, .70710678, -.70710678);
                    box(out, obj, -.004, -.707, z, .004, .707, z + 1, "wall", Double.NaN, b);
                    isEdge = true;
                }
                if (!isEdge && (flag(p, IsoFlagType.solid) || flag(p, IsoFlagType.solidtrans))) {
                    if (flag(p, IsoFlagType.canBeCut) || flag(p, IsoFlagType.vegitation) || name.contains("bush")) {
                        box(out, obj, x + .1, y + .1, z, x + .9, y + .9, z + .5, "foliage", .8, null);
                    } else box(out, obj, x + .07, y + .07, z, x + .93, y + .93, z + furnitureHeight(name, p), "furniture", Double.NaN, null);
                }
            }
        }
        ArrayList<IsoMovingObject> moving = sq.getMovingObjects();
        for (int i = 0; i < moving.size(); i++) {
            IsoMovingObject obj = moving.get(i);
            if (obj != owner && !seen.containsKey(obj) && obj instanceof IsoGameCharacter c) {
                seen.put(obj, Boolean.TRUE);
                boolean sitting = (c instanceof zombie.characters.IsoZombie zom && zom.isSitAgainstWall())
                        || c.isSitOnGround() || c.isSittingOnFurniture();
                if (sitting) {
                    cylinder(out, obj, c.getX(), c.getY(), c.getZ() + .04, .22, .46 - .04, "body");
                } else if (c.isOnFloor()) {
                    box(out, obj, c.getX() - .35, c.getY() - .2, c.getZ() + .04, c.getX() + .35, c.getY() + .2, c.getZ() + .15, "body", Double.NaN, null);
                } else {
                    double h = c.isSneaking() ? .5 : .73;
                    cylinder(out, obj, c.getX(), c.getY(), c.getZ() + .04, .20, h - .04, "body");
                }
            }
        }
    }

    static void vehicleShapes(BaseVehicle v, ArrayList<Shape> out) {
        VehicleScript script = v.getScript();
        if (script == null) return;
        Vector3f e = script.getExtents();
        Vector3f direction = v.getForwardVector(new Vector3f());
        double fx = direction.x(), fy = direction.z();
        double length = Math.sqrt(fx * fx + fy * fy);
        if (length < .01) return;
        Space b = new Space(v.getX(), v.getY(), fx / length, fy / length);
        double w = e.x() / 2.0, l = e.z() / 2.0, z = v.getZ();
        double roof = max(.48, e.y() / verticalScale);
        double bottom = min(.62, roof * .57);
        box(out, v, -w, -l, z + .03, w, l, z + bottom, "vehicle-body", Double.NaN, b);
        box(out, v, -w, -l * .35, z + roof - .035, w, l * .35, z + roof, "vehicle-roof", Double.NaN, b);
        for (int s = -1; s <= 1; s += 2) {
            double a = s < 0 ? -w : w - .025, c = s < 0 ? -w + .025 : w;
            String side = s < 0 ? "Left" : "Right";
            pane(out, v, b, z, bottom, roof, "WindowFront" + side, a, 0, c, l * .35);
            pane(out, v, b, z, bottom, roof, "WindowRear" + side, a, -l * .35, c, 0);
            for (double t : new double[] {-.35, 0, .35}) {
                box(out, v, a, l * t - .025, z + bottom, c, l * t + .025, z + roof, "vehicle-frame", Double.NaN, b);
            }
        }
        for (int s = -1; s <= 1; s += 2) {
            double a = l * s * .35 - .025, c = l * s * .35 + .025;
            pane(out, v, b, z, bottom, roof, s > 0 ? "Windshield" : "WindshieldRear", -w + .04, a, w - .04, c);
        }
    }

    static void pane(ArrayList<Shape> out, BaseVehicle v, Space b, double z, double bottom, double roof, String id,
                     double x1, double y1, double x2, double y2) {
        VehiclePart part = v.getPartById(id);
        VehicleWindow window = part == null ? null : part.getWindow();
        String state = window == null ? "panel" : !window.isDestroyed() && !window.isOpen() ? "glass" : "open";
        if (!state.equals("open")) box(out, v, x1, y1, z + bottom, x2, y2, z + roof - .035, "vehicle-" + state, state.equals("glass") ? .86 : Double.NaN, b);
    }

    // ------------------------------------------------------------------ scene, intersect, cast (R.scene .. R.cast)

    /** Shapes along a ray (3x3 squares around each visited cell, vehicles); {@code unloaded[0]} the distance to unloaded squares, else NaN. */
    static ArrayList<Shape> scene(Ray ray, Object owner, boolean includeVehicles, Context context, double[] unloaded) {
        scenes++;
        if (context.cell == null) context.cell = cell();
        IsoCell cell = context.cell;
        ArrayList<Shape> out = new ArrayList<>();
        IdentityHashMap<Shape, Boolean> seen = new IdentityHashMap<>();
        HashMap<Long, Boolean> cells = new HashMap<>();
        double[] g = {Math.floor(ray.x), Math.floor(ray.y), Math.floor(ray.z)};
        double[] o = {ray.x, ray.y, ray.z}, d = {ray.dx, ray.dy, ray.dz};
        double[] step = new double[3], nextT = new double[3], delta = new double[3];
        for (int i = 0; i < 3; i++) {
            step[i] = d[i] >= 0 ? 1 : -1;
            delta[i] = Math.abs(d[i]) > .000001 ? 1 / Math.abs(d[i]) : Double.POSITIVE_INFINITY;
            nextT[i] = delta[i] == Double.POSITIVE_INFINITY ? Double.POSITIVE_INFINITY : ((d[i] >= 0 ? g[i] + 1 - o[i] : o[i] - g[i]) * delta[i]);
        }
        double travelled = 0;
        unloaded[0] = Double.NaN;
        double visits = Math.ceil(ray.range * (Math.abs(ray.dx) + Math.abs(ray.dy) + Math.abs(ray.dz))) + 4;
        for (int visit = 1; visit <= visits; visit++) {
            IsoGridSquare center = context.load(cell, g[0], g[1], g[2]);
            if (center == null) {
                IsoGridSquare base = context.load(cell, g[0], g[1], 0);
                // Missing upper squares in a loaded column are ordinary open air.
                if (g[2] <= 0 || base == null) { unloaded[0] = travelled; break; }
            }
            for (int ax = -1; ax <= 1; ax++) {
                for (int ay = -1; ay <= 1; ay++) {
                    double x = g[0] + ax, y = g[1] + ay;
                    long key = key(x, y, g[2]);
                    if (cells.containsKey(key)) continue;
                    cells.put(key, Boolean.TRUE);
                    ArrayList<Shape> shapes = context.squares.get(key);
                    if (shapes == null) {
                        IsoGridSquare sq = context.load(cell, x, y, g[2]);
                        shapes = new ArrayList<>();
                        if (sq != null) squareShapes(sq, shapes, owner);
                        context.squares.put(key, shapes);
                    }
                    if (visitLog != null) visitLog.add((long) x + "," + (long) y + "," + (long) g[2] + "=" + shapes.size());
                    for (Shape shape : shapes) {
                        if (!seen.containsKey(shape)) { seen.put(shape, Boolean.TRUE); out.add(shape); }
                    }
                }
            }
            double t = min(min(nextT[0], nextT[1]), nextT[2]);
            if (t > ray.range) break;
            travelled = t;
            for (int i = 0; i < 3; i++) {
                if (nextT[i] <= t + EPS) { g[i] = g[i] + step[i]; nextT[i] = nextT[i] + delta[i]; }
            }
        }
        if (includeVehicles) {
            if (context.vehicles == null) {
                context.vehicles = new ArrayList<>();
                if (cell != null) {
                    for (BaseVehicle v : cell.getVehicles()) {
                        if (Math.abs(v.getX() - ray.x) + Math.abs(v.getY() - ray.y) < ray.range * 1.42 + 12) vehicleShapes(v, context.vehicles);
                    }
                }
            }
            out.addAll(context.vehicles);
        }
        return out;
    }

    /** R.intersect: {enter, leave, nx, ny, nz} into {@code hit}, false when the ray misses. */
    static boolean intersect(Ray ray, Shape s, double[] hit) {
        double ox = ray.x, oy = ray.y, dx = ray.dx, dy = ray.dy;
        if (s.space != null) {
            Space b = s.space;
            double x = ox - b.x, y = oy - b.y;
            ox = x * b.fy - y * b.fx; oy = x * b.fx + y * b.fy;
            double rdx = dx * b.fy - dy * b.fx, rdy = dx * b.fx + dy * b.fy;
            dx = rdx; dy = rdy;
        }
        double enter = 0, leave = ray.range, sign = 0;
        int axis = 0;
        for (int i = 0; i < 3; i++) {
            double o = i == 0 ? ox : i == 1 ? oy : ray.z, d = i == 0 ? dx : i == 1 ? dy : ray.dz;
            if (Math.abs(d) < .0000001) {
                if (o < s.lo[i] - EPS || o > s.hi[i] + EPS) return false;
            } else {
                double a = (s.lo[i] - o) / d, b = (s.hi[i] - o) / d;
                double normal = -1;
                if (a > b) { double swap = a; a = b; b = swap; normal = 1; }
                if (a > enter) { enter = a; axis = i + 1; sign = normal; }
                leave = min(leave, b);
                if (enter > leave + EPS) return false;
            }
        }
        if (s.cylinder) {
            double x = ox - s.cx, y = oy - s.cy;
            double a = dx * dx + dy * dy, b = 2 * (x * dx + y * dy), q = x * x + y * y - s.cr * s.cr;
            if (a < .0000001) {
                if (q > 0) return false;
            } else {
                double discriminant = b * b - 4 * a * q;
                if (discriminant < 0) return false;
                double t1 = (-b - Math.sqrt(discriminant)) / (2 * a), t2 = (-b + Math.sqrt(discriminant)) / (2 * a);
                if (t1 > enter) { enter = t1; axis = 4; }
                leave = min(leave, t2);
                if (enter > leave + EPS) return false;
            }
        }
        if (leave < EPS || enter > ray.range) return false;
        double nx = 0, ny = 0, nz = 0;
        if (axis == 1) nx = sign; else if (axis == 2) ny = sign; else if (axis == 3) nz = sign;
        else if (axis == 4) { nx = (ox + dx * enter - s.cx) / s.cr; ny = (oy + dy * enter - s.cy) / s.cr; }
        if (s.space != null) {
            Space b = s.space;
            double rnx = nx * b.fy + ny * b.fx, rny = -nx * b.fx + ny * b.fy;
            nx = rnx; ny = rny;
        }
        hit[0] = max(0, enter); hit[1] = leave; hit[2] = nx; hit[3] = ny; hit[4] = nz;
        return true;
    }

    static final class Hit {
        final double distance, exit, nx, ny, nz;
        final Shape shape;
        Hit(double[] h, Shape shape) { distance = h[0]; exit = h[1]; nx = h[2]; ny = h[3]; nz = h[4]; this.shape = shape; }
    }

    /** PZ's table.sort comparator for "a.distance<b.distance" (LuaManager.GlobalObject.TimSortComparator). */
    static final java.util.Comparator<Hit> ORDER = (a, b) -> a == b ? 0 : a.distance < b.distance ? -1 : 1;

    /** R.cast. viewPlayer < 0 and viewFloor NaN: no visibility filter (the traces). */
    static KahluaTable cast(Ray ray, KahluaTable rayTable, ArrayList<Shape> shapes, int viewPlayer, double viewFloor) {
        return cast(ray, rayTable, shapes, viewPlayer, viewFloor, null);
    }

    static KahluaTable cast(Ray ray, KahluaTable rayTable, ArrayList<Shape> shapes, int viewPlayer, double viewFloor, Object skipObject) {
        ArrayList<Hit> hits = new ArrayList<>();
        double[] h = new double[5];
        for (Shape s : shapes) {
            boolean visible = true;
            if (skipObject != null && s.object == skipObject) visible = false;
            if (visible && viewPlayer >= 0 && s.object != null) visible = s.object.getAlpha(viewPlayer) > .12;
            if (visible && !Double.isNaN(viewFloor) && s.level > viewFloor) visible = false;
            if (visible && intersect(ray, s, h)) hits.add(new Hit(h, s));
        }
        Hit[] sorted = hits.toArray(new Hit[0]);
        java.util.Arrays.sort(sorted, ORDER);
        KahluaTable result = LuaManager.platform.newTable();
        KahluaTable transmissions = LuaManager.platform.newTable();
        double distance = ray.range, power = 1;
        String reason = "range";
        Hit hit = null;
        IdentityHashMap<Object, Boolean> transmitted = new IdentityHashMap<>();
        for (Hit x : sorted) {
            Shape s = x.shape;
            if (!s.transmits) { distance = x.distance; hit = x; reason = s.kind; break; }
            Object key = s.kind.equals("vehicle-glass") ? s : (s.object != null ? s.object : s);
            if (!transmitted.containsKey(key)) {
                transmitted.put(key, Boolean.TRUE);
                power = power * s.transmission;
                KahluaTable t = LuaManager.platform.newTable();
                t.rawset("distance", x.distance); t.rawset("power", power);
                transmissions.rawset(transmissions.len() + 1, t);
            }
        }
        result.rawset("distance", distance); result.rawset("power", power); result.rawset("transmissions", transmissions);
        result.rawset("reason", reason); result.rawset("ray", rayTable);
        if (hit != null) result.rawset("hit", hitTable(hit));
        result.rawset("x", ray.x + ray.dx * distance); result.rawset("y", ray.y + ray.dy * distance); result.rawset("z", ray.z + ray.dz * distance);
        return result;
    }

    static KahluaTable hitTable(Hit hit) {
        KahluaTable t = LuaManager.platform.newTable();
        t.rawset("distance", hit.distance); t.rawset("exit", hit.exit);
        t.rawset("nx", hit.nx); t.rawset("ny", hit.ny); t.rawset("nz", hit.nz);
        // The shape's kind and object (QA, logs); Lua's hit.shape table is not rebuilt.
        t.rawset("kind", hit.shape.kind);
        if (hit.shape.object != null) t.rawset("object", hit.shape.object);
        return t;
    }

    // ------------------------------------------------------------------ Lua entry points (TWZB.ray*)

    /** R.trace: the cast along a ray, cut at unloaded squares; result.context is this trace's Context. */
    public static KahluaTable trace(KahluaTable rayTable, Object owner) {
        Ray ray = ray(rayTable);
        if (ray == null) return null;
        long t0 = System.nanoTime();
        try {
            traces++;
            Context context = new Context();
            double[] unloaded = new double[1];
            ArrayList<Shape> shapes = scene(ray, owner, true, context, unloaded);
            KahluaTable aim = rayTable.rawget("aim") instanceof KahluaTable a ? a : null;
            boolean ballisticsAim = aim != null && "ballistics".equals(aim.rawget("mode"));
            Object skipTarget = ballisticsAim ? aim.rawget("target") : null;
            KahluaTable result = cast(ray, rayTable, shapes, -1, Double.NaN, skipTarget);
            result.rawset("context", context);
            if (ballisticsAim) {
                double tx = number(aim, "targetX"), ty = number(aim, "targetY"), tz = number(aim, "targetZ");
                if (Double.isFinite(tx) && Double.isFinite(ty) && Double.isFinite(tz)) {
                    double tCenter = (tx - ray.x) * ray.dx + (ty - ray.y) * ray.dy
                            + (tz - ray.z) * ray.dz * verticalScale * verticalScale;
                    double tFront = Math.max(ray.begin + 0.02, tCenter - 0.08);
                    double curDist = (Double) result.rawget("distance");
                    if (tFront >= ray.begin && tFront < curDist && tFront <= ray.range) {
                        double hl = Math.hypot(ray.dx, ray.dy);
                        double nx = hl > 1e-6 ? -ray.dx / hl : 0, ny = hl > 1e-6 ? -ray.dy / hl : 0;
                        KahluaTable hit = LuaManager.platform.newTable();
                        hit.rawset("distance", tFront); hit.rawset("exit", tCenter);
                        hit.rawset("nx", nx); hit.rawset("ny", ny); hit.rawset("nz", 0.0);
                        hit.rawset("kind", "body");
                        if (skipTarget != null) hit.rawset("object", skipTarget);
                        result.rawset("distance", tFront); result.rawset("reason", "body"); result.rawset("hit", hit);
                        result.rawset("x", ray.x + ray.dx * tFront);
                        result.rawset("y", ray.y + ray.dy * tFront);
                        result.rawset("z", ray.z + ray.dz * tFront);
                    }
                }
            }
            double cut = unloaded[0];
            if (!Double.isNaN(cut) && cut < ((Double) result.rawget("distance"))) {
                result.rawset("distance", cut); result.rawset("hit", null); result.rawset("reason", "unloaded");
                result.rawset("x", ray.x + ray.dx * cut); result.rawset("y", ray.y + ray.dy * cut); result.rawset("z", ray.z + ray.dz * cut);
            }
            return result;
        } finally { nanos += System.nanoTime() - t0; }
    }

    /** R.cursorTarget's cast: what the viewer sees of the floor under the cursor (alpha and floor filtered). */
    public static KahluaTable cursor(KahluaTable rayTable, Object owner, double viewPlayer, double viewFloor) {
        Ray ray = ray(rayTable);
        if (ray == null) return null;
        long t0 = System.nanoTime();
        try {
            ArrayList<Shape> shapes = scene(ray, owner, true, new Context(), new double[1]);
            return cast(ray, rayTable, shapes, (int) viewPlayer, viewFloor);
        } finally { nanos += System.nanoTime() - t0; }
    }

    /** OpticView's line of sight: a shape of another kind than skip meets the ray; null for a ray Lua traces itself. */
    public static Boolean blocked(KahluaTable rayTable, Object owner, Object context, String skip) {
        Ray ray = ray(rayTable);
        if (ray == null) return null;
        long t0 = System.nanoTime();
        try {
            Context c = context instanceof Context x ? x : new Context();
            double[] h = new double[5];
            for (Shape s : scene(ray, owner, true, c, new double[1])) {
                if (!s.kind.equals(skip) && intersect(ray, s, h)) return true;
            }
            return false;
        } finally { nanos += System.nanoTime() - t0; }
    }

    /** QA parity: the shapes of R.scene along a ray, one line each, in scene order (kind lo hi transmission space cylinder level object). */
    public static String describeScene(KahluaTable rayTable, Object owner) {
        Ray ray = ray(rayTable);
        if (ray == null) return "invalid";
        StringBuilder b = new StringBuilder();
        for (Shape s : scene(ray, owner, true, new Context(), new double[1])) b.append(describe(s)).append('\n');
        return b.toString();
    }

    /** QA parity: the squares a scene visits, in order, with their shape counts ("x,y,z=n" lines). */
    static ArrayList<String> visitLog;
    public static String describeVisits(KahluaTable rayTable, Object owner) {
        Ray ray = ray(rayTable);
        if (ray == null) return "invalid";
        visitLog = new ArrayList<>();
        try {
            scene(ray, owner, false, new Context(), new double[1]);
            return String.join(";", visitLog);
        } finally { visitLog = null; }
    }

    /** QA parity: the objects of a square as squareShapes reads them: class, sprite, floor, aperture and the edge flags. */
    public static String describeSquare(double x, double y, double z) {
        IsoCell cell = cell();
        IsoGridSquare sq = cell == null ? null : cell.getGridSquare(x, y, z);
        if (sq == null) return "no square";
        StringBuilder b = new StringBuilder();
        IsoObject floor = sq.getFloor();
        b.append("square ").append(sq.getX()).append(',').append(sq.getY()).append(',').append(sq.getZ())
                .append(" solidfloor=").append(flag(sq.getProperties(), IsoFlagType.solidfloor)).append('\n');
        List<IsoObject> objects = sq.getObjects();
        for (int i = 0; i < objects.size(); i++) {
            IsoObject o = objects.get(i);
            PropertyContainer p = o.getProperties();
            IsoSprite sprite = o.getSprite();
            String[] a = aperture(o);
            b.append(o.getClass().getSimpleName()).append('@').append(Integer.toHexString(System.identityHashCode(o)))
                    .append(" sprite=").append(sprite == null ? "-" : sprite.getName()).append(" floor=").append(o == floor)
                    .append(" aperture=").append(a == null ? "-" : a[0] + a[1]).append(" props=")
                    .append(p == null ? "-" : Integer.toHexString(System.identityHashCode(p)));
            for (IsoFlagType f : new IsoFlagType[] {IsoFlagType.WallN, IsoFlagType.WallW, IsoFlagType.WallSE, IsoFlagType.cutN, IsoFlagType.cutW,
                    IsoFlagType.DoorWallN, IsoFlagType.DoorWallW, IsoFlagType.windowN, IsoFlagType.windowW, IsoFlagType.doorN, IsoFlagType.doorW,
                    IsoFlagType.solid, IsoFlagType.solidtrans, IsoFlagType.solidfloor}) {
                if (flag(p, f)) b.append(' ').append(f.name());
            }
            b.append('\n');
        }
        ArrayList<Shape> shapes = new ArrayList<>();
        squareShapes(sq, shapes, null);
        b.append("shapes=").append(shapes.size());
        for (Shape s : shapes) b.append(' ').append(s.kind);
        b.append('\n');
        return b.toString();
    }

    static String describe(Shape s) {
        java.util.Locale l = java.util.Locale.ROOT;
        return String.format(l, "%s %.5f,%.5f,%.5f %.5f,%.5f,%.5f t=%s sp=%s cy=%s lv=%d o=%s", s.kind, s.lo[0], s.lo[1], s.lo[2], s.hi[0], s.hi[1], s.hi[2],
                s.transmits ? String.format(l, "%.3f", s.transmission) : "-",
                s.space == null ? "-" : String.format(l, "%.3f,%.3f,%.4f,%.4f", s.space.x, s.space.y, s.space.fx, s.space.fy),
                s.cylinder ? String.format(l, "%.4f,%.4f,%.4f", s.cx, s.cy, s.cr) : "-", s.level,
                s.object == null ? "-" : s.object.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(s.object)));
    }

    /** R.visible: the point lies on a square the viewer sees and no opaque shape of its level covers it from the camera. */
    public static boolean visible(double x, double y, double z, double viewer, Object owner, Object context) {
        long t0 = System.nanoTime();
        try {
            visibles++;
            int n = (int) viewer;
            Context c = context instanceof Context ctx ? ctx : new Context();
            IsoCell cell = cell();
            double fx = Math.floor(x), fy = Math.floor(y), fz = Math.floor(z);
            IsoGridSquare sq = c.load(cell, fx, fy, fz);
            if (sq == null && fz > 0) sq = c.load(cell, fx, fy, 0);
            if (sq == null) return false;
            IdentityHashMap<IsoGridSquare, Boolean> visibleSquares = c.vision.computeIfAbsent(n, k -> new IdentityHashMap<>());
            Boolean seen = visibleSquares.get(sq);
            if (seen == null) { seen = sq.isCanSee(n); visibleSquares.put(sq, seen); }
            if (!seen) return false;
            Ray ray = new Ray();
            ray.x = x + .002; ray.y = y + .002; ray.z = z + .00067; ray.dx = 3; ray.dy = 3; ray.dz = 1; ray.range = .95;
            ArrayList<Shape> shapes = scene(ray, owner, true, c, new double[1]);
            Integer level = c.levels.get(n);
            if (level == null) {
                IsoPlayer player = n >= 0 && n < IsoPlayer.players.length ? IsoPlayer.players[n] : null;
                level = (int) (player != null ? Math.floor(player.getZ()) : fz);
                c.levels.put(n, level);
            }
            IdentityHashMap<IsoObject, Boolean> alpha = c.alpha.computeIfAbsent(n, k -> new IdentityHashMap<>());
            double[] h = new double[5];
            for (Shape shape : shapes) {
                if (!shape.transmits && !shape.kind.equals("body") && shape.level <= level && shape.hi[2] >= ray.z) {
                    IsoObject obj = shape.object;
                    Boolean shows = null;
                    if (obj != null) {
                        shows = alpha.get(obj);
                        if (shows == null) { shows = obj.getAlpha(n) > .12; alpha.put(obj, shows); }
                    }
                    if ((obj == null || shows) && intersect(ray, shape, h)) return false;
                }
            }
            return true;
        } finally { nanos += System.nanoTime() - t0; }
    }

    // ------------------------------------------------------------------ Isometric 3D aim target (W9-aim)

    record IsoAim(double x, double y, double z, String mode, IsoGameCharacter target) {
        IsoAim(double x, double y, double z, String mode) {
            this(x, y, z, mode, null);
        }
    }

    private static final String[] AIM_BONES = {
        "Bip01_Head", "Bip01_Spine1", "Bip01_Spine", "Bip01_Pelvis", "Bip01_L_Thigh", "Bip01_R_Thigh"
    };
    private static final zombie.core.physics.BallisticsController.AimingVectorParameters ISO_AIM_PARAMS =
            new zombie.core.physics.BallisticsController.AimingVectorParameters();
    private static volatile boolean bcFieldsChecked;
    private static java.lang.reflect.Field bcCachedCountField, bcCachedTargetsField;
    static volatile boolean qaFloorAim;

    public static void setQaFloorAim(boolean on) {
        qaFloorAim = zombie.core.Core.debug && on;
    }

    static boolean isFloorAim(IsoPlayer p) {
        return p != null && (p.isAimAtFloor() || (zombie.core.Core.debug && qaFloorAim));
    }

    private static double screenIsoX(int index, double z) {
        float sx = zombie.input.AimingReticle.getXA(index), sy = zombie.input.AimingReticle.getYA(index);
        return LuaManager.GlobalObject.screenToIsoX(index, sx, sy, (float) z);
    }

    private static double screenIsoY(int index, double z) {
        float sx = zombie.input.AimingReticle.getXA(index), sy = zombie.input.AimingReticle.getYA(index);
        return LuaManager.GlobalObject.screenToIsoY(index, sx, sy, (float) z);
    }

    private static boolean isCharacterSitting(IsoGameCharacter c) {
        return (c instanceof zombie.characters.IsoZombie z && z.isSitAgainstWall())
                || c.isSitOnGround() || c.isSittingOnFurniture();
    }

    private static double[] playerAimDir(IsoPlayer p) {
        var raw = p.getAimVector(new zombie.iso.Vector2());
        double rawLen = raw == null ? 0 : Math.hypot(raw.x, raw.y);
        var dir = rawLen >= 0.001 ? raw : p.getForwardDirection();
        double fx = dir == null ? 1 : dir.x, fy = dir == null ? 0 : dir.y;
        double fl = Math.hypot(fx, fy);
        return fl < 0.001 ? new double[] { 1.0, 0.0 } : new double[] { fx / fl, fy / fl };
    }

    private static IsoGameCharacter findBallisticsCharacter(IsoPlayer p, int targetId, double tx, double ty) {
        IsoCell cell = p.getCell();
        if (cell == null) return null;
        var list = cell.getObjectList();
        if (list == null) return null;
        IsoGameCharacter nearest = null;
        double bestD2 = 1.25 * 1.25;
        for (IsoMovingObject obj : list) {
            if (!(obj instanceof IsoGameCharacter c) || c == p || c.isDead()) continue;
            if (targetId >= 0 && c.getID() == targetId) return c;
            double dx = c.getX() - tx, dy = c.getY() - ty;
            double d2 = dx * dx + dy * dy;
            if (d2 < bestD2) { bestD2 = d2; nearest = c; }
        }
        return nearest;
    }

    private static double[] drawnBoneCenter(IsoGameCharacter c, String boneName) {
        var anim = c.getAnimationPlayer();
        boolean nativeBody = c.getClass() == IsoPlayer.class
                || c.getClass() == zombie.characters.IsoZombie.class
                || c.getClass() == zombie.characters.IsoSurvivor.class;
        if (!nativeBody || anim == null || !anim.isReady()) return null;
        ShotZonesData.Zone match = null;
        for (ShotZonesData.Zone z : c.isFemale() ? ShotZonesData.F : ShotZonesData.M) {
            if (z.bone().equals(boneName)) { match = z; break; }
        }
        if (match == null) return null;
        int idx = anim.getSkinningBoneIndex(boneName, -1);
        if (idx < 0) return null;
        var m = ShotContactsPz.drawnBone(anim, idx);
        if (!Float.isFinite(m.m00) || !Float.isFinite(m.m03) || !Float.isFinite(m.m13) || !Float.isFinite(m.m23)) {
            m = anim.getBoneModelTransform(idx, new org.lwjgl.util.vector.Matrix4f());
        }
        if (!Float.isFinite(m.m00) || !Float.isFinite(m.m03) || !Float.isFinite(m.m13) || !Float.isFinite(m.m23)) return null;
        float lx = 0.5f * (match.a().x() + match.b().x());
        float ly = 0.5f * (match.a().y() + match.b().y());
        float lz = 0.5f * (match.a().z() + match.b().z());
        var v = new zombie.iso.Vector3(
                m.m00 * lx + m.m01 * ly + m.m02 * lz + m.m03,
                m.m10 * lx + m.m11 * ly + m.m12 * lz + m.m13,
                m.m20 * lx + m.m21 * ly + m.m22 * lz + m.m23);
        zombie.core.skinnedmodel.model.Model.vectorToWorldCoords(0, 0, 0, anim.getRenderedAngle(), v);
        if (!Float.isFinite(v.x) || !Float.isFinite(v.y) || !Float.isFinite(v.z)) return null;
        return new double[] { (double) c.getX() + v.x, (double) c.getY() + v.y, (double) c.getZ() + v.z };
    }

    private static boolean blockedByWorld(IsoPlayer p, double ox, double oy, double oz,
            double bx, double by, double bz) {
        double rdx = bx - ox, rdy = by - oy, rdz = (bz - oz) * verticalScale;
        double len = Math.sqrt(rdx * rdx + rdy * rdy + rdz * rdz);
        if (len < 0.25) return false;
        Ray ray = new Ray();
        ray.x = ox; ray.y = oy; ray.z = oz;
        ray.dx = rdx / len; ray.dy = rdy / len; ray.dz = rdz / (len * verticalScale);
        ray.range = len;
        double floorLevel = Math.floor(bz);
        boolean nearFloor = bz <= floorLevel + 0.06;
        double[] h = new double[5];
        for (Shape s : scene(ray, p, true, new Context(), new double[1])) {
            if (s.transmits || "body".equals(s.kind)) continue;
            if (nearFloor && "floor".equals(s.kind) && s.level == (int) floorLevel) continue;
            if (intersect(ray, s, h) && h[0] < len - 0.18) return true;
        }
        return false;
    }

    private static String preferredAimBone(IsoGameCharacter c, int ragdollOrdinal) {
        if (ragdollOrdinal == zombie.core.physics.RagdollBodyPart.BODYPART_HEAD.ordinal()) return "Bip01_Head";
        if (ragdollOrdinal == zombie.core.physics.RagdollBodyPart.BODYPART_PELVIS.ordinal()) return "Bip01_Pelvis";
        if (ragdollOrdinal == zombie.core.physics.RagdollBodyPart.BODYPART_SPINE.ordinal()) {
            return isCharacterSitting(c) || c.isOnFloor() ? "Bip01_Spine" : "Bip01_Spine1";
        }
        if (ragdollOrdinal == zombie.core.physics.RagdollBodyPart.BODYPART_LEFT_UPPER_LEG.ordinal()
                || ragdollOrdinal == zombie.core.physics.RagdollBodyPart.BODYPART_LEFT_LOWER_LEG.ordinal()) {
            return "Bip01_L_Thigh";
        }
        if (ragdollOrdinal == zombie.core.physics.RagdollBodyPart.BODYPART_RIGHT_UPPER_LEG.ordinal()
                || ragdollOrdinal == zombie.core.physics.RagdollBodyPart.BODYPART_RIGHT_LOWER_LEG.ordinal()) {
            return "Bip01_R_Thigh";
        }
        return null;
    }

    private static IsoAim snapCharacterBone(IsoPlayer p, IsoGameCharacter c, int ragdollOrdinal,
            double hintZ, boolean strictCursor) {
        int idx = p.getIndex();
        double ox = p.getX(), oy = p.getY();
        double oz = p.getAimOriginPosZ();
        if (!Double.isFinite(oz)) oz = p.getZ() + 0.48;
        String preferred = preferredAimBone(c, ragdollOrdinal);
        double[] bestPt = null;
        double bestScore = Double.POSITIVE_INFINITY, bestErr = Double.POSITIVE_INFINITY;
        for (String bone : AIM_BONES) {
            double[] pt = drawnBoneCenter(c, bone);
            if (pt == null || Math.hypot(pt[0] - c.getX(), pt[1] - c.getY()) > 1.35
                    || pt[2] < c.getZ() - 0.10 || pt[2] > c.getZ() + 0.90) continue;
            double cx = screenIsoX(idx, pt[2]), cy = screenIsoY(idx, pt[2]);
            if (!Double.isFinite(cx) || !Double.isFinite(cy)) continue;
            double err = Math.hypot(cx - pt[0], cy - pt[1]);
            double score = err - (bone.equals(preferred) && err <= 0.35 ? 0.04 : 0.0);
            if (score < bestScore) {
                bestScore = score;
                bestErr = err;
                bestPt = pt;
            }
        }
        if (bestPt != null) {
            if (strictCursor && bestErr > 0.28) return null;
            if (blockedByWorld(p, ox, oy, oz, bestPt[0], bestPt[1], bestPt[2])) return null;
            return new IsoAim(bestPt[0], bestPt[1], bestPt[2], "ballistics", c);
        }
        boolean sitting = isCharacterSitting(c);
        double[] relZs = sitting ? new double[] { 0.34, 0.22, 0.12 }
                : (c.isOnFloor() ? new double[] { 0.09 }
                : (c.isSneaking() ? new double[] { 0.42, 0.28 } : new double[] { 0.58, 0.42, 0.26 }));
        double cz = c.getZ(), bestZ = cz + relZs[0];
        double minErr = Double.POSITIVE_INFINITY;
        for (double relZ : relZs) {
            double z = cz + relZ;
            double cx = screenIsoX(idx, z), cy = screenIsoY(idx, z);
            if (!Double.isFinite(cx) || !Double.isFinite(cy)) continue;
            double err = Math.hypot(cx - c.getX(), cy - c.getY());
            if (err < minErr) { minErr = err; bestZ = z; }
        }
        if (!Double.isFinite(minErr)) {
            double relZ = Double.isFinite(hintZ) ? hintZ - cz : relZs[0];
            if (sitting) relZ = Math.clamp(relZ, 0.12, 0.36);
            else if (c.isOnFloor()) relZ = Math.clamp(relZ, 0.05, 0.12);
            else relZ = Math.clamp(relZ, 0.24, 0.60);
            bestZ = cz + relZ;
        } else if (strictCursor && minErr > 0.26) {
            return null;
        }
        if (blockedByWorld(p, ox, oy, oz, c.getX(), c.getY(), bestZ)) return null;
        return new IsoAim(c.getX(), c.getY(), bestZ, "ballistics", c);
    }

    /**
     * Bullet's getBallisticsCameraTargets raycasts against a ~0.45 m aim-assist cylinder around each
     * character, returning an outer-shell point shifted +0.45 m SE (+X, +Y) toward the isometric camera.
     * Snap the resolved target onto the targeted character's live drawn bone under the reticle so
     * both the isometric laser and ShotOwner's anatomical capsules hit the aimed body from any bearing.
     */
    private static IsoAim snapBallisticsTarget(IsoPlayer p, int targetId, int ragdollOrdinal,
            double tx, double ty, double tz, boolean validateCursor) {
        if (!Double.isFinite(tx) || !Double.isFinite(ty) || !Double.isFinite(tz)) return null;
        if (Math.abs(tx) < 0.001 && Math.abs(ty) < 0.001 && Math.abs(tz) < 0.001) return null;
        double pdx = tx - p.getX(), pdy = ty - p.getY();
        if (pdx * pdx + pdy * pdy > 10000.0) return null;
        if (validateCursor) {
            int idx = p.getIndex();
            double cx = screenIsoX(idx, tz), cy = screenIsoY(idx, tz);
            if (!Double.isFinite(cx) || !Double.isFinite(cy) || Math.hypot(cx - tx, cy - ty) > 0.90) return null;
        }
        IsoGameCharacter c = findBallisticsCharacter(p, targetId, tx, ty);
        if (c == null) return validateCursor ? null : new IsoAim(tx, ty, tz, "ballistics");
        return snapCharacterBone(p, c, ragdollOrdinal, tz, false);
    }

    private static IsoAim cachedBallisticsTarget(IsoPlayer p, zombie.core.physics.BallisticsController bc) {
        if (!bcFieldsChecked) {
            bcFieldsChecked = true;
            try {
                var c = zombie.core.physics.BallisticsController.class;
                var fCount = c.getDeclaredField("cachedNumberOfCameraTargets");
                var fArr = c.getDeclaredField("cachedBallisticsCameraTargets");
                fCount.setAccessible(true);
                fArr.setAccessible(true);
                bcCachedCountField = fCount;
                bcCachedTargetsField = fArr;
            } catch (Throwable ignored) { }
        }
        if (bcCachedCountField == null || bcCachedTargetsField == null) return null;
        try {
            int count = bcCachedCountField.getInt(bc);
            float[] arr = (float[]) bcCachedTargetsField.get(bc);
            if (count <= 0 || arr == null || arr.length < 5) return null;
            int targetId = Math.round(arr[0]);
            int ragdoll = Math.round(arr[4]);
            double tx = arr[1], ty = arr[3], tz = arr[2] / 2.44949;
            return snapBallisticsTarget(p, targetId, ragdoll, tx, ty, tz, true);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static IsoAim cursorCharacterTarget(IsoPlayer p) {
        IsoCell cell = p.getCell();
        if (cell == null) return null;
        var list = cell.getObjectList();
        if (list == null) return null;
        int idx = p.getIndex();
        double px = p.getX(), py = p.getY();
        double[] aimDir = playerAimDir(p);
        double fx = aimDir[0], fy = aimDir[1];
        IsoAim best = null;
        double bestDist2 = Double.POSITIVE_INFINITY;
        for (IsoMovingObject obj : list) {
            if (!(obj instanceof IsoGameCharacter c) || c == p || c.isDead()) continue;
            double dx = c.getX() - px, dy = c.getY() - py;
            double d2 = dx * dx + dy * dy;
            if (d2 > 3600.0 || d2 < 0.04 || d2 >= bestDist2) continue;
            if (dx * fx + dy * fy <= 0.05) continue;
            double zMid = c.getZ() + (c.isOnFloor() ? 0.08 : 0.35);
            double cmx = screenIsoX(idx, zMid), cmy = screenIsoY(idx, zMid);
            if (!Double.isFinite(cmx) || Math.hypot(cmx - c.getX(), cmy - c.getY()) > 2.0) continue;
            IsoAim snapped = snapCharacterBone(p, c, -1, zMid, true);
            if (snapped != null) {
                bestDist2 = d2;
                best = snapped;
            }
        }
        return best;
    }

    static IsoAim isoAimPoint(IsoPlayer p) {
        return isoAimPoint(p, false);
    }

    static IsoAim isoAimPoint(IsoPlayer p, boolean legacy) {
        if (p == null || !p.isLocalPlayer() || p.getJoypadBind() >= 0) return null;
        if (isFloorAim(p)) {
            return legacy ? null : cursorPoint(p);
        }
        if (p.isAiming()) {
            var bc = p.getBallisticsController();
            if (bc != null) {
                if (bc.hasBallisticsTarget() && bc.updateAimingVector(p, ISO_AIM_PARAMS)) {
                    double tx = ISO_AIM_PARAMS.targetPosition.x;
                    double ty = ISO_AIM_PARAMS.targetPosition.y;
                    double tz = ISO_AIM_PARAMS.targetPosition.z;
                    if (legacy) {
                        double dx = tx - p.getX(), dy = ty - p.getY();
                        if (Double.isFinite(tx) && Double.isFinite(ty) && Double.isFinite(tz) && dx * dx + dy * dy <= 10000.0) {
                            return new IsoAim(tx, ty, tz, "ballistics");
                        }
                    } else {
                        int targetId = -1, ragdoll = -1;
                        if (bc.getNumberOfCameraTargets() > 0) {
                            float[] cam = bc.getCameraTargets();
                            if (cam != null && cam.length >= 5) {
                                targetId = Math.round(cam[0]);
                                ragdoll = Math.round(cam[4]);
                            }
                        }
                        IsoAim snapped = snapBallisticsTarget(p, targetId, ragdoll, tx, ty, tz, false);
                        if (snapped != null) return snapped;
                    }
                }
                if (!legacy) {
                    IsoAim cached = cachedBallisticsTarget(p, bc);
                    if (cached != null) return cached;
                }
            }
            if (!legacy) {
                IsoAim direct = cursorCharacterTarget(p);
                if (direct != null) return direct;
            }
        }
        int index = p.getIndex();
        float aimZ = p.getAimOriginPosZ();
        double x = screenIsoX(index, aimZ), y = screenIsoY(index, aimZ);
        double dx = x - p.getX(), dy = y - p.getY();
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Float.isFinite(aimZ) || dx * dx + dy * dy > 10000.0) return null;
        return new IsoAim(x, y, aimZ, "reticle");
    }

    /** Isometric aim target for TW/LaserRay: {x, y, z, mode, target?} ("ballistics", "floor", or "reticle"). */
    public static KahluaTable isoAimTarget(IsoPlayer p) {
        return isoAimTarget(p, false);
    }

    public static KahluaTable isoAimTarget(IsoPlayer p, boolean legacy) {
        IsoAim aim = isoAimPoint(p, legacy);
        if (aim == null) return null;
        KahluaTable out = LuaManager.platform.newTable();
        out.rawset(1, (Object) Double.valueOf(aim.x));
        out.rawset(2, (Object) Double.valueOf(aim.y));
        out.rawset(3, (Object) Double.valueOf(aim.z));
        out.rawset(4, (Object) aim.mode);
        if (aim.target != null) out.rawset(5, aim.target);
        return out;
    }

    static double[] castOpaque(Ray ray, ArrayList<Shape> shapes, int viewPlayer, double viewFloor) {
        ArrayList<Hit> hits = new ArrayList<>();
        double[] h = new double[5];
        for (Shape s : shapes) {
            boolean vis = true;
            if (viewPlayer >= 0 && s.object != null) vis = s.object.getAlpha(viewPlayer) > .12;
            if (!Double.isNaN(viewFloor) && s.level > viewFloor) vis = false;
            if (vis && intersect(ray, s, h)) hits.add(new Hit(h, s));
        }
        Hit[] sorted = hits.toArray(new Hit[0]);
        java.util.Arrays.sort(sorted, ORDER);
        for (Hit x : sorted) {
            if (!x.shape.transmits) {
                return new double[] {
                    ray.x + ray.dx * x.distance,
                    ray.y + ray.dy * x.distance,
                    ray.z + ray.dz * x.distance,
                    x.distance
                };
            }
        }
        return null;
    }

    static IsoAim cursorPoint(IsoPlayer p) {
        if (p == null || !p.isLocalPlayer() || p.getJoypadBind() >= 0) return null;
        int index = p.getIndex();
        double z = Math.floor(p.getZ());
        double x = screenIsoX(index, z), y = screenIsoY(index, z);
        double dx = x - p.getX(), dy = y - p.getY();
        if (!Double.isFinite(x) || !Double.isFinite(y) || dx * dx + dy * dy > 10000.0) return null;
        Ray ray = new Ray();
        ray.x = x + 3.75; ray.y = y + 3.75; ray.z = z + 1.25;
        ray.dx = -3; ray.dy = -3; ray.dz = -1; ray.range = 1.25;
        double[] hit = castOpaque(ray, scene(ray, p, true, new Context(), new double[1]), index, z);
        return hit != null ? new IsoAim(hit[0], hit[1], hit[2], "floor") : new IsoAim(x, y, z, "floor");
    }

    static double snapFloorSlabZ(double floorZ) {
        return Math.abs(floorZ - Math.round(floorZ)) <= 0.005 ? (double) Math.round(floorZ) : floorZ;
    }

    /** 3D target for ShotOwner isometric shots: floor surface point, ballistics body hit, or reticle line hit. */
    static IsoAim isoAimForShot(IsoPlayer p, double mx, double my, double mz) {
        if (p == null) return null;
        if (isFloorAim(p)) {
            IsoAim floor = cursorPoint(p);
            if (floor != null && Math.hypot(floor.x - mx, floor.y - my) > 0.05) {
                return new IsoAim(floor.x, floor.y, snapFloorSlabZ(floor.z), "floor");
            }
        }
        IsoAim aim = isoAimPoint(p, false);
        if (aim != null && "ballistics".equals(aim.mode()) && Math.hypot(aim.x - mx, aim.y - my) > 0.05) {
            return aim;
        }
        var raw = p.getAimVector(new zombie.iso.Vector2());
        double rawLen = raw == null ? 0 : Math.hypot(raw.x, raw.y);
        var dir = rawLen >= 0.001 ? raw : p.getForwardDirection();
        double fx = dir == null ? 1 : dir.x, fy = dir == null ? 0 : dir.y;
        double fl = Math.hypot(fx, fy);
        if (fl < 0.001) { fx = 1; fy = 0; } else { fx /= fl; fy /= fl; }
        double aimZ = p.getAimOriginPosZ();
        double tx, ty, tz;
        if (aim != null && (aim.x - mx) * fx + (aim.y - my) * fy > 0.25) {
            tx = aim.x; ty = aim.y; tz = aim.z;
        } else {
            tx = mx + fx * 15.0; ty = my + fy * 15.0; tz = aimZ;
        }
        double rdx = tx - mx, rdy = ty - my, rdz = (tz - mz) * verticalScale;
        double len = Math.sqrt(rdx * rdx + rdy * rdy + rdz * rdz);
        if (len < 0.01) return new IsoAim(mx + fx * 15.0, my + fy * 15.0, aimZ, "reticle");
        Ray ray = new Ray();
        ray.x = mx; ray.y = my; ray.z = mz;
        ray.dx = rdx / len; ray.dy = rdy / len; ray.dz = rdz / (len * verticalScale);
        ray.range = 90.0;
        double[] hit = castOpaque(ray, scene(ray, p, true, new Context(), new double[1]), -1, Double.NaN);
        if (hit != null && hit[3] >= 0.20) {
            return new IsoAim(hit[0], hit[1], hit[2], "reticle");
        }
        double fallbackDist = Math.max(len, 15.0);
        return new IsoAim(mx + ray.dx * fallbackDist, my + ray.dy * fallbackDist, mz + ray.dz * fallbackDist, "reticle");
    }
}
