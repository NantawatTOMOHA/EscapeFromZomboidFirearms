package pz.escapefromzomboidfirearms.zb;

import java.util.*;
import static pz.escapefromzomboidfirearms.zb.TarkovRecoilData.*;

/**
 * Pure per player/weapon procedural state. Seconds, source metres and Euler degrees.
 * Axes are the authored Tarkov WeaponRootAnim/camera axes, not VP or PZ axes.
 * No event registration, rendering, persistence, damage or game dependencies.
 */
public final class TarkovRecoil {
    public static final double TICK_SECONDS=1.0/60;
    public static final float STEP=1f/60;

    public record Vec3(float x, float y, float z) {
        public static final Vec3 ZERO=new Vec3(0,0,0);
        public Vec3 { finite(x); finite(y); finite(z); }
        public float length() { return (float)Math.sqrt(x*x+y*y+z*z); }
        public Vec3 plus(Vec3 v) { return new Vec3(x+v.x,y+v.y,z+v.z); }
        float[] array() { return new float[]{x,y,z}; }
    }
    public record Assembly(String weaponKey, List<String> installedParts, boolean folded,
                           float recoilDelta, float ergonomics, float massKg, float ergonomicWeight) {
        public Assembly {
            Objects.requireNonNull(weaponKey); installedParts=List.copyOf(installedParts);
            finite(recoilDelta); finite(ergonomics); finite(massKg); finite(ergonomicWeight);
            if (ergonomics<0 || massKg<0) throw new IllegalArgumentException("negative assembly input");
        }
    }
    public record Ammo(String tpl, float recoilPercent) {
        public Ammo { Objects.requireNonNull(tpl); finite(recoilPercent); }
    }
    /** Mouse X/Y rates and movement are already in the original TrackingTransform local frame. */
    public record Motion(Vec3 angularVelocityDegrees, Vec3 localVelocityMetres,
                         Vec3 overheatBarrelMove, float overheatMultiplier) {
        public static final Motion STILL=new Motion(Vec3.ZERO,Vec3.ZERO,Vec3.ZERO,0);
        public Motion {
            Objects.requireNonNull(angularVelocityDegrees); Objects.requireNonNull(localVelocityMetres);
            Objects.requireNonNull(overheatBarrelMove); finite(overheatMultiplier);
        }
    }
    public record Condition(float hands, float stamina, float oxygen, float overweight, float armDamage,
                            boolean holdBreath, boolean fracture, boolean tremor,
                            boolean mounted, boolean verticalMount, boolean bipod) {
        public static final Condition HEALTHY=new Condition(1,1,1,0,0,false,false,false,false,false,false);
        public Condition {
            unit(hands); unit(stamina); unit(oxygen); unit(overweight); unit(armDamage);
            if ((verticalMount || bipod) && !mounted) throw new IllegalArgumentException("support without mount");
        }
    }
    /** Speed is the caller's normalized Walk.Speed; frequency is the caller's actual step frequency. */
    public record Activity(boolean breathing, boolean walking, int walkPreset, float walkSpeed,
                           float stepFrequency, float headBobbing) {
        public static final Activity IDLE=new Activity(true,false,0,0,0.7f,1);
        public static final Activity QUIET=new Activity(false,false,0,0,0.7f,1);
        public Activity {
            unit(walkSpeed); finite(stepFrequency); finite(headBobbing);
            if (walkPreset<0 || walkPreset>3 || stepFrequency<0 || headBobbing<0)
                throw new IllegalArgumentException("invalid walk activity");
        }
    }
    public record Inputs(boolean aiming, boolean autoFire, int pose, Motion motion,
                         Condition condition, Activity activity) {
        public static final Inputs IDLE=new Inputs(false,false,2,Motion.STILL,Condition.HEALTHY,Activity.IDLE);
        public Inputs {
            if (pose<0 || pose>2) throw new IllegalArgumentException("pose must be prone/crouch/stand");
            Objects.requireNonNull(motion); Objects.requireNonNull(condition); Objects.requireNonNull(activity);
        }
    }
    public record Suppression(float x, float y) {
        public static final Suppression NONE=new Suppression(0,0);
        public Suppression { unit(x); unit(y); }
    }
    public record Shot(long tick, int index, long seed, float rotationPower, float positionPower,
                       float angleRadians, float progress, Vec3 impulse, float hipGain) {}
    /** Combined Euler vectors are diagnostic sums; the two rotation layers have distinct pivots. */
    public record Output(long tick, double remainderSeconds, Vec3 weaponPositionMetres,
                         Vec3 weaponRotationDegrees, Vec3 cameraPositionMetres, Vec3 cameraRotationDegrees,
                         Vec3 recoilPositionMetres, Vec3 recoilRotationDegrees, Vec3 recoilCameraDegrees,
                         Vec3 proceduralPositionMetres, Vec3 proceduralRotationDegrees, Vec3 swayDegrees,
                         Vec3 hipOffsetDegrees, float hipGain, int shotIndex, boolean stable,
                         float cameraSmooth, float cameraFollowSpeed) {}

