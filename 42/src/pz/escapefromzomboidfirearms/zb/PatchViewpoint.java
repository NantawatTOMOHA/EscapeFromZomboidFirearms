package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * The advices on Viewpoint and on the game's aim input (optional; allowed by the user with the bridge
 * guard, 16.10.2026). Without Viewpoint the Viewpoint class never loads and ZombieBuddy never applies
 * the first advice; with Viewpoint the advice body only calls our pass, which does nothing unless
 * ViewpointBridge verified every member it uses. The second advice is PZ's own input and is guarded by
 * the latch the W9 third-person aim sets: it never applies outside that aim.
 */
public class PatchViewpoint {
    /**
     * WorldRenderer.drawWeather (render thread, inside Viewpoint's frame: the
     * scene, its translucent parts and the weather are in its HDR target, the
     * fog, bloom, exposure, tone, anti-aliasing and upscaling are still to
     * come): the mod's effects of this frame into that target (ViewpointPass).
     * No arguments are bound, so any signature of the method takes it.
     */
    @Patch(className = "viewpoint.render.WorldRenderer", methodName = "drawWeather")
    public static class AfterWeather {
        @Patch.OnExit
        public static void exit() {
            ViewpointPass.afterWeather();
        }
    }

    /**
     * CharacterInputComponent.updateToggleToAim (main thread, every frame of a player's input): the game's own
     * "toggle to aim" handler. With that option on it is the player's; with it off the handler is muted and its
     * only effect is setForceAim(false) - which would clear the aim the W9 third-person aim keeps latched after
     * the right mouse button is released. Skipped only for the latched character and only while the option is
     * off (ViewpointBridge.holdsAim): the engine then keeps isAiming from that flag, and every other input and
     * every other player is untouched.
     */
    @Patch(className = "zombie.characters.component.CharacterInputComponent", methodName = "updateToggleToAim")
    public static class AimLatch {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This Object input) {
            return ViewpointBridge.holdsAim(input);
        }
    }
}
