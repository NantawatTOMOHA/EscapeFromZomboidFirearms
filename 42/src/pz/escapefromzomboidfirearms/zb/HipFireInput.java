package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Modifier;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.component.CharacterInputComponent;
import zombie.core.Core;
import zombie.inventory.types.HandWeapon;

/** Hip-fire input admission and isolated QA input. No game settings or OS input are changed. */
public final class HipFireInput {
    public static final long QA_TTL_NS = 250_000_000L;
    public record Qa(IsoPlayer player, CharacterInputComponent input, boolean held, long until) { }
    public record State(IsoGameCharacter player, Object item, long frame) { }
    public static volatile Qa qa;
    public static volatile State state;
    public static volatile boolean broken;
    private static volatile boolean checked;
    public static volatile String lastError = "";

    private HipFireInput() { }

    private static void member(Class<?> owner, String name, Class<?> type) throws ReflectiveOperationException {
        var m = owner.getMethod(name);
        if (m.getReturnType() != type || Modifier.isStatic(m.getModifiers())) throw new NoSuchMethodException(owner.getName() + "." + name);
    }

    /** Check every native member used by the input advice before admitting a QA override. */
    public static boolean ready() {
        if (broken) return false;
        if (checked) return true;
        try {
            member(CharacterInputComponent.class, "isAttackButtonDown", boolean.class);
            member(IsoGameCharacter.class, "getCharacterInputComponent", CharacterInputComponent.class);
            member(IsoGameCharacter.class, "getPrimaryHandItem", zombie.inventory.InventoryItem.class);
            member(IsoGameCharacter.class, "getVehicle", zombie.vehicles.BaseVehicle.class);
            member(HandWeapon.class, "isRanged", boolean.class);
            if (!Modifier.isStatic(Core.class.getField("debug").getModifiers())
                    || Core.class.getField("debug").getType() != boolean.class) {
                throw new NoSuchMethodException("hip-fire input ABI");
            }
            for (String name : new String[] {"isAiming", "isPerformingHostileAnimation", "isAnyAimKeyDown", "isForceAim", "isDead",
                    "isStrafing", "isRunning", "isSprinting", "isSitOnGround"}) {
                member(IsoGameCharacter.class, name, boolean.class);
            }
            member(IsoGameCharacter.class, "getCurrentActionContextStateName", String.class);
            member(IsoPlayer.class, "isLocalPlayer", boolean.class);
            member(IsoPlayer.class, "getPlayerNum", int.class);
            member(IsoPlayer.class, "getJoypadBind", int.class);
            checked = true;
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    public static void fail(Throwable t) {
        qa = null;
        state = null;
        if (!broken) System.out.println("[TW-HIP] input disabled: " + t);
        lastError = t.toString();
        broken = true;
    }

    public static boolean local(IsoGameCharacter c) {
        return c instanceof IsoPlayer p && p.isLocalPlayer() && p.getPlayerNum() == 0 && !p.isDead() && p.getJoypadBind() == -1;
    }

    /** QA renews the same native attack button every tick; missing renewals expire after 250 ms. */
    public static boolean qaHold(IsoGameCharacter c, boolean held) {
        try {
            if (!Core.debug || !ready() || !local(c)) {
                qaReset();
                return false;
            }
            CharacterInputComponent input = c.getCharacterInputComponent();
            if (input == null) return false;
            qa = new Qa((IsoPlayer) c, input, held, System.nanoTime() + QA_TTL_NS);
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    public static void qaReset() { qa = null; }

    /** Our Lua gate renews this only for an eligible hip press or its native attack finishing. */
    public static boolean state(IsoGameCharacter c, boolean held) {
        if (!held) {
            if (state != null && state.player == c) state = null;
            return false;
        }
        try {
            if (!ready() || !owns(c)) {
                state = null;
                return false;
            }
            state = new State(c, c.getPrimaryHandItem(), FpRecoil.worldFrame());
            return true;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
    }

    public static boolean owns(IsoGameCharacter c) {
        FpPose.Request r = c == null ? null : FpPose.requests.get(c);
        return local(c) && c.getVehicle() == null && !c.isSitOnGround() && !AnimOptOut.blocks(c)
                && ViewpointBridge.ready() && ViewpointBridge.state() == 1
                && FpPose.enabled && r != null && r.allowed && !r.spent
                && FpPose.freshFrame(r.stampFrame, FpRecoil.worldFrame()) && r.item == c.getPrimaryHandItem()
                && r.item instanceof HandWeapon w && w.isRanged();
    }

    /** Pure native intent rule: a hostile animation alone is not the player's Aim input. */
    public static boolean intent(boolean nativeAiming, boolean hostile, boolean aim, boolean forced) {
        return nativeAiming && !(hostile && !aim && !forced);
    }

    /** Only our consumers use this; the engine, animations and network retain native isAiming(). */
    public static boolean aimIntent(IsoGameCharacter c) {
        if (c == null) return false;
        boolean original = c.isAiming();
        State s = state;
        if (s == null || s.player != c || broken) return original;
        try {
            if (!FpPose.freshFrame(s.frame, FpRecoil.worldFrame()) || s.item != c.getPrimaryHandItem() || !owns(c)) return original;
            return intent(original, c.isPerformingHostileAnimation(), c.isAnyAimKeyDown(), c.isForceAim());
        } catch (Throwable t) {
            fail(t);
            return original;
        }
    }

    public static boolean attackAt(CharacterInputComponent input, boolean original, long now, boolean debug) {
        Qa q = qa;
        if (q == null || q.input != input) return original;
        if (broken || !debug || now > q.until || !local(q.player) || q.player.getCharacterInputComponent() != input) {
            qa = null;
            return original;
        }
        return q.held;
    }

    public static boolean attack(CharacterInputComponent input, boolean original) {
        if (qa == null) return original;
        try {
            return attackAt(input, original, System.nanoTime(), Core.debug);
        } catch (Throwable t) {
            fail(t);
            return original;
        }
    }

    /** Pure walking-strafe rule: only walking ('movement'/'strafe'), never idle, run, sprint or sitting. */
    public static boolean strafingFor(boolean original, boolean running, boolean sprinting, boolean sitting, String action) {
        if (original) return true;
        if (running || sprinting || sitting || action == null) return false;
        return "movement".equalsIgnoreCase(action) || "strafe".equalsIgnoreCase(action);
    }

    /** Enter native strafe only for the owned local Viewpoint FP player walking during a hip press/burst. */
    public static boolean strafing(IsoGameCharacter c, boolean original) {
        State s = state;
        if (original || s == null || s.player != c || broken) return original;
        try {
            if (!FpPose.freshFrame(s.frame, FpRecoil.worldFrame()) || s.item != c.getPrimaryHandItem() || !owns(c)
                    || c.isAnyAimKeyDown() || c.isForceAim()) {
                return original;
            }
            return strafingFor(false, c.isRunning(), c.isSprinting(), c.isSitOnGround(), c.getCurrentActionContextStateName());
        } catch (Throwable t) {
            fail(t);
            return original;
        }
    }

    @Patch(className = "zombie.characters.component.CharacterInputComponent", methodName = "isAttackButtonDown", strictMatch = true)
    public static class AttackButton {
        @Patch.OnExit
        public static void exit(@Patch.This CharacterInputComponent input, @Patch.Return(readOnly = false) boolean result) {
            result = HipFireInput.attack(input, result);
        }
    }

    @Patch(className = "zombie.characters.IsoGameCharacter", methodName = "isStrafing", strictMatch = true)
    public static class Strafing {
        @Patch.OnExit
        public static void exit(@Patch.This IsoGameCharacter character, @Patch.Return(readOnly = false) boolean result) {
            result = HipFireInput.strafing(character, result);
        }
    }
}
