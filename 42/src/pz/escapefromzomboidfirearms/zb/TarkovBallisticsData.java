package pz.escapefromzomboidfirearms.zb;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Ammo;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Flight;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.G1;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;

/** Demand-loaded binary data; the future shot owner supplies a resolved mod path.
 * No PZ or Viewpoint classes are needed, and merely loading the JAR reads no files.
 * Format and provenance: tools/ballistics_runtime.py and the W5-B4 report.
 */
public final class TarkovBallisticsData {
    public static final String RESOURCE = "media/viewpoint/TW_Ballistics.bin";
    private static final byte[] MAGIC = {'T','W','B','A','L','L',0,0};
    private static final int MAX_BYTES = 1024*1024;

    public record Profile(String key, String ammoTpl, String ownFullType, String pzFullType) { }
    public record Weapon(String key, String tpl, String defaultAmmoTpl) { }
    public record Modifier(float velocityPercent, float accuracyPercent, boolean root,
                           boolean barrel, boolean hasShotgunDispersion, float shotgunDispersion) {
        public Modifier {
            TarkovBallistics.finite(velocityPercent, "velocity modifier");
            TarkovBallistics.finite(accuracyPercent, "accuracy modifier");
            TarkovBallistics.finite(shotgunDispersion, "shotgun dispersion");
            if (shotgunDispersion < 0 || (barrel && (!hasShotgunDispersion || root))) {
                throw new IllegalArgumentException("Invalid modifier flags or dispersion");
            }
        }
    }

    public final String gunplaySha256, inputsSha256;
    public final G1 g1;
    public final Vec3 gravity;
    public final double metresPerCell, metresPerLevel, modelUnitsPerMetre;
    public final Map<String, Ammo> ammo;
    public final Map<String, Profile> profiles;
    public final Map<String, Weapon> weapons;
    public final Map<String, Modifier> modifiers;

    private TarkovBallisticsData(DataInputStream in) throws IOException {
        if (!Arrays.equals(in.readNBytes(8), MAGIC) || in.readInt() != 1) throw new IOException("Ballistics header/version");
        gunplaySha256 = hex(in, 32); inputsSha256 = hex(in, 32);
        float step = in.readFloat(); int nodes = in.readInt();
        gravity = new Vec3(in.readFloat(), in.readFloat(), in.readFloat());
        metresPerCell = in.readDouble(); metresPerLevel = in.readDouble(); modelUnitsPerMetre = in.readDouble();
        if (step != TarkovBallistics.STEP_SECONDS || nodes != TarkovBallistics.CACHE_NODES
                || !gravity.equals(TarkovBallistics.CLIENT_GRAVITY)
                || metresPerCell != TarkovBallistics.METRES_PER_CELL || metresPerLevel != TarkovBallistics.METRES_PER_LEVEL
                || modelUnitsPerMetre != 0.578) throw new IOException("Ballistics metric/timing contract changed");
        int n = count(in, 79);
        float[] mach = new float[n], coefficients = new float[n];
        for (int i = 0; i < n; i++) { mach[i] = in.readFloat(); coefficients[i] = in.readFloat(); }
        g1 = new G1(mach, coefficients);
        Map<String, Ammo> am = new LinkedHashMap<>();
        n = count(in, 4096);
        for (int i = 0; i < n; i++) {
            String tpl = hex(in, 12);
            float speed = in.readFloat(), mass = in.readFloat(), diameter = in.readFloat(), bc = in.readFloat(), life = in.readFloat();
            int projectiles = in.readInt(), flags = in.readUnsignedByte();
            if (flags > 1) throw new IOException("Ammo flags");
            put(am, tpl, new Ammo(tpl, speed, mass, diameter, bc, life, projectiles, flags == 1));
        }
        Map<String, Profile> pr = new LinkedHashMap<>();
        n = count(in, 4096);
        for (int i = 0; i < n; i++) {
            Profile p = new Profile(string(in), hex(in, 12), string(in), string(in));
            if (!am.containsKey(p.ammoTpl)) throw new IOException("Profile references unknown ammo");
            put(pr, p.key, p);
        }
        Map<String, Weapon> we = new LinkedHashMap<>();
        n = count(in, 4096);
        for (int i = 0; i < n; i++) {
            Weapon w = new Weapon(string(in), hex(in, 12), hex(in, 12));
            if (!am.containsKey(w.defaultAmmoTpl)) throw new IOException("Weapon references unknown defAmmo");
            put(we, w.key, w);
        }
        Map<String, Modifier> mo = new LinkedHashMap<>();
        n = count(in, 10000);
        for (int i = 0; i < n; i++) {
            String tpl = hex(in, 12);
            float velocity = in.readFloat(), accuracy = in.readFloat();
            int flags = in.readUnsignedByte(); float dispersion = in.readFloat();
            if ((flags & ~7) != 0) throw new IOException("Modifier flags");
            put(mo, tpl, new Modifier(velocity, accuracy, (flags&1)!=0, (flags&2)!=0, (flags&4)!=0, dispersion));
        }
        for (Weapon w : we.values()) {
            Modifier m = mo.get(w.tpl);
            if (m == null || !m.root) throw new IOException("Weapon has no root modifier");
        }
        if (in.read() != -1) throw new IOException("Trailing ballistics bytes");
        ammo = Collections.unmodifiableMap(am); profiles = Collections.unmodifiableMap(pr);
        weapons = Collections.unmodifiableMap(we); modifiers = Collections.unmodifiableMap(mo);
    }

