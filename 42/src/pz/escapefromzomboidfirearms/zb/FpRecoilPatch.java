package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Patch;

/** Tarkov's camera recoil into Viewpoint's frame (FpRecoil); does nothing without Viewpoint or the layer. */
public class FpRecoilPatch {
    /**
     * After IsoWorld.render (a player's world frame, main thread): Viewpoint has snapshotted
     * this frame (its camera additive is set) and draws it later on the render thread.
     */
    @Patch(className = "zombie.iso.IsoWorld", methodName = "render")
    public static class AfterWorld {
        @Patch.OnExit
        public static void exit() {
            try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.FP_RECOIL)) { FpRecoil.afterWorld(); }
        }
    }
}