    private record Event(long tick, long order, Inputs inputs, Assembly assembly, Ammo ammo, long seed, boolean duplet) {}
    private final TarkovRecoilData data;
    private final PriorityQueue<Event> events=new PriorityQueue<>(Comparator.comparingLong(Event::tick).thenComparingLong(Event::order));
    private final Map<String,Object> prefab, rotorSettings, breath, motion, mouse, movement, spring;
    private final Spring[] procedural=new Spring[3];
    private final Spring position, camera;
    private final Rotor rotor;
    private final BetterSpring sway=new BetterSpring();
    private final Randoms recoilRandom, breathRandom;
    private final SmoothRandom[] randoms=new SmoothRandom[7];
    private final List<Anim> breathing=new ArrayList<>();
    private final List<List<Anim>> walking=new ArrayList<>(), overweightWalking=new ArrayList<>();
    private final float[] moveVelocity=new float[3], moveAcceleration=new float[3], lastMove=new float[3];
    private final float[] moveDampV=new float[3], moveDampA=new float[3];
    private final float[] rotVelocity=new float[2], rotAcceleration=new float[2], lastRot=new float[2],
            rotVelSum=new float[2], rotAccSum=new float[2], rotDampV=new float[2], rotDampA=new float[2];
    private final float[] hipOffset=new float[3];
    private Assembly assembly;
    private Map<String,Object> weapon;
    private Inputs inputs=Inputs.IDLE;
    private final Suppression suppression;
    private long ticks, order;
    private double accumulated;
    private int shotIndex;
    private float angleAdd, weaponCurveTime, gainStartValue, gainStartTime, hardnessStart, hardnessStartTime;
    private float cameraSnapStart, cameraSnapTime, cameraFollowSpeed, breathFrequency=1, cameraSensitivity=1;
    private boolean hasShot, autoWasOn, wasWalking;
    private Shot lastShot;

    public TarkovRecoil(TarkovRecoilData data, Assembly assembly, long seed) {
        this(data,assembly,seed,Suppression.NONE);
    }
    public TarkovRecoil(TarkovRecoilData data, Assembly assembly, long seed, Suppression suppression) {
        this.data=Objects.requireNonNull(data); this.suppression=Objects.requireNonNull(suppression);
        prefab=map(data.player,"recoil"); rotorSettings=map(prefab,"HandRotationRecoil");
        breath=map(data.player,"Breath"); motion=map(data.player,"MotionReact");
        mouse=map(data.player,"mouse"); movement=map(data.player,"movement"); spring=map(data.player,"spring");
        procedural[0]=new Spring(map(spring,"CameraRotation"),true);
        procedural[1]=new Spring(map(spring,"HandsPosition"),true);
        procedural[2]=new Spring(map(spring,"HandsRotation"),true);
        procedural[1].damping=number(data.aiming,"RecoilHandDamping");
        position=new Spring(map(prefab,"HandPositionRecoil"),false);
        camera=new Spring(map(prefab,"CameraRotationRecoil"),false);
        rotor=new Rotor(); recoilRandom=new Randoms(seed); breathRandom=new Randoms(seed^0x627265617468L);
        String[] names={"XRandom","YRandom","HipXRandom","HipZRandom","TremorXRandom","TremorYRandom","TremorZRandom"};
        for (int i=0;i<names.length;i++) randoms[i]=new SmoothRandom(map(breath,names[i]));
        for (Object a:list(breath,"Curves")) breathing.add(new Anim(object(a)));
        for (Object preset:list(data.player,"walkPresets")) walking.add(anims(object(preset)));
        for (Object preset:list(data.player,"overweightPresets")) overweightWalking.add(anims(object(preset)));
        cameraSnapStart=number(data.player,"CameraSmoothSteady");
        setAssembly(assembly);
    }
    private List<Anim> anims(Map<String,Object> preset) {
        List<Anim> result=new ArrayList<>(); for (Object a:list(preset,"Curves")) result.add(new Anim(object(a))); return result;
    }

    /** Assembly changes reset the rotation process as the vanilla parameter-change path does. */
    public void setAssembly(Assembly next) {
        Objects.requireNonNull(next); Map<String,Object> w=map(map(data.weapons,next.weaponKey()),"props");
        if (assembly!=null && !assembly.weaponKey().equals(next.weaponKey()))
            throw new IllegalArgumentException("use a new state for another weapon instance");
        if (!next.equals(assembly)) {
            assembly=next; weapon=w; rotor.reset(); hasShot=false; weaponCurveTime=0;
        }
    }
    public Assembly assembly() { return assembly; }
    public Shot lastShot() { return lastShot; }
    public long ticks() { return ticks; }
    public void inputs(Inputs next) { inputs=Objects.requireNonNull(next); }
    public void inputsAt(double seconds, Inputs next) {
        events.add(new Event(eventTick(seconds),order++,Objects.requireNonNull(next),null,null,0,false));
    }
    public void shot(Assembly snapshot, Ammo ammo, long seed) {
        enqueue(ticks,snapshot,ammo,seed,false);
    }
    public void shot(Assembly snapshot, Ammo ammo, long seed, boolean secondDupletShot) {
        enqueue(ticks,snapshot,ammo,seed,secondDupletShot);
    }
    public void shotAt(double seconds, Assembly snapshot, Ammo ammo, long seed, boolean secondDupletShot) {
        enqueue(eventTick(seconds),snapshot,ammo,seed,secondDupletShot);
    }
    private void enqueue(long tick, Assembly snapshot, Ammo ammo, long seed, boolean duplet) {
        if (!assembly.weaponKey().equals(snapshot.weaponKey()) || !data.ammo.containsKey(ammo.tpl())
                || !data.ammunition(ammo.tpl()).equals(ammo)) throw new IllegalArgumentException("foreign shot snapshot");
        events.add(new Event(tick,order++,null,snapshot,ammo,seed,duplet));
    }
    private long eventTick(double seconds) {
        if (!Double.isFinite(seconds) || seconds<0 || seconds>1e12) throw new IllegalArgumentException("shot time");
        long tick=(long)Math.ceil(seconds*60-1e-9);
        if (tick<ticks) throw new IllegalArgumentException("past recoil event"); return tick;
    }
    public Output update(double seconds) {
        if (!Double.isFinite(seconds) || seconds<0 || seconds>3600) throw new IllegalArgumentException("recoil dt");
        accumulated+=seconds;
        long count=(long)Math.floor((accumulated+1e-12)*60);
        for (long i=0;i<count;i++) { tick(); ticks++; }
        accumulated-=count*TICK_SECONDS;
        if (Math.abs(accumulated)<1e-12) accumulated=0;
        return output();
    }

