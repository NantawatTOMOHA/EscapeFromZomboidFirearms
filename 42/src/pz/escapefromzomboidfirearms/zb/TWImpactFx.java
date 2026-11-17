package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Patch;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.iso.SpriteDetails.IsoFlagType;
import pz.escapefromzomboidfirearms.zb.ShotPoseSnapshot.Pz;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;
import pz.escapefromzomboidfirearms.zb.ImpactMarks.Event;

/** Separate Lua API; none of the shot-owner or shared TWZB API is changed. */
@Exposer.LuaClass
public final class TWImpactFx {
    public static long nativeCalls,nativeObserved,nativeAccepted,nativeUnknown;
    public static String nativeReason="none";
    private static long nativeSequence;
    private TWImpactFx() {}
    public static String info() {
        return "enabled="+ImpactMarks.enabled+" failed="+ImpactMarks.failed+" "+ImpactMarks.STORE.stats()
                +" frames="+ImpactFx.frames+" quads="+ImpactFx.quads+" sprites="+ImpactFx.sprites+" streaks="+ImpactFx.streaks
                +" audio="+ImpactFx.audio+" ticks="+ImpactFx.ticks+" isoFrames="+ImpactFx.isoFrames+" isoQuads="+ImpactFx.isoQuads+" isoSprites="+ImpactFx.isoSprites
                +" isoFar="+ImpactFx.isoFar+" isoBack="+ImpactFx.isoBack+" isoUnloaded="+ImpactFx.isoUnloaded+" isoNoSquare="+ImpactFx.isoNoSquare+" isoUnlit="+ImpactFx.isoUnlit+" isoNoTexture="+ImpactFx.isoNoTexture
                +" nativeCalls="+nativeCalls+" nativeObserved="+nativeObserved+" nativeAccepted="+nativeAccepted+" nativeUnknown="+nativeUnknown+" nativeReason="+nativeReason
                +" nativeSkipped="+nativeSkipped+" puffs="+ImpactMarks.puffs
                +" errors="+ImpactMarks.errors+" reason="+ImpactMarks.reason;
    }
    public static void tick() { ImpactFx.tick(); }
    public static void isometric() { ImpactFx.isometric(); }
    /** Isolated debug captures: a maximized OS window can override Core's requested resolution. */
    public static boolean qaViewport(int width,int height) {
        if (!zombie.core.Core.debug || !(width==1600 && height==900 || width==3840 && height==2160)) return false;
        zombie.core.opengl.RenderThread.queueInvokeOnRenderContext(() -> {
            long window=org.lwjglx.opengl.Display.getWindow();
            org.lwjgl.glfw.GLFW.glfwRestoreWindow(window);
            org.lwjgl.glfw.GLFW.glfwSetWindowSize(window,width,height);
        });
        return true;
    }
    public static double qaIsoZoom(double value) {
        var core=zombie.core.Core.getInstance();
        double previous=core.getZoom(0);
        if (zombie.core.Core.debug && Double.isFinite(value) && value>=core.getMinZoom() && value<=core.getMaxZoom())
            core.offscreenBuffer.setZoomAndTargetZoom(0,(float)value);
        return previous;
    }
    public static String qaIsoPoint(double x,double y,double z) {
        if (!zombie.core.Core.debug) return "debug-only";
        return ImpactFx.isoX(x,y,z)+","+ImpactFx.isoY(x,y,z)+" zoom="+zombie.core.Core.getInstance().getZoom(0);
    }
    /** QA: put the game's cached mouse at the isometric reticle ground projection of a 3D world target
     * along the local player's aim ray, and refresh BallisticsController + setAngleFromAim. */
    private static int[] savedMouse;
    private static java.lang.reflect.Field mouseXField,mouseYField;
    public static volatile int qaPinMode;
    private static volatile double qaPinX,qaPinY,qaPinZ;
    private static String setMouseWorld(int index,double x,double y,double z) throws Exception {
        float zoom=zombie.core.Core.getInstance().getZoom(index);
        if (!(zoom>0)) return "no-zoom";
        float sx=zombie.iso.IsoUtils.XToScreen((float)x,(float)y,(float)z,0)-zombie.iso.IsoCamera.getOffX(index);
        float sy=zombie.iso.IsoUtils.YToScreen((float)x,(float)y,(float)z,0)-zombie.iso.IsoCamera.getOffY(index);
        if (mouseXField==null) {
            var mx=zombie.input.Mouse.class.getDeclaredField("x");var my=zombie.input.Mouse.class.getDeclaredField("y");
            mx.setAccessible(true);my.setAccessible(true);
            mouseXField=mx;mouseYField=my;
        }
        if (savedMouse==null) savedMouse=new int[]{mouseXField.getInt(null),mouseYField.getInt(null)};
        int px=Math.round(sx/zoom),py=Math.round(sy/zoom);
        mouseXField.setInt(null,px);mouseYField.setInt(null,py);
        return px+","+py;
    }
    private static String projectQaAim(int index,double x,double y,double z,boolean refreshBallistics) throws Exception {
        var player=(index>=0 && index<zombie.characters.IsoPlayer.players.length)?zombie.characters.IsoPlayer.players[index]:null;
        if (player==null) player=zombie.characters.IsoPlayer.getInstance();
        double tx=x,ty=y,tz=z;
        double ux=1.0,uy=0.0,dz=0.0,horiz=1.0;
        if (player!=null) {
            double ox=player.getAimOriginPosX(),oy=player.getAimOriginPosY(),oz=player.getAimOriginPosZ();
            double dx=x-ox,dy=y-oy;
            dz=z-oz;horiz=Math.hypot(dx,dy);
            if (horiz>1e-6) { ux=dx/horiz;uy=dy/horiz; }
            double reach=Math.max(horiz,6.0);
            tx=ox+ux*reach;ty=oy+uy*reach;tz=oz;
        }
        String res=setMouseWorld(index,tx,ty,tz);
        if (player!=null && !ViewpointBridge.perspective()) {
            float fux=(float)ux,fuy=(float)uy;
            float heading=(float)Math.toDegrees(Math.atan2(uy,ux));
            if (heading<0f) heading+=360f;
            float pitch=(float)Math.toDegrees(Math.atan2(dz*2.44949,Math.max(1e-4,horiz)));
            pitch=Math.clamp(pitch,-85f,85f);
            player.setDirectionAngle(heading);
            player.setForwardDirection(fux,fuy);
            player.setTargetVerticalAimAngle(pitch);
            player.setCurrentVerticalAimAngle(pitch);
            var anim=player.getAnimationPlayer();
            if (anim!=null) {
                anim.setTargetAndCurrentDirection(fux,fuy);
                anim.updateVerticalAimAngle(player);
            }
            if (refreshBallistics && player.isAiming()) {
                player.updateBallistics();
            }
        }
        return res;
    }
    public static void applyQaMousePin() {
        int mode=qaPinMode;
        if (mode==0 || !zombie.core.Core.debug || ViewpointBridge.perspective()) return;
        try {
            int index=zombie.iso.IsoCamera.frameState.playerIndex;
            if (index<0) index=0;
            if (mode==1) projectQaAim(index,qaPinX,qaPinY,qaPinZ,false);
            else if (mode==2) setMouseWorld(index,qaPinX,qaPinY,qaPinZ);
        } catch (Throwable ignored) {}
    }
    public static String qaAimAt(double x,double y,double z) {
        if (!zombie.core.Core.debug) return "debug-only";
        try {
            qaPinX=x;qaPinY=y;qaPinZ=z;qaPinMode=1;
            int index=zombie.iso.IsoCamera.frameState.playerIndex;
            if (index<0) index=0;
            return projectQaAim(index,x,y,z,true);
        } catch (Throwable error) { return "error "+error; }
    }
    /** QA: put the cached mouse directly on a world point's screen pixel without re-aiming (e.g. to clear cursor cutaway). */
    public static String qaAimCursor(double x,double y,double z) {
        if (!zombie.core.Core.debug) return "debug-only";
        try {
            qaPinX=x;qaPinY=y;qaPinZ=z;qaPinMode=2;
            int index=zombie.iso.IsoCamera.frameState.playerIndex;
            if (index<0) index=0;
            return setMouseWorld(index,x,y,z);
        } catch (Throwable error) { return "error "+error; }
    }
    /** QA: restore the mouse position the native aim replaced (isometric aim only). */
    public static String qaAimRestore() {
        qaPinMode=0;
        if (savedMouse==null) return "none";
        try {
            if (mouseXField==null) {
                var mx=zombie.input.Mouse.class.getDeclaredField("x");var my=zombie.input.Mouse.class.getDeclaredField("y");
                mx.setAccessible(true);my.setAccessible(true);
                mouseXField=mx;mouseYField=my;
            }
            mouseXField.setInt(null,savedMouse[0]);mouseYField.setInt(null,savedMouse[1]);
            savedMouse=null;
            return "restored";
        } catch (Throwable error) { return "error "+error; }
    }
    public static void reset() { ImpactMarks.LAST.clear();ImpactFx.reset(); }
    public static void enabled(boolean value) { ImpactMarks.enabled=value;if (!value) reset(); }
    /** Viewer-only performance settings; contact audio and multiplayer relay remain active. */
    public static void visualOptions(boolean particles,boolean decals) { ImpactMarks.STORE.visualOptions(particles,decals); }
    private static void put(KahluaTable t,String key,Object value) { t.rawset(key,value); }
    public static KahluaTable table(Event e) {
        KahluaTable t=LuaManager.platform.newTable();
        put(t,"schema",1d);put(t,"id",e.id());put(t,"preset",e.preset());put(t,"seed",Long.toString(e.seed()));
        put(t,"x",e.point().x());put(t,"y",e.point().y());put(t,"z",e.point().z());
        put(t,"nx",(double)e.normal().x());put(t,"ny",(double)e.normal().y());put(t,"nz",(double)e.normal().z());
        put(t,"dx",(double)e.incoming().x());put(t,"dy",(double)e.incoming().y());put(t,"dz",(double)e.incoming().z());
        put(t,"outcome",e.outcome());put(t,"forward",e.forward());put(t,"vehicle",e.vehicle());put(t,"sound",e.sound());
        put(t,"decal",e.decal());
        return t;
    }
    public static KahluaTable poll() {
        Event e=ImpactMarks.STORE.poll();return e==null?null:table(e);
    }
    public static String text(KahluaTable t,String key,int max) {
        Object value=t.rawget(key);
        if (!(value instanceof String s) || s.isEmpty() || s.length()>max) throw new IllegalArgumentException("Impact field "+key);
        return s;
    }
    public static double number(KahluaTable t,String key) {
        Object value=t.rawget(key);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("Impact field "+key);
        return n.doubleValue();
    }
    public static boolean bool(KahluaTable t,String key) {
        Object value=t.rawget(key);if (!(value instanceof Boolean b)) throw new IllegalArgumentException("Impact field "+key);return b;
    }
    public static Event event(KahluaTable t) {
        if (t==null || number(t,"schema")!=1) throw new IllegalArgumentException("Impact schema");
        double x=number(t,"x"),y=number(t,"y"),z=number(t,"z");
        if (Math.abs(x)>1e6 || Math.abs(y)>1e6 || z< -32 || z>64) throw new IllegalArgumentException("Impact world bounds");
        return new Event(text(t,"id",256),text(t,"preset",64),new Pz(x,y,z),
                new Vec3((float)number(t,"nx"),(float)number(t,"ny"),(float)number(t,"nz")),
                new Vec3((float)number(t,"dx"),(float)number(t,"dy"),(float)number(t,"dz")),
                text(t,"outcome",16),bool(t,"forward"),bool(t,"vehicle"),Long.parseLong(text(t,"seed",20)),bool(t,"sound"),
                t.rawget("decal")==null || bool(t,"decal"));
    }
    /** Invalid/unloaded remote cosmetics are rejected; they cannot disable the owner or bridge. */
    public static boolean receive(KahluaTable t) {
        if (!ImpactMarks.enabled || ImpactMarks.failed) return false;
        try { Event e=event(t);return ImpactFx.loaded(e) && ImpactMarks.STORE.accept(e,false); }
        catch (Throwable ignored) { return false; }
    }
    /** CPU geometry evidence from real contacts, without claiming pixels were rendered. */
    public static KahluaTable snapshot() {        KahluaTable result=LuaManager.platform.newTable();int i=0;
        for (var m:ImpactMarks.STORE.marks()) {
            KahluaTable t=table(m.event);put(t,"cx",m.center.x());put(t,"cy",m.center.y());put(t,"cz",m.center.z());
            put(t,"width",m.halfWidth*2);put(t,"length",m.halfLength*2);put(t,"alpha",(double)m.alpha(ImpactMarks.STORE.time));
            put(t,"age",ImpactMarks.STORE.time-m.born);put(t,"texture",m.texture);
            result.rawset((double)++i,t);
        }
        return result;
    }
    /** The last cosmetic resolutions of real contacts (QA): token, sprite, preset and whether a hole was placed. */
    public static KahluaTable resolutions() {
        KahluaTable result=LuaManager.platform.newTable();int i=0;
        synchronized (ImpactMarks.LAST) {
            for (var r:ImpactMarks.LAST) {
                KahluaTable t=LuaManager.platform.newTable();
                put(t,"token",r.token());put(t,"sprite",r.sprite());put(t,"preset",r.preset());put(t,"decal",r.decal());
                result.rawset((double)++i,t);
            }
        }
        return result;
    }

