package baritone.swarm.spawn;

/** Ostinato: what one child signs in as. The access token stays off {@code toString}. */
public record SpawnSession(String username, String uuid, String accessToken) {

    public boolean online() {
        return accessToken != null && !accessToken.isBlank();
    }

    @Override
    public String toString() {
        return "spawn session [redacted]";
    }
}
