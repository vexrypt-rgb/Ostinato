package baritone.swarm.center.feed;

/**
 * Ostinato: Phase 3 read API v1. Where the window gets its data. {@link FeedReader} reads the shared feed directory;
 * tests and {@code --fake} use {@code FakeSwarm}. A source never blocks for long, never throws, and never writes to a
 * bot: closing the window just stops calling it.
 */
public interface SwarmSource {

    /** The current snapshot. Called on the window's refresh timer (about twice a second). */
    SwarmView snapshot();
}
