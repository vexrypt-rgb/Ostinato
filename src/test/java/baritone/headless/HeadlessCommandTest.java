/*
 * Ostinato: Phase 3 command parsing. No Minecraft bootstrap: stop must keep ending the run,
 * and cancel / stop path must not be parsed as process shutdown.
 */
package baritone.headless;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class HeadlessCommandTest {

    @Test
    public void stopEndsTheRunAndCancelDoesNot() {
        assertEquals(HeadlessCommands.Kind.STOP, HeadlessCommands.parse("stop").kind());
        assertEquals(HeadlessCommands.Kind.STOP, HeadlessCommands.parse("QUIT").kind());
        assertEquals(HeadlessCommands.Kind.STOP, HeadlessCommands.parse("exit").kind());
        assertEquals(HeadlessCommands.Kind.CANCEL, HeadlessCommands.parse("cancel").kind());
        assertEquals(HeadlessCommands.Kind.CANCEL, HeadlessCommands.parse("stop path").kind());
        assertEquals(HeadlessCommands.Kind.CANCEL, HeadlessCommands.parse("STOP   PATH").kind());
        assertTrue(HeadlessCommands.parse("stop now").error().contains("usage: stop"));
    }

    @Test
    public void gotoMineProbeAndCourseKeepTheirArguments() {
        HeadlessCommands.Parsed gotoCommand = HeadlessCommands.parse("goto -12 64 30");
        assertEquals(HeadlessCommands.Kind.GOTO, gotoCommand.kind());
        assertEquals(-12, gotoCommand.x());
        assertEquals(64, gotoCommand.y());
        assertEquals(30, gotoCommand.z());
        assertNull(gotoCommand.error());
        assertTrue(HeadlessCommands.parse("goto 1 2").error().contains("usage: goto"));
        assertTrue(HeadlessCommands.parse("goto 1.5 2 3").error().contains("integers"));

        assertEquals("minecraft:stone", HeadlessCommands.parse("mine MINECRAFT:STONE").text());
        assertEquals("dirt", HeadlessCommands.parse("mine dirt").text());
        assertTrue(HeadlessCommands.parse("mine").error().contains("usage: mine"));

        HeadlessCommands.Parsed place = HeadlessCommands.parse("place -3 64 8");
        assertEquals(HeadlessCommands.Kind.PLACE, place.kind());
        assertEquals(-3, place.x());
        assertEquals(64, place.y());
        assertEquals(8, place.z());
        assertNull(place.error());
        assertTrue(HeadlessCommands.parse("place 1 2").error().contains("usage: place"));
        assertTrue(HeadlessCommands.parse("place 1.5 2 3").error().contains("integers"));

        assertEquals(5, HeadlessCommands.parse("probe 5 6 7").x());
        assertEquals("C:\\courses\\my course.txt", HeadlessCommands.parse("course C:\\courses\\my course.txt").text());
        assertEquals(HeadlessCommands.Kind.STATUS, HeadlessCommands.parse("status").kind());
        assertEquals(HeadlessCommands.Kind.KILL, HeadlessCommands.parse("kill").kind());
        assertEquals("zombie", HeadlessCommands.parse("kill zombie").text());
        assertEquals(HeadlessCommands.Kind.KILL, HeadlessCommands.parse("attack").kind());
        assertEquals("zombie", HeadlessCommands.parse("ATTACK Zombie").text());
        assertTrue(HeadlessCommands.parse("kill zombie extra").error().contains("usage: kill"));
        assertTrue(HeadlessCommands.parse("nope").error().contains("unknown command"));
        assertNull(HeadlessCommands.parse(""));
        assertNull(HeadlessCommands.parse("# comment"));
    }

    @Test
    public void whispersAndServerConsoleBecomeCommands() {
        assertEquals("goto 1 2 3", HeadlessCommands.commandFromChat("Server whispers to you: goto 1 2 3"));
        assertEquals("stop path", HeadlessCommands.commandFromChat(
                "\u00a7aServer whispers to you: \u00a7fstop path"));
        assertEquals("cancel", HeadlessCommands.commandFromChat("[Server] cancel"));
        assertEquals("mine stone", HeadlessCommands.commandFromChat("<Server> mine stone"));
        assertEquals("status", HeadlessCommands.commandFromChat("[\u00a7dxX\u00a7r -> me] status"));
        assertEquals("goto 1 64 1", HeadlessCommands.commandFromChat("goto 1 64 1"));
        assertEquals("place 1 2 3", HeadlessCommands.commandFromChat("Server whispers to you: place 1 2 3"));
        assertEquals("place 4 5 6", HeadlessCommands.commandFromChat("[Server] place 4 5 6"));
        assertEquals("kill zombie", HeadlessCommands.commandFromChat("Server whispers to you: kill zombie"));
        assertEquals("attack", HeadlessCommands.commandFromChat("[Server] attack"));
        assertNull(HeadlessCommands.commandFromChat("You whisper to Server: goto 1 2 3"));
        assertNull(HeadlessCommands.commandFromChat("<Bob> goto 1 2 3"));
        assertNull(HeadlessCommands.commandFromChat("Server whispers to you: hello there"));
        assertNull(HeadlessCommands.commandFromChat("HBot1 joined the game"));
        assertNull(HeadlessCommands.commandFromChat(null));
    }
}
