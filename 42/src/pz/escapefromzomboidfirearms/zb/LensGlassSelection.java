package pz.escapefromzomboidfirearms.zb;

import se.krka.kahlua.vm.KahluaTable;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.model.ModelInstance;

/** The selected physical part, distinct from other copies of the same sight mesh. */
final class LensGlassSelection {
    record Selection(IsoPlayer player, Object weapon, String key, String tpl, String anchor, String profile) { }
    private static volatile Selection selected;
    private static volatile String window = "none";
    /**
     * FB4 (19.10.2026, user's item 9: "switching the magnification of thermal sights ... the picture loses the thermal
     * effect"): a new mode of the open window has no completed image on its first frame, so the sight's own glass was
     * drawn over the scope picture (the FLIR RS-32's is opaque black). The window of the same weapon, sight and channel
     * stays open through the switch (SWITCH_NS at most), its glass cut by the previous mode's profile while the drawn
     * mesh is still that mode's.
     */
    static final long SWITCH_NS = 300_000_000L;
    /** The same in world frames: a frame hitch (a long frame) right before a switch still keeps the window. */
    static final long SWITCH_FRAMES = 6;
    private static volatile Selection open;
    private static volatile long openAt, openFrame;
    static volatile long switches;

    private LensGlassSelection() { }

    static void set(IsoPlayer player, Object weapon, KahluaTable table) {
        selected = table == null || player == null || weapon == null ? null : new Selection(player, weapon,
                text(table, "key"), text(table, "tpl"), text(table, "anchor"), text(table, "profile"));
        window = "none";
    }

    private static String text(KahluaTable table, String key) {
        Object value = table.rawget(key);
        return value instanceof String s ? s : "";
    }

    /** Ignore continuous FOV changes, but never a different physical channel or mode. */
    static boolean sameWindow(Selection s, ScopePip.Request drawn) {
        return s != null && drawn != null && drawn.player == s.player && drawn.weapon == s.weapon
                && s.key.equals(ReticleProfile.selectionKey(drawn.key));
    }

    static boolean matches(Selection s, IsoPlayer player, Object weapon, String tpl, String anchor) {
        return s != null && s.player == player && s.weapon == weapon && !s.key.isEmpty() && !s.profile.isEmpty()
                && s.tpl.equals(tpl) && !s.anchor.isEmpty() && s.anchor.equals(anchor);
    }

    static Selection active(IsoPlayer player, ModelInstance instance, String tpl) {
        Selection s = selected;
        if (!matches(s, player, player.getPrimaryHandItem(), tpl, instance.attachmentNameParent)
                || !HipFireInput.aimIntent(player) || player.isDead()) return null;
        FpPose.Request pose = FpPose.requests.get(player);
        if (!FpPose.enabled || pose == null || pose.item != s.weapon || !pose.allowed || !pose.aiming || pose.acting
                || pose.sight == null || !(pose.ads > 0) || !(pose.fpW > 0)
                || System.nanoTime() - pose.stamp > FpPose.STALE_NS) return null;
        // These are completed draws, not the Lua intent to draw a window. A first
        // image/copy warms behind the original lens; subsequent captures open it.
        if (sameWindow(s, ScopePip.lensWindow())) { window = "pip"; return opened(s); }
        if (sameWindow(s, SightGlass.lensWindow())) { window = "main"; return opened(s); }
        if (VpSensor.lensWindow(s.key + ":")) { window = "sensor"; return opened(s); }
        Selection o = open;
        if (o != null && o != s && o.player == s.player && o.weapon == s.weapon && o.anchor.equals(s.anchor)
                && ReticleProfile.selectionKey(o.key).equals(ReticleProfile.selectionKey(s.key))
                && (System.nanoTime() - openAt < SWITCH_NS || FpRecoil.worldFrame() - openFrame <= SWITCH_FRAMES)) {
            if (!"switch".equals(window)) switches++;
            window = "switch";
            return s;
        }
        window = "none";
        return null;
    }

    private static Selection opened(Selection s) {
        open = s;
        openAt = System.nanoTime();
        openFrame = FpRecoil.worldFrame();
        return s;
    }

    /** While a mode switch keeps the window open: the previous mode's profile (its glass cut on a mesh not yet swapped). */
    static String switchProfile(Selection s) {
        Selection o = open;
        return "switch".equals(window) && o != null && o != s ? o.profile : null;
    }

    /**
     * FB8 item 5: the collimators of the held assembly other than the one aimed through (all of them at the hip) - their
     * glass stays open (TW_SightGlass G.extras); their marks are drawn by SightGlassExtras.
     */
    record Extra(String tpl, String anchor, String profile) { }
    private static volatile Extra[] extras = new Extra[0];
    private static volatile Object extrasWeapon;
    private static volatile IsoPlayer extrasPlayer;

    static void setExtras(IsoPlayer player, KahluaTable list) {
        if (player == null || list == null) {
            extras = new Extra[0];
            extrasWeapon = null;
            return;
        }
        java.util.List<Extra> out = new java.util.ArrayList<>();
        for (int i = 1; i <= Math.min(list.len(), 8); i++) {
            if (list.rawget(i) instanceof KahluaTable t) {
                Extra e = new Extra(text(t, "tpl"), text(t, "anchor"), text(t, "profile"));
                if (!e.tpl.isEmpty() && !e.anchor.isEmpty() && !e.profile.isEmpty()) out.add(e);
            }
        }
        extrasPlayer = player;
        extrasWeapon = player.getPrimaryHandItem();
        extras = out.toArray(new Extra[0]);
    }

    /** The profile whose glass is open on this part when it is another collimator of the held weapon, else null. */
    static String extraProfile(IsoPlayer player, ModelInstance instance, String tpl) {
        if (player != extrasPlayer || player.getPrimaryHandItem() != extrasWeapon || player.isDead()) return null;
        for (Extra e : extras) {
            if (e.tpl.equals(tpl) && e.anchor.equals(instance.attachmentNameParent)) return e.profile;
        }
        return null;
    }

    static String info() {
        Selection s = selected;
        return " selected=" + (s == null ? "none" : s.key) + " window=" + (s == null ? "none" : window) + " switches=" + switches
                + " selectedProfile=" + (s == null ? "none" : s.profile) + " selectedAnchor=" + (s == null ? "none" : s.anchor)
                + " extras=" + extras.length;
    }
}
