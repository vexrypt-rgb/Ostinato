package baritone.bombard;

import org.junit.Test;
import static org.junit.Assert.*;

public class AimWindowTest {
    @Test public void turnMustSettleBeforeJab() {
        var aim = new AimWindow();
        assertFalse(aim.ready(1, 7, 90, 20, 90));
        assertFalse(aim.ready(2, 7, 1, 20, 90));
        assertFalse(aim.ready(3, 7, 1, 20, 1));
        assertTrue(aim.ready(4, 7, 1, 20, 1));
        assertFalse(aim.ready(6, 7, 1, 20, 1));
    }
    @Test public void wraparoundAndRetarget() {
        var aim = new AimWindow();
        aim.ready(1, 7, 359, 0, 359);
        assertFalse(aim.ready(2, 7, 0, 0, 0));
        assertTrue(aim.ready(3, 7, 1, 0, 1));
        assertFalse(aim.ready(4, 8, 1, 0, 1));
    }
}
