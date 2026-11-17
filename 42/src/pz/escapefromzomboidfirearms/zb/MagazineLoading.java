package pz.escapefromzomboidfirearms.zb;

import java.lang.reflect.Modifier;
import java.util.WeakHashMap;

import me.zed_0xff.zombie_buddy.Exposer;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoGameCharacter;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.ModelInstanceRenderData;
import zombie.inventory.InventoryItem;
import zombie.scripting.ScriptManager;
import zombie.scripting.objects.ModelAttachment;
import zombie.scripting.objects.ModelScript;

/** Loading-only TW aliases on the actual hand, independent of the root-child prop bone. */
@Exposer.LuaClass(name = "TWMagazineLoadingNative")
public class MagazineLoading {
    private static final String PREFIX = "EscapeFromZomboidFirearms.ML_", ROUND_PREFIX = "EscapeFromZomboidFirearms.MLA_",
            HAND = "Bip01_L_Hand", RIGHT = "Bip01_R_Hand";
    private static final WeakHashMap<IsoGameCharacter, Request> requests = new WeakHashMap<>();
    /** Any request (read before the monitor by afterPose, which every AnimationPlayer passes every frame). */
    private static volatile boolean anyRequest;
    private static final java.util.regex.Pattern FEED_DISTANCE = java.util.regex.Pattern.compile("roundFeedDistanceCm=([0-9.Ee+\\-]+)");
    private static volatile boolean enabled = verify();
    private static boolean logged;
    private static long bound, errors;

    private static final class Request {
        InventoryItem magazine;
        String model, round;
        boolean roundHeld = true;
        int transfers, unmeasured;
        double worstInsertCm;
        String lastInsertion = "none";
    }

    private static void field(Class<?> owner, String name, Class<?> type) throws Exception {
        var f = owner.getField(name);
        boolean stat = owner == ScriptManager.class && name.equals("instance");
        boolean writable = owner == ModelInstance.class && (name.equals("parentBone") || name.equals("parentBoneName")
                || name.equals("attachmentNameSelf") || name.equals("attachmentNameParent"));
        if (f.getType() != type || !Modifier.isPublic(f.getModifiers()) || Modifier.isStatic(f.getModifiers()) != stat
                || (writable && Modifier.isFinal(f.getModifiers()))) {
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }
    }

    private static void method(Class<?> owner, String name, Class<?> result, Class<?>... args) throws Exception {
        var m = owner.getMethod(name, args);
        if (m.getReturnType() != result || !Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) {
            throw new NoSuchMethodException(owner.getName() + "." + name);
        }
    }

    private static boolean verify() {
        try {
            for (Class<?> c : new Class<?>[] {ModelInstance.class, AnimationPlayer.class, IsoGameCharacter.class,
                    InventoryItem.class, ModelScript.class, ModelAttachment.class, ScriptManager.class}) {
                if (!Modifier.isPublic(c.getModifiers())) throw new IllegalAccessException(c.getName());
            }
            method(ModelInstance.class, "applyModelScriptScale", void.class, String.class);
            field(ModelInstance.class, "character", IsoGameCharacter.class);
            field(ModelInstance.class, "parent", ModelInstance.class);
            field(ModelInstance.class, "animPlayer", AnimationPlayer.class);
            field(ModelInstance.class, "parentBone", int.class);
            field(ModelInstance.class, "parentBoneName", String.class);
            field(ModelInstance.class, "attachmentNameSelf", String.class);
            field(ModelInstance.class, "attachmentNameParent", String.class);
            field(ModelInstance.class, "modelScript", ModelScript.class);
            field(ModelInstance.class, "scale", float.class);
            field(IsoGameCharacter.class, "secondaryHandModel", ModelInstance.class);
            field(IsoGameCharacter.class, "primaryHandModel", ModelInstance.class);
            method(AnimationPlayer.class, "getSkinningBoneIndex", int.class, String.class, int.class);
            method(InventoryItem.class, "getFullType", String.class);
            field(ScriptManager.class, "instance", ScriptManager.class);
            method(ScriptManager.class, "getModelScript", ModelScript.class, String.class);
            method(ModelScript.class, "getScriptObjectFullType", String.class);
            method(ModelScript.class, "getMeshName", String.class);
            method(ModelScript.class, "getTextureName", String.class);
            method(ModelScript.class, "getShaderName", String.class);
            method(ModelScript.class, "getAttachmentById", ModelAttachment.class, String.class);
            field(ModelScript.class, "scale", float.class);
            method(ModelAttachment.class, "getOffset", org.joml.Vector3f.class);
            method(ModelAttachment.class, "getRotate", org.joml.Vector3f.class);
            return true;
        } catch (Throwable e) {
            disable(e);
            return false;
        }
    }

