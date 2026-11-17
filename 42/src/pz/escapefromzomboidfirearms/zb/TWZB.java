package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.inventory.types.HandWeapon;
import zombie.scripting.objects.ModelWeaponPart;
import zombie.vehicles.BaseVehicle;

/** Lua side of the Java part: TWZB.* (TW/OpticView, QA). */
@Exposer.LuaClass
public class TWZB {
    public static final String VERSION = "1.1.6";
    /** Composites seen (main thread, every frame). */
    public static volatile long compositeCount;

    public static String version() {
        return VERSION;
    }

    /** Per-record client sight data; no Viewpoint class loading or renderer calls. */
    public static KahluaTable sightData(String resource, String key) {
        return SightData.table(resource, key);
    }

    public static double composites() {
        return compositeCount;
    }

    /** B3-A shadow capture. Live hits require explicitly installed B/C adapters. */
    public static void shotSet(IsoPlayer player, HandWeapon weapon, KahluaTable params) {
        ShotOwner.set(player, weapon, params);
    }

    public static void shotTick() {
        try (PerfProbe.Scope perf = PerfProbe.cpu(PerfProbe.SHOT_FLIGHT_CONTACTS)) { ShotOwner.tick(); }
    }
    public static String shotInfo() { return ShotOwner.info(); }
    /** FB4 item 10 (QA): our live owner fires the local shots (Tarkov ballistics along the bore, no native hits). */
    public static boolean shotOwns() { return ShotOwner.ownsShots(); }
    public static String shotInfo(IsoPlayer player) { return ShotOwner.info(); }
    public static String shotStats() { return ShotOwner.stats(); }
    public static String shotTrace(boolean enabled) { return ShotOwner.trace(enabled); }
    public static void shotOptions(boolean enabled, boolean live, boolean multiplayer) { ShotOwner.options(enabled, live, multiplayer); }
    public static void shotFail(String message) { ShotOwner.fail(new IllegalStateException(message)); }
    public static void shotUnavailable(String reason) { ShotOwner.unavailable(reason); }
    public static void shotReset() { ShotOwner.resetLocal(); }
    public static KahluaTable shotZero(IsoPlayer player, HandWeapon weapon, KahluaTable params) {
        return ShotOwner.zero(player, weapon, params);
    }
    // W9-aim
    public static boolean shotLaserTarget(IsoPlayer player, HandWeapon weapon, double distanceMetres, double surfaceZ, KahluaTable out) {
        return ShotOwner.laserTarget(player, weapon, distanceMetres, surfaceZ, out);
    }
    /** TW/LaserRender's probe along the shot's flat flight where Viewpoint draws its far relief (nil: the straight ray). */
    public static KahluaTable shotLaserProbe(IsoPlayer player, HandWeapon weapon, double rangeTiles) {
        return ShotOwner.laserProbe(player, weapon, rangeTiles);
    }
    /** MP live owner transport (TW_ShotOwnerNet): Hello request, server Wire payloads, our
     * outbound intents and the server's native hit fields. Inert outside MP live. */
    public static String shotNetChallenge() { return ShotOwner.netChallenge(); }
    public static void shotNetBaseline(String reason) { ShotOwner.netBaseline(reason); }
    public static boolean shotNetMessage(String payload) { return ShotOwner.netMessage(payload); }
    public static String shotNetPoll() { return ShotOwner.netPoll(); }
    public static boolean shotNetHit(String payload) { return ShotOwner.netHit(payload); }
    public static boolean shotNetReady() { return ShotOwner.netReady(); }
    public static boolean shotNetMark(String payload) { return ShotOwner.netMark(payload); }
    /** QA: the next claimed MP round's intent is displaced ("offset") or lost ("drop"). */
    public static void qaShotMpNext(String mode) { ShotOwner.qaNext(mode); }
    /** QA (W6 item 5): the next own shot is handed back to PZ as if prepare had refused it. */
    public static void qaShotReject(String reason) { ShotOwner.qaRejectNext(reason); }
    /** QA (W6 item 5): "penalty= exempt= removed= kept=" of PZ's moodle hit chance for the held weapon. */
    public static String qaMoodlePenalty(IsoGameCharacter actor, double distance) {
        return MoodlePenalty.info(actor, (float) distance);
    }
    /** QA (W6 item 3, A/B in one run): the trace of 24.10.2026's report, before the fix. */
    public static void qaShotLegacy(boolean on) { ShotContactsPz.qaLegacy(on); }
    /** QA (W6 item 5, A/B in one run): the stress sway as a turn of the weapon and its aim (FB2 item 16). */
    public static void qaPanicLegacy(boolean on) { FpRecoil.qaLegacyPanic(on); }
    /** QA (W10 item 8): the Aiming XP events the owned shot raised, and a fresh counter window. */
    public static String xpStats() { return ShotXp.stats(); }
    public static void xpReset() { ShotXp.resetStats(); }
    /** QA (W10 item 8, before/after in one run): true reproduces the build without the fix (no event at all). */
    public static void qaXpOff(boolean off) { ShotXp.qaOff(off); }
    /** QA (W10 glasspass, before/after in one run): true restores the wave's rule, PZ's own shot stops at a vehicle. */
    public static void qaGlassPassLegacy(boolean on) { ShotVehicleGlassPass.qaLegacy(on); }
    /** QA (W10 glasspass): console lines of PZ's hit list, Bullet's targets, the glass decisions and the damage. */
    public static void qaGlassPassTrace(boolean on) { ShotVehicleGlassPass.qaTrace(on); }
    public static String glassPassStats() { return ShotVehicleGlassPass.stats(); }
    /** QA (W10 glasspass): "x,y,z,nx,ny,nz" of a window part's measured pane (vehicle-local cells), or nil. */    public static String qaGlassPane(BaseVehicle vehicle, String part) { return ShotVehicleGlassPass.qaPane(vehicle, part); }
    /** QA (W10 glasspass, MP): the rule's own pane break on this client (an MP client sends vanilla's damageWindow). */
    public static boolean qaGlassBreak(BaseVehicle vehicle, String part) {
        var p=vehicle==null?null:vehicle.getPartById(part);
        return p!=null && ShotVehicleGlass.intact(p) && (ShotVehicleGlass.breakPane(p) || zombie.network.GameClient.client);
    }
    /** QA (W11 loot, before and after in one run): true stops the ZB sweep of a bypassed container fill. */
    public static void qaLootPaths(boolean off) { PatchLootPaths.qaOff(off); }

    /** Optional local profiling. No GL calls from Lua; the render advice polls its own queries. */
    public static void perfEnable(boolean on) { PerfProbe.enable(on); }
    public static void perfPhase(String phase) { PerfProbe.phase(phase); }
    public static void perfReset() { PerfProbe.reset(); }
    public static String perfInfo() { return PerfProbe.info(); }
    public static void perfTick() {
        if (PerfProbe.due()) PerfProbe.glassStats(LensGlass.info());
        PerfProbe.tick();
    }

    /** Local viewer PIP options: scale of the shorter screen side; every = 1, 2 or 4 stationary frames. */
    public static void pipOptions(boolean on, double scale, int every) {
        ScopePip.options(on, scale, every);
    }

    /** Current source-bound sight geometry, copied before the drawn frame is captured. */
    public static void pipSet(IsoPlayer player, KahluaTable view) {
        ScopePip.set(player, view);
    }

    public static String pipInfo() {
        return ScopePip.info();
    }

    /**
     * W10 item 11: the camera-zoom picture instead of the scope's second render (option ScopePicture); field: variant
     * C, the sight's own field across the eyepiece (every reticle mark matches the world), else the zoom by M.
     */
    public static void zoomOptions(boolean on, boolean field) {
        ScopePipZoom.options(on, field);
    }

    /** The current magnified optic channel while aiming (as pipSet's geometry); nil clears. */
    public static void zoomSet(IsoPlayer player, KahluaTable view) {
        ScopePipZoom.set(player, view);
    }

    public static String zoomInfo() {
        return ScopePipZoom.info();
    }

    /** QA (A/B in one run): the reticle at the world's angular scale instead of the eyepiece's space. */
    public static void zoomTrueScale(boolean on) {
        ScopePipZoom.trueScale = on;
    }

    /** QA (A/B in one run, 02.11.2026): the camera zoom about the camera's axis, as before ScopePipZoom.axisShift. */
    public static void zoomLegacyAxis(boolean on) {
        ScopePipZoom.qaLegacyAxis = on;
    }

    /** QA: the camera zoom's mapping, degrees: the FOV for the aimed FOV, a magnification and the zoom's growth. */
    public static double zoomFov(double adsDeg, double magnification, double growth) {
        return ScopePipZoom.zoomed(adsDeg, magnification, growth);
    }

    /** QA: the layer on again after a failure, counters cleared. */
    public static void zoomReset() {
        ScopePipZoom.reset();
    }

