package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;
import zombie.characters.component.CharacterInputComponent;
import zombie.input.GameKeyboard;
import zombie.inventory.types.HandWeapon;

/**
 * Player report of 03.11.2026: "from the third person the magnification cannot be changed: after a short right click
 * into the sight, Alt takes the character out of it at once, so Alt + mouse wheel never zooms".
 *
 * The wheel gestures of the mod are held modifiers: the zoom modifier (TW_SightInput, default Left Alt) and the
 * reticle brightness row (default Left Alt + Left Shift + wheel). B42's default Sprint is Left Alt and Run is Left
 * Shift, and IsoPlayer.UpdateInputState drops a latched aim on either: "if (running || sprinting) setForceAim(false)"
 * (javap of projectzomboid.jar 42.21). A held right button keeps its aim through that (isAnyAimKeyDown), a latched one
 * does not - the mod's click into the sight from Viewpoint's third person (TW_AdsFromThird) and the game's own
 * toggle-to-aim option in any view, so their Alt + wheel ended the aim instead of zooming.
 *
 * While a local player aims a weapon of the mod (aiming, or the latched forceAim) and holds one of those gestures,
 * the run and sprint buttons read as up for that player: the aim stays, as it does under a held right button, and the
 * wheel zooms. Without the gesture, without the aim, for another weapon or another player nothing changes. The keys
 * come from the mod's options (TW_SightInput pushes them through TWZB.aimGestureKeys); the defaults are the options'.
 */
public final class AimGestureInput {
    private static final String MOD_PREFIX = "EscapeFromZomboidFirearms.";
    private static final int KEY_LSHIFT = 42, KEY_RSHIFT = 54, KEY_LCONTROL = 29, KEY_RCONTROL = 157, KEY_LMENU = 56, KEY_RMENU = 184;
    /** The zoom modifier's key (0: unbound) and the brightness wheel row (shift, ctrl, alt; none: unbound). */
    static volatile int zoomKey = KEY_LMENU;
    static volatile boolean rowShift = true, rowCtrl = false, rowAlt = true;
    public static volatile boolean enabled = true, broken;
    public static volatile long kept, errors;
    public static volatile String lastError = "";

    private AimGestureInput() { }

    /** TW_SightInput: the options' keys (the zoom modifier's key code; the brightness row's modifiers). */
    static void keys(int key, boolean shift, boolean ctrl, boolean alt) {
        zoomKey = Math.max(0, key);
        rowShift = shift;
        rowCtrl = ctrl;
        rowAlt = alt;
    }

    static String describe() {
        return "enabled=" + enabled + " zoomKey=" + zoomKey + " row=" + (rowShift ? "S" : "") + (rowCtrl ? "C" : "") + (rowAlt ? "A" : "")
                + " kept=" + kept + " errors=" + errors + " broken=" + broken + (lastError.isEmpty() ? "" : " last=" + lastError);
    }

    /** A key as TW_KeyCombo.held reads it: Shift/Ctrl/Alt on either side, any other key itself. */
    static boolean keyDown(int key, java.util.function.IntPredicate down) {
        if (key <= 0) return false;
        if (key == KEY_LSHIFT || key == KEY_RSHIFT) return down.test(KEY_LSHIFT) || down.test(KEY_RSHIFT);
        if (key == KEY_LCONTROL || key == KEY_RCONTROL) return down.test(KEY_LCONTROL) || down.test(KEY_RCONTROL);
        if (key == KEY_LMENU || key == KEY_RMENU) return down.test(KEY_LMENU) || down.test(KEY_RMENU);
        return down.test(key);
    }

    /** One of the mod's wheel gestures is held: the zoom modifier, or the brightness row exactly (TW_KeyCombo.wheelHeld). */
    static boolean gesture(java.util.function.IntPredicate down) {
        if (keyDown(zoomKey, down)) return true;
        if (!(rowShift || rowCtrl || rowAlt)) return false;
        boolean shift = keyDown(KEY_LSHIFT, down), ctrl = keyDown(KEY_LCONTROL, down), alt = keyDown(KEY_LMENU, down);
        return shift == rowShift && ctrl == rowCtrl && alt == rowAlt;
    }

    /** The rule without the engine: the button's own answer, kept up only for an aiming mod weapon with a gesture held. */
    static boolean button(boolean original, boolean aiming, String weapon, boolean gestureHeld) {
        return original && !(aiming && gestureHeld && weapon != null && weapon.startsWith(MOD_PREFIX));
    }

    /** The local player whose input this is (IsoPlayer.players: at most four), or null. */
    private static IsoPlayer owner(CharacterInputComponent input) {
        IsoPlayer[] players = IsoPlayer.players;
        if (players == null) return null;
        for (IsoPlayer p : players) {
            if (p != null && p.getCharacterInputComponent() == input) return p;
        }
        return null;
    }

    /** The advices' body (inlined into CharacterInputComponent: public members only). */
    public static boolean buttonDown(CharacterInputComponent input, boolean original) {
        if (!original || !enabled || broken || input == null) return original;
        try {
            if (!gesture(GameKeyboard::isKeyDown)) return original;
            IsoPlayer p = owner(input);
            if (p == null || !p.isLocalPlayer() || p.isDead()) return original;
            String weapon = p.getPrimaryHandItem() instanceof HandWeapon w ? w.getFullType() : null;
            boolean keep = !button(true, p.isAiming() || input.isForceAim(), weapon, true);
            if (keep) kept++;
            return keep ? false : original;
        } catch (Throwable t) {
            errors++;
            lastError = String.valueOf(t);
            broken = errors > 3;
            return original;
        }
    }

    @Patch(className = "zombie.characters.component.CharacterInputComponent", methodName = "isSprintButtonDown", strictMatch = true)
    public static class Sprint {
        @Patch.OnExit
        public static void exit(@Patch.This CharacterInputComponent input, @Patch.Return(readOnly = false) boolean result) {
            result = AimGestureInput.buttonDown(input, result);
        }
    }

    @Patch(className = "zombie.characters.component.CharacterInputComponent", methodName = "isRunButtonDown", strictMatch = true)
    public static class Run {
        @Patch.OnExit
        public static void exit(@Patch.This CharacterInputComponent input, @Patch.Return(readOnly = false) boolean result) {
            result = AimGestureInput.buttonDown(input, result);
        }
    }
}
