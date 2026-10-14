package baritone.bastion;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-layout bastion routes built from Couriway's guide (ch. 3, ch. 4) and his treasure videos. Each step names an
 * objective; BastionProcess resolves it to blocks with the scanned layout. Pure logic.
 * Housing follows Couriway's beginner video route by default, with intermediate (gold elevator) and lava variants.
 */
public final class BastionRoutes {
    private BastionRoutes() {}

    public enum Step {
        // treasure (video beginner / intermediate)
        TAKE_THIRD_BASALT_BRIDGE, PILLAR_3_HUG, COVER_2_BLOCKS, MINE_HIGH_GOLD_CRAFT_INGOTS, DISTRACT_CROSSBOWS,
        BRIDGE_GAP, BUILD_POCKET, WEAR_GOLD_HELMET, CROSS_BRICK_BRIDGE, OPEN_ABOVE_STAIRS, MINE_BLOCKER_SEAL_BEHIND,
        BRIDGE_RAMPARTS, PIGLIN_PIT, MINE_GOLD_SIDE_1, MINE_GOLD_SIDE_2, LOOT_DOUBLE, BACKUP_GOLD_1, LOOT_SINGLES,
        BACKUP_GOLD_2, TRADE_AT_PIT,
        // treasure optional / later
        PEARL_HANG_SPAWNER, BOTTOM_ROUTE,
        // housing (video: beginner)
        HOUSING_ENTRY_BASALT_CUE, LOOT_TOP_LARGE, LOOT_TOP_SINGLE, RIGHT_FORK_BLOCK_IN, DROP_PIGLINS, DISTRACT_HALF_GOLD,
        ONE_BY_ONE, DIG_DOWN_3_STRIP_4, HOUSING_PIT, MINE_WALL_GOLD, T_TUNNEL_GOLD, UNSTICK_BY_MINING, TRADE_CORNER,
        HOUSING_BOTTOM_CHEST, HOUSING_BACKUP_GOLD,
        // housing (video: intermediate / lava)
        LOOT_TOP_TRIPLE, LOOT_LEFT_RAMPART_TRIPLE, BREAK_CHEST_AGGRO_THROW, BACK_OPENING, GOLD_ELEVATOR, LAVA_STRAGGLERS,
        OUTSIDE_CHESTS, AGGRO_ONE_GOLD_ARMOR, LAVA_BLOCK_GAP, STAIR_2, LOOT_ARMOR_CHEST, BLOCK_LOWER_LAVA, CORNER_CHEST,
        // bridge (ch. 4)
        MINE_CHALICE, TRADE_TRIPLE, TRADE_SINGLE, LOOT_RAMPARTS,
        // stables (ch. 4)
        TOP_OF_MIDDLE_STAIRS, PROBE_GAPS, GOOD_GAP_GOLD, TRIPLE_GOLD, TRIPLE_CHESTS, MIDDLE_BOTTOM_CHEST, UPPER_CHESTS,
        // shared
        TRADE, LEAVE
    }

    /** Gold blocks mined from the pit corner, per side, in order. Side 1 skips bottom-right (intermediate). */
    public enum Gold { MID_1, MID_2, MID_3, TOP_LEFT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public static List<Gold> treasurePitGold(int side, boolean intermediate) {
        List<Gold> g = new ArrayList<>(J8.listOf(Gold.MID_1, Gold.MID_2, Gold.MID_3, Gold.TOP_LEFT, Gold.BOTTOM_LEFT, Gold.BOTTOM_RIGHT));
        if (intermediate && side == 1) g.remove(Gold.BOTTOM_RIGHT);
        return g;
    }

    /** Aggro rhythm while mining in sight of piglins (intermediate): mine, throw, mine, throw, mine, throw, place 2, throw. */
    public static final String RHYTHM = "MTMTMTPPT";

    /** Next action in the rhythm for action index i: 'M' mine, 'T' throw an ingot, 'P' place a block. */
    public static char rhythm(int i) {
        return RHYTHM.charAt(Math.floorMod(i, RHYTHM.length()));
    }

    /** Backup gold is crouch-mined aiming mid-up so the block drops straight into our inventory. */
    public static final boolean CROUCH_BACKUP_GOLD = true;

