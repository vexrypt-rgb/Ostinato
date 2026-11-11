package baritone.swarm.accounts;

import baritone.swarm.spawn.SpawnAuth;
import baritone.swarm.spawn.SpawnSession;
import baritone.swarm.spawn.SwarmSpawnArgs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: account store, device code, and spawn choice. No live poll and no real token. */
public class AccountManagerTest {

    private static final String DEVICE = "DEVICE-SECRET";
    private static final String ACCESS = "ACCESS-SECRET";
    private static final String REFRESH = "REFRESH-SECRET";
    private static final String XBOX = "XBOX-SECRET";
    private static final String XSTS = "XSTS-SECRET";
    private static final String MC = "MC-SECRET";
    private static final String PASSWORD = "secret-password";

    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void missingClientIdDoesNotStartTheFlow() {
        AtomicInteger calls = new AtomicInteger();
        AccountStore store = new AccountStore(temporary.getRoot().toPath().resolve("accounts"), new XorProtector());
        AccountPanelModel model = new AccountPanelModel(store, "", true);
        String message = model.beginSignIn((method, url, type, body, authorization) -> {
            calls.incrementAndGet();
            return new MicrosoftAuth.HttpExchange.Response(500, ACCESS);
        });
        assertEquals(AzureClientConfig.MISSING, message);
        assertEquals(0, calls.get());
        assertTrue(message.contains("does not include a client id"));
    }

    @Test public void signInStoresCiphertextAndDoesNotLogSecrets() throws Exception {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            AccountStore store = new AccountStore(temporary.getRoot().toPath().resolve("accounts"), new XorProtector());
            AccountPanelModel model = new AccountPanelModel(store, "app-id", true);
            ScriptHttp http = script();
            String shown = model.beginSignIn(http);
            assertTrue(shown.contains("ABCD-EFGH"));
            assertTrue(shown.contains("https://login.microsoft.com/device"));
            assertFalse(shown.contains(DEVICE));
            MicrosoftAuth.DeviceCode code = new MicrosoftAuth.DeviceCode("ABCD-EFGH",
                    "https://login.microsoft.com/device", DEVICE, 5);
            assertEquals("poll PENDING", MicrosoftAuth.poll("app-id", code, http).toString());
            assertEquals("device code [redacted]", code.toString());
            String done = model.pollOnce(http);
            assertEquals("Signed in as Bot", done);
            assertFalse(done.contains(MC));
            assertFalse(done.contains(ACCESS));
            assertFalse(done.contains(REFRESH));
            SpawnSession session = store.session("Bot");
            assertTrue(session != null && MC.equals(session.accessToken()));
            assertFalse(session.toString().contains(MC));
            assertFalse(String.valueOf(store.list()).contains(MC));
            assertNoSecrets(temporary.getRoot().toPath());
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        String logs = captured.toString(StandardCharsets.UTF_8);
        assertFalse(logs.contains(DEVICE));
        assertFalse(logs.contains(ACCESS));
        assertFalse(logs.contains(REFRESH));
        assertFalse(logs.contains(XBOX));
        assertFalse(logs.contains(XSTS));
        assertFalse(logs.contains(MC));
    }

    @Test public void slowDownAddsFiveSecondsToTheInterval() {
        MicrosoftAuth.DeviceCode code = new MicrosoftAuth.DeviceCode("ABCD-EFGH", "https://login.microsoft.com/device", DEVICE, 5);
        ScriptHttp http = new ScriptHttp();
        http.responses.add("{\"error\":\"slow_down\"}");
        http.responses.add("{\"error\":\"slow_down\"}");
        assertEquals(MicrosoftAuth.PollKind.SLOW, MicrosoftAuth.poll("app-id", code, http).kind);
        assertEquals(10, code.intervalSeconds());
        assertEquals(MicrosoftAuth.PollKind.SLOW, MicrosoftAuth.poll("app-id", code, http).kind);
        assertEquals(15, code.intervalSeconds());
    }

    @Test public void xboxRefusalsGetAFixedMessageWithoutTheBody() {
        com.google.gson.JsonObject child = new com.google.gson.JsonObject();
        child.addProperty("XErr", 2148916238L);
        child.addProperty("Message", ACCESS);
        XboxSignInException refused = XboxSignInException.of(child);
        assertTrue(refused.getMessage().contains("child account"));
        assertFalse(refused.getMessage().contains(ACCESS));
        assertTrue(XboxSignInException.message(2148916233L).contains("no Xbox profile"));
        assertEquals(XboxSignInException.GENERIC, XboxSignInException.message(42));
        assertEquals(XboxSignInException.GENERIC, XboxSignInException.of(null).getMessage());
    }

