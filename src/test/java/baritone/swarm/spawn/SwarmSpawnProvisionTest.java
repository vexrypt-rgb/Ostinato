package baritone.swarm.spawn;

import baritone.swarm.crypto.SigilEd25519;
import baritone.swarm.crypto.SigilSignets;
import baritone.swarm.roster.SwarmRoster;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import static org.junit.Assert.*;

// Ostinato: only temporary files and explicitly test-labelled keys; no client, host profiles or child JVM.
public class SwarmSpawnProvisionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void rosterRoundTripsHierarchyAndTransport() throws Exception {
        SwarmRoster roster = SwarmRoster.parse("group alpha circle=one members=Parent,Peer lead=Peer transport=team\n"
                + "group beta circle=two members=Parent lead=Parent parent=alpha transport=global\n");
        SwarmRoster updated = SwarmRoster.parse(roster.withMember("alpha", "Bot").toText());
        assertEquals("Peer", updated.group("alpha").lead());
        assertEquals("one", updated.group("alpha").circle());
        assertEquals("alpha", updated.group("beta").parent());
        assertEquals(roster.group("alpha").channel(), updated.group("alpha").channel());
        assertTrue(updated.group("alpha").has("Bot"));
        assertFalse(updated.group("beta").has("Bot"));
        assertEquals(updated.toText(), updated.withMember("alpha", "bot").toText());
    }

    @Test public void writesPublicPinAndPrivateSettingsWithoutChangingParent() throws Exception {
        Path game = temporary.newFolder("game").toPath();
        Path user = temporary.newFolder("user").toPath();
        Path home = temporary.newFolder("sigil").toPath();
        Path roster = game.resolve("baritone/swarm.txt");
        Files.createDirectories(roster.getParent());
        Files.writeString(roster, "group builders circle=test members=Parent lead=Parent transport=whisper\n");
        String parentSettings = "runMode normal\nswarmEnabled false\nswarmRosterFile old.txt\nswarmSigilHome old\nallowBreak false\n";
        Files.writeString(game.resolve("baritone/settings.txt"), parentSettings);
        Files.writeString(game.resolve("options.txt"), "test-options");
        Path childGame = game.resolve("ostinato-bots/Bot");
        Path seedFile = user.resolve(".ostinato/spawn")
                .resolve(UUID.nameUUIDFromBytes(game.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8)).toString())
                .resolve("Bot/signet-Bot.json");
        SigilEd25519 key = SigilEd25519.testSignet("spawn-DO-NOT-USE");
        SigilSignets.write(seedFile, "Bot", key, true);
        Path actual = SwarmSpawnProvision.child(game, childGame, "Bot",
                new SwarmSpawnProvision.Link(roster, home, "builders"), user);
        assertEquals(seedFile, actual);
        assertFalse(actual.startsWith(game));
        SigilSignets.Record pin = SigilSignets.parse(Files.readString(home.resolve("signet-Bot.json")));
        assertFalse(pin.key.canSign());
        assertFalse(Files.readString(home.resolve("signet-Bot.json")).contains("\"seed\""));
        assertArrayEquals(key.publicKey(), pin.key.publicKey());
        assertTrue(SigilSignets.parse(Files.readString(seedFile)).key.canSign());
        String childSettings = Files.readString(childGame.resolve("baritone/settings.txt"));
        assertTrue(childSettings.contains("runMode headless"));
        assertTrue(childSettings.contains("swarmEnabled true"));
        assertTrue(childSettings.contains("swarmRosterFile " + roster));
        assertTrue(childSettings.contains("swarmSigilHome " + home));
        assertTrue(childSettings.contains("swarmLocalSpoolDir " + home.toAbsolutePath().normalize().resolve("spool")));
        assertTrue(childSettings.contains("allowBreak false"));
        assertFalse(childSettings.contains("runMode normal"));
        assertEquals(parentSettings, Files.readString(game.resolve("baritone/settings.txt")));
        assertEquals("test-options", Files.readString(childGame.resolve("options.txt")));
        assertTrue(SwarmRoster.parse(Files.readString(roster)).group("builders").has("Bot"));
        SwarmSpawnProvision.child(game, childGame, "Bot", new SwarmSpawnProvision.Link(roster, home, "builders"), user);
        assertArrayEquals(key.publicKey(), SigilSignets.parse(Files.readString(seedFile)).key.publicKey());
    }

    @Test public void persistParentLinkRewritesOnlySwarmKeys() throws Exception {
        Path game = temporary.newFolder("game-persist").toPath();
        Path settings = game.resolve("baritone/settings.txt");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "runMode normal\nallowBreak false\nswarmEnabled false\nswarmRosterFile old.txt\n");
        Path roster = game.resolve("baritone/swarm.txt");
        Path home = temporary.newFolder("sigil-persist").toPath();
        SwarmSpawnProvision.persistParentLink(game, new SwarmSpawnProvision.Link(roster, home, "spawn"));
        String text = Files.readString(settings);
        assertTrue(text.contains("runMode normal"));
        assertTrue(text.contains("allowBreak false"));
        assertTrue(text.contains("swarmEnabled true"));
        assertTrue(text.contains("swarmRosterFile " + roster));
        assertTrue(text.contains("swarmSigilHome " + home));
        assertTrue(text.contains("swarmLocalSpoolDir " + home.toAbsolutePath().normalize().resolve("spool")));
        assertFalse(text.contains("swarmEnabled false"));
        assertFalse(text.contains("old.txt"));
    }
}
