package pz.escapefromzomboidfirearms.zb;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;

/** QA measurements of the performance pass (23.10.2026): read only when asked, nothing runs on its own. */
public final class PerfCounters {
    private PerfCounters() { }

    /** Bytes allocated by the calling thread since it started (HotSpot's per-thread counter), -1 without one. */
    public static double allocated() {
        try {
            if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t && t.isThreadAllocatedMemoryEnabled()) {
                return t.getCurrentThreadAllocatedBytes();
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    /** "name=count/ms ..." of every collector since the JVM started. */
    public static String gc() {
        StringBuilder b = new StringBuilder();
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (b.length() > 0) b.append(' ');
            b.append(g.getName().replace(' ', '_')).append('=').append(g.getCollectionCount()).append('/').append(g.getCollectionTime());
        }
        return b.toString();
    }
}
