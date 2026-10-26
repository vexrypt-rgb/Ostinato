package baritone.api.agent;

// Ostinato: a bot action the chat hook can enqueue. Full multi-step tool calling is not this interface.

import java.util.List;
import java.util.Map;

public interface AgentAction {

    String name();

    String description();

    /** Parameter names advertised to the provider. Values are strings. */
    List<String> parameterNames();

    /**
     * A Baritone command line without the chat prefix, or null when the arguments must not run.
     * Implementations never put an API key on the command line.
     */
    String toCommand(Map<String, String> args);
}
