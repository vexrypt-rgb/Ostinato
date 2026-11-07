/*
 * Ostinato: new file (kinematic MovementLine). Not part of upstream Baritone.
 */
package baritone.pathing.kinematic;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.pathing.movement.ActionCosts;
import baritone.api.pathing.movement.IMovement;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.PathBench;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.legato.LegatoFeasibility;
import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.movements.MovementLine;
import baritone.pathing.movement.movements.MovementStraight;
import baritone.pathing.movement.movements.MovementTraverse;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Ostinato: which knight moves the kinematic driver may steer ({@code kinematicLine}), and that the rest of its
 * contract (turn gate, completion) holds for them. Headless: a stone floor at y 63, the player's feet at 64.
 */
public class KinematicLineTest {

    private static final int Y = 64;
    /** A movement only needs a baritone for its player context, which these checks never touch. */
    private static IBaritone NONE;

    private SyntheticWorld w;
    private CalculationContext ctx;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
        NONE = (IBaritone) Proxy.newProxyInstance(IBaritone.class.getClassLoader(), new Class<?>[]{IBaritone.class}, (p, m, a) -> null);
    }

    @Before
    public void floor() {
        w = new SyntheticWorld(16, 16);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                w.set(x, Y - 1, z, Blocks.STONE.defaultBlockState());
            }
        }
        ctx = PathBench.context(w);
        Baritone.settings().kinematicLine.value = true;
        KinematicLine.reset();
    }

    @After
    public void offAgain() {
        Baritone.settings().kinematicLine.value = false;
        KinematicLine.reset();
    }

    private static MovementLine line(int x, int z, int dx, int dz) {
        return new MovementLine(NONE, new BetterBlockPos(x, Y, z), dx, dz);
    }

    private MovementLine checked(int x, int z, int dx, int dz) {
        MovementLine l = line(x, z, dx, dz);
        l.calculateCost(ctx);
        return l;
    }

    // 1. eligible line
    @Test
    public void plainKnightMoveIsSteered() {
        for (int[] d : new int[][]{{2, 1}, {1, 2}, {-2, 1}, {2, -1}, {-1, -2}}) {
            MovementLine l = checked(6, 6, d[0], d[1]);
            assertTrue(l.calculateCost(ctx) < ActionCosts.COST_INF);
            assertEquals(LegatoFeasibility.R_OK, l.groundReason());
            assertEquals(KinematicLine.ACCEPTED, KinematicLine.decide(true, l));
            assertTrue(KinematicController.drivable(l));
        }
    }

    // 12. setting disabled: knight moves stay with Baritone, every other move as before
    @Test
    public void offLeavesLinesToBaritoneAndNothingElseChanges() {
        MovementLine l = checked(6, 6, 2, 1);
        IMovement traverse = new MovementTraverse(NONE, new BetterBlockPos(6, Y, 6), new BetterBlockPos(7, Y, 6));
        IMovement straight = new MovementStraight(NONE, new BetterBlockPos(6, Y, 6), new BetterBlockPos(12, Y, 9));
        for (boolean on : new boolean[]{true, false}) {
            Baritone.settings().kinematicLine.value = on;
            assertEquals(on, KinematicController.drivable(l));
            assertTrue(KinematicController.drivable(traverse));
            assertTrue(KinematicController.drivable(straight));
        }
        assertEquals(KinematicLine.OFF, KinematicLine.decide(false, l));
    }

    // 2. ineligible fallback: anything but plain full-block ground keeps Baritone's MovementLine.updateState
    @Test
    public void unusualGroundFallsBack() {
        BlockState bottomSlab = Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        Object[][] cases = {
                {Y - 1, Blocks.ICE.defaultBlockState(), LegatoFeasibility.R_FLOOR},
                {Y - 1, Blocks.PACKED_ICE.defaultBlockState(), LegatoFeasibility.R_FLOOR},
                {Y - 1, Blocks.SOUL_SAND.defaultBlockState(), LegatoFeasibility.R_FLOOR},
                {Y - 1, Blocks.HONEY_BLOCK.defaultBlockState(), null},
                {Y - 1, Blocks.SLIME_BLOCK.defaultBlockState(), null},
                {Y - 1, bottomSlab, LegatoFeasibility.R_FLOOR},
                {Y, Blocks.MOSS_CARPET.defaultBlockState(), LegatoFeasibility.R_BLOCKED},
                {Y, Blocks.WATER.defaultBlockState(), "no line"},
                {Y, Blocks.OAK_DOOR.defaultBlockState(), "no line"},
        };
        for (Object[] c : cases) {
            floor();
            // the middle of the rectangle, a cell the knight move passes through but neither starts nor ends in
            w.set(7, (int) c[0], 6, (BlockState) c[1]);
            MovementLine l = checked(6, 6, 2, 1);
            if (c[2] != null) { // else: some reason, whichever check (the line's own cost or the ground's) refuses it first
                assertEquals(c[1].toString(), c[2], l.groundReason());
            }
            assertNotEquals(c[1].toString(), LegatoFeasibility.R_OK, l.groundReason());
            assertEquals(c[1].toString(), KinematicLine.GROUND, KinematicLine.decide(true, l));
            assertFalse(c[1].toString(), KinematicController.drivable(l));
        }
    }

    // 2b. hazards next to the line but inside its rectangle
    @Test
    public void hazardInTheRectangleFallsBack() {
        w.set(8, Y, 6, Blocks.SWEET_BERRY_BUSH.defaultBlockState()); // the rectangle's far corner; the dest is (8,7)
        MovementLine l = checked(6, 6, 2, 1);
        assertNotEquals(LegatoFeasibility.R_OK, l.groundReason());
        assertFalse(KinematicController.drivable(l));
    }

    // 10, 11, 13. the world changes under a steered line: the next cost check downgrades it, and a later one restores it
    @Test
    public void worldChangeDowngradesThenRecovers() {
        MovementLine l = checked(6, 6, 2, 1);
        assertTrue(KinematicController.drivable(l));
        assertTrue(KinematicController.drivable(l)); // the same answer is not counted again
        assertEquals(1, KinematicLine.decisions(KinematicLine.ACCEPTED));

        w.set(7, Y - 1, 7, Blocks.AIR.defaultBlockState()); // support lost under the rectangle
        assertTrue("the executor cancels this path", l.calculateCost(ctx) >= ActionCosts.COST_INF);
        assertFalse(KinematicController.drivable(l));
        assertEquals(1, KinematicLine.downgrades());

        w.set(7, Y - 1, 7, Blocks.ICE.defaultBlockState()); // walkable again, but not plain
        l.calculateCost(ctx);
        assertFalse(KinematicController.drivable(l));
        assertEquals("not accepted in between: no second downgrade", 1, KinematicLine.downgrades());

        w.set(7, Y - 1, 7, Blocks.STONE.defaultBlockState());
        l.calculateCost(ctx);
        assertTrue("recovers after a rejection", KinematicController.drivable(l));
        assertEquals(2, KinematicLine.decisions(KinematicLine.ACCEPTED));
        assertEquals("ice after a hole is the same refusal", 1, KinematicLine.decisions(KinematicLine.GROUND));
    }

    // 7, 8. a fresh line (a new plan) carries no earlier decision, and is not steered before its first cost check
    @Test
    public void uncheckedLinesAreNotSteered() {
        MovementLine l = line(6, 6, 2, 1);
        assertFalse(l.groundChecked());
        assertEquals(-1, l.kinematicDecision);
        assertEquals(KinematicLine.PENDING, KinematicLine.decide(true, l));
        assertFalse(KinematicController.drivable(l));
        l.calculateCost(ctx);
        assertTrue(KinematicController.drivable(l));
        MovementLine replanned = line(6, 6, 2, 1);
        assertEquals("a new plan's line decides afresh", -1, replanned.kinematicDecision);
        assertFalse(KinematicController.drivable(replanned));
    }

    @Test
    public void unloadedOrBorderIsNotSteered() {
        w.knownMaxX = 7; // the dest column (8) is not known
        MovementLine l = checked(6, 6, 2, 1);
        assertFalse(KinematicController.drivable(l));
    }

    // 3, 4. a knight bend is steered, a reversal onto a knight move is left to Baritone's turn-in-place
    @Test
    public void knightHeadingsAndTheTurnGate() {
        float east = -90f; // yaw of +x
        float knight = yaw(2, 1);
        assertEquals(-63.4f, knight, 0.1f);
        assertFalse("a 26.6 degree bend", KinematicController.turningAround(east, knight));
        assertFalse("the other knight bend", KinematicController.turningAround(east, yaw(2, -1)));
        assertFalse("a 63.4 degree knight bend", KinematicController.turningAround(east, yaw(1, 2)));
        assertTrue("back the way it came", KinematicController.turningAround(east, yaw(-2, 1)));
        assertTrue("from a reversal", KinematicController.turningAround(90f, yaw(2, 1)));
    }

    private static float yaw(int dx, int dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    // 9. completion at a waypoint boundary: the same contract as MovementLine.updateState (feet in the dest cell)
    @Test
    public void completionIsTheDestCell() {
        List<IMovement> moves = List.of(line(0, 0, 2, 1), line(2, 1, 2, 1), line(4, 2, 2, -1));
        assertEquals("start", 0, KinematicController.syncedPosition(moves, 0, 2, 0.5, Y, 0.5, true));
        assertEquals("inside the first rectangle, not done", 0, KinematicController.syncedPosition(moves, 0, 2, 1.5, Y, 0.9, true));
        assertEquals("in its far column but the wrong row", 0, KinematicController.syncedPosition(moves, 0, 2, 2.5, Y, 0.5, true));
        assertEquals("the first dest", 1, KinematicController.syncedPosition(moves, 0, 2, 2.01, Y, 1.99, true));
        assertEquals("the second dest, skipping the first", 2, KinematicController.syncedPosition(moves, 0, 2, 4.5, Y, 2.5, true));
        assertEquals("only up to the stretch's last move", 0, KinematicController.syncedPosition(moves, 0, 0, 4.5, Y, 2.5, true));
        assertEquals("on the ground one up is not the dest", 0, KinematicController.syncedPosition(moves, 0, 2, 2.5, Y + 1, 1.5, true));
        assertEquals("airborne within a block counts", 1, KinematicController.syncedPosition(moves, 0, 2, 2.5, Y + 0.4, 1.5, false));
        assertEquals("never behind pathPosition", 1, KinematicController.syncedPosition(moves, 1, 2, 0.5, Y, 0.5, true));
    }

    @Test
    public void straightLinesNeedNoGroundCheck() {
        // unchanged Legato behaviour: MovementStraight is drivable regardless of kinematicLine
        Baritone.settings().kinematicLine.value = false;
        assertTrue(KinematicController.drivable(new MovementStraight(NONE, new BetterBlockPos(1, Y, 1), new BetterBlockPos(9, Y, 4))));
        assertEquals(0, KinematicLine.decisions(KinematicLine.OFF));
    }
}
