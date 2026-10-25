/*
 * This file is part of Ostinato (a Baritone fork). New file: Ostinato code, replaces no Baritone code.
 */
package baritone.utils;

import baritone.api.utils.input.Input;

import java.util.Map;

/**
 * Ostinato: a hand, not a teleport. One 50 ms tick can only turn so fast, can only speed up so fast, and can only
 * change two keys. Pure aside from the small bit of state a hand has (how fast it is already moving, which keys are
 * down). Callers pass {@code enabled=false} to leave the requested look untouched. See PhysicalInputTest.
 */
public final class PhysicalInput {

    /** Fast flick, degrees in one tick. A mouse does not do 180. */
    public static final float MAX_YAW_SPEED = 60f;
    public static final float MAX_PITCH_SPEED = 45f;
    /** From rest, the first tick of a flick. Reaching full speed takes more than one tick. */
    public static final float MAX_YAW_ACCEL = 25f;
    public static final float MAX_PITCH_ACCEL = 20f;
    /** Thumb on a stick: full tilt is not instant. */
    public static final float MAX_STICK_SPEED = 0.65f;
    public static final float MAX_STICK_ACCEL = 0.40f;
    /** Two fingers can change state in one tick. A third key waits. */
    public static final int MAX_EDGES_PER_TICK = 2;

    private PhysicalInput() {}

    /**
     * The look. {@link #emit} with {@code enabled} false returns {@code toYaw}/{@code toPitch} and forgets its speed,
     * so the off path does not depend on what a previous tick did.
     */
    public static final class Look {
        private float velY, velP;
        private float baseVelY, baseVelP, baseFromY, baseFromP;
        private int tick = Integer.MIN_VALUE;
        private final float[] out = new float[2];
        private final float[] ny = new float[1];
        private final float[] np = new float[1];

        public void disarm() {
            velY = velP = 0f;
            tick = Integer.MIN_VALUE;
        }

        /**
         * One step from the angle at the first call this {@code tick} toward {@code toYaw}/{@code toPitch}.
         * A second call in the same tick starts over from that same angle, so boat aim and the look behavior
         * do not add two flicks.
         */
        public float[] emit(boolean enabled, int tick, float fromY, float fromP, float toY, float toP, float mouseStep) {
            if (!enabled) {
                disarm();
                out[0] = toY;
                out[1] = toP;
                return out;
            }
            if (this.tick != tick) {
                this.tick = tick;
                baseVelY = velY;
                baseVelP = velP;
                baseFromY = fromY;
                baseFromP = fromP;
            }
            float y = axis(baseFromY, toY, baseVelY, MAX_YAW_SPEED, MAX_YAW_ACCEL, true, ny);
            float p = axis(baseFromP, toP, baseVelP, MAX_PITCH_SPEED, MAX_PITCH_ACCEL, false, np);
            y = quantize(baseFromY, y, mouseStep);
            p = HumanInputCaps.clampPitch(quantize(baseFromP, p, mouseStep));
            velY = ny[0];
            velP = np[0];
            if (p >= 90f || p <= -90f) velP = 0f;
            out[0] = y;
            out[1] = p;
            return out;
        }
    }

    /** Keys already down, and how long each pending change has been waiting. */
    public static final class Chord {
        private static final Input[] KEYS = Input.values();
        private final boolean[] down = new boolean[KEYS.length];
        private final int[] age = new int[KEYS.length];
        private final int[] order = new int[KEYS.length];
        private final boolean[] picked = new boolean[KEYS.length];

        public void snap(Map<Input, Boolean> desired) {
            for (int i = 0; i < KEYS.length; i++) {
                down[i] = on(desired, KEYS[i]);
                age[i] = 0;
            }
        }

        public boolean isDown(Input input) {
            return input != null && down[input.ordinal()];
        }

