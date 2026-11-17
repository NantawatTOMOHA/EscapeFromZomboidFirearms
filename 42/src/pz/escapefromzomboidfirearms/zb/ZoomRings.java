package pz.escapefromzomboidfirearms.zb;

import java.lang.ref.WeakReference;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import me.zed_0xff.zombie_buddy.Exposer;
import org.joml.Quaterniond;
import org.joml.Matrix4d;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel.AnimatedModelInstanceRenderData;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.inventory.types.HandWeapon;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.ModelScript;
import se.krka.kahlua.vm.KahluaTable;

/** Instance-owned optical mechanics. The body, lenses and child slots never rotate. */
@Exposer.LuaClass(name = "TWZoomRingsNative")
public final class ZoomRings implements WeaponPartTransforms.Source {
    private record Binding(String tpl, String original, String body, String ring, double fov) { }
    private record Request(HandWeapon item, Map<String, Binding> rows, long frame) { }
    private record Loaded(Model model, ModelScript script) { }
    private static final class Split {
        final Model original, body;
        final ModelScript script;
        final String tpl, anchor;
        final WeakReference<ModelInstance> child;
        long draws;
        double fov, t;
        double[] lastDelta;
        Split(ModelInstance host, Loaded replacement, ModelInstance ring, Binding row) {
            original = host.model; script = host.modelScript; body = replacement.model;
            tpl = row.tpl; anchor = host.attachmentNameParent; child = new WeakReference<>(ring);
        }
    }
    private static final Map<IsoGameCharacter, Request> requests = new WeakHashMap<>();
    private static final Map<ModelInstance, Split> splits = new WeakHashMap<>();
    private static boolean enabled = verify();
    private static String reason = enabled ? "ready" : "ABI mismatch";
    private static long installed, rendered;

    static { if (enabled) WeaponPartTransforms.register("zoom rings", new ZoomRings()); }
    private ZoomRings() { }

    private static boolean verify() {
        try {
            field(AnimatedModelInstanceRenderData.class, "xfrm", org.joml.Matrix4f.class);
            field(AnimatedModelInstanceRenderData.class, "modelInstance", ModelInstance.class);
            field(ModelInstance.class, "model", Model.class);
            field(ModelInstance.class, "modelScript", ModelScript.class);
            field(ModelInstance.class, "parent", ModelInstance.class);
            field(ModelInstance.class, "character", IsoGameCharacter.class);
            field(ModelInstance.class, "sub", ArrayList.class);
            field(ModelInstance.class, "attachmentNameParent", String.class);
            field(IsoGameCharacter.class, "primaryHandModel", ModelInstance.class);
            field(Model.class, "mesh", zombie.core.skinnedmodel.model.ModelMesh.class);
            field(zombie.core.skinnedmodel.model.ModelMesh.class, "transform", org.joml.Matrix4f.class);
            method(ModelManager.class, "addStatic", ModelInstance.class, ModelInstance.class, String.class, String.class, String.class);
            method(ModelManager.class, "tryGetLoadedModel", Model.class, ModelScript.class, boolean.class);
            method(ModelManager.class, "loadStaticModel", Model.class, String.class, String.class, String.class);
            method(ModelManager.class, "resetModelInstance", void.class, ModelInstance.class, Object.class);
            method(ModelInstance.class, "setOwner", void.class, Object.class);
            method(ModelInstance.class, "getOwner", Object.class);
            method(ScriptManager.class, "getModelScript", ModelScript.class, String.class);
            method(ModelScript.class, "getMeshName", String.class);
            method(ModelScript.class, "getTextureName", String.class);
            method(ModelScript.class, "getShaderName", String.class);
            method(ModelScript.class, "getScriptObjectFullType", String.class);
            method(AnimatedModelInstanceRenderData.class, "transformToParent", AnimatedModelInstanceRenderData.class, AnimatedModelInstanceRenderData.class);
            return true;
        } catch (Throwable e) {
            System.out.println("[TW] zoom rings off; static originals: ABI " + e);
            return false;
        }
    }

    private static void field(Class<?> c, String name, Class<?> type) throws Exception {
        var f = c.getField(name);
        if (f.getType() != type || !Modifier.isPublic(f.getModifiers())) throw new NoSuchFieldException(name);
    }

    private static void method(Class<?> c, String name, Class<?> type, Class<?>... args) throws Exception {
        var m = c.getMethod(name, args);
        if (m.getReturnType() != type || !Modifier.isPublic(m.getModifiers())) throw new NoSuchMethodException(name);
    }

