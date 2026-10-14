package baritone.pathing.kinematic;

import baritone.utils.ExperimentalMovement;
import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.behavior.LookBehavior;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.InputOverrideHandler;
import baritone.pathing.movement.movements.MovementAscend;
import baritone.pathing.movement.movements.MovementDescend;
import baritone.pathing.movement.movements.MovementDiagonal;
import baritone.pathing.movement.movements.MovementFall;
import baritone.pathing.movement.movements.MovementParkour;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.api.utils.Helper;
import baritone.pathing.viz.PathViz;
import baritone.utils.BlockStateInterface;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

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
    /** Before a bridging or breaking movement: hand back earlier and stop sprinting so the momentum is gone at the edge. */
    private static final double PLACE_HANDBACK = 2.2;
    private static final double PLACE_BRAKE = 4.5;
    /** Rollout bound: falls, hazards and climbing off the path are checked separately, so plans may cut corners wider. */
    private static final double WIDE = 1.1;
    /**
     * A path that winds down passes under itself in plan view, so a point belongs to a stretch of the line only at that
     * stretch's level: from a little under its lower end to a jump above its higher end.
     */
    static final double LEVEL_BELOW = 0.6, LEVEL_ABOVE = 2.3;
    /** How far past where a rollout has got to its next tick may be placed on the line; a tick moves well under a block. */
    static final double REACH_AHEAD = 2.0;
    /**
     * Further than this in one flight hurts. Every step down a path is short enough to take, but a sprint carries past
     * the landings of a staircase of them and they add up: 1 + 2 + 3 was one fall of six in bastion run 123.
     */
    static final double SAFE_FALL = 3.01;
    /** The height the rollout's flight started from, to tell how far it has fallen when it lands. */
    private double simTop;

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
    /** Head steering: the camera is a simulated hand, so rollouts model where it will really point. */
    /** Air strafe the rollouts apply for their first ticks while airborne: +1 A, -1 D. */
    private int rolloutStrafe;
    /**
     * Ostinato analog: when not NaN, the rollouts hold this forward/strafe (stick values) for their first airborne
     * ticks instead of forward + rolloutStrafe. Only ever set when the analogMovement setting allows it.
     */
    private float rolloutAnalogF = Float.NaN, rolloutAnalogS;
    /** Ostinato analog: candidate air sticks tried after the A/D keys, {forward, strafe}. */
    private static final float[][] AIR_STICKS = {{1, 0.25f}, {1, -0.25f}, {1, 0.5f}, {1, -0.5f}, {0.5f, 0}, {0.5f, 0.25f}, {0.5f, -0.25f}, {0, 0}};
    private boolean head;
    private float camYaw, camVel0;
    private double wander;
    private final Random rng = new Random();
    private int lastDrivenTick = -10;
    private boolean longJump; // the driven stretch holds a 3 or 4 block gap
    private boolean endsAtPlace; // the stretch stops at a movement that breaks or places, which needs the player slow at the edge
    /** Ticks the controller has driven the player, so callers can verify the backend is in use. */
    public static volatile long drivenTicks;

    /** Path visualization: whether rollouts now are the fan of this tick's choice (not margin or strafe checks). */
    private boolean vizRec;
    /** The slot the running rollout records into, null when not recording. */
    private PathViz.Rollout vizR;

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
        narrowEnd = false;
        int r = drive(baritone, path, pathPosition);
        if (r >= 0) {
            drivenTicks++;
        }
        if (narrowEnd && Baritone.settings().kinematicTrace.value) {
            // Ostinato: per-tick trace onto a narrow landing: who drives, where the player is, how it moves
            net.minecraft.world.phys.Vec3 v = ctx.player().getDeltaMovement();
            System.out.printf("KTRACE narrow r=%d pos=%.3f,%.3f,%.3f v=%.3f,%.3f,%.3f og=%b%n", r,
                    ctx.player().getX(), ctx.player().getY(), ctx.player().getZ(), v.x, v.y, v.z, ctx.player().onGround());
        }
        return r;
    }

    private int drive(Baritone baritone, IPath path, int pathPosition) {
        if (!ExperimentalMovement.kinematicTravel() || ctx.player().isInWater() || ctx.player().isInLava()
                || (ctx.player().onClimbable() && !(pathPosition < path.movements().size() && path.movements().get(pathPosition) instanceof MovementTraverse))
                || ctx.player().isFallFlying() || ctx.player().isPassenger() || !ClientWorld.plainGravity(ctx)) {
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

        double[] here = project(line, real.x, real.y, real.z, Double.MAX_VALUE);
        double end = line.get(line.size() - 1)[3];
        double handback = endsAtPlace ? PLACE_HANDBACK : HANDBACK;
        if (here[1] > WIDE + 0.2) {
            return -1;
        }
        PathViz viz = PathViz.get();
        viz.corridor(line, here[0], WIDE);
        boolean arriving = end - here[0] < handback;
        narrowEnd = narrowAt(line.get(line.size() - 1));
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
                viz.stuck(real.x, real.y, real.z, "M01 kinematic stuck");
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
        if (narrowFlight) {
            // Ostinato: after a narrowTakeoff fly the arrival steering (airArrive) all the way down, not the rollouts
            if (!real.onGround) {
                return arrive(baritone, newPos, line.get(line.size() - 1));
            }
            if (ctx.player().tickCount - narrowJumpTick > 2) {
                narrowFlight = false;
            }
        }
        if (arriving) {
            return arrive(baritone, newPos, line.get(line.size() - 1));
        }
        head = Baritone.settings().headSteering.value;
        camYaw = ctx.player().getYRot();
        camVel0 = baritone.getLookBehavior().yawVelocity();
        // a slow drift of the aim point off the line, as a hand wanders; none where precision matters
        double maxWander = longJump || endsAtPlace ? 0 : Baritone.settings().pathWander.value;
        wander = Math.max(-maxWander, Math.min(maxWander, wander * 0.97 + rng.nextGaussian() * 0.15 * maxWander));

        float best = Float.NaN;
        boolean bestShort = false;
        int bestDelay = NEVER;
        double bestScore = -1e9;
        float walkOff = 0;
        boolean walkShort = false;
        double walkScore = -1e9;
        vizRec = viz.beginRollouts();
        int bestR = -1, walkR = -1;
        for (int delay : longJump ? DELAYS : JUMP_OR_NOT) {
            for (float off : delay <= 0 ? YAW_OFFSETS : NO_OFFSET) {
                double score = rollout(off, delay, false, here[0]);
                PathViz.Rollout r = vizR;
                int ri = viz.rolloutCount - 1;
                if (score > bestScore + 1e-6 && !holdsWithMargin(off, delay, here[0])) {
                    score = -1e9; // the real jump leaves a little later or shorter than the sim's: a barely-landing plan is out
                    if (r != null) {
                        r.reason = "barely lands";
                        r.score = score;
                    }
                }
                if (score > bestScore + 1e-6) {
                    bestScore = score;
                    best = off;
                    bestDelay = delay;
                    bestShort = false;
                    bestR = r != null ? ri : -1;
                }
                if (delay == NEVER && score > walkScore + 1e-6) {
                    walkScore = score;
                    walkOff = off;
                    walkShort = false;
                    walkR = r != null ? ri : -1;
                }
            }
            if (delay <= 0) {
                // steering at the line right beside the player first: gets the box off a corner it snagged on
                double score = rollout(0, delay, true, here[0]);
                int ri = vizR != null ? viz.rolloutCount - 1 : -1;
                if (delay == NEVER && score > walkScore + 1e-6) {
                    walkScore = score;
                    walkOff = 0;
                    walkShort = true;
                    walkR = ri;
                }
                if (score > bestScore + 1e-6) {
                    bestScore = score;
                    best = 0;
                    bestDelay = delay;
                    bestShort = true;
                    bestR = ri;
                }
            }
        }
        if (!longJump && bestDelay == 0 && !jumpWorthIt(bestScore, walkScore, here[0])) {
            // a hop that is barely ahead of running: stay on the ground
            bestScore = walkScore;
            best = walkOff;
            bestShort = walkShort;
            bestDelay = NEVER;
            bestR = walkR;
        }
        if (vizRec) {
            vizRec = false;
            viz.chooseRollout(bestScore <= here[0] + 0.05 ? -1 : bestR);
            if (Baritone.settings().chatDebug.value && ctx.player().tickCount % 20 == 0 && !viz.rolloutSummary.isEmpty()) {
                Helper.HELPER.logDebug("kinematic rejects: " + viz.rolloutSummary);
            }
        }
        boolean bestJump = bestDelay == 0 && real.onGround;
        int strafe = 0;
        float[] stick = null; // Ostinato analog
        if (!real.onGround && bestScore > here[0] + 0.05) {
            // micro adjustment in the air, as a player taps A or D: the camera is slow, a strafe acts next tick
            double base = bestScore;
            for (int st = -1; st <= 1; st += 2) {
                rolloutStrafe = st;
                double sc = rollout(best, bestDelay, bestShort, here[0]);
                if (sc > base + 0.08 && holdsWithMargin(best, bestDelay, here[0])) {
                    base = sc;
                    strafe = st;
                }
            }
            rolloutStrafe = 0;
            if (baritone.getInputOverrideHandler().analogAllowed()) {
                // Ostinato analog: a finer stick on top of the keys' choice; it has to beat the keys clearly
                for (float[] st : AIR_STICKS) {
                    rolloutAnalogF = st[0];
                    rolloutAnalogS = st[1];
                    double sc = rollout(best, bestDelay, bestShort, here[0]);
                    if (sc > base + 0.03 && holdsWithMargin(best, bestDelay, here[0])) {
                        base = sc;
                        stick = st;
                    }
                }
                rolloutAnalogF = Float.NaN;
            }
        }
        boolean committed = !real.onGround && ctx.player().tickCount - lastDrivenTick <= 1;
        if (bestScore <= here[0] + 0.05) {
            if (!committed) {
                if (narrowEnd && real.onGround) {
                    // Ostinato: onto a candle/lantern the rollouts (sprinting) overshoot; own the takeoff instead
                    int nt = narrowTakeoff(baritone, newPos, line.get(line.size() - 1));
                    if (nt >= 0) {
                        return nt;
                    }
                }
                return -1; // nothing makes progress safely; Baritone knows how to recover
            }
            best = 0; // mid-air with no plan left: keep steering at the line rather than hand over in flight
            bestShort = false;
        }
        lastDrivenTick = ctx.player().tickCount;
        float yaw = aim(real.x, real.z, here[0], bestShort) + best;
        if (head) {
            baritone.getLookBehavior().human();
            baritone.getLookBehavior().updateTarget(new Rotation(yaw, 6f + (float) (wander * 8)), true);
        } else {
            baritone.getLookBehavior().updateTarget(new Rotation(yaw, 0), false);
        }
        baritone.getInputOverrideHandler().clearAllKeys();
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, true);
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, !(endsAtPlace && end - here[0] < PLACE_BRAKE));
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, bestJump);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, strafe > 0);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, strafe < 0);
        if (stick != null && bestScore > here[0] + 0.05) {
            baritone.getInputOverrideHandler().setAnalogMove(stick[0], stick[1]); // Ostinato analog
        }
        return newPos;
    }

    // Ostinato: the stretch ends on a block narrower than a full one (real collision shape), see arrive
    private boolean narrowEnd;

    private boolean narrowAt(double[] to) {
        BlockPos feet = BlockPos.containing(to[0], to[1] + 0.01, to[2]);
        BlockState st = ctx.world().getBlockState(feet);
        if (MovementHelper.collisionTop(st) <= 0) {
            st = ctx.world().getBlockState(feet.below());
        }
        return MovementHelper.collisionWidth(st) < 0.9;
    }

    /**
     * Ostinato: takeoff onto a narrow landing (candle, lantern, pot). Tries, in the sim, walking (then sprinting) for
     * 0..12 ticks toward its middle and jumping, then the same mid-air steering {@link #airArrive} flies; a plan counts
     * only if the feet land on the real support top overlapping its collision shape by at least {@link #NARROW_OVERLAP}.
     * Acts on the first tick of the first plan found (fewest ticks, walking before sprinting), searched again each tick.
     */
    private int narrowTakeoff(Baritone baritone, int newPos, double[] to) {
        double top = to[1] - to[4], cx = to[0], cz = to[2];
        BlockPos feet = BlockPos.containing(cx, to[1] + 0.01, cz);
        BlockState st = ctx.world().getBlockState(feet);
        if (MovementHelper.collisionTop(st) <= 0) {
            st = ctx.world().getBlockState(feet.below());
        }
        double reach = 0.3 + MovementHelper.collisionWidth(st) / 2 - NARROW_OVERLAP;
        // Ostinato analog: keys first (walk/sprint, key air steering); only then, when allowed, plans with stick air
        // steering, then a partial stick on the ground (a slower walk that leaves from exactly the right spot)
        boolean analog = baritone.getInputOverrideHandler().analogAllowed();
        float[] groundMags = analog ? new float[]{1, 0.7f, 0.45f} : new float[]{1};
        for (int pass = 0; pass < (analog ? 2 + groundMags.length - 1 : 1); pass++) {
          boolean stickAir = pass > 0;
          float mag = pass <= 1 ? 1 : groundMags[pass - 1];
          for (int sp = 0; sp <= 1; sp++) {
            for (int delay = 0; delay <= 12; delay++) {
                if (narrowLands(sp == 1, delay, top, cx, cz, reach, mag, stickAir)) {
                    float yaw = (float) Math.toDegrees(Math.atan2(-(cx - real.x), cz - real.z));
                    baritone.getLookBehavior().updateTarget(new Rotation(yaw, 4f), true);
                    InputOverrideHandler in = baritone.getInputOverrideHandler();
                    in.clearAllKeys();
                    in.setInputForceState(Input.MOVE_FORWARD, true);
                    in.setInputForceState(Input.SPRINT, sp == 1);
                    in.setInputForceState(Input.JUMP, delay == 0);
                    if (mag < 1) {
                        in.setAnalogMove(mag, 0);
                    }
                    narrowStickAir = stickAir;
                    if (delay == 0) {
                        narrowFlight = true;
                        narrowJumpTick = ctx.player().tickCount;
                    }
                    lastDrivenTick = ctx.player().tickCount;
                    if (Baritone.settings().kinematicTrace.value) {
                        System.out.printf("KTRACE narrowTakeoff sprint=%b delay=%d reach=%.3f stickAir=%b mag=%.2f%n", sp == 1, delay, reach, stickAir, mag);
                    }
                    return newPos;
                }
            }
          }
        }
        return -1;
    }

    static final double NARROW_OVERLAP = 0.05;
    private boolean narrowFlight;
    /** Ostinato analog: the takeoff that is flying was planned with stick air steering ({@link #stickArrive}). */
    private boolean narrowStickAir;
    private int narrowJumpTick;

    private boolean narrowLands(boolean sprint, int delay, double top, double cx, double cz, double reach) {
        return narrowLands(sprint, delay, top, cx, cz, reach, 1, false);
    }

    /** Ostinato analog: {@code mag} < 1 walks on a partial stick; {@code stickAir} flies {@link #stickArrive}. */
    private boolean narrowLands(boolean sprint, int delay, double top, double cx, double cz, double reach, float mag, boolean stickAir) {
        sim.copyFrom(real);
        boolean air = false;
        for (int t = 0; t < delay + 40; t++) {
            float yaw = (float) Math.toDegrees(Math.atan2(-(cx - sim.x), cz - sim.z));
            if (t <= delay) {
                if (!sim.onGround) return false; // walked off the edge before the jump
                if (mag < 1) {
                    sim.tickAnalog(yaw, mag, 0, sprint, t == delay);
                } else {
                    sim.tick(yaw, 1, sprint, t == delay);
                }
                continue;
            }
            if (!sim.onGround) air = true;
            if (air && sim.onGround) {
                return Math.abs(sim.y - top) < 0.02 && Math.abs(sim.x - cx) <= reach && Math.abs(sim.z - cz) <= reach;
            }
            if (sim.y < top - 1.0) return false;
            double d = Math.hypot(cx - sim.x, cz - sim.z);
            if (stickAir) {
                sim.tickAnalog(yaw, stickArrive(d, Math.hypot(sim.vx, sim.vz)), 0, false, false);
                continue;
            }
            int a = airArrive(d, Math.hypot(sim.vx, sim.vz));
            sim.tick(yaw, a, false, false);
        }
        return false;
    }

    /** Ostinato: mid-air over a narrow landing: 1 hold forward toward its middle, -1 brake, 0 coast. */
    static boolean needsStepUp(double floor, double feetY) {
        return floor > feetY + 0.1;
    }

    /**
     * Ostinato analog: proportional air steering onto a narrow landing. Air drag leaves 0.91 a tick, so coasting
     * from speed v covers about 10v; ask for the stick that brings the speed to d / 10, a full stick being
     * 0.98 * 0.02 a tick.
     */
    static float stickArrive(double d, double speed) {
        double want = d / 10.1;
        return (float) Math.max(-1, Math.min(1, (want - speed) / (0.98 * 0.02)));
    }

    static int airArrive(double d, double speed) {
        if (d > 0.3) return speed < 0.25 ? 1 : 0;
        return speed > 0.08 ? -1 : 0;
    }

    /** Stretch end: ease onto the last point (slow, no sprint) so the next movement starts from rest. */
    private int arrive(Baritone baritone, int newPos, double[] to) {
        double dx = to[0] - real.x, dz = to[2] - real.z;
        double d = Math.hypot(dx, dz);
        double speed = Math.hypot(real.vx, real.vz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        baritone.getLookBehavior().human();
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, 4f), true);
        double want = Math.min(0.22, 0.04 + d * 0.3);
        InputOverrideHandler in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        float diff = Math.abs(Mth.wrapDegrees(yaw - ctx.player().getYRot()));
        if (narrowEnd && !real.onGround) {
            // Ostinato: in flight onto a candle, pot or rod: braking now drops short of it; steer for its middle instead
            int a = airArrive(d, speed);
            in.setInputForceState(Input.MOVE_FORWARD, a > 0 && diff < 60);
            in.setInputForceState(Input.MOVE_BACK, a < 0);
            if (narrowStickAir && narrowFlight && in.analogAllowed()) {
                float f = stickArrive(d, speed); // Ostinato analog: fly the plan the takeoff was checked with
                in.setAnalogMove(f > 0 && diff >= 60 ? 0 : f, 0);
            }
            return newPos;
        }
        in.setInputForceState(Input.MOVE_FORWARD, speed < want && diff < 60 && d > 0.12);
        in.setInputForceState(Input.MOVE_BACK, speed > want + 0.07 && d < 1.0);
        // Ostinato: the floor there is the real support top (a candle holds the feet 0.625 under the cell floor):
        // measured from the cell floor, standing on the candle read as a step still to climb, and it jumped off
        in.setInputForceState(Input.JUMP, real.onGround && needsStepUp(to[1] - to[4], real.y) && d < 1.3);
        return newPos;
    }

    /**
     * Score = arc progress at the end of the horizon; -inf if the player leaves the corridor or drops below the path
     * before {@link #LOOKAHEAD}. A plan with a jump delay walks that many ground ticks, then jumps at every landing.
     */
