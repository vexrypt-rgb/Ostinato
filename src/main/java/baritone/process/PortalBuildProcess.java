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
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.process.portal.PortalLayout;
import baritone.process.portal.PortalLayout.Cell;
import baritone.process.portal.PortalLayout.Face;
import baritone.process.portal.PortalLayout.Placement;
import baritone.process.portal.PortalLayout.Site;
import baritone.process.portal.PortalLayout.Stand;
import baritone.process.portal.PortalSiteFinder;
import baritone.process.portal.PortalSiteFinder.Candidate;
import baritone.process.portal.PortalSiteFinder.Kind;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.ToolSet;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.fluid.FluidState;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.PickaxeItem;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Builds a nether portal in a lava pool with a water bucket, the way speedrunners do: the first water sets the
 * base, a few blocks on the shore hold a second water above it, and lava poured from the pool sets against that
 * water cell by cell. {@link PortalLayout} has the shape, {@link PortalSiteFinder} picks the stretch of shore.
 * <p>
 * Every step looks at the world before and after: a cell is poured only with water beside it, lava that did not
 * set is taken back, and stone or cobblestone where obsidian should be is dug out and cast again. Clicks are made
 * the way a player makes them: the view turns as a hand turns it, and nothing is clicked until the block and face
 * under the crosshair are the ones meant.
 */
public final class PortalBuildProcess extends BaritoneProcessHelper {

    public enum Phase {
        IDLE("idle"),
        APPROACH("walking to the pool"),
        PREPARE("clearing the site"),
        EDGE("stepping to the edge"),
        BASE_BLOCK("base block"),
        BASE_WATER("base water"),
        BASE_BREAK("breaking the base block"),
        BASE_SET("waiting for the base"),
        FLOOR("casting the floor"),
        BASE_SCOOP("picking the water up"),
        SHAPES("stacking blocks"),
        TO_POOL("crossing to the pool side"),
        CORNER("corner block"),
        MOLD("placing the water"),
        CAST("casting the frame"),
        UNMOLD("picking the water up again"),
        DRAIN("waiting for the water to drain"),
        LIGHT("lighting"),
        REFILL("taking lava along"),
        ENTER("entering");

        public final String what;

        Phase(String what) {
            this.what = what;
        }
    }

    /** How one build went. */
    public static final class Result {

        public final boolean success;
        /** Why it stopped, for a failure. */
        public final String reason;
        public final Phase phase;
        public final String site;
        /** Ticks from arriving on the shore to the lit portal (or to the failure). */
        public final int buildTicks;
        public final int approachTicks;
        public final Map<Phase, Integer> phaseTicks;
        /** Things that went wrong on the way and were put right. */
        public final List<String> recovered;

        Result(boolean success, String reason, Phase phase, String site, int buildTicks, int approachTicks,
               Map<Phase, Integer> phaseTicks, List<String> recovered) {
            this.success = success;
            this.reason = reason;
            this.phase = phase;
            this.site = site;
            this.buildTicks = buildTicks;
            this.approachTicks = approachTicks;
            this.phaseTicks = phaseTicks;
            this.recovered = recovered;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(success ? "Portal built in " : "Portal build failed after ");
            sb.append(String.format("%.1f s", buildTicks / 20.0));
            if (!success) {
                sb.append(" while ").append(phase.what).append(": ").append(reason);
            }
            sb.append(" [");
            boolean first = true;
            for (Map.Entry<Phase, Integer> e : phaseTicks.entrySet()) {
                if (e.getKey() == Phase.APPROACH || e.getValue() == 0) {
                    continue;
                }
                sb.append(first ? "" : ", ").append(e.getKey().name().toLowerCase()).append(' ')
                        .append(String.format("%.1f", e.getValue() / 20.0));
                first = false;
            }
            sb.append("]");
            if (!recovered.isEmpty()) {
                sb.append(" recovered: ").append(recovered);
            }
            return sb.toString();
        }
    }

    private static final Predicate<ItemStack> WATER = s -> s.getItem() == Items.WATER_BUCKET;
    private static final Predicate<ItemStack> LAVA = s -> s.getItem() == Items.LAVA_BUCKET;
    private static final Predicate<ItemStack> EMPTY = s -> s.getItem() == Items.BUCKET;
    private static final Predicate<ItemStack> FLINT = s -> s.getItem() == Items.FLINT_AND_STEEL;
    private static final Predicate<ItemStack> PICKAXE = s -> s.getItem() instanceof PickaxeItem;

    /** Sites are looked for this far around the player. */
    private static final int SEARCH_RADIUS = 24;
    private static final int SEARCH_VERTICAL = 8;

    /** Where the bot stands at the edge: over the lava far enough to see the +z face of the rim it stands on. */
    private static final double EDGE_X = 1.8, EDGE_Z = 0.25;

    /** Lava moves this many ticks after something beside it changes; water takes five. */
    private static final int LAVA_TICK = 30;

    private final Random rand = new Random();

    private Phase phase = Phase.IDLE;
    private int phaseTicks;
    private boolean enter;
    private List<Candidate> candidates = Collections.emptyList();
    private int candidateIndex;
    private Candidate candidate;
    private Site site;
    private final EnumMap<Phase, Integer> spent = new EnumMap<>(Phase.class);
    private final List<String> recovered = new ArrayList<>();
    private Result last;

    /** Index into whatever list the phase works through, and a step within one item. */
    private int index;
    private int sub;
    /** Ticks until the next click or slot change is allowed: a hand is not faster than this. */
    private int cooldown;
    /** Ticks spent waiting for something in the current step. */
    private int waited;
    private int attempts;
    /** Something the build needs in the hand and no longer has. */
    private String lacking;
    /** Ticks since the build began, and when the lava beside the base block was last disturbed. */
    private int clock;
    private int baseDisturbed;
    private int baseRetries;

