package baritone.api.agent;

// Ostinato: one chat request. The API key is not part of the request body.

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AgentRequest {

    public final String model;
    public final List<Message> messages;

    public AgentRequest(String model, List<Message> messages) {
        this.model = model == null ? "" : model;
        this.messages = messages == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(messages));
    }

    public static final class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role == null ? "user" : role;
            this.content = content == null ? "" : content;
        }
    }
}
