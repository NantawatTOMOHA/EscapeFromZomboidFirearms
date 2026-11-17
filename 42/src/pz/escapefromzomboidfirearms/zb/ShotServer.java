package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import pz.escapefromzomboidfirearms.zb.ShotSnapshot.*;
import pz.escapefromzomboidfirearms.zb.ShotProtocol.*;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;

/** Server authority over the MP shot. Only the trusted native adapter supplies Evidence.
 * The shooter's drawn muzzle, bore and calibrated launch direction start the flight after
 * the server checked them against its own pose of that player; client contacts, seeds,
 * pellet directions, timestamps and native weapon bytes never determine it. The weapon,
 * assembly and cartridge come from the server's own item at its native fire boundary.
 */
public final class ShotServer implements Authority {
    public enum Mode { OFF, SHADOW, LIVE }
    /** Project policy, not imported weapon data. Times use the server clock only.
     * muzzleMetres: horizontal muzzle distance from the server's position of the shooter
     * (reach of the weapon plus the position the server has not received yet);
     * belowMetres/aboveMetres: muzzle height band over the shooter's level;
     * boreDegrees: horizontal bore against the server's facing of the shooter;
     * launchDegrees: the calibrated launch against the drawn bore (zeroing only). */
    public record Limits(double muzzleMetres,double belowMetres,double aboveMetres,double boreDegrees,double launchDegrees,
            long evidenceNanos,int reorderWindow) {
        public Limits(double muzzleMetres,double boreDegrees,long evidenceNanos,int reorderWindow) {
            this(muzzleMetres,0.5,2.6,boreDegrees,3,evidenceNanos,reorderWindow);
        }
        public Limits {
            if (!Double.isFinite(muzzleMetres) || muzzleMetres<=0 || !Double.isFinite(boreDegrees)
                    || !(belowMetres>=0) || !(aboveMetres>0) || !(launchDegrees>0) || launchDegrees>=90
                    || boreDegrees<=0 || boreDegrees>180 || evidenceNanos<=0 || evidenceNanos>ShotProtocol.PENDING_NANOS
                    || reorderWindow<1 || reorderWindow>=128) throw new IllegalArgumentException("Server limits");
        }
    }
    /** The server's own pose of the shooter at its native fire boundary (PZ x/y cells,
     * z level; forward is the native facing unit vector). Plausibility only. */
    public record ServerPose(double x,double y,double z,float forwardX,float forwardY) {
        public ServerPose {
            ShotPoseSnapshot.finite(x);ShotPoseSnapshot.finite(y);ShotPoseSnapshot.finite(z);
            if (!Float.isFinite(forwardX) || !Float.isFinite(forwardY)) throw new IllegalArgumentException("Server facing");
        }
    }
    /** Frozen BEFORE the native event listeners change ammo or the assembly.
     * receivedNanos and startSeconds belong to the server, never the client.
     */
    public record Evidence(Preparation prepared,ServerPose pose,long receivedNanos,double startSeconds,Object fallback) {
        public Evidence(Preparation prepared,ServerPose pose,long receivedNanos,double startSeconds) {
            this(prepared,pose,receivedNanos,startSeconds,null);
        }
        public Evidence {
            Objects.requireNonNull(prepared);Objects.requireNonNull(pose);
            if (!Double.isFinite(startSeconds) || startSeconds<0) throw new IllegalArgumentException("Server time");
        }
    }
    /** An implementation must supply the server world, the reported-body check, PVP and native
     * replication; no Lua setter or wire message can assert these capabilities. */
    public interface Native {
        boolean ready();
        Contacts contacts();
        Damage damage();
        /** The shooter's reported bodies for an admitted shot; the adapter keeps the plausible ones. */
        default void bodies(ShotSnapshot shot,List<ShotBodies.Body> bodies) { }
        default void finished(Id id) { }
        /** The flight's contact events of one step (holes, ricochets, exits), for every client's marks. */
        default void diagnostics(ShotSnapshot shot,List<ShotPenetration.Diagnostic> events) { }
        /** An admitted flight bends with the shooter's drawn relief (fields: ShotServer.relief). */
        default void relief(ShotSnapshot shot,String fields) { }
        /** The native shot will not fly here (refused, its intent lost or late, its shooter gone):
         * the adapter resolves it by PZ's own path from evidence.fallback(), exactly once. */
        default void fallback(int shooter,Evidence evidence,String reason) { }
        /** An intent the server refused, for its log: what the client claimed against the server's own item. */
        default void refused(FireIntent fire,NativeFire proof,String reason) { }
    }
    private static final class Stream {
        final long epoch;
        final NativeCounter counter;
        final Map<Long,Evidence> evidence=new HashMap<>();
        // Serials already decided (flown or handed to the fallback): never decided again.
        final java.util.HashSet<Long> settled=new java.util.HashSet<>();
        long high;
        Stream(long epoch,long base) { this.epoch=epoch;high=base;counter=new NativeCounter(base); }
    }
    private final ShotLedger ledger;
    private final ShotProtocol protocol;
    private final Native nativeAdapter;
    private final TarkovBallisticsData flightData;
    private final TarkovPenetrationData penetrationData;
    private final LongSupplier clock;
    private final Limits limits;
    private final String dataHash;
    private final long salt;
    private final Map<Integer,Stream> streams=new HashMap<>();
    private final Map<Integer,Long> epochs=new HashMap<>();
    private record Flight(ShotSnapshot shot,ShotPenetration.Chain chain) { }
    private final Map<Id,Flight> flights=new HashMap<>();
    private final Map<Id,FireIntent> intents=new HashMap<>();
    private final Map<Id,Long> serials=new HashMap<>();
    private long fallbacks;
    private Mode mode=Mode.SHADOW;
    /** Policy "Live: Viewpoint and isometric" (sandbox ViewpointBallistics 4): isometric intents fly too; without it
     * an isometric intent is refused into PZ's own path (the shooter's client does not claim one then). */
    private boolean isometric;
    private long isometricAccepted,isometricRefused;
    private boolean failed;
    private String reason="shadow";
    private long accepted,rejected,observed;
    /** The drawn relief of admitted intents (ShotProtocol.Relief): flights bent, samples, values clamped. */
    private long reliefs,reliefSamples,reliefClamped;
    private String lastRelief="none";
    /** Policy for a shooter's relief (no server Viewpoint can check it): the lift a drawn relief can reach from the
     * muzzle, +-min(MAX, BASE + GRADE * distance) metres - a 35 % mean grade from the muzzle (Viewpoint's relief height
     * is a player setting; Rosewood at its default rises 0.93 m within 25 m, 0.58 m within 5 m, measured values in
     * docs/pending/w10/REPORT-mprelief.md). A profile beyond bends the flight no further than that bound. */
    public static final double RELIEF_BASE_METRES=.5,RELIEF_GRADE=.35,RELIEF_MAX_METRES=32;
    /** The measured pose of every admitted intent against the server's own: the evidence for LIMITS. */
    private long poses;
    private double poseMuzzle,poseUpMin,poseUpMax,poseBore,poseLaunch;

