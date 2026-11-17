package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.BodyDamage.BodyDamage;
import zombie.inventory.types.HandWeapon;

/** Only PZ is patched. No VP/STT/PVADS/VTB implementation is inspected or changed. */
public final class PatchShotDamage {
    @Patch(className="zombie.characters.BodyDamage.BodyDamage",methodName="DamageFromWeapon",strictMatch=true)
    public static class Wound {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.This BodyDamage body,@Patch.Argument(0) HandWeapon weapon,
                @Patch.Argument(value=1,readOnly=false) int part) {
            int chosen=ShotDamageScope.woundPart(body,weapon,part);
            if (chosen==-2) return true;
            part=chosen;return false;
        }
    }

    /** The copy of a live hit whose weapon is held in both hands keeps its damage (ShotHands). */
    @Patch(className="zombie.CombatManager",methodName="applyOneHandedDamagePenalty",strictMatch=true)
    public static class BothHands {
        @Patch.OnExit
        public static void exit(@Patch.Argument(1) HandWeapon weapon,@Patch.Argument(2) float damage,
                @Patch.Return(readOnly=false) float result) {
            if (ShotHands.held(weapon)) result=damage;
        }
    }
}
