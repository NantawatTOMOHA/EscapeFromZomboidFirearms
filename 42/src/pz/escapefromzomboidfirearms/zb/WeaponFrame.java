package pz.escapefromzomboidfirearms.zb;

import zombie.characters.IsoGameCharacter;
import zombie.core.math.PZMath;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.iso.Vector3;

/**
 * The real frame of the weapon in a character's hands for the current
 * animation frame: the bone the weapon model hangs on (primaryHandModel,
 * Bip01_Prop1), as ModelInstance.getAttachmentWorldPosition computes it, in
 * the mod's look frame (TW/LaserGeometry P.frame: origin u forward, v left,
 * h up in tile units, then the model X, Y (the bore) and Z axes). Main thread
 * (bones are updated there, before rendering and lighting).
 */
public final class WeaponFrame {
    private static final org.lwjgl.util.vector.Matrix4f BONE = new org.lwjgl.util.vector.Matrix4f();
    private static final org.joml.Matrix4f W = new org.joml.Matrix4f();
    private static final Vector3 V = new Vector3(0, 0, 0);
    private static final double LEVEL = 2.44949;
    public static final double[] F = new double[12];

    private WeaponFrame() {
    }

    private static void put(int o, float x, float y, float z, float px, float py, float pz, float angle) {
        V.x = x;
        V.y = y;
        V.z = z;
        Model.vectorToWorldCoords(px, py, pz, angle, V);
        F[o] = V.x;
        F[o + 1] = V.y;
        F[o + 2] = V.z;
    }

    /** World frame: origin (x, y tiles, z levels) and the images of the model's unit axes. */
    public static boolean world(IsoGameCharacter c) {
        return world(c, null);
    }

    /** The same with an adjustment of the bone in model space (Viewpoint's carried prop, SightDrawFrame.carried), or none. */
    public static boolean world(IsoGameCharacter c, org.joml.Matrix4fc adjust) {
        ModelInstance mi = c == null ? null : c.primaryHandModel;
        if (mi == null || mi.animPlayer == null || !mi.animPlayer.isReady() || mi.parentBone < 0) {
            return false;
        }
        AnimationPlayer ap = mi.animPlayer;
        ap.getBoneModelTransform(mi.parentBone, BONE);
        PZMath.convertMatrix(BONE, W).transpose();
        if (adjust != null) {
            W.set(new org.joml.Matrix4f(adjust).mul(W));
        }
        float a = ap.getRenderedAngle();
        put(0, W.m30(), W.m31(), W.m32(), c.getX(), c.getY(), c.getZ(), a);
        put(3, W.m00(), W.m01(), W.m02(), 0, 0, 0, a);
        put(6, W.m10(), W.m11(), W.m12(), 0, 0, 0, a);
        put(9, W.m20(), W.m21(), W.m22(), 0, 0, 0, a);
        return true;
    }

    /** Transform a PZ world frame (origin x/y tiles, z levels, and 3 model axes) by a Viewpoint scene-space rigid matrix. */
    static void applySceneTransform(double[] f, org.joml.Matrix4f c, double camX, double camY, double camZ, double lift) {
        if (c == null || f == null || f.length < 12) {
            return;
        }
        double lvl = ViewpointBridge.LEVEL;
        org.joml.Vector4f s = new org.joml.Vector4f(
                (float) (camX - f[0]), (float) ((f[2] - camZ) * lvl + lift), (float) (camY - f[1]), 1f);
        c.transform(s);
        f[0] = camX - s.x;
        f[1] = camY - s.z;
        f[2] = camZ + (s.y - lift) / lvl;
        for (int k = 3; k < 12; k += 3) {
            s.set((float) -f[k], (float) (f[k + 2] * lvl), (float) -f[k + 1], 0f);
            c.transform(s);
            f[k] = -s.x;
            f[k + 1] = -s.z;
            f[k + 2] = s.y / lvl;
        }
    }

