package pz.escapefromzomboidfirearms.zb;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Flight;
import pz.escapefromzomboidfirearms.zb.TarkovBallistics.Vec3;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.inventory.types.HandWeapon;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;

/**
 * Third-person Viewpoint floating aim dot and spread-ring centre linkage (W12 reticle).
 *
 * Viewpoint's own SceneDrawer.crosshair projects a synthetic point 1000 scene units along the
 * third-person camera's look ray (CameraView.steady: eye + dir * 1000) without muzzle origin,
 * weapon bore/sight zeroing, gravity/drag, FarRelief curvature, or world/cover/character
 * obstruction. While the local player aims an EFZ firearm in Viewpoint third person and
 * ShotOwner owns the shot, afterWorld traces the first-contact Tarkov ballistic + FarRelief
 * path from the drawn weapon frame using a private read-only ShotContactsPz probe (stopping at
 * the first solid/glass/thin-cover/vehicle/character contact, or reporting a non-contact
 * trajectory horizon at MAX_TRACE_METRES / MAX_SEGMENTS / unloaded chunk boundary), writes an
 * immediate snapshot-camera projection for pipelines that bypass WorldRenderer.drawWeather
 * (Iris shader packs and Graphics.mode 0), and afterWeather refines that projection on the
 * render thread with SceneDrawer.view (Look.readBeforeDrawing) right before SceneDrawer draws
 * View.crosshairX/Y and View.ringX/Y. Weapon spread, damage, velocities and penetration
 * authority remain completely untouched.
 *
 * 02.11.2026: the contact projection is diagnostic only. The first contact can jump from a
 * nearby zombie/edge to the distant background without any camera movement. Moving the dot
 * and ring to that contact looked like aim assist. Viewpoint now owns their position and
 * visibility throughout the frame; only the first-contact target colour is overridden.
 */
public final class TpAimDot {
    /** Required Viewpoint members for third-person dot placement. */
    static final String[][] MEMBERS = {
        {"viewpoint.core.View", "crosshairX", "F", "static"},
        {"viewpoint.core.View", "crosshairY", "F", "static"},
        {"viewpoint.core.View", "crosshairOnTarget", "Z", "static"},
        {"viewpoint.core.View", "crosshairShown", "Z", "static"},
    };

    /** Optional Viewpoint members: spread-ring centre and Panini screen mapping. */
    static final String[][] OPTIONAL = {
        {"viewpoint.core.View", "ringX", "F", "static"},
        {"viewpoint.core.View", "ringY", "F", "static"},
        {"viewpoint.render.SceneData", "panini", "F", ""},
        {"viewpoint.render.Panini", "of", "(Lorg/joml/Matrix4f;F)Lviewpoint/render/Panini;", "static"},
        {"viewpoint.render.Panini", "toScreen", "(FF[F)V", ""},
    };

    /**
     * Trajectory horizon bounds: PZ's client IsoChunkMap streams a square grid around the player
     * whose axis half-span is typically 60-96 tiles (~69-111 m, ~98-157 m diagonal). 160 m and
     * 128 swept pieces cover the entire loaded client chunk diagonal; when no collider is hit
     * before MAX_TRACE_METRES, MAX_SEGMENTS, or an UNLOADED square, Mark.contact is false and
     * Mark.hitKind names "horizon" or "unloaded" rather than an impact.
     */
    static final double MAX_TRACE_METRES = 160.0;
    static final int MAX_SEGMENTS = 128;

    private static final ShotSnapshot.Id PROBE_ID =
            new ShotSnapshot.Id(ShotContactsPz.READ_ONLY_SESSION, 1L, 0, 1L);
    private static final ShotSnapshot.PelletId PROBE_PELLET_0 =
            new ShotSnapshot.PelletId(PROBE_ID, 0);
    private static final ShotSnapshot.NativeWeapon PROBE_NATIVE_WEAPON =
            new ShotSnapshot.NativeWeapon(0, new byte[] {1});
    private static final List<Double> PROBE_WORLD_FRAME =
            List.of(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0);
    private static final Vec3 UNIT_FORWARD = new Vec3(0f, 0f, 1f);

    private static final class PreviewTargets extends ShotTargets {
        private final IdentityHashMap<Object, String> map = new IdentityHashMap<>();
        private long seq;

        @Override
        public String identity(Object object) {
            if (object == null) {
                throw new IllegalArgumentException("Null target");
            }
            String id = map.get(object);
            if (id != null) {
                return id;
            }
            id = "preview:" + (++seq);
            map.put(object, id);
            return id;
        }

