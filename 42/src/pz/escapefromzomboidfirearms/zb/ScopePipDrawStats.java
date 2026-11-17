package pz.escapefromzomboidfirearms.zb;

import org.lwjgl.opengl.*;

/** Opt-in per-pass clipping primitives. GPU counters are polled without waiting. */
final class ScopePipDrawStats {
    private static final int[] TARGETS = {ARBPipelineStatisticsQuery.GL_CLIPPING_INPUT_PRIMITIVES_ARB,
            ARBPipelineStatisticsQuery.GL_CLIPPING_OUTPUT_PRIMITIVES_ARB};
    private static final String[] NAMES = {"surface", "model", "farDepth", "farColour"};
    private final int[][] queries = new int[NAMES.length][TARGETS.length];
    private boolean allocated, active, pending;
    private int begun = -1, mask;
    private volatile String result = "drawStats=off";
    private long sampleFrame;

    String info() { return result; }

    void frame(long frame, boolean enabled) {
        active = false;
        if (pending) {
            for (int[] row : queries) {
                for (int q : row) { if (GL15.glGetQueryObjecti(q, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) { return; } }
            }
            StringBuilder out = new StringBuilder("drawStatsFrame=" + sampleFrame);
            for (int i = 0; i < queries.length; i++) {
                out.append(' ').append(NAMES[i]).append("ClipInOut=")
                        .append(GL33.glGetQueryObjectui64(queries[i][0], GL15.GL_QUERY_RESULT)).append('/')
                        .append(GL33.glGetQueryObjectui64(queries[i][1], GL15.GL_QUERY_RESULT));
            }
            result = out + " primitiveCullCount=unavailable";
            pending = false;
        }
        if (!enabled) { return; }
        if (!GL.getCapabilities().GL_ARB_pipeline_statistics_query) { result = "drawStats=unsupported"; return; }
        for (int target : TARGETS) {
            if (GL15.glGetQueryi(target, GL15.GL_CURRENT_QUERY) != 0) { result = "drawStats=foreign-query-active"; return; }
        }
        if (!allocated) {
            for (int[] row : queries) { for (int i = 0; i < row.length; i++) { row[i] = GLCompat.genQuery(); } }
            allocated = true;
        }
        sampleFrame = frame;
        mask = 0;
        active = true;
    }

    void begin(int pass) {
        if (!active) { return; }
        for (int i = 0; i < TARGETS.length; i++) { GL15.glBeginQuery(TARGETS[i], queries[pass][i]); }
        begun = pass;
    }

    void end() {
        if (begun < 0) { return; }
        for (int target : TARGETS) { GL15.glEndQuery(target); }
        mask |= 1 << begun;
        begun = -1;
    }

    void finish() {
        end();
        pending = active && mask == (1 << NAMES.length) - 1;
        active = false;
    }
}