    @Test public void aBadResponseDoesNotEchoItsBody() {
        try {
            MicrosoftAuth.start("app-id", (method, url, type, body, authorization) ->
                    new MicrosoftAuth.HttpExchange.Response(400, ACCESS + " not-json"));
            assertTrue(false);
        } catch (IllegalStateException expected) {
            assertEquals("microsoft sign-in failed", expected.getMessage());
            assertFalse(expected.getMessage().contains(ACCESS));
            assertTrue(expected.getCause() == null);
        }
    }

    @Test public void importSkipsPasswordsAndDoesNotWritePlaintextOrTheLauncherFile() throws Exception {
        Path launcher = temporary.getRoot().toPath().resolve("launcher_accounts.json");
        String json = "{\"accounts\":{\"a\":{\"accessToken\":\"" + ACCESS + "\",\"password\":\"" + PASSWORD
                + "\",\"minecraftProfile\":{\"name\":\"Bot\",\"id\":\"123456781234123412341234567890ab\"}}}}";
        Files.writeString(launcher, json);
        AccountStore store = new AccountStore(temporary.getRoot().toPath().resolve("accounts"), new XorProtector());
        assertEquals(1, store.importAll(List.of(launcher)));
        assertEquals(json, Files.readString(launcher));
        assertNoSecrets(storeDir());
        SpawnSession session = store.session("Bot");
        assertTrue(session != null && ACCESS.equals(session.accessToken()));
        assertFalse(session.toString().contains(PASSWORD));
    }

    @Test public void spawnSelectsTheChosenAccountAndKeepsTheBotName() throws Exception {
        AccountStore store = new AccountStore(temporary.getRoot().toPath().resolve("accounts"), new XorProtector());
        store.saveMicrosoft("Steve", "123456781234123412341234567890ab", ACCESS, REFRESH, System.currentTimeMillis() + 3_600_000L);
        store.addOffline("Local");
        Path launcher = temporary.getRoot().toPath().resolve("launcher_accounts.json");
        Files.writeString(launcher, "{\"accounts\":{\"a\":{\"accessToken\":\"" + PASSWORD
                + "\",\"password\":\"" + PASSWORD + "\",\"minecraftProfile\":{\"name\":\"Bot\",\"id\":\"abcdefabcdefabcdefabcdefabcdefab\"}}}}");
        String before = Files.readString(launcher);
        SwarmSpawnArgs request = SwarmSpawnArgs.parse(List.of("Bot", "account:Steve"));
        assertEquals(List.of("Bot"), request.names());
        List<SpawnSession> chosen = SpawnAccountChoice.choose(request, false, store, List.of(launcher), null);
        assertEquals(1, chosen.size());
        assertEquals("Steve", chosen.get(0).username());
        assertTrue(ACCESS.equals(chosen.get(0).accessToken()));
        assertEquals(before, Files.readString(launcher));
        List<SpawnSession> offline = SpawnAccountChoice.choose(SwarmSpawnArgs.parse(List.of("Bot", "account:Local")),
                true, store, List.of(launcher), null);
        assertTrue(offline.get(0).username().equals("Local") && !offline.get(0).online());
        assertEquals(List.of("Bot1", "Bot2"), SwarmSpawnArgs.parse(List.of("Bot", "2")).names());
        List<SpawnSession> named = SpawnAccountChoice.choose(SwarmSpawnArgs.parse(List.of("Bot")), false, store, List.of(launcher), null);
        assertTrue(PASSWORD.equals(named.get(0).accessToken()));
        assertNoSecrets(storeDir());
    }

    @Test public void splicePutsTheTokenOnlyInTheReturnedArray() throws Exception {
        Path file = temporary.getRoot().toPath().resolve("auth.bin");
        byte[] plain = AuthBlob.encode("Bot", "123456781234123412341234567890ab", ACCESS, "", 0L);
        Files.write(file, new XorProtector().protect(plain));
        String[] command = new String[]{"java", "--username", "Bot"};
        String[] spliced = SpawnAuth.splice(command, file, new XorProtector());
        assertFalse(String.join(" ", command).contains(ACCESS));
        boolean present = false;
        for (String arg : spliced) {
            if (ACCESS.equals(arg)) {
                present = true;
            }
        }
        assertTrue(present);
        assertEquals("msa", spliced[spliced.length - 1]);
        assertFalse(Files.exists(file));
        Files.write(file, new XorProtector().protect(plain));
        String[] broken = SpawnAuth.splice(command, file, new SecretProtector() {
            @Override
            public byte[] protect(byte[] plain) {
                throw new IllegalStateException(ACCESS);
            }

            @Override
            public byte[] unprotect(byte[] blob) {
                throw new IllegalStateException(ACCESS);
            }
        });
        assertEquals(command.length, broken.length);
        assertFalse(String.join(" ", broken).contains(ACCESS));
        assertTrue(Files.exists(file));
    }

