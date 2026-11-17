package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.ModelScript;

/**
 * Cartridges where Tarkov shows them (FB4-A, user report 19.10.2026, item 7): the chambered round on the weapon's
 * patron_in_weapon node and a magazine's rounds on its patron_NNN nodes from the lips down (TW/CartridgeVisual,
 * tools/cartridge_nodes.py), as child models of the drawn weapon, of its installed magazine and of the magazine in
 * the support hand (a reload or a top-off). Each host keeps exactly the rounds Lua asks for: a round model with its
 * self attachment "origin" on the host's node attachment; a model reset that dropped them gets them back, a host
 * that is not drawn (another model in the hand, no magazine) gets none. Display only: no item, ammunition or
 * inventory part is created or changed. Same pattern as ReloadMagazineVisual (ownership checked before removal).
 */
@Exposer.LuaClass(name = "TWCartridgesNative")
public final class CartridgeVisual {
    /** A round mounts on its node as a part on its slot: its identity attachment (tools/cartridge_nodes.py). */
    static final String SELF = "origin";
    /**
     * Rounds on one host at most: Tarkov's prefabs carry nodes only for the rounds that can be seen (an opaque STANAG 3,
     * a windowed PMAG all 30, the largest 89), so the count shown is min(rounds, nodes) <= this.
     */
    public static final int MAX_ROUNDS = 100;
    public static long added, removed, resets, errors, ticks;
    public static String lastError = "";

    static final class Host {
        final String kind;
        String model, anchor;
        String[] rounds = new String[0], nodes = new String[0];
        ModelInstance parent;
        Object owner;
        ModelInstance[] children = new ModelInstance[0];

        Host(String kind) {
            this.kind = kind;
        }
    }

    private static final Map<IsoGameCharacter, Map<String, Host>> state = new WeakHashMap<>();
    private static final Map<String, Boolean> readyModels = new HashMap<>();

    private CartridgeVisual() {
    }

    /**
     * Lua: the rounds of one host of a character. kind "weapon" (the primary hand model, model = its script name),
     * "magazine" (the weapon's installed magazine: model = M_<tpl>, anchor = its attachment on the weapon) or "hand"
     * (the secondary hand model, model = its script name); rounds: {{round model, node attachment}, ...} from the
     * first node; nil or empty: none.
     */
    public static synchronized void set(IsoGameCharacter c, String kind, String model, String anchor, KahluaTable rounds) {
        if (c == null || kind == null) {
            return;
        }
        try {
            Map<String, Host> hosts = state.computeIfAbsent(c, k -> new HashMap<>());
            Host h = hosts.computeIfAbsent(kind, Host::new);
            int n = rounds == null ? 0 : Math.min(MAX_ROUNDS, rounds.len());
            String[] r = new String[n], a = new String[n];
            for (int i = 0; i < n; i++) {
                Object row = rounds.rawget(i + 1);
                if (row instanceof KahluaTable t) {
                    r[i] = t.rawget(1) instanceof String s ? s : null;
                    a[i] = t.rawget(2) instanceof String s ? s : null;
                }
            }
            if (!java.util.Objects.equals(h.model, model) || !java.util.Objects.equals(h.anchor, anchor)) {
                release(h);
            }
            h.model = model;
            h.anchor = anchor;
            h.rounds = r;
            h.nodes = a;
        } catch (Throwable t) {
            fail(t);
        }
    }

    /** Lua: none of a character's rounds (its weapon is put away, it left). */
    public static synchronized void clear(IsoGameCharacter c) {
        Map<String, Host> hosts = c == null ? null : state.remove(c);
        if (hosts != null) {
            for (Host h : hosts.values()) {
                release(h);
            }
        }
    }

    /** Every tick (OnTick): each host's rounds present, after a model reset too. */
    public static synchronized void tick() {
        ticks++;
        for (IsoGameCharacter c : new ArrayList<>(state.keySet())) {
            Map<String, Host> hosts = state.get(c);
            if (c == null || hosts == null) {
                continue;
            }
            for (Host h : hosts.values()) {
                try {
                    update(c, h);
                } catch (Throwable t) {
                    fail(t);
                    release(h);
                    h.rounds = new String[0];
                    h.nodes = new String[0];
                }
            }
        }
    }

