package baritone.swarm.accounts;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Ostinato: device-code sign-in. {@link #poll} performs one HTTP call and does not wait for a person.
 * Failure text never includes a response body, a token, or a device code.
 */
public final class MicrosoftAuth {

    public interface HttpExchange {
        Response exchange(String method, String url, String contentType, String body, String authorization);

        record Response(int status, String body) {
            @Override
            public String toString() {
                return "http " + status;
            }
        }
    }

    public static final class DeviceCode {
        private final String userCode;
        private final String verificationUri;
        private final String deviceCode;
        private volatile int intervalSeconds;

        DeviceCode(String userCode, String verificationUri, String deviceCode, int intervalSeconds) {
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.deviceCode = deviceCode;
            this.intervalSeconds = intervalSeconds;
        }

        public String userCode() {
            return userCode;
        }

        public String verificationUri() {
            return verificationUri;
        }

        public String deviceCode() {
            return deviceCode;
        }

        public int intervalSeconds() {
            return Math.max(intervalSeconds, 5);
        }

        /** Ostinato: RFC 8628 slow_down adds five seconds to every later poll. */
        void slowDown() {
            intervalSeconds = intervalSeconds() + 5;
        }

        public String display() {
            return "Open " + verificationUri + " and enter " + userCode;
        }

        @Override
        public String toString() {
            return "device code [redacted]";
        }
    }

    public enum PollKind { PENDING, SLOW, EXPIRED, DECLINED, FAILED, READY }

    public static final class Poll {
        public final PollKind kind;
        final String access;
        final String refresh;
        final long expiresIn;

        private Poll(PollKind kind, String access, String refresh, long expiresIn) {
            this.kind = kind;
            this.access = access;
            this.refresh = refresh;
            this.expiresIn = expiresIn;
        }

        static Poll of(PollKind kind) {
            return new Poll(kind, "", "", 0);
        }

        @Override
        public String toString() {
            return "poll " + kind;
        }
    }

    public record SignedIn(String name, String uuid, String minecraftAccess, String refreshToken, long expiryEpochMs) {
        @Override
        public String toString() {
            return "signed in [redacted]";
        }
    }

    private static final String DEVICE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBOX = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MINECRAFT = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String PROFILE = "https://api.minecraftservices.com/minecraft/profile";
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String JSON = "application/json";
    private static final String SCOPE = "XboxLive.signin offline_access";

    private MicrosoftAuth() {}

    public static DeviceCode start(String clientId, HttpExchange http) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalStateException(AzureClientConfig.MISSING);
        }
        JsonObject json = object(post(http, DEVICE, FORM, form(Map.of(
                "client_id", clientId.trim(),
                "scope", SCOPE
        )), null));
        String device = text(json, "device_code");
        String user = text(json, "user_code");
        String uri = text(json, "verification_uri");
        if (device == null || user == null || uri == null) {
            throw new IllegalStateException("microsoft sign-in failed");
        }
        int interval = 5;
        try {
            if (json.has("interval")) {
                interval = json.get("interval").getAsInt();
            }
        } catch (RuntimeException ignored) {
            interval = 5;
        }
        return new DeviceCode(user, uri, device, interval);
    }

    /** One token request. {@code authorization_pending} comes back as {@link PollKind#PENDING}. */
    public static Poll poll(String clientId, DeviceCode code, HttpExchange http) {
        if (clientId == null || clientId.isBlank() || code == null) {
            return Poll.of(PollKind.FAILED);
        }
        JsonObject json = object(post(http, TOKEN, FORM, form(Map.of(
                "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                "client_id", clientId.trim(),
                "device_code", code.deviceCode()
        )), null));
        String error = text(json, "error");
        if (error != null) {
            return switch (error) {
                case "authorization_pending" -> Poll.of(PollKind.PENDING);
                case "slow_down" -> {
                    code.slowDown();
                    yield Poll.of(PollKind.SLOW);
                }
                case "expired_token" -> Poll.of(PollKind.EXPIRED);
                case "authorization_declined" -> Poll.of(PollKind.DECLINED);
                default -> Poll.of(PollKind.FAILED);
            };
        }
        String access = text(json, "access_token");
        if (access == null || access.isBlank()) {
            return Poll.of(PollKind.FAILED);
        }
        long expires = 0;
        try {
            if (json.has("expires_in")) {
                expires = json.get("expires_in").getAsLong();
            }
        } catch (RuntimeException ignored) {
            expires = 0;
        }
        return new Poll(PollKind.READY, access, text(json, "refresh_token"), expires);
    }

    public static SignedIn refresh(String clientId, String refreshToken, HttpExchange http) {
        if (clientId == null || clientId.isBlank() || refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalStateException("microsoft sign-in failed");
        }
        JsonObject json = object(post(http, TOKEN, FORM, form(Map.of(
                "grant_type", "refresh_token",
                "client_id", clientId.trim(),
                "refresh_token", refreshToken,
                "scope", SCOPE
        )), null));
        String access = text(json, "access_token");
        if (access == null || access.isBlank()) {
            throw new IllegalStateException("microsoft sign-in failed");
        }
        String nextRefresh = text(json, "refresh_token");
        long expires = 0;
        try {
            if (json.has("expires_in")) {
                expires = json.get("expires_in").getAsLong();
            }
        } catch (RuntimeException ignored) {
            expires = 0;
        }
        return finish(access, nextRefresh == null || nextRefresh.isBlank() ? refreshToken : nextRefresh, expires, http);
    }

    public static SignedIn finish(String msAccess, String refresh, long msExpiresIn, HttpExchange http) {
        if (msAccess == null || msAccess.isBlank()) {
            throw new IllegalStateException("microsoft sign-in failed");
        }
        JsonObject xbox = object(post(http, XBOX, JSON, xboxBody(msAccess), null));
        String xboxToken = text(xbox, "Token");
        String uhs = uhs(xbox);
        if (xboxToken == null || uhs == null) {
            throw XboxSignInException.of(xbox);
        }
        JsonObject xsts = object(post(http, XSTS, JSON, xstsBody(xboxToken), null));
        String xstsToken = text(xsts, "Token");
        String xstsUhs = uhs(xsts);
        if (xstsToken == null || xstsUhs == null) {
            throw XboxSignInException.of(xsts);
        }
        JsonObject mc = object(post(http, MINECRAFT, JSON, mcBody(xstsUhs, xstsToken), null));
        String mcAccess = text(mc, "access_token");
        if (mcAccess == null || mcAccess.isBlank()) {
            throw new IllegalStateException("microsoft sign-in failed");
        }
        JsonObject profile = object(get(http, PROFILE, "Bearer " + mcAccess));
        String name = text(profile, "name");
        String id = text(profile, "id");
        if (name == null || id == null) {
            throw new IllegalStateException("microsoft sign-in failed");
        }
        long expiry = System.currentTimeMillis() + Math.max(msExpiresIn, 3600) * 1000L;
        try {
            if (mc.has("expires_in")) {
                expiry = System.currentTimeMillis() + mc.get("expires_in").getAsLong() * 1000L;
            }
        } catch (RuntimeException ignored) {
            // Keep the Microsoft lifetime computed above.
        }
        return new SignedIn(name, id, mcAccess, refresh == null ? "" : refresh, expiry);
    }

    private static HttpExchange.Response post(HttpExchange http, String url, String type, String body, String authorization) {
        if (http == null) {
            throw new IllegalStateException("microsoft sign-in failed");
        }
        try {
            HttpExchange.Response response = http.exchange("POST", url, type, body, authorization);
            if (response == null) {
                throw new IllegalStateException("microsoft sign-in failed");
            }
            return response;
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("microsoft sign-in failed");
        }
    }

    private static HttpExchange.Response get(HttpExchange http, String url, String authorization) {
        if (http == null) {
            throw new IllegalStateException("microsoft sign-in failed");
        }
        try {
            HttpExchange.Response response = http.exchange("GET", url, null, null, authorization);
            if (response == null) {
                throw new IllegalStateException("microsoft sign-in failed");
            }
            return response;
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("microsoft sign-in failed");
        }
    }

    private static JsonObject object(HttpExchange.Response response) {
        try {
            if (response.body == null || response.body.isBlank()) {
                return new JsonObject();
            }
            JsonElement element = JsonParser.parseString(response.body);
            if (!element.isJsonObject()) {
                throw new IllegalStateException("microsoft sign-in failed");
            }
            return element.getAsJsonObject();
        } catch (IllegalStateException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalStateException("microsoft sign-in failed");
        }
    }

    private static String text(JsonObject json, String key) {
        if (json == null || !json.has(key) || json.get(key).isJsonNull()) {
            return null;
        }
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String uhs(JsonObject json) {
        try {
            JsonObject claims = json.getAsJsonObject("DisplayClaims");
            JsonArray xui = claims.getAsJsonArray("xui");
            if (xui == null || xui.isEmpty()) {
                return null;
            }
            return text(xui.get(0).getAsJsonObject(), "uhs");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String xboxBody(String msAccess) {
        return "{\"Properties\":{\"AuthMethod\":\"RPS\",\"SiteName\":\"user.auth.xboxlive.com\",\"RpsTicket\":\"d="
                + escape(msAccess) + "\"},\"RelyingParty\":\"http://auth.xboxlive.com\",\"TokenType\":\"JWT\"}";
    }

    private static String xstsBody(String xboxToken) {
        return "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"" + escape(xboxToken)
                + "\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}";
    }

    private static String mcBody(String uhs, String xsts) {
        return "{\"identityToken\":\"XBL3.0 x=" + escape(uhs) + ";" + escape(xsts) + "\"}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String form(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder();
        for (var field : fields.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(field.getKey(), StandardCharsets.UTF_8));
            sb.append('=');
            sb.append(URLEncoder.encode(field.getValue() == null ? "" : field.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
