package pz.escapefromzomboidfirearms.zb;

import java.nio.ByteBuffer;
import zombie.CombatManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.IsoZombie;
import zombie.characters.animals.IsoAnimal;
import zombie.combat.ShotDirection;
import zombie.core.physics.RagdollBodyPart;
import zombie.core.random.Rand;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoWindow;
import zombie.network.GameClient;
import zombie.scripting.objects.AmmoType;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;
import pz.escapefromzomboidfirearms.zb.ShotSnapshot.*;

/** W11 hostauth, the shooter's damage executor on a server without the mod's Java (docs/pending/HOST_CLIENT_AUTHORITY_RU.md,
 * variant B). The client flies its claimed round as in single player; each body contact runs here what ShotDamagePz.humanoid
 * runs on the dedicated server - the detached weapon copy scaled by the contact, the critical roll, calculateShotDirection,
 * processTargetedHit, applyRangeHitLocationDamage on PZ's damage roll, the range multiplier and the shooter's two-hand
 * rule - then the target's native Hit on this client, as PZ's own client shows a hit at once: its reaction, and for a
 * zombie this client owns, its health (a remote zombie's damage is ignored by PZ here, and so is a remote player's wound).
 * The damage that Hit returns (PZ's processHitDamage) is what the server's Lua applies with Hit(..., true), as vanilla's
 * WeaponHit does, and what the relayed hit fields carry; a player's wound is the server's DamageFromWeapon in the exact zone
 * from the copy's damage range. World contacts (the windows and doors ShotDamagePz damages, a vehicle's pane or body) are
 * only reported: the server applies them to its own objects. Every contact becomes a report row (ShotClientAuthority).
 */
