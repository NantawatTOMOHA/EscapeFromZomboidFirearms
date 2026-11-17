package pz.escapefromzomboidfirearms.zb;

/** The detached weapon copy of a live hit whose real weapon the shooter holds in both hands: PZ's
 * one-handed penalty (CombatManager.applyOneHandedDamagePenalty, PatchShotDamage.BothHands) compares
 * hand items by reference, never finds the copy in the hands and halved every hit of a two-handed
 * weapon (user's report of 21.10.2026, item 9). Thread-confined, open only around the native Hit;
 * standard Java for detached tests. */
public final class ShotHands implements AutoCloseable {
    private static final ThreadLocal<Object> HELD = new ThreadLocal<>();
    private final Object previous;

    public ShotHands(Object weapon) {
        previous = HELD.get();
        HELD.set(java.util.Objects.requireNonNull(weapon));
    }

    /** This weapon is the copy of a live hit held in both hands. */
    public static boolean held(Object weapon) {
        return weapon != null && HELD.get() == weapon;
    }

    @Override public void close() {
        if (previous == null) HELD.remove(); else HELD.set(previous);
    }
}
