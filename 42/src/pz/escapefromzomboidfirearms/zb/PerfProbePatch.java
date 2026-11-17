package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Patch;

/** Read-only timing around the ABI-checked Viewpoint frame. No changes to arguments or results. */
public final class PerfProbePatch {
    @Patch(className = "viewpoint.SceneDrawer", methodName = "render", strictMatch = true)
    public static class Frame {
        @Patch.OnEnter
        public static void enter(@Patch.Local("twPerfFrame") PerfProbe.Scope scope) {
            scope = PerfProbe.viewpointBegin();
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit(@Patch.Local("twPerfFrame") PerfProbe.Scope scope) {
            PerfProbe.end(scope);
        }
    }
}
