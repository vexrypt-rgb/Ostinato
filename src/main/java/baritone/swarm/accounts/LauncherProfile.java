package baritone.swarm.accounts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: read launcher profiles as an import source. Password fields are never copied.
 * This class does not write the launcher file and does not log the JSON.
 */
final class LauncherProfile {

    final String name;
    final String uuid;
    final String accessToken;
    final String refreshToken;
    /** Ostinato: when the access token stops working, or 0 when the file and the token do not say. */
    final long expiryEpochMs;

    private LauncherProfile(String name, String uuid, String accessToken, String refreshToken, long expiryEpochMs) {
        this.name = name;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiryEpochMs = expiryEpochMs;
    }

    /**
     * Ostinato: the files "Import launcher" reads. Both official launcher files, then Prism and MultiMC.
     * Newer official launchers leave {@code accessToken} empty, so those yield nothing.
     */
    public static List<Path> defaultFiles(Path gameDirectory) {
        List<Path> files = new ArrayList<>();
        if (gameDirectory != null) {
            files.add(gameDirectory.resolve("launcher_accounts.json"));
        }
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(System.getProperty("user.home"), ".minecraft"));
        String appData = System.getenv("APPDATA");
        if (appData != null) {
            roots.add(Path.of(appData, ".minecraft"));
        }
        for (Path root : roots) {
            files.add(root.resolve("launcher_accounts.json"));
            files.add(root.resolve("launcher_accounts_microsoft_store.json"));
        }
        if (appData != null) {
            files.add(Path.of(appData, "PrismLauncher", "accounts.json"));
            files.add(Path.of(appData, "MultiMC", "accounts.json"));
        }
        return files;
    }

    @Override
    public String toString() {
        return "launcher profile [redacted]";
    }

    static List<LauncherProfile> read(List<Path> files) throws IOException {
        List<LauncherProfile> out = new ArrayList<>();
        if (files == null) {
            return out;
        }
        for (Path file : files) {
            if (file == null || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                JsonElement accounts = root.get("accounts");
                if (accounts == null) {
                    continue;
                }
                List<JsonElement> items = new ArrayList<>();
                if (accounts.isJsonObject()) {
                    for (var entry : accounts.getAsJsonObject().entrySet()) {
                        items.add(entry.getValue());
                    }
                } else if (accounts.isJsonArray()) {
                    // Ostinato: Prism / MultiMC keep an array.
                    accounts.getAsJsonArray().forEach(items::add);
                }
                for (JsonElement item : items) {
                    LauncherProfile found = one(item);
                    LauncherProfile profile = found != null ? found : prism(item);
                    if (profile != null && out.stream().noneMatch(seen -> seen.name.equalsIgnoreCase(profile.name))) {
                        out.add(profile);
                    }
                }
            } catch (RuntimeException ignored) {
                // Parser text can echo the file. Drop it.
            }
        }
        return out;
    }

    private static LauncherProfile one(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject account = element.getAsJsonObject();
        JsonObject profile = account.getAsJsonObject("minecraftProfile");
        if (profile == null) {
            return null;
        }
        String name = text(profile, "name");
        String uuid = text(profile, "id");
        String access = text(account, "accessToken");
        if (name == null || uuid == null || access == null || access.isBlank()) {
            return null;
        }
        long expiry = TokenExpiry.fromIso(text(account, "accessTokenExpiresAt"));
        long fromToken = TokenExpiry.fromJwt(access);
        if (fromToken > 0) {
            expiry = fromToken;
        }
        return new LauncherProfile(name, uuid, access, text(account, "refreshToken"), expiry);
    }

    /** Ostinato: a Prism / MultiMC MSA entry. Only the Minecraft token is kept; their refresh token is not ours. */
    private static LauncherProfile prism(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject account = element.getAsJsonObject();
        JsonElement ygg = account.get("ygg");
        JsonElement profile = account.get("profile");
        if (ygg == null || !ygg.isJsonObject() || profile == null || !profile.isJsonObject()) {
            return null;
        }
        String name = text(profile.getAsJsonObject(), "name");
        String uuid = text(profile.getAsJsonObject(), "id");
        String access = text(ygg.getAsJsonObject(), "token");
        if (name == null || uuid == null || access == null || access.isBlank()) {
            return null;
        }
        long expiry = TokenExpiry.fromJwt(access);
        JsonElement exp = ygg.getAsJsonObject().get("exp");
        if (expiry <= 0 && exp != null && exp.isJsonPrimitive() && exp.getAsJsonPrimitive().isNumber()) {
            expiry = exp.getAsLong() * 1000L;
        }
        return new LauncherProfile(name, uuid, access, "", expiry);
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        return value.getAsString();
    }
}
