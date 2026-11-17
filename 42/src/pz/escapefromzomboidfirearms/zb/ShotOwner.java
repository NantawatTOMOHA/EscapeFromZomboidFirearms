package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.SpriteRenderer;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoWorld;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.Lua.LuaManager;
import zombie.gameStates.ChooseGameInfo;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Flight;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;

/** Local optional shot boundary. In shadow it never suppresses a native hit; live (the single-player default, user
 * decision 6) owns the aimed and hip shots of our weapons, the channel's zero included.
 * No input/ammo/sound/FX hook is installed. All allocation and admission precede
 * the first skipped contact; the route of a claimed scope stays latched until exit.
 */
public final class ShotOwner {
    static final String[][] MEMBERS = {
        {"zombie.CombatManager", "attackCollisionCheck", "(Lzombie/characters/IsoGameCharacter;Lzombie/inventory/types/HandWeapon;Lzombie/ai/states/SwipeStatePlayer;Lzombie/AttackType;)V", ""},
        {"zombie.CombatManager", "calculateHitInfoList", "(Lzombie/characters/IsoGameCharacter;)V", ""},
        {"zombie.CombatManager", "fireWeapon", "(Lzombie/inventory/types/HandWeapon;Lzombie/characters/IsoGameCharacter;)V", ""},
        {"zombie.CombatManager", "CheckObjectHit", "(Lzombie/characters/IsoGameCharacter;Lzombie/inventory/types/HandWeapon;)Z", ""},
        {"zombie.CombatManager", "objHit", "Lzombie/iso/IsoObject;", ""},
        {"zombie.CombatManager", "treeHit", "Lzombie/iso/objects/IsoTree;", ""},
        {"zombie.CombatManager", "hitOnlyTree", "Z", ""},
        {"viewpoint.gunplay.Gunplay", "shotStarting", "(Lzombie/characters/IsoGameCharacter;Lzombie/inventory/types/HandWeapon;)V", "static"},
        {"viewpoint.gunplay.Gunplay", "hitListMade", "(Lzombie/characters/IsoGameCharacter;)V", "static"},
        {"viewpoint.core.Frame", "takenNanos", "J", ""},
        {"viewpoint.core.Frame", "viewYaw", "F", ""},
        {"viewpoint.core.Frame", "viewPitch", "F", ""},
        {"zombie.characters.IsoGameCharacter", "clearHitInfo", "()V", ""},
        {"zombie.characters.IsoGameCharacter", "getHitInfoList", "()Ljava/util/ArrayList;", ""},
        {"zombie.inventory.InventoryItem", "getID", "()I", ""},
        {"zombie.inventory.InventoryItem", "getFullType", "()Ljava/lang/String;", ""},
        {"zombie.inventory.InventoryItem", "getModData", "()Lse/krka/kahlua/vm/KahluaTable;", ""},
        {"zombie.inventory.InventoryItem", "getAmmoType", "()Lzombie/scripting/objects/AmmoType;", ""},
        {"zombie.scripting.objects.AmmoType", "getItemKey", "()Ljava/lang/String;", ""},
        {"zombie.inventory.InventoryItem", "getCurrentAmmoCount", "()I", ""},
        {"zombie.inventory.types.HandWeapon", "haveChamber", "()Z", ""},
        {"zombie.inventory.types.HandWeapon", "isRoundChambered", "()Z", ""},
        {"zombie.inventory.types.HandWeapon", "isJammed", "()Z", ""},
        // W10 item 15: the rest of vanilla ISReloadWeaponAction.canShoot (canShootReason).
        {"zombie.inventory.types.HandWeapon", "isSelectFire", "()Z", ""},
        {"zombie.inventory.types.HandWeapon", "getFireMode", "()Ljava/lang/String;", ""},
        {"zombie.characters.IsoGameCharacter", "isUnlimitedAmmo", "()Z", ""},
        {"zombie.inventory.types.HandWeapon", "isAimedFirearm", "()Z", ""},
        {"zombie.inventory.types.HandWeapon", "getAmmoPerShoot", "()I", ""},
        {"zombie.inventory.types.HandWeapon", "getPhysicsObject", "()Ljava/lang/String;", ""},
        {"zombie.inventory.types.HandWeapon", "getAllWeaponParts", "()Ljava/util/List;", ""},
        {"zombie.inventory.types.HandWeapon", "getMinDamage", "()F", ""},
        {"zombie.inventory.types.HandWeapon", "getMaxDamage", "()F", ""},
        {"zombie.inventory.InventoryItem", "saveWithSize", "(Ljava/nio/ByteBuffer;Z)V", ""},
        {"zombie.inventory.InventoryItem", "loadItem", "(Ljava/nio/ByteBuffer;I)Lzombie/inventory/InventoryItem;", "static"},
        {"zombie.iso.IsoWorld", "getWorldVersion", "()I", "static"},
        {"zombie.iso.IsoWorld", "render", "()V", ""},
        {"zombie.iso.IsoWorld", "instance", "Lzombie/iso/IsoWorld;", "static"},
        {"zombie.iso.IsoWorld", "currentCell", "Lzombie/iso/IsoCell;", ""},
        {"zombie.network.GameClient", "client", "Z", "static"},
        {"zombie.network.GameServer", "server", "Z", "static"},
        {"zombie.network.GameServer", "udpEngine", "Lzombie/core/raknet/UdpEngine;", "static"},
        {"zombie.core.raknet.UdpEngine", "connections", "Ljava/util/List;", ""},
        {"zombie.characters.IsoGameCharacter", "getPrimaryHandItem", "()Lzombie/inventory/InventoryItem;", ""},
        {"zombie.characters.IsoGameCharacter", "isAiming", "()Z", ""},
        {"zombie.characters.IsoPlayer", "players", "[Lzombie/characters/IsoPlayer;", "static"},
        {"zombie.characters.IsoPlayer", "isLocalPlayer", "()Z", ""},
        {"zombie.characters.IsoPlayer", "getPlayerNum", "()I", ""},
        {"zombie.characters.IsoPlayer", "getOnlineID", "()S", ""},
        {"zombie.characters.IsoLivingCharacter", "isDoShove", "()Z", ""},
        {"zombie.core.skinnedmodel.IGrappleableWrapper", "isDoGrapple", "()Z", ""},
        {"zombie.characters.IsoPlayer", "getNetworkCharacterAI", "()Lzombie/characters/NetworkPlayerAI;", ""},
        {"zombie.network.packets.hit.PlayerHitPacket", "set", "(Lzombie/characters/IsoPlayer;Lzombie/inventory/types/HandWeapon;Ljava/util/List;Ljava/util/List;)V", ""},
        {"zombie.network.packets.hit.PlayerHitPacket", "write", "(Lzombie/core/network/ByteBufferWriter;)V", ""},
        {"zombie.iso.IsoChunkMap", "chunkGridWidth", "I", "static"},
        {"zombie.core.SpriteRenderer", "instance", "Lzombie/core/SpriteRenderer;", "static"},
        {"zombie.core.SpriteRenderer", "getMainStateIndex", "()I", ""},
        {"zombie.GameTime", "getInstance", "()Lzombie/GameTime;", "static"},
        {"zombie.GameTime", "isGamePaused", "()Z", "static"},
        {"zombie.GameTime", "getPhysicsSecondsSinceLastUpdate", "()F", ""},
        {"zombie.gameStates.ChooseGameInfo", "getModDetails", "(Ljava/lang/String;)Lzombie/gameStates/ChooseGameInfo$Mod;", "static"},
        {"zombie.gameStates.ChooseGameInfo$Mod", "getVersionDir", "()Ljava/lang/String;", ""},
        {"zombie.gameStates.ChooseGameInfo$Mod", "getCommonDir", "()Ljava/lang/String;", ""},
        {"zombie.gameStates.ChooseGameInfo$Mod", "getDir", "()Ljava/lang/String;", ""},
        // WeaponFrame.world's native dependencies are part of this capability.
        {"zombie.characters.IsoGameCharacter", "primaryHandModel", "Lzombie/core/skinnedmodel/model/ModelInstance;", ""},
        {"zombie.core.skinnedmodel.model.ModelInstance", "animPlayer", "Lzombie/core/skinnedmodel/animation/AnimationPlayer;", ""},
        {"zombie.core.skinnedmodel.model.ModelInstance", "parentBone", "I", ""},
        {"zombie.core.skinnedmodel.animation.AnimationPlayer", "isReady", "()Z", ""},
        {"zombie.core.skinnedmodel.animation.AnimationPlayer", "getBoneModelTransform", "(ILorg/lwjgl/util/vector/Matrix4f;)Lorg/lwjgl/util/vector/Matrix4f;", ""},
        {"zombie.core.skinnedmodel.animation.AnimationPlayer", "getRenderedAngle", "()F", ""},
        {"zombie.core.skinnedmodel.model.Model", "vectorToWorldCoords", "(FFFFLzombie/iso/Vector3;)V", "static"},
        {"zombie.iso.IsoObject", "getX", "()F", ""},
        {"zombie.iso.IsoObject", "getY", "()F", ""},
        {"zombie.iso.IsoObject", "getZ", "()F", ""},
        {"zombie.core.math.PZMath", "convertMatrix", "(Lorg/lwjgl/util/vector/Matrix4f;Lorg/joml/Matrix4f;)Lorg/joml/Matrix4f;", "static"},
    };
    static final Map<String, Member> members = new HashMap<>();
    private static boolean checked, capable, coreChecked, coreCapable, failed, loggedVtb;
    private static boolean enabled = true, liveRequested, multiplayer, isometricAllowed;
    private static final ShotProtocol.NativeWitness.Counter nativeWitness=new ShotProtocol.NativeWitness.Counter();
    private static long isoFrames;
    private static String status = "shadow; not checked";
    private static TarkovBallisticsData data;
    private static TarkovPenetrationData penetrationData;
    private static ShotSnapshot.Contacts shadowContacts;
    private static ShotTargets shadowTargets;
    private static Object shadowCell,shadowPlayer;
    private static final Map<String,Calibration> zeroCache = new HashMap<>();
    private static Request request;
    private static Capture capture;
    private static long sequence, actionSerial;
    private static String session = UUID.randomUUID().toString();
    private static long epoch = 1;
    private static ShotSnapshot.Contacts contacts;
    private static ShotSnapshot.Damage damage;
    private static ShotSnapshot.Ledger ledger;
    private static ShotSnapshot.Protocol protocol;
    private static LocalIntegration local;
    private static ShotTargets targets;
    private static Consumer<ShotSnapshot> committedListener;
    private static final Engine engine = new Engine(64, ShotOwner::fail);
    private static final Map<ShotSnapshot.Id, FlightBatch> flights = new HashMap<>();
    private static long frames, rejected, segments, committedEvents, applied;
    private static long retiredShots;
    private static Throwable ownerFailure;
    private static boolean failureLogged;
    private static boolean tracing;
    private static String shotFields = "shot=unavailable phase=baseline authority=baseline";
    private static String contactFields = "pellet=unavailable t0=unavailable t1=unavailable fraction=unavailable zone=unavailable damageKey=unavailable";
    private static String last = "";
    public enum Hook { Collision, CollisionExit, Starting, Made, HitList, Fire, ObjectHit, DrawnFrame }
    private static final AtomicLongArray calls = new AtomicLongArray(Hook.values().length);
    private static long optionCalls, requestCalls, validRequests, tickCalls, localInstalls;
    // MP live (sandbox ViewpointBallistics = live on a dedicated server with the companion module):
    // the server flies and damages; this client only claims the shot and reports its drawn pose.
    private static ShotProtocol mpProtocol;
    private static ShotSnapshot.Contacts mpContacts;
    private static Object mpCell, mpPlayer;
    private static String mpChallenge, mpReason="no-handshake";
    private static final ArrayDeque<String> mpOutbound=new ArrayDeque<>();
    private static long mpFired, mpAccepted, mpRejected, mpConfirmed, mpBodies, mpHits, mpMarks;
    /** Characters near a claimed round that were not reported: off the line, without a drawn body,
     * or without a finite position on this side. */
    private static long mpBodiesFar, mpBodiesUndrawn, mpBodiesUnplaced;
    private static String mpBodyUndrawn="none", mpBodyUnplaced="none";
    private static String mpLastReject="none";
    /** PZ's own selection of the last claimed round (the server's fallback hits from it). */
    private static int mpSelected=-1;
    // W11 hostauth (variant B, docs/pending/HOST_CLIENT_AUTHORITY_RU.md): a server without the mod's Java at Server
    // ballistics = Live answers Hello with Authority{session, epoch, policy}. This client then flies its claimed rounds as
    // single player does (LocalIntegration with ShotClientDamage) and reports them (ShotClientAuthority); the server's Lua
    // checks and applies them. Null outside that mode.
    private static String authSession;
    private static int authPolicy;
    /** A Live server without the mod's Java gave this client the flight of its own rounds. */
    static boolean clientAuthority() { return multiplayer && liveRequested && authSession!=null; }
    /** The server module flies this client's claimed rounds (a dedicated server with the mod's Java). */
    static boolean serverFlies() { return multiplayer && !clientAuthority(); }
    /** QA only (TWZB.qaShotMpNext): the next claimed MP round's intent is displaced or lost. */
    private static String mpQaNext="";
    public static void qaNext(String mode) { mpQaNext=mode==null?"":mode; }
    /** QA only (TWZB.qaShotReject): prepare refuses the next own shot, so it falls back to PZ's own. */
    private static String qaRejectNext="";
    public static void qaRejectNext(String reason) { qaRejectNext=reason==null?"qa":reason; }
    private static String lastReject="none", requestStatus="missing", captureStatus="not-drawn";
    /** W10 item 16: our weapon's shots the engine fired that this owner did not take (Viewpoint's or PZ's own shot
     * decided them), the last one's reason, and per reason {nanos of its last console line, refusals since}. */
    private static long givenShots;
    private static String lastGiven="none";
    private static final Map<String,long[]> givenLines=new HashMap<>();
    static final long GIVEN_LINE_NS=30_000_000_000L;
    /** W10 item 16: the forecast of the local player's next shot with our weapon, refreshed each tick (nextShotReason):
     * null while it would be ours, else the refusal it would get. */
    private static volatile String nextShot="unchecked";

    /** Public because ZombieBuddy inlines the advice into the native target class. */
    public static void hook(Hook point) { calls.incrementAndGet(point.ordinal()); }
    static Prepared reject(String reason) { lastReject=reason;return null; }
    public static void unavailable(String reason) {
        requestStatus="lua:"+reason.replaceAll("\\s+","_");
    }

    private ShotOwner() { }

    /** C owns attempt deduplication. B's ledger sees a one-use permit only while
     * C is calling its Damage adapter AFTER recording that exact attempt. This
     * avoids both the former double commit and double-consuming the same ledger.
     * This local instance is never installed in the network/Lua peer registry.
     */
    static final class LocalIntegration {
        final ShotSnapshot.Contacts contacts;
        final ShotLedger ledger;
        final ShotDamage damage;
        final ShotProtocol protocol;
        final String dataHash;
        final int shooter;
        private final BooleanSupplier authority;
        private final LongSupplier clock;
        private boolean active=true;
        private Set<ShotSnapshot.ImpactKey> permits=Set.of();
        long applied;
        LocalIntegration(String worldSession,long connectionEpoch,int shooter,String dataHash,
                ShotSnapshot.Contacts contacts,ShotDamage.Native nativeDamage,BooleanSupplier authority,
                Consumer<String> failure,LongSupplier clock) {
            this.contacts=contacts;this.authority=authority;this.clock=clock;this.dataHash=dataHash;this.shooter=shooter;
            ledger=new ShotLedger(worldSession,2048,clock);
            if (!ledger.connect(shooter,connectionEpoch)) throw new IllegalStateException("Local epoch");
            ShotSnapshot.Ledger permitLedger=new ShotSnapshot.Ledger() {
                public boolean reserve(ShotSnapshot.Id id,long expiry) { throw new UnsupportedOperationException("C reserves"); }
                public boolean nativeFire(ShotSnapshot.Id id,int value) { throw new UnsupportedOperationException("C observes native fire"); }
                public boolean impact(ShotSnapshot.ImpactKey key) {
                    return allowed() && ledger.contains(key.pellet().shot()) && !permits.isEmpty() && permits.remove(key);
                }
                public void finish(ShotSnapshot.Id id,String reason) { ledger.finish(id,reason); }
            };
            damage=new ShotDamage(nativeDamage,permitLedger,s->allowed() && ledger.contains(s.id()),failure);
            ShotSnapshot.Damage committedDamage=new ShotSnapshot.Damage() {
                public boolean ready() { return allowed() && damage.ready(); }
                public List<ShotSnapshot.ImpactKey> commit(ShotSnapshot shot,List<ShotSnapshot.Contact> hits) throws Exception {
                    if (!permits.isEmpty()) throw new IllegalStateException("Nested local damage batch");
                    permits=new HashSet<>();
                    for (var hit:hits) permits.add(hit.key());
                    try {
                        var result=damage.commit(shot,hits);
                        applied+=result.size();
                        return result;
                    } finally { permits=Set.of(); }
                }
            };
            ShotProtocol.Authority localAuthority=new ShotProtocol.Authority() {
                public boolean ready() { return allowed() && contacts.ready(); }
                public boolean admit(ShotProtocol.FireIntent fire,ShotProtocol.NativeFire proof) {
                    return allowed() && fire.shot().id().shooter()==shooter && ledger.contains(fire.shot().id())
                            && dataHash.equals(fire.shot().dataHash()) && proof.prepared().matches(fire.shot());
                }
                public List<ShotSnapshot.Contact> validate(ShotSnapshot shot,List<ShotSnapshot.Contact> hits) {
                    if (!allowed() || !ledger.contains(shot.id())) throw new IllegalStateException("Local authority lost");
                    return hits; // SP contacts came directly from B, never from a wire proposal.
                }
            };
            protocol=new ShotProtocol(ShotProtocol.Role.LOCAL,ledger,dataHash,localAuthority,committedDamage,null,null,clock);
            damage.enabled(true);
        }
        boolean allowed() { return active && authority.getAsBoolean(); }
        long reservationExpiry() { return clock.getAsLong()+60_000_000_000L; }
        void finish(ShotSnapshot.Id id) { damage.finish(id);protocol.expire(); }
        void clear() { active=false;damage.clear();protocol.disconnect(shooter);permits=Set.of(); }
    }

