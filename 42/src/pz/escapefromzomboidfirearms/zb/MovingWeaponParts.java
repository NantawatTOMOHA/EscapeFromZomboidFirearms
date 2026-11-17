package pz.escapefromzomboidfirearms.zb;

import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel.AnimatedModelInstanceRenderData;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.inventory.types.HandWeapon;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.ModelScript;

/** Mechanical visuals only: no ammo, action, pose, shot ownership or shared ModelScript writes. */
@Exposer.LuaClass(name = "TWMovingWeaponPartsNative")
public final class MovingWeaponParts implements WeaponPartTransforms.Source {
    private static final Map<IsoGameCharacter, Request> requests = new WeakHashMap<>();
    private static final Map<HandWeapon, State> states = new WeakHashMap<>();
    private static final Map<ModelInstance, Split> splits = new WeakHashMap<>();
    private static final Map<ModelInstance, PartSplit> partSplits = new WeakHashMap<>();
    public static volatile boolean boltOpenEnabled = true;
    private static final Map<IsoGameCharacter, Set<String>> failures = new WeakHashMap<>();
    private static final Map<String, double[]> openPoses = new LinkedHashMap<>();
    private static String reason = "ready";
    private static String lastFailure = "none", anonymousFailure;
    private static Throwable abiFailure;
    private static boolean enabled = verify(), logged, traced;
    private static long rendered, installed, errors, unsupported;

    static {
        if (enabled) WeaponPartTransforms.register("mechanics", new MovingWeaponParts());
        else disable(abiFailure);
    }

    private record Binding(String path, double[] placement) { }
    private record Request(HandWeapon item, MovingWeaponPartsData.Weapon weapon, KahluaTable source,
                           Map<String, Binding> bindings, long frame) { }
    private record Split(Model original, Model replacement, List<WeakReference<ModelInstance>> children) { }
    private record PartSplitSpec(String tpl, String bodyModel, String handleModel, String handleBone) { }
    private record PartSplit(ModelInstance root, Model original, Model replacement, String anchor,
                             WeakReference<ModelInstance> handleChild) { }
    private static final class DataFailure extends IllegalStateException {
        DataFailure(String message) { super(message); }
    }
    private static final class State {
        long shotAt, pendingFrame = -1;
        long sampleFrame = Long.MIN_VALUE, sampleAt;
        boolean open, pending;
        String action = "", mode = "idle", family = "";
        double u, weight, qaU = -1;
        String qaTrack;
        long breakopenFrame = Long.MIN_VALUE, closeStartNs;
        boolean reloadOpen, reloadClosing, rackClosing;
        double reloadOpenFrac, closeStartU, breakopenU, breakopenW = 1, reloadLastU;
        int reloadChamber;
        String reloadRawClip = "", breakopenMode = "";
        MovingWeaponPartsData.Track breakopenTrack;
    }

    private record BreakopenSpec(String hingeBone, String chamberParentBone, String closeTrack,
                                 double maxDeg, double maxTransUnits, int chambers,
                                 String roundPrefix, String launcherPrefix) { }

    private static final Map<String, BreakopenSpec> BREAKOPEN_SPECS = Map.ofEntries(
            Map.entry("MP431C", new BreakopenSpec("mod_barrel", "mod_barrel", "twround:mr43:Close", 21.6, 0, 2, "mr43", null)),
            Map.entry("MP43SAWED", new BreakopenSpec("mod_barrel", "mod_barrel", "twround:mr43_sawed:Close", 21.6, 0, 2, "mr43_sawed", null)),
            Map.entry("MP18", new BreakopenSpec("mod_barrel", "mod_barrel", "twround:mp18:Close", 25.0, 0, 1, "mp18", null)),
            Map.entry("FN40GL", new BreakopenSpec("mod_launcher_tube", "mod_launcher_tube", "twl:fn40gl:Rack", 21.4, 0.1133 * GripIK.U, 1, null, "fn40gl")),
            Map.entry("CR50DS", new BreakopenSpec("weapon_crane", "mod_magazine", "twround:rhino:Close", 95.7, 0, 6, "rhino", null)),
            Map.entry("CR200DS", new BreakopenSpec("weapon_crane", "mod_magazine", "twround:rhino:Close", 95.7, 0, 6, "rhino", null)),
            Map.entry("RSH12", new BreakopenSpec("weapon_cylinder_axis", "mod_magazine", "twround:rsh12:Close", 84.6, 0, 5, "rsh12", null)),
            Map.entry("MTS255", new BreakopenSpec("weapon_cylinder_crane", "mod_magazine", "twround:mc255:Close", 103.9, 0, 5, "mc255", null)),
            Map.entry("MSGL", new BreakopenSpec("weapon_mag_axis", "mod_magazine", "twl:m32a1:Close", 90.0, 0, 6, null, "m32a1")),
            Map.entry("MOSIN", new BreakopenSpec("weapon_bolt", null, "twround:mosin_inf:Close", 96.01, 0.10595 * GripIK.U, 1, "mosin_inf", null)),
            Map.entry("MOSINSNIPER", new BreakopenSpec("weapon_bolt", null, "twround:mosin:Close", 96.01, 0.10595 * GripIK.U, 1, "mosin", null)),
            Map.entry("SKS", new BreakopenSpec("weapon_bolt_carrier", null, "twround:sks:Close", 0.0, 0.08119 * GripIK.U, 1, "sks", null)),
            Map.entry("OPSKS", new BreakopenSpec("weapon_bolt_carrier", null, "twround:sks:Close", 0.0, 0.08119 * GripIK.U, 1, "sks", null))
    );
    private static final Set<String> BOLT_OPEN_ROUND_KEYS = Set.of("MOSIN", "MOSINSNIPER", "SKS", "OPSKS");
    private static final Map<String, String> BOLT_RACK_TRACKS = Map.of(
            "MOSIN", "clip:mosin_inf_fire_chambering_0",
            "MOSINSNIPER", "clip:mosin_fire_chambering_0",
            "AXMC", "clip:axmc_fire_chambering_0",
            "DVL10", "clip:dvl_10_fire_chambering_0",
            "M700", "clip:m700_fire_chambering_0",
            "SV98", "clip:sv98_fire_chambering_0",
            "T5000M", "clip:t5000_fire_chambering_0",
            "TOZ106", "clip:toz106_fire_reload_0",
            "TRGM10", "clip:sako_rechamber_0",
            "VPO215", "clip:vpo215_fire_chambering_0"
    );
    private static final List<PartSplitSpec> PART_SPLITS = List.of(
            new PartSplitSpec("62811cd7308cb521f87a8f99", "MB_62811cd7308cb521f87a8f99_body",
                    "MB_62811cd7308cb521f87a8f99_handle", "weapon_bolt_rotation 1"),
            new PartSplitSpec("673cb4054ff4aa8f86076f4a", "MB_673cb4054ff4aa8f86076f4a_body",
                    "MB_673cb4054ff4aa8f86076f4a_handle", "mod_charge_axis")
    );
    private static final Pattern TWROUND_CLIP = Pattern.compile("^TWRound_(?:.+_)?[MF]_(mr43_sawed|mr43|mp18|rhino|rsh12|mc255|mosin_inf|mosin|sks)_(LoadSpent|Load|Close|Open|Loop|Unload)$");
    private static final Pattern TWL_CLIP = Pattern.compile("^TWL_[A-Za-z0-9_]+_[MF]_(fn40gl|m32a1)_([A-Za-z0-9]+)$");

    private MovingWeaponParts() { }

    private static void field(Class<?> c, String name, Class<?> type) throws Exception {
        var f = c.getField(name);
        if (f.getType() != type || !Modifier.isPublic(f.getModifiers())) throw new NoSuchFieldException(c.getName() + "." + name);
    }

    private static void method(Class<?> c, String name, Class<?> type, Class<?>... args) throws Exception {
        var m = c.getMethod(name, args);
        if (m.getReturnType() != type || !Modifier.isPublic(m.getModifiers())) throw new NoSuchMethodException(c.getName() + "." + name);
    }