    // aiming
    private long aimKey = Long.MIN_VALUE;
    private double aimU, aimV;
    private int onTarget;
    private int reaction;
    private int blind;
    private BlockPos scoopTarget;
    private int scoopAge;

    public PortalBuildProcess(Baritone baritone) {
        super(baritone);
    }

    /**
     * Starts a build at the best site near the player.
     *
     * @param enter walk into the portal when it is lit
     * @return null when started, otherwise why not
     */
    public String build(boolean enter) {
        if (ctx.player() == null || ctx.world() == null) {
            return "not in a world";
        }
        if (ctx.world().getDimensionKey() == World.THE_NETHER) {
            return "water does not last in the nether";
        }
        String missing = missingItems(PortalLayout.BLOCKS_NEEDED);
        if (missing != null) {
            return missing;
        }
        List<Candidate> found = find();
        if (found.isEmpty()) {
            return "no lava pool shore within " + SEARCH_RADIUS + " blocks that the portal fits on";
        }
        reset();
        this.enter = enter;
        this.candidates = found;
        this.candidateIndex = 0;
        choose(found.get(0));
        setPhase(Phase.APPROACH);
        return null;
    }

    /** The sites a build started now would consider, best first. */
    public List<Candidate> find() {
        BlockPos feet = ctx.playerFeet();
        return PortalSiteFinder.find(this::kind, feet.getX(), feet.getY(), feet.getZ(),
                SEARCH_RADIUS, SEARCH_VERTICAL, has(PICKAXE));
    }

    /** The outcome of the last build that ended, or null. */
    public Result lastResult() {
        return last;
    }

    public Phase phase() {
        return phase;
    }

    public Site site() {
        return site;
    }

    private void choose(Candidate c) {
        this.candidate = c;
        this.site = c.site;
    }

    private void reset() {
        phase = Phase.IDLE;
        phaseTicks = 0;
        candidates = Collections.emptyList();
        candidate = null;
        site = null;
        spent.clear();
        recovered.clear();
        clock = 0;
        baseRetries = 0;
        resetStep();
        index = 0;
    }

    private void resetStep() {
        sub = 0;
        waited = 0;
        attempts = 0;
        blind = 0;
        aimKey = Long.MIN_VALUE;
        scoopTarget = null;
    }

    private void setPhase(Phase next) {
        if (phase != Phase.IDLE) {
            spent.merge(phase, phaseTicks, Integer::sum);
        }
        logDebug("Portal: " + next.what);
        phase = next;
        phaseTicks = 0;
        index = 0;
        resetStep();
    }

    @Override
    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    @Override
    public void onLostControl() {
        if (phase != Phase.IDLE) {
            baritone.getInputOverrideHandler().clearAllKeys();
        }
        phase = Phase.IDLE;
    }