        @Override
        public Object resolve(String identity) {
            return null;
        }

        @Override
        public void clear() {
            map.clear();
            seq = 0;
        }
    }

    private record CachedAssembly(
            TarkovBallisticsData data,
            int weaponId,
            String key,
            String revision,
            String nativeAmmo,
            TarkovBallisticsData.Profile profile,
            TarkovBallistics.Ammo ammo,
            float factor,
            float dispersion,
            ShotSnapshot.Weapon weapon,
            List<Vec3> unitPellets) { }

    record Mark(
            Object vpFrame,
            long frameNumber,
            long worldFrame,
            float sx,
            float sy,
            float sz,
            boolean contact,
            boolean onTarget,
            boolean origShown,
            float origX,
            float origY,
            boolean origOnTarget,
            String ammoTpl,
            float initialSpeed,
            ShotPoseSnapshot.Pz muzzle,
            ShotPoseSnapshot.Pz point,
            Vec3 launch,
            float distanceMetres,
            int segments,
            String hitKind,
            String hitMaterial) { }

    private static volatile boolean settled, capable, failed, contactsFailed, qaLegacy;
    private static volatile String status = "not-checked", reason = "idle", lastError = "none";

    private static Field fCrosshairX, fCrosshairY, fCrosshairOnTarget, fCrosshairShown;
    private static Field fRingX, fRingY, fScenePanini;
    private static MethodHandle mhPaniniOf, mhPaniniToScreen;

    // Main-thread read-only world probe & per-assembly memoization (isolated from ShotOwner).
    private static final PreviewTargets previewTargets = new PreviewTargets();
    private static final ArrayList<ShotPenetration.Piece> piecesScratch = new ArrayList<>(8);
    private static final float[] mainOrigin = new float[6];
    private static final Vec3[] mainLaunchHolder = new Vec3[1];
    private static final Matrix4f mainView = new Matrix4f();
    private static final Matrix4f mainVp = new Matrix4f();
    private static final Vector4f mainClip = new Vector4f();
    private static final float[] mainScreen = new float[2];
    private static CachedAssembly cachedAssembly;
    private static IsoCell probeCell;
    private static ShotContactsPz probeWorld;
    private static ShotContacts probeContacts;
    private static IsoGameCharacter currentShooter;

    // Hand-off from main thread (afterWorld) to render thread (afterWeather), keyed by Frame.scene.
    private static final Map<Object, Mark> byScene = new IdentityHashMap<>();
    private static volatile int sceneMarks;
    private static volatile Mark latestMark;

    // Render-thread scratch objects (used exclusively inside ViewpointPass.afterWeather).
    private static final Matrix4f renderVp = new Matrix4f();
    private static final Vector4f renderClip = new Vector4f();
    private static final float[] renderScreen = new float[2];

    // Telemetry and QA diagnostics.
    private static volatile long tracedFrames, mainProjectedFrames, renderedFrames, behindCameraFrames, disownedFrames;
    private static volatile long lastTraceNanos, maxTraceNanos, totalTraceNanos;
    private static volatile float lastOrigX = Float.NaN, lastOrigY = Float.NaN;
    private static volatile float lastOutX = Float.NaN, lastOutY = Float.NaN;
    private static volatile float lastHitX = Float.NaN, lastHitY = Float.NaN;
    private static volatile boolean lastOrigOnTarget, lastRenderShown, lastBehindCamera;

    private TpAimDot() { }

    static boolean detect() {
        if (settled) {
            return capable;
        }
        return detectOnce();
    }

