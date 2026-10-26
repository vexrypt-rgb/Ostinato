package baritone.api.agent;

// Ostinato: one provider reply. Tool calls may be empty; the chat hook still accepts a TOOL line in the text.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AgentReply {

    public final String content;
    public final List<AgentToolCall> toolCalls;

    public AgentReply(String content, List<AgentToolCall> toolCalls) {
        this.content = content == null ? "" : content;
        this.toolCalls = toolCalls == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(toolCalls));
    }
}