    static boolean localNetwork(boolean client,boolean server,int connections) {
        return !client && (!server || connections==0);
    }
    static boolean localNetwork() {
        return clientAuthority() || !multiplayer && localNetwork(GameClient.client,GameServer.server,
                !GameServer.server ? 0 : GameServer.udpEngine==null ? -1 : GameServer.udpEngine.connections.size());
    }
    static void configureLocal(IsoPlayer player) {
        if (!liveRequested || !localNetwork() || data==null || request==null) return;
        String hash=data.gunplaySha256+":"+data.inputsSha256+":"+request.geometryHash;
        if (local!=null && !local.dataHash.equals(hash)) {
            if (!flights.isEmpty()) return;
            clearLocal();
        }
        if (local!=null || contacts!=null || damage!=null || ledger!=null || protocol!=null) return;
        var cell=IsoWorld.instance==null ? null : IsoWorld.instance.currentCell;
        if (cell==null) return;
        targets=new ShotTargets();
        var world=new ShotContactsPz(()->IsoWorld.instance==null ? null : IsoWorld.instance.currentCell,targets,
                s->s.id().session().equals(session) && s.id().epoch()==epoch && s.id().shooter()==player.getOnlineID()
                        && IsoPlayer.players[0]==player ? player : null,
                reason->fail(new IllegalStateException(reason)));
        // Client authority: the contacts are reported for the server's Lua, never applied for it here (ShotClientDamage).
        local=new LocalIntegration(session,epoch,player.getOnlineID(),hash,new ShotContacts(world),
                clientAuthority()?new ShotClientDamage(world):new ShotDamagePz(world,penetrationData,true),
                ()->enabled && liveRequested && abiCapable() && localNetwork()
                        && IsoWorld.instance!=null && IsoWorld.instance.currentCell==cell
                        && IsoPlayer.players[0]==player && player.isLocalPlayer(),world::fail,ShotOwner::physicalNanos);
        integrations(local.contacts,local.damage,local.ledger,local.protocol);
        localInstalls++;
    }
    static void configureShadow(IsoPlayer player) {
        if (liveRequested || !localNetwork() || penetrationData==null) return;
        var cell=IsoWorld.instance==null?null:IsoWorld.instance.currentCell;
        if (cell==null || shadowCell==cell && shadowPlayer==player && shadowContacts!=null) return;
        if (shadowTargets!=null) shadowTargets.clear();
        shadowTargets=new ShotTargets();shadowCell=cell;shadowPlayer=player;
        shadowContacts=new ShotContacts(new ShotContactsPz(()->IsoWorld.instance==null?null:IsoWorld.instance.currentCell,
                shadowTargets,s->s.id().session().equals(session) && s.id().epoch()==epoch && IsoPlayer.players[0]==player?player:null,
                reason->fail(new IllegalStateException(reason))));
    }
    /** MP: a drawn-bone world only for the point-blank check; the server owns the flight. */
    static void configureMultiplayer(IsoPlayer player) {
        if (!liveRequested || !multiplayer || penetrationData==null) return;
        var cell=IsoWorld.instance==null?null:IsoWorld.instance.currentCell;
        if (cell==null || mpCell==cell && mpPlayer==player && mpContacts!=null) return;
        mpCell=cell;mpPlayer=player;
        mpContacts=new ShotContacts(new ShotContactsPz(()->IsoWorld.instance==null?null:IsoWorld.instance.currentCell,
                new ShotTargets(),s->IsoPlayer.players[0]==player?player:null,reason->fail(new IllegalStateException(reason))));
    }
    static boolean mpLive() {
        try { return multiplayer && liveRequested && mpProtocol!=null && mpProtocol.ready(true) && mpContacts!=null && mpContacts.ready(); }
        catch (Throwable t) { fail(t);return false; }
    }
    /** The ballistics identity both sides load; the client's sight geometry never reaches the server. */
    static String mpHash() { return data==null?"":data.gunplaySha256+":"+data.inputsSha256; }
    /** Lua (TW_ShotOwnerNet): "challenge|nativeShotByte" for a Hello, or nil while MP live is off. */
    public static String netChallenge() {
        IsoPlayer p=IsoPlayer.players[0];
        if (!multiplayer || !liveRequested || !enabled || failed || p==null || !loadActive()) return null;
        mpChallenge=ShotProtocol.NATIVE_WITNESS_CAPABILITY+":"+UUID.randomUUID();
        return mpChallenge+"|"+nativeWitness.last(p);
    }
    public static void netBaseline(String reason) {
        if (authSession!=null) netAuthorityEnd("baseline");
        mpProtocol=null;mpReason="baseline:"+String.valueOf(reason).replaceAll("\\s+","_");
    }
    /** Lua (ShotClientLua): the server's Authority answer to this client's Hello (W11 hostauth). The server's session
     * and epoch name this client's rounds; a repeated answer keeps the mode, an answer to the latest challenge restarts
     * it, a late answer to an older challenge is ignored. */
    public static boolean netAuthority(String worldSession,long connectionEpoch,int policy,String challenge) {
        IsoPlayer p=IsoPlayer.players[0];
        if (!multiplayer || !liveRequested || !enabled || failed || p==null || worldSession==null || worldSession.isEmpty()
                || worldSession.length()>128 || connectionEpoch==0 || policy!=3 && policy!=4) return false;
        if (worldSession.equals(authSession) && connectionEpoch==epoch && policy==authPolicy) return true;
        if (mpChallenge==null || !mpChallenge.equals(challenge)) return false;
        closeUnclaimedEarly();
        cancelFlights("client authority changed");clearLocal();
        mpProtocol=null;mpOutbound.clear();mpChallenge=null;
        authSession=worldSession;authPolicy=policy;session=worldSession;epoch=connectionEpoch;sequence=0;
        mpReason="client-authority";
        ShotClientAuthority.reset(worldSession,connectionEpoch,policy);
        return true;
    }
    /** The server took the mode back (Baseline), or the options or session changed. */
    static void netAuthorityEnd(String reason) {
        if (authSession==null) return;
        closeUnclaimedEarly();
        cancelFlights("client authority ended");clearLocal();
        authSession=null;authPolicy=0;mpReason="client-authority-ended:"+reason;
        ShotClientAuthority.clear(reason);
    }
    /** Client authority holds and the local owner is ready to claim. */
    static boolean clientAuthorityReady() { return clientAuthority() && liveReady(); }
    /** Lua: one server Wire payload. A Hello opens a new CLIENT peer on the server's session/epoch. */
    public static boolean netMessage(String payload) {
        try {
            ShotProtocol.Message message=ShotProtocol.unwire(payload);
            if (message instanceof ShotProtocol.Hello hello) {
                IsoPlayer p=IsoPlayer.players[0];
                if (mpChallenge==null || !mpChallenge.equals(hello.challenge()) || p==null || hello.shooter()!=p.getOnlineID()) return false;
                var ledger=new ShotLedger(hello.session(),2048,System::nanoTime);
                var peer=new ShotProtocol(ShotProtocol.Role.CLIENT,ledger,mpHash(),null,null,
                        (to,m)->{ if (mpOutbound.size()>=4096) throw new IllegalStateException("MP shot outbound capacity");
                            mpOutbound.addLast(ShotProtocol.wire(m)); },ShotOwner::presented,System::nanoTime);
                peer.challenge(mpChallenge);
                if (!peer.handshake(hello)) { mpReason="handshake-rejected";return false; }
                if (!ShotProtocol.NativeWitness.agreed(hello.challenge(),hello.reason())) { mpReason="native-witness-unsupported";return false; }
                nativeWitness.align(p,hello.nativeBase());
                mpOutbound.clear();mpProtocol=peer;session=hello.session();epoch=hello.epoch();sequence=0;
                mpReason="handshake-accepted";mpChallenge=null;
                return true;
            }
            ShotProtocol peer=mpProtocol;
            return peer!=null && peer.confirm(message);
        } catch (Throwable t) { mpReason="message:"+t.getClass().getSimpleName();return false; }
    }
    public static boolean netReady() {
        ShotProtocol peer=mpProtocol;
        try { return peer!=null && peer.ready(true) && peer.capable(ShotProtocol.NATIVE_WITNESS_CAPABILITY); } catch (Throwable t) { return false; }
    }
    public static String netPoll() { return mpOutbound.isEmpty()?"":mpOutbound.removeFirst(); }
    /** Lua: one contact of a server flight (ShotServerLive.mark) as this client's own mark. */
    public static boolean netMark(String payload) {
        try {
            if (payload==null || payload.length()>4096) return false;
            try (var in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(payload)))) {
                var muzzle=new ShotPoseSnapshot.Pz(in.readDouble(),in.readDouble(),in.readDouble());
                var kind=ShotSnapshot.TargetKind.values()[in.readUnsignedByte()];
                Vec3 point=new Vec3(in.readFloat(),in.readFloat(),in.readFloat()),normal=new Vec3(in.readFloat(),in.readFloat(),in.readFloat());
                Vec3 incoming=new Vec3(in.readFloat(),in.readFloat(),in.readFloat());
                boolean forward=in.readBoolean();String material=in.readUTF(),outcome=in.readUTF(),key=in.readUTF();
                long seed=in.readLong();String sprite=in.readUTF();
                var contact=new ShotSnapshot.Contact(new ShotSnapshot.ImpactKey(new ShotSnapshot.PelletId(
                        new ShotSnapshot.Id("mp",1,0,1),0),0,"mp"),kind,0f,point,normal,-1,-1,material,"server-flight",null,forward);
                boolean shown=ImpactMarks.remote(muzzle,contact,incoming,outcome,key,seed,sprite.isEmpty()?null:sprite);
                if (shown) mpMarks++;
                return shown;
            }
        } catch (Throwable t) { mpReason="mark:"+t.getClass().getSimpleName();return false; }
    }
    /** Lua: the server's native hit fields for every client (ShotReplay). */
    public static boolean netHit(String payload) {
        boolean applied=ShotReplay.apply(payload);
        if (applied) mpHits++;
        return applied;
    }
    static void presented(ShotProtocol.Message message) {
        if (message instanceof ShotProtocol.Accepted) mpAccepted++;
        else if (message instanceof ShotProtocol.Rejected r) { mpRejected++;mpLastReject=r.reason(); }
        else if (message instanceof ShotProtocol.ImpactConfirmed) mpConfirmed++;
    }
    /** Every own native shot advances the CLIENT window, owned or not, as the server's does. */
    static void mpNative(IsoGameCharacter actor) {
        ShotProtocol peer=mpProtocol;
        if (peer==null || !(actor instanceof IsoPlayer p) || IsoPlayer.players[0]!=p) return;
        try { peer.nativeShot(nativeWitness.last(p)); }
        catch (Throwable t) { mpProtocol=null;mpReason="native-window:"+t.getClass().getSimpleName(); }
    }
    /** The bodies near this shot's line, as this client drew them (ShotBodies). Corridor: the
     * launch line widened by the spread, plus a sphere around the muzzle for ricochets. */
    static List<ShotBodies.Body> bodies(ShotSnapshot shot,IsoPlayer shooter,Vec3 launch) {
        var cell=IsoWorld.instance==null?null:IsoWorld.instance.currentCell;
        if (cell==null) return List.of();
        record Candidate(double distance,ShotBodies.Body body) { }
        ArrayList<Candidate> found=new ArrayList<>();
        boolean animals=mpProtocol!=null && mpProtocol.capable(ShotProtocol.ANIMAL_CAPABILITY);
        float spread=0.02f+shot.dispersion()/100f;
        for (var obj:cell.getObjectList()) {
            if (!(obj instanceof IsoGameCharacter c) || c==shooter) continue;
            // A body this client cannot place (an animal/corpse at NaN, a remote character the
            // server has not positioned yet) is not reported: its capsules would be NaN and the
            // server would refuse the whole intent (MP isometric, 26.10.2026).
            if (!ShotContactsPz.placed(c)) {
                mpBodiesUnplaced++;
                mpBodyUnplaced=c.getClass().getSimpleName()+"#"+c.getOnlineID()+" at="+c.getX()+","+c.getY()+","+c.getZ();
                continue;
            }
            // An animal (W10 srvlive) only to a server that resolves animal bodies; an older one refuses a whole intent.
            var animal=c instanceof zombie.characters.animals.IsoAnimal a?a:null;
            int kind=animal!=null?(animals?ShotBodies.ANIMAL:-1):c.getClass()==zombie.characters.IsoZombie.class?ShotBodies.ZOMBIE
                    :c.getClass()==IsoPlayer.class?ShotBodies.PLAYER:-1;
            if (kind<0) continue;
            Vec3 p=ShotBodies.metric(shot.pose().muzzlePz(),c.getX(),c.getY(),c.getZ()).plus(new Vec3(0,1,0));
            double along=p.dot(launch);
            double lateral=p.minus(launch.times((float)along)).length();
            boolean near=p.length()<=6, line=along>=-2 && along<=400 && lateral<=2.5+Math.max(0,along)*spread;
            if (!near && !line) { mpBodiesFar++;continue; }
            var zones=animal!=null?ShotContactsPz.animalZones(shot,animal):ShotContactsPz.zones(shot,c);
            if (zones==null && animal!=null) {
                mpBodiesUndrawn++;mpBodyUndrawn="IsoAnimal#"+c.getOnlineID()+" "+animal.getAnimalType()+" at="+c.getX()+","+c.getY();
                var envelope=ShotContactsPz.animalEnvelope(shot,animal);
                if (!envelope.isEmpty()) found.add(new Candidate(p.length(),new ShotBodies.Body(kind,c.getOnlineID(),
                        new ShotPoseSnapshot.Pz(c.getX(),c.getY(),c.getZ()),envelope)));
                continue;
            }
            if (zones==null) {
                var animation=c.getAnimationPlayer();
                mpBodiesUndrawn++;mpBodyUndrawn=c.getClass().getSimpleName()+"#"+c.getOnlineID()+" animation="
                        +(animation==null?"null":animation.isReady()?"ready":"unready")+" at="+c.getX()+","+c.getY();
                // User's report of 24.10.2026, item 3: a body this client has not animated is still a
                // body the server must be able to hit. It goes as the same coarse envelope the
                // single-player trace uses, so a long-range hit is not dropped by the report filter.
                ArrayList<ShotBodies.Zone> envelope=new ArrayList<>();
                for (var zone:ShotContactsPz.envelope(shot,c))
                    envelope.add(new ShotBodies.Zone(zone.ragdoll(),zone.body(),zone.a(),zone.b(),zone.radius()));
                found.add(new Candidate(p.length(),new ShotBodies.Body(kind,c.getOnlineID(),
                        new ShotPoseSnapshot.Pz(c.getX(),c.getY(),c.getZ()),envelope)));
                continue;
            }
            found.add(new Candidate(p.length(),new ShotBodies.Body(kind,c.getOnlineID(),
                    new ShotPoseSnapshot.Pz(c.getX(),c.getY(),c.getZ()),zones)));
        }
        found.sort(java.util.Comparator.comparingDouble(Candidate::distance));
        List<ShotBodies.Body> result=found.stream().limit(ShotBodies.MAX_BODIES).map(Candidate::body).toList();
        mpBodies+=result.size();
        return result;
    }
    /** Lua publishes options, then a request, then ticks Java. Loading the data
     * and installing LOCAL adapters must not depend on a drawn perspective frame
     * or a first collision. Admission still requires that exact drawn frame.
     */
    static void initialize() {
        Request r=request;
        if (!enabled || r==null || !abiReady() || !policy(r)
                || IsoPlayer.players[0]!=r.player || !r.player.isLocalPlayer()
                || r.player.getPrimaryHandItem()!=r.weapon || !loadActive()) return;
        configureLocal(r.player);
        configureShadow(r.player);
        configureMultiplayer(r.player);
    }
    static void clearLocal() {
        shadowContacts=null;shadowCell=null;shadowPlayer=null;
        if (shadowTargets!=null) shadowTargets.clear();shadowTargets=null;
        if (local==null) return;
        local.clear();local=null;
        if (targets!=null) targets.clear();
        targets=null;contacts=null;damage=null;ledger=null;protocol=null;
    }

    public enum State { BASELINE, PREPARED, CLAIMED, COMMITTED, FINISHED, FAILED_CLAIMED, FAILED_COMMITTED }
    /** A particular item could not be frozen before claim. Later shots can still be owned. */
    public static final class SnapshotRejected extends Exception {
        SnapshotRejected(String reason) { super(reason); }
        SnapshotRejected(String reason,Throwable cause) { super(reason,cause); }
    }
    @FunctionalInterface public interface Prepare { Prepared get() throws Exception; }
    public record Prepared(ShotSnapshot shot, Object flight) { }
    /** This engine is exercised directly by the offline fixture; no game initialization. */
    public static final class Scope {
        final Object actor, weapon;
        Prepared prepared;
        public State state = State.BASELINE;
        public boolean owned, fireSeen;
        boolean enteredCollision, enteredStarting;
        /** MP live: PZ's own hit list of this claimed round (zombie.network.fields.hit.HitInfo copies). */
        boolean collect, collected;
        List<Object> vanilla = List.of();
        // 42.22: a token is only a candidate until this exact native packet writes successfully.
        Object nativePacket,nativeToken;
        List<Object> nativeSaved;
        Object nativeSavedSquare;
        boolean nativeDeferred,nativePublished;
        Scope(Object actor, Object weapon) { this.actor = actor; this.weapon = weapon; }
        public ShotSnapshot shot() { return prepared == null ? null : prepared.shot(); }
    }
    public static final class Engine {
        private final int capacity;
        private final Consumer<Throwable> failure;
        private final ThreadLocal<ArrayDeque<Scope>> scopes = ThreadLocal.withInitial(ArrayDeque::new);
        private int reserved;
        private boolean broken;
        public long commits, events, shadowCommits, ownedCommits;
        Engine(int capacity, Consumer<Throwable> failure) { this.capacity=capacity; this.failure=failure; }
        public Scope begin(Object actor, Object weapon, boolean eligible, boolean own, Prepare prepare) {
            return open(actor, weapon, eligible, own, prepare, true, false);
        }
        /** Loaded before Viewpoint: Viewpoint.Patch_Shot.enter calls Gunplay.shotStarting before our Collision.enter. */
        public Scope beginEarly(Object actor, Object weapon, boolean eligible, boolean own, Prepare prepare) {
            return open(actor, weapon, eligible, own, prepare, false, true);
        }
        private Scope open(Object actor, Object weapon, boolean eligible, boolean own, Prepare prepare,
                boolean collision, boolean starting) {
            ArrayDeque<Scope> stack=scopes.get();
            Scope scope = new Scope(actor,weapon);
            scope.enteredCollision = collision;
            scope.enteredStarting = starting;
            stack.push(scope); // Even an unowned nested call masks its parent's suppression.
            if (!eligible || broken || reserved >= capacity) return scope;
            reserved++;
            try {
                scope.prepared=prepare.get();
                if (scope.prepared==null) { reserved--; return scope; }
                scope.owned=own;
                scope.state=own ? State.CLAIMED : State.PREPARED;
            } catch (SnapshotRejected t) {
                reserved--;
                lastReject="prepare:native-snapshot";
                System.out.println("[TW-SHOT] baseline native snapshot: "+t.getMessage().replaceAll("\\s+"," "));
            } catch (Throwable t) { reserved--; stop(t); }
            return scope;
        }
        public Scope peek() { return scopes.get().peek(); }
        /** Adopt a scope opened on Gunplay.shotStarting immediately before Collision.enter (loaded before Viewpoint). */
        public Scope adoptEarly(Object actor, Object weapon) {
            Scope top = scopes.get().peek();
            if (top != null && !top.enteredCollision && top.actor == actor && top.weapon == weapon) {
                top.enteredCollision = true;
                return top;
            }
            return null;
        }
        /** Match a scope just opened on Collision.enter (loaded after Viewpoint). */
        public Scope matchCollision(Object actor, Object weapon) {
            Scope top = scopes.get().peek();
            if (top != null && top.enteredCollision && !top.enteredStarting && top.actor == actor && top.weapon == weapon) {
                top.enteredStarting = true;
                return top;
            }
            return null;
        }
        public Scope current(Object actor, Object weapon) {
            Scope scope=scopes.get().peek();
            return scope!=null && scope.actor==actor && (weapon==null || scope.weapon==weapon) ? scope : null;
        }
        public boolean suppress(Object actor, Object weapon) {
            Scope scope=current(actor,weapon);
            return scope!=null && scope.owned;
        }
        public boolean fire(Object actor, Object weapon, Consumer<Scope> publish) {
            Scope scope=current(actor,weapon);
            if (scope==null || scope.prepared==null) return false;
            if (!scope.fireSeen) {
                scope.fireSeen=true; scope.state=State.COMMITTED; commits++;
                if (scope.owned) ownedCommits++; else shadowCommits++;
                try { publish.accept(scope); events++; }
                catch (Throwable t) { scope.state=State.FAILED_COMMITTED; stop(t); }
            }
            return scope.owned; // Latched even when publishing disabled the owner.
        }
        public void close(Scope scope, Throwable thrown) {
            if (scope==null) return;
            ArrayDeque<Scope> stack=scopes.get();
            if (stack.peek()!=scope) { stop(new IllegalStateException("Shot scope exit order")); stack.remove(scope); }
            else stack.pop();
            if (scope.prepared!=null) {
                if (thrown!=null) {
                    scope.state=scope.fireSeen ? State.FAILED_COMMITTED : State.FAILED_CLAIMED;
                    stop(thrown);
                }
                if (!scope.fireSeen) reserved--;
            }
        }
        public void finish(Scope scope) {
            if (scope.prepared!=null && scope.fireSeen && scope.state!=State.FINISHED) {
                reserved--; scope.state=State.FINISHED;
            }
        }
        private void stop(Throwable t) { if (!broken) { broken=true; failure.accept(t); } }
        public int depth() { return scopes.get().size(); }
        public int reserved() { return reserved; }
    }

    static boolean verify(String[][] rows, ClassLoader loader, Map<String,Member> into) {
        for (String[] row:rows) {
            Member member=ViewpointBridge.resolve(row,loader);
            if (member==null) return false;
            into.put(row[0]+"."+row[1],member);
        }
        return true;
    }
    public static boolean detect() {
        if (failed) return false;
        if (checked) return capable && ViewpointBridge.ready();
        if (!ViewpointBridge.detect()) return false;
        checked=true;
        if (!verify(MEMBERS,ShotOwner.class.getClassLoader(),members)) {
            failBridge(new IllegalStateException("shot member missing/changed")); return false;
        }
        // Either advice order works: whichever of Collision.enter or Starting.enter runs first opens the scope.
        capable=true;
        status="shot ABI ready";
        System.out.println("[TW-SHOT] "+status+"; ABI="+MEMBERS.length+" "+ViewpointBridge.status());
        return true;
    }
    static boolean detectCore() {
        if (failed) return false;
        if (coreChecked) return coreCapable;
        coreChecked=true;
        for (String[] row:MEMBERS) {
            if (row[0].startsWith("viewpoint.")) continue;
            Member member=ViewpointBridge.resolve(row,ShotOwner.class.getClassLoader());
            if (member==null) {
                failBridge(new IllegalStateException("shot core member missing/changed: "+row[0]+"."+row[1]));
                return false;
            }
            members.put(row[0]+"."+row[1],member);
        }
        coreCapable=true;
        if (!checked) status="shot core ABI ready";
        return true;
    }
    static boolean abiReady() {
        if (failed) return false;
        if (isometricAllowed && !ViewpointBridge.perspective()) return detectCore();
        return detect() && ViewpointBridge.ready();
    }
    static boolean abiCapable() {
        return !failed && (isometricAllowed && !ViewpointBridge.perspective() ? coreCapable : capable && ViewpointBridge.ready());
    }
    static void fail(Throwable t) {
        disable(t,false);
    }
    /** A checked interop member is missing/changed: preserve the shared ABI policy. */
    static void failBridge(Throwable t) {
        disable(t,true);
    }
    private static void disable(Throwable t,boolean interop) {
        if (failed) return;
        ShotNativeGuard.traceFailure("owner shutdown",t);
        failed=true; capable=false; status="baseline after error: "+t;
        lastReject="error:"+t.getClass().getSimpleName();
        capture=null;captureStatus="owner-failed";
        // Data/contacts/flight/Lua errors belong to this owner, not the render bridge.
        // A claimed native route stays latched; the spent shot is never replayed.
        if (interop) ViewpointBridge.fail("shot owner",t);
        else { ownerFailure=t;logFailure(); }
    }
    /** Report once, after pending flights and any latched native scope have retired. */
    static void logFailure() {
        if (ownerFailure==null || failureLogged || !flights.isEmpty()) return;
        for (Scope scope:engine.scopes.get()) if (scope.owned && scope.prepared!=null) return;
        failureLogged=true;
        System.out.println("[TWZB] Viewpoint shot owner off: "+ownerFailure+"; retiredShots="+retiredShots
                +"; spent rounds lost by design, no replay/refund; subsequent shots use baseline");
    }
    public static void load(String path) {
        if (data!=null && penetrationData!=null || failed) return;
        try {
            var flight=TarkovBallisticsData.load(Path.of(path));
            var penetration=TarkovPenetrationData.load(Path.of(path).resolveSibling("TW_Penetration.bin"));
            if (!flight.gunplaySha256.equals(penetration.gunplaySha256) || !flight.inputsSha256.equals(penetration.inputsSha256))
                throw new IllegalArgumentException("B4/B5 source hashes differ");
            data=flight;penetrationData=penetration;
        }
        catch (Throwable t) { fail(t); }
    }
    static boolean loadActive() {
        if (data!=null) return true;
        if (failed) return false;
        try {
            var mod=ChooseGameInfo.getModDetails("EscapeFromZomboidFirearms");
            if (mod==null) throw new IllegalStateException("Active mod unavailable");
            for (String root:new String[]{mod.getVersionDir(),mod.getCommonDir(),mod.getDir()}) {
                if (root==null || root.isBlank()) continue;
                Path file=Path.of(root).resolve(TarkovBallisticsData.RESOURCE);
                if (java.nio.file.Files.isRegularFile(file)) { load(file.toString()); return data!=null; }
            }
            throw new IllegalStateException("Active ballistics resource unavailable");
        } catch (Throwable t) { fail(t); return false; }
    }
    static String text(KahluaTable t,String key) {
        Object value=t.rawget(key);
        if (!(value instanceof String s)) throw new IllegalArgumentException("Shot "+key);
        return s;
    }
    static double number(KahluaTable t,String key) {
        Object value=t.rawget(key);
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new IllegalArgumentException("Shot "+key);
        return n.doubleValue();
    }
    static Vec3 vector(KahluaTable t,String key) {
        if (!(t.rawget(key) instanceof KahluaTable v)) throw new IllegalArgumentException("Shot "+key);
        return new Vec3(((Number)v.rawget(1)).floatValue(),((Number)v.rawget(2)).floatValue(),((Number)v.rawget(3)).floatValue());
    }
    record NativePart(ShotSnapshot.Part part,int id,String fullType) { }
    static final class Request {
        final IsoPlayer player;
        final HandWeapon weapon;
        final String key,revision,channel,geometryHash,path;
        final int mode,channelIndex,ownerId;
        final float zero;
        final Vec3 muzzle,bore,up;
        final Vec3 rear,front;
        final double[] sight;
        final String kind;
        final List<NativePart> parts;
        final boolean allowed,isometric,vtb,stt,sttActive,aiming;
        /** The world frame Lua renewed this request in (FpRecoil.worldFrame: one per world update, however long it took). */
        final long stampFrame=FpRecoil.worldFrame();
        Request(IsoPlayer player,HandWeapon weapon,KahluaTable t) {
            this.player=player; this.weapon=weapon;
            key=text(t,"key"); revision=text(t,"revision"); channel=text(t,"channel");
            geometryHash=text(t,"geometryHash");
            path=t.rawget("path") instanceof String s ? s : "";
            channelIndex=t.rawget("channelIndex") instanceof Number n ? n.intValue() : -1;
            ownerId=t.rawget("ownerId") instanceof Number n ? n.intValue() : 0;
            mode=(int)number(t,"mode"); zero=(float)number(t,"zero");
            muzzle=vector(t,"muzzle"); bore=vector(t,"bore").unit(); up=vector(t,"up").unit();
            rear=t.rawget("rear") instanceof KahluaTable ? vector(t,"rear") : null;
            front=t.rawget("front") instanceof KahluaTable ? vector(t,"front") : null;
            kind=t.rawget("kind") instanceof String s ? s : "";
            if (t.rawget("sight") instanceof KahluaTable values) {
                sight=new double[12];
                for (int i=0;i<12;i++) {
                    sight[i]=((Number)values.rawget(i+1)).doubleValue();
                    ShotPoseSnapshot.finite(sight[i]);
                }
            } else sight=null;
            allowed=Boolean.TRUE.equals(t.rawget("allowed"));
            isometric=Boolean.TRUE.equals(t.rawget("isometric"));
            vtb=Boolean.TRUE.equals(t.rawget("vtb")); stt=Boolean.TRUE.equals(t.rawget("stt"));
            sttActive=Boolean.TRUE.equals(t.rawget("sttActive"));
            aiming=Boolean.TRUE.equals(t.rawget("aiming"));
            List<NativePart> copy=new ArrayList<>();
            KahluaTable entries=(KahluaTable)t.rawget("parts");
            for (int i=1; entries.rawget(i)!=null; i++) {
                KahluaTable p=(KahluaTable)entries.rawget(i);
                int id=(int)number(p,"id");
                copy.add(new NativePart(new ShotSnapshot.Part(text(p,"path"),text(p,"tpl"),Integer.toUnsignedString(id)),id,text(p,"fullType")));
            }
            parts=List.copyOf(copy);
        }
    }
    record Capture(Request request, ShotPoseSnapshot pose,Object vpFrame,Object scene,float viewYaw,float viewPitch,
            double[] carriedWorld,float poseYaw,float posePitch,float[] poseEye,boolean freeLook,long worldFrame) { }
    public static void set(IsoPlayer player,HandWeapon weapon,KahluaTable params) {
        requestCalls++;
        if (player==null || weapon==null || params==null) {
            request=null;capture=null;requestStatus="missing";captureStatus="request-missing";return;
        }
        try {
            Request next=new Request(player,weapon,params);
            Request old=request;
            isometricAllowed=next.isometric;
            if (old==null || old.weapon!=weapon || !old.revision.equals(next.revision)
                    || !old.channel.equals(next.channel) || old.mode!=next.mode || old.zero!=next.zero
                    || old.aiming!=next.aiming || old.isometric!=next.isometric) {
                capture=null;captureStatus="request-changed";
            }
            request=next;
            validRequests++;requestStatus="ready";
        } catch (Throwable t) { request=null; capture=null; fail(t); }
    }
    /** Captured at the same world-render exit as PIP, including hip/TP with no optic, and isometric when IsometricBallistics is enabled. */
    public static void afterWorld() {
        closeUnclaimedEarly();
        try {
            boolean inVp=ViewpointBridge.perspective();
            boolean isoMode=!inVp && request!=null && request.isometric;
            if (isoMode) isometricAllowed=true;
            initialize();
            captureStatus= !enabled ? "option-off" : failed ? "owner-failed"
                    : !(isoMode ? detectCore() : detect()) ? "bridge-unavailable"
                    : !inVp && !isoMode ? "isometric-view" : request==null ? "request-missing"
                    : !policy(request) ? policyReason(request) : request.player.getPrimaryHandItem()!=request.weapon
                    ? "weapon-changed" : data==null ? "data-unavailable" : "ready";
            if (!captureStatus.equals("ready")) { capture=null;return; }
            Request r=request;
            if (!inVp) {
                if (!WeaponFrame.world(r.player)) { capture=null;captureStatus="weapon-frame-missing";return; }
                double[] world=WeaponFrame.F.clone();
                int index=SpriteRenderer.instance.getMainStateIndex();
                long taken=System.nanoTime();
                ShotPoseSnapshot.Pz muzzle=point(world,r.muzzle);
                Vec3 bore=direction(world,r.bore);
                Vec3 rawUp=direction(world,r.up);
                Vec3 projUp=rawUp.minus(bore.times(rawUp.dot(bore)));
                if (projUp.length()<=1e-3f) projUp=new Vec3(0,1,0).minus(bore.times(bore.y()));
                if (projUp.length()<=1e-3f) projUp=new Vec3(1,0,0).minus(bore.times(bore.x()));
                Vec3 up=projUp.unit();
                List<Double> frozen=Arrays.stream(world).boxed().toList();
                var pose=new ShotPoseSnapshot(++isoFrames,taken,0L,index,
                        r.weapon.getID(),r.revision,r.channel,r.mode,r.zero,muzzle,bore,up,frozen,poseZero(r));
                capture=new Capture(r,pose,null,null,Float.NaN,Float.NaN,world,Float.NaN,Float.NaN,null,false,FpRecoil.worldFrame());
                frames++;
                return;
            }
            Object frame=ViewpointBridge.frameNow();
            if (frame==null) { capture=null;captureStatus="vp-frame-missing";return; }
            WeaponFrame.LatchCapture latched=WeaponFrame.captureLatch(r.player,frame);
            if (latched==null) { capture=null;captureStatus="weapon-frame-missing";return; }
            double[] carriedWorld=latched.carriedWorld();
            double[] world=latched.drawnWorld();
            int index=SpriteRenderer.instance.getMainStateIndex();
            long number=ViewpointBridge.frameNumber(frame);
            Object scene=ViewpointBridge.sceneOf(frame);
            float poseYaw=latched.poseYaw(),posePitch=latched.posePitch();
            float[] poseEye=latched.poseEye();
            boolean freeLook=latched.freeLook();
            long taken=((Field)members.get("viewpoint.core.Frame.takenNanos")).getLong(frame);
            float yaw=((Field)members.get("viewpoint.core.Frame.viewYaw")).getFloat(frame);
            float pitch=((Field)members.get("viewpoint.core.Frame.viewPitch")).getFloat(frame);
            if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) throw new IllegalArgumentException("Frame view");
            ShotPoseSnapshot.Pz muzzle=point(world,r.muzzle);
            Vec3 bore=direction(world,r.bore), up=direction(world,r.up);
            List<Double> frozen=Arrays.stream(world).boxed().toList();
            var zero=poseZero(r);
            long generation=FpPose.shotGeneration(r.player,r.weapon);
            if (ViewpointBridge.state()==1 && FpPose.enabled && generation==0) {
                capture=null;captureStatus="fp-pose-not-applied";return;
            }
            var pose=new ShotPoseSnapshot(number,taken,generation,index,
                    r.weapon.getID(),r.revision,r.channel,r.mode,r.zero,muzzle,bore,up,frozen,zero);
            capture=new Capture(r,pose,frame,scene,yaw,pitch,carriedWorld,poseYaw,posePitch,poseEye,freeLook,FpRecoil.worldFrame()); frames++;
        } catch (ReflectiveOperationException | ClassCastException | LinkageError t) { capture=null;failBridge(t); }
        catch (Throwable t) { capture=null;captureStatus="error";fail(t); }
        finally {
            try { TpAimDot.afterWorld(request,capture,data); }
            catch (Throwable t) { failDotBoundary(t); }
        }
    }
    private static boolean dotBoundaryLogged;
    private static void failDotBoundary(Throwable t) {
        if (!dotBoundaryLogged) {
            dotBoundaryLogged=true;
            System.out.println("[TW-SHOT] TpAimDot boundary disabled: "+t);
        }
    }
    static ShotPoseSnapshot.Zero poseZero(Request r) {
        String tpl=data==null || data.weapons.get(r.key)==null ? "" : data.weapons.get(r.key).defaultAmmoTpl();
        return new ShotPoseSnapshot.Zero(ShotPoseSnapshot.ZeroKind.NONE,tpl,0,0,false);
    }
    record ZeroProbe(float rangeMetres,double lateralMetres,double verticalMetres,boolean reached) {
        double residual() { return Math.hypot(lateralMetres,verticalMetres); }
    }
    record Calibration(ShotPoseSnapshot.Zero zero,double[] sight,String signature,Vec3 launch,
            double rawPitch,double rawYaw,double rawBorePitch,ZeroProbe rawAtZero,ZeroProbe solvedAtZero,
            List<ZeroProbe> probes) { }
    static Calibration calibration(Request r,double[] world) {
        if (r.sight==null || r.zero<=0 || data==null) return null;
        Vec3 channelForward=direction(world,new Vec3((float)-r.sight[2],(float)-r.sight[6],(float)-r.sight[10]));
        Vec3 channelUp=direction(world,new Vec3((float)r.sight[1],(float)r.sight[5],(float)r.sight[9]));
        channelUp=channelUp.minus(channelForward.times(channelUp.dot(channelForward))).unit();
        var muzzle=point(world,r.muzzle);
        var camera=point(world,new Vec3((float)r.sight[3],(float)r.sight[7],(float)r.sight[11]));
        Vec3 offset=new Vec3((float)((camera.x()-muzzle.x())*TarkovBallistics.METRES_PER_CELL),
                (float)((camera.z()-muzzle.z())*TarkovBallistics.METRES_PER_LEVEL),
                (float)((camera.y()-muzzle.y())*TarkovBallistics.METRES_PER_CELL));
        String signature=(r.weapon==null ? "offline" : Integer.toUnsignedString(r.weapon.getID()))+"|"+r.key
                +"|"+r.revision+"|"+r.channel+"|"+r.mode+"|"+r.zero+"|"+Arrays.toString(r.sight)
                +"|"+r.muzzle+"|"+r.bore+"|"+r.up+"|"+r.rear+"|"+r.front+"|"+offset+"|"+channelForward+"|"+channelUp
                +"|"+data.weapons.get(r.key).defaultAmmoTpl()+"|"+r.geometryHash+"|"+data.gunplaySha256+data.inputsSha256;
        Calibration cached=zeroCache.get(signature);
        if (cached!=null) return cached;
        List<String> tpls=r.parts.stream().map(p->p.part.tpl()).toList();
        Flight zeroFlight=data.zeroingFlight(r.key,tpls); // Actual loaded ammo is deliberately absent.
        float units=(float)data.modelUnitsPerMetre;
        Vec3 sightPoint=new Vec3((float)r.sight[3],(float)r.sight[7],(float)r.sight[11]).dividedBy(units);
        Vec3 forward=new Vec3((float)-r.sight[2],(float)-r.sight[6],(float)-r.sight[10]).unit();
        var geometry=new TarkovBallistics.SightGeometry(r.muzzle.dividedBy(units),r.bore,r.up,sightPoint,forward);
        double pitch,yaw,borePitch;
        ShotPoseSnapshot.ZeroKind kind;
        if (r.kind.equals("aim") || r.kind.equals("iron")) {
            if (r.rear!=null && r.front!=null) {
                var result=TarkovBallistics.ironZero(zeroFlight,r.zero,geometry,r.rear.dividedBy(units),r.front.dividedBy(units));
                pitch=result.elevationFromAlignedSightRadians(); yaw=0; borePitch=result.boreElevationRadians();
            } else {
                // Sightless, folded-front or receiver aim channel: SightState already placed r.sight along the camera/bore
                // axis, so opticZero supplies the initial pitch seed for solveZero against the drawn sight line.
                var result=TarkovBallistics.opticZero(zeroFlight,r.zero,geometry);
                pitch=-result.sightPitchAdjustmentRadians(); yaw=result.boreYawRadians(); borePitch=result.boreElevationRadians();
            }
            kind=ShotPoseSnapshot.ZeroKind.IRON;
        } else if (r.kind.equals("optic") || r.kind.equals("collimator") || r.kind.equals("thermal") || r.kind.equals("nv")) {
            // FB5 item 6 (20.10.2026, "shooting through a thermal in Viewpoint has a range limit or a random spread"):
            // a thermal or night mode of an optic (SightState: the mode's kind) is the same optical channel - its
            // camera is the line of sight. Refused here, every aimed shot through it fell back to PZ's own shot.
            var result=TarkovBallistics.opticZero(zeroFlight,r.zero,geometry);
            pitch=-result.sightPitchAdjustmentRadians(); yaw=result.boreYawRadians(); borePitch=result.boreElevationRadians();
            kind=ShotPoseSnapshot.ZeroKind.OPTIC;
        } else return null;
        // The B4 iron projection is in original PWA axes. It is a diagnostic,
        // not an already calibrated rotation around the drawn channel. The
        // coordinator approved a bounded numerical solve against the same B4
        // defAmmo Flight (W6-B3A ask), keeping pose and PIP completely unchanged.
        float factor=data.assemblySpeedFactor(r.key,tpls);
        String ammoTpl=data.weapons.get(r.key).defaultAmmoTpl();
        Vec3 target=offset.plus(channelForward.times(r.zero));
        Vec3 solved=solveZero(data,ammoTpl,factor,target,pitch);
        Vec3 right=channelUp.cross(channelForward).unit();
        double solvedPitch=Math.asin(Math.max(-1,Math.min(1,solved.dot(channelUp))));
        double solvedYaw=Math.atan2(solved.dot(right),solved.dot(channelForward));
        // Store the actual unit launch direction. The immutable snapshot's
        // pelletDirections already includes this correction; no consumer adds it.
        var zero=new ShotPoseSnapshot.Zero(kind,ammoTpl,solvedPitch,solvedYaw,false);
        ZeroProbe residual=zeroProbe(data,ammoTpl,factor,solved,offset,channelForward,r.zero);
        if (!residual.reached || residual.residual()>=0.001) throw new IllegalArgumentException("Zero did not converge below 1 mm");
        ZeroProbe raw=zeroProbe(data,ammoTpl,factor,rotateLaunch(channelForward,channelUp,pitch,yaw),offset,channelForward,r.zero);
        List<ZeroProbe> probes=List.of(zeroProbe(data,ammoTpl,factor,solved,offset,channelForward,50),
                zeroProbe(data,ammoTpl,factor,solved,offset,channelForward,200),
                zeroProbe(data,ammoTpl,factor,solved,offset,channelForward,300));
        var result=new Calibration(zero,r.sight.clone(),signature,solved,pitch,yaw,borePitch,raw,residual,probes);
        if (zeroCache.size()>=128) zeroCache.clear();
        zeroCache.put(signature,result);
        return result;
    }
    /** Read-only root search in the vertical flight plane. Drag/gravity are B4's,
     * not a new ballistic approximation. Azimuth follows the target exactly;
     * isotropic drag and vertical gravity preserve that horizontal bearing.
     */
    static Vec3 solveZero(TarkovBallisticsData source,String tpl,float factor,Vec3 target,double initialAngle) {
        double horizontal=Math.hypot(target.x(),target.z());
        if (horizontal<1e-4) throw new IllegalArgumentException("Vertical zero target has no horizontal bracket");
        float range=(float)horizontal;
        double low=Math.atan2(target.y(),horizontal), high=low;
        double lowError=zeroHeight(source,tpl,factor,range,low)-target.y();
        if (lowError>0.001) throw new IllegalArgumentException("Invalid zero gravity bracket");
        double step=Math.max(0.002,2*Math.abs(initialAngle)), highError=lowError;
        for (int i=0;i<16 && highError<0;i++) {
            high=Math.min(Math.PI/2-1e-4,low+step);
            highError=zeroHeight(source,tpl,factor,range,high)-target.y();
            step*=2;
        }
        if (highError<0) throw new IllegalArgumentException("No reachable zero bracket within ammo lifetime");
        double best=low,bestError=Math.abs(lowError);
        for (int i=0;i<40;i++) {
            double mid=(low+high)/2;
            double error=zeroHeight(source,tpl,factor,range,mid)-target.y();
            if (Math.abs(error)<bestError) { best=mid;bestError=Math.abs(error); }
            if (bestError<0.0001) break;
            if (error<0) low=mid; else high=mid;
        }
        if (bestError>=0.0009) throw new IllegalArgumentException("Zero root precision");
        double horizontalUnit=Math.cos(best)/horizontal;
        return new Vec3((float)(target.x()*horizontalUnit),(float)Math.sin(best),(float)(target.z()*horizontalUnit)).unit();
    }
    static float zeroHeight(TarkovBallisticsData source,String tpl,float factor,float range,double pitch) {
        return source.flight(tpl,factor,Vec3.ZERO,new Vec3(0,(float)Math.sin(pitch),(float)Math.cos(pitch)))
                .atHorizontalRange(range).sample().position().y();
    }
    static ZeroProbe zeroProbe(TarkovBallisticsData source,String tpl,float factor,Vec3 launch,Vec3 offset,Vec3 axis,float range) {
        Vec3 target=offset.plus(axis.times(range));
        double h=Math.hypot(launch.x(),launch.z());
        if (h<1e-6) return new ZeroProbe(range,0,0,false);
        double hx=launch.x()/h,hz=launch.z()/h;
        float distance=(float)(target.x()*hx+target.z()*hz);
        if (distance<=0) return new ZeroProbe(range,0,0,false);
        try {
            var flight=source.flight(tpl,factor,Vec3.ZERO,new Vec3(0,launch.y(),(float)h));
            float height=flight.atHorizontalRange(distance).sample().position().y();
            return new ZeroProbe(range,-target.x()*hz+target.z()*hx,height-target.y(),true);
        } catch (IllegalArgumentException ex) { return new ZeroProbe(range,0,0,false); }
    }
    static Vec3 launchDirection(double[] world,Request r,Calibration calibration) {
        if (!r.aiming || r.sight==null) return direction(world,r.bore);
        Vec3 forward=direction(world,new Vec3((float)-r.sight[2],(float)-r.sight[6],(float)-r.sight[10]));
        return calibration==null ? forward : calibration.launch;
    }
    static Vec3 rotateLaunch(Vec3 forward,Vec3 up,double pitch,double yaw) {
        Vec3 right=up.cross(forward).unit();
        return forward.times((float)(Math.cos(pitch)*Math.cos(yaw)))
                .plus(right.times((float)(Math.cos(pitch)*Math.sin(yaw))))
                .plus(up.times((float)Math.sin(pitch))).unit();
    }
    /** The server's launch cone about the drawn bore, degrees (ShotServerLive.LIMITS; check_tp_convergence): thirdPersonAim's too. */
    static final double SERVER_LAUNCH_DEGREES=3;
    /** Inside SERVER_LAUNCH_DEGREES by the float precision of the launch the intent carries (radians). */
    static final double SERVER_LAUNCH_MARGIN=1e-4;
    private static final double[] CROSSHAIR=new double[10];
    /** QA (TWZB.qaTpAimLegacy): the third-person shot and laser leave the drawn launch, as before 03.11.2026. */
    public static volatile boolean tpAimLegacy;
    public static volatile long tpAims,tpAimKicks,tpAimClamps,tpAimKept;
    public static volatile double tpAimLastDeg=Double.NaN,tpAimMaxDeg,tpAimLastDepth=Double.NaN;
    /** QA (TWZB.tpAimInfo): the last call's CrosshairAim values (ViewpointBridge.crosshairAim) and the point aimed at. */
    public static final double[] tpAimLastAim=new double[13];
    /**
     * 03.11.2026, user's report (third person: the bullet flies exactly along the laser and misses Viewpoint's reticle).
     * In Viewpoint's third person its crosshair is the camera's aim: the ring marks the surface the screen centre picks,
     * and CrosshairAim turns the body so the character's aim origin faces it. Our shot left the drawn bore, a shoulder away
     * from that origin and turned by the grip pose (QA 02.11: about 0.11 m and 0.85 deg - the first contact 68 px off the
     * ring at 2.3 m, 17 px at 16 m); W12 moved the ring to that contact, which jumped with its depth, and since 02.11 the
     * ring is Viewpoint's again. Viewpoint's own gunplay aims its shots at that point (Gunplay.active: CrosshairAim.camera),
     * so ours does as well: the picked surface (MousePick.aimHit, as drawn: the relief lift taken off by the drawn frame's
     * warp, as the shot's own trace takes it), from the drawn muzzle this round's flight (ammoTpl, factor) solved to cross
     * it (solveZero), then turned by the weapon's Tarkov kick of the drawn frame (FpRecoil.tpKick: the bullet keeps the
     * bore's kick); the laser shows the same flight (laserImpactMetric). Nothing picked (sky, the own vehicle): the point
     * at the sight's zero along the look, or the look itself without a zero; out of the round's reach: straight at the
     * point. A point not ahead of the muzzle along the look (the camera inside cover) keeps the drawn launch. The turn
     * stays within SERVER_LAUNCH_DEGREES of the drawn bore - the cone the server takes in MP - in SP as well, so a bullet
     * never leaves the barrel sideways and both shoot alike: from about 3 m before the muzzle on the point is exact, nearer
     * it keeps part of the offset (QA's 0.11 m: 3 cm at 2 m). Not the isometric view, the first person or OldPoint.
     */
    static Vec3 thirdPersonAim(Request r,double[] world,Vec3 launch,String ammoTpl,float factor,boolean shot) {
        if (tpAimLegacy || r==null || world==null || launch==null || ammoTpl==null || data==null
                || !ViewpointBridge.ready() || ViewpointBridge.state()!=2 || OldPointCompat.overhead()
                || r.player==null || r.player!=IsoPlayer.players[0] || !r.player.isLocalPlayer()) return launch;
        double[] a=CROSSHAIR;
        if (!ViewpointBridge.crosshairAim(a)) return launch;
        double cell=TarkovBallistics.METRES_PER_CELL, level=TarkovBallistics.METRES_PER_LEVEL/cell; // scene units a storey
        var muzzle=point(world,r.muzzle);
        Vec3 look=new Vec3((float)a[3],(float)a[5],(float)a[4]).unit(); // metric x, up, y: the scene's units are isotropic
        // The point: the picked surface, else along the look at the sight's zero (nothing picked)
        double px=a[7],py=a[8],pz=a[9];
        boolean picked=Double.isFinite(px) && Double.isFinite(py) && Double.isFinite(pz);
        if (!picked) {
            double depth=Double.isFinite(a[6]) ? a[6] : r.zero>0 ? r.zero/cell : Double.NaN;
            px=a[0]+a[3]*depth;py=a[1]+a[4]*depth;pz=(a[2]+a[5]*depth)/level;
        }
        System.arraycopy(a,0,tpAimLastAim,0,10);
        tpAimLastAim[10]=px;tpAimLastAim[11]=py;tpAimLastAim[12]=pz;
        Vec3 aim;
        if (Double.isNaN(px)) aim=look;
        else {
            Vec3 target=new Vec3((float)((px-muzzle.x())*cell),(float)((pz-muzzle.z())*TarkovBallistics.METRES_PER_LEVEL),
                    (float)((py-muzzle.y())*cell));
            // The flight is straight as drawn; its contacts take the flat height (reliefWarp: drawn minus the lift from the
            // muzzle). The pick is the flat world (the gbuffer's position: QA 03.11, a far wall's ring point flat 0.619
            // levels, drawn on the look at 0.571), so it is aimed at as drawn: its lift from the muzzle added back.
            // The look and its depth are drawn already.
            Object relief=null;
            if (picked) {
                try { relief=ViewpointBridge.reliefOf(ViewpointBridge.frameNow()); } catch (Throwable t) { relief=null; }
            }
            if (relief!=null) {
                double lift=reliefMetres(relief,muzzle,target)-reliefMetres(relief,muzzle,Vec3.ZERO);
                target=new Vec3(target.x(),(float)(target.y()+lift),target.z());
            }
            if (!(target.dot(look)>0)) {
                if (shot) tpAimKept++;
                return launch;
            }
            try { aim=solveZero(data,ammoTpl,factor,target,0); }
            catch (IllegalArgumentException ex) { aim=target.unit(); }
        }
        double[] kick=FpRecoil.tpKick(r.player,FpRecoil.worldFrame());
        if (kick!=null) {
            aim=kicked(world,kick,aim);
            if (shot) tpAimKicks++;
        }
        Vec3 bore=direction(world,r.bore);
        double max=Math.toRadians(SERVER_LAUNCH_DEGREES)-SERVER_LAUNCH_MARGIN;
        double cos=Math.max(-1,Math.min(1,aim.dot(bore)));
        if (Math.acos(cos)>max) {
            Vec3 side=aim.minus(bore.times((float)cos));
            if (side.length()>1e-6f) {
                aim=bore.times((float)Math.cos(max)).plus(side.unit().times((float)Math.sin(max))).unit();
                if (shot) tpAimClamps++;
            }
        }
        if (shot) {
            tpAims++;
            tpAimLastDepth=Double.isFinite(a[6]) ? a[6]*cell : Double.POSITIVE_INFINITY;
            tpAimLastDeg=Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,aim.dot(launch.unit())))));
            tpAimMaxDeg=Math.max(tpAimMaxDeg,tpAimLastDeg);
        }
        return aim;
    }
    /**
     * A direction turned by the weapon's own kick: kick is Prop1 <- the weapon moved in Prop1's model axes (the drawn
     * frame's axes: WeaponFrame), so the drawn rotation R takes v to R kick R^-1 v (orthonormalised metric axes of world).
     */
    static Vec3 kicked(double[] world,double[] kick,Vec3 v) {
        Vec3 y=direction(world,new Vec3(0,1,0));
        Vec3 x=direction(world,new Vec3(1,0,0));
        x=x.minus(y.times(x.dot(y))).unit();
        Vec3 z=direction(world,new Vec3(0,0,1));
        z=z.minus(y.times(z.dot(y))).minus(x.times(z.dot(x))).unit();
        double lx=v.dot(x),ly=v.dot(y),lz=v.dot(z);
        double kx=kick[0]*lx+kick[1]*ly+kick[2]*lz, ky=kick[4]*lx+kick[5]*ly+kick[6]*lz, kz=kick[8]*lx+kick[9]*ly+kick[10]*lz;
        return x.times((float)kx).plus(y.times((float)ky)).plus(z.times((float)kz)).unit();
    }
    public static KahluaTable zero(IsoPlayer player,HandWeapon weapon,KahluaTable params) {
        if (!enabled || !detect() || !ViewpointBridge.perspective() || !loadActive()) return null;
        try {
            Request r=new Request(player,weapon,params);
            if (!policy(r)) return null;
            Capture drawn=capture;
            if (drawn==null || drawn.request.weapon!=weapon || !drawn.pose.assemblyRevision().equals(r.revision)
                    || !drawn.pose.channel().equals(r.channel)) return null;
            Calibration calibration=calibration(r,drawn.pose.worldWeaponFrame().stream().mapToDouble(Double::doubleValue).toArray());
            if (calibration==null) return null;
            KahluaTable result=LuaManager.platform.newTable(), sight=LuaManager.platform.newTable();
            for (int i=0;i<12;i++) sight.rawset(i+1,(Object)Double.valueOf(calibration.sight[i]));
            result.rawset("sight",sight); result.rawset("signature",calibration.signature);
            result.rawset("revision",r.revision); result.rawset("channel",r.channel); result.rawset("metres",(double)r.zero);
            result.rawset("kind",calibration.zero.kind().name()); result.rawset("defaultAmmo",calibration.zero.defaultAmmoTpl());
            result.rawset("pitch",calibration.zero.pitchRadians()); result.rawset("yaw",calibration.zero.yawRadians());
            result.rawset("rawPitch",calibration.rawPitch); result.rawset("rawYaw",calibration.rawYaw);
            result.rawset("residualMetres",calibration.solvedAtZero.residual());
            return result;
        } catch (IllegalArgumentException t) { return null; } // Missing geometry/modifier: baseline, never guess.
        catch (Throwable t) { fail(t); return null; }
    }
    record LaserZeroEntry(double pitch,double yaw) { }
    private static final Map<String,LaserZeroEntry> laserZeroCache = new HashMap<>();

    static Vec3 laserImpactMetric(Request r,double[] world,String nativeAmmoKey,double distanceMetres,
            double surfaceZ,Vec3[] outLaunch) {
        return laserImpactMetric(r,world,nativeAmmoKey,distanceMetres,surfaceZ,outLaunch,ShotPenetration.Warp.NONE);
    }
    /** The laser's point of impact along the flight the shot itself takes, the drawn world's relief included (warp). */
    static Vec3 laserImpactMetric(Request r,double[] world,String nativeAmmoKey,double distanceMetres,
            double surfaceZ,Vec3[] outLaunch,ShotPenetration.Warp warp) {
        if (r==null || world==null || world.length<12 || data==null) return null;
        var weaponRow=data.weapons.get(r.key);
        if (weaponRow==null) return null;
        List<String> tpls=r.parts.stream().map(p->p.part.tpl()).toList();
        for (String tpl:tpls) if (!data.modifiers.containsKey(tpl)) return null;
        ShotPoseSnapshot.Pz muzzle=point(world,r.muzzle);
        Vec3 launch;
        if (!r.aiming || r.sight==null) {
            launch=direction(world,r.bore);
        } else if (r.zero<=0) {
            launch=direction(world,new Vec3((float)-r.sight[2],(float)-r.sight[6],(float)-r.sight[10]));
        } else {
            Vec3 channelForward=direction(world,new Vec3((float)-r.sight[2],(float)-r.sight[6],(float)-r.sight[10]));
            Vec3 channelUp=direction(world,new Vec3((float)r.sight[1],(float)r.sight[5],(float)r.sight[9]));
            channelUp=channelUp.minus(channelForward.times(channelUp.dot(channelForward))).unit();
            int qFwdY=Math.round(channelForward.y()*5000f), qUpY=Math.round(channelUp.y()*5000f);
            String key=r.key+"|"+r.revision+"|"+r.channel+"|"+r.mode+"|"+r.zero
                    +"|"+Arrays.toString(r.sight)+"|"+qFwdY+"|"+qUpY+"|"+r.geometryHash;
            LaserZeroEntry cached=laserZeroCache.get(key);
            if (cached==null) {
                Calibration cal=calibration(r,world);
                if (cal==null) return null;
                cached=new LaserZeroEntry(cal.zero.pitchRadians(),cal.zero.yawRadians());
                if (laserZeroCache.size()>=256) laserZeroCache.clear();
                laserZeroCache.put(key,cached);
            }
            launch=rotateLaunch(channelForward,channelUp,cached.pitch(),cached.yaw());
        }
        launch=thirdPersonAim(r,world,launch,laserAmmo(r,nativeAmmoKey),data.assemblySpeedFactor(r.key,tpls),false);
        if (outLaunch!=null && outLaunch.length>0) outLaunch[0]=launch;
        if (!(distanceMetres>0)) return Vec3.ZERO;
        Flight flight=laserFlight(r,nativeAmmoKey,launch);
        if (flight==null) return null;
        var ammo=flight.ammo();
        int maxNodes=TarkovBallistics.CACHE_NODES;
        // The drawn flight (nodes a, b) is interpolated first and the point then taken at its flat height
        // (ShotPenetration.Warp), as the shot's own trace takes it: a node spans metres, and the relief can rise
        // steeply between two. The dot is drawn with the lift added back, on the drawn flight at the drawn surface.
        Vec3 a=flight.node(0).position();
        boolean hasPlane=Double.isFinite(surfaceZ);
        double planeZ=hasPlane ? WorldRay.snapFloorSlabZ(surfaceZ) : Double.NaN;
        float planeY=hasPlane ? (float)((planeZ-muzzle.z())*TarkovBallistics.METRES_PER_LEVEL) : Float.NaN;
        double launchH=Math.hypot(launch.x(),launch.z());
        double targetH=distanceMetres*launchH;
        for (int idx=1;idx<maxNodes;idx++) {
            var node=flight.node(idx);
            if (node.timeSeconds()>ammo.lifeSeconds()) break;
            Vec3 b=node.position();
            if (hasPlane) {
                float da=warp.flat(a,Vec3.ZERO).y()-planeY, db=warp.flat(b,Vec3.ZERO).y()-planeY;
                if ((da>=0f && db<=0f || da<=0f && db>=0f) && Math.abs(da-db)>1e-6f) {
                    float low=0f, high=1f;
                    for (int i=0;i<24;i++) {
                        float mid=(low+high)/2, dm=warp.flat(a.plus(b.minus(a).times(mid)),Vec3.ZERO).y()-planeY;
                        if (da>=0f ? dm>=0f : dm<=0f) low=mid; else high=mid;
                    }
                    return warp.flat(a.plus(b.minus(a).times((low+high)/2)),Vec3.ZERO);
                }
                if (launchH>1e-4 && Math.hypot(a.x(),a.z())>targetH*1.5+10.0) break;
            } else if (launchH>1e-4) {
                double ha=Math.hypot(a.x(),a.z());
                double hb=Math.hypot(b.x(),b.z());
                if (hb>=targetH && hb>ha+1e-6) {
                    float ratio=(float)Math.clamp((targetH-ha)/(hb-ha),0.0,1.0);
                    return warp.flat(a.plus(b.minus(a).times(ratio)),Vec3.ZERO);
                }
            } else {
                float da=a.dot(launch), db=b.dot(launch);
                if (db>=distanceMetres && db>da+1e-6f) {
                    float ratio=(float)Math.clamp((distanceMetres-da)/(db-da),0.0,1.0);
                    return warp.flat(a.plus(b.minus(a).times(ratio)),Vec3.ZERO);
                }
            }
            a=b;
        }
        if (hasPlane && launchH>1e-4) {
            a=flight.node(0).position();
            for (int idx=1;idx<maxNodes;idx++) {
                var node=flight.node(idx);
                if (node.timeSeconds()>ammo.lifeSeconds()) break;
                Vec3 b=node.position();
                double ha=Math.hypot(a.x(),a.z());
                double hb=Math.hypot(b.x(),b.z());
                if (hb>=targetH && hb>ha+1e-6) {
                    float ratio=(float)Math.clamp((targetH-ha)/(hb-ha),0.0,1.0);
                    return warp.flat(a.plus(b.minus(a).times(ratio)),Vec3.ZERO);
                }
                a=b;
            }
        }
        return warp.flat(a,Vec3.ZERO);
    }
    /** The flight a laser shows: the held weapon's ammunition (its default when unknown or inert), the assembly's speed. */
    static Flight laserFlight(Request r,String nativeAmmoKey,Vec3 launch) {
        String ammoTpl=laserAmmo(r,nativeAmmoKey);
        if (ammoTpl==null) return null;
        List<String> tpls=r.parts.stream().map(p->p.part.tpl()).toList();
        return data.flight(ammoTpl,data.assemblySpeedFactor(r.key,tpls),Vec3.ZERO,launch);
    }
    /** The round a laser shows (laserFlight): the held weapon's, its default when unknown or inert; null without one. */
    static String laserAmmo(Request r,String nativeAmmoKey) {
        var weaponRow=data.weapons.get(r.key);
        if (weaponRow==null) return null;
        var profile=actualAmmo(data,nativeAmmoKey);
        String ammoTpl=profile!=null && !data.ammo.get(profile.ammoTpl()).inert()
                ? profile.ammoTpl() : weaponRow.defaultAmmoTpl();
        var ammo=data.ammo.get(ammoTpl);
        return ammo==null || ammo.inert() ? null : ammoTpl;
    }
    /**
     * The relief of the drawn frame as the flight's Warp (user's report of 27.10.2026: bullets and the laser dot above the
     * sight on far targets, aligned on others). Viewpoint draws the world lifted by FarRelief$Lift.scene (scene units, one
     * per tile across, about 0 at the camera); a point's flat height is its drawn height minus that lift, relative to the
     * lift where the branch began. NONE without a frame, its relief or the bridge.
     */
    static ShotPenetration.Warp reliefWarp(Object frame,ShotPoseSnapshot.Pz muzzle) {
        Object relief;
        try { relief=frame==null ? null : ViewpointBridge.reliefOf(frame); }
        catch (Throwable t) { failBridge(t);return ShotPenetration.Warp.NONE; }
        if (relief==null) return ShotPenetration.Warp.NONE;
        return (point,origin)->{
            double lift=reliefMetres(relief,muzzle,point)-reliefMetres(relief,muzzle,origin);
            return lift==0 ? point : new Vec3(point.x(),(float)(point.y()-lift),point.z());
        };
    }
    /** The relief's lift (metres) at a flight point given in metres from the muzzle (x, up, y). */
    static double reliefMetres(Object relief,ShotPoseSnapshot.Pz muzzle,Vec3 p) {
        return ViewpointBridge.lift(relief,muzzle.x()+p.x()/TarkovBallistics.METRES_PER_CELL,
                muzzle.y()+p.z()/TarkovBallistics.METRES_PER_CELL)*TarkovBallistics.METRES_PER_CELL;
    }

    /** Ballistic impact point and launch ray for the held weapon's laser in Viewpoint (W9-aim). */
    public static boolean laserTarget(IsoPlayer player,HandWeapon weapon,double distanceMetres,double surfaceZ,KahluaTable out) {
        if (out==null || player==null || weapon==null || !enabled || failed
                || !ViewpointBridge.perspective() || !detect() || !loadActive()) return false;
        try {
            Request r=request;
            if (r==null || r.player!=player || r.weapon!=weapon || !policy(r)) return false;
            if (!WeaponFrame.drawnWorld(player,null)) return false;
            double[] world=WeaponFrame.F.clone();
            ShotPoseSnapshot.Pz muzzle=point(world,r.muzzle);
            var ammoType=weapon.getAmmoType();
            String nativeAmmo=ammoType==null ? "" : ammoType.getItemKey();
            Vec3[] launchHolder=new Vec3[1];
            Vec3 impactMetric=laserImpactMetric(r,world,nativeAmmo,distanceMetres,surfaceZ,launchHolder,
                    reliefWarp(ViewpointBridge.frameNow(),muzzle));
            if (impactMetric==null || launchHolder[0]==null) return false;
            ShotPoseSnapshot.Pz impact=muzzle.offset(impactMetric);
            Vec3 launch=launchHolder[0];
            double vScale=TarkovBallistics.METRES_PER_LEVEL/TarkovBallistics.METRES_PER_CELL;
            out.rawset(1,(Object)Double.valueOf(impact.x()));
            out.rawset(2,(Object)Double.valueOf(impact.y()));
            out.rawset(3,(Object)Double.valueOf(impact.z()));
            out.rawset(4,(Object)Double.valueOf(muzzle.x()));
            out.rawset(5,(Object)Double.valueOf(muzzle.y()));
            out.rawset(6,(Object)Double.valueOf(muzzle.z()));
            out.rawset(7,(Object)Double.valueOf((double)launch.x()));
            out.rawset(8,(Object)Double.valueOf((double)launch.z()));
            out.rawset(9,(Object)Double.valueOf(launch.y()/vScale));
            return true;
        } catch (IllegalArgumentException t) { return false; }
        catch (Throwable t) { fail(t); return false; }
    }

    /** Metres the laser's probe may leave the warped flight (coarser than the shot's own trace: it runs every 16-50 ms). */
    static final float LASER_TOLERANCE=.02f;
    /**
     * The laser's probe for its surface along the shot's own flat flight where the drawn frame has a relief (QA
     * 27.10.2026, Rosewood after the relief warp: a straight ray from the muzzle met a wall square at 22 m drawn 0.8 m
     * above the bullets' path, while they flew on to the building at 28 m). The flight is cut into straight pieces
     * within LASER_TOLERANCE of its flat line (ShotPenetration.pieces), each traced as R.trace traces; the result is
     * R.trace's (the hit, its point, reason), bent=true, its distance the straight ray's that laserTarget turns back into
     * this point (horizontal tiles / horizontal launch). Null without a relief (the straight ray is the flight's line),
     * for a near-vertical launch or without the shot's request: TW/LaserRender then traces the straight ray.
     */
    public static KahluaTable laserProbe(IsoPlayer player,HandWeapon weapon,double rangeTiles) {
        if (player==null || weapon==null || !enabled || failed || !(rangeTiles>0)
                || !ViewpointBridge.perspective() || !detect() || !loadActive()) return null;
        try {
            Request r=request;
            if (r==null || r.player!=player || r.weapon!=weapon || !policy(r)) return null;
            if (!WeaponFrame.drawnWorld(player,null)) return null;
            double[] world=WeaponFrame.F.clone();
            ShotPoseSnapshot.Pz muzzle=point(world,r.muzzle);
            ShotPenetration.Warp warp=reliefWarp(ViewpointBridge.frameNow(),muzzle);
            if (warp==ShotPenetration.Warp.NONE) return null;
            var ammoType=weapon.getAmmoType();
            String nativeAmmo=ammoType==null ? "" : ammoType.getItemKey();
            Vec3[] launchHolder=new Vec3[1];
            if (laserImpactMetric(r,world,nativeAmmo,0,Double.NaN,launchHolder,warp)==null || launchHolder[0]==null) return null;
            double launchH=Math.hypot(launchHolder[0].x(),launchHolder[0].z());
            Flight flight=launchH<1e-3 ? null : laserFlight(r,nativeAmmo,launchHolder[0]);
            if (flight==null) return null;
            java.util.function.Function<Float,Vec3> drawn=time->flight.sample(time).position();
            double vScale=TarkovBallistics.METRES_PER_LEVEL/TarkovBallistics.METRES_PER_CELL, travelled=0;
            float life=flight.ammo().lifeSeconds(), t0=0;
            Vec3 a=warp.flat(drawn.apply(0f),Vec3.ZERO);
            ArrayList<ShotPenetration.Piece> pieces=new ArrayList<>();
            while (t0<life && travelled<rangeTiles) {
                float t1=Math.min(life,t0+TarkovBallistics.STEP_SECONDS);
                Vec3 z=warp.flat(drawn.apply(t1),Vec3.ZERO);
                pieces.clear();
                ShotPenetration.pieces(warp,drawn,Vec3.ZERO,LASER_TOLERANCE,t0,a,t1,z,0,pieces);
                for (var piece:pieces) {
                    ShotPoseSnapshot.Pz from=muzzle.offset(piece.from()), to=muzzle.offset(piece.to());
                    double dx=to.x()-from.x(), dy=to.y()-from.y(), dz=to.z()-from.z();
                    double length=Math.sqrt(dx*dx+dy*dy+dz*dz*vScale*vScale);
                    if (length<1e-6) continue;
                    double range=Math.min(length,rangeTiles-travelled);
                    KahluaTable ray=LuaManager.platform.newTable();
                    ray.rawset("x",from.x());ray.rawset("y",from.y());ray.rawset("z",from.z());
                    ray.rawset("dx",dx/length);ray.rawset("dy",dy/length);ray.rawset("dz",dz/length);
                    ray.rawset("range",range);ray.rawset("begin",0.0);ray.rawset("owner",player);
                    KahluaTable result=WorldRay.trace(ray,player);
                    if (result==null) return null;
                    travelled+=range;
                    if (result.rawget("hit")!=null || !"range".equals(result.rawget("reason")) || travelled>=rangeTiles) {
                        double hx=(Double)result.rawget("x"), hy=(Double)result.rawget("y");
                        result.rawset("distance",Math.hypot(hx-muzzle.x(),hy-muzzle.y())/launchH);
                        result.rawset("context",null);result.rawset("bent",Boolean.TRUE);
                        return result;
                    }
                }
                t0=t1;a=z;
            }
            return null;
        } catch (IllegalArgumentException t) { return null; }
        catch (Throwable t) { fail(t); return null; }
    }

    static ShotPoseSnapshot.Pz point(double[] w,Vec3 p) {
        return new ShotPoseSnapshot.Pz(w[0]+w[3]*p.x()+w[6]*p.y()+w[9]*p.z(),
                w[1]+w[4]*p.x()+w[7]*p.y()+w[10]*p.z(),w[2]+w[5]*p.x()+w[8]*p.y()+w[11]*p.z());
    }
    static Vec3 direction(double[] w,Vec3 p) {
        return new Vec3((float)((w[3]*p.x()+w[6]*p.y()+w[9]*p.z())*TarkovBallistics.METRES_PER_CELL),
                (float)((w[5]*p.x()+w[8]*p.y()+w[11]*p.z())*TarkovBallistics.METRES_PER_LEVEL),
                (float)((w[4]*p.x()+w[7]*p.y()+w[10]*p.z())*TarkovBallistics.METRES_PER_CELL)).unit();
    }
    /** Diagnostic geographic XYZ in metres: PZ vertical is Z; B4 vertical is Y.
     * This is only an axis permutation, never a second transform of the flight.
     */
    static Vec3 pzDirection(Vec3 metric) { return new Vec3(metric.x(),metric.z(),metric.y()); }
    static boolean policy(Request r) {
        if (r.vtb && !loggedVtb) { loggedVtb=true; System.out.println("[TW-SHOT] VTB active: installed baseline (no supported exclusion)"); }
        return (r.allowed || r.isometric) && !r.vtb && !r.stt;
    }
    static String policyReason(Request r) {
        return !(r.allowed || r.isometric) ? "support-off" : r.vtb ? "vtb-active" : r.stt ? "stt-handoff" : "ready";
    }
    static boolean nativeParts(Request r) {
        var installed=r.weapon.getAllWeaponParts();
        if (installed.size()!=r.parts.size()) return false;
        for (var part:installed) {
            boolean match=false;
            for (NativePart p:r.parts) if (p.id==part.getID() && p.fullType.equals(part.getFullType())
                    && (p.part.path().equals("mod_magazine") || p.part.path().equals(part.getModData().rawget("TWPath")))) { match=true; break; }
            if (!match) return false;
        }
        return true;
    }
    /** Recheck our live S2 storage as well: a channel/zero change may occur between
     * the Lua update and the animation's collision callback within the same tick.
     */
    static boolean currentChannel(Request r) {
        if (r.channelIndex<0) return !r.aiming && r.sight==null;
        Object value=r.weapon.getModData().rawget("TW");
        if (!(value instanceof KahluaTable tw) || !r.path.equals(tw.rawget("activeSight"))
                || !(tw.rawget("activeSightChannel") instanceof Number active) || active.intValue()!=r.channelIndex) return false;
        InventoryItem owner=r.path.isEmpty() ? r.weapon : null;
        if (owner==null) for (var part:r.weapon.getAllWeaponParts()) if (part.getID()==r.ownerId) { owner=part; break; }
        if (owner==null || owner.getID()!=r.ownerId) return false;
        Object optic=owner.getModData().rawget("TWOptic");
        if (!(optic instanceof KahluaTable o) || !(o.rawget("channels") instanceof KahluaTable channels)
                || !(channels.rawget(Integer.toString(r.channelIndex)) instanceof KahluaTable state)) return false;
        return channelMatches(state,r.mode,r.zero);
    }
    static boolean channelMatches(KahluaTable state,int mode,float zero) {
        return state.rawget("mode") instanceof Number m && m.intValue()==mode
                && state.rawget("zeroDistance") instanceof Number z && z.doubleValue()==zero;
    }
    static TarkovBallisticsData.Profile actualAmmo(TarkovBallisticsData data,String nativeType) {
        if (nativeType==null || nativeType.isEmpty()) return null;
        TarkovBallisticsData.Profile result=null;
        for (var p:data.profiles.values()) if (nativeType.equals(p.pzFullType()) || nativeType.equals(p.ownFullType())) {
            if (result!=null && !result.ammoTpl().equals(p.ammoTpl())) return null;
            result=p;
        }
        return result;
    }
    static ShotSnapshot.NativeWeapon freezeWeapon(HandWeapon weapon) throws SnapshotRejected {
        float min=weapon.getMinDamage(),max=weapon.getMaxDamage();
        if (!Float.isFinite(min) || !Float.isFinite(max) || min<0 || max<min)
            throw new SnapshotRejected("invalid live damage min="+min+" max="+max);
        try {
            ByteBuffer buffer=ByteBuffer.allocate(262144);
            weapon.saveWithSize(buffer,false);
            byte[] bytes=Arrays.copyOf(buffer.array(),buffer.position());
            int version=IsoWorld.getWorldVersion();
            // PZ persists min/max damage, but NOT ammoType: load restores the script enum.
            // The actual enum key is frozen separately in ShotSnapshot.nativeAmmoType.
            InventoryItem item=InventoryItem.loadItem(ByteBuffer.wrap(bytes),version);
            if (!(item instanceof HandWeapon view) || view==weapon)
                throw new SnapshotRejected("not a detached HandWeapon");
            if (!java.util.Objects.equals(view.getFullType(),weapon.getFullType()))
                throw new SnapshotRejected("fullType live="+weapon.getFullType()+" loaded="+view.getFullType());
            if (view.getMinDamage()!=min || view.getMaxDamage()!=max)
                throw new SnapshotRejected("damage live="+min+"/"+max+" loaded="+view.getMinDamage()+"/"+view.getMaxDamage());
            return new ShotSnapshot.NativeWeapon(version,bytes);
        } catch (java.io.IOException | java.nio.BufferOverflowException ex) {
            throw new SnapshotRejected("save/load: "+ex,ex);
        }
    }
    static Prepared prepare(IsoPlayer p,HandWeapon weapon) throws Exception {
        Request r=request; Capture drawn=capture;
        if (r==null) return reject("prepare:request-missing");
        if (drawn==null) return reject("prepare:frame-missing:"+captureStatus);
        if (r.player!=p || r.weapon!=weapon) return reject("prepare:request-identity");
        if (!policy(r)) return reject("prepare:"+policyReason(r));
        if (data==null) return reject("prepare:data-unavailable");
        long now=System.nanoTime(), worldFrame=FpRecoil.worldFrame();
        // W10 item 18: freshness in world frames, as FpRecoil's (VP-W7-R2): one long frame (a GC pause, chunk streaming,
        // an autosave) right before the shot is no gap. A 250 ms wall clock here handed such a shot to Viewpoint's or PZ's
        // own. A pause runs no world update, so no shot; a switched or dropped weapon is refused by its identity above.
        if (!FpPose.freshFrame(r.stampFrame,worldFrame)) return reject("prepare:request-stale");
        if (!FpPose.freshFrame(drawn.worldFrame,worldFrame) || drawn.pose.takenNanos()>now) return reject("prepare:frame-age");
        if (drawn.vpFrame==null) return prepareIsometric(p,weapon,r,drawn);
        if (!r.revision.equals(drawn.pose.assemblyRevision())
                || !r.channel.equals(drawn.pose.channel()) || r.mode!=drawn.pose.mode() || r.zero!=drawn.pose.zeroMetres()
                || r.aiming!=drawn.request.aiming || HipFireInput.aimIntent(p)!=drawn.request.aiming) return reject("prepare:frame-request-changed");
        if (FpPose.shotGeneration(p,weapon)!=drawn.pose.poseGeneration()) return reject("prepare:pose-changed");
        if (!nativeParts(r)) return reject("prepare:native-parts-changed");
        if (!currentChannel(r)) return reject("prepare:channel-changed");
        if (!qaRejectNext.isEmpty()) { String reason=qaRejectNext;qaRejectNext="";return reject("prepare:qa-"+reason); }
        if (r.sttActive && !Boolean.TRUE.equals(weapon.getModData().rawget("ISeeThem_DisabledComponent"))) return reject("prepare:stt-exclusion-missing");
        try {
            if (ViewpointBridge.frameNumber(drawn.vpFrame)!=drawn.pose.frameNumber()
                    || ViewpointBridge.sceneOf(drawn.vpFrame)!=drawn.scene) return reject("prepare:frame-reused");
        } catch (Throwable t) { failBridge(t);throw new IllegalStateException("Drawn frame identity",t); }
        var ammoType=weapon.getAmmoType();
        String nativeType=ammoType==null ? "" : ammoType.getItemKey(); // Before native onShoot, never AmmoList tail.
        var profile=actualAmmo(data,nativeType);
        if (profile==null || data.ammo.get(profile.ammoTpl()).inert()) return reject("prepare:ammo-unsupported");
        if (!data.weapons.containsKey(r.key) || r.parts.stream().anyMatch(part->!data.modifiers.containsKey(part.part.tpl()))) return reject("prepare:assembly-unsupported");
        float[] liveLook={drawn.viewYaw,drawn.viewPitch};
        double[] world=WeaponFrame.latchedWorld(drawn.carriedWorld,p,drawn.vpFrame,
                drawn.poseYaw,drawn.posePitch,drawn.poseEye,drawn.freeLook,liveLook);
        Calibration calibration;
        try { calibration=r.aiming ? calibration(r,world) : null; }
        catch (IllegalArgumentException ex) { last="baseline zero: "+ex.getMessage(); return reject("prepare:zero-unproven"); }
        if (r.aiming && (r.sight==null || r.zero>0 && calibration==null)) return reject("prepare:sight-unproven");
        ShotPoseSnapshot frozenPose=new ShotPoseSnapshot(drawn.pose.frameNumber(),drawn.pose.takenNanos(),drawn.pose.poseGeneration(),
                drawn.pose.stateIndex(),drawn.pose.weaponId(),drawn.pose.assemblyRevision(),drawn.pose.channel(),drawn.pose.mode(),
                drawn.pose.zeroMetres(),point(world,r.muzzle),direction(world,r.bore),direction(world,r.up),
                Arrays.stream(world).boxed().toList(),calibration!=null ? calibration.zero : drawn.pose.zero());
        List<ShotSnapshot.Part> parts=r.parts.stream().map(NativePart::part).toList();
        List<String> tpls=parts.stream().map(ShotSnapshot.Part::tpl).toList();
        var ammo=data.ammo.get(profile.ammoTpl());
        float factor=data.assemblySpeedFactor(r.key,tpls), dispersion=data.shotgunDispersion(r.key,tpls);
        // The counters move only for an admitted shot (bodyBeforeMuzzle may still hand it to PZ).
        long next=sequence+1;
        if (next==0) throw new IllegalStateException("Shot sequence exhausted");
        var id=new ShotSnapshot.Id(session,epoch,p.getOnlineID(),next);
        long seed=next ^ (epoch*0x9e3779b97f4a7c15L);
        Vec3 drawnLaunch=launchDirection(world,r,calibration);
        Vec3 launch=thirdPersonAim(r,world,drawnLaunch,profile.ammoTpl(),factor,true);
        List<Vec3> directions=pelletDirections(launch,ammo.projectileCount(),dispersion,seed);
        String hash=serverFlies()?mpHash():data.gunplaySha256+":"+data.inputsSha256+":"+r.geometryHash;
        var snapshot=new ShotSnapshot(id,hash,seed,1,simulationSeconds,
                new ShotSnapshot.Weapon(weapon.getID(),weapon.getFullType(),r.key,data.weapons.get(r.key).tpl(),r.revision,actionSerial+1),
                parts,profile.ammoTpl(),nativeType,profile.key(),ammo,factor,ammo.initialSpeed()*factor,dispersion,
                frozenPose,directions,freezeWeapon(weapon));
        var tracingContacts=liveRequested?(serverFlies()?mpContacts:contacts):shadowContacts;
        if (penetrationData==null || tracingContacts==null || !tracingContacts.ready()) return reject("prepare:penetration-unavailable");
        // Point blank (user's report of 21.10.2026, item 8): the flight starts at the drawn muzzle, so a body
        // between the eye and the muzzle (a zombie at grab range, its head against the barrel) was never tested
        // and the bullet went through it. Such a shot is PZ's own (native fallback), whose hit list takes it.
        if (bodyBeforeMuzzle(r,world,snapshot,tracingContacts)) return reject("prepare:body-before-muzzle");
        sequence=next;actionSerial++;
        // MP live: no local flight; the server flies this cartridge from the drawn launch direction.
        FlightBatch flight=liveRequested && serverFlies()?new FlightBatch(snapshot,launch)
                :new FlightBatch(snapshot,data,penetrationData,tracingContacts);
        flight.calibration=calibration;
        flight.tpAimDeg=launch==drawnLaunch?Double.NaN:Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,launch.dot(drawnLaunch)))));
        flight.viewYaw=liveLook[0];flight.viewPitch=liveLook[1];
        // The flight follows the ground and the models as this frame drew them (reliefWarp).
        flight.warp(reliefWarp(drawn.vpFrame,frozenPose.muzzlePz()));
        return new Prepared(snapshot,flight);
    }
    static Prepared prepareIsometric(IsoPlayer p,HandWeapon weapon,Request r,Capture drawn) throws Exception {
        if (!r.revision.equals(drawn.pose.assemblyRevision())) return reject("prepare:frame-request-changed");
        if (!nativeParts(r)) return reject("prepare:native-parts-changed");
        if (!qaRejectNext.isEmpty()) { String reason=qaRejectNext;qaRejectNext="";return reject("prepare:qa-"+reason); }
        var ammoType=weapon.getAmmoType();
        String nativeType=ammoType==null ? "" : ammoType.getItemKey();
        var profile=actualAmmo(data,nativeType);
        if (profile==null || data.ammo.get(profile.ammoTpl()).inert()) return reject("prepare:ammo-unsupported");
        if (!data.weapons.containsKey(r.key) || r.parts.stream().anyMatch(part->!data.modifiers.containsKey(part.part.tpl())))
            return reject("prepare:assembly-unsupported");
        // MP: only a server that reads an intent's view decides an isometric one by its policy (W10 srvlive).
        if (liveRequested && serverFlies() && (mpProtocol==null || !mpProtocol.capable(ShotProtocol.ISOMETRIC_CAPABILITY)))
            return reject("prepare:server-isometric-unsupported");
        // Client authority: the server's policy 4 (Live: Viewpoint and isometric) lets isometric rounds fly here.
        if (clientAuthority() && authPolicy!=4) return reject("prepare:server-isometric-off");
        double[] world=WeaponFrame.world(p) ? WeaponFrame.F.clone() : drawn.carriedWorld.clone();
        ShotPoseSnapshot.Pz muzzle=point(world,r.muzzle);
        List<ShotSnapshot.Part> parts=r.parts.stream().map(NativePart::part).toList();
        List<String> tpls=parts.stream().map(ShotSnapshot.Part::tpl).toList();
        var ammo=data.ammo.get(profile.ammoTpl());
        float factor=data.assemblySpeedFactor(r.key,tpls), dispersion=data.shotgunDispersion(r.key,tpls);
        WorldRay.IsoAim isoTarget=p.isAiming() ? WorldRay.isoAimForShot(p,muzzle.x(),muzzle.y(),muzzle.z()) : null;
        Vec3 isoBore, isoLaunch;
        if (isoTarget!=null) {
            Vec3 targetMetric=new Vec3((float)((isoTarget.x()-muzzle.x())*TarkovBallistics.METRES_PER_CELL),
                    (float)((isoTarget.z()-muzzle.z())*TarkovBallistics.METRES_PER_LEVEL),
                    (float)((isoTarget.y()-muzzle.y())*TarkovBallistics.METRES_PER_CELL));
            if (targetMetric.length()>0.05f) {
                isoBore=targetMetric.unit();
                Vec3 solved=isoBore;
                try { solved=solveZero(data,profile.ammoTpl(),factor,targetMetric,0.0); }
                catch (IllegalArgumentException ignored) { }
                double cos=Math.max(-1.0,Math.min(1.0,isoBore.dot(solved)));
                if (Math.toDegrees(Math.acos(cos))>2.85) {
                    double maxRad=Math.toRadians(2.85);
                    Vec3 perp=solved.minus(isoBore.times((float)cos));
                    if (perp.length()>1e-5f) solved=isoBore.times((float)Math.cos(maxRad)).plus(perp.unit().times((float)Math.sin(maxRad))).unit();
                    else solved=isoBore;
                }
                isoLaunch=solved;
            } else {
                isoBore=direction(world,r.bore);isoLaunch=isoBore;
            }
        } else {
            isoBore=direction(world,r.bore);isoLaunch=isoBore;
        }
        Vec3 projUp=new Vec3(0,1,0).minus(isoBore.times(isoBore.y()));
        if (projUp.length()<=1e-3f) projUp=new Vec3(1,0,0).minus(isoBore.times(isoBore.x()));
        Vec3 isoUp=projUp.unit();
        ShotPoseSnapshot frozenPose=new ShotPoseSnapshot(drawn.pose.frameNumber(),drawn.pose.takenNanos(),0L,
                drawn.pose.stateIndex(),drawn.pose.weaponId(),drawn.pose.assemblyRevision(),drawn.pose.channel(),
                drawn.pose.mode(),drawn.pose.zeroMetres(),muzzle,isoBore,isoUp,
                Arrays.stream(world).boxed().toList(),drawn.pose.zero());
        long next=sequence+1;
        if (next==0) throw new IllegalStateException("Shot sequence exhausted");
        var id=new ShotSnapshot.Id(session,epoch,p.getOnlineID(),next);
        long seed=next ^ (epoch*0x9e3779b97f4a7c15L);
        List<Vec3> directions=pelletDirections(isoLaunch,ammo.projectileCount(),dispersion,seed);
        String hash=serverFlies()?mpHash():data.gunplaySha256+":"+data.inputsSha256+":"+r.geometryHash;
        var snapshot=new ShotSnapshot(id,hash,seed,1,simulationSeconds,
                new ShotSnapshot.Weapon(weapon.getID(),weapon.getFullType(),r.key,data.weapons.get(r.key).tpl(),r.revision,actionSerial+1),
                parts,profile.ammoTpl(),nativeType,profile.key(),ammo,factor,ammo.initialSpeed()*factor,dispersion,
                frozenPose,directions,freezeWeapon(weapon));
        var tracingContacts=liveRequested?(serverFlies()?mpContacts:contacts):shadowContacts;
        if (penetrationData==null || tracingContacts==null || !tracingContacts.ready()) return reject("prepare:penetration-unavailable");
        Vec3 from=new Vec3((float)((p.getAimOriginPosX()-muzzle.x())*TarkovBallistics.METRES_PER_CELL),
                (float)((p.getAimOriginPosZ()-muzzle.z())*TarkovBallistics.METRES_PER_LEVEL),
                (float)((p.getAimOriginPosY()-muzzle.y())*TarkovBallistics.METRES_PER_CELL));
        if (from.length()>1e-3f) {
            var segment=new ShotSnapshot.Segment(snapshot.pellet(0),0,0f,0f,from,Vec3.ZERO);
            for (var contact:tracingContacts.trace(snapshot,segment))
                if (contact.kind()==ShotSnapshot.TargetKind.CHARACTER) return reject("prepare:body-before-muzzle");
        }
        sequence=next;actionSerial++;
        FlightBatch flight=liveRequested && serverFlies()?new FlightBatch(snapshot,isoLaunch)
                :new FlightBatch(snapshot,data,penetrationData,tracingContacts);
        flight.isometric=true;
        return new Prepared(snapshot,flight);
    }
    /** A character's collider on the stretch from the eye (the sight's camera; at the hip the weapon's rear)
     * to the muzzle, where the flight does not test. */
    static boolean bodyBeforeMuzzle(Request r,double[] world,ShotSnapshot shot,ShotSnapshot.Contacts contacts) throws Exception {
        var muzzle=point(world,r.muzzle);
        var start=r.sight!=null ? point(world,new Vec3((float)r.sight[3],(float)r.sight[7],(float)r.sight[11]))
                : point(world,r.rear!=null ? r.rear : Vec3.ZERO);
        Vec3 from=new Vec3((float)((start.x()-muzzle.x())*TarkovBallistics.METRES_PER_CELL),
                (float)((start.z()-muzzle.z())*TarkovBallistics.METRES_PER_LEVEL),
                (float)((start.y()-muzzle.y())*TarkovBallistics.METRES_PER_CELL));
        if (!(from.length()>1e-3f)) return false;
        var segment=new ShotSnapshot.Segment(shot.pellet(0),0,0f,0f,from,Vec3.ZERO);
        for (var contact:contacts.trace(shot,segment)) if (contact.kind()==ShotSnapshot.TargetKind.CHARACTER) return true;
        return false;
    }
    /** Independent SplitMix64 v1 replay. No claim of Unity PRNG equivalence. */
    static List<Vec3> pelletDirections(Vec3 bore,int count,float dispersion,long seed) {
        List<Vec3> result=new ArrayList<>();
        long value=seed;
        for (int i=0;i<count;i++) {
            if (count==1) { result.add(bore); continue; }
            value+=0x9e3779b97f4a7c15L; double z=2*uniform(value)-1;
            value+=0x9e3779b97f4a7c15L; double angle=2*Math.PI*uniform(value);
            double radius=Math.sqrt(Math.max(0,1-z*z));
            Vec3 unit=new Vec3((float)(radius*Math.cos(angle)),(float)z,(float)(radius*Math.sin(angle)));
            result.add(bore.times(100).plus(unit.times(dispersion)).unit());
        }
        return List.copyOf(result);
    }
    static double uniform(long value) {
        value=(value^(value>>>30))*0xbf58476d1ce4e5b9L;
        value=(value^(value>>>27))*0x94d049bb133111ebL;
        return ((value^(value>>>31))>>>11)*0x1.0p-53;
    }
    static boolean liveReady() {
        if (serverFlies()) return enabled && abiCapable() && data!=null && mpLive();
        try {
            // The LOCAL protocol of single player or of client authority (W11 hostauth): ready(false) as before.
            return enabled && abiCapable() && data!=null
                    && liveRequested && contacts!=null && contacts.ready() && damage!=null && damage.ready()
                    && ledger!=null && protocol!=null && protocol.ready(false);
        } catch (Throwable t) { fail(t); return false; }
    }
    /** FB4 item 10: our live owner decides the shots of our weapons now (the aim ring of Viewpoint's gunplay means nothing for them). */
    public static boolean ownsShots() {
        return liveReady() && (localNetwork() || multiplayer);
    }
    static String liveReason() {
        try {
            if (serverFlies()) return data==null ? "data-unavailable" : mpProtocol==null ? "multiplayer-baseline:"+mpReason
                    : !mpProtocol.ready(true) ? "multiplayer-baseline:handshake-lost" : mpContacts==null || !mpContacts.ready()
                    ? "multiplayer-baseline:contacts-not-ready" : "ready";
            return data==null ? "data-unavailable" : !localNetwork() ? "multiplayer-baseline"
                    : contacts==null || damage==null || ledger==null || protocol==null ? "adapters-missing"
                    : !contacts.ready() ? "contacts-not-ready" : !damage.ready() ? "damage-not-ready"
                    : !protocol.ready(false) ? "protocol-not-ready" : "ready";
        } catch (Throwable t) { fail(t);return "adapter-error"; }
    }
    static String admissionReason(IsoGameCharacter actor,HandWeapon weapon) { return admissionReason(actor,weapon,true); }
    /** shot=false leaves out what only decides whether this attack fires at all (a shove or grab, vanilla canShoot):
     * the forecast of nextShotReason, where an attack that does not fire is nobody's shot. */
    static String admissionReason(IsoGameCharacter actor,HandWeapon weapon,boolean shot) {
        if (!enabled) return "option-off";
        if (failed) return "owner-failed";
        boolean inVp=ViewpointBridge.perspective();
        boolean isoMode=!inVp && (isometricAllowed || request!=null && request.isometric);
        if (!(isoMode ? detectCore() : detect())) return "bridge-unavailable";
        if (!inVp && !isoMode) return "isometric-view";
        // W11 oldpoint: in OldPoint's rotating overhead camera the player aims with the cursor, not down a sight, and the
        // game maps that cursor to its own aim target; our bore-aligned Tarkov shot would leave along the weapon instead.
        // The round is the game's own there, as with a vanilla firearm (the marker shows by the same camera rule).
        if (OldPointCompat.overhead()) return "overhead-view";
        if (!(actor instanceof IsoPlayer p) || !p.isLocalPlayer() || p.getPlayerNum()!=0 || IsoPlayer.players[0]!=p) return "not-local-player-zero";
        if (weapon==null || p.getPrimaryHandItem()!=weapon) return "weapon-changed";
        if (!weapon.isAimedFirearm() || weapon.getAmmoPerShoot()!=1
                || weapon.getPhysicsObject()!=null && !weapon.getPhysicsObject().isEmpty()) return "weapon-unsupported";
        if (shot && (p.isDoShove() || p.isDoGrapple())) return "melee-action";
        String fire=shot ? fireReason(p,weapon) : null;
        if (fire!=null) return fire;
        if (liveRequested && !localNetwork() && !mpLive()) return liveReason();
        if (liveRequested && !liveReady()) return liveReason();
        if (engine.reserved()>=64) return "queue-full";
        return null;
    }
    /**
     * W10 item 15: vanilla ISReloadWeaponAction.canShoot (B42 shared/TimedActions/ISReloadWeaponAction.lua), the rule the
     * engine's attack hook fires by: never in Safe; with unlimited ammo (the debug cheat, an MP admin's) no cartridge and
     * no jam needed; else not jammed and a chambered round (without a chamber, a round in the weapon). Null when it fires,
     * else the reason. tools/check_shot_admission.py runs it against the game's own Lua on the whole matrix.
     */
    static String canShootReason(IsoGameCharacter actor,HandWeapon weapon) {
        if (weapon.isSelectFire() && "Safe".equals(weapon.getFireMode())) return "fire-mode-safe";
        if (actor.isUnlimitedAmmo()) return null;
        if (weapon.isJammed()) return "weapon-jammed";
        if (weapon.haveChamber() ? !weapon.isRoundChambered() : weapon.getCurrentAmmoCount()<=0) return "no-cartridge";
        return null;
    }
    /** At the collision the engine has fired already (its empty-weapon animation has no collision event): a refusal of
     * canShootReason is asked again of the live ISReloadWeaponAction.canShoot, which another mod may have replaced
     * (infinite ammo); its yes admits the shot. */
    static String fireReason(IsoGameCharacter actor,HandWeapon weapon) {
        String reason=canShootReason(actor,weapon);
        return reason==null || !liveCanShoot(actor,weapon) ? reason : null;
    }
    static boolean liveCanShoot(IsoGameCharacter actor,HandWeapon weapon) {
        try {
            if (LuaManager.env==null || LuaManager.thread==null || LuaManager.caller==null) return false;
            Object rule=LuaManager.env.rawget("ISReloadWeaponAction") instanceof KahluaTable t ? t.rawget("canShoot") : null;
            if (rule==null) return false;
            var result=LuaManager.caller.protectedCall(LuaManager.thread,rule,new Object[]{actor,weapon});
            return result!=null && result.isSuccess() && Boolean.TRUE.equals(result.getFirst());
        } catch (Throwable t) { return false; }
    }
    /** A shot this owner never takes by design (a launcher's native projectile, split-screen players), not a refusal. */
    static boolean byDesign(String reason) {
        return "weapon-unsupported".equals(reason) || "not-local-player-zero".equals(reason);
    }
    static boolean ourWeapon(IsoGameCharacter actor,HandWeapon weapon) {
        return actor instanceof IsoPlayer p && p.isLocalPlayer() && weapon!=null && weapon.isAimedFirearm()
                && weapon.getFullType()!=null && weapon.getFullType().startsWith("EscapeFromZomboidFirearms.");
    }
    /**
     * W10 item 16: what the next shot of the local player's weapon would get, by the admission of openScope (without
     * the shove and canShoot clauses: an attack that does not fire is nobody's shot) and its persistent prepare refusals
     * (no request for the weapon, policy). Null while it would be ours, or for a weapon or shot this owner never takes.
     * Main thread, once a tick (tick); FpAimRing keeps Viewpoint's ring and crosshair dot while it is not null.
     */
    static String nextShotReason() {
        try {
            IsoPlayer p=IsoPlayer.players[0];
            HandWeapon w=p!=null && p.getPrimaryHandItem() instanceof HandWeapon h ? h : null;
            if (!ourWeapon(p,w)) return null;
            String reason=admissionReason(p,w,false);
            if (reason==null && !liveReady()) reason=liveRequested ? liveReason() : "shadow";
            if (reason==null && (request==null || request.player!=p || request.weapon!=w)) reason="prepare:request-missing";
            if (reason==null && !policy(request)) reason=policyReason(request); // A constant: nothing allocated per tick.
            return byDesign(reason) ? null : reason;
        } catch (Throwable t) { return "forecast-error"; }
    }
    /** Whether the local player's next shot with our weapon would be ours (the last tick's nextShotReason). */
    public static boolean nextShotOwned() { return nextShot==null; }
    /** A shot this owner hands to the game because of the camera, not a refused shot of ours: counted, no console line
     * (W11 oldpoint: in OldPoint's overhead camera every round is the game's own, as with a vanilla firearm). */
    static boolean givenByView(String reason) { return "overhead-view".equals(reason); }
    /** Our weapon's shot the engine fired and this owner did not take: counted, and one console line per reason, then
     * at most one per GIVEN_LINE_NS (W10 item 16: a silent fallback to Viewpoint's spread is visible in console.txt). */
    static void given(IsoGameCharacter actor,HandWeapon weapon,String reason) {
        try {
            String why = reason==null ? null : reason.startsWith("collision:") ? reason.substring(10) : reason;
            if (reason==null || !ourWeapon(actor,weapon) || byDesign(why)) return;
            givenShots++;lastGiven=reason;
            if (givenByView(why)) return; // Counted, but an expected hand-over: no console line.
            long now=System.nanoTime();
            long[] row=givenLines.computeIfAbsent(reason,k->new long[]{now-GIVEN_LINE_NS,0});
            row[1]++;
            if (now-row[0]<GIVEN_LINE_NS) return;
            System.out.println("[TW-SHOT] baseline shot: "+reason+" (Viewpoint's or PZ's own shot decides it; "+row[1]
                    +" since this reason's last line; ownedShots="+engine.ownedCommits+" givenShots="+givenShots+")");
            row[0]=now;row[1]=0;
        } catch (Throwable ignored) { } // Diagnostics only: never in the way of the native shot.
    }
    static void finishLedger(ShotSnapshot.Id id,String reason) {
        if (ledger==null) return;
        try {
            ledger.finish(id,reason);
            if (local!=null) local.finish(id); // Tombstone first, then release B's terminal pellets.
        } catch (Throwable t) { fail(t); }
    }
    static void closeUnclaimedEarly() {
        Scope top;
        while ((top=engine.peek())!=null && !top.enteredCollision) exit(top,null);
    }
    static Scope openScope(IsoGameCharacter actor,HandWeapon weapon,boolean fromCollision) {
        try {
            initialize();
            String reason=admissionReason(actor,weapon);
            boolean eligible=reason==null;
            if (!eligible) { lastReject="collision:"+reason;rejected++; }
            boolean own=eligible && liveReady(); // prepare proves final-frame zero before this claim can latch.
            if (eligible && liveRequested && !own) { eligible=false;lastReject="collision:"+liveReason();rejected++; }
            Prepare prep=()->{
                Prepared prepared=prepare((IsoPlayer)actor,weapon);
                if (own && prepared!=null && local!=null && !local.dataHash.equals(prepared.shot().dataHash())) return reject("prepare:data-changed");
                if (own && prepared!=null && serverFlies()) return prepared;
                if (own && prepared!=null && !ledger.reserve(prepared.shot().id(),
                        local==null ? System.nanoTime()+60_000_000_000L : local.reservationExpiry())) return reject("prepare:ledger-reserve");
                return prepared;
            };
            Scope scope=fromCollision ? engine.begin(actor,weapon,eligible,own,prep)
                    : engine.beginEarly(actor,weapon,eligible,own,prep);
            if (eligible && scope.prepared==null) rejected++;
            if (!scope.owned) given(actor,weapon,scope.prepared!=null ? "shadow" : lastReject);
            return scope;
        } catch (Throwable t) {
            fail(t);
            given(actor,weapon,lastReject);
            return fromCollision ? engine.begin(actor,weapon,false,false,()->null)
                    : engine.beginEarly(actor,weapon,false,false,()->null);
        }
    }
    public static Scope enter(IsoGameCharacter actor,HandWeapon weapon) {
        Scope early=engine.adoptEarly(actor,weapon);
        if (early!=null) return early;
        closeUnclaimedEarly();
        return openScope(actor,weapon,true);
    }
    /** Gunplay.shotStarting OnEnter: matches the scope opened by Collision.enter when loaded after Viewpoint,
     * or opens the scope first (adopted by Collision.enter immediately next) when loaded before Viewpoint. */
    public static boolean shotStarting(IsoGameCharacter actor,HandWeapon weapon) {
        Scope matched=engine.matchCollision(actor,weapon);
        if (matched!=null) return matched.owned;
        Scope top=engine.peek();
        if (top!=null && top.enteredCollision && !top.enteredStarting) return false;
        closeUnclaimedEarly();
        return openScope(actor,weapon,false).owned;
    }
    public static void exit(Scope scope,Throwable thrown) {
        if (scope!=null) {
            if (scope.nativeDeferred && !scope.nativePublished)
                nativeWitness.cancel((ShotProtocol.NativeWitness.Token)scope.nativeToken);
            try { restoreNativeList(scope); } catch (Throwable failure) { fail(failure); }
        }
        engine.close(scope,thrown);
        if (scope!=null && scope.prepared!=null && (thrown!=null || scope.state==State.FAILED_COMMITTED)) {
            flights.remove(scope.shot().id());
            retire(scope,"native exception");
        } else if (scope!=null && scope.prepared!=null && !scope.fireSeen && scope.owned && ledger!=null) {
            finishLedger(scope.shot().id(),"native fire not reached");
        }
        if (scope!=null && scope.nativeDeferred && !scope.nativePublished && scope.fireSeen && scope.prepared!=null) engine.finish(scope);
        logFailure();
    }
    public static boolean suppress(IsoGameCharacter actor,HandWeapon weapon) { return engine.suppress(actor,weapon); }
    /** FB4 item 12: the native object of a contact's target identity (cosmetic lookups only), or null. */
    static Object targetObject(String identity) {
        try {
            ShotTargets live=targets, shadow=shadowTargets;
            Object o=live==null ? null : live.resolve(identity);
            return o!=null ? o : shadow==null ? null : shadow.resolve(identity);
        } catch (RuntimeException e) { return null; }
    }
    public static boolean hitList(IsoGameCharacter actor) {
        if (!engine.suppress(actor,null)) return false;
        // MP live: PZ selects its targets as usual; hitListMade keeps them for the server's fallback
        // and empties the list before any of them is hit here.
        Scope scope=engine.current(actor,null);
        if (serverFlies() && scope!=null && !scope.collected) { scope.collect=true;return false; }
        try { actor.clearHitInfo(); }
        catch (Throwable t) { fail(t); }
        return true;
    }
    /** OnExit of calculateHitInfoList: the claimed MP round keeps PZ's selection and hits nothing. */
    public static void hitListMade(IsoGameCharacter actor) {
        Scope scope=engine.current(actor,null);
        if (scope==null || !scope.owned || !scope.collect || scope.collected) return;
        scope.collect=false;scope.collected=true;
        try {
            ArrayList<Object> copies=new ArrayList<>();
            // PZArrayList.iterator() throws in B42.21 (mplive-04): indexed reads only.
            var list=actor.getHitInfoList();
            for (int i=0;i<list.size();i++) {
                var hit=list.get(i);
                var copy=new zombie.network.fields.hit.HitInfo().init(hit);copy.chance=hit.chance;copies.add(copy);
            }
            scope.vanilla=List.copyOf(copies);mpSelected=copies.size();
            actor.clearHitInfo();
        } catch (Throwable t) { fail(t); }
    }
    /** Around GameClient.sendPlayerHit of the claimed round's miss packet (target null): PZ's own
     * selection, each chance marked +1000 (PZ's chances never exceed 100) as a claimed round's. */
    public static void missPacket(IsoGameCharacter actor,Object target,boolean enter) {
        Scope scope=engine.current(actor,null);
        if (target!=null || scope==null || !scope.owned || !multiplayer) return;
        if (!enter) { restoreNativeList(scope);return; }
        // PlayerHitSquare carries the shooter's attack target square, which PZ takes from the
        // cursor pick (SwipeStatePlayer.doAttack); with none the server drops the packet as
        // inconsistent, and the claimed round's cartridge and native serial with it (mplive-04).
        // A non-null cursor can also be stale, distant or unloaded. Use only the actual loaded actor square.
        var square=actor.getCurrentSquare();
        // Collision, stairs and vehicle updates can leave current behind x/y/z until postupdate.
        // Match PlayerHitSquarePacket's XY metric and AntiCheatHitLongDistance's negotiated range, not tile equality.
        var connection=GameClient.connection;
        // Client UdpConnection.relevantRange stays zero: only GameServer.receivePlayerConnect fills it.
        // ConnectPacket sends this chunk width; the server derives relevantRange as width/2+2.
        // Match that native rule without trusting the uninitialized client-side connection field.
        int chunkWidth=zombie.iso.IsoChunkMap.chunkGridWidth;
        if (square==null || !Float.isFinite(actor.getX()) || !Float.isFinite(actor.getY()) || !Float.isFinite(actor.getZ())
                || connection==null || chunkWidth<=0 || chunkWidth>Byte.MAX_VALUE
                || zombie.iso.IsoUtils.DistanceTo(square.getX(),square.getY(),actor.getX(),actor.getY())
                    >(chunkWidth/2+2)*8f*1.2f) throw new IllegalStateException("Native miss square unavailable");
        actor.setAttackTargetSquare(square);
        // No-server-Java/clientAuthority needs only a native miss/event. Private fallback data has no receiver there.
        if (!scope.nativeDeferred) return;
        var rows=actor.getHitInfoList();
        // Player.write serializes at most 100 entries. The witness is already index zero; reserve the other 99.
        for (Object row:scope.vanilla) {
            if (rows.size()>=100) break;
            var hit=(zombie.network.fields.hit.HitInfo)row;
            var marked=new zombie.network.fields.hit.HitInfo().init(hit);marked.chance=1000+Math.max(0,Math.min(100,hit.chance));
            rows.add(marked);
        }
    }
    /** Native 42.22 packet construction; unclaimed callers keep the original list and rows byte-for-byte. */
    public static List<zombie.CombatManager.TargetHitList> nativePacket(Object packet,IsoPlayer actor,HandWeapon weapon,
            List<zombie.CombatManager.TargetHitList> original) {
        Scope scope=engine.current(actor,weapon);
        if (scope==null || !scope.owned || !scope.fireSeen || !multiplayer) return original;
        try {
            scope.nativePacket=packet;
            scope.nativeSaved=new ArrayList<>(actor.getHitInfoList());
            scope.nativeSavedSquare=actor.getAttackTargetSquare();
            actor.getHitInfoList().clear();
            if (scope.nativeDeferred) {
                if (scope.nativeToken==null) scope.nativeToken=nativeWitness.reserve(actor);
                var token=(ShotProtocol.NativeWitness.Token)scope.nativeToken;
                if (token==null) throw new IllegalStateException("Native witness reservation occupied");
                var marker=new zombie.network.fields.hit.HitInfo().init(actor,actor.getX(),actor.getY(),actor.getZ(),1f,0f);
                marker.chance=ShotProtocol.NativeWitness.chance(token.ordinal());
                actor.getHitInfoList().add(marker);
            }
            missPacket(actor,null,true);
            return List.of(new zombie.CombatManager.TargetHitList(null,List.of()));
        } catch (Throwable error) {
            try { restoreNativeList(scope); } catch (Throwable restore) { error.addSuppressed(restore); }
            nativeWitness.cancel((ShotProtocol.NativeWitness.Token)scope.nativeToken);
            fail(error);
            // Claimed collision was already suppressed. Never emit the original native hit list after a partial failure.
            throw new IllegalStateException("Claimed native packet preparation failed",error);
        }
    }
    private static void restoreNativeList(Scope scope) {
        if (scope.nativeSaved==null || !(scope.actor instanceof IsoGameCharacter actor)) return;
        try {
            var list=actor.getHitInfoList();list.clear();
            for (Object hit:scope.nativeSaved) list.add((zombie.network.fields.hit.HitInfo)hit);
        } finally {
            try { actor.setAttackTargetSquare((zombie.iso.IsoGridSquare)scope.nativeSavedSquare); }
            finally { scope.nativeSaved=null;scope.nativeSavedSquare=null; }
        }
    }
    public static void nativePacketSet(Object packet,IsoPlayer actor,HandWeapon weapon,Throwable error) {
        Scope scope=engine.current(actor,weapon);
        if (scope==null || scope.nativePacket!=packet) return;
        try { restoreNativeList(scope); }
        catch (Throwable failure) {
            nativeWitness.cancel((ShotProtocol.NativeWitness.Token)scope.nativeToken);fail(failure);
            throw new IllegalStateException("Claimed native packet restoration failed",failure);
        }
        if (error!=null) { nativeWitness.cancel((ShotProtocol.NativeWitness.Token)scope.nativeToken);fail(error); }
    }
    /** A real claimed cartridge has reached native serialization; no ID is manufactured by a marker alone. */
    public static void nativePacketWritten(Object packet,Throwable error) {
        IsoPlayer actor=IsoPlayer.players[0];
        if (!GameClient.client || actor==null) return;
        Scope scope=engine.current(actor,null);
        if (scope==null || !scope.nativeDeferred || scope.nativePacket!=packet || scope.nativePublished) return;
        var token=(ShotProtocol.NativeWitness.Token)scope.nativeToken;
        if (error!=null) { nativeWitness.cancel(token);return; }
        try {
            if (!scope.fireSeen || !scope.owned || !nativeWitness.commit(token)) throw new IllegalStateException("Unobserved native witness write");
            publish(scope,token.ordinal());
            scope.nativePublished=true;
        } catch (Throwable failure) { fail(failure); }
    }
    public static boolean objectHit(Object manager,IsoGameCharacter actor,HandWeapon weapon) {
        if (!engine.suppress(actor,weapon)) return false;
        try {
            ((Field)members.get("zombie.CombatManager.objHit")).set(manager,null);
            ((Field)members.get("zombie.CombatManager.treeHit")).set(manager,null);
            ((Field)members.get("zombie.CombatManager.hitOnlyTree")).setBoolean(manager,false);
        } catch (Throwable t) { fail(t); }
        return true;
    }
    public static boolean fire(HandWeapon weapon,IsoGameCharacter actor) {
        Scope current=engine.current(actor,weapon);
        if (current==null || !current.owned) mpNative(actor);
        if (current==null) lastReject="fire:collision-scope-missing";
        return engine.fire(actor,weapon,scope->{
            if (scope.owned && multiplayer && serverFlies()) {
                if (!netReady()) throw new IllegalStateException("Native witness scheme unavailable");
                scope.nativeDeferred=true;
            } else {
                // LOCAL/client-authority needs no network witness: this is the existing real cartridge-event count.
                publish(scope,(int)(engine.commits&255));
            }
        });
    }
    static void publish(Scope scope,int nativeByte) {
        if (failed) throw new IllegalStateException("Claimed shot terminated after owner failure");
        FlightBatch flight=(FlightBatch)scope.prepared.flight();
        flight.scope=scope;
        if (scope.owned && serverFlies()) { publishMultiplayer(scope,flight,nativeByte);return; }
        flights.put(scope.shot().id(),flight); // Reserved before the first old contact was suppressed.
        if (scope.owned) {
            try {
                if (!ledger.nativeFire(scope.shot().id(),nativeByte)) throw new IllegalStateException("Native shot correlation");
                protocol.fire(scope.shot(),nativeByte);
                if (clientAuthority()) ShotClientAuthority.fired(scope.shot(),flight.isometric);
                committedEvents++;
                if (committedListener!=null) committedListener.accept(scope.shot()); // B6 must replace, not add, the VP kick.
            } catch (Exception t) { throw new IllegalStateException(t); }
        }
        var shot=scope.shot();
        Vec3 borePz=pzDirection(shot.pose().boreForward());
        shotFields="session="+shot.id().session()+" epoch="+shot.id().epochText()+" shot="+shot.id().sequenceText()
                +" phase="+(scope.owned ? "live" : "shadow")+" authority="+(scope.owned ? "local" : "baseline")
                +" nativeShotByte="+nativeByte+" weapon="+Integer.toUnsignedString(shot.weapon().id())
                +" assembly="+shot.weapon().assemblyRevision()+" actualAmmo="+shot.actualAmmoTpl()
                +" defAmmo="+shot.pose().zero().defaultAmmoTpl()+" speed="+shot.initialSpeed()
                +" frame="+shot.pose().frameNumber()+" pose="+shot.pose().poseGeneration()+" channel="+shot.pose().channel()
                +" zero="+shot.pose().zeroMetres()+" muzzle="+shot.pose().muzzlePz().x()+","+shot.pose().muzzlePz().y()+","+shot.pose().muzzlePz().z()
                +" bore="+shot.pose().boreForward().x()+","+shot.pose().boreForward().y()+","+shot.pose().boreForward().z()
                +" boreAxes=X/up/Y borePz="+borePz.x()+","+borePz.y()+","+borePz.z()
                +" boreSource=drawn-weapon-frame"
                +" frameViewYaw="+(Float.isFinite(flight.viewYaw)?flight.viewYaw:"unavailable")
                +" frameViewPitch="+(Float.isFinite(flight.viewPitch)?flight.viewPitch:"unavailable")
                +" pellets="+shot.ammo().projectileCount()
                +" penetrationReplay="+ShotPenetration.RANDOM_VERSION+" cache="+(flight.chain==null?"legacy":flight.chain.cacheId);
        flight.fields=shotFields;
        contactFields="pellet=unavailable t0=unavailable t1=unavailable fraction=unavailable zone=unavailable damageKey=unavailable";
        last="shot="+shot.id().session()+"/"+shot.id().epochText()+"/"+shot.id().shooter()+"/"+shot.id().sequenceText()
                +" shadow="+!scope.owned+" nativeByte="+nativeByte+" ammo="+shot.actualAmmoTpl()+" enum="+shot.nativeAmmoType()
                +" frame="+shot.pose().frameNumber()+" ageNs="+(System.nanoTime()-shot.pose().takenNanos())
                +" pose="+shot.pose().poseGeneration()+" channel="+shot.pose().channel()+" zero="+shot.pose().zeroMetres()
                +" zeroInPose="+shot.pose().zero().appliedToPose()+" launchCalibrated="+(flight.calibration!=null)+" v0="+shot.initialSpeed()
                +" muzzle="+shot.pose().muzzlePz()+" bore="+shot.pose().boreForward()
                +" tpAimDeg="+(Double.isNaN(flight.tpAimDeg)?"off":String.format(java.util.Locale.ROOT,"%.3f",flight.tpAimDeg));
        if (flight.calibration!=null) last+=" rawZero="+flight.calibration.rawAtZero+" solvedZero="+flight.calibration.solvedAtZero
                +" zeroProbes="+flight.calibration.probes;
        traceLine("fire");
    }
    /** The claimed MP cartridge goes to the server as one intent; nothing flies or damages here. */
    static void publishMultiplayer(Scope scope,FlightBatch flight,int nativeByte) {
        var shot=scope.shot();
        ShotProtocol.Relief relief=ShotProtocol.Relief.NONE;long reliefMicros=0;
        try {
            if (mpProtocol==null) throw new IllegalStateException("MP handshake lost after claim");
            String qa=mpQaNext;mpQaNext="";
            if (qa.equals("offset")) {
                // A muzzle ten cells away from where the server stands this shooter.
                var p=shot.pose();
                var pose=new ShotPoseSnapshot(p.frameNumber(),p.takenNanos(),p.poseGeneration(),p.stateIndex(),p.weaponId(),
                        p.assemblyRevision(),p.channel(),p.mode(),p.zeroMetres(),
                        new ShotPoseSnapshot.Pz(p.muzzlePz().x()+10,p.muzzlePz().y(),p.muzzlePz().z()),p.boreForward(),p.boreUp(),
                        p.worldWeaponFrame(),p.zero());
                shot=new ShotSnapshot(shot.id(),shot.dataHash(),shot.seed(),shot.replayVersion(),shot.startTimeSeconds(),shot.weapon(),
                        shot.parts(),shot.actualAmmoTpl(),shot.nativeAmmoType(),shot.profile(),shot.ammo(),shot.speedFactor(),
                        shot.initialSpeed(),shot.dispersion(),pose,shot.pelletDirections(),shot.nativeWeapon());
            }
            int queued=mpOutbound.size();
            // QA "flat": the intent without the relief, as an older client sends it (the server flies it flat).
            long sampled=System.nanoTime();
            relief=qa.equals("flat")?ShotProtocol.Relief.NONE:relief(shot,flight);
            reliefMicros=(System.nanoTime()-sampled)/1000;
            mpProtocol.fire(shot,nativeByte,flight.launch,bodies(shot,(IsoPlayer)scope.actor,flight.launch),relief,
                    flight.isometric?ShotProtocol.View.ISOMETRIC:ShotProtocol.View.VIEWPOINT);
            if (qa.equals("drop")) while (mpOutbound.size()>queued) mpOutbound.removeLast();
            mpFired++;committedEvents++;
            if (committedListener!=null) committedListener.accept(shot);
        } catch (Exception t) { throw new IllegalStateException(t); }
        // Nothing flies here: the cartridge's reservation ends with its intent (the native route stays latched).
        engine.finish(scope);
        shotFields="session="+shot.id().session()+" epoch="+shot.id().epochText()+" shot="+shot.id().sequenceText()
                +" phase=live authority=server nativeShotByte="+nativeByte+" weapon="+Integer.toUnsignedString(shot.weapon().id())
                +" assembly="+shot.weapon().assemblyRevision()+" actualAmmo="+shot.actualAmmoTpl()+" pellets="+shot.ammo().projectileCount()
                +" muzzle="+shot.pose().muzzlePz().x()+","+shot.pose().muzzlePz().y()+","+shot.pose().muzzlePz().z()
                +" bore="+shot.pose().boreForward().x()+","+shot.pose().boreForward().y()+","+shot.pose().boreForward().z()
                +" launch="+flight.launch.x()+","+flight.launch.y()+","+flight.launch.z()
                +" view="+(flight.isometric?"isometric":"viewpoint")
                +" relief={"+ShotServer.relief(relief,flight.launch,0)+" micros="+reliefMicros+"}";
        last="shot="+shot.id().session()+"/"+shot.id().epochText()+"/"+shot.id().shooter()+"/"+shot.id().sequenceText()
                +" mp=server nativeByte="+nativeByte+" ammo="+shot.actualAmmoTpl()+" frame="+shot.pose().frameNumber();
        traceLine("fire");
    }
    /** The drawn relief this cartridge's flight bends with (FlightBatch.warp, reliefWarp of its frame) along its launch
     * line, for the server's flight of it (user's report of 27.10.2026, item 13): rows beside the line for pellets.
     * NONE without a relief (isometric, no Viewpoint frame). */
    static ShotProtocol.Relief relief(ShotSnapshot shot,FlightBatch flight) {
        var warp=flight.warp;
        if (warp==ShotPenetration.Warp.NONE || flight.launch==null) return ShotProtocol.Relief.NONE;
        float spread=shot.pelletDirections().size()>1 ? shot.dispersion()/100f : 0;
        return ShotProtocol.Relief.sample((x,z)->-warp.flat(new Vec3((float)x,0,(float)z),Vec3.ZERO).y(),flight.launch,spread,
                ShotProtocol.Relief.MAX_RANGE_MILLIMETRES/1000.0);
    }
    static final class FlightBatch {
        final ShotSnapshot shot;
        final List<Flight> pellets;
        final boolean[] ended;
        final int[] ordinals;
        ShotPenetration.Chain chain;
        int chainContacts,chainBodyHits,chainExits,chainRicochets,chainFragments,chainUnknown;
        Scope scope;
        Calibration calibration;
        float viewYaw=Float.NaN,viewPitch=Float.NaN;
        /** Degrees thirdPersonAim turned the drawn launch by; NaN when it left it. */
        double tpAimDeg=Double.NaN;
        String fields;
        float time;
        double pending;
        FlightBatch(ShotSnapshot shot,TarkovBallisticsData data) {
            this.shot=shot; pellets=shot.pelletDirections().stream()
                    .map(d->data.flight(shot.actualAmmoTpl(),shot.speedFactor(),Vec3.ZERO,d)).toList();
            ended=new boolean[pellets.size()]; ordinals=new int[pellets.size()];
        }
        FlightBatch(ShotSnapshot shot,TarkovBallisticsData data,TarkovPenetrationData penetration,ShotSnapshot.Contacts contacts) {
            this(shot,data);chain=new ShotPenetration.Chain(shot,data,penetration,contacts);
        }
        /** MP intent only: the calibrated launch direction, no local pellets or chain. */
        Vec3 launch;
        /** Prepared from the isometric aim (prepareIsometric), not a Viewpoint frame: the MP intent's view. */
        boolean isometric;
        FlightBatch(ShotSnapshot shot,Vec3 launch) {
            this.shot=shot;pellets=List.of();ended=new boolean[0];ordinals=new int[0];this.launch=launch;
        }
        /** The drawn world's relief under this cartridge (reliefWarp), for the chain and the pellets alike. */
        ShotPenetration.Warp warp=ShotPenetration.Warp.NONE;
        void warp(ShotPenetration.Warp next) {
            warp=next==null ? ShotPenetration.Warp.NONE : next;
            if (chain!=null) chain.warp=warp;
        }
    }
    private static double simulationSeconds;
    // The local C ledger shares B4's physical clock; a paused menu cannot expire
    // a live flight by wall time while its trajectory is deliberately paused.
    static long physicalNanos() { return (long)(simulationSeconds*1_000_000_000d); }
    public static void tick() {
        tickCalls++;
        closeUnclaimedEarly();
        try { initialize();tickFlights(); } catch (Throwable t) { fail(t);cancelFlights("tick failed"); }
        String next=nextShotReason();
        // Player's report of 02.11.2026 ("ADS does not hide the crosshair"): a shot Viewpoint's own spread decides is
        // named in console.txt once per reason and session (at the hip its dot and ring stay for it, W10 item 16).
        if (next!=null && !next.equals(nextShot) && keptLines.size()<KEPT_LINES && keptLines.add(next))
            System.out.println("[TW-SHOT] next shot not the mod's: "+next+" - Viewpoint's own shot decides it; its dot and"
                    +" ring stay at the hip (one line per reason)");
        nextShot=next;
    }
    private static final Set<String> keptLines=new HashSet<>();
    private static final int KEPT_LINES=32;
    static void tickFlights() {
        if (local!=null && !local.allowed()) {
            cancelFlights("local authority ended");clearLocal();return;
        }
        if (failed || !enabled || (capable || coreCapable) && !abiCapable()) {
            cancelFlights(failed?"shot owner failed; spent rounds lost without replay":"bridge disabled");return;
        }
        if (flights.isEmpty() && !(capable || coreCapable) || GameTime.isGamePaused()) return;
        float delta=GameTime.getInstance().getPhysicsSecondsSinceLastUpdate();
        if (!Float.isFinite(delta) || delta<0) { fail(new IllegalArgumentException("Physics clock")); return; }
        advance(delta);
        if (local!=null) local.protocol.expire();
    }
    static void cancelFlights(String reason) {
        for (FlightBatch batch:flights.values()) {
            retire(batch.scope,reason);
        }
        flights.clear();
        logFailure();
    }
    static void retire(Scope scope,String reason) {
        if (failed && scope.owned && scope.fireSeen && scope.state!=State.FINISHED) retiredShots++;
        engine.finish(scope);
        if (scope.owned) finishLedger(scope.shot().id(),reason);
    }
    /** The same bounded physical steps are used by runtime and the integration fixture. */
    static void advance(float delta) {
        if (!Float.isFinite(delta) || delta<0) throw new IllegalArgumentException("Physics clock");
        simulationSeconds+=delta;
        for (var iterator=flights.values().iterator();iterator.hasNext();) {
            if (failed) break; // A failure on one cartridge cannot mutate a later cartridge this tick.
            FlightBatch batch=iterator.next(); batch.pending+=delta;
            boolean done=false;
            try {
                for (int steps=0;batch.pending>=TarkovBallistics.STEP_SECONDS && steps<32;steps++) {
                    if (local!=null && !local.allowed()) { done=true;break; }
                    float end=Math.min(batch.shot.ammo().lifeSeconds(),batch.time+TarkovBallistics.STEP_SECONDS);
                    if (batch.chain!=null) {
                        var step=batch.chain.advance(end);segments+=step.segments();
                        if (batch.scope.owned) for (int start=0;start<step.impacts().size();start+=ShotProtocol.MAX_CONTACTS) {
                            long before=local==null?0:local.applied;
                            protocol.impact(batch.shot,step.impacts().subList(start,Math.min(start+ShotProtocol.MAX_CONTACTS,step.impacts().size())));
                            if (local!=null) applied+=local.applied-before;
                        }
                        for (var event:step.diagnostics()) {
                            ImpactMarks.contact(batch.shot,event,batch.scope.owned);
                            // Client authority: the same mark for the other clients, relayed by the server's Lua.
                            if (clientAuthority() && batch.scope.owned) ShotClientAuthority.mark(batch.shot,event,targetObject(event.contact().key().target()));
                            var hit=event.contact();shotFields=batch.fields;
                            batch.chainContacts++;
                            if (hit.kind()==ShotSnapshot.TargetKind.CHARACTER && hit.forwardHit()) batch.chainBodyHits++;
                            if (!hit.forwardHit()) batch.chainExits++;
                            if (event.outcome().equals("RICOCHET")) batch.chainRicochets++;
                            if (event.outcome().equals("FRAGMENT")) batch.chainFragments+=event.children();
                            if (event.outcome().equals("UNKNOWN_STOP")) batch.chainUnknown++;
                            contactFields="pellet="+hit.key().pellet().index()+" t0="+batch.time+" t1="+end+" fraction="+hit.fraction()
                                    +" zone="+hit.bodyPart()+" ragdoll="+hit.ragdollPart()+" target="+hit.key().target()
                                    +" stopM="+stopMetres(hit)+" stopPz="+stopSquare(batch.shot,hit)
                                    +" damageKey="+shotKey(hit.key())+" "+event.fields()
                                    +" chainContacts="+batch.chainContacts+" chainBodyHits="+batch.chainBodyHits+" chainExits="+batch.chainExits
                                    +" chainRicochets="+batch.chainRicochets+" chainFragments="+batch.chainFragments+" chainUnknown="+batch.chainUnknown;
                            traceLine(hit.forwardHit()?"impact":"exit");
                        }
                        batch.time=end;batch.pending-=TarkovBallistics.STEP_SECONDS;
                        if (step.done()) { done=true;break; }
                        continue;
                    }
                    List<ShotSnapshot.Contact> hits=new ArrayList<>();
                    for (int i=0;i<batch.pellets.size();i++) if (!batch.ended[i]) {
                        var flight=batch.pellets.get(i);
                        var segment=new ShotSnapshot.Segment(batch.shot.pellet(i),batch.ordinals[i]++,batch.time,end,
                                batch.warp.flat(flight.sample(batch.time).position(),Vec3.ZERO),
                                batch.warp.flat(flight.sample(end).position(),Vec3.ZERO));
                        segments++;
                        if (batch.scope.owned) {
                            var found=contacts.trace(batch.shot,segment);
                            if (!found.isEmpty()) { hits.add(found.get(0));batch.ended[i]=true; }
                        }
                    }
                    if (!hits.isEmpty()) {
                        long before=local==null ? 0 : local.applied;
                        protocol.impact(batch.shot,List.copyOf(hits));
                        if (local!=null) applied+=local.applied-before;
                        // C is the only caller of B's Damage adapter. Publish each
                        // pellet with its own cartridge even when flights overlap.
                        for (var hit:hits) {
                            shotFields=batch.fields;
                            contactFields="pellet="+hit.key().pellet().index()+" t0="+batch.time+" t1="+end+" fraction="+hit.fraction()
                                    +" zone="+hit.bodyPart()+" ragdoll="+hit.ragdollPart()+" target="+hit.key().target()
                                    +" damageKey="+shotKey(hit.key());
                            traceLine("impact");
                        }
                    }
                    batch.time=end; batch.pending-=TarkovBallistics.STEP_SECONDS;
                    done=end>=batch.shot.ammo().lifeSeconds();
                    boolean all=true;
                    for (boolean ended:batch.ended) all&=ended;
                    if (done || all) { done=true; break; }
                }
            } catch (Throwable t) { fail(t); done=true; }
            if (done || batch.scope.state==State.FAILED_COMMITTED) {
                iterator.remove();retire(batch.scope,failed?"shot owner failed; spent round lost without replay":"flight finished");
            }
        }
        if (failed) cancelFlights("shot owner failed; spent shot retired without replay");
    }
    /** C installs its authority adapters explicitly; this alone never enables live ownership. */
    public static void integrations(ShotSnapshot.Contacts c,ShotSnapshot.Damage d,ShotSnapshot.Ledger l,ShotSnapshot.Protocol p) {
        if (!flights.isEmpty()) throw new IllegalStateException("Adapters cannot change during flight");
        contacts=c; damage=d; ledger=l; protocol=p;
    }
    public static void options(boolean on,boolean live,boolean mp) {
        optionCalls++;
        closeUnclaimedEarly();
        if (enabled!=on || liveRequested!=live || multiplayer!=mp) {
            netAuthorityEnd("option-changed");
            cancelFlights("option changed");clearLocal();capture=null;request=null;isometricAllowed=false;
            requestStatus="missing";captureStatus="option-changed";
            mpProtocol=null;mpContacts=null;mpCell=null;mpPlayer=null;mpOutbound.clear();mpReason="option-changed";
        }
        enabled=on;liveRequested=live;multiplayer=mp;
    }
    /** No automatic kick: B6 must first own a guarded VP Impulses.shot replacement. */
    public static void onCommitted(Consumer<ShotSnapshot> listener) { committedListener=listener; }
    public static void reset(String worldSession,long connectionEpoch) {
        ShotSnapshot.text(worldSession);
        if (connectionEpoch==0) throw new IllegalArgumentException("Zero epoch");
        closeUnclaimedEarly();
        netAuthorityEnd("session-reset");
        cancelFlights("session reset");clearLocal();
        mpProtocol=null;mpContacts=null;mpCell=null;mpPlayer=null;mpOutbound.clear();mpChallenge=null;mpReason="session-reset";
        nativeWitness.reset();
        capture=null;request=null;isometricAllowed=false;zeroCache.clear();laserZeroCache.clear();session=worldSession;epoch=connectionEpoch;sequence=0;
        lastReject="none";requestStatus="missing";captureStatus="not-drawn";givenLines.clear();keptLines.clear();
        shotFields="shot=unavailable phase=baseline authority=baseline";
        contactFields="pellet=unavailable t0=unavailable t1=unavailable fraction=unavailable zone=unavailable damageKey=unavailable";
        tracing=false;
        simulationSeconds=0;
    }
    public static void resetLocal() { reset(UUID.randomUUID().toString(),epoch==Long.MAX_VALUE ? 1 : epoch+1); }
    public static String info() {
        StringBuilder hooks=new StringBuilder();
        for (Hook point:Hook.values()) hooks.append(" calls").append(point).append('=').append(calls.get(point.ordinal()));
        String modeStatus=!enabled ? "off" : !(capable || coreCapable) || failed ? status : !liveRequested
                ? "shadow; live adapters not used" : liveReady() ? "live; adapters ready" : "baseline; live requested; "+liveReason();
        return "committed="+engine.ownedCommits+" applied="+applied+" "+shotFields+" "+contactFields
                +" FX=unavailable recoil=unavailable mode="+(!enabled ? "off" : liveRequested ? "live" : "shadow")
                +" enabled="+enabled+" liveRequested="+liveRequested+" liveReady="+liveReady()+" frames="+frames
                +" rejected="+rejected+" pending="+engine.reserved()+" shadowShots="+engine.shadowCommits
                +" ownedShots="+engine.ownedCommits+" givenShots="+givenShots+" lastGiven="+lastGiven
                +" nextShot="+(nextShot==null ? "owned" : nextShot)+" committedEvents="+committedEvents
                +" segments="+segments+" scopes="+engine.depth()+" view="+ViewpointBridge.state()
                +" retiredShots="+retiredShots+" penetrationReady="+(penetrationData!=null)+" "+ShotBleedingPz.info()
                +" request="+requestStatus+" dataReady="+(data!=null)+" captureStatus="+captureStatus
                +" lastReject="+lastReject+" mp="+(mpProtocol!=null && mpLive()?"live":"off")+" mpReason="+mpReason
                +" mpFired="+mpFired+" mpAccepted="+mpAccepted+" mpRejected="+mpRejected+" mpLastReject="+mpLastReject
                +" mpConfirmed="+mpConfirmed+" mpBodies="+mpBodies+" mpBodiesFar="+mpBodiesFar+" mpBodiesUndrawn="+mpBodiesUndrawn+" mpBodyUndrawn={"+mpBodyUndrawn+"}"+" mpBodiesUnplaced="+mpBodiesUnplaced+" mpBodyUnplaced={"+mpBodyUnplaced+"}"+" mpHits="+mpHits+" mpMarks="+mpMarks+" mpSelected="+mpSelected+" mpReplay={"+ShotReplay.info()+"} "+ShotClientAuthority.info()
                +" "+ShotContactsPz.poseInfo()+" optionCalls="+optionCalls+" requestCalls="+requestCalls
                +" validRequests="+validRequests+" tickCalls="+tickCalls+" localInstalls="+localInstalls+hooks
                +" status={"+modeStatus+"} last={"+last+"}";
    }
    /** Where a contact met the flight, metres from the drawn muzzle along its own line. */
    static String stopMetres(ShotSnapshot.Contact hit) {
        return String.format(java.util.Locale.ROOT,"%.3f",hit.point().length());
    }
    /** The PZ square and level that contact point stands in (x:y:z), from the same muzzle. */
    static String stopSquare(ShotSnapshot shot,ShotSnapshot.Contact hit) {
        ShotPoseSnapshot.Pz muzzle=shot.pose().muzzlePz();
        int x=(int)Math.floor(muzzle.x()+hit.point().x()/TarkovBallistics.METRES_PER_CELL);
        int y=(int)Math.floor(muzzle.y()+hit.point().z()/TarkovBallistics.METRES_PER_CELL);
        int z=(int)Math.floor(muzzle.z()+hit.point().y()/TarkovBallistics.METRES_PER_LEVEL);
        return x+":"+y+":"+z;
    }
    static String shotKey(ShotSnapshot.ImpactKey key) {
        var id=key.pellet().shot();
        return id.session()+"/"+id.epochText()+"/"+id.shooter()+"/"+id.sequenceText()+"/"+key.pellet().index()+"/"+key.contactOrdinal()+"/"+key.target();
    }
    public static String stats() { return info(); }
    public static String trace(boolean on) { tracing=on;return info(); }
    static void traceLine(String event) {
        if (tracing || enabled && !liveRequested) System.out.println("[TW-SHOT] "+event+" "+info());
    }
}
