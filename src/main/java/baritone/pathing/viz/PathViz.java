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

package baritone.pathing.viz;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Ostinato: what the kinematic and physics movers decided, kept for the in-game path visualizations
 * ({@code baritone.utils.PathVizRenderer}). The controllers call the recorders here on the client tick; each one
 * returns at once when its render setting is off, so nothing is collected that will not be drawn. Every history is a
 * bounded {@link Ring} or a fixed array, reused tick to tick.
 * <ul>
 * <li>K1 corridor: the line the kinematic controller follows, how far along it the player is, the next node.</li>
 * <li>K2 rollouts: this tick's candidate plans, the chosen one and why each other one lost.</li>
 * <li>K3 jumps: template jump take-off, apex and landing; where the controller got stuck and handed back (M01).</li>
 * <li>P1 speed trail, P2 inputs and ghosts: one sample per physics-driven tick.</li>
 * <li>P3 search: the explored tree of the last plan, its predicted states, the actual ones and replans.</li>
 * </ul>
 */
public final class PathViz {

    public static final int MAX_LINE = 64;
    public static final int MAX_ROLLOUTS = 32;
    public static final int MAX_ROLLOUT_TICKS = 40;
    public static final int MAX_JUMPS = 24;
    public static final int MAX_STUCK = 8;
    public static final int MAX_TRAIL = 400;
    public static final int MAX_GHOSTS = 24;
    public static final int MAX_TREE_EDGES = 3000;
    public static final int MAX_PLAN = 200;
    public static final int MAX_REPLANS = 16;
    public static final int MAX_ARC = 48;
    public static final int MAX_HANDBACKS = 8;

    /** Rollout outcomes. */
    public static final String OK = "ok";

    // ---- toggles

    private final BooleanSupplier corridorOn, rolloutsOn, jumpsOn, speedOn, inputsOn, searchOn;
    private final LongSupplier clock;

    public PathViz(BooleanSupplier corridor, BooleanSupplier rollouts, BooleanSupplier jumps,
                   BooleanSupplier speed, BooleanSupplier inputs, BooleanSupplier search, LongSupplier clockMs) {
        this.corridorOn = corridor;
        this.rolloutsOn = rollouts;
        this.jumpsOn = jumps;
        this.speedOn = speed;
        this.inputsOn = inputs;
        this.searchOn = search;
        this.clock = clockMs;
    }

    private static volatile PathViz instance;

    /** The shared recorder, bound to the Baritone render settings on first use. */
    public static PathViz get() {
        PathViz v = instance;
        if (v == null) {
            synchronized (PathViz.class) {
                if (instance == null) {
                    baritone.api.Settings s = baritone.api.BaritoneAPI.getSettings();
                    instance = new PathViz(() -> s.renderKinCorridor.value, () -> s.renderKinRollouts.value,
                            () -> s.renderKinJumps.value, () -> s.renderPhysSpeedTrail.value, () -> s.renderPhysInputs.value,
                            () -> s.renderPhysSearch.value, System::currentTimeMillis);
                }
                v = instance;
            }
        }
        return v;
    }

    /** For tests: replace the shared recorder (null resets to the settings-bound one). */
    public static void set(PathViz v) {
        instance = v;
    }

    public boolean corridorOn() {
        return corridorOn.getAsBoolean();
    }

    public boolean rolloutsOn() {
        return rolloutsOn.getAsBoolean();
    }

    public boolean jumpsOn() {
        return jumpsOn.getAsBoolean();
    }

    public boolean speedOn() {
        return speedOn.getAsBoolean();
    }

    public boolean inputsOn() {
        return inputsOn.getAsBoolean();
    }

    public boolean searchOn() {
        return searchOn.getAsBoolean();
    }

    public boolean physOn() {
        return speedOn() || inputsOn() || searchOn();
    }

    public long now() {
        return clock.getAsLong();
    }

    // ================================================================== K1 corridor

    /** Line points as x, y, z, arc length. */
    public final double[] line = new double[MAX_LINE * 4];
    public int lineCount;
    /** Arc length the player has reached, and the corridor half width. */
    public double lineS, lineWidth;
    public long lineStamp;

