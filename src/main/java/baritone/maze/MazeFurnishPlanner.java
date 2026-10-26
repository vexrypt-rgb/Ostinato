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

import baritone.api.maze.MazeFurnishJob;
import baritone.api.maze.MazeFurnishJob.ItemCount;
import baritone.api.maze.MazeFurnishJob.Step;
import baritone.api.maze.MazeFurnishJob.StepKind;
import baritone.maze.LootPlanner.LootPlacement;
import baritone.maze.LootPlanner.LootStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Ostinato: turns a carved room into a {@link MazeFurnishJob}: lights and containers the dig-only survival pass left
 * out, the loot that goes in each container, and the shell gaps (entry shaft included) to seal last.
 * Pure: the world comes in as a {@link BlockProbe}.
 */
public final class MazeFurnishPlanner {

    private MazeFurnishPlanner() {}

    public static MazeFurnishJob plan(RoomPlan plan, List<LootPlacement> loot, BlockProbe probe, boolean survival) {
        List<LootPlacement> containers = loot == null ? List.of() : loot;
        List<Step> steps = new ArrayList<>();
        if (survival) {
            addCladding(plan, steps);
            for (int x = 0; x < plan.sizeX; x++) {
                for (int y = 0; y < plan.sizeY; y++) {
                    for (int z = 0; z < plan.sizeZ; z++) {
                        if (plan.role(x, y, z) == BlockRole.LIGHT) {
                            steps.add(new Step(StepKind.LIGHT, plan.originX + x, plan.originY + y, plan.originZ + z,
                                    lightIds(plan.theme), List.of()));
                        }
                    }
                }
            }
            for (LootPlacement placement : containers) {
                steps.add(new Step(StepKind.CONTAINER, placement.x(), placement.y(), placement.z(),
                        List.of(containerId(placement.kind())), List.of()));
            }
        }
        for (LootPlacement placement : containers) {
            List<ItemCount> contents = new ArrayList<>();
            for (LootStack stack : placement.stacks()) {
                contents.add(new ItemCount(stack.itemId(), stack.count()));
            }
            steps.add(new Step(StepKind.FILL, placement.x(), placement.y(), placement.z(), List.of(), contents));
        }
        if (survival && probe != null) {
            List<ShellSeal.BlockPos3> gaps = new ArrayList<>(shellHoles(plan, probe));
            for (ShellSeal.BlockPos3 gap : ShellSeal.maintenanceTargets(plan, probe)) {
                if (!gaps.contains(gap)) {
                    gaps.add(gap);
                }
            }
            // bottom up, so a shaft is closed from the room side first and the bot can pillar out over it
            gaps.sort(Comparator.comparingInt(ShellSeal.BlockPos3::y)
                    .thenComparingInt(ShellSeal.BlockPos3::x)
                    .thenComparingInt(ShellSeal.BlockPos3::z));
            for (ShellSeal.BlockPos3 gap : gaps) {
                steps.add(new Step(StepKind.SEAL, gap.x(), gap.y(), gap.z(), sealIds(plan.theme), List.of()));
            }
        }
        return new MazeFurnishJob(plan.cellX, plan.cellZ, plan.theme.id, survival, steps);
    }

    /**
     * Planned shell, wall, floor, ceiling and cap cells that are not solid in the world. {@link ShellSeal} assumes the
     * plan's own solid cells are there, so it cannot see the hole the bot dug through the ceiling to get in.
     */
    static List<ShellSeal.BlockPos3> shellHoles(RoomPlan plan, BlockProbe probe) {
        List<ShellSeal.BlockPos3> out = new ArrayList<>();
        for (int x = 0; x < plan.sizeX; x++) {
            for (int y = 0; y < plan.sizeY; y++) {
                for (int z = 0; z < plan.sizeZ; z++) {
                    BlockRole role = plan.role(x, y, z);
                    if (!role.solid() || role == BlockRole.LIGHT || role == BlockRole.FEATURE) {
                        continue;
                    }
                    int wx = plan.originX + x;
                    int wy = plan.originY + y;
                    int wz = plan.originZ + z;
                    if (probe.get(wx, wy, wz) != BlockProbe.BlockKind.SOLID) {
                        out.add(new ShellSeal.BlockPos3(wx, wy, wz));
                    }
                }
            }
        }
        return out;
    }

