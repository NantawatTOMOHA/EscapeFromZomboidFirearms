package pz.escapefromzomboidfirearms.zb;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import me.zed_0xff.zombie_buddy.Patch;
import org.joml.Matrix4f;
import zombie.characters.AttachedItems.AttachedItem;
import zombie.characters.AttachedItems.AttachedItems;
import zombie.characters.AttachedItems.AttachedLocation;
import zombie.characters.AttachedItems.AttachedLocationGroup;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.advancedanimation.AnimatedModel.AnimatedModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.core.skinnedmodel.model.ModelMesh;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.HandWeapon;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.ModelAttachment;
import zombie.scripting.objects.ModelScript;
import zombie.scripting.objects.ModelWeaponPart;
import zombie.scripting.objects.ScriptModule;

/** One render point for instance-local rigid changes. Producers never edit shared attachments. */
public final class WeaponPartTransforms {
    /**
     * Immersive Snow compatibility (bug wave W7, 25.10.2026): the model scripts the snow mod copies from the text of
     * ours (its ISnowItem_* copies) and the model each copy was made from, registered by TW_SnowCompat. Its copy is
     * made once and never updated, while our pool copy is re-placed for another assembly: an anchor the copy does
     * not have is read from our own model at draw time instead of being lost.
     */
    public static final ConcurrentHashMap<String, String> snowBases = new ConcurrentHashMap<>();
    public interface Source {
        /** A true row-major 4x4 in this instance's exported model frame, or null for identity. */
        double[] local(ModelInstance instance);
        void failed(Throwable cause);
        /** Return true to retire the source; false rejects only this instance/frame. */
        default boolean failed(ModelInstance instance, Throwable cause) { failed(cause); return true; }
    }

    private static final Map<String, Source> sources = new LinkedHashMap<>();
    /** The sources in registration order, republished on every change (performance pass 23.10.2026): the advice
     * runs for every model of every drawn character on the engine's slot-init threads and takes no lock. */
    private static volatile Source[] active = new Source[0];

    private WeaponPartTransforms() { }

    public static synchronized void register(String name, Source source) {
        if (name == null || source == null) throw new IllegalArgumentException("transform source");
        sources.put(name, source);
        active = sources.values().toArray(Source[]::new);
    }

    public static synchronized void unregister(String name) {
        if (name != null) sources.remove(name);
        active = sources.values().toArray(Source[]::new);
    }

    private static synchronized void retire(Source source) {
        sources.values().removeIf(s -> s == source);
        active = sources.values().toArray(Source[]::new);
    }

    /** Every source changes only a model below a character's held weapon (MovingWeaponParts: below
     * mi.character.primaryHandModel within 16 links; ZoomRings: below a root that is its character's
     * primaryHandModel within 32): the body, clothing and every zombie model end here without a source. */
    static boolean belowHeldWeapon(ModelInstance mi) {
        if (mi.parent == null) return false;
        ModelInstance held = mi.character == null ? null : mi.character.primaryHandModel;
        ModelInstance a = mi;
        for (int i = 0; a != null && i <= 32; i++) {
            if (a == held || a.character != null && a.character.primaryHandModel == a) return true;
            a = a.parent;
        }
        return false;
    }

    public static Matrix4f matrix(double[] a) {
        if (a == null || a.length != 16) throw new IllegalArgumentException("part matrix");
        for (double x : a) if (!Double.isFinite(x)) throw new IllegalArgumentException("part matrix finite");
        return new Matrix4f().set((float) a[0], (float) a[4], (float) a[8], (float) a[12],
                (float) a[1], (float) a[5], (float) a[9], (float) a[13],
                (float) a[2], (float) a[6], (float) a[10], (float) a[14],
                (float) a[3], (float) a[7], (float) a[11], (float) a[15]);
    }

