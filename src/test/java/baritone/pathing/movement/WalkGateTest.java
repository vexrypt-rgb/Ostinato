/*
 * Ostinato: new file (Presto v4). Not part of upstream Baritone.
 */
package baritone.pathing.movement;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato: Baritone's walk holds forward only once the move's yaw is within 60° of where it walks. presto-v4-fix7 lap
 * 22: a Traverse turned round on the one-wide walkway with forward held, yaw 45, 13, -19, -51, -83 for a target near
 * -100, and the player went from z 0.45 to 1.09 before the turn was done.
 */
public class WalkGateTest {

    private static float step(float from, float to) {
        float d = (to - from) % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return from + Math.max(-32f, Math.min(32f, d));
    }

    @Test
    public void reversalWaitsAFewTicks() {
        float yaw = 77f, to = -96f;
        int waited = 0;
        for (int t = 0; t < 8; t++) {
            yaw = step(yaw, to);
            if (!MovementHelper.walkGate(yaw, to)) break;
            waited++;
        }
        assertTrue("waited " + waited, waited >= 2 && waited <= 4);
    }

    @Test
    public void cornerWalksOn() {
        assertFalse("a 90° corner, one 32° step in", MovementHelper.walkGate(step(-90f, 0f), 0f));
        assertFalse("straight on", MovementHelper.walkGate(-90f, 270f));
        assertTrue("facing back", MovementHelper.walkGate(90f, -90f));
    }
}
