package baritone.api.agent;

// Ostinato: which leftover race worlds may be deleted. Named fixtures and vexflat are always kept.

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

public interface WorldCleanup {

    /** True when this world name must never be deleted. */
    boolean keep(String worldName);

    /** True when this name is a race leftover this policy is allowed to remove. */
    boolean isRaceWorld(String worldName);

    /** Names from the input that would be deleted. Does not touch the disk. */
    List<String> plan(Collection<String> worldNames);

    /**
     * Deletes planned race worlds that are direct children of {@code savesRoot}.
     * Returns the names removed. Callers pass a race directory, never a personal saves folder, from tests.
     */
    List<String> deleteLeftovers(Path savesRoot) throws IOException;
}
