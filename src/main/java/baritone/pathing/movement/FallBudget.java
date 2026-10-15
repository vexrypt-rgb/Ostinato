package baritone.pathing.movement;

/**
 * How far a fall may go and still leave a health floor. Damage is {@code blocks - 3} half-hearts
 * before armor, which only makes the hit smaller, so the budget is the pessimistic one.
 * The settings safe height is always allowed: a 3 block fall deals nothing.
 */
public final class FallBudget {

    /** Half-hearts to keep when experimental movement is off. */
    public static final double FLOOR = 10;
    /** Tallest damaging fall planned without experimental movement. 6 blocks is 3 damage. */
    public static final int CAP = 6;

    private FallBudget() {}

    public static int maxBlocks(double health, double minLeft, int safe, int cap) {
        int affordable = (int) Math.floor(health - minLeft) + 3;
        if (affordable < safe) {
            return safe;
        }
        return Math.min(cap, affordable);
    }
}
