package baritone.swarm.accounts;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Ostinato: when an imported Minecraft token stops working. Reads only the {@code exp} claim.
 * Never logs or returns any part of the token. Returns 0 when it cannot tell.
 */
public final class TokenExpiry {

    private TokenExpiry() {}

    public static long fromJwt(String token) {
        if (token == null) {
            return 0L;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return 0L;
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonObject claims = JsonParser.parseString(new String(payload, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement exp = claims.get("exp");
            if (exp == null || !exp.isJsonPrimitive() || !exp.getAsJsonPrimitive().isNumber()) {
                return 0L;
            }
            long seconds = exp.getAsLong();
            return seconds > 0 ? seconds * 1000L : 0L;
        } catch (RuntimeException ignored) {
            // The message could echo payload text. Drop it.
            return 0L;
        }
    }

    public static long fromIso(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(text.trim()).toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    /** Short text for the accounts strip, for example "21 h left". */
    public static String left(long expiryEpochMs, long nowMs) {
        long ms = expiryEpochMs - nowMs;
        if (ms <= 0) {
            return "expired";
        }
        long minutes = ms / 60_000L;
        if (minutes < 60) {
            return Math.max(minutes, 1) + " min left";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + " h left" : (hours / 24) + " d left";
    }
}