    /** Pure CPU entry, also used by the native import/conjugation regression check. */
    public static Matrix4f composed(Matrix4f stored, Matrix4f imported, double[] change) {
        Matrix4f local = matrix(change);
        if (Math.abs(local.determinant() - 1) > .003f) throw new IllegalArgumentException("part transform not rigid");
        Matrix4f next = new Matrix4f(stored).transpose().mul(new Matrix4f(imported).invert()).mul(local).mul(imported).transpose();
        if (!next.isFinite()) throw new IllegalStateException("part render matrix");
        return next;
    }

    /** Stored native transforms are transposed. Insert before the imported mesh transform. */
    public static void apply(Object instance) {
        if (PerfLegacy.on) { applyLocked(instance); return; }
        Source[] list = active;
        if (list.length == 0 || !(instance instanceof AnimatedModelInstanceRenderData rd) || rd.modelInstance == null
                || !belowHeldWeapon(rd.modelInstance)) return;
        for (Source source : list) {
            try {
                double[] change = source.local(rd.modelInstance);
                if (change == null) continue;
                ModelMesh mesh = rd.modelInstance.model == null ? null : rd.modelInstance.model.mesh;
                if (mesh == null || !mesh.isReady()) continue;
                Matrix4f imported = mesh.transform == null ? new Matrix4f() : new Matrix4f(mesh.transform).transpose();
                rd.xfrm.set(composed(rd.xfrm, imported, change));
            } catch (Throwable e) {
                boolean retire = true;
                try { retire = source.failed(rd.modelInstance, e); }
                catch (Throwable failure) { System.out.println("[TW] part transform source failure: " + failure); }
                if (retire) retire(source);
            }
        }
    }

    /** The form before the performance pass (QA A/B, PerfLegacy): one monitor for every call, no prefilter. */
    static synchronized void applyLocked(Object instance) {
        if (!(instance instanceof AnimatedModelInstanceRenderData rd) || rd.modelInstance == null || sources.isEmpty()) return;
        for (Source source : sources.values().toArray(Source[]::new)) {
            try {
                double[] change = source.local(rd.modelInstance);
                if (change == null) continue;
                ModelMesh mesh = rd.modelInstance.model == null ? null : rd.modelInstance.model.mesh;
                if (mesh == null || !mesh.isReady()) continue;
                Matrix4f imported = mesh.transform == null ? new Matrix4f() : new Matrix4f(mesh.transform).transpose();
                rd.xfrm.set(composed(rd.xfrm, imported, change));
            } catch (Throwable e) {
                boolean retire = true;
                try { retire = source.failed(rd.modelInstance, e); }
                catch (Throwable failure) { System.out.println("[TW] part transform source failure: " + failure); }
                if (retire) { sources.values().removeIf(s -> s == source); active = sources.values().toArray(Source[]::new); }
            }
        }
    }

    @Patch(className = "zombie.core.skinnedmodel.advancedanimation.AnimatedModel$AnimatedModelInstanceRenderData", methodName = "transformToParent")
    public static class AfterParent {
        @Patch.OnExit
        public static void exit(@Patch.This Object instance) {
            if (instance instanceof AnimatedModelInstanceRenderData rd) missingAnchor(rd);
            WeaponPartTransforms.apply(instance);
        }
    }

    // W9-anchors: lock-free item -> published pool script fallback and hand-model state inspection.
    private static final String MOD_NAME = "EscapeFromZomboidFirearms";
    private static final String MOD_PREFIX = MOD_NAME + ".";
    private static final int MOD_PREFIX_LEN = MOD_PREFIX.length();
    private static final String SNOW_PART_PREFIX = "ISnowItem_EscapeFromZomboidFirearms_M_";
    private static final int SNOW_INNER_OFFSET = "ISnowItem_EscapeFromZomboidFirearms_".length();

    private static final ReferenceQueue<InventoryItem> anchorSourceQueue = new ReferenceQueue<>();
    private static final ConcurrentHashMap<Object, String> partAnchorSources = new ConcurrentHashMap<>();
    private static final AtomicLong partAnchorHits = new AtomicLong();
    private static final AtomicLong snowAnchorHits = new AtomicLong();
    private static volatile String lastPartAnchorCase = "none";

