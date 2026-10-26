package baritone.agent;

// Ostinato: overnight queue. One run at a time: create, agent, score, record, delete, next.
// Off by default. The caller only constructs this after overnightRaces is on.
// A run id already in races.jsonl is not written again.

import baritone.api.agent.RaceRecorder;
import baritone.api.agent.RaceRun;
import baritone.api.agent.RaceWorldHost;
import baritone.api.agent.WorldCleanup;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OvernightOrchestrator {

    public enum Phase {
        IDLE, CREATE_WORLD, RUN_AGENT, SCORE, RECORD, DELETE_WORLD, DONE
    }

    /** How long leave may take before the open world is left on disk. */
    static final long LEAVE_WAIT_MS = 10_000L;

    private final List<HypothesisRun> queue;
    private final RaceWorldHost host;
    private final OvernightAgent agent;
    private final OvernightScorer scorer;
    private final WorldCleanup cleanup;
    private final RaceRecorder recorder;
    private final Path savesRoot;
    private final Path stateFile;
    private final boolean markRaceRoot;
    private final long defaultTimeoutMs;

    private Phase phase = Phase.IDLE;
    private int index;
    private long runStart;
    private long phaseStart;
    private boolean abort;
    private boolean stopAfter;
    private boolean createStarted;
    private boolean agentStarted;
    private boolean leaveStarted;
    private boolean recorded;
    /** True only after this run's create returned READY. Leave is limited to that world. */
    private boolean opened;
    private String outcome = "";
    private String scoreText = "n/a";
    private String provider = "local";
    private String model = "";
    private String detail = "";
    private Map<String, String> metrics = new LinkedHashMap<>();
    private OvernightAgent.AgentTick agentTick = OvernightAgent.AgentTick.pending();

    public OvernightOrchestrator(List<HypothesisRun> queue, RaceWorldHost host, OvernightAgent agent,
            OvernightScorer scorer, WorldCleanup cleanup, RaceRecorder recorder, Path savesRoot, Path stateFile,
            boolean markRaceRoot, long defaultTimeoutMs) {
        this.queue = List.copyOf(queue);
        this.host = host;
        this.agent = agent;
        this.scorer = scorer;
        this.cleanup = cleanup;
        this.recorder = recorder;
        this.savesRoot = savesRoot;
        this.stateFile = stateFile;
        this.markRaceRoot = markRaceRoot;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public Phase phase() {
        return phase;
    }

    public int index() {
        return index;
    }

    public boolean stopRequested() {
        return stopAfter;
    }

    public boolean abortRequested() {
        return abort;
    }

    public void requestAbort() {
        abort = true;
    }

    public void requestStop() {
        stopAfter = true;
    }

    public String statusLine() {
        if (phase == Phase.DONE || phase == Phase.IDLE) {
            return phase == Phase.DONE ? "overnight done" : "overnight idle";
        }
        HypothesisRun run = current();
        String name = run == null ? "-" : run.hypothesis + " " + run.variant + " " + run.world;
        return "overnight " + (index + 1) + "/" + queue.size() + " " + phase + " " + name;
    }

    /** Begin at the first line. A previous state file is replaced. */
    public void start(long nowMs) {
        abort = false;
        stopAfter = false;
        index = 0;
        if (queue.isEmpty()) {
            phase = Phase.DONE;
            persist();
            return;
        }
        beginRun(nowMs);
        persist();
    }

    /**
     * Continue an in-progress state file. A missing file or a finished queue starts from the first line.
     * Returns true when an in-progress run was restored.
     */
    public boolean resumeOrStart(long nowMs) {
        if (stateFile != null && Files.isRegularFile(stateFile)) {
            try {
                if (load()) {
                    return true;
                }
            } catch (RuntimeException | IOException ex) {
                // A broken state file starts over. The queue file is the source of runs.
            }
        }
        start(nowMs);
        return false;
    }

    /** One transition, or no change while create, the agent, or leave is still pending. */
    public Phase poll(long nowMs) {
        if (phase == Phase.IDLE || phase == Phase.DONE) {
            return phase;
        }
        if (abort && phase != Phase.RECORD && phase != Phase.DELETE_WORLD) {
            cancelAgent();
            outcome = "abort";
            scoreText = "n/a";
            ensureMetrics(nowMs);
            phase = Phase.RECORD;
            phaseStart = nowMs;
            leaveStarted = false;
            persist();
            return phase;
        }
        switch (phase) {
            case CREATE_WORLD -> stepCreate(nowMs);
            case RUN_AGENT -> stepAgent(nowMs);
            case SCORE -> stepScore(nowMs);
            case RECORD -> stepRecord(nowMs);
            case DELETE_WORLD -> stepDelete(nowMs);
            default -> { }
        }
        persist();
        return phase;
    }

    private void stepCreate(long nowMs) {
        HypothesisRun run = current();
        if (run == null) {
            phase = Phase.DONE;
            return;
        }
        if (timedOut(nowMs, run)) {
            timeout(nowMs);
            return;
        }
        if (!createStarted) {
            host.beginCreate(run.world, run.seed, run.flat);
            createStarted = true;
        }
        RaceWorldHost.Result result = host.pollCreate();
        if (result.status == RaceWorldHost.Status.PENDING) {
            return;
        }
        if (result.status == RaceWorldHost.Status.READY) {
            opened = true;
            phase = Phase.RUN_AGENT;
            phaseStart = nowMs;
            agentStarted = false;
            return;
        }
        outcome = result.status == RaceWorldHost.Status.STUBBED ? "stubbed" : "error";
        detail = result.detail;
        scoreText = "n/a";
        ensureMetrics(nowMs);
        phase = Phase.RECORD;
        phaseStart = nowMs;
        leaveStarted = false;
    }

    private void stepAgent(long nowMs) {
        HypothesisRun run = current();
        if (run == null) {
            phase = Phase.DONE;
            return;
        }
        if (timedOut(nowMs, run)) {
            cancelAgent();
            timeout(nowMs);
            return;
        }
        if (!agentStarted) {
            agent.begin(run);
            agentStarted = true;
        }
        agentTick = agent.poll(nowMs);
        if (agentTick.status == OvernightAgent.AgentTick.Status.PENDING) {
            return;
        }
        provider = agentTick.provider.isBlank() ? "local" : agentTick.provider;
        model = agentTick.model;
        if (!agentTick.detail.isBlank()) {
            detail = agentTick.detail;
        }
        phase = Phase.SCORE;
        phaseStart = nowMs;
    }

    private void stepScore(long nowMs) {
        HypothesisRun run = current();
        if (run == null) {
            phase = Phase.DONE;
            return;
        }
        OvernightScorer.OvernightScore scored = scorer.score(run, agentTick, Math.max(0, nowMs - runStart));
        outcome = scored.outcome;
        scoreText = scored.score;
        metrics = new LinkedHashMap<>(scored.metrics);
        ensureMetrics(nowMs);
        phase = Phase.RECORD;
        phaseStart = nowMs;
        leaveStarted = false;
    }

    private void stepRecord(long nowMs) {
        HypothesisRun run = current();
        if (run == null) {
            phase = Phase.DONE;
            return;
        }
        ensureMetrics(nowMs);
        try {
            if (!recorded && !recorder.containsRun(run.runId())) {
                recorder.record(new RaceRun(run.runId(), run.hypothesis, "agent",
                        provider.isBlank() ? "local" : provider, model, metrics,
                        outcome.isBlank() ? "incomplete" : outcome));
            }
            recorded = true;
            phase = Phase.DELETE_WORLD;
            phaseStart = nowMs;
            leaveStarted = false;
        } catch (IOException ex) {
            detail = "record failed";
        }
    }

    private void stepDelete(long nowMs) {
        // Ostinato: only leave a world this run opened. A stubbed or failed create must not drop the user's world.
        if (opened) {
            if (!leaveStarted) {
                host.beginLeave();
                leaveStarted = true;
            }
            if (host.inWorld()) {
                host.pollLeave();
                if (host.inWorld() && nowMs - phaseStart < LEAVE_WAIT_MS) {
                    return;
                }
                if (host.inWorld()) {
                    finishRun(nowMs);
                    return;
                }
            }
        }
        try {
            mark();
            HypothesisRun run = current();
            boolean openIsRun = run != null && host.inWorld() && run.world.equals(host.currentWorld());
            if (run != null && !openIsRun) {
                cleanup.deleteWorld(savesRoot, run.world);
            }
            if (!host.inWorld()) {
                cleanup.deleteLeftovers(savesRoot);
            }
        } catch (IOException ex) {
            detail = "delete failed";
        }
        finishRun(nowMs);
    }

    private void finishRun(long nowMs) {
        index++;
        if (abort || stopAfter || index >= queue.size()) {
            phase = Phase.DONE;
            return;
        }
        beginRun(nowMs);
    }

    private void beginRun(long nowMs) {
        phase = Phase.CREATE_WORLD;
        runStart = nowMs;
        phaseStart = nowMs;
        outcome = "";
        scoreText = "n/a";
        provider = "local";
        model = "";
        detail = "";
        metrics = new LinkedHashMap<>();
        createStarted = false;
        agentStarted = false;
        leaveStarted = false;
        recorded = false;
        opened = false;
        agentTick = OvernightAgent.AgentTick.pending();
    }

    private void timeout(long nowMs) {
        outcome = "timeout";
        scoreText = "n/a";
        ensureMetrics(nowMs);
        phase = Phase.RECORD;
        phaseStart = nowMs;
        leaveStarted = false;
    }

    private void cancelAgent() {
        try {
            agent.cancel();
        } catch (RuntimeException ignored) {
            // A cancel must not skip the record and delete.
        }
    }

    private boolean timedOut(long nowMs, HypothesisRun run) {
        long limit = run.timeoutOr(defaultTimeoutMs);
        return limit > 0 && nowMs - runStart >= limit;
    }

    private void ensureMetrics(long nowMs) {
        if (metrics == null) {
            metrics = new LinkedHashMap<>();
        } else {
            metrics = new LinkedHashMap<>(metrics);
        }
        HypothesisRun run = current();
        if (run != null) {
            metrics.putIfAbsent("variant", run.variant);
            metrics.putIfAbsent("world", run.world);
        }
        metrics.putIfAbsent("score", scoreText == null || scoreText.isBlank() ? "n/a" : scoreText);
        metrics.putIfAbsent("timeMs", Long.toString(Math.max(0, nowMs - runStart)));
        if (detail != null && !detail.isBlank()) {
            metrics.putIfAbsent("detail", detail);
        }
    }

    private void mark() throws IOException {
        if (!markRaceRoot || savesRoot == null || !Files.isDirectory(savesRoot)) {
            return;
        }
        Path marker = savesRoot.resolve(RaceWorldCleanup.MARKER);
        if (!Files.isRegularFile(marker)) {
            Files.writeString(marker, "race root\n", StandardCharsets.UTF_8);
        }
    }

    private HypothesisRun current() {
        if (index < 0 || index >= queue.size()) {
            return null;
        }
        return queue.get(index);
    }

    private void persist() {
        if (stateFile == null) {
            return;
        }
        try {
            JsonObject object = new JsonObject();
            object.addProperty("index", index);
            object.addProperty("phase", phase.name());
            HypothesisRun run = current();
            object.addProperty("runId", run == null ? "" : run.runId());
            object.addProperty("runStartMs", runStart);
            object.addProperty("phaseStartMs", phaseStart);
            object.addProperty("abort", abort);
            object.addProperty("stopAfter", stopAfter);
            object.addProperty("recorded", recorded);
            object.addProperty("opened", opened);
            object.addProperty("outcome", outcome);
            object.addProperty("score", scoreText);
            object.addProperty("provider", provider);
            object.addProperty("model", model);
            object.addProperty("detail", detail);
            JsonObject metricObject = new JsonObject();
            for (Map.Entry<String, String> entry : metrics.entrySet()) {
                metricObject.addProperty(entry.getKey(), entry.getValue());
            }
            object.add("metrics", metricObject);
            Path parent = stateFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, object.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {
            // The queue still advances. A later poll tries the state file again.
        }
    }

    /** @return true when the saved phase is an in-progress run */
    private boolean load() throws IOException {
        JsonElement element = JsonParser.parseString(Files.readString(stateFile, StandardCharsets.UTF_8));
        if (!element.isJsonObject()) {
            return false;
        }
        JsonObject object = element.getAsJsonObject();
        index = object.get("index").getAsInt();
        phase = Phase.valueOf(object.get("phase").getAsString());
        runStart = object.has("runStartMs") ? object.get("runStartMs").getAsLong() : 0L;
        phaseStart = object.has("phaseStartMs") ? object.get("phaseStartMs").getAsLong() : runStart;
        abort = object.has("abort") && object.get("abort").getAsBoolean();
        stopAfter = object.has("stopAfter") && object.get("stopAfter").getAsBoolean();
        recorded = object.has("recorded") && object.get("recorded").getAsBoolean();
        opened = object.has("opened") && object.get("opened").getAsBoolean();
        outcome = text(object, "outcome");
        scoreText = text(object, "score");
        if (scoreText.isBlank()) {
            scoreText = "n/a";
        }
        provider = text(object, "provider");
        if (provider.isBlank()) {
            provider = "local";
        }
        model = text(object, "model");
        detail = text(object, "detail");
        metrics = new LinkedHashMap<>();
        if (object.has("metrics") && object.get("metrics").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("metrics").entrySet()) {
                if (!entry.getValue().isJsonNull()) {
                    metrics.put(entry.getKey(), entry.getValue().getAsString());
                }
            }
        }
        createStarted = false;
        agentStarted = false;
        leaveStarted = false;
        if (phase == Phase.DONE || phase == Phase.IDLE || index >= queue.size()) {
            phase = Phase.DONE;
            return false;
        }
        return true;
    }

    private static String text(JsonObject object, String name) {
        if (!object.has(name) || object.get(name).isJsonNull()) {
            return "";
        }
        return object.get(name).getAsString();
    }
}
