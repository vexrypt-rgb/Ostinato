/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.manager;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CommandGuardTest {

    private final List<String> log = new ArrayList<>();

    @Test
    public void noGuardRunsEverything() {
        assertTrue(CommandManager.guardAllows(null, null, "goto 1 2 3", log::add));
        assertTrue(log.isEmpty());
    }

    @Test
    public void aRefusalStopsTheCommandAndSaysWhy() {
        List<String> seen = new ArrayList<>();
        assertFalse(CommandManager.guardAllows((c, line) -> {
            seen.add(line);
            return Optional.of("movement is held by combat");
        }, null, "goto 1 2 3", log::add));
        assertEquals(List.of("goto 1 2 3"), seen);
        assertEquals(List.of("Not run: movement is held by combat"), log);
    }

    @Test
    public void anEmptyAnswerRuns() {
        assertTrue(CommandManager.guardAllows((c, line) -> Optional.empty(), null, "mine diamond_ore", log::add));
    }

    @Test
    public void aBrokenGuardDoesNotBreakOstinato() {
        assertTrue(CommandManager.guardAllows((c, line) -> { throw new IllegalStateException("bug"); }, null, "goto", log::add));
        assertTrue(log.get(0).startsWith("Command guard failed"));
    }
}
