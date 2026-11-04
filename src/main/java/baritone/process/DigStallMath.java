package baritone.process;

/**
 * Ostinato: how long a dig-stall swing may run before that block is given up.
 * {@code progressPerTick} is the fraction of the block removed each tick (1 breaks it immediately).
 */
public final class DigStallMath {

    private DigStallMath() {}

    public static int budgetTicks(double progressPerTick) {
        int ticks;
        if (!(progressPerTick > 0) || Double.isInfinite(progressPerTick)) ticks = 20 * 8;
        else ticks = (int) Math.ceil(1.0 / progressPerTick) + 40;
        if (ticks < 40) ticks = 40;
        if (ticks > 20 * 20) ticks = 20 * 20;
        return ticks;
    }
}
