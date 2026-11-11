package baritone.swarm.spawn;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

// Ostinato: this JVM owns only the processes it started, never processes discovered by name.
public final class SwarmSpawnRegistry {
    public static final SwarmSpawnRegistry INSTANCE = new SwarmSpawnRegistry();
    public record Child(String name, Process process, Path gameDir, Path log, String server) {
        public String status() {
            return name + " pid=" + process.pid() + " " + (process.isAlive() ? "alive" : "dead")
                    + " server=" + server + " log=" + log;
        }
    }

    private final Map<String, Child> children = new LinkedHashMap<>();

    private SwarmSpawnRegistry() {
        // ProcessHandle.current().onExit() is forbidden by the JDK. A shutdown hook is its in-process equivalent;
        // the child's external parent handle watcher also covers forced termination of this JVM.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> despawn("all"), "Ostinato swarm cleanup"));
    }

    public synchronized void checkNames(List<String> names, String parent) {
        for (String name : names) {
            if (name.equalsIgnoreCase(parent)) throw new IllegalArgumentException("Bot name must not equal parent username " + parent);
            Child existing = children.get(name.toLowerCase(Locale.ROOT));
            if (existing != null && existing.process().isAlive()) throw new IllegalArgumentException("Bot " + name + " is already running");
        }
    }

    public synchronized Child start(String name, String server, Path game, Path log, ProcessBuilder builder) throws IOException {
        checkNames(List.of(name), "");
        Process process = builder.start();
        Child child = new Child(name, process, game, log, server);
        children.put(name.toLowerCase(Locale.ROOT), child);
        return child;
    }

    public synchronized List<Child> list() {
        return new ArrayList<>(children.values());
    }

    public synchronized int despawn(String name) {
        int count = 0;
        for (Child child : new ArrayList<>(children.values())) {
            if (name.equalsIgnoreCase("all") || child.name().equalsIgnoreCase(name)) {
                child.process().destroy();
                try {
                    if (!child.process().waitFor(2, TimeUnit.SECONDS)) child.process().destroyForcibly();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    child.process().destroyForcibly();
                }
                children.remove(child.name().toLowerCase(Locale.ROOT));
                count++;
            }
        }
        return count;
    }
}
