package baritone.api.agent;

// Ostinato: writes race results. The overnight multi-agent race is not implemented yet.

import java.io.IOException;

public interface RaceRecorder {

    void record(RaceRun run) throws IOException;
}