    public void corridor(List<double[]> pts, double s, double width) {
        if (!corridorOn()) {
            return;
        }
        int n = Math.min(pts.size(), MAX_LINE);
        for (int i = 0; i < n; i++) {
            double[] p = pts.get(i);
            System.arraycopy(p, 0, line, i * 4, 4);
        }
        lineCount = n;
        lineS = s;
        lineWidth = width;
        lineStamp = now();
    }

    /** Index of the first line point ahead of the player, or -1. */
    public int nextNode() {
        for (int i = 0; i < lineCount; i++) {
            if (line[i * 4 + 3] > lineS + 0.05) {
                return i;
            }
        }
        return -1;
    }

    // ================================================================== K2 rollouts

    public static final class Rollout {
        public final float[] pts = new float[MAX_ROLLOUT_TICKS * 3];
        public int n;
        public boolean chosen;
        public double score;
        public String reason = OK;
        public float yawOffset;
        public int delay;

        public void point(double x, double y, double z) {
            if (n < MAX_ROLLOUT_TICKS) {
                pts[n * 3] = (float) x;
                pts[n * 3 + 1] = (float) y;
                pts[n * 3 + 2] = (float) z;
                n++;
            }
        }

        /** Rejected: scored out by a hard rule, not merely less progress. */
        public boolean rejected() {
            return !OK.equals(reason);
        }
    }

    private final Rollout[] rolloutPool = new Rollout[MAX_ROLLOUTS];
    public int rolloutCount;
    public long rolloutStamp;
    /** Reject reasons of the last tick, e.g. "exits corridor x3, hard landing x1"; for debug output. */
    public String rolloutSummary = "";

    {
        for (int i = 0; i < MAX_ROLLOUTS; i++) {
            rolloutPool[i] = new Rollout();
        }
    }

    public Rollout rollout(int i) {
        return rolloutPool[i];
    }

    /** Start a tick's fan; false when rollouts are not drawn. */
    public boolean beginRollouts() {
        rolloutCount = 0;
        if (!rolloutsOn()) {
            return false;
        }
        rolloutStamp = now();
        return true;
    }

    /** A fresh rollout slot, or null when off or the pool is full. */
    public Rollout startRollout(float yawOffset, int delay) {
        if (rolloutCount >= MAX_ROLLOUTS || !rolloutsOn()) {
            return null;
        }
        Rollout r = rolloutPool[rolloutCount++];
        r.n = 0;
        r.chosen = false;
        r.score = Double.NaN;
        r.reason = OK;
        r.yawOffset = yawOffset;
        r.delay = delay;
        return r;
    }

