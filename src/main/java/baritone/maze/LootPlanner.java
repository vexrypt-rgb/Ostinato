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

import java.util.ArrayList;
import java.util.List;

/**
 * Seed-stable container plans. Chests are mixed, barrels are bulk junk or food, pots are a sherd and a tiny valuable.
 * Rates stay low. A position already in the ledger is skipped and never refilled.
 * <p>
 * Item ids are names a later inserter would put in the container. This class does not touch inventories.
 * Deep "lore books" are plain {@code book} items; written books with pages are not generated yet.
 */
public final class LootPlanner {

    public enum ContainerKind {
        CHEST, BARREL, POT;

        public static ContainerKind byId(String id) {
            if (id == null) {
                return null;
            }
            for (ContainerKind kind : values()) {
                if (kind.name().equalsIgnoreCase(id)) {
                    return kind;
                }
            }
            return null;
        }
    }

    public record LootStack(String itemId, int count) {}

    public record LootPlacement(int x, int y, int z, ContainerKind kind, MazeTheme theme, List<LootStack> stacks) {
        public String payload() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < stacks.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(stacks.get(i).itemId).append('*').append(stacks.get(i).count);
            }
            return sb.toString();
        }
    }

    public interface PlacedLedger {
        boolean contains(int x, int y, int z);

        PlacedLedger EMPTY = (x, y, z) -> false;
    }

    private LootPlanner() {}

    public static List<LootPlacement> plan(RoomPlan plan, PlacedLedger ledger) {
        PlacedLedger book = ledger == null ? PlacedLedger.EMPTY : ledger;
        int gate = (int) Long.remainderUnsigned(MazeSeed.mix(plan.roomSeed, 99, plan.theme.ordinal()), 100); // Ostinato: harness knob -Dostinato.mazetest.lootGate=0..99 forces the container roll for proof runs
        gate = Integer.getInteger("ostinato.mazetest.lootGate", gate);
        ContainerKind[] kinds = kindsFor(plan.theme, gate);
        List<LootPlacement> out = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            int[] spot = candidate(plan, i);
            if (spot == null) {
                break;
            }
            int wx = plan.originX + spot[0];
            int wy = plan.originY + spot[1];
            int wz = plan.originZ + spot[2];
            if (book.contains(wx, wy, wz)) {
                continue;
            }
            out.add(new LootPlacement(wx, wy, wz, kinds[i], plan.theme, roll(plan.theme, kinds[i], plan.roomSeed, i)));
        }
        return List.copyOf(out);
    }

    /** Item ids legal for this theme and container. Tests use this to check a roll did not leave the table. */
    public static List<String> table(MazeTheme theme, ContainerKind kind) {
        return List.of(items(theme, kind));
    }

    private static int[] candidate(RoomPlan plan, int nth) {
        int start = (int) Long.remainderUnsigned(plan.roomSeed, MazePlanner.LOOT_SPOTS.length);
        int found = 0;
        for (int i = 0; i < MazePlanner.LOOT_SPOTS.length; i++) {
            int[] spot = MazePlanner.LOOT_SPOTS[(start + i) % MazePlanner.LOOT_SPOTS.length];
            if (plan.role(spot[0], spot[1], spot[2]) != BlockRole.AIR) {
                continue;
            }
            if (found == nth) {
                return spot;
            }
            found++;
        }
        return null;
    }

    private static ContainerKind[] kindsFor(MazeTheme theme, int gate) {
        return switch (theme) {
            case YELLOW_OFFICE -> gate < 55 ? new ContainerKind[0]
                    : gate < 85 ? new ContainerKind[]{ContainerKind.BARREL}
                    : gate < 95 ? new ContainerKind[]{ContainerKind.BARREL, ContainerKind.POT}
                    : new ContainerKind[]{ContainerKind.CHEST};
            case PIPES -> gate < 50 ? new ContainerKind[0]
                    : gate < 80 ? new ContainerKind[]{ContainerKind.CHEST}
                    : gate < 93 ? new ContainerKind[]{ContainerKind.BARREL}
                    : new ContainerKind[]{ContainerKind.POT};
            case HUB -> gate < 45 ? new ContainerKind[0]
                    : gate < 80 ? new ContainerKind[]{ContainerKind.CHEST}
                    : gate < 93 ? new ContainerKind[]{ContainerKind.BARREL}
                    : new ContainerKind[]{ContainerKind.CHEST, ContainerKind.POT};
            case DEEP -> gate < 75 ? new ContainerKind[0]
                    : gate < 92 ? new ContainerKind[]{ContainerKind.CHEST}
                    : new ContainerKind[]{ContainerKind.POT};
        };
    }

    private static List<LootStack> roll(MazeTheme theme, ContainerKind kind, long roomSeed, int index) {
        String[] table = items(theme, kind);
        int stacks = 1 + (int) Long.remainderUnsigned(MazeSeed.mix(roomSeed, index, kind.ordinal() + 10), maxStacks(kind));
        List<LootStack> out = new ArrayList<>();
        for (int i = 0; i < stacks; i++) {
            String item = table[(int) Long.remainderUnsigned(MazeSeed.mix(roomSeed, index * 17 + i, kind.ordinal()), table.length)];
            int count = 1 + (int) Long.remainderUnsigned(MazeSeed.mix(roomSeed, i, 40 + kind.ordinal()), maxCount(theme, kind));
            if (item.equals("diamond") || item.equals("emerald") || item.startsWith("music_disc") || item.endsWith("_pottery_sherd")) {
                count = 1;
            }
            out.add(new LootStack(item, count));
        }
        return List.copyOf(out);
    }

    private static int maxStacks(ContainerKind kind) {
        return switch (kind) {
            case CHEST -> 3;
            case BARREL -> 4;
            case POT -> 1;
        };
    }

    private static int maxCount(MazeTheme theme, ContainerKind kind) {
        if (kind == ContainerKind.POT) {
            return 1;
        }
        if (theme == MazeTheme.DEEP) {
            return kind == ContainerKind.CHEST ? 2 : 4;
        }
        return kind == ContainerKind.CHEST ? 3 : 8;
    }

    private static String[] items(MazeTheme theme, ContainerKind kind) {
        return switch (theme) {
            case YELLOW_OFFICE -> switch (kind) {
                case CHEST -> new String[]{"paper", "book", "cookie", "emerald", "string"};
                case BARREL -> new String[]{"bread", "rotten_flesh", "string", "stick", "bowl", "paper", "wheat_seeds"};
                case POT -> new String[]{"brick", "angler_pottery_sherd", "gold_nugget"};
            };
            case PIPES -> switch (kind) {
                case CHEST -> new String[]{"redstone", "repeater", "redstone_torch", "iron_nugget", "comparator"};
                case BARREL -> new String[]{"rail", "cobblestone", "coal", "redstone", "string"};
                case POT -> new String[]{"miner_pottery_sherd", "iron_nugget", "redstone"};
            };
            case HUB -> switch (kind) {
                case CHEST -> new String[]{"book", "map", "emerald", "name_tag", "paper"};
                case BARREL -> new String[]{"bread", "paper", "book", "cookie"};
                case POT -> new String[]{"prize_pottery_sherd", "emerald", "book"};
            };
            case DEEP -> switch (kind) {
                case CHEST -> new String[]{"diamond", "book", "music_disc_13", "prize_pottery_sherd", "emerald"};
                case BARREL -> new String[]{"bread", "coal", "paper"};
                case POT -> new String[]{"angler_pottery_sherd", "diamond", "gold_nugget"};
            };
        };
    }
}