    private static final class StoredItemKey extends WeakReference<InventoryItem> {
        final int hash;
        StoredItemKey(InventoryItem item, ReferenceQueue<InventoryItem> queue) {
            super(item, queue);
            this.hash = System.identityHashCode(item);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            InventoryItem mine = get();
            if (mine == null) return false;
            if (o instanceof StoredItemKey k) return mine == k.get();
            if (o instanceof LookupItemKey k) return mine == k.item;
            return false;
        }
    }

    private static final class LookupItemKey {
        final InventoryItem item;
        final int hash;
        LookupItemKey(InventoryItem item) {
            this.item = item;
            this.hash = System.identityHashCode(item);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (o instanceof StoredItemKey k) return item == k.get();
            if (o instanceof LookupItemKey k) return item == k.item;
            return false;
        }
    }

    private static void drainAnchorSourceQueue() {
        Reference<? extends InventoryItem> ref;
        while ((ref = anchorSourceQueue.poll()) != null) partAnchorSources.remove(ref);
    }

    /** Publishes the current pool model script (EscapeFromZomboidFirearms.M_<tpl>_Pdd) of an item (TW/Render.apply). */
    public static void partAnchorSource(Object item, String poolModelFullType) {
        drainAnchorSourceQueue();
        if (!(item instanceof InventoryItem inv)) return;
        if (poolModelFullType == null || poolModelFullType.isEmpty()) {
            partAnchorSources.remove(new LookupItemKey(inv));
            return;
        }
        partAnchorSources.put(new StoredItemKey(inv, anchorSourceQueue), poolModelFullType);
    }

    /** Drops the published pool model script of an item (TW/Render.clear, TW/Render.forget). */
    public static void partAnchorForget(Object item) {
        drainAnchorSourceQueue();
        if (item instanceof InventoryItem inv) partAnchorSources.remove(new LookupItemKey(inv));
    }

    public static String anchorSourceOf(InventoryItem item) {
        return item == null ? null : partAnchorSources.get(new LookupItemKey(item));
    }

    public static String partAnchorStats() {
        return "hits=" + partAnchorHits.get() + " snowHits=" + snowAnchorHits.get()
                + " sources=" + partAnchorSources.size() + " last=" + lastPartAnchorCase;
    }

