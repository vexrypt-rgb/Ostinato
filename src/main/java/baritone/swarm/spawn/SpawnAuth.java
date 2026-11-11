package baritone.swarm.spawn;

import baritone.swarm.accounts.AuthBlob;
import baritone.swarm.accounts.SecretProtector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Ostinato: the child reads a protected auth file inside {@code Main.main} and splices the token into
 * the in-memory argument array. The operating-system command line never carries it.
 */
public final class SpawnAuth {

    private SpawnAuth() {}

    public static String[] splice(String[] args) {
        String path = System.getProperty("ostinato.swarm.authFile");
        if (path == null || path.isBlank()) {
            return args;
        }
        SecretProtector protector;
        try {
            protector = baritone.swarm.accounts.DpapiProtector.platform();
        } catch (RuntimeException e) {
            return args;
        }
        return splice(args, Path.of(path), protector);
    }

    public static String[] splice(String[] args, Path file, SecretProtector protector) {
        if (args == null || file == null || protector == null) {
            return args;
        }
        byte[] plain = null;
        try {
            if (!Files.isRegularFile(file)) {
                return args;
            }
            plain = protector.unprotect(Files.readAllBytes(file));
            AuthBlob.Decoded fields = AuthBlob.decode(plain);
            if (fields.access() == null || fields.access().isBlank()) {
                return args;
            }
            List<String> out = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--accessToken".equals(arg) || "--uuid".equals(arg) || "--userType".equals(arg)) {
                    if (i + 1 < args.length) {
                        i++;
                    }
                    continue;
                }
                if (arg.startsWith("--accessToken=") || arg.startsWith("--uuid=") || arg.startsWith("--userType=")) {
                    continue;
                }
                out.add(arg);
            }
            if (fields.uuid() != null && !fields.uuid().isBlank()) {
                out.add("--uuid");
                out.add(fields.uuid());
            }
            out.add("--accessToken");
            out.add(fields.access());
            out.add("--userType");
            out.add("msa");
            try {
                Files.deleteIfExists(file);
            } catch (Exception ignored) {
                // Best-effort: the token is already in memory. Do not fail the launch.
            }
            return out.toArray(String[]::new);
        } catch (Throwable ignored) {
            return args;
        } finally {
            if (plain != null) {
                Arrays.fill(plain, (byte) 0);
            }
        }
    }
}
