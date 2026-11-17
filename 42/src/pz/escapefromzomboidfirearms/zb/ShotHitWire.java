package pz.escapefromzomboidfirearms.zb;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import zombie.CombatManager;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.core.network.ByteBufferWriter;
import zombie.inventory.types.HandWeapon;
import zombie.network.fields.hit.Hit;
import zombie.network.packets.hit.PlayerHitAnimalPacket;
import zombie.network.packets.hit.PlayerHitPlayerPacket;
import zombie.network.packets.hit.PlayerHitZombiePacket;

/** The wire of a live shot's results that the other clients replay (ShotOwner.netHit -> ShotReplay, netMark ->
 * ImpactMarks.remote). Moved byte for byte from the server module (W11 hostauth, docs/pending/HOST_CLIENT_AUTHORITY_RU.md):
 * the dedicated server's flight encodes with it (ShotServerReplication, ShotServerLive.mark), and so does the shooter's
 * client on a server without the mod's Java, whose server Lua relays the payloads it accepted.
 */
public final class ShotHitWire {
    public static final int ZOMBIE=0, PLAYER=1, ANIMAL=2, MAX_BYTES=8192;
    private ShotHitWire() { }

    /** The native hit fields of one contact: the damage, PZ's range multiplier, the target's hit force and direction
     * after its Hit, the head (Head, Neck) or legs part. */
    public static Hit fields(float applied,float range,IsoGameCharacter target,int bodyPart) {
        boolean head=bodyPart==BodyPartType.ToIndex(BodyPartType.Head) || bodyPart==BodyPartType.ToIndex(BodyPartType.Neck);
        boolean legs=bodyPart>=BodyPartType.ToIndex(BodyPartType.UpperLeg_L);
        // 42.22 carries Hit's applied damage; its native replay already uses range multiplier 1.
        Hit hit=new Hit();
        hit.set(target.getHitForce(),target.getHitDir().x,target.getHitDir().y,
                new CombatManager.HitData((byte)(head?1:0),!head && legs,false,false,true),false,applied);
        return hit;
    }
    /** Payload: kind byte (0 zombie, 1 player, 2 animal) + the target's online ID before the hit (short) + the native
     * packet's write(), Base64. Null for any other target. An animal (an IsoPlayer in B42) goes as vanilla's
     * PlayerHitAnimalPacket. */
    public static String hit(IsoPlayer shooter,HandWeapon weapon,IsoGameCharacter target,int targetId,Hit hit) {
        ByteBuffer buffer=ByteBuffer.allocate(MAX_BYTES);
        buffer.put((byte)(target instanceof IsoAnimal?ANIMAL:target instanceof IsoZombie?ZOMBIE:PLAYER));
        buffer.putShort((short)targetId);
        ByteBufferWriter writer=new ByteBufferWriter(buffer);
        if (target instanceof IsoAnimal animal) {
            PlayerHitAnimalPacket packet=new PlayerHitAnimalPacket();
            packet.set(shooter,weapon,new CombatManager.TargetHitList(animal,List.of(hit)),List.of());
            packet.write(writer);
        } else if (target instanceof IsoZombie zombie) {
            PlayerHitZombiePacket packet=new PlayerHitZombiePacket();
            packet.set(shooter,weapon,new CombatManager.TargetHitList(zombie,List.of(hit)),List.of());
            packet.write(writer);
        } else if (target instanceof IsoPlayer victim) {
            PlayerHitPlayerPacket packet=new PlayerHitPlayerPacket();
            packet.set(shooter,weapon,new CombatManager.TargetHitList(victim,List.of(hit)),List.of());
            packet.write(writer);
        } else return null;
        return Base64.getEncoder().encodeToString(Arrays.copyOf(buffer.array(),buffer.position()));
    }
    /** One world/vehicle contact of a flight for every client's ImpactMarks.remote: the shot's muzzle, the contact in
     * its metric frame, its outcome and the sprite of the struck object. Null for a character or unloaded contact. */
    public static String mark(ShotSnapshot shot,ShotPenetration.Diagnostic event,Object struck) throws java.io.IOException {
        var c=event.contact();
        if (c.kind()==ShotSnapshot.TargetKind.CHARACTER || c.kind()==ShotSnapshot.TargetKind.UNLOADED) return null;
        var buffer=new java.io.ByteArrayOutputStream();
        try (var out=new java.io.DataOutputStream(buffer)) {
            var m=shot.pose().muzzlePz();out.writeDouble(m.x());out.writeDouble(m.y());out.writeDouble(m.z());
            out.writeByte(c.kind().ordinal());
            for (var v:new TarkovBallistics.Vec3[]{c.point(),c.normal(),event.incomingDirection()}) {
                out.writeFloat(v.x());out.writeFloat(v.y());out.writeFloat(v.z());
            }
            out.writeBoolean(c.forwardHit());out.writeUTF(c.material());out.writeUTF(event.outcome());
            var k=c.key();var id=k.pellet().shot();
            out.writeUTF(id.shooter()+"/"+id.epochText()+"/"+id.sequenceText()+"/"+k.pellet().index()+"/"+k.contactOrdinal());
            out.writeLong(shot.seed()^((long)k.pellet().index()<<32)^k.contactOrdinal());
            String sprite=struck instanceof zombie.iso.IsoObject o?o.getSpriteName():null;
            out.writeUTF(sprite==null?"":sprite);
        }
        return Base64.getEncoder().encodeToString(buffer.toByteArray());
    }
}