    public static synchronized boolean ready() { return enabled; }

    private static String text(KahluaTable row, String name) {
        Object value = row.rawget(name);
        if (!(value instanceof String s) || s.isEmpty()) throw new IllegalArgumentException("zoom " + name);
        return s;
    }

    /** Main thread. Actual FOV is the same value read by PIP and FFP/SFP. */
    public static synchronized void update(IsoGameCharacter player, HandWeapon item, KahluaTable rows) {
        if (!enabled || player == null) return;
        if (item == null || player.getPrimaryHandItem() != item) { off(player); return; }
        try {
            Map<String, Binding> bindings = new LinkedHashMap<>();
            if (rows != null) for (int i = 1; rows.rawget(i) instanceof KahluaTable row; i++) {
                String tpl = text(row, "tpl");
                if (!ZoomRingData.ROWS.containsKey(tpl) || !(row.rawget("fov") instanceof Number f) || !Double.isFinite(f.doubleValue()))
                    throw new IllegalArgumentException("zoom ring source/FOV");
                bindings.put(text(row, "anchor"), new Binding(tpl, text(row, "original"), text(row, "body"), text(row, "ring"), f.doubleValue()));
            }
            requests.put(player, new Request(item, Map.copyOf(bindings), FpRecoil.worldFrame()));
            installTree(player.primaryHandModel, bindings, 0);
        } catch (Throwable e) { disable(e); }
    }

    private static Loaded loaded(String name) {
        ModelScript script = ScriptManager.instance.getModelScript("EscapeFromZomboidFirearms." + name);
        if (script == null) throw new IllegalStateException("missing zoom model " + name);
        ModelManager manager = ModelManager.instance;
        Model model = manager.tryGetLoadedModel(script, true);
        if (model == null) model = manager.loadStaticModel(script.getMeshName(), script.getTextureName(), script.getShaderName());
        return model != null && model.isReady() && model.mesh != null && model.mesh.isReady() ? new Loaded(model, script) : null;
    }

    private static void installTree(ModelInstance host, Map<String, Binding> bindings, int depth) {
        if (host == null || depth > 16) return;
        Binding row = bindings.get(host.attachmentNameParent);
        Split old = splits.get(host);
        if (old != null && (host.model != old.body || row == null || !row.tpl.equals(old.tpl)
                || !host.modelScript.getScriptObjectFullType().equals("EscapeFromZomboidFirearms." + row.body))) {
            restore(host, old); splits.remove(host); old = null;
        }
        if (old == null && row != null && host.modelScript != null
                && ("TW/" + row.original).equals(host.modelScript.getMeshName())) {
            Loaded body = loaded(row.body), ring = loaded(row.ring);
            if (body != null && ring != null) {
                ModelInstance child = ModelManager.instance.addStatic(host, "EscapeFromZomboidFirearms." + row.ring, null, null);
                if (child == null) throw new IllegalStateException("zoom child unavailable after preload");
                Split split = new Split(host, body, child, row);
                splits.put(host, split);
                Object owner = host.getOwner();
                if (owner != null) child.setOwner(owner);
                // The body's own mesh key admits only its independently checked glass row.
                host.model = body.model; host.modelScript = body.script;
                installed++;
            }
        }
        if (host.sub != null) for (ModelInstance child : host.sub.toArray(ModelInstance[]::new)) installTree(child, bindings, depth+1);
    }

    /** Pure CPU rotation, shared by the renderer and independent native tests. */
    public static double fraction(ZoomRingData.Ring row, double fov) {
        if (!Double.isFinite(fov)) throw new IllegalArgumentException("zoom FOV finite");
        return Math.max(0, Math.min(1, (fov-row.wide())/(row.narrow()-row.wide())));
    }