    private static synchronized boolean detectOnce() {
        if (settled) {
            return capable;
        }
        try {
            ClassLoader cl = TpAimDot.class.getClassLoader();
            Map<String, Member> found = new IdentityHashMap<>();
            for (String[] row : MEMBERS) {
                Member m = ViewpointBridge.resolve(row, cl);
                if (m == null) {
                    status = "member-missing:" + row[0] + "." + row[1];
                    System.out.println("[TW-RETICLE] Viewpoint third-person dot disabled: " + status);
                    capable = false;
                    return false;
                }
                found.put((row[0] + "." + row[1]).intern(), m);
            }
            fCrosshairX = (Field) found.get("viewpoint.core.View.crosshairX");
            fCrosshairY = (Field) found.get("viewpoint.core.View.crosshairY");
            fCrosshairOnTarget = (Field) found.get("viewpoint.core.View.crosshairOnTarget");
            fCrosshairShown = (Field) found.get("viewpoint.core.View.crosshairShown");

            Member rx = ViewpointBridge.resolve(OPTIONAL[0], cl);
            Member ry = ViewpointBridge.resolve(OPTIONAL[1], cl);
            if (rx instanceof Field fx && ry instanceof Field fy) {
                fRingX = fx;
                fRingY = fy;
            }
            Member sp = ViewpointBridge.resolve(OPTIONAL[2], cl);
            Member pOf = ViewpointBridge.resolve(OPTIONAL[3], cl);
            Member pTo = ViewpointBridge.resolve(OPTIONAL[4], cl);
            if (sp instanceof Field fsp && pOf instanceof Method mOf && pTo instanceof Method mTo) {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                fScenePanini = fsp;
                mhPaniniOf = lookup.unreflect(mOf);
                mhPaniniToScreen = lookup.unreflect(mTo);
            }
            status = "ready";
            capable = true;
            return true;
        } catch (Throwable t) {
            status = "detect-error:" + t.getClass().getSimpleName();
            lastError = t.toString();
            System.out.println("[TW-RETICLE] Viewpoint third-person dot detect failed: " + t);
            capable = false;
            return false;
        } finally {
            settled = true;
        }
    }

    private static void failContacts(String detail) {
        if (!contactsFailed) {
            contactsFailed = true;
            lastError = "contacts:" + detail;
            System.out.println("[TW-RETICLE] Read-only contact probe disabled: " + detail);
        }
        clearScenes();
    }

    private static void failDot(String where, Throwable t) {
        if (!failed) {
            failed = true;
            status = "failed:" + where;
            lastError = where + ":" + t;
            System.out.println("[TW-RETICLE] Third-person dot disabled at " + where + ": " + t);
        }
        clearScenes();
    }

    private static void clearScenes() {
        latestMark = null;
        if (sceneMarks != 0) {
            synchronized (byScene) {
                byScene.clear();
                sceneMarks = 0;
            }
        }
    }

    private static void disown(String why) {
        reason = why;
        disownedFrames++;
        clearScenes();
    }

    private static boolean ensureContacts(IsoCell cell) {
        if (contactsFailed) {
            return false;
        }
        if (probeCell == cell && probeWorld != null && probeContacts != null) {
            return probeContacts.ready();
        }
        previewTargets.clear();
        probeCell = cell;
        probeWorld = new ShotContactsPz(
                () -> IsoWorld.instance == null ? null : IsoWorld.instance.currentCell,
                previewTargets,
                s -> s != null && ShotContactsPz.readOnly(s) ? currentShooter : null,
                TpAimDot::failContacts);
        probeContacts = new ShotContacts(probeWorld);
        return !contactsFailed && probeContacts.ready();
    }

    /**
     * Resolve the round item key that will actually fire on the next trigger pull.
     *
     * Gunworks (SWMG) stores loaded rounds bottom-to-top in modData.AmmoList (the chambered round
     * sits at the end of the table, matching AmmoKind.java and TW/AmmoKind.lua), and applies
     * AmmoProfileSetter lazily inside RAFattackHook when the next shot starts rather than at the
     * end of onShoot or ISRackFirearm. Reading the top valid round of AmmoList while loaded > 0
     * keeps the pre-shot 3P aim dot synchronized immediately after mixed-magazine shots and bolt
     * racks, falling back to w.getAmmoType() when no Gunworks AmmoList is present.
     */
    static String nextRoundAmmoKey(HandWeapon w, TarkovBallisticsData data) {
        int loaded = Math.max(0, w.getCurrentAmmoCount())
                + (w.haveChamber() && w.isRoundChambered() ? 1 : 0);
        if (loaded > 0) {
            KahluaTable md = w.getModData();
            if (md != null && md.rawget("AmmoList") instanceof KahluaTable list) {
                String top = null;
                for (int i = list.len(); i >= 1; i--) {
                    if (list.rawget(i) instanceof String s && !s.isEmpty()) {
                        top = s;
                        break;
                    }
                }
                if (top != null && ShotOwner.actualAmmo(data, top) != null) {
                    return top;
                }
            }
        }
        var ammoType = w.getAmmoType();
        return ammoType == null ? "" : ammoType.getItemKey();
    }

