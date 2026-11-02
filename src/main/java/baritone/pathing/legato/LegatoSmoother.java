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

package baritone.pathing.legato;

import baritone.api.pathing.movement.ActionCosts;

/**
 * Ostinato Legato: shortcuts a finished path. From each position, the farthest later position of the same flat
 * walking run is tried first: the straight line there, cut into pieces of at most {@link LegatoFeasibility#MAX_SPAN}
 * blocks whose ends are the cells nearest the line, must be walkable piece by piece and cheaper than the moves it
 * replaces once the turns on both are counted. Moves that are not plain flat walking (jumps, gaps, drops, climbs,
 * mining, placing, swimming, doors) are never inside a shortcut, so they stay exactly as planned.
 * <p>
 * Works over plain positions and two callbacks, so it is the same code headless and in game. Bounded: at most
 * {@link #MAX_EVALUATIONS} line checks per path, after which the rest of the path is kept as planned.
 */
public final class LegatoSmoother {

    /** Most moves one shortcut may replace. */
    public static final int MAX_MOVES = 256;
    /** A shortcut must save at least this many ticks, so near-ties keep the planned moves. */
    public static final double MIN_SAVING = 0.3;
    /** Line checks one path may spend. */
    public static final int MAX_EVALUATIONS = 4000;
    /** The look turns at most this many degrees a tick. */
    static final double TURN_RATE = 32;

    private LegatoSmoother() {}

    /** The path being shortcut: positions 0..moves(), move i goes from position i to position i + 1. */
    public interface Route {
        int moves();

        int x(int position);

        int y(int position);

        int z(int position);

        /** Is move i a plain flat walking move with nothing to break or place? */
        boolean eligible(int move);

        double cost(int move);
    }

    /** Cost of the straight line between two cells, or {@link ActionCosts#COST_INF} when it cannot be walked. */
    @FunctionalInterface
    public interface Lines {
        double cost(int x0, int y, int z0, int x1, int z1);
    }

    /** The shortcut path: {@link #size} points, and per step between two points the planned move kept, or -1 for a line. */
    public static final class Plan {
        public int size;
        public int[] x;
        public int[] y;
        public int[] z;
        public int[] move;
        /** Original moves replaced by lines, lines made, line checks spent. */
        public int replaced;
        public int lines;
        public int evaluations;
        public boolean budgetSpent;

        Plan(int capacity) {
            x = new int[capacity];
            y = new int[capacity];
            z = new int[capacity];
            move = new int[capacity];
        }

        void add(int px, int py, int pz, int step) {
            if (size == x.length) {
                int c = size * 2;
                x = java.util.Arrays.copyOf(x, c);
                y = java.util.Arrays.copyOf(y, c);
                z = java.util.Arrays.copyOf(z, c);
                move = java.util.Arrays.copyOf(move, c);
            }
            x[size] = px;
            y[size] = py;
            z[size] = pz;
            if (size > 0) {
                move[size - 1] = step;
            }
            size++;
        }

        /** True when nothing was shortcut. */
        public boolean unchanged() {
            return lines == 0;
        }
    }

    /** Number of pieces a line is cut into. */
    static int pieces(int dx, int dz) {
        int m = Math.max(Math.abs(dx), Math.abs(dz));
        return Math.max(1, (m + LegatoFeasibility.MAX_SPAN - 1) / LegatoFeasibility.MAX_SPAN);
    }

    /** Cell k of {@code m} along a line of length d: the nearest cell to the true line. */
    static int along(int from, int d, int k, int m) {
        return from + (int) Math.round(d * (double) k / m);
    }