    private void tick() {
        // Due input changes precede their following shots; order is explicit within a tick.
        boolean resetIndex=!inputs.autoFire();
        while (!events.isEmpty() && events.peek().tick()<=ticks) {
            Event e=events.remove();
            if (e.inputs()!=null) { inputs=e.inputs(); resetIndex|=!inputs.autoFire(); }
            else {
                if (resetIndex) { shotIndex=0; rotor.stable=false; angleAdd=0; resetIndex=false; }
                setAssembly(e.assembly()); applyShot(e);
            }
        }
        if (resetIndex) { shotIndex=0; rotor.stable=false; angleAdd=0; }
        if (autoWasOn && !inputs.autoFire()) rotor.curveTime=Math.min(lengthSquared(rotor.current),rotor.curveTime);
        autoWasOn=inputs.autoFire();
        float aimIntensity=inputs.aiming()?number(data.aiming,"AimProceduralIntensity"):1;
        procedural[1].inputIntensity=procedural[2].inputIntensity=aimIntensity*aimIntensity;
        processMotion(); processBreath(); processWalk();
        procedural[1].step(); procedural[2].step();
        Condition c=inputs.condition();
        float damage=c.armDamage()+c.overweight();
        sway.step(STEP,lerp(component(data.hard,"SWAY_FREQ_NORMAL_DAMAGED",0),
                        component(data.hard,"SWAY_FREQ_NORMAL_DAMAGED",1),damage),
                lerp(component(data.hard,"SWAY_DAMPING_NORMAL_DAMAGED",0),
                        component(data.hard,"SWAY_DAMPING_NORMAL_DAMAGED",1),damage));
        position.step(); camera.step(); rotor.step();
        if (hasShot) weaponCurveTime+=STEP;
        procedural[0].step();
        // UpdateWeaponVariables replaces the prefab's follow range/step with the weapon template's.
        float followStep=number(weapon,"CameraToWeaponAngleStep",number(data.player,"CameraToWeaponAngleStep"))*STEP;
        if (!inputs.autoFire() || !inputs.aiming() || lengthSquared(rotor.current)>0)
            cameraFollowSpeed=clamp(cameraFollowSpeed+(inputs.autoFire() && inputs.aiming()?followStep:-followStep),
                    followRange(0),followRange(1));
    }
    private float followRange(int axis) {
        return component(weapon.containsKey("CameraToWeaponAngleSpeedRange")?weapon:data.player,"CameraToWeaponAngleSpeedRange",axis);
    }
    /** Upper end of the follow range: Tarkov's hip camera lerp speed. */
    public float cameraFollowMax() { return followRange(1); }
    /** FirearmController nulls ergonomics when either arm is damaged. Keep this
     * a state input to sway, without rebuilding/resetting the shot assembly. */
    public float effectiveErgonomicWeight() {
        return inputs.condition().armDamage()>0 ? assembly.massKg() : assembly.ergonomicWeight();
    }
    /** The rotation process alone (HandRotationRecoilEffect.Current), the ADS camera follow's input. */
    public Vec3 handRotationRecoil() { return vec(rotor.current); }

