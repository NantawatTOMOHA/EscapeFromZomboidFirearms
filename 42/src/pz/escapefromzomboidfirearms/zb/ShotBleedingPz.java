package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Modifier;
import java.util.function.Consumer;
import zombie.characters.BodyDamage.BodyPart;
import pz.escapefromzomboidfirearms.zb.ShotSnapshot.Contact;

/** B5 proposals on an already admitted native PZ wound, never a second HP path.
 * PZ has one bleeding timer: a heavy proposal uses its native deep-wound state.
 * All new APIs are checked before the local damage adapter can become ready.
 */
public final class ShotBleedingPz {
    // B42.21 BodyPart constructor/RestoreToFullHealth both set health to 100.
    // This is PZ's own part maximum, not a Tarkov body-health conversion.
    public static final float PART_MAX_HEALTH=100f;
    private static String last="bleeding=unobserved";
    private final TarkovPenetrationData data;
    private boolean ready;
    public record Before(float health,boolean heavy) {}
    public ShotBleedingPz(TarkovPenetrationData data,Consumer<String> failure) {
        this.data=java.util.Objects.requireNonNull(data);
        try {
            verify(BodyPart.class);
            method(zombie.characters.BodyDamage.BodyDamage.class,"getBodyPart",BodyPart.class,
                    zombie.characters.BodyDamage.BodyPartType.class);
            var index=zombie.characters.BodyDamage.BodyPartType.class.getMethod("FromIndex",int.class);
            if (!Modifier.isPublic(index.getModifiers()) || !Modifier.isStatic(index.getModifiers())
                    || index.getReturnType()!=zombie.characters.BodyDamage.BodyPartType.class) throw new NoSuchMethodException("BodyPartType.FromIndex");
            ready=true;
        }
        catch (ReflectiveOperationException | LinkageError ex) { failure.accept("bleeding ABI: "+ex); }
    }
    public static void verify(Class<?> part) throws ReflectiveOperationException {
        for (String name:new String[]{"getHealth","getBleedingTime"}) method(part,name,float.class);
        for (String name:new String[]{"isDeepWounded","bandaged","IsBleedingStemmed","IsCauterized"}) method(part,name,boolean.class);
        for (String name:new String[]{"generateDeepWound","generateBleeding"}) method(part,name,void.class);
        method(part,"setBleedingTime",void.class,float.class);
    }
    private static void method(Class<?> owner,String name,Class<?> result,Class<?>... parameters) throws ReflectiveOperationException {
        var method=owner.getMethod(name,parameters);
        if (!Modifier.isPublic(owner.getModifiers()) || !Modifier.isPublic(method.getModifiers())
                || Modifier.isStatic(method.getModifiers()) || method.getReturnType()!=result)
            throw new NoSuchMethodException(owner.getName()+"."+name);
    }
    public boolean ready() { return ready; }
    public Before before(BodyPart part) { return new Before(part.getHealth(),part.isDeepWounded()); }
    public void apply(String ammoTpl,Contact contact,BodyPart part,Before before) {
        if (!ready || contact.effect()==null || !contact.forwardHit()) return;
        float damage=Math.max(0,before.health-part.getHealth());
        boolean blocked=damage<=0 || part.bandaged() || part.IsBleedingStemmed() || part.IsCauterized();
        var effect=contact.effect();
        var proposal=TarkovPenetration.bleeding(data,data.ammo(ammoTpl),damage,PART_MAX_HEALTH,1,blocked,
                before.heavy,effect.heavyRoll(),effect.lightRoll());
        float existing=part.getBleedingTime();
        if (proposal==TarkovPenetration.Bleeding.HEAVY && !part.isDeepWounded()) part.generateDeepWound();
        else if (proposal!=TarkovPenetration.Bleeding.NONE) part.generateBleeding();
        if (proposal!=TarkovPenetration.Bleeding.NONE && part.getBleedingTime()<existing) part.setBleedingTime(existing);
        last="bleeding="+proposal+" bleedShot="+contact.key().pellet().shot().sequenceText()
                +" bleedContact="+contact.key().contactOrdinal()+" bleedPart="+contact.bodyPart()+" woundDamage="+damage
                +" bleedBlocked="+blocked+" bleedTime="+part.getBleedingTime()+" deepWound="+part.isDeepWounded();
    }
    public void unsupported(Contact contact,String reason) {
        last="bleeding=UNSUPPORTED ("+reason+") bleedShot="+contact.key().pellet().shot().sequenceText()
                +" bleedContact="+contact.key().contactOrdinal()+" bleedPart="+contact.bodyPart();
        System.out.println("[TW-SHOT] "+last);
    }
    public static String info() { return last; }
}
