package pz.escapefromzomboidfirearms.zb;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicitly loaded vanilla data. No game/VP dependency, startup hook or file search.
 * The future local owner resolves the active mod path and calls load itself.
 * Format, selected prefab and source hashes: tools/handling_runtime.py.
 */
public final class TarkovHandlingData {
    public static final String RESOURCE = "media/viewpoint/TW_Handling.bin";
    private static final byte[] MAGIC = {'T', 'W', 'H', 'A', 'N', 'D', 0, 0};
    private static final int MAX_BYTES = 1024 * 1024;

    public record Bipod(double recoil, double returnSpeed, double cameraSnap, double breath) {
        public Bipod {
            nonnegative(recoil); nonnegative(returnSpeed); nonnegative(cameraSnap); nonnegative(breath);
        }
    }

    public record Mount(boolean allowed, double verticalRecoil, double horizontalRecoil, double returnSpeed,
                        double cameraSnap, double verticalBreath, double horizontalBreath) {
        public Mount {
            nonnegative(verticalRecoil); nonnegative(horizontalRecoil); nonnegative(returnSpeed);
            nonnegative(cameraSnap); nonnegative(verticalBreath); nonnegative(horizontalBreath);
        }
    }

    public record Item(double massKg, double ergonomics, boolean root, boolean ammo, Mount mount, Bipod bipod) {
        public Item {
            nonnegative(massKg); finite(ergonomics);
            if ((root && (ammo || bipod != null)) || (root != (mount != null))
                    || (ammo && (ergonomics != 0 || bipod != null))) {
                throw new IllegalArgumentException("Conflicting handling item flags");
            }
        }
    }

    /** One installed instance or cartridge stack; paths are unique within a snapshot.
     * Parts have count 1, ammunition may have any positive count. Excludes the root.
     */
    public record Installed(String path, String tpl, int count) {
        public Installed {
            if (path == null || path.isBlank() || tpl == null || tpl.isBlank() || count <= 0) {
                throw new IllegalArgumentException("Invalid installed handling item");
            }
        }
    }

    public record Assembly(String weaponKey, double rawErgonomics, double massKg,
                           Map<String, Bipod> installedBipods) {
        public Assembly {
            if (weaponKey == null || weaponKey.isBlank()) throw new IllegalArgumentException("Missing weapon key");
            finite(rawErgonomics); nonnegative(massKg);
            installedBipods = Map.copyOf(installedBipods);
        }
    }

    /** The selected B2 curves are unweighted cubic Hermite, clamped at the ends.
     * Tangents are retained, including when adjacent key values happen to match.
     */
    public static final class Curve {
        private final double[][] keys;

        private Curve(DataInputStream in) throws IOException {
            int pre = in.readInt(), post = in.readInt(), rotation = in.readInt();
            if (pre != 2 || post != 2 || rotation < 0 || rotation > 4) throw new IOException("Handling curve mode");
            keys = new double[count(in, 128)][4];
            for (int i = 0; i < keys.length; i++) {
                for (int j = 0; j < 4; j++) keys[i][j] = number(in);
                if (i > 0 && keys[i][0] <= keys[i-1][0]) throw new IOException("Handling curve key order");
            }
        }

        public double at(double time) {
            finite(time);
            if (time <= keys[0][0]) return keys[0][1];
            int last = keys.length - 1;
            if (time >= keys[last][0]) return keys[last][1];
            int i = 1;
            while (time > keys[i][0]) i++;
            double[] a = keys[i-1], b = keys[i];
            double span = b[0] - a[0], u = (time - a[0]) / span;
            double u2 = u*u, u3 = u2*u;
            return (2*u3 - 3*u2 + 1)*a[1] + (u3 - 2*u2 + u)*span*a[3]
                    + (-2*u3 + 3*u2)*b[1] + (u3 - u2)*span*b[2];
        }
    }

    public final String gunplaySha256, curvesSha256, prefab;
    public final Map<String, Double> parameters;
    public final Map<String, Curve> curves;
    public final Map<String, Item> items;
    public final Map<String, String> weapons;

