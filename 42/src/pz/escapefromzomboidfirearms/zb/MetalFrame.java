package pz.escapefromzomboidfirearms.zb;

import java.util.List;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.core.SpriteRenderer;
import zombie.core.textures.TextureDraw;

/**
 * The drawers of one Viewpoint frame (the scope picture, the marks of the sights, the devices' images) where the
 * Viewpoint Metal shim (VPMetal) draws Viewpoint: inside its frame, at the exit of SceneDrawer.drawFrame.
 *
 * On the game's own OpenGL they are ordinary drawers right after SceneDrawer: Viewpoint's image is in the world frame
 * buffer, they draw over it. The shim keeps that image in a Metal texture and hands it to the real context when the
 * outermost glPopAttrib closes the frame (vpshim.gl.Present.viewpointFrameDone). A drawer after that draws into the
 * shim's other screen texture, which Viewpoint never drew, and its own glPopAttrib would show that texture instead of
 * the frame. So under the shim they run where ProjectViewpointADS draws its scope (the same exit, Patches$PipFrame): the
 * frame's image is finished and still the shim's.
 *
 * A drawer still sits in the render queue, for its postRender as ever. It never draws from there: without the frame's
 * exit there is no image of Viewpoint's to draw over, and its glPopAttrib would show the shim's other screen texture. Without
 * the shim queue() is SpriteRenderer.drawGeneric.
 */
public final class MetalFrame {
    private MetalFrame() {
    }

    /** One drawer of a frame: drawn at the frame's exit (MetalFrame.afterDrawFrame), its postRender in the queue. */
    static final class Tail extends TextureDraw.GenericDrawer {
        final TextureDraw.GenericDrawer drawer;

        Tail(TextureDraw.GenericDrawer drawer) {
            this.drawer = drawer;
        }

        @Override
        public void render() {
        }

        @Override
        public void postRender() {
            drawer.postRender();
        }
    }

    /** Main thread: a drawer of the frame the main thread is making. */
    static void queue(TextureDraw.GenericDrawer drawer) {
        if (GLCompat.metalPath()) {
            ViewpointPass.Batch batch = ViewpointPass.open != null ? ViewpointPass.open : ViewpointPass.lastOpened;
            if (batch != null) {
                Tail tail = new Tail(drawer);
                if (batch.addTail(tail)) {
                    SpriteRenderer.instance.drawGeneric(tail);
                    return;
                }
            }
        }
        SpriteRenderer.instance.drawGeneric(drawer);
    }

    /** Render thread: Viewpoint's frame is finished, the shim has not shown it yet. */
    public static void afterDrawFrame() {
        if (!GLCompat.metalPath()) {
            return;
        }
        try {
            ViewpointPass.Batch batch = ViewpointPass.batchOf(ViewpointBridge.currentScene());
            if (batch == null) {
                return;
            }
            // The fallback of the effects (no HDR path this frame) belongs inside the frame too.
            batch.post();
            batch.hdr = true;
            List<Tail> tails = batch.takeTails();
            for (Tail tail : tails) {
                // Each one's failure is its own: Viewpoint turns itself off for an exception out of its frame.
                try {
                    ViewpointBridge.restoreOutput();
                    tail.drawer.render();
                } catch (Throwable t) {
                    if (!failureLogged) {
                        failureLogged = true;
                        System.out.println("[TWZB] metal frame: " + tail.drawer.getClass().getName() + ": " + t);
                        t.printStackTrace(System.out);
                    }
                }
            }
            ViewpointBridge.restoreOutput();
            drawn += tails.size();
            if (!tails.isEmpty() && !firstLogged) {
                firstLogged = true;
                System.out.println("[TWZB] metal frame: " + tails.size() + " drawers inside Viewpoint's frame, first of them "
                        + tails.get(0).drawer.getClass().getName());
            }
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                System.out.println("[TWZB] metal frame: " + t);
                t.printStackTrace(System.out);
            }
        }
    }

    /** QA and the player's log: the drawers run inside Viewpoint's frames so far. */
    public static volatile long drawn;
    private static boolean firstLogged, failureLogged;

    @Patch(className = "viewpoint.SceneDrawer", methodName = "drawFrame")
    public static class AfterDrawFrame {
        @Patch.OnExit
        public static void exit() {
            MetalFrame.afterDrawFrame();
        }
    }
}
