package pz.escapefromzomboidfirearms.zb;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
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

import pz.escapefromzomboidfirearms.zb.TarkovPenetration.Ammo;
import pz.escapefromzomboidfirearms.zb.TarkovPenetration.BleedSpec;
import pz.escapefromzomboidfirearms.zb.TarkovPenetration.Material;

/** B5 data loads only at an explicitly resolved mod path. No static initializer
 * reads files, registers a Lua API, or changes the existing shot/damage owner.
 * tools/penetration_runtime.py documents the bounded big-endian format.
 */
public final class TarkovPenetrationData {
    public static final String RESOURCE = "media/viewpoint/TW_Penetration.bin";
    private static final byte[] MAGIC = {'T','W','P','E','N',0,0,0};
    private static final int MAX_BYTES = 1024*1024;
    public record Profile(String key, String tpl, String ownFullType, String pzFullType) { }
    public record BodySettings(float penetrationLevel, float penetrationChance, float damageMod) {
        public BodySettings {
            TarkovPenetration.nonnegative(penetrationLevel, "body penetration level");
            TarkovPenetration.probability(penetrationChance);
            TarkovPenetration.nonnegative(damageMod, "body damage modifier");
        }
    }

    public final String gunplaySha256, inputsSha256;
    public final float damageDegradationCoefficient, softArmorBluntMultiplier;
    public final BleedSpec lightBleeding, heavyBleeding;
    public final Map<String, Ammo> ammo;
    public final Map<String, Profile> profiles;
    public final Map<String, Float> armorMaterials;
    public final List<Material> materials;
    public final Map<String, Material> materialsByName;
    public final Map<String, BodySettings> bodies;
    private final float[] resistances, penetrationBinLimits;

    private TarkovPenetrationData(DataInputStream in) throws IOException {
        if (!Arrays.equals(in.readNBytes(8), MAGIC) || in.readInt() != 1)
            throw new IOException("Penetration header/version");
        gunplaySha256 = hex(in, 32); inputsSha256 = hex(in, 32);
        damageDegradationCoefficient = in.readFloat(); softArmorBluntMultiplier = in.readFloat();
        TarkovPenetration.probability(damageDegradationCoefficient);
        TarkovPenetration.probability(softArmorBluntMultiplier);
        int n = count(in, 11);
        if (n != 11) throw new IOException("Armor class count");
        resistances = new float[n];
        for (int i = 0; i < n; i++) {
            resistances[i] = in.readFloat(); TarkovPenetration.positive(resistances[i], "class resistance");
            if (i > 0 && resistances[i] <= resistances[i-1]) throw new IOException("Armor resistance order");
        }
        n = count(in, 64);
        Map<String, Float> arm = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String name = string(in); float value = in.readFloat();
            TarkovPenetration.positive(value, "armor destructibility"); put(arm, name, value);
        }
        lightBleeding = new BleedSpec(in.readFloat(), in.readFloat(), in.readFloat());
        heavyBleeding = new BleedSpec(in.readFloat(), in.readFloat(), in.readFloat());
        penetrationBinLimits = new float[6];
        for (int i = 0; i < 6; i++) {
            float value = penetrationBinLimits[i] = in.readFloat();
            TarkovPenetration.probability(value);
            if ((i > 0 && value <= penetrationBinLimits[i-1]) || (i == 5 && value != 1))
                throw new IOException("Penetration random bins");
        }
        n = count(in, 4096);
        Map<String, Ammo> am = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String tpl = hex(in, 12);
            float[] f = new float[13];
            for (int j = 0; j < f.length; j++) f[j] = in.readFloat();
            int min = in.readUnsignedShort(), max = in.readUnsignedShort(), flags = in.readUnsignedByte();
            if ((flags & ~3) != 0) throw new IOException("Penetration ammo flags");
            put(am, tpl, new Ammo(tpl, f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8],
                    f[9], f[10], f[11], f[12], min, max, (flags&1)!=0, (flags&2)!=0));
        }
        n = count(in, 4096);
        Map<String, Profile> pr = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Profile p = new Profile(string(in), hex(in, 12), string(in), string(in));
            if (!am.containsKey(p.tpl)) throw new IOException("Unknown profile ammo");
            put(pr, p.key, p);
        }
        n = count(in, 1024);
        Map<String, Material> ma = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            String name = string(in); int index = in.readInt(), type = in.readInt();
            if (index != i) throw new IOException("Preset index/order");
            Material m = new Material(name, index, type, in.readFloat(), in.readFloat(), in.readFloat(),
                    in.readFloat(), in.readFloat(), in.readFloat(), false, 0);
            put(ma, name, m);
        }
        n = count(in, 1024);
        Map<String, BodySettings> bo = new LinkedHashMap<>();
        for (int i = 0; i < n; i++)
            put(bo, string(in), new BodySettings(in.readFloat(), in.readFloat(), in.readFloat()));
        if (in.read() != -1) throw new IOException("Trailing penetration resource bytes");
        ammo = Collections.unmodifiableMap(am); profiles = Collections.unmodifiableMap(pr);
        armorMaterials = Collections.unmodifiableMap(arm); materials = List.copyOf(ma.values());
        materialsByName = Collections.unmodifiableMap(ma); bodies = Collections.unmodifiableMap(bo);
    }

    public static TarkovPenetrationData load(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) { return decode(in.readNBytes(MAX_BYTES+1)); }
    }
    public static TarkovPenetrationData decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Penetration resource too large");
        try (var in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            return new TarkovPenetrationData(in);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid penetration resource value", ex);
        }
    }
    public float classResistance(int armorClass) {
        if (armorClass < 0 || armorClass >= resistances.length) throw new IllegalArgumentException("Unknown armor class");
        return resistances[armorClass];
    }
    public float armorDestructibility(String name) { return require(armorMaterials, name); }
    public float penetrationBinLimit(int index) { return penetrationBinLimits[index]; }
    public Material material(String name) { return require(materialsByName, name); }
    public Ammo ammo(String tpl) { return require(ammo, tpl); }
    public Material body(String zone) {
        BodySettings b = require(bodies, zone);
        return material("Body").body(zone, b.penetrationLevel, b.penetrationChance, b.damageMod);
    }

    private static <T> T require(Map<String, T> map, String key) {
        T result = map.get(key);
        if (result == null) throw new IllegalArgumentException("Unknown penetration ID: "+key);
        return result;
    }
    private static <T> void put(Map<String, T> map, String key, T value) throws IOException {
        if (key.isEmpty() || map.putIfAbsent(key, value) != null) throw new IOException("Empty/duplicate penetration ID");
    }
    private static int count(DataInputStream in, int max) throws IOException {
        int result = in.readInt();
        if (result < 1 || result > max || result > in.available()) throw new IOException("Penetration record count");
        return result;
    }
    private static String hex(DataInputStream in, int size) throws IOException {
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new IOException("Truncated penetration ID/hash");
        return HexFormat.of().formatHex(bytes);
    }
    private static String string(DataInputStream in) throws IOException {
        int size = in.readUnsignedShort();
        if (size > 4096) throw new IOException("Penetration string too long");
        byte[] bytes = in.readNBytes(size);
        if (bytes.length != size) throw new IOException("Truncated penetration text");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
    }
}
