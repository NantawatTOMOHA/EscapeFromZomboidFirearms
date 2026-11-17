package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Member;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;

/**
 * Viewpoint's aim ring while the first-person pose aims down sights (FB4, 19.10.2026, user's item 5: "the
 * drawn crosshair is still shown while aiming"). The four white arcs are the vanilla firearm reticle
 * (IsoReticle: its arms' offset is the hit chance), which Viewpoint reuses as its ring round the screen's
 * middle (its option gunplay.ring: the crosshair wanders inside it) and gates at CombatHooks.skipIsoReticle,
 * the entry of IsoReticle.render. FB2's hide (FpViewLatch.crosshair) clears only Viewpoint's crosshair dot,
 * which the ring never reads. At the exit of that gate the vanilla draw is skipped while the pose aims down
 * the sights of our weapon (FpAdsCamera.aiming: the AdsHideCrosshair option, our weapon in the layer, ADS
 * held or still visible), and at the hip while our live shot owner fires it (item 10); Viewpoint's own exit
 * (isoReticleDone) still runs and restores what it changed. W10 item 16: both only while the next shot is really ours
 * (hides: ShotOwner.nextShotOwned, the admission of that shot, not the owner's global readiness) - a shot the owner
 * would give to Viewpoint keeps Viewpoint's ring and dot, since its spread decides that shot.
 * Its own member table: a changed gate turns only this hide off (one log line).
 */
public final class FpAimRing {
    static final String[][] MEMBERS = {
        {"viewpoint.CombatHooks", "skipIsoReticle", "(Lzombie/iso/sprite/IsoReticle;)Z", "static"},
    };

    private static boolean checked;
    private static volatile boolean ready;
    public static volatile long gates, hidden, errors;
    public static volatile String status = "unchecked";

    private FpAimRing() { }

    static synchronized boolean detect() {
        if (checked) {
            return ready && ViewpointBridge.ready();
        }
        if (!ViewpointBridge.ready() && !ViewpointBridge.detect()) {
            return false;
        }
        checked = true;
        try {
            Member gate = ViewpointBridge.resolve(MEMBERS[0], FpAimRing.class.getClassLoader());
            if (gate == null) {
                status = "member " + MEMBERS[0][0] + "." + MEMBERS[0][1] + " " + MEMBERS[0][2] + " missing or changed";
                System.out.println("[TWZB] Viewpoint aim ring: " + status + ", hide off (bridge stays on)");
                return false;
            }
            ready = true;
            status = "on";
        } catch (Throwable t) {
            status = "lookup failed: " + t;
            System.out.println("[TWZB] Viewpoint aim ring: " + status + ", hide off (bridge stays on)");
        }
        return ready;
    }

    /** Exit of CombatHooks.skipIsoReticle (main thread, the reticle's render): true skips the vanilla draw. */
    public static boolean skip(boolean skip) {
        gates++;
        if (skip || !ready && !detect()) {
            return skip;
        }
        try {
            IsoPlayer p = IsoPlayer.getInstance();
            if (ViewpointBridge.state() != 1 || !hides(p)) {
                return false;
            }
            hidden++;
            return true;
        } catch (Throwable t) {
            errors++;
            String message = "gate: " + t;
            if (!message.equals(status)) {
                status = message;
                System.out.println("[TWZB] Viewpoint aim ring " + message + ", the ring is drawn as before");
            }
            return skip;
        }
    }

    /**
     * FB4 item 10 ("remove the game's random spread"): firing from the hip (the game's aim state without the sights) the
     * ring is Viewpoint's spread bound - its crosshair dot wanders inside it and Viewpoint's shot would go there - but
     * our live owner fires our weapon along its bore with Tarkov ballistics (ShotOwner), so ring and dot only mislead:
     * both are skipped too (the same option; FpViewLatch.crosshair hides the dot).
     */
    static boolean hip(IsoPlayer p) {
        return FpAdsCamera.hideCrosshair && p != null && p.isAiming() && p.getPrimaryHandItem() != null && FpViewLatch.layer();
    }

    /**
     * W10 item 16 (the user's report: "a silent fallback to Viewpoint's mechanics - its ring and crosshair hidden, an
     * invisible random spread"): at the hip (hip) Viewpoint's ring and crosshair dot are hidden only while the next shot
     * of our weapon is ours (ShotOwner.nextShotOwned: the admission of that shot, refreshed each tick) - there they are
     * the only aim of a shot Viewpoint's own spread decides. In ADS (FpAdsCamera.aiming) the option alone hides them
     * (user's decision of 02.11.2026 on the players' "ADS in first person does not hide the crosshair"): the sight is
     * the aim. FpViewLatch.crosshair hides the dot by the same rule.
     */
    public static boolean hides(IsoPlayer p) {
        return FpAdsCamera.aiming(p) || hip(p) && ShotOwner.nextShotOwned();
    }

    public static String stats() {
        return "status=" + status + " gates=" + gates + " hidden=" + hidden + " errors=" + errors;
    }

    public static void reset() {
        gates = hidden = errors = 0;
    }

    @Patch(className = "viewpoint.CombatHooks", methodName = "skipIsoReticle")
    public static class Gate {
        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean skip) {
            skip = FpAimRing.skip(skip);
        }
    }
}
