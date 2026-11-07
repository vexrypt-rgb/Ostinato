package baritone.pathing.kinematic;

import baritone.utils.ExperimentalMovement;
import baritone.Baritone;
import baritone.api.gracenote.IGraceNote;
import baritone.api.pathing.calc.IPath;
import baritone.api.physics.IPredictivePhysics;
import baritone.api.physics.PredictionRequest;
import baritone.api.physics.PredictionResult;
import baritone.gracenote.GraceNote;
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
import baritone.pathing.movement.movements.MovementLine;
import baritone.pathing.movement.movements.MovementStraight;
import baritone.pathing.legato.LegatoFeasibility;
import baritone.pathing.movement.movements.MovementTraverse;
import baritone.api.utils.Helper;
import baritone.pathing.viz.PathViz;
import baritone.utils.BlockStateInterface;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
    private boolean head;
    private float camYaw, camVel0;
    private double wander;
    private final Random rng = baritone.utils.OstinatoRandom.create("kinematic.wander"); // Ostinato: seedable for the parity check
    private int lastDrivenTick = -10;
    private boolean longJump; // the driven stretch holds a 3 or 4 block gap
    private boolean straightAhead; // Legato: the driven stretch holds a straight any-angle line
    private boolean endsAtPlace; // the stretch stops at a movement that breaks or places, which needs the player slow at the edge
    /** Ticks the controller has driven the player, so callers can verify the backend is in use. */
    public static volatile long drivenTicks;

    /** Path visualization: whether rollouts now are the fan of this tick's choice (not margin or strafe checks). */
    private boolean vizRec;
    /** The slot the running rollout records into, null when not recording. */
    private PathViz.Rollout vizR;

    /**
     * Ostinato Presto: one per player, kept across paths (a controller lives one path; trust is about the model and
     * the server, not the path). See {@link Presto}.
     */
    private static final java.util.Map<IPlayerContext, Presto> PRESTO = new java.util.WeakHashMap<>();
    private final Presto presto;
    /** The engine's flight of a gap takeoff runs at most this long, and gets this long (cooperative) to answer. */
    private static final int PRESTO_HORIZON = 40;
    private static final long PRESTO_DEADLINE = 5_000_000L;
    /** After a vetoed takeoff hands the jump to Baritone, the controller sits out this long so it is not taken back mid-run. */
    private static final int PRESTO_REST = 20;
    private int prestoDefers;
    private static Boolean prestoVersionSupported;
    private final float[] prestoYawVelocity = new float[1];
    private PredictionResult.Landing checkedLanding;
    /** The touchdown of the running rollout's first jump (tick counted from now, -1 none), and of the chosen plan's. */
    private int rollT, rollLandT, bestLandT;
    private double rollLandX, rollLandY, rollLandZ, bestLandX, bestLandY, bestLandZ;

    public KinematicController(IPlayerContext ctx) {
        this.ctx = ctx;
        this.world = new ClientWorld(ctx);
        this.real = new PlayerSim(world);
        this.sim = new PlayerSim(world);
        synchronized (PRESTO) {
            this.presto = PRESTO.computeIfAbsent(ctx, c -> new Presto());
        }
    }

    /** Ostinato Presto: this player's monitor and counters (for the harness and logs). */
    /**
     * Ostinato Presto: the landing audit, run every client tick (from PathingBehavior) whoever is driving: a jump that
     * goes wrong usually ends with the path abandoned and this controller no longer called.
     */
    public static void audit(Baritone baritone) {
        IPlayerContext ctx = baritone.getPlayerContext();
        Presto p;
        synchronized (PRESTO) {
            p = PRESTO.get(ctx); // disabled/unused mode must not allocate a monitor
        }
        if (p == null) return;
        boolean eligible = Baritone.settings().presto.value && ctx.player() != null && ctx.player().isAlive()
                && versionSupported() && supportedBody(ctx) && !graceNoteBusy(baritone) && !baritone.getPvpProcess().isActive();
        if (p.auditContext(ctx.world(), ctx.player(), eligible) && p.armed()) {
            p.landing(ctx.player().tickCount, ctx.player().getY(), ctx.player().onGround());
        }
    }

    public static void invalidatePresto(IPlayerContext ctx, boolean correction) {
        synchronized (PRESTO) {
            Presto p = PRESTO.get(ctx);
            if (p != null) {
                if (correction && Baritone.settings().presto.value) p.corrected();
                else p.invalidate();
            }
        }
    }

    private static boolean versionSupported() {
        if (prestoVersionSupported == null) {
            prestoVersionSupported = Presto.supportsVersion(net.minecraft.SharedConstants.getCurrentVersion().name());
        }
        return prestoVersionSupported;
    }

    private static boolean supportedBody(IPlayerContext ctx) {
        var p = ctx.player();
        return ClientWorld.plainGravity(ctx) && !p.isNoGravity() && !p.getAbilities().flying && !p.isFallFlying()
                && !p.isPassenger() && !p.isInWater() && !p.isInLava() && !p.isShiftKeyDown()
                && Presto.supportsBody(p.getBbWidth(), p.getBbHeight(), p.maxUpStep(),
                p.getAttributeValue(Attributes.AIR_DRAG_MODIFIER), p.getAttributeValue(Attributes.FRICTION_MODIFIER),
                p.getAttributeValue(Attributes.BOUNCINESS));
    }

    public static Presto presto(IPlayerContext ctx) {
        synchronized (PRESTO) {
            return PRESTO.computeIfAbsent(ctx, c -> new Presto());
        }
    }

    /**
     * @return the path position to continue from if the controller drove this tick, or -1 to let Baritone run the movement
     */
    public int tick(Baritone baritone, IPath path, int pathPosition) {
        narrowEnd = false;
        traceDelay = Integer.MIN_VALUE;
        traceJump = false;
        traceGain = Double.NaN;
        branch = "rollout";
        int r = drive(baritone, path, pathPosition);
        traceTick = ctx.player().tickCount;
        traceR = r;
        traceWhy = r < 0 ? declined : branch;
        if (r >= 0) {
            drivenTicks++;
            if (prestoOn && Baritone.settings().prestoDebug.value && drivenTicks % 600 == 0) {
                System.out.println(presto.summary());
            }
        }
        boolean jumpNext = pathPosition < path.movements().size() && (path.movements().get(pathPosition) instanceof baritone.pathing.movement.movements.MovementParkour
                || path.movements().get(pathPosition) instanceof baritone.pathing.movement.movements.MovementJump);
        if (r < 0 && !narrowEnd && declined != null && Baritone.settings().kinematicTrace.value && pathPosition < path.movements().size()) {
            BetterBlockPos d = path.movements().get(pathPosition).getDest();
            if (narrowAt(new double[]{d.x + 0.5, d.y, d.z + 0.5})) {
                System.out.println(String.format("KTRACE decline p=%d why=%s", pathPosition, declined));
            }
        }
        if ((narrowEnd || jumpNext) && Baritone.settings().kinematicTrace.value) {
            // Ostinato: per-tick trace onto a narrow landing or through a jump: who drives, where the player is, how it moves
            net.minecraft.world.phys.Vec3 v = ctx.player().getDeltaMovement();
            // println, not printf: the client's stdout capture logs println only, and printf lines were lost
            System.out.println(String.format("KTRACE " + (narrowEnd ? "narrow" : "jump") + " p=" + pathPosition + " r=%d pos=%.3f,%.3f,%.3f v=%.3f,%.3f,%.3f og=%b yaw=%.1f why=%s", r,
                    ctx.player().getX(), ctx.player().getY(), ctx.player().getZ(), v.x, v.y, v.z, ctx.player().onGround(), Mth.wrapDegrees(ctx.player().getYRot()), traceWhy));
        }
        return r;
    }

    /** Ostinato: why the controller left the last tick to Baritone before it looked at the stretch (trace only). */
    private String declined;
    /** Ostinato Presto v4.1: which part of drive() drove the last tick (trace only; constant strings). */
    private String branch;

    /*
     * Ostinato Presto v4.1, trace only (read by the live harness; no allocation): the last tick's client tick, return,
     * why it declined or which branch drove, the chosen plan's jump delay (MIN_VALUE: no rollouts ran, -1 walk), whether
     * it pressed jump, the plan's progress along the line over standing still, and whether the stretch was a long jump.
     */
    public int traceTick = -1, traceR = -1, traceDelay = Integer.MIN_VALUE;
    public boolean traceJump, traceLong;
    public double traceGain = Double.NaN;
    public String traceWhy;

    private int drive(Baritone baritone, IPath path, int pathPosition) {
        checkedLanding = null;
        prestoOn = Baritone.settings().presto.value && versionSupported() && ctx.player().isAlive()
                && supportedBody(ctx) && !graceNoteBusy(baritone) && !baritone.getPvpProcess().isActive();
        if (!prestoOn) {
            presto.invalidate();
            prestoDefers = 0;
        }
        declined = "state";
        if (!ExperimentalMovement.kinematicTravel() || ctx.player().isInWater() || ctx.player().isInLava()
                || (ctx.player().onClimbable() && !(pathPosition < path.movements().size() && path.movements().get(pathPosition) instanceof MovementTraverse))
                || ctx.player().isFallFlying() || ctx.player().isPassenger() || !ClientWorld.plainGravity(ctx)) {
            return -1;
        }
        if (cooldown > 0) {
            cooldown--;
            declined = "cooldown";
            return -1;
        }
        world.reset();
        if (!buildLine(path, pathPosition)) {
            declined = endsAtPlace ? "place" : "line";
            return -1;
        }
        ClientWorld.readPlayer(ctx, real);
        int tick = ctx.player().tickCount;
        if (prestoOn) {
            long h0 = System.nanoTime();
            baritone.getPredictivePhysics().warmUp(); // Presto v4: the first takeoff check is not the cold one
            presto.begin(ctx.world(), ctx.player(), path, tick);
            boolean was = presto.trusted();
            Presto.Reason miss = presto.observe(tick, real.x, real.y, real.z, real.vx, real.vz, real.onGround);
            presto.hookNanos += System.nanoTime() - h0;
            presto.hookTicks++;
            if (was != presto.trusted() && Baritone.settings().prestoDebug.value) {
                Helper.HELPER.logDebug("Presto: " + (was ? "prediction distrusted (" + miss + ")" : "prediction trusted again"));
            }
        } else {
            presto.drop();
            presto.disarm(); // Grace Note's episode: whatever lands now is not the jump that was predicted
        }

        double[] here = project(line, real.x, real.y, real.z, Double.MAX_VALUE);
        double end = line.get(line.size() - 1)[3];
        double handback = endsAtPlace ? PLACE_HANDBACK : HANDBACK;
        if (here[1] > WIDE + 0.2) {
            declined = "off line " + here[1];
            return -1;
        }
        if (prestoOn && !presto.trusted() && longJump && real.onGround && baritoneJumps(path, pathPosition)) {
            // Ostinato Presto: the sim has been missing the real player; Baritone's own parkour takes the gaps it can
            // until the sim tracks again. Gaps only this controller flies (four wide, off a slab) stay with it.
            presto.declined++;
            presto.drop();
            declined = "presto untrusted";
            return -1;
        }
        declined = null;
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
        if (airAim != null) {
            if (real.onGround) {
                airAim = null;
            } else {
                // Ostinato: still flying onto a narrow block the position sync has already counted reached (the feet
                // cell is its cell mid-air): keep steering for its middle, or the coast carried past an iron bar (cubed)
                branch = "steerAir";
                return steerAir(baritone, newPos, airAim[0], airAim[1]);
            }
        }
        if (recenter > 0) {
            recenter--;
            // the second time, once back on the line, walk along it: the rollout keeps steering into the same snag
            boolean along = recenters > 1 && here[1] < 0.1;
            double[] c = pointAt(here[0] + (along ? 0.6 : 0));
            float yaw = (float) Math.toDegrees(Math.atan2(-(c[0] - real.x), c[2] - real.z));
            branch = "recenter";
            baritone.getLookBehavior().updateTarget(new Rotation(yaw, 0), false);
            baritone.getInputOverrideHandler().clearAllKeys();
            baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_FORWARD, along || here[1] > 0.05);
            return newPos;
        }
        if (narrowFlight) {
            // Ostinato: after a narrowTakeoff fly the arrival steering (airArrive) all the way down, not the rollouts
            if (!real.onGround) {
                branch = "narrowFlight";
                return arrive(baritone, newPos, line.get(line.size() - 1));
            }
            if (ctx.player().tickCount - narrowJumpTick > 2) {
                narrowFlight = false;
            }
        }
        if (narrowStart && real.onGround) {
            // no run-up on a block an eighth wide: jump from where we stand (or hand the takeoff to TakeoffController)
            branch = "narrowStart";
            return narrowTakeoff(baritone, newPos, line.get(line.size() - 1));
        }
        boolean backing = ctx.player().tickCount - narrowBackTick <= 15; // backing up for a run-up takes it out of range
        if (narrowEnd && real.onGround && end - here[0] < NARROW_RANGE + (backing ? 1.5 : 0)) {
            // Ostinato: a short hop onto a narrow block: the rollouts score how far along the line a jump gets before
            // the horizon, and a jump that falls past an open trapdoor's edge is still above it then (cubed level 1)
            branch = "narrowEnd";
            int nt = narrowTakeoff(baritone, newPos, line.get(line.size() - 1));
            if (nt >= 0) {
                return nt;
            }
        }
        if (arriving) {
            branch = "arrive";
            return arrive(baritone, newPos, line.get(line.size() - 1));
        }
        head = Baritone.settings().headSteering.value;
        camYaw = ctx.player().getYRot();
        camVel0 = baritone.getLookBehavior().yawVelocity();
        if (real.onGround && turningAround(ctx.playerRotations().getYaw(), aim(real.x, real.z, here[0], false))) {
            declined = "turning";
            return -1;
        }
        // a slow drift of the aim point off the line, as a hand wanders; none where precision matters
        double maxWander = longJump || endsAtPlace ? 0 : Baritone.settings().pathWander.value;
        if (straightAhead) {
            // Legato: a line was checked with the box plus MARGIN either side; wander only inside half of that
            maxWander = Math.min(maxWander, LegatoFeasibility.MARGIN / 2);
        }
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
        // Ostinato Presto: the best plan that jumps later, for a takeoff the engine vetoes
        double laterScore = -1e9;
        float laterOff = 0;
        int laterDelay = NEVER;
        bestLandT = -1;
        for (int delay : longJump ? DELAYS : JUMP_OR_NOT) {
            for (float off : delay <= 0 ? YAW_OFFSETS : NO_OFFSET) {
                double score = rollout(off, delay, false, here[0]);
                PathViz.Rollout r = vizR;
                int ri = viz.rolloutCount - 1;
                int lt = rollLandT;
                double lx = rollLandX, ly = rollLandY, lz = rollLandZ;
                if (delay > 0 && score > laterScore + 1e-6) {
                    laterScore = score;
                    laterOff = off;
                    laterDelay = delay;
                }
                if (score > bestScore + 1e-6 && !holdsWithMargin(off, delay, false, here[0])) {
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
                    bestLand(lt, lx, ly, lz);
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
                PathViz.Rollout r = vizR;
                int ri = vizR != null ? viz.rolloutCount - 1 : -1;
                int lt = rollLandT;
                double lx = rollLandX, ly = rollLandY, lz = rollLandZ;
                if (score > bestScore + 1e-6 && !holdsWithMargin(0, delay, true, here[0])) {
                    // Ostinato Presto v4: the margin holds for this aim too. Beside the line by 0.2, the point right
                    // beside the player is 25° off the axis; a jump there, taken on the tick a hop landed at the
                    // run-up's edge, fell 0.05 short of the four gap (presto-v4-fix5 lap 9 with Presto, fix6 lap 0 without)
                    score = -1e9;
                    if (r != null) {
                        r.reason = "barely lands";
                        r.score = score;
                    }
                }
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
                    bestLand(lt, lx, ly, lz);
                }
            }
        }
        boolean holdHop = !longJump && bestDelay == 0 && prestoOn && !presto.trusted() && walkScore > here[0] + 0.05;
        if (holdHop && jumpWorthIt(bestScore, walkScore, here[0]) && real.onGround) {
            presto.hopsHeld++; // Ostinato Presto: the sim is off the real player, so no hop that running can do without
        }
        if (!longJump && bestDelay == 0 && (!jumpWorthIt(bestScore, walkScore, here[0]) || holdHop)) {
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
        traceLong = longJump;
        traceDelay = bestDelay;
        traceGain = bestScore - here[0];
        int strafe = 0;
        if (!real.onGround && bestScore > here[0] + 0.05) {
            // micro adjustment in the air, as a player taps A or D: the camera is slow, a strafe acts next tick
            double base = bestScore;
            for (int st = -1; st <= 1; st += 2) {
                rolloutStrafe = st;
                double sc = rollout(best, bestDelay, bestShort, here[0]);
                if (sc > base + 0.08 && holdsWithMargin(best, bestDelay, bestShort, here[0])) {
                    base = sc;
                    strafe = st;
                }
            }
            rolloutStrafe = 0;
        }
        boolean sprintKey = !(endsAtPlace && end - here[0] < PLACE_BRAKE);
        if (!real.onGround || !longJump) {
            prestoDefers = 0;
        }
        if (prestoOn && bestJump && longJump && Baritone.settings().prestoPhysicsCheck.value && bestLandT >= 0 && !crossesGap()) {
            // Ostinato Presto: a step up or down inside the stretch, floor under the whole line: nothing to fall into.
            // The engine holds one yaw where the controller steers, and from a standstill that once flew a step-up off
            // the one-wide walkway and vetoed it (live run 3); the landing audit still covers the jump
            presto.noGap++;
        } else if (prestoOn && bestJump && longJump && Baritone.settings().prestoPhysicsCheck.value) {
            // Ostinato Presto: fly this takeoff in the engine (vanilla 26.3) before pressing it
            Presto.Verdict v = takeoffCheck(baritone, sprintKey, tick);
            if (v.veto()) {
                if (Baritone.settings().prestoDebug.value) {
                    Helper.HELPER.logDebug("Presto: takeoff vetoed (" + v + ") at " + ctx.playerFeet());
                }
                if (laterScore > here[0] + 0.05 && prestoDefers < Presto.MAX_DEFER && holdsWithMargin(laterOff, laterDelay, false, here[0])) {
                    // a later takeoff the sim lands too: run on this tick and ask again on the next
                    prestoDefers++;
                    presto.deferred++;
                    bestScore = laterScore;
                    best = laterOff;
                    bestDelay = laterDelay;
                    bestShort = false;
                    bestJump = false;
                } else if (baritoneJumps(path, pathPosition)) {
                    // no later takeoff: Baritone's parkour takes the jump from here
                    presto.fallbacks++;
                    presto.drop();
                    cooldown = PRESTO_REST;
                    declined = "presto " + v;
                    return -1;
                } else {
                    // only this controller flies this gap: nothing safer than its own jump, so it stands (as with Presto off)
                    presto.overridden++;
                }
            } else {
                prestoDefers = 0;
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
                declined = "no progress";
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
        baritone.getInputOverrideHandler().setInputForceState(Input.SPRINT, sprintKey);
        baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, bestJump);
        traceJump = bestJump;
        traceDelay = bestDelay; // a Presto deferral may have replaced the plan
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_LEFT, strafe > 0);
        baritone.getInputOverrideHandler().setInputForceState(Input.MOVE_RIGHT, strafe < 0);
        if (prestoOn) {
            // Ostinato Presto: what these keys should do by next tick, with the camera where it will really be
            long h0 = System.nanoTime();
            float applied = head ? LookBehavior.modelYawStep(camYaw, camVel0, yaw, prestoYawVelocity) : yaw;
            sim.copyFrom(real);
            sim.tick(applied, 1, strafe, sprintKey, bestJump);
            presto.expect(tick, sim.x, sim.y, sim.z, sim.vx, sim.vz, sim.onGround);
            if (bestJump && bestLandT >= 0) {
                // Arm only after this exact jump's inputs are committed, never while merely considering a plan.
                if (checkedLanding != null) presto.arm(tick, checkedLanding.tick, checkedLanding.y, bestLandT, bestLandY);
                else if (!presto.armed()) presto.armSim(tick, bestLandT, bestLandY);
            }
            presto.hookNanos += System.nanoTime() - h0;
        }
        return newPos;
    }

    /** Ostinato Presto: whether Presto acts this tick (setting on, Grace Note not in an episode). */
    private boolean prestoOn;

    /** Grace Note owns emergencies: while it has an episode open Presto keeps out, and what it expected no longer holds. */
    private static boolean graceNoteBusy(Baritone baritone) {
        GraceNote gn = baritone.getGraceNote();
        if (gn.holdsControl()) {
            return true;
        }
        IGraceNote.Phase p = gn.status().phase();
        return p != IGraceNote.Phase.MONITORING && p != IGraceNote.Phase.COMPLETED
                && p != IGraceNote.Phase.FAILED;
    }

    /**
     * Ostinato Presto: whether Baritone's own parkour can take every long jump of the stretch (three wide at most, off a
     * full block); the four-wide ones and those off a slab or soul sand are flown by this controller only.
     */
    private boolean baritoneJumps(IPath path, int pathPosition) {
        for (int k = pathPosition; k <= lastMove && k < path.movements().size(); k++) {
            IMovement mv = path.movements().get(k);
            if (mv instanceof MovementParkour) {
                BetterBlockPos s = mv.getSrc(), d = mv.getDest();
                BlockState on = ctx.world().getBlockState(s.below());
                if (Math.abs(d.x - s.x) + Math.abs(d.z - s.z) > 4 || MovementHelper.collisionTop(on) < 1 || on.getBlock() == Blocks.SOUL_SAND) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Ostinato Presto: whether the straight line from the feet to PlayerSim's landing passes over a column with no
     * collision anywhere from a block under the lower of the two feet heights up to the higher: a gap to fall into.
     * Sampled every quarter block along the line's middle.
     */
    private boolean crossesGap() {
        double dx = bestLandX - real.x, dz = bestLandZ - real.z;
        int n = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dz * dz) / 0.25));
        int lo = Mth.floor(Math.min(real.y, bestLandY)) - 1, hi = Mth.floor(Math.max(real.y, bestLandY));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k <= n; k++) {
            int bx = Mth.floor(real.x + dx * k / n), bz = Mth.floor(real.z + dz * k / n);
            boolean floor = false;
            for (int by = lo; by <= hi && !floor; by++) {
                floor = MovementHelper.collisionTop(ctx.world().getBlockState(p.set(bx, by, bz))) > 0;
            }
            if (!floor) {
                return true;
            }
        }
        return false;
    }

    private void bestLand(int t, double x, double y, double z) {
        bestLandT = t;
        bestLandX = x;
        bestLandY = y;
        bestLandZ = z;
    }

    /**
     * Ostinato Presto: the chosen takeoff, flown by the predictive physics engine with W, sprint and jump held toward
     * where PlayerSim lands it. Its first landing is compared with PlayerSim's (see {@link Presto#judge}). Inline: one
     * call on a gap jump's takeoff tick, SCREENING, horizon {@link #PRESTO_HORIZON} at most.
     */
    private Presto.Verdict takeoffCheck(Baritone baritone, boolean sprint, int tick) {
        if (bestLandT <= 0 || bestLandT > PRESTO_HORIZON) {
            presto.checked(Presto.Verdict.UNAVAILABLE, 0);
            return Presto.Verdict.UNAVAILABLE; // the plan lands past the rollout: nothing to compare with
        }
        long t0 = System.nanoTime();
        Presto.Verdict v;
        PredictionResult r = null;
        try {
            IPredictivePhysics ppe = baritone.getPredictivePhysics();
            float toLand = (float) Math.toDegrees(Math.atan2(-(bestLandX - real.x), bestLandZ - real.z));
            r = ppe.predict(PredictionRequest.trajectory(ppe.capture(ctx.player()), Math.min(PRESTO_HORIZON, bestLandT + 12))
                    .input(new PredictionRequest.Input(1, 0, true, sprint, false, toLand, Float.NaN))
                    .priority(PredictionRequest.Priority.MOVEMENT)
                    .fidelity(PredictionRequest.Fidelity.SCREENING)
                    .consumer("presto")
                    .deadlineIn(PRESTO_DEADLINE)
                    .build());
            boolean usable = Presto.usable(r, bestLandT);
            PredictionResult.Landing l = r.landing;
            double side = l == null ? 0 : Presto.offLine(real.x, real.z, bestLandX, bestLandZ, l.x, l.z);
            v = Presto.judge(usable, l != null, l == null ? 0 : l.damage, l != null && l.lethal, l == null ? 0 : l.y, bestLandY, side);
            if (usable && v == Presto.Verdict.UNAVAILABLE) {
                presto.offLine++;
            }
        } catch (RuntimeException e) {
            v = Presto.Verdict.UNAVAILABLE; // an unsupported body or no world: no opinion, the controller decides alone
        }
        presto.checked(v, System.nanoTime() - t0);
        if (Baritone.settings().prestoDebug.value) {
            PredictionResult.Landing l = r == null ? null : r.landing;
            System.out.println(String.format(java.util.Locale.ROOT, "PRESTO check t=%d pos=%.3f,%.3f,%.3f v=%.4f,%.4f cam=%.1f sprint=%b sim=%d@%.3f,%.3f,%.3f ppe=%s %s %s",
                    tick, real.x, real.y, real.z, real.vx, real.vz, ctx.player().getYRot(), sprint, bestLandT, bestLandX, bestLandY, bestLandZ,
                    l == null ? "none" : String.format(java.util.Locale.ROOT, "%d@%.3f,%.3f,%.3f", l.tick, l.x, l.y, l.z),
                    r == null ? "-" : r.status + "/" + r.confidence, v));
        }
        if (v == Presto.Verdict.AGREE) {
            checkedLanding = r.landing;
        }
        return v;
    }

    // Ostinato: the stretch ends on a block narrower than a full one (real collision shape), see arrive
    private boolean narrowEnd;
    // Ostinato: the stretch is one gap jump off a narrow block
    private boolean narrowStart;

    private boolean narrowAt(double[] to) {
        BlockPos feet = BlockPos.containing(to[0], to[1] + 0.01, to[2]);
        BlockState st = ctx.world().getBlockState(feet);
        if (MovementHelper.collisionTop(st) <= 0) {
            st = ctx.world().getBlockState(feet.below());
        }
        // a sixteenth in on each side (honey, chest, cake) lands like a full block; honey read as narrow had the
        // controller hunting for a landing onto the honey it was taking off from
        return MovementHelper.collisionTopWidth(st) < 0.8;
    }

    /**
     * Ostinato: takeoff onto a narrow landing (candle, lantern, pot). Tries, in the sim, walking (then sprinting) for
     * 0..12 ticks toward its middle and jumping, then the same mid-air steering {@link #airArrive} flies; a plan counts
     * only if the feet land on the real support top overlapping its collision shape by at least {@link #NARROW_OVERLAP}.
     * Acts on the first tick of the first plan found (fewest ticks, walking before sprinting), searched again each tick.
     */
    private int narrowTakeoff(Baritone baritone, int newPos, double[] to) {
        double top = to[1] - to[4];
        double[] n = narrowTarget(to);
        double cx = n[0], cz = n[1];
        if (Math.abs(real.y - top) < 0.02 && narrowWalks(top, cx, cz, n[2], n[3])) {
            // Ostinato: already level with it and the floor carries the feet there (an open trapdoor against a full
            // block of the same top): walk, a hop from the wide side landed off the plate (cubed level 1)
            return arrive(baritone, newPos, to);
        }
        // Ostinato: and if nothing lands from here, back away from it first for a run-up: on the edge of a 3/16 trapdoor
        // rail every walk toward the landing stepped off at once, and the standing jump fell short (cubed level 1).
        // Per back-up the plan landing furthest inside the shape wins: the fewest-ticks plan just reached the edge, and
        // the camera's lag in the real client then missed it. The first back-up with a margin of NARROW_SAFE is taken,
        // else the best plan of all.
        int pick = -1, pickSp = 0, pickDelay = 0;
        double pickM = 0;
        for (int back = 0; back <= NARROW_BACK && pickM < NARROW_SAFE; back += 2) {
            for (int sp = 0; sp <= 1; sp++) {
                for (int delay = 0; delay <= 12; delay++) {
                    double m = narrowLands(back, sp == 1, delay, top, cx, cz, n[2], n[3]);
                    if (m > pickM) {
                        pick = back;
                        pickSp = sp;
                        pickDelay = delay;
                        pickM = m;
                    }
                }
            }
        }
        if (pick < 0) {
            if (Baritone.settings().kinematicTrace.value) {
                System.out.printf("KTRACE narrowTakeoff none at=%.3f,%.3f reach=%.3f,%.3f%n", cx, cz, n[2], n[3]);
            }
            double[] h = narrowTarget(new double[]{real.x, real.y, real.z, 0, 0});
            if (!real.onGround || Math.min(h[2], h[3]) >= 0.45) {
                return -1;
            }
            // Ostinato: standing on a narrow block with no takeoff from here (landed on its very edge): the arrival
            // walk toward the next one stepped off it (cubed level 1). Sneak back toward its middle instead, the edge
            // guard keeps the feet on it, and plan again from there.
            double hx = h[0] - real.x, hz = h[1] - real.z;
            InputOverrideHandler in = baritone.getInputOverrideHandler();
            in.clearAllKeys();
            in.setInputForceState(Input.SNEAK, true);
            float hy = (float) Math.toDegrees(Math.atan2(-hx, hz));
            baritone.getLookBehavior().updateTarget(new Rotation(hy, 4f), true);
            in.setInputForceState(Input.MOVE_FORWARD, Math.hypot(hx, hz) > 0.05 && Math.abs(Mth.wrapDegrees(hy - ctx.player().getYRot())) < 30);
            lastDrivenTick = ctx.player().tickCount;
            return newPos;
        }
        int back = pick, sp = pickSp, delay = pickDelay;
        float yaw = (float) Math.toDegrees(Math.atan2(-(cx - real.x), cz - real.z));
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, 4f), true);
        InputOverrideHandler in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        lastDrivenTick = ctx.player().tickCount;
        if (Baritone.settings().kinematicTrace.value) {
            System.out.printf("KTRACE narrowTakeoff back=%d sprint=%b delay=%d margin=%.3f at=%.3f,%.3f reach=%.3f,%.3f%n", back, sp == 1, delay, pickM, cx, cz, n[2], n[3]);
        }
        if (Math.abs(Mth.wrapDegrees(yaw - ctx.player().getYRot())) > NARROW_TURN && Math.hypot(real.vx, real.vz) < 0.05) {
            // Ostinato: the plan walks toward the target at once; from a stand (a teleport, a landing) the
            // camera is still turning, and W then walked sideways off an iron bar (cubed level 10). Turn first, under
            // sneak: a landing's last drift carried the feet off a trapdoor's edge while turning (cubed level 1).
            in.setInputForceState(Input.SNEAK, true);
            return newPos;
        }
        if (back > 0) {
            in.setInputForceState(Input.MOVE_BACK, true);
            narrowBackTick = ctx.player().tickCount;
            return newPos;
        }
        in.setInputForceState(Input.MOVE_FORWARD, true);
        in.setInputForceState(Input.SPRINT, sp == 1);
        in.setInputForceState(Input.JUMP, delay == 0);
        if (delay == 0) {
            narrowFlight = true;
            narrowJumpTick = ctx.player().tickCount;
        }
        return newPos;
    }

    static final double NARROW_OVERLAP = 0.05;
    /** The most ticks a narrow takeoff backs away from its landing for a run-up. */
    static final int NARROW_BACK = 10;
    /** How far inside the landing's reach (blocks, on the tighter axis) a narrow takeoff plan should land. */
    static final double NARROW_SAFE = 0.05;
    /** How far (degrees) the camera may still be off the takeoff heading when a narrow takeoff from a stand starts walking. */
    static final float NARROW_TURN = 6;
    /** The middle of the narrow landing being flown onto, kept until the feet are down. */
    private double[] airAim;
    /** How far from a narrow stretch end the controller plans the takeoff itself rather than the rollouts. */
    static final double NARROW_RANGE = 2.0;

    /**
     * Ostinato: where to land on a narrow stretch end: the middle of its support's collision shape and, per axis, how
     * far the feet may be from it and still overlap the shape by {@link #NARROW_OVERLAP}. Not the cell middle: an
     * open trapdoor is a 3/16 wall along one side of its cell, so aiming at the middle jumped past it.
     */
    private double[] narrowTarget(double[] to) {
        BlockPos feet = BlockPos.containing(to[0], to[1] + 0.01, to[2]);
        BlockState st = ctx.world().getBlockState(feet);
        BlockPos at = feet;
        if (MovementHelper.collisionTop(st) <= 0) {
            at = feet.below();
            st = ctx.world().getBlockState(at);
        }
        net.minecraft.world.phys.AABB b = MovementHelper.collisionTopBounds(st);
        if (b == null) {
            double r = 0.3 + 0.5 - NARROW_OVERLAP;
            return new double[]{to[0], to[2], r, r};
        }
        double[] x = narrowSpan(at.getX(), b.minX, b.maxX), z = narrowSpan(at.getZ(), b.minZ, b.maxZ);
        return new double[]{x[0], z[0], x[1], z[1]};
    }

    /** Middle and half width of the feet positions over [lo, hi) of the cell at c that overlap it and stay in the cell. */
    static double[] narrowSpan(int c, double lo, double hi) {
        double a = Math.max(c + lo - 0.3 + NARROW_OVERLAP, c + 0.01), b = Math.min(c + hi + 0.3 - NARROW_OVERLAP, c + 0.99);
        return b < a ? new double[]{c + (lo + hi) / 2, 0} : new double[]{(a + b) / 2, (b - a) / 2};
    }
    private boolean narrowFlight;
    private int narrowJumpTick, narrowBackTick = -100;

    /** Whether {@link #arrive}'s ground steering settles the feet inside the reach without leaving the top. */
    private boolean narrowWalks(double top, double cx, double cz, double reachX, double reachZ) {
        sim.copyFrom(real);
        for (int t = 0; t < 40; t++) {
            if (!sim.onGround || Math.abs(sim.y - top) > 0.02) return false;
            double dx = cx - sim.x, dz = cz - sim.z, d = Math.hypot(dx, dz), speed = Math.hypot(sim.vx, sim.vz);
            if (speed < 0.03 && Math.min(reachX - Math.abs(dx), reachZ - Math.abs(dz)) >= NARROW_SAFE) return true;
            double want = Math.min(0.22, 0.04 + d * 0.3);
            int a = speed < want && d > 0.12 ? 1 : speed > want + 0.07 && d < 1.0 ? -1 : 0;
            sim.tick((float) Math.toDegrees(Math.atan2(-dx, dz)), a, false, false);
        }
        return false;
    }

    /** How far inside the reach the plan lands (blocks, tighter axis), or -1 if it does not. */
    private double narrowLands(int back, boolean sprint, int delay, double top, double cx, double cz, double reachX, double reachZ) {
        sim.copyFrom(real);
        boolean air = false;
        for (int t = 0; t < back; t++) {
            if (!sim.onGround) return -1; // backed off the far edge
            sim.tick((float) Math.toDegrees(Math.atan2(-(cx - sim.x), cz - sim.z)), -1, false, false);
        }
        for (int t = 0; t < delay + 40; t++) {
            float yaw = (float) Math.toDegrees(Math.atan2(-(cx - sim.x), cz - sim.z));
            if (t <= delay) {
                if (!sim.onGround) return -1; // walked off the edge before the jump
                sim.tick(yaw, 1, sprint, t == delay);
                continue;
            }
            if (!sim.onGround) air = true;
            if (air && sim.onGround) {
                double m = Math.min(reachX - Math.abs(sim.x - cx), reachZ - Math.abs(sim.z - cz));
                return Math.abs(sim.y - top) < 0.02 && m >= 0 ? m + 1e-6 : -1;
            }
            if (sim.y < top - 1.0) return -1;
            double d = Math.hypot(cx - sim.x, cz - sim.z);
            int a = airArrive(d, Math.hypot(sim.vx, sim.vz));
            sim.tick(yaw, a, false, false);
        }
        return -1;
    }

    /** Ostinato: mid-air over a narrow landing: 1 hold forward toward its middle, -1 brake, 0 coast. */
    static boolean needsStepUp(double floor, double feetY) {
        return floor > feetY + 0.1;
    }

    static int airArrive(double d, double speed) {
        if (d > 0.3) return speed < 0.25 ? 1 : 0;
        return speed > 0.08 ? -1 : 0;
    }

    /** Ostinato: mid-air onto a narrow landing whose middle is (cx, cz): the arrival steering of {@link #arrive}. */
    private int steerAir(Baritone baritone, int newPos, double cx, double cz) {
        double dx = cx - real.x, dz = cz - real.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        baritone.getLookBehavior().human();
        baritone.getLookBehavior().updateTarget(new Rotation(yaw, 4f), true);
        InputOverrideHandler in = baritone.getInputOverrideHandler();
        in.clearAllKeys();
        int a = airArrive(Math.hypot(dx, dz), Math.hypot(real.vx, real.vz));
        in.setInputForceState(Input.MOVE_FORWARD, a > 0 && Math.abs(Mth.wrapDegrees(yaw - ctx.player().getYRot())) < 60);
        in.setInputForceState(Input.MOVE_BACK, a < 0);
        lastDrivenTick = ctx.player().tickCount;
        return newPos;
    }

    /** Stretch end: ease onto the last point (slow, no sprint) so the next movement starts from rest. */
    private int arrive(Baritone baritone, int newPos, double[] to) {
        double[] n = narrowEnd ? narrowTarget(to) : null;
        double dx = (n != null ? n[0] : to[0]) - real.x, dz = (n != null ? n[1] : to[2]) - real.z;
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
            airAim = new double[]{n[0], n[1]};
            int a = airArrive(d, speed);
            in.setInputForceState(Input.MOVE_FORWARD, a > 0 && diff < 60);
            in.setInputForceState(Input.MOVE_BACK, a < 0);
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
        simJumped = false;
        rollT = 0;
        rollLandT = -1;
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
            boolean air = !sim.onGround;
            simJumped |= jump;
            sim.tick(want, 1, !sim.onGround && t < 4 ? rolloutStrafe : 0, true, jump);
            rollT = t + 1;
            if (air && sim.onGround && simJumped) {
                touchdown();
            }
            if (air && sim.onGround && simJumped && !landsSquare()) {
                return reject("lands on edge");
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
                if (wetBelow(pr[0])) return reject("into water");
                if (climbedOff(pr[0])) return reject("climbs off path");
            } else if (hardLanding() || pr[1] > WIDE || hazard(sim.x, sim.y, sim.z) || sim.y < floorAt(pr[0]) - 0.4 || wetBelow(pr[0]) || climbedOff(pr[0])) {
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
                // on the path with no jump pending: nothing later in this plan can fall in
                return edgeHolds() ? score : reject("slides off edge");
            }
        }
        return settles(cam, camVel, s) ? score : reject("lands in gap");
    }

    /** Ostinato Presto: the rollout's first touchdown after its jump, for the takeoff check to compare with. */
    private void touchdown() {
        if (rollLandT < 0) {
            rollLandT = rollT;
            rollLandX = sim.x;
            rollLandY = sim.y;
            rollLandZ = sim.z;
        }
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
            rollT++;
            if (sim.onGround && simJumped) {
                touchdown();
            }
            if (sim.onGround && simJumped && !landsSquare()) {
                return false;
            }
            double[] pr = project(line, sim.x, sim.y, sim.z, s + REACH_AHEAD);
            if (hardLanding() || pr[1] > WIDE || sim.y < floorAt(pr[0]) - 0.4 || wetBelow(pr[0]) || hazard(sim.x, sim.y, sim.z) || climbedOff(pr[0])) {
                return false;
            }
            s = Math.max(s, pr[0]);
        }
        return sim.onGround && edgeHolds();
    }

    /**
     * Ostinato: in water under the path's floor. Water slows a fall, so a jump short into the pool under a gap stayed
     * above the falls-below-path line long enough to swim out onto the landing in the sim (cubed level 3).
     */
    private boolean wetBelow(double s) {
        return sim.y < floorAt(s) - 0.05 && sim.inWater();
    }

    private final List<double[]> under = new ArrayList<>();

    /**
     * Ostinato: a plan that ends on the ground with nothing under the middle of the feet hangs on an edge, and the
     * speed it has may carry it off. A short jump came down on the very lip of its takeoff block heading into the gap:
     * the horizon ended on that landing tick, and the next tick fell (cubed level 3). Coast it a few ticks, no keys.
     */
    private boolean edgeHolds() {
        if (!sim.onGround) {
            return true;
        }
        under.clear();
        world.collect(sim.x - 0.01, sim.y - 0.05, sim.z - 0.01, sim.x + 0.01, sim.y, sim.z + 0.01, under);
        for (double[] b : under) {
            if (b[0] <= sim.x && b[3] >= sim.x && b[2] <= sim.z && b[5] >= sim.z && b[4] >= sim.y - 0.05 && b[1] < sim.y) {
                return true;
            }
        }
        for (int t = 0; t < 5; t++) {
            sim.tick(0, false, false, false);
            if (!sim.onGround) {
                return false;
            }
        }
        return true;
    }

    /** Whether the current rollout has jumped (its touchdowns are checked by {@link #landsSquare}). */
    private boolean simJumped;
    /** How far in from the box's sides a jump's touchdown must still have the floor under it. */
    static final double LAND_INSET = 0.2;

    /**
     * Ostinato: a jump touches down with the floor under the middle of the feet, give or take {@link #LAND_INSET} of the
     * box's 0.3 to each side, or with the floor just ahead of it (a long jump reaching the near lip is fine). A touchdown
     * on a side edge only held in the sim: the real camera turned a few degrees less in flight, and a gap3 onto a
     * one-wide block taken from 0.7 off the line came down 0.3 east of it (cubed level 6).
     */
    private boolean landsSquare() {
        double hs = Math.hypot(sim.vx, sim.vz), ux = hs < 1e-4 ? 0 : sim.vx / hs, uz = hs < 1e-4 ? 0 : sim.vz / hs;
        double h = 0.3 - LAND_INSET, ax = ux * 0.3, az = uz * 0.3;
        double minX = sim.x - h + Math.min(0, ax), maxX = sim.x + h + Math.max(0, ax);
        double minZ = sim.z - h + Math.min(0, az), maxZ = sim.z + h + Math.max(0, az);
        under.clear();
        world.collect(minX, sim.y - 0.05, minZ, maxX, sim.y, maxZ, under);
        for (double[] b : under) {
            if (b[0] < maxX && b[3] > minX && b[2] < maxZ && b[5] > minZ && b[4] >= sim.y - 0.05 && b[1] < sim.y) {
                return true;
            }
        }
        return false;
    }

    static final double LATERAL_MARGIN = 0.15;

    /**
     * A long jump must still land if it leaves a quarter block late, or a little to either side: no barely-landing
     * plans. Ostinato: the side shifts catch a jump cutting toward a path that turns after the landing, which touched
     * down on the landing block's very edge in the sim and fell past it for real (cubed level 9).
     */
    private boolean holdsWithMargin(float off, int delay, boolean shortAim, double s0) {
        double hs = Math.hypot(real.vx, real.vz);
        if (!longJump || !real.onGround || hs < 0.01) {
            return true;
        }
        double[][] shifts = marginShifts(real.vx, real.vz);
        double x = real.x, z = real.z;
        boolean rec = vizRec;
        vizRec = false;
        boolean holds = true;
        for (double[] d : shifts) {
            real.x = x + d[0];
            real.z = z + d[1];
            if (rollout(off, delay, shortAim, s0) <= -1e8) {
                holds = false;
                break;
            }
        }
        vizRec = rec;
        real.x = x;
        real.z = z;
        return holds;
    }

    /**
     * The take-off spots {@link #holdsWithMargin} flies from, as offsets from the real one: a quarter block back along
     * the ground velocity (vx, vz, not zero), and {@link #LATERAL_MARGIN} to either side of it.
     */
    static double[][] marginShifts(double vx, double vz) {
        double hs = Math.hypot(vx, vz), ux = vx / hs, uz = vz / hs;
        return new double[][]{{-ux * 0.25, -uz * 0.25}, {-uz * LATERAL_MARGIN, ux * LATERAL_MARGIN}, {uz * LATERAL_MARGIN, -ux * LATERAL_MARGIN}};
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

    /** Above this many degrees between the look and the path, the rollouts' yaw model is no guide (see turningAround). */
    static final float TURN_LIMIT = 90f;

    /**
     * Ostinato Presto v4: whether the player, on the ground, still has to turn more than {@link #TURN_LIMIT} to face
     * along the path. The rollouts model the look's turn per tick, but near a reversal which way it goes depends on
     * the aim's jitter and the target's drift, and the model and the real look turned opposite ways: after the
     * one-block gap's walk-back (facing west, path east) the controller took the Traverse and jumped mid-turn,
     * flying off the walkway's side (presto-v4-fix2 laps 3, 15). Baritone's movement turns the player first.
     */
    static boolean turningAround(float moveYaw, float pathYaw) {
        float d = (pathYaw - moveYaw) % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return Math.abs(d) > TURN_LIMIT;
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
        narrowStart = false;
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
            // Ostinato: a candle or lantern is stood on, not run over: end the stretch on it, and a gap off one is a
            // stretch of its own, flown as a standing jump (narrowTakeoff)
            boolean offNarrow = i == pathPosition && mv instanceof MovementParkour && narrowAt(line.get(0));
            if (offNarrow || narrowAt(line.get(line.size() - 1))) {
                narrowStart = offNarrow;
                i++;
                break;
            }
        }
        lastMove = i - 1;
        longJump = false;
        straightAhead = false;
        for (int k = pathPosition; k < i; k++) {
            IMovement mv = moves.get(k);
            longJump |= mv instanceof MovementParkour && mv.getSrc().distSqr(mv.getDest()) >= 16;
            straightAhead |= mv instanceof MovementStraight;
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

    static boolean drivable(IMovement mv) {
        if (mv instanceof MovementTraverse || mv instanceof MovementDiagonal || mv instanceof MovementAscend) {
            return mv.getDest().y - mv.getSrc().y <= 1;
        }
        if (mv instanceof MovementStraight) {
            return true; // Legato: flat by construction
        }
        if (mv instanceof MovementLine) {
            return KinematicLine.drivable((MovementLine) mv); // a knight move: behind kinematicLine, plain ground only
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
        return syncedPosition(path.movements(), pathPosition, lastMove, real.x, real.y, real.z, real.onGround);
    }

    /** The furthest move in [pathPosition, lastMove] whose dest cell holds the feet, plus one; else pathPosition. */
    static int syncedPosition(List<? extends IMovement> moves, int pathPosition, int lastMove, double x, double y, double z, boolean onGround) {
        int fx = PlayerSim.floor(x), fz = PlayerSim.floor(z);
        int fy = PlayerSim.floor(y + 1e-3);
        for (int i = lastMove; i >= pathPosition; i--) {
            BetterBlockPos d = moves.get(i).getDest();
            if (d.x == fx && d.z == fz && (onGround ? fy == d.y : fy >= d.y - 1 && fy <= d.y + 1)) {
                return i + 1;
            }
        }
        return pathPosition;
    }
}
