/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.maze.IMazeFurnisher;
import baritone.api.maze.MazeFurnishJob;
import baritone.api.maze.MazeFurnishers;
import baritone.altoclef.AltoClefSettings;
import baritone.maze.BlockRole;
import baritone.maze.ConnectedLight;
import baritone.maze.ConnectedPlan;
import baritone.maze.LootPlanner;
import baritone.maze.MazeFurnishPlanner;
import baritone.maze.MazeMap;
import baritone.maze.MazePlanner;
import baritone.maze.MazeSeed;
import baritone.maze.MazeSwarmBus;
import baritone.maze.MazeWorldProbe;
import baritone.maze.MazeSchematic;
import baritone.maze.RoomPlan;
import baritone.maze.StealthCadence;
import baritone.maze.TunnelSchematic;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds one underground room when {@code mazeMode} is on and {@code #maze build} has been used.
 * While the setting is off this process stays inactive and does not change path costs, movement, or any other process.
 * The wait before the build is the stealth cadence. The builder then carves the schematic; per-block pacing inside
 * the builder is not applied here.
 */
public final class MazeProcess extends BaritoneProcessHelper {

    private enum Phase { IDLE, WAIT, DESCEND }

    private Phase phase = Phase.IDLE;
    private int waitTicks;
    private RoomPlan pending;
    private List<LootPlanner.LootPlacement> pendingLoot = List.of();
    private Path mapFile;
    private String summary = "idle";
    private String handedToBuilder;
    private MazeSwarmBus bus = MazeSwarmBus.Nop.INSTANCE;

    public MazeProcess(Baritone baritone) {
        super(baritone);
    }

    /** Future swarm transport. Publishing still only happens when a room is actually handed to the builder. */
    public void attachBus(MazeSwarmBus bus) {
        this.bus = bus == null ? MazeSwarmBus.Nop.INSTANCE : bus;
    }

    public boolean enabled() {
        return Baritone.settings().mazeMode.value;
    }

    /** Why a command stopped, or the line to print when it worked. */
    public static final class Outcome {
        public final String error;
        public final String message;

        Outcome(String error, String message) {
            this.error = error;
            this.message = message;
        }

        public boolean ok() {
            return error == null;
        }

        static Outcome fail(String error) {
            return new Outcome(error, null);
        }

        static Outcome ok(String message) {
            return new Outcome(null, message);
        }
    }

    /**
     * Accepts one room under the player and waits the cadence before the builder starts.
     * Does not claim the cell until the wait ends and the builder has taken the schematic.
     */
    public Outcome buildHere() {
        Report report = prepare(true);
        return accept(report);
    }

    // Ostinato: run94 expand. MazeTest's nextRoom picked cell -5,1 (hub) and walked there, but prepare() spiralled from
    // the feet to the first sealable cell and built -6,0 (yellow_office). nextRoom now dry-runs the seal check for its
    // target at the Y it will use, and this builds exactly that cell (placeOpen around it only if it no longer seals).
    private int[] forcedCell;

    public Outcome buildAt(int cellX, int cellZ, int preferredY) {
        forcedCell = new int[]{cellX, cellZ, preferredY};
        try {
            return accept(prepare(true));
        } finally {
            forcedCell = null;
        }
    }

    private Outcome accept(Report report) {
        if (report.error != null) {
            return Outcome.fail(report.error);
        }
        StealthCadence cadence = StealthCadence.from(
                Baritone.settings().mazeMinActionDelay.value,
                Baritone.settings().mazeMaxActionDelay.value,
                Baritone.settings().mazeActionJitter.value);
        pending = report.plan;
        pendingLoot = report.loot;
        mapFile = report.mapFile;
        waitTicks = cadence.delayTicks(report.plan.roomSeed, 0);
        phase = Phase.WAIT;
        summary = report.message + " Waiting " + waitTicks + " ticks before building.";
        return Outcome.ok(summary);
    }

    /** Plans a room and reports it. Does not claim the cell or start the builder. */
    /** Ostinato: the last room handed to the builder, for the live MazeTest harness. Null until one is built. */
    public RoomPlan lastBuilt() {
        return lastBuilt;
    }

    private RoomPlan lastBuilt;
    private boolean restoreBreakFromAbove;

    /** Ostinato: puts breakFromAbove back once the survival room build that turned it on is no longer running. */
    private void restoreSettingsWhenDone() {
        boolean digging = handedToBuilder != null && baritone.getBuilderProcess().isActive();
        if (restoreBreakFromAbove && !digging) {
            Baritone.settings().breakFromAbove.value = false;
            restoreBreakFromAbove = false;
        }
        if (guardSet && !digging) {
            AltoClefSettings.getInstance().setOstinatoGuard(null);
            guardSet = false;
        }
    }

    public Outcome planHere() {
        Report report = prepare(false);
        return report.error != null ? Outcome.fail(report.error) : Outcome.ok(report.message);
    }

    public String status() {
        if (!enabled()) {
            return "Maze mode is off";
        }
        if (phase == Phase.WAIT && (pending != null || pendingSegment != null) || phase == Phase.DESCEND) {
            return summary;
        }
        if (handedToBuilder != null && baritone.getBuilderProcess().isActive()
                && baritone.getBuilderProcess().displayName().contains(handedToBuilder)) {
            return baritone.getBuilderProcess().displayName();
        }
        return "Maze mode is on, idle";
    }

    public void cancel() {
        clearPending();
        if (guardSet) {
            AltoClefSettings.getInstance().setOstinatoGuard(null);
            guardSet = false;
        }
        if (handedToBuilder != null && baritone.getBuilderProcess().isActive()
                && baritone.getBuilderProcess().displayName().contains(handedToBuilder)) {
            baritone.getPathingBehavior().cancelEverything();
        }
        handedToBuilder = null;
        summary = "idle";
    }

    @Override
    public boolean isActive() {
        restoreSettingsWhenDone();
        driveConnected(); // Ostinato: run107, called every tick whichever process is in control
        if (!enabled()) {
            if (phase != Phase.IDLE) {
                clearPending();
            }
            return false;
        }
        return phase == Phase.WAIT && (pending != null || pendingSegment != null) || phase == Phase.DESCEND && descentEntry != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (phase == Phase.DESCEND && descentEntry != null && enabled()) {
            return descend();
        }
        if (phase != Phase.WAIT || (pending == null && pendingSegment == null) || !enabled()) {
            clearPending();
            return new PathingCommand((Goal) null, PathingCommandType.DEFER);
        }
        if (waitTicks > 0) {
            waitTicks--;
            summary = pendingSegment != null
                    ? "Waiting " + waitTicks + " ticks before digging connected room " + pendingSegment.room.gx() + "," + pendingSegment.room.gz()
                    : "Waiting " + waitTicks + " ticks before building cell " + pending.cellX + "," + pending.cellZ;
            return new PathingCommand((Goal) null, PathingCommandType.REQUEST_PAUSE);
        }
        return pendingSegment != null ? handOffConnected() : handOff();
    }

    @Override
    public void onLostControl() {
        clearPending();
    }

    @Override
    public String displayName0() {
        return summary.startsWith("Waiting") || summary.startsWith("Maze") ? summary : "Maze " + summary;
    }

    private PathingCommand handOff() {
        RoomPlan plan = pending;
        List<LootPlanner.LootPlacement> loot = pendingLoot;
        Path file = mapFile;
        clearPending();
        try {
            MazeMap map = MazeMap.load(file, bus);
            map.pull();
            if (map.claimed(plan.cellX, plan.cellZ)) {
                summary = "Cell " + plan.cellX + "," + plan.cellZ + " was claimed while waiting";
                logDirect(summary);
                return new PathingCommand((Goal) null, PathingCommandType.DEFER);
            }
            String name = "maze-" + plan.cellX + "," + plan.cellZ;
            String bot = botId();
            if (!map.claim(plan.cellX, plan.cellZ, plan.roomSeed, plan.theme.id, bot, false)) {
                summary = "Cell " + plan.cellX + "," + plan.cellZ + " was claimed while waiting";
                logDirect(summary);
                return new PathingCommand((Goal) null, PathingCommandType.DEFER);
            }
            List<LootPlanner.LootPlacement> recorded = new ArrayList<>();
            for (LootPlanner.LootPlacement placement : loot) {
                if (map.recordLoot(placement, false)) {
                    recorded.add(placement);
                }
            }
            map.save(file);
            map.bus().publishClaim(plan.cellX, plan.cellZ, plan.roomSeed, plan.theme.id, MazeMap.sanitize(bot));
            for (LootPlanner.LootPlacement placement : recorded) {
                map.bus().publishLoot(placement.x(), placement.y(), placement.z(),
                        placement.kind().name().toLowerCase(), placement.theme().id, placement.payload());
            }
            boolean survival = !ctx.player().getAbilities().instabuild;
            if (survival && !Baritone.settings().breakFromAbove.value) {
                // Ostinato: a survival room is dug from inside. The builder only breaks blocks at or above the feet unless
                // breakFromAbove is on, and live it stood on the last interior layer with nothing in reach (no path, no keys).
                Baritone.settings().breakFromAbove.value = true;
                restoreBreakFromAbove = true;
            }
            baritone.getBuilderProcess().build(name, new MazeSchematic(plan, loot, survival), new BlockPos(plan.originX, plan.originY, plan.originZ));
            handedToBuilder = name;
            lastBuilt = plan;
            furnishRoom = plan; // Ostinato: remembered for #maze furnish (TenorClef gather, light, loot, seal)
            furnishLoot = loot;
            furnishSegment = null;
            summary = "Building " + name + " (" + plan.theme.displayName + ")";
            logDirect(summary);
        } catch (IOException e) {
            summary = "Maze map could not be saved: " + e.getMessage();
            logDirect(summary);
        }
        return new PathingCommand((Goal) null, PathingCommandType.DEFER);
    }

    private RoomPlan furnishRoom;
    private List<LootPlanner.LootPlacement> furnishLoot = List.of();

    /**
     * Ostinato: plans what the last room still needs (lights, containers, loot, shell and entry shaft seal) and hands it
     * to the registered {@link IMazeFurnisher}, normally TenorClef. Ostinato does not gather or fill anything itself;
     * with no furnisher this only prints the job and its materials. Creative rooms only get fills.
     */
    public Outcome furnishHere() {
        if (!enabled()) {
            return Outcome.fail("Maze mode is off");
        }
        if (ctx.player() == null || ctx.world() == null) {
            return Outcome.fail("No world");
        }
        if (furnishRoom == null) {
            return Outcome.fail("No room built yet this session");
        }
        if (handedToBuilder != null && baritone.getBuilderProcess().isActive()) {
            return Outcome.fail("The room is still being dug");
        }
        boolean survival = !ctx.player().getAbilities().instabuild;
        MazeFurnishPlanner.woods = nearbyPlanks(); // Ostinato: wood cladding asks for planks this area actually grows
        MazeFurnishJob job = furnishSegment != null
                ? connectedJob(furnishSegment, furnishLoot, survival)
                : MazeFurnishPlanner.plan(furnishRoom, furnishLoot, new MazeWorldProbe(ctx.world()), survival);
        String materials = job.materials().entrySet().stream()
                .map(e -> e.getKey() + " x" + e.getValue())
                .reduce((a, b) -> a + ", " + b).orElse("nothing");
        lastJob = job;
        IMazeFurnisher furnisher = MazeFurnishers.get();
        if (furnisher == null) {
            return Outcome.ok("Furnish " + job.summary() + ". Needs " + materials + ". No furnisher registered (TenorClef registers one).");
        }
        if (!furnisher.furnish(job)) {
            return Outcome.fail(furnisher.name() + " refused the furnish job");
        }
        return Outcome.ok("Handed " + job.summary() + " to " + furnisher.name() + ". Needs " + materials);
    }

    private Report prepare(boolean building) {
        if (!enabled()) {
            return Report.error("Maze mode is off. Set mazeMode to true. Pathing is unchanged while it is off.");
        }
        if (ctx.player() == null || ctx.world() == null) {
            return Report.error("No world");
        }
        if (building && phase == Phase.WAIT) {
            return Report.error("Already waiting to build a room");
        }
        if (building && baritone.getBuilderProcess().isActive()) {
            return Report.error("The builder is already working");
        }
        Path file = mapPath();
        MazeMap map;
        try {
            map = MazeMap.load(file, bus);
            map.pull();
        } catch (IOException e) {
            return Report.error("Could not read the maze map: " + e.getMessage());
        }
        int minY = ctx.world().getMinY();
        int maxY = ctx.world().getMaxY();
        int preferred = forcedCell != null ? forcedCell[2] : ctx.playerFeet().y - 16;
        int limit = maxY - MazePlanner.SIZE_Y - MazeSeed.COVER;
        if (preferred > limit) {
            preferred = limit;
        }
        if (preferred < minY) {
            preferred = minY;
        }
        int cellX = forcedCell != null ? forcedCell[0] : MazeSeed.cellOf(ctx.playerFeet().x);
        int cellZ = forcedCell != null ? forcedCell[1] : MazeSeed.cellOf(ctx.playerFeet().z);
        MazePlanner.Attempt attempt = null;
        if (forcedCell != null && !map.claimed(cellX, cellZ)) {
            attempt = MazePlanner.place(Baritone.settings().mazeSeed.value, cellX, cellZ, preferred, minY, maxY,
                    new MazeWorldProbe(ctx.world()));
            if (!attempt.ok()) {
                attempt = null;
            }
        }
        if (attempt == null) {
            attempt = MazePlanner.placeOpen(
                    Baritone.settings().mazeSeed.value,
                    cellX, cellZ, preferred, minY, maxY,
                    new MazeWorldProbe(ctx.world()),
                    map, MazePlanner.SEARCH_RADIUS);
        }
        if (!attempt.ok()) {
            return Report.error(attempt.refusal == null ? "No sealed room nearby" : attempt.refusal);
        }
        List<LootPlanner.LootPlacement> loot = LootPlanner.plan(attempt.plan, map);
        String message = "Cell " + attempt.cellX + "," + attempt.cellZ
                + " theme " + attempt.plan.theme.id
                + " at " + attempt.plan.originX + " " + attempt.plan.originY + " " + attempt.plan.originZ
                + (attempt.shifted ? " (shifted off a cave or the surface)" : "")
                + ", " + loot.size() + " container" + (loot.size() == 1 ? "" : "s");
        return new Report(null, message, attempt.plan, loot, file);
    }

    private Path mapPath() {
        String raw = Baritone.settings().mazeMapFile.value;
        if (raw == null || raw.isBlank()) {
            raw = "maze-map.txt";
        }
        Path path = Path.of(raw);
        if (!path.isAbsolute()) {
            // Ostinato: run96. The map lived in the game's baritone directory, so claims outlived the world they were
            // made in: every mazetest run makes a fresh world, and 172 stale claims sent room 1 five cells from spawn
            // (a 150-block shaft, dig timeout) and made buildAt skip its sealed target -2,-3 as "claimed". A relative map
            // now lives with the world (singleplayer save / server folder, per dimension), like Baritone's cache.
            var world = baritone.getWorldProvider().getCurrentWorld();
            path = (world != null ? world.directory : baritone.getDirectory()).resolve(raw);
        }
        return path.normalize();
    }

    private String botId() {
        if (ctx.player() == null) {
            return "local";
        }
        return MazeMap.sanitize(ctx.player().getName().getString());
    }

    private void clearPending() {
        phase = Phase.IDLE;
        pending = null;
        pendingSegment = null;
        pendingEntry = null;
        pendingLoot = List.of();
        waitTicks = 0;
        descentEntry = null;
        descentSegment = null;
        descentLoot = List.of();
    }

    private static final class Report {
        final String error;
        final String message;
        final RoomPlan plan;
        final List<LootPlanner.LootPlacement> loot;
        final Path mapFile;

        Report(String error, String message, RoomPlan plan, List<LootPlanner.LootPlacement> loot, Path mapFile) {
            this.error = error;
            this.message = message;
            this.plan = plan;
            this.loot = loot;
            this.mapFile = mapFile;
        }

        static Report error(String error) {
            return new Report(error, null, null, List.of(), null);
        }
    }

    // ---- Ostinato: run107, #maze.backrooms.connected ---------------------------------------------------------------
    //
    // Backrooms rooms on a 16-block grid, each joined to a built neighbour by a 3x3 #maze.tunnels-style tunnel, so the
    // whole complex is one walkable, sealed space. A segment (the new room, its tunnel and the parent's new doorway) is
    // dug from inside the complex as one dig-only schematic, then furnished like a room: room cladding by theme, tunnel
    // cladding by palette, the fewest torches that leave no dark spawn cell anywhere in the complex, the room's loot
    // containers and fill, and seals. The complex lives in maze-connected.txt beside the world's maze map.

    /** Neighbours the next-room search dry-runs per call before it reports none. */
    public static final int CONNECTED_TRIES = 12;
    private ConnectedPlan.Segment pendingSegment;
    private ConnectedPlan.Segment furnishSegment;
    private ConnectedPlan.Segment lastSegment;
    private boolean guardSet;
    private volatile String lastLight = "";

    /** The last segment handed to the builder, for the live harness; null until one is dug this session. */
    public ConnectedPlan.Segment lastSegment() {
        return lastSegment;
    }

    /** What the last connected furnish planned for light: torches, dark cells left, search size. */
    public String lastLight() {
        return lastLight;
    }

    /** The complex saved for this world, or null before its first room. */
    public ConnectedPlan.Complex loadComplex() throws IOException {
        Path file = connectedPath();
        if (!Files.exists(file)) return null;
        return ConnectedPlan.load(Files.readAllLines(file, StandardCharsets.UTF_8));
    }

    private void saveComplex(ConnectedPlan.Complex c) throws IOException {
        Path file = connectedPath();
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, ConnectedPlan.save(c), StandardCharsets.UTF_8);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private Path connectedPath() {
        return mapPath().resolveSibling("maze-connected.txt");
    }

    /**
     * Dry-runs the next room of the complex: a free neighbour (newest rooms first) whose room and tunnel seal at a depth
     * the tunnel can climb to. Null when there is no complex yet. Reads the world; the harness calls it on the server
     * thread. Neighbours that seal at no depth are remembered in the saved complex.
     */
    public ConnectedPlan.Choice connectedChoice() throws IOException {
        ConnectedPlan.Complex c = loadComplex();
        if (c == null || c.isEmpty()) return null;
        int failedBefore = c.failedCells().size();
        ConnectedPlan.Choice choice = onServer(() -> nextChoice(c));
        if (c.failedCells().size() != failedBefore) saveComplex(c);
        return choice;
    }

    /** The next-room dry run against the level the probes read; call it through {@link #onServer}. */
    private ConnectedPlan.Choice nextChoice(ConnectedPlan.Complex c) {
        net.minecraft.world.level.Level level = probeLevel();
        return ConnectedPlan.next(c, new MazeWorldProbe(ctx.world()),
                (x, z) -> level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR, x, z),
                level.getMinY(), level.getMaxY(), CONNECTED_TRIES);
    }

    /**
     * Runs world-reading planning on the integrated server's thread. Every {@link MazeWorldProbe} read from the client
     * thread is a round trip to the server thread, and a dry run is thousands of them; one hop does it all at once.
     */
    private static <T> T onServer(java.util.function.Supplier<T> work) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.server.MinecraftServer server = mc == null ? null : mc.getSingleplayerServer();
        if (server == null || server.isSameThread()) return work.get();
        try {
            return server.submit(work).get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("server did not answer: " + e, e);
        }
    }

    /** Plans the next connected room (the first one under the feet) and waits the cadence before digging it. */
    public Outcome buildConnected(ConnectedPlan.Choice forced) {
        ConnectedReport r = prepareConnected(true, forced);
        if (r.error != null) return Outcome.fail(r.error);
        StealthCadence cadence = StealthCadence.from(
                Baritone.settings().mazeMinActionDelay.value,
                Baritone.settings().mazeMaxActionDelay.value,
                Baritone.settings().mazeActionJitter.value);
        pending = null;
        pendingSegment = r.segment;
        pendingEntry = r.entry;
        pendingLoot = r.loot;
        mapFile = r.mapFile;
        waitTicks = cadence.delayTicks(r.segment.plan.roomSeed, 0);
        phase = Phase.WAIT;
        summary = r.message + " Waiting " + waitTicks + " ticks before digging.";
        return Outcome.ok(summary);
    }

    public Outcome planConnected() {
        ConnectedReport r = prepareConnected(false, null);
        return r.error != null ? Outcome.fail(r.error) : Outcome.ok(r.message);
    }

    public String connectedStatus() {
        try {
            ConnectedPlan.Complex c = loadComplex();
            String base = c == null ? "No connected complex in this world yet"
                    : "Connected complex: " + c.rooms().size() + " rooms, anchor " + c.anchorX + "," + c.anchorZ
                    + (c.failedCells().isEmpty() ? "" : ", " + c.failedCells().size() + " neighbours unsealable");
            return base + ". " + status() + (lastLight.isEmpty() ? "" : ". Light: " + lastLight);
        } catch (IOException e) {
            return "Could not read the connected complex: " + e.getMessage();
        }
    }

    private static final class ConnectedReport {
        final String error;
        final String message;
        final ConnectedPlan.Segment segment;
        final Entry entry;
        final List<LootPlanner.LootPlacement> loot;
        final Path mapFile;

        ConnectedReport(String error, String message, ConnectedPlan.Segment segment, Entry entry, List<LootPlanner.LootPlacement> loot, Path mapFile) {
            this.error = error;
            this.message = message;
            this.segment = segment;
            this.entry = entry;
            this.loot = loot;
            this.mapFile = mapFile;
        }

        static ConnectedReport error(String error) {
            return new ConnectedReport(error, null, null, null, List.of(), null);
        }
    }

    private ConnectedReport prepareConnected(boolean building, ConnectedPlan.Choice forced) {
        if (!enabled()) return ConnectedReport.error("Maze mode is off. Set mazeMode to true. Pathing is unchanged while it is off.");
        if (ctx.player() == null || ctx.world() == null) return ConnectedReport.error("No world");
        if (building && phase != Phase.IDLE) return ConnectedReport.error("Already waiting to build a room");
        if (building && baritone.getBuilderProcess().isActive()) return ConnectedReport.error("The builder is already working");
        Path file = mapPath();
        MazeMap map;
        ConnectedPlan.Complex c;
        try {
            map = MazeMap.load(file, bus);
            map.pull();
            c = loadComplex();
        } catch (IOException e) {
            return ConnectedReport.error("Could not read the maze maps: " + e.getMessage());
        }
        long seed = Baritone.settings().mazeSeed.value;
        BlockPos feet = ctx.playerFeet();
        int failedBefore = c == null ? 0 : c.failedCells().size();
        // Outside the complex (the first room, or back from the surface): come down into the new room unseen.
        boolean outside = c == null || !c.air().contains(ConnectedPlan.key(feet.getX(), feet.getY(), feet.getZ()));
        Planned planned;
        try {
            ConnectedPlan.Complex complex = c;
            planned = onServer(() -> planSegment(complex, forced, seed, feet, outside));
        } catch (RuntimeException e) {
            return ConnectedReport.error("Planning failed: " + e.getMessage());
        }
        if (c != null && c.failedCells().size() != failedBefore) {
            try {
                saveComplex(c);
            } catch (IOException ignored) {
                // the failures are only a shortcut; the next search finds them again
            }
        }
        if (planned.error != null) return ConnectedReport.error(planned.error);
        ConnectedPlan.Segment seg = planned.segment;
        String where = planned.where + (planned.entry == null ? "" : ", way in at " + planned.entry.x() + "," + planned.entry.z());
        List<LootPlanner.LootPlacement> loot = LootPlanner.plan(seg.plan, map);
        String message = "Connected room " + seg.room.gx() + "," + seg.room.gz() + " " + where
                + " theme " + seg.plan.theme.id
                + " at " + seg.plan.originX + " " + seg.plan.originY + " " + seg.plan.originZ
                + ", tunnel " + seg.tunnel.size() + " cells " + seg.palette.id
                + ", " + loot.size() + " container" + (loot.size() == 1 ? "" : "s");
        return new ConnectedReport(null, message, seg, planned.entry, loot, file);
    }

    private record Planned(ConnectedPlan.Segment segment, String where, Entry entry, String error) {}

    /** World-reading half of {@link #prepareConnected}: the segment and its palette. Runs on the server thread. */
    private Planned planSegment(ConnectedPlan.Complex c, ConnectedPlan.Choice forced, long seed, BlockPos feet, boolean outside) {
        MazeWorldProbe probe = new MazeWorldProbe(ctx.world());
        net.minecraft.world.level.Level level = probeLevel();
        int minY = level.getMinY(), maxY = level.getMaxY();
        ConnectedPlan.Segment seg;
        String where;
        if (c == null || c.isEmpty()) {
            int pref = ConnectedPlan.preferredY(feet.getY(), minY, maxY);
            List<String> why = new ArrayList<>();
            ConnectedPlan.First first = ConnectedPlan.firstRoom(seed, feet.getX(), feet.getZ(), pref, minY, maxY, probe, why);
            if (first == null) return new Planned(null, null, null, "No sealed first room under the player: " + String.join(", ", why));
            seg = ConnectedPlan.first(first.complex(), 0, 0, first.originY());
            where = "first room";
        } else {
            ConnectedPlan.Choice choice = forced != null ? forced : nextChoice(c);
            if (choice.parentDir() < 0) {
                return new Planned(null, null, null, "No neighbour seals (" + choice.tried() + " tried):" + (choice.why().isEmpty() ? " none free" : " " + choice.why()));
            }
            ConnectedPlan.Room parent = c.room(choice.gx() - ConnectedPlan.dx(choice.parentDir()), choice.gz() - ConnectedPlan.dz(choice.parentDir()));
            if (parent == null || c.room(choice.gx(), choice.gz()) != null) return new Planned(null, null, null, "Stale choice " + choice);
            seg = ConnectedPlan.join(c, c.air(), parent, choice.parentDir(), choice.originY());
            if (forced != null && !ConnectedPlan.breaches(seg, probe, false).isEmpty()) {
                return new Planned(null, null, null, "Room " + choice.gx() + "," + choice.gz() + " no longer seals");
            }
            where = "joined " + dirName(choice.parentDir()) + " of " + parent.gx() + "," + parent.gz()
                    + " (rise " + (choice.originY() - parent.originY()) + ")";
        }
        Entry entry = null;
        if (outside) {
            entry = entryFor(level, probe, seg);
            if (entry == null) {
                if (c != null && !c.isEmpty()) c.fail(seg.room.gx(), seg.room.gz()); // try another neighbour next time
                return new Planned(null, null, null, "No clear way down into room " + seg.room.gx() + "," + seg.room.gz()
                        + " (every column meets a cave, fluid or unloaded ground)");
            }
        }
        Map<String, Integer> stone = stoneAround(level, seg.tunnelBox());
        seg.choosePalette(id -> stone.getOrDefault(id, 0));
        return new Planned(seg, where, entry, null);
    }

    private static String dirName(int dir) {
        return switch (dir) {
            case ConnectedPlan.EAST -> "east";
            case ConnectedPlan.SOUTH -> "south";
            case ConnectedPlan.WEST -> "west";
            default -> "north";
        };
    }

    private PathingCommand handOffConnected() {
        ConnectedPlan.Segment seg = pendingSegment;
        List<LootPlanner.LootPlacement> loot = pendingLoot;
        Entry entry = pendingEntry;
        clearPending();
        try {
            ConnectedPlan.Complex now = loadComplex();
            if (now != null && now.room(seg.room.gx(), seg.room.gz()) != null) {
                summary = "Connected room " + seg.room.gx() + "," + seg.room.gz() + " was built while waiting";
                logDirect(summary);
                connectedFailure = summary;
                return new PathingCommand((Goal) null, PathingCommandType.DEFER);
            }
        } catch (IOException e) {
            summary = "Maze map could not be read: " + e.getMessage();
            logDirect(summary);
            connectedFailure = summary;
            return new PathingCommand((Goal) null, PathingCommandType.DEFER);
        }
        if (entry != null) {
            descentEntry = entry;
            lastEntry = entry;
            descentSegment = seg;
            descentLoot = loot;
            descentTicks = 0;
            descentStepTicks = 0;
            descentFeetY = Integer.MIN_VALUE;
            descentDug.clear();
            phase = Phase.DESCEND;
            System.out.println("MAZETEST CONNECTED descent start column=" + entry.x() + "," + entry.z() + " ground=" + entry.groundY()
                    + " ceiling=" + entry.ceilingY() + " floor=" + entry.floorY());
            return descend();
        }
        return startDig(seg, loot);
    }

    /** Claims the segment in both maps and hands its cells to the builder, dug from where the bot stands. */
    private PathingCommand startDig(ConnectedPlan.Segment seg, List<LootPlanner.LootPlacement> loot) {
        Path file = mapPath();
        try {
            MazeMap map = MazeMap.load(file, bus);
            map.pull();
            for (LootPlanner.LootPlacement placement : loot) map.recordLoot(placement, false);
            map.save(file);
            saveComplex(seg.after);
            boolean survival = !ctx.player().getAbilities().instabuild;
            if (survival && !Baritone.settings().breakFromAbove.value) {
                Baritone.settings().breakFromAbove.value = true; // dug from inside, as a survival room is
                restoreBreakFromAbove = true;
            }
            Set<BlockPos> cells = new LinkedHashSet<>();
            for (long k : seg.newAir) cells.add(new BlockPos(ConnectedPlan.x(k), ConnectedPlan.y(k), ConnectedPlan.z(k)));
            String name = "maze-connected-" + seg.room.gx() + "," + seg.room.gz();
            dig(name, cells, seg);
            startedSegment = seg;
            lastBuilt = seg.plan;
            lastSegment = seg;
            furnishRoom = seg.plan;
            furnishSegment = seg;
            furnishLoot = loot;
            summary = "Digging " + name + " (" + seg.plan.theme.displayName + ", " + seg.newAir.size() + " cells, "
                    + seg.palette.id + " tunnel)";
            logDirect(summary);
        } catch (IOException e) {
            summary = "Maze map could not be saved: " + e.getMessage();
            logDirect(summary);
            connectedFailure = summary;
        }
        return new PathingCommand((Goal) null, PathingCommandType.DEFER);
    }

    /** Dig-only builder pass over {@code cells}, the finished complex guarded against path shortcuts. */
    private void dig(String name, Set<BlockPos> cells, ConnectedPlan.Segment seg) {
        TunnelSchematic schematic = TunnelSchematic.of(cells, null);
        Set<Long> guard = seg.guard();
        if (!guard.isEmpty()) {
            // No path shortcut through the finished complex: its shell, torches and containers stay put.
            AltoClefSettings.getInstance().setOstinatoGuard((x, y, z) -> guard.contains(ConnectedPlan.key(x, y, z)));
            guardSet = true;
        }
        baritone.getBuilderProcess().build(name, schematic, schematic.origin);
        handedToBuilder = name;
    }

    // ---- Ostinato: run107, the hidden way in -----------------------------------------------------------------------
    //
    // The bot is outside the complex for the first room, and after the furnisher went up for wood. It comes down into
    // the new room, still solid rock, through a 1x1 shaft: dig the block underfoot, sink one block, put a block back
    // above its head. The ground closes over it all the way down to the ceiling, so nothing shows from the surface.

    /**
     * The shaft: column {@code x, z} from the ground block {@code groundY}, refilled down to {@code ceilingY}; the bot
     * stops with its feet at {@code floorY}, on the room's floor block.
     */
    public record Entry(int x, int z, int groundY, int ceilingY, int floorY) {}

    private static final int DESCENT_STEP_TICKS = 20 * 20;
    private static final int DESCENT_WALK_TICKS = 20 * 180;
    private Entry pendingEntry;
    private Entry descentEntry;
    private Entry lastEntry;
    private ConnectedPlan.Segment descentSegment;
    private List<LootPlanner.LootPlacement> descentLoot = List.of();
    private int descentTicks, descentStepTicks, descentFeetY;
    private final Map<Integer, Block> descentDug = new HashMap<>();

    /** The last way in used this session, for the live harness; null when every segment was dug from inside. */
    public Entry lastEntry() {
        return lastEntry;
    }

    /** An interior column of the new room whose shaft is clear, nearest the bot first. Server thread. */
    private Entry entryFor(net.minecraft.world.level.Level level, MazeWorldProbe probe, ConnectedPlan.Segment seg) {
        RoomPlan plan = seg.plan;
        int ceilingY = plan.originY + ConnectedPlan.ROOM - 1;
        BlockPos feet = ctx.playerFeet();
        outer:
        for (int[] col : ConnectedPlan.entryColumns(seg.after, seg.room, feet.getX(), feet.getZ())) {
            int rx = col[0] - plan.originX, rz = col[1] - plan.originZ;
            for (int ry = 2; ry < ConnectedPlan.ROOM - 1; ry++) {
                if (plan.role(rx, ry, rz) != BlockRole.AIR) continue outer; // never through the feature block
            }
            int ground = groundAt(level, col[0], col[1]);
            if (!standable(level, col[0], ground + 1, col[1]) || !standable(level, col[0], ground + 2, col[1])) continue;
            if (ConnectedPlan.shaftClear(probe, col[0], col[1], ground, ceilingY)) {
                return new Entry(col[0], col[1], ground, ceilingY, plan.originY + 2); // feet on the floor block (ry 1)
            }
        }
        return null;
    }

    /** Open for a player to stand in: no log or leaf to cut (that would show), no fluid. */
    private static boolean standable(net.minecraft.world.level.Level level, int x, int y, int z) {
        BlockState st = level.getBlockState(new BlockPos(x, y, z));
        return (st.isAir() || st.canBeReplaced()) && st.getFluidState().isEmpty();
    }

    /** The top natural block of a column, under trees, plants and snow; minY when unloaded. */
    private static int groundAt(net.minecraft.world.level.Level level, int x, int z) {
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (; y > level.getMinY(); y--) {
            BlockState st = level.getBlockState(at.set(x, y, z));
            if (st.isAir() || st.canBeReplaced() || st.is(net.minecraft.tags.BlockTags.LOGS) || st.is(net.minecraft.tags.BlockTags.LEAVES)) {
                continue;
            }
            return y;
        }
        return level.getMinY();
    }

    private PathingCommand descend() {
        Entry e = descentEntry;
        BetterBlockPos feet = ctx.playerFeet();
        descentTicks++;
        baritone.getInputOverrideHandler().clearAllKeys();
        if (feet.x != e.x() || feet.z != e.z()) {
            if (descentFeetY != Integer.MIN_VALUE) {
                return abortDescent("off the shaft column at " + feet);
            }
            if (descentTicks > DESCENT_WALK_TICKS) {
                return abortDescent("could not reach the way in at " + e.x() + "," + e.z());
            }
            summary = "Walking to the way in at " + e.x() + " " + (e.groundY() + 1) + " " + e.z();
            return new PathingCommand(new GoalBlock(e.x(), e.groundY() + 1, e.z()), PathingCommandType.SET_GOAL_AND_PATH);
        }
        if (!ctx.player().onGround()) {
            return new PathingCommand((Goal) null, PathingCommandType.CANCEL_AND_SET_GOAL); // sinking one block
        }
        if (feet.y != descentFeetY) {
            descentFeetY = feet.y;
            descentStepTicks = 0;
        }
        if (++descentStepTicks > DESCENT_STEP_TICKS) {
            return abortDescent("no progress at " + feet);
        }
        int fillY = feet.y + 2;
        if (fillY >= e.ceilingY() && fillY <= e.groundY()) {
            BlockPos fill = new BlockPos(e.x(), fillY, e.z());
            BlockState there = ctx.world().getBlockState(fill);
            if (there.isAir() || there.canBeReplaced()) {
                return closeAbove(fill, e);
            }
        }
        if (feet.y <= e.floorY()) {
            int closed = 0;
            for (int y = e.ceilingY(); y <= e.groundY(); y++) {
                BlockState st = ctx.world().getBlockState(new BlockPos(e.x(), y, e.z()));
                if (!st.isAir() && st.getFluidState().isEmpty()) closed++;
            }
            summary = "Down the hidden way in: " + closed + "/" + (e.groundY() - e.ceilingY() + 1) + " shaft blocks closed";
            logDirect(summary);
            System.out.println("MAZETEST CONNECTED descent done column=" + e.x() + "," + e.z() + " ground=" + e.groundY()
                    + " closed=" + closed + "/" + (e.groundY() - e.ceilingY() + 1) + " ticks=" + descentTicks);
            ConnectedPlan.Segment seg = descentSegment;
            List<LootPlanner.LootPlacement> loot = descentLoot;
            endDescent();
            return startDig(seg, loot);
        }
        BlockPos below = feet.below();
        BlockState bs = ctx.world().getBlockState(below);
        if (!bs.getFluidState().isEmpty()) {
            return abortDescent("fluid under the shaft at " + below.toShortString());
        }
        if (bs.isAir()) {
            return new PathingCommand((Goal) null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        descentDug.put(below.getY(), bs.getBlock());
        Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(),
                new Vec3(below.getX() + 0.5, below.getY() + 1, below.getZ() + 0.5), ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rot, true);
        MovementHelper.switchToBestToolFor(ctx, bs);
        if (ctx.isLookingAt(below) || ctx.playerRotations().isReallyCloseTo(rot)) {
            baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
        }
        summary = "Going down the hidden way in, y " + feet.y;
        return new PathingCommand((Goal) null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    /** Puts a block into {@code fill}, the cell above the bot's head, against the block above it or a shaft wall. */
    private PathingCommand closeAbove(BlockPos fill, Entry e) {
        if (!selectFiller(fill.getY(), e)) {
            return abortDescent("no block to close the shaft with");
        }
        for (Direction d : new Direction[]{Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos against = fill.relative(d);
            BlockState as = ctx.world().getBlockState(against);
            if (as.isAir() || as.canBeReplaced() || !as.getFluidState().isEmpty()) continue;
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(against).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            Rotation rot = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), hit, ctx.playerRotations());
            baritone.getLookBehavior().updateTarget(rot, true);
            baritone.getInputOverrideHandler().setInputForceState(Input.SNEAK, true);
            if (ctx.isLookingAt(against) && ctx.objectMouseOver() instanceof BlockHitResult bh && bh.getDirection() == face) {
                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
            }
            summary = "Closing the way in above, y " + fill.getY();
            return new PathingCommand((Goal) null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return abortDescent("nothing to place against at " + fill.toShortString());
    }

    /**
     * Selects a block for shaft cell {@code y}: what was dug there (grass gives dirt, stone cobble), dirt near the top,
     * then any plain stone. Never sand or gravel: it would fall on the bot.
     */
    private boolean selectFiller(int y, Entry e) {
        List<Item> order = new ArrayList<>();
        Block dug = descentDug.get(y);
        if (dug != null) order.add(fillerFor(dug));
        if (y >= e.groundY() - 2) order.add(Items.DIRT);
        order.addAll(List.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE, Items.DEEPSLATE, Items.ANDESITE, Items.DIORITE,
                Items.GRANITE, Items.TUFF, Items.SANDSTONE, Items.DIRT, Items.NETHERRACK));
        for (Item item : order) {
            if (!(item instanceof BlockItem bi) || bi.getBlock() instanceof FallingBlock) continue;
            if (baritone.getInventoryBehavior().throwaway(true, stack -> stack.is(item), true)) return true;
        }
        return false;
    }

    private static Item fillerFor(Block dug) {
        if (dug == Blocks.GRASS_BLOCK || dug == Blocks.PODZOL || dug == Blocks.MYCELIUM || dug == Blocks.DIRT_PATH
                || dug == Blocks.FARMLAND || dug == Blocks.ROOTED_DIRT || dug == Blocks.COARSE_DIRT) {
            return Items.DIRT;
        }
        if (dug == Blocks.STONE) return Items.COBBLESTONE;
        if (dug == Blocks.DEEPSLATE) return Items.COBBLED_DEEPSLATE;
        return dug.asItem();
    }

    private PathingCommand abortDescent(String why) {
        summary = "The way in failed: " + why;
        logDirect(summary);
        System.out.println("MAZETEST CONNECTED descent-fail " + why);
        connectedFailure = summary;
        endDescent();
        return new PathingCommand((Goal) null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private void endDescent() {
        baritone.getInputOverrideHandler().clearAllKeys();
        descentEntry = null;
        descentSegment = null;
        descentLoot = List.of();
        descentDug.clear();
        phase = Phase.IDLE;
    }

    // ---- Ostinato: run107, #maze.backrooms.connected forever -------------------------------------------------------
    //
    // plan (cadence wait) -> [hidden way in] -> dig -> recheck the cells -> furnish -> wait for the furnisher -> again.
    // Driven from isActive(), which runs every tick, so the run carries on while TenorClef holds the controls.

    /** Lets the live harness check each segment. Called on the client thread. */
    public interface ConnectedWatcher {
        /** The builder let go of the segment's cells. */
        default void dug(ConnectedPlan.Segment seg) {
        }

        /** The furnisher let go of the segment's job; returns ticks to wait before the next segment. */
        default int furnished(ConnectedPlan.Segment seg, MazeFurnishJob job, boolean timedOut) {
            return 0;
        }

        /** Planning, the way in or the dig failed; the run tries again after a pause. */
        default void failed(String why) {
        }
    }

    private enum Drive { OFF, BUILD, FURNISH, HOLD }

    private static final int FURNISH_LIMIT = 20 * 60 * 40;
    private static final int RETRY_TICKS = 20 * 30;
    private static final int MAX_FAILURES = 10;
    private Drive drive = Drive.OFF;
    private int driveTicks, holdTicks, failures, furnishTries, rechecks, segmentsDone;
    private boolean sawBusy;
    private int lastDriveTick = Integer.MIN_VALUE;
    private ConnectedPlan.Segment startedSegment;
    private volatile MazeFurnishJob lastJob;
    private ConnectedWatcher watcher;
    private String connectedFailure;

    public void setConnectedWatcher(ConnectedWatcher watcher) {
        this.watcher = watcher;
    }

    public boolean connectedRunning() {
        return drive != Drive.OFF;
    }

    public int connectedSegments() {
        return segmentsDone;
    }

    /** The last job handed to the furnisher, room or segment. */
    public MazeFurnishJob lastJob() {
        return lastJob;
    }

    public String driveState() {
        return drive + (drive == Drive.HOLD ? " " + holdTicks : "") + " segments=" + segmentsDone + " failures=" + failures;
    }

    /** Starts the endless run: one segment after another, each furnished before the next is dug. */
    public Outcome startConnected() {
        if (drive != Drive.OFF) return Outcome.fail("Already running (" + driveState() + ")");
        Baritone.settings().mazeMode.value = true;
        failures = 0;
        return nextSegment();
    }

    public void stopConnected() {
        if (drive == Drive.FURNISH) cancelFurnisher();
        drive = Drive.OFF;
        cancel();
    }

    private Outcome nextSegment() {
        startedSegment = null;
        rechecks = 0;
        furnishTries = 0;
        connectedFailure = null;
        Outcome out = buildConnected(null);
        if (out.ok()) {
            drive = Drive.BUILD;
            driveTicks = 0;
        } else {
            retryLater(out.error);
        }
        return out;
    }

    private void retryLater(String why) {
        failures++;
        if (watcher != null) watcher.failed(why);
        System.out.println("MAZETEST CONNECTED fail " + failures + "/" + MAX_FAILURES + " " + why);
        logDirect("Connected: " + why + (failures >= MAX_FAILURES ? " Giving up." : " Trying again in " + RETRY_TICKS / 20 + "s."));
        if (failures >= MAX_FAILURES) {
            drive = Drive.OFF;
            return;
        }
        drive = Drive.HOLD;
        holdTicks = RETRY_TICKS;
    }

    private void driveConnected() {
        if (drive == Drive.OFF) return;
        if (!enabled() || ctx.player() == null) {
            drive = Drive.OFF;
            return;
        }
        int tick = ctx.player().tickCount;
        if (tick == lastDriveTick) return; // isActive() can be asked more than once a tick
        lastDriveTick = tick;
        driveTicks++;
        switch (drive) {
            case BUILD -> {
                boolean digging = handedToBuilder != null && baritone.getBuilderProcess().isActive();
                if (phase != Phase.IDLE || digging || driveTicks < 20) return;
                ConnectedPlan.Segment seg = startedSegment;
                if (seg == null) {
                    retryLater(connectedFailure != null ? connectedFailure : "the segment never reached the builder");
                    return;
                }
                Set<BlockPos> left = uncarved(seg);
                if (!left.isEmpty() && rechecks < 2) {
                    // a client ghost or a block the builder gave up on: one more pass over just those cells
                    rechecks++;
                    System.out.println("MAZETEST CONNECTED recheck " + rechecks + " cells=" + left.size());
                    dig("maze-connected-recheck-" + rechecks, left, seg);
                    driveTicks = 0;
                    return;
                }
                if (watcher != null) watcher.dug(seg);
                Outcome out = furnishHere();
                if (!out.ok()) {
                    if (++furnishTries < 8) {
                        driveTicks = 0;
                        return;
                    }
                    retryLater("furnish refused: " + out.error);
                    return;
                }
                logDirect(out.message);
                drive = Drive.FURNISH;
                driveTicks = 0;
                sawBusy = false;
            }
            case FURNISH -> {
                boolean busy = furnisherBusy();
                if (busy) sawBusy = true;
                boolean timedOut = driveTicks > FURNISH_LIMIT;
                if (!timedOut && (busy || !sawBusy && driveTicks < 200)) return;
                if (timedOut) cancelFurnisher();
                segmentsDone++;
                failures = 0;
                int hold = watcher == null ? 0 : watcher.furnished(startedSegment, lastJob, timedOut);
                if (drive == Drive.OFF) return; // the watcher ended the run
                drive = Drive.HOLD;
                holdTicks = Math.max(hold, 20);
            }
            case HOLD -> {
                if (--holdTicks <= 0) nextSegment();
            }
            default -> {
            }
        }
    }

    /** Cells of the segment that are still not air on the server. */
    private Set<BlockPos> uncarved(ConnectedPlan.Segment seg) {
        return onServer(() -> {
            net.minecraft.world.level.Level level = probeLevel();
            Set<BlockPos> out = new LinkedHashSet<>();
            for (long k : seg.newAir) {
                BlockPos at = new BlockPos(ConnectedPlan.x(k), ConnectedPlan.y(k), ConnectedPlan.z(k));
                if (!level.getBlockState(at).isAir()) out.add(at);
            }
            return out;
        });
    }

    private static boolean furnisherBusy() {
        IMazeFurnisher f = MazeFurnishers.get();
        if (f == null) return false;
        try {
            return f.busy();
        } catch (RuntimeException e) {
            return false; // a furnisher older than busy(): fall back to the 10 s grace
        }
    }

    private static void cancelFurnisher() {
        IMazeFurnisher f = MazeFurnishers.get();
        if (f == null) return;
        try {
            f.cancel();
        } catch (RuntimeException ignored) {
            // older furnisher
        }
    }

    /**
     * The furnish job of a dug segment: cladding (room theme, parent's door floor, tunnel palette), the torches the whole
     * complex still needs, the room's containers and fill, then seals bottom up.
     */
    private MazeFurnishJob connectedJob(ConnectedPlan.Segment seg, List<LootPlanner.LootPlacement> loot, boolean survival) {
        return onServer(() -> connectedJobNow(seg, loot, survival));
    }

    // The schematic only digs, so creative gets every step too; survival only changes how TenorClef gets the blocks.
    private MazeFurnishJob connectedJobNow(ConnectedPlan.Segment seg, List<LootPlanner.LootPlacement> loot, boolean survival) {
        MazeWorldProbe probe = new MazeWorldProbe(ctx.world());
        net.minecraft.world.level.Level level = probeLevel();
        List<MazeFurnishJob.Step> steps = new ArrayList<>();
        ConnectedPlan.Complex c = seg.after;
        List<MazeFurnishJob.Step> clad = ConnectedPlan.cladding(seg);
        steps.addAll(clad);
        // Light is planned over the loaded part of the whole complex: the new segment's dark cells, and any older cell a
        // broken torch left dark. Unloaded cells are left to a later furnish rather than guessed at.
        Set<Long> air = new HashSet<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (long k : c.air()) {
            if (level.isLoaded(at.set(ConnectedPlan.x(k), ConnectedPlan.y(k), ConnectedPlan.z(k)))) air.add(k);
        }
        Set<Long> containers = c.containers();
        Set<Long> open = ConnectedLight.open(air, containers);
        List<Long> torches = torchesIn(level, air);
        ConnectedLight.Result light = ConnectedLight.plan(open, torches,
                k -> ConnectedPlan.mountable(c, open, containers, k), k -> ConnectedPlan.preferredTorch(c, k));
        for (long k : light.torches()) {
            steps.add(new MazeFurnishJob.Step(MazeFurnishJob.StepKind.LIGHT, ConnectedPlan.x(k), ConnectedPlan.y(k), ConnectedPlan.z(k),
                    MazeFurnishPlanner.lightIds(seg.plan.theme), List.of()));
        }
        lastLight = "standing=" + torches.size() + " new=" + light.torches().size() + " dark-left=" + light.dark().size()
                + " targets=" + light.targets() + " pool=" + light.pool() + (light.exact() ? " exact" : " greedy")
                + (air.size() < c.air().size() ? " unloaded=" + (c.air().size() - air.size()) : "");
        System.out.println("MAZETEST CONNECTED light " + lastLight + " at " + light.torches().stream()
                .map(k -> ConnectedPlan.x(k) + "," + ConnectedPlan.y(k) + "," + ConnectedPlan.z(k)).toList());
        for (LootPlanner.LootPlacement placement : loot) {
            steps.add(new MazeFurnishJob.Step(MazeFurnishJob.StepKind.CONTAINER, placement.x(), placement.y(), placement.z(),
                    List.of(MazeFurnishPlanner.containerId(placement.kind())), List.of()));
        }
        for (LootPlanner.LootPlacement placement : loot) {
            List<MazeFurnishJob.ItemCount> contents = new ArrayList<>();
            for (LootPlanner.LootStack stack : placement.stacks()) contents.add(new MazeFurnishJob.ItemCount(stack.itemId(), stack.count()));
            steps.add(new MazeFurnishJob.Step(MazeFurnishJob.StepKind.FILL, placement.x(), placement.y(), placement.z(), List.of(), contents));
        }
        Set<Long> cladKeys = new HashSet<>();
        for (MazeFurnishJob.Step st : clad) cladKeys.add(ConnectedPlan.key(st.x(), st.y(), st.z()));
        Set<Long> gaps = new LinkedHashSet<>(ConnectedPlan.shellHoles(seg, probe, cladKeys));
        gaps.addAll(ConnectedPlan.breaches(seg, probe, true));
        List<Long> sorted = new ArrayList<>(gaps);
        // bottom up, so a shaft is closed from the room side first and the bot can pillar out over it
        sorted.sort(Comparator.comparingInt(ConnectedPlan::y).thenComparingInt(ConnectedPlan::x).thenComparingInt(ConnectedPlan::z));
        for (long k : sorted) {
            steps.add(new MazeFurnishJob.Step(MazeFurnishJob.StepKind.SEAL, ConnectedPlan.x(k), ConnectedPlan.y(k), ConnectedPlan.z(k),
                    MazeFurnishPlanner.sealIds(seg.plan.theme, seg.plan.originY), List.of()));
        }
        int[] airCells = new int[seg.newAir.size() * 3];
        int i = 0;
        for (long k : seg.newAir) {
            airCells[i++] = ConnectedPlan.x(k);
            airCells[i++] = ConnectedPlan.y(k);
            airCells[i++] = ConnectedPlan.z(k);
        }
        return new MazeFurnishJob(seg.room.gx(), seg.room.gz(), seg.plan.theme.id, survival, steps, airCells);
    }

    /** Torches standing in {@code air} (loaded cells only). Any light source would do; torches are what the bot places. */
    public static List<Long> torchesIn(net.minecraft.world.level.Level level, Set<Long> air) {
        List<Long> out = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (long k : air) {
            at.set(ConnectedPlan.x(k), ConnectedPlan.y(k), ConnectedPlan.z(k));
            if (!level.isLoaded(at)) continue;
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(at);
            if (st.is(net.minecraft.world.level.block.Blocks.TORCH) || st.is(net.minecraft.world.level.block.Blocks.WALL_TORCH)) out.add(k);
        }
        return out;
    }

    /** The level the maze probes read: the integrated server's overworld in singleplayer, else the client world. */
    public net.minecraft.world.level.Level probeLevel() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.getSingleplayerServer() != null && mc.getSingleplayerServer().overworld() != null) {
            return mc.getSingleplayerServer().overworld();
        }
        return ctx.world();
    }

    /** Palette stones within 16 blocks of the tunnel (8 up and down), by block id. */
    private static Map<String, Integer> stoneAround(net.minecraft.world.level.Level level, int[] box) {
        Set<String> wanted = Set.of("andesite", "diorite", "granite", "tuff");
        Map<String, Integer> out = new HashMap<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int x = box[0] - 16; x <= box[3] + 16; x++) {
            for (int z = box[2] - 16; z <= box[5] + 16; z++) {
                for (int y = Math.max(level.getMinY(), box[1] - 8); y <= Math.min(level.getMaxY() - 1, box[4] + 8); y++) {
                    at.set(x, y, z);
                    if (!level.isLoaded(at)) continue;
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).getPath();
                    if (wanted.contains(id)) out.merge(id, 1, Integer::sum);
                }
            }
        }
        return out;
    }

    /** Ostinato: plank ids for the logs in the treetops around the player, most common first. */
    private java.util.List<String> nearbyPlanks() {
        java.util.Map<String, Integer> seen = new java.util.HashMap<>();
        net.minecraft.core.BlockPos.MutableBlockPos at = new net.minecraft.core.BlockPos.MutableBlockPos();
        int px = ctx.playerFeet().x, pz = ctx.playerFeet().z;
        for (int x = px - 64; x <= px + 64; x += 2) {
            for (int z = pz - 64; z <= pz + 64; z += 2) {
                int top = ctx.world().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
                for (int y = top; y > top - 12; y--) {
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(ctx.world().getBlockState(at.set(x, y, z)).getBlock()).getPath();
                    if (id.endsWith("_log") && !id.startsWith("stripped_")) seen.merge(id.substring(0, id.length() - 4) + "_planks", 1, Integer::sum);
                }
            }
        }
        // Ostinato: only woods TenorClef can harvest (TenorClef 4983e4e9 catalogues every overworld tree wood incl. cherry, mangrove, pale oak). With none
        // left, stone stands in for planks so the furnish never waits on a tree it cannot chop.
        java.util.Set<String> ok = java.util.Set.of("oak_planks", "spruce_planks", "birch_planks", "jungle_planks", "acacia_planks", "dark_oak_planks", "cherry_planks", "mangrove_planks", "pale_oak_planks");
        java.util.List<String> out = new java.util.ArrayList<>(seen.keySet());
        out.removeIf(w -> !ok.contains(w));
        if (out.isEmpty() && !seen.isEmpty()) out.add("polished_andesite");
        out.sort((a, b) -> seen.get(b) - seen.get(a));
        logDirect("Maze furnish: woods nearby " + seen + " usable " + out);
        return out;
    }}
