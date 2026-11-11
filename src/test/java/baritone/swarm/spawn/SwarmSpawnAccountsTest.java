package baritone.swarm.spawn;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.Assert.*;

// Ostinato: synthetic launcher fixtures; no user accounts or network access.
public class SwarmSpawnAccountsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void offlinePolicy() {
        UUID online = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
        assertTrue(SwarmSpawnAccounts.offline("127.0.0.1:25565", "Parent", online));
        assertTrue(SwarmSpawnAccounts.offline("[::1]:25565", "Parent", online));
        assertFalse(SwarmSpawnAccounts.offline("remote.example", "Parent", online));
        UUID offline = UUID.nameUUIDFromBytes("OfflinePlayer:Parent".getBytes(StandardCharsets.UTF_8));
        assertTrue(SwarmSpawnAccounts.offline("remote.example", "Parent", offline));
    }

    @Test public void readsExistingProfileWithoutWriting() throws Exception {
        Path file = temporary.newFile("launcher_accounts.json").toPath();
        String json = "{\"accounts\":{\"account\":{\"accessToken\":\"TEST-TOKEN\",\"minecraftProfile\":{"
                + "\"name\":\"Bot\",\"id\":\"123456781234123412341234567890ab\"}}}}";
        Files.writeString(file, json);
        assertEquals("TEST-TOKEN", SwarmSpawnAccounts.find("Bot", List.of(file)).accessToken());
        assertEquals(json, Files.readString(file));
        try { SwarmSpawnAccounts.find("Other", List.of(file)); fail(); }
        catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("configured account for Other"));
            assertTrue(expected.getMessage().contains("will not store passwords"));
        }
        Files.writeString(file, json.replace("TEST-TOKEN", ""));
        try { SwarmSpawnAccounts.find("Bot", List.of(file)); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("access token")); }
    }
}
