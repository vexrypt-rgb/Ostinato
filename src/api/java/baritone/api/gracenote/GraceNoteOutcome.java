/*
 * Ostinato: new file (Grace Note). Not part of upstream Baritone.
 */
package baritone.api.gracenote;

/**
 * One finished episode. Each stage is only claimed when it was seen, as a tick stamp (-1 when it was not):
 * requested (control asked for), started (the path executor was seen held), executed (the bucket was seen emptied in
 * hand), acknowledged (the water was seen where it was aimed and still there a tick later), avoided (in water before
 * fall damage could apply), landed (at rest on ground or in fluid), released (the hold was seen gone). A cancelled
 * request is not a stop, a timeout is not a release, a finished maneuver is not survival.
 *
 * @param measuredDamage health lost from the episode's start to the end of the settle window; includes any other damage
 *                       taken meanwhile
 * @param recovered      the water was picked back up
 */
public record GraceNoteOutcome(long sequence, long episode, IGraceNote.Mode mode, IGraceNote.Emergency emergency,
                               IGraceNote.Response response, IGraceNote.Result result, String reason,
                               float predictedDamage, boolean predictedLethal, float measuredDamage, boolean alive,
                               long startTick, long requested, long started, long executed, long acknowledged,
                               long avoided, long landed, long released, long endTick, boolean recovered) {

    public boolean survived() {
        return alive && landed >= 0;
    }

    public boolean tookControl() {
        return started >= 0;
    }
}
