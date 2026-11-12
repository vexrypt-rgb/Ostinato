package baritone.swarm.transport;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Ostinato: sliding event count behind the link monitor's chat budget and traffic figures. */
public class WindowCounterTest {

    @Test public void countsOnlyTheWindow() {
        WindowCounter c = new WindowCounter(60_000, 100);
        c.add(0);
        c.add(10_000);
        c.add(59_999);
        assertEquals(3, c.count(59_999));
        assertEquals(2, c.count(60_000)); // the event at 0 leaves exactly one window later
        assertEquals(1, c.count(70_000));
        assertEquals(0, c.count(200_000));
        assertEquals(3, c.total());
        assertEquals(60_000, c.windowMs());
    }

    @Test public void boundedButTotalKeepsCounting() {
        WindowCounter c = new WindowCounter(60_000, 4);
        for (int i = 0; i < 10; i++) {
            c.add(i);
        }
        assertEquals(4, c.count(10));
        assertEquals(10, c.total());
    }

    @Test public void nonsenseSizesAreClamped() {
        WindowCounter c = new WindowCounter(0, 0);
        c.add(5);
        assertEquals(1, c.count(5));
        assertEquals(0, c.count(6));
    }
}
