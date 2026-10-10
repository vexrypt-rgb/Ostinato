package baritone.pathing.kinematic;

import baritone.utils.ExperimentalMovement;
import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.utils.BlockStateInterface;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Drives plain walking stretches of a Baritone path with physics look-ahead instead of the per-movement
 * state machines: each tick it simulates a handful of yaw/jump choices a few ticks ahead with
 * {@link PlayerSim} and presses the keys of the one that gets furthest along the path while staying on it.
 * Anything it can't model (breaking, placing, water, big drops, parkour...) is left to Baritone.
 */
public final class KinematicController {

    private static final int MAX_LOOKAHEAD_MOVES = 12;
    private static final int HORIZON = 12;
    private static final float[] YAW_OFFSETS = {0, -8, 8, -20, 20, -40, 40};
    private static final float[] NO_OFFSET = {0};
    // a plan waits this many ground ticks, then jumps at every landing; long gaps need the hop timed onto the edge
    private static final int NEVER = -1;
    private static final int[] DELAYS = {NEVER, 0, 1, 2, 3, 4, 5, 6, 8, 10};
    private static final int[] JUMP_OR_NOT = {NEVER, 0};
    // plans are scored at HORIZON but simulated this far so a hop chain that ends in a gap is rejected
    private static final int LOOKAHEAD = 36;
    private static final double BUMP = 0.6; // ~2 sprint ticks: grazing a wall also drops sprint, so clean lines should win
    /** Hand back to Baritone this far before the end of the drivable stretch. */
    private static final double HANDBACK = 1.2;
    /** Rollout bound: falls, hazards and climbing off the path are checked separately, so plans may cut corners wider. */
    private static final double WIDE = 1.1;

    private final IPlayerContext ctx;
    private final ClientWorld world;
    private final PlayerSim real;
    private final PlayerSim sim;
    private final List<double[]> line = new ArrayList<>(); // x, y, z, arc length
    private int lastMove;
    /** No-progress watchdog: when the sim predicts progress the real world blocks, back off to Baritone. */
    private double lastX, lastZ;
    private int stuckTicks, cooldown;
    /** Ticks left walking straight back onto the path line after the hitbox caught a corner beside it. */
    private int recenter, recenters;
    private boolean longJump; // the driven stretch holds a 3 or 4 block gap
    /** Ticks the controller has driven the player, so callers can verify the backend is in use. */
    public static volatile long drivenTicks;

    public KinematicController(IPlayerContext ctx) {
        this.ctx = ctx;
        this.world = new ClientWorld(ctx);
        this.real = new PlayerSim(world);
        this.sim = new PlayerSim(world);
    }

