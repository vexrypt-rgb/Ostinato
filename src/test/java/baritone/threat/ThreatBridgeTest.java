package baritone.threat;

import baritone.api.threat.*;
import org.junit.Test;
import org.junit.Assume;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import static org.junit.Assert.*;

/** Compile the actual TenorClef adapter against Java 8, then consume actual Ostinato values. */
public class ThreatBridgeTest {
    @Test public void tenorClefConsumesActualSnapshotWithoutMinecraftOrPolicy() throws Exception {
        Path source = Path.of(System.getProperty("threat.bridge.source", "../TenorClef-main/src/main/java/adris/altoclef/threat/OstinatoThreatBridge.java"));
        Assume.assumeTrue("TenorClef sibling checkout required", Files.isRegularFile(source));
        Path output = Files.createTempDirectory("threat-bridge-test");
        try {
            assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "--release", "8", "-d", output.toString(), source.toString()));
            try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()})) {
                Class<?> bridge = loader.loadClass("adris.altoclef.threat.OstinatoThreatBridge");
                var a = ThreatRulesTest.run(ThreatRulesTest.obs(ThreatRules.Kind.MELEE, ThreatAssessment.Sight.CLEAR, true, true, false, 1., 4., 0));
                var snapshot = new ThreatSnapshot(0, 42, a.subject(), "overworld", List.of(a), Set.of(), false, "OBSERVED");
                Object view = bridge.getMethod("fromSnapshot", Object.class).invoke(null, snapshot);
                assertTrue(view.getClass().getField("available").getBoolean(view));
                assertEquals(5, view.getClass().getField("highestLevel").getInt(view));
                assertEquals(42, view.getClass().getField("tick").getLong(view));
                assertTrue(view.getClass().getField("sources").get(view).toString().contains("CONDITIONAL_IMMINENT_LETHAL_DAMAGE"));
                Object absent = bridge.getMethod("read", Object.class).invoke(null, new Object());
                assertFalse(absent.getClass().getField("available").getBoolean(absent));
                assertEquals("UNSUPPORTED_OSTINATO_VERSION", absent.getClass().getField("status").get(absent));
            }
        } finally {
            try (var paths = Files.walk(output)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        }
    }
}
