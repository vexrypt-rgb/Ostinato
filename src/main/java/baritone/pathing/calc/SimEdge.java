/*
 * Ostinato: new file (Ostinato 2.0 Phase 05, simulation-derived A* edges). Not part of upstream Baritone.
 */
package baritone.pathing.calc;

import baritone.contract.PredictionTolerance;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.Moves;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * One movement edge priced by stepping {@link PlayerSim} from the body the previous edge predicted, instead of by the
 * legacy closed-form move cost. Covers walking (TRAVERSE), turning (the yaw a 32°/tick cap lets the executor reach,
 * and the momentum a turn loses) and the one-block jump (ASCEND); a flat DIAGONAL too. Everything else stays legacy.
 * <p>
 * The controller copies what the executor does for these movements (docs/ostinato2/PHASE05_AUDIT.md §9): forward
 * toward the destination centre with the yaw capped at {@link #YAW_CAP} per tick, sprint when the context may sprint,
 * and for an ascend the jump rule of {@code MovementAscend.updateState} with sprint kept only after a straight
 * traverse when {@code sprintAscends} is on. An edge ends on the tick the body enters the destination column at the
 * destination height, so the next edge starts with whatever momentum is left. A body may overshoot or cut a corner
 * through the source's safe neighbours (a reversal at sprint does), but not leave them.
 * <p>
 * An edge is simulated only when it is pure locomotion (nothing to break or place, a floor to stand on, no fluid, no
 * ladder, no magma sneak). Otherwise, or when the simulated body does not reach the destination, the caller keeps the
 * legacy cost and the body becomes {@link Body#known unknown}. A simulated edge from an unknown body starts at the
 * source centre at steady speed along its own direction: the legacy steady-state assumption.
 * <p>
 * Not thread-safe: one instance per search.
 */
public final class SimEdge {

    public static final int NONE = -1, TRAVERSE = 0, DIAGONAL = 1, ASCEND = 2;
    /** LookBehavior's capped turn per tick. */
    public static final float YAW_CAP = 32f;
    /** No edge of one block may take longer than this; the executor would be well past its own patience by then. */
    static final int MAX_TICKS = 60;
    /** Horizontal speed (blocks per tick, as PlayerSim stores it after drag) under which the body is at rest. */
    static final double REST = 0.03;
    /** Stored horizontal speed that separates a walk from a sprint: steady walk is 0.118, steady sprint 0.153. */
    static final double SPRINT_BAND = 0.135;
    /** Vanilla food exhaustion (REPORTED, Minecraft wiki; 26.3's server code is not in the decompiled tree). */
    static final double FOOD_SPRINT_PER_BLOCK = 0.1, FOOD_JUMP = 0.05, FOOD_SPRINT_JUMP = 0.2;

    /** A predicted body: what one edge hands to the next. */
    public static final class Body {
        /** False: no prediction (start of a next segment, or after a legacy edge). */
        public boolean known;
        public double x, y, z, vx, vy, vz;
        public float yaw;
        public boolean onGround, sprinting;
        public int jumpTicks;
        /** The family of the edge that produced this body and its horizontal direction (for the sprint-ascend rule). */
        public int lastKind = NONE, lastDx, lastDz;
        /** Search bookkeeping: the slot of the state that holds this body. Not copied by {@link #set}. */
        int slot = UNKNOWN_SLOT;

        public Body set(Body o) {
            known = o.known;
            x = o.x; y = o.y; z = o.z; vx = o.vx; vy = o.vy; vz = o.vz;
            yaw = o.yaw; onGround = o.onGround; sprinting = o.sprinting; jumpTicks = o.jumpTicks;
            lastKind = o.lastKind; lastDx = o.lastDx; lastDz = o.lastDz;
            return this;
        }

        public Body unknown() {
            known = false;
            lastKind = NONE;
            return this;
        }

        public double speed() {
            return Math.hypot(vx, vz);
        }

        void from(PlayerSim s, float yaw) {
            known = true;
            x = s.x; y = s.y; z = s.z; vx = s.vx; vy = s.vy; vz = s.vz;
            this.yaw = yaw; onGround = s.onGround; sprinting = s.sprinting; jumpTicks = s.jumpTicks;
        }

        void into(PlayerSim s) {
            s.x = x; s.y = y; s.z = z; s.vx = vx; s.vy = vy; s.vz = vz;
            s.onGround = onGround; s.sprinting = sprinting; s.jumpTicks = jumpTicks;
            s.collidedH = false; s.minorCollision = false; s.swimming = false;
            s.supported = false; s.onGroundNoBlocks = false;
        }
    }

    /** What one simulated edge predicts. */
    public static final class Result {
        /**
         * The edge's cost: its ticks, plus, from an unknown body, the steady time of the half block between the source
         * centre (where an unknown body is put) and the cell boundary (where a chained edge starts). That keeps a mix
         * of legacy and simulated edges priced over the same distance as the legacy model (centre to centre).
         */
        public double cost;
        public int ticks;
        /** Ticks off the ground: the part of the edge a missed jump could lose (risk). */
        public int air;
        /** Sum of the per-tick p99 one-step error bounds of {@link PredictionTolerance#MEASURED} (blocks, open loop). */
        public double drift;
        /** Food exhaustion the edge causes. */
        public double food;
        public final Body end = new Body();
    }

    /** Steady sprint and walk on plain ground, facing +z, as PlayerSim stores them: speed, vy, jump cooldown. */
    static final double SPRINT_SPEED, WALK_SPEED, STEADY_VY;
    /** Steady displacement per tick (blocks) at sprint and walk. */
    static final double SPRINT_DISP, WALK_DISP;
    /**
     * The most a worse body at a cell can cost over a better one: from full sprint the wrong way, turning round and
     * covering three blocks, against the same three blocks from steady sprint the right way, plus one tick. Used only
     * for dominance pruning (approximate; docs/ostinato2/PHASE05_AUDIT.md §3).
     */
    public static final double MOMENTUM_BOUND;

    private static final PlayerSim.World FLAT = new PlayerSim.World() {
        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++) {
                for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++) {
                    if (minY < 0) out.add(new double[]{x, -1, z, x + 1, 0, z + 1});
                }
            }
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return 0.6f;
        }
    };

    static {
        double[] sprint = steady(true), walk = steady(false);
        SPRINT_SPEED = sprint[0];
        WALK_SPEED = walk[0];
        STEADY_VY = sprint[1];
        SPRINT_DISP = sprint[2];
        WALK_DISP = walk[2];
        MOMENTUM_BOUND = reversalLoss() + 1;
    }

    private static double[] steady(boolean sprint) {
        PlayerSim s = new PlayerSim(FLAT);
        s.x = 0.5;
        s.z = 0.5;
        for (int i = 0; i < 3; i++) s.tick(0, false, false, false);
        for (int i = 0; i < 80; i++) s.tick(0, true, sprint, false);
        double z0 = s.z;
        for (int i = 0; i < 20; i++) s.tick(0, true, sprint, false);
        return new double[]{Math.hypot(s.vx, s.vz), s.vy, (s.z - z0) / 20};
    }

    /** Ticks lost by meeting a straight three-block run at full sprint the wrong way. */
    private static double reversalLoss() {
        SimEdge e = new SimEdge(FLAT, null);
        e.canSprint = true;
        Body wrong = new Body(), right = new Body();
        e.steadyBody(wrong, 0, 0, 0, 0, -1); // sprinting toward -z
        e.steadyBody(right, 0, 0, 0, 0, 1);
        Result r = new Result();
        int a = 0, b = 0;
        for (int i = 0; i < 3; i++) {
            if (!e.run(wrong, TRAVERSE, 0, 0, i, 0, 0, i + 1, r)) return MAX_TICKS;
            a += r.ticks;
            wrong.set(r.end);
            if (!e.run(right, TRAVERSE, 0, 0, i, 0, 0, i + 1, r)) return MAX_TICKS;
            b += r.ticks;
            right.set(r.end);
        }
        return a - b;
    }

    private final PlayerSim sim;
    private final CalculationContext ctx;
    /** Whether the bot may sprint, and whether a straight traverse into an ascend keeps the sprint. */
    public boolean canSprint, sprintAscends;
    /** Diagnostics for this instance. */
    public long sims, reached, simTicks;
    /**
     * Phase 05B diagnostics (-Dostinato.simedge.profile, off by default): sims, reached, ticks and nanoseconds by kind,
     * and how many sims repeated an earlier one's exact input (what a memo of {@link #run} would have saved). Counting
     * only; no result changes.
     */
    public static final boolean PROFILE = Boolean.getBoolean("ostinato.simedge.profile");
    public final long[] kindSims = new long[3], kindReached = new long[3], kindTicks = new long[3], kindNs = new long[3];
    public long dupExact;
    private it.unimi.dsi.fastutil.longs.LongOpenHashSet seen;
    /** Diagnostics: when set, {@link #run} appends every simulated tick's x, y, z here. Null in the search. */
    public it.unimi.dsi.fastutil.doubles.DoubleArrayList trail;

    public SimEdge(PlayerSim.World world, CalculationContext ctx) {
        this.sim = new PlayerSim(world);
        this.ctx = ctx;
        if (ctx != null) {
            canSprint = ctx.canSprint;
        }
    }

    /** The family a move belongs to here, or {@link #NONE} when the sim does not price it. */
    public static int kind(Moves m) {
        return switch (m.family()) {
            case "TRAVERSE" -> TRAVERSE;
            case "DIAGONAL" -> DIAGONAL;
            case "ASCEND" -> ASCEND;
            default -> NONE;
        };
    }

    /** The motion-class slot of a body (0 rest, 1..64 heading x band x air, 65 unknown). */
    public static final int SLOTS = 66, UNKNOWN_SLOT = 65, SECTORS = 16;

    public static int slot(Body b) {
        if (!b.known) return UNKNOWN_SLOT;
        double sp = b.speed();
        if (sp < REST) return 0;
        double a = Math.atan2(b.vz, b.vx);
        int sector = Math.floorMod((int) Math.round(a / (2 * Math.PI) * SECTORS), SECTORS);
        int band = sp < SPRINT_BAND ? 0 : 1;
        int air = b.onGround ? 0 : 1;
        return 1 + (sector * 2 + band) * 2 + air;
    }

    /**
     * Whether this edge is pure locomotion the sim can price: cells free to walk through, a floor at the destination,
     * no fluid, no ladder, no magma (the executor sneaks there).
     */
    public boolean eligible(int kind, int x, int y, int z, int dx, int dy, int dz) {
        if (kind == NONE || ctx == null) return false;
        if (kind == DIAGONAL && dy != y) return false;
        BlockState srcDown = ctx.get(x, y - 1, z);
        if (srcDown.is(Blocks.MAGMA_BLOCK) || MovementHelper.isClimbable(srcDown.getBlock()) || MovementHelper.isWater(srcDown)) return false;
        if (!clear(x, y, z) || !clear(dx, dy, dz)) return false;
        if (!MovementHelper.canWalkOn(ctx, dx, dy - 1, dz)) return false;
        if (kind == ASCEND && !free(x, y + 2, z)) return false; // the jump needs head room over the source
        return true;
    }

    /** A cell next to the source an overshooting or corner-cutting body may cross: walkable, dry, with a floor. */
    private boolean safe(int x, int y, int z) {
        return ctx == null || clear(x, y, z) && MovementHelper.canWalkOn(ctx, x, y - 1, z);
    }

    private boolean clear(int x, int y, int z) {
        return free(x, y, z) && free(x, y + 1, z) && !MovementHelper.isWater(ctx.get(x, y, z)) && !MovementHelper.isWater(ctx.get(x, y + 1, z));
    }

    private boolean free(int x, int y, int z) {
        return MovementHelper.fullyPassable(ctx, x, y, z);
    }

    /** MovementAscend.headBonkClear: the four cells beside the source two up can be walked through. */
    private boolean headBonkClear(int x, int y, int z) {
        return ctx == null || MovementHelper.canWalkThrough(ctx, x + 1, y + 2, z) && MovementHelper.canWalkThrough(ctx, x - 1, y + 2, z)
                && MovementHelper.canWalkThrough(ctx, x, y + 2, z + 1) && MovementHelper.canWalkThrough(ctx, x, y + 2, z - 1);
    }

    /** A body at the source centre at steady speed toward the destination (the legacy assumption). */
    void steadyBody(Body b, int x, int y, int z, int ddx, int ddz) {
        double len = Math.hypot(ddx, ddz);
        double sp = canSprint ? SPRINT_SPEED : WALK_SPEED;
        b.known = true;
        b.x = x + 0.5;
        b.y = y;
        b.z = z + 0.5;
        b.vx = ddx / len * sp;
        b.vz = ddz / len * sp;
        b.vy = STEADY_VY;
        b.yaw = (float) Math.toDegrees(Math.atan2(-ddx, ddz));
        b.onGround = true;
        b.sprinting = canSprint;
        b.jumpTicks = 0;
        b.lastKind = NONE;
    }

    private final Body start = new Body();

    /**
     * Simulates one edge from {@code from} (in or at the source cell) to the destination cell. True when the body
     * entered the destination at its height within {@link #MAX_TICKS}, with the prediction in {@code out}.
     */
    public boolean run(Body from, int kind, int x, int y, int z, int dx, int dy, int dz, Result out) {
        if (!PROFILE) {
            return run0(from, kind, x, y, z, dx, dy, dz, out);
        }
        if (seen == null) seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        if (!seen.add(inputKey(from, kind, x, y, z, dx, dy, dz))) dupExact++;
        long t0 = System.nanoTime();
        boolean ok = run0(from, kind, x, y, z, dx, dy, dz, out);
        kindNs[kind] += System.nanoTime() - t0;
        kindSims[kind]++;
        if (ok) {
            kindReached[kind]++;
            kindTicks[kind] += out.ticks;
        }
        return ok;
    }

    /** A 64-bit hash of everything {@link #run0} reads (profile only). */
    private long inputKey(Body b, int kind, int x, int y, int z, int dx, int dy, int dz) {
        long h = kind * 31L + (canSprint ? 7 : 0) + (sprintAscends ? 13 : 0);
        h = h * 0x9E3779B97F4A7C15L + baritone.api.utils.BetterBlockPos.longHash(x, y, z);
        h = h * 0x9E3779B97F4A7C15L + baritone.api.utils.BetterBlockPos.longHash(dx, dy, dz);
        if (b.known) {
            h = h * 0x9E3779B97F4A7C15L + Double.doubleToLongBits(b.x);
            h = h * 0x9E3779B97F4A7C15L + Double.doubleToLongBits(b.y);
            h = h * 0x9E3779B97F4A7C15L + Double.doubleToLongBits(b.z);
            h = h * 0x9E3779B97F4A7C15L + Double.doubleToLongBits(b.vx);
            h = h * 0x9E3779B97F4A7C15L + Double.doubleToLongBits(b.vy);
            h = h * 0x9E3779B97F4A7C15L + Double.doubleToLongBits(b.vz);
            h = h * 0x9E3779B97F4A7C15L + Float.floatToIntBits(b.yaw);
            h = h * 0x9E3779B97F4A7C15L + (b.onGround ? 1 : 0) + (b.sprinting ? 2 : 0) + b.jumpTicks * 4L;
            h = h * 0x9E3779B97F4A7C15L + b.lastKind * 1009L + b.lastDx * 31L + b.lastDz;
        } else {
            h = h * 0x9E3779B97F4A7C15L + 0x5bd1e995L;
        }
        return h ^ h >>> 29;
    }

    private boolean run0(Body from, int kind, int x, int y, int z, int dx, int dy, int dz, Result out) {
        sims++;
        int ddx = dx - x, ddz = dz - z;
        if (from.known) {
            start.set(from);
        } else {
            steadyBody(start, x, y, z, ddx, ddz);
        }
        start.into(sim);
        float yaw = start.yaw;
        boolean sprint = canSprint;
        if (kind == ASCEND) {
            // PathExecutor.sprintableAscend: only straight on from a traverse the same way, and only with sprintAscends
            sprint = canSprint && sprintAscends && start.lastKind == TRAVERSE && start.lastDx == ddx && start.lastDz == ddz;
            if (!sprint) sim.sprinting = false; // the executor lets go of the sprint (PathExecutor setSprinting(false))
        }
        boolean headClear = kind == ASCEND && headBonkClear(x, y, z);
        double tx = dx + 0.5, tz = dz + 0.5;
        int minY = Math.min(y, dy);
        double half = from.known ? 0 : 0.5 * Math.hypot(ddx, ddz) / (canSprint ? SPRINT_DISP : WALK_DISP);
        out.ticks = 0;
        out.air = 0;
        out.drift = 0;
        out.food = 0;
        for (int t = 1; t <= MAX_TICKS; t++) {
            float want = (float) Math.toDegrees(Math.atan2(-(tx - sim.x), tz - sim.z));
            float turn = wrap(want - yaw);
            yaw += Math.max(-YAW_CAP, Math.min(YAW_CAP, turn));
            boolean jump = false;
            if (kind == ASCEND && sim.onGround && sim.y < dy - 1e-3) {
                // MovementAscend.updateState: no jump with lateral motion over 0.1; at once when the head is clear,
                // else only within 1.2 of the destination centre and 0.2 to the side
                double lateral = ddx != 0 ? sim.vz : sim.vx;
                double flat = Math.abs(ddx) * Math.abs(tx - sim.x) + Math.abs(ddz) * Math.abs(tz - sim.z);
                double side = Math.abs(ddz) * Math.abs(tx - sim.x) + Math.abs(ddx) * Math.abs(tz - sim.z);
                jump = Math.abs(lateral) <= 0.1 && (headClear || flat <= 1.2 && side <= 0.2);
            }
            PredictionTolerance.Mode mode = PredictionTolerance.classify(sim, true, sim.sprinting || sprint);
            boolean wasGround = sim.onGround;
            double px = sim.x, pz = sim.z;
            sim.tick(yaw, 1, 0, sprint, jump);
            if (trail != null) {
                trail.add(sim.x);
                trail.add(sim.y);
                trail.add(sim.z);
            }
            out.ticks = t;
            out.drift += PredictionTolerance.MEASURED.oneStep(mode);
            if (!sim.onGround) out.air++;
            if (sim.sprinting) out.food += FOOD_SPRINT_PER_BLOCK * Math.hypot(sim.x - px, sim.z - pz);
            if (jump && wasGround && !sim.onGround && sim.vy > 0) out.food += sim.sprinting ? FOOD_SPRINT_JUMP : FOOD_JUMP;
            int cx = PlayerSim.floor(sim.x), cz = PlayerSim.floor(sim.z);
            if (sim.y < minY - 0.6) break; // fell off
            if (cx == dx && cz == dz && sim.y >= dy - 0.01 && sim.y < dy + 0.6) {
                out.cost = t + half;
                out.end.from(sim, yaw);
                out.end.lastKind = kind;
                out.end.lastDx = ddx;
                out.end.lastDz = ddz;
                reached++;
                simTicks += t;
                return true;
            }
            if (!(cx == x && cz == z) && !(cx == dx && cz == dz) && !(Math.abs(cx - x) <= 1 && Math.abs(cz - z) <= 1 && safe(cx, minY, cz))) {
                break; // left the source's neighbourhood (an overshoot is allowed onto safe ground): the executor would be off the path
            }
        }
        return false;
    }

    private static float wrap(float deg) {
        deg %= 360f;
        if (deg >= 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    /** One link of a priced path, for the live harness and the benchmark. */
    public record Priced(double legacy, double sim, boolean simulated, int ticks, int air, double drift, double food) {}

    /**
     * Prices a cell sequence both ways: the legacy cost of the cheapest move between each pair, and the chained sim
     * from {@code first} (or unknown when null), falling back to the legacy cost (and an unknown body) where the sim
     * does not cover the link. Used for planning accuracy on the same path, whichever search chose it.
     */
    public List<Priced> price(List<int[]> cells, Body first) {
        List<Priced> out = new ArrayList<>();
        Body body = new Body();
        if (first == null) body.unknown();
        else body.set(first);
        Result r = new Result();
        baritone.utils.pathing.MutableMoveResult res = new baritone.utils.pathing.MutableMoveResult();
        for (int i = 0; i + 1 < cells.size(); i++) {
            int[] a = cells.get(i), b = cells.get(i + 1);
            double legacy = baritone.api.pathing.movement.ActionCosts.COST_INF;
            Moves used = null;
            for (Moves m : Moves.all()) {
                if (!m.dynamicXZ && (a[0] + m.xOffset != b[0] || a[2] + m.zOffset != b[2])) continue;
                if (!m.dynamicY && a[1] + m.yOffset != b[1]) continue;
                res.reset();
                try {
                    m.apply(ctx, a[0], a[1], a[2], res);
                } catch (RuntimeException ex) {
                    continue;
                }
                if (res.x == b[0] && res.y == b[1] && res.z == b[2] && res.cost < legacy) {
                    legacy = res.cost;
                    used = m;
                }
            }
            int k = used == null ? NONE : kind(used);
            if (k != NONE && eligible(k, a[0], a[1], a[2], b[0], b[1], b[2]) && run(body, k, a[0], a[1], a[2], b[0], b[1], b[2], r)) {
                out.add(new Priced(legacy, r.cost, true, r.ticks, r.air, r.drift, r.food));
                body.set(r.end);
            } else {
                out.add(new Priced(legacy, legacy, false, 0, 0, 0, 0));
                body.unknown();
            }
        }
        return out;
    }
}
