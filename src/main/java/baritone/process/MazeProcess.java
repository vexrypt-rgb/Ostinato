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
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.maze.LootPlanner;
import baritone.maze.MazeMap;
import baritone.maze.MazePlanner;
import baritone.maze.MazeSeed;
import baritone.maze.MazeSwarmBus;
import baritone.maze.MazeWorldProbe;
import baritone.maze.MazeSchematic;
import baritone.maze.RoomPlan;
import baritone.maze.StealthCadence;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds one underground room when {@code mazeMode} is on and {@code #maze build} has been used.
 * While the setting is off this process stays inactive and does not change path costs, movement, or any other process.
 * The wait before the build is the stealth cadence. The builder then carves the schematic; per-block pacing inside
 * the builder is not applied here.
 */
public final class MazeProcess extends BaritoneProcessHelper {

    private enum Phase { IDLE, WAIT }

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

    public Outcome planHere() {
        Report report = prepare(false);
        return report.error != null ? Outcome.fail(report.error) : Outcome.ok(report.message);
    }

    public String status() {
        if (!enabled()) {
            return "Maze mode is off";
        }
        if (phase == Phase.WAIT && pending != null) {
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
        if (handedToBuilder != null && baritone.getBuilderProcess().isActive()
                && baritone.getBuilderProcess().displayName().contains(handedToBuilder)) {
            baritone.getPathingBehavior().cancelEverything();
        }
        handedToBuilder = null;
        summary = "idle";
    }

    @Override
    public boolean isActive() {
        if (!enabled()) {
            if (phase != Phase.IDLE) {
                clearPending();
            }
            return false;
        }
        return phase == Phase.WAIT && pending != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (phase != Phase.WAIT || pending == null || !enabled()) {
            clearPending();
            return new PathingCommand((Goal) null, PathingCommandType.DEFER);
        }
        if (waitTicks > 0) {
            waitTicks--;
            summary = "Waiting " + waitTicks + " ticks before building cell " + pending.cellX + "," + pending.cellZ;
            return new PathingCommand((Goal) null, PathingCommandType.REQUEST_PAUSE);
        }
        return handOff();
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
            baritone.getBuilderProcess().build(name, new MazeSchematic(plan, loot, !ctx.player().getAbilities().instabuild), new BlockPos(plan.originX, plan.originY, plan.originZ));
            handedToBuilder = name;
            lastBuilt = plan;
            summary = "Building " + name + " (" + plan.theme.displayName + ")";
            logDirect(summary);
        } catch (IOException e) {
            summary = "Maze map could not be saved: " + e.getMessage();
            logDirect(summary);
        }
        return new PathingCommand((Goal) null, PathingCommandType.DEFER);
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
        int preferred = ctx.playerFeet().y - 16;
        int limit = maxY - MazePlanner.SIZE_Y - MazeSeed.COVER;
        if (preferred > limit) {
            preferred = limit;
        }
        if (preferred < minY) {
            preferred = minY;
        }
        int cellX = MazeSeed.cellOf(ctx.playerFeet().x);
        int cellZ = MazeSeed.cellOf(ctx.playerFeet().z);
        MazePlanner.Attempt attempt = MazePlanner.placeOpen(
                Baritone.settings().mazeSeed.value,
                cellX, cellZ, preferred, minY, maxY,
                new MazeWorldProbe(ctx.world()),
                map, MazePlanner.SEARCH_RADIUS);
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
            path = baritone.getDirectory().resolve(raw);
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
        pendingLoot = List.of();
        waitTicks = 0;
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
}
