/*
 * Ostinato: tests for the physical mouse and key gate. New file.
 */
package baritone.utils;

import baritone.api.utils.input.Input;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.Assert.*;

public class PhysicalInputTest {

    @Test
    public void settingDefaultsOff() throws Exception {
        Path p = Paths.get("src/api/java/baritone/api/Settings.java");
        if (!Files.exists(p)) p = Paths.get("..").resolve("src/api/java/baritone/api/Settings.java");
        String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        assertTrue(src.contains("physicalInput = new Setting<>(false)"));
    }

    @Test
    public void offLeavesATeleportAimUntouched() {
        PhysicalInput.Look look = new PhysicalInput.Look();
        float[] warmed = copy(look.emit(true, 1, 0f, 0f, 179f, 40f, 0f));
        assertEquals(PhysicalInput.MAX_YAW_ACCEL, warmed[0], 1e-4);
        float[] off = copy(look.emit(false, 2, 0f, 10f, 180f, 80f, 0f));
        assertEquals(180f, off[0], 0f);
        assertEquals(80f, off[1], 0f);
        // the off call forgot the flick, so the next on starts from rest again
        float[] again = copy(look.emit(true, 3, 0f, 0f, 179f, 0f, 0f));
        assertEquals(PhysicalInput.MAX_YAW_ACCEL, again[0], 1e-4);
    }

    @Test
    public void yawAcceleratesAndDoesNotSnap() {
        PhysicalInput.Look look = new PhysicalInput.Look();
        float[] a = copy(look.emit(true, 1, 0f, 0f, 179f, 0f, 0f));
        assertEquals(25f, a[0], 1e-4);
        float[] b = copy(look.emit(true, 2, a[0], 0f, 179f, 0f, 0f));
        assertEquals(75f, b[0], 1e-4);
        float[] c = copy(look.emit(true, 3, b[0], 0f, 179f, 0f, 0f));
        assertEquals(135f, c[0], 1e-4);
        float[] d = copy(look.emit(true, 4, c[0], 0f, 179f, 0f, 0f));
        assertEquals(179f, d[0], 1e-4);
        // a dead-behind aim is a tie; either way is at most one acceleration step, never the snap
        float[] behind = copy(new PhysicalInput.Look().emit(true, 1, 0f, 0f, 180f, 0f, 0f));
        assertEquals(PhysicalInput.MAX_YAW_ACCEL, Math.abs(behind[0]), 1e-4);
    }

    @Test
    public void aSecondAimInTheSameTickDoesNotStack() {
        PhysicalInput.Look once = new PhysicalInput.Look();
        float[] single = copy(once.emit(true, 1, 0f, 0f, 179f, 0f, 0f));
        PhysicalInput.Look twice = new PhysicalInput.Look();
        twice.emit(true, 1, 0f, 0f, 90f, 0f, 0f);
        float[] replaced = copy(twice.emit(true, 1, 50f, 0f, 179f, 0f, 0f));
        assertEquals(single[0], replaced[0], 1e-4);
        assertEquals(single[1], replaced[1], 1e-4);
    }

    @Test
    public void aFastFlickCannotReverseInOneTick() {
        PhysicalInput.Look look = new PhysicalInput.Look();
        float y = 0f;
        for (int t = 1; t <= 3; t++) {
            float[] s = copy(look.emit(true, t, y, 0f, 179f, 0f, 0f));
            y = s[0];
        }
        assertEquals(135f, y, 1e-3);
        float[] back = copy(look.emit(true, 4, y, 0f, y - 179f, 0f, 0f));
        assertTrue("still carried forward, got " + back[0], back[0] > y);
    }

    @Test
    public void pitchStaysInRangeAndNaNDoesNotTeleport() {
        PhysicalInput.Look look = new PhysicalInput.Look();
        float[] up = copy(look.emit(true, 1, 80f, 80f, 80f, 200f, 0f));
        assertEquals(90f, up[1], 1e-4);
        float[] held = copy(look.emit(true, 2, 15f, 30f, Float.NaN, Float.NaN, 0f));
        assertEquals(15f, held[0], 0f);
        assertEquals(30f, held[1], 0f);
        float[] down = copy(new PhysicalInput.Look().emit(true, 1, 0f, -80f, 0f, -400f, 0f));
        assertEquals(-90f, down[1], 1e-4);
    }

