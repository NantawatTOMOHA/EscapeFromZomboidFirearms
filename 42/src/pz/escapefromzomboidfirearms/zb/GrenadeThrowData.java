package pz.escapefromzomboidfirearms.zb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Independent original-grenade clip resource. A malformed resource is rejected whole. */
public final class GrenadeThrowData {
    public static final String MAGIC = "TWHGTHRW";
    public final String digest;
    public final double[][][] wristAxes;
    public final Map<String, Item> items;

    public static final class Track {
        public final String action, clip;
        public final double seconds, release;
        public final double[] times;
        public final double[][][] poses;
        public final double[][] goals;

        Track(String action, String clip, double seconds, double release, double[] times, double[][][] poses, double[][] goals) {
            this.action = action; this.clip = clip; this.seconds = seconds; this.release = release;
            this.times = times; this.poses = poses; this.goals = goals;
        }

        public int frame(double time) {
            int index = java.util.Arrays.binarySearch(times, time);
            return index >= 0 ? index : Math.max(0, Math.min(times.length - 1, -index - 2));
        }

        public double[] pose(int bone, double time) {
            int i = frame(time), j = Math.min(i + 1, times.length - 1);
            double w = i == j ? 0 : Math.max(0, Math.min(1, (time - times[i]) / (times[j] - times[i])));
            return FpPose.interp(poses[i][bone], poses[j][bone], w);
        }

        public double[] goals(double time) {
            int i = frame(time), j = Math.min(i + 1, times.length - 1);
            double w = i == j ? 0 : Math.max(0, Math.min(1, (time - times[i]) / (times[j] - times[i])));
            double[] out = new double[6];
            for (int k = 0; k < 6; k++) out[k] = goals[i][k] + (goals[j][k] - goals[i][k]) * w;
            return out;
        }
    }

    public record Item(String tpl, double[] emitPoint, Map<String, Track> tracks) { }

    private GrenadeThrowData(String digest, double[][][] wristAxes, Map<String, Item> items) {
        this.digest = digest; this.wristAxes = wristAxes; this.items = Map.copyOf(items);
    }