public final class ShotClientDamage implements ShotDamage.Native {
    private final ShotContactsPz world;
    /** One vehicle body hit per shot and vehicle, as ShotDamagePz.vehicleHit (its pellets do not each hit a vehicle). */
    private final java.util.WeakHashMap<ShotSnapshot,java.util.Set<String>> vehicles=new java.util.WeakHashMap<>();
    public ShotClientDamage(ShotContactsPz world) { this.world=java.util.Objects.requireNonNull(world); }
    @Override public boolean ready() { return world.ready(); }
    @Override public ShotDamage.Prepared prepare(ShotSnapshot shot,Contact contact) throws Exception {
        if (!ready() || !GameClient.client || contact.kind()==TargetKind.UNLOADED) return null;
        // W11 corpses, as ShotDamagePz.prepare: a dead body is a contact of the round, never a target to wound.
        if (contact.kind()==TargetKind.CHARACTER && ShotPenetration.corpse(contact)) return corpseBlood(contact);
        try {
            IsoGameCharacter attacker=world.shooter.apply(shot);
            Object target=world.targets.resolve(contact.key().target());
            if (!(attacker instanceof IsoPlayer shooter)) return null;
            if (contact.kind()==TargetKind.CHARACTER) {
                if (!(target instanceof IsoGameCharacter character) || character.getSquare()==null
                        || !ShotDamagePz.validZones(contact.ragdollPart(),contact.bodyPart()) || !allowed(shooter,character)) return null;
                HandWeapon view=weapon(shot,contact);
                return () -> {
                    if (!ready() || !GameClient.client || world.targets.resolve(contact.key().target())!=character
                            || character.getSquare()==null || !allowed(shooter,character)) return false;
                    return hit(shot,contact,shooter,character,view);
                };
            }
            if (contact.kind()==TargetKind.VEHICLE) {
                BaseVehicle vehicle=target instanceof VehiclePart part?part.getVehicle():target instanceof BaseVehicle v?v:null;
                if (vehicle==null || vehicle.getSquare()==null) return null;
                if (target instanceof VehiclePart part) {
                    // The pane the round crossed (ShotVehicleGlass.breakPane on the server), as ShotDamagePz.
                    if (part.getWindow()==null || ShotWorldMaterials.preset(contact.material())==null) return null;
                    return () -> report(shot,contact,"vehicle-glass",vehicle.getId(),part.getId(),vehicle.getX(),vehicle.getY(),vehicle.getZ(),0,null,0);
                }
                if (!contact.material().equals("vehicle-bounds")) return null;
                HandWeapon view=weapon(shot,contact);
                return () -> {
                    if (!vehicles.computeIfAbsent(shot,s->new java.util.HashSet<>()).add(world.targets.identity(vehicle))) return false;
                    float damage=nativeDamage(view,shooter);
                    return report(shot,contact,"vehicle",vehicle.getId(),"",vehicle.getX(),vehicle.getY(),vehicle.getZ(),damage,view,0);
                };
            }
            if (!(target instanceof IsoObject object) || object.getSquare()==null || !eligibleWorld(object,contact)) return null;
            HandWeapon view=weapon(shot,contact);
            var square=object.getSquare();
            String kind=object instanceof IsoWindow?"window":"door";
            int state=object instanceof IsoDoor door && door.IsOpen()?1:0;
            String material=ShotWorldMaterials.nativeMaterial(contact.material());
            String sprite=object.getSpriteName();
            return () -> report(shot,contact,kind,-1,(sprite==null?"":sprite)+"|"+material,
                    square.getX(),square.getY(),square.getZ(),0,view,state);
        } catch (Exception | LinkageError ex) { world.guard.fail("client damage preparation: "+ex,ex);throw ex; }
    }
    /** A target vanilla's ranged attack accepts (zombie, animal, player) that may be hurt: alive, not the shooter, not
     * protected; a player only where PZ's own client rule allows PvP (CombatManager.checkPVP, as ShotDamagePz without
     * server rules). The server checks PvP again with its own options at every hit. */
    private static boolean allowed(IsoGameCharacter attacker,IsoGameCharacter target) {
        if (attacker==target || target.isDead() || target.isGodMod() || target.isInvulnerable()) return false;
        if (target instanceof IsoAnimal || target instanceof IsoZombie) return true;
        return target instanceof IsoPlayer && CombatManager.checkPVP(attacker,target,false);
    }
    /** ShotDamagePz.eligibleWorld: a window's pane or barricade, a door by its drawn state; a coarse envelope is an
     * obstruction, not proof of a destructible hit. */
    static boolean eligibleWorld(IsoObject object,Contact contact) {
        String material=ShotWorldMaterials.nativeMaterial(contact.material());
        if (object instanceof IsoWindow window) {
            if (material.equals("barricade")) return window.isBarricaded();
            return material.equals("glass") && !window.IsOpen() && !window.isSmashed()
                    && !window.isDestroyed() && !window.isBarricaded();
        }
        return object instanceof IsoDoor door && material.equals("door")
                && contact.proof().equals("PZ.door-state/"+(door.IsOpen()?"rotated-leaf":"closed-face"));
    }
    private boolean report(ShotSnapshot shot,Contact contact,String kind,int id,String object,double x,double y,double z,
            float damage,HandWeapon view,int state) {
        var point=shot.pose().muzzlePz().offset(contact.point());
        ShotClientAuthority.contact(shot,new ShotClientAuthority.Contact(kind,id,point.x(),point.y(),point.z(),x,y,z,
                contact.ragdollPart(),contact.bodyPart(),damage,damage,1,false,contact.key().pellet().index(),contact.key().contactOrdinal(),
                null,object,view==null?0:view.getMinDamage(),view==null?0:view.getMaxDamage(),state));
        return true;
    }
    private boolean hit(ShotSnapshot shot,Contact contact,IsoPlayer attacker,IsoGameCharacter target,HandWeapon view) throws Exception {
        try {
            int targetId=target.getOnlineID();
            double tx=target.getX(),ty=target.getY(),tz=target.getZ();
            boolean animal=target instanceof IsoAnimal;
            var body=animal?null:target.getBodyDamage();
            // A player's exact zone, as ShotDamagePz.humanoid scopes it (the server wounds that zone; PZ skips a remote
            // player's DamageFromWeapon on this client).
            try (ShotDamageScope scope=body==null?null:new ShotDamageScope(body,view,contact.bodyPart())) {
                CombatManager manager=CombatManager.getInstance();
                // Vanilla rolls the critical hit of every hit (CombatManager.processHit), as ShotDamagePz does on a server.
                boolean critical=Rand.Next(100)<attacker.calculateCritChance(target);
                attacker.setCriticalHit(critical);
                RagdollBodyPart part=RagdollBodyPart.values()[contact.ragdollPart()];
                ShotDirection direction=(ShotDirection)world.guard.method("zombie.CombatManager","calculateShotDirection")
                        .invoke(manager,attacker,target);
                world.guard.method("zombie.CombatManager","processTargetedHit").invoke(manager,view,attacker,target,part,direction);
                float damage=(float)world.guard.method("zombie.CombatManager","applyRangeHitLocationDamage")
                        .invoke(manager,target,view,contact.ragdollPart(),nativeDamage(view,attacker));
                float range=ShotDamagePz.rangeMultiplier(view);
                float applied;
                try (ShotHands hands=ShotDamagePz.heldInBothHands(attacker,view)?new ShotHands(view):null) {
                    applied=target.Hit(view,attacker,damage,false,range);
                }
                // The relayed hit names the shooter's real weapon (other clients resolve it in the shooter's hands).
                HandWeapon held=attacker.getPrimaryHandItem() instanceof HandWeapon w && w.getID()==shot.weapon().id()?w:null;
                String payload=held==null?null:ShotHitWire.hit(attacker,held,target,targetId,
                        ShotHitWire.fields(applied,range,target,contact.bodyPart()));
                var point=shot.pose().muzzlePz().offset(contact.point());
                ShotClientAuthority.contact(shot,new ShotClientAuthority.Contact(animal?"animal":target instanceof IsoZombie?"zombie":"player",
                        targetId,point.x(),point.y(),point.z(),tx,ty,tz,contact.ragdollPart(),contact.bodyPart(),damage,applied,range,
                        critical,contact.key().pellet().index(),contact.key().contactOrdinal(),payload,"",view.getMinDamage(),view.getMaxDamage(),0));
                return true;
            }
        } catch (Exception | LinkageError ex) { world.guard.fail("client impact: "+ex,ex);throw ex; }
    }
    /** ShotDamagePz.corpseBlood on the shooter's client: PZ's own blood splat on the corpse's square, and nothing else - the
     * action returns false, so no report row (no damage, no XP on the server), nothing relayed. */
    private ShotDamage.Prepared corpseBlood(Contact contact) {
        Object target=world.targets.resolve(contact.key().target());
        IsoGridSquare square=target instanceof IsoGameCharacter character?character.getCurrentSquare()
                :target instanceof IsoDeadBody body?body.getSquare():null;
        if (square==null) return null;
        return () -> {
            if (!ready() || world.targets.resolve(contact.key().target())!=target) return false;
            square.splatBlood(2,0.5f);
            return false;
        };
    }
    /** PZ's firearm damage roll (ShotDamagePz.nativeDamage). */
    static float nativeDamage(HandWeapon view,IsoGameCharacter attacker) {
        float min=view.getMinDamage(),max=view.getMaxDamage();
        return (min==max?min:Rand.Next(min,max))*view.getDamageMod(attacker);
    }
    /** The detached weapon view of ShotDamagePz.weapon: frozen bytes, the frozen ammo enum, each pellet a full hit of
     * the weapon scaled only by the Tarkov speed falloff of this contact. */
    private static HandWeapon weapon(ShotSnapshot shot,Contact contact) throws Exception {
        ByteBuffer buffer=ByteBuffer.wrap(shot.nativeWeapon().bytes());
        InventoryItem item=InventoryItem.loadItem(buffer,shot.nativeWeapon().worldVersion());
        if (!(item instanceof HandWeapon view) || !view.getFullType().equals(shot.weapon().fullType())
                || view.getContainer()!=null || view.getWorldItem()!=null || !view.isRanged()) {
            throw new IllegalStateException("Invalid unequipped native damage view");
        }
        var ammo=AmmoType.getByItemKey(shot.nativeAmmoType()).orElseThrow(
                ()->new IllegalStateException("Frozen ammo type unavailable: "+shot.nativeAmmoType()));
        view.setAmmoType(ammo);
        float min=view.getMinDamage(),max=view.getMaxDamage();
        if (!Float.isFinite(min) || !Float.isFinite(max) || min<0 || max<min) throw new IllegalStateException("Native damage stats");
        float remaining=contact.effect()==null?1:contact.effect().damageScale();
        view.setMinDamage(min*remaining);view.setMaxDamage(max*remaining);
        return view;
    }
}
