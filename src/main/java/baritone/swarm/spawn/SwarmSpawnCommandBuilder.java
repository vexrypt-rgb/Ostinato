package baritone.swarm.spawn;

import baritone.swarm.accounts.AuthBlob;
import baritone.swarm.accounts.PrivateFiles;
import baritone.swarm.accounts.SecretProtector;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

// Ostinato: reconstruct the client directly, never invoke its Gradle/launcher wrapper.
public final class SwarmSpawnCommandBuilder {
    private static final Set<String> REPLACED = Set.of("--username", "--uuid", "--accessToken", "--gameDir",
            "--quickPlayMultiplayer", "--quickPlaySingleplayer", "--quickPlayRealms", "--quickPlayPath",
            "--userProperties", "--profileProperties", "--clientId", "--xuid", "--userType", "--server", "--port");

    public record Snapshot(List<String> command, Path workingDirectory, Path gameDirectory, long pid) {}

    public static Snapshot current(Path gameDirectory) throws IOException {
        List<String> command = null;
        ProcessHandle.Info info = ProcessHandle.current().info();
        if (SwarmSpawnMemory.windows()) {
            try {
                command = split(SwarmSpawnMemory.powershell("[Console]::OutputEncoding = [Text.Encoding]::UTF8; "
                        + "(Get-CimInstance Win32_Process -Filter 'ProcessId=" + ProcessHandle.current().pid() + "').CommandLine"));
            } catch (IOException | IllegalArgumentException ignored) {
                // MXBean fallback below also sees arguments supplied by JAVA_TOOL_OPTIONS.
            }
        } else if (info.command().isPresent() && info.arguments().isPresent()) {
            command = new ArrayList<>();
            command.add(info.command().get());
            command.addAll(Arrays.asList(info.arguments().get()));
        }
        if (command == null || command.isEmpty()) {
            command = fallback(System.getProperty("java.home"), ManagementFactory.getRuntimeMXBean().getInputArguments(),
                    System.getProperty("java.class.path"), System.getProperty("sun.java.command", ""));
        } else {
            // OS argv omits JAVA_TOOL_OPTIONS; retain its properties/classpath-related JVM options too.
            List<String> extra = new ArrayList<>(ManagementFactory.getRuntimeMXBean().getInputArguments());
            extra.removeAll(command);
            command.addAll(1, extra);
        }
        // Launchers sometimes hide their classpath in @argfiles. The VM has already expanded those.
        if (command.stream().anyMatch(arg -> arg.startsWith("@"))) {
            command = fallback(System.getProperty("java.home"), ManagementFactory.getRuntimeMXBean().getInputArguments(),
                    System.getProperty("java.class.path"), System.getProperty("sun.java.command", ""));
        }
        mainIndex(command); // Refuse an unsupported launch before provisioning keys or settings.
        Path java = Path.of(command.get(0));
        if (!Files.isRegularFile(java) || !java.getFileName().toString().matches("(?i)java(w)?(\\.exe)?")) {
            throw new IOException("Cannot find the current client's Java command; spawn refused");
        }
        return new Snapshot(List.copyOf(command), Path.of(System.getProperty("user.dir")).toAbsolutePath(),
                gameDirectory.toAbsolutePath(), ProcessHandle.current().pid());
    }

    public static List<String> fallback(String javaHome, List<String> vmArgs, String classpath, String main) {
        if (main.isBlank()) throw new IllegalArgumentException("Cannot reconstruct the current Java command");
        List<String> command = new ArrayList<>();
        command.add(Path.of(javaHome, "bin", SwarmSpawnMemory.windows() ? "java.exe" : "java").toString());
        command.addAll(vmArgs);
        command.add("-cp");
        command.add(classpath);
        command.addAll(split(main));
        return command;
    }