    private static ShotOwner.Engine ownerEngine;
    /** Non-null only when ShotOwner owns or shadow-traces THIS exact shot and therefore emits its own marks. */
    static String ownerReason(Object actor,Object weapon) {
        if (actor instanceof zombie.characters.IsoGameCharacter ch
                && (weapon==null || weapon instanceof zombie.inventory.types.HandWeapon)
                && ShotOwner.suppress(ch,(zombie.inventory.types.HandWeapon)weapon)) return "owner-live";
        try {
            if (ownerEngine==null) {
                var field=ShotOwner.class.getDeclaredField("engine");
                field.setAccessible(true);
                ownerEngine=(ShotOwner.Engine)field.get(null);
            }
            ShotOwner.Scope scope=ownerEngine.current(actor,weapon);
            if (scope!=null && scope.shot()!=null) return scope.owned?"owner-live":"owner-shadow";
        } catch (Throwable ignored) {}
        return null;
    }

    private static final long SMASH_WINDOW_TTL_NS=100_000_000L;
    private static int attackDepth;
    private static zombie.iso.objects.IsoWindow recentSmashedWindow;
    private static long recentSmashedNs;
    private static zombie.vehicles.VehicleWindow recentVehicleWindow;
    private static long recentVehicleWindowNs;

    public static void enterAttackCollision() {
        long now=System.nanoTime();
        if (attackDepth++==0) {
            if (now-recentSmashedNs>SMASH_WINDOW_TTL_NS) { recentSmashedWindow=null;recentSmashedNs=0L; }
            if (now-recentVehicleWindowNs>SMASH_WINDOW_TTL_NS) { recentVehicleWindow=null;recentVehicleWindowNs=0L; }
        }
    }
    public static void exitAttackCollision() {
        if (attackDepth>0 && --attackDepth==0) clearRecentShattered();
        else if (attackDepth<=0) { attackDepth=0;clearRecentShattered(); }
    }
    public static void clearRecentShattered() {
        recentSmashedWindow=null;recentSmashedNs=0L;
        recentVehicleWindow=null;recentVehicleWindowNs=0L;
    }
    public static void noteWindowSmash(zombie.iso.objects.IsoWindow window) {
        if (window!=null && !window.isSmashed() && !window.isDestroyed() && !window.isGlassRemoved() && !window.IsOpen()) {
            recentSmashedWindow=window;
            recentSmashedNs=System.nanoTime();
        }
    }
    public static void noteVehicleWindowDamage(zombie.vehicles.VehicleWindow window,int amount) {
        if (amount>0 && window!=null && window.isHittable()) {
            recentVehicleWindow=window;
            recentVehicleWindowNs=System.nanoTime();
        }
    }
    static zombie.iso.objects.IsoWindow recentWindow(long nowNs) {
        var win=recentSmashedWindow;
        long at=recentSmashedNs;
        if (attackDepth<=0) { recentSmashedWindow=null;recentSmashedNs=0L; }
        return win!=null && nowNs-at>=0L && nowNs-at<=SMASH_WINDOW_TTL_NS?win:null;
    }
    static zombie.vehicles.VehicleWindow recentVehicleWindow(long nowNs) {
        var win=recentVehicleWindow;
        long at=recentVehicleWindowNs;
        if (attackDepth<=0) { recentVehicleWindow=null;recentVehicleWindowNs=0L; }
        return win!=null && nowNs-at>=0L && nowNs-at<=SMASH_WINDOW_TTL_NS?win:null;
    }
    static boolean windowGlassHittable(zombie.iso.objects.IsoWindow window,zombie.iso.objects.IsoWindow hitWindow) {
        return window!=null && !window.IsOpen() && !window.isGlassRemoved()
                && ((!window.isSmashed() && !window.isDestroyed()) || window==hitWindow);
    }
    static boolean vehicleWindowGlassHittable(zombie.vehicles.VehicleWindow window,int condition,
            zombie.vehicles.VehicleWindow hitVehicleWindow) {
        if (window==null || (window.isOpen() && window.getOpenDelta()>=1f)) return false;
        return (!window.isDestroyed() && condition>0) || window==hitVehicleWindow;
    }

    private static int missedDepth,pendingArgsLen;
    private static Object pendingEffect;
    private static zombie.characters.IsoPlayer pendingPlayer;
    private static float pendingRange;
    private static zombie.iso.IsoGridSquare pendingTerminal;