    private static CachedAssembly resolveAssembly(
            TarkovBallisticsData data, HandWeapon w, ShotOwner.Request r, String nativeAmmo) {
        int wid = w.getID();
        CachedAssembly c = cachedAssembly;
        if (c != null
                && c.data() == data
                && c.weaponId() == wid
                && c.key().equals(r.key)
                && c.revision().equals(r.revision)
                && c.nativeAmmo().equals(nativeAmmo)) {
            return c;
        }
        var profile = ShotOwner.actualAmmo(data, nativeAmmo);
        if (profile == null) {
            return null;
        }
        var ammo = data.ammo.get(profile.ammoTpl());
        if (ammo == null || ammo.inert()) {
            return null;
        }
        var weaponRow = data.weapons.get(r.key);
        if (weaponRow == null) {
            return null;
        }
        ArrayList<String> tpls = new ArrayList<>(r.parts.size());
        for (ShotOwner.NativePart np : r.parts) {
            String tpl = np.part().tpl();
            if (!data.modifiers.containsKey(tpl)) {
                return null;
            }
            tpls.add(tpl);
        }
        float factor = data.assemblySpeedFactor(r.key, tpls);
        float dispersion = data.shotgunDispersion(r.key, tpls);
        ShotSnapshot.Weapon weaponSnap = new ShotSnapshot.Weapon(
                wid, w.getFullType(), r.key, weaponRow.tpl(), r.revision, 1L);
        List<Vec3> unitPellets = List.copyOf(Collections.nCopies(ammo.projectileCount(), UNIT_FORWARD));
        c = new CachedAssembly(
                data, wid, r.key, r.revision, nativeAmmo, profile, ammo, factor, dispersion, weaponSnap, unitPellets);
        cachedAssembly = c;
        return c;
    }