    private static void disable(Throwable cause) {
        enabled = false;
        errors++;
        requests.clear();
        anyRequest = false;
        if (!logged) {
            logged = true;
            System.out.println("[TW] magazine loading hand hook off; vanilla display: " + cause);
        }
    }

    public static boolean ready() { return enabled; }

    /** A scoped display request from our action wrapper; never changes inventory or rounds. */
    public static synchronized boolean begin(IsoGameCharacter character, InventoryItem magazine, String alias) {
        if (!enabled || character == null || magazine == null || alias == null || !alias.startsWith(PREFIX)) return false;
        try {
            String tpl = alias.substring(PREFIX.length());
            String stem = "EscapeFromZomboidFirearms.P_" + tpl, full = magazine.getFullType();
            if (!tpl.matches("[0-9a-f]{24}") || !(full.equals(stem) || full.startsWith(stem + "_"))) return false;
            ModelScript model = ScriptManager.instance.getModelScript(alias);
            ModelScript source = ScriptManager.instance.getModelScript("EscapeFromZomboidFirearms.M_" + tpl);
            if (model == null || source == null) return false;
            if (model.scale != 1 || source.scale != 1 || !model.getMeshName().equals(source.getMeshName())
                    || !model.getTextureName().equals(source.getTextureName())
                    || !model.getShaderName().equals(source.getShaderName()) || model.getAttachmentById(HAND) == null) {
                throw new IllegalStateException("loading alias differs from its delivered item model: " + alias);
            }
            Request r = new Request();
            r.magazine = magazine;
            r.model = alias;
            requests.put(character, r);
            anyRequest = true;
            return true;
        } catch (Throwable e) {
            disable(e);
            return false;
        }
    }

    public static synchronized void finish(IsoGameCharacter character, InventoryItem magazine) {
        Request r = requests.get(character);
        if (r != null && r.magazine == magazine) requests.remove(character);
        anyRequest = !requests.isEmpty();
    }

    /** Display the selected Tarkov cartridge only while the native loop carries it. */
    public static synchronized boolean selectRound(IsoGameCharacter character, String alias) {
        Request r = requests.get(character);
        if (!enabled || r == null) return false;
        if (alias == null) { r.round = null; return true; }
        if (!alias.startsWith(ROUND_PREFIX)) return false;
        try {
            ModelScript model = ScriptManager.instance.getModelScript(alias);
            if (model == null || model.getMeshName() == null) return false;
            String mesh = model.getMeshName();
            if (!mesh.matches("TW/[0-9a-f]{24}")) return false;
            ModelScript source = ScriptManager.instance.getModelScript("EscapeFromZomboidFirearms.M_" + mesh.substring(3));
            if (source == null || model.scale != 1 || source.scale != 1 || model.getAttachmentById(RIGHT) == null
                    || model.getAttachmentById(HAND) == null
                    || !mesh.equals(source.getMeshName()) || !model.getTextureName().equals(source.getTextureName())
                    || !model.getShaderName().equals(source.getShaderName())) return false;
            r.round = alias;
            return true;
        } catch (Throwable e) { disable(e); return false; }
    }

    /** Rebind the existing prop at insertion; resetting hand models restarts native tracks. */
    public static synchronized void setRoundHeld(IsoGameCharacter character, boolean held) {
        Request r = requests.get(character);
        if (r == null || r.round == null) return;
        r.roundHeld = held;
        bind(character.primaryHandModel, null);
    }

    /** Final native pose, after all arm layers. AnimEvents precede these bones and
     * their loop latches can lag the authored track; neither is a pose clock. */
    public static void afterPose(Object object) {
        if (AnimOptOut.blocks(object)) return; // W8 animopt: the cartridge in the hand belongs to vanilla/other mods
        if (enabled && anyRequest) afterPoseLocked(object);
    }

