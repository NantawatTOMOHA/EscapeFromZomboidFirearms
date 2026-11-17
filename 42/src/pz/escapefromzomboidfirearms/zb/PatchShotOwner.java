package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.AttackType;
import zombie.ai.states.SwipeStatePlayer;
import zombie.characters.IsoGameCharacter;
import zombie.inventory.types.HandWeapon;

/** Exact binding of the six guarded shot targets; never patch STT, PVADS or VTB. */
public class PatchShotOwner {
    @Patch(className="zombie.CombatManager", methodName="attackCollisionCheck", strictMatch=true)
    public static class Collision {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) IsoGameCharacter actor,@Patch.Argument(1) HandWeapon weapon,
                @Patch.Argument(2) SwipeStatePlayer swipe,@Patch.Argument(3) AttackType type,
                @Patch.Local("twShotScope") ShotOwner.Scope scope) {
            ShotOwner.hook(ShotOwner.Hook.Collision);
            try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.SHOT_OWNER)) { scope=ShotOwner.enter(actor,weapon); }
        }
        @Patch.OnExit(onThrowable=Throwable.class)
        public static void exit(@Patch.Local("twShotScope") ShotOwner.Scope scope,@Patch.Thrown Throwable thrown) {
            ShotOwner.hook(ShotOwner.Hook.CollisionExit);
            try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.SHOT_OWNER)) { ShotOwner.exit(scope,thrown); }
        }
    }
    @Patch(className="viewpoint.gunplay.Gunplay", methodName="shotStarting", strictMatch=true)
    public static class Starting {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.Argument(0) IsoGameCharacter actor,@Patch.Argument(1) HandWeapon weapon) {
            ShotOwner.hook(ShotOwner.Hook.Starting);
            try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.SHOT_OWNER)) { return ShotOwner.shotStarting(actor,weapon); }
        }
    }
    @Patch(className="viewpoint.gunplay.Gunplay", methodName="hitListMade", strictMatch=true)
    public static class Made {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.Argument(0) IsoGameCharacter actor) {
            ShotOwner.hook(ShotOwner.Hook.Made);return ShotOwner.suppress(actor,null);
        }
    }
    @Patch(className="zombie.CombatManager", methodName="calculateHitInfoList", strictMatch=true)
    public static class HitList {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.Argument(0) IsoGameCharacter actor) {
            ShotOwner.hook(ShotOwner.Hook.HitList);return ShotOwner.hitList(actor);
        }
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) IsoGameCharacter actor) { ShotOwner.hitListMade(actor); }
    }
    /** MP live: PZ's own target selection of a claimed round rides its native miss packet, so the
     * server can resolve the round by PZ's path if it refuses the intent or the intent is lost. */
    @Patch(className="zombie.network.packets.hit.PlayerHitPacket", methodName="set", strictMatch=true)
    public static class MissPacket {
        @Patch.OnEnter
        public static void enter(@Patch.This Object packet,@Patch.Argument(0) zombie.characters.IsoPlayer actor,
                @Patch.Argument(1) HandWeapon weapon,
                @Patch.Argument(value=2,readOnly=false) java.util.List<zombie.CombatManager.TargetHitList> targets) {
            targets=ShotOwner.nativePacket(packet,actor,weapon,targets);
        }
        @Patch.OnExit(onThrowable=Throwable.class)
        public static void exit(@Patch.This Object packet,@Patch.Argument(0) zombie.characters.IsoPlayer actor,
                @Patch.Argument(1) HandWeapon weapon,@Patch.Thrown Throwable error) {
            ShotOwner.nativePacketSet(packet,actor,weapon,error);
        }
    }
    @Patch(className="zombie.network.packets.hit.PlayerHitPacket", methodName="write", strictMatch=true)
    public static class NativeWritten {
        @Patch.OnExit(onThrowable=Throwable.class)
        public static void exit(@Patch.This Object packet,
                @Patch.Argument(0) zombie.core.network.ByteBufferWriter writer,@Patch.Thrown Throwable error) {
            ShotOwner.nativePacketWritten(packet,error);
        }
    }
    @Patch(className="zombie.CombatManager", methodName="fireWeapon", strictMatch=true)
    public static class Fire {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.Argument(0) HandWeapon weapon,@Patch.Argument(1) IsoGameCharacter actor) {
            ShotOwner.hook(ShotOwner.Hook.Fire);
            try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.SHOT_OWNER)) { return ShotOwner.fire(weapon,actor); }
        }
    }
    @Patch(className="zombie.CombatManager", methodName="CheckObjectHit", strictMatch=true)
    public static class ObjectHit {
        @Patch.OnEnter(skipOn=true)
        public static boolean enter(@Patch.This Object manager,@Patch.Argument(0) IsoGameCharacter actor,
                @Patch.Argument(1) HandWeapon weapon) {
            ShotOwner.hook(ShotOwner.Hook.ObjectHit);return ShotOwner.objectHit(manager,actor,weapon);
        }
    }
    @Patch(className="zombie.iso.IsoWorld", methodName="render", strictMatch=true)
    public static class DrawnFrame {
        @Patch.OnExit
        public static void exit() {
            ShotOwner.hook(ShotOwner.Hook.DrawnFrame);
            try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.SHOT_OWNER)) { ShotOwner.afterWorld(); }
        }
    }
}