    @Test public void unknownExpiryUsesTheAccessTokenAndExpiredWithoutRefreshIsRefused() throws Exception {
        AccountStore store = new AccountStore(temporary.getRoot().toPath().resolve("accounts"), new XorProtector());
        Path launcher = temporary.getRoot().toPath().resolve("launcher_accounts.json");
        Files.writeString(launcher, "{\"accounts\":{\"a\":{\"accessToken\":\"" + ACCESS
                + "\",\"refreshToken\":\"" + REFRESH + "\",\"minecraftProfile\":{\"name\":\"Imported\",\"id\":\"123456781234123412341234567890ab\"}}}}");
        assertEquals("Imported", store.importNamed("Imported", List.of(launcher)).name());
        assertFalse(store.needsRefresh("Imported"));
        assertFalse(store.expiredWithoutRefresh("Imported"));
        assertEquals("", store.refreshToken("Imported"));
        List<SpawnSession> imported = SpawnAccountChoice.choose(SwarmSpawnArgs.parse(List.of("Bot", "account:Imported")),
                false, store, List.of(), (name, token) -> {
                    throw new AssertionError("launcher refresh tokens must not be used");
                });
        assertTrue(ACCESS.equals(imported.get(0).accessToken()));
        store.saveMicrosoft("Ours", "abcdefabcdefabcdefabcdefabcdefab", ACCESS, REFRESH, 1L);
        assertTrue(store.needsRefresh("Ours"));
        AtomicInteger refreshes = new AtomicInteger();
        List<SpawnSession> chosen = SpawnAccountChoice.choose(SwarmSpawnArgs.parse(List.of("Bot", "account:Ours")),
                false, store, List.of(), (name, token) -> {
                    refreshes.incrementAndGet();
                    assertEquals(REFRESH, token);
                    store.saveMicrosoft(name, "abcdefabcdefabcdefabcdefabcdefab", MC, REFRESH,
                            System.currentTimeMillis() + 3_600_000L);
                    return store.session(name);
                });
        assertEquals(1, refreshes.get());
        assertTrue(MC.equals(chosen.get(0).accessToken()));
        store.saveMicrosoft("Stale", "12345678123412341234123456789012", ACCESS, "", 1L);
        try {
            SpawnAccountChoice.choose(SwarmSpawnArgs.parse(List.of("Bot", "account:Stale")),
                    false, store, List.of(), null);
            assertTrue(false);
        } catch (IllegalArgumentException expected) {
            assertEquals("account needs sign-in", expected.getMessage());
        }
    }

    @Test public void dpapiRoundTripWhenThePlatformHasIt() {
        if (!DpapiProtector.available()) {
            return;
        }
        SecretProtector protector = DpapiProtector.platform();
        byte[] blob = protector.protect("roundtrip-sample".getBytes(StandardCharsets.UTF_8));
        assertFalse(new String(blob, StandardCharsets.ISO_8859_1).contains("roundtrip-sample"));
        String back = new String(protector.unprotect(blob), StandardCharsets.UTF_8);
        assertEquals("roundtrip-sample", back);
    }

    private Path storeDir() {
        return temporary.getRoot().toPath().resolve("accounts");
    }

    private static void assertNoSecrets(Path root) throws Exception {
        List<Path> files = new ArrayList<>();
        try (var walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(files::add);
        }
        for (Path file : files) {
            String text = Files.readString(file, StandardCharsets.ISO_8859_1);
            assertFalse(text.contains(DEVICE));
            assertFalse(text.contains(ACCESS));
            assertFalse(text.contains(REFRESH));
            assertFalse(text.contains(XBOX));
            assertFalse(text.contains(XSTS));
            assertFalse(text.contains(MC));
            assertFalse(text.contains(PASSWORD));
        }
    }

    private static ScriptHttp script() {
        ScriptHttp http = new ScriptHttp();
        http.responses.add("{\"user_code\":\"ABCD-EFGH\",\"device_code\":\"" + DEVICE
                + "\",\"verification_uri\":\"https://login.microsoft.com/device\",\"interval\":5}");
        http.responses.add("{\"error\":\"authorization_pending\"}");
        http.responses.add("{\"access_token\":\"" + ACCESS + "\",\"refresh_token\":\"" + REFRESH + "\",\"expires_in\":3600}");
        http.responses.add("{\"Token\":\"" + XBOX + "\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"123\"}]}}");
        http.responses.add("{\"Token\":\"" + XSTS + "\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"123\"}]}}");
        http.responses.add("{\"access_token\":\"" + MC + "\",\"expires_in\":86400}");
        http.responses.add("{\"id\":\"123456781234123412341234567890ab\",\"name\":\"Bot\"}");
        return http;
    }

    private static final class ScriptHttp implements MicrosoftAuth.HttpExchange {
        final List<String> responses = new ArrayList<>();
        int cursor;

        @Override
        public Response exchange(String method, String url, String contentType, String body, String authorization) {
            if (cursor >= responses.size()) {
                throw new IllegalStateException("microsoft sign-in failed");
            }
            return new Response(200, responses.get(cursor++));
        }
    }
}
