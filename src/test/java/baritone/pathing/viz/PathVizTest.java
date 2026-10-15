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

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class PathVizTest {

    /** A recorder with each view switched by a flag array, and a settable clock. */
    private static final class Rig {
        final boolean[] on = {true, true, true, true, true, true};
        long t = 1000;
        final PathViz v = new PathViz(() -> on[0], () -> on[1], () -> on[2], () -> on[3], () -> on[4], () -> on[5], () -> t);

        Rig all(boolean b) {
            Arrays.fill(on, b);
            return this;
        }
    }

    private static List<double[]> line(int n) {
        List<double[]> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(new double[]{i + 0.5, 64, 0.5, i});
        }
        return l;
    }

    @Test
    public void ringKeepsTheNewestInOrder() {
        Ring<int[]> r = new Ring<>(3, () -> new int[1]);
        for (int i = 0; i < 7; i++) {
            r.add()[0] = i;
        }
        assertEquals(3, r.size());
        assertEquals(4, r.get(0)[0]);
        assertEquals(5, r.get(1)[0]);
        assertEquals(6, r.newest()[0]);
        r.clear();
        assertEquals(0, r.size());
        assertNull(r.newest());
    }

    @Test
    public void ringReusesItsSlots() {
        Ring<int[]> r = new Ring<>(2, () -> new int[1]);
        int[] a = r.add(), b = r.add();
        assertSame(a, r.add());
        assertSame(b, r.add());
    }

    @Test
    public void corridorCopiesTheLineAndFindsTheNextNode() {
        Rig rig = new Rig();
        rig.v.corridor(line(5), 1.5, 1.1);
        assertEquals(5, rig.v.lineCount);
        assertEquals(2, rig.v.nextNode());
        assertEquals(1000, rig.v.lineStamp);
    }

    @Test
    public void corridorIsBounded() {
        Rig rig = new Rig();
        rig.v.corridor(line(PathViz.MAX_LINE + 50), 0, 1.1);
        assertEquals(PathViz.MAX_LINE, rig.v.lineCount);
    }

    @Test
    public void rolloutFanRecordsChoiceAndReasons() {
        Rig rig = new Rig();
        PathViz v = rig.v;
        assertTrue(v.beginRollouts());
        PathViz.Rollout a = v.startRollout(0, -1);
        a.point(0, 0, 0);
        a.point(1, 0, 0);
        PathViz.Rollout b = v.startRollout(20, -1);
        b.reason = "exits corridor";
        PathViz.Rollout c = v.startRollout(-20, -1);
        c.reason = "exits corridor";
        PathViz.Rollout d = v.startRollout(0, 0);
        d.reason = "lands in gap";
        v.chooseRollout(0);
        assertEquals(4, v.rolloutCount);
        assertTrue(v.rollout(0).chosen);
        assertFalse(v.rollout(1).chosen);
        assertEquals(2, v.rollout(0).n);
        assertEquals("exits corridor x2, lands in gap x1", v.rolloutSummary);
        // next tick starts a fresh fan in the same slots
        assertTrue(v.beginRollouts());
        assertSame(a, v.startRollout(0, -1));
        assertEquals(0, a.n);
        assertEquals(PathViz.OK, a.reason);
    }

    @Test
    public void rolloutPointsAndPoolAreBounded() {
        Rig rig = new Rig();
        PathViz v = rig.v;
        v.beginRollouts();
        PathViz.Rollout r = v.startRollout(0, -1);
        for (int i = 0; i < PathViz.MAX_ROLLOUT_TICKS + 20; i++) {
            r.point(i, 0, 0);
        }
        assertEquals(PathViz.MAX_ROLLOUT_TICKS, r.n);
        for (int i = 1; i < PathViz.MAX_ROLLOUTS; i++) {
            assertNotNull(v.startRollout(0, -1));
        }
        assertNull(v.startRollout(0, -1));
    }

    @Test
    public void jumpTakeoffApexLanding() {
        Rig rig = new Rig();
        PathViz v = rig.v;
        Object mv = new Object();
        v.jumpAir(mv, 0, 99, 0); // nothing open yet
        assertEquals(0, v.jumps.size());
        v.jumpTakeoff(mv, PathViz.JUMP, "jump 3,0,0", 0, 64, 0);
        v.jumpAir(mv, 1, 65.2, 0);
        v.jumpAir(mv, 2, 64.8, 0);
        v.jumpAir(new Object(), 9, 99, 9); // another movement's tick is ignored
        v.jumpLand(mv, 3, 64, 0, false);
        PathViz.JumpMark m = v.jumps.newest();
        assertEquals("jump 3,0,0", m.label);
        assertEquals(65.2, m.ay, 1e-9);
        assertEquals(1, m.ax, 1e-9);
        assertTrue(m.landed);
        assertEquals(3, m.lx, 1e-9);
        v.jumpLand(mv, 7, 64, 0, false); // already landed: unchanged
        assertEquals(3, m.lx, 1e-9);
    }

    @Test
    public void historiesAreBounded() {
        Rig rig = new Rig();
        PathViz v = rig.v;
        Object mv = new Object();
        for (int i = 0; i < 100; i++) {
            v.jumpTakeoff(mv, PathViz.JUMP, "j", i, 64, 0);
            v.stuck(i, 64, 0, "M01");
            v.newPlan(i, 64, 0, "drift");
        }
        for (int i = 0; i < PathViz.MAX_TRAIL * 2; i++) {
            rig.t++;
            v.physTick(i, 64, 0, 0.2, 0, 0, true, false, 0, PathViz.IN_W | PathViz.IN_JUMP);
            v.plannedPoint(i, 64, 0);
            v.actualPoint(i, 64, 0);
        }
        assertEquals(PathViz.MAX_JUMPS, v.jumps.size());
        assertEquals(PathViz.MAX_STUCK, v.stuck.size());
        assertEquals(PathViz.MAX_REPLANS, v.replans.size());
        assertEquals(PathViz.MAX_TRAIL, v.trail.size());
        assertEquals(PathViz.MAX_GHOSTS, v.ghosts.size());
        assertEquals(PathViz.MAX_PLAN, v.plannedCount);
        assertEquals(PathViz.MAX_PLAN, v.actualCount);
        assertTrue(v.beginTree());
        for (int i = 0; i < PathViz.MAX_TREE_EDGES + 10; i++) {
            v.treeEdge(0, 0, 0, 1, 1, 1, i);
        }
        v.endTree();
        assertEquals(PathViz.MAX_TREE_EDGES, v.treeCount);
        v.treeEdge(0, 0, 0, 1, 1, 1, 1); // closed: ignored
        assertEquals(PathViz.MAX_TREE_EDGES, v.treeCount);
    }

    @Test
    public void physTickMarksJumpApexLandingGhosts() {
        Rig rig = new Rig();
        PathViz v = rig.v;
        v.physTick(0, 64, 0, 0.28, 0, 0, true, false, 0, PathViz.IN_W | PathViz.IN_SPRINT | PathViz.IN_JUMP);
        v.physTick(0.3, 64.4, 0, 0.3, 0.33, 0, false, false, 0, PathViz.IN_W);
        v.physTick(0.6, 65.1, 0, 0.3, 0.1, 0, false, false, 0, PathViz.IN_W);
        v.physTick(0.9, 65.2, 0, 0.3, -0.05, 0, false, false, 0, PathViz.IN_W);
        v.physTick(1.2, 64.0, 0, 0.3, 0, 0, true, false, 0, PathViz.IN_W);
        assertEquals(3, v.ghosts.size());
        assertEquals(PathViz.GHOST_JUMP, v.ghosts.get(0).kind);
        assertEquals(PathViz.GHOST_APEX, v.ghosts.get(1).kind);
        assertEquals(PathViz.GHOST_LAND, v.ghosts.get(2).kind);
        assertEquals(5, v.trail.size());
        assertEquals(0.28, v.trail.get(0).hspeed, 1e-9);
    }

    @Test
    public void physTickGhostOnGrab() {
        Rig rig = new Rig();
        PathViz v = rig.v;
        v.physTick(0, 64, 0, 0.2, 0.1, 0, false, false, 0, PathViz.IN_W);
        v.physTick(0, 64, 0, 0.0, 0.0, 0, false, true, 0, PathViz.IN_W);
        assertEquals(PathViz.GHOST_GRAB, v.ghosts.newest().kind);
    }

    @Test
    public void aPauseStartsANewTrail() {
        Rig rig = new Rig();
        PathViz v = rig.v;
        v.physTick(0, 64, 0, 0.2, 0, 0, true, false, 0, 0);
        v.physTick(1, 64, 0, 0.2, 0, 0, true, false, 0, 0);
        rig.t += 5000;
        v.physTick(9, 64, 0, 0.2, 0, 0, true, false, 0, 0);
        assertEquals(1, v.trail.size());
    }

    @Test
    public void plannedAgainstActualError() {
        Rig rig = new Rig();
        PathViz v = rig.v;
        v.newPlan(0, 64, 0, null);
        assertEquals(0, v.replans.size()); // the first plan of a stretch is not a replan
        v.plannedPoint(0, 64, 0);
        v.plannedPoint(1, 64, 0);
        v.actualPoint(0, 64, 0);
        v.actualPoint(1.5, 64, 0);
        assertEquals(0, v.error(0), 1e-9);
        assertTrue(v.error(1) > PathViz.ERROR_RED);
        assertTrue(Double.isNaN(v.error(2)));
        v.newPlan(1.5, 64, 0, "drift");
        assertEquals(1, v.replans.size());
        assertEquals("drift", v.replans.newest().why);
        assertEquals(0, v.plannedCount);
        assertEquals(0, v.actualCount);
    }

    @Test
    public void togglesOffCollectNothing() {
        Rig rig = new Rig().all(false);
        PathViz v = rig.v;
        Object mv = new Object();
        v.corridor(line(5), 1, 1.1);
        assertFalse(v.beginRollouts());
        assertNull(v.startRollout(0, -1));
        v.jumpTakeoff(mv, PathViz.JUMP, "j", 0, 64, 0);
        v.stuck(0, 64, 0, "M01");
        v.physTick(0, 64, 0, 0.2, 0, 0, true, false, 0, PathViz.IN_JUMP);
        assertFalse(v.beginTree());
        v.treeEdge(0, 0, 0, 1, 1, 1, 1);
        v.newPlan(0, 64, 0, "drift");
        v.actualPoint(0, 64, 0);
        assertEquals(0, v.lineCount);
        assertEquals(0, v.rolloutCount);
        assertEquals(0, v.jumps.size());
        assertEquals(0, v.stuck.size());
        assertEquals(0, v.trail.size());
        assertEquals(0, v.ghosts.size());
        assertEquals(0, v.treeCount);
        assertEquals(0, v.replans.size());
        assertEquals(0, v.actualCount);
        assertFalse(v.physOn());
    }

    @Test
    public void eachToggleGatesOnlyItsOwnView() {
        Rig rig = new Rig();
        PathViz v = rig.v;
        rig.on[3] = false; // speed trail off, inputs still on: samples are still kept for the glyphs
        v.physTick(0, 64, 0, 0.2, 0, 0, true, false, 0, 0);
        assertEquals(1, v.trail.size());
        rig.on[4] = false;
        v.physTick(1, 64, 0, 0.2, 0, 0, true, false, 0, 0);
        assertEquals(1, v.trail.size());
        rig.on[0] = false;
        v.corridor(line(3), 0, 1);
        assertEquals(0, v.lineCount);
        assertTrue(v.beginRollouts()); // rollouts are their own toggle
        rig.on[2] = false;
        v.stuck(0, 0, 0, "x");
        assertEquals(0, v.stuck.size());
    }
}
