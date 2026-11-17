package pz.escapefromzomboidfirearms.zb;

import java.util.Set;

import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.model.ModelInstance;

/**
 * Render-thread transaction over draw flags, never the player's live models. The viewer's body stays out of the
 * scope's picture. FB4 (19.10.2026, user's item 15: "in optical sights the weapon model disappears - it must be
 * visible"): the own weapon is drawn there as Tarkov's optic camera sees it (barrel, front sight, devices ahead of
 * the objective); only the active sight's own parts and the mounts carrying it (HIDE, from the Lua request) - the
 * tube and rings round the camera - stay out.
 */
final class ScopePipExclusion implements AutoCloseable {
    /** Part templates kept out of the scope picture: the active sight, its subtree and its mounts (render thread). */
    static volatile Set<String> hide = Set.of();
    static volatile long weaponKept, weaponHidden;
    private final Object draws;
    private final int[] original, filtered;
    private final boolean cameraClear;
    final int excluded;

    ScopePipExclusion(Object scene, Object viewer) throws Throwable {
        if (viewer == null) {
            throw new IllegalArgumentException("PIP viewer missing");
        }
        draws = ViewpointPipBridge.get("SceneData.models", scene);
        cameraClear = (boolean) ViewpointPipBridge.get("CameraClear.on", null);
        original = (int[]) ViewpointPipBridge.get("ModelDraws.flags", draws);
        ModelInstance[] instances = (ModelInstance[]) ViewpointPipBridge.get("ModelDraws.instances", draws);
        int count = (int) ViewpointPipBridge.get("ModelDraws.count", draws);
        if (count < 0 || original == null || instances == null || count > original.length || count > instances.length) {
            throw new IllegalStateException("PIP model table changed");
        }
        int mask = (int) ViewpointPipBridge.get("ModelDraws.COLOUR", null) | (int) ViewpointPipBridge.get("ModelDraws.SHADOW", null);
        if (mask == 0) {
            throw new IllegalStateException("PIP model visibility flags missing");
        }
        int[] scratch = ScopePipCpu.flags(original);
        filtered = scratch == null ? original.clone() : scratch;
        int n = 0;
        ModelInstance weapon = viewer instanceof IsoGameCharacter c ? c.primaryHandModel : null;
        Set<String> parts = hide;
        try {
            for (int i = 0; i < count; i++) {
                if (belongs(instances[i], viewer, weapon, parts)) {
                    filtered[i] &= ~mask;
                    n++;
                }
            }
        } catch (Throwable t) {
            ScopePipCpu.release(filtered);
            throw t;
        }
        excluded = n;
    }

    static boolean belongs(ModelInstance instance, Object viewer) {
        return belongs(instance, viewer, null, Set.of());
    }

    /** Excluded: the viewer's own models, except the parts of the held weapon not in hide (the bare root stays). */
    static boolean belongs(ModelInstance instance, Object viewer, ModelInstance weapon, Set<String> hide) {
        // Parts can have no character of their own. Follow the actual instance
        // tree, so an identical model held by someone else remains visible.
        // The held weapon first: its part instances carry the viewer as their character too.
        if (weapon != null) {
            int up = 0;
            ModelInstance below = null;
            for (ModelInstance p = instance; p != null; p = p.parent) {
                if (p == weapon) {
                    // no list (a sight on the root itself, an older request): the whole weapon stays out, as before
                    boolean out = hide.isEmpty() || below != null && hidden(below.attachmentNameParent, hide);
                    if (out) weaponHidden++; else weaponKept++;
                    return out;
                }
                below = p;
                if (++up > 128) {
                    throw new IllegalStateException("cyclic or unbounded PIP model ancestry");
                }
            }
        }
        int depth = 0;
        for (ModelInstance p = instance; p != null; p = p.parent) {
            if (p.character == viewer) {
                return true;
            }
            if (++depth > 128) {
                throw new IllegalStateException("cyclic or unbounded PIP model ancestry");
            }
        }
        return false;
    }

    /** A part's anchor on the weapon root is "t_<tpl>_<copy>" (TW/Render.anchorName). */
    static boolean hidden(String anchor, Set<String> hide) {
        if (anchor == null || !anchor.startsWith("t_") || hide.isEmpty()) {
            return false;
        }
        int end = anchor.lastIndexOf('_');
        return end > 2 && hide.contains(anchor.substring(2, end));
    }

    void apply() throws Throwable {
        ViewpointPipBridge.put("ModelDraws.flags", draws, filtered);
        ViewpointPipBridge.put("CameraClear.on", null, false);
    }

    @Override
    public void close() throws Exception {
        Throwable failure = null;
        try {
            ViewpointPipBridge.put("ModelDraws.flags", draws, original);
        } catch (Throwable t) {
            failure = t;
        }
        try {
            ViewpointPipBridge.put("CameraClear.on", null, cameraClear);
        } catch (Throwable t) {
            failure = t;
        }
        ScopePipCpu.release(filtered);
        if (failure != null) {
            throw new Exception("PIP model/clearing restore failed", failure);
        }
    }
}
