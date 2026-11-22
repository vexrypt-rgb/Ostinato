package baritone.swarm.center.feed;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Ostinato: the feed's own JSON codec. */
public class JsonTest {

    @Test public void roundTripsEveryType() {
        Map<String, Object> m = Json.map("s", "a\"b\\c\nd\u0001", "n", 42L, "d", 1.25, "t", true, "z", null,
                "l", List.of(1L, "x"), "o", Json.map("k", "v"), "a", new int[]{1, -2, 3});
        Map<String, Object> back = Json.object(Json.write(m));
        assertEquals("a\"b\\c\nd\u0001", back.get("s"));
        assertEquals(42L, back.get("n"));
        assertEquals(1.25, back.get("d"));
        assertEquals(true, back.get("t"));
        assertTrue(back.containsKey("z"));
        assertNull(back.get("z"));
        assertEquals(List.of(1L, "x"), back.get("l"));
        assertEquals("v", Json.obj(back, "o").get("k"));
        assertEquals(-2, Json.ints(back, "a")[1]);
    }

    @Test public void wholeDoublesWriteAsIntegersAndNanAsNull() {
        assertEquals("{\"a\":3,\"b\":null}", Json.write(Json.map("a", 3.0, "b", Double.NaN)));
    }

    @Test public void typedAccessFallsBackOnWrongTypes() {
        Map<String, Object> m = Json.object("{\"a\":\"x\",\"b\":5,\"c\":[1,\"q\",{}]}");
        assertEquals(-1, Json.num(m, "a", -1));
        assertEquals("d", Json.str(m, "b", "d"));
        assertEquals(1, Json.objs(m, "c").size());
        assertNull(Json.ints(m, "c"));
        assertEquals(7, Json.i32(m, "missing", 7));
    }

    @Test public void malformedTextIsRefused() {
        for (String bad : new String[]{"", "{", "{\"a\":}", "[1,]", "{\"a\":1} x", "nul", "\"\\q\""}) {
            assertThrows(bad, IllegalArgumentException.class, () -> Json.parse(bad));
        }
    }

    @Test public void deepNestingIsRefused() {
        String deep = "[".repeat(Json.MAX_DEPTH + 2) + "]".repeat(Json.MAX_DEPTH + 2);
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep));
    }

    @Test public void unicodeEscapesParse() {
        assertEquals("é✖", Json.parse("\"\\u00e9\\u2716\""));
    }
}
