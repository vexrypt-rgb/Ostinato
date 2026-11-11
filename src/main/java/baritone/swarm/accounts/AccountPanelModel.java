package baritone.swarm.accounts;

/**
 * Ostinato: account-manager actions with no window. A missing client id does not start the device-code flow.
 */
public final class AccountPanelModel {

    private final AccountStore store;
    private final String clientId;
    private final boolean protectorReady;
    private MicrosoftAuth.DeviceCode pending;

    public AccountPanelModel(AccountStore store, String clientId, boolean protectorReady) {
        this.store = store;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.protectorReady = protectorReady;
    }

    public AccountStore store() {
        return store;
    }

    public int intervalSeconds() {
        return pending == null ? 5 : pending.intervalSeconds();
    }

    public String beginSignIn(MicrosoftAuth.HttpExchange http) {
        if (clientId.isBlank()) {
            return AzureClientConfig.MISSING;
        }
        if (!protectorReady || store.protectorMissing()) {
            return "Windows DPAPI is not available";
        }
        try {
            pending = MicrosoftAuth.start(clientId, http);
            return pending.display();
        } catch (RuntimeException e) {
            pending = null;
            return safe(e);
        }
    }

    /** One poll. Pending stays on the same display string. A success saves the account and drops the device code. */
    public String pollOnce(MicrosoftAuth.HttpExchange http) {
        if (pending == null) {
            return "Sign-in has not started";
        }
        try {
            MicrosoftAuth.Poll poll = MicrosoftAuth.poll(clientId, pending, http);
            if (poll.kind == MicrosoftAuth.PollKind.PENDING || poll.kind == MicrosoftAuth.PollKind.SLOW) {
                return pending.display();
            }
            if (poll.kind != MicrosoftAuth.PollKind.READY) {
                pending = null;
                return "Sign-in stopped";
            }
            MicrosoftAuth.SignedIn signed = MicrosoftAuth.finish(poll.access, poll.refresh, poll.expiresIn, http);
            store.saveMicrosoft(signed.name(), signed.uuid(), signed.minecraftAccess(), signed.refreshToken(), signed.expiryEpochMs());
            pending = null;
            return "Signed in as " + signed.name();
        } catch (Exception e) {
            pending = null;
            return e instanceof RuntimeException runtime ? safe(runtime) : "microsoft sign-in failed";
        }
    }

    private static String safe(RuntimeException error) {
        String message = error.getMessage();
        if (error instanceof XboxSignInException) {
            return message;
        }
        if (AzureClientConfig.MISSING.equals(message) || "microsoft sign-in failed".equals(message)
                || "xbox sign-in failed".equals(message) || "Windows DPAPI is not available".equals(message)) {
            return message;
        }
        return "microsoft sign-in failed";
    }
}