    /** Best first. The first entry matches what the creative schematic places, so both modes look the same. */
    public static List<String> lightIds(MazeTheme theme) {
        return switch (theme) {
            default -> List.of("torch"); // Ostinato: floor torch; no wall insert, so the shell stays whole
            // (Ostinato: glowstone/sea lantern/shroomlight inserts dropped for torches)
            
        };
    }

    /** Plain stone the shell is dug out of, so a patched gap looks like untouched ground. */
    public static List<String> sealIds(MazeTheme theme) {
        return theme == MazeTheme.DEEP
                ? List.of("cobbled_deepslate", "deepslate", "cobblestone", "stone")
                : List.of("cobblestone", "stone", "andesite", "cobbled_deepslate", "dirt");
    }

    public static String containerId(LootPlanner.ContainerKind kind) {
        return switch (kind) {
            case CHEST -> "chest";
            case BARREL -> "barrel";
            case POT -> "decorated_pot";
        };
    }

    /**
     * Ostinato: survival cladding. The dig pass leaves bare stone; every floor, wall and ceiling face that looks into
     * the room is swapped for the theme's block. Sent as LIGHT-kind steps (TenorClef: break what is there, place one
     * of blockIds) so the furnisher needs no new step kind. Cells that touch no room air stay as dug stone.
     */
    static void addCladding(RoomPlan plan, List<Step> steps) {
        for (int y = 0; y < plan.sizeY; y++) {
            for (int x = 0; x < plan.sizeX; x++) {
                for (int z = 0; z < plan.sizeZ; z++) {
                    BlockRole role = plan.role(x, y, z);
                    if (role != BlockRole.FLOOR && role != BlockRole.WALL && role != BlockRole.CEILING) continue;
                    if (!touchesRoomAir(plan, x, y, z)) continue;
                    steps.add(new Step(StepKind.LIGHT, plan.originX + x, plan.originY + y, plan.originZ + z,
                            cladIds(plan.theme, role), List.of()));
                }
            }
        }
    }

    private static boolean touchesRoomAir(RoomPlan plan, int x, int y, int z) {
        int[][] d = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] o : d) {
            int ax = x + o[0], ay = y + o[1], az = z + o[2];
            if (plan.inRelative(ax, ay, az)) {
                BlockRole r = plan.role(ax, ay, az);
                if (r == BlockRole.AIR || r == BlockRole.LIGHT) return true;
            }
        }
        return false;
    }

    /**
     * Ostinato: per-theme palette, best first. The first id matches the creative schematic; later ids are themed blocks
     * a survival furnisher can actually make (TenorClef picks the first it can obtain), never plain stone.
     */
    public static List<String> cladIds(MazeTheme theme, BlockRole role) {
        return switch (theme) {
            case YELLOW_OFFICE -> switch (role) {
                case FLOOR -> List.of("birch_planks", "oak_planks", "yellow_wool");
                case WALL -> List.of("yellow_concrete", "yellow_terracotta", "sandstone", "birch_planks");
                default -> List.of("smooth_sandstone", "smooth_stone", "stone_bricks");
            };
            case PIPES -> switch (role) {
                case FLOOR -> List.of("cracked_stone_bricks", "stone_bricks");
                case WALL -> List.of("stone_bricks", "polished_andesite");
                default -> List.of("stone_bricks", "smooth_stone");
            };
            case HUB -> switch (role) {
                case FLOOR -> List.of("smooth_quartz", "birch_planks", "oak_planks");
                case WALL -> List.of("bookshelf", "spruce_planks", "oak_planks");
                default -> List.of("quartz_block", "smooth_stone", "birch_planks");
            };
            case DEEP -> switch (role) {
                case FLOOR -> List.of("polished_deepslate", "cobbled_deepslate");
                default -> List.of("deepslate_bricks", "polished_deepslate", "cobbled_deepslate");
            };
        };
    }}
