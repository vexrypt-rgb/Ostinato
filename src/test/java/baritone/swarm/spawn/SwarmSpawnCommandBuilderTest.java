package baritone.swarm.spawn;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

// Ostinato: constructing ProcessBuilder is safe; these tests never call start().
public class SwarmSpawnCommandBuilderTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private SwarmSpawnCommandBuilder.Snapshot snapshot(List<String> extras) throws Exception {
        Path root = temporary.getRoot().toPath();
        Path java = root.resolve("java.exe");
        if (!Files.exists(java)) Files.createFile(java);
        List<String> args = new ArrayList<>(List.of(java.toString(), "-Xmx4G", "-Xms1G", "-XX:+UseParallelGC",
                "-Dostinato.runMode=normal", "-Dostinato.windowPos=1,2"));
        args.addAll(extras);
        args.addAll(List.of("-cp", "mods/a.jar;libraries/b.jar", "net.fabricmc.loader.impl.launch.knot.KnotClient",
                "--username", "Parent", "--uuid", "parent-uuid", "--accessToken", "PARENT-SECRET",
                "--gameDir", root.toString(), "--quickPlaySingleplayer", "OldWorld", "--assetsDir", "assets"));
        return new SwarmSpawnCommandBuilder.Snapshot(args, root, root, 12345);
    }

    @Test public void offlineCommandReplacesIdentityMemoryWindowAndRedirects() throws Exception {
        var parent = snapshot(List.of());
        Path game = parent.gameDirectory().resolve("ostinato-bots/Bot");
        Path log = game.resolve("logs/spawn-test.log");
        ProcessBuilder builder = SwarmSpawnCommandBuilder.build(parent, "Bot", "localhost:25565", game, log,
                temporary.getRoot().toPath().resolve("private.json"), null, null);
        List<String> args = builder.command();
        assertEquals(parent.command().get(0), args.get(0));
        for (String arg : List.of("-Xmx768M", "-Xms256M", "-XX:+UseG1GC", "-Dostinato.runMode=headless",
                "-Dostinato.swarm.spawnParentPid=12345", "-Dostinato.swarm.spawnOffline=true", "--gameDir", game.toString(), "--username", "Bot",
                "--quickPlayMultiplayer", "localhost:25565", "mods/a.jar;libraries/b.jar")) assertTrue(arg, args.contains(arg));
        for (String arg : List.of("-Xmx4G", "-Xms1G", "-XX:+UseParallelGC", "--accessToken", "--uuid", "Parent",
                "PARENT-SECRET", "--quickPlaySingleplayer", "-Dostinato.runMode=normal", "-Dostinato.windowPos=1,2")) assertFalse(arg, args.contains(arg));
        assertEquals(log.toFile(), builder.redirectOutput().file());
        assertEquals(ProcessBuilder.Redirect.Type.READ, builder.redirectInput().type());
        assertTrue(builder.redirectErrorStream());
        assertFalse(builder.environment().containsKey("JAVA_TOOL_OPTIONS"));
        assertEquals(parent.workingDirectory().toFile(), builder.directory());
    }

    @Test public void onlineUsesOnlyProvidedProfile() throws Exception {
        var parent = snapshot(List.of());
        Path game = parent.gameDirectory().resolve("ostinato-bots/Bot");
        String uuid = "123456781234123412341234567890ab";
        var session = new SpawnSession("AccountName", uuid, "CHILD-TOKEN");
        var protector = new baritone.swarm.accounts.XorProtector();
        List<String> args = SwarmSpawnCommandBuilder.build(parent, "Bot", "server.example", game,
                game.resolve("out.log"), game.resolve("unused-private.json"), session, protector).command();
        assertTrue(args.contains("--username"));
        assertEquals("AccountName", args.get(args.indexOf("--username") + 1));
        assertTrue(args.contains("-Dostinato.swarm.spawnOffline=false"));
        assertTrue(args.stream().anyMatch(arg -> arg.startsWith("-Dostinato.swarm.authFile=")));
        assertFalse(args.contains("--accessToken"));
        assertFalse(args.contains("--uuid"));
        assertFalse(args.contains("CHILD-TOKEN"));
        assertFalse(args.contains(uuid));
        assertFalse(args.contains("PARENT-SECRET"));
        assertFalse(session.toString().contains("CHILD"));
        String stored = Files.readString(game.resolve("auth.bin"), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(stored.contains("CHILD-TOKEN"));
    }

    @Test public void fabricConfigIsPrivateAndSanitized() throws Exception {
        Path config = temporary.getRoot().toPath().resolve("parent.cfg");
        String original = "commonProperties\n\tostinato.runMode=normal\nclientArgs\n\t--username\n\tParent\n"
                + "\t--accessToken\n\tPARENT-SECRET\n\t--quickPlayMultiplayer\n\told.example\n";
        Files.writeString(config, original);
        var parent = snapshot(List.of("-Dfabric.dli.config=" + config, "-Dfabric.dli.workDir=old"));
        Path game = parent.gameDirectory().resolve("ostinato-bots/Bot");
        Files.createDirectories(game);
        var builder = SwarmSpawnCommandBuilder.build(parent, "Bot", "localhost", game, game.resolve("out.log"),
                temporary.getRoot().toPath().resolve("private.json"), null, null);
        assertTrue(builder.command().contains("-Dfabric.dli.config=" + game.resolve("launch.cfg")));
        assertTrue(builder.command().contains("-Dfabric.dli.workDir=" + game));
        String child = Files.readString(game.resolve("launch.cfg"));
        assertTrue(child.contains("\t--username\n\tBot".replace("\n", System.lineSeparator())));
        assertTrue(child.contains(game.toString()));
        assertFalse(child.contains("PARENT-SECRET"));
        assertFalse(child.contains("old.example"));
        assertFalse(child.contains("runMode=normal"));
        assertEquals(original, Files.readString(config));
    }

    @Test public void fabricOnlineIdentityStaysOutOfTheChildConfig() throws Exception {
        Path config = temporary.getRoot().toPath().resolve("parent-online.cfg");
        String original = "clientArgs\n\t--username\n\tParent\n\t--accessToken\n\tPARENT-SECRET\n";
        Files.writeString(config, original);
        var parent = snapshot(List.of("-Dfabric.dli.config=" + config));
        Path game = parent.gameDirectory().resolve("ostinato-bots/Bot");
        Files.createDirectories(game);
        var session = new SpawnSession("AccountName", "123456781234123412341234567890ab", "CHILD-TOKEN");
        var builder = SwarmSpawnCommandBuilder.build(parent, "Bot", "server.example", game, game.resolve("out.log"),
                temporary.getRoot().toPath().resolve("private.json"), session, new baritone.swarm.accounts.XorProtector());
        String child = Files.readString(game.resolve("launch.cfg"));
        assertFalse(child.contains("CHILD-TOKEN"));
        assertFalse(child.contains("123456781234123412341234567890ab"));
        assertTrue(child.contains("AccountName"));
        assertFalse(child.contains("PARENT-SECRET"));
        assertEquals(original, Files.readString(config));
        assertFalse(builder.command().contains("CHILD-TOKEN"));
        assertFalse(builder.command().contains("--accessToken"));
        String stored = Files.readString(game.resolve("auth.bin"), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(stored.contains("CHILD-TOKEN"));
    }

    @Test public void windowsQuotesAndMxBeanFallback() {
        assertEquals(List.of("C:\\Program Files\\Java\\java.exe", "-cp", "a b;c", "Main", "--username", "Parent"),
                SwarmSpawnCommandBuilder.split("\"C:\\Program Files\\Java\\java.exe\" -cp \"a b;c\" Main --username Parent"));
        List<String> fallback = SwarmSpawnCommandBuilder.fallback("fake-java", List.of("-Dexample=true"), "a;b", "Main --username Parent");
        assertTrue(fallback.contains("-Dexample=true"));
        assertTrue(fallback.contains("a;b"));
        assertTrue(fallback.contains("Main"));
    }
}
