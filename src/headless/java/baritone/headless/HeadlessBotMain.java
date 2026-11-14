/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 1). New file, replaces no Baritone code.
 *
 * Entry point. Starts one bot connection against the local dedicated server, ticks it at 20 Hz,
 * writes evidence, and accepts a few local file commands (never a remote connection):
 *   probe <x> <y> <z>   append the block state held in the bot's chunk store to probe-out.txt
 *   status              write a status snapshot to the evidence file
 *   course <file>       load a scripted PlayerSim course and attach the movement driver
 *   stop                end the run early
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
        int exit = 0;
        try {
            while (System.nanoTime() < deadline && !stop) {
                Thread.sleep(250);
                List<String> now = readLines(commands);
                for (int i = commandLines.size(); i < now.size(); i++) {
                    String line = now.get(i).trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    System.out.println("[HeadlessBotMain] command: " + line);
                    if (line.equals("stop")) {
                        stop = true;
                    } else if (line.equals("status")) {
                        snapshot(evidence, bot, startedAt, tickerErrors.get(), "command status");
                    } else if (line.startsWith("probe ")) {
                        probe(probeOut, bot, line);
                    } else if (line.startsWith("course ")) {
                        Path course = Path.of(line.substring("course ".length()).trim());
                        List<String> spec = Files.readAllLines(course, StandardCharsets.UTF_8);
                        HeadlessBlockStateInterface bsi = new HeadlessBlockStateInterface(bot.chunks());
                        HeadlessMovement driver = new HeadlessMovement(bsi, HeadlessMovement.parse(spec));
                        bot.setMovement(driver);
                        snapshot(evidence, bot, startedAt, tickerErrors.get(), "course attached " + course);
                    } else {
                        System.err.println("[HeadlessBotMain] unknown command: " + line);
                    }
                }
                commandLines = now;
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