/** How much further along the line, over the horizon, a jump must get than running for it to be taken. */
    static final double JUMP_GAIN = 0.75;

    /**
     * Jump on open ground only when it is needed (running makes no progress: a gap, a step, a block in the way) or
     * when it is measurably faster than running, not because a sprint-jump scored a hair further.
     */
    static boolean jumpWorthIt(double jumpScore, double walkScore, double s0) {
        return walkScore <= s0 + 0.05 || jumpScore >= walkScore + JUMP_GAIN;
    }

        private double rollout(float yawOffset, int delay, boolean shortAim, double s0) {
        vizR = vizRec ? PathViz.get().startRollout(yawOffset, delay) : null;
        double r = rollout0(yawOffset, delay, shortAim, s0);
        if (vizR != null) {
            vizR.score = r;
        }
        return r;
    }

    private double reject(String why) {
        if (vizR != null) {
            vizR.reason = why;
        }
        return -1e9;
    }

    private double rollout0(float yawOffset, int delay, boolean shortAim, double s0) {
        sim.copyFrom(real);
        simTop = real.y + (real.onGround ? 0 : ctx.player().fallDistance);
        double s = s0, score = Double.NaN;
        int ground = 0, bumps = 0;
        boolean wasBumping = real.collidedH;
        double end = line.get(line.size() - 1)[3];
        int lookahead = longJump ? LOOKAHEAD : HORIZON;
        float cam = camYaw, camVel = camVel0;
        float[] vel = new float[1];
        for (int t = 0; t < lookahead; t++) {
            float off = t < 4 ? yawOffset : 0;
            // off long gaps a jump plan jumps once, now; toward one it keeps hopping to carry the speed over
            boolean jump = delay != NEVER && sim.onGround && (longJump ? ground++ >= delay : t == 0);
            float want = aim(sim.x, sim.z, s, shortAim && t < 4) + off;
            if (head) {
                cam = LookBehavior.modelYawStep(cam, camVel, want, vel);
                camVel = vel[0];
                want = cam;
            }
            if (!Float.isNaN(rolloutAnalogF) && !sim.onGround && t < 4) {
                sim.tickAnalog(want, rolloutAnalogF, rolloutAnalogS, true, jump); // Ostinato analog
            } else {
                sim.tick(want, 1, !sim.onGround && t < 4 ? rolloutStrafe : 0, true, jump);
            }
            if (sim.collidedH && !wasBumping && t < HORIZON) {
                bumps++; // grazing a wall or trunk cancels sprint (and the sprint-jump boost) in vanilla
            }
            wasBumping = sim.collidedH;
            if (vizR != null) {
                vizR.point(sim.x, sim.y, sim.z);
            }
            double[] pr = project(line, sim.x, sim.y, sim.z, s + REACH_AHEAD);
            if (vizR != null) {
                // Ostinato path viz: the same tests as below, one at a time, to name the reason
                if (hardLanding()) return reject("hard landing");
                if (pr[1] > WIDE) return reject("exits corridor");
                if (hazard(sim.x, sim.y, sim.z)) return reject("hazard");
                if (sim.y < floorAt(pr[0]) - 0.4) return reject("falls below path");
                if (climbedOff(pr[0])) return reject("climbs off path");
            } else if (hardLanding() || pr[1] > WIDE || hazard(sim.x, sim.y, sim.z) || sim.y < floorAt(pr[0]) - 0.4 || climbedOff(pr[0])) {
                return -1e9;
            }
            s = Math.max(s, pr[0]);
            if (Double.isNaN(score) && s >= end - 0.3) {
                return settles(cam, camVel, s) ? s + (HORIZON - t) * 0.3 - BUMP * bumps : reject("lands in gap"); // reached the end early
            }
            if (t == HORIZON - 1) {
                // keep a little credit for speed along the path so it prefers carrying momentum
                score = s + 0.5 * Math.sqrt(sim.vx * sim.vx + sim.vz * sim.vz) - BUMP * bumps;
            }
            if (t >= HORIZON - 1 && sim.onGround && (delay == NEVER || s >= end - 0.3)) {
                return score; // on the path with no jump pending: nothing later in this plan can fall in
            }
        }
        return settles(cam, camVel, s) ? score : reject("lands in gap");
    }

    /** Call after every rollout tick: whether the rollout just touched down from further up than is safe. */
    private boolean hardLanding() {
        if (!sim.onGround) {
            simTop = Math.max(simTop, sim.y);
            return false;
        }
        boolean hard = simTop - sim.y > SAFE_FALL;
        simTop = sim.y;
        return hard;
    }

    /** Still airborne at the end of the horizon: make sure the last jump lands on the path rather than in a gap. */
    private boolean settles(float cam, float camVel, double s) {
        float[] vel = new float[1];
        for (int t = 0; t < 14 && !sim.onGround; t++) {
            float want = aim(sim.x, sim.z, s, false);
            if (head) {
                cam = LookBehavior.modelYawStep(cam, camVel, want, vel);
                camVel = vel[0];
                want = cam;
            }
            sim.tick(want, true, true, false);
            double[] pr = project(line, sim.x, sim.y, sim.z, s + REACH_AHEAD);
            if (hardLanding() || pr[1] > WIDE || sim.y < floorAt(pr[0]) - 0.4 || hazard(sim.x, sim.y, sim.z) || climbedOff(pr[0])) {
                return false;
            }
            s = Math.max(s, pr[0]);
        }
        return sim.onGround;
    }

    /** A long jump must still land if it leaves a quarter block late: no barely-landing plans. */
    private boolean holdsWithMargin(float off, int delay, double s0) {
        double hs = Math.hypot(real.vx, real.vz);
        if (!longJump || !real.onGround || hs < 0.01) {
            return true;
        }
        double x = real.x, z = real.z;
        real.x -= real.vx / hs * 0.25;
        real.z -= real.vz / hs * 0.25;
        boolean rec = vizRec;
        vizRec = false;
        double r = rollout(off, delay, false, s0);
        vizRec = rec;
        real.x = x;
        real.z = z;
        return r > -1e8;
    }

    /**
     * Combat chase: would a sprint-jump now toward (tx, tz) land safely and close at least as much ground as running on?
     * Simulates both options for a few ticks; a jump into lava, a gap or a drop is refused.
     */
    public boolean jumpHelps(double tx, double tz) {
        if (ctx.player().isInWater() || ctx.player().isInLava() || !ClientWorld.plainGravity(ctx)) {
            return false;
        }
        world.reset();
        ClientWorld.readPlayer(ctx, real);
        real.sprinting = true;
        float yaw = (float) Math.toDegrees(Math.atan2(-(tx - real.x), tz - real.z));
        double[] closed = new double[2];
        for (int j = 0; j < 2; j++) {
            sim.copyFrom(real);
            boolean ok = true;
            for (int t = 0; t < 14; t++) {
                sim.tick(yaw, true, true, j == 1 && t == 0);
                if (sim.y < real.y - 1.5 || hazard(sim.x, sim.y, sim.z)) {
                    ok = false;
                    break;
                }
            }
            for (int t = 0; ok && t < 10 && !sim.onGround; t++) {
                sim.tick(yaw, true, true, false);
                if (sim.y < real.y - 1.5 || hazard(sim.x, sim.y, sim.z)) {
                    ok = false;
                }
            }
            if (!ok || !sim.onGround) {
                closed[j] = j == 0 ? 0 : -1e9;
                if (j == 0 && !ok) {
                    return false; // running straight on is no better; let the caller decide
                }
                continue;
            }
            closed[j] = -Math.hypot(sim.x - tx, sim.z - tz);
        }
        return closed[1] > closed[0] - 0.05;
    }

    // Lava, fire, magma or cactus under or inside the player box. TenorClef s320t: a rollout inside the 0.55
    // corridor carried the player into lava beside the path while building a bucket portal.
    private boolean hazard(double x, double y, double z) {
        return hazard(ctx, x, y, z);
    }

    static boolean hazard(IPlayerContext ctx, double x, double y, double z) {
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
        if (wander != 0 && !shortAim) {
            double[] a = pointAt(s), b = pointAt(s + 1.0);
            double dx = b[0] - a[0], dz = b[2] - a[2], len = Math.hypot(dx, dz);
            double fade = Math.min(1.0, Math.max(0.0, (line.get(line.size() - 1)[3] - s - 1.0) / 2.0)); // true to the line at its end
            if (len > 1e-6) {
                tgt = new double[]{tgt[0] - dz / len * wander * fade, tgt[1], tgt[2] + dx / len * wander * fade};
            }
        }
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
        endsAtPlace = false;
        for (; i < moves.size() && i < pathPosition + MAX_LOOKAHEAD_MOVES; i++) {
            IMovement mv = moves.get(i);
            if (!drivable(mv)) {
                // an extension is laid at the edge: arrive there slowly
                endsAtPlace |= mv instanceof MovementParkour && ((MovementParkour) mv).extensions() > 0;
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
            if (!movement.toBreakCached.isEmpty() && movement.toPlaceCached.isEmpty() && minedOut(movement)) {
                movement.resetBlockCache(); // everything it had to break is gone: it is a plain walk now
                if (bsi == null) {
                    bsi = new BlockStateInterface(ctx);
                }
                movement.toBreak(bsi);
                movement.toPlace(bsi);
            }
            if (!movement.toBreakCached.isEmpty() || !movement.toPlaceCached.isEmpty()) {
                endsAtPlace = true;
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
        return line.size() >= 2;
    }

    private boolean minedOut(Movement movement) {
        for (net.minecraft.core.BlockPos b : movement.toBreakCached) {
            if (!MovementHelper.canWalkThrough(ctx, new BetterBlockPos(b))) {
                return false;
            }
        }
        return true;
    }

    private static boolean drivable(IMovement mv) {
        if (mv instanceof MovementTraverse || mv instanceof MovementDiagonal || mv instanceof MovementAscend) {
            return mv.getDest().y - mv.getSrc().y <= 1;
        }
        if (mv instanceof MovementParkour) {
            if (((MovementParkour) mv).extensions() > 0) {
                return false; // the extension is laid by ExtensionController / the movement itself
            }
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
        line.add(new double[]{x, b.y, z, s, supportDrop(ctx.world().getBlockState(b), ctx.world().getBlockState(b.below()))});
    }

    /**
     * Ostinato: how far below the cell floor the feet really rest. A candle, pot or lantern under the node holds the
     * player at its own collision top, up to 0.75 lower than a full block; the rollouts took touching down there for a
     * fall and handed every such landing back to Baritone.
     */
    static double supportDrop(BlockState feet, BlockState below) {
        if (MovementHelper.collisionTop(feet) > 0) return 0;
        return dropFor(MovementHelper.collisionTop(below));
    }

    static double dropFor(double top) {
        return top > 0 && top < 1 ? 1 - top : 0;
    }

    /** Nearest point on the polyline: {arc length, horizontal distance}. */
    /**
     * Only stretches at the point's own level count, and none that start past {@code sMax}. In plan view alone, a spot in
     * the air beside a winding way down lies "on" the stretch that passes underneath, further along and lower: running
     * straight off the edge then scored as progress, and the driver kept steering there (bastion runs 119-121, ten blocks
     * wide of the path and thirteen above it). With no stretch at its level the point is off the line: distance MAX_VALUE.
     */
    static double[] project(List<double[]> line, double x, double y, double z, double sMax) {
        double bestS = 0, bestD = Double.MAX_VALUE;
        for (int i = 0; i + 1 < line.size(); i++) {
            double[] a = line.get(i), b = line.get(i + 1);
            if (a[3] > sMax) {
                break;
            }
            if (y < Math.min(a[1], b[1]) - LEVEL_BELOW || y > Math.max(a[1], b[1]) + LEVEL_ABOVE) {
                continue;
            }
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
        return new double[]{bestS, bestD == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(bestD)};
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
                return Math.min(a[1] - a[4], b[1] - b[4]);
            }
        }
        double[] last = line.get(line.size() - 1);
        return last[1] - last[4];
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