    private static boolean verify() {
        try {
            method(AnimatedModelInstanceRenderData.class, "transformToParent", AnimatedModelInstanceRenderData.class, AnimatedModelInstanceRenderData.class);
            field(AnimatedModelInstanceRenderData.class, "xfrm", org.joml.Matrix4f.class);
            field(AnimatedModelInstanceRenderData.class, "modelInstance", ModelInstance.class);
            field(ModelInstance.class, "model", Model.class);
            field(ModelInstance.class, "parent", ModelInstance.class);
            field(ModelInstance.class, "character", IsoGameCharacter.class);
            field(ModelInstance.class, "sub", ArrayList.class);
            field(ModelInstance.class, "modelScript", ModelScript.class);
            field(ModelInstance.class, "attachmentNameParent", String.class);
            field(ModelInstance.class, "scale", float.class);
            field(ModelInstance.class, "animPlayer", AnimationPlayer.class);
            field(IsoGameCharacter.class, "primaryHandModel", ModelInstance.class);
            field(Model.class, "mesh", zombie.core.skinnedmodel.model.ModelMesh.class);
            field(zombie.core.skinnedmodel.model.ModelMesh.class, "transform", org.joml.Matrix4f.class);
            method(ModelManager.class, "addStatic", ModelInstance.class, ModelInstance.class, String.class, String.class, String.class);
            method(ModelManager.class, "tryGetLoadedModel", Model.class, ModelScript.class, boolean.class);
            method(ModelManager.class, "tryGetLoadedModel", Model.class, String.class, String.class, boolean.class, String.class, boolean.class);
            method(ModelManager.class, "getLoadedModel", Model.class, String.class, String.class, boolean.class, String.class);
            method(ModelManager.class, "loadStaticModel", Model.class, String.class, String.class, String.class);
            method(ModelManager.class, "resetModelInstance", void.class, ModelInstance.class, Object.class);
            method(ModelInstance.class, "setOwner", void.class, Object.class);
            method(ModelInstance.class, "getOwner", Object.class);
            method(ModelScript.class, "getScriptObjectFullType", String.class);
            method(ModelScript.class, "getMeshName", String.class);
            method(ModelScript.class, "getTextureName", String.class);
            method(ModelScript.class, "getShaderName", String.class);
            method(HandWeapon.class, "isRoundChambered", boolean.class);
            method(HandWeapon.class, "getCurrentAmmoCount", int.class);
            method(HandWeapon.class, "getWeaponSprite", String.class);
            method(AnimationPlayer.class, "getSkinningBoneIndex", int.class, String.class, int.class);
            method(AnimationPlayer.class, "getBoneModelTransform", org.lwjgl.util.vector.Matrix4f.class, int.class, org.lwjgl.util.vector.Matrix4f.class);
            return true;
        } catch (Throwable e) { abiFailure = e; reason = "ABI: " + e; return false; }
    }

    public static synchronized boolean ready() { return enabled; }

    public static synchronized boolean isBreakopenReloadOpen(Object item) {
        if (!boltOpenEnabled || !(item instanceof HandWeapon hw)) return false;
        State st = states.get(hw);
        return st != null && (st.reloadOpen || st.reloadOpenFrac > 0.05);
    }

    public static synchronized void setBoltOpenEnabled(boolean on) {
        boltOpenEnabled = on;
        for (State s : states.values()) {
            if (s == null) continue;
            s.reloadOpen = false;
            s.reloadClosing = false;
            s.rackClosing = false;
            s.reloadOpenFrac = 0;
            s.breakopenTrack = null;
            s.reloadRawClip = "";
            s.reloadLastU = 0;
        }
    }

    public static synchronized boolean isBoltOpenEnabled() { return boltOpenEnabled; }

    /** Immutable assembly placement table; refreshed by Lua only when the installed tree changes. */
    public static synchronized void update(IsoGameCharacter c, HandWeapon item, String key, KahluaTable table) {
        if (!enabled || c == null) return;
        try {
            if (item == null || key == null || c.getPrimaryHandItem() != item) { off(c); return; }
            MovingWeaponPartsData data = MovingWeaponPartsData.get();
            if (data == null) { disable(new DataFailure(MovingWeaponPartsData.status)); return; }
            MovingWeaponPartsData.Weapon w = data.weapons.get(key);
            if (w == null) { off(c); return; }
            Request old = requests.get(c);
            Map<String, Binding> bindings = old != null && old.item == item && old.source == table ? old.bindings : bindings(table);
            Request r = new Request(item, w, table, bindings, FpRecoil.worldFrame());
            requests.put(c, r);
            State s = states.computeIfAbsent(item, ignored -> new State());
            if ((old == null || old.item != item) && !safeVarBool(c, "isLoading")
                    && !safeVarBool(c, "isUnloading") && !safeVarBool(c, "isRacking")) {
                s.reloadOpen = false;
                s.reloadClosing = false;
                s.rackClosing = false;
                s.reloadOpenFrac = 0;
                s.breakopenTrack = null;
                s.reloadRawClip = "";
                s.reloadLastU = 0;
            }
            if (item.isRoundChambered()) { s.open = false; s.pending = false; }
            else if (s.pending) {
                if (item.getCurrentAmmoCount() == 0) { s.open = w.boltCatch(); s.pending = false; }
                else if (r.frame > s.pendingFrame + 3) s.pending = false;
            }
            ModelInstance root = c.primaryHandModel;
            String sprite = item.getWeaponSprite();
            if (sprite != null && root != null && root.modelScript != null && sprite.equals(root.modelScript.getScriptObjectFullType()))
                install(root, w, snowShader(root));
        } catch (Throwable e) { reject(c, null, "update", e); }
    }

    private static Map<String, Binding> bindings(KahluaTable table) {
        Map<String, Binding> out = new LinkedHashMap<>();
        if (table != null) for (int i = 1; table.rawget(i) instanceof KahluaTable row; i++) {
            Object a = row.rawget("anchor"), p = row.rawget("path");
            double[] matrix = numbers(row, "matrix", 12);
            if (!(a instanceof String anchor) || !(p instanceof String path) || matrix == null) throw new IllegalArgumentException("mechanical assembly binding");
            out.put(anchor, new Binding(path, GripIK.rows(matrix, 0)));
        }
        return Map.copyOf(out);
    }

    private static double[] numbers(KahluaTable t, String key, int count) {
        Object value = t.rawget(key);
        if (!(value instanceof KahluaTable table)) return null;
        double[] a = new double[count];
        for (int i = 0; i < count; i++) {
            if (!(table.rawget(i + 1) instanceof Number n) || !Double.isFinite(n.doubleValue())) return null;
            a[i] = n.doubleValue();
        }
        return a;
    }

    /** Called at the existing confirmed muzzle event, including remote shots; it never fires a shot. */
    public static synchronized void shot(IsoGameCharacter c) {
        if (!enabled || c == null) return;
        try {
            Request r = requests.get(c);
            if (r == null || c.getPrimaryHandItem() != r.item) return;
            State s = states.computeIfAbsent(r.item, ignored -> new State());
            s.shotAt = System.nanoTime();
            s.sampleAt = s.shotAt; s.sampleFrame = FpRecoil.worldFrame();
            s.open = false;
            s.reloadOpen = false;
            s.reloadClosing = false;
            s.rackClosing = false;
            s.reloadOpenFrac = 0;
            s.breakopenTrack = null;
            s.pending = r.weapon.boltCatch();
            s.pendingFrame = FpRecoil.worldFrame();
        } catch (Throwable e) { reject(c, null, "shot", e); }
    }

    private static Model loaded(String name) {
        return loaded(name, null);
    }

    /**
     * Immersive Snow compatibility (bug wave W7, 25.10.2026): while the snow mod draws its copy of our root model
     * (an isnow_item_&lt;stage&gt; shader), the split receiver and its moving parts must be loaded with that same shader,
     * or the drawn receiver loses the snow the mod put on the rest of it.
     */
    private static String snowShader(ModelInstance root) {
        String shader = root == null || root.modelScript == null ? null : root.modelScript.getShaderName();
        return shader != null && (shader.startsWith("isnow_item_") || shader.startsWith("isnow_itemd_")) ? shader : null;
    }

    private static Model loaded(String name, String shaderOverride) {
        if (name == null || name.isEmpty()) return null;
        ModelScript script = ScriptManager.instance.getModelScript("EscapeFromZomboidFirearms." + name);
        if (script == null) throw new DataFailure("missing mechanical model " + name);
        ModelManager mm = ModelManager.instance;
        if (mm == null || script.getMeshName() == null) return null;
        String shader = shaderOverride == null ? script.getShaderName() : shaderOverride;
        Model model = mm.tryGetLoadedModel(script.getMeshName(), script.getTextureName(), true, shader, false);
        if (model == null) {
            mm.loadStaticModel(script.getMeshName(), script.getTextureName(), shader);
            model = mm.getLoadedModel(script.getMeshName(), script.getTextureName(), true, shader);
        }
        return model != null && model.isReady() && model.mesh != null && model.mesh.isReady() ? model : null;
    }

    /** Publish the split only when every partition is ready; retain the whole original otherwise. */
    private static PartSplitSpec matchPartSplit(ModelScript ms) {
        if (ms == null) return null;
        String mesh = ms.getMeshName();
        String ft = ms.getScriptObjectFullType();
        for (PartSplitSpec s : PART_SPLITS) {
            if (("TW/" + s.tpl).equals(mesh)) return s;
            if (ft != null && (ft.equals(MOD_PREFIX + "M_" + s.tpl) || ft.startsWith(MOD_PREFIX + "M_" + s.tpl + "_"))) return s;
        }
        return null;
    }

