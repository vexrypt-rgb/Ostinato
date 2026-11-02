/*
 * Ostinato: new file (Presto v4). Not part of upstream Baritone.
 */
package baritone.behavior;

import baritone.api.utils.Rotation;
import baritone.utils.HumanInputCaps;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Ostinato: a silent look's turn gate. presto-v4-fix1: after the one-block gap the parkour walk-back left the camera
 * facing west (yaw ~80), the next Traverse aimed east (-90) silently, and with the human-input gate measured from the
 * camera the sent yaw stuck 120 short of the camera; the 32° aim step from there moved the player at about -75, which
 * walked it off the side of the one-wide walkway (laps 0, 1, 3, 5, 7, 9, 11 fell at z 1.6-2.3).
 */
public class SilentLookGateTest {

    private static final float GATE = HumanInputCaps.DEFAULT_MAX_YAW_PER_TICK;

    /** The aim processor's step: the short way from the last sent yaw, at most 32°. */
    private static float aimStep(float sent, float target) {
        float d = target - sent;
        d %= 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return sent + Math.max(-32f, Math.min(32f, d));
    }

    /** Yaw sent after {@code ticks} silent ticks toward {@code target}, the camera parked at {@code camera}. */
    private static float sentAfter(boolean fromSent, float camera, float sent, float target, int ticks) {
        for (int i = 0; i < ticks; i++) {
            float actual = aimStep(sent, target);
            Rotation from = fromSent
                    ? LookBehavior.gateFrom(LookBehavior.Target.Mode.SERVER, false, new Rotation(camera, 0), new Rotation(sent, 0))
                    : new Rotation(camera, 0);
            sent = HumanInputCaps.capYaw(from.getYaw(), actual, GATE);
        }
        return sent;
    }

    private static float off(float a, float b) {
        float d = (a - b) % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return Math.abs(d);
    }

    /** The defect: measured from the parked camera, the sent yaw never gets within the gate of a target behind it. */
    @Test
    public void cameraBasedGatePinsTheSilentYaw() {
        float sent = sentAfter(false, 76.6f, 76.6f, -90f, 40);
        assertEquals("pinned at the gate's edge", GATE, off(sent, 76.6f), 0.01f);
        assertTrue("and far from the target", off(sent, -90f) > 40f);
    }

    /** The fix: measured from the last sent rotation the silent look reaches the target, 32° a tick. */
    @Test
    public void sentBasedGateReachesTheTarget() {
        float sent = sentAfter(true, 76.6f, 76.6f, -90f, 6);
        assertEquals(0f, off(sent, -90f), 0.01f);
        // and a single tick never turns past the gate
        float one = sentAfter(true, 76.6f, 76.6f, -90f, 1);
        assertTrue(off(one, 76.6f) <= GATE + 1e-3f);
    }

    /** Only a silent, non-human look uses the sent rotation; a visible look or the human step keep the camera. */
    @Test
    public void baseFollowsWhatTheServerLastSaw() {
        Rotation camera = new Rotation(80, 5), sent = new Rotation(-60, -10);
        assertSame(sent, LookBehavior.gateFrom(LookBehavior.Target.Mode.SERVER, false, camera, sent));
        assertSame(camera, LookBehavior.gateFrom(LookBehavior.Target.Mode.CLIENT, false, camera, sent));
        assertSame(camera, LookBehavior.gateFrom(LookBehavior.Target.Mode.SERVER, true, camera, sent));
        assertSame("nothing sent yet", camera, LookBehavior.gateFrom(LookBehavior.Target.Mode.SERVER, false, camera, null));
    }
}
