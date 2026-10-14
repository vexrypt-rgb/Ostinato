package baritone.bastion;

import java.util.List;

/**
 * Routing priors from Couriway's bastion guide (ch. 3): where chests and gold usually are per layout, the housing
 * route with and without gold armor, and the piglin aggro tiers. Pure logic, no game classes.
 */
public final class BastionPriors {
    private BastionPriors() {}

    /** Piglin aggro tiers. NONE: neutral. T1: sees us within 16 without gold armor. T2: we broke gold or a container
     * within 16, or opened a container in their sight (lasts 30 s). T3: we hurt a piglin (nearby ones get T2). */
    public enum Tier { NONE, T1, T2, T3 }

    public static final double AGGRO_RANGE = 16;
    public static final int T2_TICKS = 30 * 20;
    /** Piglins only re-check line of sight once a second. */
    public static final int LOS_PERIOD = 20;
    /** After T3, gold on the floor is traded again only after 10 s. */
    public static final int FLOOR_GOLD_AFTER_T3 = 10 * 20;

    /** Tier a piglin at distance d ends up in, given what we did. */
    public static Tier tier(double d, boolean sees, boolean goldArmor, boolean brokeGoldOrContainer, boolean openedInSight, boolean hurtThis, boolean hurtNearby) {
        if (hurtThis) return Tier.T3;
        if (d <= AGGRO_RANGE && (brokeGoldOrContainer || hurtNearby || (openedInSight && sees))) return Tier.T2;
        if (d <= AGGRO_RANGE && sees && !goldArmor) return Tier.T1;
        return Tier.NONE;
    }

    /** Breaking gold or a container: only with no undistracted piglin within 16 (distract them with an ingot first). */
    public static boolean mayBreakGold(double nearestUndistracted) {
        return nearestUndistracted > AGGRO_RANGE;
    }

    /** Opening a container: fine unless an undistracted piglin within 16 sees us. */
    public static boolean mayOpen(double nearestUndistractedSeeing) {
        return nearestUndistractedSeeing > AGGRO_RANGE;
    }

    /** A LoS-gated move is safe in the window just after the piglin's once-a-second sight check. */
    public static boolean slipWindow(long ticksSinceLosCheck, int moveTicks) {
        return ticksSinceLosCheck >= 0 && ticksSinceLosCheck + moveTicks < LOS_PERIOD;
    }

    /** Will floor gold be picked up for trade now, given the ticks since we last hurt a piglin (-1 = never)? */
    public static boolean floorGoldTraded(long ticksSinceT3) {
        return ticksSinceT3 < 0 || ticksSinceT3 >= FLOOR_GOLD_AFTER_T3;
    }

    public enum Spot { TOP, RIGHT_TOP_CHESTS, RIGHT_BOTTOM_DOUBLE, LEFT_RAMPART, SOUL_SAND_CENTER, MID_GOLD, BACKUP_GOLD_LEFT }

    /** Housing route: armored goes top, chests, then trades the middle gold blocks; unarmored takes armor from the
     * top triple (or the bottom double) first. */
    public static List<Spot> housingRoute(boolean goldArmor) {
        return goldArmor
                ? J8.listOf(Spot.TOP, Spot.RIGHT_TOP_CHESTS, Spot.RIGHT_BOTTOM_DOUBLE, Spot.SOUL_SAND_CENTER, Spot.MID_GOLD, Spot.BACKUP_GOLD_LEFT)
                : J8.listOf(Spot.RIGHT_TOP_CHESTS, Spot.TOP, Spot.RIGHT_BOTTOM_DOUBLE, Spot.SOUL_SAND_CENTER, Spot.MID_GOLD, Spot.BACKUP_GOLD_LEFT);
    }

    /** Guaranteed chests: housing right rampart double+single at top and a double at the bottom = 5. */
    public static int guaranteedChests(String layout) {
        switch (layout) {
            case "housing": return 5;
            case "treasure": return 4; // 2 near the top of the right rampart + a double on the left
            case "bridge": return 2; // each rampart has at least a single
            case "stables": return 1; // middle lower section, bottom front
            default: return 0;
        }
    }

    /** Left housing rampart: ruin, single, or triple, each 1/3. Expected chest count. */
    public static double housingLeftExpected() {
        return (0 + 1 + 3) / 3.0;
    }

    /** Backup gold blocks on the housing left rampart: 1 halfway up, 4 at its bottom. */
    public static int housingBackupGoldBlocks() {
        return 5;
    }

    /** Treasure gold over the lava basin bridge. */
    public static int treasureBridgeGoldBlocks() {
        return 2;
    }

    /** Obsidian outer chests: a lantern means a chest there. */
    public static boolean outerChestLikely(boolean lantern) {
        return lantern;
    }

