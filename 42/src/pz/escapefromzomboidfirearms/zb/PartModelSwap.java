package pz.escapefromzomboidfirearms.zb;

import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.ModelManager;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.ModelScript;
import zombie.scripting.objects.ModelWeaponPart;

/**
 * One part model of the drawn weapon replaced in place (FB4, 19.10.2026, user's item 9: "switching the magnification
 * of thermal sights jerks the hands and for a second the picture loses the thermal effect"). A sight mode with its own
 * mesh (FLIR RS-32, REAP-IR...) is another render library row of the same part; the full refresh re-created every
 * hand model for it (resetEquippedHandsModels): the hands' pose restarted, the sight was gone for a frame and the scope
 * picture lost its pairing. Here only that part's instance is replaced, the way ModelManager.addWeaponPartModels adds
 * it (addStatic on the weapon instance, the slot as owner), at the same place in the weapon's list; the old instance
 * is retired by resetModelInstanceRecurse, which waits for the frame still drawing it. Main thread (Lua:
 * TW/Render.swapStates). Results: "done"; "same" (already drawn); "none" (no drawn hands: their next build reads the
 * new rows); "wait" (the mesh, or its copy without the glass of the scope picture's window - LensGlass - is still
 * being made; nothing changed); anything else refuses and the caller re-creates the hand models as before.
 */
public final class PartModelSwap {
    public static volatile long swaps, waits, refusals, errors, preloads;
    public static volatile String last = "none";

    private PartModelSwap() { }

    public static String swap(IsoGameCharacter c, String root, ModelWeaponPart from, ModelWeaponPart to) {
        String result;
        try {
            result = run(c, root, from, to);
        } catch (Throwable t) {
            errors++;
            result = "error " + t;
        }
        switch (result) {
            case "done" -> swaps++;
            case "wait" -> waits++;
            case "same", "none" -> { }
            default -> refusals++;
        }
        last = result;
        return result;
    }

    private static String run(IsoGameCharacter c, String root, ModelWeaponPart from, ModelWeaponPart to) {
        if (c == null || root == null || from == null || to == null || to.modelName == null || to.attachmentParent == null) {
            return "arguments";
        }
        if (!to.attachmentParent.equals(from.attachmentParent)) return "anchor differs";
        ModelManager.ModelSlot slot = ModelManager.instance.getSlot(c);
        ModelInstance weapon = c.primaryHandModel;
        if (slot == null || weapon == null) return "none";
        if (weapon.modelScript == null || weapon.modelScript != ScriptManager.instance.getModelScript(root)) return "root";
        int at = -1;
        for (int i = 0; i < weapon.sub.size(); i++) {
            if (to.attachmentParent.equals(weapon.sub.get(i).attachmentNameParent)) {
                if (at >= 0) return "anchor twice";
                at = i;
            }
        }
        if (at < 0) return "no part";
        ModelInstance old = weapon.sub.get(at);
        ModelScript wanted = ScriptManager.instance.getModelScript(to.modelName);
        if (wanted == null) return "no script";
        if (old.modelScript == wanted) return "same";
        if (old.modelScript != ScriptManager.instance.getModelScript(from.modelName)) return "other model";
        Model model = loaded(wanted);
        if (model == null || model.isFailure()) return "no mesh";
        // the mesh and the copy of it without its glass (the scope picture's window): both ready before it is drawn
        if (!model.isReady() || !LensGlass.prepared(model.mesh, wanted.getMeshName())) return "wait";
        ModelInstance fresh = ModelManager.instance.addStatic(weapon, to.modelName, to.attachmentNameSelf, to.attachmentParent);
        if (fresh == null) return "no instance";
        fresh.setOwner(slot);
        // addStatic appended it; the part keeps its place among the weapon's parts
        int end = weapon.sub.size() - 1;
        if (end > at && weapon.sub.get(end) == fresh) {
            weapon.sub.remove(end);
            weapon.sub.set(at, fresh);
        } else {
            weapon.sub.remove(old);
        }
        ModelManager.instance.resetModelInstanceRecurse(old, slot);
        return "done";
    }

    /** The mesh of a model script as ModelManager.addStaticForcedTex finds it; a missing one starts loading. */
    static Model loaded(ModelScript script) {
        String mesh = script.getMeshName(), tex = script.getTextureName(), shader = script.getShaderName();
        if (mesh == null) return null;
        Model model = ModelManager.instance.tryGetLoadedModel(mesh, tex, true, shader, false);
        if (model == null) {
            ModelManager.instance.loadStaticModel(mesh, tex, shader);
            model = ModelManager.instance.getLoadedModel(mesh, tex, true, shader);
        }
        return model;
    }

    /** Starts loading a row's mesh ahead of a mode switch (TW/Render.preloadStates): "ready", "loading" or "missing". */
    public static String preload(ModelWeaponPart row) {
        try {
            ModelScript script = row == null || row.modelName == null ? null : ScriptManager.instance.getModelScript(row.modelName);
            Model model = script == null ? null : loaded(script);
            if (model == null || model.isFailure()) return "missing";
            preloads++;
            return model.isReady() && LensGlass.prepared(model.mesh, script.getMeshName()) ? "ready" : "loading";
        } catch (Throwable t) {
            errors++;
            last = "preload " + t;
            return "missing";
        }
    }

    public static String stats() {
        return "swaps=" + swaps + " waits=" + waits + " refusals=" + refusals + " errors=" + errors + " preloads=" + preloads + " last=" + last;
    }

    public static void reset() {
        swaps = waits = refusals = errors = preloads = 0;
        last = "none";
    }
}