    private static void installPartSplits(ModelInstance root, String snow) {
        for (var e : new ArrayList<>(partSplits.entrySet())) {
            ModelInstance sub = e.getKey();
            PartSplit ps = e.getValue();
            if (ps.root != root) continue;
            if (root.sub == null || !root.sub.contains(sub) || sub.model != ps.replacement) {
                partSplits.remove(sub);
                rollbackPartSplit(sub, ps);
            } else if (!java.util.Objects.equals(sub.attachmentNameParent, ps.anchor)) {
                ModelInstance hc = ps.handleChild.get();
                if (hc != null && root.sub.contains(hc)) hc.attachmentNameParent = sub.attachmentNameParent;
                partSplits.put(sub, new PartSplit(root, ps.original, ps.replacement, sub.attachmentNameParent, ps.handleChild));
            }
        }
        if (root.sub == null) return;
        for (ModelInstance sub : root.sub.toArray(ModelInstance[]::new)) {
            if (sub == null || sub.model == null || !sub.model.isReady() || partSplits.containsKey(sub)) continue;
            PartSplitSpec spec = matchPartSplit(sub.modelScript);
            if (spec == null) continue;
            Model body = loaded(spec.bodyModel, snow);
            Model handle = loaded(spec.handleModel, snow);
            if (body == null || handle == null) continue;
            ModelInstance child = ModelManager.instance.addStatic(root, MOD_PREFIX + spec.handleModel, sub.attachmentNameParent, sub.attachmentNameSelf);
            if (child == null) throw new IllegalStateException("mechanical part handle unavailable after preload");
            if (snow != null) child.model = handle;
            Object owner = root.getOwner();
            if (owner != null) child.setOwner(owner);
            partSplits.put(sub, new PartSplit(root, sub.model, body, sub.attachmentNameParent, new WeakReference<>(child)));
            sub.model = body;
            installed++;
        }
    }

    private static void install(ModelInstance root, MovingWeaponPartsData.Weapon w, String snow) {
        if (root == null || w == null) return;
        installPartSplits(root, snow);
        Split old = splits.get(root);
        if (old != null && root.model == old.replacement) return;
        if (w.body().isEmpty() || root.model == null || !root.model.isReady()) return;
        Model body = loaded(w.body(), snow);
        boolean all = body != null;
        for (MovingWeaponPartsData.Node node : w.nodes().values()) if (!node.model().isEmpty()) all &= loaded(node.model(), snow) != null;
        if (!all) return;
        List<WeakReference<ModelInstance>> children = new ArrayList<>();
        Model original = root.model;
        // Register rollback before the first allocation, including a partial failure.
        splits.put(root, new Split(original, body, children));
        for (MovingWeaponPartsData.Node node : w.nodes().values()) if (!node.model().isEmpty()) {
            ModelInstance child = ModelManager.instance.addStatic(root, "EscapeFromZomboidFirearms." + node.model(), null, null);
            if (child == null) throw new IllegalStateException("mechanical child unavailable after preload");
            // the same snow on the moving parts: the child's own script keeps its name (it is matched every frame)
            if (snow != null) {
                Model shaded = loaded(node.model(), snow);
                if (shaded != null) child.model = shaded;
            }
            children.add(new WeakReference<>(child));
            Object owner = root.getOwner();
            if (owner != null) child.setOwner(owner);
        }
        root.model = body;
        installed++;
    }

    @Override
    public double[] local(ModelInstance instance) {
        try { return localNow(instance); }
        catch (Throwable cause) { failed(instance, cause); return null; }
    }

    @Override
    public void failed(Throwable cause) { reject(null, null, "render", cause); }

    @Override
    public boolean failed(ModelInstance instance, Throwable cause) {
        reject(instance == null ? null : instance.character, instance, "render", cause);
        return !enabled;
    }

    private static final String MOD_PREFIX = "EscapeFromZomboidFirearms.";
    private static final Map<String, String> HANDLE_MODEL_BONES = Map.of(
            MOD_PREFIX + "MB_62811cd7308cb521f87a8f99_handle", "weapon_bolt_rotation 1",
            MOD_PREFIX + "MB_673cb4054ff4aa8f86076f4a_handle", "mod_charge_axis"
    );

    private static synchronized double[] localNow(ModelInstance mi) {
        if (!enabled || mi == null || mi.modelScript == null) return null;
        IsoGameCharacter owner = mi.character;
        if (owner == null) {
            ModelInstance p = mi.parent;
            for (int i = 0; p != null && i < 16; i++, p = p.parent) {
                if (p.character != null) { owner = p.character; break; }
            }
        }
        if (owner == null) return null;
        Request r = requests.get(owner);
        if (r == null || owner.getPrimaryHandItem() != r.item || FpRecoil.worldFrame() > r.frame + 3) return null;
        ModelInstance root = owner.primaryHandModel;
        if (root == null || mi == root) return null;
        // Restrict to the held weapon subtree, never clothing, holsters or a window preview.
        ModelInstance parent = mi;
        for (int i = 0; parent != null && i < 16 && parent != root; i++) parent = parent.parent;
        if (parent != root) return null;
        String model = mi.modelScript.getScriptObjectFullType();
        if (model == null) return null;
        String bone = null;
        double[] placement = FpPose.identity();
        for (MovingWeaponPartsData.Node n : r.weapon.nodes().values()) {
            // model.equals("EscapeFromZomboidFirearms." + n.model()) without building the name for every node and model
            if (!n.model().isEmpty() && model.length() == MOD_PREFIX.length() + n.model().length()
                    && model.startsWith(MOD_PREFIX) && model.endsWith(n.model())) { bone = n.name(); break; }
        }
        if (bone == null && mi.parent == root && mi.attachmentNameParent != null && mi.attachmentNameParent.startsWith(CHAMBER)) {
            return chamberRound(mi, r);
        }
        String handleBone = bone == null ? HANDLE_MODEL_BONES.get(model) : null;
        if (handleBone != null) {
            Binding b = mi.attachmentNameParent == null ? null : r.bindings.get(mi.attachmentNameParent);
            if (b == null) return null;
            bone = handleBone;
            placement = b.placement;
        } else if (bone == null) {
            Binding b = mi.attachmentNameParent == null ? null : r.bindings.get(mi.attachmentNameParent);
            if (b == null) return null;
            for (var slot : r.weapon.slots().entrySet()) {
                if (b.path.equals(slot.getKey()) || b.path.startsWith(slot.getKey() + "/")) { bone = slot.getValue(); break; }
            }
            placement = b.placement;
        }
        if (bone == null) return null;
        State state = states.get(r.item);
        if (state == null) return null;
        double[] delta = delta(owner, r, state, bone);
        if (delta == null) return null;
        MovingWeaponPartsData.Node node = r.weapon.nodes().get(bone);
        if (node == null) return null;
        double[] change = GripIK.mulNew(GripIK.mulNew(node.bind(), delta), GripIK.inverse(node.bind()));
        rendered++;
        return GripIK.mulNew(GripIK.mulNew(GripIK.inverse(placement), change), placement);
    }

    /** The chambered round's node attachments on the weapon (TW_CartridgeVisual: tw_chamber_1..n). */
    static final String CHAMBER = "tw_chamber_";
    /** Tarkov's chamber nodes (tools/moving_weapon_parts.py: patron_in_weapon[_NNN], binds = the round mounts). */
    static final String PATRON = "patron_in_weapon";
    /** A round's mount and its chamber node's bind agree to the attachment's float rounding (model units). */
    static final double ROUND_MATCH = 1e-4;
    /**
     * A clip carries the chambered round as a chamber check does when it moves it along the bore (ROUND_AXIAL of the
     * way) and either starts it in the chamber and draws it no farther than ROUND_MAX (model units) - Tarkov's checks
     * carry it straight back 1.3-5.3 cm, the ASH-12's 12.7 mm round the farthest - or holds it aside at both ends of the
     * clip (the MP5's and the AK-50's checks keep it at the feed, 4.4 and 10.6 cm off, and bring it within ROUND_MAX of
     * the chamber while the action is open). Not a check: a charge draws it 7.7 cm, a feed brings a round from the
     * magazine (it starts 4-8 cm off and ends in the chamber), a hand loading or unloading carries one 22-73 cm, and the
     * MCX's .300 clips hold it 1.33 cm aside of the 5.56 idle.
     */
    static final double ROUND_START = .001 * GripIK.U, ROUND_MIN = .0005 * GripIK.U, ROUND_MAX = .06 * GripIK.U, ROUND_AXIAL = .9;
    public static long chamberMoves, transfers;
    /** QA: how far the last chamber check carried the round from the chamber (cm, the weapon's frame). */
    public static double chamberCm;
    /** QA: the node whose track carried it (patron_in_weapon: the round's own curve; a bolt: FB5's stand-in). */
    public static String chamberBone = "";
    /** QA (wave 1488): the carried round's base (the weapon's model frame, model units) and its weapon's key. */
    public static volatile double[] chamberAt;
    public static volatile String chamberKey = "";

