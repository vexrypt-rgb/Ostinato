/*
 * Ostinato: single-bot ore gathering while legit mode is on. Not part of upstream Baritone.
 */
package baritone.legit;

import baritone.api.legit.SeenOnly;
import baritone.bastion.FluidPocket;

import java.util.Locale;

/**
 * Cave frontier, branch mining, and water-air decisions for one bot.
 * <p>
 * A branch cell is a function of the tunnel origin, a salt, the branch index, and the step. It does not take an ore
 * position, so the tunnel is not aimed at a hidden ore. Spacing is {@link #SPACING} blocks center to center, which
 * leaves two blocks between one-wide tunnels, so each of those blocks has a face on a tunnel. After that face is
 * open, {@link SeenOnly} may admit the ore. Nothing here is written to disk.
 * <p>
 * Coordinated gather for more than one bot is refused with the same sentence phase 1 uses for lease work. This class
 * does not place a torch or a door. Those clicks need a live client. Low air does not sink deeper.
 */
public final class LegitGather {

    /** Same sentence as {@code CommandProtocol}'s phase 1 refusal. */
    public static final String MULTI_BOT_REFUSAL = "swarm: coordinated work is not available in phase 1";

    /** Center-to-center distance of one-wide branches. Two blocks of stone sit between them. */
    public static final int SPACING = 3;

    /** North, east, south, west. Shared with the live sampler. */
    public static final int[] DX = {0, 1, 0, -1};
    public static final int[] DZ = {-1, 0, 1, 0};

    private LegitGather() {}

    public enum Strategy {
        REJECT, YIELD, RETREAT, LOOK, SWAP_TOOL, LIGHT, CAVE, BRANCH, WATER_DOOR, SINK_FOR_DOOR
    }

    /** One decision. {@code hasGoal} is false when the bot should not path. */
    public static final class Plan {
        public final Strategy strategy;
        public final int x;
        public final int y;
        public final int z;
        public final boolean hasGoal;
        public final String refusal;

        private Plan(Strategy strategy, int x, int y, int z, boolean hasGoal, String refusal) {
            this.strategy = strategy;
            this.x = x;
            this.y = y;
            this.z = z;
            this.hasGoal = hasGoal;
            this.refusal = refusal;
        }

        static Plan stay(Strategy strategy) {
            return new Plan(strategy, 0, 0, 0, false, null);
        }

        static Plan at(Strategy strategy, int x, int y, int z) {
            return new Plan(strategy, x, y, z, true, null);
        }
    }

    /**
     * Inputs for {@link #plan}. Tests fill this in. The live sampler does too. Ore coordinates are not a field.
     */
    public static final class Facts {
        public boolean unreadable;
        public boolean multiBot;
        public boolean graceBusy;
        public boolean headInWater;
        public int air = 300;
        public int maxAir = 300;
        public int surface;
        public boolean inPocket;
        public int doors;
        public boolean floor = true;
        public boolean exit;
        public boolean pickaxe = true;
        public boolean holdingPick = true;
        public boolean pickInInventory = true;
        public int feetX;
        public int feetY;
        public int feetZ;
        public int originX;
        public int originZ;
        public int salt;
        public int tick;
        public int branchIndex;
        public int branchStep;
        public String block = "diamond_ore";
        public final boolean[] open = new boolean[4];
        public final int[] light = new int[4];
    }

    public static Plan plan(Facts facts) {
        if (facts == null || facts.unreadable) {
            return Plan.stay(Strategy.YIELD);
        }
        if (facts.multiBot) {
            return new Plan(Strategy.REJECT, 0, 0, 0, false, MULTI_BOT_REFUSAL);
        }
        if (facts.graceBusy) {
            return Plan.stay(Strategy.YIELD);
        }
        if (facts.headInWater) {
            return water(facts);
        }
        if (!facts.holdingPick) {
            return Plan.stay(facts.pickInInventory ? Strategy.SWAP_TOOL : Strategy.YIELD);
        }
        if (lookThisTick(facts.tick, facts.salt)) {
            return Plan.stay(Strategy.LOOK);
        }
        int frontier = frontierIndex(facts.open, facts.light, facts.salt);
        if (frontier >= 0) {
            int x = facts.feetX + DX[frontier];
            int y = facts.feetY;
            int z = facts.feetZ + DZ[frontier];
            if (facts.light[frontier] < SeenOnly.MIN_LIGHT) {
                return Plan.at(Strategy.LIGHT, x, y, z);
            }
            return Plan.at(Strategy.CAVE, x, y, z);
        }
        return branch(facts);
    }

