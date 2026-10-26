package baritone.agent;

// Ostinato: queue order, state machine, cleanup, recorder, and the off switch. No Minecraft client.

import baritone.api.agent.RaceRecorder;
import baritone.api.agent.RaceRun;
import baritone.api.agent.RaceWorldHost;

import net.minecraft.world.level.levelgen.WorldOptions;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class OvernightRaceTest {

    private static final String QUEUE = """
            # A then B is file order, not sorted
            {"hypothesis":"speed","variant":"B","world":"hyp_b","seed":"71006","flat":true,"prompt":"mine stone","timeoutMs":5000}
            {"hypothesis":"speed","variant":"A","world":"hyp_a","flat":false}
            """;

    @Test
    public void queueOrderAndRejectsANonRaceWorld() {
        List<HypothesisRun> runs = HypothesisQueue.parse(QUEUE);
        assertEquals(2, runs.size());
        assertEquals(0, runs.get(0).index);
        assertEquals("B", runs.get(0).variant);
        assertEquals("hyp_b", runs.get(0).world);
        assertEquals("71006", runs.get(0).seed);
        assertTrue(runs.get(0).flat);
        assertEquals(5000L, runs.get(0).timeoutMs);
        assertEquals("speed-B-hyp_b", runs.get(0).runId());
        assertEquals("A", runs.get(1).variant);
        assertFalse(runs.get(1).flat);
        assertEquals(0L, runs.get(1).timeoutMs);
        assertTrue(HypothesisQueue.parse("\n# only a comment\n").isEmpty());
        assertTrue(HypothesisQueue.parse("").isEmpty());
        try {
            HypothesisQueue.parse("{\"hypothesis\":\"speed\",\"variant\":\"A\",\"world\":\"New World\"}");
            fail("world");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("world"));
        }
        try {
            HypothesisQueue.parse("{");
            fail("json");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not json"));
        }
    }

    @Test
    public void happyPathRecordsInOrderAndDeletesOnlyRaceWorlds() throws Exception {
        Fixture fix = fixture(QUEUE);
        for (String name : List.of("vexflat", "VexFlat-26.3", "pk_hh", "pkrun_1", "SigilTest", "gs_world",
                "New World", "race_old", "hyp_a", "pk_race_trap")) {
            Files.createDirectories(fix.saves.resolve(name));
        }
        drain(fix.orch, 0, 1);
        assertEquals(OvernightOrchestrator.Phase.DONE, fix.orch.phase());
        assertEquals(List.of("hyp_b", "hyp_a"), fix.host.created());
        assertEquals(List.of("71006", ""), fix.host.seeds());
        assertEquals(List.of(true, false), fix.host.flats());
        assertEquals(2, fix.agent.begins);

        String races = Files.readString(fix.races, StandardCharsets.UTF_8);
        String[] lines = races.strip().split("\\R");
        assertEquals(2, lines.length);
        assertTrue(lines[0].contains("\"runId\":\"speed-B-hyp_b\""));
        assertTrue(lines[0].contains("\"hypothesis\":\"speed\""));
        assertTrue(lines[0].contains("\"variant\":\"B\""));
        assertTrue(lines[0].contains("\"world\":\"hyp_b\""));
        assertTrue(lines[0].contains("\"score\":\"3/3\""));
        assertTrue(lines[0].contains("\"timeMs\""));
        assertTrue(lines[0].contains("\"outcome\":\"pass\""));
        assertTrue(lines[1].contains("\"runId\":\"speed-A-hyp_a\""));
        assertTrue(lines[1].contains("\"variant\":\"A\""));
        assertFalse(races.contains("apiKey"));
        assertFalse(races.contains("sk-"));

        for (String kept : List.of("vexflat", "VexFlat-26.3", "pk_hh", "pkrun_1", "SigilTest", "gs_world",
                "New World", "pk_race_trap")) {
            assertTrue(kept, Files.isDirectory(fix.saves.resolve(kept)));
        }
        for (String gone : List.of("race_old", "hyp_a", "hyp_b")) {
            assertFalse(gone, Files.exists(fix.saves.resolve(gone)));
        }
        assertTrue(Files.isRegularFile(fix.saves.resolve(RaceWorldCleanup.MARKER)));
    }

    @Test
    public void stubFailureTimeoutAbortAndStop() throws Exception {
        Fixture stub = fixture(QUEUE);
        stub.host.enqueue(RaceWorldHost.Status.STUBBED);
        drain(stub.orch, 0, 1);
        assertEquals(1, stub.agent.begins);
        String races = Files.readString(stub.races, StandardCharsets.UTF_8);
        assertTrue(races.contains("\"outcome\":\"stubbed\""));
        assertTrue(races.contains("\"outcome\":\"pass\""));
        assertTrue(races.indexOf("stubbed") < races.indexOf("\"outcome\":\"pass\""));

        Fixture failed = fixture(QUEUE);
        failed.host.enqueue(RaceWorldHost.Status.FAILED);
        drain(failed.orch, 0, 1);
        String failedLines = Files.readString(failed.races, StandardCharsets.UTF_8);
        assertTrue(failedLines.contains("\"outcome\":\"error\""));
        assertTrue(failedLines.contains("\"runId\":\"speed-A-hyp_a\""));

        List<HypothesisRun> slow = HypothesisQueue.parse(
                "{\"hypothesis\":\"speed\",\"variant\":\"A\",\"world\":\"hyp_a\",\"timeoutMs\":1000}\n"
                        + "{\"hypothesis\":\"speed\",\"variant\":\"B\",\"world\":\"hyp_b\",\"timeoutMs\":1000}\n");
        Fixture timed = fixture(slow);
        timed.agent.hold = 50;
        timed.orch.start(0);
        assertEquals(OvernightOrchestrator.Phase.RUN_AGENT, timed.orch.poll(0));
        assertEquals(OvernightOrchestrator.Phase.RUN_AGENT, timed.orch.poll(1));
        assertEquals(1, timed.agent.begins);
        assertEquals(OvernightOrchestrator.Phase.RECORD, timed.orch.poll(1000));
        drain(timed.orch, 1000, 1);
        String timedLines = Files.readString(timed.races, StandardCharsets.UTF_8);
        assertTrue(timedLines.contains("\"runId\":\"speed-A-hyp_a\""));
        assertTrue(timedLines.contains("\"outcome\":\"timeout\""));
        assertTrue(timedLines.contains("\"runId\":\"speed-B-hyp_b\""));
        assertEquals(OvernightOrchestrator.Phase.DONE, timed.orch.phase());

        Fixture aborted = fixture(QUEUE);
        aborted.agent.hold = 50;
        aborted.orch.start(0);
        aborted.orch.poll(0);
        aborted.orch.poll(1);
        aborted.orch.requestAbort();
        assertEquals(OvernightOrchestrator.Phase.RECORD, aborted.orch.poll(2));
        assertEquals(OvernightOrchestrator.Phase.DELETE_WORLD, aborted.orch.poll(3));
        assertEquals(OvernightOrchestrator.Phase.DONE, aborted.orch.poll(4));
        assertEquals(1, aborted.agent.begins);
        assertTrue(Files.readString(aborted.races, StandardCharsets.UTF_8).contains("\"outcome\":\"abort\""));
        assertFalse(Files.readString(aborted.races, StandardCharsets.UTF_8).contains("hyp_a"));

        Fixture stopped = fixture(QUEUE);
        stopped.orch.start(0);
        assertEquals(OvernightOrchestrator.Phase.RUN_AGENT, stopped.orch.poll(0));
        stopped.orch.requestStop();
        drain(stopped.orch, 1, 1);
        assertEquals(OvernightOrchestrator.Phase.DONE, stopped.orch.phase());
        assertEquals(1, stopped.agent.begins);
        String stoppedLines = Files.readString(stopped.races, StandardCharsets.UTF_8).strip();
        assertEquals(1, stoppedLines.split("\\R").length);
        assertTrue(stoppedLines.contains("speed-B-hyp_b"));
    }

    @Test
    public void pendingCreateResumeAndLeaveTimeout() throws Exception {
        Fixture pending = fixture(one("hyp_a"));
        pending.host.enqueue(RaceWorldHost.Status.PENDING, RaceWorldHost.Status.PENDING);
        pending.orch.start(0);
        assertEquals(OvernightOrchestrator.Phase.CREATE_WORLD, pending.orch.poll(0));
        assertEquals(OvernightOrchestrator.Phase.CREATE_WORLD, pending.orch.poll(1));
        assertEquals(OvernightOrchestrator.Phase.RUN_AGENT, pending.orch.poll(2));
        drain(pending.orch, 3, 1);
        assertEquals(OvernightOrchestrator.Phase.DONE, pending.orch.phase());

        Fixture resume = fixture(QUEUE);
        resume.orch.start(0);
        assertEquals(OvernightOrchestrator.Phase.RUN_AGENT, resume.orch.poll(0));
        assertEquals(OvernightOrchestrator.Phase.SCORE, resume.orch.poll(1));
        assertEquals(OvernightOrchestrator.Phase.RECORD, resume.orch.poll(2));
        assertFalse(Files.exists(resume.races));
        OvernightOrchestrator again = resume.copy();
        assertTrue(again.resumeOrStart(10));
        assertEquals(OvernightOrchestrator.Phase.RECORD, again.phase());
        assertEquals(OvernightOrchestrator.Phase.DELETE_WORLD, again.poll(11));
        assertEquals(1, Files.readString(resume.races, StandardCharsets.UTF_8).strip().split("\\R").length);
        String saved = Files.readString(resume.state, StandardCharsets.UTF_8);
        Files.writeString(resume.state, saved.replace("DELETE_WORLD", "RECORD").replace("\"recorded\":true", "\"recorded\":false"));
        OvernightOrchestrator third = resume.copy();
        assertTrue(third.resumeOrStart(12));
        third.poll(13);
        assertEquals(1, Files.readString(resume.races, StandardCharsets.UTF_8).strip().split("\\R").length);

        Fixture stuck = fixture(one("hyp_keep"));
        Files.createDirectories(stuck.saves.resolve("race_old"));
        stuck.host.keepOpen();
        stuck.orch.start(0);
        long t = 0;
        for (int i = 0; i < 20 && stuck.orch.phase() != OvernightOrchestrator.Phase.DELETE_WORLD; i++) {
            stuck.orch.poll(t);
            t++;
        }
        assertEquals(OvernightOrchestrator.Phase.DELETE_WORLD, stuck.orch.phase());
        assertEquals(OvernightOrchestrator.Phase.DELETE_WORLD, stuck.orch.poll(t));
        assertEquals(OvernightOrchestrator.Phase.DONE, stuck.orch.poll(t + OvernightOrchestrator.LEAVE_WAIT_MS));
        assertTrue(Files.isDirectory(stuck.saves.resolve("hyp_keep")));
        assertTrue(Files.isDirectory(stuck.saves.resolve("race_old")));
    }

    @Test
    public void noMarkerMeansNothingIsDeletedAndRecordRetries() throws Exception {
        Fixture bare = fixture(one("hyp_a"));
        bare = bare.withMark(false);
        Files.createDirectories(bare.saves.resolve("race_old"));
        Files.createDirectories(bare.saves.resolve("vexflat"));
        drain(bare.orch, 0, 1);
        assertTrue(Files.isDirectory(bare.saves.resolve("hyp_a")));
        assertTrue(Files.isDirectory(bare.saves.resolve("race_old")));
        assertTrue(Files.isDirectory(bare.saves.resolve("vexflat")));
        assertFalse(Files.exists(bare.saves.resolve(RaceWorldCleanup.MARKER)));

        Path dir = Files.createTempDirectory("ostinato-overnight-flaky");
        Path races = dir.resolve("races.jsonl");
        FlakyRecorder recorder = new FlakyRecorder(new JsonlRaceRecorder(races));
        recorder.fails = 1;
        MemoryRaceWorldHost host = new MemoryRaceWorldHost(dir.resolve("saves"));
        Files.createDirectories(dir.resolve("saves"));
        ScriptAgent agent = new ScriptAgent();
        OvernightOrchestrator orch = new OvernightOrchestrator(HypothesisQueue.parse(one("hyp_a")), host, agent,
                new CountScorer(), RaceWorldCleanup.INSTANCE, recorder, dir.resolve("saves"), dir.resolve("state.json"),
                true, 0);
        orch.start(0);
        while (orch.phase() != OvernightOrchestrator.Phase.RECORD) {
            orch.poll(0);
        }
        assertEquals(OvernightOrchestrator.Phase.RECORD, orch.poll(1));
        assertFalse(Files.exists(races));
        assertEquals(OvernightOrchestrator.Phase.DELETE_WORLD, orch.poll(2));
        assertTrue(Files.readString(races, StandardCharsets.UTF_8).contains("\"outcome\":\"pass\""));
    }

    @Test
    public void optInDefaultsOffAndPlansAreExplicit() throws Exception {
        assertFalse(new HarnessScorer(false, false, "heuristic", null).usesCircuit());
        OvernightScorer.OvernightScore scored = new HarnessScorer(false, false, "heuristic", null)
                .score(HypothesisQueue.parse(one("hyp_a")).get(0),
                        new OvernightAgent.AgentTick(OvernightAgent.AgentTick.Status.DONE, "local", "", ""), 4);
        assertEquals("n/a", scored.score);
        assertEquals("incomplete", scored.outcome);
        assertEquals("none", scored.metrics.get("circuit"));
        assertFalse(scored.metrics.containsKey("stage.items"));

        String settings = Files.readString(project("src/api/java/baritone/api/Settings.java"), StandardCharsets.UTF_8);
        assertTrue(settings.contains("overnightRaces = new Setting<>(false)"));
        assertTrue(settings.contains("overnightEveryBlockItem = new Setting<>(false)"));
        String command = Files.readString(project("src/main/java/baritone/command/defaults/OvernightCommand.java"),
                StandardCharsets.UTF_8);
        assertTrue(command.contains("overnightRaces is off"));
        assertTrue(command.contains("overnightEveryBlockItem"));
        String host = Files.readString(project("src/main/java/baritone/agent/ClientRaceWorldHost.java"),
                StandardCharsets.UTF_8);
        assertTrue(host.contains("createFreshLevel"));
        assertTrue(host.contains("WorldPresets.FLAT"));
        assertTrue(host.contains("WorldOptions.parseSeed"));
        assertTrue(host.contains("GameType.SURVIVAL"));
        assertTrue(host.contains("openWorld"));
        assertTrue(host.contains("disconnect"));
        assertFalse(host.contains("STUB_NEW"));

        assertEquals(RaceWorldPlans.Kind.CREATE_NEW, RaceWorldPlans.plan(true, false, false, false));
        assertEquals(RaceWorldPlans.Kind.OPEN_EXISTING, RaceWorldPlans.plan(true, true, false, false));
        assertEquals(RaceWorldPlans.Kind.ALREADY_OPEN, RaceWorldPlans.plan(true, true, true, true));
        assertEquals(RaceWorldPlans.Kind.BUSY, RaceWorldPlans.plan(true, true, true, false));
        assertEquals(RaceWorldPlans.Kind.REJECT, RaceWorldPlans.plan(false, true, false, false));

        assertEquals("pvp players", AgentActions.toCommand("pvp", Map.of("target", "players")));
        assertEquals("pvp Steve", AgentActions.toCommand("pvp", Map.of("target", "Steve")));
        assertEquals("set autoTool true", AgentActions.toCommand("gear", Map.of("name", "autoTool", "value", "true")));
        assertEquals(null, AgentActions.toCommand("gear", Map.of("name", "agentApiKey", "value", "secret")));
        assertTrue(AgentPolicy.toolAllowed(AgentPolicy.DEFAULT_TOOLS, "pvp"));
        assertTrue(AgentPolicy.toolAllowed(AgentPolicy.DEFAULT_TOOLS, "gear"));

        for (String rel : new String[]{
                "src/main/java/baritone/pathing/calc/AbstractNodeCostSearch.java",
                "src/main/java/baritone/pathing/decide/PathDecisionMatrix.java"}) {
            String src = Files.readString(project(rel), StandardCharsets.UTF_8);
            assertFalse(rel, src.contains("overnightRaces") || src.contains("OvernightOrchestrator"));
        }
        String readme = Files.readString(project("docs/race/overnight.md"), StandardCharsets.UTF_8);
        assertTrue(readme.contains("queue.jsonl"));
        assertTrue(readme.contains("races.jsonl"));
        assertTrue(readme.contains("stubbed"));
        assertTrue(readme.contains("CREATE_NEW"));
        assertFalse(readme.contains("STUB_NEW"));
        assertTrue(readme.contains("vexflat"));
    }

    @Test
    public void seedFlatParsingFolderCollisionAndMetrics() throws Exception {
        HypothesisRun omitted = HypothesisQueue.parse(one("hyp_a")).get(0);
        assertEquals("", omitted.seed);
        assertTrue(omitted.flat);
        assertEquals(OptionalLong.empty(), RaceWorldPlans.parseSeed(null));
        assertEquals(OptionalLong.empty(), RaceWorldPlans.parseSeed("  "));
        assertEquals(OptionalLong.of(71006L), RaceWorldPlans.parseSeed(" 71006 "));
        assertEquals(OptionalLong.of(-3L), RaceWorldPlans.parseSeed("-3"));
        assertEquals(OptionalLong.of("stone".hashCode()), RaceWorldPlans.parseSeed("stone"));
        for (String text : new String[]{"", "  ", "71006", "-3", "stone", "999999999999999999999"}) {
            assertEquals(text, WorldOptions.parseSeed(text), RaceWorldPlans.parseSeed(text));
        }

        Path saves = Files.createTempDirectory("ostinato-folder");
        assertEquals("race_x", RaceWorldPlans.resolvedFolder(saves, "race_x"));
        assertEquals("", RaceWorldPlans.folderMismatch("race_x", "race_x"));
        Files.createDirectories(saves.resolve("race_x"));
        assertEquals("race_x (1)", RaceWorldPlans.resolvedFolder(saves, "race_x"));
        String renamed = RaceWorldPlans.folderMismatch("race_x", "race_x (1)");
        assertTrue(renamed.contains("race_x"));
        assertTrue(renamed.contains("race_x (1)"));
        Files.createDirectories(saves.resolve("race_x (1)"));
        assertEquals("race_x (2)", RaceWorldPlans.resolvedFolder(saves, "race_x"));

        Fixture fix = fixture(QUEUE);
        drain(fix.orch, 0, 1);
        String races = Files.readString(fix.races, StandardCharsets.UTF_8);
        assertTrue(races.contains("\"seed\":\"71006\""));
        assertTrue(races.contains("\"flat\":\"true\""));
        assertTrue(races.contains("\"seed\":\"\""));
        assertTrue(races.contains("\"flat\":\"false\""));

        Fixture failed = fixture(QUEUE);
        failed.host.enqueue(RaceWorldHost.Status.FAILED);
        drain(failed.orch, 0, 1);
        String failedLines = Files.readString(failed.races, StandardCharsets.UTF_8);
        assertTrue(failedLines.contains("\"outcome\":\"error\""));
        assertTrue(failedLines.contains("\"seed\":\"71006\""));
        assertTrue(failedLines.contains("\"flat\":\"true\""));
        assertTrue(failedLines.contains("\"runId\":\"speed-A-hyp_a\""));
        assertTrue(failedLines.contains("\"flat\":\"false\""));
    }

    private static String one(String world) {
        return "{\"hypothesis\":\"speed\",\"variant\":\"A\",\"world\":\"" + world + "\"}\n";
    }

    private static Fixture fixture(String jsonl) throws IOException {
        return fixture(HypothesisQueue.parse(jsonl));
    }

    private static Fixture fixture(List<HypothesisRun> runs) throws IOException {
        Path dir = Files.createTempDirectory("ostinato-overnight");
        Path saves = dir.resolve("saves");
        Files.createDirectories(saves);
        MemoryRaceWorldHost host = new MemoryRaceWorldHost(saves);
        ScriptAgent agent = new ScriptAgent();
        Path races = dir.resolve("out").resolve("races.jsonl");
        Path state = dir.resolve("state.json");
        OvernightOrchestrator orch = new OvernightOrchestrator(runs, host, agent, new CountScorer(),
                RaceWorldCleanup.INSTANCE, new JsonlRaceRecorder(races), saves, state, true, 0);
        return new Fixture(dir, saves, races, state, host, agent, orch, true);
    }

    private static void drain(OvernightOrchestrator orch, long now, long step) {
        if (orch.phase() == OvernightOrchestrator.Phase.IDLE) {
            orch.start(now);
        }
        for (int i = 0; i < 40 && orch.phase() != OvernightOrchestrator.Phase.DONE; i++) {
            orch.poll(now + (long) i * step);
        }
    }

    private static Path project(String rel) {
        Path path = Path.of(rel);
        return Files.exists(path) ? path : Path.of("..").resolve(rel);
    }

    private static final class Fixture {
        final Path dir;
        final Path saves;
        final Path races;
        final Path state;
        final MemoryRaceWorldHost host;
        final ScriptAgent agent;
        final OvernightOrchestrator orch;
        final boolean mark;

        Fixture(Path dir, Path saves, Path races, Path state, MemoryRaceWorldHost host, ScriptAgent agent,
                OvernightOrchestrator orch, boolean mark) {
            this.dir = dir;
            this.saves = saves;
            this.races = races;
            this.state = state;
            this.host = host;
            this.agent = agent;
            this.orch = orch;
            this.mark = mark;
        }

        Fixture withMark(boolean markRaceRoot) {
            OvernightOrchestrator orch = new OvernightOrchestrator(HypothesisQueue.parse(one("hyp_a")), host, agent,
                    new CountScorer(), RaceWorldCleanup.INSTANCE, new JsonlRaceRecorder(races), saves, state,
                    markRaceRoot, 0);
            return new Fixture(dir, saves, races, state, host, agent, orch, markRaceRoot);
        }

        OvernightOrchestrator copy() {
            return new OvernightOrchestrator(HypothesisQueue.parse(QUEUE), new MemoryRaceWorldHost(saves), new ScriptAgent(),
                    new CountScorer(), RaceWorldCleanup.INSTANCE, new JsonlRaceRecorder(races), saves, state, true, 0);
        }
    }

    private static final class ScriptAgent implements OvernightAgent {
        int begins;
        int hold;
        boolean begun;

        @Override
        public void begin(HypothesisRun run) {
            begins++;
            begun = true;
        }

        @Override
        public OvernightAgent.AgentTick poll(long nowMs) {
            if (!begun) {
                return OvernightAgent.AgentTick.pending();
            }
            if (hold > 0) {
                hold--;
                return OvernightAgent.AgentTick.pending();
            }
            return new OvernightAgent.AgentTick(OvernightAgent.AgentTick.Status.DONE, "groq", "openai/gpt-oss-20b", "ok");
        }

        @Override
        public void cancel() {
            begun = false;
            hold = 0;
        }
    }

    private static final class CountScorer implements OvernightScorer {
        @Override
        public OvernightScore score(HypothesisRun run, OvernightAgent.AgentTick agent, long elapsedMs) {
            Map<String, String> metrics = new LinkedHashMap<>();
            metrics.put("variant", run.variant);
            metrics.put("world", run.world);
            metrics.put("score", "3/3");
            metrics.put("timeMs", Long.toString(Math.max(0, elapsedMs)));
            return new OvernightScore("3/3", "pass", metrics);
        }
    }

    private static final class FlakyRecorder implements RaceRecorder {
        final JsonlRaceRecorder inner;
        int fails;

        FlakyRecorder(JsonlRaceRecorder inner) {
            this.inner = inner;
        }

        @Override
        public void record(RaceRun run) throws IOException {
            if (fails > 0) {
                fails--;
                throw new IOException("disk");
            }
            inner.record(run);
        }

        @Override
        public boolean containsRun(String runId) throws IOException {
            return inner.containsRun(runId);
        }
    }
}