        /** Apply at most {@code maxEdges} differences. Releases first, then the change that has waited longest. */
        public int step(Map<Input, Boolean> desired, int maxEdges) {
            int pending = 0;
            for (int i = 0; i < KEYS.length; i++) {
                if (on(desired, KEYS[i]) != down[i]) {
                    order[pending++] = i;
                } else {
                    age[i] = 0;
                }
            }
            for (int a = 1; a < pending; a++) {
                int v = order[a];
                int b = a;
                while (b > 0 && after(order[b - 1], v, desired)) {
                    order[b] = order[b - 1];
                    b--;
                }
                order[b] = v;
            }
            int applied = 0;
            int allow = maxEdges < 0 ? 0 : maxEdges;
            for (int k = 0; k < pending && applied < allow; k++) {
                int i = order[k];
                down[i] = on(desired, KEYS[i]);
                age[i] = 0;
                picked[i] = true;
                applied++;
            }
            for (int k = 0; k < pending; k++) {
                int i = order[k];
                if (!picked[i]) age[i]++;
                picked[i] = false;
            }
            return applied;
        }

        /** {@code i} should be ordered after {@code j} (j is more urgent). */
        private boolean after(int i, int j, Map<Input, Boolean> desired) {
            boolean relI = !on(desired, KEYS[i]);
            boolean relJ = !on(desired, KEYS[j]);
            if (relI != relJ) return !relI;
            if (age[i] != age[j]) return age[i] < age[j];
            return i > j;
        }
    }

    /** A stick axis pair. Disarm forgets the position so the off path never reads it. */
    public static final class Stick {
        private float left, forward, velL, velF;
        private final float[] out = new float[2];

        public void disarm() {
            left = forward = velL = velF = 0f;
        }

        public float[] slew(float wantL, float wantF) {
            wantL = HumanInputCaps.clampAxis(wantL);
            wantF = HumanInputCaps.clampAxis(wantF);
            velL = slewAxis(velL, wantL - left);
            velF = slewAxis(velF, wantF - forward);
            if (passes(wantL - left, velL)) velL = wantL - left;
            if (passes(wantF - forward, velF)) velF = wantF - forward;
            left = HumanInputCaps.clampAxis(left + velL);
            forward = HumanInputCaps.clampAxis(forward + velF);
            out[0] = left;
            out[1] = forward;
            return out;
        }

        private static float slewAxis(float vel, float error) {
            float want = clamp(error, -MAX_STICK_SPEED, MAX_STICK_SPEED);
            return clamp(vel + clamp(want - vel, -MAX_STICK_ACCEL, MAX_STICK_ACCEL), -MAX_STICK_SPEED, MAX_STICK_SPEED);
        }

        private static boolean passes(float error, float vel) {
            return error == 0f || (vel * error > 0f && Math.abs(vel) > Math.abs(error));
        }
    }

    static float axis(float from, float to, float vel, float maxSpeed, float maxAccel, boolean wrap, float[] outVel) {
        if (!Float.isFinite(from)) from = 0f;
        if (!Float.isFinite(to)) {
            outVel[0] = clamp(vel + clamp(-vel, -maxAccel, maxAccel), -maxSpeed, maxSpeed);
            return from;
        }
        float delta = wrap ? HumanInputCaps.wrap(to - from) : (to - from);
        float want = clamp(delta, -maxSpeed, maxSpeed);
        float nv = clamp(vel + clamp(want - vel, -maxAccel, maxAccel), -maxSpeed, maxSpeed);
        if (passesTarget(delta, nv)) nv = delta;
        outVel[0] = nv;
        float next = from + nv;
        if (!wrap) {
            if (next > 90f) { next = 90f; outVel[0] = 0f; }
            else if (next < -90f) { next = -90f; outVel[0] = 0f; }
        }
        return next;
    }

    private static boolean passesTarget(float delta, float vel) {
        return delta == 0f || (vel * delta > 0f && Math.abs(vel) > Math.abs(delta));
    }

    /** Whole mouse counts, and never a bigger step than the continuous limit already allowed. */
    static float quantize(float from, float to, float mouseStep) {
        if (!(mouseStep > 0f) || !Float.isFinite(mouseStep) || !Float.isFinite(to)) return to;
        float d = to - from;
        float q = Math.round(d / mouseStep) * mouseStep;
        if (Math.abs(q) > Math.abs(d) + 1e-4f) {
            q = Math.copySign((float) (Math.floor(Math.abs(d) / mouseStep) * (double) mouseStep), d);
        }
        return from + q;
    }

    private static boolean on(Map<Input, Boolean> desired, Input key) {
        return desired != null && desired.getOrDefault(key, false);
    }

    private static float clamp(float v, float lo, float hi) {
        if (!Float.isFinite(v)) return 0f;
        return v < lo ? lo : Math.min(v, hi);
    }
}
