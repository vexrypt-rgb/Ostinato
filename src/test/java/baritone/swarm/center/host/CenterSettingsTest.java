package baritone.swarm.center.host;

import baritone.Baritone;
import baritone.api.Settings;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Ostinato: the Phase 3 settings are off by default and never restart the swarm link. */
public class CenterSettingsTest {

    @BeforeClass public static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @After public void reset() {
        Settings s = Baritone.settings();
        s.swarmCenterFeed.reset();
        s.swarmCenterHost.reset();
        s.swarmCenterDir.reset();
        s.swarmLocalSpoolDir.reset();
        s.swarmLeases.reset();
    }

    @Test public void offByDefault() {
        Settings s = Baritone.settings();
        assertFalse(s.swarmCenterFeed.value);
        assertFalse(s.swarmCenterHost.value);
        assertEquals("", s.swarmCenterDir.value);
    }

    @Test public void changingThemDoesNotChangeTheLinkKey() throws Exception {
        Settings s = Baritone.settings();
        java.lang.reflect.Method key = baritone.behavior.SwarmBehavior.class.getDeclaredMethod("settingsKey", Settings.class);
        key.setAccessible(true);
        String before = (String) key.invoke(null, s);
        s.swarmCenterFeed.value = true;
        s.swarmCenterHost.value = true;
        s.swarmCenterDir.value = "C:/x";
        assertEquals(before, key.invoke(null, s));
        s.swarmLeases.value = !s.swarmLeases.value;
        assertFalse("other swarm settings still restart it", before.equals(key.invoke(null, s)));
    }

    @Test public void directoryDefaults() {
        Settings s = Baritone.settings();
        Path game = Path.of("C:/inst/Copper");
        assertEquals(game.resolve("ostinato").resolve("center"), CenterFeedService.dir(s, game));
        s.swarmLocalSpoolDir.value = "C:/swarm/spool";
        assertEquals(Path.of("C:/swarm/center"), CenterFeedService.dir(s, game));
        s.swarmCenterDir.value = "D:/cc";
        assertEquals(Path.of("D:/cc"), CenterFeedService.dir(s, game));
    }
}
