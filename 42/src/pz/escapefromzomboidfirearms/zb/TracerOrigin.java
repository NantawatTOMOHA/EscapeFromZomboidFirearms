package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.types.HandWeapon;

/**
 * FB8 item 4 (user report of 22.10.2026, "the tracer does not fly out of the barrel"). The tracer is the game's own
 * (IsoBulletTracerEffects; the mod draws none): createEffect starts it at BallisticsController.getMuzzlePosition, and
 * since B42.20 that point is projected back along the bore onto the plane through the character's origin
 * (getMuzzlePositionAtCharacterPlane), so the line starts level with the body - and from the model script's static
 * "muzzle" attachment, the default preset's muzzle. For this mod's weapons the start is moved to the bore exit of the
 * assembly in the hands (MuzzleFx.boreExit, the flash's frame); addEffect copies x0/y0/z0 into start and aims the line
 * from there, so the end and the impact cosmetics (TWImpactFx.nativeTracer) follow. Other weapons are not touched.
 */
public final class TracerOrigin {
    public static volatile boolean enabled = true;
    static volatile long moved, skipped, failed;
    static volatile double lastShift;
    static volatile String reason = "";
    /**
     * Performance pass 23.10.2026: the last move's numbers (from x y z, start x y z, character x y z, look degrees);
     * info() formats them for QA (fb8tracer reads from/start/character/look) instead of every shot. Main thread.
     */
    private static final double[] LAST = new double[10];
    private static volatile boolean hasLast;
    private static Field x0, y0, z0;

    private TracerOrigin() {
    }

    public static void exit(IsoGameCharacter c, Object effect) {
        if (!enabled || effect == null || c == null) {
            return;
        }
        try {
            if (!(c.getPrimaryHandItem() instanceof HandWeapon weapon)
                    || !weapon.getFullType().startsWith("EscapeFromZomboidFirearms.")) {
                return;
            }
            double[] p = new double[3];
            if (!MuzzleFx.boreExit(c, p)) {
                skipped++;
                reason = "no-frame";
                return;
            }
            if (x0 == null) {
                Class<?> type = effect.getClass();
                Field a = type.getDeclaredField("x0"), b = type.getDeclaredField("y0"), d = type.getDeclaredField("z0");
                for (Field f : new Field[] {a, b, d}) {
                    if (f.getType() != float.class || Modifier.isStatic(f.getModifiers())) {
                        throw new IllegalStateException("native tracer origin ABI");
                    }
                    f.setAccessible(true);
                }
                x0 = a;
                y0 = b;
                z0 = d;
            }
            double dx = p[0] - x0.getFloat(effect), dy = p[1] - y0.getFloat(effect);
            lastShift = Math.sqrt(dx * dx + dy * dy);
            // (a float is formatted as its double value: stored widened, the same text)
            LAST[0] = x0.getFloat(effect);
            LAST[1] = y0.getFloat(effect);
            LAST[2] = z0.getFloat(effect);
            LAST[3] = p[0];
            LAST[4] = p[1];
            LAST[5] = p[2];
            LAST[6] = c.getX();
            LAST[7] = c.getY();
            LAST[8] = c.getZ();
            LAST[9] = Math.toDegrees(c.getLookAngleRadians());
            hasLast = true;
            x0.setFloat(effect, (float) p[0]);
            y0.setFloat(effect, (float) p[1]);
            z0.setFloat(effect, (float) p[2]);
            moved++;
            reason = "";
        } catch (Throwable t) {
            failed++;
            reason = t.toString();
        }
    }

    public static String info() {
        return "tracer enabled=" + enabled + " moved=" + moved + " skipped=" + skipped + " failed=" + failed
                + " lastShiftTiles=" + String.format(java.util.Locale.ROOT, "%.3f", lastShift)
                + (reason.isEmpty() ? "" : " reason=" + reason) + (!hasLast ? "" : " " + String.format(java.util.Locale.ROOT,
                        "from=%.3f,%.3f,%.3f start=%.3f,%.3f,%.3f character=%.3f,%.3f,%.3f look=%.1f",
                        LAST[0], LAST[1], LAST[2], LAST[3], LAST[4], LAST[5], LAST[6], LAST[7], LAST[8], LAST[9]));
    }

    @Patch(className = "zombie.iso.objects.IsoBulletTracerEffects", methodName = "createEffect")
    public static final class Create {
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoGameCharacter c, @Patch.Return Object effect) {
            TracerOrigin.exit(c, effect);
        }
    }
}
