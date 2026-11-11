package baritone.swarm.accounts;

import baritone.swarm.spawn.SwarmSpawnArgs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Ostinato: imported tokens carry an expiry, and an expired one says how to fix it. No real token. */
public class TokenImportTest {

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static String jwt(long expSeconds) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String head = enc.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String body = enc.encodeToString(("{\"sub\":\"SECRET-SUBJECT\",\"exp\":" + expSeconds + "}")
                .getBytes(StandardCharsets.UTF_8));
        return head + "." + body + ".SIGNATURE-SECRET";
    }

    private static long inHours(int hours) {
        return System.currentTimeMillis() / 1000L + hours * 3600L;
    }

    private AccountStore store() {
        return new AccountStore(temporary.getRoot().toPath().resolve("accounts"), new XorProtector());
    }

    @Test public void expiryFromJwtAndIso() {
        assertEquals(1_900_000_000_000L, TokenExpiry.fromJwt(jwt(1_900_000_000L)));
        assertEquals(0L, TokenExpiry.fromJwt("not-a-token"));
        assertEquals(0L, TokenExpiry.fromJwt("a.!!!.c"));
        assertEquals(0L, TokenExpiry.fromJwt(null));
        assertEquals(Instant.parse("2026-01-02T03:04:05Z").toEpochMilli(), TokenExpiry.fromIso("2026-01-02T03:04:05Z"));
        assertEquals(0L, TokenExpiry.fromIso("yesterday"));
        assertEquals("expired", TokenExpiry.left(1000, 2000));
        assertEquals("5 min left", TokenExpiry.left(5 * 60_000L, 0));
        assertEquals("21 h left", TokenExpiry.left(21 * 3_600_000L, 0));
        assertEquals("3 d left", TokenExpiry.left(72 * 3_600_000L, 0));
    }

    @Test public void prismArrayImportsAndExpiredLauncherProfileIsSkipped() throws Exception {
        Path prism = temporary.getRoot().toPath().resolve("accounts.json");
        Files.writeString(prism, "{\"accounts\":[{\"type\":\"MSA\",\"ygg\":{\"token\":\"" + jwt(inHours(20))
                + "\"},\"profile\":{\"name\":\"Prismy\",\"id\":\"123456781234123412341234567890ab\"}}]}");
        Path official = temporary.getRoot().toPath().resolve("launcher_accounts.json");
        Files.writeString(official, "{\"accounts\":{\"a\":{\"accessToken\":\"OLD-SECRET\",\"accessTokenExpiresAt\":"
                + "\"2020-01-01T00:00:00Z\",\"minecraftProfile\":{\"name\":\"Old\",\"id\":\"223456781234123412341234567890ab\"}}}}");
        AccountStore store = store();
        AccountStore.ImportReport report = store.importReport(List.of(official, prism));
        assertEquals(1, report.saved());
        assertEquals(1, report.expired());
        assertTrue(report.line().contains("already expired, re-sign in to the launcher"));
        assertNull(store.find("Old"));
        AccountStore.Listed prismy = store.find("Prismy");
        assertNotNull(prismy);
        assertTrue(prismy.imported());
        assertTrue(AccountStore.describe(prismy, System.currentTimeMillis()).matches("Prismy \\(imported, 1?\\d h left\\)"));
    }

    @Test public void emptyLauncherSaysWhatToDo() throws Exception {
        Path official = temporary.getRoot().toPath().resolve("launcher_accounts.json");
        Files.writeString(official, "{\"accounts\":{\"a\":{\"accessToken\":\"\",\"minecraftProfile\":{\"name\":\"Main\",\"id\":\"x\"}}}}");
        AccountStore.ImportReport report = store().importReport(List.of(official));
        assertEquals(0, report.saved());
        assertTrue(report.line().startsWith("No launcher token found"));
    }

    @Test public void sessionImportNeedsALiveToken() throws Exception {
        AccountStore store = store();
        try {
            store.importSession("Main", "12345678-1234-1234-1234-1234567890ab", "offline-token");
            fail("non-JWT session saved");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().startsWith("No online session"));
        }
        try {
            store.importSession("Main", "12345678-1234-1234-1234-1234567890ab", jwt(inHours(-1)));
            fail("expired session saved");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().contains("SECRET"));
        }
        AccountStore.Listed saved = store.importSession("Main", "12345678-1234-1234-1234-1234567890ab", jwt(inHours(23)));
        assertNotNull(saved);
        assertTrue(saved.imported());
        assertEquals("12345678123412341234567890ab".length() + 4, saved.uuid().length());
        assertFalse(AccountStore.describe(saved, System.currentTimeMillis()).contains("SECRET"));
    }

    @Test public void describeLabels() throws Exception {
        AccountStore store = store();
        store.addOffline("Local");
        assertEquals("Local (offline)", AccountStore.describe(store.find("Local"), 0));
        store.importSession("Main", "12345678123412341234567890abcdef", jwt(inHours(5)));
        AccountStore.Listed main = store.find("Main");
        assertEquals("Main (token expired, re-import from launcher)",
                AccountStore.describe(main, main.expiryEpochMs() + 1));
    }

    @Test public void spawnWithExpiredImportSaysReimport() throws Exception {
        AccountStore store = store();
        // Ostinato: an imported token that has since expired; write it directly.
        java.lang.reflect.Method save = AccountStore.class.getDeclaredMethod("saveMicrosoft", String.class, String.class,
                String.class, String.class, long.class, boolean.class);
        save.setAccessible(true);
        save.invoke(store, "Main", "12345678123412341234567890abcdef", "TOKEN-SECRET", "", System.currentTimeMillis() - 1000L, true);
        try {
            SpawnAccountChoice.choose(SwarmSpawnArgs.parse(List.of("Main")), false, store, List.of(), null);
            fail("expired import spawned");
        } catch (IllegalArgumentException expected) {
            assertEquals("token expired, re-import from launcher", expected.getMessage());
        }
    }
}
