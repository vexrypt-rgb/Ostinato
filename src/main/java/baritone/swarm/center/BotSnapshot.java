package baritone.swarm.center;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;

/**
 * Ostinato: one mission-control tile. Health and hunger below zero mean the client has no number yet.
 * {@code memory} is blank when spawn status does not carry it.
 */
public record BotSnapshot(
        String name,
        int health,
        int hunger,
        String task,
        ExecutionState execution,
        ObservationState observation,
        String link,
        String memory,
        String server
) {
    public BotSnapshot {
        name = name == null || name.isBlank() ? "bot" : name.trim();
        task = task == null ? "" : task;
        link = link == null ? "" : link;
        memory = memory == null ? "" : memory;
        server = server == null ? "" : server;
        if (observation == null) {
            observation = ObservationState.UNKNOWN;
        }
    }
}