    private static synchronized void afterPoseLocked(Object object) {
        if (!enabled) return;
        try {
            AnimationPlayer ap = (AnimationPlayer) object;
            if (ap.parentPlayer != null) return;
            IsoGameCharacter character = ap.getIsoGameCharacter();
            Request r = requests.get(character);
            if (r == null || r.round == null || ap.getMultiTrack() == null) return;
            AnimationTrack best = null;
            for (AnimationTrack track : ap.getMultiTrack().getTracks()) {
                String name = track.getName();
                if (name != null && name.startsWith("Bob_IdleLoadMagazine")
                        && (best == null || track.getBlendWeight() > best.getBlendWeight())) best = track;
            }
            if (best == null) return;
            boolean held = best.getCurrentTimeFraction() < .5f;
            if (r.roundHeld && !held) {
                ModelInstance magazine = character.secondaryHandModel;
                ModelAttachment feed = magazine == null || magazine.modelScript == null ? null : magazine.modelScript.getAttachmentById("loadingFeed");
                if (best.getBlendWeight() > .95f && feed != null) {
                    String geometry = geometry(character, feed.getOffset().z);
                    var match = FEED_DISTANCE.matcher(geometry);
                    if (geometry.contains("roundBone=" + RIGHT) && match.find()) {
                        r.transfers++;
                        r.worstInsertCm = Math.max(r.worstInsertCm, Double.parseDouble(match.group(1)));
                        r.lastInsertion = geometry;
                    } else r.unmeasured++;
                } else r.unmeasured++;
            }
            setRoundHeld(character, held);
        } catch (Throwable e) { disable(e); }
    }

    public static synchronized String proof(IsoGameCharacter character) {
        Request r = requests.get(character);
        return r == null ? "transfers=0 request=none" : "transfers=" + r.transfers + " unmeasured=" + r.unmeasured
                + " worstInsertCm=" + r.worstInsertCm + " last={" + r.lastInsertion + "}";
    }

    /** Reapply to a replacement instance after an engine model reset, before the next draw. */
    public static synchronized void refresh(IsoGameCharacter character) {
        if (enabled && character != null) {
            bind(character.secondaryHandModel, null);
            bind(character.primaryHandModel, null);
        }
    }

    // Called by inlined advice: this class and method must stay PUBLIC.
    public static synchronized void onScale(Object instance, String alias) {
        if (enabled && alias != null && (alias.startsWith(PREFIX) || alias.startsWith(ROUND_PREFIX))) bind((ModelInstance) instance, alias);
    }

    private static void bind(ModelInstance model, String alias) {
        if (model == null) return;
        try {
            Request r = requests.get(model.character);
            if (r == null || model.modelScript == null || model.parent == null || model.animPlayer == null) return;
            String name = alias != null ? alias : model.modelScript.getScriptObjectFullType();
            boolean round = name.equals(r.round);
            if ((!round && !r.model.equals(name)) || !name.equals(model.modelScript.getScriptObjectFullType())) return;
            String handName = round && r.roundHeld ? RIGHT : HAND;
            if (model.modelScript.getAttachmentById(handName) == null || model.scale != 1) {
                throw new IllegalStateException("loading alias lost its attachment/scale");
            }
            int hand = model.animPlayer.getSkinningBoneIndex(handName, -1);
            if (hand < 0) throw new IllegalStateException("loading skeleton has no " + handName);
            boolean changed = model.parentBone != hand || !handName.equals(model.parentBoneName)
                    || !handName.equals(model.attachmentNameSelf) || model.attachmentNameParent != null;
            // No skeleton or shared ModelScript mutation: native and VP drawing use the
            // final hand transform, even when the FP layer has moved it after animation.
            model.parentBone = hand;
            model.parentBoneName = handName;
            model.attachmentNameSelf = handName;
            model.attachmentNameParent = null;
            if (changed) bound++;
        } catch (Throwable e) {
            disable(e);
        }
    }