    private void applyShot(Event e) {
        shotIndex++; recoilRandom.state=e.seed();
        int n=(int)number(weapon,"RecoilStableIndexShot");
        if (shotIndex==n && !rotor.stable) { rotor.stable=true; copy(rotor.current,rotor.anchor); }
        float progress=flag(prefab,"ProgressPowerOn") && shotIndex>=number(prefab,"ProgressPowerShot")
                ?clamp(shotIndex,1,n)/n:1;
        rotor.progress=progress;
        float scale=Math.max(0,(1+assembly.recoilDelta())*(1-suppression.x()-0.1f*suppression.y()));
        float r=(number(weapon,"RecoilForceUp")*scale+number(data.aiming,"RecoilVertBonus"))
                *number(prefab,"IncomingRotationStrengthMultiplier");
        float p=(number(weapon,"RecoilForceBack")*scale+number(data.aiming,"RecoilBackBonus"))
                *number(prefab,"IncomingRotationStrengthMultiplier");
        float r0=component(prefab,"BasicRecoilRotationStrengthRange",0)*r,
                r1=component(prefab,"BasicRecoilRotationStrengthRange",1)*r,
                p0=component(prefab,"BasicRecoilPositionStrengthRange",0)*p,
                p1=component(prefab,"BasicRecoilPositionStrengthRange",1)*p;
        float center=lerpAngle(number(weapon,"RecoilAngle"),number(rotorSettings,"MaxRecoilRotationAngle"),suppression.y());
        float spread=number(weapon,"RecolDispersion")/(1+suppression.y()), a0=0,a1=0;
        for (Object obj:list(weapon,"ShotsGroupSettings")) {
            Map<String,Object> group=object(obj);
            if (shotIndex>=number(group,"StartShotIndex") && shotIndex<=number(group,"EndShotIndex")) {
                r0+=component(group,"ShotRecoilRotationStrength",0); r1+=component(group,"ShotRecoilRotationStrength",1);
                p0+=component(group,"ShotRecoilPositionStrength",0); p1+=component(group,"ShotRecoilPositionStrength",1);
                a0+=component(group,"ShotRecoilRadianRange",0); a1+=component(group,"ShotRecoilRadianRange",1);
            }
        }
        if (rotor.stable) {
            angleAdd=clamp(angleAdd+number(weapon,"RecoilStableAngleIncreaseStep"),
                    component(weapon,"ProgressRecoilAngleOnStable",0),component(weapon,"ProgressRecoilAngleOnStable",1));
            a0-=angleAdd; a1+=angleAdd;
        }
        float force=(e.duplet()?1.5f:1)+e.ammo().recoilPercent()/100;
        float rot=recoilRandom.range(r0,r1)*force, pos=recoilRandom.range(p0,p1)*force;
        float angle=recoilRandom.range(radians(center-spread+a0),radians(center+spread+a1));
        float[] impulse={-(float)Math.sin(angle)*rot*component(data.aiming,"RecoilXIntensityByPose",inputs.pose()),
                (float)Math.cos(angle)*rot*component(data.aiming,"RecoilYIntensityByPose",inputs.pose()),
                pos*Math.min(number(weapon,"RecoilPosZMult"),component(data.aiming,"RecoilZIntensityByPose",inputs.pose()))};
        for (int i=0;i<3;i++) impulse[i]*=position.intensity;
        float heat=radians(center)*r*(component(prefab,"BasicRecoilRotationStrengthRange",0)
                +component(prefab,"BasicRecoilRotationStrengthRange",1))/2*inputs.motion().overheatMultiplier();
        impulse[0]+=inputs.motion().overheatBarrelMove().x()*heat;
        impulse[1]+=inputs.motion().overheatBarrelMove().y()*heat;
        List<Object> routes=list(prefab,"ShotRecoilProcessValues");
        for (int i=0;i<routes.size();i++) {
            Map<String,Object> route=object(routes.get(i));
            int target=(int)number(route,"ProcessType"), to=(int)number(route,"ToAxis");
            float mult=i==3?number(weapon,"RecoilCamera"):i==4?-number(weapon,"RecoilCamera"):number(route,"IntensityMultiplicator");
            float value=impulse[(int)number(route,"FromAxis")]*mult;
            if (target==0) camera.force[to]+=value;
            else if (target==1) position.force[to]+=value;
            else if (target==2) rotor.add(to,value);
            // Serialized targets 3/4 have no consumer in this vanilla build.
        }
        hasShot=true; weaponCurveTime=0;
        if (!inputs.aiming() && !inputs.condition().mounted()) {
            float gain=hipGain();
            gain+=Math.max(0,number(weapon,"HipInnaccuracyGain")-0.2f*((float)Math.sqrt(gain)-1));
            gainStartValue=gain; gainStartTime=now()+number(weapon,"HipAccuracyRestorationDelay");
            hardnessStart=number(breath,"_shotHardness"); hardnessStartTime=now();
            for (int i=2;i<4;i++) {
                randoms[i].amplitude=clamp(component(breath,"_baseHipRandomAmplitudes",i-2)+gain,0,3);
                randoms[i].hardness=number(breath,"_shotHardness");
            }
            float h0=randoms[2].get(breathRandom.range(component(breath,"_randomBetween",0),component(breath,"_randomBetween",1)));
            float h1=randoms[3].get(breathRandom.range(component(breath,"_randomBetween",0),component(breath,"_randomBetween",1)));
            float hipIntensity=breathIntensity()*number(breath,"HipPenalty");
            hipOffset[0]=Math.min(hipOffset[0],h0)*hipIntensity; hipOffset[1]=0; hipOffset[2]=h1/2*hipIntensity;
        }
        float snap=number(weapon,"CameraSnap",number(data.player,"CameraSmoothRecoil"));
        if (snap<=0) snap=number(data.player,"CameraSmoothRecoil");
        cameraSnapStart=snap*number(data.aiming,"CameraSnapGlobalMult");
        if (inputs.condition().mounted()) cameraSnapStart*=inputs.condition().bipod() && !inputs.condition().verticalMount()
                ?bipod("BipodCameraSnapMultiplier",1):number(weapon,"MountCameraSnapMultiplier",1);
        cameraSnapTime=now()+0.2f;
        lastShot=new Shot(ticks,shotIndex,e.seed(),rot,pos,angle,progress,vec(impulse),hipGain());
    }

