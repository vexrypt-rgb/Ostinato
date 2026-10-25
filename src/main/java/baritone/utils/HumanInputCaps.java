/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.utils;

import baritone.Baritone;

/**
 * Ostinato: the last gate before a rotation or stick input reaches the player, so the bot can only produce what a
 * human with a mouse and keyboard or controller could: no turn bigger than a fast flick in one tick (no teleport aim),
 * pitch inside [-90, 90], no NaN, and a stick no further than full tilt. Pure functions; see HumanInputCapsTest.
 */
public final class HumanInputCaps {

    /** Fastest plausible flick, degrees per 50 ms tick (about 2400 deg/s, past top esports flicks). */
    public static final float DEFAULT_MAX_YAW_PER_TICK = 120f;
    public static final float DEFAULT_MAX_PITCH_PER_TICK = 90f;

    private HumanInputCaps() {}

    public static float maxYaw() {
        return Baritone.settings().humanInputCaps.value ? Baritone.settings().maxYawPerTick.value.floatValue() : Float.MAX_VALUE;
    }

    public static float maxPitch() {
        return Baritone.settings().humanInputCaps.value ? Baritone.settings().maxPitchPerTick.value.floatValue() : Float.MAX_VALUE;
    }

    /** Yaw one tick may reach from {@code from} toward {@code to}, turning the short way, at most {@code max} degrees. */
    public static float capYaw(float from, float to, float max) {
        if (!Float.isFinite(to)) return from;
        float d = wrap(to - from);
        if (d > max) d = max;
        if (d < -max) d = -max;
        return from + d;
    }

    /** Pitch one tick may reach, at most {@code max} degrees away and always inside [-90, 90]. */
    public static float capPitch(float from, float to, float max) {
        if (!Float.isFinite(to)) return clampPitch(from);
        float d = to - from;
        if (d > max) d = max;
        if (d < -max) d = -max;
        return clampPitch(from + d);
    }

    public static float clampPitch(float p) {
        return p < -90f ? -90f : Math.min(p, 90f);
    }

    /** A stick axis: full tilt is 1, NaN is centred. */
    public static float clampAxis(float v) {
        if (!Float.isFinite(v)) return 0f;
        return v < -1f ? -1f : Math.min(v, 1f);
    }

    static float wrap(float d) {
        d %= 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
