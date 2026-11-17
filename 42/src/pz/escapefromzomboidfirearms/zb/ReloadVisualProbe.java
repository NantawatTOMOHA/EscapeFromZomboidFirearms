package pz.escapefromzomboidfirearms.zb;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.visual.IHumanVisual;

/** Read-only native scene/track receipt shared by reload observers and scoped QA. */
@Exposer.LuaClass(name="TWReloadVisualNative")
public final class ReloadVisualProbe {
    private ReloadVisualProbe() { }

    public static String name(ModelInstance instance) {
        return instance == null || instance.modelScript == null ? "" : instance.modelScript.getScriptObjectFullType();
    }

    public static int count(ModelInstance instance, String model, int depth) {
        if (instance == null || depth > 12) return 0;
        int n = model != null && !model.isEmpty() && model.equals(name(instance))
                && instance.model != null && instance.model.isReady() ? 1 : 0;
        for (ModelInstance child : instance.sub) n += count(child, model, depth + 1);
        return n;
    }

    public static KahluaTable snapshot(IsoGameCharacter c, String model) {
        KahluaTable out = LuaManager.platform.newTable();
        if (c == null) return out;
        ModelInstance body = c.getModelInstance();
        out.rawset("body", body == null || body.model == null ? "" : body.model.name);
        var visual = c instanceof IHumanVisual v ? v.getHumanVisual() : null;
        if (visual != null) {
            var expected = visual.getModelScript();
            out.rawset("bodyScript", expected == null ? "" : expected.getScriptObjectFullType());
            out.rawset("bodyMatches", body != null && body.model != null && body.model == visual.getModel());
        }
        ModelInstance root = c.primaryHandModel;
        out.rawset("weapon", (double) count(root, model, 0));
        out.rawset("hand", (double) count(c.secondaryHandModel, model, 0));
        out.rawset("secondary", name(c.secondaryHandModel));
        out.rawset("root", name(root));
        out.rawset("action", c.getVariableString("PerformingAction"));
        var ap = c.getAnimationPlayer();
        AnimationTrack best = null;
        if (ap != null && ap.getMultiTrack() != null) for (AnimationTrack t : ap.getMultiTrack().getTracks()) {
            if (t == null || t.getClip() == null || t.getBlendWeight() <= 0) continue;
            String n = t.getClip().name;
            if (!(n.contains("_Reload_") || n.startsWith("TWInspect_"))) continue;
            if (best == null || t.getBlendWeight() > best.getBlendWeight()) best = t;
        }
        if (best != null) {
            out.rawset("track", best.getClip().name);
            out.rawset("fraction", (double) best.getCurrentTimeValue() / Math.max(1e-6, best.getDuration()));
            out.rawset("weight", (double) best.getBlendWeight());
        }
        return out;
    }
}