    /**
     * Treasure route. Beginner keeps the cover blocks; intermediate drops them. Optional steps (pearl hang for the
     * spawner, bottom route) are only appended when asked: they need render-distance tricks or fire res.
     */
    public static List<Step> treasure(boolean intermediate, boolean optionalSpawner, boolean optionalBottom) {
        List<Step> s = new ArrayList<>();
        s.add(Step.TAKE_THIRD_BASALT_BRIDGE);
        s.add(Step.PILLAR_3_HUG);
        if (!intermediate) s.add(Step.COVER_2_BLOCKS);
        s.add(Step.MINE_HIGH_GOLD_CRAFT_INGOTS);
        s.add(Step.DISTRACT_CROSSBOWS);
        s.add(Step.BRIDGE_GAP);
        s.add(Step.BUILD_POCKET);
        s.add(Step.WEAR_GOLD_HELMET);
        s.add(Step.CROSS_BRICK_BRIDGE);
        s.add(Step.OPEN_ABOVE_STAIRS);
        s.add(Step.MINE_BLOCKER_SEAL_BEHIND);
        s.add(Step.BRIDGE_RAMPARTS);
        s.add(Step.PIGLIN_PIT);
        s.add(Step.MINE_GOLD_SIDE_1);
        s.add(Step.MINE_GOLD_SIDE_2);
        s.add(Step.LOOT_DOUBLE);
        s.add(Step.BACKUP_GOLD_1);
        s.add(Step.LOOT_SINGLES);
        s.add(Step.BACKUP_GOLD_2);
        s.add(Step.TRADE_AT_PIT);
        if (optionalSpawner) s.add(Step.PEARL_HANG_SPAWNER);
        if (optionalBottom) s.add(Step.BOTTOM_ROUTE);
        s.add(Step.LEAVE);
        return s;
    }

    public enum HousingVariant { BEGINNER, INTERMEDIATE, LAVA }

    /** Lava variant when a large share of the netherrack floor cells we scanned carry lava. */
    public static final double LAVA_FLOOR_SHARE = 0.3;

    public static HousingVariant housingVariant(int floorCells, int lavaFloorCells, boolean intermediate) {
        if (floorCells > 0 && lavaFloorCells >= LAVA_FLOOR_SHARE * floorCells) return HousingVariant.LAVA;
        return intermediate ? HousingVariant.INTERMEDIATE : HousingVariant.BEGINNER;
    }

    /**
     * Housing routes from Couriway's videos. The Hard/Easy difficulty toggle the video uses to unstick piglins needs
     * a settings change mid-run, so it is replaced by UNSTICK_BY_MINING (mine the block under or beside a stuck
     * piglin so it drops into the pit).
     */
    public static List<Step> housing(HousingVariant v) {
        switch (v) {
            case INTERMEDIATE: return J8.listOf(Step.LOOT_TOP_TRIPLE, Step.LOOT_LEFT_RAMPART_TRIPLE, Step.BREAK_CHEST_AGGRO_THROW,
                    Step.BACK_OPENING, Step.ONE_BY_ONE, Step.GOLD_ELEVATOR, Step.TRADE, Step.LAVA_STRAGGLERS, Step.OUTSIDE_CHESTS, Step.LEAVE);
            case LAVA: return J8.listOf(Step.AGGRO_ONE_GOLD_ARMOR, Step.LAVA_BLOCK_GAP, Step.STAIR_2, Step.LOOT_ARMOR_CHEST,
                    Step.BLOCK_LOWER_LAVA, Step.CORNER_CHEST, Step.GOLD_ELEVATOR, Step.TRADE, Step.LAVA_STRAGGLERS, Step.OUTSIDE_CHESTS, Step.LEAVE);
            default: return J8.listOf(Step.HOUSING_ENTRY_BASALT_CUE, Step.LOOT_TOP_LARGE, Step.LOOT_TOP_SINGLE, Step.RIGHT_FORK_BLOCK_IN,
                    Step.DROP_PIGLINS, Step.DISTRACT_HALF_GOLD, Step.ONE_BY_ONE, Step.DIG_DOWN_3_STRIP_4, Step.HOUSING_PIT,
                    Step.MINE_WALL_GOLD, Step.T_TUNNEL_GOLD, Step.UNSTICK_BY_MINING, Step.TRADE_CORNER, Step.HOUSING_BOTTOM_CHEST,
                    Step.HOUSING_BACKUP_GOLD, Step.LEAVE);
        }
    }

