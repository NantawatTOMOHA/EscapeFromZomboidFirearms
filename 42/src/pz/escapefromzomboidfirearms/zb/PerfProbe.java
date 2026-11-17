package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

/** Optional diagnostics. Timestamps nest foreign timer queries; results are never waited for. */
public final class PerfProbe {
    public static final int VIEWPOINT = 0, PIP_SCENE = 1, PIP_HISTORY = 2, PIP_COMPOSITE = 3,
            PIP_EFFECTS = 4, SENSOR_SCOPE = 5, SENSOR_LENS = 6, SENSOR_HEAD = 7,
            RETICLE = 8, EFFECTS = 9, LIGHTS = 10, FP_POSE = 11, FP_RECOIL = 12,
            SHOT_OWNER = 13, SHOT_FLIGHT_CONTACTS = 14, FX_UPDATE = 15,
            PIP_VIEW = 16, PIP_CULL = 17, PIP_MESH_UPLOAD = 18, PIP_MODELS = 19,
            PIP_FAR = 20, PIP_SHADOW = 21, PIP_DRAW = 22, PIP_RESTORE = 23, PIP_WEATHER = 24;
    static final String[] NAMES = {"viewpoint", "pipScene", "pipHistory", "pipComposite", "pipEffects",
            "sensorScope", "sensorLens", "sensorHead", "reticle", "effects", "lights", "fpPose",
            "fpRecoil", "shotOwner", "shotFlightContacts", "fxUpdate",
            "pipView", "pipCull", "pipMeshUpload", "pipModels", "pipFar", "pipShadow", "pipDraw", "pipRestore", "pipWeather"};
    static final int CAPACITY = 8192, QUERY_SLOTS = 128;
    private static volatile Session current;
    private static volatile boolean failed, cleanup;
    private static volatile String status = "off", last = "enabled=false", glass = "unobserved";
    private static boolean verified;
    private static Pool pool;
    static LongSupplier clock = System::nanoTime;
    static Queries queries = new OpenGLQueries();

    private PerfProbe() { }

    /** No Viewpoint initialization, foreign writes, or GL calls during admission. */
    static boolean abi(ClassLoader loader) throws ReflectiveOperationException {
        var method = Class.forName("viewpoint.SceneDrawer", false, loader).getDeclaredMethod("render");
        int access = method.getModifiers();
        return method.getReturnType() == void.class && Modifier.isPublic(access) && !Modifier.isStatic(access);
    }

    public static synchronized void enable(boolean on) {
        if (!on) {
            if (current != null) last = describe(current);
            current = null;
            cleanup = true;
            return;
        }
        if (current != null || failed) return;
        try {
            if (!verified && !abi(PerfProbe.class.getClassLoader())) throw new NoSuchMethodException("SceneDrawer.render()V public instance");
            verified = true;
            current = new Session("manual", clock.getAsLong());
            status = "ready";
        } catch (Throwable t) { fail(t); }
    }

    public static boolean enabled() { return current != null; }

    /** A new epoch: delayed queries and scopes can never enter the next measurement. */
    public static synchronized void phase(String label) {
        if (current != null) current = new Session(label == null ? "manual" : label.replaceAll("[^A-Za-z0-9_.-]", "_"), clock.getAsLong());
    }

    public static void reset() { phase("manual"); }

    /** Main-thread frame boundary, after all support work queued for that frame. */
    public static void mainFrame() {
        Session s = current;
        if (s != null) synchronized (s) { s.mainFrames++; }
    }

    /** Render thread only. One entry for every real Viewpoint render, including support-off QA. */
    public static Scope viewpointBegin() {
        Session s = current;
        if (s == null) {
            if (cleanup) dispose();
            return null;
        }
        try {
            long now = clock.getAsLong();
            synchronized (s) {
                s.renderFrames++;
                if (s.previousFrame != 0) s.cadence.add(s.renderFrames, now - s.previousFrame);
                s.previousFrame = now;
            }
            if (pool != null) pool.poll(s);
            return render(VIEWPOINT);
        } catch (Throwable t) { fail(t); return null; }
    }

    public static Scope cpu(int part) { return begin(part, false); }
    public static Scope render(int part) { return begin(part, true); }

    private static Scope begin(int part, boolean gpu) {
        Session s = current;
        if (s == null) return null; // No clock, allocation, lock, class lookup, or GL when off.
        boolean renderThread = gpu || part >= PIP_VIEW;
        if (renderThread && s.renderFrames == 0) return null; // A phase may change halfway through an older render state.
        try {
            long start = clock.getAsLong();
            long frame = renderThread ? s.renderFrames : s.mainFrames;
            Scope scope = new Scope(s, part, frame, start);
            if (gpu) {
                if (pool == null) pool = new Pool(queries);
                scope.query = pool.begin(scope);
            }
            return scope;
        } catch (Throwable t) { fail(t); return null; }
    }

