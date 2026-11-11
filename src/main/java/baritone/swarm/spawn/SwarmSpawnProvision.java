package baritone.swarm.spawn;

import baritone.swarm.SwarmKeys;
import baritone.swarm.crypto.*;
import baritone.swarm.roster.SwarmRoster;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Ostinato: only settings enter game directories; circle passphrases and private seeds stay outside them.
public final class SwarmSpawnProvision {
    public record Link(Path roster, Path home, String group) {
        // Ostinato: parent and children share this spool so same-PC commands skip whispers.
        public Path spool() {
            return home.toAbsolutePath().normalize().resolve("spool");
        }
    }

    public static Link link(Path game, Path roster, Path configuredHome, Path userHome, String parent) throws Exception {
        SwarmSpawnArgs.name(parent);
        Path home = configuredHome == null ? userHome.resolve(".ostinato/sigil") : configuredHome;
        home = home.toAbsolutePath().normalize();
        if (home.startsWith(game.toAbsolutePath().normalize())) {
            throw new IOException("SIGIL home must be outside the game directory; move the keyring before spawning");
        }
        Files.createDirectories(home);
        SwarmRoster parsed = Files.isRegularFile(roster) ? SwarmRoster.parse(Files.readString(roster)) : SwarmRoster.empty();
        String group;
        if (!parsed.groupsOf(parent).isEmpty()) {
            SwarmKeys.circlesFor(parsed, parent, home); // Validate existing circles before writing anything.
            group = parsed.groupsOf(parent).get(0).id();
        } else {
            if (!parsed.groups().isEmpty()) throw new IOException("Parent is not a member of the configured swarm roster");
            group = "spawn";
            String circleName = "spawn-" + java.util.UUID.randomUUID();
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            String passphrase = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            SigilCircle circle = SigilCircle.derive(circleName, passphrase);
            JsonObject record = new JsonObject();
            record.addProperty("kind", "circle");
            record.addProperty("kdf", SigilCircle.KDF_ID);
            record.addProperty("name", circle.name());
            record.addProperty("passphrase", passphrase);
            record.addProperty("fingerprint", circle.fingerprint());
            record.addProperty("slug", circle.slug());
            Files.writeString(home.resolve("circle-" + circleName + ".json"), record.toString());
            parsed = SwarmRoster.parse("group spawn circle=" + circleName + " members=" + parent + " lead=" + parent + " transport=whisper\n");
            Files.createDirectories(roster.toAbsolutePath().getParent());
            writeRoster(roster, parsed);
        }
        Map<String, SigilEd25519> pins = SigilSignets.loadMap(home);
        SigilEd25519 parentKey = pins.get(parent);
        if (parentKey == null) {
            SigilSignets.write(home.resolve("signet-" + parent + ".json"), parent, SigilEd25519.generate(), true);
        } else if (!parentKey.canSign()) {
            throw new IOException("Parent has only a public SIGIL pin; configure its local signing seed first");
        }
        return new Link(roster.toAbsolutePath(), home, group);
    }

