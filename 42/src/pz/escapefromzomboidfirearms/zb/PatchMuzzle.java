package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;

/** Tarkov muzzle effects (MuzzleFx) in place of the vanilla muzzle flash of a Tarkov weapon. */
public class PatchMuzzle {
    /** Only the light is suppressed; Viewpoint's separate shot impulse keeps running. */
    @Patch(className = "viewpoint.light.MuzzleFlashes", methodName = "fired", strictMatch = true)
    public static class ViewpointLight {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoGameCharacter character) {
            return MuzzleFx.suppressLight(character);
        }
    }

    /**
     * EffectsManager.startMuzzleFlash(character, inverse probability): called on
     * every shot of an aimed firearm (CombatManager.attackCollisionCheck) and for
     * remote players (PlayerHit). A Tarkov weapon plays its own effects; the
     * vanilla flash model and light are skipped. Other weapons are untouched.
     * With Viewpoint the skip also skips its advice there (we load after it):
     * the bridge hands it the shot, so its muzzle light and camera kick stay.
     */
    @Patch(className = "zombie.EffectsManager", methodName = "startMuzzleFlash")
    public static class Flash {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoGameCharacter character) {
            // advice code is inlined: public members only
            boolean played = MuzzleFx.shot(character);
            MovingWeaponParts.shot(character);
            if (played) {
                FpRecoil.shot(character); // Tarkov recoil in the first person; takes the camera kick
                ViewpointBridge.keepFlash(character);
            }
            return played;
        }
    }

    /**
     * After IsoWorld.render (a player's world frame, main thread): with
     * Viewpoint the batch of its perspective pass opens here, right after its
     * own frame was queued (effects of this frame can go into it); then the
     * effects into the world frame.
     */
    @Patch(className = "zombie.iso.IsoWorld", methodName = "render")
    public static class AfterWorld {
        @Patch.OnExit
        public static void exit() {
            if (!ModLua.present()) return; // a server without the mod: no batch, no scope prewarm (ModLua)
            ViewpointPass.afterWorld();
            MuzzleFx.afterWorld();
        }
    }
}
