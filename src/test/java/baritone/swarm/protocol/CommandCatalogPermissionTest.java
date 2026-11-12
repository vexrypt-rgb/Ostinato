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

package baritone.swarm.protocol;

import baritone.api.command.CommandPermission;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Ostinato: the catalog's fallback permission matches what ExploreCommand declares (OWNER). */
public class CommandCatalogPermissionTest {

    @Test
    public void exploreDefaultsToOwner() {
        assertEquals(CommandPermission.OWNER, CommandCatalog.defaultPermission("explore"));
        assertEquals(CommandPermission.OWNER, CommandCatalog.infer("explore", null).permission);
        assertEquals(CommandPermission.OWNER, CommandCatalog.infer("EXPLORE", null).permission);
        // The declared permission still wins, and explore keeps its navigation tags.
        assertEquals(CommandPermission.OWNER, CommandCatalog.infer("explore", CommandPermission.OWNER).permission);
        assertEquals(CommandPermission.SWARM, CommandCatalog.infer("goto", null).permission);
    }
}
