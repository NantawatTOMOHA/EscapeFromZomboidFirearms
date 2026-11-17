package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.CombatManager;
import zombie.characters.IsoGameCharacter;

/** Only PZ is patched. User's decision of 24.10.2026 (W6 item 5): the stress and panic terms leave
 * the hit chance of this mod's weapons in Viewpoint, so a fallback shot keeps the accuracy the live
 * shot has; every other moodle term and every other weapon stay as vanilla (MoodlePenalty). */
public final class PatchMoodlePenalty {
    @Patch(className = "zombie.CombatManager", methodName = "getMoodlesPenalty", strictMatch = true)
    public static class Penalty {
        @Patch.OnExit
        public static void exit(@Patch.This Object manager, @Patch.Argument(0) IsoGameCharacter actor,
                @Patch.Argument(1) float distance, @Patch.Return(readOnly = false) float result) {
            if (actor == null || !MoodlePenalty.exempt(actor)) return;
            CombatManager combat = manager instanceof CombatManager current ? current : CombatManager.getInstance();
            result = Math.max(0, result - MoodlePenalty.removed(combat, actor, distance));
        }
    }
}
