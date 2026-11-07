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
        List<ShellSeal.BlockPos3> lateGaps = List.of();
        if (survival && probe != null) {
            List<ShellSeal.BlockPos3> gaps = new ArrayList<>(shellHoles(plan, probe));
            for (ShellSeal.BlockPos3 gap : ShellSeal.maintenanceTargets(plan, probe)) {
                if (!gaps.contains(gap)) gaps.add(gap);
            }
            // bottom up, so a shaft is closed from the room side first and the bot can pillar out over it
            gaps.sort(Comparator.comparingInt(ShellSeal.BlockPos3::y)
                    .thenComparingInt(ShellSeal.BlockPos3::x)
                    .thenComparingInt(ShellSeal.BlockPos3::z));
            lateGaps = new ArrayList<>();
            // Ostinato: run141. Lobby shell holes at the room's y (air, cobble under them) were sealed after the
            // floor cladding, so a cherry plank sat on the hole. The seal pass then stood in the cave below and
            // skipped the cell (SEAL-LEFT-OPEN, seal 85/117 when the 40 min cap fired). Close a hole that this
            // plan is about to cover before that cover is placed, while the cell above is still open.
            for (ShellSeal.BlockPos3 gap : gaps) {
                if (cappedByCladding(plan, gap)) steps.add(sealStep(plan, gap));
                else lateGaps.add(gap);
            }
        }
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
        for (ShellSeal.BlockPos3 gap : lateGaps) steps.add(sealStep(plan, gap));
        // Ostinato: run100. The carved cells, so TenorClef can tell scaffolding left in the room from the room itself.
        List<Integer> air = new ArrayList<>();
        for (int x = 0; x < plan.sizeX; x++) {
            for (int y = 0; y < plan.sizeY; y++) {
                for (int z = 0; z < plan.sizeZ; z++) {
                    if (plan.role(x, y, z) == BlockRole.AIR) {
                        air.add(plan.originX + x);
                        air.add(plan.originY + y);
                        air.add(plan.originZ + z);
                    }
                }
            }
        }
        int[] airCells = new int[air.size()];
        for (int i = 0; i < airCells.length; i++) {
            airCells[i] = air.get(i);
        }
        return new MazeFurnishJob(plan.cellX, plan.cellZ, plan.theme.id, survival, steps, airCells);
    }

    private static Step sealStep(RoomPlan plan, ShellSeal.BlockPos3 gap) {
        return new Step(StepKind.SEAL, gap.x(), gap.y(), gap.z(), sealIds(plan.theme, plan.originY), List.of());
    }

    /** A hole this plan will not clad, sitting under a floor/wall/ceiling cell it will. */
    private static boolean cappedByCladding(RoomPlan plan, ShellSeal.BlockPos3 gap) {
        int lx = gap.x() - plan.originX;
        int ly = gap.y() - plan.originY;
        int lz = gap.z() - plan.originZ;
        if (cladTarget(plan, lx, ly, lz)) return false;
        return cladTarget(plan, lx, ly + 1, lz);
    }

    private static boolean cladTarget(RoomPlan plan, int x, int y, int z) {
        if (!plan.inRelative(x, y, z)) return false;
        BlockRole role = plan.role(x, y, z);
        return (role == BlockRole.FLOOR || role == BlockRole.WALL || role == BlockRole.CEILING || role == BlockRole.WATER)
                && touchesRoomAir(plan, x, y, z);
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
                    if (role == BlockRole.WATER && POOL_WATER) {
                        continue; // Ostinato: 2026-11-02, a filled pool is fluid by design
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
        return sealIds(theme, Integer.MIN_VALUE);
    }

    /**
     * Deepslate replaces stone below y=0 and blends through y=8. A DEEP room shifted above that
     * (run51: floor y=73) is dug from stone; sealing it with cobbled deepslate sends the bot to bedrock.
     */
    public static List<String> sealIds(MazeTheme theme, int originY) {
        if (theme == MazeTheme.DEEP && deepslateLayer(originY)) {
            return List.of("cobbled_deepslate", "deepslate", "cobblestone", "stone");
        }
        return List.of("cobblestone", "stone", "andesite", "cobbled_deepslate", "dirt");
    }

    /** True when the room floor is in the deepslate band, not the stone layer above y=8. */
    static boolean deepslateLayer(int originY) {
        return originY <= 8;
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
                    if (role != BlockRole.FLOOR && role != BlockRole.WALL && role != BlockRole.CEILING && role != BlockRole.WATER) continue;
                    if (!touchesRoomAir(plan, x, y, z)) continue;
                    steps.add(new Step(StepKind.LIGHT, plan.originX + x, plan.originY + y, plan.originZ + z,
                            cladIds(plan.theme, role, plan.originY), List.of()));
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
     * Ostinato: 2026-11-02, poolrooms. TenorClef's furnisher places blocks, not fluids, so by default a pool cell is
     * clad as a pale tile basin floor. {@code -Dostinato.maze.water=true} asks for {@code water} there instead, for a
     * furnisher that can empty a bucket into it (and leaves the cell out of the solid-shell holes).
     */
    public static final boolean POOL_WATER = Boolean.getBoolean("ostinato.maze.water");

    /** Ostinato: plank ids for logs near the room, most common first. Set by MazeProcess before planning; empty = unknown. */
    public static volatile List<String> woods = List.of();

    /**
     * Ostinato: {@code ids} with every *_planks entry swapped for a wood that grows nearby (the {@code rank}-th most common,
     * so floor and walls differ when two woods are around). Non-wood ids keep their place, so concrete stays first.
     */
    static List<String> wood(int rank, String... ids) {
        List<String> w = woods;
        List<String> out = new ArrayList<>();
        boolean put = false;
        for (String id : ids) {
            if (id.endsWith("_planks") && !w.isEmpty()) {
                if (!put) { out.add(w.get(Math.min(rank, w.size() - 1))); put = true; }
                for (String o : w) if (!out.contains(o)) out.add(o);
            } else if (!out.contains(id)) out.add(id);
        }
        return List.copyOf(out);
    }
    /**
     * Ostinato: per-theme palette, best first. The first id matches the creative schematic; later ids are themed blocks
     * a survival furnisher can actually make (TenorClef picks the first it can obtain). A DEEP room above y=8
     * uses cobblestone from the stone it was dug through.
     */
    public static List<String> cladIds(MazeTheme theme, BlockRole role) {
        return cladIds(theme, role, Integer.MIN_VALUE);
    }

    public static List<String> cladIds(MazeTheme theme, BlockRole role, int originY) {
        if (role == BlockRole.WATER) {
            return POOL_WATER ? List.of("water") : List.of("diorite", "polished_diorite", "calcite"); // Ostinato: pool basin, raw diorite against the polished rim
        }
        // Ostinato: survival picks the first id TenorClef can obtain, so cheap themed blocks (no smelting chains of
        // hundreds of stone) come first; the creative look (concrete, quartz, bricks) stays in MazeSchematic.
        return switch (theme) {
            case YELLOW_OFFICE -> switch (role) {
                case FLOOR -> wood(0, "oak_planks", "spruce_planks", "birch_planks");
                case WALL -> wood(1, "yellow_concrete", "yellow_terracotta", "birch_planks", "sandstone", "oak_planks");
                default -> wood(2, "polished_diorite", "polished_andesite", "birch_planks");
            };
            case PIPES -> switch (role) {
                case FLOOR -> List.of("polished_andesite", "andesite");
                case WALL -> List.of("andesite", "polished_andesite");
                default -> List.of("polished_andesite", "andesite");
            };
            case HUB -> switch (role) {
                case FLOOR -> wood(0, "birch_planks", "oak_planks");
                case WALL -> wood(1, "spruce_planks", "oak_planks");
                default -> wood(2, "polished_diorite", "birch_planks");
            };
            // Ostinato: run51. Theme deep at y=73 still asked for polished_deepslate x26 and cobbled_deepslate x112.
            // Those blocks only generate at bedrock. The bot left the room, stuck at y=3, and the 90s abort closed the client.
            case DEEP -> deepslateLayer(originY)
                    ? switch (role) {
                        case FLOOR -> List.of("polished_deepslate", "cobbled_deepslate");
                        default -> List.of("cobbled_deepslate", "polished_deepslate");
                    }
                    : List.of("cobblestone", "andesite", "polished_andesite");
            // Ostinato: 2026-11-02, connected-mode themes. Cheap and mineable first, like the four above.
            case MONO_YELLOW -> switch (role) {
                case FLOOR -> wood(0, "birch_planks", "oak_planks");
                case WALL -> wood(0, "yellow_concrete", "yellow_terracotta", "birch_planks", "sandstone");
                default -> wood(0, "birch_planks", "polished_diorite");
            };
            case POOLROOMS -> switch (role) {
                case FLOOR -> List.of("polished_diorite", "diorite", "calcite");
                case WALL -> List.of("diorite", "polished_diorite", "calcite");
                default -> List.of("polished_diorite", "diorite");
            };
            // Ostinato: tuff room palette. Maintenance rooms on the deepslate layer (mazeDepthY) clad in tuff bricks /
            // polished tuff crafted by TenorClef from tuff mined around the room; raw tuff and cobbled deepslate fall back.
            case MAINTENANCE -> deepslateLayer(originY)
                    ? switch (role) {
                        case FLOOR -> List.of("polished_tuff", "tuff", "cobbled_deepslate");
                        case WALL -> List.of("tuff_bricks", "polished_tuff", "tuff", "cobbled_deepslate");
                        default -> List.of("polished_tuff", "tuff_bricks", "tuff", "cobbled_deepslate");
                    }
                    : switch (role) {
                case FLOOR -> List.of("cobblestone", "andesite");
                case WALL -> List.of("andesite", "cobblestone", "tuff");
                default -> List.of("polished_andesite", "andesite", "cobblestone");
            };
            case WAREHOUSE -> switch (role) {
                case FLOOR -> List.of("polished_andesite", "andesite", "cobblestone");
                case WALL -> List.of("cobblestone", "andesite");
                default -> wood(1, "spruce_planks", "oak_planks");
            };
            case LOBBY -> switch (role) {
                case FLOOR -> wood(0, "oak_planks", "spruce_planks");
                case WALL -> wood(1, "birch_planks", "sandstone", "oak_planks");
                default -> List.of("polished_diorite", "diorite", "birch_planks");
            };
        };
    }}