    /**
     * FB5 item 1 (20.10.2026) and wave 1488 (21.10.2026, "the chamber of a loaded weapon is still empty"): the round
     * in the chamber moves with a chamber check as Tarkov moves it. Tarkov's clips animate the round's own node
     * (patron_in_weapon: the AK's check carries it back 2.90 cm, the M4A1's 3.57, the MCX's 3.29, the Glock's 1.61),
     * so it follows that curve of the playing track - this weapon's own clip, or the clip of the family whose hands
     * play (resolve) - as a part on its slot (its attachment placement in the weapon's frame), whenever the clip
     * carries the round (carriesRound). A clip without that curve, or one that brings the round from elsewhere (the
     * MP5's check starts it 4.4 cm off the chamber), keeps FB5's stand-in on a chamber check: the bolt head, else the
     * bolt. Other actions and the fire cycle keep the round in the chamber.
     */
    private static double[] chamberRound(ModelInstance mi, Request r) {
        BreakopenSpec bspec = BREAKOPEN_SPECS.get(r.weapon.key());
        if (bspec != null && !boltOpenEnabled && BOLT_OPEN_ROUND_KEYS.contains(r.weapon.key())) bspec = null;
        if (bspec != null && bspec.chamberParentBone() != null) {
            State bstate = states.get(r.item);
            double[] placement = attached(mi);
            MovingWeaponPartsData data = MovingWeaponPartsData.get();
            Map<String, MovingWeaponPartsData.Track> tracks = data == null || r.weapon.family() == null ? null : data.families.get(r.weapon.family());
            FpPose.Request bpose = FpPose.enabled ? FpPose.requests.get(mi.character) : null;
            double[] bdelta = bstate == null || placement == null || tracks == null ? null
                    : breakopenDelta(mi.character, r, bstate, bspec, tracks, bpose, bspec.chamberParentBone());
            MovingWeaponPartsData.Node parentNode = bdelta == null ? null : r.weapon.nodes().get(bspec.chamberParentBone());
            if (bdelta != null && parentNode != null) {
                double[] change = GripIK.mulNew(GripIK.mulNew(parentNode.bind(), bdelta), GripIK.inverse(parentNode.bind()));
                chamberMoves++;
                chamberBone = bspec.chamberParentBone();
                chamberCm = pivotTravel(change, placement) / GripIK.CM;
                chamberAt = new double[] {change[0] * placement[3] + change[1] * placement[7] + change[2] * placement[11] + change[3],
                        change[4] * placement[3] + change[5] * placement[7] + change[6] * placement[11] + change[7],
                        change[8] * placement[3] + change[9] * placement[7] + change[10] * placement[11] + change[11]};
                chamberKey = r.weapon.key();
                return GripIK.mulNew(GripIK.mulNew(GripIK.inverse(placement), change), placement);
            }
        }
        FpPose.Request pose = FpPose.enabled ? FpPose.requests.get(mi.character) : null;
        if (pose == null || pose.item != r.item || pose.fpW <= .5 || pose.action == null || pose.actionW <= 1e-3
                || pose.action.name == null) return null;
        State state = states.get(r.item);
        double[] placement = attached(mi);
        Resolved playing = resolve(MovingWeaponPartsData.get(), r.weapon, pose.action.name);
        if (state == null || placement == null || playing == null) return null;
        String bone = roundBone(r.weapon, playing.track(), chamberNode(r.weapon, placement), pose.action.name);
        double[] delta = bone == null ? null : delta(mi.character, r, state, bone);
        MovingWeaponPartsData.Node node = bone == null ? null : r.weapon.nodes().get(bone);
        if (delta == null || node == null) return null;
        double[] change = GripIK.mulNew(GripIK.mulNew(node.bind(), delta), GripIK.inverse(node.bind()));
        chamberMoves++;
        chamberBone = bone;
        chamberCm = pivotTravel(change, node.bind()) / GripIK.CM;
        double[] b = placement;
        chamberAt = new double[] {change[0] * b[3] + change[1] * b[7] + change[2] * b[11] + change[3],
                change[4] * b[3] + change[5] * b[7] + change[6] * b[11] + change[7], change[8] * b[3] + change[9] * b[7] + change[10] * b[11] + change[11]};
        chamberKey = r.weapon.key();
        return GripIK.mulNew(GripIK.mulNew(GripIK.inverse(placement), change), placement);
    }

    /**
     * The node whose curve carries the round: its own chamber node when the clip carries the round there, else on a
     * chamber check FB5's stand-in (the bolt head, else the bolt), else none (the round stays in the chamber).
     */
    static String roundBone(MovingWeaponPartsData.Weapon w, MovingWeaponPartsData.Track track, String chamber, String action) {
        if (w == null || track == null) return null;
        if (chamber != null && carriesRound(track, chamber)) return chamber;
        if (action == null || !action.contains("check_chamber")) return null;
        return w.nodes().containsKey("weapon_bolt_larva") ? "weapon_bolt_larva" : w.nodes().containsKey("weapon_bolt") ? "weapon_bolt" : null;
    }

    /** The clip moves the round along the bore as a chamber check does (ROUND_START... above). */
    static boolean carriesRound(MovingWeaponPartsData.Track track, String bone) {
        double start = track.start(bone), most = track.travel(bone);
        double[] ends = track.returnAndNearest(bone);
        if (most < ROUND_MIN || track.axialShare(bone) < ROUND_AXIAL) return false;
        return start <= ROUND_START ? most <= ROUND_MAX : ends[0] <= ROUND_START && ends[1] <= ROUND_MAX;
    }