    public float hipGain() {
        return Math.max(0,gainStartValue-Math.max(0,now()-gainStartTime)*number(weapon,"HipAccuracyRestorationSpeed"));
    }
    private float breathIntensity() {
        return component(data.aiming,"ProceduralIntensityByPose",inputs.pose())
                *(inputs.aiming()?number(data.aiming,"AimProceduralIntensity"):1);
    }
    private void processBreath() {
        if (!inputs.activity().breathing()) { Arrays.fill(hipOffset,0); return; }
        Condition c=inputs.condition();
        float stamina=flag(data.stamina,"StaminaExhaustionCausesJiggle")?Math.min(c.hands(),c.stamina()):c.hands();
        stamina=clamp(stamina,number(breath,"EnergyLowerLimit"),c.fracture()?number(breath,"EnergyFractureLimit"):1);
        if (c.mounted() && stamina>number(breath,"EnergyLowerLimit") && !c.fracture()) return;
        float mount=!c.mounted()?1:c.verticalMount()?number(weapon,"MountingVerticalOutOfBreathMultiplier"):
                c.bipod()?number(breath,"BipodOutOfBreathMultiplier"):number(weapon,"MountingHorizontalOutOfBreathMultiplier");
        float intensity=breathIntensity()*mount;
        float shake=1, amplitude;
        if (c.holdBreath()) { amplitude=0.15f; shake=0.15f; }
        else {
            float oxygen=flag(data.stamina,"StaminaExhaustionRocksCamera")?Math.min(c.oxygen(),c.stamina()):c.oxygen();
            float t=curve(breath,"_lackOfOxygenStrength").value(oxygen);
            amplitude=clamp(lerp(4,inputs.aiming()?0.75f:1,t),1,1.5f)*intensity;
            breathFrequency=clamp(lerp(4,1,t),1,2.5f)*STEP; cameraSensitivity=lerp(2,0,t)*intensity;
        }
        Map<String,Object> params=map(data.player,"breathParameters");
        randoms[1].amplitude=curve(params,"AmplitudeCurve").value(stamina);
        float delay=curve(params,"Delay").value(stamina);
        for (int i=0;i<2;i++) { randoms[i].minDelay=delay/2; randoms[i].maxDelay=delay; }
        randoms[1].hardness=curve(params,"Hardness").value(stamina);
        float y=randoms[1].get(STEP), x=randoms[0].get(STEP);
        float[] shakeForce={Math.max(0,-y)*(1-stamina)*2,y,x};
        for (int i=0;i<3;i++) procedural[2].force[i]+=shakeForce[i]*shake*intensity;
        float gain=(float)Math.sqrt(hipGain());
        float hardness=moveTowards(hardnessStart,0.1f,Math.max(0,now()-hardnessStartTime)*number(map(breath,"Hardness"),"Speed"));
        for (int i=2;i<4;i++) {
            randoms[i].amplitude=clamp(component(breath,"_baseHipRandomAmplitudes",i-2)+gain,0,3); randoms[i].hardness=hardness;
        }
        float[] offset=new float[3];
        if (c.tremor() || c.fracture()) {
            for (int i=0;i<3;i++) offset[i]=randoms[i+4].get(c.tremor()?STEP:STEP/2)*intensity;
            if (c.fracture() && !inputs.aiming()) offset[0]+=Math.max(0,y)*lerp(1,100/number(breath,"EnergyFractureLimit"),stamina)*intensity;
        } else if (!inputs.aiming()) {
            offset[0]=randoms[2].get(STEP)*intensity*number(breath,"HipPenalty");
            offset[2]=randoms[3].get(STEP)*intensity*number(breath,"HipPenalty");
        }
        float diff=0; for(int i=0;i<3;i++) diff+=(offset[i]-hipOffset[i])*(offset[i]-hipOffset[i]);
        for(int i=0;i<3;i++) hipOffset[i]=diff>0.01f?lerp(hipOffset[i],offset[i],0.1f):offset[i];
        breathing.get(0).process(breathFrequency,amplitude);
        breathing.get(1).process(breathFrequency,amplitude*cameraSensitivity);
    }

    private void processWalk() {
        Activity a=inputs.activity();
        if (!a.walking()) {
            if (wasWalking) { for (List<Anim> list:walking) for(Anim anim:list) anim.time=0;
                for (List<Anim> list:overweightWalking) for(Anim anim:list) anim.time=0; }
            wasWalking=false; return;
        }
        wasWalking=true;
        Map<String,Object> walk=map(data.player,"Walk");
        Map<String,Object> range=object(list(walk,"IntensityMinMax").get(a.walkPreset()==3?1:0));
        float intensity=lerp(number(range,"x"),number(range,"y"),a.walkSpeed());
        for (Anim anim:walking.get(a.walkPreset())) anim.process(STEP*a.stepFrequency(),intensity);
        float heavy=lerp(0,number(data.stamina,"WalkVisualEffectMultiplier"),inputs.condition().overweight());
        if (heavy>0) for (Anim anim:overweightWalking.get(a.walkPreset())) anim.process(STEP*a.stepFrequency(),heavy);
    }

