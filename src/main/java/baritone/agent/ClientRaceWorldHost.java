package baritone.agent;

// Ostinato: live singleplayer host. Opening an existing race folder and leaving it are live.
// A missing world is stubbed: this class does not open the Create World screen or click its buttons.
// Deletion is RaceWorldCleanup, and only after the world has been left.

import baritone.api.agent.RaceWorldHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ClientRaceWorldHost implements RaceWorldHost {

    private final Minecraft mc;
    private String world = "";
    private RaceWorldPlans.Kind kind = RaceWorldPlans.Kind.STUB_NEW;
    private boolean opening;
    private boolean cancelled;
    private String failure = "";

    public ClientRaceWorldHost(Minecraft mc) {
        this.mc = mc;
    }

    public Path savesRoot() {
        if (mc == null || mc.gameDirectory == null) {
            return Path.of("saves");
        }
        return mc.gameDirectory.toPath().resolve("saves");
    }

    @Override
    public void beginCreate(String world, String seed, boolean flat) {
        this.world = world == null ? "" : world;
        this.opening = false;
        this.cancelled = false;
        this.failure = "";
        boolean raceName = RaceWorldCleanup.INSTANCE.isRaceWorld(this.world);
        boolean exists = raceName && Files.isDirectory(savesRoot().resolve(this.world));
        boolean open = inWorld();
        boolean same = open && this.world.equals(currentWorld());
        kind = RaceWorldPlans.plan(raceName, exists, open, same);
        if (kind != RaceWorldPlans.Kind.OPEN_EXISTING) {
            return;
        }
        try {
            // Ostinato: vanilla open of a world folder that already exists. Seed and flat are not reapplied.
            mc.createWorldOpenFlows().openWorld(this.world, () -> cancelled = true);
            opening = true;
        } catch (RuntimeException ex) {
            failure = ex.getClass().getSimpleName();
        }
    }

    @Override
    public RaceWorldHost.Result pollCreate() {
        if (!failure.isBlank()) {
            return RaceWorldHost.Result.failed(failure);
        }
        return switch (kind) {
            case REJECT -> RaceWorldHost.Result.failed("race world name required");
            case BUSY -> RaceWorldHost.Result.failed("a world is already open");
            case STUB_NEW -> RaceWorldHost.Result.stubbed("new world creation is not automated");
            case ALREADY_OPEN -> RaceWorldHost.Result.ready();
            case OPEN_EXISTING -> {
                if (cancelled) {
                    yield RaceWorldHost.Result.failed("open cancelled");
                }
                if (inWorld() && world.equals(currentWorld())) {
                    yield RaceWorldHost.Result.ready();
                }
                yield opening ? RaceWorldHost.Result.pending() : RaceWorldHost.Result.failed("open failed");
            }
        };
    }

    @Override
    public void beginLeave() {
        // Ostinato: same disconnect the pathing code uses when a goal disconnects. It does not quit the client.
        if (mc != null && mc.level instanceof ClientLevel level) {
            level.disconnect(Component.literal("[Ostinato] overnight race closed"));
        }
    }

    @Override
    public RaceWorldHost.Result pollLeave() {
        return inWorld() ? RaceWorldHost.Result.pending() : RaceWorldHost.Result.ready();
    }

    @Override
    public boolean inWorld() {
        return mc != null && mc.player != null && mc.getSingleplayerServer() != null;
    }

    @Override
    public String currentWorld() {
        if (!inWorld()) {
            return "";
        }
        try {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT);
            Path name = root.getFileName();
            if (name == null || name.toString().equals(".") || name.toString().isBlank()) {
                name = root.getParent() == null ? null : root.getParent().getFileName();
            }
            return name == null ? "" : name.toString();
        } catch (RuntimeException ex) {
            return "";
        }
    }
}