    private TarkovHandlingData(DataInputStream in) throws IOException {
        if (!Arrays.equals(in.readNBytes(8), MAGIC) || in.readInt() != 1) throw new IOException("Handling header/version");
        gunplaySha256 = hex(in, 32); curvesSha256 = hex(in, 32); prefab = text(in);
        Map<String, Double> pa = new LinkedHashMap<>();
        for (int i = count(in, 256); i > 0; i--) put(pa, text(in), number(in));
        parameters = Collections.unmodifiableMap(pa);
        Map<String, Curve> cu = new LinkedHashMap<>();
        for (int i = count(in, 32); i > 0; i--) put(cu, text(in), new Curve(in));
        curves = Collections.unmodifiableMap(cu);
        Map<String, Item> it = new LinkedHashMap<>();
        for (int i = count(in, 10000); i > 0; i--) {
            String tpl = hex(in, 12);
            int flags = in.readUnsignedByte();
            if ((flags & ~7) != 0) throw new IOException("Handling item flags");
            double mass = number(in), ergo = number(in);
            Mount mount = null;
            if ((flags & 1) != 0) {
                int allowed = in.readUnsignedByte();
                if (allowed > 1) throw new IOException("Handling mount flag");
                mount = new Mount(allowed == 1, number(in), number(in), number(in), number(in), number(in), number(in));
            }
            Bipod bipod = (flags & 4) == 0 ? null : new Bipod(number(in), number(in), number(in), number(in));
            put(it, tpl, new Item(mass, ergo, (flags & 1) != 0, (flags & 2) != 0, mount, bipod));
        }
        items = Collections.unmodifiableMap(it);
        Map<String, String> we = new LinkedHashMap<>();
        for (int i = count(in, 1000); i > 0; i--) {
            String key = text(in), tpl = hex(in, 12);
            if (!require(items, tpl).root) throw new IOException("Handling weapon without root");
            put(we, key, tpl);
        }
        weapons = Collections.unmodifiableMap(we);
        if (in.read() != -1) throw new IOException("Trailing handling bytes");
        validate();
    }

    private void validate() throws IOException {
        for (String key : List.of("Aiming.LightWeight", "Aiming.HeavyWeight", "Aiming.MinTimeLight",
                "Aiming.MinTimeHeavy", "Aiming.MaxTimeLight", "Aiming.MaxTimeHeavy", "Aiming.AimProceduralIntensity",
                "Stamina.Capacity", "Stamina.HandsCapacity", "Stamina.HandsRestoration", "Stamina.OxygenCapacity",
                "Stamina.OxygenRestoration", "Stamina.AimDrainRate", "Stamina.BaseHoldBreathConsumption",
                "Stamina.AimingSpeedMultiplier", "Timing.Step", "Breath.Intensity", "Breath.EnergyLowerLimit",
                "Breath.EnergyFractureLimit", "Pwa.AimSwayMaxThreshold")) {
            if (parameter(key) <= 0) throw new IOException("Nonpositive handling parameter: " + key);
        }
        // Constructor traversal demands every parameter the core will use, before admitting the resource.
        for (String group : List.of("Stamina.AimConsumptionByPose", "Stamina.RestorationMultiplierByPose",
                "Aiming.ProceduralIntensityByPose")) {
            for (String axis : List.of("x", "y", "z")) {
                if (parameter(group + "." + axis) <= 0) throw new IOException("Invalid pose factor");
            }
        }
        for (String group : List.of("StaminaDrain", "StaminaRestoration")) {
            double left = parameter(group + ".LeftPlatoPoint"), right = parameter(group + ".RightPlatoPoint");
            if (left <= 0 || right < left || parameter(group + ".RightLimit") <= right
                    || parameter(group + ".ZeroValue") <= 0 || parameter(group + ".LowerLeftPoint") <= 0
                    || parameter(group + ".LowerRightPoint") <= 0) throw new IOException("Invalid stamina piecewise function");
        }
        for (String key : List.of("Stamina.MountingVerticalAimDrainRateMultiplier",
                "Stamina.MountingHorizontalAimDrainRateMultiplier", "Stamina.BipodAimDrainRateMultiplier",
                "Breath.MountingVerticalOutOfBreathMultiplier", "Breath.MountingHorizontalOutOfBreathMultiplier",
                "Breath.BipodOutOfBreathMultiplier", "Recoil.MountVerticalRecoilMultiplier",
                "Recoil.MountHorizontalRecoilMultiplier", "Recoil.BipodRecoilMultiplier",
                "Recoil.MountingReturnHandSpeedMultiplier", "Recoil.BipodReturnHandSpeedMultiplier",
                "Pwa.MountingCameraSmoothRecoilMultiplier", "Pwa.BipodCameraSmoothRecoilMultiplier",
                "Motion.BipodModifier", "Mount.MountingSwayFactorModifier", "Pwa.AimSwayStartsThreshold",
                "Stamina.HoldBreathStaminaMultiplier.x", "Stamina.HoldBreathStaminaMultiplier.y",
                "Stamina.HoldBreathStaminaMultiplier.z")) {
            if (parameter(key) < 0) throw new IOException("Negative handling factor");
        }
        for (String key : List.of("Stamina.StaminaExhaustionCausesJiggle", "Stamina.StaminaExhaustionRocksCamera")) {
            double v = parameter(key);
            if (v != 0 && v != 1) throw new IOException("Invalid handling boolean");
        }
        if (parameter("Aiming.HeavyWeight") <= parameter("Aiming.LightWeight")
                || parameter("Pwa.AimSwayMaxThreshold") <= parameter("Pwa.AimSwayStartsThreshold")
                || parameter("Timing.Step") > 0.05
                || parameter("Breath.EnergyLowerLimit") >= parameter("Breath.EnergyFractureLimit")
                || parameter("Breath.EnergyFractureLimit") > 1) throw new IOException("Invalid handling range");
        for (String key : List.of("Breath.AmplitudeCurve", "Breath.Delay", "Breath.Hardness", "Breath.Oxygen",
                "Hard.SWAY_STRENGTH_PER_KG", "Hard.DISPLACEMENT_STRENGTH_PER_KG")) require(curves, key);
    }