    private void processMotion() {
        float[] target=inputs.motion().localVelocityMetres().array();
        for(int i=0;i<3;i++) {
            moveVelocity[i]=smoothDamp(moveVelocity[i],target[i],moveDampV,i,number(movement,"VelSmooth"));
            moveAcceleration[i]=smoothDamp(moveAcceleration[i],moveVelocity[i]-lastMove[i],moveDampA,i,number(movement,"AccSmooth"));
            lastMove[i]=moveVelocity[i];
        }
        float[] angular=inputs.motion().angularVelocityDegrees().array();
        for(int i=0;i<2;i++) {
            rotVelocity[i]=clamp(rotVelocity[i],-number(motion,"RotationInputClamp"),number(motion,"RotationInputClamp"));
            rotAccSum[i]=lerp(rotAccSum[i],rotVelocity[i]-lastRot[i],1f/3); lastRot[i]=rotVelocity[i];
            rotVelSum[i]=lerp(rotVelSum[i],angular[i],0.2f);
            rotVelocity[i]=smoothDamp(rotVelocity[i],rotVelSum[i],rotDampV,i,number(mouse,"VelSmooth"));
            rotAcceleration[i]=smoothDamp(rotAcceleration[i],rotAccSum[i],rotDampA,i,number(mouse,"AccSmooth"));
        }
        float[] sources={rotVelocity[0],rotAcceleration[0],rotVelocity[1],rotAcceleration[1],
                moveVelocity[0],moveVelocity[1],moveVelocity[2],moveAcceleration[0],moveAcceleration[1],moveAcceleration[2]};
        Condition c=inputs.condition();
        float mount=c.mounted()&&c.bipod()?number(map(data.root,"mounting"),"MountingSwayFactorModifier"):1;
        float swayStrength=curve(data.hard,"SWAY_STRENGTH_PER_KG").value(effectiveErgonomicWeight()*(1+c.overweight()))
                *(inputs.aiming()?number(data.aiming,"AimProceduralIntensity"):1);
        for(Object obj:list(mouse,"Vals")) {
            Map<String,Object> route=object(obj); int axis=(int)number(route,"Component");
            float value=curve(mouse,"Magnitude").value(sources[(int)number(route,"From")]);
            sway.velocity[axis]+=value*swayStrength*(axis==1&&inputs.aiming()?0.3f:1)*mount*number(route,"Intensity")*STEP;
        }
        for(Object obj:list(movement,"Vals")) {
            Map<String,Object> route=object(obj); int axis=(int)number(route,"Component"), to=(int)number(route,"To");
            if (to>=0 && to<3) procedural[to].force[axis]+=sources[(int)number(route,"From")]
                    *number(route,"Intensity")*STEP*number(motion,"Intensity")*mount;
        }
    }

    public Output output() {
        float[][] authored=new float[3][3];
        if (hasShot && flag(map(weapon,"WeaponRecoilSettings"),"Enable")) {
            for(Object obj:list(map(weapon,"WeaponRecoilSettings"),"Values")) {
                Map<String,Object> v=object(obj), p=map(v,"Process"); if(!flag(v,"Enable")) continue;
                int target=target((String)v.get("Target")), axis="XYZ".indexOf((String)p.get("ComponentType"));
                float sampleTime=Math.max(0,weaponCurveTime-STEP)*number(p,"CurveTimeMultiply");
                authored[target][axis]+=curve(p,"TransformationCurve").value(sampleTime)
                        *number(p,inputs.aiming()?"CurveAimingValueMultiply":"CurveValueMultiply");
            }
        }
        float[] r=plus(rotor.current,authored[2]);
        if(flag(prefab,"UseOffset")) r=plus(r,hipOffset);
        float[] p=plus(position.current,authored[1]), c=plus(camera.current,authored[0]);
        float[] proceduralRotation=procedural[2].current.clone();
        float displacement=curve(data.hard,"DISPLACEMENT_STRENGTH_PER_KG").value(effectiveErgonomicWeight()*(1+inputs.condition().overweight()))
                *(inputs.aiming()?number(data.player,"AimingDisplacementStr"):1);
        for(int i=0;i<3;i++) proceduralRotation[i]+=sway.current[i]*(1+(i==1?0:displacement));
        return new Output(ticks,accumulated,vec(plus(p,procedural[1].current)),vec(plus(r,proceduralRotation)),Vec3.ZERO,
                vec(plus(c,procedural[0].current)),vec(p),vec(r),vec(c),vec(procedural[1].current),vec(proceduralRotation),
                vec(sway.current),vec(hipOffset),hipGain(),shotIndex,rotor.stable,
                moveTowards(cameraSnapStart,number(data.player,"CameraSmoothSteady"),
                        Math.max(0,now()-cameraSnapTime)*number(map(data.player,"CameraSmoothBlender"),"Speed")),cameraFollowSpeed);
    }

    private final class Anim {
        final Map<String,Object> values; float time;
        Anim(Map<String,Object> values) { this.values=values; time=number(values,"TimeShift"); }
        void process(float dt, float intensity) {
            time+=dt; float value=curve(values,"Curve").value(time)*intensity;
            for (Object obj:list(values,"Vals")) {
                Map<String,Object> v=object(obj); int to=(int)number(v,"To"), axis=(int)number(v,"Component");
                if(to>=0 && to<3) procedural[to].force[axis]+=value*number(v,"Intensity")*(to==0?inputs.activity().headBobbing():1);
            }
        }
    }
    private static final class Spring {
        final float[] current=new float[3], velocity=new float[3], force=new float[3];
        final float intensity, returnSpeed, max; float damping, inputIntensity;
        Spring(Map<String,Object> settings, boolean limited) {
            intensity=number(settings,limited?"InputIntensity":"Intensity");
            inputIntensity=intensity;
            returnSpeed=number(settings,"ReturnSpeed"); damping=number(settings,"Damping");
            max=limited?number(settings,"AccelerationMax"):Float.POSITIVE_INFINITY;
        }
        void step() {
            float magnitude=(float)Math.sqrt(lengthSquared(force));
            float limit=magnitude>max?max/magnitude:1;
            for(int i=0;i<3;i++) {
                velocity[i]+=force[i]*limit*inputIntensity; force[i]=0;
                velocity[i]=(velocity[i]-current[i]*returnSpeed)*damping; current[i]+=velocity[i];
            }
        }
    }