    public ShotServer(String session,String dataHash,long salt,Native nativeAdapter,
            TarkovBallisticsData flightData,TarkovPenetrationData penetrationData,
            Limits limits,Transport transport,LongSupplier clock) {
        this.dataHash=Objects.requireNonNull(dataHash);this.salt=salt;this.nativeAdapter=Objects.requireNonNull(nativeAdapter);
        this.flightData=Objects.requireNonNull(flightData);this.penetrationData=Objects.requireNonNull(penetrationData);
        this.clock=Objects.requireNonNull(clock);this.limits=Objects.requireNonNull(limits);
        ledger=new ShotLedger(session,4096,clock);
        protocol=new ShotProtocol(Role.SERVER,ledger,dataHash,this,nativeAdapter.damage(),transport,null,clock);
    }
    public ShotProtocol protocol() { return protocol; }
    public void isometric(boolean value) { isometric=value; }
    public boolean isometric() { return isometric; }
    public void mode(Mode value) {
        Objects.requireNonNull(value);
        if (value!=mode) {
            // A mode transition revokes every epoch. Returning to live needs a
            // fresh native base/handshake; queued old shots cannot resume.
            for (int shooter:List.copyOf(streams.keySet())) disconnect(shooter);
            mode=value;
        }
        reason=failed?"authority-failed":value.name().toLowerCase();
    }
    @Override public boolean ready() {
        return !failed && mode==Mode.LIVE && nativeAdapter.ready()
                && nativeAdapter.contacts()!=null && nativeAdapter.contacts().ready()
                && nativeAdapter.damage()!=null && nativeAdapter.damage().ready();
    }
    /** Authenticated connection lifecycle, NOT a client-provided epoch/base. */
    public Hello connect(int shooter,long epoch,long nativeBase,String challenge) {
        Long previous=epochs.get(shooter);
        if (!ready() || epoch==0 || nativeBase==-1L || previous!=null && Long.compareUnsigned(epoch,previous)<=0
                || !ledger.connect(shooter,epoch)) return null;
        streams.put(shooter,new Stream(epoch,nativeBase));epochs.put(shooter,epoch);
        return new Hello(challenge,ledger.session(),epoch,shooter,dataHash,ShotSnapshot.SCHEMA,
                ShotSnapshot.METRIC_VERSION,ShotSnapshot.REPLAY_VERSION,nativeBase,true,
                "server-authority "+ShotProtocol.RELIEF_CAPABILITY+" "+ShotProtocol.ISOMETRIC_CAPABILITY+" "+ShotProtocol.ANIMAL_CAPABILITY
                        +" "+ShotProtocol.NATIVE_WITNESS_CAPABILITY);
    }
    /** Native-only entry: never called by receive(), FireIntent or ImpactProposal. */
    public boolean observe(int shooter,int nativeByte,Evidence evidence) throws Exception {
        Stream stream=streams.get(shooter);
        if (!ready() || stream==null || !dataHash.equals(evidence.prepared().dataHash())) return false;
        try {
            expireEvidence(stream,shooter);
            long serial=stream.counter.observe(nativeByte);
            if (Long.compareUnsigned(serial,stream.high)>0) {
                if (Long.compareUnsigned(serial-stream.high,limits.reorderWindow())>0) return close(shooter,"native-gap");
                stream.high=serial;
            } else if (Long.compareUnsigned(stream.high-serial,limits.reorderWindow())>=0) return close(shooter,"native-old");
            if (stream.evidence.containsKey(serial) || stream.settled.contains(serial) || stream.evidence.size()>=2048) return false;
            stream.evidence.put(serial,evidence);observed++;
            return protocol.nativeFire(new NativeFire(new ShotLedger.NativeKey(shooter,stream.epoch,serial),nativeByte&255,evidence.prepared()));
        } catch (IllegalStateException error) { close(shooter,"native-counter");return false; }
    }
    /** A native firearm shot of this shooter the server cannot prepare (no armed TW assembly):
     * it still advances the stream, so the window never mistakes a long vanilla burst for a gap. */
    public void nativeShot(int shooter,int nativeByte) {
        Stream stream=streams.get(shooter);
        if (stream==null) return;
        try {
            long serial=stream.counter.observe(nativeByte);
            if (Long.compareUnsigned(serial,stream.high)>0) {
                if (Long.compareUnsigned(serial-stream.high,limits.reorderWindow())>0) { close(shooter,"native-gap");return; }
                stream.high=serial;
            }
        } catch (IllegalStateException error) { close(shooter,"native-counter"); }
    }
    private void expireEvidence(Stream stream,int shooter) {
        long now=clock.getAsLong();
        for (Long serial:List.copyOf(stream.evidence.keySet())) {
            if (now-stream.evidence.get(serial).receivedNanos()>limits.evidenceNanos()) fallback(stream,shooter,serial,"evidence-expired");
        }
        long fence=stream.high;
        stream.settled.removeIf(value->Long.compareUnsigned(fence-value,2L*limits.reorderWindow())>0);
    }
    /** One decision per native serial: it either flies (accepted) or falls back, never both. */
    private void fallback(Stream stream,int shooter,long serial,String why) {
        Evidence evidence=stream.evidence.remove(serial);
        if (evidence==null) return;
        stream.settled.add(serial);
        if (evidence.fallback()==null) return;
        fallbacks++;
        nativeAdapter.fallback(shooter,evidence,why);
    }
    private boolean close(int shooter,String why) { rejected++;reason=why;disconnect(shooter);return false; }
    @Override public boolean admit(FireIntent fire,NativeFire proof) {
        Stream stream=streams.get(fire.shot().id().shooter());
        if (!ready() || stream==null || stream.epoch!=fire.shot().id().epoch()) return false;
        Evidence evidence=stream.evidence.get(proof.key().serial());
        if (evidence==null) return false;
        if (clock.getAsLong()-evidence.receivedNanos()<0 || clock.getAsLong()-evidence.receivedNanos()>limits.evidenceNanos()) {
            rejected++;reason="evidence-age";fallback(stream,fire.shot().id().shooter(),proof.key().serial(),reason);return false;
        }
        boolean iso=fire.view()==View.ISOMETRIC;
        String refusal=iso && !isometric?"server-isometric-off":pose(fire,evidence,limits);
        if (refusal!=null) {
            rejected++;reason=refusal;if (iso) isometricRefused++;
            fallback(stream,fire.shot().id().shooter(),proof.key().serial(),refusal);return false;
        }
        if (iso) isometricAccepted++;
        measure(fire,evidence);
        intents.put(fire.shot().id(),fire);
        return true;
    }
    /** The round is decided the moment its intent is refused: PZ's own hit now, never at the evidence limit two seconds
     * later (a player's report of 04.11.2026). The limit stays for an intent that never arrived. */
    @Override public void refused(FireIntent fire,NativeFire proof,String why) {
        nativeAdapter.refused(fire,proof,why);
        Stream stream=streams.get(proof.key().shooter());
        if (stream!=null && stream.epoch==proof.key().epoch()) fallback(stream,proof.key().shooter(),proof.key().serial(),why);
    }
    /** Null when the shooter's drawn pose is plausible for the server's pose, else the reason. */
    public static String pose(FireIntent fire,Evidence evidence,Limits limits) {
        var client=fire.shot().pose();var server=evidence.pose();var prepared=evidence.prepared();
        if (client.weaponId()!=prepared.weapon().id() || !client.assemblyRevision().equals(prepared.weapon().assemblyRevision()))
            return "server-pose-weapon";
        var a=client.muzzlePz();
        double dx=(a.x()-server.x())*TarkovBallistics.METRES_PER_CELL;
        double dz=(a.y()-server.y())*TarkovBallistics.METRES_PER_CELL;
        double up=(a.z()-server.z())*TarkovBallistics.METRES_PER_LEVEL;
        if (dx*dx+dz*dz>limits.muzzleMetres()*limits.muzzleMetres()) return "server-pose-muzzle";
        if (up< -limits.belowMetres() || up>limits.aboveMetres()) return "server-pose-height";
        Vec3 bore=client.boreForward();
        double horizontal=Math.hypot(bore.x(),bore.z()),facing=Math.hypot(server.forwardX(),server.forwardY());
        // A nearly vertical bore has no meaningful heading; its muzzle stays bounded above.
        if (horizontal>0.2 && facing>0.5) {
            double cos=(bore.x()*server.forwardX()+bore.z()*server.forwardY())/(horizontal*facing);
            if (cos<Math.cos(Math.toRadians(Math.min(180,limits.boreDegrees())))-1e-9) return "server-pose-bore";
        }
        if (fire.launch().dot(bore)<Math.cos(Math.toRadians(limits.launchDegrees()))) return "server-pose-launch";
        return null;
    }
    private void measure(FireIntent fire,Evidence evidence) {
        var client=fire.shot().pose();var server=evidence.pose();var a=client.muzzlePz();
        double dx=(a.x()-server.x())*TarkovBallistics.METRES_PER_CELL,dz=(a.y()-server.y())*TarkovBallistics.METRES_PER_CELL;
        double up=(a.z()-server.z())*TarkovBallistics.METRES_PER_LEVEL;
        Vec3 bore=client.boreForward();
        double horizontal=Math.hypot(bore.x(),bore.z()),facing=Math.hypot(server.forwardX(),server.forwardY());
        double bearing=horizontal>0.2 && facing>0.5?Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,
                (bore.x()*server.forwardX()+bore.z()*server.forwardY())/(horizontal*facing))))):0;
        double launch=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,fire.launch().dot(bore)))));
        poses++;poseMuzzle=Math.max(poseMuzzle,Math.hypot(dx,dz));
        poseUpMin=poses==1?up:Math.min(poseUpMin,up);poseUpMax=poses==1?up:Math.max(poseUpMax,up);
        poseBore=Math.max(poseBore,bearing);poseLaunch=Math.max(poseLaunch,launch);
    }
    /** Rebuild the entire ballistic input from server proof. Cosmetic client
     * frame identity is not used to move the muzzle or to aim the server shot. */
    @Override public ShotSnapshot authoritative(FireIntent fire,NativeFire proof) {
        Evidence evidence=streams.get(fire.shot().id().shooter()).evidence.get(proof.key().serial());
        var p=evidence.prepared();var id=fire.shot().id();
        serials.put(id,proof.key().serial());
        long seed=mix(mix(salt^id.epoch())^mix(((long)id.shooter()<<32)^proof.key().serial()));
        // The admitted drawn pose starts the flight; the server's weapon, cartridge, seed and clock own the rest.
        return new ShotSnapshot(id,p.dataHash(),seed,ShotSnapshot.REPLAY_VERSION,evidence.startSeconds(),p.weapon(),p.parts(),
                p.actualAmmoTpl(),p.nativeAmmoType(),p.profile(),p.ammo(),p.speedFactor(),p.initialSpeed(),p.dispersion(),
                fire.shot().pose(),directions(fire.launch(),p.ammo().projectileCount(),p.dispersion(),seed),p.nativeWeapon());
    }
    @Override public boolean serverSimulated() { return true; }
    @Override public void accepted(ShotSnapshot shot) {
        if (!ready() || flights.size()>=2048) throw new IllegalStateException("Server flight capacity/readiness");
        FireIntent fire=intents.remove(shot.id());
        Long serial=serials.remove(shot.id());Stream stream=streams.get(shot.id().shooter());
        if (serial!=null && stream!=null) { stream.evidence.remove(serial);stream.settled.add(serial); }
        nativeAdapter.bodies(shot,fire==null?List.of():fire.bodies());
        var chain=new ShotPenetration.Chain(shot,flightData,penetrationData,nativeAdapter.contacts());
        // The shooter's flight bends with its drawn relief (ShotOwner.reliefWarp); this one bends the same way.
        if (fire!=null && fire.relief().samples()>0) {
            var clamped=fire.relief().clamped(RELIEF_BASE_METRES,RELIEF_GRADE,RELIEF_MAX_METRES);
            chain.warp=warp(clamped,fire.launch());
            reliefs++;reliefSamples+=clamped.samples();
            short[] sent=fire.relief().lifts(),kept=clamped.lifts();int changed=0;
            for (int i=0;i<sent.length;i++) if (sent[i]!=kept[i]) changed++;
            reliefClamped+=changed;
            lastRelief=relief(clamped,fire.launch(),changed);
            nativeAdapter.relief(shot,lastRelief);
        }
        flights.put(shot.id(),new Flight(shot,chain));accepted++;
    }
    /** A shooter's (clamped) relief as a flight's Warp, as ShotOwner.reliefWarp bends the shooter's own flight: a point
     * is traced at its drawn height minus the lift there, relative to the lift where its branch began. */
    public static ShotPenetration.Warp warp(Relief relief,Vec3 launch) {
        if (relief.samples()==0) return ShotPenetration.Warp.NONE;
        var lift=relief.along(launch);
        return (point,origin)->{
            double d=lift.applyAsDouble(point)-lift.applyAsDouble(origin);
            return d==0 ? point : new Vec3(point.x(),(float)(point.y()-d),point.z());
        };
    }
    /** Log fields of a relief: its rows, samples, range and the line's lift at 5-50 m (metres). */
    public static String relief(Relief relief,Vec3 launch,int clamped) {
        var lift=relief.along(launch);
        double h=Math.hypot(launch.x(),launch.z());
        StringBuilder text=new StringBuilder(String.format(java.util.Locale.ROOT,"rows=%d samples=%d range=%.1f clamped=%d lift=",
                relief.rows(),relief.samples(),relief.samples()==0?0:relief.millimetres()[relief.samples()-1]/1000.0,clamped));
        for (int m:new int[]{5,10,15,20,25,50}) {
            Vec3 at=h<1e-3?Vec3.ZERO:new Vec3((float)(launch.x()/h*m),0,(float)(launch.z()/h*m));
            text.append(m==5?"":",").append(m).append(':').append(String.format(java.util.Locale.ROOT,"%.3f",lift.applyAsDouble(at)));
        }
        return text.toString();
    }
    @Override public List<Contact> validate(ShotSnapshot shot,List<Contact> ignored) { return List.of(); }
    /** Advance only on the server clock. No client Impact is required to hit.
     * The caller passes simulation seconds, never an envelope timestamp. */
    public void tick(double seconds) throws Exception {
        if (!Double.isFinite(seconds) || seconds<0) throw new IllegalArgumentException("Server simulation clock");
        if (!ready()) { flights.clear();return; }
        try {
            for (Id id:List.copyOf(flights.keySet())) {
                var flight=flights.get(id);var shot=flight.shot();var chain=flight.chain();
                if (!ledger.current(id)) { flights.remove(id);nativeAdapter.finished(id);continue; }
                float end=(float)Math.min(shot.ammo().lifeSeconds(),Math.max(0,seconds-shot.startTimeSeconds()));
                // Chain exposes its own elapsed time so a reordered tick cannot
                // replay work. Bound each call even after a long scheduler gap.
                float time=chain.time();
                for (int steps=0;time<end && !chain.done() && steps<256;steps++) {
                    time=Math.min(end,time+TarkovBallistics.STEP_SECONDS);
                    var step=chain.advance(time);protocol.serverImpact(id,step.impacts());
                    if (!step.diagnostics().isEmpty()) nativeAdapter.diagnostics(shot,step.diagnostics());
                }
                if (chain.done()) { flights.remove(id);ledger.finish(id,"server-flight-ended");nativeAdapter.finished(id); }
            }
            protocol.expire();
            for (var entry:List.copyOf(streams.entrySet())) expireEvidence(entry.getValue(),entry.getKey());
            intents.keySet().removeIf(id->!ledger.contains(id));serials.keySet().removeIf(id->!ledger.contains(id));
        } catch (Exception error) {
            failed=true;reason="server-flight-failed";
            for (Id id:flights.keySet()) nativeAdapter.finished(id);
            flights.clear();throw error;
        }
    }
    public void disconnect(int shooter) {
        Stream stream=streams.get(shooter);
        if (stream!=null) for (Long serial:List.copyOf(stream.evidence.keySet())) fallback(stream,shooter,serial,"stream-closed");
        streams.remove(shooter);protocol.disconnect(shooter);intents.keySet().removeIf(id->id.shooter()==shooter);
        serials.keySet().removeIf(id->id.shooter()==shooter);
        for (Id id:List.copyOf(flights.keySet())) if (id.shooter()==shooter) { flights.remove(id);nativeAdapter.finished(id); }
    }
    public int flights() { return flights.size(); }
    public boolean streaming(int shooter) { return streams.containsKey(shooter); }
    public boolean failed() { return failed; }
    public String status() { return "mode="+mode+" ready="+ready()+" observed="+observed+" accepted="+accepted
            +" rejected="+rejected+" fallbacks="+fallbacks+" flights="+flights.size()+" reason="+reason
            +" isometric={live="+isometric+" accepted="+isometricAccepted+" refused="+isometricRefused+"}"
            +String.format(java.util.Locale.ROOT," pose={n=%d muzzleMax=%.3f up=%.3f..%.3f boreMax=%.2f launchMax=%.3f}",
                    poses,poseMuzzle,poseUpMin,poseUpMax,poseBore,poseLaunch)
            +" relief={n="+reliefs+" samples="+reliefSamples+" clamped="+reliefClamped+" last="+lastRelief+"}"; }
    private static long mix(long value) {
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;value=(value^(value>>>27))*0x94d049bb133111ebL;
        return value^(value>>>31);
    }
    private static List<Vec3> directions(Vec3 bore,int count,float dispersion,long seed) {
        ArrayList<Vec3> result=new ArrayList<>();long value=seed;
        for (int i=0;i<count;i++) {
            if (count==1) { result.add(bore);continue; }
            double z=2*uniform(value+=0x9e3779b97f4a7c15L)-1;
            double angle=2*Math.PI*uniform(value+=0x9e3779b97f4a7c15L),radius=Math.sqrt(Math.max(0,1-z*z));
            Vec3 noise=new Vec3((float)(radius*Math.cos(angle)),(float)z,(float)(radius*Math.sin(angle)));
            result.add(bore.times(100).plus(noise.times(dispersion)).unit());
        }
        return List.copyOf(result);
    }
    private static double uniform(long value) { return (mix(value)>>>11)*0x1.0p-53; }
}