    public static Plan smooth(Route route, Lines lines) {
        int n = route.moves();
        Plan plan = new Plan(n + 1);
        plan.add(route.x(0), route.y(0), route.z(0), -1);
        // prefix sums, so a candidate line is priced in constant time: cost of moves 0..k-1, and the turns at
        // positions 1..k-1 between them
        double[] costs = new double[n + 1];
        double[] turns = new double[n + 1];
        for (int k = 0; k < n; k++) {
            costs[k + 1] = costs[k] + route.cost(k);
            turns[k + 1] = turns[k] + (k == 0 ? 0 : turnTicks(route.x(k) - route.x(k - 1), route.z(k) - route.z(k - 1),
                    route.x(k + 1) - route.x(k), route.z(k + 1) - route.z(k)));
        }
        double inX = 0;
        double inZ = 0; // heading into the current position (0, 0: none yet)
        int i = 0;
        while (i < n) {
            int best = -1;
            if (route.eligible(i) && !plan.budgetSpent) {
                int far = i;
                while (far < n && far - i < MAX_MOVES && route.eligible(far) && route.y(far + 1) == route.y(i)) {
                    far++;
                }
                for (int j = far; j >= i + 2 && best < 0; j--) {
                    if (plan.evaluations >= MAX_EVALUATIONS) {
                        plan.budgetSpent = true;
                        break;
                    }
                    if (shortcut(route, lines, plan, costs, turns, i, j, inX, inZ)) {
                        best = j;
                    }
                }
            }
            if (best > 0) {
                int dx = route.x(best) - route.x(i);
                int dz = route.z(best) - route.z(i);
                int m = pieces(dx, dz);
                int px = route.x(i);
                int pz = route.z(i);
                for (int k = 1; k <= m; k++) {
                    int qx = along(route.x(i), dx, k, m);
                    int qz = along(route.z(i), dz, k, m);
                    plan.add(qx, route.y(i), qz, -1);
                    inX = qx - px;
                    inZ = qz - pz;
                    px = qx;
                    pz = qz;
                }
                plan.lines += m;
                plan.replaced += best - i;
                i = best;
            } else {
                plan.add(route.x(i + 1), route.y(i + 1), route.z(i + 1), i);
                inX = route.x(i + 1) - route.x(i);
                inZ = route.z(i + 1) - route.z(i);
                i++;
            }
        }
        return plan;
    }

    /** Is the line from position i to position j walkable and cheaper than moves i..j-1? */
    private static boolean shortcut(Route route, Lines lines, Plan plan, double[] costs, double[] turns,
                                    int i, int j, double inX, double inZ) {
        int n = route.moves();
        int x0 = route.x(i);
        int z0 = route.z(i);
        int y = route.y(i);
        int dx = route.x(j) - x0;
        int dz = route.z(j) - z0;
        if (dx == 0 && dz == 0) {
            return false;
        }
        double planned = costs[j] - costs[i];
        if (planned >= ActionCosts.COST_INF) {
            return false;
        }
        double outX = j < n ? route.x(j + 1) - route.x(j) : 0;
        double outZ = j < n ? route.z(j + 1) - route.z(j) : 0;
        planned += turnTicks(inX, inZ, route.x(i + 1) - x0, route.z(i + 1) - z0)
                + (turns[j] - turns[i + 1])
                + turnTicks(route.x(j) - route.x(j - 1), route.z(j) - route.z(j - 1), outX, outZ);
        // the geometry alone first: no world reads for a line that could not be cheaper even if open. The pieces
        // bend by under a degree where they round to cells, so the line turns only where it starts and ends
        double budget = planned - turnTicks(inX, inZ, dx, dz) - turnTicks(dx, dz, outX, outZ) - MIN_SAVING;
        if (budget <= 0 || Math.hypot(dx, dz) * ActionCosts.SPRINT_ONE_BLOCK_COST >= budget) {
            return false; // no line is faster than a sprint
        }
        int m = pieces(dx, dz);
        double cost = 0;
        int px = x0;
        int pz = z0;
        for (int k = 1; k <= m; k++) {
            int qx = along(x0, dx, k, m);
            int qz = along(z0, dz, k, m);
            plan.evaluations++;
            cost += lines.cost(px, y, pz, qx, qz);
            if (cost >= budget) {
                return false;
            }
            px = qx;
            pz = qz;
        }
        return true;
    }

    /**
     * Ticks a change of heading costs the walk. Up to a right angle the controller turns while it keeps running,
     * losing about a quarter tick per look step; past that it stops and turns first.
     */
    static double turnTicks(double ax, double az, double bx, double bz) {
        if ((ax == 0 && az == 0) || (bx == 0 && bz == 0)) {
            return 0;
        }
        double dot = ax * bx + az * bz;
        double cross = ax * bz - az * bx;
        double deg = Math.toDegrees(Math.abs(Math.atan2(cross, dot)));
        if (deg < 1e-6) {
            return 0;
        }
        if (deg <= 90) {
            return 0.25 * deg / TURN_RATE;
        }
        return 2 + Math.ceil((deg - 45) / TURN_RATE);
    }
}