    /** Main thread (exit of ShotOwner.afterWorld): compute and publish the 3P aim mark for this drawn frame. */
    static void afterWorld(ShotOwner.Request r, ShotOwner.Capture drawn, TarkovBallisticsData data) {
        if (qaLegacy) {
            disown("qa-legacy");
            return;
        }
        if (failed || contactsFailed) {
            disown(failed ? "dot-failed" : "contacts-failed");
            return;
        }
        if (!ViewpointBridge.ready() || ViewpointBridge.state() != 2 || OldPointCompat.overhead()) {
            disown("not-third-person");
            return;
        }
        if (!detect()) {
            disown("abi-unavailable");
            return;
        }
        try {
            if (r == null || drawn == null || drawn.vpFrame() == null || drawn.scene() == null) {
                disown("capture-missing");
                return;
            }
            IsoPlayer p = IsoPlayer.players[0];
            if (p == null || p != r.player || !p.isLocalPlayer() || p.getPlayerNum() != 0 || p.isDead()) {
                disown("player-ineligible");
                return;
            }
            HandWeapon w = r.weapon;
            if (w == null || p.getPrimaryHandItem() != w || !ShotOwner.ourWeapon(p, w)) {
                disown("not-our-weapon");
                return;
            }
            if (w.getAmmoPerShoot() != 1 || (w.getPhysicsObject() != null && !w.getPhysicsObject().isEmpty())) {
                disown("weapon-unsupported");
                return;
            }
            if (!p.isAiming() || !r.aiming || !HipFireInput.aimIntent(p)) {
                disown("not-aiming");
                return;
            }
            if (!ShotOwner.policy(r) || !ShotOwner.ownsShots() || data == null) {
                disown("shot-not-owned");
                return;
            }
            long now = System.nanoTime(), worldFrame = FpRecoil.worldFrame();
            if (!FpPose.freshFrame(r.stampFrame, worldFrame)
                    || !FpPose.freshFrame(drawn.worldFrame(), worldFrame)
                    || drawn.pose().takenNanos() > now) {
                disown("stale-frame");
                return;
            }
            if (!r.revision.equals(drawn.pose().assemblyRevision())
                    || !r.channel.equals(drawn.pose().channel())
                    || r.mode != drawn.pose().mode()
                    || r.zero != drawn.pose().zeroMetres()
                    || r.aiming != drawn.request().aiming) {
                disown("request-mismatch");
                return;
            }
            if (!ShotOwner.nativeParts(r) || !ShotOwner.currentChannel(r)) {
                disown("parts-or-channel-changed");
                return;
            }
            if (r.sttActive && !Boolean.TRUE.equals(w.getModData().rawget("ISeeThem_DisabledComponent"))) {
                disown("stt-exclusion-missing");
                return;
            }
            if (ViewpointBridge.frameNumber(drawn.vpFrame()) != drawn.pose().frameNumber()
                    || ViewpointBridge.sceneOf(drawn.vpFrame()) != drawn.scene()) {
                disown("frame-reused");
                return;
            }
            String nativeAmmo = nextRoundAmmoKey(w, data);
            var preProfile = ShotOwner.actualAmmo(data, nativeAmmo);
            if (preProfile == null) {
                disown("ammo-unsupported");
                return;
            }
            var preAmmo = data.ammo.get(preProfile.ammoTpl());
            if (preAmmo == null || preAmmo.inert()) {
                disown("ammo-inert");
                return;
            }
            CachedAssembly asm = resolveAssembly(data, w, r, nativeAmmo);
            if (asm == null) {
                disown("assembly-unsupported");
                return;
            }
            IsoCell cell = IsoWorld.instance == null ? null : IsoWorld.instance.currentCell;
            if (cell == null || !ensureContacts(cell)) {
                disown("contacts-unavailable");
                return;
            }

            long traceStart = System.nanoTime();
            double[] world = drawn.carriedWorld();
            ShotPoseSnapshot.Pz muzzle = ShotOwner.point(world, r.muzzle);
            ShotPenetration.Warp warp = ShotOwner.reliefWarp(drawn.vpFrame(), muzzle);

            mainLaunchHolder[0] = null;
            Vec3 zeroCheck;
            try {
                zeroCheck = ShotOwner.laserImpactMetric(
                        r, world, nativeAmmo, 0, Double.NaN, mainLaunchHolder, ShotPenetration.Warp.NONE);
            } catch (IllegalArgumentException ex) {
                disown("zero-unproven");
                return;
            }
            if (zeroCheck == null || mainLaunchHolder[0] == null || r.sight == null) {
                disown("sight-unproven");
                return;
            }
            Vec3 launch = mainLaunchHolder[0];
            Flight flight = data.flight(asm.ammo().tpl(), asm.factor(), Vec3.ZERO, launch);

            Vec3 bore = ShotOwner.direction(world, r.bore);
            Vec3 rawUp = ShotOwner.direction(world, r.up);
            Vec3 projUp = rawUp.minus(bore.times(rawUp.dot(bore)));
            if (projUp.length() <= 1e-3f) {
                projUp = new Vec3(0, 1, 0).minus(bore.times(bore.y()));
            }
            if (projUp.length() <= 1e-3f) {
                projUp = new Vec3(1, 0, 0).minus(bore.times(bore.x()));
            }
            Vec3 up = projUp.unit();

            ShotPoseSnapshot pose = new ShotPoseSnapshot(
                    drawn.pose().frameNumber(),
                    drawn.pose().takenNanos(),
                    drawn.pose().poseGeneration(),
                    drawn.pose().stateIndex(),
                    drawn.pose().weaponId(),
                    drawn.pose().assemblyRevision(),
                    drawn.pose().channel(),
                    drawn.pose().mode(),
                    drawn.pose().zeroMetres(),
                    muzzle,
                    bore,
                    up,
                    PROBE_WORLD_FRAME,
                    drawn.pose().zero());
            float initialSpeed = asm.ammo().initialSpeed() * asm.factor();
            ShotSnapshot probeShot = new ShotSnapshot(
                    PROBE_ID,
                    "tp-aim-dot",
                    1L,
                    ShotSnapshot.REPLAY_VERSION,
                    0.0,
                    asm.weapon(),
                    List.of(),
                    asm.profile().ammoTpl(),
                    nativeAmmo,
                    asm.profile().key(),
                    asm.ammo(),
                    asm.factor(),
                    initialSpeed,
                    asm.dispersion(),
                    pose,
                    asm.unitPellets(),
                    PROBE_NATIVE_WEAPON);

            Vec3 hitMetric = null;
            boolean contact = false;
            boolean onTarget = false;
            String hitKind = "horizon";
            String hitMaterial = "air";
            int segOrdinal = 0;

            currentShooter = p;
            try {
                // Point-blank pre-muzzle character check (matches ShotOwner.bodyBeforeMuzzle).
                ShotPoseSnapshot.Pz start = ShotOwner.point(
                        world, new Vec3((float) r.sight[3], (float) r.sight[7], (float) r.sight[11]));
                Vec3 preFrom = new Vec3(
                        (float) ((start.x() - muzzle.x()) * TarkovBallistics.METRES_PER_CELL),
                        (float) ((start.z() - muzzle.z()) * TarkovBallistics.METRES_PER_LEVEL),
                        (float) ((start.y() - muzzle.y()) * TarkovBallistics.METRES_PER_CELL));
                if (preFrom.length() > 1e-3f) {
                    ShotSnapshot.Segment preSeg = new ShotSnapshot.Segment(
                            PROBE_PELLET_0, segOrdinal++, 0f, 0f, preFrom, Vec3.ZERO);
                    for (ShotSnapshot.Contact c : probeContacts.trace(probeShot, preSeg)) {
                        if (c.kind() == ShotSnapshot.TargetKind.CHARACTER) {
                            hitMetric = c.point();
                            boolean corpse = ShotPenetration.corpse(c);
                            contact = true;
                            onTarget = !corpse;
                            hitKind = corpse ? "pre-muzzle:corpse" : "pre-muzzle:character";
                            hitMaterial = c.material();
                            break;
                        }
                    }
                }

                if (hitMetric == null) {
                    Function<Float, Vec3> drawnFlight = time -> flight.sample(time).position();
                    float life = asm.ammo().lifeSeconds(), t0 = 0f;
                    Vec3 a = warp.flat(drawnFlight.apply(0f), Vec3.ZERO);
                    Vec3 lastFlat = a;
                    double travelled = 0.0;
                    outer:
                    while (t0 < life && travelled < MAX_TRACE_METRES && segOrdinal < MAX_SEGMENTS) {
                        float t1 = Math.min(life, t0 + TarkovBallistics.STEP_SECONDS);
                        Vec3 z = warp.flat(drawnFlight.apply(t1), Vec3.ZERO);
                        piecesScratch.clear();
                        ShotPenetration.pieces(
                                warp, drawnFlight, Vec3.ZERO, ShotPenetration.WARP_TOLERANCE,
                                t0, a, t1, z, 0, piecesScratch);
                        for (int pi = 0; pi < piecesScratch.size(); pi++) {
                            if (segOrdinal >= MAX_SEGMENTS) {
                                break outer;
                            }
                            ShotPenetration.Piece piece = piecesScratch.get(pi);
                            Vec3 pFrom = piece.from(), pTo = piece.to();
                            double stepLen = pTo.minus(pFrom).length();
                            if (travelled + stepLen > MAX_TRACE_METRES && stepLen > 1e-6) {
                                float f = (float) Math.clamp((MAX_TRACE_METRES - travelled) / stepLen, 0.0, 1.0);
                                pTo = pFrom.plus(pTo.minus(pFrom).times(f));
                                stepLen = MAX_TRACE_METRES - travelled;
                            }
                            ShotSnapshot.Segment seg = new ShotSnapshot.Segment(
                                    PROBE_PELLET_0, segOrdinal++, piece.t0(), piece.t1(), pFrom, pTo);
                            List<ShotSnapshot.Contact> found = probeContacts.trace(probeShot, seg);
                            lastFlat = pTo;
                            travelled += stepLen;
                            if (!found.isEmpty()) {
                                ShotSnapshot.Contact c = found.get(0);
                                hitMetric = c.point();
                                if (c.kind() == ShotSnapshot.TargetKind.UNLOADED) {
                                    contact = false;
                                    onTarget = false;
                                    hitKind = "unloaded";
                                    hitMaterial = "unloaded";
                                } else {
                                    boolean corpse = ShotPenetration.corpse(c);
                                    contact = true;
                                    onTarget = c.kind() == ShotSnapshot.TargetKind.CHARACTER && !corpse;
                                    hitKind = corpse ? "corpse" : c.kind().name().toLowerCase(Locale.ROOT);
                                    hitMaterial = c.material();
                                }
                                break outer;
                            }
                            if (travelled >= MAX_TRACE_METRES) {
                                break outer;
                            }
                        }
                        t0 = t1;
                        a = z;
                    }
                    if (hitMetric == null) {
                        hitMetric = lastFlat;
                    }
                }
            } finally {
                currentShooter = null;
                previewTargets.clear();
            }

            if (contactsFailed) {
                disown("contacts-failed");
                return;
            }

            ShotPoseSnapshot.Pz point = muzzle.offset(hitMetric);
            Object relief = ViewpointBridge.origin(drawn.vpFrame(), mainOrigin);
            float sx = (float) (mainOrigin[0] - point.x());
            float sy = (float) ((point.z() - mainOrigin[2]) * ViewpointBridge.LEVEL)
                    + ViewpointBridge.lift(relief, point.x(), point.y());
            float sz = (float) (mainOrigin[1] - point.y());
            if (!Float.isFinite(sx) || !Float.isFinite(sy) || !Float.isFinite(sz)) {
                disown("scene-nonfinite");
                return;
            }

            boolean origShown = fCrosshairShown.getBoolean(null);
            float origX = fCrosshairX.getFloat(null);
            float origY = fCrosshairY.getFloat(null);
            boolean origOnTarget = fCrosshairOnTarget.getBoolean(null);
            lastOrigX = origX;
            lastOrigY = origY;
            lastOrigOnTarget = origOnTarget;

            Mark mark = new Mark(
                    drawn.vpFrame(),
                    drawn.pose().frameNumber(),
                    worldFrame,
                    sx,
                    sy,
                    sz,
                    contact,
                    onTarget,
                    origShown,
                    origX,
                    origY,
                    origOnTarget,
                    asm.profile().ammoTpl(),
                    initialSpeed,
                    muzzle,
                    point,
                    launch,
                    hitMetric.length(),
                    segOrdinal,
                    hitKind,
                    hitMaterial);
            synchronized (byScene) {
                if (byScene.size() >= 8) {
                    byScene.clear();
                }
                byScene.put(drawn.scene(), mark);
                sceneMarks = byScene.size();
            }
            latestMark = mark;
            reason = "owned";

            // Immediate main-thread projection using SceneDrawer.view so pipelines that skip
            // WorldRenderer.drawWeather (Iris shader packs, Graphics.mode 0) still draw the
            // ballistic contact diagnostics and target colour; afterWeather refines these when
            // WorldRenderer.drawWeather runs with Look.readBeforeDrawing.
            Matrix4f proj = ViewpointBridge.projection(drawn.vpFrame());
            if (proj != null) {
                Matrix4f fallbackView = snapshotView();
                if (fallbackView != null) {
                    projectToCrosshair(
                            drawn.scene(), mark, fallbackView, proj, mainVp, mainClip, mainScreen, false);
                }
            }

            long elapsed = System.nanoTime() - traceStart;
            lastTraceNanos = elapsed;
            totalTraceNanos += elapsed;
            if (elapsed > maxTraceNanos) {
                maxTraceNanos = elapsed;
            }
            tracedFrames++;
        } catch (Throwable t) {
            failDot("afterWorld", t);
        }
    }

