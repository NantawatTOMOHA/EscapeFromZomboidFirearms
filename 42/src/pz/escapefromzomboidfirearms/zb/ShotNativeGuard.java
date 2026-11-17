package pz.escapefromzomboidfirearms.zb;

import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Consumer;

/** Exact PZ ABI preflight for the two B3-B native adapters, without class
 * initialization. A/C supply ViewpointBridge.fail as the failure sink, so one
 * failed capability disables the entire bridge. Hashes are evidence, not gates.
 */
public final class ShotNativeGuard {
    private static final java.util.concurrent.atomic.AtomicBoolean TRACE_LOGGED=new java.util.concurrent.atomic.AtomicBoolean();
    // owner, name, descriptor, static. Declaring owners are used for inherited calls.
    public static final String[][] MEMBERS={
        {"zombie.iso.IsoCell","getGridSquare","(III)Lzombie/iso/IsoGridSquare;",""},
        {"zombie.iso.IsoCell","getObjectList","()Ljava/util/Set;",""},
        {"zombie.iso.IsoCell","getVehicles","()Ljava/util/Set;",""},
        {"zombie.iso.IsoGridSquare","getObjects","()Ljava/util/List;",""},
        {"zombie.iso.IsoGridSquare","getSpecialObjects","()Ljava/util/List;",""},
        {"zombie.iso.IsoGridSquare","isSolidFloor","()Z",""},
        {"zombie.iso.IsoGridSquare","HasStairs","()Z",""},
        {"zombie.iso.IsoGridSquare","getX","()I",""},
        {"zombie.iso.IsoGridSquare","getY","()I",""},
        {"zombie.iso.IsoGridSquare","getZ","()I",""},
        {"zombie.iso.IsoGridSquare","getWindowTo","(Lzombie/iso/IsoGridSquare;)Lzombie/iso/objects/IsoWindow;",""},
        {"zombie.iso.IsoGridSquare","getDoorTo","(Lzombie/iso/IsoGridSquare;)Lzombie/iso/IsoObject;",""},
        {"zombie.iso.IsoGridSquare","testVisionAdjacent","(IIIZZ)Lzombie/iso/LosUtil$TestResults;",""},
        {"zombie.iso.IsoObject","getSquare","()Lzombie/iso/IsoGridSquare;",""},
        {"zombie.iso.IsoObject","getCell","()Lzombie/iso/IsoCell;",""},
        {"zombie.iso.IsoObject","getX","()F",""},
        {"zombie.iso.IsoObject","getY","()F",""},
        {"zombie.iso.IsoObject","getZ","()F",""},
        {"zombie.iso.IsoObject","getProperties","()Lzombie/core/properties/PropertyContainer;",""},
        {"zombie.iso.IsoObject","isBush","()Z",""},
        {"zombie.iso.IsoObject","WeaponHit","(Lzombie/characters/IsoGameCharacter;Lzombie/inventory/types/HandWeapon;)V",""},
        {"zombie.iso.objects.IsoWindow","IsOpen","()Z",""},
        {"zombie.iso.objects.IsoWindow","isSmashed","()Z",""},
        {"zombie.iso.objects.IsoWindow","isGlassRemoved","()Z",""},
        {"zombie.iso.objects.IsoWindow","isDestroyed","()Z",""},
        {"zombie.iso.objects.IsoWindow","getNorth","()Z",""},
        {"zombie.iso.objects.IsoWindow","isBarricaded","()Z",""},
        {"zombie.iso.objects.IsoWindow","getBarricadeOnSameSquare","()Lzombie/iso/objects/IsoBarricade;",""},
        {"zombie.iso.objects.IsoWindow","getBarricadeOnOppositeSquare","()Lzombie/iso/objects/IsoBarricade;",""},
        {"zombie.iso.objects.IsoDoor","IsOpen","()Z",""},
        {"zombie.iso.objects.IsoDoor","isDestroyed","()Z",""},
        {"zombie.iso.objects.IsoDoor","isBarricaded","()Z",""},
        {"zombie.iso.objects.IsoDoor","getNorth","()Z",""},
        {"zombie.iso.objects.IsoDoor","getBarricadeOnSameSquare","()Lzombie/iso/objects/IsoBarricade;",""},
        {"zombie.iso.objects.IsoDoor","getBarricadeOnOppositeSquare","()Lzombie/iso/objects/IsoBarricade;",""},
        {"zombie.iso.objects.IsoThumpable","getBarricadeOnSameSquare","()Lzombie/iso/objects/IsoBarricade;",""},
        {"zombie.iso.objects.IsoThumpable","getBarricadeOnOppositeSquare","()Lzombie/iso/objects/IsoBarricade;",""},
        {"zombie.iso.objects.IsoBarricade","isDestroyed","()Z",""},
        {"zombie.iso.objects.IsoBarricade","isMetal","()Z",""},
        {"zombie.iso.objects.IsoBarricade","isMetalBar","()Z",""},
        {"zombie.iso.objects.IsoBarricade","getNumPlanks","()I",""},
        {"zombie.iso.objects.IsoBarricade","getBarricadedObject","()Lzombie/iso/objects/interfaces/BarricadeAble;",""},
        {"zombie.iso.objects.IsoCurtain","IsOpen","()Z",""},
        {"zombie.iso.objects.IsoCurtain","getNorth","()Z",""},
        {"zombie.iso.objects.IsoCurtain","getOppositeSquare","()Lzombie/iso/IsoGridSquare;",""},
        {"zombie.iso.objects.IsoCurtain","getObjectAttachedTo","()Lzombie/iso/IsoObject;",""},
        {"zombie.iso.objects.IsoTree","getSize","()I",""},
        {"zombie.core.properties.PropertyContainer","has","(Ljava/lang/String;)Z",""},
        {"zombie.core.properties.PropertyContainer","get","(Ljava/lang/String;)Ljava/lang/String;",""},
        {"zombie.characters.IsoGameCharacter","isDead","()Z",""},
        {"zombie.characters.IsoGameCharacter","isGodMod","()Z",""},
        {"zombie.characters.IsoGameCharacter","isInvulnerable","()Z",""},
        {"zombie.characters.IsoGameCharacter","isFemale","()Z",""},
        {"zombie.characters.IsoGameCharacter","getAnimationPlayer","()Lzombie/core/skinnedmodel/animation/AnimationPlayer;",""},
        {"zombie.characters.IsoGameCharacter","getBodyDamage","()Lzombie/characters/BodyDamage/BodyDamage;",""},
        {"zombie.characters.IsoGameCharacter","Hit","(Lzombie/inventory/types/HandWeapon;Lzombie/characters/IsoGameCharacter;FZF)F",""},
        {"zombie.core.skinnedmodel.animation.AnimationPlayer","isReady","()Z",""},
        {"zombie.core.skinnedmodel.animation.AnimationPlayer","getSkinningBoneIndex","(Ljava/lang/String;I)I",""},
        {"zombie.core.skinnedmodel.animation.AnimationPlayer","getBoneModelTransform","(ILorg/lwjgl/util/vector/Matrix4f;)Lorg/lwjgl/util/vector/Matrix4f;",""},
        {"zombie.core.skinnedmodel.animation.AnimationPlayer","getRenderedAngle","()F",""},
        {"zombie.core.skinnedmodel.model.Model","vectorToWorldCoords","(FFFFLzombie/iso/Vector3;)V","static"},
        {"zombie.iso.Vector3","<init>","(FFF)V",""},
        {"zombie.iso.Vector3","x","F",""},
        {"zombie.iso.Vector3","y","F",""},
        {"zombie.iso.Vector3","z","F",""},
        {"zombie.vehicles.BaseVehicle","getScript","()Lzombie/scripting/objects/VehicleScript;",""},
        {"zombie.vehicles.BaseVehicle","getWorldTransform","(Lzombie/core/physics/Transform;)Lzombie/core/physics/Transform;",""},
        {"zombie.vehicles.BaseVehicle","processHit","(Lzombie/characters/IsoGameCharacter;Lzombie/inventory/types/HandWeapon;F)Z",""},
        {"zombie.scripting.objects.VehicleScript","getExtents","()Lorg/joml/Vector3f;",""},
        {"zombie.scripting.objects.VehicleScript","getCenterOfMassOffset","()Lorg/joml/Vector3f;",""},
        {"zombie.core.physics.Transform","<init>","()V",""},
        {"zombie.core.physics.Transform","<init>","(Lzombie/core/physics/Transform;)V",""},
        {"zombie.core.physics.Transform","inverse","()V",""},
        {"zombie.core.physics.Transform","transform","(Lorg/joml/Vector3f;)V",""},
        {"zombie.core.physics.Transform","basis","Lorg/joml/Matrix3f;",""},
        {"zombie.core.physics.WorldSimulation","instance","Lzombie/core/physics/WorldSimulation;","static"},
        {"zombie.core.physics.WorldSimulation","offsetX","F",""},
        {"zombie.core.physics.WorldSimulation","offsetY","F",""},
        {"zombie.CombatManager","getInstance","()Lzombie/CombatManager;","static"},
        {"zombie.CombatManager","checkPVP","(Lzombie/iso/IsoMovingObject;Lzombie/iso/IsoMovingObject;Z)Z","static"},
        {"zombie.CombatManager","calculateShotDirection","(Lzombie/characters/IsoGameCharacter;Lzombie/characters/IsoGameCharacter;)Lzombie/combat/ShotDirection;",""},
        {"zombie.CombatManager","processTargetedHit","(Lzombie/inventory/types/HandWeapon;Lzombie/characters/IsoGameCharacter;Lzombie/characters/IsoGameCharacter;Lzombie/core/physics/RagdollBodyPart;Lzombie/combat/ShotDirection;)V",""},
        {"zombie.CombatManager","applyRangeHitLocationDamage","(Lzombie/characters/IsoGameCharacter;Lzombie/inventory/types/HandWeapon;IF)F",""},
        {"zombie.characters.BodyDamage.BodyDamage","DamageFromWeapon","(Lzombie/inventory/types/HandWeapon;I)V",""},
        {"zombie.characters.BodyDamage.BodyPartType","ToIndex","(Lzombie/characters/BodyDamage/BodyPartType;)I","static"},
        {"zombie.characters.BodyDamage.BodyPartType","valueOf","(Ljava/lang/String;)Lzombie/characters/BodyDamage/BodyPartType;","static"},
        {"zombie.core.physics.RagdollBodyPart","valueOf","(Ljava/lang/String;)Lzombie/core/physics/RagdollBodyPart;","static"},
        {"zombie.core.physics.RagdollBodyPart","values","()[Lzombie/core/physics/RagdollBodyPart;","static"},
        {"zombie.inventory.InventoryItem","loadItem","(Ljava/nio/ByteBuffer;I)Lzombie/inventory/InventoryItem;","static"},
        {"zombie.inventory.InventoryItem","getFullType","()Ljava/lang/String;",""},
        {"zombie.inventory.InventoryItem","getContainer","()Lzombie/inventory/ItemContainer;",""},
        {"zombie.inventory.InventoryItem","getWorldItem","()Lzombie/iso/objects/IsoWorldInventoryObject;",""},
        {"zombie.inventory.InventoryItem","setAmmoType","(Lzombie/scripting/objects/AmmoType;)V",""},
        {"zombie.scripting.objects.AmmoType","getByItemKey","(Ljava/lang/String;)Ljava/util/Optional;","static"},
        {"zombie.inventory.types.HandWeapon","isRanged","()Z",""},
        {"zombie.inventory.types.HandWeapon","getMinDamage","()F",""},
        {"zombie.inventory.types.HandWeapon","getMaxDamage","()F",""},
        {"zombie.inventory.types.HandWeapon","setMinDamage","(F)V",""},
        {"zombie.inventory.types.HandWeapon","setMaxDamage","(F)V",""},
        {"zombie.inventory.types.HandWeapon","getDamageMod","(Lzombie/characters/IsoGameCharacter;)F",""},
        {"zombie.core.random.Rand","Next","(FF)F","static"},
        {"zombie.network.GameClient","client","Z","static"},
        {"zombie.iso.LosUtil$TestResults","Blocked","Lzombie/iso/LosUtil$TestResults;","static"},
        {"zombie.core.physics.RagdollBodyPart","BODYPART_COUNT","Lzombie/core/physics/RagdollBodyPart;","static"},
        {"zombie.characters.BodyDamage.BodyPartType","MAX","Lzombie/characters/BodyDamage/BodyPartType;","static"},
        {"org.lwjgl.util.vector.Matrix4f","<init>","()V",""},
        {"org.joml.Vector3f","<init>","(FFF)V",""},
        {"org.joml.Vector3f","x","F",""},
        {"org.joml.Vector3f","y","F",""},
        {"org.joml.Vector3f","z","F",""},
        {"org.joml.Matrix3f","transform","(Lorg/joml/Vector3f;)Lorg/joml/Vector3f;",""},
    };
    private final Consumer<String> failure;
    private final HashMap<String,Method> methods=new HashMap<>();
    private volatile boolean ready,failed;
    public ShotNativeGuard(Consumer<String> failure) { this.failure=Objects.requireNonNull(failure); }
    public boolean ready() { return ready && !failed; }
    public boolean verify(ClassLoader loader) {
        if (failed) return false;
        try {
            for (String name:new String[]{"zombie.characters.IsoPlayer","zombie.characters.IsoZombie","zombie.characters.IsoSurvivor",
                    "zombie.iso.objects.IsoWindow","zombie.iso.objects.IsoDoor","zombie.iso.objects.IsoThumpable",
                    "zombie.iso.objects.IsoBarricade","zombie.iso.objects.IsoCurtain","zombie.iso.objects.IsoTree"}) {
                Class.forName(name,false,loader);
            }
            for (String[] row:MEMBERS) verifyMember(loader,row);
            for (ShotZonesData.Zone zone:ShotZonesData.M) {
                verifyMember(loader,new String[]{"zombie.core.physics.RagdollBodyPart",zone.ragdoll(),"Lzombie/core/physics/RagdollBodyPart;","static"});
                verifyMember(loader,new String[]{"zombie.characters.BodyDamage.BodyPartType",zone.bodyPart(),"Lzombie/characters/BodyDamage/BodyPartType;","static"});
            }
            for (String field:new String[]{"m00","m01","m02","m03","m10","m11","m12","m13","m20","m21","m22","m23"}) {
                verifyMember(loader,new String[]{"org.lwjgl.util.vector.Matrix4f",field,"F",""});
            }
            ready=true;return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) { fail("native ABI: "+ex,ex);return false; }
    }
    /** Public for the detached guard tests. Does not initialize game classes. */
    public void verifyMember(ClassLoader loader,String[] row) throws ReflectiveOperationException {
        Class<?> owner=Class.forName(row[0],false,loader);boolean isStatic="static".equals(row[3]);
        if (row[2].startsWith("(")) {
            MethodType type=MethodType.fromMethodDescriptorString(row[2],loader);
            if (row[1].equals("<init>")) {
                if (isStatic || type.returnType()!=void.class) throw new NoSuchMethodException(row[0]+" constructor flags");
                owner.getDeclaredConstructor(type.parameterArray());return;
            }
            Method method;
            try { method=owner.getDeclaredMethod(row[1],type.parameterArray()); }
            catch (NoSuchMethodException inherited) {
                method=owner.getMethod(row[1],type.parameterArray());
            }
            if (method.getReturnType()!=type.returnType() || Modifier.isStatic(method.getModifiers())!=isStatic
                    || !method.trySetAccessible()) throw new NoSuchMethodException(row[0]+"."+row[1]+" signature/flags/access");
            methods.put(row[0]+"#"+row[1],method);
        } else {
            Field field=owner.getDeclaredField(row[1]);
            Class<?> type=MethodType.fromMethodDescriptorString("()"+row[2],loader).returnType();
            if (field.getType()!=type || Modifier.isStatic(field.getModifiers())!=isStatic || !field.trySetAccessible()) {
                throw new NoSuchFieldException(row[0]+"."+row[1]+" signature/flags/access");
            }
        }
    }
    public Method method(String owner,String name) {
        Method method=methods.get(owner+"#"+name);
        if (!ready() || method==null) throw new IllegalStateException("Unverified native method "+owner+"."+name);
        return method;
    }
    public void fail(String reason) { fail(reason,new IllegalStateException(reason)); }
    /** One original stack for all shot-owner/adapter shutdown paths in this JVM. */
    public static void traceFailure(String reason,Throwable error) {
        if (TRACE_LOGGED.compareAndSet(false,true)) {
            System.out.println("[TWZB] Viewpoint shot native failure: "+reason);
            error.printStackTrace(System.out);
        }
    }
    public synchronized void fail(String reason,Throwable error) {
        ready=false;
        if (!failed) {
            failed=true;
            traceFailure(reason,error);
            failure.accept(reason);
        }
    }
}
