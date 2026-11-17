package pz.escapefromzomboidfirearms.zb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zombie.gameStates.ChooseGameInfo;

/**
 * Demand-loaded Tarkov first-person poses of the pose layer (FpPose), generated
 * by tools/fp_pose_runtime.py (format in its docstring). Every pose is the
 * weapon's Bip01_Prop1 in the eye frame (+X left, +Y up, -Z forward, model
 * units), 3x4 rows; read once, never written. A malformed resource is refused
 * as a whole (one log line) and the layer stays off.
 */
public final class FpPoseData {
    static final String RESOURCE = "media/viewpoint/TW_FpPoses.bin";
    static final byte[] MAGIC = {'T', 'W', 'F', 'P', 'P', 'O', 'S', 'E'};
    static final int VERSION = 6;
    static final int F_ROLL = 1, F_HOST = 2, F_IDLE = 4;
    static final int T_RELATIVE = 1, T_LOOP = 2, T_GOALS = 4, T_HOST = 8, T_HANDS = 16, T_MANUAL = 32;
    private static final int MAX_BYTES = 16 * 1024 * 1024, MAX_COUNT = 65536;
    private static FpPoseData cached;
    private static boolean attempted;
    static volatile String status = "not loaded";

    /** Tarkov globals.Aiming: LightWeight, HeavyWeight, MinTimeLight, MinTimeHeavy, MaxTimeLight, MaxTimeHeavy. */
    final float[] aiming;
    final int fps;
    final Map<String, Family> families;
    final Map<String, Family> byClass, byWeapon;
    final List<Track> tracks;
    final Map<String, Track> aliases;
    final Map<String, LauncherCamera> launcherCameras;

    /**
     * Original Tarkov aim point, in the underbarrel part's exported mount datum, and (VERSION 6) the support wrist that
     * holds the launcher there: Tarkov's launcher IDLE WEAPON palm with the PZ wrist the host family puts on its own
     * Tarkov palm, male/female (tools/fp_pose_runtime.py launcher_support).
     */
    static final class LauncherCamera {
        final String node;
        final double[] camera;
        final double[][] support;

        LauncherCamera(String node, double[] camera, double[][] support) {
            this.node = node;
            this.camera = camera;
            this.support = support;
        }
    }

    /** A reference weapon of a grip class (or a launcher): its idle in the eye frame and its elbow goals. */
    static final class Family {
        final String name, weapon;
        final int flags;
        final double[] idle, goals, hands, readyHands;
        /** The PZ hand frames on Tarkov's hand landmarks (VP-W12-H4): male/female, left/right. */
        final double[][][] wrists;
        /** The source contact (marker or FK palm) of each wrist frame: male/female, left/right, xyz. */
        final double[][][] contacts;
        /** Finger0 / Finger1 rotations relative to the hand (4x4, no translation): male/female, left/right, finger. */
        final double[][][][] fingers;
        Track patrol, patrolIn, patrolOut, ready;

        Family(String name, String weapon, int flags, double[] idle, double[] goals, double[] hands, double[] readyHands, double[][][] wrists,
               double[][][] contacts, double[][][][] fingers) {
            this.name = name;
            this.weapon = weapon;
            this.flags = flags;
            this.idle = idle;
            this.goals = goals;
            this.hands = hands;
            this.readyHands = readyHands;
            this.wrists = wrists;
            this.contacts = contacts;
            this.fingers = fingers;
        }

        boolean hasIdle() {
            return (flags & F_IDLE) != 0;
        }
    }

    /** Frames over the fraction u of a clip (0..1): a pose (3x4) and, with T_GOALS, two elbow goals each. */
    static final class Track {
        final String name;
        final int flags, frames, stride;
        final float seconds;
        final float[] data;

        Track(String name, int flags, float seconds, int frames, float[] data) {
            this.name = name;
            this.flags = flags;
            this.seconds = seconds;
            this.frames = frames;
            this.stride = 12 + ((flags & T_GOALS) != 0 ? 6 : 0) + ((flags & T_HANDS) != 0 ? 24 : 0);
            this.data = data;
        }

        boolean relative() {
            return (flags & T_RELATIVE) != 0;
        }

        boolean hasGoals() {
            return (flags & T_GOALS) != 0;
        }

