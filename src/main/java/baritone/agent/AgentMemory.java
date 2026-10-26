package baritone.agent;

// Ostinato: cross-session chat memory. The file stores role and text only, never an API key.

import baritone.api.agent.AgentRequest;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class AgentMemory {

    public static final int MAX_TURNS = 8;

    private AgentMemory() {}

    public static void append(Path file, String role, String content, String secret) throws IOException {
        String text = OpenAiChatClient.safeText(content == null ? "" : content, secret).replace('\n', ' ').trim();
        if (text.isEmpty()) {
            return;
        }
        List<AgentRequest.Message> turns = new ArrayList<>(recent(file));
        turns.add(new AgentRequest.Message(role == null ? "user" : role, text));
        while (turns.size() > MAX_TURNS) {
            turns.remove(0);
        }
        Files.createDirectories(file.getParent() == null ? Path.of(".") : file.getParent());
        StringBuilder out = new StringBuilder();
        for (AgentRequest.Message turn : turns) {
            JsonObject object = new JsonObject();
            object.addProperty("role", turn.role);
            object.addProperty("content", turn.content);
            out.append(object).append(System.lineSeparator());
        }
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
    }

    public static List<AgentRequest.Message> recent(Path file) throws IOException {
        List<AgentRequest.Message> turns = new ArrayList<>();
        if (file == null || !Files.isRegularFile(file)) {
            return turns;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            JsonObject object = JsonParser.parseString(line).getAsJsonObject();
            String role = object.has("role") ? object.get("role").getAsString() : "user";
            String content = object.has("content") && !object.get("content").isJsonNull()
                    ? object.get("content").getAsString() : "";
            turns.add(new AgentRequest.Message(role, content));
        }
        if (turns.size() > MAX_TURNS) {
            return new ArrayList<>(turns.subList(turns.size() - MAX_TURNS, turns.size()));
        }
        return turns;
    }
}
