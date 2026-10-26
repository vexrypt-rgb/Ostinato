package baritone.api.agent;

// Ostinato: writes race results. A run id already on disk is not written again.

import java.io.IOException;

public interface RaceRecorder {

    void record(RaceRun run) throws IOException;

    /** True when this recorder already has a line for {@code runId}. The default is false. */
    default boolean containsRun(String runId) throws IOException {
        return false;
    }
}