    static void resetPartAnchorsForTest() {
        drainAnchorSourceQueue();
        partAnchorSources.clear();
        partAnchorHits.set(0);
        snowAnchorHits.set(0);
        lastPartAnchorCase = "none";
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isPoolCopyRange(String s, int start, int end) {
        int len = end - start;
        if (len < 6 || !s.startsWith("M_", start)) return false;
        return s.charAt(end - 4) == '_'
                && s.charAt(end - 3) == 'P'
                && isAsciiDigit(s.charAt(end - 2))
                && isAsciiDigit(s.charAt(end - 1));
    }

    private static boolean isOurPoolRootScript(ModelScript script) {
        if (script == null) return false;
        ScriptModule mod = script.getModule();
        String name = script.getName();
        if (mod != null && mod.getName() != null && name != null) {
            return MOD_NAME.equals(mod.getName()) && isPoolCopyRange(name, 0, name.length());
        }
        String full = script.getScriptObjectFullType();
        return full != null && full.startsWith(MOD_PREFIX) && isPoolCopyRange(full, MOD_PREFIX_LEN, full.length());
    }

    private static boolean scriptEqualsFullType(ModelScript script, String expectedFull) {
        if (script == null || expectedFull == null) return false;
        ScriptModule mod = script.getModule();
        String name = script.getName();
        if (mod != null && mod.getName() != null && name != null) {
            String m = mod.getName();
            return expectedFull.length() == m.length() + 1 + name.length()
                    && expectedFull.startsWith(m)
                    && expectedFull.charAt(m.length()) == '.'
                    && expectedFull.endsWith(name);
        }
        return expectedFull.equals(script.getScriptObjectFullType());
    }

    private static String scriptFullType(ModelScript script) {
        if (script == null) return null;
        ScriptModule mod = script.getModule();
        if (mod != null && mod.getName() != null) {
            if (script.getScriptObjectName() != null) return script.getScriptObjectFullType();
            if (script.getName() != null) return mod.getName() + "." + script.getName();
        }
        String full = script.getScriptObjectFullType();
        return "<unknown>".equals(full) ? null : full;
    }

    private static boolean isOurPartRange(String s, int start, int end) {
        int len = end - start;
        if (len <= 2) return false;
        if (s.startsWith("P_", start) || s.startsWith("ZR_", start)) return true;
        if (s.startsWith(SNOW_PART_PREFIX, start)) {
            if (len >= SNOW_PART_PREFIX.length() + 7
                    && s.charAt(end - 3) == '_' && s.charAt(end - 2) == 's' && isAsciiDigit(s.charAt(end - 1))
                    && isPoolCopyRange(s, start + SNOW_INNER_OFFSET, end - 3)) {
                return false;
            }
            return true;
        }
        if (!s.startsWith("M_", start) || isPoolCopyRange(s, start, end)) return false;
        if (len == "M_explosion".length() && s.startsWith("M_explosion", start)) return false;
        return !s.startsWith("M_FX_", start);
    }

    private static boolean isOurPartModelScript(ModelScript script) {
        if (script == null) return false;
        ScriptModule mod = script.getModule();
        String name = script.getName();
        if (mod != null && mod.getName() != null && name != null) {
            return MOD_NAME.equals(mod.getName()) && isOurPartRange(name, 0, name.length());
        }
        String full = script.getScriptObjectFullType();
        return full != null && full.startsWith(MOD_PREFIX) && isOurPartRange(full, MOD_PREFIX_LEN, full.length());
    }

    private static boolean isOurPartType(String partType) {
        return partType != null && (partType.startsWith("EscapeFromZomboidFirearms.P_") || partType.startsWith("P_"));
    }

    private static boolean isOurPart(InventoryItem item, ModelInstance child, String id) {
        if (child == null || !isOurPartModelScript(child.modelScript)) return false;
        if (item instanceof HandWeapon hw) {
            var rows = hw.getModelWeaponPart();
            if (rows != null && !rows.isEmpty()) {
                boolean sawAnchor = false;
                for (int i = 0, n = rows.size(); i < n; i++) {
                    ModelWeaponPart row;
                    try { row = rows.get(i); } catch (IndexOutOfBoundsException e) { break; }
                    if (row == null || !id.equals(row.attachmentParent)) continue;
                    sawAnchor = true;
                    if (isOurPartType(row.partType)) return true;
                }
                if (sawAnchor) return false;
            }
        }
        return true;
    }

    private static boolean isOurWeapon(HandWeapon hw) {
        String mod = hw.getModule();
        if (mod != null) return MOD_NAME.equals(mod);
        var si = hw.getScriptItem();
        if (si != null && si.getModule() != null && si.getModule().getName() != null) {
            return MOD_NAME.equals(si.getModule().getName());
        }
        return anchorSourceOf(hw) != null;
    }

    private static boolean hasAttachment(ModelInstance parent, String id) {
        try {
            return parent.getAttachmentById(id) != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean hasScriptAttachment(ModelScript script, String id) {
        return attachmentOf(script, id) != null;
    }

    private static ModelAttachment attachmentOf(ModelScript script, String id) {
        if (script == null || id == null) return null;
        try {
            ModelAttachment a = script.getAttachmentById(id);
            if (a != null) return a;
        } catch (RuntimeException ignored) {
            // Transient HashMap resize on main thread during configure(); scan attachments below.
        }
        try {
            for (int i = 0, n = script.getAttachmentCount(); i < n; i++) {
                ModelAttachment a = script.getAttachment(i);
                if (a != null && id.equals(a.getId())) return a;
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static InventoryItem rootItem(ModelInstance parent, ModelInstance child) {
        IsoGameCharacter chr = parent.character != null ? parent.character : (child == null ? null : child.character);
        if (chr == null) return null;
        if (parent == chr.primaryHandModel) return chr.getPrimaryHandItem();
        if (parent == chr.secondaryHandModel) return chr.getSecondaryHandItem();
        if (parent.attachmentNameParent == null || (parent.parent != null && parent.parent.parent != null)) return null;
        AttachedItems attached = chr.getAttachedItems();
        if (attached == null) return null;
        AttachedLocationGroup group = attached.getGroup();
        if (group == null) return null;
        InventoryItem fallbackMatch = null;
        String parentMesh = parent.modelScript == null ? null : parent.modelScript.getMeshName();
        for (int i = 0, n = attached.size(); i < n; i++) {
            AttachedItem ai;
            try { ai = attached.get(i); } catch (IndexOutOfBoundsException e) { break; }
            if (ai == null) continue;
            InventoryItem it = ai.getItem();
            if (it == null || it == chr.getPrimaryHandItem() || it == chr.getSecondaryHandItem()) continue;
            AttachedLocation loc = group.getLocation(ai.getLocation());
            if (loc == null || !parent.attachmentNameParent.equals(loc.getAttachmentName())) continue;
            if (fallbackMatch == null) fallbackMatch = it;
            String poolName = anchorSourceOf(it);
            if (poolName != null && parentMesh != null && ScriptManager.instance != null) {
                ModelScript pool = ScriptManager.instance.getModelScript(poolName);
                if (pool != null && parentMesh.equals(pool.getMeshName())) return it;
            }
        }
        return fallbackMatch;
    }

    private static boolean hasOurAnchorRows(ModelInstance m, HandWeapon hw) {
        var rows = hw.getModelWeaponPart();
        if (rows != null) {
            for (int i = 0, n = rows.size(); i < n; i++) {
                ModelWeaponPart row;
                try { row = rows.get(i); } catch (IndexOutOfBoundsException e) { break; }
                if (row != null && row.attachmentParent != null && row.attachmentParent.startsWith("t_")
                        && (row.partType == null || isOurPartType(row.partType))) {
                    return true;
                }
            }
        }
        var sub = m.sub;
        if (sub != null) {
            for (int i = 0, n = sub.size(); i < n; i++) {
                ModelInstance s;
                try { s = sub.get(i); } catch (IndexOutOfBoundsException e) { break; }
                if (s != null && s.attachmentNameParent != null && s.attachmentNameParent.startsWith("t_")
                        && isOurPart(hw, s, s.attachmentNameParent)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int missingAnchorCount(ModelInstance m, HandWeapon hw, ModelScript root, boolean stopAtFirst) {
        int missing = 0;
        var sub = m.sub;
        if (sub != null) {
            for (int i = 0, n = sub.size(); i < n; i++) {
                ModelInstance s;
                try { s = sub.get(i); } catch (IndexOutOfBoundsException e) { break; }
                if (s != null && s.attachmentNameParent != null && s.attachmentNameParent.startsWith("t_")
                        && isOurPart(hw, s, s.attachmentNameParent)
                        && !hasScriptAttachment(root, s.attachmentNameParent)) {
                    if (stopAtFirst) return 1;
                    missing++;
                }
            }
        }
        var rows = hw.getModelWeaponPart();
        if (rows != null) {
            for (int i = 0, n = rows.size(); i < n; i++) {
                ModelWeaponPart row;
                try { row = rows.get(i); } catch (IndexOutOfBoundsException e) { break; }
                if (row == null || row.attachmentParent == null || !row.attachmentParent.startsWith("t_")
                        || (row.partType != null && !isOurPartType(row.partType))) continue;
                if (!hasScriptAttachment(root, row.attachmentParent)) {
                    if (stopAtFirst) return 1;
                    boolean countedInSub = false;
                    if (sub != null) {
                        for (int j = 0, mSize = sub.size(); j < mSize; j++) {
                            ModelInstance s = sub.get(j);
                            if (s != null && row.attachmentParent.equals(s.attachmentNameParent)) {
                                countedInSub = true;
                                break;
                            }
                        }
                    }
                    if (!countedInSub) missing++;
                }
            }
        }
        return missing;
    }

    private static int statePriority(String state) {
        return switch (state) {
            case "own-missing" -> 4;
            case "stale" -> 3;
            case "ok" -> 2;
            case "foreign" -> 1;
            default -> 0;
        };
    }

    private static String singleHandState(ModelInstance m, InventoryItem item) {
        if (m == null || !(item instanceof HandWeapon hw) || !isOurWeapon(hw)) return null;
        ModelScript root = m.modelScript;
        if (!isOurPoolRootScript(root)) return "foreign";
        String published = anchorSourceOf(hw);
        if (published == null) return hasOurAnchorRows(m, hw) ? "stale" : "ok";
        if (!scriptEqualsFullType(root, published)) return "stale";
        return missingAnchorCount(m, hw, root, true) > 0 ? "own-missing" : "ok";
    }

    /** Zero-allocation hot-path hand model check for TW/Runtime: ok | own-missing | stale | foreign | none. */
    public static String handModelState(IsoGameCharacter c) {
        if (c == null) return "none";
        InventoryItem primary = c.getPrimaryHandItem();
        String pState = singleHandState(c.primaryHandModel, primary);
        InventoryItem secondary = c.getSecondaryHandItem();
        if (secondary != null && secondary != primary) {
            String sState = singleHandState(c.secondaryHandModel, secondary);
            if (pState == null) return sState == null ? "none" : sState;
            if (sState != null && statePriority(sState) > statePriority(pState)) return sState;
        }
        return pState == null ? "none" : pState;
    }

    /** Diagnostic string for logs/QA (root, sprite, pool, missing anchor count). */
    public static String handModelDetail(IsoGameCharacter c) {
        String state = handModelState(c);
        if (c == null) return "state=none root=none sprite=none missing=0";
        ModelInstance m = c.primaryHandModel;
        InventoryItem item = c.getPrimaryHandItem();
        String hand = "primary";
        if (singleHandState(m, item) == null) {
            InventoryItem sec = c.getSecondaryHandItem();
            if (sec != null && sec != item && singleHandState(c.secondaryHandModel, sec) != null) {
                m = c.secondaryHandModel;
                item = sec;
                hand = "secondary";
            }
        }
        if (m == null || !(item instanceof HandWeapon hw) || !isOurWeapon(hw)) {
            return "state=" + state + " root=" + (m == null ? "none" : String.valueOf(scriptFullType(m.modelScript)))
                    + " sprite=" + (item instanceof HandWeapon w ? String.valueOf(w.getWeaponSprite()) : "none")
                    + " missing=0";
        }
        ModelScript root = m.modelScript;
        int missing = missingAnchorCount(m, hw, root, false);
        var list = hw.getWeaponSpritesByIndex();
        var rows = hw.getModelWeaponPart();
        StringBuilder b = new StringBuilder(128)
                .append("state=").append(state)
                .append(" hand=").append(hand)
                .append(" root=").append(root == null ? "none" : String.valueOf(scriptFullType(root)))
                .append(" sprite=").append(hw.getWeaponSprite())
                .append(" pool=").append(anchorSourceOf(hw))
                .append(" idx=").append(hw.getModelIndex())
                .append(" sub=").append(m.sub == null ? 0 : m.sub.size())
                .append(" rows=").append(rows == null ? 0 : rows.size())
                .append(" missing=").append(missing);
        if (list != null && hw.getModelIndex() >= 0 && hw.getModelIndex() < list.size()) {
            b.append(" show=").append(list.get(hw.getModelIndex()));
        }
        return b.toString();
    }

    /** Registers a snow copy of a model script and the model it was made from (TW_SnowCompat.ensure). */
    public static void snowBase(String copy, String base) {
        if (copy != null && base != null && !copy.equals(base)) snowBases.put(copy, base);
    }

    /** The anchor of our own model when the drawn parent's script (a text copy) does not have it. */
    public static ModelAttachment poolAttachment(ModelInstance parent, String id) {
        return poolAttachment(parent, null, id);
    }

    /**
     * Resolves a missing attachment anchor on parent without mutating any script:
     * 1) snowBases (Immersive Snow text copy -> our pool script);
     * 2) W9-anchors: for our part (EscapeFromZomboidFirearms P_* / M_* / ZR_* / snow part) and a t_* anchor, reads
     *    the anchor from this item's published pool copy when parent.modelScript.getMeshName() matches the pool copy's.
     */
    public static ModelAttachment poolAttachment(ModelInstance parent, ModelInstance child, String id) {
        if (parent == null || parent.modelScript == null || id == null || ScriptManager.instance == null) return null;
        String parentFull = scriptFullType(parent.modelScript);
        String base = parentFull == null ? null : snowBases.get(parentFull);
        if (base != null) {
            ModelAttachment snowAnchor = attachmentOf(ScriptManager.instance.getModelScript(base), id);
            if (snowAnchor != null) snowAnchorHits.incrementAndGet();
            return snowAnchor;
        }
        if (child == null || !id.startsWith("t_")) return null;
        InventoryItem item = rootItem(parent, child);
        if (item == null || !isOurPart(item, child, id)) return null;
        String poolFull = anchorSourceOf(item);
        if (poolFull == null) return null;
        ModelScript pool = ScriptManager.instance.getModelScript(poolFull);
        if (pool == null) return null;
        String rootMesh = parent.modelScript.getMeshName();
        if (rootMesh == null || !rootMesh.equals(pool.getMeshName())) return null;
        ModelAttachment anchor = attachmentOf(pool, id);
        if (anchor != null) {
            partAnchorHits.incrementAndGet();
            lastPartAnchorCase = (parentFull == null ? "?" : parentFull) + "->" + poolFull + ":" + id;
        }
        return anchor;
    }

    /**
     * The engine's transformToParent leaves a part at the parent's origin when its anchor is missing (Immersive Snow's
     * copy of our model script is made from the text, where our runtime mounts do not exist; or a weapon root holds a
     * stale/foreign script or races configure). Rebuilds the transform with the anchor of our own pool script, exactly
     * as transformToParent does: parent frame, parent attachment, self attachment, the part's mesh transform and its
     * scale; the part transform sources then apply on top.
     */
    public static void missingAnchor(AnimatedModelInstanceRenderData rd) {
        ModelInstance mi = rd.modelInstance;
        if (mi == null || mi.attachmentNameParent == null || rd.parent == null) return;
        ModelInstance parent = rd.parent.modelInstance;
        if (parent == null || hasAttachment(parent, mi.attachmentNameParent)) return;
        if (mi.parentBoneName != null && hasAttachment(parent, mi.parentBoneName)) return;
        ModelAttachment anchor = poolAttachment(parent, mi, mi.attachmentNameParent);
        if (anchor == null) return;
        Matrix4f m = new Matrix4f(rd.parent.xfrm).transpose();
        ModelInstanceRenderData.applyBoneTransform(parent, anchor.getBone(), m);
        Matrix4f attachment = new Matrix4f();
        ModelInstanceRenderData.makeAttachmentTransform(anchor, attachment);
        m.mul(attachment);
        ModelAttachment self = mi.attachmentNameSelf == null ? null : mi.getAttachmentById(mi.attachmentNameSelf);
        if (self != null) {
            ModelInstanceRenderData.makeAttachmentTransform(self, attachment);
            if (ModelInstanceRenderData.invertAttachmentSelfTransform) attachment.invert();
            m.mul(attachment);
        }
        if (mi.model != null && mi.model.mesh != null) ModelInstanceRenderData.postMultiplyMeshTransform(m, mi.model.mesh);
        if (mi.scale != 1f) m.scale(mi.scale);
        rd.xfrm.set(m.transpose());
    }
}
