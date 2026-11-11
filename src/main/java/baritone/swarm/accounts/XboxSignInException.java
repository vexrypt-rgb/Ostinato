package baritone.swarm.accounts;

import com.google.gson.JsonObject;

/**
 * Ostinato: an Xbox Live or XSTS refusal with a fixed, safe message picked from the XErr code.
 * The response body is never part of the message.
 */
public final class XboxSignInException extends IllegalStateException {

    public static final String GENERIC = "xbox sign-in failed";

    private XboxSignInException(String message) {
        super(message);
    }

    static XboxSignInException of(JsonObject json) {
        long code = 0;
        try {
            if (json != null && json.has("XErr")) {
                code = json.get("XErr").getAsLong();
            }
        } catch (RuntimeException ignored) {
            code = 0;
        }
        return new XboxSignInException(message(code));
    }

    public static String message(long code) {
        if (code == 2148916233L) {
            return "xbox sign-in failed: this Microsoft account has no Xbox profile (sign in once at minecraft.net)";
        }
        if (code == 2148916235L) {
            return "xbox sign-in failed: Xbox Live is not available in this account's country";
        }
        if (code == 2148916236L || code == 2148916237L) {
            return "xbox sign-in failed: this account needs adult verification at xbox.com";
        }
        if (code == 2148916238L) {
            return "xbox sign-in failed: child account (add it to a Microsoft family first)";
        }
        return GENERIC;
    }
}