    /**
     * FluidPocket chooses the water action. A sink while air is at or under {@link FluidPocket#MARGIN} becomes a
     * retreat, so the bot does not swim deeper without a breath. A tunnel toward an exit that this method cannot
     * point at is a yield, not a path through stone.
     */
    static Plan water(Facts facts) {
        int air = Math.max(0, facts.air);
        int max = Math.max(1, facts.maxAir);
        FluidPocket.Action action = FluidPocket.decide(air, max, facts.surface, facts.inPocket, Math.max(0, facts.doors),
                facts.floor, facts.exit, facts.pickaxe);
        boolean low = air <= FluidPocket.MARGIN;
        if (action == FluidPocket.Action.SINK && low) {
            return Plan.at(Strategy.RETREAT, facts.feetX, facts.feetY + 1, facts.feetZ);
        }
        return switch (action) {
            case WAIT, NONE -> Plan.stay(Strategy.YIELD);
            case SWIM_UP -> Plan.at(Strategy.RETREAT, facts.feetX, facts.feetY + 1, facts.feetZ);
            case DOOR -> Plan.at(Strategy.WATER_DOOR, facts.feetX, facts.feetY, facts.feetZ);
            case SINK -> Plan.at(Strategy.SINK_FOR_DOOR, facts.feetX, facts.feetY - 1, facts.feetZ);
            case TUNNEL -> low
                    ? Plan.at(Strategy.RETREAT, facts.feetX, facts.feetY + 1, facts.feetZ)
                    : Plan.stay(Strategy.YIELD);
        };
    }

    static Plan branch(Facts facts) {
        int level = branchY(facts.block);
        if (facts.feetY != level) {
            int step = facts.feetY > level ? -1 : 1;
            return Plan.at(Strategy.BRANCH, facts.feetX, facts.feetY + step, facts.feetZ);
        }
        return Plan.at(Strategy.BRANCH,
                cellX(facts.originX, facts.originZ, facts.salt, facts.branchIndex, facts.branchStep),
                level,
                cellZ(facts.originX, facts.originZ, facts.salt, facts.branchIndex, facts.branchStep));
    }

    /** 26.3 branch level for one block name. See the notes file for the nether and diamond choices. */
    public static int branchY(String block) {
        return switch (normalize(block)) {
            case "diamond", "redstone" -> -59;
            case "gold" -> -16;
            case "lapis" -> 0;
            case "copper" -> 48;
            case "coal" -> 96;
            case "emerald" -> 232;
            case "ancient_debris", "ancientdebris", "netherite" -> 15;
            case "nether_quartz", "quartz", "nether_gold" -> 48;
            default -> 16;
        };
    }

    /** Every peak recorded for the block. The branch uses {@link #branchY}, which is the first of these. */
    public static int[] peaks(String block) {
        return switch (normalize(block)) {
            case "iron" -> new int[]{16, 232};
            case "coal" -> new int[]{96, 136};
            default -> new int[]{branchY(block)};
        };
    }

    public static int heading(int salt) {
        return Math.floorMod(salt, 4);
    }

    /** How many branch steps this tunnel runs before the next parallel branch. Varies with the salt. */
    public static int runLength(int salt, int branchIndex) {
        return 8 + Math.floorMod(salt * 31 + branchIndex * 7, 17);
    }

    /** How often, in ticks, the bot looks around instead of walking. Varies with the salt. */
    public static int lookPeriod(int salt) {
        return 5 + Math.floorMod(salt, 5);
    }

    public static boolean lookThisTick(int tick, int salt) {
        int period = lookPeriod(salt);
        return tick > 0 && period > 0 && tick % period == 0;
    }

    /**
     * The block to mine at this step. One step past the origin, then one further each step. Not an ore coordinate.
     */
    public static int cellX(int originX, int originZ, int salt, int branchIndex, int step) {
        int[] forward = forward(heading(salt));
        int[] side = side(heading(salt));
        int along = step + 1;
        return originX + forward[0] * along + side[0] * branchIndex * SPACING;
    }

    public static int cellZ(int originX, int originZ, int salt, int branchIndex, int step) {
        int[] forward = forward(heading(salt));
        int[] side = side(heading(salt));
        int along = step + 1;
        return originZ + forward[1] * along + side[1] * branchIndex * SPACING;
    }

    /**
     * An open neighbor to walk toward, or -1 when every neighbor is closed. A dark open cell wins over a lit one,
     * so the bot lights before it walks deeper. Ties rotate by salt.
     */
    public static int frontierIndex(boolean[] open, int[] light, int salt) {
        if (open == null || light == null) {
            return -1;
        }
        int n = Math.min(4, Math.min(open.length, light.length));
        int start = Math.floorMod(salt, 4);
        int dark = -1;
        int lit = -1;
        for (int i = 0; i < n; i++) {
            int idx = (start + i) % n;
            if (!open[idx]) {
                continue;
            }
            if (light[idx] < SeenOnly.MIN_LIGHT && dark < 0) {
                dark = idx;
            } else if (light[idx] >= SeenOnly.MIN_LIGHT && lit < 0) {
                lit = idx;
            }
        }
        return dark >= 0 ? dark : lit;
    }

    static String normalize(String block) {
        if (block == null) {
            return "";
        }
        String name = block.toLowerCase(Locale.ROOT).trim();
        int colon = name.lastIndexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        if (name.startsWith("block.minecraft.")) {
            name = name.substring("block.minecraft.".length());
        }
        name = name.replace("deepslate_", "");
        if (name.endsWith("_ore")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static int[] forward(int heading) {
        return switch (heading) {
            case 1 -> new int[]{0, 1};
            case 2 -> new int[]{-1, 0};
            case 3 -> new int[]{0, -1};
            default -> new int[]{1, 0};
        };
    }

    private static int[] side(int heading) {
        return switch (heading) {
            case 1 -> new int[]{-1, 0};
            case 2 -> new int[]{0, -1};
            case 3 -> new int[]{1, 0};
            default -> new int[]{0, 1};
        };
    }
}
