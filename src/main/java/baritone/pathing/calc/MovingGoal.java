package baritone.pathing.calc;

/**
 * A goal that moves (a followed entity, a chase) used to throw away the path the moment its end
 * left the goal. If the next stretch still gets closer, keep walking it and replan from a point ahead.
 */
public final class MovingGoal {

    public enum Action { KEEP, RETARGET, CANCEL }

    private MovingGoal() {}

    /**
     * @param destWasInGoal the path was planned to finish inside its goal
     * @param destStillInGoal that same end block is inside the goal as it stands now
     * @param hereH         heuristic of the new goal at the position we are on
     * @param aheadH        heuristic of the new goal at the horizon position
     */
    public static Action decide(boolean destWasInGoal, boolean destStillInGoal, double hereH, double aheadH) {
        if (!destWasInGoal || destStillInGoal) {
            return Action.KEEP;
        }
        if (aheadH <= hereH + 1e-6) {
            return Action.RETARGET;
        }
        return Action.CANCEL;
    }

    /** Index in the position list to start the replacement search. */
    public static int horizonIndex(int positionCount, int cursor, int minAhead, int maxAhead) {
        if (positionCount <= 0) {
            return 0;
        }
        int at = Math.max(0, Math.min(cursor, positionCount - 1));
        int left = positionCount - 1 - at;
        if (left <= minAhead) {
            return at;
        }
        return at + Math.min(maxAhead, Math.max(minAhead, left / 2));
    }
}
