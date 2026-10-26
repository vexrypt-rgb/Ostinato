package baritone.agent;

// Ostinato: decide how a race world is opened. No Minecraft classes, so tests can pin each case.

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * How a race world is opened. A missing world is {@link Kind#CREATE_NEW}.
 */
public final class RaceWorldPlans {

    public enum Kind {
        /** Folder already exists. The client may call openWorld. */
        OPEN_EXISTING,
        /** No folder and no world is loaded. The client creates this folder. */
        CREATE_NEW,
        /** That race world is already loaded. */
        ALREADY_OPEN,
        /** Some other world is loaded. Leave it before opening another. */
        BUSY,
        /** Name is not a race leftover, so it is never created or deleted. */
        REJECT
    }

    private RaceWorldPlans() {}

    public static Kind plan(boolean raceName, boolean folderExists, boolean worldOpen, boolean sameWorld) {
        if (!raceName) {
            return Kind.REJECT;
        }
        if (worldOpen && sameWorld) {
            return Kind.ALREADY_OPEN;
        }
        if (worldOpen) {
            return Kind.BUSY;
        }
        if (folderExists) {
            return Kind.OPEN_EXISTING;
        }
        return Kind.CREATE_NEW;
    }

    // Ostinato: same rules as WorldOptions.parseSeed. Blank is empty so the client picks a random seed.
    public static OptionalLong parseSeed(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Long.parseLong(trimmed));
        } catch (NumberFormatException ex) {
            return OptionalLong.of(trimmed.hashCode());
        }
    }

    // Ostinato: the create screen's "name (1)" suffix. A result other than the queue name is a collision.
    public static String resolvedFolder(Path saves, String requested) {
        String name = requested == null ? "" : requested;
        if (name.isEmpty() || saves == null || !Files.exists(saves.resolve(name))) {
            return name;
        }
        for (int i = 1; i < 1000; i++) {
            String candidate = name + " (" + i + ")";
            if (!Files.exists(saves.resolve(candidate))) {
                return candidate;
            }
        }
        return name + " (1000)";
    }

    // Ostinato: empty when the folder is exactly the queue name. Otherwise the create fails.
    public static String folderMismatch(String requested, String resolved) {
        if (requested != null && requested.equals(resolved)) {
            return "";
        }
        String want = requested == null ? "" : requested;
        String got = resolved == null || resolved.isBlank() ? "(none)" : resolved;
        return "world folder must equal " + want + " but was " + got;
    }
}
