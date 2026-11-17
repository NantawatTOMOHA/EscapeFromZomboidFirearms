package pz.escapefromzomboidfirearms.zb;

/** QA A/B of the performance pass (23.10.2026): true runs the former forms of the changed hot paths in the same session
 *  (TWZB.perfLegacy; the Lua side has R.legacy and R.snapshotOff). Off by default, memory only, never saved. */
public final class PerfLegacy {
    public static volatile boolean on;

    private PerfLegacy() { }
}
