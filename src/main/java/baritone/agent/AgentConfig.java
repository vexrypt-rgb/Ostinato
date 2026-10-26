package baritone.agent;

// Ostinato: resolves provider, base URL, model, and key. toString never includes the key.
// No cloud key falls back to Ollama. An explicit ollama choice stays on Ollama.

import baritone.Baritone;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Function;

public final class AgentConfig {

    public final String provider;
    public final String baseUrl;
    public final String model;
    /** True when a cloud choice had no key and Ollama was selected instead. */
    public final boolean ollamaFallback;
    private final String apiKey;

    AgentConfig(String provider, String baseUrl, String model, String apiKey, boolean ollamaFallback) {
        this.provider = provider;
        this.baseUrl = baseUrl;
        this.model = model;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.ollamaFallback = ollamaFallback;
    }

    public boolean hasKey() {
        return !apiKey.isBlank();
    }

    /** The header value. Callers must not log it. */
    public String apiKey() {
        return apiKey;
    }

    @Override
    public String toString() {
        return "provider=" + provider
                + " model=" + model
                + " base=" + baseUrl
                + " key=" + (hasKey() ? "yes" : "no")
                + (ollamaFallback ? " fallback=ollama" : "");
    }

    public static AgentConfig fromSettings() {
        return resolve(
                Baritone.settings().agentProvider.value,
                Baritone.settings().agentBaseUrl.value,
                Baritone.settings().agentModel.value,
                Baritone.settings().agentApiKey.value,
                System::getenv,
                readKeyFile(Baritone.settings().agentKeyFile.value));
    }

    /** First line of the key file. Empty or missing means no vault key. The text is never logged. */
    static String readKeyFile(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        try {
            Path file = Path.of(path.trim());
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String line = Files.readString(file);
            int newline = line.indexOf('\n');
            if (newline >= 0) {
                line = line.substring(0, newline);
            }
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            return blankToNull(line.trim());
        } catch (IOException ex) {
            return null;
        }
    }

    public static AgentConfig resolve(String providerRaw, String baseRaw, String modelRaw, String keyRaw,
            Function<String, String> env) {
        return resolve(providerRaw, baseRaw, modelRaw, keyRaw, env, null);
    }

    public static AgentConfig resolve(String providerRaw, String baseRaw, String modelRaw, String keyRaw,
            Function<String, String> env, String keyFileText) {
        String provider = norm(providerRaw);
        String base = trim(baseRaw);
        String model = trim(modelRaw);
        String settingKey = blankToNull(keyRaw);
        String fileKey = blankToNull(keyFileText);

        if ("ollama".equals(provider)) {
            return ollama(base, model, false);
        }

        AgentPresets preset = AgentPresets.byId(provider);
        if (preset != null && preset.needsKey()) {
            String key = first(settingKey, envOf(env, preset.envVar), envOf(env, "OSTINATO_AGENT_API_KEY"), fileKey);
            if (key == null) {
                return ollama("", model, true);
            }
            return new AgentConfig(preset.id, chooseBase(base, preset.baseUrl), chooseModel(model, preset.model), key, false);
        }

        if (!base.isEmpty()) {
            String key = first(settingKey, envOf(env, "OSTINATO_AGENT_API_KEY"), fileKey);
            if (key == null && !isLocal(base)) {
                return ollama("", model, true);
            }
            String id = provider.isEmpty() ? (isOllamaUrl(base) ? "ollama" : "custom") : provider;
            String fallbackModel = "ollama".equals(id) ? AgentPresets.OLLAMA.model : "default";
            return new AgentConfig(id, stripSlash(base), chooseModel(model, fallbackModel), key == null ? "" : key, false);
        }

        for (AgentPresets cloud : AgentPresets.CLOUD) {
            String envKey = envOf(env, cloud.envVar);
            if (envKey != null) {
                String key = first(settingKey, envKey, fileKey);
                return new AgentConfig(cloud.id, cloud.baseUrl, chooseModel(model, cloud.model), key, false);
            }
        }
        return ollama("", model, true);
    }

    private static AgentConfig ollama(String base, String model, boolean fallback) {
        // A fallback never keeps a cloud URL. An explicit ollama base (including a LAN host) is kept.
        String url = fallback || base.isEmpty() ? AgentPresets.OLLAMA.baseUrl : stripSlash(base);
        return new AgentConfig("ollama", url, chooseModel(model, AgentPresets.OLLAMA.model), "", fallback);
    }

    static boolean isLocal(String base) {
        String b = base.toLowerCase(Locale.ROOT);
        return b.startsWith("http://localhost")
                || b.startsWith("http://127.0.0.1")
                || b.startsWith("http://[::1]")
                || b.startsWith("http://0.0.0.0");
    }

    private static boolean isOllamaUrl(String base) {
        return stripSlash(base).equalsIgnoreCase(AgentPresets.OLLAMA.baseUrl);
    }

    private static String chooseBase(String override, String preset) {
        return override.isEmpty() ? preset : stripSlash(override);
    }

    private static String chooseModel(String override, String preset) {
        return override.isEmpty() ? preset : override;
    }

    private static String stripSlash(String base) {
        String b = base.trim();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    private static String norm(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static String blankToNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return raw.trim();
    }

    private static String envOf(Function<String, String> env, String name) {
        if (env == null || name == null || name.isEmpty()) {
            return null;
        }
        try {
            return blankToNull(env.apply(name));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String first(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
