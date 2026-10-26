package baritone.api.agent;

// Ostinato: swappable OpenAI-compatible chat provider. Implementations must call the network off the game thread.

import java.util.concurrent.CompletableFuture;

public interface AgentChatProvider {

    /** Provider id such as groq, gemini, openrouter, or ollama. Never a secret. */
    String id();

    /**
     * One chat completion. The future completes off the game thread.
     * Callers that touch the world hop back to the client thread themselves.
     */
    CompletableFuture<AgentReply> complete(AgentRequest request);
}