    private final class Rotor {
        final float[] current=new float[3], velocity=new float[3], force=new float[3], previous=new float[3],
                anchor=new float[3], next=new float[3], after=new float[3];
        boolean stable, returning, need; int side;
        float curveTime, autoSpeed, progress=1, offsetImpulse=0.01f, afterX=0.01f;
        void reset() {
            for(float[] a:new float[][]{current,velocity,force,previous,anchor,next,after}) Arrays.fill(a,0);
            curveTime=autoSpeed=0; offsetImpulse=afterX=0.01f; returning=need=false;
        }
        void add(int axis, float value) {
            value*=number(weapon,"RecoilCategoryMultiplierHandRotation");
            Condition c=inputs.condition();
            if(c.mounted()) value*=c.verticalMount()?number(weapon,"MountVerticalRecoilMultiplier"):
                    c.bipod()?bipod("BipodRecoilMultiplier",number(rotorSettings,"BipodRecoilMultiplier")):
                            number(weapon,"MountHorizontalRecoilMultiplier");
            if(stable) Arrays.fill(velocity,0);
            force[axis]+=value*progress; need=returning=true;
        }
        void step() {
            if(need) {
                float intensity=inputs.aiming()?number(data.aiming,"AimProceduralIntensity"):1;
                for(int i=0;i<3;i++) { velocity[i]+=force[i]*intensity; force[i]=0; }
                curveTime=Math.min(lengthSquared(current),curveTime);
                if(stable) {
                    float distance=recoilRandom.range(component(rotorSettings,"NextStablePointDistanceRange",0),component(rotorSettings,"NextStablePointDistanceRange",1));
                    for(int i=0;i<3;i++) next[i]=anchor[i]+velocity[i]*distance;
                    float dist=0; for(int i=0;i<3;i++) dist+=(next[i]-current[i])*(next[i]-current[i]);
                    float limit=number(rotorSettings,"ToNextStablePointDistanceLimit");
                    if(Math.sqrt(dist)>limit) for(int i=0;i<3;i++) next[i]=current[i]+(next[i]-current[i])*limit;
                }
            }
            float returnSpeed=number(weapon,"RecoilReturnSpeedHandRotation")*number(data.aiming,"RecoilConvergenceMult");
            if(!inputs.autoFire()) {
                autoSpeed=returnSpeed*curve(rotorSettings,"ReturnSpeedCurve").value(curveTime);
                if(inputs.condition().mounted()) autoSpeed*=inputs.condition().bipod()
                        ?bipod("BipodReturnHandSpeedMultiplier",number(rotorSettings,"BipodReturnHandSpeedMultiplier"))
                        :number(weapon,"MountReturnSpeedHandMultiplier");
            }
            if(need && inputs.autoFire() && !stable) autoSpeed*=progress;
            curveTime+=STEP;
            for(int i=0;i<3;i++) {
                velocity[i]*=number(weapon,"RecoilDampingHandRotation");
                if(stable) current[i]+=(next[i]-current[i])*number(rotorSettings,"ToNextCurrentSpeed");
                else { velocity[i]-=current[i]*autoSpeed; current[i]+=velocity[i]; }
            }
            if(current[0]<previous[0] && returning) {
                after[2]=side>0?recoilRandom.range(current[2],component(weapon,"PostRecoilHorizontalRangeHandRotation",1)):
                        side<0?recoilRandom.range(component(weapon,"PostRecoilHorizontalRangeHandRotation",0),current[2]):0;
                after[0]=recoilRandom.range(component(weapon,"PostRecoilVerticalRangeHandRotation",0),component(weapon,"PostRecoilVerticalRangeHandRotation",1));
                afterX=current[0]; offsetImpulse=Math.abs(current[0]*returnSpeed)*number(weapon,"RecoilReturnPathOffsetHandRotation");
                if(inputs.condition().mounted()) offsetImpulse*=number(weapon,"MountReturnSpeedHandMultiplier");
                side=current[2]>0?1:current[2]<0?-1:recoilRandom.range(-1,1)>=0?1:-1;
            } else if(!inputs.autoFire() && returning) {
                offsetImpulse*=number(weapon,"RecoilReturnPathDampingHandRotation");
                if(offsetImpulse<0.001f) {
                    if(flag(weapon,"ResetAfterShot")) reset(); else returning=false;
                } else velocity[2]+=offsetImpulse*(side>0?1:-1);
            }
            if(!inputs.autoFire() && afterX!=0) {
                float amount=clamp(1-Math.abs(clamp(current[0],afterX,-0.01f)/afterX),0.01f,1)*0.01f;
                current[0]=lerp(current[0],after[0],amount); current[2]=lerp(current[2],after[2],amount);
            }
            copy(current,previous); need=false;
        }
    }