        /** Frame position of u (a loop wraps, otherwise clamped): {index, next index, weight}. */
        double[] at(double u) {
            double x;
            if ((flags & T_LOOP) != 0) {
                x = (u - Math.floor(u)) * (frames - 1);
            } else {
                x = Math.max(0, Math.min(1, u)) * (frames - 1);
            }
            int i = Math.min((int) Math.floor(x), frames - 1);
            int j = Math.min(i + 1, frames - 1);
            return new double[] {i, j, x - i};
        }

        /** The pose at u as a true 4x4 (row-major, column vectors): position lerp, rotation slerp. */
        double[] pose(double u) {
            double[] k = at(u);
            double[] a = matrix((int) k[0]), b = matrix((int) k[1]);
            return FpPose.interp(a, b, k[2]);
        }

        /** Elbow goals at u (left xyz, right xyz, eye frame), or null. */
        double[] goals(double u) {
            if (!hasGoals()) {
                return null;
            }
            double[] k = at(u);
            int a = (int) k[0] * stride + 12, b = (int) k[1] * stride + 12;
            double[] g = new double[6];
            for (int i = 0; i < 6; i++) {
                g[i] = data[a + i] + (data[b + i] - data[a + i]) * k[2];
            }
            return g;
        }

        double[] matrix(int frame) {
            int o = frame * stride;
            double[] m = new double[16];
            for (int i = 0; i < 12; i++) {
                m[i] = data[o + i];
            }
            m[15] = 1;
            return m;
        }

        /** Marker/FK contact motion relative to the family's idle, in source Prop1 axes. */
        double[][] hands(double u) {
            if ((flags & T_HANDS) == 0) return null;
            double[] k = at(u);
            double[][] out = new double[2][];
            for (int side = 0; side < 2; side++) {
                double[] a = new double[16], b = new double[16];
                int o = 12 + (hasGoals() ? 6 : 0) + side * 12;
                for (int i = 0; i < 12; i++) {
                    a[i] = data[(int) k[0] * stride + o + i];
                    b[i] = data[(int) k[1] * stride + o + i];
                }
                a[15] = b[15] = 1;
                out[side] = FpPose.interp(a, b, k[2]);
            }
            return out;
        }
    }

    private FpPoseData(float[] aiming, int fps, Map<String, Family> families, Map<String, Family> byClass, Map<String, Family> byWeapon,
                       List<Track> tracks, Map<String, Track> aliases, Map<String, LauncherCamera> launcherCameras) {
        this.aiming = aiming;
        this.fps = fps;
        this.families = Collections.unmodifiableMap(families);
        this.byClass = Collections.unmodifiableMap(byClass);
        this.byWeapon = Collections.unmodifiableMap(byWeapon);
        this.tracks = Collections.unmodifiableList(tracks);
        this.aliases = Collections.unmodifiableMap(aliases);
        this.launcherCameras = Collections.unmodifiableMap(launcherCameras);
    }

    /** First call only: the resource of the active mod through PZ (as ReticleMeshData), then cached. */
    public static synchronized FpPoseData get() {
        if (!attempted) {
            attempted = true;
            try {
                ChooseGameInfo.Mod mod = ChooseGameInfo.getModDetails("EscapeFromZomboidFirearms");
                if (mod == null) {
                    throw new IOException("mod information unavailable");
                }
                for (String root : new String[] {mod.getVersionDir(), mod.getCommonDir(), mod.getDir()}) {
                    if (root == null || root.isBlank()) {
                        continue;
                    }
                    Path path = Path.of(root).resolve(RESOURCE);
                    if (Files.isRegularFile(path)) {
                        cached = load(path);
                        return cached;
                    }
                }
                throw new IOException("resource missing from the mod directories");
            } catch (Exception | LinkageError e) {
                fail(e);
            }
        }
        return cached;
    }

    /** Explicit path (offline checks; no PZ state). Null when malformed. */
    public static FpPoseData load(Path path) {
        try {
            long size = Files.size(path);
            if (size < 32 || size > MAX_BYTES) {
                throw new IOException("size " + size);
            }
            FpPoseData d = parse(ByteBuffer.wrap(Files.readAllBytes(path)).order(ByteOrder.LITTLE_ENDIAN));
            status = "loaded " + d.families.size() + " families, " + d.tracks.size() + " tracks, " + d.aliases.size() + " clips";
            return d;
        } catch (Exception | LinkageError e) {
            fail(e);
            return null;
        }
    }

