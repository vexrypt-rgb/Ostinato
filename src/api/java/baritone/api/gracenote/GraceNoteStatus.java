/*
 * Ostinato: new file (Grace Note). Not part of upstream Baritone.
 */
package baritone.api.gracenote;

/**
 * Grace Note on one tick.
 *
 * @param episode        the current or last episode's number, 0 before the first
 * @param reason         why the phase is what it is (a verdict such as TOO_LATE, or the last transition's cause)
 * @param landingTicks   ticks to the predicted landing when last evaluated, -1 none
 * @param reachTick      first tick the landing block is within the bucket's reach, -1 never
 * @param clickTick      the planned tick to empty the bucket (relative to evaluation), -1 none
 * @param confidence     the forecast's PPE confidence, "NONE" without one
 * @param threatLevel    the Threat Assessment's highest level on the snapshot read (context only, never a trigger)
 */
public record GraceNoteStatus(long tick, IGraceNote.Mode mode, IGraceNote.Phase phase, long episode,
                              IGraceNote.Emergency emergency, IGraceNote.Response response, boolean holdsControl,
                              String reason, float predictedDamage, boolean lethal, int landingTicks, int reachTick,
                              int clickTick, String confidence, int threatLevel) {

    public static GraceNoteStatus idle(long tick, IGraceNote.Mode mode) {
        return new GraceNoteStatus(tick, mode, IGraceNote.Phase.MONITORING, 0, IGraceNote.Emergency.NONE,
                IGraceNote.Response.NONE, false, "IDLE", 0, false, -1, -1, -1, "NONE", 0);
    }
}
