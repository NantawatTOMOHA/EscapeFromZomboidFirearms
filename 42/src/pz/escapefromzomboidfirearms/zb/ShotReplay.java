package pz.escapefromzomboidfirearms.zb;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import zombie.CombatManager;
import zombie.characters.IsoGameCharacter;
import zombie.core.network.ByteBufferReader;
import zombie.network.GameClient;
import zombie.network.packets.hit.PlayerHitAnimalPacket;
import zombie.network.packets.hit.PlayerHit;
import zombie.network.packets.hit.PlayerHitPlayerPacket;
import zombie.network.packets.hit.PlayerHitZombiePacket;
import zombie.popman.animal.AnimalInstanceManager;

/** MP client: one server-applied hit of a live shot, as PZ's own relayed hit packet would show
 * it. The payload is the native packet's write() made on the server after its damage; this
 * client parses it with the packet's own parse() and runs what a relayed hit runs (target
 * state, processHits, fall, react), except attack(): the round, flash and tracer came with
 * the shooter's native shot, and attack() would start another swing of the shooter. Damage
 * of remote zombies is ignored by PZ on clients; a player's wound is the server's (syncDamage).
 */
public final class ShotReplay {
    private static long applied, inconsistent, superseded, errors;
    private static String last = "none";
    private ShotReplay() { }

    public static synchronized boolean apply(String payload) {
        try {
            if (!GameClient.client || payload == null || payload.length() > 16384) return false;
            byte[] bytes = Base64.getDecoder().decode(payload);
            if (bytes.length < 4) return false;
            short id = ByteBuffer.wrap(bytes, 1, 2).getShort();
            boolean animal = bytes[0] == 2;
            PlayerHit packet = switch (bytes[0]) {
                case 0 -> new PlayerHitZombiePacket();
                case 1 -> new PlayerHitPlayerPacket();
                // An animal (W10 srvlive): vanilla's own relayed packet; its health and death come by PZ's animal sync.
                case 2 -> new PlayerHitAnimalPacket();
                default -> null;
            };
            if (packet == null) return false;
            packet.parse(new ByteBufferReader(ByteBuffer.wrap(Arrays.copyOfRange(bytes, 3, bytes.length))), GameClient.connection);
            IsoGameCharacter struck=packet.getTarget() instanceof IsoGameCharacter character?character:null;
            if (!packet.isConsistent(GameClient.connection) || struck == null) {
                // The hit killed the zombie: PZ's own death sync (die()) already shows it here.
                var zombie = bytes[0] == 0 && id >= 0 ? GameClient.IDToZombieMap.get(id) : null;
                if (bytes[0] == 0 && id >= 0 && (zombie == null || zombie.isDead())) { superseded++; last = "superseded:zombie-dead id=" + id; return false; }
                var beast = animal ? AnimalInstanceManager.getInstance().get(id) : null;
                if (animal && (beast == null || beast.isDead())) { superseded++; last = "superseded:animal-dead id=" + id; return false; }
                inconsistent++; last = "inconsistent id=" + id; return false;
            }
            // The animal packet carries no character state (its target is an AnimalID): process() and react() only.
            /** The packet's target field (Zombie or Player); its own process() applies the reported state.
             * In 42.22 that state is delegated through native preProcess/postProcess. Do not call attack(). */
            PlayerHit.resetAttackState();
            CombatManager.getInstance().clearDotList();
            packet.preProcess();
            packet.process();
            packet.postProcess();
            packet.react();
            applied++;
            last = "target=" + struck.getOnlineID() + " reaction=" + struck.getHitReaction()
                    + " dead=" + struck.isDead();
            return true;
        } catch (Throwable t) { errors++; last = "error:" + t.getClass().getSimpleName(); return false; }
    }
    public static synchronized String info() {
        return "replayed=" + applied + " superseded=" + superseded + " inconsistent=" + inconsistent + " errors=" + errors + " last={" + last + "}";
    }
}