    /** Public because advice is inlined into another package. close() never changes gameplay failures. */
    public static final class Scope implements AutoCloseable {
        final Session session;
        final int part;
        final long frame, start;
        Slot query;
        boolean closed;

        Scope(Session session, int part, long frame, long start) {
            this.session = session; this.part = part; this.frame = frame; this.start = start;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try {
                if (query != null) {
                    queries.stamp(query.end);
                    query.pending = true;
                }
                if (current == session) synchronized (session) {
                    session.cpu[part].add(frame, Math.max(0, clock.getAsLong() - start));
                }
            } catch (Throwable t) { fail(t); }
        }
    }

    /** For advice, whose local may be null or invalidated by an option change. */
    public static void end(Scope scope) { if (scope != null) scope.close(); }

    static final class Session {
        final String phase;
        final long started;
        volatile long mainFrames = 1, renderFrames;
        long previousFrame, logged, dropped;
        final Series cadence = new Series();
        final Series[] cpu = new Series[NAMES.length], gpu = new Series[NAMES.length];
        final Map<Long, Integer>[] pending;
        final TreeSet<Long>[] missing;

        @SuppressWarnings("unchecked") Session(String phase, long started) {
            this.phase = phase; this.started = started; logged = started;
            pending = new Map[NAMES.length]; missing = new TreeSet[NAMES.length];
            for (int i = 0; i < NAMES.length; i++) {
                cpu[i] = new Series(); gpu[i] = new Series();
                pending[i] = new HashMap<>(); missing[i] = new TreeSet<>();
            }
        }
    }

    /** Bounded frame sums, not percentiles of individual calls or of one-second averages. */
    static final class Series {
        final TreeMap<Long, Long> values = new TreeMap<>();
        long calls;

        void add(long frame, long nanos) {
            values.merge(frame, nanos, Long::sum); calls++;
            while (values.size() > CAPACITY) values.pollFirstEntry();
        }

        double[] stats(long first, long last, Map<Long, Integer> pending, TreeSet<Long> missing) {
            int count = 0;
            double[] ms = new double[(int) Math.max(0, Math.min(CAPACITY, last - first + 1))];
            first = Math.max(first, last - CAPACITY + 1);
            for (long frame = first; frame <= last; frame++) {
                if (pending != null && (pending.containsKey(frame) || missing.contains(frame))) continue;
                ms[count++] = values.getOrDefault(frame, 0L) / 1e6;
            }
            return distribution(Arrays.copyOf(ms, count));
        }
    }

    /** n, mean, median (even populations averaged), nearest-rank p95. */
    static double[] distribution(double[] ms) {
        if (ms.length == 0) return new double[] {0, Double.NaN, Double.NaN, Double.NaN};
        Arrays.sort(ms);
        double sum = 0;
        for (double value : ms) sum += value;
        int n = ms.length;
        return new double[] {n, sum / n, (ms[(n - 1) / 2] + ms[n / 2]) / 2, ms[(int) Math.ceil(n * .95) - 1]};
    }

    interface Queries {
        int create();
        void stamp(int id);
        boolean ready(int id);
        long value(int id);
        void delete(int id);
    }

    static final class OpenGLQueries implements Queries {
        public int create() {
            if (!GL.getCapabilities().OpenGL33) throw new IllegalStateException("OpenGL timestamp queries unavailable");
            return GLCompat.genQuery();
        }
        public void stamp(int id) { GL33.glQueryCounter(id, GL33.GL_TIMESTAMP); }
        public boolean ready(int id) { return GL15.glGetQueryObjecti(id, GL15.GL_QUERY_RESULT_AVAILABLE) != 0; }
        public long value(int id) { return GL33.glGetQueryObjectui64(id, GL15.GL_QUERY_RESULT); }
        public void delete(int id) { GL15.glDeleteQueries(id); }
    }

    static final class Slot {
        int start, end;
        boolean busy, pending;
        Scope owner;
    }

    static final class Pool {
        final Queries gl;
        final Slot[] slots = new Slot[QUERY_SLOTS];
        int cursor;

        Pool(Queries gl) { this.gl = gl; }

