/*
 * Ostinato Legato: the path shortcut pass, over plain positions.
 */
package baritone.pathing.legato;

import baritone.api.pathing.movement.ActionCosts;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class LegatoSmootherTest {

    static final double PER_BLOCK = ActionCosts.SPRINT_ONE_BLOCK_COST;

    /** A route of grid steps; a step flagged false is a jump, a climb or anything else that must stay. */
    static final class Grid implements LegatoSmoother.Route {
        final List<int[]> pos = new ArrayList<>();
        final List<Boolean> walk = new ArrayList<>();

        Grid(int x, int y, int z) {
            pos.add(new int[]{x, y, z});
        }

        Grid step(int dx, int dy, int dz, boolean plainWalk) {
            int[] p = pos.get(pos.size() - 1);
            pos.add(new int[]{p[0] + dx, p[1] + dy, p[2] + dz});
            walk.add(plainWalk);
            return this;
        }

        Grid walk(int dx, int dz, int times) {
            for (int i = 0; i < times; i++) {
                step(dx, 0, dz, true);
            }
            return this;
        }

        public int moves() { return walk.size(); }
        public int x(int p) { return pos.get(p)[0]; }
        public int y(int p) { return pos.get(p)[1]; }
        public int z(int p) { return pos.get(p)[2]; }
        public boolean eligible(int m) { return walk.get(m); }

        public double cost(int m) {
            int[] a = pos.get(m), b = pos.get(m + 1);
            return Math.hypot(b[0] - a[0], b[2] - a[2]) * PER_BLOCK + (walk.get(m) ? 0 : 10);
        }
    }

    static final LegatoSmoother.Lines OPEN = (x0, y, z0, x1, z1) -> Math.hypot(x1 - x0, z1 - z0) * PER_BLOCK;
    static final LegatoSmoother.Lines WALLED = (x0, y, z0, x1, z1) -> ActionCosts.COST_INF;

    static LegatoSmoother.Plan plan(Grid g, LegatoSmoother.Lines lines) {
        LegatoSmoother.Plan p = LegatoSmoother.smooth(g, lines);
        // the plan always runs from the route's start to its end, and kept moves keep their own ends
        assertEquals(g.x(0), p.x[0]);
        assertEquals(g.z(0), p.z[0]);
        assertEquals(g.x(g.moves()), p.x[p.size - 1]);
        assertEquals(g.z(g.moves()), p.z[p.size - 1]);
        for (int k = 0; k + 1 < p.size; k++) {
            int m = p.move[k];
            if (m >= 0) {
                assertEquals(g.x(m), p.x[k]);
                assertEquals(g.z(m), p.z[k]);
                assertEquals(g.x(m + 1), p.x[k + 1]);
                assertEquals(g.z(m + 1), p.z[k + 1]);
            } else {
                assertEquals(p.y[k], p.y[k + 1]);
                assertTrue(Math.abs(p.x[k + 1] - p.x[k]) <= LegatoFeasibility.MAX_SPAN);
                assertTrue(Math.abs(p.z[k + 1] - p.z[k]) <= LegatoFeasibility.MAX_SPAN);
            }
        }
        return p;
    }

    static void unchanged(Grid g, LegatoSmoother.Plan p) {
        assertTrue(p.unchanged());
        assertEquals(g.moves() + 1, p.size);
        for (int k = 0; k < g.moves(); k++) {
            assertEquals(k, p.move[k]);
        }
    }

    @Test
    public void zigzagOnOpenGroundBecomesOneLine() {
        Grid g = new Grid(0, 64, 0).walk(1, 1, 3).walk(1, 0, 4); // to (7, 3), not a 45 degree target
        LegatoSmoother.Plan p = plan(g, OPEN);
        assertEquals(2, p.size);
        assertEquals(-1, p.move[0]);
        assertEquals(1, p.lines);
        assertEquals(7, p.replaced);
    }

    @Test
    public void nothingFeasibleKeepsThePath() {
        Grid g = new Grid(0, 64, 0).walk(1, 1, 3).walk(1, 0, 4);
        unchanged(g, plan(g, WALLED));
    }

    @Test
    public void collinearMovesStay() {
        Grid g = new Grid(0, 64, 0).walk(1, 0, 6);
        unchanged(g, plan(g, OPEN));
        Grid d = new Grid(0, 64, 0).walk(1, 1, 5);
        unchanged(d, plan(d, OPEN));
    }

    @Test
    public void specialMovesAreNeverInsideALine() {
        // walk, a parkour jump (flat, but not plain walking), walk
        Grid g = new Grid(0, 64, 0).walk(1, 1, 2).walk(1, 0, 2).step(3, 0, 0, false).walk(1, 1, 2).walk(1, 0, 2);
        LegatoSmoother.Plan p = plan(g, OPEN);
        int jump = -1;
        for (int k = 0; k + 1 < p.size; k++) {
            if (p.move[k] == 4) {
                jump = k;
            }
        }
        assertTrue("the jump is kept", jump >= 0);
        assertEquals(4, p.size); // line, jump, line
        assertEquals(-1, p.move[0]);
        assertEquals(-1, p.move[2]);
    }

    @Test
    public void heightChangesEndALine() {
        Grid g = new Grid(0, 64, 0).walk(1, 1, 2).walk(1, 0, 2).step(1, 1, 0, true).walk(1, 1, 2).walk(1, 0, 2);
        LegatoSmoother.Plan p = plan(g, OPEN); // plan() checks every line is flat
        boolean climbKept = false;
        for (int k = 0; k + 1 < p.size; k++) {
            climbKept |= p.move[k] == 4;
        }
        assertTrue(climbKept);
        assertFalse(p.unchanged());
    }

    @Test
    public void linesAreCutIntoPieces() {
        Grid g = new Grid(0, 64, 0);
        for (int i = 0; i < 10; i++) {
            g.walk(1, 1, 1).walk(1, 0, 2);
        }
        LegatoSmoother.Plan p = plan(g, OPEN); // to (30, 10): plan() checks each piece is at most MAX_SPAN
        assertEquals(1 + LegatoSmoother.pieces(30, 10), p.size);
        assertEquals(g.moves(), p.replaced);
    }

    @Test
    public void longRunsStraightenAcrossTheWholeRun() {
        // a knight run then a bend into a straight run, the shape the search finds on open ground
        Grid g = new Grid(0, 64, 0);
        for (int i = 0; i < 40; i++) {
            g.step(2, 0, 1, true);
        }
        g.walk(1, 0, 90);
        LegatoSmoother.Plan p = plan(g, OPEN);
        double planned = 40 * Math.hypot(2, 1) + 90;
        double length = 0;
        for (int k = 0; k + 1 < p.size; k++) {
            length += Math.hypot(p.x[k + 1] - p.x[k], p.z[k + 1] - p.z[k]);
        }
        double straight = Math.hypot(170, 40);
        assertTrue("length " + length + " planned " + planned, length < straight * 1.002);
        assertEquals(g.moves(), p.replaced);
    }

    @Test
    public void runsLongerThanTheCapAreSplit() {
        Grid g = new Grid(0, 64, 0);
        for (int i = 0; i < LegatoSmoother.MAX_MOVES; i++) {
            g.step(2, 0, 1, true);
        }
        g.walk(1, 0, LegatoSmoother.MAX_MOVES);
        LegatoSmoother.Plan p = plan(g, OPEN);
        assertFalse(p.unchanged());
        assertTrue(p.replaced <= g.moves());
    }

    @Test
    public void evaluationsAreBounded() {
        Grid g = new Grid(0, 64, 0);
        for (int i = 0; i < 400; i++) {
            g.walk(1, 1, 1).walk(1, 0, 2);
        }
        LegatoSmoother.Plan p = plan(g, WALLED); // nothing passes, so every candidate is tried
        assertTrue(p.evaluations <= LegatoSmoother.MAX_EVALUATIONS);
        assertTrue(p.budgetSpent);
        unchanged(g, p);
    }

    @Test
    public void onlyCheaperLinesAreTaken() {
        // a line that costs more than the moves it replaces (a slow surface, say) is not taken
        Grid g = new Grid(0, 64, 0).walk(1, 1, 3).walk(1, 0, 4);
        LegatoSmoother.Lines slow = (x0, y, z0, x1, z1) -> Math.hypot(x1 - x0, z1 - z0) * PER_BLOCK * 1.3;
        unchanged(g, plan(g, slow));
    }

    @Test
    public void partialFeasibilityShortcutsWhatFits() {
        // only lines that stay at z <= 2 fit
        Grid g = new Grid(0, 64, 0).walk(1, 1, 2).walk(1, 0, 3).walk(1, 1, 2).walk(1, 0, 3);
        LegatoSmoother.Lines lowOnly = (x0, y, z0, x1, z1) -> Math.max(z0, z1) <= 2 ? Math.hypot(x1 - x0, z1 - z0) * PER_BLOCK : ActionCosts.COST_INF;
        LegatoSmoother.Plan p = plan(g, lowOnly);
        assertFalse(p.unchanged());
        for (int k = 0; k + 1 < p.size; k++) {
            if (p.move[k] < 0) {
                assertTrue(Math.max(p.z[k], p.z[k + 1]) <= 2);
            }
        }
    }

    @Test
    public void turnModel() {
        assertEquals(0, LegatoSmoother.turnTicks(1, 0, 1, 0), 0);
        assertEquals(0, LegatoSmoother.turnTicks(0, 0, 1, 0), 0);
        assertEquals(0.25 * 45 / 32, LegatoSmoother.turnTicks(1, 0, 1, 1), 1e-9); // turns on the run
        assertEquals(0.25 * 90 / 32, LegatoSmoother.turnTicks(1, 0, 0, 1), 1e-9);
        assertEquals(2 + Math.ceil((135 - 45) / 32.0), LegatoSmoother.turnTicks(1, 0, -1, 1), 1e-9); // stops and turns
        assertTrue(LegatoSmoother.turnTicks(1, 0, -1, 0) > LegatoSmoother.turnTicks(1, 0, 0, 1));
    }

    @Test
    public void emptyAndTinyRoutes() {
        Grid none = new Grid(0, 64, 0);
        assertEquals(1, plan(none, OPEN).size);
        Grid one = new Grid(0, 64, 0).walk(1, 0, 1);
        unchanged(one, plan(one, OPEN));
    }
}