    public double parameter(String key) { return require(parameters, key); }
    public Curve curve(String key) { return require(curves, key); }

    /** Always rebuild from the immutable root and the current complete snapshot.
     * Installed bipods grant no benefit until an explicit Support names their path.
     * Cartridge mass is from the original template, not the PZ inventory-weight scale.
     */
    public Assembly assemble(String weaponKey, List<Installed> installed) {
        Item root = require(items, require(weapons, weaponKey));
        double mass = root.massKg, delta = 0;
        HashSet<String> paths = new HashSet<>();
        Map<String, Bipod> bipods = new LinkedHashMap<>();
        for (Installed entry : List.copyOf(installed)) {
            if (!paths.add(entry.path)) throw new IllegalArgumentException("Duplicate installed path: " + entry.path);
            Item part = require(items, entry.tpl);
            if (part.root || (!part.ammo && entry.count != 1)) throw new IllegalArgumentException("Invalid part/count");
            mass += part.massKg * entry.count;
            delta += part.ergonomics;
            if (part.bipod != null) bipods.put(entry.path, part.bipod);
        }
        double ergonomics = root.ergonomics * (1 + delta / Math.max(1, root.ergonomics));
        return new Assembly(weaponKey, ergonomics, mass, bipods);
    }

    /** Vanilla U+E7EA GetAt; zero is deliberately discontinuous from positive input. */
    public double staminaFunction(String group, double value) {
        finite(value);
        double x = Math.max(0, Math.min(parameter(group + ".RightLimit"), value));
        if (x == 0) return parameter(group + ".ZeroValue");
        double left = parameter(group + ".LeftPlatoPoint"), right = parameter(group + ".RightPlatoPoint");
        if (x <= left) {
            double low = parameter(group + ".LowerLeftPoint");
            return low + (1 - low) * x / left;
        }
        if (x <= right) return 1;
        return 1 + (1 - parameter(group + ".LowerRightPoint")) * (x - right)
                / (right - parameter(group + ".RightLimit"));
    }

    public static TarkovHandlingData load(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) { return decode(in.readNBytes(MAX_BYTES + 1)); }
    }

    public static TarkovHandlingData decode(byte[] bytes) throws IOException {
        if (bytes.length < 108 || bytes.length > MAX_BYTES) throw new IOException("Handling resource size");
        int payload = bytes.length - 32;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes, 0, payload);
            if (!MessageDigest.isEqual(digest.digest(), Arrays.copyOfRange(bytes, payload, bytes.length))) {
                throw new IOException("Handling resource checksum");
            }
        } catch (NoSuchAlgorithmException ex) { throw new IOException("Missing SHA-256", ex); }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 0, payload))) {
            return new TarkovHandlingData(in);
        } catch (IllegalArgumentException ex) { throw new IOException("Invalid handling value/reference", ex); }
    }

    private static double number(DataInputStream in) throws IOException {
        double value = in.readDouble(); finite(value); return value;
    }
    private static int count(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n <= 0 || n > max || n > in.available()) throw new IOException("Handling record count");
        return n;
    }
    private static String hex(DataInputStream in, int count) throws IOException {
        byte[] bytes = in.readNBytes(count);
        if (bytes.length != count) throw new IOException("Truncated handling ID/hash");
        return HexFormat.of().formatHex(bytes);
    }
    private static String text(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        if (n == 0 || n > 4096) throw new IOException("Handling identifier length");
        byte[] bytes = in.readNBytes(n);
        if (bytes.length != n) throw new IOException("Truncated handling identifier");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    private static <T> void put(Map<String, T> map, String key, T value) throws IOException {
        if (key.isBlank() || map.putIfAbsent(key, value) != null) throw new IOException("Duplicate/empty handling ID");
    }
    private static <T> T require(Map<String, T> map, String key) {
        T value = map.get(key);
        if (value == null) throw new IllegalArgumentException("Unknown handling ID: " + key);
        return value;
    }
    static void finite(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Nonfinite handling value");
    }
    static void nonnegative(double value) {
        finite(value);
        if (value < 0) throw new IllegalArgumentException("Negative handling value");
    }
}
