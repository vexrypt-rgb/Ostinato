package baritone.swarm.center.host;

import baritone.swarm.center.app.CenterApp;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Ostinato: Phase 3, {@code #center} on a bot with the feed on: start the standalone window ({@link CenterApp}) as its
 * own detached JVM from the jar (or class folder) this mod runs from, pointed at the feed directory. It outlives this
 * client. {@code #center close} ends that process by the pid recorded in {@code window.pid}; nothing else is touched.
 */
public final class CenterLauncher {

    /** JVM flags of the window process. */
    public static final List<String> JVM_FLAGS = List.of("-Xmx128m", "-XX:+UseSerialGC");

    private CenterLauncher() {}

    /** The command line, without starting anything (tests and the launch log). */
    public static List<String> command(Path javaHome, String classPath, Path dir, String windowPos) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
        Path bin = javaHome.resolve("bin").resolve(windows ? "javaw.exe" : "java");
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.toString());
        // Ostinato: a small heap and the serial collector keep the window near 180 MB private (p3cc run 1: 363 MB
        // with -Xmx256m and the default collector).
        cmd.addAll(JVM_FLAGS);
        cmd.add("-Dostinato.windowPos=" + (windowPos == null || windowPos.isBlank() ? CenterApp.DEFAULT_POS : windowPos));
        cmd.add("-cp");
        cmd.add(classPath);
        cmd.add(CenterApp.class.getName());
        cmd.add("--dir");
        cmd.add(dir.toAbsolutePath().toString());
        cmd.add("--log");
        cmd.add(dir.resolve("window.log").toAbsolutePath().toString());
        return cmd;
    }

    /** Where {@link CenterApp}'s classes live, or empty when that is not a plain jar or folder. */
    public static Optional<String> classPath() {
        try {
            URL url = CenterApp.class.getProtectionDomain().getCodeSource().getLocation();
            String s = url.toString();
            if (s.startsWith("jar:")) {
                s = s.substring(4, s.indexOf("!/") < 0 ? s.length() : s.indexOf("!/"));
            }
            File f = new File(new java.net.URI(s));
            return f.exists() ? Optional.of(f.getAbsolutePath()) : Optional.empty();
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Starts the window; returns its pid. A window this launcher started earlier and still running is reused. */
    public static long launch(Path dir) throws IOException {
        Optional<ProcessHandle> running = running(dir);
        if (running.isPresent()) {
            return running.get().pid();
        }
        String cp = classPath().orElseThrow(() -> new IOException("cannot find the window's classes; run "
                + "./gradlew -p center-app run --args=\"--dir " + dir + "\""));
        Files.createDirectories(dir);
        ProcessBuilder pb = new ProcessBuilder(command(Path.of(System.getProperty("java.home")), cp, dir,
                System.getProperty("ostinato.windowPos")));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(dir.resolve("window.out").toFile()));
        Process p = pb.start();
        Files.writeString(dir.resolve("window.pid"), Long.toString(p.pid()), StandardCharsets.US_ASCII);
        return p.pid();
    }

    /** Ends the window this launcher started, if it is still running. */
    public static boolean close(Path dir) {
        Optional<ProcessHandle> running = running(dir);
        running.ifPresent(ProcessHandle::destroy);
        return running.isPresent();
    }

    static Optional<ProcessHandle> running(Path dir) {
        try {
            long pid = Long.parseLong(Files.readString(dir.resolve("window.pid")).trim());
            return ProcessHandle.of(pid).filter(ProcessHandle::isAlive)
                    .filter(h -> h.info().commandLine().map(c -> c.contains(CenterApp.class.getName())).orElse(true));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
