package baritone.agent;

// Ostinato: OpenAI-compatible POST {base}/chat/completions via java.net.http.
// sendAsync runs off the caller. Redirects are refused so the key stays on the chosen host.
// The key is an Authorization header only and is stripped from error text.

import baritone.api.agent.AgentAction;
import baritone.api.agent.AgentChatProvider;
import baritone.api.agent.AgentReply;
import baritone.api.agent.AgentRequest;
import baritone.api.agent.AgentToolCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public final class OpenAiChatClient implements AgentChatProvider {

    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(45);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final AgentConfig config;
    private final HttpClient http;
    /** Null means a 429 is a normal failure. The supplier is used at most once, and never for Ollama. */
    private final Supplier<AgentConfig> on429;
    /** Null advertises every built-in tool. A csv limits the schema to the whitelist. */
    private final String toolCsv;

    public OpenAiChatClient(AgentConfig config) {
        this(config, HTTP, null, null);
    }

    /** When retryOn429 is on and this host is not Ollama, one 429 retries the same turn against Ollama. */
    public OpenAiChatClient(AgentConfig config, boolean retryOn429) {
        this(config, HTTP, retrySupplier(config, retryOn429), null);
    }

    public OpenAiChatClient(AgentConfig config, boolean retryOn429, String toolCsv) {
        this(config, HTTP, retrySupplier(config, retryOn429), toolCsv);
    }

    OpenAiChatClient(AgentConfig config, HttpClient http) {
        this(config, http, null, null);
    }

    OpenAiChatClient(AgentConfig config, HttpClient http, Supplier<AgentConfig> on429) {
        this(config, http, on429, null);
    }

    private OpenAiChatClient(AgentConfig config, HttpClient http, Supplier<AgentConfig> on429, String toolCsv) {
        this.config = config;
        this.http = http;
        this.on429 = on429;
        this.toolCsv = toolCsv;
    }

    private static Supplier<AgentConfig> retrySupplier(AgentConfig config, boolean retryOn429) {
        if (!retryOn429 || config == null || "ollama".equals(config.provider)) {
            return null;
        }
        String model = config.model;
        return () -> AgentConfig.resolve("ollama", "", model, "", null);
    }

    @Override
    public String id() {
        return config.provider;
    }

    public static URI chatUri(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + "/chat/completions");
    }

    @Override
    public CompletableFuture<AgentReply> complete(AgentRequest request) {
        String key = config.apiKey();
        HttpRequest.Builder builder = HttpRequest.newBuilder(chatUri(config.baseUrl))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(request)));
        if (config.hasKey()) {
            builder.header("Authorization", "Bearer " + key);
        }
        HttpRequest httpRequest = builder.build();
        return http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    // Ostinato: one 429 retry, composed. Never join here; that deadlocks the HTTP thread.
                    if (response.statusCode() == 429 && on429 != null && !"ollama".equals(config.provider)) {
                        AgentConfig fallback = on429.get();
                        if (fallback != null) {
                            return new OpenAiChatClient(fallback, http, null).complete(request);
                        }
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        CompletableFuture<AgentReply> failed = new CompletableFuture<>();
                        failed.completeExceptionally(new IllegalStateException(
                                failureMessage(response.statusCode(), response.body(), key)));
                        return failed;
                    }
                    return CompletableFuture.completedFuture(parseSuccess(response.body()));
                })
                .exceptionally(err -> {
                    Throwable cause = err.getCause() == null ? err : err.getCause();
                    if (cause instanceof IllegalStateException && cause.getMessage() != null
                            && cause.getMessage().startsWith("provider returned")) {
                        throw new IllegalStateException(safeText(cause.getMessage(), key));
                    }
                    throw new IllegalStateException(limit(safeText(cause.getMessage() == null
                            ? cause.getClass().getSimpleName() : cause.getMessage(), key)));
                });
    }

    String body(AgentRequest request) {
        JsonObject root = new JsonObject();
        String model = request.model == null || request.model.isBlank() ? config.model : request.model;
        root.addProperty("model", model);
        JsonArray messages = new JsonArray();
        for (AgentRequest.Message message : request.messages) {
            JsonObject one = new JsonObject();
            one.addProperty("role", message.role);
            one.addProperty("content", message.content);
            messages.add(one);
        }
        root.add("messages", messages);
        root.add("tools", toolSchema());
        root.addProperty("tool_choice", "auto");
        return root.toString();
    }

    private JsonArray toolSchema() {
        JsonArray tools = new JsonArray();
        for (AgentAction action : AgentActions.ALL) {
            if (toolCsv != null && !AgentPolicy.toolAllowed(toolCsv, action.name())) {
                continue;
            }
            JsonObject parameters = new JsonObject();
            parameters.addProperty("type", "object");
            JsonObject properties = new JsonObject();
            for (String name : action.parameterNames()) {
                JsonObject property = new JsonObject();
                property.addProperty("type", "string");
                properties.add(name, property);
            }
            parameters.add("properties", properties);
            JsonObject function = new JsonObject();
            function.addProperty("name", action.name());
            function.addProperty("description", action.description());
            function.add("parameters", parameters);
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.add("function", function);
            tools.add(tool);
        }
        return tools;
    }

    public static AgentReply parseSuccess(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject message = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
        String content = "";
        if (message.has("content") && !message.get("content").isJsonNull()) {
            content = message.get("content").getAsString();
        }
        List<AgentToolCall> calls = new ArrayList<>();
        if (message.has("tool_calls") && message.get("tool_calls").isJsonArray()) {
            for (JsonElement element : message.getAsJsonArray("tool_calls")) {
                JsonObject function = element.getAsJsonObject().getAsJsonObject("function");
                String name = function.get("name").getAsString();
                calls.add(new AgentToolCall(name, arguments(function.get("arguments"))));
            }
        }
        return new AgentReply(content, calls);
    }

    static Map<String, String> arguments(JsonElement arguments) {
        if (arguments == null || arguments.isJsonNull()) {
            return Map.of();
        }
        JsonElement parsed = arguments;
        if (arguments.isJsonPrimitive()) {
            String raw = arguments.getAsString();
            if (raw.isBlank()) {
                return Map.of();
            }
            parsed = JsonParser.parseString(raw);
        }
        if (!parsed.isJsonObject()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonPrimitive()) {
                out.put(entry.getKey(), value.getAsString());
            }
        }
        return out;
    }

    public static String failureMessage(int status, String body, String apiKey) {
        String snippet = safeText(body == null ? "" : body, apiKey).replaceAll("\\s+", " ").trim();
        if (snippet.length() > 160) {
            snippet = snippet.substring(0, 160);
        }
        if (snippet.isEmpty()) {
            return "provider returned " + status;
        }
        return "provider returned " + status + " " + snippet;
    }

    public static String safeText(String text, String apiKey) {
        if (text == null) {
            return "";
        }
        String out = text;
        if (apiKey != null && !apiKey.isBlank()) {
            out = out.replace(apiKey, "(hidden)");
        }
        return out;
    }

    private static String limit(String text) {
        if (text.length() > 180) {
            return text.substring(0, 180);
        }
        return text;
    }
}