    public static synchronized String describe(IsoGameCharacter character) {
        if (!enabled) return "native=off request=none errors=" + errors;
        Request r = requests.get(character);
        ModelInstance model = character == null ? null : character.secondaryHandModel;
        ModelAttachment attachment = model == null || model.modelScript == null ? null : model.modelScript.getAttachmentById(HAND);
        return "native=" + (enabled ? "ready" : "off") + " request=" + (r == null ? "none" : r.model)
                + " bone=" + (model == null ? "pending" : model.parentBoneName)
                + " offset=" + (attachment == null ? "pending" : attachment.getOffset())
                + " rotate=" + (attachment == null ? "pending" : attachment.getRotate())
                + " scale=" + (model == null ? "pending" : model.scale) + " bound=" + bound + " errors=" + errors
                + " invertSelf=" + ModelInstanceRenderData.invertAttachmentSelfTransform;
    }

    /** Read-only QA: final hand frames and the native self-attachment convention. */
    public static synchronized String geometry(IsoGameCharacter character, double mouthZ) {
        try {
            ModelInstance model = character == null ? null : character.secondaryHandModel;
            if (model == null || model.animPlayer == null || model.modelScript == null) return "geometry=pending";
            AnimationPlayer ap = model.animPlayer;
            int hand = ap.getSkinningBoneIndex(HAND, -1), thumb = ap.getSkinningBoneIndex("Bip01_R_Finger0Nub", -1);
            String rightBone = thumb < 0 ? "Bip01_R_Hand" : "Bip01_R_Finger0Nub";
            if (hand < 0 || ap.getSkinningBoneIndex(rightBone, -1) < 0) return "geometry=missing-bone";
            ModelAttachment attachment = model.modelScript.getAttachmentById(model.attachmentNameSelf);
            if (attachment == null) return "geometry=no-attachment";
            org.joml.Matrix4f anchor = ModelInstanceRenderData.makeAttachmentTransform(attachment, new org.joml.Matrix4f());
            if (ModelInstanceRenderData.invertAttachmentSelfTransform) anchor.invert();
            org.joml.Vector3f mouth = anchor.transformPosition(new org.joml.Vector3f(0, 0, (float) mouthZ));
            org.joml.Matrix4f bone = new org.joml.Matrix4f();
            ModelInstanceRenderData.makeBoneTransform(ap, HAND, bone);
            bone.transformPosition(mouth);
            ModelInstanceRenderData.makeBoneTransform(ap, rightBone, bone);
            org.joml.Vector3f right = bone.getTranslation(new org.joml.Vector3f());
            String cartridge = " round=hidden";
            Request request = requests.get(character);
            ModelInstance round = character.primaryHandModel;
            if (request != null && request.round != null && round != null && round.modelScript != null
                    && request.round.equals(round.modelScript.getScriptObjectFullType())) {
                ModelAttachment grip = round.modelScript.getAttachmentById(round.attachmentNameSelf), centre = round.modelScript.getAttachmentById("loadingCentre");
                if (grip != null && centre != null) {
                    org.joml.Matrix4f transform = ModelInstanceRenderData.makeAttachmentTransform(grip, new org.joml.Matrix4f());
                    if (ModelInstanceRenderData.invertAttachmentSelfTransform) transform.invert();
                    org.joml.Vector3f point = transform.transformPosition(new org.joml.Vector3f(centre.getOffset()));
                    ModelInstanceRenderData.makeBoneTransform(ap, round.parentBoneName, bone);
                    bone.transformPosition(point);
                    cartridge = " round=" + request.round + " roundBone=" + round.parentBoneName + " roundCentre=" + point
                            + " roundFeedDistanceCm=" + mouth.distance(point) * 100 / 0.578;
                }
            }
            return "mouth=" + mouth + " rightBone=" + rightBone + " right=" + right + cartridge
                    + " distanceCm=" + mouth.distance(right) * 100 / 0.578
                    + " bones=" + ap.getSkinningData().numBones() + " invertSelf=" + ModelInstanceRenderData.invertAttachmentSelfTransform;
        } catch (Throwable e) { return "geometry=unavailable " + e; }
    }

    @Patch(className = "zombie.core.skinnedmodel.model.ModelInstance", methodName = "applyModelScriptScale")
    public static class AfterScale {
        @Patch.OnExit
        public static void exit(@Patch.This Object instance, @Patch.Argument(0) String alias) {
            MagazineLoading.onScale(instance, alias);
        }
    }
}
