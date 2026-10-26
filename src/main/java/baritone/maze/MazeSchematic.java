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

package baritone.maze;

import baritone.api.schematic.AbstractSchematic;
import baritone.maze.LootPlanner.ContainerKind;
import baritone.maze.LootPlanner.LootPlacement;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Turns a room plan into a builder schematic. Cells marked {@link BlockRole#OUTSIDE} are left alone, so the
 * corridor's extra shell does not replace the stone beside it. Air cells are carved. Container blocks are placed;
 * their contents stay in the loot ledger until a later pass inserts them.
 */
public final class MazeSchematic extends AbstractSchematic {

    private final RoomPlan plan;
    private final List<LootPlacement> loot;

    /** Survival: carve the interior and keep whatever solid block is already there for the shell. */
    private final boolean survival;

    public MazeSchematic(RoomPlan plan, List<LootPlacement> loot) {
        this(plan, loot, false);
    }

    public MazeSchematic(RoomPlan plan, List<LootPlacement> loot, boolean survival) {
        super(plan.sizeX, plan.sizeY, plan.sizeZ);
        this.plan = plan;
        this.loot = loot == null ? List.of() : List.copyOf(loot);
        this.survival = survival;
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState currentState) {
        if (x < 0 || y < 0 || z < 0 || x >= plan.sizeX || y >= plan.sizeY || z >= plan.sizeZ) {
            return false;
        }
        return plan.role(x, y, z) != BlockRole.OUTSIDE;
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        if (survival) {
            return survivalState(plan.role(x, y, z), current, approxPlaceable);
        }
        for (LootPlacement placement : loot) {
            if (placement.x() - plan.originX == x && placement.y() - plan.originY == y && placement.z() - plan.originZ == z) {
                return container(placement.kind());
            }
        }
        return state(plan.theme, plan.role(x, y, z));
    }

    /**
     * Survival is a dig-only pass: the interior is carved and every shell, floor, wall and ceiling cell keeps whatever is
     * already there. Nothing is placed. A shell cell that is air is usually the bot's own entry shaft, and asking the
     * builder to fill it while the bot stands in it made the live build stall (GoalPlace above its head, breaks never
     * taken). Gaps, lights and containers are left for a later seal and loot pass.
     */
    static BlockState survivalState(BlockRole role, BlockState current, List<BlockState> approxPlaceable) {
        if (role == BlockRole.AIR || role == BlockRole.OUTSIDE || current == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return current;
    }

    private static BlockState container(ContainerKind kind) {
        return switch (kind) {
            case CHEST -> Blocks.CHEST.defaultBlockState();
            case BARREL -> Blocks.BARREL.defaultBlockState();
            case POT -> Blocks.DECORATED_POT.defaultBlockState();
        };
    }

    static BlockState state(MazeTheme theme, BlockRole role) {
        if (role == BlockRole.AIR || role == BlockRole.OUTSIDE) {
            return Blocks.AIR.defaultBlockState();
        }
        if (role == BlockRole.LIGHT) {
            return theme == MazeTheme.HUB ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.GLOWSTONE.defaultBlockState();
        }
        return switch (theme) {
            case YELLOW_OFFICE -> switch (role) {
                case FLOOR -> Blocks.WOOL.yellow().defaultBlockState();
                case WALL -> Blocks.CONCRETE.yellow().defaultBlockState();
                case FEATURE -> Blocks.OAK_PLANKS.defaultBlockState();
                default -> Blocks.SMOOTH_SANDSTONE.defaultBlockState();
            };
            case PIPES -> switch (role) {
                case FLOOR -> Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
                case FEATURE -> Blocks.IRON_BLOCK.defaultBlockState();
                default -> Blocks.STONE_BRICKS.defaultBlockState();
            };
            case HUB -> switch (role) {
                case FLOOR -> Blocks.SMOOTH_QUARTZ.defaultBlockState();
                case WALL, FEATURE -> Blocks.BOOKSHELF.defaultBlockState();
                default -> Blocks.QUARTZ_BLOCK.defaultBlockState();
            };
            case DEEP -> switch (role) {
                case FLOOR -> Blocks.POLISHED_DEEPSLATE.defaultBlockState();
                case FEATURE -> Blocks.AMETHYST_BLOCK.defaultBlockState();
                default -> Blocks.DEEPSLATE_BRICKS.defaultBlockState();
            };
        };
    }
}