    static ModelInstance host(IsoGameCharacter c, Host h) {
        if (c.isDead() || h.model == null) {
            return null;
        }
        if ("weapon".equals(h.kind)) {
            ModelInstance root = c.primaryHandModel;
            return root != null && h.model.equals(ReloadVisualProbe.name(root)) ? root : null;
        }
        if ("hand".equals(h.kind)) {
            ModelInstance hand = c.secondaryHandModel;
            return hand != null && h.model.equals(ReloadVisualProbe.name(hand)) ? hand : null;
        }
        ModelInstance root = c.primaryHandModel;
        if (root == null || h.anchor == null) {
            return null;
        }
        for (ModelInstance child : root.sub) {
            if (child != null && h.anchor.equals(child.attachmentNameParent) && h.model.equals(ReloadVisualProbe.name(child))) {
                return child;
            }
        }
        return null;
    }

    private static void update(IsoGameCharacter c, Host h) {
        ModelInstance parent = host(c, h);
        if (parent != h.parent) {
            release(h);
        }
        if (parent == null || parent.modelScript == null || parent.model == null || !parent.model.isReady()) {
            return;
        }
        h.parent = parent;
        h.owner = parent.getOwner();
        if (h.children.length != h.rounds.length) {
            ModelInstance[] grown = new ModelInstance[h.rounds.length];
            for (int i = 0; i < h.children.length; i++) {
                if (i < grown.length) {
                    grown[i] = h.children[i];
                } else {
                    drop(h, h.children[i]);
                }
            }
            h.children = grown;
        }
        for (int i = 0; i < h.rounds.length; i++) {
            ModelInstance child = h.children[i];
            String round = h.rounds[i], node = h.nodes[i];
            boolean right = child != null && owns(h, child) && round != null && round.equals(ReloadVisualProbe.name(child))
                    && node != null && node.equals(child.attachmentNameParent);
            if (right) {
                continue;
            }
            if (child != null) {
                if (!owns(h, child)) {
                    resets++;
                }
                drop(h, child);
                h.children[i] = null;
            }
            if (round == null || node == null || parent.modelScript.getAttachmentById(node) == null || !ready(round)) {
                continue;
            }
            ModelInstance made = ModelManager.instance.addStatic(parent, round, SELF, node);
            if (made != null) {
                if (h.owner != null) {
                    made.setOwner(h.owner);
                }
                h.children[i] = made;
                added++;
            }
        }
    }

    /** Ours and still in place: a model reset may have returned the object to the pool. */
    static boolean owns(Host h, ModelInstance child) {
        return child != null && h.parent != null && child.parent == h.parent && child.getOwner() == h.owner
                && SELF.equals(child.attachmentNameSelf) && h.parent.sub.contains(child);
    }

    private static void drop(Host h, ModelInstance child) {
        if (child != null && owns(h, child)) {
            h.parent.sub.remove(child);
            ModelManager.instance.resetModelInstance(child, h.owner);
            removed++;
        }
    }

    private static void release(Host h) {
        for (ModelInstance child : h.children) {
            drop(h, child);
        }
        h.children = new ModelInstance[0];
        h.parent = null;
        h.owner = null;
    }

    private static boolean ready(String name) {
        Boolean known = readyModels.get(name);
        if (Boolean.TRUE.equals(known)) {
            return true;
        }
        ModelScript script = ScriptManager.instance.getModelScript(name);
        ModelManager manager = ModelManager.instance;
        if (script == null || manager == null || script.getAttachmentById(SELF) == null) {
            return false;
        }
        Model model = manager.tryGetLoadedModel(script, true);
        if (model == null) {
            model = manager.loadStaticModel(script.getMeshName(), script.getTextureName(), script.getShaderName());
        }
        boolean ok = model != null && model.isReady() && model.mesh != null && model.mesh.isReady();
        if (ok) {
            readyModels.put(name, true);
        }
        return ok;
    }

    private static void fail(Throwable t) {
        errors++;
        String message = String.valueOf(t);
        if (!message.equals(lastError)) {
            lastError = message;
            System.out.println("[TW] cartridge visual: " + message);
        }
    }

    /** QA: rounds drawn now per host of a character, "weapon=1 magazine=5 hand=0", and the counters. */
    public static synchronized String describe(IsoGameCharacter c) {
        StringBuilder s = new StringBuilder();
        Map<String, Host> hosts = c == null ? null : state.get(c);
        for (String kind : new String[] {"weapon", "magazine", "hand"}) {
            Host h = hosts == null ? null : hosts.get(kind);
            int n = 0, want = 0;
            if (h != null) {
                for (ModelInstance child : h.children) {
                    if (owns(h, child)) {
                        n++;
                    }
                }
                for (String r : h.rounds) {
                    if (r != null) {
                        want++;
                    }
                }
            }
            s.append(kind).append('=').append(n).append('/').append(want).append(' ');
        }
        return s.append("added=").append(added).append(" removed=").append(removed).append(" resets=").append(resets)
                .append(" errors=").append(errors).toString();
    }
}
