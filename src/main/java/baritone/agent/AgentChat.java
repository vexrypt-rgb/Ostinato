package baritone.agent;

// Ostinato: one in-game turn. Both agentApi and agentChat must be on before a provider is called.
// Tool calls enqueue at most a few existing commands. The multi-step tool loop is not here.

import baritone.api.agent.AgentReply;
import baritone.api.agent.AgentRequest;
import baritone.api.agent.AgentToolCall;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AgentChat {

    public static final int MAX_COMMANDS = 4;
    public static final int MAX_CHAT = 500;

    public static final String SYSTEM = "You are the Ostinato in-game assistant. Enqueue bot tasks with the tools. "
            + "goto walks to x, y, z or a block id. mine breaks a block id, with an optional count. "
            + "follow uses target players or entities, or entity plus a mob id, or player plus a name. "
            + "settings changes one Baritone setting by name and value. "
            + "pvp fights players, hostiles, or a player name. gear changes one gear or combat setting. "
            + "route saves or runs a named task route; op is save, run, or list. "
            + "Do not ask for or repeat API keys. Keep the reply to one or two sentences. "
            + "If tools are unavailable, write a line: TOOL name key=value.";

    private AgentChat() {}

    public static final class Outcome {
        public final boolean callProvider;
        public final String chat;

        Outcome(boolean callProvider, String chat) {
            this.callProvider = callProvider;
            this.chat = chat;
        }
    }

    public static final class Delivery {
        public final String chat;
        public final List<String> commands;

        Delivery(String chat, List<String> commands) {
            this.chat = chat;
            this.commands = commands;
        }
    }

    public static final class RouteResult {
        public final String chat;
        public final List<String> commands;

        RouteResult(String chat, List<String> commands) {
            this.chat = chat;
            this.commands = commands;
        }
    }

    /** No provider work when either switch is off or the message is empty. */
    public static Outcome begin(boolean agentApi, boolean agentChat, String message) {
        if (!agentApi) {
            return new Outcome(false, "agentApi is off");
        }
        if (!agentChat) {
            return new Outcome(false, "agentChat is off");
        }
        if (message == null || message.isBlank()) {
            return new Outcome(false, "usage: #agent <message>");
        }
        return new Outcome(true, null);
    }

    public static AgentRequest requestFor(String model, String message) {
        return requestFor(model, List.of(), message);
    }

    public static AgentRequest requestFor(String model, List<AgentRequest.Message> memory, String message) {
        List<AgentRequest.Message> messages = new ArrayList<>();
        messages.add(new AgentRequest.Message("system", SYSTEM));
        if (memory != null) {
            messages.addAll(memory);
        }
        messages.add(new AgentRequest.Message("user", message));
        return new AgentRequest(model, messages);
    }

    /**
     * Local route save, run, and list. Null when the message is a normal chat turn.
     * Run returns the saved steps. The caller still applies dry-run.
     */
    public static RouteResult handleRoute(String message, Path dir, String tools) throws IOException {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        if (!trimmed.regionMatches(true, 0, "route ", 0, 6)) {
            return null;
        }
        String rest = trimmed.substring(6).trim();
        String op = rest.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        String after = rest.length() > op.length() ? rest.substring(op.length()).trim() : "";
        if ("list".equals(op)) {
            List<String> names = RouteStore.list(dir);
            return new RouteResult(names.isEmpty() ? "agent routes: none" : "agent routes: " + String.join(", ", names),
                    List.of());
        }
        if ("run".equals(op)) {
            RouteStore.TaskRoute route;
            try {
                route = RouteStore.load(dir, after);
            } catch (IllegalArgumentException ex) {
                return new RouteResult("agent: no route", List.of());
            }
            if (route == null) {
                return new RouteResult("agent: no route " + after, List.of());
            }
            List<String> commands = new ArrayList<>();
            for (String step : route.steps) {
                if (RouteStore.stepAllowed(step, tools)) {
                    commands.add(step);
                }
            }
            return new RouteResult("agent route " + route.name + " v" + route.version + " (" + commands.size() + " steps)",
                    List.copyOf(commands));
        }
        if ("save".equals(op)) {
            int space = after.indexOf(' ');
            if (space <= 0) {
                return new RouteResult("usage: #agent route save <name> <step> ; <step>", List.of());
            }
            String name = after.substring(0, space).trim();
            List<String> steps = new ArrayList<>();
            for (String part : after.substring(space + 1).split(";")) {
                if (!part.isBlank()) {
                    steps.add(part.trim());
                }
            }
            try {
                RouteStore.TaskRoute saved = RouteStore.save(dir, name, steps, tools);
                return new RouteResult("saved " + saved.name + " v" + saved.version + " (" + saved.steps.size() + " steps)",
                        List.of());
            } catch (IllegalArgumentException ex) {
                return new RouteResult("agent: route not saved", List.of());
            }
        }
        return new RouteResult("usage: #agent route save|run|list", List.of());
    }

    public static Delivery deliver(AgentReply reply) {
        return deliver(reply, AgentPolicy.DEFAULT_TOOLS);
    }

    public static Delivery deliver(AgentReply reply, String tools) {
        List<AgentToolCall> calls = new ArrayList<>();
        if (reply != null && !reply.toolCalls.isEmpty()) {
            calls.addAll(reply.toolCalls);
        } else {
            calls.addAll(parseToolLines(reply == null ? "" : reply.content));
        }
        List<String> commands = new ArrayList<>();
        StringBuilder notes = new StringBuilder();
        int used = 0;
        for (AgentToolCall call : calls) {
            if (used >= MAX_COMMANDS) {
                break;
            }
            used++;
            if (!AgentPolicy.toolAllowed(tools, call.name)) {
                notes.append(" skipped ").append(call.name);
                continue;
            }
            if ("route".equalsIgnoreCase(call.name)) {
                notes.append(" route ").append(call.arguments.getOrDefault("op", "")).append(" ")
                        .append(call.arguments.getOrDefault("name", ""));
                continue;
            }
            String line = AgentActions.toCommand(call.name, call.arguments);
            if (line == null) {
                notes.append(" skipped ").append(call.name);
            } else {
                commands.add(line);
                notes.append(" queued ").append(line);
            }
        }
        String text = stripToolLines(reply == null ? "" : reply.content);
        if (text.length() > MAX_CHAT) {
            text = text.substring(0, MAX_CHAT) + "...";
        }
        String chat = (text.isBlank() ? "agent" : text) + notes;
        return new Delivery(chat.trim(), List.copyOf(commands));
    }

    static List<AgentToolCall> parseToolLines(String content) {
        List<AgentToolCall> out = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return out;
        }
        for (String raw : content.split("\\R")) {
            String line = raw.trim();
            if (!line.regionMatches(true, 0, "TOOL ", 0, 5)) {
                continue;
            }
            String rest = line.substring(5).trim();
            int space = rest.indexOf(' ');
            String name = space < 0 ? rest : rest.substring(0, space);
            String tail = space < 0 ? "" : rest.substring(space + 1).trim();
            Map<String, String> args = new LinkedHashMap<>();
            if (!tail.isEmpty()) {
                for (String part : tail.split("\\s+")) {
                    int eq = part.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    args.put(part.substring(0, eq).toLowerCase(Locale.ROOT), part.substring(eq + 1));
                }
            }
            out.add(new AgentToolCall(name, args));
        }
        return out;
    }

    static String stripToolLines(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (String raw : content.split("\\R")) {
            if (raw.trim().regionMatches(true, 0, "TOOL ", 0, 5)) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(raw.trim());
        }
        return text.toString().trim();
    }
}
