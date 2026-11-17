package pz.escapefromzomboidfirearms.zb;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.inventory.types.HandWeapon;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.ModelScript;

/** A seated magazine before the inventory receipt, never an inventory attachment. */
@Exposer.LuaClass(name="TWReloadMagazineNative")
public final class ReloadMagazineVisual {
    private static final Map<IsoGameCharacter, Request> requests = new WeakHashMap<>();
    public static long added, handedOff, cancelled, errors;
    private static final String SELF = "Bip01_Prop2";

    private static final class Request {
        final HandWeapon item;
        final String model, anchor;
        long expires = Long.MAX_VALUE;
        ModelInstance parent, child;
        Object owner;
        Request(HandWeapon item, String model, String anchor) { this.item=item;this.model=model;this.anchor=anchor; }
    }

    private ReloadMagazineVisual() { }

    private static boolean ready(String name) {
        ModelScript script = ScriptManager.instance.getModelScript(name);
        ModelManager manager = ModelManager.instance;
        if (script == null || manager == null || script.getAttachmentById(SELF) == null) return false;
        Model model = manager.tryGetLoadedModel(script, true);
        if (model == null) model = manager.loadStaticModel(script.getMeshName(), script.getTextureName(), script.getShaderName());
        return model != null && model.isReady() && model.mesh != null && model.mesh.isReady();
    }

    public static synchronized boolean seat(IsoGameCharacter c, HandWeapon item, String model, String anchor) {
        if (c == null || c.getPrimaryHandItem() != item || model == null || anchor == null) return false;
        try {
            if (!ready(model)) return false;
            Request old = requests.get(c);
            if (old != null && old.item == item && old.model.equals(model) && old.anchor.equals(anchor)) return true;
            if (old != null) remove(old);
            requests.put(c, new Request(item, model, anchor));
            return true;
        } catch (Throwable failure) { reject(c, failure);return false; }
    }

    private static boolean owns(Request r) {
        return r.child != null && r.parent != null && r.child.parent == r.parent
                && r.child.getOwner() == r.owner && SELF.equals(r.child.attachmentNameSelf)
                && r.anchor.equals(r.child.attachmentNameParent) && r.model.equals(ReloadVisualProbe.name(r.child))
                && r.parent.sub.contains(r.child);
    }

    private static void remove(Request r) {
        // A native model reset may have returned this object to the pool. Check
        // its attachment and owner as well as identity before touching it.
        if (owns(r)) {
            r.parent.sub.remove(r.child);
            ModelManager.instance.resetModelInstance(r.child, r.owner);
        }
        r.child=null;r.parent=null;r.owner=null;
    }

    public static synchronized void off(IsoGameCharacter c, HandWeapon item) {
        Request r=requests.get(c);
        if (r == null || item != null && r.item != item) return;
        try { remove(r);requests.remove(c);cancelled++; }
        catch (Throwable failure) { reject(c,failure); }
    }

    public static synchronized void complete(IsoGameCharacter c, HandWeapon item) {
        Request r=requests.get(c);
        if (r != null && r.item == item) r.expires=System.nanoTime()+10_000_000_000L;
    }

    private static boolean realPart(ModelInstance root, Request r) {
        if (root == null) return false;
        for (ModelInstance child : root.sub) {
            if (child == r.child && owns(r)) continue;
            if (r.model.equals(ReloadVisualProbe.name(child)) && child.model != null && child.model.isReady()) return true;
        }
        return false;
    }

    private static void update(IsoGameCharacter c, Request r) {
        if (c.getPrimaryHandItem() != r.item || c.isDead() || System.nanoTime() > r.expires) { off(c,r.item);return; }
        ModelInstance root=c.primaryHandModel;
        if (root == null || root.modelScript == null) return;
        if (r.parent != null && (r.parent != root || !owns(r))) remove(r);
        if (realPart(root,r)) {
            remove(r);requests.remove(c);handedOff++;return;
        }
        // The hand disappears through the ordinary native action override. Do
        // not add a second copy while that very model is still on Prop2.
        if (ReloadVisualProbe.count(c.secondaryHandModel,r.model,0)>0) return;
        if (owns(r)) return;
        if (!ReloadVisualProbe.name(root).equals(r.item.getWeaponSprite())
                || root.modelScript.getAttachmentById(r.anchor)==null || !ready(r.model)) return;
        r.parent=root;r.owner=root.getOwner();
        r.child=ModelManager.instance.addStatic(root,r.model,SELF,r.anchor);
        if (r.child == null) throw new IllegalStateException("seated magazine model unavailable after preload");
        if (r.owner != null) r.child.setOwner(r.owner);
        added++;
    }

    public static synchronized void tick() {
        for (IsoGameCharacter c : new ArrayList<>(requests.keySet())) {
            Request r=requests.get(c);
            if (c == null || r == null) continue;
            try { update(c,r); }
            catch (Throwable failure) { reject(c,failure); }
        }
    }

