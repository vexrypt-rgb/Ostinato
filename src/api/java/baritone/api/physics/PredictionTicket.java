/*
 * Ostinato: new file (predictive physics engine). Not part of upstream Baritone.
 */
package baritone.api.physics;

/** Ostinato: a submitted {@link PredictionRequest}. Poll it; nothing on it blocks. */
public interface PredictionTicket {
    PredictionRequest request();

    boolean isDone();

    /** Null until done; then never null (a cancelled, expired or rejected ticket has a result saying so). */
    PredictionResult result();

    /** Drop the request if it has not run; a running one finishes but its result is discarded. */
    void cancel();
}
