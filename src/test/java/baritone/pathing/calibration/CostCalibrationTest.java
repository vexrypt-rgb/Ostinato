/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code.
 */
package baritone.pathing.calibration;

import baritone.pathing.calc.PathBench;
import baritone.pathing.movement.Moves;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class CostCalibrationTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Test
    public void aggregatesMedianRatioAndFailureRate() {
        List<String> s = new ArrayList<>();
        s.add("move,class,planned,actual,success,dy,wet");
        for (int i = 0; i < 9; i++) s.add("TRAVERSE_NORTH,MovementTraverse,4.000," + (5 + (i % 3)) + ",true,0,false");
        s.add("TRAVERSE_NORTH,MovementTraverse,4.000,40,false,0,false");
        List<String> rows = CostCalibration.aggregate(s);
        assertEquals("move,n,ratio,pfail", rows.get(0));
        assertEquals("TRAVERSE_NORTH,10,1.5000,0.1000", rows.get(1));
    }

    @Test
    public void tableAppliesOnlyWithEnoughSamples() {
        List<String> rows = List.of("move,n,ratio,pfail", "TRAVERSE_NORTH,10,1.5,0.1", "ASCEND_NORTH,3,2.0,0.5");
        CostCalibration.Table t = CostCalibration.fromRows(rows, true, true, 5, 200);
        assertEquals(1.5, t.mult[Moves.TRAVERSE_NORTH.ordinal()], 1e-9);
        assertEquals(20, t.add[Moves.TRAVERSE_NORTH.ordinal()], 1e-9);
        assertEquals(1, t.mult[Moves.ASCEND_NORTH.ordinal()], 1e-9);
        assertEquals(0, t.add[Moves.ASCEND_NORTH.ordinal()], 1e-9);
        CostCalibration.Table off = CostCalibration.fromRows(rows, false, false, 5, 200);
        assertEquals(1, off.mult[Moves.TRAVERSE_NORTH.ordinal()], 1e-9);
    }
}
