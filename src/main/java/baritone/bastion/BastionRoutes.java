package baritone.bastion;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-layout bastion routes built from Couriway's guide (ch. 3, ch. 4) and his treasure videos. Each step names an
 * objective; BastionProcess resolves it to blocks with the scanned layout. Pure logic.
 * Video route notes for housing are still to come: {@link #housing} keeps the ch. 3 order until then.
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
        // housing (ch. 3)
        GET_ARMOR, HOUSING_TOP, HOUSING_RIGHT_CHESTS, HOUSING_BOTTOM_DOUBLE, HOUSING_SOUL_SAND_CHEST, HOUSING_LEFT_RAMPART,
        HOUSING_MID_GOLD, HOUSING_BACKUP_GOLD,
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
        List<Gold> g = new ArrayList<>(List.of(Gold.MID_1, Gold.MID_2, Gold.MID_3, Gold.TOP_LEFT, Gold.BOTTOM_LEFT, Gold.BOTTOM_RIGHT));
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

    /** Housing (ch. 3 order; video notes pending). */
    public static List<Step> housing(boolean goldArmor) {
        List<Step> s = new ArrayList<>();
        if (!goldArmor) s.add(Step.GET_ARMOR); // top triple or bottom double
        for (BastionPriors.Spot p : BastionPriors.housingRoute(goldArmor)) {
            switch (p) {
                case TOP: if (goldArmor) s.add(Step.HOUSING_TOP); break;
                case RIGHT_TOP_CHESTS: s.add(Step.HOUSING_RIGHT_CHESTS); break;
                case RIGHT_BOTTOM_DOUBLE: s.add(Step.HOUSING_BOTTOM_DOUBLE); break;
                case SOUL_SAND_CENTER: s.add(Step.HOUSING_SOUL_SAND_CHEST); break;
                case MID_GOLD: s.add(Step.HOUSING_MID_GOLD); break;
                case BACKUP_GOLD_LEFT: s.add(Step.HOUSING_BACKUP_GOLD); break;
                default: break;
            }
        }
        s.add(Step.HOUSING_LEFT_RAMPART);
        s.add(Step.TRADE);
        s.add(Step.LEAVE);
        return s;
    }

    /** Bridge: whole chalice first, then trade at a triple (or a single) while looting the ramparts. */
    public static List<Step> bridge(boolean leftTriple, boolean rightTriple) {
        boolean triple = leftTriple || rightTriple;
        return List.of(Step.MINE_CHALICE, triple ? Step.TRADE_TRIPLE : Step.TRADE_SINGLE, Step.LOOT_RAMPARTS, Step.LEAVE);
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

    /** Route for a scanned layout name; unknown layouts fall back to trade-and-leave. */
    public static List<Step> forLayout(String layout, boolean goldArmor, boolean intermediate) {
        switch (layout) {
            case "treasure": return treasure(intermediate, false, false);
            case "housing": return housing(goldArmor);
            case "bridge": return bridge(false, false);
            case "stables": return stables(null, null, false);
            default: return List.of(Step.TRADE, Step.LEAVE);
        }
    }

    /** Aggro gate for a step that breaks gold or a container: distract first when an undistracted piglin is near. */
    public static boolean needsDistractFirst(Step s, double nearestUndistracted) {
        boolean breaksGold = s == Step.MINE_HIGH_GOLD_CRAFT_INGOTS || s == Step.MINE_GOLD_SIDE_1 || s == Step.MINE_GOLD_SIDE_2
                || s == Step.BACKUP_GOLD_1 || s == Step.BACKUP_GOLD_2 || s == Step.MINE_CHALICE || s == Step.GOOD_GAP_GOLD
                || s == Step.TRIPLE_GOLD || s == Step.HOUSING_MID_GOLD || s == Step.HOUSING_BACKUP_GOLD;
        return breaksGold && !BastionPriors.mayBreakGold(nearestUndistracted);
    }
}
