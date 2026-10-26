package baritone.agent;

// Ostinato: one provider turn on the existing agent session. The harness does not path, fight, or equip.
// Dry-run is the agent's own switch. This class does not turn it off.
// Both agentApi and agentChat have to be on or the turn is skipped and the race is still scored.

import baritone.api.agent.AgentReply;
import baritone.api.agent.AgentRequest;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public final class SessionOvernightAgent implements OvernightAgent {

    private final AgentSession session;
    private final boolean oneSession;
    private final boolean agentApi;
    private final boolean agentChat;
    private final String tools;
    private final boolean retryOn429;
    private final boolean memory;
    private final java.nio.file.Path memoryFile;
    private final Consumer<List<String>> apply;
    private final java.util.function.Supplier<AgentConfig> config;

    private CompletableFuture<AgentReply> future;
    private OvernightAgent.AgentTick done;
    private boolean locked;
    private String promptSent = "";
    private String key = "";
    private String provider = "local";
    private String model = "";

    public SessionOvernightAgent(AgentSession session, boolean oneSession, boolean agentApi, boolean agentChat,
            String tools, boolean retryOn429, boolean memory, java.nio.file.Path memoryFile,
            java.util.function.Supplier<AgentConfig> config, Consumer<List<String>> apply) {
        this.session = session == null ? new AgentSession() : session;
        this.oneSession = oneSession;
        this.agentApi = agentApi;
        this.agentChat = agentChat;
        this.tools = tools;
        this.retryOn429 = retryOn429;
        this.memory = memory;
        this.memoryFile = memoryFile;
        this.config = config;
        this.apply = apply;
    }

    @Override
    public void begin(HypothesisRun run) {
        future = null;
        done = null;
        locked = false;
        promptSent = run.prompt == null || run.prompt.isBlank() ? run.hypothesis : run.prompt;
        AgentChat.Outcome gate = AgentChat.begin(agentApi, agentChat, promptSent);
        if (!gate.callProvider) {
            done = new OvernightAgent.AgentTick(OvernightAgent.AgentTick.Status.SKIPPED, "local", "", gate.chat);
            return;
        }
        if (oneSession && !session.tryStart()) {
            done = new OvernightAgent.AgentTick(OvernightAgent.AgentTick.Status.ERROR, "local", "", "agent: busy");
            return;
        }
        locked = oneSession;
        AgentConfig resolved = config.get();
        key = resolved.apiKey();
        provider = resolved.provider;
        model = resolved.model;
        List<AgentRequest.Message> prior = List.of();
        if (memory && memoryFile != null) {
            try {
                prior = AgentMemory.recent(memoryFile);
            } catch (java.io.IOException ex) {
                prior = List.of();
            }
        }
        OpenAiChatClient client = new OpenAiChatClient(resolved, retryOn429, tools);
        future = client.complete(AgentChat.requestFor(resolved.model, prior, promptSent));
    }

    @Override
    public OvernightAgent.AgentTick poll(long nowMs) {
        if (done != null) {
            return done;
        }
        if (future == null || !future.isDone()) {
            return OvernightAgent.AgentTick.pending();
        }
        try {
            AgentReply reply = future.get();
            AgentChat.Delivery delivery = AgentChat.deliver(reply, tools);
            if (apply != null && delivery.commands != null && !delivery.commands.isEmpty()) {
                apply.accept(delivery.commands);
            }
            if (memory && memoryFile != null && reply != null) {
                try {
                    AgentMemory.append(memoryFile, "user", promptSent, key);
                    AgentMemory.append(memoryFile, "assistant", reply.content, key);
                } catch (java.io.IOException ignored) {
                    // Memory must not block the score, and the error must not print the key.
                }
            }
            done = new OvernightAgent.AgentTick(OvernightAgent.AgentTick.Status.DONE, provider, model,
                    OpenAiChatClient.safeText(delivery.chat, key));
        } catch (Exception ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            String text = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            done = new OvernightAgent.AgentTick(OvernightAgent.AgentTick.Status.ERROR, provider, model,
                    OpenAiChatClient.safeText(text, key));
        } finally {
            unlock();
        }
        return done;
    }

    @Override
    public void cancel() {
        if (future != null) {
            future.cancel(true);
        }
        unlock();
    }

    private void unlock() {
        if (locked) {
            session.finish();
            locked = false;
        }
    }
}
