/*
 * Ostinato 2.0 Phase 04 validation: a harness instrument. Not part of upstream Baritone.
 */
package baritone.utils;

/**
 * Client tick wall time, {@code Minecraft.tick} HEAD to RETURN (Baritone's post-tick listeners included). Off unless
 * the client was launched with {@code -Dostinato.tickClock=true}; with it off the two mixin hooks read one constant
 * and do nothing else.
 */
public final class TickClock {
    private TickClock() {}

    public static final boolean ON = Boolean.getBoolean("ostinato.tickClock");

    private static long start;
    /** Nanoseconds the last finished tick took; 0 until one has finished. */
    public static volatile long last;
    /** Ticks finished since launch. */
    public static volatile long count;

    /** {@code Minecraft.tick} HEAD. */
    public static void begin() {
        start = System.nanoTime();
    }

    /** {@code Minecraft.tick} RETURN. */
    public static void end() {
        if (start != 0) {
            last = System.nanoTime() - start;
            count++;
        }
    }
}
