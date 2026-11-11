package baritone.swarm.accounts;

import baritone.swarm.spawn.SpawnSession;
import baritone.swarm.spawn.SwarmSpawnArgs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: which account a spawn uses. The chosen account wins. Otherwise the bot's own name.
 * Launcher files are imported only when the store has no match. Offline servers do not read tokens.
 */
public final class SpawnAccountChoice {

    @FunctionalInterface
    public interface Refresher {
        SpawnSession refresh(String name, String refreshToken) throws IOException;
    }

    private SpawnAccountChoice() {}

    public static List<SpawnSession> choose(SwarmSpawnArgs args, boolean offline, AccountStore store,
                                            List<java.nio.file.Path> launcher, Refresher refresher) throws IOException {
        if (args.account() != null && args.names().size() > 1) {
            throw new IllegalArgumentException("One account cannot sign in more than one bot");
        }
        List<SpawnSession> sessions = new ArrayList<>();
        for (String name : args.names()) {
            sessions.add(one(name, args.account(), offline, store, launcher, refresher));
        }
        return List.copyOf(sessions);
    }

    private static SpawnSession one(String bot, String account, boolean offline, AccountStore store,
                                   List<java.nio.file.Path> launcher, Refresher refresher) throws IOException {
        if (offline) {
            if (account == null) {
                return null;
            }
            AccountStore.Listed listed = store.find(account);
            if (listed == null || listed.kind() != AccountStore.Kind.OFFLINE) {
                throw new IllegalArgumentException("No offline account " + account);
            }
            return new SpawnSession(listed.username(), listed.uuid(), null);
        }
        String key = account == null ? bot : account;
        AccountStore.Listed listed = store.find(key);
        if (listed == null) {
            listed = store.importNamed(key, launcher);
        }
        if (listed == null || listed.kind() != AccountStore.Kind.MICROSOFT) {
            throw new IllegalArgumentException("Online-mode servers need a configured account for " + key
                    + "; Ostinato will not store passwords");
        }
        if (store.expiredWithoutRefresh(key)) {
            // Ostinato: an imported token cannot be refreshed here; say what fixes it.
            throw new IllegalArgumentException(listed.imported()
                    ? "token expired, re-import from launcher" : "account needs sign-in");
        }
        if (store.needsRefresh(key)) {
            if (refresher == null) {
                throw new IllegalArgumentException("account needs sign-in");
            }
            SpawnSession refreshed = refresher.refresh(key, store.refreshToken(key));
            if (refreshed == null || !refreshed.online()) {
                throw new IllegalArgumentException("account needs sign-in");
            }
            return refreshed;
        }
        SpawnSession session = store.session(key);
        if (session == null || !session.online()) {
            throw new IllegalArgumentException("Online-mode servers need a configured account for " + key
                    + "; Ostinato will not store passwords");
        }
        return session;
    }
}
