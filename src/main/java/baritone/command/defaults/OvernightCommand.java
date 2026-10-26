package baritone.command.defaults;

// Ostinato: #overnight runs the hypothesis queue when overnightRaces is on.
// Off, this returns without creating or deleting a world. One queue at a time.

import baritone.Baritone;
import baritone.agent.AgentConfig;
import baritone.agent.AgentSession;
import baritone.agent.ClientRaceWorldHost;
import baritone.agent.HarnessScorer;
import baritone.agent.HypothesisQueue;
import baritone.agent.HypothesisRun;
import baritone.agent.JsonlRaceRecorder;
import baritone.agent.OpenAiChatClient;
import baritone.agent.OvernightOrchestrator;
import baritone.agent.RaceWorldCleanup;
import baritone.agent.SessionOvernightAgent;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class OvernightCommand extends Command {

    private OvernightOrchestrator active;
    private OvernightOrchestrator.Phase logged = OvernightOrchestrator.Phase.IDLE;

    public OvernightCommand(IBaritone baritone) {
        super(baritone, "overnight");
        // Ostinato: the listener returns immediately while overnightRaces is off or no queue is running.
        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onTick(TickEvent event) {
                tick(event);
            }
        });
    }

    private void tick(TickEvent event) {
        if (event.getType() != TickEvent.Type.IN || active == null) {
            return;
        }
        if (!Baritone.settings().overnightRaces.value) {
            return;
        }
        OvernightOrchestrator.Phase before = active.phase();
        OvernightOrchestrator.Phase after = active.poll(System.currentTimeMillis());
        if (after != before && after != logged) {
            logDirect(active.statusLine());
            logged = after;
        }
        if (after == OvernightOrchestrator.Phase.DONE) {
            active = null;
        }
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        String extra = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "";
        args.requireMax(0);
        Settings settings = Baritone.settings();
        // Ostinato: default off. A stray #overnight does not create or delete a world.
        if (!settings.overnightRaces.value) {
            logDirect("overnightRaces is off");
            return;
        }
        switch (action) {
            case "start" -> start(settings, "fresh".equals(extra));
            case "stop" -> {
                if (active == null) {
                    logDirect("overnight idle");
                    return;
                }
                active.requestStop();
                logDirect("overnight stop");
            }
            case "abort" -> {
                if (active == null) {
                    logDirect("overnight idle");
                    return;
                }
                active.requestAbort();
                logDirect("overnight abort");
            }
            case "queue" -> printQueue(settings);
            case "status" -> logDirect(active == null ? "overnight idle" : active.statusLine());
            default -> logDirect("usage: #overnight start [fresh] | stop | abort | status | queue");
        }
    }

    private void start(Settings settings, boolean fresh) {
        if (active != null && active.phase() != OvernightOrchestrator.Phase.DONE) {
            logDirect("overnight: busy");
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) {
            logDirect("overnight: no client");
            return;
        }
        Path dir = Path.of(settings.overnightDir.value == null ? "baritone/overnight" : settings.overnightDir.value);
        if (!dir.isAbsolute()) {
            dir = mc.gameDirectory.toPath().resolve(dir);
        }
        Path queueFile = dir.resolve("queue.jsonl");
        List<HypothesisRun> queue;
        try {
            queue = HypothesisQueue.load(queueFile);
        } catch (IOException ex) {
            logDirect("no overnight queue");
            return;
        } catch (IllegalArgumentException ex) {
            logDirect("overnight: " + ex.getMessage());
            return;
        }
        if (queue.isEmpty()) {
            logDirect("overnight queue empty");
            return;
        }
        ClientRaceWorldHost host = new ClientRaceWorldHost(mc);
        boolean circuit = settings.overnightEveryBlockItem.value;
        String modelFile = settings.trainingModelFile.value;
        HarnessScorer scorer = new HarnessScorer(circuit, settings.trainingLegit.value,
                settings.trainingModel.value,
                modelFile == null || modelFile.isBlank() ? null : Path.of(modelFile));
        SessionOvernightAgent agent = new SessionOvernightAgent(AgentSession.CLIENT, settings.agentOneSession.value,
                settings.agentApi.value, settings.agentChat.value, settings.agentTools.value,
                settings.agent429ToOllama.value, settings.agentMemory.value, AgentCommand.MEMORY_FILE,
                AgentConfig::fromSettings, commands -> apply(commands));
        long timeout = settings.overnightTimeoutMs.value == null ? 600_000L : settings.overnightTimeoutMs.value.longValue();
        OvernightOrchestrator orch = new OvernightOrchestrator(queue, host, agent, scorer, RaceWorldCleanup.INSTANCE,
                new JsonlRaceRecorder(dir.resolve("races.jsonl")), host.savesRoot(), dir.resolve("state.json"),
                true, timeout);
        long now = System.currentTimeMillis();
        if (fresh) {
            orch.start(now);
        } else {
            orch.resumeOrStart(now);
        }
        active = orch;
        logged = orch.phase();
        logDirect(orch.statusLine());
        if (circuit) {
            logDirect("overnight scoring every_block_item");
        }
    }

    private void apply(List<String> commands) {
        String key = AgentConfig.fromSettings().apiKey();
        boolean dry = Baritone.settings().agentDryRun.value;
        for (String line : commands) {
            String shown = OpenAiChatClient.safeText(line, key);
            if (dry) {
                logDirect("agent dry-run: " + shown);
                continue;
            }
            try {
                if (!baritone.getCommandManager().execute(line)) {
                    logDirect("agent: no such command " + shown);
                }
            } catch (Throwable ignored) {
                logDirect("agent: could not run " + shown);
            }
        }
    }

    private void printQueue(Settings settings) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) {
            logDirect("overnight: no client");
            return;
        }
        Path dir = Path.of(settings.overnightDir.value == null ? "baritone/overnight" : settings.overnightDir.value);
        if (!dir.isAbsolute()) {
            dir = mc.gameDirectory.toPath().resolve(dir);
        }
        try {
            int n = 1;
            for (HypothesisRun run : HypothesisQueue.load(dir.resolve("queue.jsonl"))) {
                logDirect(n + " " + run.hypothesis + " " + run.variant + " " + run.world);
                n++;
            }
            if (n == 1) {
                logDirect("overnight queue empty");
            }
        } catch (IOException ex) {
            logDirect("no overnight queue");
        } catch (IllegalArgumentException ex) {
            logDirect("overnight: " + ex.getMessage());
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.of("start", "fresh", "stop", "abort", "status", "queue");
    }

    @Override
    public String getShortDesc() {
        return "Run the overnight hypothesis queue";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Runs queued A/B hypothesis races on one client. The harness scores. The agent drives the bot.",
                "overnightRaces must be on. It defaults off, and an off command does not create or delete a world.",
                "Start writes ostinato-race-root.txt into the saves folder and then deletes only race_, hyp_, and agentrun_ worlds.",
                "vexflat, pk_, pkrun_, SigilTest, and gs_world are kept.",
                "Opening an existing race world is live. Creating a new world through the menu is not automated.",
                "overnightEveryBlockItem (off) adds the every_block_item score. It does not run while off.",
                "agentDryRun still prints commands instead of running them until you turn it off.",
                "",
                "Usage:",
                "> overnight start",
                "> overnight start fresh",
                "> overnight stop",
                "> overnight abort",
                "> overnight status",
                "> overnight queue");
    }
}
