package baritone.agent;

// Ostinato: provider resolution, the chat hook, and the off switch. No real worlds and no cloud calls.

import baritone.api.agent.AgentChatProvider;
import baritone.api.agent.AgentReply;
import baritone.api.agent.AgentRequest;
import baritone.api.agent.AgentToolCall;
import baritone.api.agent.RaceRun;
import baritone.api.utils.SettingsUtil;
import baritone.training.TrainingSuite;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AgentApiTest {

    private static final String SECRET = "sk-test-secret-value";

    @Test
    public void presetsAndOllamaFallback() {
        assertEquals("https://api.groq.com/openai/v1", AgentPresets.GROQ.baseUrl);
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai", AgentPresets.GEMINI.baseUrl);
        assertEquals("https://openrouter.ai/api/v1", AgentPresets.OPENROUTER.baseUrl);
        assertEquals("http://localhost:11434/v1", AgentPresets.OLLAMA.baseUrl);
        assertEquals("openai/gpt-oss-20b", AgentPresets.GROQ.model);
        assertEquals("gemini-3.8-flash", AgentPresets.GEMINI.model);
        assertEquals("openrouter/free", AgentPresets.OPENROUTER.model);
        assertEquals("llama3.2", AgentPresets.OLLAMA.model);

        AgentConfig none = AgentConfig.resolve("", "", "", "", env());
        assertEquals("ollama", none.provider);
        assertEquals(AgentPresets.OLLAMA.baseUrl, none.baseUrl);
        assertEquals(AgentPresets.OLLAMA.model, none.model);
        assertTrue(none.ollamaFallback);
        assertFalse(none.hasKey());
        assertFalse(none.toString().contains(SECRET));

        AgentConfig groq = AgentConfig.resolve("", "", "", "", env("GROQ_API_KEY", SECRET));
        assertEquals("groq", groq.provider);
        assertEquals(AgentPresets.GROQ.baseUrl, groq.baseUrl);
        assertEquals(SECRET, groq.apiKey());
        assertFalse(groq.ollamaFallback);
        assertFalse(groq.toString().contains(SECRET));
        assertTrue(groq.toString().contains("key=yes"));

        AgentConfig gemini = AgentConfig.resolve("gemini", "", "gemini-custom", "", env("GEMINI_API_KEY", SECRET));
        assertEquals("gemini", gemini.provider);
        assertEquals("gemini-custom", gemini.model);
        assertEquals(AgentPresets.GEMINI.baseUrl, gemini.baseUrl);

        AgentConfig router = AgentConfig.resolve("openrouter", "", "", "", env("OSTINATO_AGENT_API_KEY", SECRET));
        assertEquals("openrouter", router.provider);
        assertEquals(SECRET, router.apiKey());
        assertEquals("openrouter/free", router.model);

        AgentConfig missing = AgentConfig.resolve("groq", "", "", "", env());
        assertEquals("ollama", missing.provider);
        assertTrue(missing.ollamaFallback);
        assertFalse(missing.hasKey());

        AgentConfig explicit = AgentConfig.resolve("ollama", "http://127.0.0.1:11434/v1", "mistral", "",
                env("GROQ_API_KEY", SECRET));
        assertEquals("ollama", explicit.provider);
        assertEquals("http://127.0.0.1:11434/v1", explicit.baseUrl);
        assertEquals("mistral", explicit.model);
        assertFalse(explicit.ollamaFallback);
        assertFalse(explicit.hasKey());

        AgentConfig settingKey = AgentConfig.resolve("groq", "", "", SECRET, env());
        assertEquals("groq", settingKey.provider);
        assertEquals(SECRET, settingKey.apiKey());

        AgentConfig custom = AgentConfig.resolve("", "http://localhost:8080/v1/", "", "", env());
        assertEquals("custom", custom.provider);
        assertEquals("http://localhost:8080/v1", custom.baseUrl);
        assertFalse(custom.hasKey());
        assertFalse(custom.ollamaFallback);

        AgentConfig cloudUrl = AgentConfig.resolve("", "https://example.test/v1", "", "", env());
        assertEquals("ollama", cloudUrl.provider);
        assertTrue(cloudUrl.ollamaFallback);

        assertEquals("https://api.groq.com/openai/v1/chat/completions",
                OpenAiChatClient.chatUri(AgentPresets.GROQ.baseUrl).toString());
        assertEquals("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
                OpenAiChatClient.chatUri(AgentPresets.GEMINI.baseUrl + "/").toString());
    }

    @Test
    public void keyIsStrippedFromErrorsAndTheBody() throws Exception {
        String failure = OpenAiChatClient.failureMessage(401, "unauthorized " + SECRET, SECRET);
        assertTrue(failure.contains("401"));
        assertTrue(failure.contains("(hidden)"));
        assertFalse(failure.contains(SECRET));
        assertEquals("agentApiKey (hidden)", SettingsUtil.redactSettingsLine("agentApiKey " + SECRET));
        assertTrue(SettingsUtil.isSecretSetting("agentApiKey"));
        assertFalse(SettingsUtil.isSecretSetting("agentModel"));

        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            started.countDown();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            byte[] resp = ("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"On my way.\","
                    + "\"tool_calls\":[{\"id\":\"1\",\"type\":\"function\",\"function\":{\"name\":\"goto\","
                    + "\"arguments\":\"{\\\"x\\\":\\\"1\\\",\\\"y\\\":\\\"64\\\",\\\"z\\\":\\\"2\\\"}\"}}]}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            AgentConfig config = AgentConfig.resolve("groq", "http://127.0.0.1:" + port + "/v1", "test-model", SECRET, env());
            OpenAiChatClient client = new OpenAiChatClient(config);
            assertTrue(client instanceof AgentChatProvider);
            CountDownLatch returned = new CountDownLatch(1);
            AtomicReference<CompletableFuture<AgentReply>> box = new AtomicReference<>();
            Thread caller = new Thread(() -> {
                box.set(client.complete(AgentChat.requestFor("test-model", "walk to 1 64 2")));
                returned.countDown();
            });
            caller.start();
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue("complete() must return while the response is still held", returned.await(2, TimeUnit.SECONDS));
            assertFalse(box.get().isDone());
            release.countDown();
            AgentReply reply = box.get().get(5, TimeUnit.SECONDS);
            assertEquals("On my way.", reply.content);
            assertEquals("goto 1 64 2", AgentChat.deliver(reply).commands.get(0));
            assertEquals("Bearer " + SECRET, auth.get());
            assertFalse(body.get().contains(SECRET));
            assertTrue(body.get().contains("test-model"));
            assertTrue(body.get().contains("\"name\":\"goto\""));
            assertTrue(body.get().contains("\"name\":\"mine\""));
            assertFalse(AgentChat.SYSTEM.contains(SECRET));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    public void ollamaSendsNoAuthorizationHeader() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>("missing");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            auth.set(header == null ? "" : header);
            byte[] resp = "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            AgentConfig config = AgentConfig.resolve("ollama", "http://127.0.0.1:" + port + "/v1", "", "", env());
            AgentReply reply = new OpenAiChatClient(config).complete(AgentChat.requestFor("", "hi")).get(5, TimeUnit.SECONDS);
            assertEquals("ok", reply.content);
            assertEquals("", auth.get());
            assertFalse(config.hasKey());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void offDoesNotCallTheProviderAndFingerprintsStay() throws Exception {
        AgentChat.Outcome apiOff = AgentChat.begin(false, true, "mine stone");
        AgentChat.Outcome chatOff = AgentChat.begin(true, false, "mine stone");
        AgentChat.Outcome empty = AgentChat.begin(true, true, "  ");
        AgentChat.Outcome on = AgentChat.begin(true, true, "mine stone");
        assertFalse(apiOff.callProvider);
        assertEquals("agentApi is off", apiOff.chat);
        assertFalse(chatOff.callProvider);
        assertEquals("agentChat is off", chatOff.chat);
        assertFalse(empty.callProvider);
        assertTrue(on.callProvider);

        String settings = Files.readString(project("src/api/java/baritone/api/Settings.java"), StandardCharsets.UTF_8);
        assertTrue(settings.contains("agentApi = new Setting<>(false)"));
        assertTrue(settings.contains("agentChat = new Setting<>(false)"));
        assertTrue(settings.contains("agentDryRun = new Setting<>(true)"));
        assertTrue(settings.contains("agentOwnerOnly = new Setting<>(true)"));
        assertTrue(settings.contains("agentOneSession = new Setting<>(true)"));
        assertTrue(settings.contains("agent429ToOllama = new Setting<>(true)"));
        assertTrue(settings.contains("agentMemory = new Setting<>(true)"));
        for (String rel : new String[]{
                "src/test/java/baritone/pathing/calc/PathBench.java",
                "src/main/java/baritone/pathing/calc/AbstractNodeCostSearch.java",
                "src/main/java/baritone/pathing/decide/PathDecisionMatrix.java"}) {
            String src = Files.readString(project(rel), StandardCharsets.UTF_8);
            assertFalse(rel, src.contains("baritone.agent") || src.contains("agentApi") || src.contains("agentChat"));
        }

        Path out = Files.createTempDirectory("ostinato-agent-off");
        assertEquals(21, TrainingSuite.INSTANCE.run("log-planks", false, "heuristic", null, out).totalTicks);
        assertEquals(65, TrainingSuite.INSTANCE.run("stone-step", false, "heuristic", null, out).totalTicks);
        assertEquals(28, TrainingSuite.INSTANCE.run("night-listen", false, "heuristic", null, out).totalTicks);
        assertEquals(38, TrainingSuite.INSTANCE.run("night-listen", true, "heuristic", null, out).totalTicks);
    }

    @Test
    public void toolsMapOntoCommandsAndSecretsAreRefused() {
        assertEquals("goto 1 64 2", AgentActions.toCommand("goto", Map.of("x", "1", "y", "64", "z", "2")));
        assertEquals("goto oak_log", AgentActions.toCommand("goto", Map.of("block", "oak_log")));
        assertEquals("mine 4 stone", AgentActions.toCommand("mine", Map.of("block", "stone", "count", "4")));
        assertEquals("follow players", AgentActions.toCommand("follow", Map.of("target", "players")));
        assertEquals("follow entity pig", AgentActions.toCommand("follow", Map.of("entity", "pig")));
        assertEquals("set allowSprint true", AgentActions.toCommand("settings", Map.of("name", "allowSprint", "value", "true")));
        assertEquals(null, AgentActions.toCommand("settings", Map.of("name", "agentApiKey", "value", SECRET)));
        assertEquals(null, AgentActions.toCommand("settings", Map.of("name", "agentBaseUrl", "value", "https://evil.test")));
        assertEquals(null, AgentActions.toCommand("goto", Map.of("x", "1;say hi", "y", "2", "z", "3")));

        AgentReply text = new AgentReply("Heading over.\nTOOL mine block=dirt count=2", List.of());
        AgentChat.Delivery delivery = AgentChat.deliver(text);
        assertEquals(List.of("mine 2 dirt"), delivery.commands);
        assertTrue(delivery.chat.contains("Heading over."));
        assertFalse(delivery.chat.contains("TOOL"));

        AgentReply calls = new AgentReply("ok", List.of(
                new AgentToolCall("settings", Map.of("name", "agentApiKey", "value", SECRET))));
        AgentChat.Delivery skipped = AgentChat.deliver(calls);
        assertTrue(skipped.commands.isEmpty());
        assertTrue(skipped.chat.contains("skipped"));
        assertFalse(skipped.chat.contains(SECRET));

        AgentChat.Delivery blocked = AgentChat.deliver(
                new AgentReply("", List.of(new AgentToolCall("mine", Map.of("block", "stone")))), "goto");
        assertTrue(blocked.commands.isEmpty());
        assertTrue(blocked.chat.contains("skipped"));
        assertFalse(AgentPolicy.toolAllowed("goto", "mine"));
        assertTrue(AgentPolicy.toolAllowed("goto,settings", "set"));

        AgentChat.Delivery routeNote = AgentChat.deliver(new AgentReply("ok", List.of(
                new AgentToolCall("route", Map.of("op", "run", "name", "wood")))));
        assertTrue(routeNote.commands.isEmpty());
        assertTrue(routeNote.chat.contains("route run wood"));
        assertEquals(null, AgentActions.toCommand("route", Map.of("op", "run", "name", "wood")));

        String schema = new OpenAiChatClient(
                AgentConfig.resolve("ollama", "http://127.0.0.1:9/v1", "", "", env()), false, "goto")
                .body(AgentChat.requestFor("m", "hi"));
        assertTrue(schema.contains("\"name\":\"goto\""));
        assertFalse(schema.contains("\"name\":\"mine\""));
    }

    @Test
    public void raceRecordHasNoKeyField() throws Exception {
        Path dir = Files.createTempDirectory("ostinato-agent-race");
        RaceRun run = new RaceRun("race_1", "shorter parkour", "agent-a", "groq", "openai/gpt-oss-20b",
                Map.of("ticks", "21", "deaths", "0"), "pass");
        new JsonlRaceRecorder(dir.resolve("races.jsonl")).record(run);
        String line = Files.readString(dir.resolve("races.jsonl"), StandardCharsets.UTF_8);
        assertTrue(line.contains("\"runId\":\"race_1\""));
        assertTrue(line.contains("\"hypothesis\":\"shorter parkour\""));
        assertTrue(line.contains("\"provider\":\"groq\""));
        assertTrue(line.contains("\"outcome\":\"pass\""));
        assertTrue(line.contains("\"ticks\":\"21\""));
        assertFalse(line.contains("apiKey"));
        assertFalse(line.contains(SECRET));
    }

    @Test
    public void routesMemorySessionOwnerAndKeyFile() throws Exception {
        assertTrue(AgentPolicy.ownerAllowed(true, "", "Steve"));
        assertTrue(AgentPolicy.ownerAllowed(true, "Steve", "steve"));
        assertFalse(AgentPolicy.ownerAllowed(true, "Steve", ""));
        assertFalse(AgentPolicy.ownerAllowed(true, "Steve", "Alex"));
        assertTrue(AgentPolicy.ownerAllowed(false, "Steve", "Alex"));

        AgentSession session = new AgentSession();
        assertTrue(session.tryStart());
        assertFalse(session.tryStart());
        session.finish();
        assertTrue(session.tryStart());
        session.finish();

        Path dir = Files.createTempDirectory("ostinato-agent-routes");
        AgentChat.RouteResult saved = AgentChat.handleRoute(
                "route save wood mine 2 oak_log ; goto 1 64 2", dir, AgentPolicy.DEFAULT_TOOLS);
        assertTrue(saved.chat.contains("v1"));
        AgentChat.RouteResult again = AgentChat.handleRoute("route save wood goto 3 64 4", dir, AgentPolicy.DEFAULT_TOOLS);
        assertTrue(again.chat.contains("v2"));
        RouteStore.TaskRoute loaded = RouteStore.load(dir, "wood");
        assertEquals(2, loaded.version);
        assertEquals(List.of("goto 3 64 4"), loaded.steps);
        AgentChat.RouteResult ran = AgentChat.handleRoute("route run wood", dir, AgentPolicy.DEFAULT_TOOLS);
        assertEquals(List.of("goto 3 64 4"), ran.commands);
        assertTrue(AgentChat.handleRoute("route list", dir, AgentPolicy.DEFAULT_TOOLS).chat.contains("wood"));
        assertNull(AgentChat.handleRoute("mine stone", dir, AgentPolicy.DEFAULT_TOOLS));

        AgentChat.RouteResult badName = AgentChat.handleRoute(
                "route save bad#name goto 1 64 2", dir, AgentPolicy.DEFAULT_TOOLS);
        assertEquals("agent: route not saved", badName.chat);
        AgentChat.RouteResult secretStep = AgentChat.handleRoute(
                "route save leak set agentApiKey " + SECRET, dir, AgentPolicy.DEFAULT_TOOLS);
        assertEquals("agent: route not saved", secretStep.chat);
        assertFalse(secretStep.chat.contains(SECRET));
        assertFalse(RouteStore.stepAllowed("set agentApi true", AgentPolicy.DEFAULT_TOOLS));
        assertFalse(RouteStore.stepAllowed("goto 1 64 2 # note", AgentPolicy.DEFAULT_TOOLS));
        assertFalse(Files.exists(dir.resolve("leak.json")));
        assertFalse(Files.exists(dir.resolve("bad#name.json")));

        Path memory = Files.createTempDirectory("ostinato-agent-memory").resolve("agent-memory.jsonl");
        AgentMemory.append(memory, "user", "hello " + SECRET, SECRET);
        AgentMemory.append(memory, "assistant", "ok", SECRET);
        String raw = Files.readString(memory, StandardCharsets.UTF_8);
        assertFalse(raw.contains(SECRET));
        assertTrue(raw.contains("(hidden)"));
        List<AgentRequest.Message> recent = AgentMemory.recent(memory);
        assertEquals(2, recent.size());
        AgentRequest request = AgentChat.requestFor("m", recent, "next");
        assertEquals("system", request.messages.get(0).role);
        assertEquals("user", request.messages.get(1).role);
        assertEquals("next", request.messages.get(request.messages.size() - 1).content);
        assertFalse(request.messages.get(1).content.contains(SECRET));

        Path keyFile = Files.createTempFile("ostinato-agent-key", ".txt");
        Files.writeString(keyFile, SECRET + "\nsecond-line", StandardCharsets.UTF_8);
        assertEquals(SECRET, AgentConfig.readKeyFile(keyFile.toString()));
        AgentConfig fromFile = AgentConfig.resolve("groq", "", "", "", env(), SECRET);
        assertEquals("groq", fromFile.provider);
        assertEquals(SECRET, fromFile.apiKey());
        assertFalse(fromFile.toString().contains(SECRET));
        AgentConfig fileAlone = AgentConfig.resolve("", "", "", "", env(), SECRET);
        assertEquals("ollama", fileAlone.provider);
        assertTrue(fileAlone.ollamaFallback);
        assertFalse(fileAlone.hasKey());
    }

    @Test
    public void rateLimitFallsBackToOllamaOnce() throws Exception {
        AtomicInteger hitsA = new AtomicInteger();
        AtomicReference<String> authB = new AtomicReference<>("missing");
        HttpServer cloud = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        cloud.createContext("/v1/chat/completions", exchange -> {
            hitsA.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] resp = ("slow " + SECRET).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        HttpServer local = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        local.createContext("/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String header = exchange.getRequestHeaders().getFirst("Authorization");
            authB.set(header == null ? "" : header);
            byte[] resp = "{\"choices\":[{\"message\":{\"content\":\"from-b\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        cloud.start();
        local.start();
        try {
            int cloudPort = cloud.getAddress().getPort();
            int localPort = local.getAddress().getPort();
            AgentConfig primary = AgentConfig.resolve("groq", "http://127.0.0.1:" + cloudPort + "/v1", "test-model", SECRET, env());
            AgentConfig fallback = AgentConfig.resolve("ollama", "http://127.0.0.1:" + localPort + "/v1", "llama", "", env());
            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            AgentReply reply = new OpenAiChatClient(primary, http, () -> fallback)
                    .complete(AgentChat.requestFor("test-model", "hi"))
                    .get(5, TimeUnit.SECONDS);
            assertEquals("from-b", reply.content);
            assertEquals(1, hitsA.get());
            assertEquals("", authB.get());
            assertFalse(reply.content.contains(SECRET));

            AtomicInteger fallbacks = new AtomicInteger();
            HttpServer ollamaOnly = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ollamaOnly.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] resp = "slow down".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(429, resp.length);
                exchange.getResponseBody().write(resp);
                exchange.close();
            });
            ollamaOnly.start();
            try {
                AgentConfig already = AgentConfig.resolve("ollama",
                        "http://127.0.0.1:" + ollamaOnly.getAddress().getPort() + "/v1", "", "", env());
                try {
                    new OpenAiChatClient(already, http, () -> {
                        fallbacks.incrementAndGet();
                        return fallback;
                    }).complete(AgentChat.requestFor("", "hi")).get(5, TimeUnit.SECONDS);
                    fail("ollama 429 must not be retried");
                } catch (ExecutionException ex) {
                    assertFalse(ex.toString().contains(SECRET));
                }
                assertEquals(0, fallbacks.get());
            } finally {
                ollamaOnly.stop(0);
            }
        } finally {
            cloud.stop(0);
            local.stop(0);
        }
    }

    private static Function<String, String> env(String... pairs) {
        java.util.Map<String, String> values = new java.util.HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values::get;
    }

    private static Path project(String rel) {
        Path path = Path.of(rel);
        return Files.exists(path) ? path : Path.of("..").resolve(rel);
    }
}