    public static Path child(Path parentGame, Path game, String name, Link link, Path userHome) throws Exception {
        SwarmSpawnArgs.name(name);
        Path expected = parentGame.toAbsolutePath().normalize().resolve("ostinato-bots").resolve(name);
        if (!game.toAbsolutePath().normalize().equals(expected)) throw new IOException("Invalid private bot game directory");
        rejectLinks(expected);
        Files.createDirectories(game);
        // Existing instance directories may be reused, but never follow links back into the parent settings.
        try (var files = Files.walk(game)) {
            for (Path file : files.toList()) {
                if (Files.isSymbolicLink(file) || Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).isOther()) {
                    throw new IOException("Refusing linked bot instance entry: " + file);
                }
            }
        }
        for (String entry : List.of("options.txt", "altoclef_settings.json", "usercache.json", "config", "altoclef", "baritone")) {
            copyMissing(parentGame.resolve(entry), game.resolve(entry));
        }
        Path privateHome = userHome.resolve(".ostinato/spawn")
                .resolve(java.util.UUID.nameUUIDFromBytes(parentGame.toAbsolutePath().toString()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()).resolve(name);
        if (privateHome.toAbsolutePath().normalize().startsWith(parentGame.toAbsolutePath().normalize())) {
            throw new IOException("Private SIGIL storage must be outside the game directory");
        }
        Path privateFile = privateHome.resolve("signet-" + name + ".json").toAbsolutePath();
        Map<String, SigilEd25519> pins = SigilSignets.loadMap(link.home());
        SigilEd25519 key;
        if (Files.isRegularFile(privateFile)) {
            SigilSignets.Record local = SigilSignets.parse(Files.readString(privateFile));
            if (!local.name.equals(name) || !local.key.canSign()) throw new IOException("Invalid private bot signet");
            key = local.key;
        } else {
            if (pins.containsKey(name)) throw new IOException("A SIGIL pin already exists for " + name + "; refusing to replace its identity");
            key = SigilEd25519.generate();
            SigilSignets.write(privateFile, name, key, true);
        }
        if (pins.containsKey(name) && !java.util.Arrays.equals(pins.get(name).publicKey(), key.publicKey())) {
            throw new IOException("Private bot signet conflicts with its existing public pin");
        }
        SigilSignets.write(link.home().resolve("signet-" + name + ".json"), name, key, false);
        SwarmRoster roster = SwarmRoster.parse(Files.readString(link.roster()));
        writeRoster(link.roster(), roster.withMember(link.group(), name));
        Path settings = game.resolve("baritone/settings.txt");
        Files.createDirectories(settings.getParent());
        List<String> lines = Files.isRegularFile(settings) ? new ArrayList<>(Files.readAllLines(settings)) : new ArrayList<>();
        lines.removeIf(line -> line.trim().matches("(?i)(runMode|swarmEnabled|swarmRosterFile|swarmSigilHome|swarmLocalSpoolDir)\\s+.*"));
        lines.addAll(List.of("runMode headless", "swarmEnabled true", "swarmRosterFile " + link.roster(), "swarmSigilHome " + link.home(),
                "swarmLocalSpoolDir " + link.spool()));
        Files.write(settings, lines);
        return privateFile;
    }

    // Ostinato: keep these parent keys so a restart still finds the provisioned keyring and shared spool.
    public static void persistParentLink(Path game, Link link) throws IOException {
        Path settings = game.resolve("baritone/settings.txt");
        Files.createDirectories(settings.getParent());
        List<String> lines = Files.isRegularFile(settings) ? new ArrayList<>(Files.readAllLines(settings)) : new ArrayList<>();
        lines.removeIf(line -> line.trim().matches("(?i)(swarmEnabled|swarmRosterFile|swarmSigilHome|swarmLocalSpoolDir)\\s+.*"));
        lines.addAll(List.of("swarmEnabled true", "swarmRosterFile " + link.roster(), "swarmSigilHome " + link.home(),
                "swarmLocalSpoolDir " + link.spool()));
        Files.write(settings, lines);
    }

    private static void copyMissing(Path source, Path destination) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.isSymbolicLink(source) || Files.readAttributes(source, java.nio.file.attribute.BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).isOther()) throw new IOException("Refusing to copy linked settings: " + source);
        if (Files.isDirectory(source)) {
            Files.createDirectories(destination);
            try (var files = Files.list(source)) {
                for (Path file : files.toList()) copyMissing(file, destination.resolve(file.getFileName()));
            }
        } else {
            Files.copy(source, destination);
        }
    }

    private static void rejectLinks(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                    && (Files.isSymbolicLink(current) || Files.readAttributes(current,
                    java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isOther())) {
                throw new IOException("Refusing linked bot instance directory: " + current);
            }
        }
    }

    private static void writeRoster(Path file, SwarmRoster roster) throws IOException {
        Path pending = Files.createTempFile(file.toAbsolutePath().getParent(), ".swarm-", ".tmp");
        try {
            Files.writeString(pending, roster.toText());
            try {
                Files.move(pending, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(pending, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }
}
