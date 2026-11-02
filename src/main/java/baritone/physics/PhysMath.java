/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.physics;

/** Ostinato: the parts of vanilla's Mth the engine needs, bit for bit (sin/cos are vanilla's 65536-entry table). */
public final class PhysMath {
    private PhysMath() {}

    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) SIN[i] = (float) Math.sin(i / 10430.378350470453);
    }

    public static float sin(double r) {
        return SIN[(int) ((long) (r * 10430.378350470453) & 0xFFFFL)];
    }

    public static float cos(double r) {
        return SIN[(int) ((long) (r * 10430.378350470453 + 16384.0) & 0xFFFFL)];
    }

    /** Mth.equal(double, double). */
    public static boolean equal(double a, double b) {
        return Math.abs(b - a) < (double) 1.0E-5f;
    }

    public static int floor(double v) {
        return (int) Math.floor(v);
    }

    public static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    public static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    public static double frac(double v) {
        return v - Math.floor(v);
    }

    public static int sign(double v) {
        return v == 0 ? 0 : v > 0 ? 1 : -1;
    }

    /** LivingEntity.computeModifiedFriction. */
    public static float modifiedFriction(float friction, float modifier) {
        return clamp(1.0f - (1.0f - friction) * modifier, 0.0f, 1.0f);
    }

    /** BlockPos.asLong packing (x 26 bits, z 26 bits, y 12 bits). */
    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    public static int unpackX(long p) {
        return (int) (p >> 38);
    }

    public static int unpackY(long p) {
        return (int) (p << 52 >> 52);
    }

    public static int unpackZ(long p) {
        return (int) (p << 26 >> 38);
    }
}
