package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Member;

import me.zed_0xff.zombie_buddy.Patch;

/**
 * W11 camera2 (items 2 and 17, 29.10.2026): Viewpoint's crosshair pick never reads our first-person weapon.
 *
 * Viewpoint's CrosshairAim turns the depth MousePick.read samples at the screen centre into the distance it latches on a
 * 1P<->3P switch (and on a vehicle enter/exit) and then turns Look.yaw toward. With the weapon (SELF) in that depth the
 * point was 0.05-0.6 m away and the view spun round. Here: the read's exit draws the SELF that FpViewLatch.gbufferExit
 * left pending (an Iris frame's too: FpViewLatch.irisModelsEnter), the entry of IndirectPass.draw - the first reader of
 * the gbuffer after the read - draws it if the read's exit did not, and before CrosshairAim.snapshot an aim hit read
 * from a frame before the last vehicle enter/exit is dropped (ViewpointBridge.PickFrames). Without the mod's Lua every
 * advice returns at once; with a changed target (MEMBERS) all of them do, and SELF is drawn before the pick as before.
 */
public final class VpCrosshairPick {
    /** Guarded members: the advice targets. A mismatch turns this class off (FpViewLatch then draws SELF at once). */
    static final String[][] MEMBERS = {
        {"viewpoint.render.MousePick", "read", "(Lviewpoint/render/FrameContext;ILorg/joml/Matrix4f;)V", "static"},
        {"viewpoint.render.IndirectPass", "draw", "(Lviewpoint/render/FrameContext;ZZ)V|(Lviewpoint/render/FrameContext;Z)V", ""},
        {"viewpoint.input.CrosshairAim", "snapshot", "(Lviewpoint/core/Frame;Lzombie/characters/IsoGameCharacter;Lviewpoint/render/FarRelief$Lift;)V", "static"},
        {"viewpoint.render.IrisGeometry", "models", "(Lviewpoint/render/IrisPipeline;Ljava/util/Map;Lviewpoint/render/FrameContext;Lviewpoint/render/ShadowPass;Lviewpoint/render/ModelPass;)V", ""},
    };

    private static boolean checked, on;

    private VpCrosshairPick() {
    }

    /** All advice targets as this code knows them (once; a mismatch is named in the log and turns this class off). */
    static synchronized boolean on() {
        if (checked) {
            return on;
        }
        checked = true;
        ClassLoader cl = ViewpointBridge.class.getClassLoader();
        for (String[] row : MEMBERS) {
            Member m = ViewpointBridge.resolve(row, cl);
            if (m == null) {
                System.out.println("[TWZB] Viewpoint crosshair pick: member " + row[0] + "." + row[1] + " " + row[2]
                        + " missing or changed, the weapon is drawn before the pick as before");
                return false;
            }
        }
        on = true;
        return true;
    }

    public static void readEnter(Object frame) {
        if (on()) {
            ViewpointBridge.pickEnter(frame);
        }
    }

    public static void readExit(Object frame) {
        if (on()) {
            ViewpointBridge.pickExit(frame);
            FpViewLatch.pickExit(frame);
        }
    }

    public static void afterPick(Object frame) {
        if (on()) {
            FpViewLatch.selfFallback(frame);
        }
    }

    public static void aimSnapshot(Object frame, Object character) {
        if (on()) {
            ViewpointBridge.aimSnapshot(frame, character);
        }
    }

    public static void irisModels(Object geometry, Object pipeline, Object values, Object frame, Object shadows, Object pass) {
        if (on()) {
            FpViewLatch.irisModelsEnter(geometry, pipeline, values, frame, shadows, pass);
        }
    }

    // ------------------------------------------------------------------ advice (inlined: public members only)

    @Patch(className = "viewpoint.render.MousePick", methodName = "read")
    public static class Pick {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object frame) {
            if (!ModLua.present()) return; // a server without the mod: nothing of ours is drawn (ModLua)
            VpCrosshairPick.readEnter(frame);
        }

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) Object frame) {
            if (!ModLua.present()) return;
            VpCrosshairPick.readExit(frame);
        }
    }

    @Patch(className = "viewpoint.render.IndirectPass", methodName = "draw")
    public static class AfterPick {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object frame) {
            if (!ModLua.present()) return;
            VpCrosshairPick.afterPick(frame);
        }
    }

    @Patch(className = "viewpoint.input.CrosshairAim", methodName = "snapshot")
    public static class AimSnapshot {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object frame, @Patch.Argument(1) Object character) {
            if (!ModLua.present()) return;
            VpCrosshairPick.aimSnapshot(frame, character);
        }
    }

    @Patch(className = "viewpoint.render.IrisGeometry", methodName = "models")
    public static class IrisModels {
        @Patch.OnEnter
        public static void enter(@Patch.This Object geometry, @Patch.Argument(0) Object pipeline, @Patch.Argument(1) Object values,
                @Patch.Argument(2) Object frame, @Patch.Argument(3) Object shadows, @Patch.Argument(4) Object pass) {
            if (!ModLua.present()) return;
            VpCrosshairPick.irisModels(geometry, pipeline, values, frame, shadows, pass);
        }
    }
}
