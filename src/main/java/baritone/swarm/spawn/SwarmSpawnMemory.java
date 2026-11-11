package baritone.swarm.spawn;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

// Ostinato: commit, not physical RAM; injectable so tests never query the host.
public final class SwarmSpawnMemory {
    public static final long REQUIRED = 1536L * 1024 * 1024;

    @FunctionalInterface
    public interface Probe {
        long freeBytes() throws IOException;
    }

    public static void require(Probe probe, int count) throws IOException {
        long free = probe.freeBytes();
        if (free < REQUIRED * count) {
            throw new IllegalArgumentException("Only " + free / (1024 * 1024)
                    + " MiB free commit; about 1.5 GB (1536 MiB) of free commit is required per bot ("
                    + count + " requested)");
        }
    }

    public static long freeCommit() throws IOException {
        if (!windows()) {
            throw new IOException("Free-commit probing is currently supported only on Windows");
        }
        try {
            return Long.parseLong(powershell("(Get-CimInstance Win32_OperatingSystem).FreeVirtualMemory").trim()) * 1024L;
        } catch (NumberFormatException e) {
            throw new IOException("Cannot determine free commit; spawn refused");
        }
    }

    static boolean windows() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    static String powershell(String script) throws IOException {
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("NUL")))
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // A dev classpath can exceed the pipe buffer; drain while CIM is still running.
        java.util.concurrent.FutureTask<byte[]> output = new java.util.concurrent.FutureTask<>(() -> process.getInputStream().readAllBytes());
        Thread reader = new Thread(output, "Ostinato spawn Windows query");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                throw new IOException("Windows process query timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Windows process query failed");
            }
            return new String(output.get(1, TimeUnit.SECONDS), StandardCharsets.UTF_8).trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Windows process query interrupted", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("Cannot read Windows process query", e);
        } finally {
            process.destroyForcibly();
        }
    }
}