    /** Defer tracer geometry while inside CombatManager.missedShot so 2-arg addEffect tracers recover their
     * 3D endpoint in both isometric (missedShot x,y,z) and Viewpoint FP/3P (eyeNow + lookDirection), while
     * 6-arg addEffect tracers keep Viewpoint's Shot.end regardless of mod load order. */
    public static void enterMissedShot() {
        if (missedDepth++==0) {
            pendingEffect=null;pendingPlayer=null;pendingRange=0f;pendingTerminal=null;pendingArgsLen=0;
        }
    }
    public static void exitMissedShot(zombie.characters.IsoGameCharacter actor,float range,float x,float y,float z,Throwable thrown) {
        int depth=missedDepth>0?--missedDepth:0;
        Object effect=pendingEffect;
        var player=pendingPlayer;
        var terminal=pendingTerminal;
        int argsLen=pendingArgsLen;
        float shotRange=pendingRange>0?pendingRange:range;
        if (depth==0) {
            pendingEffect=null;pendingPlayer=null;pendingRange=0f;pendingTerminal=null;pendingArgsLen=0;
        }
        if (depth!=0 || thrown!=null || effect==null || player==null || actor!=player) return;
        try {
            boolean perspective=ViewpointBridge.perspective();
            zombie.iso.IsoGridSquare effectiveTerminal=perspective?null:terminal;
            Pz overrideOrigin=null,overrideEnd=null;
            if (perspective) {
                if (argsLen==2) {
                    Pz[] sight=vpSightRay(shotRange);
                    if (sight!=null) { overrideOrigin=sight[0];overrideEnd=sight[1]; }
                }
            } else if (Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
                    && (Math.abs(x)>1f || Math.abs(y)>1f)) {
                overrideEnd=new Pz(x,y,z);
            }
            long nowNs=System.nanoTime();
            nativeRange(effect,player,effectiveTerminal,overrideOrigin,overrideEnd,shotRange,
                    recentWindow(nowNs),recentVehicleWindow(nowNs));
        } catch (Throwable error) { nativeUnknown++;nativeReason=error.toString(); }
    }