    private static void fail(Throwable e) {
        status = "refused: " + e;
        System.out.println("[TWZB] first-person poses " + RESOURCE + " " + status + "; first-person pose layer off");
    }

    static FpPoseData parse(ByteBuffer b) throws IOException {
        byte[] magic = new byte[8];
        b.get(magic);
        if (!Arrays.equals(magic, MAGIC)) {
            throw new IOException("magic");
        }
        if (b.getInt() != VERSION) {
            throw new IOException("version");
        }
        int fps = b.getInt();
        float[] aiming = new float[6];
        for (int i = 0; i < 6; i++) {
            aiming[i] = finite(b.getFloat());
        }
        int nf = count(b);
        List<Family> fams = new ArrayList<>();
        List<int[]> links = new ArrayList<>();
        for (int k = 0; k < nf; k++) {
            String name = text(b), weapon = text(b);
            int flags = b.getInt();
            double[] idle = new double[16];
            for (int i = 0; i < 12; i++) {
                idle[i] = finite(b.getFloat());
            }
            idle[15] = 1;
            double[] goals = new double[6];
            for (int i = 0; i < 6; i++) {
                goals[i] = finite(b.getFloat());
            }
            double[] hands = new double[6], readyHands = new double[6];
            for (int i = 0; i < 6; i++) {
                hands[i] = finite(b.getFloat());
            }
            for (int i = 0; i < 6; i++) {
                readyHands[i] = finite(b.getFloat());
            }
            double[][][] wrists = new double[2][2][16];
            for (double[][] body : wrists) {
                for (double[] wrist : body) {
                    for (int i = 0; i < 12; i++) {
                        wrist[i] = finite(b.getFloat());
                    }
                    wrist[15] = 1;
                    rigid(wrist, "wrist " + name);
                }
            }
            double[][][] contacts = new double[2][2][3];
            for (double[][] body : contacts) {
                for (double[] contact : body) {
                    for (int i = 0; i < 3; i++) {
                        contact[i] = finite(b.getFloat());
                    }
                }
            }
            double[][][][] fingers = new double[2][2][2][16];
            for (double[][][] body : fingers) {
                for (double[][] hand : body) {
                    for (double[] finger : hand) {
                        for (int i = 0; i < 9; i++) {
                            finger[i / 3 * 4 + i % 3] = finite(b.getFloat());
                        }
                        finger[15] = 1;
                        rigid(finger, "finger " + name);
                    }
                }
            }
            links.add(new int[] {b.getInt(), b.getInt(), b.getInt(), b.getInt()});
            fams.add(new Family(name, weapon, flags, idle, goals, hands, readyHands, wrists, contacts, fingers));
        }
        int nc = count(b);
        Map<String, String> classes = new HashMap<>();
        for (int k = 0; k < nc; k++) {
            String cls = text(b);
            classes.put(cls, text(b));
        }
        int nt = count(b);
        List<Track> tracks = new ArrayList<>();
        for (int k = 0; k < nt; k++) {
            String name = text(b);
            int flags = b.getInt();
            float seconds = finite(b.getFloat());
            int frames = count(b);
            if (frames < 1) {
                throw new IOException("track " + name + " without frames");
            }
            int stride = 12 + ((flags & T_GOALS) != 0 ? 6 : 0) + ((flags & T_HANDS) != 0 ? 24 : 0);
            float[] data = new float[frames * stride];
            for (int i = 0; i < data.length; i++) {
                data[i] = finite(b.getFloat());
            }
            if ((flags & T_HANDS) != 0) {
                for (int frame = 0; frame < frames; frame++) for (int side = 0; side < 2; side++) {
                    double[] hand = new double[16];
                    int offset = frame * stride + 12 + ((flags & T_GOALS) != 0 ? 6 : 0) + side * 12;
                    for (int i = 0; i < 12; i++) hand[i] = data[offset + i];
                    rigid(hand, "hand motion " + name);
                }
            }
            tracks.add(new Track(name, flags, seconds, frames, data));
        }
        int na = count(b);
        Map<String, Track> aliases = new HashMap<>();
        for (int k = 0; k < na; k++) {
            String clip = text(b);
            int t = b.getInt();
            if (t < 0 || t >= tracks.size()) {
                throw new IOException("alias " + clip + " to track " + t);
            }
            aliases.put(clip, tracks.get(t));
        }
        int nl = count(b);
        Map<String, LauncherCamera> cameras = new HashMap<>();
        for (int k = 0; k < nl; k++) {
            String tpl = text(b), node = text(b);
            double[] camera = new double[16];
            for (int i = 0; i < 12; i++) {
                camera[i] = finite(b.getFloat());
            }
            camera[15] = 1;
            double error = 0;
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    double dot = camera[i] * camera[j] + camera[4 + i] * camera[4 + j] + camera[8 + i] * camera[8 + j];
                    error = Math.max(error, Math.abs(dot - (i == j ? 1 : 0)));
                }
            }
            double det = camera[0] * (camera[5] * camera[10] - camera[6] * camera[9])
                    - camera[1] * (camera[4] * camera[10] - camera[6] * camera[8])
                    + camera[2] * (camera[4] * camera[9] - camera[5] * camera[8]);
            double[][] support = new double[2][16];
            for (double[] wrist : support) {
                for (int i = 0; i < 12; i++) {
                    wrist[i] = finite(b.getFloat());
                }
                wrist[15] = 1;
                rigid(wrist, "launcher support " + tpl);
            }
            if (error > 1e-4 || Math.abs(det - 1) > 1e-4 || cameras.put(tpl, new LauncherCamera(node, camera, support)) != null) {
                throw new IOException("invalid/duplicate launcher camera " + tpl);
            }
        }
        if (b.hasRemaining()) {
            throw new IOException(b.remaining() + " trailing bytes");
        }
        Map<String, Family> families = new HashMap<>(), byWeapon = new HashMap<>(), byClass = new HashMap<>();
        for (int k = 0; k < fams.size(); k++) {
            Family f = fams.get(k);
            int[] l = links.get(k);
            f.patrol = track(tracks, l[0]);
            f.patrolIn = track(tracks, l[1]);
            f.patrolOut = track(tracks, l[2]);
            f.ready = track(tracks, l[3]);
            families.put(f.name, f);
            if ((f.flags & F_HOST) == 0) {
                byWeapon.put(f.weapon, f);
            }
        }
        for (Map.Entry<String, String> e : classes.entrySet()) {
            Family f = families.get(e.getValue());
            if (f == null || !f.hasIdle()) {
                throw new IOException("class " + e.getKey() + " to family " + e.getValue());
            }
            byClass.put(e.getKey(), f);
        }
        return new FpPoseData(aiming, fps, families, byClass, byWeapon, tracks, aliases, cameras);
    }

    private static void rigid(double[] m, String name) throws IOException {
        for (int i = 0; i < 3; i++) for (int j = 0; j < 3; j++) {
            double dot = m[i] * m[j] + m[i + 4] * m[j + 4] + m[i + 8] * m[j + 8];
            if (Math.abs(dot - (i == j ? 1 : 0)) > 2e-4) throw new IOException("non-rigid " + name);
        }
        if (Math.abs(GripIK.dot(GripIK.cross(new double[] {m[0], m[4], m[8]}, new double[] {m[1], m[5], m[9]}),
                new double[] {m[2], m[6], m[10]}) - 1) > 2e-4) throw new IOException("reflected " + name);
    }

    private static Track track(List<Track> tracks, int i) throws IOException {
        if (i < -1 || i >= tracks.size()) {
            throw new IOException("track index " + i);
        }
        return i < 0 ? null : tracks.get(i);
    }

    private static int count(ByteBuffer b) throws IOException {
        int n = b.getInt();
        if (n < 0 || n > MAX_COUNT) {
            throw new IOException("count " + n);
        }
        return n;
    }

    private static float finite(float v) throws IOException {
        if (!Float.isFinite(v)) {
            throw new IOException("non-finite value");
        }
        return v;
    }

    private static String text(ByteBuffer b) throws IOException {
        int n = Short.toUnsignedInt(b.getShort());
        if (n > b.remaining()) {
            throw new IOException("string length " + n);
        }
        byte[] s = new byte[n];
        b.get(s);
        return new String(s, StandardCharsets.UTF_8);
    }
}
