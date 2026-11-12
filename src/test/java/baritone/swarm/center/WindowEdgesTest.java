package baritone.swarm.center;

import baritone.swarm.center.WindowEdges.Bounds;
import baritone.swarm.center.WindowEdges.Edge;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Ostinato: hit test and resize math behind the command center's own title bar and borders. */
public class WindowEdgesTest {

    @Test public void interiorAndOutsideGrabNothing() {
        assertEquals(Edge.NONE, WindowEdges.hit(500, 400, 1000, 800, 5));
        assertEquals(Edge.NONE, WindowEdges.hit(-1, 400, 1000, 800, 5));
        assertEquals(Edge.NONE, WindowEdges.hit(1000, 400, 1000, 800, 5));
        assertEquals(Edge.NONE, WindowEdges.hit(0, 0, 0, 0, 5));
        assertEquals(Edge.NONE, WindowEdges.hit(0, 0, 1000, 800, 0));
    }

    @Test public void sidesAndCorners() {
        assertEquals(Edge.N, WindowEdges.hit(500, 0, 1000, 800, 5));
        assertEquals(Edge.S, WindowEdges.hit(500, 799, 1000, 800, 5));
        assertEquals(Edge.W, WindowEdges.hit(4, 400, 1000, 800, 5));
        assertEquals(Edge.E, WindowEdges.hit(995, 400, 1000, 800, 5));
        assertEquals(Edge.NONE, WindowEdges.hit(5, 400, 1000, 800, 5));
        assertEquals(Edge.NW, WindowEdges.hit(0, 0, 1000, 800, 5));
        assertEquals(Edge.SE, WindowEdges.hit(999, 799, 1000, 800, 5));
        // corners reach three grips along each side
        assertEquals(Edge.NE, WindowEdges.hit(986, 2, 1000, 800, 5));
        assertEquals(Edge.N, WindowEdges.hit(984, 2, 1000, 800, 5));
        assertEquals(Edge.SW, WindowEdges.hit(2, 786, 1000, 800, 5));
        assertEquals(Edge.W, WindowEdges.hit(2, 784, 1000, 800, 5));
    }

    @Test public void tinyWindowStillPicksOneSide() {
        assertEquals(Edge.NW, WindowEdges.hit(1, 1, 8, 8, 5));
        assertEquals(Edge.SE, WindowEdges.hit(6, 6, 8, 8, 5));
    }

    @Test public void dragKeepsTheOppositeSide() {
        Bounds start = new Bounds(100, 50, 1000, 800);
        assertEquals(new Bounds(100, 50, 1040, 830), WindowEdges.resize(start, Edge.SE, 40, 30, 760, 520));
        assertEquals(new Bounds(140, 80, 960, 770), WindowEdges.resize(start, Edge.NW, 40, 30, 760, 520));
        assertEquals(new Bounds(100, 50, 1000, 830), WindowEdges.resize(start, Edge.S, 40, 30, 760, 520));
        assertEquals(start, WindowEdges.resize(start, Edge.NONE, 40, 30, 760, 520));
    }

    @Test public void neverBelowTheMinimum() {
        Bounds start = new Bounds(100, 50, 1000, 800);
        // dragging the west side far right stops at the minimum width; the east side does not move
        assertEquals(new Bounds(340, 50, 760, 800), WindowEdges.resize(start, Edge.W, 900, 0, 760, 520));
        assertEquals(new Bounds(100, 330, 1000, 520), WindowEdges.resize(start, Edge.N, 0, 900, 760, 520));
        assertEquals(new Bounds(100, 50, 760, 520), WindowEdges.resize(start, Edge.SE, -900, -900, 760, 520));
    }
}
