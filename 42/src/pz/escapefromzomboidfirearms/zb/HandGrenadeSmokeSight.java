package pz.escapefromzomboidfirearms.zb;

import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoFire;

/** Common native smoke policy, safe in the dedicated companion. No renderer. */
public final class HandGrenadeSmokeSight {
    private static volatile Boolean supported;
    private static volatile boolean enabled=true,active;
    private static final String SESSION=java.util.UUID.randomUUID().toString();
    private record Cloud(String id,double x,double y,int z,double radius) { }
    private static final java.util.concurrent.ConcurrentHashMap<String,Cloud> clouds=new java.util.concurrent.ConcurrentHashMap<>();
    private static volatile Cloud[] current=new Cloud[0];
    private static final ThreadLocal<Boolean> bypass=ThreadLocal.withInitial(()->false);
    private static volatile Boolean perceptionABI;
    public static volatile long perceptionCalls,perceptionBlocked,spottingBlocked;
    private HandGrenadeSmokeSight() { }
    public static void configure(boolean on,boolean hasClouds) {
        enabled=on;active=hasClouds;
        if (!hasClouds) { clouds.clear();current=new Cloud[0]; }
    }
    public static boolean perceptionSupported() {
        if (perceptionABI!=null) return perceptionABI;
        try {
            var method=zombie.characters.IsoGameCharacter.class.getMethod("CanSee",zombie.iso.IsoMovingObject.class);
            perceptionABI=method.getReturnType()==boolean.class && !java.lang.reflect.Modifier.isStatic(method.getModifiers());
        } catch (Throwable failure) { perceptionABI=false; }
        return perceptionABI;
    }
    public static boolean cloud(String id,double x,double y,double z,double radius) {
        if (!perceptionSupported() || id==null || id.isBlank() || !Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z) || !Double.isFinite(radius) || radius<0 || radius>100) return false;
        // Performance pass 23.10.2026: Lua renews every cloud every tick; an unchanged footprint keeps its record and array.
        Cloud old=radius==0?null:clouds.get(id);
        if (old!=null && old.x()==x && old.y()==y && old.z()==(int)Math.floor(z) && old.radius()==radius) return true;
        if (radius==0) clouds.remove(id);
        else if (clouds.containsKey(id) || clouds.size()<128) clouds.put(id,new Cloud(id,x,y,(int)Math.floor(z),radius));
        else return false;
        current=clouds.values().toArray(Cloud[]::new);
        return true;
    }
    public static void drop(String id) { if (id!=null && clouds.remove(id)!=null) current=clouds.values().toArray(Cloud[]::new); }
    /** Geometry queries (bullets/blasts/ordinary lineClear) are unchanged. Only
     * character perception uses this smoke footprint, like the source AI area.
     */
    public static boolean blocks(double ax,double ay,double az,double bx,double by,double bz) {
        if (!enabled || !active || Math.floor(az)!=Math.floor(bz)) return false;
        double dx=bx-ax,dy=by-ay,length=dx*dx+dy*dy;
        for (Cloud cloud:current) if (cloud.z==(int)Math.floor(az)) {
            double t=length==0?0:Math.max(0,Math.min(1,((cloud.x-ax)*dx+(cloud.y-ay)*dy)/length));
            double x=ax+t*dx-cloud.x,y=ay+t*dy-cloud.y;
            if (x*x+y*y<=cloud.radius*cloud.radius) return true;
        }
        return false;
    }
    public static boolean perception(Object observer,Object target,boolean original) {
        if (!original || !enabled || !active || bypass.get() || observer==target
                || !(observer instanceof zombie.characters.IsoGameCharacter actor)
                || !(target instanceof zombie.iso.IsoMovingObject object)) return original;
        perceptionCalls++;
        if (blocks(actor.getX(),actor.getY(),actor.getZ(),object.getX(),object.getY(),object.getZ())) {
            perceptionBlocked++;return false;
        }
        return original;
    }
    /** Native zombie spotting has its own visibility calculation. Stop this
     * visual acquisition before it can refresh the target, without changing
     * hearing, pathfinding, target memory or remote zombie authority.
     */
    public static boolean spotting(Object observer,Object target) {
        // blocks() needs a live cloud: every zombie's spotting passes here.
        if (!enabled || !active) return false;
        if (!(observer instanceof zombie.characters.IsoZombie actor)
                || !(target instanceof zombie.iso.IsoMovingObject object)
                || (zombie.network.GameClient.client && actor.isRemoteZombie())) return false;
        if (blocks(actor.getX(),actor.getY(),actor.getZ(),object.getX(),object.getY(),object.getZ())) {
            spottingBlocked++;return true;
        }
        return false;
    }
    /** Read the real native geometry result without smoke, for a thermal
     * consumer or a paired QA observation. It never turns native false into true.
     */
    public static boolean unobscured(zombie.characters.IsoGameCharacter observer,zombie.iso.IsoMovingObject target) {
        if (observer==null || target==null) return false;
        boolean old=bypass.get();bypass.set(true);
        try { return observer.CanSee(target); } finally { bypass.set(old); }
    }
    public static String info() { return "clouds="+current.length+" calls="+perceptionCalls+" blocked="+perceptionBlocked+" spottingBlocked="+spottingBlocked+" supported="+perceptionSupported(); }
    public static boolean owned(boolean smoke,Object marker,int life) {
        return smoke && marker instanceof String id && !id.isEmpty() && life>0;
    }
    public static boolean supported() {
        if (supported!=null) return supported;
        try {
            var vision=IsoFire.class.getMethod("TestVision",IsoGridSquare.class,IsoGridSquare.class);
            var smoke=IsoFire.class.getField("smoke");
            var life=IsoFire.class.getMethod("getLife");
            var data=IsoObject.class.getMethod("getModData");
            var add=IsoFire.class.getMethod("addToWorld");
            var setLife=IsoFire.class.getMethod("setLife",int.class);
            supported=vision.getReturnType()==IsoObject.VisionResult.class && smoke.getType()==boolean.class
                    && life.getReturnType()==int.class && data.getReturnType()==se.krka.kahlua.vm.KahluaTable.class
                    && !java.lang.reflect.Modifier.isStatic(vision.getModifiers())
                    && !java.lang.reflect.Modifier.isStatic(smoke.getModifiers())
                    && !java.lang.reflect.Modifier.isStatic(life.getModifiers())
                    && !java.lang.reflect.Modifier.isStatic(data.getModifiers());
            supported=supported && add.getReturnType()==void.class && setLife.getReturnType()==void.class
                    && !java.lang.reflect.Modifier.isStatic(add.getModifiers())
                    && !java.lang.reflect.Modifier.isStatic(setLife.getModifiers());
        } catch (Throwable ex) { supported=false; }
        if (!supported) System.out.println("[TW-HAND-GRENADE] native smoke vision ABI unavailable");
        return supported;
    }
    public static void stamp(IsoFire fire) {
        if (supported() && fire!=null) fire.getModData().rawset("TWHandSmokeSession",SESSION);
    }
    public static boolean stale(boolean smoke,Object marker,Object session) {
        return owned(smoke,marker,1) && !SESSION.equals(session);
    }
    /** Old serialized cloud objects are retired; live authority entries recreate
     * their current source radius. A missing native adapter still has a short
     * Lua-renewed life lease, so unloaded/expired clouds cannot become permanent.
     */
    public static void loaded(IsoFire fire) {
        try {
            if (supported() && fire!=null && stale(fire.smoke,fire.getModData().rawget("TWHandSmoke"),
                    fire.getModData().rawget("TWHandSmokeSession"))) fire.setLife(0);
        } catch (Throwable ex) { supported=false; }
    }
    /** Public: called by advice inserted into the game's IsoFire class. */
    public static IsoObject.VisionResult vision(IsoFire fire,IsoObject.VisionResult original) {
        if (!enabled || !active) return original;
        try {
            if (supported() && fire!=null && owned(fire.smoke,fire.getModData().rawget("TWHandSmoke"),fire.getLife()))
                return IsoObject.VisionResult.Blocked;
        } catch (Throwable ex) { supported=false; }
        return original;
    }
}
