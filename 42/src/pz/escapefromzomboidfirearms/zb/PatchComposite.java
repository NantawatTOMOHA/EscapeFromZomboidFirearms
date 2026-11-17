package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * Right after the game queued the composite of the world frames to the screen
 * (MultiTextureFBO2.render, main thread): the Viewpoint pass of this frame
 * takes no more primitives; queue the scope image over it, and the copy of the
 * frame the Tarkov weapon screens frost (FrostedBackdrop).
 */
public class PatchComposite {
    @Patch(className = "zombie.core.textures.MultiTextureFBO2", methodName = "render")
    public static class AfterComposite {
        @Patch.OnExit
        public static void exit() {
            if (!ModLua.present()) return; // a server without the mod: nothing of ours is drawn (ModLua)
            ViewpointPass.close();
            OpticRenderer.queue();
            ExplosionFx.captureFrame();
            FrostedBackdrop.captureFrame();
            PerfProbe.mainFrame();
        }
    }
}