    /** Mark rollout {@code index} (as counted by {@link #rolloutCount} before it started) chosen, and build the summary. */
    public void chooseRollout(int index) {
        StringBuilder sb = new StringBuilder();
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (int i = 0; i < rolloutCount; i++) {
            Rollout r = rolloutPool[i];
            r.chosen = i == index;
            if (r.rejected()) {
                counts.merge(r.reason, 1, Integer::sum);
            }
        }
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.getKey()).append(" x").append(e.getValue());
        }
        rolloutSummary = sb.toString();
    }

    // ================================================================== K3 jumps and stuck

    public static final int JUMP = 0, CHAIN = 1, CLIMB = 2;

    public static final class JumpMark {
        public String label;
        public int kind;
        public double tx, ty, tz;
        public double ax, ay, az;
        public double lx = Double.NaN, ly, lz;
        public boolean landed, grabbed;
        public long stamp;
        /** The flown arc, one point per tick from take-off to landing. */
        public final float[] arc = new float[MAX_ARC * 3];
        public int arcN;
        Object owner;

        void arc(double x, double y, double z) {
            if (arcN < MAX_ARC) {
                arc[arcN * 3] = (float) x;
                arc[arcN * 3 + 1] = (float) y;
                arc[arcN * 3 + 2] = (float) z;
                arcN++;
            }
        }
    }

    public final Ring<JumpMark> jumps = new Ring<>(MAX_JUMPS, JumpMark::new);

    public void jumpTakeoff(Object owner, int kind, String label, double x, double y, double z) {
        if (!jumpsOn()) {
            return;
        }
        JumpMark m = jumps.add();
        m.owner = owner;
        m.kind = kind;
        m.label = label;
        m.tx = m.ax = x;
        m.ty = m.ay = y;
        m.tz = m.az = z;
        m.lx = Double.NaN;
        m.landed = m.grabbed = false;
        m.arcN = 0;
        m.arc(x, y, z);
        m.stamp = now();
    }

    private JumpMark open(Object owner) {
        JumpMark m = jumps.newest();
        return m != null && m.owner == owner && !m.landed ? m : null;
    }

    /** An airborne tick of the open jump: keeps the highest point. */
    public void jumpAir(Object owner, double x, double y, double z) {
        if (!jumpsOn()) {
            return;
        }
        JumpMark m = open(owner);
        if (m != null) {
            m.arc(x, y, z);
        }
        if (m != null && y > m.ay) {
            m.ax = x;
            m.ay = y;
            m.az = z;
        }
    }

    public void jumpLand(Object owner, double x, double y, double z, boolean grabbed) {
        if (!jumpsOn()) {
            return;
        }
        JumpMark m = open(owner);
        if (m != null) {
            m.arc(x, y, z);
            m.lx = x;
            m.ly = y;
            m.lz = z;
            m.landed = true;
            m.grabbed = grabbed;
            m.stamp = now();
        }
    }

    public static final class Mark {
        public double x, y, z;
        public String why;
        public long stamp;
    }

    public final Ring<Mark> stuck = new Ring<>(MAX_STUCK, Mark::new);

    /** Where travel handed back to Baritone; the corridor turns hand-back coloured for a moment after. */
    public final Ring<Mark> handbacks = new Ring<>(MAX_HANDBACKS, Mark::new);
    public long handbackStamp = Long.MIN_VALUE / 2;
    public boolean handbackStuck;

    public void handback(double x, double y, double z, String why) {
        if (!jumpsOn()) {
            return;
        }
        Mark m = handbacks.add();
        m.x = x;
        m.y = y;
        m.z = z;
        m.why = why;
        m.stamp = now();
        if (m.stamp - handbackStamp > 500) {
            handbackStuck = false; // a plain hand-back; a stuck one just before keeps the red
        }
        handbackStamp = m.stamp;
    }

    public void stuck(double x, double y, double z, String why) {
        if (!jumpsOn()) {
            return;
        }
        handbackStamp = now();
        handbackStuck = true;
        Mark m = stuck.add();
        m.x = x;
        m.y = y;
        m.z = z;
        m.why = why;
        m.stamp = now();
    }

    // ================================================================== P1/P2 per-tick samples

    public static final int IN_W = 1, IN_SPRINT = 2, IN_JUMP = 4, IN_LEFT = 8, IN_RIGHT = 16;

    public static final class Sample {
        public double x, y, z, hspeed;
        public float yaw;
        public int inputs;
        public long stamp;
    }

    public final Ring<Sample> trail = new Ring<>(MAX_TRAIL, Sample::new);

    public static final int GHOST_JUMP = 0, GHOST_APEX = 1, GHOST_LAND = 2, GHOST_GRAB = 3;

    public static final class Ghost {
        public double x, y, z;
        public int kind;
        public long stamp;
    }

    public final Ring<Ghost> ghosts = new Ring<>(MAX_GHOSTS, Ghost::new);
    private boolean lastGround = true;
    private double lastVy;

    /**
     * One physics-driven tick: the real position and velocity before the keys act, and the keys pressed.
     * A pause of over two seconds starts a new trail.
     */
    public void physTick(double x, double y, double z, double vx, double vy, double vz, boolean onGround,
                         boolean climbing, float yaw, int inputs) {
        boolean speed = speedOn(), in = inputsOn();
        if (!speed && !in) {
            return;
        }
        long t = now();
        Sample last = trail.newest();
        if (last != null && t - last.stamp > 2000) {
            trail.clear();
            ghosts.clear();
            lastGround = true;
        }
        Sample s = trail.add();
        s.x = x;
        s.y = y;
        s.z = z;
        s.hspeed = Math.sqrt(vx * vx + vz * vz);
        s.yaw = yaw;
        s.inputs = inputs;
        s.stamp = t;
        if (in) {
            if (onGround && (inputs & IN_JUMP) != 0) {
                ghost(GHOST_JUMP, x, y, z, t);
            } else if (!lastGround && climbing) {
                ghost(GHOST_GRAB, x, y, z, t);
            } else if (!onGround && lastVy > 0 && vy <= 0) {
                ghost(GHOST_APEX, x, y, z, t);
            } else if (!lastGround && onGround) {
                ghost(GHOST_LAND, x, y, z, t);
            }
        }
        lastGround = onGround || climbing;
        lastVy = vy;
    }

    private void ghost(int kind, double x, double y, double z, long t) {
        Ghost g = ghosts.add();
        g.kind = kind;
        g.x = x;
        g.y = y;
        g.z = z;
        g.stamp = t;
    }

    // ================================================================== P3 search tree, plan, actual

    /** Explored edges: parent x, y, z, child x, y, z, depth. */
    public final float[] tree = new float[MAX_TREE_EDGES * 7];
    public int treeCount, treeMaxDepth;
    public long treeStamp;
    private boolean treeOpen;

    /** Start recording a search; false (and nothing kept) when the search view is off. */
    public boolean beginTree() {
        treeOpen = searchOn();
        if (treeOpen) {
            treeCount = 0;
            treeMaxDepth = 1;
            treeStamp = now();
        }
        return treeOpen;
    }

    public void treeEdge(double px, double py, double pz, double cx, double cy, double cz, int depth) {
        if (!treeOpen || treeCount >= MAX_TREE_EDGES) {
            return;
        }
        int o = treeCount++ * 7;
        tree[o] = (float) px;
        tree[o + 1] = (float) py;
        tree[o + 2] = (float) pz;
        tree[o + 3] = (float) cx;
        tree[o + 4] = (float) cy;
        tree[o + 5] = (float) cz;
        tree[o + 6] = depth;
        treeMaxDepth = Math.max(treeMaxDepth, depth);
    }

    public void endTree() {
        treeOpen = false;
    }

    /** Predicted feet positions of the current plan, one per tick, and the actual ones reached so far. */
    public final double[] planned = new double[MAX_PLAN * 3];
    public final double[] actual = new double[MAX_PLAN * 3];
    public int plannedCount, actualCount;
    /** Distance at which a planned/actual link turns red. */
    public static final double ERROR_RED = 0.3;

    public final Ring<Mark> replans = new Ring<>(MAX_REPLANS, Mark::new);

    /** A new plan: drops the old prediction; {@code why} is null for the first plan of a stretch. */
    public void newPlan(double x, double y, double z, String why) {
        if (!searchOn()) {
            return;
        }
        plannedCount = 0;
        actualCount = 0;
        if (why != null) {
            Mark m = replans.add();
            m.x = x;
            m.y = y;
            m.z = z;
            m.why = why;
            m.stamp = now();
        }
    }

    public void plannedPoint(double x, double y, double z) {
        if (plannedCount >= MAX_PLAN) {
            return;
        }
        int o = plannedCount++ * 3;
        planned[o] = x;
        planned[o + 1] = y;
        planned[o + 2] = z;
    }

    /** The real position at plan step {@code actualCount}. */
    public void actualPoint(double x, double y, double z) {
        if (!searchOn() || actualCount >= MAX_PLAN) {
            return;
        }
        int o = actualCount++ * 3;
        actual[o] = x;
        actual[o + 1] = y;
        actual[o + 2] = z;
    }

    /** Distance between the actual and the planned position at step {@code i}, NaN if either is missing. */
    public double error(int i) {
        if (i >= actualCount || i >= plannedCount) {
            return Double.NaN;
        }
        double dx = actual[i * 3] - planned[i * 3], dy = actual[i * 3 + 1] - planned[i * 3 + 1], dz = actual[i * 3 + 2] - planned[i * 3 + 2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The path is over (at the goal, or cancelled): drop every mark of it so nothing lingers until it fades. */
    public void clear() {
        lineCount = 0;
        rolloutCount = 0;
        jumps.clear();
        stuck.clear();
        handbacks.clear();
        trail.clear();
        ghosts.clear();
        replans.clear();
        plannedCount = 0;
        actualCount = 0;
        if (!treeOpen) {
            treeCount = 0;
        }
    }
}
