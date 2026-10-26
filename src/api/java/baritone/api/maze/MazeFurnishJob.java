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

package baritone.api.maze;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: everything a maze room still needs after the builder carved it, in plain ids and world coordinates so a
 * client mod (TenorClef) can gather and place it without touching Ostinato internals.
 * <p>
 * Steps come in the order they should run: lights, containers, fills, then seals. Seals go last because the entry shaft
 * is one of them, and the bot has to be out of the room before it closes the shaft behind itself.
 */
public final class MazeFurnishJob {

    public enum StepKind {
        /** Place a light block, replacing the stone the dig pass left there. {@link Step#blockIds} is best first. */
        LIGHT,
        /** Place an empty container block. */
        CONTAINER,
        /** Put {@link Step#contents} into the container at this position. */
        FILL,
        /** Fill a gap in the shell or the entry shaft so the room is hidden again. */
        SEAL
    }

    public record ItemCount(String itemId, int count) {}

    /** One placement or fill. {@code blockIds} are Minecraft ids without the namespace, best first; any one will do. */
    public record Step(StepKind kind, int x, int y, int z, List<String> blockIds, List<ItemCount> contents) {
        public Step {
            blockIds = List.copyOf(blockIds);
            contents = List.copyOf(contents);
        }
    }

    public final int cellX;
    public final int cellZ;
    public final String themeId;
    /** False in creative: the schematic already placed lights and containers, so only fills are listed. */
    public final boolean survival;
    public final List<Step> steps;

    public MazeFurnishJob(int cellX, int cellZ, String themeId, boolean survival, List<Step> steps) {
        this.cellX = cellX;
        this.cellZ = cellZ;
        this.themeId = themeId;
        this.survival = survival;
        this.steps = List.copyOf(steps);
    }

    public List<Step> steps(StepKind kind) {
        List<Step> out = new ArrayList<>();
        for (Step step : steps) {
            if (step.kind() == kind) {
                out.add(step);
            }
        }
        return out;
    }

    /**
     * Items to have in the inventory before starting: the first choice of every placed block plus every fill item.
     * Insertion order is stable so a gatherer can work down the list.
     */
    public Map<String, Integer> materials() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Step step : steps) {
            if (step.kind() == StepKind.FILL) {
                for (ItemCount item : step.contents()) {
                    out.merge(item.itemId(), item.count(), Integer::sum);
                }
            } else if (!step.blockIds().isEmpty()) {
                out.merge(step.blockIds().get(0), 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(out);
    }

    public String summary() {
        return "cell " + cellX + "," + cellZ + " " + themeId + ": "
                + steps(StepKind.LIGHT).size() + " light, "
                + steps(StepKind.CONTAINER).size() + " container, "
                + steps(StepKind.FILL).size() + " fill, "
                + steps(StepKind.SEAL).size() + " seal";
    }
}