    /** Observe native endpoints; range-only tracers admit geometry-based cosmetics, not hit/damage proof.
     * The native engine still owns all damage, ammo, sound and projectile effects.
     */
    public static void nativeTracer(Object[] args,Object result) {
        nativeCalls++;
        nativeReason="args:"+args.length+",result:"+(result!=null);
        if (result==null || !ImpactMarks.enabled || ImpactMarks.failed) return;
        if (args.length!=2 && args.length!=6) return;
        try {
            if (!(args[0] instanceof zombie.characters.IsoPlayer player) || !player.isLocalPlayer()
                    || !(player.getPrimaryHandItem() instanceof zombie.inventory.types.HandWeapon weapon)
                    || !weapon.getFullType().startsWith("EscapeFromZomboidFirearms.")) return;
            // Per-shot check: skip only when ShotOwner actually claims or shadow-traces THIS shot.
            // Unowned shots (isometric view, Debug Unlimited Ammo without chambered cartridge, AnimVanillaFirst,
            // ViewpointBallistics=Off, buckshot, muzzle-blocked fallback) receive cosmetic marks here.
            String owned=ownerReason(player,weapon);
            if (owned!=null) { nativeSkipped++;nativeReason=owned;return; }
            float range=args[1] instanceof Number n?n.floatValue():0f;
            zombie.iso.IsoGridSquare terminal=args.length==6 && args[5] instanceof zombie.iso.IsoGridSquare square?square:null;
            if (missedDepth>0) {
                pendingEffect=result;pendingPlayer=player;pendingRange=range;pendingTerminal=terminal;pendingArgsLen=args.length;
                return;
            }
            nativeDirect(result,player,weapon,range,terminal);
        } catch (Throwable error) { nativeUnknown++;nativeReason=error.toString(); }
    }
    public static long nativeSkipped;
    private static java.lang.reflect.Field tracerStart,tracerEnd;
    private static void ensureTracerFields() throws Exception {
        if (tracerStart!=null) return;
        Class<?> type=zombie.iso.objects.IsoBulletTracerEffects.Effect.class;
        var start=type.getDeclaredField("start");var end=type.getDeclaredField("end");
        for (var f:new java.lang.reflect.Field[]{start,end}) {
            if (f.getType()!=org.joml.Vector3f.class || java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                throw new IllegalStateException("native tracer endpoint ABI");
            f.setAccessible(true);
        }
        tracerStart=start;tracerEnd=end;
    }
    private static Pz[] vpSightRay(float range) {
        var eye=ViewpointBridge.eyeNow();
        float[] dir=new float[3];
        if (eye==null || !ViewpointBridge.lookDirection(dir)) return null;
        double ox=eye.x+eye.aheadX,oy=eye.y+eye.aheadY,oz=eye.z+eye.aheadZ;
        double r=range>0?range:35.0;
        return new Pz[]{
            new Pz(ox,oy,oz),
            new Pz(ox-dir[0]*r,oy-dir[2]*r,oz+(dir[1]/ViewpointBridge.LEVEL)*r)
        };
    }
    private static Pz barrelRayEnd(zombie.characters.IsoPlayer player,Pz origin,float range) {
        float r=range>0?range:35f;
        var bc=player.getBallisticsController();
        if (bc!=null) {
            var s=new zombie.iso.Vector3();var d=new zombie.iso.Vector3();
            bc.calculateMuzzlePosition(s,d);
            if (d.getLength()>1e-5f) {
                d.normalize();
                return new Pz(s.x+d.x*r,s.y+d.y*r,s.z+d.z*r);
            }
        }
        var fwd=player.getForwardDirection();
        double len=fwd==null?0:Math.hypot(fwd.x,fwd.y);
        double dx=len>1e-5?fwd.x/len:1,dy=len>1e-5?fwd.y/len:0;
        double pitch=Math.toRadians(player.getCurrentVerticalAimAngle());
        double cp=Math.cos(pitch),sp=Math.sin(pitch);
        double dz=sp*(TarkovBallistics.METRES_PER_CELL/TarkovBallistics.METRES_PER_LEVEL);
        return new Pz(origin.x()+dx*cp*r,origin.y()+dy*cp*r,origin.z()+dz*r);
    }
    private static void nativeDirect(Object effect,zombie.characters.IsoPlayer player,
            zombie.inventory.types.HandWeapon weapon,float range,zombie.iso.IsoGridSquare terminal) throws Exception {
        ensureTracerFields();
        var a=new org.joml.Vector3f((org.joml.Vector3f)tracerStart.get(effect));
        var b=new org.joml.Vector3f((org.joml.Vector3f)tracerEnd.get(effect));
        Pz origin=new Pz(a.x,a.y,a.z);
        var hits=player.getHitInfoList();
        zombie.iso.objects.IsoWindow hitWindow=null;
        Pz overrideOrigin=null,overrideEnd=null;
        boolean perspective=ViewpointBridge.perspective();
        if (!weapon.isRangeFalloff()) {
            if (hits!=null && !hits.isEmpty() && hits.get(0) instanceof zombie.network.fields.hit.HitInfo hit) {
                var moving=hit.getObject();var win=hit.getWindow();
                if (moving!=null && !(moving instanceof zombie.vehicles.BaseVehicle) && win==null) {
                    nativeSkipped++;nativeReason="native-character-hit";return;
                }
                if (win!=null) {
                    hitWindow=win;
                    if (Math.abs(b.z-win.getZ())<=1e-3f) {
                        if (perspective) {
                            Pz[] sight=vpSightRay(range);
                            if (sight!=null) { overrideOrigin=sight[0];overrideEnd=sight[1]; }
                        } else {
                            overrideEnd=barrelRayEnd(player,origin,range);
                        }
                    }
                } else if (moving instanceof zombie.vehicles.BaseVehicle
                        && Math.abs(b.z-moving.getZ())<=1e-3f) {
                    if (perspective) {
                        Pz[] sight=vpSightRay(range);
                        if (sight!=null) { overrideOrigin=sight[0];overrideEnd=sight[1]; }
                    } else {
                        overrideEnd=barrelRayEnd(player,origin,range);
                    }
                }
            }
        } else if (hits!=null) {
            for (int i=0;i<hits.size();i++) {
                if (!(hits.get(i) instanceof zombie.network.fields.hit.HitInfo hit)) continue;
                var moving=hit.getObject();var win=hit.getWindow();
                if (win!=null && hitWindow==null) hitWindow=win;
                if (terminal==null && moving!=null && !(moving instanceof zombie.vehicles.BaseVehicle) && win==null
                        && Math.hypot(b.x-hit.x,b.y-hit.y)<0.2) {
                    nativeSkipped++;nativeReason="native-character-hit";return;
                }
            }
        }
        long nowNs=System.nanoTime();
        if (hitWindow==null) hitWindow=recentWindow(nowNs);
        nativeRange(effect,player,perspective?null:terminal,overrideOrigin,overrideEnd,range,
                hitWindow,recentVehicleWindow(nowNs));
    }

    private record NativeHit(double fraction,Vec3 normal,String preset,boolean decal,boolean vehicle,
            String token,String sprite,String proof) {
        NativeHit(double fraction,Vec3 normal,String preset,boolean decal,boolean vehicle) {
            this(fraction,normal,preset,decal,vehicle,"","","");
        }
    }
    private static Vec3 metric(Pz from,double x,double y,double z) {
        return new Vec3((float)((x-from.x())*TarkovBallistics.METRES_PER_CELL),
                (float)((z-from.z())*TarkovBallistics.METRES_PER_LEVEL),(float)((y-from.y())*TarkovBallistics.METRES_PER_CELL));
    }
    private static ShotContacts.Face face(Pz origin,zombie.iso.IsoGridSquare sq,int axis) {
        Vec3 min=metric(origin,sq.getX(),sq.getY(),sq.getZ()),max=metric(origin,sq.getX()+1,sq.getY()+1,sq.getZ()+1);
        float value=(float)ShotContacts.get(min,axis);
        if (axis==0) max=new Vec3(value,max.y(),max.z());
        else if (axis==1) max=new Vec3(max.x(),value,max.z());else max=new Vec3(max.x(),max.y(),value);
        return new ShotContacts.Face(axis,value,min,max);
    }
    private static ShotContacts.Box subBox(Pz origin,zombie.iso.IsoGridSquare sq,double x0,double x1,double y0,double y1,double z0,double z1) {
        return new ShotContacts.Box(metric(origin,sq.getX()+x0,sq.getY()+y0,sq.getZ()+z0),
                metric(origin,sq.getX()+x1,sq.getY()+y1,sq.getZ()+z1));
    }
    private static NativeHit closer(NativeHit old,ShotContacts.Hit hit,String preset,boolean decal,boolean vehicle,
            String token,String sprite) {
        return hit!=null && hit.fraction()>1e-5 && (old==null || hit.fraction()<old.fraction())
                ?new NativeHit(hit.fraction(),hit.normal(),preset,decal,vehicle,token,sprite,""):old;
    }
    /** The cosmetic preset of a native surface, by the same table the shot owner uses. */
    private static String cosmetic(String token,zombie.iso.IsoObject object) {
        if (object==null) return ImpactSurfaces.fallback(token);
        var props=object.getProperties();
        String preset=ImpactSurfaces.preset(token,object.getSpriteName(),
                props==null?null:props.get("MaterialType"),props==null?null:props.get("Material"));
        return preset!=null?preset:ImpactSurfaces.fallback(token);
    }
    /** A persistent hole is not placed on a whole-tile envelope (tree, bush, vehicle shell). */
    private static boolean decal(String token,zombie.iso.IsoObject object) {
        return ImpactSurfaces.decal(token) && !ImpactMarks.soft(object==null?null:object.getSpriteName());
    }
    private static String barricadePreset(zombie.iso.objects.IsoBarricade b) {
        if (b==null || b.isDestroyed()) return null;
        if (b.isMetal() || b.isMetalBar()) return "MetalThin";
        if (b.getNumPlanks()>0) return "WoodThin";
        return null;
    }
    private static String strongerBarricade(String a,String b) {
        if (a==null) return b;
        if (b==null) return a;
        return "MetalThin".equals(a) || "MetalThin".equals(b)?"MetalThin":a;
    }
    private static boolean positiveProp(zombie.core.properties.PropertyContainer p,String key) {
        if (p==null || !p.has(key)) return false;
        try { return Double.parseDouble(p.get(key))>0; }
        catch (NumberFormatException | NullPointerException ex) { return true; }
    }
    private static double parseProp(zombie.core.properties.PropertyContainer p,String key) {
        if (p==null || !p.has(key)) return -1;
        try { return Double.parseDouble(p.get(key)); }
        catch (NumberFormatException | NullPointerException ex) { return -1; }
    }
    private static double volumeTopFraction(zombie.core.properties.PropertyContainer p,boolean hasSurface) {
        double px=Math.max(parseProp(p,"ItemHeight"),parseProp(p,"Surface"));
        if (px>0) return Math.clamp(px/96.0,0.08,1.0);
        if (p.has(IsoFlagType.halfheight) || p.has("IsLow")) return 0.36;
        if (p.has(IsoFlagType.bed) || p.has("SeatMaterial")) return 0.34;
        if (p.has(IsoFlagType.solidtrans)) return p.has(IsoFlagType.blocksight)?0.85:0.42;
        return hasSurface && !p.has(IsoFlagType.solid)?0.36:1.0;
    }
    private static NativeHit windowSurround(NativeHit best,ShotContacts.Face boundary,boolean fullWindow,
            String preset,String sprite,Vec3 to) {
        double u0=fullWindow?0.04:0.06,u1=fullWindow?0.96:0.94,v0=fullWindow?0.10:0.27,v1=fullWindow?0.90:0.82;
        best=closer(best,ShotContacts.subFace(boundary,0,1,0,v0).intersect(Vec3.ZERO,to),preset,true,false,"frame",sprite);
        best=closer(best,ShotContacts.subFace(boundary,0,1,v1,1).intersect(Vec3.ZERO,to),preset,true,false,"frame",sprite);
        best=closer(best,ShotContacts.subFace(boundary,0,u0,v0,v1).intersect(Vec3.ZERO,to),preset,true,false,"frame",sprite);
        best=closer(best,ShotContacts.subFace(boundary,u1,1,v0,v1).intersect(Vec3.ZERO,to),preset,true,false,"frame",sprite);
        return best;
    }
    private static boolean hasWindowSurround(zombie.iso.IsoGridSquare sq,zombie.iso.IsoObject exclude,IsoFlagType flag) {
        var objs=sq.getObjects();
        if (objs==null) return false;
        for (int i=0;i<objs.size();i++) {
            var o=objs.get(i);
            if (o!=exclude && o.getProperties()!=null && o.getProperties().has(flag)) return true;
        }
        return false;
    }

    /** Intersect the unowned shot's 3D trajectory with loaded native cell and vehicle geometry for cosmetics only.
     * No shot snapshot, invented ammunition, damage request or replacement flight is created.
     */
    private static void nativeRange(Object effect,zombie.characters.IsoPlayer player,zombie.iso.IsoGridSquare terminal,
            Pz overrideOrigin,Pz overrideEnd,float shotRange,zombie.iso.objects.IsoWindow hitWindow,
            zombie.vehicles.VehicleWindow hitVehicleWindow) throws Exception {
        ensureTracerFields();
        var a=new org.joml.Vector3f((org.joml.Vector3f)tracerStart.get(effect));
        var b=new org.joml.Vector3f((org.joml.Vector3f)tracerEnd.get(effect));
        Pz origin=overrideOrigin!=null?overrideOrigin:new Pz(a.x,a.y,a.z);
        Pz end=overrideEnd!=null?overrideEnd:new Pz(b.x,b.y,b.z);
        Vec3 to=metric(origin,end.x(),end.y(),end.z());double distance=to.length();
        if (!(distance>0 && distance<2000)) {nativeUnknown++;nativeReason="invalid-native-range";return;}
        if (terminal!=null && overrideEnd==null) {
            double c=TarkovBallistics.METRES_PER_CELL,h=TarkovBallistics.METRES_PER_LEVEL,diag=Math.sqrt(2*c*c+h*h);
            double targetDist=distance+diag;
            to=to.times((float)(targetDist/distance));distance=to.length();
        } else if (terminal==null) {
            double maxMetres=shotRange>0?shotRange*TarkovBallistics.METRES_PER_CELL:Double.POSITIVE_INFINITY;
            if (distance<maxMetres-0.25) {
                to=to.times((float)((distance+0.35)/distance));distance=to.length();
            }
        }
        nativeObserved++;
        var cell=zombie.iso.IsoWorld.instance.getCell();
        var visited=new java.util.HashSet<Long>();java.util.HashSet<String> visitedFar=null;
        NativeHit best=null;double unloaded=Double.POSITIVE_INFINITY;
        int stepX=to.x()>1e-4f?1:to.x()<-1e-4f?-1:0;
        int stepY=to.z()>1e-4f?1:to.z()<-1e-4f?-1:0;
        int pieces=(int)Math.ceil(distance/12);
        for (int piece=0;piece<pieces;piece++) {
            if (best!=null && best.fraction()+0.05<(double)piece/pieces) break;
            Pz begin=origin.offset(to.times(piece/(float)pieces)),finish=origin.offset(to.times((piece+1)/(float)pieces));
            for (var tile:ShotContacts.cells(begin,finish)) {
                if (terminal!=null && overrideEnd==null) {
                    if (stepX!=0 && (tile.x()-terminal.getX())*stepX>1) continue;
                    if (stepY!=0 && (tile.y()-terminal.getY())*stepY>1) continue;
                    if (Math.abs(tile.z()-terminal.getZ())>1) continue;
                }
                int tx=tile.x(),ty=tile.y(),tz=tile.z();
                if (tx>=-(1<<20) && tx<(1<<20) && ty>=-(1<<20) && ty<(1<<20) && tz>=-(1<<19) && tz<(1<<19)) {
                    if (!visited.add(((long)(tx&0x1FFFFF)<<41)|((long)(ty&0x1FFFFF)<<20)|(tz&0xFFFFF))) continue;
                } else {
                    if (visitedFar==null) visitedFar=new java.util.HashSet<>();
                    if (!visitedFar.add(tx+":"+ty+":"+tz)) continue;
                }
                var sq=cell.getGridSquare(tile.x(),tile.y(),tile.z());
                if (sq==null) {
                    if (tile.z()!=0 && cell.getGridSquare(tile.x(),tile.y(),0)!=null) continue;
                    unloaded=Math.min(unloaded,(piece+tile.entry())/pieces);continue;
                }
                for (int list=0;list<2;list++) {
                    var listObjects=list==0?sq.getObjects():sq.getSpecialObjects();
                    if (listObjects==null) continue;
                    for (int i=0;i<listObjects.size();i++) {
                        var object=listObjects.get(i);
                        if (object instanceof zombie.iso.objects.IsoCurtain) continue;
                        var props=object.getProperties();
                        if (object==sq.getFloor()) {
                            if (props!=null && Math.abs(to.y())>1e-7)
                                best=closer(best,face(origin,sq,1).intersect(Vec3.ZERO,to),
                                        cosmetic("floor",object),true,false,"floor",object.getSpriteName());
                            continue;
                        }
                        boolean stair=object.getSpriteName()!=null && object.getSpriteName().startsWith("fixtures_stairs");
                        if (stair) {
                            var box=subBox(origin,sq,0,1,0,1,0,1);
                            best=closer(best,box.intersect(Vec3.ZERO,to),cosmetic("stairs",object),true,false,"stairs",object.getSpriteName());
                            continue;
                        }
                        if (object instanceof zombie.iso.objects.IsoBarricade barricade) {
                            if (!barricade.isDestroyed() && barricade.getBarricadedObject()==null) {
                                String bp=barricadePreset(barricade);
                                if (bp==null) bp=cosmetic("barricade",barricade);
                                best=closer(best,ShotContacts.subFace(face(origin,sq,0),0,1,0,0.86).intersect(Vec3.ZERO,to),
                                        bp,true,false,"barricade",barricade.getSpriteName());
                            }
                            continue;
                        }
                        if (object instanceof zombie.iso.objects.IsoWindow window) {
                            boolean north=window.getNorth();
                            boolean full=props!=null && props.has("FullWindow");
                            var boundary=face(origin,sq,north?2:0);
                            if (!hasWindowSurround(sq,window,north?IsoFlagType.WindowN:IsoFlagType.WindowW))
                                best=windowSurround(best,boundary,full,cosmetic("frame",window),window.getSpriteName(),to);
                            double u0=full?0.04:0.06,u1=full?0.96:0.94,v0=full?0.10:0.27,v1=full?0.90:0.82;
                            var opening=ShotContacts.subFace(boundary,u0,u1,v0,v1);
                            String bp=strongerBarricade(barricadePreset(window.getBarricadeOnSameSquare()),
                                    barricadePreset(window.getBarricadeOnOppositeSquare()));
                            if (window.isBarricaded() || bp!=null) {
                                best=closer(best,opening.intersect(Vec3.ZERO,to),
                                        bp!=null?bp:cosmetic("barricade",window),true,false,"barricade",window.getSpriteName());
                            } else if (windowGlassHittable(window,hitWindow)
                                    && window!=null) {
                                best=closer(best,opening.intersect(Vec3.ZERO,to),"Glass",true,false,"glass",window.getSpriteName());
                            }
                            continue;
                        }
                        if (object instanceof zombie.iso.objects.IsoDoor door) {
                            if (door.isDestroyed()) continue;
                            String sound=props==null?null:props.get("DoorSound");
                            if (door.IsOpen() && ("GarageDoor".equals(sound) || "SlidingGlassDoor".equals(sound))) continue;
                            double top="GarageDoor".equals(sound)?0.92
                                    :(props!=null && (props.has(IsoFlagType.HoppableN) || props.has(IsoFlagType.HoppableW)
                                        || "WoodGateSmall".equals(sound) || "MetalPoleGateSmall".equals(sound))?0.36:0.86);
                            var collider=ShotContacts.doorLeaf("native-visual",face(origin,sq,2),face(origin,sq,0),door.getNorth(),door.IsOpen(),top);
                            String bp=strongerBarricade(barricadePreset(door.getBarricadeOnSameSquare()),
                                    barricadePreset(door.getBarricadeOnOppositeSquare()));
                            String leaf=bp!=null?bp:(props==null?null:ShotWorldMaterials.lookup("thumpable",props.get("MaterialType"),props.get("Material")));
                            String tok=bp!=null?"barricade":"door";
                            if (leaf==null) leaf=cosmetic(tok,object);
                            best=closer(best,collider.shape().intersect(Vec3.ZERO,to),leaf,decal(tok,object),false,tok,object.getSpriteName());
                            continue;
                        }
                        if (object instanceof zombie.iso.objects.IsoThumpable thumpable) {
                            best=nativeThumpable(best,origin,sq,thumpable,props,to);
                            continue;
                        }
                        if (props==null) continue;
                        if (props.has(IsoFlagType.water) || props.has(IsoFlagType.solidfloor)
                                || props.has(IsoFlagType.FloorOverlay) || props.has(IsoFlagType.attachedCeiling)
                                || props.has(IsoFlagType.attachedFloor)) continue;
                        boolean tree=object instanceof zombie.iso.objects.IsoTree || props.has("tree");
                        if (tree) {
                            String tp=ShotWorldMaterials.lookup("tree",props.get("MaterialType"),props.get("Material"));
                            if (tp==null) tp=cosmetic("tree",object);
                            best=closer(best,subBox(origin,sq,0,1,0,1,0,1).intersect(Vec3.ZERO,to),tp,false,false,"tree",object.getSpriteName());
                            continue;
                        }
                        boolean bush=object.isBush() || props.has("Bush") || props.has(IsoFlagType.vegitation) || props.has(IsoFlagType.canBeCut);
                        if (bush) {
                            if (ImpactMarks.soft(object.getSpriteName()))
                                best=closer(best,subBox(origin,sq,0,1,0,1,0,1).intersect(Vec3.ZERO,to),
                                        cosmetic("vegetation",object),false,false,"vegetation",object.getSpriteName());
                            continue;
                        }
                        boolean anyEdge=false;
                        for (int axis:new int[]{0,2}) {
                            boolean north=axis==2;
                            boolean winFlag=props.has(north?IsoFlagType.WindowN:IsoFlagType.WindowW);
                            boolean doorFlag=props.has(north?IsoFlagType.DoorWallN:IsoFlagType.DoorWallW);
                            boolean lowHop=props.has(north?IsoFlagType.HoppableN:IsoFlagType.HoppableW);
                            boolean tallHop=props.has(north?IsoFlagType.TallHoppableN:IsoFlagType.TallHoppableW);
                            boolean colFlag=props.has(north?IsoFlagType.collideN:IsoFlagType.collideW);
                            boolean wallFlag=props.has(north?IsoFlagType.WallN:IsoFlagType.WallW) || props.has(IsoFlagType.WallNW);
                            if (!(winFlag || doorFlag || lowHop || tallHop || colFlag || wallFlag)) continue;
                            anyEdge=true;
                            var boundary=face(origin,sq,axis);
                            var other=cell.getGridSquare(sq.getX()-(north?0:1),sq.getY()-(north?1:0),sq.getZ());
                            var winObj=other==null?null:(sq.getWindowTo(other)!=null?sq.getWindowTo(other):other.getWindowTo(sq));
                            var doorObj=other==null?null:(sq.getDoorTo(other)!=null?sq.getDoorTo(other):other.getDoorTo(sq));
                            if ((winObj!=null || doorObj!=null) && !winFlag && !doorFlag) continue;
                            if (winFlag) {
                                boolean full=props.has("FullWindow") || (winObj!=null && winObj.getProperties()!=null && winObj.getProperties().has("FullWindow"));
                                String fp=ShotWorldMaterials.lookup("wall",props.get("MaterialType"),props.get("Material"));
                                if (fp==null) fp=cosmetic("frame",object);
                                best=windowSurround(best,boundary,full,fp,object.getSpriteName(),to);
                                continue;
                            }
                            if (doorFlag) {
                                String fp=ShotWorldMaterials.lookup("wall",props.get("MaterialType"),props.get("Material"));
                                if (fp==null) fp=cosmetic("frame",object);
                                best=closer(best,ShotContacts.subFace(boundary,0,1,0.86,1).intersect(Vec3.ZERO,to),
                                        fp,true,false,"frame",object.getSpriteName());
                                continue;
                            }
                            String typed=ShotWorldMaterials.lookup("wall",props.get("MaterialType"),props.get("Material"));
                            if (props.has(IsoFlagType.WallOverlay) && typed==null) continue;
                            String fenceLow=props.get("FenceTypeLow");
                            boolean lowFence=lowHop || colFlag && (fenceLow!=null || props.has(IsoFlagType.halfheight) || props.has("IsLow"));
                            boolean tallFence=!lowFence && (tallHop || colFlag && props.get("FenceTypeHigh")!=null);
                            if (lowFence || tallFence || colFlag) {
                                double top="RoadBlock".equals(fenceLow)?0.24:lowFence?0.36:tallFence?0.85:1.0;
                                var shaped=top>=0.999?boundary:ShotContacts.subFace(boundary,0,1,0,top);
                                String tok=(lowFence || tallFence)?"fence":"wall";
                                String ep=ShotWorldMaterials.lookup(tok,props.get("MaterialType"),props.get("Material"));
                                if (ep==null) ep=cosmetic(tok,object);
                                best=closer(best,shaped.intersect(Vec3.ZERO,to),ep,decal(tok,object),false,tok,object.getSpriteName());
                                continue;
                            }
                            // A plain vanilla wall carries IsoFlagType.WallW/WallN/WallNW without collideW/collideN.
                            best=closer(best,boundary.intersect(Vec3.ZERO,to),
                                    cosmetic("unmeasured-opaque-edge",object),true,false,"unmeasured-opaque-edge",object.getSpriteName());
                        }
                        if (anyEdge) continue;
                        if (props.has(IsoFlagType.ontable) || props.has(IsoFlagType.attachedSurface)
                                || props.has("IsTableTop") || props.has("IsSurfaceOffset")) continue;
                        boolean hasSurface=positiveProp(props,"Surface");
                        if (props.has(IsoFlagType.solid) || props.has(IsoFlagType.solidtrans) || hasSurface) {
                            double top=volumeTopFraction(props,hasSurface);
                            var box=subBox(origin,sq,0,1,0,1,0,top);
                            String solid=ShotWorldMaterials.lookup("furniture",props.get("MaterialType"),props.get("Material"));
                            if (solid==null) solid=cosmetic("solid-object",object);
                            NativeHit found=closer(best,box.intersect(Vec3.ZERO,to),solid,decal("solid-object",object),false,"solid-object",object.getSpriteName());
                            if (found!=best) found=new NativeHit(found.fraction(),found.normal(),found.preset(),found.decal(),false,
                                    found.token(),found.sprite(),
                                    object.getObjectName()+"/"+(object.getSprite()==null?"no-sprite":object.getSprite().getName())
                                    +"/"+props.get("MaterialType")+"/"+props.get("Material"));
                            best=found;
                        }
                    }
                }
            }
        }
        for (var vehicle:cell.getVehicles()) best=nativeVehicle(best,origin,to,vehicle,hitVehicleWindow);
        if (best==null) {nativeReason="native-range:no-loaded-surface";return;}
        if (unloaded<best.fraction()-1e-6) {nativeUnknown++;nativeReason="native-range:unloaded-before-surface";return;}
        Pz point=origin.offset(to.times((float)best.fraction()));
        if (best.preset()==null || ImpactData.profile(best.preset())==null) {
            nativeUnknown++;nativeReason="native-range:unproven-material@"+point+" "+best.proof()+" ray="+origin+"->"+end;return;
        }
        ImpactMarks.resolved(best.token()==null || best.token().isEmpty()?"native":best.token(),best.sprite(),
                best.preset(),best.decal());
        String id="native/"+player.getOnlineID()+"/"+System.nanoTime()+"/"+(++nativeSequence);
        boolean sound=terminal==null;
        if (ImpactMarks.STORE.accept(new Event(id,best.preset(),point,best.normal(),to.unit(),"STOP",true,best.vehicle(),nativeSequence,sound,best.decal()),true)) nativeAccepted++;
        nativeReason=(terminal==null?"native-range:":"native-terminal-tile:")+best.preset()
                +(best.vehicle()?(best.decal()?":vehicle-part":":vehicle-particle-proxy"):!best.decal()?":tree-particle-envelope":"");
    }

    private static NativeHit nativeThumpable(NativeHit best,Pz origin,zombie.iso.IsoGridSquare sq,
            zombie.iso.objects.IsoThumpable object,zombie.core.properties.PropertyContainer p,Vec3 to) {
        if (object.isDestroyed()) return best;
        String sprite=object.getSpriteName();
        if (p==null || object.isStairs())
            return closer(best,subBox(origin,sq,0,1,0,1,0,1).intersect(Vec3.ZERO,to),
                    cosmetic("stairs",object),true,false,"stairs",sprite);
        if (object.isFloor()) {
            if (Math.abs(to.y())<=1e-7) return best;
            String fp=ImpactSurfaces.preset("floor",sprite,p.get("MaterialType"),p.get("Material"));
            if (fp==null) fp=ImpactSurfaces.fallback("floor");
            return closer(best,face(origin,sq,1).intersect(Vec3.ZERO,to),fp,true,false,"floor",sprite);
        }
        String bp=strongerBarricade(barricadePreset(object.getBarricadeOnSameSquare()),
                barricadePreset(object.getBarricadeOnOppositeSquare()));
        if (object.isBlockAllTheSquare() || p.has(IsoFlagType.solid) || p.has(IsoFlagType.solidtrans) || positiveProp(p,"Surface")) {
            double top=object.isBlockAllTheSquare() && parseProp(p,"ItemHeight")<=0 && parseProp(p,"Surface")<=0
                    ?1.0:volumeTopFraction(p,positiveProp(p,"Surface"));
            String sp=ShotWorldMaterials.lookup("furniture",p.get("MaterialType"),p.get("Material"));
            if (sp==null) sp=cosmetic("solid-object",object);
            return closer(best,subBox(origin,sq,0,1,0,1,0,top).intersect(Vec3.ZERO,to),
                    sp,decal("solid-object",object),false,"solid-object",sprite);
        }
        String tp=ShotWorldMaterials.lookup("thumpable",p.get("MaterialType"),p.get("Material"));
        if (object.isCorner()) {
            String cp=tp!=null?tp:cosmetic("wall",object);
            best=closer(best,face(origin,sq,2).intersect(Vec3.ZERO,to),cp,decal("wall",object),false,"wall",sprite);
            return closer(best,face(origin,sq,0).intersect(Vec3.ZERO,to),cp,decal("wall",object),false,"wall",sprite);
        }
        boolean north=object.getNorth();
        if (object.isDoor()) {
            double top=(p.has(IsoFlagType.HoppableN) || p.has(IsoFlagType.HoppableW))?0.36:0.86;
            var leaf=ShotContacts.doorLeaf("native-thump-door",face(origin,sq,2),face(origin,sq,0),north,object.IsOpen(),top);
            String dp=bp!=null?bp:(tp!=null?tp:cosmetic("door",object));
            String tok=bp!=null?"barricade":"door";
            return closer(best,leaf.shape().intersect(Vec3.ZERO,to),dp,decal(tok,object),false,tok,sprite);
        }
        if (object.isDoorFrame()) {
            String fp=tp!=null?tp:cosmetic("frame",object);
            return closer(best,ShotContacts.subFace(face(origin,sq,north?2:0),0,1,0.86,1).intersect(Vec3.ZERO,to),
                    fp,true,false,"frame",sprite);
        }
        if (object.isWindowN() || object.isWindowW()) {
            String fp=tp!=null?tp:cosmetic("frame",object);
            return windowSurround(best,face(origin,sq,object.isWindowN()?2:0),p.has("FullWindow"),fp,sprite,to);
        }
        boolean hop=object.isHoppable() || p.has(IsoFlagType.HoppableN) || p.has(IsoFlagType.HoppableW) || p.get("FenceTypeLow")!=null;
        boolean tall=object.isTallHoppable() || p.has(IsoFlagType.TallHoppableN) || p.has(IsoFlagType.TallHoppableW) || p.get("FenceTypeHigh")!=null;
        if (hop || tall) {
            double top=hop?0.36:0.85;
            String fp=bp!=null?bp:(tp!=null?tp:cosmetic("fence",object));
            String tok=bp!=null?"barricade":"fence";
            return closer(best,ShotContacts.subFace(face(origin,sq,north?2:0),0,1,0,top).intersect(Vec3.ZERO,to),
                    fp,decal(tok,object),false,tok,sprite);
        }
        if (object.IsOpen() || object.isCanPassThrough()) return best;
        String wp=bp!=null?bp:(tp!=null?tp:cosmetic("wall",object));
        String tok=bp!=null?"barricade":"wall";
        return closer(best,face(origin,sq,north?2:0).intersect(Vec3.ZERO,to),wp,decal(tok,object),false,tok,sprite);
    }

    /** Same native script-area proxies as ShotContactsPz: persistent decals on stationary vehicle doors,
     * windows, engines and tyres; puff-only on unassigned vehicle shells and on moving vehicles.
     */
    private static NativeHit nativeVehicle(NativeHit best,Pz origin,Vec3 to,zombie.vehicles.BaseVehicle vehicle,
            zombie.vehicles.VehicleWindow hitVehicleWindow) {
        var script=vehicle.getScript();var parts=vehicle.getParts();var sim=zombie.core.physics.WorldSimulation.instance;
        if (script==null || parts==null || sim==null) return best;
        var ext=script.getExtents();var centre=script.getCenterOfMassOffset();
        if (!(ext.x>0 && ext.y>0 && ext.z>0)) return best;
        var transform=vehicle.getWorldTransform(new zombie.core.physics.Transform());
        if (missesBounds(transform,sim,origin,to,ext,centre)) return best;
        var rotation=new zombie.core.physics.Transform(transform);rotation.origin.set(0,0,0);
        var inverse=new zombie.core.physics.Transform(rotation);inverse.inverse();
        Vec3 offset=new Vec3((float)((transform.origin.x+sim.offsetX-origin.x())*TarkovBallistics.METRES_PER_CELL),
                (float)(transform.origin.y*TarkovBallistics.METRES_PER_CELL-origin.z()*TarkovBallistics.METRES_PER_LEVEL),
                (float)((transform.origin.z+sim.offsetY-origin.y())*TarkovBallistics.METRES_PER_CELL));
        java.util.function.Function<Vec3,Vec3> local=point -> {
            var delta=point.minus(offset).times((float)(1/TarkovBallistics.METRES_PER_CELL));
            var v=new org.joml.Vector3f(delta.x(),delta.y(),delta.z());inverse.transform(v);return new Vec3(v.x,v.y,v.z);
        };
        Vec3 a=local.apply(Vec3.ZERO),b=local.apply(to);
        Vec3 min=new Vec3(centre.x-ext.x/2,centre.y-ext.y/2,centre.z-ext.z/2);
        Vec3 max=new Vec3(centre.x+ext.x/2,centre.y+ext.y/2,centre.z+ext.z/2);
        Vec3 broadMin=min,broadMax=max;
        for (int i=0;i<script.getWheelCount();i++) {
            var wheel=script.getWheel(i);var p=wheel.getOffset();var model=script.getModelOffset();
            float half=wheel.width*script.getModelScale()/2,r=wheel.radius;
            if (!(Float.isFinite(half) && half>0 && Float.isFinite(r) && r>0)) continue;
            broadMin=new Vec3(Math.min(broadMin.x(),p.x+model.x-half),Math.min(broadMin.y(),p.y+model.y-r),Math.min(broadMin.z(),p.z+model.z-r));
            broadMax=new Vec3(Math.max(broadMax.x(),p.x+model.x+half),Math.max(broadMax.y(),p.y+model.y+r),Math.max(broadMax.z(),p.z+model.z+r));
        }
        if (new ShotContacts.Box(broadMin,broadMax).intersect(a,b)==null) return best;
        float lowerTopY=max.y(),cabinMinZ=min.z(),cabinMaxZ=max.z();
        boolean splitShell=false;
        int shapeCount=script.getPhysicsShapeCount();
        if (shapeCount>0) {
            float minBottom=Float.POSITIVE_INFINITY;
            for (int i=0;i<shapeCount;i++) {
                var ps=script.getPhysicsShape(i);
                if (ps==null || ps.type!=1) continue;
                var so=ps.getOffset();var se=ps.getExtents();
                if (so==null || se==null || se.x<0.5f*ext.x) continue;
                minBottom=Math.min(minBottom,so.y-se.y*0.5f);
            }
            if (Float.isFinite(minBottom)) {
                float tol=0.08f*script.getModelScale(),bestZ=-1f,chassisTop=max.y();
                for (int i=0;i<shapeCount;i++) {
                    var ps=script.getPhysicsShape(i);
                    if (ps==null || ps.type!=1) continue;
                    var so=ps.getOffset();var se=ps.getExtents();
                    if (so==null || se==null || se.x<0.5f*ext.x) continue;
                    if (so.y-se.y*0.5f<=minBottom+tol && se.z>bestZ) {
                        bestZ=se.z;chassisTop=so.y+se.y*0.5f;
                    }
                }
                if (bestZ>0 && chassisTop<max.y()-0.02f*script.getModelScale()) {
                    lowerTopY=Math.clamp(chassisTop,min.y(),max.y());
                    float cMinZ=Float.POSITIVE_INFINITY,cMaxZ=Float.NEGATIVE_INFINITY;
                    for (int i=0;i<shapeCount;i++) {
                        var ps=script.getPhysicsShape(i);
                        if (ps==null || ps.type!=1) continue;
                        var so=ps.getOffset();var se=ps.getExtents();var sr=ps.getRotate();
                        if (so==null || se==null || se.x<0.5f*ext.x) continue;
                        double rx=sr==null?0:Math.toRadians(sr.x);
                        float halfY=(float)(Math.abs(Math.cos(rx))*se.y*0.5+Math.abs(Math.sin(rx))*se.z*0.5);
                        float halfZ=(float)(Math.abs(Math.sin(rx))*se.y*0.5+Math.abs(Math.cos(rx))*se.z*0.5);
                        if (so.y+halfY>lowerTopY+0.01f*script.getModelScale()) {
                            cMinZ=Math.min(cMinZ,so.z-halfZ);cMaxZ=Math.max(cMaxZ,so.z+halfZ);
                        }
                    }
                    if (cMinZ<cMaxZ) {
                        cabinMinZ=Math.max(min.z(),cMinZ);cabinMaxZ=Math.min(max.z(),cMaxZ);splitShell=true;
                    }
                }
            }
        }
        float panelSplit=splitShell?lowerTopY:centre.y;
        boolean stationary=Math.abs(vehicle.getCurrentSpeedKmHour())<=0.5f;
        var apertures=new java.util.ArrayList<ShotContacts.Face>();NativeHit hit=null;
        for (int i=0;i<parts.getPartCount();i++) {
            var part=parts.getPartByIndex(i);var window=part.getWindow();
            int wheelIdx=part.getWheelIndex();
            String kind=window!=null?"window":part==parts.getEngine()?"engine"
                    :(wheelIdx>=0 && wheelIdx<script.getWheelCount() && part.getId().equals("Tire"+script.getWheel(wheelIdx).getId()))?"tyre"
                    :part.getDoor()!=null?"body":null;
            if (kind==null) continue;
            ShotContacts.Shape localShape;
            if ("tyre".equals(kind)) {
                var wheel=script.getWheel(wheelIdx);var p=wheel.getOffset();var model=script.getModelOffset();
                float half=wheel.width*script.getModelScale()/2,r=wheel.radius;
                if (!(Float.isFinite(half) && half>0 && Float.isFinite(r) && r>0)) continue;
                localShape=new ShotContacts.Box(new Vec3(p.x+model.x-half,p.y+model.y-r,p.z+model.z-r),
                        new Vec3(p.x+model.x+half,p.y+model.y+r,p.z+model.z+r));
            } else {
                var area=script.getAreaById(part.getArea());if (area==null || !(area.w>0 && area.h>0)) continue;
                var face=ShotContactsPz.vehicleFace(area,min,max,cabinMinZ,cabinMaxZ,panelSplit,window!=null);if (face==null) continue;
                apertures.add(face);
                localShape=face;
                if ("engine".equals(kind)) {
                    float depth=face.axis()==0?area.w:area.h;
                    Vec3 lo=face.min(),hi=face.max();
                    if (face.axis()==0) {
                        lo=new Vec3(Math.max(min.x(),face.coordinate()-depth),lo.y(),lo.z());
                        hi=new Vec3(Math.min(max.x(),face.coordinate()+depth),hi.y(),hi.z());
                    } else {
                        lo=new Vec3(lo.x(),lo.y(),Math.max(min.z(),face.coordinate()-depth));
                        hi=new Vec3(hi.x(),hi.y(),Math.min(max.z(),face.coordinate()+depth));
                    }
                    localShape=new ShotContacts.Box(lo,hi);
                }
            }
            if (!ShotContactsPz.vehicleInstalled(part)) continue;
            var parent=part.getParent();String preset=ShotWorldMaterials.vehicle(kind);
            if (window!=null) {
                if (!vehicleWindowGlassHittable(window,part.getCondition(),hitVehicleWindow)
                        || parent!=null && !ShotContactsPz.vehicleInstalled(parent)) continue;
                if (window.isOpen() || !Float.isFinite(window.getOpenDelta()) || window.getOpenDelta()!=0
                        || parent!=null && parent.getDoor()!=null && parent.getDoor().isOpen()) preset=null;
            } else if (part.getDoor()!=null && part.getDoor().isOpen()) preset=null;
            String tok="vehicle-"+kind;
            boolean partDecal=preset!=null && stationary && ImpactSurfaces.decal(tok);
            hit=closer(hit,localShape.intersect(a,b),preset,partDecal,true,preset==null?null:tok,null);
        }
        if (!splitShell) {
            for (int axis=0;axis<3;axis++) for (boolean high:new boolean[]{false,true}) {
                float v=(float)ShotContacts.get(high?max:min,axis);
                Vec3 lo=axis==0?new Vec3(v,min.y(),min.z()):axis==1?new Vec3(min.x(),v,min.z()):new Vec3(min.x(),min.y(),v);
                Vec3 hi=axis==0?new Vec3(v,max.y(),max.z()):axis==1?new Vec3(max.x(),v,max.z()):new Vec3(max.x(),max.y(),v);
                var shell=new ShotContactsPz.VehicleShell(new ShotContacts.Face(axis,v,lo,hi),apertures);
                hit=closer(hit,shell.intersect(a,b),"MetalThin",false,true,"vehicle-bounds",null);
            }
        } else {
            ShotContacts.Face[] shellFaces={
                new ShotContacts.Face(0,min.x(),new Vec3(min.x(),min.y(),min.z()),new Vec3(min.x(),panelSplit,max.z())),
                new ShotContacts.Face(0,max.x(),new Vec3(max.x(),min.y(),min.z()),new Vec3(max.x(),panelSplit,max.z())),
                new ShotContacts.Face(0,min.x(),new Vec3(min.x(),panelSplit,cabinMinZ),new Vec3(min.x(),max.y(),cabinMaxZ)),
                new ShotContacts.Face(0,max.x(),new Vec3(max.x(),panelSplit,cabinMinZ),new Vec3(max.x(),max.y(),cabinMaxZ)),
                new ShotContacts.Face(2,min.z(),new Vec3(min.x(),min.y(),min.z()),new Vec3(max.x(),panelSplit,min.z())),
                new ShotContacts.Face(2,max.z(),new Vec3(min.x(),min.y(),max.z()),new Vec3(max.x(),panelSplit,max.z())),
                new ShotContacts.Face(2,cabinMinZ,new Vec3(min.x(),panelSplit,cabinMinZ),new Vec3(max.x(),max.y(),cabinMinZ)),
                new ShotContacts.Face(2,cabinMaxZ,new Vec3(min.x(),panelSplit,cabinMaxZ),new Vec3(max.x(),max.y(),cabinMaxZ)),
                new ShotContacts.Face(1,min.y(),new Vec3(min.x(),min.y(),min.z()),new Vec3(max.x(),min.y(),max.z())),
                new ShotContacts.Face(1,max.y(),new Vec3(min.x(),max.y(),cabinMinZ),new Vec3(max.x(),max.y(),cabinMaxZ)),
            };
            for (var sf:shellFaces) hit=closer(hit,new ShotContactsPz.VehicleShell(sf,apertures).intersect(a,b),"MetalThin",false,true,"vehicle-bounds",null);
            if (cabinMinZ>min.z()+1e-4f)
                hit=closer(hit,new ShotContactsPz.VehicleShell(new ShotContacts.Face(1,panelSplit,new Vec3(min.x(),panelSplit,min.z()),new Vec3(max.x(),panelSplit,cabinMinZ)),apertures).intersect(a,b),"MetalThin",false,true,"vehicle-bounds",null);
            if (cabinMaxZ<max.z()-1e-4f)
                hit=closer(hit,new ShotContactsPz.VehicleShell(new ShotContacts.Face(1,panelSplit,new Vec3(min.x(),panelSplit,cabinMaxZ),new Vec3(max.x(),panelSplit,max.z())),apertures).intersect(a,b),"MetalThin",false,true,"vehicle-bounds",null);
        }
        if (hit==null || best!=null && best.fraction()<=hit.fraction()) return best;
        var n=new org.joml.Vector3f(hit.normal().x(),hit.normal().y(),hit.normal().z());rotation.basis.transform(n);
        return new NativeHit(hit.fraction(),new Vec3(n.x,n.y,n.z).unit(),hit.preset(),hit.decal(),true,
                hit.token(),hit.sprite(),"");
    }
    /**
     * Performance pass 23.10.2026: every vehicle of the cell was framed (three transforms, the ray in its space and
     * a dozen vectors) before its box test. The ray is put in the vehicle's box space by the same operations as
     * nativeVehicle's (scratch objects) and tested against the sphere round that box first: a ray that passes the
     * sphere by more than a generous margin cannot meet the box (Box.intersect's tolerance is 1e-9 of the ray), so
     * only those are left out. A non-finite distance never leaves a vehicle out. Main thread.
     */
    private static final zombie.core.physics.Transform SPHERE_ROTATION=new zombie.core.physics.Transform(),SPHERE_INVERSE=new zombie.core.physics.Transform();
    private static final org.joml.Vector3f SPHERE_A=new org.joml.Vector3f(),SPHERE_B=new org.joml.Vector3f();
    private static boolean missesBounds(zombie.core.physics.Transform transform,zombie.core.physics.WorldSimulation sim,Pz origin,Vec3 to,
            org.joml.Vector3f ext,org.joml.Vector3f centre) {
        var rotation=SPHERE_ROTATION;rotation.set(transform);rotation.origin.set(0,0,0);
        var inverse=SPHERE_INVERSE;inverse.set(rotation);inverse.inverse();
        float ox=(float)((transform.origin.x+sim.offsetX-origin.x())*TarkovBallistics.METRES_PER_CELL);
        float oy=(float)(transform.origin.y*TarkovBallistics.METRES_PER_CELL-origin.z()*TarkovBallistics.METRES_PER_LEVEL);
        float oz=(float)((transform.origin.z+sim.offsetY-origin.y())*TarkovBallistics.METRES_PER_CELL);
        float k=(float)(1/TarkovBallistics.METRES_PER_CELL);
        var a=SPHERE_A.set((0-ox)*k,(0-oy)*k,(0-oz)*k);inverse.transform(a);
        var b=SPHERE_B.set((to.x()-ox)*k,(to.y()-oy)*k,(to.z()-oz)*k);inverse.transform(b);
        double dx=b.x-a.x,dy=b.y-a.y,dz=b.z-a.z,length2=dx*dx+dy*dy+dz*dz;
        double cx=centre.x-a.x,cy=centre.y-a.y,cz=centre.z-a.z;
        double s=length2>0?Math.max(0,Math.min(1,(cx*dx+cy*dy+cz*dz)/length2)):0;
        double ex=cx-s*dx,ey=cy-s*dy,ez=cz-s*dz;
        double radius=0.5*Math.sqrt((double)ext.x*ext.x+(double)ext.y*ext.y+(double)ext.z*ext.z);
        double margin=0.65+1e-3*radius+1e-5*(Math.sqrt(length2)+Math.abs(a.x)+Math.abs(a.y)+Math.abs(a.z));
        double reach=radius+margin;
        return ex*ex+ey*ey+ez*ez>reach*reach;
    }
    @Patch(className="zombie.CombatManager",methodName="attackCollisionCheck",strictMatch=true)
    public static final class NativeAttackCollision {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) zombie.characters.IsoGameCharacter actor,
                @Patch.Argument(1) zombie.inventory.types.HandWeapon weapon,
                @Patch.Argument(2) zombie.ai.states.SwipeStatePlayer swipe,
                @Patch.Argument(3) zombie.AttackType type) {
            TWImpactFx.enterAttackCollision();
        }
        @Patch.OnExit(onThrowable=Throwable.class)
        public static void exit(@Patch.Argument(0) zombie.characters.IsoGameCharacter actor,
                @Patch.Argument(1) zombie.inventory.types.HandWeapon weapon,
                @Patch.Argument(2) zombie.ai.states.SwipeStatePlayer swipe,
                @Patch.Argument(3) zombie.AttackType type,@Patch.Thrown Throwable thrown) {
            TWImpactFx.exitAttackCollision();
        }
    }
    @Patch(className="zombie.iso.objects.IsoWindow",methodName="smashWindow",strictMatch=true)
    public static final class NativeWindowSmash {
        @Patch.OnEnter
        public static void enter(@Patch.This zombie.iso.objects.IsoWindow self,
                @Patch.Argument(0) boolean bRemote,@Patch.Argument(1) boolean doAlarm) {
            TWImpactFx.noteWindowSmash(self);
        }
    }
    @Patch(className="zombie.vehicles.VehicleWindow",methodName="damage",strictMatch=true)
    public static final class NativeVehicleWindowDamage {
        @Patch.OnEnter
        public static void enter(@Patch.This zombie.vehicles.VehicleWindow self,
                @Patch.Argument(0) int amount) {
            TWImpactFx.noteVehicleWindowDamage(self,amount);
        }
    }
    @Patch(className="zombie.CombatManager",methodName="missedShot")
    public static final class NativeMissedShot {
        @Patch.OnEnter
        public static void enter() {
            TWImpactFx.enterMissedShot();
        }
        @Patch.OnExit(onThrowable=Throwable.class)
        public static void exit(@Patch.Argument(0) zombie.characters.IsoGameCharacter actor,
                @Patch.Argument(1) float range,@Patch.Argument(2) float x,
                @Patch.Argument(3) float y,@Patch.Argument(4) float z,@Patch.Thrown Throwable thrown) {
            TWImpactFx.exitMissedShot(actor,range,x,y,z,thrown);
        }
    }
    @Patch(className="zombie.iso.objects.IsoBulletTracerEffects",methodName="addEffect")
    public static final class NativeTracer {
        @Patch.OnExit
        public static void exit(@Patch.AllArguments Object[] args,@Patch.Return Object result) {
            TWImpactFx.nativeTracer(args,result);
        }
    }
    @Patch(className="zombie.input.Mouse",methodName="update")
    public static final class QaMousePin {
        @Patch.OnExit
        public static void exit() {
            if (TWImpactFx.qaPinMode!=0) TWImpactFx.applyQaMousePin();
        }
    }
}
