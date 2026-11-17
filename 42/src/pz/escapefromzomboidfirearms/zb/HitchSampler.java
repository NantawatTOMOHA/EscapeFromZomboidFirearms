package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;

import zombie.core.opengl.RenderThread;

/**
 * QA only (debug): where a long frame goes. The game's main thread (the caller of start) and the render thread are
 * sampled every millisecond for ms; the caller marks each of its frames (mark), and report() counts, over the frames
 * longer than a threshold, the methods on the sampled stacks: self (the top frame) and inclusive (once per sample).
 */
public final class HitchSampler {
    private record Sample(long at, int thread, StackTraceElement[] stack) { }

    private static final List<Sample> samples = new ArrayList<>();
    private static final List<Long> marks = new ArrayList<>();
    private static volatile Thread worker;
    private static Thread[] targets = new Thread[0];

    private HitchSampler() {
    }

    /** Main thread: sampling for ms from now (the previous samples and marks dropped). */
    static synchronized void start(double ms) {
        stop();
        samples.clear();
        marks.clear();
        Thread render = RenderThread.renderThread;
        targets = render != null && render != Thread.currentThread()
                ? new Thread[] {Thread.currentThread(), render} : new Thread[] {Thread.currentThread()};
        long until = System.nanoTime() + (long) (ms * 1e6);
        Thread[] watched = targets;
        Thread t = new Thread(() -> {
            while (System.nanoTime() < until && !Thread.currentThread().isInterrupted()) {
                for (int i = 0; i < watched.length; i++) {
                    StackTraceElement[] stack = watched[i].getStackTrace();
                    synchronized (HitchSampler.class) {
                        samples.add(new Sample(System.nanoTime(), i, stack));
                    }
                }
                LockSupport.parkNanos(1_000_000L);
            }
        }, "TW hitch sampler");
        t.setDaemon(true);
        worker = t;
        t.start();
    }

    static synchronized void mark() {
        marks.add(System.nanoTime());
    }

    static void stop() {
        Thread t = worker;
        worker = null;
        if (t != null) {
            t.interrupt();
            try {
                t.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The frames longer than thresholdMs, and per thread the top methods over them; printed and returned. */
    static synchronized String report(double thresholdMs, int top) {
        stop();
        List<long[]> long_ = new ArrayList<>();
        for (int i = 1; i < marks.size(); i++) {
            long a = marks.get(i - 1), b = marks.get(i);
            if ((b - a) / 1e6 >= thresholdMs) {
                long_.add(new long[] {a, b});
            }
        }
        StringBuilder out = new StringBuilder();
        long first = marks.isEmpty() ? 0 : marks.get(0);
        out.append(String.format(Locale.ROOT, "frames=%d samples=%d long=%d", Math.max(0, marks.size() - 1), samples.size(), long_.size()));
        for (long[] f : long_) {
            out.append(String.format(Locale.ROOT, " [%.0f+%.1fms]", (f[0] - first) / 1e6, (f[1] - f[0]) / 1e6));
        }
        line(out.toString());
        for (int thread = 0; thread < targets.length; thread++) {
            Map<String, Integer> self = new HashMap<>(), inclusive = new HashMap<>();
            int n = 0;
            for (Sample s : samples) {
                if (s.thread != thread || !inside(long_, s.at) || s.stack.length == 0) {
                    continue;
                }
                n++;
                self.merge(name(s.stack[0]), 1, Integer::sum);
                Set<String> seen = new HashSet<>();
                for (StackTraceElement e : s.stack) {
                    String key = name(e);
                    if (seen.add(key)) {
                        inclusive.merge(key, 1, Integer::sum);
                    }
                }
            }
            String who = targets[thread].getName();
            line("thread=" + who + " samples=" + n + " self:" + topOf(self, top, n));
            line("thread=" + who + " samples=" + n + " inclusive:" + topOf(inclusive, top * 2, n));
        }
        return out.toString();
    }

    private static boolean inside(List<long[]> frames, long at) {
        for (long[] f : frames) {
            if (at >= f[0] && at <= f[1]) {
                return true;
            }
        }
        return false;
    }

    private static String name(StackTraceElement e) {
        String c = e.getClassName();
        return c.substring(c.lastIndexOf('.') + 1) + "." + e.getMethodName();
    }

    private static String topOf(Map<String, Integer> counts, int top, int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(top, list.size()); i++) {
            Map.Entry<String, Integer> e = list.get(i);
            s.append(String.format(Locale.ROOT, " %s=%d(%.0f%%)", e.getKey(), e.getValue(), n > 0 ? 100.0 * e.getValue() / n : 0));
        }
        return s.toString();
    }

    private static void line(String text) {
        System.out.println("[TWZB-HITCH] " + text);
    }
}
