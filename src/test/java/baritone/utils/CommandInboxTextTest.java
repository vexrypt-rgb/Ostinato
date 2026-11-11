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

package baritone.utils;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Ostinato: parser for the -Dostinato.commandInbox drain. */
public class CommandInboxTextTest {

    @Test
    public void skipsBlankAndComments() {
        assertEquals(Collections.emptyList(), CommandInboxText.commands(""));
        assertEquals(Collections.emptyList(), CommandInboxText.commands("   \n// note\n; also\n\n"));
        assertNull(CommandInboxText.normalize(null));
        assertNull(CommandInboxText.normalize(" #  "));
    }

    @Test
    public void stripsPrefixAndKeepsArgs() {
        assertEquals("swarm status", CommandInboxText.normalize("#swarm status"));
        assertEquals("swarm send Azalea goto 1 2 3", CommandInboxText.normalize("  swarm send Azalea goto 1 2 3"));
        assertEquals("swarm outcome 11111111-1111-1111-1111-111111111111",
                CommandInboxText.normalize("@swarm outcome 11111111-1111-1111-1111-111111111111"));
        assertEquals(Arrays.asList("swarm status", "cancel"),
                CommandInboxText.commands("#swarm status\r\n// skip\r\n#cancel\n"));
    }
}
