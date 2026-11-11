package baritone.swarm.spawn;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

// Ostinato: read official launcher profiles only; never persist authentication material.
public final class SwarmSpawnAccounts {
    public record Profile(String uuid, String accessToken) {
        public Profile {
            if (uuid == null || !uuid.replace("-", "").matches("[0-9a-fA-F]{32}")
                    || accessToken == null || accessToken.isBlank()) {
                throw new IllegalArgumentException("Configured account is missing a UUID or access token");
            }
        }

        @Override public String toString() { return "Configured launcher account [redacted]"; }
    }

    public static boolean offline(String server, String parentName, UUID parentUuid) {
        String host = server.toLowerCase(java.util.Locale.ROOT);
        if (host.startsWith("[")) {
            int end = host.indexOf(']');
            host = end < 0 ? host : host.substring(1, end);
        } else if (host.indexOf(':') == host.lastIndexOf(':') && host.contains(":")) {
            host = host.substring(0, host.indexOf(':'));
        }
        return host.equals("localhost") || host.equals("::1") || host.matches("127\\.[0-9]+\\.[0-9]+\\.[0-9]+")
                || UUID.nameUUIDFromBytes(("OfflinePlayer:" + parentName).getBytes(StandardCharsets.UTF_8)).equals(parentUuid);
    }

    /** Launcher files are read for import. This method does not create them. */
    public static List<Path> launcherFiles(Path gameDirectory) {
        // Ostinato: same list as the accounts strip's Import launcher.
        return baritone.swarm.accounts.AccountStore.launcherFiles(gameDirectory);
    }

    public static Profile find(String name, List<Path> files) throws IOException {
        for (Path file : files) {
            if (!Files.isRegularFile(file)) continue;
            try {
                JsonObject root = new JsonParser().parse(Files.readString(file)).getAsJsonObject();
                JsonObject accounts = root.getAsJsonObject("accounts");
                if (accounts == null) continue;
                for (var entry : accounts.entrySet()) {
                    JsonObject account = entry.getValue().getAsJsonObject();
                    JsonObject profile = account.getAsJsonObject("minecraftProfile");
                    if (profile != null && name.equalsIgnoreCase(string(profile, "name"))) {
                        String token = string(account, "accessToken");
                        String id = string(profile, "id");
                        if (token != null && !token.isBlank() && id != null) return new Profile(id, token);
                    }
                }
            } catch (RuntimeException ignored) {
                // Never echo JSON/parser diagnostics: they may include credentials.
            }
        }
        throw new IllegalArgumentException("Online-mode servers need a configured account for " + name
                + "; Ostinato will not store passwords; add the account in the launcher (with a usable access token)"
                + " or use a local/offline server");
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
