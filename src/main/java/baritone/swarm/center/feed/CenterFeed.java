package baritone.swarm.center.feed;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Ostinato: Phase 3 command-center read API, version 1. The swarm publishes what the standalone window needs as small
 * JSON files in one shared directory ({@code swarmCenterDir}); the window only reads them. Every file has one writer:
 *
 * <pre>
 *   bots/&lt;name&gt;.json     {@link BotFeed}          each bot, about once a second
 *   terrain/&lt;name&gt;.json  {@link TerrainFeed}      each bot, every {@link #TERRAIN_PERIOD_MS}, from its own cache
 *   coordinator.json      {@link CoordinatorFeed}  the lease coordinator: leases, reservations, observations
 *   catalog.json          {@link CatalogFeed}      the command host: the registry buttons the window may press
 *   host.json             {@link HostInfo}         the command host: loopback port of the command channel
 *   host.key              the command host: HMAC key for {@link CenterRequest}; user-only permissions
 * </pre>
 *
 * Every JSON file starts with the same envelope: {@code schema}, {@code v}, {@code kind}, {@code writtenAtMs} (wall
 * clock of the writer) and {@code seq} (per writer, counts up). Compatibility rule: inside v1 fields are only ever
 * added, never renamed, removed or given a new meaning, and readers ignore keys they do not know. A breaking change
 * is v2; a v1 reader then refuses the file with {@link #check}'s reason instead of guessing.
 *
 * <p>No file here carries a command. Commands go only over the authenticated command channel
 * ({@link CenterRequest}).
 */
public final class CenterFeed {

    public static final String SCHEMA = "ostinato.center";
    public static final int VERSION = 1;

    public static final String KIND_BOT = "bot";
    public static final String KIND_TERRAIN = "terrain";
    public static final String KIND_COORDINATOR = "coordinator";
    public static final String KIND_CATALOG = "catalog";
    public static final String KIND_HOST = "host";

    /** A bot feed younger than this is fresh. */
    public static final long FRESH_MS = 3_000L;
    /** A bot feed older than this is offline (the bot exited, crashed or froze). Between the two it is stale. */
    public static final long OFFLINE_MS = 15_000L;
    /** How often a bot writes its feed. */
    public static final long BOT_PERIOD_MS = 1_000L;
    /** How often a bot writes its terrain. */
    public static final long TERRAIN_PERIOD_MS = 10_000L;
    /** Largest file a reader accepts; a bigger one is refused, never half read. */
    public static final int MAX_BYTES = 512 * 1024;

    private CenterFeed() {}

    // ---- layout ----------------------------------------------------------------------------------------------------

    /** Member ids as the swarm roster allows them; keeps every path inside the directory. */
    public static boolean safeName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,16}");
    }

    public static Path botFile(Path dir, String name) {
        return dir.resolve("bots").resolve(checked(name) + ".json");
    }

    public static Path terrainFile(Path dir, String name) {
        return dir.resolve("terrain").resolve(checked(name) + ".json");
    }

    public static Path coordinatorFile(Path dir) {
        return dir.resolve("coordinator.json");
    }

    public static Path catalogFile(Path dir) {
        return dir.resolve("catalog.json");
    }

    public static Path hostFile(Path dir) {
        return dir.resolve("host.json");
    }

    public static Path keyFile(Path dir) {
        return dir.resolve("host.key");
    }

    private static String checked(String name) {
        if (!safeName(name)) {
            throw new IllegalArgumentException("bad member name");
        }
        return name;
    }

    // ---- envelope --------------------------------------------------------------------------------------------------

    public static Map<String, Object> envelope(String kind, long writtenAtMs, long seq) {
        return Json.map("schema", SCHEMA, "v", VERSION, "kind", kind, "writtenAtMs", writtenAtMs, "seq", seq);
    }

    /** Null when {@code m} is a v1 file of {@code kind}; otherwise the reason a reader refuses it. */
    public static String check(Map<String, Object> m, String kind) {
        if (m == null) {
            return "empty";
        }
        if (!SCHEMA.equals(Json.str(m, "schema", null))) {
            return "not an ostinato.center file";
        }
        long v = Json.num(m, "v", -1);
        if (v != VERSION) {
            return v > VERSION ? "feed v" + v + " is newer than this window (v" + VERSION + ")"
                    : "feed v" + v + " is not supported (v" + VERSION + ")";
        }
        if (!kind.equals(Json.str(m, "kind", null))) {
            return "not a " + kind + " file";
        }
        return null;
    }

    // ---- files -----------------------------------------------------------------------------------------------------

    /**
     * Writes {@code text} to a temp file next to {@code file} and renames it over the target, so a reader sees the old
     * file or the new one, never a torn one. A reader holding the old file open makes the rename fail on Windows for
     * as long as it reads (microseconds); that is retried with backoff for up to about 1.3 s, on the caller's thread
     * (the bot's feed writer thread, never the client thread).
     */
    public static void writeAtomic(Path file, String text) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, "." + file.getFileName() + "-", ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            IOException last = null;
            for (int attempt = 0; attempt < 8; attempt++) {
                try {
                    try {
                        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return;
                } catch (AccessDeniedException e) {
                    last = e;
                    try {
                        Thread.sleep(5L << attempt); // 5, 10, 20 ... 640 ms
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            throw last == null ? new IOException("rename failed") : last;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** The file's text, or null when it is missing, unreadable or over {@link #MAX_BYTES}. Never throws. */
    public static String read(Path file) {
        try {
            if (Files.size(file) > MAX_BYTES) {
                return null;
            }
            byte[] bytes = Files.readAllBytes(file); // NIO opens with share-delete, so the writer's rename still works
            return bytes.length > MAX_BYTES ? null : new String(bytes, StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
