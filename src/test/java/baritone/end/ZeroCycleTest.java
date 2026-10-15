package baritone.end;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZeroCycleTest {

    /** 26.3 node x, z from Mth's sine table. Math.sin of the same angle is off by a block on several of these. */
    private static final int[][] NODE = {
            {60, 0}, {51, 30}, {29, 51}, {0, 60}, {-31, 51}, {-52, 29},
            {-60, 0}, {-52, -31}, {-30, -52}, {0, -60}, {29, -52}, {51, -30},
            {40, 0}, {28, 28}, {0, 40}, {-29, 28}, {-40, 0}, {-29, -29},
            {0, -40}, {28, -29}, {20, 0}, {0, 20}, {-20, 0}, {0, -20}
    };

    @Test
    public void nodesMatchTheSineTable() {
        StringBuilder bad = new StringBuilder();
        for (int i = 0; i < ZeroCycle.NODES; i++) {
            if (ZeroCycle.nodeX(i) != NODE[i][0] || ZeroCycle.nodeZ(i) != NODE[i][1]) {
                bad.append(i).append(" expected ").append(NODE[i][0]).append(',').append(NODE[i][1])
                        .append(" was ").append(ZeroCycle.nodeX(i)).append(',').append(ZeroCycle.nodeZ(i)).append('\n');
            }
        }
        assertEquals("", bad.toString());
        assertEquals(5, ZeroCycle.nodeLift(0));
        assertEquals(15, ZeroCycle.nodeLift(12));
        assertEquals(5, ZeroCycle.nodeLift(20));
        assertEquals(73, ZeroCycle.nodeY(0, 0));
        assertEquals(80, ZeroCycle.nodeY(0, 75));
        assertEquals(90, ZeroCycle.nodeY(12, 75));
    }

    @Test
    public void pillarsSitOnTheRadius42Ring() {
        assertEquals(42, ZeroCycle.pillarX(0));
        assertEquals(0, ZeroCycle.pillarZ(0));
        assertEquals(-42, ZeroCycle.pillarX(5));
        assertEquals(-1, ZeroCycle.pillarZ(5));
        assertEquals(2, ZeroCycle.radius(0));
        assertEquals(2, ZeroCycle.radius(2));
        assertEquals(3, ZeroCycle.radius(3));
        assertEquals(5, ZeroCycle.radius(9));
        assertEquals(76, ZeroCycle.pillarHeight(0));
        assertEquals(82, ZeroCycle.pillarHeight(2));
        assertEquals(103, ZeroCycle.pillarHeight(9));
        assertFalse(ZeroCycle.caged(0));
        assertTrue(ZeroCycle.caged(1));
        assertTrue(ZeroCycle.caged(2));
        assertFalse(ZeroCycle.caged(3));
        assertEquals(8, ZeroCycle.nearestPillar(10));
        assertEquals(12, ZeroCycle.pillarX(8));
        assertEquals(-40, ZeroCycle.pillarZ(8));
        assertEquals(6, ZeroCycle.nearestPillar(7));
        assertEquals(0, ZeroCycle.nearestPillar(0));
        assertEquals(5, ZeroCycle.nearestPillar(6));
    }

    @Test
    public void flatPodiumKeepsTheFirstInnerNode() {
        int[] flat = new int[ZeroCycle.NODES];
        assertEquals(20, ZeroCycle.closest(0, 128, 0, flat));
        // (10.1, 10.9) is nearer node 21, but the fight floors to (10, 10) and the tie keeps node 20.
        assertEquals(20, ZeroCycle.closest(10.1, 128, 10.9, flat));
        assertEquals(7, ZeroCycle.holdingTarget(20, false, false));
        assertEquals(3, ZeroCycle.holdingTarget(20, false, true));
        assertEquals(ZeroCycle.Approach.BACK_DIAGONAL, ZeroCycle.approach(7));
        assertEquals(ZeroCycle.Approach.SIDE, ZeroCycle.approach(3));
        assertTrue(ZeroCycle.straight(3));
        assertFalse(ZeroCycle.straight(7));

        int[] buried = new int[ZeroCycle.NODES];
        for (int i = ZeroCycle.OUTER; i < ZeroCycle.NODES; i++) {
            buried[i] = 400;
        }
        assertEquals(2, ZeroCycle.closest(0, 128, 0, buried));
        assertEquals(20, ZeroCycle.closest(0, 128, 0, buried, false));

        assertEquals(10, ZeroCycle.holdingTarget(11, false, false));
        assertEquals(6, ZeroCycle.holdingTarget(11, false, true));
        assertEquals(4, ZeroCycle.holdingTarget(5, false, false));
        assertEquals(0, ZeroCycle.holdingTarget(5, false, true));
        assertEquals(ZeroCycle.Approach.FRONT_DIAGONAL, ZeroCycle.approach(10));
        assertEquals(ZeroCycle.Approach.BACK_STRAIGHT, ZeroCycle.approach(6));
        assertEquals(ZeroCycle.Approach.BACK_DIAGONAL, ZeroCycle.approach(4));
        assertEquals(ZeroCycle.Approach.FRONT_STRAIGHT, ZeroCycle.approach(0));
    }

    @Test
    public void standIsTheFrontCheatSheetAndTwoLowerOnAStraight() {
        assertEquals(ZeroCycle.Tower.SMALL, ZeroCycle.tower(76));
        assertEquals(ZeroCycle.Tower.CAGE, ZeroCycle.tower(79));
        assertEquals(ZeroCycle.Tower.CAGE, ZeroCycle.tower(82));
        assertEquals(ZeroCycle.Tower.MEDIUM, ZeroCycle.tower(88));
        assertEquals(ZeroCycle.Tower.MEDIUM, ZeroCycle.tower(91));
        assertEquals(ZeroCycle.Tower.BIG, ZeroCycle.tower(94));
        assertEquals(95, ZeroCycle.standY(ZeroCycle.Approach.FRONT_DIAGONAL, 76));
        assertEquals(95, ZeroCycle.standY(ZeroCycle.Approach.FRONT_DIAGONAL, 88));
        assertEquals(93, ZeroCycle.standY(ZeroCycle.Approach.FRONT_STRAIGHT, 88));
        assertEquals(99, ZeroCycle.standY(ZeroCycle.Approach.BACK_DIAGONAL, 91));
        assertEquals(97, ZeroCycle.standY(ZeroCycle.Approach.BACK_STRAIGHT, 94));
        // Pillar 8 is the tower nearest the common front diagonal. Radius 2, northwest corner, then west 3 and north 1.
        assertArrayEquals(new int[]{7, -43}, ZeroCycle.standXZ(12, -40, 2, ZeroCycle.Tower.SMALL, ZeroCycle.Approach.FRONT_DIAGONAL));
        assertArrayEquals(new int[]{7, -42}, ZeroCycle.standXZ(12, -40, 2, ZeroCycle.Tower.CAGE, ZeroCycle.Approach.FRONT_DIAGONAL));
        assertArrayEquals(new int[]{17, -37}, ZeroCycle.standXZ(12, -40, 2, ZeroCycle.Tower.SMALL, ZeroCycle.Approach.BACK_DIAGONAL));
    }

    @Test
    public void rigPutsTheBedBetweenTwoAnchors() {
        ZeroCycle.Piece[] pieces = ZeroCycle.rigPieces();
        int[][] steps = ZeroCycle.rigSteps();
        assertEquals(pieces.length, steps.length);
        int bed = -1;
        for (int i = 0; i < pieces.length; i++) {
            if (pieces[i] == ZeroCycle.Piece.BED) {
                bed = i;
            }
        }
        assertTrue(bed > 0 && bed < pieces.length - 1);
        assertEquals(ZeroCycle.Piece.ANCHOR, pieces[bed - 1]);
        assertEquals(ZeroCycle.Piece.ANCHOR, pieces[bed + 1]);
        assertEquals(steps[bed][1], steps[bed - 1][1]);
        assertEquals(steps[bed][2], steps[bed - 1][2]);
        assertEquals(steps[bed][0] - 1, steps[bed - 1][0]);
        assertEquals(steps[bed][0] + 1, steps[bed + 1][0]);
        assertArrayEquals(new int[]{1, 0}, ZeroCycle.forward(0, 0, 10, 1));
        assertArrayEquals(new int[]{0, -1}, ZeroCycle.left(1, 0));
    }

    @Test
    public void rigFacesTheNodeFromTheFrontStand() {
        int[] stand = ZeroCycle.standXZ(12, -40, 2, ZeroCycle.Tower.SMALL, ZeroCycle.Approach.FRONT_DIAGONAL);
        int y = ZeroCycle.standY(ZeroCycle.Approach.FRONT_DIAGONAL, 76);
        int[][] world = ZeroCycle.rigAt(stand[0], y, stand[1], ZeroCycle.nodeX(10), ZeroCycle.nodeZ(10));
        ZeroCycle.Piece[] pieces = ZeroCycle.rigPieces();
        int bed = 0;
        while (pieces[bed] != ZeroCycle.Piece.BED) {
            bed++;
        }
        // Stand (7, 95, -43), node 10 is east of it, so left is north. The bed sits between the anchors.
        assertArrayEquals(new int[]{11, 98, -44}, world[bed]);
        assertArrayEquals(new int[]{10, 98, -44}, world[bed - 1]);
        assertArrayEquals(new int[]{12, 98, -44}, world[bed + 1]);
        assertEquals(ZeroCycle.Piece.ANCHOR, pieces[bed - 1]);
        assertEquals(ZeroCycle.Piece.ANCHOR, pieces[bed + 1]);

        // Facing south, left is east, so the bed is one block east of the forward line.
        int[][] south = ZeroCycle.rigAt(0, 95, 0, ZeroCycle.nodeX(3), ZeroCycle.nodeZ(3));
        assertArrayEquals(new int[]{1, 98, 4}, south[bed]);
    }
}