    private static Matrix4f snapshotView() {
        Matrix4f v = ViewpointBridge.view();
        if (v == null) {
            return null;
        }
        mainView.set(v);
        if (!Float.isFinite(mainView.m00()) || !Float.isFinite(mainView.m33()) || Math.abs(mainView.m33()) < 1e-4f) {
            return null;
        }
        return mainView;
    }

    private static void projectToCrosshair(
            Object scene,
            Mark mark,
            Matrix4f view,
            Matrix4f proj,
            Matrix4f scratchVp,
            Vector4f scratchClip,
            float[] scratchScreen,
            boolean renderPass)
            throws Throwable {
        // Read the current native output, never restore origX/Y: SceneDrawer may already have
        // advanced to a newer camera. Neither contact depth nor a behind-camera muzzle contact
        // may move/hide Viewpoint's steady aiming reference or its spread ring.
        lastOutX = fCrosshairX.getFloat(null);
        lastOutY = fCrosshairY.getFloat(null);
        lastRenderShown = fCrosshairShown.getBoolean(null);
        if (!lastRenderShown) {
            lastBehindCamera = false;
            return;
        }
        Matrix4f vp = proj.mul(view, scratchVp);
        Vector4f clip = scratchClip.set(mark.sx(), mark.sy(), mark.sz(), 1f);
        vp.transform(clip);
        if (!Float.isFinite(clip.w) || clip.w <= 1e-4f) {
            fCrosshairOnTarget.setBoolean(null, false);
            lastHitX = Float.NaN;
            lastHitY = Float.NaN;
            lastBehindCamera = true;
            if (renderPass) {
                behindCameraFrames++;
                renderedFrames++;
            } else {
                mainProjectedFrames++;
            }
            return;
        }

        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;
        float outX = ndcX;
        float outY = ndcY;
        if (fScenePanini != null && mhPaniniOf != null && mhPaniniToScreen != null) {
            float paniniStrength = fScenePanini.getFloat(scene);
            Object panini = mhPaniniOf.invoke(proj, paniniStrength);
            mhPaniniToScreen.invoke(panini, ndcX, ndcY, scratchScreen);
            outX = scratchScreen[0];
            outY = scratchScreen[1];
        }
        if (!Float.isFinite(outX) || !Float.isFinite(outY)) {
            return;
        }
        outX = Math.max(-1f, Math.min(1f, outX));
        outY = Math.max(-1f, Math.min(1f, outY));

        fCrosshairOnTarget.setBoolean(null, mark.onTarget());
        lastHitX = outX;
        lastHitY = outY;
        lastBehindCamera = false;
        if (renderPass) {
            renderedFrames++;
        } else {
            mainProjectedFrames++;
        }
    }

