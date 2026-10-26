package baritone.api.agent;

// Ostinato: one tool call. Arguments stay plain strings so the chat hook can map them onto commands.

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentToolCall {

    public final String name;
    public final Map<String, String> arguments;

    public AgentToolCall(String name, Map<String, String> arguments) {
        this.name = name == null ? "" : name;
        Map<String, String> copy = new LinkedHashMap<>();
        if (arguments != null) {
            for (Map.Entry<String, String> entry : arguments.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.arguments = Collections.unmodifiableMap(copy);
    }
}