    @Override
    public String displayName0() {
        return "Portal: " + phase.what;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (phase == Phase.APPROACH) {
            return approach(calcFailed);
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        phaseTicks++;
        clock++;
        if (cooldown > 0) {
            cooldown--;
        }
        if (ctx.player().isInLava()) {
            return fail("fell into the lava");
        }
        if (phaseTicks > 1200) {
            return fail("made no progress for a minute");
        }
        String problem;
        try {
            lacking = null;
            problem = step();
            if (problem == null && lacking != null) {
                problem = "no " + lacking + " left";
            }
        } catch (RuntimeException e) {
            e.printStackTrace();
            problem = "internal error: " + e;
        }
        if (problem != null) {
            return fail(problem);
        }
        if (phase == Phase.IDLE) {
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private PathingCommand approach(boolean calcFailed) {
        phaseTicks++;
        BlockPos goal = pos(PortalLayout.RIM_FEET);
        // the pool can look different from close up, and other things change it
        boolean stale = phaseTicks % 20 == 0
                && !PortalSiteFinder.evaluate(this::kind, site, has(PICKAXE)).usable();
        if (calcFailed || stale || phaseTicks > 2400) {
            candidateIndex++;
            if (candidateIndex >= candidates.size() || candidateIndex >= 4) {
                return fail(calcFailed ? "cannot get to the shore" : "the site is no longer usable");
            }
            choose(candidates.get(candidateIndex));
            logDebug("Portal: trying " + candidate);
            return new PathingCommand(new GoalBlock(pos(PortalLayout.RIM_FEET)), PathingCommandType.FORCE_REVALIDATE_GOAL_AND_PATH);
        }
        if (ctx.playerFeet().equals(goal) && ctx.player().isOnGround()) {
            Candidate now = PortalSiteFinder.evaluate(this::kind, site, has(PICKAXE));
            if (!now.usable()) {
                return fail("the site is no longer usable: " + now.reject);
            }
            choose(now);
            String missing = missingItems(now.blocksNeeded());
            if (missing != null) {
                return fail(missing);
            }
            setPhase(Phase.PREPARE);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(new GoalBlock(goal), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private PathingCommand fail(String reason) {
        finish(false, reason);
        return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
    }

    private void finish(boolean success, String reason) {
        Phase at = phase;
        spent.merge(phase, phaseTicks, Integer::sum);
        int build = 0;
        for (Map.Entry<Phase, Integer> e : spent.entrySet()) {
            if (e.getKey() != Phase.APPROACH) {
                build += e.getValue();
            }
        }
        last = new Result(success, reason, at, String.valueOf(candidate), build,
                spent.getOrDefault(Phase.APPROACH, 0), new EnumMap<>(spent), new ArrayList<>(recovered));
        logDirect(last.toString());
        baritone.getInputOverrideHandler().clearAllKeys();
        phase = Phase.IDLE;
    }

    // ------------------------------------------------------------------------------------------------------------
    // the build, a phase at a time. Each returns null to carry on, or what went wrong.
    // ------------------------------------------------------------------------------------------------------------

    private String step() {
        switch (phase) {
            case PREPARE:
                return prepare();
            case EDGE:
                if (walk(EDGE_X, EDGE_Z, true, 0.12)) {
                    setPhase(Phase.BASE_BLOCK);
                }
                return null;
            case BASE_BLOCK:
                walk(EDGE_X, EDGE_Z, true, 0.12);
                if (ground(PortalLayout.BASE_BLOCK.cell)) {
                    baseDisturbed = clock;
                    setPhase(Phase.BASE_WATER);
                    return null;
                }
                if (kind(PortalLayout.BASE_BLOCK.cell) != Kind.LAVA_SOURCE) {
                    return "the lava at " + where(PortalLayout.BASE_BLOCK.cell) + " is gone";
                }
                return place(PortalLayout.BASE_BLOCK, true);
            case BASE_WATER:
                walk(EDGE_X, EDGE_Z, true, 0.12);
                if (waterSource(PortalLayout.BASE_WATER.cell)) {
                    setPhase(Phase.BASE_BREAK);
                    return null;
                }
                if (!has(WATER)) {
                    return ++waited > 20 ? "the water did not go where it was put" : null;
                }
                return pour(PortalLayout.BASE_WATER, WATER);
            case BASE_BREAK:
                walk(EDGE_X, EDGE_Z, true, 0.12);
                if (!ground(PortalLayout.BASE_BLOCK.cell)) {
                    setPhase(Phase.BASE_SET);
                    return null;
                }
                if (sub == 0) {
                    // The lava beside the block was disturbed when the block went in, and moves once, LAVA_TICK
                    // later. If the block is gone by then and the water has not yet run into the gap, the lava
                    // gets there first and sets as cobblestone. So the block comes out well before or just after.
                    int out = clock - baseDisturbed + breakTicks(pos(PortalLayout.BASE_BLOCK.cell)) + 3;
                    if (out > LAVA_TICK - 9 && out <= LAVA_TICK + 3) {
                        return dig(pos(PortalLayout.BASE_BLOCK.cell), where(PortalLayout.BASE_BLOCK.cell), false);
                    }
                    sub = 1;
                }
                return dig(PortalLayout.BASE_BLOCK.cell);
            case BASE_SET:
                walk(EDGE_X, EDGE_Z, true, 0.12);
                return baseSet();
            case FLOOR:
                walk(EDGE_X, EDGE_Z, true, 0.12);
                if (index >= PortalLayout.FLOOR.size()) {
                    setPhase(Phase.BASE_SCOOP);
                    return null;
                }
                return castCell(PortalLayout.FLOOR.get(index), null, "floor " + (index + 1));
            case BASE_SCOOP:
                walk(EDGE_X, EDGE_Z, true, 0.12);
                if (!waterSource(PortalLayout.BASE_WATER.cell)) {
                    if (has(WATER)) {
                        setPhase(Phase.SHAPES);
                        return null;
                    }
                    return ++waited > 20 ? "the water is gone" : null;
                }
                return scoop(Collections.singletonList(pos(PortalLayout.BASE_WATER.cell)), "the water");
            case SHAPES:
                return shapes();
            case TO_POOL:
                return toPool();
            case CORNER:
                stand(Stand.A);
                if (ground(PortalLayout.CORNER.cell)) {
                    setPhase(Phase.MOLD);
                    return null;
                }
                return place(PortalLayout.CORNER, false);
            case MOLD:
                stand(Stand.A);
                if (waterSource(PortalLayout.MOLD_WATER.cell)) {
                    setPhase(Phase.CAST);
                    return null;
                }
                if (!has(WATER)) {
                    return ++waited > 20 ? "the water did not go where it was put" : null;
                }
                return pour(PortalLayout.MOLD_WATER, WATER);
            case CAST: {
                if (index >= PortalLayout.CASTS.size()) {
                    setPhase(Phase.UNMOLD);
                    return null;
                }
                Placement p = PortalLayout.CASTS.get(index);
                if (!stand(p.stand)) {
                    return null;
                }
                return castCell(p.cell, p, p.name);
            }
            case UNMOLD:
                if (!stand(Stand.A)) {
                    return null;
                }
                if (!waterSource(PortalLayout.MOLD_WATER.cell)) {
                    if (has(WATER)) {
                        setPhase(Phase.DRAIN);
                        return null;
                    }
                    return ++waited > 20 ? "the water is gone" : null;
                }
                return scoop(Collections.singletonList(pos(PortalLayout.MOLD_WATER.cell)), "the water");
            case DRAIN:
                stand(Stand.A);
                return drain();
            case LIGHT:
                stand(Stand.A);
                return light();
            case REFILL:
                stand(Stand.A);
                if (has(LAVA) || !has(EMPTY) || waited > 200) {
                    if (enter) {
                        setPhase(Phase.ENTER);
                    } else {
                        finish(true, null);
                    }
                    return null;
                }
                waited++;
                return scoopLava();
            case ENTER:
                if (phaseTicks == 1) {
                    // the build is over: what is left is a walk
                    Phase keep = phase;
                    finish(true, null);
                    phase = keep;
                }
                if (walk(1.5, 0.5, false, 0.2) || phaseTicks > 100) {
                    phase = Phase.IDLE;
                }
                return null;
            default:
                return "nothing to do in " + phase;
        }
    }

    private String prepare() {
        // the ends of the shore row first: each is clicked against the side of the one next to it
        if (index < candidate.rimPlaces.size()) {
            Cell rim = candidate.rimPlaces.get(index);
            if (ground(rim)) {
                index++;
                return null;
            }
            boolean left = rim.x == 0;
            walk(left ? 0.75 : 3.25, -0.5, true, 0.1);
            Cell support = new Cell(left ? 1 : 2, 1, -1);
            if (!hold(this::throwaway, "blocks")) {
                return null;
            }
            BlockRayTraceResult hit = aimFace(pos(support), dir(left ? Face.MINUS_X : Face.PLUS_X), false, false);
            if (hit != null) {
                use(hit);
            }
            return blind > 100 ? "cannot fill in the shore at " + where(rim) : null;
        }
        // back from the end of the row, if that is where it was
        if (!stand(Stand.RIM)) {
            return null;
        }
        int at = index - candidate.rimPlaces.size();
        if (at < candidate.breaks.size()) {
            Cell c = candidate.breaks.get(at);
            Kind k = kind(c);
            if (k == Kind.AIR) {
                index++;
                return null;
            }
            return dig(c);
        }
        setPhase(Phase.EDGE);
        return null;
    }

    private String baseSet() {
        waited++;
        // the water runs into the broken block's cell and sets what is around and under it
        boolean settled = true;
        Cell[] around = {new Cell(0, 1, 0), new Cell(3, 1, 0), PortalLayout.STAND_A, PortalLayout.STAND_B,
                PortalLayout.FLOOR.get(0), PortalLayout.FLOOR.get(1)};
        String pending = null;
        for (Cell c : around) {
            Kind k = kind(c);
            if (k == Kind.LAVA_SOURCE || k == Kind.LAVA_FLOW) {
                settled = false;
                pending = where(c);
            }
        }
        if (ground(PortalLayout.BASE_BLOCK.cell)) {
            // lava ran into the gap before the water did and set there
            if (++baseRetries > 3) {
                return "lava keeps running into the base at " + where(PortalLayout.BASE_BLOCK.cell);
            }
            note("lava got into the base before the water and set as " + name(PortalLayout.BASE_BLOCK.cell) + ", dug out");
            baseDisturbed = clock;
            setPhase(Phase.BASE_BREAK);
            return null;
        }
        if (!water(PortalLayout.BASE_BLOCK.cell)) {
            settled = false;
        }
        if (settled) {
            setPhase(Phase.FLOOR);
            return null;
        }
        if (waited > 60) {
            return pending != null ? "the lava at " + pending + " did not set" : "the water did not spread";
        }
        return null;
    }

    private String shapes() {
        if (sub == 0) {
            if (stand(Stand.RIM)) {
                sub = 1;
            }
            return null;
        }
        if (index >= PortalLayout.SHAPES.size()) {
            setPhase(Phase.TO_POOL);
            return null;
        }
        Placement p = PortalLayout.SHAPES.get(index);
        if (ground(p.cell)) {
            index++;
            aimKey = Long.MIN_VALUE;
            return null;
        }
        // stand up straight here: the third pillar block is put on from a jump. Off the spot, sneak back to it:
        // a block cannot be put where the bot is standing, and the lava is a step away.
        if (!walk(1.5, -0.5, false, 0.15)) {
            input(Input.SNEAK);
        }
        return place(p, false);
    }

    private String toPool() {
        double feet = ctx.player().getPosY();
        int top = site.worldY(2);
        lookAt(facePoint(pos(PortalLayout.CORNER.support), dir(PortalLayout.CORNER.face), 0.5, 0.5));
        if (sub == 0) {
            // off the rim into the emptied row
            walk(1.5, 0.45, false, 0.15);
            if (feet < top - 0.5 && ctx.player().isOnGround()) {
                sub = 1;
            }
            return phaseTicks > 100 && feet >= top - 0.5 ? "cannot get down into the emptied row" : null;
        }
        if (sub == 1) {
            // up onto the obsidian on the pool side, and no further: the pool is beyond it
            if (feet >= top - 0.01 && ctx.player().isOnGround()) {
                sub = 2;
                return null;
            }
            walk(1.5, 1.5, false, 0.1);
            if (ctx.player().isOnGround()) {
                input(Input.JUMP);
            }
            return phaseTicks > 200 ? "cannot get up onto the pool side" : null;
        }
        if (stand(Stand.A)) {
            setPhase(Phase.CORNER);
        }
        return null;
    }

    private String drain() {
        waited++;
        for (Cell c : PortalLayout.FRAME) {
            if (kind(c) != Kind.OBSIDIAN) {
                return "the frame is not obsidian at " + where(c);
            }
        }
        boolean clear = true;
        for (Cell c : PortalLayout.INTERIOR) {
            Kind k = kind(c);
            if (k == Kind.AIR) {
                continue;
            }
            clear = false;
            if (k == Kind.SOLID || k == Kind.PLANT || k == Kind.OBSIDIAN) {
                if (waited % 100 == 1) {
                    note("dug " + name(c) + " out of the portal");
                }
                return dig(c);
            }
            if (k == Kind.LAVA_SOURCE && has(EMPTY)) {
                return scoop(Collections.singletonList(pos(c)), "stray lava");
            }
        }
        if (clear) {
            setPhase(Phase.LIGHT);
            return null;
        }
        return waited > 400 ? "the portal did not drain" : null;
    }

    private String light() {
        for (Cell c : PortalLayout.INTERIOR) {
            if (state(c).getBlock() == Blocks.NETHER_PORTAL) {
                setPhase(Phase.REFILL);
                return null;
            }
        }
        if (!hold(FLINT, "flint and steel")) {
            return null;
        }
        if (attempts > 5) {
            return "the portal did not light";
        }
        Cell floor = PortalLayout.FLOOR.get(0);
        BlockRayTraceResult hit = aimFace(pos(floor), Direction.UP, false, false);
        if (hit != null) {
            use(hit);
            cooldown = 10;
            attempts++;
        }
        return blind > 100 ? "cannot see the bottom of the portal" : null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // casting one cell
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Works toward {@code cell} being obsidian, from whatever it is now, and moves on to the next index when it
     * is. {@code planned} is the click the layout intends; without one, any solid neighbour that can be seen is
     * clicked.
     */
    private String castCell(Cell cell, Placement planned, String name) {
        Kind k = kind(cell);
        switch (k) {
            case OBSIDIAN:
                index++;
                resetStep();
                return null;
            case LAVA_SOURCE:
                // poured and waiting for the server, or poured with no water beside it
                if (++waited < 30) {
                    return null;
                }
                if (waited == 30) {
                    note(name + " did not set, taking the lava back");
                    attempts++;
                }
                if (attempts > 3) {
                    return name + " will not set at " + where(cell);
                }
                return scoop(Collections.singletonList(pos(cell)), "the lava");
            case LAVA_FLOW:
                return ++waited > 200 ? "lava is running into " + name + " at " + where(cell) : null;
            case SOLID:
            case PLANT:
                // cobblestone or stone where the lava met running water, or the pool's own floor
                if (sub != 9) {
                    sub = 9;
                    if (planned != null || state(cell).getBlock() == Blocks.COBBLESTONE) {
                        note(name + " was " + name(cell) + ", dug out");
                    }
                    if (!has(PICKAXE) && state(cell).getMaterial() == Material.ROCK) {
                        return name + " is " + name(cell) + " and there is no pickaxe";
                    }
                }
                return dig(cell);
            case OTHER:
                return name + " is blocked at " + where(cell);
            default:
                break;
        }
        // empty, or water: pour
        sub = 0;
        if (!has(LAVA)) {
            if (!has(EMPTY)) {
                return "no bucket to fetch lava with";
            }
            return scoopLava();
        }
        if (!waterBeside(cell)) {
            return ++waited > 100 ? "no water beside " + name + " at " + where(cell) : null;
        }
        waited = 0;
        if (!hold(LAVA, "lava bucket")) {
            return null;
        }
        BlockRayTraceResult hit = null;
        if (planned != null && ground(planned.support)) {
            hit = aimFace(pos(planned.support), dir(planned.face), false, false);
        }
        if (hit == null && (planned == null || blind > 10 || !ground(planned.support))) {
            // another way in: any solid neighbour whose face toward the cell can be seen
            for (Face f : Face.values()) {
                Cell support = new Cell(cell.x - f.dx, cell.y - f.dy, cell.z - f.dz);
                if (ground(support) && visiblePoint(pos(support), dir(f), eye()) != null) {
                    hit = aimFace(pos(support), dir(f), false, false);
                    break;
                }
            }
        }
        if (hit != null) {
            use(hit);
            waited = 0;
            return null;
        }
        return blind > 120 ? "cannot see where to pour " + name + " at " + where(cell) : null;
    }

    private boolean waterBeside(Cell c) {
        // lava sets against water on any side but below
        for (Face f : Face.values()) {
            if (f != Face.DOWN && water(c.offset(f))) {
                return true;
            }
        }
        return false;
    }

    private String scoopLava() {
        List<BlockPos> sources = new ArrayList<>();
        for (int x = -3; x <= 6; x++) {
            for (int z = 0; z <= 5; z++) {
                if (z == 0 && x >= 0 && x <= 3) {
                    continue;
                }
                Cell c = new Cell(x, 1, z);
                if (kind(c) == Kind.LAVA_SOURCE) {
                    sources.add(pos(c));
                }
            }
        }
        if (sources.isEmpty()) {
            return "no lava left in reach";
        }
        return scoop(sources, "lava");
    }

    // ------------------------------------------------------------------------------------------------------------
    // hands: placing, pouring, scooping, digging
    // ------------------------------------------------------------------------------------------------------------

    private String place(Placement p, boolean quickToBreak) {
        if (!holdBlock(quickToBreak)) {
            return null;
        }
        boolean jump = p.face == Face.UP && p.cell.y >= 4;
        BlockRayTraceResult hit = aimFace(pos(p.support), dir(p.face), jump, jump);
        if (hit != null) {
            use(hit);
        }
        return blind > 100 ? "cannot see where to put the " + p.name + " at " + where(p.cell) : null;
    }

    private String pour(Placement p, Predicate<ItemStack> bucket) {
        if (!hold(bucket, "bucket")) {
            return null;
        }
        BlockRayTraceResult hit = aimFace(pos(p.support), dir(p.face), false, false);
        if (hit != null) {
            use(hit);
            waited = 0;
        }
        return blind > 100 ? "cannot see where to put the " + p.name + " at " + where(p.cell) : null;
    }

    /** Right click on a block face, as the game does it: the block first, then the item in the hand. */
    private void use(BlockRayTraceResult hit) {
        ActionResultType result = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), Hand.MAIN_HAND, hit);
        if (result == ActionResultType.PASS && !ctx.player().getHeldItem(Hand.MAIN_HAND).isEmpty()) {
            result = ctx.playerController().processRightClick(ctx.player(), ctx.world(), Hand.MAIN_HAND);
        }
        if (result == ActionResultType.SUCCESS) {
            ctx.player().swingArm(Hand.MAIN_HAND);
        }
        cooldown = 3 + rand.nextInt(3);
        onTarget = 0;
    }

    /** Takes a source block up with an empty bucket. Any of the given cells will do. */
    private String scoop(List<BlockPos> cells, String what) {
        if (!hold(EMPTY, "empty bucket")) {
            return null;
        }
        Vector3d eye = eye();
        if (scoopTarget != null && (++scoopAge > 40 || !cells.contains(scoopTarget) || fluidPoint(scoopTarget, eye) == null)) {
            scoopTarget = null;
        }
        Vector3d point = scoopTarget == null ? null : fluidPoint(scoopTarget, eye);
        if (point == null) {
            // the one that takes the least turning
            Vector3d view = RotationUtils.calcVector3dFromRotation(ctx.playerRotations());
            double best = -2;
            for (BlockPos cell : cells) {
                Vector3d p = fluidPoint(cell, eye);
                if (p == null) {
                    continue;
                }
                double dot = p.subtract(eye).normalize().dotProduct(view);
                if (dot > best) {
                    best = dot;
                    point = p;
                    scoopTarget = cell;
                    scoopAge = 0;
                }
            }
        }
        if (point == null) {
            return ++blind > 100 ? "cannot reach " + what : null;
        }
        blind = 0;
        lookAt(point);
        RayTraceResult now = trace(eye, ctx.playerRotations(), RayTraceContext.FluidMode.SOURCE_ONLY);
        boolean on = now.getType() == RayTraceResult.Type.BLOCK && cells.contains(((BlockRayTraceResult) now).getPos());
        onTarget = on ? onTarget + 1 : 0;
        if (on && onTarget >= 2 && cooldown == 0) {
            // what the crosshair is on gets the click first, as it does for a player
            RayTraceResult cross = trace(eye, ctx.playerRotations(), RayTraceContext.FluidMode.NONE);
            ActionResultType result = ActionResultType.PASS;
            if (cross.getType() == RayTraceResult.Type.BLOCK) {
                result = ctx.playerController().processRightClickBlock(ctx.player(), ctx.world(), Hand.MAIN_HAND, (BlockRayTraceResult) cross);
            }
            if (result == ActionResultType.PASS) {
                result = ctx.playerController().processRightClick(ctx.player(), ctx.world(), Hand.MAIN_HAND);
            }
            if (result == ActionResultType.SUCCESS) {
                ctx.player().swingArm(Hand.MAIN_HAND);
            }
            cooldown = 3 + rand.nextInt(3);
            onTarget = 0;
            scoopTarget = null;
        }
        return null;
    }

    /** A point in a fluid cell that an empty bucket's ray gets to before anything else, or null. */
    private Vector3d fluidPoint(BlockPos cell, Vector3d eye) {
        FluidState fluid = ctx.world().getFluidState(cell);
        if (!fluid.isSource()) {
            return null;
        }
        double top = fluid.getActualHeight(ctx.world(), cell);
        double[] across = {0.5, 0.3, 0.7};
        double[] up = {top * 0.6, top * 0.9, top * 0.2};
        for (double y : up) {
            for (double x : across) {
                for (double z : across) {
                    Vector3d p = new Vector3d(cell.getX() + x, cell.getY() + y, cell.getZ() + z);
                    RayTraceResult r = traceTo(eye, p, RayTraceContext.FluidMode.SOURCE_ONLY);
                    if (r.getType() == RayTraceResult.Type.BLOCK && ((BlockRayTraceResult) r).getPos().equals(cell)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** Breaks a block: the best tool, the view on it, the button held while it is under the crosshair. */
    private String dig(Cell c) {
        return dig(pos(c), where(c), true);
    }

    /** Ticks the best tool takes to break what is at {@code p}. */
    private int breakTicks(BlockPos p) {
        double perTick = new ToolSet(ctx.player()).getStrVsBlock(ctx.world().getBlockState(p));
        return perTick <= 0 ? 1000 : (int) Math.ceil(1.0 / perTick);
    }

    /** @param press false to get the tool out and the view onto the block without starting on it */
    private String dig(BlockPos p, String where, boolean press) {
        BlockState s = ctx.world().getBlockState(p);
        if (s.getBlockHardness(ctx.world(), p) < 0) {
            return "cannot break what is at " + where;
        }
        MovementHelper.switchToBestToolFor(ctx, s);
        Vector3d eye = eye();
        Vector3d point = null;
        double near = Double.MAX_VALUE;
        for (Direction face : Direction.values()) {
            Vector3d v = visiblePoint(p, face, eye);
            if (v != null && v.squareDistanceTo(eye) < near) {
                near = v.squareDistanceTo(eye);
                point = v;
            }
        }
        if (point == null) {
            lookAt(new Vector3d(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
            return ++blind > 100 ? "cannot reach the block at " + where : null;
        }
        blind = 0;
        lookAt(point);
        RayTraceResult now = trace(eye, ctx.playerRotations(), RayTraceContext.FluidMode.NONE);
        RayTraceResult cross = ctx.objectMouseOver();
        if (press && onBlock(now, p) && onBlock(cross, p)) {
            input(Input.CLICK_LEFT);
        }
        return null;
    }

    private static boolean onBlock(RayTraceResult r, BlockPos p) {
        return r != null && r.getType() == RayTraceResult.Type.BLOCK && ((BlockRayTraceResult) r).getPos().equals(p);
    }

    // ------------------------------------------------------------------------------------------------------------
    // eyes
    // ------------------------------------------------------------------------------------------------------------

    private Vector3d eye() {
        return ctx.player().getEyePosition(1.0F);
    }

    private double reach() {
        return ctx.playerController().getBlockReachDistance();
    }

    private RayTraceResult trace(Vector3d eye, Rotation rot, RayTraceContext.FluidMode fluid) {
        Vector3d d = RotationUtils.calcVector3dFromRotation(rot);
        double r = reach();
        Vector3d end = eye.add(d.x * r, d.y * r, d.z * r);
        return ctx.world().rayTraceBlocks(new RayTraceContext(eye, end, RayTraceContext.BlockMode.OUTLINE, fluid, ctx.player()));
    }

    private RayTraceResult traceTo(Vector3d eye, Vector3d point, RayTraceContext.FluidMode fluid) {
        Vector3d d = point.subtract(eye).normalize();
        double r = reach();
        Vector3d end = eye.add(d.x * r, d.y * r, d.z * r);
        return ctx.world().rayTraceBlocks(new RayTraceContext(eye, end, RayTraceContext.BlockMode.OUTLINE, fluid, ctx.player()));
    }

    private static Vector3d facePoint(BlockPos p, Direction face, double u, double v) {
        double x = p.getX(), y = p.getY(), z = p.getZ();
        switch (face) {
            case UP:
                return new Vector3d(x + u, y + 1, z + v);
            case DOWN:
                return new Vector3d(x + u, y, z + v);
            case EAST:
                return new Vector3d(x + 1, y + v, z + u);
            case WEST:
                return new Vector3d(x, y + v, z + u);
            case SOUTH:
                return new Vector3d(x + u, y + v, z + 1);
            default:
                return new Vector3d(x + u, y + v, z);
        }
    }

    private boolean sees(Vector3d eye, Vector3d point, BlockPos p, Direction face) {
        RayTraceResult r = traceTo(eye, point, RayTraceContext.FluidMode.NONE);
        return r.getType() == RayTraceResult.Type.BLOCK
                && ((BlockRayTraceResult) r).getPos().equals(p)
                && ((BlockRayTraceResult) r).getFace() == face;
    }

    private static final double[] GRID = {0.5, 0.35, 0.65, 0.2, 0.8, 0.1, 0.9};

    /** A point on the face that can be clicked from where the eyes are, or null. */
    private Vector3d visiblePoint(BlockPos p, Direction face, Vector3d eye) {
        // the face has to be turned toward the eyes at all
        Vector3d center = facePoint(p, face, 0.5, 0.5);
        double toward = (eye.x - center.x) * face.getXOffset() + (eye.y - center.y) * face.getYOffset() + (eye.z - center.z) * face.getZOffset();
        if (toward <= 0) {
            return null;
        }
        for (double u : GRID) {
            for (double v : GRID) {
                Vector3d point = facePoint(p, face, u, v);
                if (sees(eye, point, p, face)) {
                    return point;
                }
            }
        }
        return null;
    }

    /** Turns the view toward a point the way a hand does. */
    private void lookAt(Vector3d point) {
        Rotation want = RotationUtils.calcRotationFromVec3d(eye(), point, ctx.playerRotations());
        baritone.getLookBehavior().humanMove();
        baritone.getLookBehavior().updateTarget(want, true);
    }

    /**
     * Turns toward a face and says when to click: the result is the hit of the view the player has right now,
     * once that has been on the face for a reaction time, and null until then.
     *
     * @param jump  jump when the face cannot be seen from the ground
     * @param quick click the moment the view is on it (the top of a jump does not wait)
     */
    private BlockRayTraceResult aimFace(BlockPos support, Direction face, boolean jump, boolean quick) {
        long key = support.toLong() * 6 + face.ordinal();
        if (key != aimKey) {
            aimKey = key;
            aimU = 0.3 + rand.nextDouble() * 0.4;
            aimV = 0.3 + rand.nextDouble() * 0.4;
            onTarget = 0;
            blind = 0;
            reaction = 1 + rand.nextInt(2);
        }
        Vector3d eye = eye();
        Vector3d point = facePoint(support, face, aimU, aimV);
        if (!sees(eye, point, support, face)) {
            point = visiblePoint(support, face, eye);
        }
        if (point == null) {
            blind++;
            onTarget = 0;
            lookAt(facePoint(support, face, 0.5, 0.5));
            if (jump && ctx.player().isOnGround()) {
                input(Input.JUMP);
            }
            return null;
        }
        blind = 0;
        lookAt(point);
        RayTraceResult now = trace(eye, ctx.playerRotations(), RayTraceContext.FluidMode.NONE);
        boolean on = now.getType() == RayTraceResult.Type.BLOCK
                && ((BlockRayTraceResult) now).getPos().equals(support)
                && ((BlockRayTraceResult) now).getFace() == face;
        onTarget = on ? onTarget + 1 : 0;
        if (on && cooldown == 0 && (quick || onTarget > reaction)) {
            return (BlockRayTraceResult) now;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------------------
    // feet
    // ------------------------------------------------------------------------------------------------------------

    private void input(Input in) {
        baritone.getInputOverrideHandler().setInputForceState(in, true);
    }

    /** Goes to, and stays at, one of the standing places. True when there. */
    private boolean stand(Stand s) {
        switch (s) {
            case RIM:
                return walk(1.5, -0.5, true, 0.15);
            case A:
                return walk(1.5, 1.5, true, 0.15);
            case B:
                return walk(2.5, 1.5, true, 0.15);
            default:
                return walk(EDGE_X, EDGE_Z, true, 0.12);
        }
    }

    /**
     * Steps toward a point given in site coordinates with the movement keys, whichever way the view is turned.
     * Sneaking, the edge of a block is as far as it goes. True when within {@code tolerance}.
     */
    private boolean walk(double sx, double sz, boolean sneak, double tolerance) {
        if (sneak) {
            input(Input.SNEAK);
        }
        double dx = site.pointX(sx, sz) - ctx.player().getPosX();
        double dz = site.pointZ(sx, sz) - ctx.player().getPosZ();
        if (dx * dx + dz * dz <= tolerance * tolerance) {
            return true;
        }
        double yaw = Math.toRadians(ctx.player().rotationYaw);
        double forward = -Math.sin(yaw) * dx + Math.cos(yaw) * dz;
        double left = Math.cos(yaw) * dx + Math.sin(yaw) * dz;
        double dead = tolerance * 0.7;
        if (forward > dead) {
            input(Input.MOVE_FORWARD);
        } else if (forward < -dead) {
            input(Input.MOVE_BACK);
        }
        if (left > dead) {
            input(Input.MOVE_LEFT);
        } else if (left < -dead) {
            input(Input.MOVE_RIGHT);
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // pockets
    // ------------------------------------------------------------------------------------------------------------

    private boolean has(Predicate<ItemStack> what) {
        for (ItemStack s : ctx.player().inventory.mainInventory) {
            if (what.test(s)) {
                return true;
            }
        }
        return false;
    }

    /** A block that can be spared, will stay where it is put, and will not catch fire next to lava. */
    private boolean throwaway(ItemStack s) {
        Item item = s.getItem();
        if (s.isEmpty() || !(item instanceof BlockItem) || !Baritone.settings().acceptableThrowawayItems.value.contains(item)) {
            return false;
        }
        Block b = ((BlockItem) item).getBlock();
        BlockState d = b.getDefaultState();
        return !(b instanceof FallingBlock) && !d.getMaterial().isFlammable() && b != Blocks.OBSIDIAN
                && MovementHelper.isBlockNormalCube(d);
    }

    private String missingItems(int blocks) {
        List<String> missing = new ArrayList<>();
        if (!has(WATER)) {
            missing.add("a water bucket");
        }
        if (!has(EMPTY) && !has(LAVA)) {
            missing.add("a second bucket");
        }
        if (!has(FLINT)) {
            missing.add("flint and steel");
        }
        int count = 0;
        for (ItemStack s : ctx.player().inventory.mainInventory) {
            if (throwaway(s)) {
                count += s.getCount();
            }
        }
        if (count < blocks) {
            missing.add(blocks + " blocks to build with (have " + count + ")");
        }
        return missing.isEmpty() ? null : "missing " + String.join(", ", missing);
    }

    private boolean essential(ItemStack s) {
        return WATER.test(s) || LAVA.test(s) || EMPTY.test(s) || FLINT.test(s) || PICKAXE.test(s) || throwaway(s);
    }

    /**
     * Gets something into the hand. False while that is still happening: a slot change takes a moment, and an
     * item that is not on the hotbar is swapped onto it first.
     */
    private boolean hold(Predicate<ItemStack> what, String name) {
        NonNullList<ItemStack> inv = ctx.player().inventory.mainInventory;
        if (what.test(inv.get(ctx.player().inventory.currentItem))) {
            return true;
        }
        if (cooldown > 0) {
            return false;
        }
        for (int i = 0; i < 9; i++) {
            if (what.test(inv.get(i))) {
                select(i);
                return false;
            }
        }
        for (int i = 9; i < 36; i++) {
            if (what.test(inv.get(i))) {
                int to = 8;
                for (int h = 8; h >= 0; h--) {
                    if (inv.get(h).isEmpty()) {
                        to = h;
                        break;
                    }
                    if (!essential(inv.get(h))) {
                        to = h;
                    }
                }
                ctx.playerController().windowClick(ctx.player().container.windowId, i, to, ClickType.SWAP, ctx.player());
                cooldown = 4;
                return false;
            }
        }
        lacking = name;
        return false;
    }

    private void select(int slot) {
        ctx.player().inventory.currentItem = slot;
        cooldown = 1 + rand.nextInt(2);
        onTarget = 0;
    }

    /** A building block in the hand. For one that is about to be broken again, the one that breaks fastest. */
    private boolean holdBlock(boolean quickToBreak) {
        if (!quickToBreak) {
            return hold(this::throwaway, "blocks");
        }
        NonNullList<ItemStack> inv = ctx.player().inventory.mainInventory;
        ToolSet tools = new ToolSet(ctx.player());
        int best = -1;
        double speed = -1;
        for (int i = 0; i < 9; i++) {
            if (throwaway(inv.get(i))) {
                double v = tools.getStrVsBlock(((BlockItem) inv.get(i).getItem()).getBlock().getDefaultState());
                if (v > speed) {
                    speed = v;
                    best = i;
                }
            }
        }
        if (best < 0) {
            return hold(this::throwaway, "blocks");
        }
        if (ctx.player().inventory.currentItem == best) {
            return true;
        }
        if (cooldown == 0) {
            select(best);
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // the world, in site coordinates
    // ------------------------------------------------------------------------------------------------------------

    private BlockPos pos(Cell c) {
        return new BlockPos(site.worldX(c), site.worldY(c), site.worldZ(c));
    }

    private Direction dir(Face f) {
        int[] step = site.worldStep(f);
        for (Direction d : Direction.values()) {
            if (d.getXOffset() == step[0] && d.getYOffset() == step[1] && d.getZOffset() == step[2]) {
                return d;
            }
        }
        throw new IllegalStateException(f.toString());
    }

    private BlockState state(Cell c) {
        return ctx.world().getBlockState(pos(c));
    }

    private Kind kind(Cell c) {
        return kind(site.worldX(c), site.worldY(c), site.worldZ(c));
    }

    private boolean ground(Cell c) {
        Kind k = kind(c);
        return k == Kind.SOLID || k == Kind.OBSIDIAN;
    }

    private boolean water(Cell c) {
        return kind(c) == Kind.WATER;
    }

    private boolean waterSource(Cell c) {
        return water(c) && ctx.world().getFluidState(pos(c)).isSource();
    }

    private String where(Cell c) {
        BlockPos p = pos(c);
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private String name(Cell c) {
        return Registry.BLOCK.getKey(state(c).getBlock()).getPath().replace('_', ' ');
    }

    private void note(String what) {
        recovered.add(what);
        logDebug("Portal: " + what);
    }

    /** What a block is, as far as the build cares. */
    private Kind kind(int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        if (!ctx.world().isBlockLoaded(p)) {
            return Kind.OTHER;
        }
        BlockState s = ctx.world().getBlockState(p);
        Block b = s.getBlock();
        FluidState fluid = s.getFluidState();
        if (b == Blocks.LAVA) {
            return fluid.isSource() ? Kind.LAVA_SOURCE : Kind.LAVA_FLOW;
        }
        if (b == Blocks.WATER) {
            return Kind.WATER;
        }
        if (!fluid.isEmpty()) {
            return Kind.OTHER;
        }
        if (s.isAir()) {
            return Kind.AIR;
        }
        if (b == Blocks.OBSIDIAN) {
            return Kind.OBSIDIAN;
        }
        float hardness = s.getBlockHardness(ctx.world(), p);
        if (hardness < 0 || b instanceof ITileEntityProvider) {
            return Kind.OTHER;
        }
        if (s.getCollisionShape(ctx.world(), p).isEmpty()) {
            return hardness <= 0.2f ? Kind.PLANT : Kind.OTHER;
        }
        return MovementHelper.isBlockNormalCube(s) ? Kind.SOLID : Kind.OTHER;
    }
}
