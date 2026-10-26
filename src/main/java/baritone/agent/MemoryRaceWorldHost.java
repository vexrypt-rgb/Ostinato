package baritone.agent;

// Ostinato: stub race host. It creates a folder and never opens a Minecraft client.
// Scripted statuses let tests cover pending, stubbed, and failed creates.

import baritone.api.agent.RaceWorldHost;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class MemoryRaceWorldHost implements RaceWorldHost {

    private final Path root;
    private final Deque<RaceWorldHost.Status> script = new ArrayDeque<>();
    private final List<String> created = new ArrayList<>();
    private final List<String> seeds = new ArrayList<>();
    private final List<Boolean> flats = new ArrayList<>();
    private String requested = "";
    private String seed = "";
    private boolean flat = true;
    private String open = "";
    private RaceWorldHost.Result decided;
    private boolean keepOpen;

    public MemoryRaceWorldHost(Path root) {
        this.root = root;
    }

    /** Next pollCreate results. PENDING is returned once and does not finish the create. */
    public MemoryRaceWorldHost enqueue(RaceWorldHost.Status... statuses) {
        for (RaceWorldHost.Status status : statuses) {
            script.add(status);
        }
        return this;
    }

    /** Leave stays pending, so the orchestrator must not delete the open world. */
    public void keepOpen() {
        this.keepOpen = true;
    }

    public String lastWorld() {
        return requested;
    }

    public String lastSeed() {
        return seed;
    }

    public boolean lastFlat() {
        return flat;
    }

    public List<String> seeds() {
        return List.copyOf(seeds);
    }

    public List<Boolean> flats() {
        return List.copyOf(flats);
    }

    public List<String> created() {
        return List.copyOf(created);
    }

    @Override
    public void beginCreate(String world, String seed, boolean flat) {
        this.requested = world == null ? "" : world;
        this.seed = seed == null ? "" : seed;
        this.flat = flat;
        this.seeds.add(this.seed);
        this.flats.add(flat);
        this.decided = null;
    }

    @Override
    public RaceWorldHost.Result pollCreate() {
        if (decided != null) {
            return decided;
        }
        if (!script.isEmpty()) {
            RaceWorldHost.Status status = script.poll();
            if (status == RaceWorldHost.Status.PENDING) {
                return RaceWorldHost.Result.pending();
            }
            if (status == RaceWorldHost.Status.STUBBED) {
                decided = RaceWorldHost.Result.stubbed("stub");
                return decided;
            }
            if (status == RaceWorldHost.Status.FAILED) {
                decided = RaceWorldHost.Result.failed("failed");
                return decided;
            }
        }
        try {
            if (root != null && !requested.isBlank()) {
                Files.createDirectories(root.resolve(requested));
            }
        } catch (IOException ex) {
            decided = RaceWorldHost.Result.failed("folder");
            return decided;
        }
        created.add(requested);
        open = requested;
        decided = RaceWorldHost.Result.ready();
        return decided;
    }

    @Override
    public void beginLeave() {
        // The folder stays until cleanup. Leaving only drops the open flag.
    }

    @Override
    public RaceWorldHost.Result pollLeave() {
        if (keepOpen && !open.isBlank()) {
            return RaceWorldHost.Result.pending();
        }
        open = "";
        return RaceWorldHost.Result.ready();
    }

    @Override
    public boolean inWorld() {
        return !open.isBlank();
    }

    @Override
    public String currentWorld() {
        return open;
    }
}
