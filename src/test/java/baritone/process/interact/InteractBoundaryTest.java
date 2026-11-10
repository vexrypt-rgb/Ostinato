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

package baritone.process.interact;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Ostinato 2.0 Phase 09: the get-to-block capability reads open containers only through its version adapter. The
 * menu API (which differs by version) appears in {@link ClientContainers} and nowhere else in the capability, its
 * contract or its task-runner consumer; the contract and the rule import no Minecraft class at all.
 */
public class InteractBoundaryTest {

    private static final String ADAPTER = "src/main/java/baritone/process/interact/ClientContainers.java";
    private static final String[] VERSION_FREE = {
            "src/main/java/baritone/process/interact/ContainerView.java",
            "src/main/java/baritone/process/interact/OpenVerifier.java",
    };
    private static final String[] MENU_FREE = {
            "src/main/java/baritone/process/GetToBlockProcess.java",
            "src/main/java/baritone/gui/tasks/game/BaritoneTaskAdapter.java",
            "src/api/java/baritone/api/process/IGetToBlockProcess.java",
            "src/main/java/baritone/process/interact/ContainerView.java",
            "src/main/java/baritone/process/interact/OpenVerifier.java",
    };
    /** Menu API: the menu classes' package, the player's open menu field, the close call. */
    private static final String[] MENU_API = {"net.minecraft.world.inventory", "containerMenu", "closeContainer("};

    @Test
    public void onlyTheAdapterNamesTheMenuApi() throws IOException {
        for (String f : MENU_FREE) {
            String src = read(f);
            for (String api : MENU_API) {
                assertFalse(f + " uses " + api, src.contains(api));
            }
        }
        String adapter = read(ADAPTER);
        for (String api : MENU_API) {
            assertTrue(ADAPTER + " should hold " + api, adapter.contains(api));
        }
    }

    @Test
    public void theContractAndTheRuleImportNoMinecraft() throws IOException {
        for (String f : VERSION_FREE) {
            assertFalse(f, read(f).contains("import net.minecraft"));
        }
    }

    @Test
    public void theRunnerUsesTheContractNotTheImplementation() throws IOException {
        String runner = read("src/main/java/baritone/gui/tasks/game/BaritoneTaskAdapter.java");
        assertFalse(runner.contains("import baritone.process."));
        assertTrue(runner.contains("import baritone.api.process.IGetToBlockProcess;"));
    }

    private static String read(String rel) throws IOException {
        Path path = Path.of(rel);
        return Files.readString(Files.exists(path) ? path : Path.of("..").resolve(rel), StandardCharsets.UTF_8);
    }
}
