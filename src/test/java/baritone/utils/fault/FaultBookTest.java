package baritone.utils.fault;

import org.junit.Assert;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class FaultBookTest {

    @Test
    public void loopDetectorFlagsAnyAlternatingPair() {
        LoopDetector d = new LoopDetector(4, 60_000);
        Assert.assertNull(d.feed("A", 0));
        Assert.assertNull(d.feed("A", 1)); // repeats are not changes
        Assert.assertNull(d.feed("B", 2));
        Assert.assertNull(d.feed("A", 3));
        Assert.assertNull(d.feed("B", 4));
        Assert.assertEquals("A<->B x4", d.feed("A", 5));
        Assert.assertNull(d.feed("B", 6)); // reported once, then re-armed
    }

    @Test
    public void loopDetectorIgnoresSlowOrThreeWayChanges() {
        LoopDetector d = new LoopDetector(4, 1_000);
        for (int i = 0; i < 10; i++) Assert.assertNull(d.feed(i % 2 == 0 ? "A" : "B", i * 5_000L));
        LoopDetector e = new LoopDetector(4, 60_000);
        String[] seq = {"A", "B", "C", "A", "B", "C", "A"};
        for (int i = 0; i < seq.length; i++) Assert.assertNull(e.feed(seq[i], i));
    }

    @Test
    public void episodesTrackSecondsLostAndSummaryIsSortedByCost() throws Exception {
        Path dir = Files.createTempDirectory("faultbook");
        FaultBook.configure(dir, () -> Map.of("phase", "IRON"), c -> "hint-" + c);
        FaultBook.reset(0);
        FaultBook.record("S200", "no \"progress\"", 1_000);
        FaultBook.record("E10", "water", 2_000);
        FaultBook.progress(11_000);   // S200 cost 10s, E10 cost 9s
        FaultBook.record("S200", "again", 20_000);
        FaultBook.progress(25_000);   // S200 +5s

        List<String> lines = Files.readAllLines(dir.resolve(FaultBook.EVENTS));
        Assert.assertEquals(6, lines.size());
        Assert.assertTrue(lines.get(0).contains("\"code\":\"S200\""));
        Assert.assertTrue(lines.get(0).contains("\"phase\":\"IRON\""));
        Assert.assertTrue(lines.get(0).contains("no \\\"progress\\\""));
        Assert.assertTrue(lines.get(0).contains("\"sev\":\"RECOVERY\""));

        String sum = FaultBook.summaryJson(30_000, "test");
        Assert.assertTrue(sum.contains("\"lost_s\":\"24.0\""));
        Assert.assertTrue(sum.indexOf("S200") < sum.indexOf("E10"));
        Assert.assertTrue(sum.contains("\"hint\":\"hint-E10\""));
    }

    @Test
    public void severityFromPrefix() {
        Assert.assertEquals(FaultBook.Severity.ERROR, FaultBook.severityOf("E50"));
        Assert.assertEquals(FaultBook.Severity.RECOVERY, FaultBook.severityOf("S200"));
        Assert.assertEquals(FaultBook.Severity.WARN, FaultBook.severityOf("C210"));
    }
}