    /** QA (debug only): a world point whose screen position the drawn eyepiece frames record; nil-like NaN clears. */
    public static boolean zoomWatch(double x, double y, double z) {
        if (!zombie.Lua.LuaManager.GlobalObject.getDebug()) return false;
        ScopePipZoom.watch = Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) ? new double[] {x, y, z} : null;
        return true;
    }

    /** QA: one raw scene colour/depth dump in this profile's Screenshots folder. */
    public static String pipDump(String name) {
        return ScopePipDump.request(name);
    }

    /** QA: render from the exact main eye/view at this vertical FOV; zero restores the source scope. */
    public static void pipDebugFov(double degrees) {
        ScopePip.debugFov(degrees);
    }

    /** QA only: opt into sparse asynchronous centre samples (off by default). */
    public static void pipProbeEnable(boolean on, int every) {
        ScopePip.Probe.options(on, every);
    }

    /** Cached QA result; no GL readback or render-thread wait from Lua. */
    public static String pipProbe() {
        return ScopePip.Probe.info();
    }

    /** Reticle/glass diagnostics and our unmagnified channel request, adjacent to the PIP API. */
    public static String reticleInfo() {
        return "pip=" + ScopePip.ready() + " " + ReticleRendererPip.info();
    }

    public static void glassSet(IsoPlayer player, KahluaTable view) {
        SightGlass.set(player, view);
    }

    public static String glassInfo() {
        return SightGlass.info();
    }

    /** FB8 item 5: the other collimators of the held assembly (TW_SightGlass G.extras): open glass and their marks. */
    public static void glassExtras(IsoPlayer player, KahluaTable list) {
        SightGlassExtras.set(player, list);
    }

    public static String glassExtrasInfo() {
        return SightGlassExtras.info();
    }

    /** Source glass omitted only in the local held Viewpoint mesh copy. */
    public static String lensGlassInfo() {
        return LensGlass.info();
    }

    public static void lensGlassEnable(boolean on) {
        LensGlass.enable(on);
    }

    public static void lensGlassSet(IsoPlayer player, Object weapon, KahluaTable selection) {
        LensGlassSelection.set(player, weapon, selection);
    }

    /** FB4 item 9: a sight mode's mesh replaced in the drawn hands without re-creating them (TW/Render.swapStates). */
    public static String swapPartModel(IsoGameCharacter c, String root, ModelWeaponPart from, ModelWeaponPart to) {
        return PartModelSwap.swap(c, root, from, to);
    }

    public static String preloadPartModel(ModelWeaponPart row) {
        return PartModelSwap.preload(row);
    }

    public static String partSwapStats() {
        return PartModelSwap.stats();
    }

    public static boolean pipReady() {
        return ScopePip.ready();
    }

    public static double pipTexture() {
        return ScopePip.texture();
    }

    /** The scope image of a local player: kind ("thermal"/"nv") and values, see OpticState.read. */
    public static void setOptic(int player, KahluaTable view) {
        if (player >= 0 && player < 4) {
            OpticState.views[player] = view == null ? null : OpticState.read(view);
        }
    }

    public static void clearOptic(int player) {
        if (player >= 0 && player < 4) {
            OpticState.views[player] = null;
            HeatRenderer.set(player, null);
        }
    }

    /** Warm things of a player's thermal image: {obj=, input=} or {x=,y=,z=,input=,radius=}. */
    public static void setHeat(int player, KahluaTable list) {
        HeatRenderer.set(player, list);
    }

    /** Counters for QA: "frames passes thermalFrames heatQuads errors models modelErrors". */
    public static String stats() {
        return OpticRenderer.frames + " " + OpticRenderer.passes + " " + OpticRenderer.thermalFrames + " "
                + HeatRenderer.quads + " " + OpticRenderer.errors + " " + ModelHeat.models + " " + ModelHeat.errors;
    }

    /** TW/LaserRay's world trace in Java (WorldRay): R.trace, R.cursorTarget's cast, R.visible, R.blocked. */
    public static void rayConfig(double vertical, double glass, double bottom, double top) { WorldRay.config(vertical, glass, bottom, top); }
    public static KahluaTable rayTrace(KahluaTable ray, Object owner) { return WorldRay.trace(ray, owner); }
    public static KahluaTable rayCursor(KahluaTable ray, Object owner, double viewer, double floor) { return WorldRay.cursor(ray, owner, viewer, floor); }
    /** Isometric aim target for TW/LaserRay: BallisticsController 3D target when present, nil when floor-aiming, else AimingReticle at getAimOriginPosZ(). */
    public static KahluaTable isoAimTarget(IsoPlayer p) {
        return WorldRay.isoAimTarget(p, false); // W9-aim
    }
    public static KahluaTable isoAimTarget(IsoPlayer p, boolean legacy) {
        return WorldRay.isoAimTarget(p, legacy); // W9-aim
    }
    public static void qaFloorAim(boolean on) { WorldRay.setQaFloorAim(on); }
    public static void qaZombiePosture(zombie.characters.IsoZombie zed, String posture, double fx, double fy) {
        if (zed == null) return;
        float dfx = (float) fx, dfy = (float) fy;
        if ("sitting_wall".equals(posture)) {
            zed.setCanWalk(false);
            var sq = zed.getCurrentSquare();
            if (sq != null && zombie.popman.ZombiePopulationManager.instance != null) {
                zombie.popman.ZombiePopulationManager.instance.sitAgainstWall(zed, sq);
                zed.setX(sq.getX() + 0.5f);
                zed.setY(sq.getY() + 0.5f);
                zed.setZ(sq.getZ());
                zed.setLastX(zed.getX());
                zed.setLastY(zed.getY());
                zed.setLastZ(zed.getZ());
            }
            zed.setDir(zombie.iso.IsoDirections.fromAngle(dfx, dfy));
            zed.setForwardDirection(dfx, dfy);
            zed.setSitAgainstWall(true);
            zed.setOnFloor(true);
            zed.setKnockedDown(false);
            zed.changeState(zombie.ai.states.ZombieSittingState.instance());
        } else if ("prone".equals(posture)) {
            zed.setCanWalk(false);
            zed.setSitAgainstWall(false);
            zed.setOnFloor(true);
            zed.setKnockedDown(true);
            zed.setFallOnFront(false);
            zed.setDir(zombie.iso.IsoDirections.fromAngle(dfx, dfy));
            zed.setForwardDirection(dfx, dfy);
            zed.changeState(zombie.ai.states.ZombieOnGroundState.instance());
        } else {
            zed.setCanWalk(true);
            zed.setSitAgainstWall(false);
            zed.setOnFloor(false);
            zed.setKnockedDown(false);
            zed.setDir(zombie.iso.IsoDirections.fromAngle(dfx, dfy));
            zed.setForwardDirection(dfx, dfy);
        }
        var ap = zed.getAnimationPlayer();
        if (ap != null) ap.setTargetAndCurrentDirection(dfx, dfy);
    }
    public static boolean rayVisible(double x, double y, double z, double viewer, Object owner, Object context) {
        return WorldRay.visible(x, y, z, viewer, owner, context);
    }
    public static Boolean rayBlocked(KahluaTable ray, Object owner, Object context, String skip) { return WorldRay.blocked(ray, owner, context, skip); }
    public static Object rayContext() { return WorldRay.context(); }
    public static String rayInfo() { return WorldRay.info(); }
    public static String rayScene(KahluaTable ray, Object owner) { return WorldRay.describeScene(ray, owner); }
    /** TW/LaserRender M.draw's line loop in one call (IsoBeams). */
    public static double laserLines(double n, KahluaTable points, KahluaTable result, double zoom, double left, double top, double alpha,
                                    double maxDistance, double fog, double r, double g, double b, double cr, double cg, double cb) {
        return IsoBeams.laser(n, points, result, zoom, left, top, alpha, maxDistance, fog, r, g, b, cr, cg, cb);
    }
    public static void laserCapture(boolean on) { IsoBeams.capture(on); }
    public static KahluaTable laserCaptured() { return IsoBeams.captured(); }
    public static String raySquare(double x, double y, double z) { return WorldRay.describeSquare(x, y, z); }
    public static String rayVisits(KahluaTable ray, Object owner) { return WorldRay.describeVisits(ray, owner); }
    public static String identity(Object o) { return o == null ? "-" : o.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(o)); }
    public static void rayReset() { WorldRay.reset(); }
    /** TW/Runtime's snapshot cache: the version of a weapon's installed parts (PartsVersion), 0 for anything else. */
    public static double partsVersion(Object weapon) { return weapon instanceof HandWeapon w ? PartsVersion.of(w) : 0; }
    /** QA (performance pass 23.10.2026): bytes the calling (main) thread allocated so far, and a clock in nanoseconds. */
    public static double allocatedBytes() { return PerfCounters.allocated(); }
    public static double nanoTime() { return System.nanoTime(); }
    public static String gcInfo() { return PerfCounters.gc(); }
    public static void perfLegacy(boolean on) { PerfLegacy.on = on; }
    public static boolean perfLegacyOn() { return PerfLegacy.on; }
    /** W13 QA A/B of the full scope boundary (ScopePipGL): true reads the range of empty indexed buffer slots too, as before. */
    public static void qaPipIndexedLegacy(boolean legacy) { ScopePipGL.emptyRangeReads = legacy; }
    /**
     * W13 QA: record true starts a new record of the full scope boundaries' indexed buffer slots from 0, false stops
     * recording; either returns the record as one snapshot
     * "boundaries= slots= empty= bound= rangeQueries= legacy= recording=". Nothing is counted outside a record.
     */
    public static String qaPipIndexedStats(boolean record) { return ScopePipGL.slotStats(record); }

    /**
     * Frame of the weapon model in a character's hands for the current animation
     * frame, in its look frame: 12 numbers as TW/LaserGeometry P.frame (origin,
     * model X, Y, Z axes), or nil without a ready hand model.
     */
    public static KahluaTable weaponFrame(IsoGameCharacter c) {
        if (!WeaponFrame.look(c)) {
            return null;
        }
        KahluaTable t = LuaManager.platform.newTable();
        for (int i = 0; i < 12; i++) {
            t.rawset(i + 1, (Object) Double.valueOf(WeaponFrame.F[i]));
        }
        return t;
    }

    /**
     * The weapon frame before the first-person pose layer's obstacle retreat (FpPose; the
     * frame TW/WeaponObstacle queries, so a retreated weapon does not end its own retreat),
     * as weaponFrame; weaponFrame itself when the layer holds no fresh pose.
     */
    public static KahluaTable weaponFrameNeutral(IsoGameCharacter c) {
        double[] f = FpPose.neutralLook(c);
        if (f == null) {
            return weaponFrame(c);
        }
        KahluaTable t = LuaManager.platform.newTable();
        for (int i = 0; i < 12; i++) {
            t.rawset(i + 1, (Object) Double.valueOf(f[i]));
        }
        return t;
    }

    /**
     * First-person pose layer (FpPose, TW_FirstPersonPose.lua): a local player's weapon
     * and its static targets {weapon=key, class=grip class, dR={3}, sight={12} (own Prop1
     * <- camera of the selected channel, eye axes) or nil, ergonomics=, weight=, rear={3},
     * retreat={pullUntil, back, up, pitch degrees, elbowOut, chest}}. Renew every tick; the
     * same table is not re-read.
     */
    public static void fpPose(IsoGameCharacter c, Object item, KahluaTable params) {
        if (c != null && item != null && params != null) {
            FpPose.set(c, item, params);
        }
    }

    /**
     * Every tick with fpPose: aiming, the carry stance ("low", "high", "patrol" or ""), the obstacle
     * ratio (0..1), a timed action running, the first-person poses allowed (Viewpoint support on).
     */
    public static void fpPoseState(IsoGameCharacter c, boolean aiming, String carry, double obstacle, boolean acting, boolean allowed) {
        if (c != null) {
            FpPose.state(c, aiming, carry, obstacle, acting, allowed);
        }
    }

    public static void fpPoseOff(IsoGameCharacter c) {
        if (c != null) {
            FpPose.clear(c);
        }
    }

    /** Hip fire (TW_HipFire.lua, VP-W12-H3): Tarkov's trigger per pose family (TW/Data/HipFire), handed over once. */
    public static void fpPoseHipData(KahluaTable data) {
        FpPose.hipData(data);
    }

    /**
     * Hip fire: a local player's trigger held (renew every tick) or released. The answer of the pose
     * layer's last update: "ready" (the weapon is at its hip pose: start the attack now), "raising"
     * (leaving a carry stance on Tarkov's time) or "off:<reason>".
     */
    public static String fpPoseTrigger(IsoGameCharacter c, boolean held) {
        return FpPose.trigger(c, held);
    }

    /** QA: the layer on or off (off: the poses as without it, Viewpoint's arm pitch as before). */
    public static void fpPoseEnable(boolean on) {
        FpPose.enabled = on;
    }

    /**
     * W8 animopt (the user's item 9): the two client options "Third person: vanilla animations" and
     * "First person: vanilla animations" (TW_AnimOptOut.lua). Every pose/IK layer asks AnimOptOut.blocks()
     * at its entry and does nothing in the view whose option is on.
     */
    public static void animOptOut(boolean thirdPerson, boolean firstPerson) {
        AnimOptOut.set(thirdPerson, firstPerson);
    }

    /** W8 animopt QA: "third=... first=... yields=... view=...". */
    public static String animOptOutInfo() {
        return AnimOptOut.info();
    }

    /**
     * W9-inertia (Hardship Inertia): the character's animation belongs to that mod now (its floor postures).
     * TW_CompatHardshipInertia.lua keeps this current for every player; it yields exactly like the
     * "vanilla animations" options, but for that one character.
     */
    public static boolean animOptOutYield(IsoGameCharacter c, boolean on) {
        return AnimOptOut.yieldCharacter(c, on);
    }

    /** QA: Tarkov's aim time multiplied by this (> 0; 1 = the B1 formula). */
    public static void fpPoseAdsScale(double scale) {
        if (scale > 0) {
            FpPose.adsTimeScale = scale;
        }
    }

    /** QA: "calls= applied= firstPerson= retreats= eyePairs= ... data=" of the layer. */
    public static String fpPoseStats() {
        return FpPose.stats();
    }

    /** QA: what the layer did for a character (mode, family, action clip and time, eye, camera-relative Prop1), or nil. */
    public static String fpPoseOf(IsoGameCharacter c) {
        return FpPose.describe(c);
    }

    /** QA (wave 1488): the pixels one drawn model adds to a frame - screenshots with it (a1, a2) and without it (b). */
    public static String qaImageDiff(String a1, String a2, String b, double threshold, String crop) {
        return QaImageDiff.diff(a1, a2, b, (int) threshold, crop);
    }

    /** QA (wave 1488): the same inside a screen box "x0,y0,x1,y1" (qaChamberBox); the whole frame without one. */
    public static String qaImageDiffBox(String a1, String a2, String b, double threshold, String crop, String box) {
        String[] v = box == null ? new String[0] : box.split(",");
        if (v.length != 4) return QaImageDiff.diff(a1, a2, b, (int) threshold, crop);
        return QaImageDiff.diff(a1, a2, b, (int) threshold, crop, Integer.parseInt(v[0].trim()), Integer.parseInt(v[1].trim()),
                Integer.parseInt(v[2].trim()), Integer.parseInt(v[3].trim()));
    }

    /** QA (wave 1488): the screen box of the chambered round now, "x0,y0,x1,y1" in pixels grown by margin, or "none". */
    public static String qaChamberBox(IsoGameCharacter c, double margin) {
        return QaImageDiff.chamberBox(c, margin);
    }

    /** QA (FB1): record the arm bones the pose layer writes; returns the last record. */
    public static String fpPoseBones(boolean on) {
        FpPose.bonesProbe = on;
        return FpPose.bones;
    }

    /** QA only: actual captured iron/optic mesh points; null releases the probe. */
    public static void ironProbe(IsoPlayer player, KahluaTable value) {
        IronSightProbe.set(player, value);
    }

    public static String ironProbe() {
        return IronSightProbe.info();
    }

    /** Main ADS FOV from our selected channel's OpticSight identity, independent of its magnification. */
    public static void adsCameraState(IsoGameCharacter c, Object item, boolean optical, boolean on, boolean paused) {
        FpAdsCamera.state(c, item, optical, on, paused);
    }

    public static void adsCameraOff(IsoGameCharacter c) {
        FpAdsCamera.off(c);
    }

    /** QA: override the ADS camera layer; the untouched Viewpoint FOV resumes on the next call. */
    public static void adsCameraEnable(boolean on) {
        FpAdsCamera.enabled = on;
    }

    public static String adsCameraStats() {
        return FpAdsCamera.stats();
    }

    /** QA: base/current/target vertical FOV in degrees, ADS progress, effective weight and raise time. */
    public static String adsCameraOf(IsoGameCharacter c) {
        return FpAdsCamera.describe(c);
    }

    /** Tarkov sensitivity (W10 item 9): the multiplier of Viewpoint's first-person mouse delta for a player now. */
    public static void sensState(IsoGameCharacter c, Object item, double factor) {
        FpSensitivity.state(c, item, factor);
    }

    public static void sensOff(IsoGameCharacter c) {
        FpSensitivity.off(c);
    }

    /** QA: the layer's counters and state, the patched setting's own answer, a player's row and an A/B switch. */
    public static String sensStats() {
        return FpSensitivity.stats();
    }

    public static String sensOf(IsoGameCharacter c) {
        return FpSensitivity.describe(c);
    }

    public static String sensProbe() {
        return FpSensitivity.probe();
    }

    /** QA: Viewpoint's look now ("yaw= pitch= captured="), for the injected mouse-delta measurements. */
    public static String sensLook() {
        return FpSensitivity.look();
    }

    public static void sensEnable(boolean on) {
        FpSensitivity.enabled = on;
    }

    public static void sensReset() {
        FpSensitivity.reset();
    }

    /**
     * QA only: bring the game's own window to the foreground through GLFW's own API, from the game's own
     * thread (raising it from outside the process crashed the game's message pump twice on 27.10.2026). The
     * injected mouse delta needs the window in front; a refusal is reported and the QA case retries.
     */
    public static boolean sensFocus() {
        try {
            long window = org.lwjglx.opengl.Display.getWindow();
            if (window == 0L) {
                return false;
            }
            org.lwjgl.glfw.GLFW.glfwFocusWindow(window);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Mod options (FB2 items 3, 9): share 0..1 of Tarkov's ADS narrowing (irons and optics alike); hide Viewpoint's crosshair while aiming. */
    public static void adsCameraOptions(double narrowing, boolean hideCrosshair) {
        adsCameraShares(narrowing, narrowing, hideCrosshair);
    }

    /** Mod options (FB3 item 4): see-through model on/off - weapon, its sight and the hands - its opacity 0.1..0.9 at the full effect, blur radius (pixels at 1080 lines). */
    public static void seeThroughOptions(boolean on, double opacity, double blur) {
        SeeThrough.options(on, opacity, blur);
    }

    /** FB3 item 4: the sight of the moment - the front sight part's mesh, post apex and width (its local units), the active optic's or collimator's mesh ("" none). */
    public static void seeThroughSight(String front, double x, double y, double z, double width, String optic) {
        SeeThrough.sight(front, x, y, z, width, optic);
    }

    /** QA: "status= enabled= opacity= blur= frames= faded= kept= restored= blurred= pixels= weight= share= near= radius= sharp= ...". */
    public static String seeThroughStats() {
        return SeeThrough.stats();
    }

    /** QA: zero the see-through counters. */
    public static void seeThroughReset() {
        SeeThrough.reset();
    }

    /** Mod options (FB3 item 3): shares 0..1 of Tarkov's ADS narrowing for irons/collimators and for optics; hide the crosshair while aiming. */
    public static void adsCameraShares(double iron, double optic, boolean hideCrosshair) {
        if (Double.isFinite(iron)) {
            FpAdsCamera.narrowing = Math.max(0, Math.min(1, iron));
        }
        if (Double.isFinite(optic)) {
            FpAdsCamera.narrowingOptic = Math.max(0, Math.min(1, optic));
        }
        FpAdsCamera.hideCrosshair = hideCrosshair;
    }

    /** FB2 items 8, 13: draw-time latch of the own weapon to Viewpoint's camera, depth clamp of the near plane (QA switches). */
    public static void viewLatchEnable(boolean latch, boolean clamp) {
        FpViewLatch.enabled = latch;
        FpViewLatch.clamp = clamp;
    }

    /** QA (FB8 item 1, A/B): false lets the main view's ADS depth clamp into the scope picture's pass, as before. */
    public static void pipPictureNear(boolean on) {
        FpViewLatch.pictureNear = on;
    }

    public static String viewLatchStats() {
        return FpViewLatch.stats();
    }

    /**
     * W9-adsnear (QA, A/B in one run): the iron/built-in sight's near-band tie uses GL_LEQUAL (the enclosing part drawn
     * last wins); false keeps Viewpoint's GL_LESS, as before.
     */
    public static void viewLatchNearestWins(boolean on) {
        FpViewLatch.nearestWins = on;
    }

    /** W9-adsnear: the depth function the clamp draws with for an iron/built-in sight (GL_LEQUAL) and for an optic
     * (GL_LESS, Viewpoint's own), asked by offline gates. */
    public static int viewLatchDepthFunc(boolean ironSight) {
        return FpViewLatch.clampDepthFunc(ironSight);
    }

    public static void viewLatchReset() {
        FpViewLatch.reset();
    }

    /** W9-adsnear2 QA: override SELF opaque draw order (0=natural, 1=submission asc, 2=submission desc, 3=reverse natural). */
    public static void qaNearOrderMode(int mode) {
        FpViewLatch.qaOrderMode = mode;
    }

    /** W9-adsnear2 QA: arm a one-shot dump of the next main-view gbuffer pass's SELF draw order and active GL_DEPTH_FUNC. */
    public static void qaArmNearDump(String tag) {
        FpViewLatch.qaLastDump = "";
        FpViewLatch.qaDumpTag = tag;
    }

    /** W9-adsnear2 QA: read the last captured near-band gbuffer draw-order dump. */
    public static String qaNearLastDump() {
        return FpViewLatch.qaLastDump;
    }

    /**
     * Tarkov recoil, sway, breath and arm stamina in Viewpoint's first person (FpRecoil,
     * TW_FirstPersonRecoil.lua), every tick for a local player holding an own weapon:
     * {hold= the hold-breath key held, rounds= in the magazine, chambered=, ammo= the actual
     * cartridge's template or "", auto= automatic or burst mode, enabled= the option, paused=}.
     */
    public static void recoilState(IsoGameCharacter c, KahluaTable t) {
        FpRecoil.state(c, t);
    }

    /** The player is gone (death, exit): its transient arms/oxygen state is dropped. */
    public static void recoilOff(IsoGameCharacter c) {
        FpRecoil.clear(c);
    }

    /** QA: the whole integration on/off (off: the poses, aim time and Viewpoint's camera kick as before). */
    public static void recoilEnable(boolean on) {
        FpRecoil.enabled = on;
    }

    /** QA: Tarkov's camera into Viewpoint's frame on/off (off: Viewpoint's camera kick as before). */
    public static void recoilCamera(boolean on) {
        FpRecoil.camera = on;
    }

    /** QA: a support for a player: "vertical", "horizontal", "bipod" (its installed bipod) or "" (none, the default). */
    public static void recoilSupport(IsoGameCharacter c, String kind) {
        FpRecoil.support(c, kind);
    }

    /** QA: one recoil impulse through the shot path (no round, no muzzle effects, no call of Viewpoint). */
    public static boolean recoilQaShot(IsoGameCharacter c) {
        return FpRecoil.qaShot(c);
    }

    /**
     * The weapon offset of the last first-person frame: 16 numbers, own Prop1 <- the moved Prop1
     * (rows; the drawn weapon frame already has it), then Viewpoint's camera additive (yaw right,
     * pitch up, roll; radians); nil without a fresh one. For the shot owner's direction.
     */
    public static KahluaTable recoilOffset(IsoGameCharacter c) {
        double[] o = FpRecoil.offset(c);
        if (o == null) {
            return null;
        }
        KahluaTable t = LuaManager.platform.newTable();
        for (int i = 0; i < o.length; i++) {
            t.rawset(i + 1, (Object) Double.valueOf(o[i]));
        }
        return t;
    }

    /** QA: "enabled= ... frames= shots= refused= lastRefusal= refusedBy=off:0,...,pose:0,timeline:0 kicksReplaced= cameraWrites= errors= data=". */
    public static String recoilStats() {
        return FpRecoil.stats();
    }

    /** QA: a player's recoil and handling state (index, stable, channels, camera, follow, arms, oxygen, breath), or nil. */
    public static String recoilOf(IsoGameCharacter c) {
        return FpRecoil.describe(c);
    }

    /** QA: Viewpoint's own third-person toggle (persisted setting); false without the bridge or the member. */
    public static boolean vpFlipThirdPerson() {
        return ViewpointBridge.flipThirdPerson();
    }

    /** QA: Viewpoint's view turned level toward the world direction (dx, dy); false without the bridge. */
    public static boolean vpLookToward(double dx, double dy) {
        return ViewpointBridge.lookToward(dx, dy);
    }

    /**
     * QA: height (z, levels) of the native PZ muzzle, or null. BallisticsController is not
     * exposed to Lua, so the penetration scene reads it here to align its vehicle fixture.
     */
    public static Double qaNativeMuzzleZ(IsoGameCharacter character) {
        try {
            var controller = character == null ? null : character.getBallisticsController();
            var muzzle = controller == null ? null : controller.getMuzzlePosition();
            return muzzle == null || !Float.isFinite(muzzle.z) ? null : (double) muzzle.z;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /**
     * QA: width of a vehicle script wheel, or null. VehicleScript$Wheel keeps it only as a public
     * field, which Kahlua cannot read, so the penetration scene's tyre case reads it here.
     */
    public static Double qaWheelWidth(BaseVehicle vehicle, int index) {
        try {
            var script = vehicle == null ? null : vehicle.getScript();
            var wheel = script == null ? null : script.getWheel(index);
            return wheel == null || !Float.isFinite(wheel.width) ? null : (double) wheel.width;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** QA: radius of a vehicle script wheel, or null (a field Kahlua cannot read, as above). */
    public static Double qaWheelRadius(BaseVehicle vehicle, int index) {
        try {
            var script = vehicle == null ? null : vehicle.getScript();
            var wheel = script == null ? null : script.getWheel(index);
            return wheel == null || !Float.isFinite(wheel.radius) ? null : (double) wheel.radius;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** QA: guarded Viewpoint look pitch, radians; null without a readable member. */
    public static Double vpLookPitch() {
        return ViewpointBridge.lookPitch();
    }

    /** QA: set a finite look pitch in [-pi/2, pi/2]; false only refuses this request. */
    public static boolean vpSetLookPitch(double pitch) {
        return ViewpointBridge.setLookPitch(pitch);
    }

    /** QA (FB2 item 13): the look yaw in radians (null without the bridge), and a finite one set (a mouse turn stand-in). */
    public static Double vpLookYaw() {
        return ViewpointBridge.lookYaw();
    }

    public static boolean vpSetLookYaw(double yaw) {
        return ViewpointBridge.setLookYaw(yaw);
    }

    /**
     * QA (bug wave 324, the user's item 2): Viewpoint's free look and the pose layer's answer for a character - the anchor,
     * the camera, the body, the weapon frame's look, the drawn bore (FpFreeLook.describe); degrees.
     */
    public static String vpFreeLookOf(IsoGameCharacter c) {
        return FpFreeLook.describe(c);
    }

    /** QA (debug only): Viewpoint reads this key (its code: keyboard, 10000 + mouse button) as held or not for 250 ms; renew every tick. */
    public static boolean vpFreeLookQaHold(double code, boolean held) {
        return FpFreeLook.qaHold((int) code, held);
    }

    /** The same for ms (at most 5000: around a QA screenshot, whose stall outlasts the 250 ms). */
    public static boolean vpFreeLookQaHoldFor(double code, boolean held, double ms) {
        return FpFreeLook.qaHold((int) code, held, (long) (Math.max(0, ms) * 1e6));
    }

    public static void vpFreeLookQaReset() {
        FpFreeLook.qaReset();
    }

    /** QA (A/B in one run): false - the weapon's frame follows the view during a free look, as before 24.10.2026. */
    public static void vpFreeLookEnable(boolean on) {
        FpFreeLook.enabled = on;
    }

    public static String vpFreeLookStats() {
        return FpFreeLook.stats();
    }

    public static void vpFreeLookStatsReset() {
        FpFreeLook.reset();
    }

    /** QA: a player's arms and oxygen full again (a new session). */
    public static void handlingReset(IsoGameCharacter c) {
        FpRecoil.reset(c);
    }

    /**
     * The game torch of a player's hand-held weapon shines along (dx, dy);
     * dist and strength at most these (0 keeps the item's). Renew every frame.
     */
    public static void torch(IsoPlayer p, Object item, double dx, double dy, double dist, double strength) {
        if (p != null && item != null) {
            TorchAim.set(p, item, (float) dx, (float) dy, (float) dist, (float) strength);
        }
    }

    public static void torchOff(IsoPlayer p) {
        if (p != null) {
            TorchAim.clear(p);
        }
        TorchAim.prune();
    }

    /** Torches turned along a bore so far (QA). */
    public static double torches() {
        return TorchAim.applied;
    }

    /** Heat sources of a player's thermal view at the last refresh, by kind (QA). */
    public static String heatSources(int player) {
        return HeatSources.counts(player);
    }

    /**
     * QA: largest camera difference since the last reset, "zoomMain= offXMain=
     * offYMain= zoomRender= offXRender= offYRender= frames=" (main: against
     * IsoCamera.frameState; render: against the render state's PlayerCamera).
     */
    public static String cameraProbe(boolean reset) {
        float[] p = OpticRenderer.probe;
        String s = String.format(java.util.Locale.ROOT, "zoomMain=%.5f offXMain=%.3f offYMain=%.3f zoomRender=%.5f offXRender=%.3f offYRender=%.3f frames=%d staleWorld=%d staleRunMax=%d",
                p[0], p[1], p[2], p[3], p[4], p[5], OpticRenderer.probeFrames, OpticRenderer.staleWorld, OpticRenderer.staleRunMax);
        if (reset) {
            java.util.Arrays.fill(p, 0f);
            OpticRenderer.probeFrames = 0;
            OpticRenderer.staleWorld = 0;
            OpticRenderer.staleRunMax = 0;
        }
        return s;
    }

    /** QA: zoom state (Core, target, display, the world frame's size) and the ids the last dump read. */
    public static String zoomState() {
        zombie.core.textures.MultiTextureFBO2 b = zombie.core.Core.getInstance().getOffscreenBuffer() == null ? null : zombie.core.Core.getInstance().offscreenBuffer;
        return "core=" + zombie.core.Core.getInstance().getZoom(0) + (b == null ? "" : " raw=" + b.getZoom(0) + " target=" + b.getTargetZoom(0) + " display=" + b.getDisplayZoom(0))
                + " offscreen=" + zombie.iso.IsoCamera.getOffscreenWidth(0) + "x" + zombie.iso.IsoCamera.getOffscreenHeight(0)
                + " | dump " + OpticRenderer.Drawer.captureInfo;
    }

    /** QA: capture the next composited frame (with the scope image) to Screenshots/TW_Capture_<name>.png. */
    public static void qaCapture(String name) {
        OpticRenderer.captureDump = false;
        OpticRenderer.captureName = name;
    }

    /** QA: as qaCapture, with the world frame and the heat buffer (_world, _heat) and the world frame's checksum. */
    public static void qaDump(String name) {
        OpticRenderer.captureDump = true;
        OpticRenderer.captureName = name;
    }

    /** QA: CRC32 of the world frame of the last qaDump (hex), "" before one. */
    public static String qaWorldSum() {
        return OpticRenderer.Drawer.worldSum;
    }

    /** QA: a vehicle's warmth and heat field (VehicleHeat.describe). */
    public static String vehicleHeat(BaseVehicle v) {
        return v == null ? null : VehicleHeat.describe(v);
    }

    /** QA: "vehicles=<models drawn> errors=<n> corpses=<atlas images drawn>". */
    public static String heatModels() {
        return "vehicles=" + VehicleHeat.drawn + " errors=" + VehicleHeat.errors + " corpses=" + HeatRenderer.corpses + " fires=" + HeatRenderer.fires
                + " corpsesSeen=" + HeatRenderer.corpsesSeen + " corpsesAtlas=" + HeatRenderer.corpsesAtlas + " corpsesNotReady=" + HeatRenderer.corpsesNotReady;
    }

    /**
     * Runtime grip transfer (GripIK): the weapon in this character's hands and
     * both arms move from the reference grip class onto the weapon's own
     * geometry. params: dR(3), A(12), hold(3), hasButt, butt(3), refButt(3),
     * pocket(3), hasElbow, elbow(3), hasHigh, highA(12), highElbow(3), highBore(2)
     * (TW_GripIK.lua).
     * Renew every tick; the same table is not re-read.
     */
    public static void gripIK(IsoGameCharacter c, Object item, KahluaTable params) {
        if (c != null && item != null && params != null) {
            GripIK.set(c, item, params);
        }
    }

    private static int[] savedMouse;

    /** Isolated debug QA: renew the local player's native attack button for at most 250 ms. */
    public static boolean hipFireQaHold(IsoGameCharacter c, boolean held) {
        return HipFireInput.qaHold(c, held);
    }

    /** Release the QA input override immediately; the original button is read again. */
    public static void hipFireQaReset() {
        HipFireInput.qaReset();
    }

    /** Renew our eligible hip context; release clears it without changing native player state. */
    public static boolean hipFireState(IsoGameCharacter c, boolean held) {
        return HipFireInput.state(c, held);
    }

    /** Native aiming intent for our ADS consumers; a hip attack's hostile animation alone is not ADS. */
    public static boolean aimIntent(IsoGameCharacter c) {
        return HipFireInput.aimIntent(c);
    }

    /**
     * QA only (grip side views): the game's cached mouse position (zombie.input.Mouse,
     * never the OS cursor) points from the player's aim origin along a world heading
     * in degrees, so aiming faces it; a negative heading restores the cache.
     */
    public static String qaAimInput(IsoPlayer p, double degrees) {
        try {
            java.lang.reflect.Field mx = zombie.input.Mouse.class.getDeclaredField("x");
            java.lang.reflect.Field my = zombie.input.Mouse.class.getDeclaredField("y");
            mx.setAccessible(true);
            my.setAccessible(true);
            if (degrees < 0) {
                if (savedMouse != null) {
                    mx.setInt(null, savedMouse[0]);
                    my.setInt(null, savedMouse[1]);
                    savedMouse = null;
                }
                return "restored";
            }
            if (savedMouse == null) {
                savedMouse = new int[] {mx.getInt(null), my.getInt(null)};
            }
            int index = p.getIndex();
            double r = Math.toRadians(degrees);
            float x = p.getAimOriginPosX() + (float) Math.cos(r) * 6, y = p.getAimOriginPosY() + (float) Math.sin(r) * 6, z = p.getAimOriginPosZ();
            float zoom = zombie.core.Core.getInstance().getZoom(index);
            float sx = zombie.iso.IsoUtils.XToScreen(x, y, z, 0) - zombie.iso.IsoCamera.getOffX(index);
            float sy = zombie.iso.IsoUtils.YToScreen(x, y, z, 0) - zombie.iso.IsoCamera.getOffY(index);
            mx.setInt(null, Math.round(sx / zoom));
            my.setInt(null, Math.round(sy / zoom));
            return "mouse " + mx.getInt(null) + " " + my.getInt(null);
        } catch (Throwable e) {
            return "error " + e;
        }
    }

    /** QA: per-frame trace of the grip transfer on/off (clears the records). */
    public static void gripIKTrace(boolean on) {
        GripIK.traces.clear();
        GripIK.tracing = on;
    }

    /** QA: "frames w hw elev applied why hand=x,y,z axis=x,y,z models" of a character, or nil. */
    public static String gripIKTraceOf(IsoGameCharacter c) {
        double[] t = c == null ? null : GripIK.traces.get(c);
        if (t == null) {
            return null;
        }
        return String.format(java.util.Locale.ROOT, "f=%d w=%.2f hw=%.2f el=%.0f ap=%d why=%d hand=%.1f,%.1f,%.1f axis=%.2f,%.2f,%.2f m=%d gun=%.1f,%.1f,%.1f lh=%.1f,%.1f,%.1f turn=%d tw=%.0f,%.0f ct=%.0f lk=%.2f state=%s rh=%.1f,%.1f,%.1f rc=%.1f,%.1f,%.1f bore=%.3f,%.3f,%.3f top=%.3f,%.3f,%.3f wr=%.2f",
                (long) t[0], t[1], t[2], t[3], (long) t[4], (long) t[5], t[6], t[7], t[8], t[9], t[10], t[11], (long) t[12],
                t[13], t[14], t[15], t[16], t[17], t[18], (long) t[19], t[20], t[21], t[22], t[23], c.getCurrentActionContextStateName(),
                t[24], t[25], t[26], t[27], t[28], t[29], t[30], t[31], t[32], t[33], t[34], t[35], t[36]);
    }

    /** QA: the clips a character plays, heaviest first, "name:weight;..." (at most n). */
    public static String gripIKTracks(IsoGameCharacter c, double n) {
        return GripIK.tracks(c, (int) n);
    }

    /** Renew with gripIK: whether the character runs a timed action (reload, rack, equip). */
    public static void gripIKActing(IsoGameCharacter c, boolean acting) {
        if (c != null) {
            GripIK.acting(c, acting);
        }
    }

    /** Reload visuals: {mode, ...} (GripIK.mags: mode 1 = 13, mode 2 = 31, mode 3 = 13 or 17 numbers), or nil to stop. */
    public static void gripIKMag(IsoGameCharacter c, KahluaTable t) {
        if (c == null) {
            return;
        }
        if (t == null) {
            GripIK.mag(c, null);
            return;
        }
        int size = t.len();
        double[] m = new double[size];
        for (int i = 0; i < size; i++) {
            Object v = t.rawget(i + 1);
            m[i] = v instanceof Double ? (Double) v : 0;
        }
        GripIK.mag(c, m);
    }

    /** The turn hold (GripIK.turnHold) on or off - QA comparisons. */
    public static void gripIKTurnHold(boolean on) {
        GripIK.turnHold = on;
    }

    public static void gripIKTwistCap(boolean on) {
        GripIK.twistCap = on;
    }

    /** The first-person elbow relief (FpPose.FP_RELIEF_FROM) on or off - QA comparisons. */
    public static void fpPoseRelief(boolean on) {
        FpPose.reliefOn = on;
    }

    /** W10 manualeye (QA A/B): Tarkov eye-level manual cycling, round reloads and unloads on or off. */
    public static void fpManualEye(boolean on) {
        FpPose.manualEye = on;
    }

    public static boolean fpManualEyeState() {
        return FpPose.manualEye;
    }

    public static String fpWeaponEnvelope(IsoGameCharacter c) {
        return FpPose.weaponEnvelope(c);
    }

    public static boolean isBreakopenReloadOpen(Object item) {
        return MovingWeaponParts.isBreakopenReloadOpen(item);
    }

    /** Natural arms (ArmNatural, the last layer) on or off - QA comparisons. */
    public static void armNaturalEnable(boolean on) {
        ArmNatural.enabled = on;
        ArmNatural.reset();
    }

    /** "calls= applied= errors= nanos= maxTauDeg= maxRhoDeg= maxOffHingeDeg= enabled=". */
    public static String armNaturalStats() {
        return ArmNatural.stats();
    }

    /** QA: both arms' binds, the arms now and the first arm the layer left as it came (not finite). */
    public static String armNaturalProbe(IsoGameCharacter c) {
        return ArmNatural.probe(c);
    }

    /** " armL=tau,rho,off armR=..." of a character (degrees), " arm=none" before its first frame. */
    public static String armNaturalOf(IsoGameCharacter c) {
        return ArmNatural.describe(c);
    }

    public static void gripIKLowLift(boolean on) {
        GripIK.lowLift = on;
    }

    public static void gripIKArmClear(boolean on) {
        GripIK.armClear = on;
    }

    /** W10 prone QA: A/B toggle for Bip01_Prop1 seating while yielded to Hardship Inertia. */
    public static void gripIKFloorSeat(boolean on) {
        GripIK.floorSeat = on;
    }

    public static void gripIKOff(IsoGameCharacter c) {
        if (c != null) {
            GripIK.clear(c);
        }
    }

    public static void gripIKEnable(boolean on) {
        GripIK.enabled = on;
        if (on) {
            GripIK.reset();
        }
    }

    /** "calls= applied= nanos= checks= maxContactCm= meanContactCm= maxFkCm= maxResidualCm= lastWeight= requests=". */
    public static String gripIKStats() {
        return GripIK.stats() + (GripIK.lastError.isEmpty() ? "" : " error=" + GripIK.lastError);
    }

    /** Tarkov muzzle effects: the data table TW/Data/MuzzleFx (TW/MuzzleFx, once per game start). */
    public static void muzzleData(KahluaTable data) {
        MuzzleFx.setData(data);
    }

    /**
     * The effect nodes of an item's assembly (TW/MuzzleFx, Tarkov MuzzleManager
     * selection): {m=12 numbers in the root model space, jet=, fume=, sparks=,
     * smoke=, x=}; launcher: the underbarrel mode (its fumes only).
     */
    public static void muzzleSetup(Object item, String weaponTpl, KahluaTable nodes, boolean launcher) {
        MuzzleFx.setup(item, weaponTpl, nodes, launcher);
    }

    public static void muzzleClear(Object item) {
        MuzzleFx.clear(item);
    }

    /** The overheat factors and hot objects of an item's assembly (TW/MuzzleFx.heat), or nil (no overheat). */
    public static void muzzleHeat(Object item, KahluaTable heat) {
        MuzzleFx.setHeat(item, heat);
    }

    /** QA: an item's overheat now. */
    public static double muzzleOverheat(Object item) {
        return MuzzleFx.overheatOf(item);
    }

    /** QA: set an item's overheat as after a shot now. */
    public static void muzzleQaOverheat(Object item, double value) {
        MuzzleFx.setOverheat(item, (float) value);
    }

    /** QA: an item's overheat setup ("shot= cool= mods= hot= visible="), or nil. */
    public static String muzzleHeatOf(Object item) {
        return MuzzleFx.heatOf(item);
    }

    /** An explosion at (x, y, z level): the Tarkov explosion light and its heat (Gunworks ExplosionFX, TW/MuzzleFx). */
    public static void explosion(double x, double y, double z) {
        MuzzleFx.explosion((float) x, (float) y, (float) z);
    }

    /** QA: muzzle effects on or off (off: the vanilla flash). */
    public static void muzzleEnable(boolean on) {
        MuzzleFx.enabled = on;
    }

    /** QA (FB4 item 11): the muzzle effects lifted by the relief at the shooter (on) or at each point (off, as before). */
    public static void muzzleQaAnchor(boolean on) {
        MuzzleFx.anchored = on;
    }

    /** QA (FB4 item 11): Viewpoint's relief lift (scene units) at a world point in the frame opened last; NaN without one. */
    public static double vpLift(double x, double y) {
        return ViewpointPass.liftNow(x, y);
    }

    /** QA (FB4 item 11): the muzzle effects with or without Viewpoint's carried prop adjustment; its last value. */
    public static void muzzleQaCarry(boolean on) {
        MuzzleFx.carry = on;
    }

    public static String qaCarry() {
        return SightDrawFrame.carryInfo();
    }

    /** QA (FB7 item 1): the first-person shadow pose - state and the last capture's measurements. */
    public static String shadowPoseInfo() {
        return ShadowPose.info();
    }

    /** QA A/B (FB7 item 1): false leaves the first-person pose in the shadow, as before. */
    public static void shadowPose(boolean on) {
        ShadowPose.enabled = on; } public static void snowHook(boolean on) { ShadowPose.snowHookEnabled = on; } public static String snowHookInfo() { return ShadowPose.snowInfo();
    }

    /** The mod option "Smoke of shots" (TW_MuzzleFx): false - shots emit no smoke (MuzzleFx.smokeOn). */
    public static void muzzleSmoke(boolean on) {
        MuzzleFx.smokeOn = on;
    }

    /** Local visual choices; shot admission, damage, ammunition and recoil remain owned by their existing paths. */
    public static void muzzleEffects(boolean flash, boolean sparks, boolean light, boolean heat) {
        MuzzleFx.effects(flash, sparks, light, heat);
    }

    /** QA (A/B in one run): the smoke puffs' limit (MuzzleFx.MAX_SMOKE; 600: the shared pool alone, as before; 0: the default). */
    public static void qaSmokeLimit(double limit) {
        MuzzleFx.smokeLimit = limit >= 1 ? (int) limit : MuzzleFx.MAX_SMOKE;
    }

    /** QA: counters of the muzzle effects (MuzzleFx.stats). */
    public static String muzzleStats() {
        return MuzzleFx.stats();
    }

    /** QA: plays a shot of the character's weapon as EffectsManager.startMuzzleFlash would (no round fired). */
    public static boolean muzzleTest(IsoGameCharacter c) {
        return MuzzleFx.shot(c);
    }

    /** QA: hold every latest flash at this phase of its shot (0..1) for screenshots; negative: off. */
    public static void muzzleQaPhase(double phase) {
        MuzzleFx.qaPhase = (float) phase;
    }

    /** QA: read back a pixel under the first muzzle quad before and after it is drawn (muzzleStats probe[...]). */
    public static void muzzleQaProbe(boolean on) {
        MuzzleFx.qaProbe = on;
        MuzzleFx.probeResult = "";
    }

    /** QA: the effect nodes set for an item, or nil. */
    public static String muzzleDescribe(Object item) {
        return MuzzleFx.describe(item);
    }

    /** QA (FB8 item 4): the native tracer's start moved to the bore exit (TracerOrigin) - counters. */
    public static String tracerInfo() {
        return TracerOrigin.info();
    }

    /** QA (FB8 item 4): the bore exit of the character's assembly, "x,y,z" (tiles, levels) or "". */
    public static String tracerBoreExit(IsoGameCharacter c) {
        double[] p = new double[3];
        return MuzzleFx.boreExit(c, p) ? String.format(java.util.Locale.ROOT, "%.4f,%.4f,%.4f", p[0], p[1], p[2]) : "";
    }

    /** QA (FB8 item 4, A/B): false leaves the game's own tracer start. */
    public static void tracerEnable(boolean on) {
        TracerOrigin.enabled = on;
    }

    public static String lastError() {
        return OpticRenderer.lastError + (ModelHeat.lastError.isEmpty() ? "" : " | model: " + ModelHeat.lastError)
                + (VehicleHeat.lastError.isEmpty() ? "" : " | vehicle: " + VehicleHeat.lastError)
                + (MuzzleFx.lastError.isEmpty() ? "" : " | muzzle: " + MuzzleFx.lastError)
                + (ViewpointPass.lastError.isEmpty() ? "" : " | viewpoint: " + ViewpointPass.lastError)
                + (ViewpointBridge.lastError.isEmpty() ? "" : " | viewpoint bridge: " + ViewpointBridge.lastError);
    }

    // --- Viewpoint (optional, ViewpointBridge / ViewpointPass) ---------------------

    /** 0 isometric (or no Viewpoint bridge), 1 Viewpoint first person, 2 Viewpoint third person. */
    public static double vpState() {
        return ViewpointBridge.state();
    }

    /** Viewpoint draws its 3D view now, bridge on or off: the isometric overlays must not be drawn (FB1). */
    public static boolean vpViewActive() {
        return ViewpointBridge.viewActive();
    }

    /**
     * W9 user item 8: Viewpoint's own third-person preference on foot (1 third person, 0 first person, -1 unknown:
     * no bridge or a Viewpoint without that member). The AIM-from-third-person feature restores exactly this value.
     */
    public static int vpThirdPersonSetting() {
        Boolean on = ViewpointBridge.thirdPersonSetting();
        return on == null ? -1 : on ? 1 : 0;
    }

    /**
     * W9 user item 8 (TW_AdsFromThird): aiming from Viewpoint's third person shows its real first person for as long
     * as the aim lasts; true when the view was switched (on) or the player's third-person preference was put back
     * (off). False refuses this request only: no bridge, a member missing, or the player already in first person.
     */
    public static boolean vpAdsFirstPerson(boolean on) {
        return ViewpointBridge.firstPersonForAds(on);
    }

    /** QA (W9 item 8): the drawn camera's horizontal offset from the eye in the newest Viewpoint frame, tiles; -1 without one. */
    public static double vpCameraDistance() {
        return ViewpointBridge.cameraDistance();
    }

    /**
     * W9 user item 8: the raw aim key of a player - mouse, keyboard or gamepad axis - not the latched forceAim
     * (the game's own toggle-to-aim keeps that). Kahlua cannot index the input component a character returns, so
     * the read lives here. False without a component: the Lua side then falls back to the aim state's own edges.
     */
    /**
     * Player report of 03.11.2026 (AimGestureInput): the keys of the mod's wheel gestures that keep a latched aim from
     * the game's run and sprint buttons - the zoom modifier's key code and the reticle brightness row. TW_SightInput.
     */
    public static void aimGestureKeys(int key, boolean shift, boolean ctrl, boolean alt) {
        AimGestureInput.keys(key, shift, ctrl, alt);
    }

    /** QA: AimGestureInput's keys, switch and counters; on=false/true switches the rule for a before/after. */
    public static String aimGestureInfo() {
        return AimGestureInput.describe();
    }

    public static void aimGestureOn(boolean on) {
        AimGestureInput.enabled = on;
    }

    public static boolean aimKeyDown(IsoGameCharacter c) {
        try {
            zombie.characters.component.CharacterInputComponent input = c == null ? null : c.getCharacterInputComponent();
            return input != null && input.isAimKeyDownInternal();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * W9 user item 8: the game's own latched aim (forceAim - what its "toggle to aim" option edits), set from the
     * Lua side for the press without the button being held. Java only: the default interface method is not part of
     * the Lua-exposed player, and in the plain hold mode the game clears that flag every frame (its toggle-to-aim
     * handler is muted, see PatchViewpoint.AimLatch), so the latch and the patch live together (ViewpointBridge).
     * False when the player or the call refused.
     */
    public static boolean aimForce(IsoGameCharacter c, boolean on) {
        return ViewpointBridge.aimForce(c, on);
    }

    /** QA: the bridge (version, SHA-256, load order, members) and the pass counters. */
    public static String vpInfo() {
        return ViewpointBridge.info() + " | pass " + ViewpointPass.stats() + " | skipped muzzle=" + MuzzleFx.vpSkipped
                + " optic=" + OpticRenderer.vpSkipped;
    }

    // --- Viewpoint lights (VpLights, VP_W6_L) ---------------------------------------

    /**
     * TW/LightRender: the lens of the viewer's visible Tarkov weapon light (x, y tiles, z levels) and the
     * weapon's bore (dx, dy tiles, dz levels); Viewpoint's own flashlight starts there along the bore.
     */
    public static boolean vpFlashLens(double x, double y, double z, double dx, double dy, double dz) {
        return VpLights.flashLens(x, y, z, dx, dy, dz);
    }

    /** TW/LightRender: the viewer's weapon is hidden by an action; Viewpoint's own flashlight (that weapon's light) is off this frame. */
    public static boolean vpFlashHidden() {
        return VpLights.flashHidden();
    }

    /** The fire-mode gesture (TW/WeaponInspect, FB4-A): its right hand's weight on the selector contact (gripIKMag). */
    public static void gripIKMagWeight(IsoGameCharacter c, double w) {
        if (c != null) {
            GripIK.magWeight(c, w);
        }
    }

    /**
     * The fire-mode gesture in the first-person layer (FpPose): the source clip's engine name (its pose track), the
     * fraction on the source clock and the gesture's number (a new number: a press during a gesture); null clip: none.
     */
    public static void fpPoseGesture(IsoGameCharacter c, String clip, double u, double token) {
        if (c != null) {
            FpPose.gesture(c, clip, u, (long) token);
        }
    }

    /** QA (FB4-A item 16): a laser's points in the open Viewpoint frame, lifted point by point and as drawn (VpLights). */
    public static String vpLaserStraightness(KahluaTable points) {
        return VpLights.straightProbe(points);
    }

    /** QA (FB4-A item 18 A/B): the smoothed release of the reach corrections (FpPose) and contact windows (GripIK) on/off. */
    public static void actionRamps(boolean on) {
        FpPose.actingRamp = on;
        GripIK.actRamp = on;
    }

    /** QA: Viewpoint's flashlight along the bore (VpFlashAim): "status= set= aimed= kept= hidden= errors=". */
    public static String vpFlashAimInfo() {
        return VpFlashAim.info();
    }

    /** QA: Tarkov lights in Viewpoint on (default) or off (its own muzzle light; PZ's light sources and smoke light as before). */
    public static void vpLights(boolean on) {
        VpLights.enabled = on;
    }

    /** QA: Tarkov lights in Viewpoint: counters and the last frame's point-light records. */
    public static String vpLightsInfo() {
        return VpLights.info();
    }

    /** QA: the HDR path of the pass on or off (off: the post pass over Viewpoint's image) and its colour scale (> 0). */
    public static void vpHdr(boolean on, double gain) {
        ViewpointPass.hdrEnabled = on;
        if (gain > 0) {
            ViewpointPass.hdrGain = (float) gain;
        }
    }

    // --- Viewpoint night vision and thermal (VpSensor, VP_W5_T) ---------------------

    /**
     * The night vision / thermal scope channel aimed through now: TW/OpticView javaView of its mode
     * plus prefix (item id:path:tpl:channel:mode:), blocks (pixelation across the lens) and, for a 1x
     * sight, geometry (as pipSet's); nil clears.
     */
    public static void vpSensorScope(KahluaTable view) {
        VpSensor.setScope(view);
    }

    /** The head device: {kind=nv, intensity, r, g, b, noise, noiseScale, gainLimit, mask, maskSize, maskAspect} or thermal values; nil clears. */
    public static void vpSensorHead(KahluaTable device) {
        VpSensor.setHead(device);
    }

    /** Every tick: the viewer sees infrared (night vision), optic effects on, the heat buffer's share of the picture. */
    public static void vpSensorState(boolean ir, boolean on, double heatScale) {
        VpSensor.setState(ir, on, heatScale);
    }

    /** Thermal inputs {living=, zombie=, animal=} and range (tiles) of the warm things. */
    public static void vpSensorConfig(KahluaTable config) {
        VpSensor.setConfig(config);
    }

    /** The class of the pass primitives submitted next: 1 infrared, 0 visible light. */
    public static void vpSensorMark(double cls) {
        ViewpointPass.mark = cls >= 0.5 ? VpSensor.CLS_IR : VpSensor.CLS_VISIBLE;
    }

    /** Infrared primitives are wanted now (a night vision view in Viewpoint: scope, eyepiece or head). */
    public static boolean vpSensorIr() {
        return VpSensor.irWanted();
    }

    /** QA: the sensors' state, counters and cost (CPU ms of the last scope / eyepiece / head image, GPU ms). */
    public static String vpSensorInfo() {
        return VpSensor.info();
    }

    /** QA: enable/reset frame projection samples; disabled by default, no GL readback. */
    public static void vpProbe(boolean on) {
        ViewpointPass.probing(on);
    }

    /** QA: last first jet (otherwise particle), laser point and marker from their actual drawn frame. */
    public static String vpProbe() {
        return ViewpointPass.probe();
    }

    /** QA: an opaque magenta 20 cm billboard at the first active emitter, using the muzzle path. */
    public static void vpMuzzleMarker(boolean on) {
        ViewpointPass.muzzleMarker = on;
    }

    /** The perspective pass takes primitives now (between the world frame and the screen composite). */
    public static boolean vpAccepting() {
        return ViewpointPass.accepting();
    }

    /** The eye and view direction of this Viewpoint frame: {x, y, z (levels), dx, dy, dz (levels per unit)}, or nil. */
    public static KahluaTable vpEye() {
        double[] e = new double[6];
        if (!ViewpointPass.eye(e)) {
            return null;
        }
        KahluaTable t = LuaManager.platform.newTable();
        String[] keys = {"x", "y", "z", "dx", "dy", "dz"};
        for (int i = 0; i < 6; i++) {
            t.rawset(keys[i], (Object) Double.valueOf(e[i]));
        }
        return t;
    }

    static double[] numbers(KahluaTable t) {
        int n = t == null ? 0 : t.len();
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            Object v = t.rawget(i + 1);
            out[i] = v instanceof Double ? (Double) v : 0;
        }
        return out;
    }

    /**
     * A strip in the Viewpoint frame: points {x, y, z (levels), alpha, ...} in
     * the world, colour and alpha, width in pixels (1080 lines) and in the world
     * (tiles), blend 0 alpha / 1 additive, depth softness (tiles).
     */
    public static boolean vpStrip(KahluaTable points, double r, double g, double b, double a, double widthPx, double worldWidth,
                                  double blend, double soft) {
        double[] p = numbers(points);
        return ViewpointPass.strip(p, p.length / 4, (float) r, (float) g, (float) b, (float) a, (float) widthPx, (float) worldWidth,
                (int) blend, (float) soft);
    }

    /** A camera-facing sprite: world radius (tiles), smallest radius in pixels, texture media/textures/<name>.png or nil, bias toward the camera. */
    public static boolean vpSprite(double x, double y, double z, double radius, double minPx, double r, double g, double b, double a,
                                   String texture, double blend, double soft, double bias) {
        return ViewpointPass.sprite(x, y, z, (float) radius, (float) minPx, (float) r, (float) g, (float) b, (float) a, texture,
                (int) blend, (float) soft, (float) bias);
    }

    /** An oriented quad: corners {x, y, z, x, y, z, ...} (4, in order around it), alphas {a1..a4}. */
    public static boolean vpQuad(KahluaTable corners, double r, double g, double b, KahluaTable alphas, String texture, double blend,
                                 double soft, double bias) {
        double[] c = numbers(corners), al = numbers(alphas);
        if (c.length < 12) {
            return false;
        }
        float[] a4 = new float[4];
        for (int i = 0; i < 4; i++) {
            a4[i] = (float) (al.length > i ? al[i] : al.length > 0 ? al[0] : 1);
        }
        return ViewpointPass.quad(c, (float) r, (float) g, (float) b, a4, texture, (int) blend, (float) soft, (float) bias);
    }
    // FB2-B: owner-local vanilla rangefinder LCD, captured before rendering.
    public static void rangefinderDisplay(IsoPlayer player, Object weapon, KahluaTable vertices, KahluaTable color, String atlas) {
        ReticleRendererRangefinder.set(player, weapon, vertices, color, atlas);
    }

    // The display panel of the device's rangefinder mode, drawn under the digits.
    public static void rangefinderPanel(IsoPlayer player, Object weapon, KahluaTable vertices, String atlas) {
        ReticleRendererRangefinder.setPanel(player, weapon, vertices, atlas);
    }

    public static String rangefinderInfo() { return ReticleRendererRangefinder.info(); }

    // FB4-K (item 4): QA rings of the first-person weapon in the camera (pose / capture / draw), off by default.
    public static void jitterProbe(boolean on) {
        FpJitterProbe.on = on;
        FpJitterProbe.reset();
    }

    public static String jitterProbeDump(String ring, double n) {
        return FpJitterProbe.dump(ring, (int) n);
    }

    /**
     * QA (FB5 item 4, A/B in one run): walk - the pose's eye carried with the character's position (false: held where
     * the camera was at the last capture, FB4); eye - the drawing's latch carries the posed eye onto its camera (false:
     * the look's turn only).
     */
    public static void qaFpEye(boolean walk, boolean eye) {
        SightDrawFrame.carryWalk = walk;
        FpViewLatch.eyeLatch = eye;
    }

    /** QA (FB5 items 5, 7, A/B in one run): the selected sight kept beyond Viewpoint's near plane in ADS (false: as before). */
    public static void qaKeepOut(boolean on) {
        FpPose.keepOut = on;
    }

    /** QA (the user's report of 24.10.2026, A/B in one run): the hip slide out of reach held through a shot (false: as before). */
    public static void qaSlideHold(boolean on) {
        FpPose.slideHold = on;
    }

    /** QA (performance, A/B in one run): parts of the scope's second world left out (ViewpointPipBridge.qaSkip bits). */
    public static void qaPipSkip(double mask) {
        ViewpointPipBridge.qaSkip = (int) mask;
    }

    /** QA (A/B in one run): false - the scope picture without its precipitation layer, exactly as before it existed. */
    public static void qaPipWeather(boolean on) {
        ViewpointPipBridge.qaWeather = on;
    }

    /** QA (performance, A/B in one run): the scope picture's size follows the eyepiece (false: the option's share of the screen). */
    public static void qaPipLensFit(boolean on) {
        ScopePip.lensFit = on;
    }

    /** QA, before the world starts: false builds the scope's second world in the first raise (as before ScopePip.prewarm). */
    public static void qaPipPrewarm(boolean on) {
        ScopePip.prewarm = on;
    }

    /** QA (debug): stacks of the main and render threads sampled every millisecond for ms (HitchSampler). */
    public static boolean qaHitchStart(double ms) {
        if (!zombie.Lua.LuaManager.GlobalObject.getDebug()) return false;
        HitchSampler.start(ms);
        return true;
    }

    /** QA: one of the caller's frames (HitchSampler.report measures the frames between marks). */
    public static void qaHitchMark() {
        HitchSampler.mark();
    }

    /** QA: the frames over thresholdMs and the top methods sampled in them, printed as [TWZB-HITCH] lines. */
    public static String qaHitchReport(double thresholdMs, double top) {
        return HitchSampler.report(thresholdMs, (int) top);
    }

    /** OnGameStart (TW/MuzzleFx.lua): the mod's GL programs compiled now, not at their first use (ShaderWarmup). */
    public static void warmShaders() {
        ShaderWarmup.queue();
    }

    /** QA: the warm-up's result ("done ... totalMs=... <step>=<ms>", "disabled", "queued"). */
    public static String shaderWarmInfo() {
        return ShaderWarmup.info();
    }

    /** QA, before the world starts: false keeps every program to its first use (as before the warm-up). */
    public static void qaShaderWarm(boolean on) {
        ShaderWarmup.disabled = !on;
    }

    /** QA (A/B in one run): the scope picture starts at the eyepiece's size at full ADS (false: at the cap, as before). */
    public static void qaPipPredictFit(boolean on) {
        ScopePip.predictFit = on;
    }

    /** QA: the next scope picture starts as the first of a session (no size kept from an earlier sight). */
    public static void qaPipFitReset() {
        zombie.core.SpriteRenderer.instance.drawGeneric(new zombie.core.textures.TextureDraw.GenericDrawer() {
            @Override public void render() { ScopePip.resetFit(); }
            @Override public void postRender() { }
        });
    }

    /** QA (performance, A/B in one run): the scope's world under the main view's shadow maps (false: its own shadow pass). */
    public static void qaPipSharedShadows(boolean on) {
        ViewpointPipBridge.sharedShadows = on;
    }

    /** QA (A/B in one run): the scope picture read through a Catmull-Rom spline (false: one bilinear tap, as before). */
    public static void qaPipSharp(boolean on) {
        ScopePip.sharpPicture = on;
    }

    /**
     * QA (FB5 item 5, A/B in one run): the thermal scope's three changes together - the eyepiece nearer than the near
     * plane still projected, the own shot's glows near the scope camera veiled out of its focus, the heat kept while
     * the picture is requested (false: all three as before).
     */
    public static void qaThermalFix(boolean on) {
        ScopePipGeometry.nearLens = on;
        VpHeat.scopeFocus = on ? VpHeat.SCOPE_FOCUS : 0f;
        VpSensor.heatOnRequest = on;
    }

    // FB4-K (item 5): Viewpoint's aim ring (the vanilla reticle it reuses) skipped while aiming down sights.
    public static String aimRingStats() {
        return FpAimRing.stats();
    }

    public static void aimRingReset() {
        FpAimRing.reset();
    }

    /**
     * QA (FB4 item 9; bug wave W7, Immersive Snow): the drawn weapon of the hands - its root model script and the
     * anchors the parts hang from, then its part instances and their model scripts. A part whose anchor is not in the
     * drawn root script is marked "!" (the engine then places it at the weapon's origin). The item's own model fields
     * are read directly (Java, not the Lua metatable): the snow mod swaps them under Lua and hooks the Lua getters.
     */
    public static String qaHandModel(IsoGameCharacter c) {
        var m = c == null ? null : c.primaryHandModel;
        if (m == null) return "none";
        var root = m.modelScript;
        StringBuilder b = new StringBuilder("id=").append(System.identityHashCode(m)).append(" root=")
                .append(root == null ? "?" : root.getFullType()).append(" parts=").append(m.sub.size());
        if (c.getPrimaryHandItem() instanceof HandWeapon w) {
            var list = w.getWeaponSpritesByIndex();
            b.append(" sprite=").append(w.getWeaponSprite()).append(" idx=").append(w.getModelIndex())
                    .append(" list=").append(list == null ? -1 : list.size());
            if (list != null && w.getModelIndex() >= 0 && w.getModelIndex() < list.size()) {
                b.append(" show=").append(list.get(w.getModelIndex()));
            }
        }
        for (var s : m.sub) {
            boolean anchor = s.attachmentNameParent == null
                    || (root != null && root.getAttachmentById(s.attachmentNameParent) != null);
            b.append(' ').append(s.attachmentNameParent).append(anchor ? "=" : "!=")
                    .append(s.modelScript == null ? "?" : s.modelScript.getName())
                    .append('#').append(System.identityHashCode(s));
        }
        return b.toString();
    }

    /**
     * Immersive Snow compatibility (bug wave W7, 25.10.2026, TW_SnowCompat): the model names of a weapon's part list.
     * Kahlua cannot read the public fields of ModelWeaponPart, so the snow compat asks Java for them, makes its snow
     * copies of those models in Lua and then has the same list copied with the names replaced.
     */
    public static KahluaTable partModelNames(Object rows) {
        var out = LuaManager.platform.newTable();
        if (!(rows instanceof java.util.List<?> list)) return out;
        int at = 1;
        for (Object o : list) {
            if (o instanceof ModelWeaponPart row && row.modelName != null) out.rawset(at++, row.modelName);
        }
        return out;
    }

    /** A copy of a part list with each row's model replaced by the name mapped in models (same fields otherwise). */
    public static java.util.ArrayList<ModelWeaponPart> partRowsWithModels(Object rows, KahluaTable models) {
        var out = new java.util.ArrayList<ModelWeaponPart>();
        if (!(rows instanceof java.util.List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof ModelWeaponPart row)) continue;
            var made = new ModelWeaponPart();
            made.partType = row.partType;
            made.attachmentNameSelf = row.attachmentNameSelf;
            made.attachmentParent = row.attachmentParent;
            Object mapped = row.modelName == null || models == null ? null : models.rawget(row.modelName);
            made.modelName = mapped instanceof String s && !s.isEmpty() ? s : row.modelName;
            out.add(made);
        }
        return out;
    }

    /**
     * Immersive Snow compatibility (bug wave W7): a copy of one of our model scripts and the model it was made from,
     * so the renderer can take a mount the copy does not have (they are placed at runtime) from our own model.
     */
    public static void snowBase(String copy, String base) { WeaponPartTransforms.snowBase(copy, base); }

    // W9-anchors
    public static void partAnchorSource(Object item, String poolModelFullType) {
        WeaponPartTransforms.partAnchorSource(item, poolModelFullType);
    }
    public static void partAnchorForget(Object item) {
        WeaponPartTransforms.partAnchorForget(item);
    }
    public static String handModelState(IsoGameCharacter character) {
        return WeaponPartTransforms.handModelState(character);
    }
    public static String handModelDetail(IsoGameCharacter character) {
        return WeaponPartTransforms.handModelDetail(character);
    }
    public static String partAnchorStats() {
        return WeaponPartTransforms.partAnchorStats();
    }

    /** QA (FB4 item 5): the vanilla reticle mode of player 0 (RETICLE as real aiming input sets it; forced aim keeps CURSOR). */
    public static void qaReticle(boolean on) {
        zombie.iso.sprite.IsoReticle.trySetTargetReticleMode(0, on ? zombie.iso.sprite.IsoReticleMode.RETICLE : zombie.iso.sprite.IsoReticleMode.CURSOR);
    }

    /**
     * Tarkov weapon screens (TW/TarkovUI/Frosted): queue the frosted game frame over (x, y, w, h) of
     * the screen, from a screen's prerender. look: tint r, g, b, tint amount, darken, desaturate,
     * vignette. True once a blurred frame exists (false: the Lua side draws a plain plate).
     */
    public static boolean frostedBackdrop(double x, double y, double w, double h, double r, double g, double b, double tint,
            double darken, double desaturate, double vignette) {
        return FrostedBackdrop.request(x, y, w, h, new float[] {(float) r, (float) g, (float) b, (float) tint, (float) darken,
                (float) desaturate, (float) vignette, 1f});
    }

    public static String frostInfo() {
        return FrostedBackdrop.info();
    }

    /**
     * Tarkov weapon screens (TW/TarkovUI/Theme): sharp SDF text. The Lua UI draws the game's SDF
     * fonts zoomed but through the plain sprite shader, which shows the distance field as a soft
     * glow; this wraps the following text draws in the game's own SDF shader, as AtomUIText does
     * (threshold 0.125 / zoom), with an optional outline (0..1 of the field) and drop shadow.
     */
    public static boolean sdfBegin(double zoom, double outline, double r, double g, double b, double a, boolean shadow) {
        zombie.core.opengl.SDFShader shader = zombie.ui.TextManager.sdfShader;
        if (shader == null || !shader.isCompiled()) return false;
        shader.updateThreshold((float) (0.125 / Math.max(0.05, zoom)));
        shader.updateShadow(shadow ? 1f : 0f);
        shader.updateOutline((float) outline, (float) r, (float) g, (float) b, (float) a);
        zombie.IndieGL.StartShader(shader);
        return true;
    }

    public static void sdfEnd() {
        zombie.IndieGL.EndShader();
    }

    /** Tarkov weapon screens (TW/TarkovUI/Stats): muzzle velocity (m/s) of an assembly; 0 when unknown. */
    public static double muzzleVelocity(String weaponKey, String ammoTpl, KahluaTable parts) {
        java.util.List<String> list = new java.util.ArrayList<>();
        int n = parts == null ? 0 : parts.len();
        for (int i = 0; i < n; i++) {
            Object v = parts.rawget(i + 1);
            if (v instanceof String s) list.add(s);
        }
        return UiBallistics.muzzleVelocity(weaponKey, ammoTpl, list);
    }

    // FB4-K (item 12): the cosmetic contact store of shot marks (counts, marks and particles alive), for QA.
    public static String impactStats() {
        var s = ImpactMarks.STORE;
        return "enabled=" + ImpactMarks.enabled + " failed=" + ImpactMarks.failed + " reason=" + ImpactMarks.reason + " accepted=" + s.accepted
                + " unknown=" + s.unknown + " unloaded=" + s.unloaded + " duplicates=" + s.duplicates + " marks=" + s.marks.size()
                + " particles=" + s.particles.size() + " frames=" + ImpactFx.frames + " quads=" + ImpactFx.quads + " isoFrames=" + ImpactFx.isoFrames
                + " isoQuads=" + ImpactFx.isoQuads + " audio=" + ImpactFx.audio + " fallbacks=" + ImpactMarks.fallbacks
                + " lastFallbacks=" + String.join(",", ImpactMarks.FALLBACKS) + " lastUnknown=" + String.join(",", ImpactMarks.UNKNOWN);
    }

    // W11 camera2 (items 2, 17): QA of Viewpoint's crosshair pick - SELF drawn after MousePick.read, the aim hit read
    // before a vehicle enter/exit dropped, the layer off in vehicles; each switch true restores the behaviour before.
    public static void qaCameraYawLegacy(boolean on) {
        FpViewLatch.qaSelfInPick = on;
        FpViewLatch.qaIgnoreVehicleLayer = on;
        ViewpointBridge.qaLegacyVehiclePick = on;
    }

    public static void qaCameraSelfInPick(boolean on) {
        FpViewLatch.qaSelfInPick = on;
    }

    public static void qaCameraVehicleLayer(boolean ignoreVehicle) {
        FpViewLatch.qaIgnoreVehicleLayer = ignoreVehicle;
    }

    public static void qaCameraVehiclePick(boolean legacy) {
        ViewpointBridge.qaLegacyVehiclePick = legacy;
    }

    /** QA: the gbuffer's packed depth/stencil at nine screen points, read at the entry of IndirectPass.draw (one frame). */
    public static void qaGbufferProbe() {
        FpViewLatch.qaGbufferProbe = true;
    }

    public static String qaGbufferProbeResult() {
        return FpViewLatch.qaGbufferProbeResult;
    }

    public static boolean qaSetThirdPersonToggle(boolean seated, boolean thirdPerson) {
        return ViewpointBridge.qaSetThirdPersonToggle(seated, thirdPerson);
    }

    public static String cameraYawSample(IsoGameCharacter c) {
        return ViewpointBridge.cameraYawSample(c);
    }

    public static String cameraYawStats() {
        return ViewpointBridge.cameraYawStats();
    }

    public static void cameraYawReset() {
        ViewpointBridge.cameraYawReset();
    }

    // ---------------------------------------------------------------- W11 oldpoint (QA only, never shipped)

    /** QA: OldPoint's rotating overhead camera draws the world now (its own topDownActive, through the guarded probe). */
    public static boolean vpOverhead() {
        return OldPointCompat.overhead();
    }

    /** QA (A/B in one run): 0 follows OldPoint, 1 reports its overhead, -1 ignores it (the behaviour before W11 oldpoint). */
    public static double qaOldPointForce(double mode) {
        return OldPointCompat.qaForce((int) mode);
    }

    public static String oldPointStats() {
        return OldPointCompat.stats();
    }

    /** QA: the point under the cursor at a level height, as OldPoint maps it for its own ballistics: "x,y,z,dx,dy,dz". */
    public static String qaOldPointAim(double height) {
        return OldPointCompat.aimPoint(height);
    }

    /** QA: OldPoint's "cursor over the UI" flag, "true"/"false", or "unavailable:..." without OldPoint. */
    public static String qaOldPointOverUI() {
        return OldPointCompat.qaOverUI();
    }

    /** QA: Viewpoint's own crosshair dot is drawn this frame (View.crosshairShown after every advice), 1/0, -1 unknown. */
    public static double vpCrosshairShown() {
        try {
            if (!ViewpointBridge.ready() && !FpViewLatch.detect()) {
                return -1;
            }
            return FpViewLatch.crosshairShown() ? 1 : 0;
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String tpDotInfo() {
        try {
            return TpAimDot.info();
        } catch (Throwable t) {
            return "status=boundary-error error=" + t;
        }
    }

    public static void qaTpDotLegacy(boolean legacy) {
        try {
            TpAimDot.qaLegacy(legacy);
        } catch (Throwable ignored) {
        }
    }

    /** QA: the third-person shot's aim at Viewpoint's crosshair point (ShotOwner.thirdPersonAim) - counters and the last shot. */
    public static String tpAimInfo() {
        double[] a = ShotOwner.tpAimLastAim.clone();
        double level = ViewpointBridge.LEVEL;
        return String.format(java.util.Locale.ROOT,
                "legacy=%b shots=%d kicks=%d clamps=%d kept=%d lastDeg=%.4f maxDeg=%.4f lastDepthM=%.3f"
                        + " aimPoint=%.4f,%.4f,%.4f place=%.4f,%.4f,%.4f look=%.5f,%.5f,%.5f pick=%.4f hit=%.4f,%.4f,%.4f",
                ShotOwner.tpAimLegacy, ShotOwner.tpAims, ShotOwner.tpAimKicks, ShotOwner.tpAimClamps, ShotOwner.tpAimKept,
                ShotOwner.tpAimLastDeg, ShotOwner.tpAimMaxDeg, ShotOwner.tpAimLastDepth,
                a[10], a[11], a[12], a[0], a[1], a[2] / level, a[3], a[4], a[5], a[6], a[7], a[8], a[9]);
    }

    /** QA: the third-person shot and laser leave the drawn launch (true), as before 03.11.2026 - the negative control. */
    public static void qaTpAimLegacy(boolean legacy) {
        ShotOwner.tpAimLegacy = legacy;
    }
}
