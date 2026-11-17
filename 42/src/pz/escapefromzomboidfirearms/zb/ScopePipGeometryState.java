package pz.escapefromzomboidfirearms.zb;

import java.nio.FloatBuffer;
import java.util.Arrays;

import org.lwjgl.BufferUtils;

/** A scope sees solid loaded geometry, without the main eye's fade or tile plan. */
final class ScopePipGeometryState implements AutoCloseable {
    // Render thread only. Meshes.prepare owns the record layout and grows this
    // scratch buffer itself; we never reproduce that layout or alter main data.
    private static FloatBuffer scopeRecords = BufferUtils.createFloatBuffer(1);
    private final Object scene;
    private final float[] originalFade, solidFade;
    private final int[] originalPlan, fullPlan;
    private final ScopePipCpu.Geometry scratch;
    private final FloatBuffer originalRecords;
    private final Object recordBuffer, recordsAt, recordBytes;
    private boolean recordsInstalled;
    final int meshes, faded, planned;

    ScopePipGeometryState(Object scene) throws Throwable {
        this(scene, true);
    }

    ScopePipGeometryState(Object scene, boolean diagnostics) throws Throwable {
        this.scene = scene;
        meshes = (int) ViewpointPipBridge.get("SceneData.meshCount", scene);
        originalFade = (float[]) ViewpointPipBridge.get("SceneData.meshFade", scene);
        originalPlan = (int[]) ViewpointPipBridge.get("SceneData.meshPlan", scene);
        if (meshes < 0 || originalFade == null || originalPlan == null
                || meshes > originalFade.length / 2 || meshes > originalPlan.length) {
            throw new IllegalStateException("PIP geometry table changed");
        }
        originalRecords = (FloatBuffer) ViewpointPipBridge.get("MeshArena.records", null);
        if (originalRecords == null || originalRecords == scopeRecords) {
            throw new IllegalStateException("PIP geometry records unavailable or nested");
        }
        recordBuffer = ViewpointPipBridge.get("MeshArena.recordBuffer", null);
        recordsAt = ViewpointPipBridge.get("MeshArena.recordsAt", null);
        recordBytes = ViewpointPipBridge.get("MeshArena.recordBytes", null);
        scratch = ScopePipCpu.geometry(originalFade, originalPlan, meshes);
        solidFade = scratch == null ? originalFade.clone() : scratch.fade;
        fullPlan = scratch == null ? originalPlan.clone() : scratch.plan;
        int f = 0, p = 0;
        for (int i = 0; i < meshes && (diagnostics || scratch == null); i++) {
            // Full normalized coverage. These are interval endpoints, not
            // a copied fade calibration or a substitute material alpha.
            if (diagnostics) {
                if (originalFade[i * 2] > 0 || originalFade[i * 2 + 1] < 1) { f++; }
                if (originalPlan[i] >= 0) { p++; }
            }
            if (scratch == null) {
                solidFade[i * 2] = 0;
                solidFade[i * 2 + 1] = 1;
            }
        }
        if (scratch == null) Arrays.fill(fullPlan, 0, meshes, -1); // Depth tests the full mesh.
        faded = diagnostics ? f : -1;
        planned = diagnostics ? p : -1;
    }

    void apply() throws Throwable {
        ViewpointPipBridge.put("SceneData.meshFade", scene, solidFade);
        ViewpointPipBridge.put("SceneData.meshPlan", scene, fullPlan);
        ViewpointPipBridge.put("MeshArena.records", null, scopeRecords);
        recordsInstalled = true;
        // The shader consumes uploaded mesh records, not the CPU fade array.
        // Refreshing the array alone would leave the main fade on the GPU.
        ViewpointPipBridge.call("Meshes.prepare", null, scene);
    }

    @Override
    public void close() throws Exception {
        Throwable failure = null;
        if (recordsInstalled) {
            try { scopeRecords = (FloatBuffer) ViewpointPipBridge.get("MeshArena.records", null); }
            catch (Throwable t) { failure = t; }
        }
        // Restore the exact uploaded range, not a second main-world render or
        // a reupload into the main scratch buffer. Each restore is independent.
        try { ViewpointPipBridge.put("SceneData.meshFade", scene, originalFade); } catch (Throwable t) { failure = t; }
        try { ViewpointPipBridge.put("SceneData.meshPlan", scene, originalPlan); } catch (Throwable t) { failure = t; }
        try { ViewpointPipBridge.put("MeshArena.records", null, originalRecords); } catch (Throwable t) { failure = t; }
        try { ViewpointPipBridge.put("MeshArena.recordBuffer", null, recordBuffer); } catch (Throwable t) { failure = t; }
        try { ViewpointPipBridge.put("MeshArena.recordsAt", null, recordsAt); } catch (Throwable t) { failure = t; }
        try { ViewpointPipBridge.put("MeshArena.recordBytes", null, recordBytes); } catch (Throwable t) { failure = t; }
        if (scratch != null) scratch.release();
        if (failure != null) {
            throw new Exception("PIP geometry restore failed", failure);
        }
    }
}