    public static ProcessBuilder build(Snapshot parent, String name, String server, Path gameDir, Path log,
                                       Path signet, SpawnSession session, SecretProtector protector) throws IOException {
        List<String> source = parent.command();
        int main = mainIndex(source);
        List<String> result = new ArrayList<>();
        String executable = source.get(0);
        if (executable.toLowerCase(java.util.Locale.ROOT).endsWith("javaw.exe")) {
            executable = executable.substring(0, executable.length() - 9) + "java.exe";
        }
        if (!Files.isRegularFile(Path.of(executable))) throw new IOException("Cannot find child Java executable");
        result.add(executable);
        Path dli = null;
        Path mods = parent.gameDirectory().resolve("mods");
        for (int i = 1; i < main; i++) {
            String arg = source.get(i);
            if (arg.startsWith("-Dfabric.dli.config=")) {
                dli = parent.workingDirectory().resolve(arg.substring(arg.indexOf('=') + 1)).normalize();
                result.add("-Dfabric.dli.config=" + gameDir.resolve("launch.cfg"));
            } else if (arg.startsWith("-Dfabric.modsFolder=")) {
                mods = parent.workingDirectory().resolve(arg.substring(arg.indexOf('=') + 1)).normalize();
            } else if (arg.startsWith("-Dfabric.dli.workDir=")) {
                result.add("-Dfabric.dli.workDir=" + gameDir);
            } else if (!replaceVm(arg)) {
                result.add(arg);
            }
        }
        boolean online = session != null && session.online();
        String username = session != null && session.username() != null && !session.username().isBlank()
                ? session.username() : name;
        if (online) {
            if (protector == null) {
                throw new IOException("Windows DPAPI is not available");
            }
            byte[] plain = AuthBlob.encode(username, session.uuid(), session.accessToken(), "", 0L);
            try {
                PrivateFiles.writeRestricted(gameDir.resolve("auth.bin"), protector.protect(plain));
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        }
        result.addAll(List.of("-Xmx768M", "-Xms256M", "-XX:+UseG1GC", "-XX:MaxMetaspaceSize=384M",
                "-XX:ReservedCodeCacheSize=128M", "-XX:CompressedClassSpaceSize=128M", "-XX:G1HeapRegionSize=8M",
                "-Dostinato.runMode=headless", "-Dostinato.commandInbox=true",
                "-Dostinato.swarm.spawnParentPid=" + parent.pid(), "-Dostinato.swarm.spawnSignet=" + signet,
                "-Dostinato.swarm.spawnOffline=" + (!online),
                "-Dfabric.modsFolder=" + mods));
        if (online) {
            // Ostinato: path only. The token is spliced into Main's argument array, not this command line.
            result.add("-Dostinato.swarm.authFile=" + gameDir.resolve("auth.bin").toAbsolutePath());
        }
        result.add(source.get(main));
        result.addAll(cleanArgs(source.subList(main + 1, source.size())));
        List<String> childArgs = new ArrayList<>(List.of("--gameDir", gameDir.toString(), "--username", username,
                "--quickPlayMultiplayer", server));
        if (dli != null) {
            List<String> config = cleanDli(Files.readAllLines(dli));
            int section = -1;
            for (int i = 0; i < config.size(); i++) {
                if (config.get(i).equals("clientArgs")) section = i;
            }
            if (section < 0) {
                config.add("clientArgs");
                section = config.size() - 1;
            }
            int insertion = section + 1;
            for (String arg : childArgs) config.add(insertion++, "\t" + arg);
            Files.write(gameDir.resolve("launch.cfg"), config);
        } else {
            result.addAll(childArgs);
        }
        ProcessBuilder builder = new ProcessBuilder(result).directory(parent.workingDirectory().toFile())
                .redirectInput(ProcessBuilder.Redirect.from(new java.io.File(SwarmSpawnMemory.windows() ? "NUL" : "/dev/null")))
                .redirectOutput(log.toFile()).redirectErrorStream(true);
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        return builder;
    }

    private static boolean replaceVm(String arg) {
        return arg.startsWith("-Xmx") || arg.startsWith("-Xms") || arg.matches("-XX:[+-]Use.*GC")
                || arg.startsWith("-XX:MaxMetaspaceSize=") || arg.startsWith("-XX:ReservedCodeCacheSize=")
                || arg.startsWith("-XX:CompressedClassSpaceSize=") || arg.startsWith("-XX:G1HeapRegionSize=")
                || arg.startsWith("-Dostinato.runMode=") || arg.startsWith("-Dostinato.swarm.spawn")
                || arg.startsWith("-Dostinato.swarm.authFile=")
                || arg.startsWith("-Dostinato.commandInbox=") || arg.startsWith("-Dostinato.window")
                || arg.startsWith("-Dtenorclef.autorun.") || arg.startsWith("-Dtenorclef.autoload.")
                || arg.startsWith("-Dfabric.modsFolder=") || arg.startsWith("-agentlib:")
                || arg.startsWith("-Xlog:");
    }

    private static int mainIndex(List<String> args) {
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (Set.of("-cp", "-classpath", "--class-path", "--module-path", "-p", "--add-opens", "--add-exports", "--add-modules").contains(arg)) {
                i++;
            } else if (arg.equals("-jar") || arg.equals("-m") || arg.equals("--module")) {
                throw new IllegalArgumentException("Jar/module launcher reconstruction is not supported; use a classpath client launch");
            } else if (arg.startsWith("@")) {
                throw new IllegalArgumentException("Java argument-file launch cannot be safely reconstructed");
            } else if (!arg.startsWith("-")) {
                return i;
            }
        }
        throw new IllegalArgumentException("Cannot find client main class in Java command");
    }

    private static List<String> cleanArgs(List<String> args) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            String key = arg.contains("=") ? arg.substring(0, arg.indexOf('=')) : arg;
            if (REPLACED.contains(key)) {
                if (!arg.contains("=") && i + 1 < args.size()) i++;
            } else out.add(arg);
        }
        return out;
    }

    static List<String> cleanDli(List<String> lines) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String value = line.trim();
            String key = value.contains("=") ? value.substring(0, value.indexOf('=')) : value;
            if (REPLACED.contains(key)) {
                if (!value.contains("=")) i++;
            } else if (!value.startsWith("ostinato.runMode=") && !value.startsWith("ostinato.window")
                    && !value.startsWith("ostinato.swarm.spawn") && !value.startsWith("ostinato.swarm.authFile=")
                    && !value.startsWith("tenorclef.autorun.")
                    && !value.startsWith("tenorclef.autoload.")) {
                out.add(line);
            }
        }
        return out;
    }

    /** Windows CRT quoting (backslashes are literal except immediately before a quote). */
    public static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false, started = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                int start = i;
                while (i < line.length() && line.charAt(i) == '\\') i++;
                int n = i - start;
                if (i < line.length() && line.charAt(i) == '"') {
                    token.append("\\".repeat(n / 2));
                    if (n % 2 == 1) token.append('"'); else quoted = !quoted;
                } else {
                    token.append("\\".repeat(n));
                    i--;
                }
                started = true;
            } else if (c == '"') {
                quoted = !quoted;
                started = true;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (started) out.add(token.toString());
                token.setLength(0);
                started = false;
            } else {
                token.append(c);
                started = true;
            }
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quote in parent Java command");
        if (started) out.add(token.toString());
        return out;
    }
}