    /**
     * @return the path position to continue from if the controller drove this tick, or -1 to let Baritone run the movement
     */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        int r = drive(baritone, path, pathPosition);
        if (r >= 0) {
            drivenTicks++;
        }
        return r;
    }

    private int drive(Baritone baritone, IPath path, int pathPosition) {
        if (!ExperimentalMovement.kinematicTravel() || ctx.player().isInWater() || ctx.player().isInLava()
                || ctx.player().onClimbable() || ctx.player().isFallFlying() || ctx.player().isPassenger()) {
            return -1;
        }
        if (cooldown > 0) {
            cooldown--;
            return -1;
        }
        world.reset();
        if (!buildLine(path, pathPosition)) {
            return -1;
        }
        ClientWorld.readPlayer(ctx, real);

        double[] here = project(real.x, real.z);
        double end = line.get(line.size() - 1)[3];
        // at the end of the whole path drive onto the goal block instead of handing back early
        double handback = lastMove == path.movements().size() - 1 ? 0.3 : HANDBACK;
        if (here[1] > WIDE + 0.2 || end - here[0] < handback) {
            return -1;
        }
        double moved = (real.x - lastX) * (real.x - lastX) + (real.z - lastZ) * (real.z - lastZ);
        lastX = real.x;
        lastZ = real.z;
        stuckTicks = moved < 0.0025 ? stuckTicks + 1 : 0;
        if (stuckTicks > 20) {
            stuckTicks = 0;
            // usually the box drifted off the line and snags a block in the next column; Baritone
            // can't free that either, so first step back onto the line, then give up to Baritone
            if (recenters++ < 2) {
                recenter = 8;
            } else {
                recenters = 0;
                cooldown = 60;
                Baritone.settings().movementFault.value.accept("M01", "kinematic stuck at " + ctx.playerFeet() + ", handing back to Baritone");
                return -1;
            }
        }
        int newPos = syncPosition(path, pathPosition);
        if (newPos > pathPosition) {
            recenters = 0;
        }
        if (recenter > 0) {
            recenter--;
            // the second time, once back on the line, walk along it: the rollout keeps steering into the same snag
            boolean along = recenters > 1 && here[1] < 0.1;
            double[] c = pointAt(here[0] + (along ? 0.6 : 0));
            float yaw = (float) Math.toDegrees(Math.atan2(-(c[0] - real.x), c[2] - real.z));
            baritone.getLookBehavior().updateTarget(new Rotation(yaw, 0), false);
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, along || here[1] > 0.05);
            return newPos;
        }

        float best = Float.NaN;
        boolean bestShort = false;
        int bestDelay = NEVER;
        double bestScore = -1e9;
        for (int delay : longJump ? DELAYS : JUMP_OR_NOT) {
            for (float off : delay <= 0 ? YAW_OFFSETS : NO_OFFSET) {
                double score = rollout(off, delay, false, here[0]);
                if (score > bestScore + 1e-6) {
                    bestScore = score;
                    best = off;
                    bestDelay = delay;
                    bestShort = false;
                }
            }
            if (delay <= 0) {
                // steering at the line right beside the player first: gets the box off a corner it snagged on
                double score = rollout(0, delay, true, here[0]);
                if (score > bestScore + 1e-6) {
                    bestScore = score;
                    best = 0;
                    bestDelay = delay;
                    bestShort = true;
                }
            }
        }
        boolean bestJump = bestDelay == 0 && real.onGround;
        if (bestScore <= here[0] + 0.05) {
            return -1; // nothing makes progress safely; Baritone knows how to recover
        }
        float yaw = aim(real.x, real.z, here[0], bestShort) + best;
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, 0), false);
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, bestJump);
        return newPos;
    }

    /**
     * Score = arc progress at the end of the horizon; -inf if the player leaves the corridor or drops below the path
     * before {@link #LOOKAHEAD}. A plan with a jump delay walks that many ground ticks, then jumps at every landing.
     */
    private double rollout(float yawOffset, int delay, boolean shortAim, double s0) {
        sim.copyFrom(real);
        double s = s0, score = Double.NaN;
        int ground = 0, bumps = 0;
        boolean wasBumping = real.collidedH;
        double end = line.get(line.size() - 1)[3];
        int lookahead = longJump ? LOOKAHEAD : HORIZON;
        for (int t = 0; t < lookahead; t++) {
            float off = t < 4 ? yawOffset : 0;
            // off long gaps a jump plan jumps once, now; toward one it keeps hopping to carry the speed over
            boolean jump = delay != NEVER && sim.onGround && (longJump ? ground++ >= delay : t == 0);
            sim.tick(aim(sim.x, sim.z, s, shortAim && t < 4) + off, true, true, jump);
            if (sim.collidedH && !wasBumping && t < HORIZON) {
                bumps++; // grazing a wall or trunk cancels sprint (and the sprint-jump boost) in vanilla
            }
            wasBumping = sim.collidedH;
            double[] pr = project(sim.x, sim.z);
            if (pr[1] > WIDE || hazard(sim.x, sim.y, sim.z) || sim.y < floorAt(pr[0]) - 0.4 || climbedOff(pr[0])) {
                return -1e9;
            }
            s = Math.max(s, pr[0]);
            if (Double.isNaN(score) && s >= end - 0.3) {
                return s + (HORIZON - t) * 0.3 - BUMP * bumps; // reached the end early
            }
            if (t == HORIZON - 1) {
                // keep a little credit for speed along the path so it prefers carrying momentum
                score = s + 0.5 * Math.sqrt(sim.vx * sim.vx + sim.vz * sim.vz) - BUMP * bumps;
            }
            if (t >= HORIZON - 1 && sim.onGround && (delay == NEVER || s >= end - 0.3)) {
                return score; // on the path with no jump pending: nothing later in this plan can fall in
            }
        }
        // still airborne: make sure the last jump lands on the path rather than in a gap
        for (int t = 0; t < 14 && !sim.onGround; t++) {
            sim.tick(aim(sim.x, sim.z, s, false), true, true, false);
            double[] pr = project(sim.x, sim.z);
            if (pr[1] > WIDE || sim.y < floorAt(pr[0]) - 0.4 || hazard(sim.x, sim.y, sim.z) || climbedOff(pr[0])) {
                return -1e9;
            }
            s = Math.max(s, pr[0]);
        }
        return sim.onGround ? score : -1e9;
    }

    // Lava, fire, magma or cactus under or inside the player box. TenorClef s320t: a rollout inside the 0.55
    // corridor carried the player into lava beside the path while building a bucket portal.
    private boolean hazard(double x, double y, double z) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (double dx = -0.3; dx <= 0.31; dx += 0.6) {
            for (double dz = -0.3; dz <= 0.31; dz += 0.6) {
                int bx = PlayerSim.floor(x + dx), bz = PlayerSim.floor(z + dz), by = PlayerSim.floor(y);
                for (int yy = by - 1; yy <= by + 1; yy++) {
                    Block b = ctx.world().getBlockState(p.set(bx, yy, bz)).getBlock();
                    if (b == Blocks.LAVA || b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CACTUS
                            || (yy == by - 1 && b == Blocks.MAGMA_BLOCK)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private float aim(double x, double z, double s, boolean shortAim) {
        double[] tgt = pointAt(s + (shortAim ? 0.4 : 1.6));
        return (float) Math.toDegrees(Math.atan2(-(tgt[0] - x), tgt[2] - z));
    }

    private boolean buildLine(IPath path, int pathPosition) {
        line.clear();
        List<IMovement> moves = path.movements();
        if (pathPosition >= moves.size()) {
            return false;
        }
        BlockStateInterface bsi = null;
        BetterBlockPos src = moves.get(pathPosition).getSrc();
        add(src);
        int i = pathPosition;
        for (; i < moves.size() && i < pathPosition + MAX_LOOKAHEAD_MOVES; i++) {
            IMovement mv = moves.get(i);
            if (!drivable(mv)) {
                break;
            }
            Movement movement = (Movement) mv;
            if (movement.toBreakCached == null || movement.toPlaceCached == null) {
                if (bsi == null) {
                    bsi = new BlockStateInterface(ctx);
                }
                movement.toBreak(bsi);
                movement.toPlace(bsi);
            }
            if (!movement.toBreakCached.isEmpty() || !movement.toPlaceCached.isEmpty()) {
                break;
            }
            add(mv.getDest());
        }
        lastMove = i - 1;
        longJump = false;
        for (int k = pathPosition; k < i; k++) {
            IMovement mv = moves.get(k);
            longJump |= mv instanceof MovementParkour && mv.getSrc().distSqr(mv.getDest()) >= 16;
        }
        return line.size() >= 3;
    }

    private static boolean drivable(IMovement mv) {
        if (mv instanceof MovementTraverse || mv instanceof MovementDiagonal || mv instanceof MovementAscend) {
            return mv.getDest().y - mv.getSrc().y <= 1;
        }
        if (mv instanceof MovementParkour) {
            // flat 2+ block gaps; a sprint jump overshoots a 1 block gap, Baritone walks that one
            int d = Math.abs(mv.getDest().x - mv.getSrc().x) + Math.abs(mv.getDest().z - mv.getSrc().z);
            return mv.getDest().y == mv.getSrc().y && d >= 3;
        }
        int drop = mv.getSrc().y - mv.getDest().y;
        return (mv instanceof MovementDescend || mv instanceof MovementFall) && drop >= 1 && drop <= 3;
    }

    private void add(BetterBlockPos b) {
        double x = b.x + 0.5, z = b.z + 0.5;
        double s = 0;
        if (!line.isEmpty()) {
            double[] prev = line.get(line.size() - 1);
            s = prev[3] + Math.sqrt((x - prev[0]) * (x - prev[0]) + (z - prev[2]) * (z - prev[2]));
        }
        line.add(new double[]{x, b.y, z, s});
    }

    /** Nearest point on the polyline: {arc length, horizontal distance}. */
    private double[] project(double x, double z) {
        double bestS = 0, bestD = Double.MAX_VALUE;
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i), b = line.get(i + 1);
            double dx = b[0] - a[0], dz = b[2] - a[2];
            double len2 = dx * dx + dz * dz;
            double t = len2 == 0 ? 0 : ((x - a[0]) * dx + (z - a[2]) * dz) / len2;
            t = Math.max(0, Math.min(1, t));
            double px = a[0] + dx * t, pz = a[2] + dz * t;
            double d = (x - px) * (x - px) + (z - pz) * (z - pz);
            if (d < bestD) {
                bestD = d;
                bestS = a[3] + (b[3] - a[3]) * t;
            }
        }
        return new double[]{bestS, Math.sqrt(bestD)};
    }

    private double[] pointAt(double s) {
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i), b = line.get(i + 1);
            if (s <= b[3] || i + 2 == line.size()) {
                double len = b[3] - a[3];
                double t = len == 0 ? 1 : Math.max(0, Math.min(1, (s - a[3]) / len));
                return new double[]{a[0] + (b[0] - a[0]) * t, a[1], a[2] + (b[2] - a[2]) * t};
            }
        }
        return line.get(line.size() - 1);
    }

    /** Standing above the path: the box stepped or jumped onto terrain the path goes around, and Baritone loses it. */
    private boolean climbedOff(double s) {
        if (!sim.onGround) {
            return false;
        }
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i), b = line.get(i + 1);
            if (s <= b[3] || i + 2 == line.size()) {
                return sim.y > Math.max(a[1], b[1]) + 0.6;
            }
        }
        return false;
    }

    /** Lowest floor the player may be at around arc length s (an ascend/descend switches floors mid-segment). */
    private double floorAt(double s) {
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i), b = line.get(i + 1);
            if (s <= b[3] || i + 2 == line.size()) {
                return Math.min(a[1], b[1]);
            }
        }
        return line.get(line.size() - 1)[1];
    }

    /** Advance past moves whose destination the player already stands in (on the ground at its floor, or near it mid-jump;
     * standing a floor below an ascend's destination must not skip the ascend). */
    private int syncPosition(IPath path, int pathPosition) {
        int fx = PlayerSim.floor(real.x), fz = PlayerSim.floor(real.z);
        int fy = PlayerSim.floor(real.y + 1e-3);
        for (int i = lastMove; i >= pathPosition; i--) {
            BetterBlockPos d = path.movements().get(i).getDest();
            if (d.x == fx && d.z == fz && (real.onGround ? fy == d.y : fy >= d.y - 1 && fy <= d.y + 1)) {
                return i + 1;
            }
        }
        return pathPosition;
    }
}
