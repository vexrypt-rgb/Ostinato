package baritone.command.defaults;

import baritone.pathing.CostCalibration;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class TimingsCommandTest {

    @Test
    public void listsFamiliesSorted() {
        CostCalibration.reset();
        try {
            assertEquals(0, TimingsCommand.lines().size());
            CostCalibration.record("TRAVERSE", 10, 15);
            CostCalibration.record("TRAVERSE", 10, 5);
            CostCalibration.record("ASCEND", 4, 8);
            List<String> l = TimingsCommand.lines();
            assertEquals(2, l.size());
            assertEquals(String.format("ASCEND: 1, %.3f, %.3f", 2.0, 1.0), l.get(0));
            assertEquals(String.format("TRAVERSE: 2, %.3f, %.3f", 1.0, 1.0), l.get(1));
        } finally {
            CostCalibration.reset();
        }
    }
}
