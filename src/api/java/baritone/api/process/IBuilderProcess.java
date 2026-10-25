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

package baritone.api.process;

import baritone.api.schematic.ISchematic;
import baritone.api.schematic.MaskSchematic;
import baritone.api.schematic.partition.PartitionPlan;
import baritone.api.schematic.partition.RegionPart;
import baritone.api.schematic.partition.SchematicCells;
import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3i;

import java.io.File;
import java.util.List;

/**
 * @author Brady
 * @since 1/15/2019
 */
public interface IBuilderProcess extends IBaritoneProcess {

    /**
     * Requests a build for the specified schematic, labeled as specified, with the specified origin.
     *
     * @param name      A user-friendly name for the schematic
     * @param schematic The object representation of the schematic
     * @param origin    The origin position of the schematic being built
     */
    void build(String name, ISchematic schematic, Vector3i origin);

    /**
     * Requests a build for the specified schematic, labeled as specified, with the specified origin.
     *
     * @param name      A user-friendly name for the schematic
     * @param schematic The file path of the schematic
     * @param origin    The origin position of the schematic being built
     * @return Whether or not the schematic was able to load from file
     */
    boolean build(String name, File schematic, Vector3i origin);

    /**
     * Builds only one region of a partitioned schematic, for several bots building the same schematic at the same
     * origin. Same as {@code build(name, MaskSchematic.create(schematic, plan.regionMask(regionIndex, part)), origin)},
     * so the builder skips every cell outside the region, and blocks already correct are skipped as usual (safe to
     * re-run or re-assign). {@code BuilderProcess} additionally keeps pathing from breaking or placing in the other
     * regions when {@code buildRegionProtectForeign} is on.
     * <p>
     * Every bot must use the same schematic, origin, plan and {@code buildSchematicRotation/Mirror}: the plan is in the
     * un-rotated schematic's coordinates. For {@link baritone.api.schematic.partition.PartitionStrategy#LAYERS} plans
     * ({@link PartitionPlan#isOrdered()}) region {@code i} needs regions below it finished first.
     *
     * @param name        A user-friendly name for the schematic
     * @param schematic   The whole schematic, the same one the plan was made from
     * @param origin      The origin of the whole schematic (shared by all bots)
     * @param regionIndex Which region of the plan to build
     * @param plan        From {@link SchematicCells#partition(ISchematic, int)} or
     *                    {@link baritone.api.schematic.partition.SchematicPartitioner}
     * @param part        The whole region, only its interior, or only its seam cells
     * @throws IllegalArgumentException if the plan doesn't fit the schematic's size or the index is out of range
     */
    default void buildRegion(String name, ISchematic schematic, Vector3i origin, int regionIndex, PartitionPlan plan, RegionPart part) {
        SchematicCells.requireFits(schematic, plan);
        build(name, MaskSchematic.create(schematic, plan.regionMask(regionIndex, part)), origin);
    }

    /**
     * Builds the whole of one region, see {@link #buildRegion(String, ISchematic, Vector3i, int, PartitionPlan, RegionPart)}.
     */
    default void buildRegion(String name, ISchematic schematic, Vector3i origin, int regionIndex, PartitionPlan plan) {
        buildRegion(name, schematic, origin, regionIndex, plan, RegionPart.WHOLE);
    }

    /**
     * Loads a schematic file and builds the whole of one region of it, see
     * {@link #buildRegion(String, ISchematic, Vector3i, int, PartitionPlan, RegionPart)}.
     *
     * @return Whether or not the schematic was able to load from file
     * @throws IllegalArgumentException if the plan doesn't fit the schematic's size or the index is out of range
     */
    boolean buildRegion(String name, File schematic, Vector3i origin, int regionIndex, PartitionPlan plan);

    @Deprecated
    default boolean build(String schematicFile, BlockPos origin) {
        File file = new File(new File(Minecraft.getInstance().gameDir, "schematics"), schematicFile);
        return build(schematicFile, file, origin);
    }

    void buildOpenSchematic();

    void buildOpenLitematic(int i);

    void pause();

    boolean isPaused();

    void popStack();

    /** @deprecated Ostinato: use {@link #isExternalBuildFinished()}. */
    @Deprecated
    boolean isFromAltoclefFinished();

    /** Ostinato: true once a build started by a driving mod has finished. Neutral name for {@link #isFromAltoclefFinished()}. */
    default boolean isExternalBuildFinished() {
        return isFromAltoclefFinished();
    }

    void resume();

    void clearArea(BlockPos corner1, BlockPos corner2);

    /**
     * @return A list of block states that are estimated to be placeable by this builder process. You can use this in
     * schematics, for example, to pick a state that the builder process will be happy with, because any variation will
     * cause it to give up. This is updated every tick, but only while the builder process is active.
     */
    List<BlockState> getApproxPlaceable();
}