    /**
     * W10 maghandmp: another player's weapon during its action, as this client draws it. B42 sends the action's hand
     * models by name (PlayerActionsState.setParams: the item's getStaticModel()) and the observer rebuilds them by name
     * (NetworkCharacterAI.setOverride -> forceNullOverride, overridePrimaryHandModel -> ModelManager
     * .DoCharacterModelEquipped: addStatic(slot, name, Bip01_Prop1)). The equipped path adds the weapon's part models
     * (addWeaponPartModels); that one does not, so a magazine check, eject, insert, drop or rack of another player showed
     * our weapon as its bare base mesh (the rifle "vanished" from the hands). Give that instance the part models the
     * equipped path gives, per installed part its ModelWeaponPart at its attachments, kept to the weapon's current list
     * (a reload's visible assembly, TW/ReloadVisual) until the engine rebuilds the hands when the action ends.
     * The name is the owner's render copy (TW/Render pool, M_<tpl>_Pnn: getStaticModel() is the weaponSprite); this
     * client's copy of the same weapon may have another index, configured for another assembly: such a name is
     * retargeted to this client's own copy first, and the engine rebuilds the hands by it.
     */
    private static final Map<IsoGameCharacter, Parts> remote = new WeakHashMap<>();
    public static long partsAdded, partsRemoved, retargeted;

    private static final class Parts {
        ModelInstance root;
        final Map<String, ModelInstance> drawn = new java.util.HashMap<>();
    }

    private static boolean drawnByName(IsoGameCharacter c, HandWeapon w, ModelInstance root) {
        String name = c.overridePrimaryHandModel;
        return name != null && !name.isEmpty() && root != null && root.modelScript != null
                && name.equals(w.getStaticModel()) && root.modelScript == ScriptManager.instance.getModelScript(name);
    }

    /** own when name is another render copy of the same weapon (TW/Render: M_<tpl>_Pnn), else null. */
    static String otherCopy(String name, String own) {
        int at = own == null ? -1 : own.lastIndexOf("_P");
        if (name == null || at <= 0 || name.equals(own) || name.length() <= at + 2 || !name.startsWith(own.substring(0, at + 2))) return null;
        return name.substring(at + 2).chars().allMatch(Character::isDigit) ? own : null;
    }

    public static synchronized int remoteParts(IsoGameCharacter c) {
        if (c == null || c.isLocal() || !(c.getPrimaryHandItem() instanceof HandWeapon w)
                || !w.getFullType().startsWith("EscapeFromZomboidFirearms.")) {
            if (c != null) remote.remove(c);
            return 0;
        }
        ModelInstance root = c.primaryHandModel;
        Parts p = remote.get(c);
        String own = otherCopy(c.overridePrimaryHandModel, w.getStaticModel());
        if (own != null) {
            c.overridePrimaryHandModel = own; // this weapon, the owner's copy index: this client's copy of it
            c.resetModelNextFrame();
            remote.remove(c);retargeted++;
            return 0;
        }
        if (!drawnByName(c, w, root)) {
            if (p != null) remote.remove(c); // the engine rebuilt the hands: our part models went with that instance
            return 0;
        }
        try {
            if (p == null || p.root != root) { p = new Parts();p.root = root;remote.put(c, p); }
            Map<String, zombie.scripting.objects.ModelWeaponPart> want = new java.util.LinkedHashMap<>();
            ArrayList<zombie.scripting.objects.ModelWeaponPart> models = w.getModelWeaponPart();
            if (models != null) for (zombie.inventory.types.WeaponPart part : w.getAllWeaponParts()) {
                for (zombie.scripting.objects.ModelWeaponPart m : models) {
                    if (m != null && m.modelName != null && part.getFullType().equals(m.partType)) {
                        want.put(m.modelName + "|" + m.attachmentNameSelf + "|" + m.attachmentParent, m);
                    }
                }
            }
            for (java.util.Iterator<Map.Entry<String, ModelInstance>> it = p.drawn.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, ModelInstance> e = it.next();
                ModelInstance child = e.getValue();
                if (want.containsKey(e.getKey()) && root.sub.contains(child)) continue;
                if (root.sub.remove(child)) ModelManager.instance.resetModelInstance(child, root.getOwner());
                it.remove();partsRemoved++;
            }
            int n = 0;
            for (Map.Entry<String, zombie.scripting.objects.ModelWeaponPart> e : want.entrySet()) {
                if (p.drawn.containsKey(e.getKey())) continue;
                zombie.scripting.objects.ModelWeaponPart m = e.getValue();
                boolean engine = false; // a build of the game that draws them itself: never twice
                for (ModelInstance child : root.sub) {
                    engine |= !p.drawn.containsValue(child) && m.modelName.equals(ReloadVisualProbe.name(child))
                            && java.util.Objects.equals(m.attachmentNameSelf, child.attachmentNameSelf)
                            && java.util.Objects.equals(m.attachmentParent, child.attachmentNameParent);
                }
                if (engine) continue;
                ModelInstance child = ModelManager.instance.addStatic(root, m.modelName, m.attachmentNameSelf, m.attachmentParent);
                if (child == null) continue;
                if (root.getOwner() != null) child.setOwner(root.getOwner());
                p.drawn.put(e.getKey(), child);n++;partsAdded++;
            }
            return n;
        } catch (Throwable failure) {
            errors++;remote.remove(c);
            System.out.println("[TW] remote weapon parts rejected: "+failure);
            return 0;
        }
    }

