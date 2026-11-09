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

package baritone.pathing.physics;

import baritone.api.utils.input.Input;
import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.unified.UnifiedExecutor;
import baritone.utils.InputArbiter;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ostinato 2.0 Phase 06: the physics driver's tick, legacy or unified, run headless against a block world.
 * <p>
 * Follows {@link PhysicsTravel}'s tick in order: the cancel stop, the lease before planning, the drift check, the one
 * correction ({@link CorrectionPolicy}), the replan, the prediction, the keys. Left out, because they need a game: the
 * path's lookahead and positions (one goal, position 0 throughout), the aim processor's turn gate, the look behaviour,
 * the native search. The "game" ticks a second {@link PlayerSim} from the keys as they stand after every press, in a
 * truth world that may be slipperier than the planner's model, and may shove it mid-tick (knockback lands after the
 * driver has predicted). Tier S: a copy of the loop, not the loop.
 * <p>
 * The legacy arm has no executor. A cancel is refused every tick the physics driver drives, as PathExecutor's
 * safe-to-cancel return says, so a legacy cancel waits for the run to end.
 */
final class UnifiedHarness {

    /** PhysicsTravel's search budget. */
    static final int NODE_BUDGET = 1500;
    /** Diagnostics only (the bench never sets these): the budget {@link #run} plans with, and whether it keeps every tick's state. */
    static int nodeBudget = NODE_BUDGET;
    static boolean trace;
    /** PhysicsTravel's goal radius. */
    static final double RADIUS = 0.35;
    /** The path object the lease is held for. One path a run. */
    private static final Object PATH = new Object();

    private UnifiedHarness() {}

    /** Solid unit blocks, one slipperiness for all. Two worlds can share the blocks and differ in slip. */
    static final class Blocks implements PlayerSim.World {
        final Set<Long> solid;
        final float slip;

        Blocks() {
            this(new HashSet<>(), 0.6f);
        }

        private Blocks(Set<Long> solid, float slip) {
            this.solid = solid;
            this.slip = slip;
        }

        /** The same blocks with another slipperiness. */
        Blocks slip(float s) {
            return new Blocks(solid, s);
        }

        Blocks add(int x, int y, int z) {
            solid.add(key(x, y, z));
            return this;
        }

