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

/** Headless: each layer of the path viz emits segments, in its colours, from recorded data alone. */
public class PathVizDrawTest {

    private static final class Seg {
        final double[] p;
        final float r, g, b, a;

        Seg(double[] p, float r, float g, float b, float a) {
            this.p = p;
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
        }

        boolean red() {
            return r > 0.9F && g < 0.3F && b < 0.3F;
        }

        boolean green() {
            return g > 0.9F && r < 0.3F;
        }

        boolean cyan() {
            return r < 0.1F && g > 0.8F && b > 0.9F;
        }

        boolean yellow() {
            return r > 0.9F && g > 0.8F && b < 0.2F;
        }

        boolean orange() {
            return r > 0.9F && g > 0.4F && g < 0.7F && b < 0.1F;
        }

        boolean purple() {
            return r > 0.6F && r < 0.9F && b > 0.9F;
        }
    }

    private static final class Rec implements PathVizDraw.Sink {
        final List<Seg> segs = new ArrayList<>();

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, float r, float g, float b, float a, float w) {
            assertTrue(w > 0);
            segs.add(new Seg(new double[]{x1, y1, z1, x2, y2, z2}, r, g, b, a));
        }

        long count(java.util.function.Predicate<Seg> p) {
            return segs.stream().filter(p).count();
        }
    }

    private final boolean[] on = new boolean[6];
    private long t = 10_000;
    private final PathViz v = new PathViz(() -> on[0], () -> on[1], () -> on[2], () -> on[3], () -> on[4], () -> on[5], () -> t);

    private void only(int i) {
        Arrays.fill(on, false);
        on[i] = true;
    }

    private Rec kin() {
        Rec r = new Rec();
        PathVizDraw.drawKin(v, t, r);
        return r;
    }

    private Rec phys() {
        Rec r = new Rec();
        PathVizDraw.drawPhys(v, t, 0, -10, r);
        return r;
    }

    private static List<double[]> line(int n) {
        List<double[]> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(new double[]{0.5, 64, i + 0.5, i});
        }
        return l;
    }

    @Test
    public void corridorEmitsCyanPathAndYellowTube() {
        only(0);
        v.corridor(line(6), 1.5, 1.1);
        assertTrue(PathVizDraw.hasKin(v, t));
        Rec r = kin();
        assertTrue(r.count(Seg::cyan) > 0);
        assertTrue(r.count(Seg::yellow) > 0);
        assertTrue(r.count(Seg::green) > 0); // next target
    }

    @Test
    public void corridorTurnsRedAfterAStuckHandback() {
        Arrays.fill(on, true);
        v.corridor(line(6), 1.5, 1.1);
        t += 1000; // the controller stopped feeding it
        v.stuck(0.5, 64, 2, "M01");
        v.handback(0.5, 64, 2, "to BARITONE");
        assertTrue(PathVizDraw.corridorLive(v, t));
        Rec r = kin();
        assertTrue(r.count(Seg::red) > 20);
        assertEquals(0, r.count(Seg::yellow));
        assertTrue(r.count(Seg::orange) > 0); // hand-back post
        t += PathVizDraw.HANDBACK_MS + 1;
        assertFalse(PathVizDraw.corridorLive(v, t));
    }

    @Test
    public void rolloutFanShowsLosersAboveTheChosenPlan() {
        only(1);
        v.beginRollouts();
        PathViz.Rollout chosen = v.startRollout(0, -1);
        PathViz.Rollout lost = v.startRollout(8, -1);
        PathViz.Rollout rej = v.startRollout(40, -1);
        for (int k = 0; k < 10; k++) { // all on the same line, as rollouts converge back onto it
            chosen.point(0.5, 64, k * 0.3);
            lost.point(0.5, 64, k * 0.3);
            if (k < 4) {
                rej.point(0.5, 64, k * 0.3);
            }
        }
        rej.reason = "exits corridor";
        v.chooseRollout(0);
        Rec r = kin();
        assertTrue(r.count(Seg::green) > 0);
        assertTrue(r.count(Seg::orange) > 0);
        assertTrue(r.count(Seg::red) >= 3 + 3); // path and the X
        double greenY = r.segs.stream().filter(Seg::green).mapToDouble(s -> s.p[1]).max().getAsDouble();
        double orangeY = r.segs.stream().filter(Seg::orange).mapToDouble(s -> s.p[1]).min().getAsDouble();
        assertTrue("losers drawn above the chosen plan", orangeY > greenY);
        t += PathVizDraw.FAN_MS - 100; // still up a moment after the controller stops
        assertTrue(PathVizDraw.hasKin(v, t));
    }

    @Test
    public void jumpEmitsArcRingsAndHandback() {
        only(2);
        Object mv = new Object();
        v.jumpTakeoff(mv, PathViz.JUMP, "jump", 0, 64, 0);
        for (int k = 1; k < 10; k++) {
            v.jumpAir(mv, k * 0.4, 64 + 1.25 * Math.sin(k / 10.0 * Math.PI), 0);
        }
        v.jumpLand(mv, 4, 64, 0, false);
        PathViz.JumpMark m = v.jumps.newest();
        assertEquals(11, m.arcN);
        Rec r = kin();
        assertTrue(r.count(Seg::purple) >= 2 * 10); // halo + core per arc segment
        assertTrue(r.count(Seg::yellow) > 0); // take-off
        assertTrue(r.count(Seg::cyan) > 0); // landing
        v.handback(5, 64, 0, "to BARITONE");
        assertTrue(kin().count(Seg::orange) > 0);
        v.stuck(5, 64, 0, "M01");
        assertTrue(kin().count(Seg::red) > 0);
    }

    @Test
    public void speedTrailEmits() {
        only(3);
        for (int i = 0; i < 10; i++) {
            t++;
            v.physTick(i * 0.3, 64, 0, 0.1 + i * 0.02, 0, 0, true, false, 0, PathViz.IN_W);
        }
        assertTrue(PathVizDraw.hasPhys(v, t));
        assertTrue(phys().segs.size() >= 9);
    }

    @Test
    public void inputsEmitLettersTrajectoryAndGhosts() {
        only(4);
        t++;
        v.physTick(0, 64, 0, 0.28, 0, 0, true, false, 0, PathViz.IN_W | PathViz.IN_SPRINT | PathViz.IN_JUMP);
        t++;
        v.physTick(0, 64.4, 0.3, 0.28, 0.3, 0, false, false, 0, PathViz.IN_W | PathViz.IN_SPRINT);
        Rec r = phys();
        assertTrue(r.count(Seg::cyan) > 0); // trajectory
        assertTrue(r.count(Seg::orange) >= 5 + 2); // "S+"
        assertTrue(r.count(Seg::yellow) >= 4); // "J" on the jump tick
        assertTrue(r.count(s -> s.b > 0.3F && s.b < 0.4F && s.r > 0.9F) >= 12); // the jump ghost box
        // letters face the camera: the camera is at -z, so strokes run along x
        assertTrue(r.segs.stream().filter(Seg::orange).anyMatch(s -> Math.abs(s.p[3] - s.p[0]) > 0.05 && Math.abs(s.p[5] - s.p[2]) < 1e-9));
    }

    @Test
    public void searchEmitsTreePlanActualAndReplans() {
        only(5);
        v.beginTree();
        v.treeEdge(0, 64, 0, 1, 64, 0, 1);
        v.treeEdge(1, 64, 0, 2, 64, 0, 2);
        v.endTree();
        v.newPlan(0, 64, 0, "drift");
        v.plannedPoint(0, 64, 0);
        v.plannedPoint(1, 64, 0);
        v.actualPoint(0, 64, 0);
        v.actualPoint(1.6, 64, 0);
        t++;
        v.physTick(0, 64, 0, 0, 0, 0, true, false, 0, 0); // search is on, so a trail sample is not kept, but harmless
        assertTrue(PathVizDraw.hasPhys(v, t));
        Rec r = phys();
        assertTrue(r.segs.size() >= 2 + 1 + 1 + 1 + 5); // tree, planned, actual, one non-zero error link, replan
        assertTrue(r.count(Seg::red) > 0); // the error link past the threshold
    }

    @Test
    public void nothingIsEmittedWhenAllOff() {
        Arrays.fill(on, true);
        Object mv = new Object();
        v.corridor(line(6), 1.5, 1.1);
        v.jumpTakeoff(mv, PathViz.JUMP, "j", 0, 64, 0);
        v.physTick(0, 64, 0, 0.2, 0, 0, true, false, 0, PathViz.IN_W);
        Arrays.fill(on, false);
        assertFalse(PathVizDraw.hasKin(v, t));
        assertFalse(PathVizDraw.hasPhys(v, t));
        assertEquals(0, kin().segs.size());
        assertEquals(0, phys().segs.size());
    }
}