    /**
     * The magazine in another player's support hand through its reload track (TW_ReloadVisual.observe): the owner names
     * it mid-action (setOverrideHandModels at TWMagGrab/TWMagDraw), which B42 never sends - only the action's start
     * (PlayerActionsState.setParams). While the engine draws that player's action hands by name, this client names the
     * same magazine model on Bip01_Prop2; null clears it. False outside such an action.
     */
    public static synchronized boolean remoteHand(IsoGameCharacter c, String model) {
        if (!ours(c) || c.overridePrimaryHandModel == null || c.overridePrimaryHandModel.isEmpty()) return false;
        if (model != null && !ready(model)) return false;
        if (java.util.Objects.equals(model, c.overrideSecondaryHandModel)) return true;
        c.overrideSecondaryHandModel = model;
        c.resetModelNextFrame();
        hands++;
        return true;
    }
    public static long hands;

    /** Another player holding this mod's weapon: only its items carry the module name (none without the mod's scripts). */
    private static boolean ours(IsoGameCharacter c) {
        return c != null && !c.isLocal() && c.getPrimaryHandItem() instanceof HandWeapon w
                && w.getFullType().startsWith("EscapeFromZomboidFirearms.");
    }

    /** QA: ModelManager hand builds seen by the advice below, and those of another player's mod weapon by name. */
    public static long buildsSeen, buildsActed;

    /** ModelManager is about to build a character's hands: another player's weapon by this client's copy of it (a
     *  reset asked for from inside the build would be dropped: update() clears its list after it). Every zombie and
     *  every local rebuild passes here: a field test first, nothing allocated before it. */
    public static void handsBuilding(Object o) {
        buildsSeen++;
        if (!(o instanceof zombie.characters.IsoPlayer c) || c.overridePrimaryHandModel == null || !ours(c)) return;
        buildsActed++;
        String own = otherCopy(c.overridePrimaryHandModel, ((HandWeapon) c.getPrimaryHandItem()).getStaticModel());
        if (own != null) { c.overridePrimaryHandModel = own;retargeted++; }
    }

    /** ... and has built them: the part models in the same frame (TW_ReloadVisual's tick keeps them to the list). */
    public static void handsBuilt(Object o) {
        if (o instanceof zombie.characters.IsoPlayer c && c.overridePrimaryHandModel != null && ours(c)) remoteParts(c);
    }

    @Patch(className = "zombie.core.skinnedmodel.ModelManager", methodName = "Reset")
    public static class HandsReset {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object c) { ReloadMagazineVisual.handsBuilding(c); }
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) Object c) { ReloadMagazineVisual.handsBuilt(c); }
    }

    @Patch(className = "zombie.core.skinnedmodel.ModelManager", methodName = "Add")
    public static class HandsAdd {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object c) { ReloadMagazineVisual.handsBuilding(c); }
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) Object c) { ReloadMagazineVisual.handsBuilt(c); }
    }

    @Patch(className = "zombie.core.skinnedmodel.ModelManager", methodName = "ResetCharacterEquippedHands")
    public static class HandsEquipped {
        @Patch.OnEnter
        public static void enter(@Patch.Argument(0) Object c) { ReloadMagazineVisual.handsBuilding(c); }
        @Patch.OnExit
        public static void exit(@Patch.Argument(0) Object c) { ReloadMagazineVisual.handsBuilt(c); }
    }

    /** QA: the part models under a character's drawn weapon (-1: no weapon model). */
    public static int handParts(IsoGameCharacter c) {
        ModelInstance root = c == null ? null : c.primaryHandModel;
        return root == null ? -1 : root.sub.size();
    }

    public static synchronized String describe() {
        return "active="+requests.size()+" added="+added+" handedOff="+handedOff+" cancelled="+cancelled+" errors="+errors
                +" remote="+remote.size()+" partsAdded="+partsAdded+" partsRemoved="+partsRemoved+" retargeted="+retargeted+" hands="+hands+" builds="+buildsSeen+"/"+buildsActed;
    }

    private static void reject(IsoGameCharacter c, Throwable failure) {
        errors++;
        Request r=requests.remove(c);
        if (r != null) try { remove(r); } catch (Throwable ignored) { }
        System.out.println("[TW] seated magazine visual rejected: "+failure);
    }
}
