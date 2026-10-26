package baritone.agent;

// Ostinato: built-in OpenAI-compatible endpoints. Ollama is the local backup and needs no key.

public enum AgentPresets {

    GROQ("groq", "https://api.groq.com/openai/v1", "openai/gpt-oss-20b", "GROQ_API_KEY"),
    GEMINI("gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-3.8-flash", "GEMINI_API_KEY"),
    OPENROUTER("openrouter", "https://openrouter.ai/api/v1", "openrouter/free", "OPENROUTER_API_KEY"),
    OLLAMA("ollama", "http://localhost:11434/v1", "llama3.2", "");

    public static final AgentPresets[] CLOUD = {GROQ, GEMINI, OPENROUTER};

    public final String id;
    public final String baseUrl;
    public final String model;
    /** Environment variable that holds this provider's key. Empty for Ollama. */
    public final String envVar;

    AgentPresets(String id, String baseUrl, String model, String envVar) {
        this.id = id;
        this.baseUrl = baseUrl;
        this.model = model;
        this.envVar = envVar;
    }

    public static AgentPresets byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String needle = id.trim().toLowerCase(java.util.Locale.ROOT);
        for (AgentPresets preset : values()) {
            if (preset.id.equals(needle)) {
                return preset;
            }
        }
        return null;
    }

    public boolean needsKey() {
        return this != OLLAMA;
    }
}