    /** Wall gold mined in the strip tunnel is pushed away with a placed block so we do not pick the gold block back up. */
    public static final boolean PUSH_GOLD_ITEMS_AWAY = true;
    /** Trading corner: gold on the left, us on the right. */
    public static final boolean GOLD_LEFT_OF_US = true;
    /** Gold elevator carries gold blocks up in batches of 3, then 4. */
    public static final int[] ELEVATOR_BATCHES = {3, 4};

    /** Bridge: whole chalice first, then trade at a triple (or a single) while looting the ramparts. */
    public static List<Step> bridge(boolean leftTriple, boolean rightTriple) {
        boolean triple = leftTriple || rightTriple;
        return J8.listOf(Step.MINE_CHALICE, triple ? Step.TRADE_TRIPLE : Step.TRADE_SINGLE, Step.LOOT_RAMPARTS, Step.LEAVE);
    }

    /** Stables: start at the top of the middle stairs, probe the gaps, then gold, then chests. */
    public static List<Step> stables(Boolean leftGood, Boolean rightGood, boolean anyTriple) {
        List<Step> s = new ArrayList<>();
        s.add(Step.TOP_OF_MIDDLE_STAIRS);
        if (leftGood == null || rightGood == null) s.add(Step.PROBE_GAPS);
        boolean good = Boolean.TRUE.equals(leftGood) || Boolean.TRUE.equals(rightGood);
        for (BastionPriors.StablesStep p : BastionPriors.stablesRoute(good, anyTriple)) {
            switch (p) {
                case GOOD_GAP_GOLD: s.add(Step.GOOD_GAP_GOLD); break;
                case TRIPLE_GOLD: s.add(Step.TRIPLE_GOLD); break;
                case TRIPLE_CHESTS: s.add(Step.TRIPLE_CHESTS); break;
                case MIDDLE_BOTTOM_CHEST: s.add(Step.MIDDLE_BOTTOM_CHEST); break;
                case UPPER_CHESTS: s.add(Step.UPPER_CHESTS); break;
                default: break;
            }
        }
        s.add(Step.TRADE);
        s.add(Step.LEAVE);
        return s;
    }

    /** Index of the first step that trades (the trade camp waits for it); the route size if none. */
    public static int firstTradeIndex(List<Step> route) {
        for (int i = 0; i < route.size(); i++) {
            Step s = route.get(i);
            if (s == Step.TRADE || s == Step.TRADE_AT_PIT || s == Step.TRADE_CORNER || s == Step.TRADE_TRIPLE || s == Step.TRADE_SINGLE
                    || s == Step.GOLD_ELEVATOR || s == Step.HOUSING_PIT || s == Step.PIGLIN_PIT) return i;
        }
        return route.size();
    }

    /** Route for a scanned layout name; unknown layouts fall back to trade-and-leave. */
    public static List<Step> forLayout(String layout, boolean goldArmor, boolean intermediate) {
        switch (layout) {
            case "treasure": return treasure(intermediate, false, false);
            case "housing": return housing(intermediate ? HousingVariant.INTERMEDIATE : HousingVariant.BEGINNER);
            case "bridge": return bridge(false, false);
            case "stables": return stables(null, null, false);
            default: return J8.listOf(Step.TRADE, Step.LEAVE);
        }
    }

    /** Aggro gate for a step that breaks gold or a container: distract first when an undistracted piglin is near. */
    public static boolean needsDistractFirst(Step s, double nearestUndistracted) {
        boolean breaksGold = s == Step.MINE_HIGH_GOLD_CRAFT_INGOTS || s == Step.MINE_GOLD_SIDE_1 || s == Step.MINE_GOLD_SIDE_2
                || s == Step.BACKUP_GOLD_1 || s == Step.BACKUP_GOLD_2 || s == Step.MINE_CHALICE || s == Step.GOOD_GAP_GOLD
                || s == Step.TRIPLE_GOLD || s == Step.MINE_WALL_GOLD || s == Step.T_TUNNEL_GOLD || s == Step.HOUSING_BACKUP_GOLD
                || s == Step.GOLD_ELEVATOR;
        return breaksGold && !BastionPriors.mayBreakGold(nearestUndistracted);
    }
}
