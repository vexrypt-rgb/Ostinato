/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 1). New file, replaces no Baritone code.
 *
 * Entry point. Starts one bot connection against the local dedicated server, ticks it at 20 Hz,
 * writes evidence, and accepts a few local file commands (never a remote connection):
 *   probe <x> <y> <z>   append the block state held in the bot's chunk store to probe-out.txt
 *   status              write a status snapshot to the evidence file
 *   course <file>       load a scripted PlayerSim course and attach the movement driver
 *   goto <x> <y> <z>    plan with Ostinato A* and walk there
 *   mine <block>        path to the nearest loaded block of that id and break it
 *   place <x> <y> <z>   place one throwaway block into that cell against an adjacent support
 *   kill [type]          fight monsters (or a type such as zombie) with vanilla melee
 *   attack [type]        alias of kill
 *   craft <item> [count]  2x2 / table crafting via real container clicks or the recipe book
 *   smelt <item> [count]  place/open a furnace, smelt the item with coal, take the output
 *   chest deposit|withdraw <item> [count]
 *   drop <item> [count]   drop from the hotbar
 *   pickup [item]         walk over a nearby dropped item
 *   equip [item]          shift-click armor into the armor slots
 *   cancel              cancel pathing/mining/placing (does not exit)
 *   stop path           alias of cancel
 *   stop                end the run (quit and exit are aliases)
 *
 * The same lines are accepted from commands.txt, from stdin, and from an owner whisper / server
 * system chat (see {@link HeadlessCommands#commandFromChat}).
 */
package baritone.headless;

import net.minecraft.SharedConstants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.network.EventLoopGroupHolder;
import net.minecraft.core.RegistryAccess;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class HeadlessBotMain {

    private static final long TICK_NANOS = 50_000_000L; // 20 TPS

    private HeadlessBotMain() {
    }

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 25565;
        String username = args.length > 2 ? args[2] : "HBot1";
        long seconds = args.length > 3 ? Long.parseLong(args[3]) : 600L;
        Path evidence = Path.of(args.length > 4 ? args[4] : "headless-evidence.txt");
        Path commands = args.length > 5 ? Path.of(args[5]) : evidence.getParent().resolve("commands.txt");
        Path probeOut = evidence.getParent().resolve("probe-out.txt");

        Files.createDirectories(evidence.toAbsolutePath().getParent());
        Files.writeString(evidence, "", StandardCharsets.UTF_8);
        if (!Files.exists(commands)) {
            Files.writeString(commands, "", StandardCharsets.UTF_8);
        }
        Files.writeString(probeOut, "", StandardCharsets.UTF_8);

        // Plain JVM: no client main, no Fabric loader, no render thread. Bootstrap is enough for
        // registries, block states, voxel shapes and the network codecs (proven by the Phase 0 probe).
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        RegistryAccess.Frozen access = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);

        EventLoopGroupHolder holder = EventLoopGroupHolder.remote(false);
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        Connection.connect(new InetSocketAddress(host, port), holder, connection);

        HeadlessBot bot = new HeadlessBot(new HeadlessTransport.ConnectionTransport(connection),
                host, port, username, UUIDUtil.createOfflinePlayerUUID(username), access);
        ConcurrentLinkedQueue<String> inbox = new ConcurrentLinkedQueue<>();
        bot.setChatCommandListener(inbox::add);
        Thread stdin = new Thread(() -> readStdin(inbox), "headless-bot-stdin");
        stdin.setDaemon(true);
        stdin.start();
        long startedAt = System.nanoTime();
        bot.start();

        AtomicInteger tickerErrors = new AtomicInteger();
        ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(daemon("headless-bot-tick"));
        ticker.scheduleAtFixedRate(() -> {
            try {
                bot.tick();
            } catch (RuntimeException e) {
                tickerErrors.incrementAndGet();
                System.err.println("[HeadlessBotMain] tick error: " + e);
                e.printStackTrace(System.err);
            }
        }, 50, 50, TimeUnit.MILLISECONDS);

        long deadline = startedAt + TimeUnit.SECONDS.toNanos(seconds);
        long nextSnapshot = startedAt;
        List<String> commandLines = readLines(commands);
        boolean stop = false;
        boolean hostFailed = false;
        int exit = 0;
        try {
            while (System.nanoTime() < deadline && !stop) {
                Thread.sleep(250);
                if (!hostFailed && bot.hostReady() && bot.playerLoaded() && !bot.hostAttached()) {
                    try {
                        bot.ensureHost();
                        System.out.println("[HeadlessBotMain] pathing host attached");
                    } catch (RuntimeException e) {
                        hostFailed = true;
                        System.err.println("[HeadlessBotMain] pathing host failed: " + e);
                        e.printStackTrace(System.err);
                    }
                }
                List<String> now = readLines(commands);
                for (int i = commandLines.size(); i < now.size() && !stop; i++) {
                    stop = handle(now.get(i), bot, evidence, probeOut, startedAt, tickerErrors.get());
                }
                commandLines = now;
                String extra;
                while (!stop && (extra = inbox.poll()) != null) {
                    stop = handle(extra, bot, evidence, probeOut, startedAt, tickerErrors.get());
                }
                if (stop) {
                    break;
                }
                if (System.nanoTime() >= nextSnapshot) {
                    snapshot(evidence, bot, startedAt, tickerErrors.get(), "periodic");
                    nextSnapshot = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
                }
                if (!bot.isConnected()) {
                    snapshot(evidence, bot, startedAt, tickerErrors.get(), "DISCONNECTED");
                    exit = 2;
                    break;
                }
            }
        } finally {
            snapshot(evidence, bot, startedAt, tickerErrors.get(), "final");
            ticker.shutdownNow();
            try {
                ticker.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            bot.closeHost();
            try {
                connection.disconnect(Component.literal("headless bot finished"));
            } catch (RuntimeException ignored) {
                // channel may already be closed
            }
            holder.eventLoopGroup().shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }

        long elapsed = System.nanoTime() - startedAt;
        String summary = String.format("%nRESULT elapsed=%.1fs state=%s connected=%s corrections=%d ticks=%d packetsIn=%d packetsOut=%d chunks=%d%n",
                elapsed / 1e9, bot.state(), bot.isConnected(), bot.corrections(), bot.ticks(),
                bot.packetsIn(), bot.packetsOut(), bot.chunks().chunkCount());
        Files.writeString(evidence, summary, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        HeadlessMovement driver = bot.movement();
        if (driver != null) {
            String sim = String.format("SIM_FINAL x=%.9f y=%.9f z=%.9f | REPORTED x=%.9f y=%.9f z=%.9f"
                            + " | delta=%.12g | simTicks=%d positionPackets=%d resyncs=%d corrections=%d%n",
                    driver.sim().x, driver.sim().y, driver.sim().z,
                    driver.reportedX(), driver.reportedY(), driver.reportedZ(),
                    Math.sqrt(Math.pow(driver.sim().x - driver.reportedX(), 2)
                            + Math.pow(driver.sim().y - driver.reportedY(), 2)
                            + Math.pow(driver.sim().z - driver.reportedZ(), 2)),
                    driver.simTicks(), driver.positionPackets(), driver.resyncs(), bot.corrections());
            Files.writeString(evidence, sim, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
            System.out.print(sim);
        }
        System.out.print(summary);
        // Everything we created is shut down; exit explicitly so no Netty thread keeps the JVM alive.
        System.exit(exit);
    }

    /** @return true when the process should exit ({@code stop}, {@code quit}, {@code exit}) */
    private static boolean handle(String raw, HeadlessBot bot, Path evidence, Path probeOut,
                                  long startedAt, int tickerErrors) throws IOException {
        HeadlessCommands.Parsed parsed = HeadlessCommands.parse(raw);
        if (parsed == null) {
            return false;
        }
        System.out.println("[HeadlessBotMain] command: " + raw.trim());
        if (parsed.error() != null) {
            System.err.println("[HeadlessBotMain] " + parsed.error());
            return false;
        }
        switch (parsed.kind()) {
            case STOP -> {
                return true;
            }
            case CANCEL -> bot.enqueuePathing("cancel");
            case GOTO -> bot.enqueuePathing("goto " + parsed.x() + " " + parsed.y() + " " + parsed.z());
            case MINE -> bot.enqueuePathing("mine " + parsed.text());
            case PLACE -> bot.enqueuePathing("place " + parsed.x() + " " + parsed.y() + " " + parsed.z());
            case KILL -> bot.enqueuePathing(parsed.text().isEmpty() ? "kill" : "kill " + parsed.text());
            case CRAFT -> bot.enqueuePathing("craft " + parsed.text() + (parsed.x() != 1 ? " " + parsed.x() : ""));
            case SMELT -> bot.enqueuePathing("smelt " + parsed.text() + (parsed.x() != 1 ? " " + parsed.x() : ""));
            case DROP -> bot.enqueuePathing("drop " + parsed.text() + (parsed.x() != 1 ? " " + parsed.x() : ""));
            case PICKUP -> bot.enqueuePathing(parsed.text().isEmpty() ? "pickup" : "pickup " + parsed.text());
            case EQUIP -> bot.enqueuePathing(parsed.text().isEmpty() ? "equip" : "equip " + parsed.text());
            case CHEST -> bot.enqueuePathing("chest " + (parsed.y() == 1 ? "withdraw " : "deposit ") + parsed.text()
                    + (parsed.x() != 1 ? " " + parsed.x() : ""));
            case STATUS -> snapshot(evidence, bot, startedAt, tickerErrors, "command status");
            case PROBE -> probe(probeOut, bot, "probe " + parsed.x() + " " + parsed.y() + " " + parsed.z());
            case COURSE -> {
                if (bot.hostReady()) {
                    try {
                        bot.ensureHost();
                    } catch (RuntimeException e) {
                        System.err.println("[HeadlessBotMain] pathing host failed: " + e.getMessage());
                    }
                }
                Path course = Path.of(parsed.text());
                List<String> spec = Files.readAllLines(course, StandardCharsets.UTF_8);
                HeadlessBlockStateInterface bsi = new HeadlessBlockStateInterface(bot.chunks());
                HeadlessMovement driver = new HeadlessMovement(bsi, HeadlessMovement.parse(spec));
                bot.setMovement(driver);
                snapshot(evidence, bot, startedAt, tickerErrors, "course attached " + course);
            }
            default -> System.err.println("[HeadlessBotMain] unknown command: " + raw.trim());
        }
        return false;
    }

    private static void readStdin(ConcurrentLinkedQueue<String> inbox) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                inbox.add(line);
            }
        } catch (IOException e) {
            System.err.println("[HeadlessBotMain] stdin closed: " + e.getMessage());
        }
    }

    private static void probe(Path probeOut, HeadlessBot bot, String line) throws IOException {
        String[] parts = line.split("\\s+");
        if (parts.length != 4) {
            System.err.println("[HeadlessBotMain] usage: probe <x> <y> <z>");
            return;
        }
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);
        int z = Integer.parseInt(parts[3]);
        var state = bot.chunks().getBlockState(x, y, z);
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String out = String.format("probe %d %d %d state=%s block=%s chunks=%d sectionsLoaded=%d%n",
                x, y, z, state, blockId, bot.chunks().chunkCount(), bot.chunks().decodedSections());
        Files.writeString(probeOut, out, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        System.out.print(out);
    }

    private static void snapshot(Path evidence, HeadlessBot bot, long startedAt, int tickerErrors, String why) {
        String head = String.format("%n===== %s @ %.1fs (MST %s) tickErrors=%d =====%n",
                why, (System.nanoTime() - startedAt) / 1e9,
                java.time.LocalDateTime.now(), tickerErrors);
        try {
            Files.writeString(evidence, head + bot.status(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("[HeadlessBotMain] evidence write failed: " + e);
        }
    }

    private static List<String> readLines(Path p) {
        try {
            return new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }
}
