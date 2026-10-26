package baritone.agent;

// Ostinato: live singleplayer host. Opening an existing race folder, creating a missing one, and leaving it are live.
// A missing world uses WorldOpenFlows.createFreshLevel. The folder name has to match the queue name.
// Deletion is RaceWorldCleanup, and only after the world has been left.

import baritone.api.agent.RaceWorldHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

public final class ClientRaceWorldHost implements RaceWorldHost {

    private final Minecraft mc;
    private String world = "";
    private String seed = "";
    private boolean flat = true;
    private RaceWorldPlans.Kind kind = RaceWorldPlans.Kind.CREATE_NEW;
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
        this.seed = seed == null ? "" : seed;
        this.flat = flat;
        this.opening = false;
        this.cancelled = false;
        this.failure = "";
        boolean raceName = RaceWorldCleanup.INSTANCE.isRaceWorld(this.world);
        boolean exists = raceName && Files.isDirectory(savesRoot().resolve(this.world));
        boolean open = inWorld();
        boolean same = open && this.world.equals(currentWorld());
        kind = RaceWorldPlans.plan(raceName, exists, open, same);
        if (kind == RaceWorldPlans.Kind.OPEN_EXISTING) {
            openExisting();
        } else if (kind == RaceWorldPlans.Kind.CREATE_NEW) {
            createNew();
        }
    }

    private void openExisting() {
        try {
            // Ostinato: vanilla open of a world folder that already exists. Seed and flat are not reapplied.
            mc.createWorldOpenFlows().openWorld(this.world, () -> cancelled = true);
            opening = true;
        } catch (RuntimeException ex) {
            failure = ex.getClass().getSimpleName();
        }
    }

    private void createNew() {
        if (mc == null) {
            failure = "no client";
            return;
        }
        // Ostinato: refuse a renamed folder such as "race_x (1)" before vanilla creates it.
        String mismatch = RaceWorldPlans.folderMismatch(world, RaceWorldPlans.resolvedFolder(savesRoot(), world));
        if (!mismatch.isEmpty()) {
            failure = mismatch;
            return;
        }
        try {
            OptionalLong parsed = WorldOptions.parseSeed(seed);
            WorldOptions options = parsed.isEmpty()
                    ? WorldOptions.defaultWithRandomSeed()
                    : new WorldOptions(parsed.getAsLong(), true, false);
            LevelSettings settings = new LevelSettings(world, GameType.SURVIVAL,
                    LevelSettings.DifficultySettings.DEFAULT, false, WorldDataConfiguration.DEFAULT);
            boolean flatWorld = flat;
            Screen back = mc.gui == null ? null : mc.gui.screen();
            // Ostinato: exact folder id, queue seed, flat preset. Blank seed is random. Survival is the screen default.
            mc.createWorldOpenFlows().createFreshLevel(world, settings, options, registries -> {
                ResourceKey<WorldPreset> key = flatWorld ? WorldPresets.FLAT : WorldPresets.NORMAL;
                return registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(key).value().createWorldDimensions();
            }, back);
            if (!Files.isDirectory(savesRoot().resolve(world))) {
                failure = "create failed";
                return;
            }
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
            case ALREADY_OPEN -> RaceWorldHost.Result.ready();
            case OPEN_EXISTING, CREATE_NEW -> pollOpening();
        };
    }

    private RaceWorldHost.Result pollOpening() {
        if (cancelled) {
            return RaceWorldHost.Result.failed(kind == RaceWorldPlans.Kind.CREATE_NEW ? "create cancelled" : "open cancelled");
        }
        if (inWorld()) {
            String open = currentWorld();
            if (world.equals(open)) {
                return RaceWorldHost.Result.ready();
            }
            // Ostinato: a created world whose folder is not the queue name fails. An open waits until it matches.
            if (kind == RaceWorldPlans.Kind.CREATE_NEW && !open.isEmpty()) {
                return RaceWorldHost.Result.failed(RaceWorldPlans.folderMismatch(world, open));
            }
        }
        if (!opening) {
            return RaceWorldHost.Result.failed(kind == RaceWorldPlans.Kind.CREATE_NEW ? "create failed" : "open failed");
        }
        return RaceWorldHost.Result.pending();
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
