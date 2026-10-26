package baritone.command.defaults;

// Ostinato: #agent <message> sends one chat turn when agentApi and agentChat are both on.
// The HTTP call is async. The reply is applied on the client thread. Off, this returns without a request.
// Saved routes and status stay local. Dry-run prints commands and does not run them.

import baritone.Baritone;
import baritone.agent.AgentChat;
import baritone.agent.AgentConfig;
import baritone.agent.AgentMemory;
import baritone.agent.AgentPolicy;
import baritone.agent.AgentSession;
import baritone.agent.OpenAiChatClient;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.agent.AgentReply;
import baritone.api.agent.AgentRequest;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class AgentCommand extends Command {

    public static final Path MEMORY_FILE = Path.of("baritone", "agent-memory.jsonl");

    public AgentCommand(IBaritone baritone) {
        super(baritone, "agent");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        StringBuilder text = new StringBuilder();
        while (args.hasAny()) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(args.getString());
        }
        String message = text.toString();
        Settings settings = Baritone.settings();
        String sender = ctx.player() == null ? "" : ctx.player().getName().getString();
        if (!AgentPolicy.ownerAllowed(settings.agentOwnerOnly.value, settings.agentOwner.value, sender)) {
            logDirect("agent: owner only");
            return;
        }
        boolean api = settings.agentApi.value;
        boolean chat = settings.agentChat.value;
        if ("status".equalsIgnoreCase(message.trim())) {
            // Ostinato: status is local. It prints the resolved provider and never the key.
            AgentChat.Outcome gate = AgentChat.begin(api, chat, "status");
            logDirect(gate.callProvider ? "agent on" : gate.chat);
            logDirect(AgentConfig.fromSettings().toString());
            if (settings.agentDryRun.value) {
                logDirect("agent dry-run");
            }
            return;
        }
        AgentChat.Outcome gate = AgentChat.begin(api, chat, message);
        if (!gate.callProvider) {
            logDirect(gate.chat);
            return;
        }
        String tools = settings.agentTools.value;
        try {
            AgentChat.RouteResult route = AgentChat.handleRoute(message, Path.of(settings.agentRouteDir.value), tools);
            if (route != null) {
                applyCommands(route.commands);
                logDirect(OpenAiChatClient.safeText(route.chat, AgentConfig.fromSettings().apiKey()));
                return;
            }
        } catch (IOException ex) {
            logDirect("agent: route not saved");
            return;
        }
        boolean locked = false;
        if (settings.agentOneSession.value) {
            if (!AgentSession.CLIENT.tryStart()) {
                logDirect("agent: busy");
                return;
            }
            locked = true;
        }
        try {
            ask(settings, message, tools, locked);
        } catch (RuntimeException ex) {
            if (locked) {
                AgentSession.CLIENT.finish();
            }
            logDirect("agent: " + OpenAiChatClient.safeText(ex.getMessage() == null
                    ? ex.getClass().getSimpleName() : ex.getMessage(), AgentConfig.fromSettings().apiKey()));
        }
    }

    private void ask(Settings settings, String message, String tools, boolean locked) {
        AgentConfig config = AgentConfig.fromSettings();
        logDirect("agent: asking " + OpenAiChatClient.safeText(config.provider + " (" + config.model + ")", config.apiKey()));
        List<AgentRequest.Message> memory = List.of();
        if (settings.agentMemory.value) {
            try {
                memory = AgentMemory.recent(MEMORY_FILE);
            } catch (IOException ex) {
                memory = List.of();
            }
        }
        List<AgentRequest.Message> prior = memory;
        boolean remember = settings.agentMemory.value;
        OpenAiChatClient client = new OpenAiChatClient(config, settings.agent429ToOllama.value, tools);
        client.complete(AgentChat.requestFor(config.model, prior, message)).whenComplete((reply, err) -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null) {
                if (locked) {
                    AgentSession.CLIENT.finish();
                }
                return;
            }
            // Ostinato: hop back to the client thread before chat or commands.
            minecraft.execute(() -> {
                try {
                    deliver(config, reply, err, tools, remember, message);
                } finally {
                    if (locked) {
                        AgentSession.CLIENT.finish();
                    }
                }
            });
        });
    }

    private void deliver(AgentConfig config, AgentReply reply, Throwable err, String tools, boolean remember, String message) {
        if (!Baritone.settings().agentApi.value || !Baritone.settings().agentChat.value) {
            return;
        }
        if (err != null) {
            Throwable cause = err.getCause() == null ? err : err.getCause();
            String text = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            logDirect("agent: " + OpenAiChatClient.safeText(text, config.apiKey()));
            return;
        }
        if (remember && reply != null) {
            try {
                AgentMemory.append(MEMORY_FILE, "user", message, config.apiKey());
                AgentMemory.append(MEMORY_FILE, "assistant", reply.content, config.apiKey());
            } catch (IOException ignored) {
                // A memory file must not block the reply, and its error must not print the key.
            }
        }
        AgentChat.Delivery delivery = AgentChat.deliver(reply, tools);
        applyCommands(delivery.commands);
        logDirect(OpenAiChatClient.safeText(delivery.chat, config.apiKey()));
    }

    private void applyCommands(List<String> commands) {
        String key = AgentConfig.fromSettings().apiKey();
        boolean dry = Baritone.settings().agentDryRun.value;
        for (String line : commands) {
            String shown = OpenAiChatClient.safeText(line, key);
            if (dry) {
                logDirect("agent dry-run: " + shown);
                continue;
            }
            try {
                if (!baritone.getCommandManager().execute(line)) {
                    logDirect("agent: no such command " + shown);
                }
            } catch (Throwable ignored) {
                logDirect("agent: could not run " + shown);
            }
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Ask the configured AI provider";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Sends one message to the OpenAI-compatible provider and prints the reply.",
                "Needs agentApi and agentChat. Both default off. The API key is never printed.",
                "Owner-only by default. Dry-run prints goto, mine, follow, and settings and does not run them.",
                "Saved routes: route save <name> <step> ; <step>, route run <name>, route list.",
                "One provider call at a time. HTTP 429 retries once on Ollama. Chat memory stays on disk without the key.",
                "",
                "Usage:",
                "> agent <message>",
                "> agent status",
                "> agent route list");
    }
}