    /**
     * Render thread (ViewpointPass.afterWeather, right before WorldRenderer.finish and
     * SceneDrawer.drawCrosshair): refine the frame's 3P trajectory point projection with the
     * live render camera (SceneDrawer.view with Look.readBeforeDrawing), projection, and Panini.
     */
    static void afterWeather() {
        if (sceneMarks == 0 || qaLegacy || failed || contactsFailed) {
            return;
        }
        if (!ViewpointBridge.ready() || ViewpointBridge.state() != 2 || OldPointCompat.overhead()) {
            clearScenes();
            return;
        }
        if (!detect()) {
            return;
        }
        try {
            Object scene = ViewpointBridge.currentScene();
            if (scene == null) {
                return;
            }
            Mark mark;
            synchronized (byScene) {
                mark = byScene.get(scene);
            }
            if (mark == null) {
                return;
            }
            if (!FpPose.freshFrame(mark.worldFrame(), FpRecoil.worldFrame())
                    || ViewpointBridge.frameNumber(mark.vpFrame()) != mark.frameNumber()) {
                synchronized (byScene) {
                    byScene.remove(scene, mark);
                    sceneMarks = byScene.size();
                }
                return;
            }

            Matrix4f view = ViewpointBridge.view();
            Matrix4f proj = ViewpointBridge.projection(mark.vpFrame());
            if (view == null || proj == null) {
                return;
            }
            projectToCrosshair(
                    scene, mark, view, proj, renderVp, renderClip, renderScreen, true);
        } catch (Throwable t) {
            failDot("afterWeather", t);
        }
    }