    public static GrenadeThrowData load(Path path) throws IOException {
        long size = Files.size(path);
        if (size < 400 || size > 16_000_000) throw new IOException("grenade throws size");
        try {
            byte[] bytes = Files.readAllBytes(path);
            ByteBuffer in = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            byte[] magic = new byte[8]; in.get(magic);
            if (!MAGIC.equals(new String(magic, StandardCharsets.US_ASCII)) || in.getInt() != 1 || in.getInt() != 60)
                throw new IOException("grenade throws header");
            byte[] digest = new byte[32]; in.get(digest);
            byte[] checksum = new byte[32]; in.get(checksum);
            byte[] actual = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Arrays.copyOfRange(bytes, 80, bytes.length));
            if (!java.util.Arrays.equals(checksum, actual)) throw new IOException("grenade throws checksum");
            double[][][] axes = new double[2][2][9];
            for (double[][] body : axes) for (double[] hand : body) {
                for (int i = 0; i < 9; i++) hand[i] = finite(in.getDouble());
                rotation(hand, 3);
            }
            int count = bounded(in.getInt(), 1, 12);
            Map<String, Item> items = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String tpl = string(in);
                if (!tpl.matches("[0-9a-f]{24}")) throw new IOException("grenade throws tpl");
                double[] point = new double[3];
                for (int k = 0; k < 3; k++) {
                    point[k] = finite(in.getDouble());
                    if (Math.abs(point[k]) > 1) throw new IOException("grenade throws point");
                }
                int actions = bounded(in.getInt(), 5, 10);
                Map<String, Track> tracks = new LinkedHashMap<>();
                for (int a = 0; a < actions; a++) {
                    String action = string(in), clip = string(in);
                    if (!java.util.Set.of("equip", "idle", "pin", "weakPin", "hold", "weakHold", "strong", "weak", "cancel", "cancelOut").contains(action))
                        throw new IOException("grenade throws action");
                    double seconds = finite(in.getDouble()), release = finite(in.getDouble());
                    if (!(seconds > 0 && seconds <= 20) || !(release == -1 || release >= 0 && release < seconds))
                        throw new IOException("grenade throws time");
                    boolean thrown = action.equals("strong") || action.equals("weak");
                    if (thrown != (release >= 0)) throw new IOException("grenade throws release");
                    int n = bounded(in.getInt(), 2, 2048);
                    double[] times = new double[n];
                    double[][][] poses = new double[n][3][16];
                    double[][] goals = new double[n][6];
                    boolean eventSample = !thrown;
                    for (int f = 0; f < n; f++) {
                        times[f] = finite(in.getDouble());
                        if (times[f] < 0 || times[f] > seconds + 1e-9 || f > 0 && times[f] <= times[f - 1])
                            throw new IOException("grenade throws samples");
                        eventSample |= Math.abs(times[f] - release) < 1e-12;
                        for (double[] pose : poses[f]) {
                            for (int k = 0; k < 12; k++) pose[k] = finite(in.getFloat());
                            pose[15] = 1; rotation(pose, 4);
                            for (int k : new int[]{3, 7, 11}) if (Math.abs(pose[k]) > 10) throw new IOException("grenade throws bounds");
                        }
                        for (int k = 0; k < 6; k++) {
                            goals[f][k] = finite(in.getFloat());
                            if (Math.abs(goals[f][k]) > 10) throw new IOException("grenade throws goal");
                        }
                    }
                    if (times[0] != 0 || Math.abs(times[n - 1] - seconds) > 1e-9 || !eventSample)
                        throw new IOException("grenade throws endpoints/release sample");
                    if (tracks.put(action, new Track(action, clip, seconds, release, times, poses, goals)) != null)
                        throw new IOException("grenade throws duplicate track");
                }
                for (String required : new String[]{"equip", "idle", "strong", "weak", "cancel"})
                    if (!tracks.containsKey(required)) throw new IOException("grenade throws missing action");
                if (items.put(tpl, new Item(tpl, point, Map.copyOf(tracks))) != null) throw new IOException("grenade throws duplicate item");
            }
            if (in.hasRemaining()) throw new IOException("grenade throws trailing bytes");
            return new GrenadeThrowData(java.util.HexFormat.of().formatHex(digest), axes, items);
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException failure) {
            throw new IOException("grenade throws malformed", failure);
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IOException("grenade throws checksum unavailable", failure);
        }
    }

    private static int bounded(int n, int lo, int hi) throws IOException {
        if (n < lo || n > hi) throw new IOException("grenade throws count");
        return n;
    }

    private static double finite(double value) throws IOException {
        if (!Double.isFinite(value)) throw new IOException("grenade throws nonfinite");
        return value;
    }

    private static String string(ByteBuffer in) throws IOException {
        int n = bounded(Short.toUnsignedInt(in.getShort()), 1, 160);
        byte[] raw = new byte[n]; in.get(raw);
        String out = new String(raw, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(raw, out.getBytes(StandardCharsets.UTF_8))) throw new IOException("grenade throws utf8");
        return out;
    }

    private static void rotation(double[] m, int stride) throws IOException {
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double value = 0;
            for (int k = 0; k < 3; k++) value += m[k * stride + i] * m[k * stride + j];
            if (Math.abs(value - (i == j ? 1 : 0)) > 3e-4) throw new IOException("grenade throws rotation");
        }
        double determinant = m[0] * (m[stride + 1] * m[2 * stride + 2] - m[stride + 2] * m[2 * stride + 1])
                - m[1] * (m[stride] * m[2 * stride + 2] - m[stride + 2] * m[2 * stride])
                + m[2] * (m[stride] * m[2 * stride + 1] - m[stride + 1] * m[2 * stride]);
        if (Math.abs(determinant - 1) > 3e-4) throw new IOException("grenade throws handedness");
    }
}