    @Test
    public void mouseCountsNeverExceedTheContinuousStep() {
        PhysicalInput.Look look = new PhysicalInput.Look();
        float[] q = copy(look.emit(true, 1, 0f, 0f, 179f, 0f, 10f));
        assertEquals(20f, q[0], 1e-4);
        assertTrue(q[0] <= PhysicalInput.MAX_YAW_ACCEL + 1e-3);
    }

    @Test
    public void stickDoesNotJumpToFullTilt() {
        PhysicalInput.Stick stick = new PhysicalInput.Stick();
        float[] a = copy(stick.slew(3f, Float.NaN));
        assertEquals(PhysicalInput.MAX_STICK_ACCEL, a[0], 1e-4);
        assertEquals(0f, a[1], 0f);
        assertTrue(Math.abs(a[0]) <= 1f);
        float[] b = copy(stick.slew(1f, -2f));
        assertEquals(1f, b[0], 1e-4);
        assertTrue(b[1] > -1f && b[1] < 0f);
        float[] c = copy(stick.slew(1f, -1f));
        assertEquals(-1f, c[1], 1e-4);
    }

    @Test
    public void chordChangesAtMostTwoKeysAndReleasesFirst() {
        PhysicalInput.Chord chord = new PhysicalInput.Chord();
        Map<Input, Boolean> all = new EnumMap<>(Input.class);
        for (Input in : Input.values()) all.put(in, true);
        int n = chord.step(all, PhysicalInput.MAX_EDGES_PER_TICK);
        assertEquals(2, n);
        assertTrue(chord.isDown(Input.MOVE_FORWARD));
        assertTrue(chord.isDown(Input.MOVE_BACK));
        assertFalse(chord.isDown(Input.JUMP));
        assertFalse(chord.isDown(Input.SPRINT));
        int down = 0;
        for (Input in : Input.values()) if (chord.isDown(in)) down++;
        assertEquals(2, down);

        // the two that are already down are free: two new keys can still change
        all.put(Input.MOVE_LEFT, true);
        all.put(Input.MOVE_RIGHT, true);
        chord.step(all, PhysicalInput.MAX_EDGES_PER_TICK);
        assertTrue(chord.isDown(Input.MOVE_LEFT));
        assertTrue(chord.isDown(Input.MOVE_RIGHT));
        assertFalse(chord.isDown(Input.JUMP));

        Map<Input, Boolean> stop = new EnumMap<>(Input.class);
        stop.put(Input.JUMP, true); // a new press, while four keys must be released
        chord.step(stop, PhysicalInput.MAX_EDGES_PER_TICK);
        assertFalse(chord.isDown(Input.MOVE_FORWARD));
        assertFalse(chord.isDown(Input.MOVE_BACK));
        assertFalse("a release uses the budget before a new press", chord.isDown(Input.JUMP));
        assertTrue(chord.isDown(Input.MOVE_LEFT));
    }

    @Test
    public void fullChordTakesMoreThanOneTick() {
        PhysicalInput.Chord chord = new PhysicalInput.Chord();
        Map<Input, Boolean> all = new EnumMap<>(Input.class);
        for (Input in : Input.values()) all.put(in, true);
        int ticks = 0;
        int held = 0;
        while (held < Input.values().length && ticks < 20) {
            chord.step(all, PhysicalInput.MAX_EDGES_PER_TICK);
            held = 0;
            for (Input in : Input.values()) if (chord.isDown(in)) held++;
            ticks++;
            assertTrue(held <= ticks * PhysicalInput.MAX_EDGES_PER_TICK);
        }
        assertEquals(Input.values().length, held);
        assertTrue(ticks >= 5);
    }

    private static float[] copy(float[] in) {
        return new float[]{in[0], in[1]};
    }
}
