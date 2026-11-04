package baritone.bombard;

import org.junit.Test;
import static org.junit.Assert.*;
import static baritone.bombard.ImpulseAttempt.Result.*;

public class ImpulseAttemptTest {
    @Test public void frictionIsNotAnImpulse() {
        var a = new ImpulseAttempt(10, -2, 0, 0.4, 0);
        assertEquals(WAITING, a.observe(11, true, -2 * 0.98 * 0.7, 0));
    }
    @Test public void commandIsNotAnEffect() {
        var a = new ImpulseAttempt(10, 0, 0, 0.4, 0);
        assertEquals(WAITING, a.observe(10, true, 0.4, 0));
        assertEquals(WAITING, a.observe(11, true, 0, 0));
        assertEquals(UNKNOWN, a.observe(23, true, 0, 0));
    }
    @Test public void directionMatters() {
        var a = new ImpulseAttempt(10, 0, 0, 0.4, 0);
        assertEquals(WAITING, a.observe(11, true, -0.4, 0));
        assertEquals(WAITING, a.observe(12, true, 0, 0.4));
        assertEquals(IMPULSE_OBSERVED, a.observe(13, true, 0.3, 0));
    }
    @Test public void disappearanceIsNotSuccess() {
        assertEquals(UNKNOWN, new ImpulseAttempt(10, 0, 0, 0.4, 0).observe(11, false, 0.4, 0));
    }
    @Test public void interruptionIsTerminal() {
        var a = new ImpulseAttempt(10, 0, 0, 0.4, 0);
        assertEquals(INTERRUPTED, a.interrupt());
        assertEquals(INTERRUPTED, a.observe(11, true, 0.4, 0));
    }
}
