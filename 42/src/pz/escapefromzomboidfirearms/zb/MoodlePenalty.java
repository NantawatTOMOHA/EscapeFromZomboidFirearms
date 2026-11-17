package pz.escapefromzomboidfirearms.zb;

import zombie.CombatManager;
import zombie.SandboxOptions;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.Moodles.Moodles;
import zombie.combat.CombatConfig;
import zombie.combat.CombatConfigKey;
import zombie.inventory.types.HandWeapon;
import zombie.scripting.objects.MoodleType;

/**
 * W6 item 5 (the user's decision of 24.10.2026): in the first person of Viewpoint stress and panic
 * make the hands and the weapon float a little, but they must not lower accuracy - the bullet flies
 * where the sight points. The mod's own live shot never used PZ's hit chance; only a shot that falls
 * back to PZ does (an admission or prepare rejection, or a claimed multiplayer round the server will
 * not fly). PZ's chance takes CombatManager.getMoodlesPenalty, which adds the PANIC and STRESS levels
 * (with their distance modifiers) to a percentage penalty.
 *
 * <p>This class removes exactly those two terms for a local player holding one of this mod's weapons
 * while Viewpoint shows the world, and keeps every other term (tired, endurance, drunk) and the
 * sandbox multiplier. Vanilla weapons, the isometric view and a session without Viewpoint are
 * untouched. In multiplayer the same client computes the chance that a dedicated server rolls for a
 * claimed round, so one place covers both fallback paths.
 */
public final class MoodlePenalty {
    static final String PREFIX = "EscapeFromZomboidFirearms.";
    private MoodlePenalty() { }

    /** The local shooter holds one of this mod's weapons and Viewpoint draws the world. */
    public static boolean exempt(IsoGameCharacter actor) {
        if (!(actor instanceof IsoPlayer player) || !player.isLocalPlayer()) return false;
        if (!(player.getPrimaryHandItem() instanceof HandWeapon weapon)) return false;
        if (weapon.getFullType() == null || !weapon.getFullType().startsWith(PREFIX)) return false;
        return ViewpointBridge.perspective();
    }

    /** The panic + stress part of PZ's own moodle penalty, with PZ's own config and sandbox multiplier.
     * Public: a ZombieBuddy patch inlines its body into another package (zombie.CombatManager), so a
     * package-private method is an IllegalAccessError there (in game, 24.10.2026). */
    public static float removed(CombatManager manager, IsoGameCharacter actor, float distance) {
        Moodles moodles = actor.getMoodles();
        CombatConfig config = manager == null ? null : manager.getCombatConfig();
        if (moodles == null || config == null || !Float.isFinite(distance)) return 0;
        float panic = moodles.getMoodleLevel(MoodleType.PANIC);
        float stress = moodles.getMoodleLevel(MoodleType.STRESS);
        if (!(panic > 0) && !(stress > 0)) return 0;
        double value = panic * (config.get(CombatConfigKey.PANIC_TO_HIT_BASE_PENALTY)
                        + distance * config.get(CombatConfigKey.PANIC_TO_HIT_DISTANCE_MODIFIER))
                + stress * (config.get(CombatConfigKey.STRESS_TO_HIT_BASE_PENALTY)
                        + distance * config.get(CombatConfigKey.STRESS_TO_HIT_DISTANCE_MODIFIER));
        SandboxOptions sandbox = SandboxOptions.instance;
        if (sandbox != null) value *= sandbox.firearmMoodleMultiplier.getValue();
        return Float.isFinite((float) value) ? (float) value : 0;
    }

    /** QA (TWZB.qaMoodlePenalty): the native penalty of the held weapon and the part this class removes. */
    public static String info(IsoGameCharacter actor, float distance) {
        try {
            CombatManager manager = CombatManager.getInstance();
            float total = manager.getMoodlesPenalty(actor, distance);
            boolean exempt = exempt(actor);
            float removed = exempt ? removed(manager, actor, distance) : 0;
            return "distance=" + distance + " penalty=" + total + " exempt=" + exempt
                    + " removed=" + removed + " kept=" + Math.max(0, total - removed);
        } catch (Throwable t) {
            return "error=" + t;
        }
    }
}
