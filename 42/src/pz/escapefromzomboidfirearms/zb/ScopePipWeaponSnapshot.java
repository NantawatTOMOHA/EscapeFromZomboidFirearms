package pz.escapefromzomboidfirearms.zb;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import zombie.characters.IsoPlayer;
import zombie.core.SpriteRenderer;

/** Immutable drawn-weapon geometry owned by one SpriteRenderer frame state. */
public final class ScopePipWeaponSnapshot {
    private static final ScopePipWeaponSnapshot[] frames = new ScopePipWeaponSnapshot[3];
    public final Object scene;
    public final long number;
    final ScopePip.Request request;
    final Vector3f camera, ocular, objective, front, edgeX, edgeY, expectedEye, forward, up, origin;
    final Vector3f[] boundary;

    private ScopePipWeaponSnapshot(Object frame, ScopePip.Request request, Matrix4f root) throws Throwable {
        this.request = request;
        scene = ViewpointBridge.sceneOf(frame);
        number = ViewpointBridge.frameNumber(frame);
        float[] origin = new float[6];
        ViewpointBridge.origin(frame, origin);
        this.origin = new Vector3f(origin[0], origin[1], origin[2]);
        camera = point(root, request.camera);
        ocular = point(root, request.ocular);
        objective = request.objective == null ? null : point(root, request.objective);
        front = request.front == null ? null : point(root, request.front);
        edgeX = point(root, request.edgeX);
        edgeY = point(root, request.edgeY);
        boundary = request.boundary == null ? null : new Vector3f[request.boundary.length];
        if (boundary != null) for (int i = 0; i < boundary.length; i++) boundary[i] = point(root, request.boundary[i]);
        expectedEye = point(root, request.eye);
        forward = root.transformDirection(new Vector3f(request.forward)).normalize();
        up = root.transformDirection(new Vector3f(request.up)).normalize();
    }

    private static Vector3f point(Matrix4f root, float[] local) {
        return root.transformPosition(new Vector3f(local));
    }

    private ScopePipWeaponSnapshot(ScopePipWeaponSnapshot source, Matrix4f correction) {
        request = source.request; scene = source.scene; number = source.number;
        origin = new Vector3f(source.origin);
        camera = correction.transformPosition(new Vector3f(source.camera));
        ocular = correction.transformPosition(new Vector3f(source.ocular));
        objective = source.objective == null ? null : correction.transformPosition(new Vector3f(source.objective));
        front = source.front == null ? null : correction.transformPosition(new Vector3f(source.front));
        edgeX = correction.transformPosition(new Vector3f(source.edgeX));
        edgeY = correction.transformPosition(new Vector3f(source.edgeY));
        expectedEye = correction.transformPosition(new Vector3f(source.expectedEye));
        forward = correction.transformDirection(new Vector3f(source.forward)).normalize();
        up = correction.transformDirection(new Vector3f(source.up)).normalize();
        boundary = source.boundary == null ? null : new Vector3f[source.boundary.length];
        if (boundary != null) for (int i = 0; i < boundary.length; i++)
            boundary[i] = correction.transformPosition(new Vector3f(source.boundary[i]));
    }

    /** Render thread, after WorldRenderer.begin: the same rigid C as the drawn SELF models.
     * Keep the published capture immutable, so neither a repeated draw nor another pass turns twice. */
    ScopePipWeaponSnapshot forRender() {
        Matrix4f correction = FpViewLatch.correction(scene);
        return correction == null ? this : new ScopePipWeaponSnapshot(this, correction);
    }

    /**
     * At IsoWorld.render exit: the exact root matrix captured for this scene,
     * including VP's carried body adjustment and one rigid terrain placement.
     * Applying relief independently to lens points would deform that frame.
     */
    static ScopePipWeaponSnapshot capture(Object frame, ScopePip.Request request) throws Throwable {
        return capture(frame, request, true);
    }

    /** published: the frame's own snapshot (frame()); false for the glass of the other sights (SightGlassExtras). */
    static ScopePipWeaponSnapshot capture(Object frame, ScopePip.Request request, boolean published) throws Throwable {
        int index = SpriteRenderer.instance.getMainStateIndex();
        if (index < 0 || index >= frames.length) {
            return null;
        }
        if (published) {
            frames[index] = null;
        }
        IsoPlayer player = request.player;
        SightDrawFrame.Pair drawn = SightDrawFrame.frame(index, ViewpointBridge.sceneOf(frame), ViewpointBridge.frameNumber(frame));
        if (drawn == null || !drawn.firstPerson || drawn.player != player || drawn.weapon != request.weapon || drawn.instance != player.primaryHandModel) {
            return null;
        }
        ScopePipWeaponSnapshot result = new ScopePipWeaponSnapshot(frame, request, drawn.root);
        if (published) {
            frames[index] = result;
        }
        return result;
    }

    /** Exact frame identity, useful for further lens/reticle passes. No GL calls. */
    public static ScopePipWeaponSnapshot frame(int stateIndex, Object scene, long number) {
        ScopePipWeaponSnapshot snapshot = stateIndex < 0 || stateIndex >= frames.length ? null : frames[stateIndex];
        return snapshot != null && snapshot.scene == scene && snapshot.number == number ? snapshot : null;
    }

    public Vector3f ocular() { return new Vector3f(ocular); }
    public Vector3f edgeX() { return new Vector3f(edgeX); }
    public Vector3f edgeY() { return new Vector3f(edgeY); }
    public Vector3f camera() { return new Vector3f(camera); }
    public Vector3f forward() { return new Vector3f(forward); }
    public Vector3f up() { return new Vector3f(up); }
}