    public static void qaLegacy(boolean legacy) {
        qaLegacy = legacy;
        if (legacy) {
            disown("qa-legacy");
        }
    }

    public static String info() {
        Mark m = latestMark;
        boolean active = m != null && sceneMarks > 0 && !qaLegacy && !failed && !contactsFailed
                && ViewpointBridge.ready() && ViewpointBridge.state() == 2 && !OldPointCompat.overhead()
                && FpPose.freshFrame(m.worldFrame(), FpRecoil.worldFrame());
        double avgUs = tracedFrames > 0 ? (totalTraceNanos / 1000.0) / tracedFrames : 0.0;
        String base = String.format(
                Locale.ROOT,
                "status=%s active=%b reason=%s legacy=%b capable=%b ring=%b panini=%b"
                        + " traced=%d mainProj=%d rendered=%d behindCam=%d disowned=%d"
                        + " lastUs=%.1f avgUs=%.1f maxUs=%.1f"
                        + " orig=%.5f,%.5f origOnTarget=%b out=%.5f,%.5f shown=%b behind=%b"
                        + " position=native hitScreen=%.5f,%.5f",
                status,
                active,
                reason,
                qaLegacy,
                capable,
                fRingX != null && fRingY != null,
                mhPaniniToScreen != null,
                tracedFrames,
                mainProjectedFrames,
                renderedFrames,
                behindCameraFrames,
                disownedFrames,
                lastTraceNanos / 1000.0,
                avgUs,
                maxTraceNanos / 1000.0,
                lastOrigX,
                lastOrigY,
                lastOrigOnTarget,
                lastOutX,
                lastOutY,
                lastRenderShown,
                lastBehindCamera,
                lastHitX,
                lastHitY);
        if (m == null) {
            return base + " mark=none lastError={" + lastError + "}";
        }
        return base + String.format(
                Locale.ROOT,
                " frame=%d worldFrame=%d distM=%.3f segs=%d contact=%b kind=%s material=%s onTarget=%b"
                        + " ammo=%s speed=%.1f muzzle=%.4f,%.4f,%.4f point=%.4f,%.4f,%.4f"
                        + " launch=%.5f,%.5f,%.5f scene=%.4f,%.4f,%.4f lastError={%s}",
                m.frameNumber(),
                m.worldFrame(),
                m.distanceMetres(),
                m.segments(),
                m.contact(),
                m.hitKind(),
                m.hitMaterial(),
                m.onTarget(),
                m.ammoTpl(),
                m.initialSpeed(),
                m.muzzle().x(),
                m.muzzle().y(),
                m.muzzle().z(),
                m.point().x(),
                m.point().y(),
                m.point().z(),
                m.launch().x(),
                m.launch().y(),
                m.launch().z(),
                m.sx(),
                m.sy(),
                m.sz(),
                lastError);
    }
}