    /** Closed-form damped oscillator; separate from the discrete recoil/force springs. */
    public static final class BetterSpring {
        final float[] current=new float[3], velocity=new float[3];
        public void applyVelocity(Vec3 value) { float[] v=value.array(); for(int i=0;i<3;i++) velocity[i]+=v[i]; }
        public Vec3 value() { return vec(current); }
        public void step(float dt, float omega, float damping) {
            finite(dt); finite(omega); finite(damping);
            if(dt<0 || omega<=0 || damping<0) throw new IllegalArgumentException("oscillator input");
            for(int i=0;i<3;i++) {
                double x=current[i], v=velocity[i], p, q;
                if(damping>1.0001f) {
                    double a=-omega*damping, b=omega*Math.sqrt(damping*damping-1), r1=a-b,r2=a+b;
                    double c1=(v-x*r2)/(-2*b),c2=x-c1,e1=Math.exp(r1*dt),e2=Math.exp(r2*dt);
                    p=c1*e1+c2*e2; q=c1*r1*e1+c2*r2*e2;
                } else if(damping>0.9999f) {
                    double e=Math.exp(-omega*dt), c=v+omega*x; p=(c*dt+x)*e; q=c*e-p*omega;
                } else {
                    double z=omega*damping,a=omega*Math.sqrt(1-damping*damping),e=Math.exp(-z*dt),
                            c=Math.cos(a*dt),s=Math.sin(a*dt),b=(v+z*x)/a;
                    p=e*(x*c+b*s); q=-e*((x*z-b*a)*c+(x*a+b*z)*s);
                }
                current[i]=(float)p; velocity[i]=(float)q;
            }
        }
    }
    private final class SmoothRandom {
        float amplitude, minDelay,maxDelay,hardness,countdown,value,increase;
        SmoothRandom(Map<String,Object> p) {
            amplitude=number(p,"Amplitude"); hardness=number(p,"Hardness");
            minDelay=component(p,"MinMaxDelay",0); maxDelay=component(p,"MinMaxDelay",1);
        }
        float get(float dt) {
            countdown-=dt;
            if(countdown<0) {
                countdown=breathRandom.range(minDelay,maxDelay);
                float slope=(breathRandom.range(-amplitude,amplitude)-value)/countdown;
                increase+=(slope-increase)*hardness;
            }
            value+=increase*dt; return value;
        }
    }
    /** Project replay generator. No claim of matching UnityEngine.Random state or draw sequence. */
    public static final class Randoms {
        private long state;
        public Randoms(long seed) { state=seed; }
        public float unit() {
            long z=(state+=0x9E3779B97F4A7C15L);
            z=(z^(z>>>30))*0xBF58476D1CE4E5B9L; z=(z^(z>>>27))*0x94D049BB133111EBL; z^=z>>>31;
            return (z>>>40)*0x1.0p-24f;
        }
        public float range(float min, float max) { return min+(max-min)*unit(); }
    }
    private float bipod(String field, float fallback) {
        for(String tpl:assembly.installedParts()) {
            Map<String,Object> p=map(data.modifiers,tpl); if(p.containsKey(field)) return number(p,field);
        }
        return fallback;
    }
    private float now() { return (float)(ticks*TICK_SECONDS); }
    static float component(Map<String,Object> map, String key, int axis) { return number(map(map,key),"xyz".substring(axis,axis+1)); }
    private static int target(String t) {
        return switch(t) { case "CameraRotation" -> 0; case "HandsPosition" -> 1; case "HandsRotation" -> 2;
            default -> throw new IllegalArgumentException("unknown authored target"); };
    }
    private static void unit(float f) { finite(f); if(f<0 || f>1) throw new IllegalArgumentException("normalized input"); }
    private static float radians(float f) { return f*((float)Math.PI/180); }
    private static float clamp(float x,float min,float max) { if(x<min) return min; if(x>max) return max; return x; }
    private static float lerp(float a,float b,float t) { return a+(b-a)*clamp(t,0,1); }
    private static float lerpAngle(float a,float b,float t) { float d=(b-a)%360; if(d<0)d+=360; if(d>180)d-=360; return a+d*clamp(t,0,1); }
    private static float moveTowards(float a,float b,float step) { return Math.abs(b-a)<=step?b:a+Math.signum(b-a)*step; }
    private static float smoothDamp(float current,float target,float[] vel,int i,float smoothTime) {
        float omega=2/Math.max(0.0001f,smoothTime),x=omega*STEP,e=1/(1+x+0.48f*x*x+0.235f*x*x*x),change=current-target;
        float temp=(vel[i]+omega*change)*STEP; vel[i]=(vel[i]-omega*temp)*e;
        float result=target+(change+temp)*e;
        if((target-current>0)==(result>target)) { result=target; vel[i]=0; }
        return result;
    }
    private static float lengthSquared(float[] a) { float r=0; for(float f:a) r+=f*f; return r; }
    private static Vec3 vec(float[] a) { return new Vec3(a[0],a[1],a[2]); }
    private static float[] plus(float[] a,float[] b) { return new float[]{a[0]+b[0],a[1]+b[1],a[2]+b[2]}; }
    private static void copy(float[] a,float[] b) { System.arraycopy(a,0,b,0,3); }
}