    public static double[] delta(String tpl, double fov) {
        ZoomRingData.Ring row = ZoomRingData.ROWS.get(tpl);
        if (row == null) return null;
        double t = fraction(row, fov);
        double[] a = row.start().clone(), b = row.end();
        double dot = 0;
        for (int i = 0; i < 4; i++) dot += a[i]*b[i];
        if (!row.inverse() && dot < 0) { for (int i = 0; i < 4; i++) a[i] = -a[i]; dot = -dot; }
        double angle = Math.acos(Math.max(-1, Math.min(1, dot)));
        double[] q = new double[4];
        if (angle < 1e-8) q = a;
        else {
            double sin = Math.sin(angle);
            if (Math.abs(sin) < 1e-8) throw new IllegalArgumentException("antipodal zoom pivots");
            for (int i = 0; i < 4; i++) q[i] = (Math.sin((1-t)*angle)*a[i]+Math.sin(t*angle)*b[i])/sin;
        }
        Quaterniond rotation = new Quaterniond(q[0], q[1], q[2], q[3]).normalize();
        double[] bind = row.bind(), p = row.pivot();
        rotation.mul(new Quaterniond(bind[0], bind[1], bind[2], bind[3]).conjugate()).normalize();
        Matrix4d matrix = new Matrix4d().translation(p[0], p[1], p[2]).rotate(rotation).translate(-p[0], -p[1], -p[2]);
        return new double[] {matrix.m00(),matrix.m10(),matrix.m20(),matrix.m30(),
                matrix.m01(),matrix.m11(),matrix.m21(),matrix.m31(),matrix.m02(),matrix.m12(),matrix.m22(),matrix.m32(),0,0,0,1};
    }

    @Override public double[] local(ModelInstance instance) { return localNow(instance); }
    @Override public void failed(Throwable cause) { disable(cause); }

    private static synchronized double[] localNow(ModelInstance instance) {
        if (!enabled || instance == null || instance.parent == null) return null;
        Split split = splits.get(instance.parent);
        if (split == null || split.child.get() != instance || instance.parent.model != split.body) return null;
        ModelInstance root = instance;
        for (int i = 0; root != null && i < 32; i++) {
            if (root.character != null && root.character.primaryHandModel == root) break;
            root = root.parent;
        }
        if (root == null || root.character == null || root.character.primaryHandModel != root) return null;
        IsoGameCharacter character = root.character;
        Request request = character == null ? null : requests.get(character);
        if (request == null || character.primaryHandModel != root || character.getPrimaryHandItem() != request.item
                || FpRecoil.worldFrame() > request.frame+3) return null;
        Binding row = request.rows.get(split.anchor);
        if (row == null || !row.tpl.equals(split.tpl)) return null;
        split.fov = row.fov; split.t = fraction(ZoomRingData.ROWS.get(row.tpl), row.fov);
        split.lastDelta = delta(row.tpl, row.fov);
        split.draws++; rendered++;
        return split.lastDelta.clone();
    }

    private static void restore(ModelInstance host, Split split) {
        if (host.model == split.body) { host.model = split.original; host.modelScript = split.script; }
        ModelInstance child = split.child.get();
        if (child != null && host.sub != null && host.sub.remove(child)) {
            Object owner = child.getOwner();
            if (owner != null) ModelManager.instance.resetModelInstance(child, owner);
        }
    }

    public static synchronized void off(IsoGameCharacter player) {
        requests.remove(player);
        ModelInstance root = player == null ? null : player.primaryHandModel;
        for (var e : new ArrayList<>(splits.entrySet())) {
            ModelInstance ancestor = e.getKey();
            for (int i = 0; ancestor != null && ancestor != root && i < 32; i++) ancestor = ancestor.parent;
            if (root != null && ancestor == root) { restore(e.getKey(), e.getValue()); splits.remove(e.getKey()); }
        }
    }

    private static synchronized void disable(Throwable error) {
        if (!enabled) return;
        enabled = false; reason = error.toString();
        for (var e : new ArrayList<>(splits.entrySet())) restore(e.getKey(), e.getValue());
        splits.clear(); requests.clear();
        WeaponPartTransforms.unregister("zoom rings");
        System.out.println("[TW] zoom rings off; static originals: " + reason);
    }

    /** Per physical anchor and a fresh render count; old frames cannot satisfy QA. */
    public static synchronized String sample(IsoGameCharacter player, String anchor) {
        for (var e : splits.entrySet()) {
            Split s = e.getValue();
            if (!s.anchor.equals(anchor)) continue;
            ModelInstance root = e.getKey();
            for (int i = 0; root != null && player != null && root != player.primaryHandModel && i < 32; i++) root = root.parent;
            if (player != null && root == player.primaryHandModel)
                return "tpl="+s.tpl+" anchor="+s.anchor+" fov="+s.fov+" t="+s.t+" draws="+s.draws
                        +" body="+e.getKey().modelScript.getMeshName()+" delta="+java.util.Arrays.toString(s.lastDelta);
        }
        return "none";
    }

    public static synchronized String info() { return "enabled="+enabled+" reason="+reason+" installed="+installed+" rendered="+rendered; }
}
