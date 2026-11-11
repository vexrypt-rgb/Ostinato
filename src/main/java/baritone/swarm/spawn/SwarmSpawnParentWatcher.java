package baritone.swarm.spawn;

import java.io.PrintStream; // Ostinato: testable shutdown announcement.

// Ostinato: a daemon in the child covers parent crashes and forced termination, not only clean shutdown.
public final class SwarmSpawnParentWatcher {
    private static boolean installed;

    public static synchronized void install() {
        String raw = System.getProperty("ostinato.swarm.spawnParentPid");
        if (installed || raw == null || raw.isBlank()) return;
        installed = true;
        Thread watcher = new Thread(() -> watch(raw.trim()), "Ostinato spawn parent watcher");
        watcher.setDaemon(true);
        watcher.start();
    }

    // Ostinato: exit only after the parent handle is observed to have died, never on a lookup/parse failure.
    static void watch(String raw) {
        try {
            long pid = Long.parseLong(raw);
            if (pid == ProcessHandle.current().pid()) {
                System.err.println("Ostinato: spawn parent PID is this process; not watching.");
                return;
            }
            ProcessHandle parent = waitForHandle(pid);
            if (parent == null) {
                System.err.println("Ostinato: could not open spawn parent PID " + pid + "; child will not auto-exit.");
                return;
            }
            if (parent.isAlive()) {
                parent.onExit().join();
            }
            announceParentExit(pid, System.out); // Ostinato: persist the shutdown reason before exiting.
            System.err.flush(); // Ostinato: also drain pending diagnostics.
            System.exit(0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            System.err.println("Ostinato: spawn parent watcher failed: " + e.getMessage());
        }
    }

    // Ostinato: separately testable without terminating the test process.
    static void announceParentExit(long pid, PrintStream out) {
        out.println("Ostinato: spawn parent PID " + pid + " exited; child exiting.");
        out.flush();
    }

    private static ProcessHandle waitForHandle(long pid) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            var handle = ProcessHandle.of(pid);
            if (handle.isPresent()) return handle.get();
            Thread.sleep(100L);
        }
        return ProcessHandle.of(pid).orElse(null);
    }
}