    /** Treasure cube obsidian chest offset from the cue, as {right, up} in the wall's frame (+1 right, +1 up).
     * 2x3 basalt mid-wall: chest above-left. Hanging lanterns on chains: chest below-right. Null if no cue. */
    public static int[] cubeChestOffset(boolean basaltMidWall, boolean chainLanterns) {
        if (basaltMidWall) return new int[]{-1, 1};
        if (chainLanterns) return new int[]{1, -1};
        return null;
    }
    // ---- Bridge (ch. 4): all gold is in the chalice at the end of the bridge; mine it all first, then trade at a triple rampart.
    public enum Rampart { SINGLE, DOUBLE, TRIPLE }

    /** Chests in a rampart section. */
    public static int chests(Rampart r) {
        return r == Rampart.SINGLE ? 1 : r == Rampart.DOUBLE ? 2 : 3;
    }

    /** Bridge ramparts are 50% single / 50% triple each: expected chests over both. */
    public static double bridgeExpectedChests() {
        return 2 * (0.5 * 1 + 0.5 * 3);
    }

    public enum BridgeStep { MINE_CHALICE, TRADE_LEFT_TRIPLE, TRADE_RIGHT_TRIPLE, TRADE_SINGLE, LOOT_OTHER }

    /** Bridge route: whole chalice first, then trade at a triple (left preferred if both) while looting; a double
     * single trades at a single rampart. */
    public static List<BridgeStep> bridgeRoute(boolean leftTriple, boolean rightTriple) {
        BridgeStep trade = leftTriple ? BridgeStep.TRADE_LEFT_TRIPLE : rightTriple ? BridgeStep.TRADE_RIGHT_TRIPLE : BridgeStep.TRADE_SINGLE;
        return J8.listOf(BridgeStep.MINE_CHALICE, trade, BridgeStep.LOOT_OTHER);
    }

    // ---- Stables (ch. 4): upper ramparts (3 sections, each single/double/triple), lower ramparts (left/right good or bad gap).

    /** Gold blocks behind chiseled polished blackstone about halfway down a triple upper rampart. */
    public static int stablesTripleGoldBlocks(Rampart upper) {
        return upper == Rampart.TRIPLE ? 4 : 0;
    }

    /** Upper rampart identification: a lantern at the front means a double; tallest is the triple; a diagonal
     * hallway is the single. */
    public static Rampart stablesUpper(boolean lanternFront, boolean tallest) {
        if (tallest) return Rampart.TRIPLE;
        return lanternFront ? Rampart.DOUBLE : Rampart.SINGLE;
    }

    /** Lower side gap: good = many piglins and hidden gold at the bottom; bad = a single chest near the bottom.
     * Cues: probe block open (true) means good; outside top gap 1 tall = good, 2 tall = bad; smooth back wall = good.
     * Returns null when no cue is known. */
    public static Boolean stablesGapGood(Boolean probeOpen, int outsideGapHeight, Boolean smoothBack) {
        if (probeOpen != null) return probeOpen;
        if (outsideGapHeight == 1) return true;
        if (outsideGapHeight == 2) return false;
        return smoothBack;
    }

    /** The middle lower section always has a chest at the very bottom front. */
    public static int stablesGuaranteedChests() {
        return 1;
    }

    public enum StablesStep { TOP_OF_MIDDLE_STAIRS, GOOD_GAP_GOLD, TRIPLE_GOLD, TRIPLE_CHESTS, MIDDLE_BOTTOM_CHEST, UPPER_CHESTS }

    /** Stables route: start at the top of the middle stairs; take a good gap's gold if there is one, else a triple's
     * 4 gold blocks; then chests. */
    public static List<StablesStep> stablesRoute(boolean anyGoodGap, boolean anyTriple) {
        if (anyGoodGap) return J8.listOf(StablesStep.TOP_OF_MIDDLE_STAIRS, StablesStep.GOOD_GAP_GOLD, StablesStep.MIDDLE_BOTTOM_CHEST, StablesStep.UPPER_CHESTS);
        if (anyTriple) return J8.listOf(StablesStep.TOP_OF_MIDDLE_STAIRS, StablesStep.TRIPLE_GOLD, StablesStep.TRIPLE_CHESTS, StablesStep.MIDDLE_BOTTOM_CHEST);
        return J8.listOf(StablesStep.TOP_OF_MIDDLE_STAIRS, StablesStep.MIDDLE_BOTTOM_CHEST, StablesStep.UPPER_CHESTS);
    }

    /** Ticks we try to close in on one piglin to hand it gold before giving up (s1161-b01 looped ~3 min). */
    public static final int CLOSE_IN_TICKS = 100;

    /** Give up closing in: too long overall, or it is close but out of sight (behind a wall) for 2 s. */
    public static boolean giveUpCloseIn(long startTick, long now, double dist, boolean visible) {
        long t = now - startTick;
        return t > CLOSE_IN_TICKS || (!visible && dist <= 8 && t > 40);
    }
}
