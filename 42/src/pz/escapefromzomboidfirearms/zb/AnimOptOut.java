package pz.escapefromzomboidfirearms.zb;

import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.animation.AnimationPlayer;

/**
 * W8 animopt (the user's item 9, 25.10.2026): two client options that give the weapon animations back to
 * vanilla and to other animation mods - one for the third person (the isometric view and Viewpoint's third
 * person), one for Viewpoint's first person. TW_AnimOptOut.lua registers and reads them, keeps them across a
 * restart and calls {@link #set} through TWZB.animOptOut; every pose layer asks {@link #blocks} at its entry
 * and does nothing while its view's option is on.
 *
 * <p>Off by default (the mod poses as before). A layer that returns early writes no bones, no hand props and
 * no manually parented items, so the vanilla animation - and whatever another mod's AnimSets do - stays, and
 * the weapon remains on the vanilla hand bone. Timed actions, ballistics and the sight data are untouched:
 * only the pose/animation layer of that view stops.
 *
 * <p>Which option governs a character: the local player follows the view (Viewpoint's first person is the
 * "first person" option; the isometric view and Viewpoint's third person are the "third person" one); every
 * other character the client draws is always in the third person, so only that option applies to it.
 */
public final class AnimOptOut {
    /** "Third person: vanilla animations" - the isometric view and Viewpoint's third person. */
    public static volatile boolean third;
    /** "First person: vanilla animations" - Viewpoint's first person. */
    public static volatile boolean first;
    /**
     * W9-inertia (Hardship Inertia): characters whose animation another mod owns right now (lying, crawling),
     * pushed from Lua by TWZB.animOptOutYield. The same yield as the options, but for one character; weak keys,
     * so a character that leaves the world is forgotten.
     */
    private static final java.util.Map<IsoGameCharacter, Boolean> YIELDS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** QA: how many layer entries yielded (main thread only). */
    static long yields;

    /** The drawing view, read once per world frame (ViewpointBridge.state is a reflected read). */
    private static long viewFrame = Long.MIN_VALUE;
    private static int viewCache = -1;

    private AnimOptOut() {
    }

    /** TWZB.animOptOut: the two options as Lua read them. */
    public static void set(boolean thirdPerson, boolean firstPerson) {
        third = thirdPerson;
        first = firstPerson;
    }

    /**
     * W9-inertia: TWZB.animOptOutYield - another mod owns this character's animation now (Hardship Inertia's
     * floor postures). Every layer yields for that character exactly as if its view's option were on; Lua clears
     * it when the character is back on its feet. Returns the value now in force.
     */
    public static boolean yieldCharacter(IsoGameCharacter c, boolean on) {
        if (c == null) {
            return false;
        }
        if (on) {
            YIELDS.put(c, Boolean.TRUE);
        } else {
            YIELDS.remove(c);
        }
        return on;
    }

    /** Another mod owns this character's animation now (W9-inertia). */
    public static boolean yielded(IsoGameCharacter c) {
        return c != null && !YIELDS.isEmpty() && YIELDS.containsKey(c);
    }

    /** At least one option is on or one character is yielded: the layers may have to yield. */
    public static boolean any() {
        return third || first || !YIELDS.isEmpty();
    }

    /** 0 isometric (or no Viewpoint), 1 Viewpoint first person, 2 Viewpoint third person; once per world frame. */
    public static int view() {
        long frame = FpRecoil.worldFrame();
        if (frame >= 0 && frame == viewFrame && viewCache >= 0) {
            return viewCache;
        }
        int view = ViewpointBridge.state();
        viewCache = view;
        viewFrame = frame;
        return view;
    }

    /** The option that governs a view: 1 is Viewpoint's first person, 0 the isometric, 2 Viewpoint's third person. */
    public static boolean blocks(int view) {
        if (!third && !first) {
            return false;
        }
        if (view == 1 ? first : third) {
            yields++;
            return true;
        }
        return false;
    }

    /** Do the two W8 "vanilla animations" options themselves forbid posing this character now (excluding per-character yields)? */
    public static boolean optionsBlock(IsoGameCharacter c) {
        if (!third && !first) {
            return false;
        }
        boolean local = c instanceof IsoPlayer p && p.isLocalPlayer() && p.getPlayerNum() == 0;
        return (local ? view() : 2) == 1 ? first : third;
    }

    /** The same for an AnimationPlayer, a character, or null. */
    public static boolean optionsBlock(Object animationPlayerOrCharacter) {
        if (animationPlayerOrCharacter instanceof AnimationPlayer ap) {
            return optionsBlock(ap.getIsoGameCharacter());
        }
        if (animationPlayerOrCharacter instanceof IsoGameCharacter c) {
            return optionsBlock(c);
        }
        return third;
    }

    /** The option that governs this character now (see the class comment); W9-inertia: a yielded character yields. */
    public static boolean blocks(IsoGameCharacter c) {
        if (yielded(c)) {
            yields++;
            return true;
        }
        if (!third && !first) {
            return false;
        }
        boolean local = c instanceof IsoPlayer p && p.isLocalPlayer() && p.getPlayerNum() == 0;
        return blocks(local ? view() : 2);
    }

    /** The same for an AnimationPlayer, a character, or null (unknown: the drawing view's third-person rule). */
    public static boolean blocks(Object animationPlayerOrCharacter) {
        if (animationPlayerOrCharacter instanceof AnimationPlayer ap) {
            return blocks(ap.getIsoGameCharacter());
        }
        if (animationPlayerOrCharacter instanceof IsoGameCharacter c) {
            return blocks(c);
        }
        return third;
    }

    /** Only the sight-alignment entry may run during opted-out first-person Aim, never other animation layers. */
    public static boolean keepsAds(IsoGameCharacter c) {
        if (!first || !(c instanceof IsoPlayer p) || !p.isLocalPlayer() || p.getPlayerNum() != 0
                || view() != 1 || yielded(c)) return false;
        FpPose.Request r = FpPose.requests.get(c);
        return r != null && r.allowed && !r.spent && r.aiming && !r.acting;
    }

    /** FpPose's narrow permission exception; generic blocks() still protects poses, IK, recoil and hand props. */
    public static boolean blocksFpPose(Object animationPlayerOrCharacter) {
        IsoGameCharacter c = animationPlayerOrCharacter instanceof AnimationPlayer ap ? ap.getIsoGameCharacter()
                : animationPlayerOrCharacter instanceof IsoGameCharacter character ? character : null;
        return !keepsAds(c) && blocks(animationPlayerOrCharacter);
    }

    /** The local player's view now (the view-only layers: the first-person shadow, the ADS camera). */
    public static boolean blocksLocalView() {
        return blocks(view());
    }

    /** QA: "third=... first=... yields=... view=...". */
    public static String info() {
        return "third=" + third + " first=" + first + " yields=" + yields + " view=" + view()
                + " characters=" + YIELDS.size();
    }
}
