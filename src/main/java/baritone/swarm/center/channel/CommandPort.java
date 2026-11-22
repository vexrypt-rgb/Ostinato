package baritone.swarm.center.channel;

/**
 * Ostinato: Phase 3. How the window sends a {@link CenterRequest}. {@link CenterClient} is the real one (loopback
 * socket to the command host); UI tests use {@code FakeCommandPort}. Implementations never throw: a failure is a
 * refused reply with a reason. May block for up to a few seconds, so the window calls it off the event thread.
 */
@FunctionalInterface
public interface CommandPort {
    CenterReply send(CenterRequest request);
}