    /** Stateless evaluation of FpViewLatch's draw-time rigid scene transform from poseLook/poseEye to liveLook. */
    static org.joml.Matrix4f latchCorrection(Object vpFrame, float yawP, float pitchP, float[] poseEye,
                                             boolean free, float yawNow, float pitchNow) {
        if (vpFrame == null || !Float.isFinite(yawP) || !Float.isFinite(pitchP)
                || !Float.isFinite(yawNow) || !Float.isFinite(pitchNow)) {
            return null;
        }
        float yawD = free ? yawP : yawNow, pitchD = free ? pitchP : pitchNow;
        float dy = (float) Math.IEEEremainder(yawD - yawP, 2 * Math.PI), dp = pitchD - pitchP;
        if (Math.abs(dy) > FpViewLatch.LIMIT || Math.abs(dp) > FpViewLatch.LIMIT) {
            return null;
        }
        try {
            float[] ep = new float[3], fp = new float[3], ed = new float[3], fd = new float[3];
            if (!ViewpointBridge.camera(vpFrame, yawP, pitchP, ep, fp)
                    || !ViewpointBridge.camera(vpFrame, yawD, pitchD, ed, fd)) {
                return null;
            }
            if (FpViewLatch.eyeLatch && poseEye != null && FpViewLatch.finite(poseEye)) {
                double dx = ed[0] - poseEye[0], ey = ed[1] - poseEye[1], dz = ed[2] - poseEye[2];
                if (Math.sqrt(dx * dx + ey * ey + dz * dz) <= FpViewLatch.EYE_LIMIT) {
                    System.arraycopy(poseEye, 0, ep, 0, 3);
                }
            }
            return FpViewLatch.between(ep, fp, ed, fd);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Re-apply FpViewLatch alignment onto an unlatched carried world frame using the live Viewpoint Look.yaw/pitch. */
    static double[] latchedWorld(double[] carriedWorld, IsoGameCharacter c, Object vpFrame,
                                 float yawP, float pitchP, float[] poseEye, boolean free, float[] outLook) {
        double[] out = carriedWorld.clone();
        if (vpFrame == null || ViewpointBridge.state() != 1
                || !(c instanceof zombie.characters.IsoPlayer ip) || !ip.isLocalPlayer() || ip.getPlayerNum() != 0
                || !FpViewLatch.enabled || !FpViewLatch.detect() || !layer(ip)
                || !Float.isFinite(yawP) || !Float.isFinite(pitchP)) {
            return out;
        }
        try {
            float[] nowLook = new float[2];
            ViewpointBridge.look(nowLook);
            if (outLook != null && outLook.length >= 2) {
                outLook[0] = nowLook[0];
                outLook[1] = nowLook[1];
            }
            org.joml.Matrix4f cMat = latchCorrection(vpFrame, yawP, pitchP, poseEye, free, nowLook[0], nowLook[1]);
            if (cMat != null) {
                float[] origin = new float[6];
                Object relief = ViewpointBridge.origin(vpFrame, origin);
                float lift = ViewpointBridge.lift(relief, c.getX(), c.getY());
                applySceneTransform(out, cMat, origin[0], origin[1], origin[2], lift);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * FpViewLatch.layer() for the weapon frame: the same pose layer conditions, its age in world frames (FpPose.freshFrame)
     * instead of 500 ms of wall clock - one long frame right before a shot must not drop the latch the drawing has (W10
     * item 18). The drawing's own latch keeps layer().
     */
    static boolean layer(zombie.characters.IsoPlayer p) {
        FpPose.Request r = FpPose.requests.get(p);
        return r != null && FpPose.enabled && r.allowed && !r.spent && r.fpW > 1e-4 && r.eyeValid && r.eye != null
                && p.getPrimaryHandItem() == r.item && FpPose.freshFrame(r.stampFrame, FpRecoil.worldFrame());
    }

    record LatchCapture(double[] carriedWorld, double[] drawnWorld, float poseYaw, float posePitch,
                        float[] poseEye, boolean freeLook) {
    }

    /** Capture both the carried world frame and the latched drawn world frame with FpPose eye metadata for ShotOwner. */
    static LatchCapture captureLatch(IsoGameCharacter c, Object vpFrame) {
        boolean localFp = ViewpointBridge.state() == 1 && c instanceof zombie.characters.IsoPlayer ip
                && ip.isLocalPlayer() && ip.getPlayerNum() == 0;
        if (!world(c, localFp ? SightDrawFrame.carried(c) : null)) {
            return null;
        }
        double[] carried = F.clone();
        float poseYaw = Float.NaN, posePitch = Float.NaN;
        float[] poseEye = null;
        boolean freeLook = false;
        double[] drawn = carried.clone();
        if (localFp) {
            try {
                FpPose.Request req = FpPose.requests.get(c);
                double[] eye = req == null ? null : req.eye;
                if (eye != null && eye.length >= 5) {
                    poseYaw = (float) eye[3];
                    posePitch = (float) eye[4];
                    freeLook = FpFreeLook.free(eye);
                }
                Object frame = vpFrame != null ? vpFrame
                        : (ViewpointPass.open != null && ViewpointPass.open.frame != null ? ViewpointPass.open.frame
                        : ViewpointBridge.frameNow());
                if (frame != null) {
                    Object scene = ViewpointBridge.sceneOf(frame);
                    long number = ViewpointBridge.frameNumber(frame);
                    SightDrawFrame.Pair pair = null;
                    for (int si = 0; si < 3 && pair == null; si++) {
                        pair = SightDrawFrame.frame(si, scene, number);
                    }
                    org.joml.Vector3f pe = pair == null ? null : SightDrawFrame.poseEye(pair, (zombie.characters.IsoPlayer) c);
                    if (pe != null) {
                        poseEye = new float[] {pe.x, pe.y, pe.z};
                    }
                    drawn = latchedWorld(carried, c, frame, poseYaw, posePitch, poseEye, freeLook, null);
                }
            } catch (Throwable ignored) {
            }
        }
        System.arraycopy(drawn, 0, F, 0, 12);
        return new LatchCapture(carried, drawn, poseYaw, posePitch, poseEye, freeLook);
    }

    /**
     * World frame of the weapon as Viewpoint actually draws it in first person: includes LegReach's carried body
     * adjustment D (SightDrawFrame.carried) and FpViewLatch's scene-space look/walk latch C_latch; falls back to
     * world(c) in third person, remote characters, or isometric view.
     */
    public static boolean drawnWorld(IsoGameCharacter c, Object vpFrame) {
        return captureLatch(c, vpFrame) != null;
    }

    /** The same frame in the look frame of the character (drop-in for the measured pose tables). */
    public static boolean look(IsoGameCharacter c) {
        if (!drawnWorld(c, null)) {
            return false;
        }
        double l = c.getLookAngleRadians(), cs = Math.cos(l), sn = Math.sin(l);
        for (int k = 0; k < 12; k += 3) {
            double ex = F[k] - (k == 0 ? c.getX() : 0);
            double ey = F[k + 1] - (k == 0 ? c.getY() : 0);
            double ez = F[k + 2] - (k == 0 ? c.getZ() : 0);
            F[k] = ex * cs + ey * sn;
            F[k + 1] = -ex * sn + ey * cs;
            F[k + 2] = ez * LEVEL;
        }
        return true;
    }
}