        Slot begin(Scope owner) {
            for (int n = 0; n < slots.length; n++) {
                int index = cursor;
                cursor = (cursor + 1) % slots.length;
                Slot q = slots[index];
                if (q != null && q.busy) continue;
                if (q == null) {
                    slots[index] = q = new Slot();
                    q.start = gl.create(); q.end = gl.create();
                    if (q.start == 0 || q.end == 0) throw new IllegalStateException("Timestamp query allocation failed");
                }
                q.owner = owner; q.busy = true; q.pending = false;
                gl.stamp(q.start);
                synchronized (owner.session) {
                    owner.session.pending[owner.part].merge(owner.frame, 1, Integer::sum);
                }
                return q;
            }
            synchronized (owner.session) {
                owner.session.dropped++;
                owner.session.missing[owner.part].add(owner.frame);
            }
            return null; // Saturation drops a measurement, never the pass or a frame.
        }

        void poll(Session active) {
            for (Slot q : slots) {
                if (q == null || !q.busy || !q.pending) continue;
                Scope o = q.owner;
                if (o.session == active) {
                    // Even the start query is checked. GL_QUERY_RESULT is never used on an unfinished query.
                    if (!gl.ready(q.end) || !gl.ready(q.start)) continue;
                    long nanos = gl.value(q.end) - gl.value(q.start);
                    if (nanos < 0) throw new IllegalStateException("Non-monotonic GPU timestamps");
                    synchronized (active) {
                        active.gpu[o.part].add(o.frame, nanos);
                        active.pending[o.part].computeIfPresent(o.frame, (key, count) -> count == 1 ? null : count - 1);
                    }
                }
                // Old-epoch results are discarded without readback; reuse does not need their result.
                q.busy = false; q.pending = false; q.owner = null;
            }
        }

        void dispose() {
            for (Slot q : slots) if (q != null) {
                if (q.start != 0) gl.delete(q.start);
                if (q.end != 0) gl.delete(q.end);
            }
        }
    }

    private static void dispose() {
        cleanup = false;
        try { if (pool != null) pool.dispose(); }
        catch (Throwable t) { fail(t); }
        finally { pool = null; }
    }

    private static synchronized void fail(Throwable t) {
        current = null; cleanup = true;
        if (!failed) {
            failed = true; status = "off: " + t;
            System.out.println("[TWZB] perf probe " + status);
        }
    }

    /** CPU only. LensGlass owns its advice; no timings are invented from counters. */
    public static void glassStats(String info) {
        int end = info.indexOf(" models=[");
        glass = end < 0 ? info : info.substring(0, end);
    }

    public static boolean due() {
        Session s = current;
        return s != null && clock.getAsLong() - s.logged >= 1_000_000_000L;
    }

    /** Main thread, at most one log line per second. No GL and no render-thread wait. */
    public static void tick() {
        Session s = current;
        if (s == null) return;
        long now = clock.getAsLong();
        if (now - s.logged < 1_000_000_000L) return;
        s.logged = now;
        System.out.println("[TWZB] perf " + info());
    }

    public static String info() {
        Session s = current;
        return s == null ? "enabled=false status={" + status + "} last={" + last + "}" : describe(s);
    }

    private static String describe(Session s) {
        synchronized (s) {
            double[] cadence = s.cadence.stats(2, s.renderFrames, null, null);
            StringBuilder text = new StringBuilder(String.format(Locale.ROOT,
                    "enabled=true phase=%s elapsed=%.3f renderFrames=%d mainFrames=%d frameN=%.0f frameP50=%.6f frameP95=%.6f dropped=%d retained=%d truncated=%s inclusive=true",
                    s.phase, (clock.getAsLong() - s.started) / 1e9, s.renderFrames, s.mainFrames,
                    cadence[0], cadence[2], cadence[3], s.dropped, CAPACITY,
                    s.renderFrames > CAPACITY || s.mainFrames > CAPACITY));
            for (int p = 0; p < NAMES.length; p++) {
                boolean gpu = p <= EFFECTS;
                long frames = gpu || p >= PIP_VIEW ? s.renderFrames : Math.max(0, s.mainFrames - 1);
                text.append(' ').append(NAMES[p]).append("{cpu=").append(numbers(s.cpu[p].stats(1, frames, null, null)))
                        .append(" calls=").append(s.cpu[p].calls).append(" gpu=")
                        .append(gpu ? numbers(s.gpu[p].stats(1, frames, s.pending[p], s.missing[p])) : "NA")
                        .append(" gpuCalls=").append(s.gpu[p].calls).append('}');
                s.missing[p].headSet(Math.max(1, frames - CAPACITY + 1)).clear();
            }
            return text.append(" glass{cpu=unknown gpu=unknown stats=").append(glass).append('}').toString();
        }
    }

    /** Frame count / mean / p50 / p95 in ms, including zero-work frames; NaN means unobserved. */
    private static String numbers(double[] stats) {
        return String.format(Locale.ROOT, "%.0f/%.6f/%.6f/%.6f", stats[0], stats[1], stats[2], stats[3]);
    }
}