    public static TarkovBallisticsData load(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            return decode(in.readNBytes(MAX_BYTES+1));
        }
    }

    public static TarkovBallisticsData decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Ballistics resource too large");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return new TarkovBallisticsData(in);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid ballistics value", ex);
        }
    }

    /** Parts are an ordered per-instance snapshot and exclude the weapon root.
     * Repeated tpl values at different installed paths remain separate instances.
     */
    public float assemblySpeedFactor(String weaponKey, List<String> installedPartTpls) {
        Weapon weapon = require(weapons, weaponKey);
        float[] parts = new float[installedPartTpls.size()];
        for (int i = 0; i < parts.length; i++) {
            Modifier modifier = require(modifiers, installedPartTpls.get(i));
            if (modifier.root) throw new IllegalArgumentException("Root passed as an installed part");
            parts[i] = modifier.velocityPercent;
        }
        return TarkovBallistics.speedFactor(require(modifiers, weapon.tpl).velocityPercent, parts);
    }

    /** The first barrel supplies base dispersion; root is used only without one. */
    public float shotgunDispersion(String weaponKey, List<String> installedPartTpls) {
        Weapon weapon = require(weapons, weaponKey);
        Modifier root = require(modifiers, weapon.tpl);
        float base = root.shotgunDispersion, accuracy = 0;
        boolean barrelFound = false;
        for (String tpl : installedPartTpls) {
            Modifier part = require(modifiers, tpl);
            if (part.root) throw new IllegalArgumentException("Root passed as an installed part");
            accuracy += part.accuracyPercent;
            if (!barrelFound && part.barrel) { base = part.shotgunDispersion; barrelFound = true; }
        }
        float result = base * (1f-accuracy/100f);
        TarkovBallistics.finite(result, "assembled shotgun dispersion");
        if (result < 0) throw new IllegalArgumentException("Negative assembled shotgun dispersion");
        return result;
    }

    public Flight flight(String ammoTpl, float speedFactor, Vec3 origin, Vec3 direction) {
        return new Flight(require(ammo, ammoTpl), speedFactor, origin, direction, g1, gravity);
    }

    /** Deliberately has no argument for the currently loaded cartridge. */
    public Flight zeroingFlight(String weaponKey, List<String> installedPartTpls) {
        Weapon weapon = require(weapons, weaponKey);
        return flight(weapon.defaultAmmoTpl, assemblySpeedFactor(weaponKey, installedPartTpls), Vec3.ZERO, new Vec3(0,0,1));
    }

    private static <T> T require(Map<String, T> map, String key) {
        T value = map.get(key);
        if (value == null) throw new IllegalArgumentException("Unknown ballistics ID: " + key);
        return value;
    }
    private static <T> void put(Map<String, T> map, String key, T value) throws IOException {
        if (key.isEmpty() || map.putIfAbsent(key, value) != null) throw new IOException("Empty/duplicate ballistics ID");
    }
    private static int count(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 1 || n > max || n > in.available()) throw new IOException("Ballistics record count");
        return n;
    }
    private static String hex(DataInputStream in, int size) throws IOException {
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new IOException("Truncated ballistics ID/hash");
        return HexFormat.of().formatHex(bytes);
    }
    private static String string(DataInputStream in) throws IOException {
        int n = in.readUnsignedShort();
        if (n > 4096) throw new IOException("Ballistics text too long");
        byte[] bytes = in.readNBytes(n);
        if (bytes.length != n) throw new IOException("Truncated ballistics text");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
}
