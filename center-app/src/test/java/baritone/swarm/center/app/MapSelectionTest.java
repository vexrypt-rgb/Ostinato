package baritone.swarm.center.app;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Ostinato: marker hits, click, shift-click and the selection box. */
public class MapSelectionTest {

    @Test public void hitTestPicksTheNearestAndKeepsTheFirstTie() {
        List<MapHit.Marker> markers = List.of(
                new MapHit.Marker("Copper", 0, 0),
                new MapHit.Marker("Azalea", 10, 0),
                new MapHit.Marker("Spelunker", 100, 100));
        assertEquals("Copper", MapHit.nearest(markers, 4, 0, 10));
        assertEquals("Azalea", MapHit.nearest(markers, 8, 0, 10));
        assertNull(MapHit.nearest(markers, 40, 0, 10));
        assertEquals("Copper", MapHit.nearest(List.of(
                new MapHit.Marker("Copper", 0, 0),
                new MapHit.Marker("Azalea", 0, 0)), 0, 0, 10));
        assertNull(MapHit.nearest(List.of(), 0, 0, 10));
        assertNull(MapHit.nearest(null, 0, 0, 10));
    }

    @Test public void clickReplacesAndShiftClickToggles() {
        MapSelection s = new MapSelection();
        s.click("Azalea");
        assertEquals(List.of("Azalea"), s.names());
        s.click("Copper");
        assertEquals(List.of("Copper"), s.names());
        s.shiftClick("Azalea");
        assertEquals(List.of("Copper", "Azalea"), s.names());
        s.shiftClick("Copper");
        assertEquals(List.of("Azalea"), s.names());
        s.shiftClick(null);
        s.shiftClick("  ");
        assertEquals(List.of("Azalea"), s.names());
        s.click(null);
        assertTrue(s.names().isEmpty());
        s.click("Oak");
        s.clear();
        assertFalse(s.contains("Oak"));
    }

    @Test public void boxSelectIncludesTheEdgesAndCanAdd() {
        List<MapHit.Marker> markers = new java.util.ArrayList<>();
        markers.add(new MapHit.Marker("Azalea", 0, 0));
        markers.add(new MapHit.Marker("Spelunker", 10, 10));
        markers.add(new MapHit.Marker("Copper", 50, 50));
        markers.add(new MapHit.Marker(null, 5, 5));
        markers.add(null);
        MapSelection s = new MapSelection();
        s.box(markers, 0, 0, 12, 12, false);
        assertEquals(List.of("Azalea", "Spelunker"), s.names());
        s.box(markers, 12, 0, 0, 12, false);
        assertEquals(List.of("Azalea", "Spelunker"), s.names());
        s.box(markers, 1, 1, 9, 9, false);
        assertTrue(s.names().isEmpty());
        s.click("Copper");
        s.box(markers, 0, 0, 10, 10, true);
        assertEquals(List.of("Copper", "Azalea", "Spelunker"), s.names());
        s.box(null, 0, 0, 1, 1, false);
        assertTrue(s.names().isEmpty());
        s.click("Oak");
        s.box(null, 0, 0, 1, 1, true);
        assertEquals(List.of("Oak"), s.names());
    }

    @Test public void replaceDropsBlanks() {
        MapSelection s = new MapSelection();
        s.click("Copper");
        s.replace(java.util.Arrays.asList("Azalea", " ", null, "Azalea", "Spelunker"));
        assertEquals(List.of("Azalea", "Spelunker"), s.names());
        s.replace(null);
        assertTrue(s.names().isEmpty());
    }
}
