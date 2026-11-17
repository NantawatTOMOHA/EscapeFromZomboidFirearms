package pz.escapefromzomboidfirearms.zb;

import java.nio.ByteBuffer;
import zombie.CombatManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.characters.BodyDamage.BodyPartType;
import zombie.combat.ShotDirection;
import zombie.core.physics.RagdollBodyPart;
import zombie.core.random.Rand;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoObject;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoDeadBody;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoWindow;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.scripting.objects.AmmoType;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;
import pz.escapefromzomboidfirearms.zb.ShotSnapshot.*;

/** Native PZ damage view. No direct health, wounds, armour, ammo, network-hit
 * packet or onShoot writes/calls. The coordinator must supply server authority
 * through ShotDamage; ordinary GameClient predictions are never damaging here.
 */
public final class ShotDamagePz implements ShotDamage.Native {
    private final ShotContactsPz world;
    private final ShotBleedingPz bleeding;
    // A weak root key lives for the owner's shot lifetime without retaining
    // retired snapshots/vehicles. Record attempts BEFORE native mutation.
    private final VehicleAttempts vehicleAttempts=new VehicleAttempts();
    static final class VehicleAttempts {
        final java.util.WeakHashMap<ShotSnapshot,java.util.Set<String>> roots=new java.util.WeakHashMap<>();
        synchronized boolean claim(ShotSnapshot shot,int pellet,String vehicle) {
            return roots.computeIfAbsent(shot,s->new java.util.HashSet<>()).add(pellet+"/"+vehicle);
        }
    }
    /** Who may be hurt. PZ checks PvP/safety/non-PvP zones/factions on the attacking client only
     * (CombatManager.checkPVP returns IsoPlayer.getCoopPVP() on a server), so a dedicated server
     * supplies its own server-side rule set (ShotServerPvp). */
    public interface Rules { boolean allowed(IsoGameCharacter attacker,IsoGameCharacter target); }
    /** Called after one accepted native character Hit; a dedicated server sends the native hit
     * fields to every client from here (ShotServerReplication). targetId is the target's online ID
     * before the hit: a zombie the hit killed has none any more. */
    public interface Replication {
        void hit(ShotSnapshot shot,Contact contact,IsoGameCharacter attacker,IsoGameCharacter target,int targetId,
                HandWeapon view,float damage,float range) throws Exception;
    }
    private final Rules rules;
    private final Replication replication;
    /** This adapter applies firearm hits: a confirmed character hit raises vanilla's OnWeaponHitXp (ShotXp).
     * Ordnance adapters (hand-grenade fragments) leave it off: vanilla grants nothing for an explosion. */
    private final boolean grantsAimingXp;
    public ShotDamagePz(ShotContactsPz world) {
        this.world=java.util.Objects.requireNonNull(world);bleeding=null;
        grantsAimingXp=false;rules=null;replication=null;
    }
    public ShotDamagePz(ShotContactsPz world,TarkovPenetrationData data) { this(world,data,false,null,null); }
    public ShotDamagePz(ShotContactsPz world,TarkovPenetrationData data,boolean grantsAimingXp) {
        this(world,data,grantsAimingXp,null,null);
    }
    public ShotDamagePz(ShotContactsPz world,TarkovPenetrationData data,Rules rules,Replication replication) {
        this(world,data,true,rules,replication);
    }
    public ShotDamagePz(ShotContactsPz world,TarkovPenetrationData data,boolean grantsAimingXp,Rules rules,Replication replication) {
        this.world=java.util.Objects.requireNonNull(world);bleeding=new ShotBleedingPz(data,world::fail);
        this.grantsAimingXp=grantsAimingXp;this.rules=rules;this.replication=replication;
    }
    @Override public boolean ready() { return world.ready() && (bleeding==null || bleeding.ready()); }
    @Override public ShotDamage.Prepared prepare(ShotSnapshot shot,Contact contact) throws Exception {
        if (!ready() || GameClient.client || contact.kind()==TargetKind.UNLOADED) return null;
        // W11 corpses: a dead body is a contact of the round, never a target to wound. Give it PZ's own blood and
        // stop there: no wound, no bleeding, no XP; the dedicated server applies neither blood nor damage.
        if (contact.kind()==TargetKind.CHARACTER && ShotPenetration.corpse(contact)) return corpseBlood(contact);
        try {
            IsoGameCharacter attacker=world.shooter.apply(shot);
            Object target=world.targets.resolve(contact.key().target());
            if (contact.kind()==TargetKind.VEHICLE && target instanceof VehiclePart part) {
                BaseVehicle vehicle=part.getVehicle();
                if (attacker==null || vehicle==null || ShotWorldMaterials.preset(contact.material())==null
                        || !vehicleCurrent(vehicle,part)) return null;
                // The pane the round crossed breaks (ShotVehicleGlass), not a random part of that side (processRangeHit).
                if (part.getWindow()!=null) return () -> ready() && !GameClient.client
                        && world.targets.resolve(contact.key().target())==part && vehicleCurrent(vehicle,part) && ShotVehicleGlass.breakPane(part);
                HandWeapon view=weapon(shot,contact);
                return () -> {
                    if (!ready() || GameClient.client || world.targets.resolve(contact.key().target())!=part
                            || !vehicleCurrent(vehicle,part)) return false;
                    return vehicleHit(shot,contact,attacker,vehicle,view);
                };
            }
            if (attacker==null || !(target instanceof IsoObject object) || object.getSquare()==null) return null;
            if (contact.kind()==TargetKind.CHARACTER) {
                if (!(object instanceof IsoGameCharacter character) || !validZones(contact.ragdollPart(),contact.bodyPart())
                        || !allowed(attacker,character)) return null;
            } else if (contact.kind()==TargetKind.VEHICLE) {
                if (!(object instanceof BaseVehicle) || !contact.material().equals("vehicle-bounds")) return null;
            } else if (!eligibleWorld(object,contact)) {
                // A coarse envelope is an obstruction, not proof of a destructible hit.
                return null;
            }
            HandWeapon view=weapon(shot,contact);
            return () -> {
                if (!ready() || GameClient.client || world.targets.resolve(contact.key().target())!=object || object.getSquare()==null) return false;
                if (object instanceof IsoGameCharacter character) {
                    if (!allowed(attacker,character) || !object.getCell().getObjectList().contains(character)) return false;
                    return humanoid(shot,contact,attacker,character,view);
                }
                if (object instanceof BaseVehicle vehicle) {
                    if (!object.getCell().getVehicles().contains(vehicle)) return false;
                    return vehicleHit(shot,contact,attacker,vehicle,view);
                }
                if (!eligibleWorld(object,contact) || !object.getSquare().getObjects().contains(object)
                        && !object.getSquare().getSpecialObjects().contains(object)) return false;
                // One native operation handles its barricade/pane/door state. No extra smash.
                object.WeaponHit(attacker,view);return true;
            };
        } catch (Exception | LinkageError ex) { world.guard.fail("damage preparation: "+ex,ex);throw ex; }
    }
    /**
     * W11 corpses: the only native effect of a round in a dead body is PZ's own blood splat on the corpse's square.
     * The prepared action always returns false, so no impact is counted and no Aiming XP (ShotXp) or wound path runs.
     * A dedicated server gets nothing: it may stop the round but never splats blood from a corpse and never damages it.
     */
    private ShotDamage.Prepared corpseBlood(Contact contact) {
        if (GameServer.server) return null;
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
    private boolean vehicleCurrent(BaseVehicle vehicle,VehiclePart part) {
        if (vehicle.getSquare()==null || !vehicle.getCell().getVehicles().contains(vehicle)
                || part.getVehicle()!=vehicle || !vehicle.getParts().contains(part) || !ShotContactsPz.vehicleInstalled(part)
                || part.getCondition()<=0 || part.getDoor()!=null && part.getDoor().isOpen()) return false;
        var window=part.getWindow();var parent=part.getParent();
        return window==null || part.getCondition()>0 && !window.isDestroyed() && !window.isOpen()
                && (parent==null || ShotContactsPz.vehicleInstalled(parent) && (parent.getDoor()==null || !parent.getDoor().isOpen()));
    }
    private boolean vehicleHit(ShotSnapshot shot,Contact contact,IsoGameCharacter attacker,BaseVehicle vehicle,HandWeapon view) {
        // One native hit per shot and vehicle, as vanilla's shot of the same weapon (its pellets do
        // not each hit a vehicle); every pellet carries the cartridge's full damage (weapon()).
        if (!vehicleAttempts.claim(shot,0,world.targets.identity(vehicle))) return false;
        // Native processRangeHit chooses its own part from PZ's aim/direction.
        // A proxy contact is not authority to call private applyDamageToPart or
        // mutate the named part's HP; native selection may differ from the proxy.
        return ShotVehicleGlass.owned(()->vehicle.processHit(attacker,view,nativeDamage(view,attacker)));
    }
    private boolean allowed(IsoGameCharacter attacker,IsoGameCharacter target) {
        return attacker!=target && !target.isDead() && !target.isGodMod() && !target.isInvulnerable()
                && (rules==null?CombatManager.checkPVP(attacker,target,false):rules.allowed(attacker,target));
    }
    private static boolean eligibleWorld(IsoObject object,Contact contact) {
        String material=ShotWorldMaterials.nativeMaterial(contact.material());
        if (object instanceof IsoWindow window) {
            if (material.equals("barricade")) return window.isBarricaded();
            return material.equals("glass") && !window.IsOpen() && !window.isSmashed()
                    && !window.isDestroyed() && !window.isBarricaded();
        }
        return object instanceof IsoDoor door && material.equals("door")
                && contact.proof().equals("PZ.door-state/"+(door.IsOpen()?"rotated-leaf":"closed-face"));
    }
    private HandWeapon weapon(ShotSnapshot shot,Contact contact) throws Exception {
        ByteBuffer buffer=ByteBuffer.wrap(shot.nativeWeapon().bytes());
        InventoryItem item=InventoryItem.loadItem(buffer,shot.nativeWeapon().worldVersion());
        if (!(item instanceof HandWeapon view) || !view.getFullType().equals(shot.weapon().fullType())
                || view.getContainer()!=null || view.getWorldItem()!=null || !view.isRanged()) {
            throw new IllegalStateException("Invalid unequipped native damage view");
        }
        // PZ loadItem restores script ammo, not the effective Gunworks cartridge.
        // Apply only the frozen enum to this detached view; never reapply an ammo profile.
        var ammo=AmmoType.getByItemKey(shot.nativeAmmoType()).orElseThrow(
                ()->new IllegalStateException("Frozen ammo type unavailable: "+shot.nativeAmmoType()));
        view.setAmmoType(ammo);
        float min=view.getMinDamage(),max=view.getMaxDamage();
        if (!Float.isFinite(min) || !Float.isFinite(max) || min<0 || max<min) throw new IllegalStateException("Native damage stats");
        // Each pellet that reaches a body is a full hit of the weapon, as in vanilla: its shot repeats
        // the hit of a target for every pellet of the spread on it (CombatManager.attackCollisionCheck,
        // spreadCount). The pellets used to share one cartridge's damage, a ninth each for buckshot
        // (user's report of 21.10.2026, item 9). Only the Tarkov speed falloff scales it here.
        // BodyDamage reads the weapon stats again, so scale ONLY this detached view.
        float remaining=contact.effect()==null?1:contact.effect().damageScale();
        view.setMinDamage(min*remaining);view.setMaxDamage(max*remaining);
        return view;
    }
    private boolean humanoid(ShotSnapshot shot,Contact contact,IsoGameCharacter attacker,
            IsoGameCharacter target,HandWeapon view) throws Exception {
        try {
            int targetId=target.getOnlineID();
            // An animal's BodyDamage is never wounded by PZ (IsoAnimal.hitConsequences): no zone scope, no bleeding.
            boolean animal=target instanceof zombie.characters.animals.IsoAnimal;
            var body=animal?null:target.getBodyDamage();
            // B42 zombies have no BodyDamage. Their native ragdoll/Hit path still
            // applies health damage, but cannot receive a scoped wound or bleeding.
            try (ShotDamageScope scope=body==null?null:new ShotDamageScope(body,view,contact.bodyPart())) {
                var wound=body==null || bleeding==null || contact.effect()==null?null:
                        body.getBodyPart(BodyPartType.FromIndex(contact.bodyPart()));
                var before=body==null || bleeding==null || contact.effect()==null?null:bleeding.before(wound);
                CombatManager manager=CombatManager.getInstance();
                // Vanilla rolls the critical hit of every hit (CombatManager.processHit); the live hit
                // kept the roll of the trigger press, or of the previous zombie's hit.
                if (attacker instanceof IsoPlayer player) player.setCriticalHit(Rand.Next(100)<player.calculateCritChance(target));
                RagdollBodyPart part=RagdollBodyPart.values()[contact.ragdollPart()];
                ShotDirection direction=(ShotDirection)world.guard.method("zombie.CombatManager","calculateShotDirection")
                        .invoke(manager,attacker,target);
                world.guard.method("zombie.CombatManager","processTargetedHit").invoke(manager,view,attacker,target,part,direction);
                float damage=(float)world.guard.method("zombie.CombatManager","applyRangeHitLocationDamage")
                        .invoke(manager,target,view,contact.ragdollPart(),nativeDamage(view,attacker));
                // This argument is a DAMAGE MULTIPLIER, not metric distance. Passing
                // the flight range here would multiply health damage by metres.
                // The copy is never in the shooter's hands: PZ's one-handed penalty is lifted for a
                // weapon the shooter holds in both hands (ShotHands, PatchShotDamage.BothHands).
                float applied;
                try (ShotHands hands=heldInBothHands(attacker,view)?new ShotHands(view):null) {
                    applied=target.Hit(view,attacker,damage,false,rangeMultiplier(view));
                }
                // Vanilla's own Aiming XP event, exactly once per confirmed hit (item 8, 27.10.2026): our
                // claimed shot replaced PZ's hit list, so CombatManager/WeaponHit never raise it for us.
                if (grantsAimingXp) ShotXp.hit(shot,attacker,view,target,damage);
                if (before!=null) bleeding.apply(shot.actualAmmoTpl(),contact,wound,before);
                else if (body==null && bleeding!=null && contact.effect()!=null) {
                    bleeding.unsupported(contact,animal?"animal: no wound model":target instanceof zombie.characters.IsoZombie?
                            "zombie has no BodyDamage":"target has no BodyDamage");
                }
                // After the wound and its bleeding: the server's replication syncs both at once.
                if (replication!=null) replication.hit(shot,contact,attacker,target,targetId,view,applied,rangeMultiplier(view));
                return true;
            }
        } catch (Exception | LinkageError ex) { world.guard.fail("native impact: "+ex,ex);throw ex; }
    }
    /** Vanilla's damage multiplier of a ranged hit (CombatManager.attackCollisionCheck: rangeDel):
     * 2 for a weapon with range falloff (shotguns), 1 for the others. */
    static float rangeMultiplier(HandWeapon view) {
        return view.isRangeFalloff()?2f:1f;
    }
    /** The shooter holds a weapon of this type in both hands (the real item; the copy is detached). */
    static boolean heldInBothHands(IsoGameCharacter attacker,HandWeapon view) {
        var primary=attacker.getPrimaryHandItem();
        return view.isTwoHandWeapon() && primary instanceof HandWeapon held && primary==attacker.getSecondaryHandItem()
                && held.getFullType().equals(view.getFullType());
    }
    private static float nativeDamage(HandWeapon view,IsoGameCharacter attacker) {
        float min=view.getMinDamage(),max=view.getMaxDamage();
        return (min==max?min:Rand.Next(min,max))*view.getDamageMod(attacker);
    }
    /** Separate native enum namespaces; coarse ragdoll families are checked
     * against exact anatomical zones, without the native RANDOM family chooser.
     */
    public static boolean validZones(int ragdoll,int body) {
        if (ragdoll<0 || ragdoll>=RagdollBodyPart.BODYPART_COUNT.ordinal() || body<0
                || body>=BodyPartType.ToIndex(BodyPartType.MAX)) return false;
        for (ShotZonesData.Zone zone:ShotZonesData.M) {
            if (RagdollBodyPart.valueOf(zone.ragdoll()).ordinal()==ragdoll
                    && BodyPartType.ToIndex(BodyPartType.valueOf(zone.bodyPart()))==body) return true;
        }
        return false;
    }
}
