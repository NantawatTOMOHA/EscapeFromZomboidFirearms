package pz.escapefromzomboidfirearms.zb;

import java.util.Locale;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Immutable diagnostics of the displayed camera and its matching main render frame. */
final class ScopePipCamera {
    // Diagnostic conversion only: the existing Model.vectorToWorldCoords uses
    // 1.5 tiles per model unit, and every model uses the character-based U/m.
    static final double SCENE_UNITS_PER_METRE = 1.5 * GripIK.U;
    private final long cameraFrame, eyeFrame;
    private final String source;
    private final Vector3f camera, eye, fieldEye, forward, look, ocular, objective, expectedEye;
    private final Matrix4f mainMatrix, scopeMatrix;
    private final float[] imageBasis;

    ScopePipCamera(ScopePipWeaponSnapshot snapshot, long frame, Matrix4f scopeView, Matrix4f mainView, Vector3f fieldEye, boolean debug) {
        cameraFrame = frame;
        eyeFrame = snapshot.number;
        source = debug ? "main-debug" : snapshot.request.cameraSource;
        mainMatrix = new Matrix4f(mainView);
        scopeMatrix = new Matrix4f(scopeView);
        imageBasis = ScopePipComposite.imageBasis(scopeView, snapshot.ocular, snapshot.edgeX, snapshot.edgeY);
        camera = eye(scopeView);
        eye = eye(mainView);
        forward = forward(scopeView);
        look = forward(mainView);
        this.fieldEye = new Vector3f(fieldEye);
        ocular = new Vector3f(snapshot.ocular);
        objective = snapshot.objective == null ? null : new Vector3f(snapshot.objective);
        expectedEye = new Vector3f(snapshot.expectedEye);
    }

    static Vector3f eye(Matrix4f view) {
        return new Matrix4f(view).invertAffine().getTranslation(new Vector3f());
    }

    static Vector3f forward(Matrix4f view) {
        return new Matrix4f(view).invertAffine().transformDirection(new Vector3f(0, 0, -1)).normalize();
    }

    static double metres(Vector3f a, Vector3f b) {
        return a.distance(b) / SCENE_UNITS_PER_METRE;
    }

    static double angle(Vector3f a, Vector3f b) {
        // atan2 retains sub-degree precision near parallel axes.
        return Math.toDegrees(Math.atan2(new Vector3f(a).cross(b).length(), a.dot(b)));
    }

    private static String xyz(Vector3f p) {
        return p == null ? "unknown" : String.format(Locale.ROOT, "%.6f,%.6f,%.6f", p.x, p.y, p.z);
    }

    String info() {
        return " cameraSpace=VP cameraFrame=" + cameraFrame + " eyeFrame=" + eyeFrame + " cameraSource=" + source
                + " camera=" + xyz(camera) + " vpEye=" + xyz(eye) + " vpFieldEye=" + xyz(fieldEye)
                + " ocular=" + xyz(ocular) + " objective=" + xyz(objective) + " authoredEye=" + xyz(expectedEye)
                + " scopeAxis=" + xyz(forward) + " vpAxis=" + xyz(look)
                + " mainViewColumnMajor=" + ScopePipSceneState.matrix(mainMatrix)
                + " pipViewColumnMajor=" + ScopePipSceneState.matrix(scopeMatrix)
                + " imageBasis=" + java.util.Arrays.toString(imageBasis).replace(" ", "")
                + " imageDet=" + (imageBasis[0]*imageBasis[3]-imageBasis[1]*imageBasis[2])
                + " viewMaxDelta=" + ScopePipSceneState.difference(mainMatrix, scopeMatrix)
                + String.format(Locale.ROOT, " cameraDistanceM=%.6f axisAngleDeg=%.6f eyeErrorM=%.6f vpEyeErrorM=%.6f",
                        metres(camera, eye), angle(forward, look), metres(expectedEye, eye), metres(fieldEye, eye));
    }
}
