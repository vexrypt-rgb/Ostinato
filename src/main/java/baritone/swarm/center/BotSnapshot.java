package baritone.swarm.center;

import baritone.api.swarm.protocol.ExecutionState;
import baritone.api.swarm.protocol.ObservationState;
import baritone.swarm.SwarmSample;

/**
 * Ostinato: one mission-control tile. Health and hunger below zero mean the client has no number yet.
 * {@code perf} is the bot's latest status sample (path time, tick rate, ping, RAM); every figure in it is
 * unknown for a bot that has not sent one, or is too old to send one at all.
 */
public record BotSnapshot(
        String name,
        int health,
        int hunger,
        String task,
        ExecutionState execution,
        ObservationState observation,
        String link,
        SwarmSample perf,
        String server
) {
    public BotSnapshot {
        name = name == null || name.isBlank() ? "bot" : name.trim();
        task = task == null ? "" : task;
        link = link == null ? "" : link;
        perf = perf == null ? SwarmSample.UNKNOWN : perf; // Ostinato: replaces the never-filled memory text.
        server = server == null ? "" : server;
        if (observation == null) {
            observation = ObservationState.UNKNOWN;
        }
    }
}
