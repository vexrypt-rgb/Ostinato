package baritone.pathing.movement;

/**
 * How far a fall may go and still leave a health floor. Damage is {@code blocks - 3} half-hearts
 * before armor, which only makes the hit smaller, so the budget is the pessimistic one.
 * The settings safe height is always allowed: a 3 block fall deals nothing.
 */
public final class FallBudget {

    /** Half-hearts to keep when experimental movement is off. */
    public static final double FLOOR = 10;
    /**
     * Tallest damaging fall planned without experimental movement: 13 blocks is 10 damage, which from full health
     * lands exactly on {@link #FLOOR}, so health is what really limits it. Maps with fall damage on expect drops like
     * that (an 11 block roof drop costs 4 hearts), and the cost ({@code fallDamageCost} per half-heart) keeps them a
     * last resort. The executor asks again with the health it has before stepping off.
     */
    public static final int CAP = 13;

    private FallBudget() {}

    public static int maxBlocks(double health, double minLeft, int safe, int cap) {
        int affordable = (int) Math.floor(health - minLeft) + 3;
        if (affordable < safe) {
            return safe;
        }
        return Math.min(cap, affordable);
    }
}