    /** How far a model-space change moves a node's pivot (its bind's origin; model units): the round's base for a chamber node. */
    static double pivotTravel(double[] change, double[] bind) {
        double x = bind[3], y = bind[7], z = bind[11];
        double dx = change[0] * x + change[1] * y + change[2] * z + change[3] - x;
        double dy = change[4] * x + change[5] * y + change[6] * z + change[7] - y;
        double dz = change[8] * x + change[9] * y + change[10] * z + change[11] - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The weapon's chamber node at a round's mount (its placement in the weapon's frame): the patron bind there. */
    static String chamberNode(MovingWeaponPartsData.Weapon w, double[] placement) {
        if (w == null || placement == null) return null;
        String best = null;
        double nearest = ROUND_MATCH;
        for (MovingWeaponPartsData.Node n : w.nodes().values()) {
            if (!n.name().startsWith(PATRON)) continue;
            double[] b = n.bind();
            double dx = b[3] - placement[3], dy = b[7] - placement[7], dz = b[11] - placement[11];
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < nearest) { nearest = d; best = n.name(); }
        }
        return best;
    }

    /** The track a weapon plays for a first-person action, the family it belongs to, and that family's frames (null: own). */
    record Resolved(MovingWeaponPartsData.Track track, String family, MovingWeaponPartsData.Weapon reference) { }

    /**
     * Wave 1488: the first-person track of an action for this weapon. Its own family's clip of that name; else - the
     * hands play another family's clip (tools/weapon_inspect.py REFERENCE_TRANSFER: the chamber checks of 107 weapons,
     * the reload sequences of transferred rifles) - that family's track, unless the family keeps its own check clip
     * under that name (an alias: the hands' clip would leave its carrier or slide still). An own track that never
     * moves (an override controller whose curves do not bind to the rig: HK416A5, ADAR, TX-15, Glock 19X) yields to a
     * moving one of the same name. Several owners: the one that moves the most. Null without any.
     */
    static Resolved resolve(MovingWeaponPartsData data, MovingWeaponPartsData.Weapon w, String name) {
        if (data == null || w == null || name == null) return null;
        Map<String, MovingWeaponPartsData.Track> own = w.family() == null ? null : data.families.get(w.family());
        String alias = data.aliases.getOrDefault(w.family(), Map.of()).get(name);
        MovingWeaponPartsData.Track mine = own == null ? null : own.get(alias != null ? alias : name);
        if (mine != null && mine.moving()) return new Resolved(mine, w.family(), null);
        Resolved best = null;
        for (String family : data.owners.getOrDefault(name, List.of())) {
            if (family.equals(w.family())) continue;
            MovingWeaponPartsData.Track t = data.families.get(family).get(name);
            MovingWeaponPartsData.Weapon reference = data.references.get(family);
            if (t != null && reference != null && t.moving() && (best == null || t.motion() > best.track().motion())) {
                best = new Resolved(t, family, reference);
            }
        }
        return best != null ? best : mine == null ? null : new Resolved(mine, w.family(), null);
    }

    /**
     * Another family's bone delta onto this weapon's node of the same name: the motion in model space as the clip moves
     * the reference weapon's node (its orientation), about this weapon's own pivot - with Q from this node's frame to
     * the reference's, bind (Q delta Q^-1) bind^-1 = [R_ref | p] delta [R_ref | p]^-1. The same delta when the frames agree.
     */
    static double[] mapped(double[] delta, MovingWeaponPartsData.Weapon w, MovingWeaponPartsData.Weapon reference, String bone) {
        if (delta == null || w == null || reference == null || reference == w) return delta;
        MovingWeaponPartsData.Node mine = w.nodes().get(bone), theirs = reference.nodes().get(bone);
        if (mine == null || theirs == null) return delta;
        double[] q = GripIK.mulNew(GripIK.inverse(rotation(mine.bind())), rotation(theirs.bind()));
        return GripIK.mulNew(GripIK.mulNew(q, delta), GripIK.inverse(q));
    }

    private static double[] rotation(double[] m) {
        double[] r = m.clone();
        r[3] = r[7] = r[11] = 0;
        return r;
    }

    /** A child's model frame in its parent's: the parent's node attachment by its own (self) one, as the engine composes them. */
    static double[] attached(ModelInstance mi) {
        var parentScript = mi.parent == null ? null : mi.parent.modelScript;
        var node = parentScript == null ? null : parentScript.getAttachmentById(mi.attachmentNameParent);
        var self = mi.modelScript == null || mi.attachmentNameSelf == null ? null : mi.modelScript.getAttachmentById(mi.attachmentNameSelf);
        if (node == null) return null;
        org.joml.Matrix4f a = zombie.core.skinnedmodel.model.ModelInstanceRenderData.makeAttachmentTransform(node, new org.joml.Matrix4f());
        if (self != null) {
            org.joml.Matrix4f s = zombie.core.skinnedmodel.model.ModelInstanceRenderData.makeAttachmentTransform(self, new org.joml.Matrix4f());
            if (zombie.core.skinnedmodel.model.ModelInstanceRenderData.invertAttachmentSelfTransform) s.invert();
            a.mul(s);
        }
        if (!a.isFinite()) return null;
        double[] out = new double[16];
        for (int i = 0; i < 4; i++) for (int j = 0; j < 4; j++) out[i * 4 + j] = a.get(j, i);
        return out;
    }

    private static double[] delta(IsoGameCharacter c, Request r, State state, String bone) {
        if (c == null || r == null || state == null || bone == null) return null;
        MovingWeaponPartsData data = MovingWeaponPartsData.get();
        if (data == null) { disable(new DataFailure(MovingWeaponPartsData.status)); return null; }
        Map<String, MovingWeaponPartsData.Track> tracks = r.weapon.family() == null ? null : data.families.get(r.weapon.family());
        FpPose.Request pose = FpPose.enabled ? FpPose.requests.get(c) : null;
        if (state.qaTrack != null) {
            MovingWeaponPartsData.Track tr = tracks == null ? null : tracks.get(state.qaTrack);
            state.mode = "qa"; state.action = state.qaTrack; state.u = state.qaU; state.weight = 1;
            return tr == null ? null : tr.sample(bone, state.qaU);
        }
        BreakopenSpec bspec = BREAKOPEN_SPECS.get(r.weapon.key());
        if (bspec != null && !boltOpenEnabled && BOLT_OPEN_ROUND_KEYS.contains(r.weapon.key())) bspec = null;
        if (bspec != null && tracks != null) {
            double[] bd = breakopenDelta(c, r, state, bspec, tracks, pose, bone);
            if (bd != null) return bd;
        }
        if (pose != null && pose.item == r.item && pose.fpW > .5 && pose.action != null && pose.actionW > 1e-3) {
            // Wave 1488: a clip of another family (the hands of a REFERENCE_TRANSFER weapon) is that family's track.
            Resolved playing = pose.action.name == null ? null : resolve(data, r.weapon, pose.action.name);
            state.action = pose.action.name; state.u = pose.actionU; state.weight = pose.actionW;
            if (playing != null) {
                double[] value = mapped(playing.track().sample(bone, pose.actionU), r.weapon, playing.reference(), bone);
                state.mode = "fp action";
                state.family = playing.family();
                if (playing.reference() != null) transfers++;
                // A regular magazine insertion has no bolt motion. Keep an observed
                // catch until a charge action or chambered round releases it.
                if (value != null && !(state.open && stationary(playing.track(), bone))) return FpPose.interp(FpPose.identity(), value, Math.min(1, pose.actionW));
            } else if ((pose.action.flags & FpPoseData.T_MANUAL) != 0) {
                state.mode = "fp manual";
                state.family = r.weapon.family() == null ? "" : r.weapon.family();
            } else { state.mode = "source action unavailable"; state.family = ""; unsupported++; }
        }
        // The catch, the rack and the fire cycle below are the weapon's own clips.
        if (tracks == null) return null;
        if (state.open && (bone.startsWith("weapon_bolt") || bone.equals("mod_reciever"))) {
            state.mode = "bolt catch";
            double[] held = openPose(r.weapon, tracks, bone);
            MovingWeaponPartsData.Track fire = fireTrack(tracks);
            double cycle = r.weapon.rpm() > 0 ? 60 / r.weapon.rpm() : fire == null ? 0 : fire.seconds;
            if (held != null && fire != null && state.shotAt != 0 && cycle > 0) {
                double u = shotAge(state) / cycle;
                double stop = catchFraction(fire, bone, held);
                if (u >= 0 && u < stop) return fire.sample(bone, u);
            }
            return held;
        }
        String boltRackName = boltOpenEnabled ? BOLT_RACK_TRACKS.get(r.weapon.key()) : null;
        if (boltRackName != null && safeVarBool(c, "isRacking")) {
            AnimationTrack br = boltRack(c);
            MovingWeaponPartsData.Track btr = br == null ? null : tracks.get(boltRackName);
            if (btr != null) {
                state.shotAt = 0;
                state.mode = "source bolt rack"; state.action = btr.name;
                state.family = r.weapon.family();
                state.u = pose != null && pose.item == r.item && pose.fpW > .5 && pose.action != null && pose.actionW > .5
                        ? pose.actionU : Math.max(0, Math.min(1, br.getCurrentTimeFraction()));
                state.weight = Math.min(1, br.getBlendWeight());
                return btr.sample(bone, state.u);
            }
        }
        AnimationTrack rack = rack(c);
        // PZ third-person hands do not share the source grip/window. Keep them
        // stationary until an independently measured projection is available.
        if (rack != null && pose != null && pose.item == r.item && pose.fpW > .5) {
            MovingWeaponPartsData.Track tr = rackTrack(tracks);
            if (tr != null) {
                state.mode = "source rack on PZ clock"; state.action = tr.name;
                state.u = pose != null && pose.item == r.item && pose.action != null && pose.actionW > .5
                        ? pose.actionU : rack.getCurrentTimeFraction();
                state.weight = Math.min(1, rack.getBlendWeight());
                return tr.sample(bone, state.u);
            }
        }
        if (state.shotAt != 0 && !r.weapon.pump()) {
            MovingWeaponPartsData.Track fire = fireTrack(tracks);
            double age = shotAge(state);
            double cycle = r.weapon.rpm() > 0 ? 60 / r.weapon.rpm() : fire == null ? 0 : fire.seconds;
            if (fire != null && age >= 0 && age <= cycle) {
                state.mode = "source fire"; state.action = fire.name; state.u = age / cycle; state.weight = 1;
                return fire.sample(bone, state.u);
            }
        }
        state.mode = "idle";
        return null;
    }

    static double rotDeg(double[] m) {
        if (m == null) return 0;
        double c = Math.max(-1.0, Math.min(1.0, (m[0] + m[5] + m[10] - 1.0) * 0.5));
        return Math.toDegrees(Math.acos(c));
    }

    private static double openness(BreakopenSpec spec, double[] d) {
        if (spec == null || d == null) return 0;
        double f = spec.maxDeg > 1 ? Math.min(1.0, rotDeg(d) / spec.maxDeg) : 0;
        if (spec.maxTransUnits > 1e-5) {
            double t = Math.sqrt(d[3] * d[3] + d[7] * d[7] + d[11] * d[11]);
            f = Math.max(f, Math.min(1.0, t / spec.maxTransUnits));
        }
        return f;
    }

    private static double closeStartFraction(BreakopenSpec spec, MovingWeaponPartsData.Track closeTr, double targetFrac) {
        if (targetFrac >= 0.94 || closeTr == null) return 0;
        float[] frames = closeTr.bones.get(spec.hingeBone);
        if (frames == null) return 1.0 - Math.max(0, Math.min(1, targetFrac));
        int n = frames.length / 12;
        if (n < 2) return 0;
        for (int i = 0; i < n; i++) {
            if (openness(spec, MovingWeaponPartsData.Track.frame(frames, i)) <= targetFrac) {
                return i / (double) (n - 1);
            }
        }
        return 0.85;
    }

    private static boolean safeVarBool(IsoGameCharacter c, String key) {
        try { return c != null && c.getVariableBoolean(key); }
        catch (Throwable ignored) { return false; }
    }

    private static double safeVarFloat(IsoGameCharacter c, String key, float fallback) {
        try { return c == null ? fallback : c.getVariableFloat(key, fallback); }
        catch (Throwable ignored) { return fallback; }
    }

    private static AnimationPlayer safeAnimPlayer(IsoGameCharacter c) {
        if (c == null) return null;
        if (c.primaryHandModel != null && c.primaryHandModel.animPlayer != null) return c.primaryHandModel.animPlayer;
        try { return c.getAnimationPlayer(); }
        catch (Throwable ignored) { return null; }
    }

    private static double[] breakopenDelta(IsoGameCharacter c, Request r, State state, BreakopenSpec spec,
                                           Map<String, MovingWeaponPartsData.Track> tracks, FpPose.Request pose, String bone) {
        long wf = FpRecoil.worldFrame();
        if (wf == 0 || state.breakopenFrame != wf) {
            state.breakopenFrame = wf;
            updateBreakopenState(c, r, state, spec, tracks, pose);
        }
        if (state.breakopenTrack == null) return null;
        state.mode = state.breakopenMode;
        state.action = state.breakopenTrack.name;
        state.family = r.weapon.family();
        state.u = state.breakopenU;
        state.weight = state.breakopenW;
        return state.breakopenTrack.sample(bone, state.breakopenU);
    }

    static boolean fpBreakopenActionMatches(String actionName, boolean loading, boolean unloading, boolean racking) {
        if (actionName == null || !(loading || unloading || racking)) return false;
        boolean isClose = actionName.contains("mag_in_end") || actionName.contains("reload_end")
                || actionName.endsWith(":Close") || actionName.endsWith(":Rack");
        if (isClose) return racking || unloading;
        if (actionName.contains("ammo_out") || actionName.contains("ammo_extract") || actionName.contains("shell_out")
                || actionName.endsWith(":Unload") || actionName.endsWith(":Dump")) {
            return unloading;
        }
        return loading && (actionName.startsWith("twround:") || actionName.startsWith("twl:")
                || actionName.contains("mag_out") || actionName.contains("mag_in") || actionName.contains("reload"));
    }

    static boolean multiTrackActionMatches(BreakopenSpec spec, Matcher mr, Matcher ml,
                                           boolean loading, boolean unloading, boolean racking) {
        if (spec == null || !(loading || unloading || racking)) return false;
        if (mr != null && mr.matches()) {
            if (!mr.group(1).equals(spec.roundPrefix)) return false;
            String act = mr.group(2);
            if ("Close".equals(act)) return racking;
            if ("Unload".equals(act)) return unloading;
            return loading;
        }
        if (ml != null && ml.matches()) {
            if (!ml.group(1).equals(spec.launcherPrefix)) return false;
            String act = ml.group(2);
            if ("Close".equals(act) || "Rack".equals(act)) return racking;
            if ("Unload".equals(act) || "Dump".equals(act)) return unloading;
            return loading;
        }
        return false;
    }

    private static void updateBreakopenState(IsoGameCharacter c, Request r, State state, BreakopenSpec spec,
                                             Map<String, MovingWeaponPartsData.Track> tracks, FpPose.Request pose) {
        MovingWeaponPartsData.Track active = null;
        double activeU = 0;
        boolean isClosing = false;

        boolean racking = safeVarBool(c, "isRacking");
        boolean unloading = safeVarBool(c, "isUnloading");
        boolean loading = safeVarBool(c, "isLoading");
        boolean boltRound = BOLT_OPEN_ROUND_KEYS.contains(r.weapon.key());
        if (!racking) state.rackClosing = false;

        if (pose != null && pose.item == r.item && pose.fpW > 0.5 && pose.action != null
                && pose.actionW > 1e-3 && fpBreakopenActionMatches(pose.action.name, loading, unloading, racking)) {
            MovingWeaponPartsData.Track fpTr = tracks.get(pose.action.name);
            if (fpTr != null) {
                active = fpTr;
                activeU = Math.max(0, Math.min(1, pose.actionU));
                String nm = pose.action.name;
                isClosing = nm.contains("mag_in_end") || nm.contains("reload_end") || nm.endsWith(":Close") || nm.endsWith(":Rack");
            }
        }

        AnimationPlayer ap = safeAnimPlayer(c);

        if (active == null && (loading || unloading || racking) && ap != null && ap.getMultiTrack() != null) {
            AnimationTrack best = null;
            Matcher bestRound = null, bestLauncher = null;
            for (AnimationTrack t : ap.getMultiTrack().getTracks()) {
                if (t == null || t.getClip() == null || t.getClip().name == null) continue;
                String cn = t.getClip().name;
                Matcher mr = TWROUND_CLIP.matcher(cn);
                Matcher ml = mr.matches() ? null : TWL_CLIP.matcher(cn);
                if (!multiTrackActionMatches(spec, mr, ml, loading, unloading, racking)) continue;
                String candAct = mr.matches() ? mr.group(2) : ml.group(2);
                boolean candClose = "Close".equals(candAct) || "Rack".equals(candAct);
                float minWeight = (racking && candClose) ? 0.01f : 0.05f;
                if (t.getBlendWeight() <= minWeight) continue;
                if (best == null) {
                    best = t; bestRound = mr.matches() ? mr : null; bestLauncher = ml;
                } else {
                    boolean curClose = (bestRound != null && "Close".equals(bestRound.group(2)))
                            || (bestLauncher != null && ("Close".equals(bestLauncher.group(2)) || "Rack".equals(bestLauncher.group(2))));
                    boolean preferIncoming = best.getCurrentTimeFraction() > 0.92f && t.getCurrentTimeFraction() < 0.50f && t.getBlendWeight() > 0.15f;
                    if ((racking && candClose && !curClose) || preferIncoming
                            || (!curClose && t.getBlendWeight() > best.getBlendWeight())) {
                        best = t; bestRound = mr.matches() ? mr : null; bestLauncher = ml;
                    }
                }
            }
            if (best != null) {
                String cn = best.getClip().name;
                activeU = Math.max(0, Math.min(1, best.getCurrentTimeFraction()));
                if (bestRound != null) {
                    String rfam = bestRound.group(1), act = bestRound.group(2);
                    if (("Open".equals(act) || "Loop".equals(act)) && spec.chambers > 1
                            && ("rhino".equals(rfam) || "rsh12".equals(rfam) || "mc255".equals(rfam))) {
                        if (!cn.equals(state.reloadRawClip) || activeU < state.reloadLastU - 0.25) {
                            state.reloadChamber = Math.max(0, Math.min(spec.chambers - 1, r.item.getCurrentAmmoCount()));
                        }
                        active = tracks.get("twround:" + rfam + ":" + act + "_" + state.reloadChamber);
                    }
                    if (active == null) active = tracks.get("twround:" + rfam + ":" + act);
                    isClosing = "Close".equals(act);
                } else if (bestLauncher != null) {
                    String lfam = bestLauncher.group(1), act = bestLauncher.group(2);
                    active = tracks.get("twl:" + lfam + ":" + act);
                    isClosing = "Close".equals(act) || "Rack".equals(act) || ("fn40gl".equals(lfam) && "Unload".equals(act));
                }
                state.reloadRawClip = cn;
                state.reloadLastU = activeU;
            }
        }

        if (active == null && (unloading || loading || racking) && ap != null && ap.getMultiTrack() != null) {
            boolean allowBoltRackClose = !boltRound || state.reloadOpen || state.rackClosing;
            AnimationTrack vBest = null;
            for (AnimationTrack t : ap.getMultiTrack().getTracks()) {
                if (t == null || t.getClip() == null || t.getClip().name == null || t.getBlendWeight() <= 0.05f) continue;
                String cn = t.getClip().name;
                boolean boltRackClip = racking && allowBoltRackClose
                        && (cn.contains("Reload_Bolt_Rack") || cn.contains("AimBoltRack"));
                if (cn.contains("Reload_DBShotgun") || cn.contains("Reload_Revolver")
                        || cn.contains("Reload_Lever") || cn.contains("Reload_Shotgun") || cn.contains("BreakAction")
                        || boltRackClip) {
                    if (vBest == null || t.getBlendWeight() > vBest.getBlendWeight()) vBest = t;
                }
            }
            if (vBest != null) {
                activeU = Math.max(0, Math.min(1, vBest.getCurrentTimeFraction()));
                if (spec.roundPrefix != null) {
                    if (unloading) active = tracks.get("twround:" + spec.roundPrefix + ":Unload");
                    else if (racking && allowBoltRackClose) {
                        if (boltRound) state.rackClosing = true;
                        active = tracks.get(spec.closeTrack);
                        isClosing = true;
                    } else if (spec.chambers > 1 && ("rhino".equals(spec.roundPrefix) || "rsh12".equals(spec.roundPrefix) || "mc255".equals(spec.roundPrefix))) {
                        int k = Math.max(0, Math.min(spec.chambers - 1, r.item.getCurrentAmmoCount()));
                        active = tracks.get("twround:" + spec.roundPrefix + ":Open_" + k);
                    } else if (loading) {
                        active = tracks.get("twround:" + spec.roundPrefix + ":Load");
                        if (active == null) active = tracks.get("twround:" + spec.roundPrefix + (state.reloadOpen ? ":Loop" : ":Open"));
                    }
                } else if (spec.launcherPrefix != null) {
                    if (unloading) active = tracks.get("twl:" + spec.launcherPrefix + ("m32a1".equals(spec.launcherPrefix) ? ":Dump" : ":Unload"));
                    else if (racking) { active = tracks.get(spec.closeTrack); isClosing = true; }
                    else active = tracks.get("m32a1".equals(spec.launcherPrefix) ? "twl:m32a1:First0" : "twl:fn40gl:Load");
                }
            }
        }

        if (active != null) {
            state.reloadClosing = false;
            state.breakopenTrack = active;
            state.breakopenU = activeU;
            state.breakopenW = 1.0;
            state.breakopenMode = "reload_breakopen";
            double frac = openness(spec, active.sample(spec.hingeBone, activeU));
            if (frac > 0.04) {
                state.reloadOpen = true;
                state.reloadOpenFrac = frac;
            } else if (isClosing && activeU > 0.70) {
                state.reloadOpen = false;
                state.reloadOpenFrac = 0;
            }
            return;
        }

        state.reloadRawClip = "";
        state.reloadLastU = 0;
        if (state.reloadOpen) {
            MovingWeaponPartsData.Track closeTr = tracks.get(spec.closeTrack);
            if (closeTr == null || closeTr.seconds <= 1e-4) {
                state.reloadOpen = false;
                state.reloadClosing = false;
                state.reloadOpenFrac = 0;
                state.breakopenTrack = null;
                return;
            }
            long nowNs = System.nanoTime();
            if (!state.reloadClosing) {
                state.reloadClosing = true;
                state.closeStartNs = nowNs;
                state.closeStartU = closeStartFraction(spec, closeTr, state.reloadOpenFrac);
            }
            double speed = Math.max(0.25, safeVarFloat(c, "ReloadSpeed", 1.0f));
            double elapsed = Math.max(0.0, (nowNs - state.closeStartNs) / 1e9) * speed;
            double rem = Math.max(0.08, closeTr.seconds);
            double cu = state.closeStartU + elapsed / rem;
            if (cu >= 1.0) {
                state.reloadOpen = false;
                state.reloadClosing = false;
                state.reloadOpenFrac = 0;
                state.breakopenTrack = null;
            } else {
                state.breakopenTrack = closeTr;
                state.breakopenU = cu;
                state.breakopenW = 1.0;
                state.breakopenMode = "reload_close";
            }
        } else {
            state.reloadClosing = false;
            state.breakopenTrack = null;
        }
    }

    // A family's track maps are immutable (MovingWeaponPartsData: Map.copyOf, loaded once): its fire and rack
    // tracks are chosen once per map (performance pass 23.10.2026: the stream, sort and String.matches ran for
    // every moving part every frame after the first shot). The same filters and orders choose them.
    private static final Pattern FIRE = Pattern.compile("clip:.*_fire(?:_0)?");
    private static final Pattern RACK = Pattern.compile("clip:.*_(?:reload_charge(?:_to_idle)?(?:_0)?|rechamber(?:_0)?)");
    private static final Object NONE = new Object();
    private static final Map<Map<String, MovingWeaponPartsData.Track>, Object> fireTracks = new IdentityHashMap<>(), rackTracks = new IdentityHashMap<>();

    private static MovingWeaponPartsData.Track memo(Map<Map<String, MovingWeaponPartsData.Track>, Object> memo,
                                                    Map<String, MovingWeaponPartsData.Track> tracks,
                                                    java.util.function.Function<Map<String, MovingWeaponPartsData.Track>, MovingWeaponPartsData.Track> choose) {
        if (tracks == null) return null;
        if (PerfLegacy.on) return choose.apply(tracks);
        synchronized (memo) {
            Object chosen = memo.get(tracks);
            if (chosen == null) { MovingWeaponPartsData.Track t = choose.apply(tracks); chosen = t == null ? NONE : t; memo.put(tracks, chosen); }
            return chosen == NONE ? null : (MovingWeaponPartsData.Track) chosen;
        }
    }

    static MovingWeaponPartsData.Track fireTrack(Map<String, MovingWeaponPartsData.Track> tracks) {
        return memo(fireTracks, tracks, t -> t.entrySet().stream().filter(e -> FIRE.matcher(e.getKey()).matches())
                .sorted(Map.Entry.comparingByKey()).map(Map.Entry::getValue).findFirst().orElse(null));
    }

    private static double shotAge(State state) {
        long frame = FpRecoil.worldFrame();
        if (state.sampleFrame != frame) { state.sampleFrame = frame; state.sampleAt = System.nanoTime(); }
        return (state.sampleAt - state.shotAt) / 1e9;
    }

    /** First source-curve crossing of the source catch position, without a made-up closing curve. */
    static double catchFraction(MovingWeaponPartsData.Track fire, String bone, double[] held) {
        if (fire == null || bone == null || held == null) return 0;
        float[] frames = fire.bones.get(bone);
        if (frames == null) return 0;
        double norm = held[3] * held[3] + held[7] * held[7] + held[11] * held[11];
        if (norm < 1e-8) return 0;
        double prev = 0;
        int n = frames.length / 12;
        for (int i = 0; i < n; i++) {
            int p = i * 12;
            double dot = (frames[p + 3] * held[3] + frames[p + 7] * held[7] + frames[p + 11] * held[11]) / norm;
            if (dot >= 1) return i == 0 ? 0 : (i - 1 + (1 - prev) / (dot - prev)) / (n - 1);
            prev = dot;
        }
        return 0;
    }

    static MovingWeaponPartsData.Track rackTrack(Map<String, MovingWeaponPartsData.Track> tracks) {
        return memo(rackTracks, tracks, t -> t.entrySet().stream().filter(e -> RACK.matcher(e.getKey()).matches())
                .sorted((a, b) -> {
                    int x = a.getKey().contains("to_idle") ? 0 : a.getKey().contains("rechamber") ? 1 : 2;
                    int y = b.getKey().contains("to_idle") ? 0 : b.getKey().contains("rechamber") ? 1 : 2;
                    return x == y ? a.getKey().compareTo(b.getKey()) : Integer.compare(x, y);
                }).map(Map.Entry::getValue).findFirst().orElse(null));
    }

    private static AnimationTrack boltRack(IsoGameCharacter c) {
        AnimationPlayer ap = safeAnimPlayer(c);
        if (ap == null || ap.getMultiTrack() == null) return null;
        AnimationTrack best = null;
        for (AnimationTrack t : ap.getMultiTrack().getTracks()) {
            if (t == null || t.getClip() == null || t.getClip().name == null || t.getBlendWeight() <= 0) continue;
            String low = t.getClip().name.toLowerCase(Locale.ROOT);
            if (low.contains("rack") && !low.contains("takeoffrack")
                    && (best == null || t.getBlendWeight() > best.getBlendWeight())) best = t;
        }
        return best;
    }

    private static AnimationTrack rack(IsoGameCharacter c) {
        ModelInstance root = c.primaryHandModel;
        AnimationPlayer ap = root == null ? null : root.animPlayer;
        if (ap == null || ap.getMultiTrack() == null) return null;
        AnimationTrack best = null;
        for (AnimationTrack t : ap.getMultiTrack().getTracks()) {
            if (t != null && t.getClip() != null && t.getBlendWeight() > 0
                    && t.getClip().name.toLowerCase(Locale.ROOT).contains("rack")
                    && (best == null || t.getBlendWeight() > best.getBlendWeight())) best = t;
        }
        return best;
    }

    private static boolean stationary(MovingWeaponPartsData.Track track, String bone) {
        if (track == null || bone == null) return true;
        float[] f = track.bones.get(bone);
        if (f == null) return true;
        for (int k = 0; k < f.length / 12; k++) {
            double[] m = MovingWeaponPartsData.Track.frame(f, k);
            if (m[3] * m[3] + m[7] * m[7] + m[11] * m[11] > 1e-8) return false;
        }
        return true;
    }

    static double[] openPose(MovingWeaponPartsData.Weapon weapon, Map<String, MovingWeaponPartsData.Track> tracks, String bone) {
        if (weapon == null || tracks == null || bone == null) return null;
        String key = weapon.family() + "/" + bone;
        if (openPoses.containsKey(key)) return openPoses.get(key);
        // The start of an authored catch-release clip is its held-open pose.
        // Full charging travel can exceed that position and is not a substitute.
        double[] best = tracks.entrySet().stream().filter(e -> e.getKey().matches("clip:.*_reload_catch(?:_0)?"))
                .sorted(Map.Entry.comparingByKey()).map(e -> e.getValue().sample(bone, 0)).filter(m -> m != null
                        && m[3] * m[3] + m[7] * m[7] + m[11] * m[11] > 1e-8).findFirst().orElse(null);
        openPoses.put(key, best);
        return best;
    }

    /** Isolated QA fixture only. It changes the visual sample, never the hands, inventory or actions. */
    public static synchronized boolean qa(IsoGameCharacter c, String track, double u) {
        if (!enabled || c == null) return false;
        Request r = requests.get(c);
        if (r == null) return false;
        try {
            if (c.getPrimaryHandItem() != r.item) { off(c); return false; }
            State s = states.get(r.item);
            if (s == null) return false;
            if (track == null || track.isEmpty()) { s.qaTrack = null; s.qaU = -1; return true; }
            MovingWeaponPartsData data = MovingWeaponPartsData.get();
            if (data == null) { disable(new DataFailure(MovingWeaponPartsData.status)); return false; }
            if (r.weapon.family() == null || !data.families.getOrDefault(r.weapon.family(), Map.of()).containsKey(track) || !Double.isFinite(u)) return false;
            s.qaTrack = track; s.qaU = Math.max(0, Math.min(1, u));
            return true;
        } catch (Throwable cause) { reject(c, null, "qa", cause); return false; }
    }

    public static synchronized String describe(IsoGameCharacter c) {
        Request r = c == null ? null : requests.get(c);
        State s = r == null ? null : states.get(r.item);
        return "enabled=" + enabled + " status={" + reason + "} data={" + MovingWeaponPartsData.status + "} weapon=" + (r == null ? "none" : r.weapon.key())
                + " mode=" + (s == null ? "none" : s.mode) + " track=" + (s == null ? "none" : s.action)
                + " trackFamily=" + (s == null || s.family.isEmpty() ? "none" : s.family)
                + " u=" + (s == null ? 0 : s.u) + " weight=" + (s == null ? 0 : s.weight) + " open=" + (s != null && s.open)
                + " splits=" + installed + " rendered=" + rendered + " chamberMoves=" + chamberMoves
                + String.format(java.util.Locale.ROOT, " chamberCm=%.2f", chamberCm) + " chamberBone=" + (chamberBone.isEmpty() ? "none" : chamberBone)
                + " transfers=" + transfers + " unsupported=" + unsupported + " errors=" + errors
                + " rootMatch=" + (r != null && c.primaryHandModel != null && c.primaryHandModel.modelScript != null
                        && r.item.getWeaponSprite() != null && r.item.getWeaponSprite().equals(c.primaryHandModel.modelScript.getScriptObjectFullType()))
                + " reloadOpen=" + (s != null && s.reloadOpen)
                + " lastFailure={" + lastFailure + "}";
    }

    /** Per-frame numeric diagnostics. Distances are to the authored pivot, not a fitted grip. */
    public static synchronized String probe(IsoGameCharacter c) {
        Request r = c == null ? null : requests.get(c);
        if (r == null || c.primaryHandModel == null || c.primaryHandModel.animPlayer == null) return "request=none";
        try {
            if (c.getPrimaryHandItem() != r.item) return "request=none";
            AnimationPlayer ap = c.primaryHandModel.animPlayer;
            org.lwjgl.util.vector.Matrix4f tmp = new org.lwjgl.util.vector.Matrix4f();
            int prop = ap.getSkinningBoneIndex("Bip01_Prop1", -1);
            if (prop < 0) return "prop=missing";
            double[] inverse = GripIK.inverse(GripIK.read(ap.getBoneModelTransform(prop, tmp), new double[16]));
            StringBuilder out = new StringBuilder();
            State state = states.get(r.item);
            for (var node : r.weapon.nodes().values()) {
                double[] d = delta(c, r, state, node.name());
                double[] frame = d == null ? node.bind() : GripIK.mulNew(node.bind(), d);
                out.append(" part=").append(node.name().replace(' ', '_')).append(" pivot=").append(frame[3]).append(',').append(frame[7]).append(',').append(frame[11])
                        .append(" rotDeg=").append(rotDeg(d));
                for (String side : new String[] {"L", "R"}) {
                    int index = ap.getSkinningBoneIndex("Bip01_" + side + "_Hand", -1);
                    if (index < 0) continue;
                    double[] hand = GripIK.mulNew(inverse, GripIK.read(ap.getBoneModelTransform(index, tmp), new double[16]));
                    double distance = GripIK.dist(GripIK.pos(hand), GripIK.pos(frame)) / GripIK.CM;
                    out.append(' ').append(side).append("PivotCm=").append(distance);
                }
            }
            return out.toString();
        } catch (Throwable e) { reject(c, null, "probe", e); return "probe=rejected"; }
    }

    public static synchronized void off(IsoGameCharacter c) {
        if (c == null) return;
        requests.remove(c);
        restore(c, null);
    }

    private static void trace(Throwable cause) {
        if (!traced && cause != null) { traced = true; cause.printStackTrace(System.out); }
    }

    private static void logFailure(IsoGameCharacter c, String phase, Throwable cause) {
        errors++;
        lastFailure = phase + ": " + cause;
        boolean fresh = c == null ? !lastFailure.equals(anonymousFailure) : failures.computeIfAbsent(c, ignored -> new HashSet<>()).add(lastFailure);
        if (c == null) anonymousFailure = lastFailure;
        if (fresh) System.out.println("[TW] moving weapon parts request rejected; " + lastFailure);
        trace(cause);
    }

    /** Runtime failures belong to this request; only ABI or shared data failures retire mechanics. */
    private static synchronized void reject(IsoGameCharacter c, ModelInstance instance, String phase, Throwable cause) {
        if (cause == null) return;
        if (cause instanceof LinkageError || cause instanceof DataFailure) { disable(cause); return; }
        if (c == null && instance != null) {
            ModelInstance parent = instance;
            for (int i = 0; parent != null && i < 16; i++, parent = parent.parent) {
                if (parent.character != null) { c = parent.character; break; }
            }
        }
        Request r = c == null ? null : requests.remove(c);
        if (r != null) states.remove(r.item);
        logFailure(c, phase, cause);
        restore(c, instance);
    }

    private static void restore(IsoGameCharacter c, ModelInstance instance) {
        for (var e : new ArrayList<>(partSplits.entrySet())) {
            ModelInstance sub = e.getKey();
            PartSplit ps = e.getValue();
            if (sub == null || ps == null) continue;
            if (sub != instance && ps.root != instance && (c == null || ps.root == null || ps.root.character != c)) continue;
            partSplits.remove(sub);
            rollbackPartSplit(sub, ps);
        }
        for (var e : new ArrayList<>(splits.entrySet())) {
            ModelInstance root = e.getKey();
            if (root == null || (root != instance && (c == null || root.character != c))) continue;
            Split split = e.getValue();
            splits.remove(root);
            rollback(root, split);
        }
    }

    private static void rollbackPartSplit(ModelInstance sub, PartSplit ps) {
        if (ps == null) return;
        if (sub != null && (sub.model == ps.replacement || sub.model == ps.original)) {
            sub.model = ps.original;
        }
        ModelInstance child = ps.handleChild.get();
        if (child != null && ps.root != null && child.parent == ps.root && ps.root.sub != null && ps.root.sub.remove(child)) {
            Object owner = child.getOwner();
            if (owner == null) return;
            try { ModelManager.instance.resetModelInstance(child, owner); }
            catch (Throwable cause) {
                if (cause instanceof LinkageError) disable(cause);
                else logFailure(ps.root.character, "rollback", cause);
            }
        }
    }

    private static void rollback(ModelInstance root, Split split) {
        if (root == null || split == null) return;
        // A pooled root may already belong to a later weapon/model generation.
        if (root.model != split.replacement && root.model != split.original) return;
        root.model = split.original;
        for (WeakReference<ModelInstance> ref : split.children) {
            ModelInstance child = ref.get();
            if (child != null && child.parent == root && root.sub.remove(child)) {
                // A child the engine already released has no owner left to clear (FB1: the
                // weapon switch of a user session reached here with getOwner() == null).
                Object owner = child.getOwner();
                if (owner == null) continue;
                try { ModelManager.instance.resetModelInstance(child, owner); }
                catch (Throwable cause) {
                    if (cause instanceof LinkageError) disable(cause);
                    else logFailure(root.character, "rollback", cause);
                }
            }
        }
    }

    public static synchronized void disable(Throwable cause) {
        if (!enabled && logged) return;
        enabled = false; errors++; reason = String.valueOf(cause);
        var partOriginals = new ArrayList<>(partSplits.entrySet());
        var originals = new ArrayList<>(splits.entrySet());
        partSplits.clear(); splits.clear(); requests.clear(); states.clear();
        if (!logged) { logged = true; System.out.println("[TW] moving weapon parts off; static originals: " + reason); }
        trace(cause);
        for (var e : partOriginals) rollbackPartSplit(e.getKey(), e.getValue());
        for (var e : originals) rollback(e.getKey(), e.getValue());
    }
}