        /** Ground at y -1 over the rectangle, both ends included. */
        Blocks ground(int x0, int x1, int z0, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    add(x, -1, z);
                }
            }
            return this;
        }

        private static long key(int x, int y, int z) {
            return ((long) (x + 512) << 20) | ((long) (y + 512) << 10) | (z + 512);
        }

        @Override
        public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
            for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++) {
                for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++) {
                    for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++) {
                        if (solid.contains(key(x, y, z))) {
                            out.add(new double[]{x, y, z, x + 1, y + 1, z + 1});
                        }
                    }
                }
            }
        }

        @Override
        public float slipperiness(int x, int y, int z) {
            return slip;
        }
    }

    /** The key map: what is forced down. */
    static final class FakeKeys implements UnifiedExecutor.Keys {
        final EnumSet<Input> down = EnumSet.noneOf(Input.class);

        @Override
        public boolean forcedRaw(Input input) {
            return down.contains(input);
        }

        @Override
        public boolean analogForced() {
            return false;
        }

        @Override
        public void releaseRaw(Input input) {
            down.remove(input);
        }
    }

    /** A start, a goal, a world, and what goes wrong on the way. */
    static final class Scenario {
        final String name;
        final Blocks model;
        Blocks truth;
        final double sx, sz, gx, gy, gz;
        int maxTicks = 200;
        int kickTick = -1;
        double kickVx, kickVz;
        /** Kick on the first tick the player is at or past this z instead, if not NaN. */
        double kickAtZ = Double.NaN;
        int cancelTick = -1;
        boolean cancelAfterJump;
        Input preGate, conflict;
        int pressFrom = 1, pressTo = Integer.MAX_VALUE, pressEvery = 1;

        Scenario(String name, Blocks model, double sx, double sz, double gx, double gz) {
            this.name = name;
            this.model = model;
            this.truth = model;
            this.sx = sx;
            this.sz = sz;
            this.gx = gx;
            this.gy = 0;
            this.gz = gz;
        }

        /** The game's ground is this slippery; the planner still thinks 0.6. */
        Scenario truthSlip(float slip) {
            truth = model.slip(slip);
            return this;
        }

        /** Shove the player on tick {@code t}, after the driver predicted. */
        Scenario kick(int t, double vx, double vz) {
            kickTick = t;
            kickVx = vx;
            kickVz = vz;
            return this;
        }

        /** Shove the player on the first tick it starts at or past {@code z}, after the driver predicted. */
        Scenario kickAtZ(double z, double vx, double vz) {
            kickAtZ = z;
            kickVx = vx;
            kickVz = vz;
            return this;
        }

        /** Ask to cancel every tick from {@code t}. */
        Scenario cancelAt(int t) {
            cancelTick = t;
            return this;
        }

        /** Ask to cancel every tick from the tick after the first jump press. */
        Scenario cancelAfterJump() {
            cancelAfterJump = true;
            return this;
        }

        /** Another behaviour presses {@code before} ahead of the driver and {@code after} once it has written. */
        Scenario presses(Input before, Input after, int from, int to) {
            preGate = before;
            conflict = after;
            pressFrom = from;
            pressTo = to;
            return this;
        }

        boolean pressing(int t) {
            return t >= pressFrom && t <= pressTo && (t - pressFrom) % pressEvery == 0;
        }
    }

    enum End { ARRIVED, CANCEL_STOP, EMPTY_PLAN, REFUSED, TIMEOUT }

    /** What one run did. */
    static final class Result {
        final String name;
        final boolean unified;
        final UnifiedExecutor ux;
        final FakeKeys keys;
        End end;
        int ticks;
        int plans, driftReplans;
        long planNanos;
        /** Phase 06A: time in the deeper retry search, in all (also counted in {@link #planNanos}) and the longest one. */
        long retryNanos, retryNanosMax;
        /** Phase 06A: the most nodes one retry popped, or -1 if none ran. */
        int retryExpansionsMax = -1;
        final List<Long> tickNanos = new ArrayList<>();
        int jumps, sprints, collidedTicks;
        /** The tick the shove landed, or -1. */
        int kickedAt = -1;
        double maxSpeed;
        /** First tick a cancel was asked for, or -1. */
        int cancelFrom = -1;
        int cancelRefusals;
        /** Ticks from the first cancel request to the stop (unified) or to the end of the run (legacy); -1 if none. */
        int cancelLatency = -1;
        /** Presses ahead of the driver that reached the key map. */
        int preLanded;
        /** Presses after the driver's write that reached the key map. */
        int conflictLanded;
        boolean onGroundAtEnd;
        double endX, endY, endZ;
        /** With {@link #trace}: the player at the start of every tick, tick 1 first. */
        final List<PlayerSim> states = new ArrayList<>();
        /** With {@link #trace}: the player when a plan came back empty. */
        PlayerSim failState;

        Result(String name, boolean unified, UnifiedExecutor ux, FakeKeys keys) {
            this.name = name;
            this.unified = unified;
            this.ux = ux;
            this.keys = keys;
        }

        long count(UnifiedExecutor.Counter c) {
            return ux == null ? 0 : ux.count(c);
        }

        @Override
        public String toString() {
            StringBuilder b = new StringBuilder();
            b.append(name).append(' ').append(unified ? "unified" : "legacy").append(' ').append(end).append(" ticks=").append(ticks)
                    .append(" plans=").append(plans).append(" driftReplans=").append(driftReplans)
                    .append(" kickedAt=").append(kickedAt).append(" jumps=").append(jumps).append(" sprints=").append(sprints).append(" collided=").append(collidedTicks)
                    .append(" cancelLatency=").append(cancelLatency).append(" preLanded=").append(preLanded)
                    .append(" conflictLanded=").append(conflictLanded).append(" keysDown=").append(keys.down);
            if (ux != null) {
                b.append(" state=").append(ux.state()).append('/').append(ux.lastCause());
                for (UnifiedExecutor.Counter c : UnifiedExecutor.Counter.values()) {
                    long v = ux.count(c);
                    if (v != 0) {
                        b.append(' ').append(c).append('=').append(v);
                    }
                }
            }
            return b.toString();
        }
    }

    /** A player standing at ({@code x}, 0, {@code z}) in {@code w}. */
    static PlayerSim standing(PlayerSim.World w, double x, double z) {
        PlayerSim s = new PlayerSim(w);
        s.x = x;
        s.y = 0;
        s.z = z;
        for (int i = 0; i < 3; i++) {
            s.tick(0, false, false, false);
        }
        if (!s.onGround) {
            throw new IllegalStateException("no ground at " + x + ", " + z);
        }
        return s;
    }

    /** A press from a behaviour other than the driver, through the gate when there is one. */
    private static boolean press(UnifiedExecutor ux, FakeKeys keys, Input input) {
        if (ux != null && ux.refusesPress(input)) {
            return false;
        }
        keys.down.add(input);
        return true;
    }

    static Result run(Scenario s, boolean unified) {
        FakeKeys keys = new FakeKeys();
        UnifiedExecutor ux = unified ? new UnifiedExecutor(new InputArbiter(), keys) : null;
        Result r = new Result(s.name, unified, ux, keys);
        PlayerSim real = standing(s.truth, s.sx, s.sz);
        PlayerSim predicted = new PlayerSim(s.model);
        PlayerSim scratch = new PlayerSim(s.model);
        PhysicsPathfinder finder = new PhysicsPathfinder(s.model, nodeBudget);
        PhysicsPathfinder retryFinder = null;
        List<PhysicsPathfinder.Action> plan = null;
        int step = 0, corrections = 0;
        int cancelFrom = s.cancelTick;
        boolean kicked = false;
        for (int t = 1; t <= s.maxTicks; t++) {
            if (trace) {
                r.states.add(new PlayerSim(s.truth).copyFrom(real));
            }
            if (ux != null) {
                ux.beginTick();
            }
            // Behaviours that run ahead of the driver: the gate is open, so these land, and the driver's clear lifts them.
            if (s.preGate != null && s.pressing(t) && press(ux, keys, s.preGate)) {
                r.preLanded++;
            }
            if (cancelFrom < 0 && s.cancelAfterJump && r.jumps > 0) {
                cancelFrom = t;
            }
            if (cancelFrom > 0 && t >= cancelFrom) {
                // PathExecutor refuses a cancel while the physics driver drives.
                if (r.cancelFrom < 0) {
                    r.cancelFrom = t;
                }
                r.cancelRefusals++;
                if (ux != null) {
                    ux.cancelRefused();
                }
            }

            long t0 = System.nanoTime();
            if (ux != null && ux.cancelDue(real.onGround)) {
                keys.down.clear();
                ux.stopForCancel();
                r.tickNanos.add(System.nanoTime() - t0);
                r.cancelLatency = t - r.cancelFrom;
                return finish(r, End.CANCEL_STOP, t, real);
            }
            if (ux != null && !ux.beginWrite(PATH, 0)) {
                return finish(r, End.REFUSED, t, real);
            }
            boolean drifted = plan != null && step > 0 && PhysicsTravel.drifted(predicted, real, null, null);
            if (ux != null) {
                // PhysicsTravel.checkDivergence; the harness never retargets.
                boolean predictedTick = plan != null && step > 0;
                CorrectionPolicy.Verdict v = CorrectionPolicy.judge(predictedTick, drifted, corrections,
                        predictedTick && step >= plan.size(), false);
                if (v == CorrectionPolicy.Verdict.REPLAY) {
                    corrections++;
                    v = CorrectionPolicy.replayed(CorrectionPolicy.replayReaches(scratch, real, plan, step, s.gx, s.gy, s.gz, RADIUS));
                }
                v.count(ux);
                drifted = v.replan;
            }
            if (plan == null || step >= plan.size() || drifted) {
                step = 0;
                corrections = 0;
                r.plans++;
                if (drifted) {
                    r.driftReplans++;
                }
                long p0 = System.nanoTime();
                plan = finder.plan(real, s.gx, s.gy, s.gz, RADIUS);
                r.planNanos += System.nanoTime() - p0;
                if ((plan == null || plan.isEmpty()) && ux != null && drifted) {
                    // PhysicsTravel's Phase 06A retry: one deeper search after a correction-triggered replan found nothing.
                    ux.bump(UnifiedExecutor.Counter.CORRECTION_RETRIES);
                    if (retryFinder == null) {
                        retryFinder = new PhysicsPathfinder(s.model, CorrectionPolicy.RETRY_NODES);
                    }
                    long q0 = System.nanoTime();
                    plan = retryFinder.plan(real, s.gx, s.gy, s.gz, RADIUS);
                    long q = System.nanoTime() - q0;
                    r.planNanos += q;
                    r.retryNanos += q;
                    r.retryNanosMax = Math.max(r.retryNanosMax, q);
                    r.retryExpansionsMax = Math.max(r.retryExpansionsMax, retryFinder.lastExpansions());
                    if (plan != null && !plan.isEmpty()) {
                        ux.bump(UnifiedExecutor.Counter.CORRECTION_RETRY_PLANS);
                    }
                }
                if (plan == null || plan.isEmpty()) {
                    if (trace) {
                        r.failState = new PlayerSim(s.truth).copyFrom(real);
                    }
                    if (ux != null) {
                        ux.fail(UnifiedExecutor.Cause.EMPTY_PLAN);
                        ux.handback(UnifiedExecutor.Cause.DROP, 0);
                    }
                    r.tickNanos.add(System.nanoTime() - t0);
                    return finish(r, End.EMPTY_PLAN, t, real);
                }
            }
            PhysicsPathfinder.Action a = plan.get(step++);
            predicted.copyFrom(real);
            predicted.tick(a.yaw, a.forward, a.sprint, a.jump);
            keys.down.clear();
            if (a.forward) {
                keys.down.add(Input.MOVE_FORWARD);
            }
            if (a.sprint) {
                keys.down.add(Input.SPRINT);
            }
            if (a.jump) {
                keys.down.add(Input.JUMP);
            }
            if (ux != null) {
                ux.endWrite(a.forward, a.sprint, a.jump);
            }
            r.tickNanos.add(System.nanoTime() - t0);
            if (a.jump) {
                r.jumps++;
            }
            if (a.sprint) {
                r.sprints++;
            }

            // Behaviours that run after the driver: the gate refuses these while the lease is held.
            if (s.conflict != null && s.pressing(t) && press(ux, keys, s.conflict)) {
                r.conflictLanded++;
            }
            // The game's tick, from the keys as they stand. A shove lands after the driver predicted.
            if (!kicked && (t == s.kickTick || !Double.isNaN(s.kickAtZ) && real.z >= s.kickAtZ)) {
                kicked = true;
                r.kickedAt = t;
                real.vx += s.kickVx;
                real.vz += s.kickVz;
            }
            int in = (keys.down.contains(Input.MOVE_FORWARD) ? 1 : 0) - (keys.down.contains(Input.MOVE_BACK) ? 1 : 0);
            int strafe = (keys.down.contains(Input.MOVE_LEFT) ? 1 : 0) - (keys.down.contains(Input.MOVE_RIGHT) ? 1 : 0);
            real.tick(a.yaw, in, strafe, keys.down.contains(Input.SPRINT), keys.down.contains(Input.JUMP));
            if (real.collidedH) {
                r.collidedTicks++;
            }
            r.maxSpeed = Math.max(r.maxSpeed, Math.sqrt(real.vx * real.vx + real.vz * real.vz));
            if (ux != null) {
                ux.endTick(true);
            }
            double dx = real.x - s.gx, dy = real.y - s.gy, dz = real.z - s.gz;
            if (real.onGround && dx * dx + dy * dy + dz * dz <= RADIUS * RADIUS) {
                // PathExecutor clears the keys when the segment finishes, then tells the executor.
                keys.down.clear();
                if (ux != null) {
                    ux.segmentEnded(true);
                }
                if (r.cancelFrom > 0 && r.cancelLatency < 0) {
                    r.cancelLatency = t - r.cancelFrom;
                }
                return finish(r, End.ARRIVED, t, real);
            }
        }
        return finish(r, End.TIMEOUT, s.maxTicks, real);
    }

    private static Result finish(Result r, End end, int t, PlayerSim real) {
        r.end = end;
        r.ticks = t;
        r.onGroundAtEnd = real.onGround;
        r.endX = real.x;
        r.endY = real.y;
        r.endZ = real.z;
        return r;
    }

    /** The {@code q} quantile (0..1) of {@code xs}, nearest rank. */
    static long quantile(List<Long> xs, double q) {
        if (xs.isEmpty()) {
            return 0;
        }
        List<Long> s = new ArrayList<>(xs);
        s.sort(null);
        int i = (int) Math.ceil(q * s.size()) - 1;
        return s.get(Math.max(0, Math.min(s.size() - 1, i)));
    }
}
