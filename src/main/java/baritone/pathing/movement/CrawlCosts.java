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

package baritone.pathing.movement;

import baritone.api.pathing.movement.ActionCosts;

/**
 * Ostinato: the pure numbers behind 1-high (crawl) tunnelling, kept free of the world so they can be unit tested.
 * <p>
 * Vanilla 26.3, from the decompiled source:
 * <ul>
 *     <li>{@code Player.updatePlayerPose}: the desired pose (STANDING, or CROUCHING with shift) is used if it fits,
 *     else CROUCHING if that fits, else SWIMMING. So any time neither a 1.8 nor a 1.5 tall box fits, the player
 *     crawls; no input is needed. Crawl ends by itself the tick a standing box fits again.</li>
 *     <li>{@code LocalPlayer.modifyInput}: {@code isMovingSlowly() = isCrouching() || isVisuallyCrawling()} scales
 *     the input by the SNEAKING_SPEED attribute (0.3, raised by Swift Sneak), exactly like sneaking.</li>
 *     <li>{@code LocalPlayer.canStartSprinting} refuses while moving slowly (out of water), but
 *     {@code shouldStopRunSprinting} does not stop a sprint that was already running.</li>
 *     <li>{@code Player.getDestroySpeed}: only !onGround (/5) and eyes in water change mining speed. A crawling
 *     player is on the ground, so prone mining is as fast as standing mining.</li>
 * </ul>
 */
public final class CrawlCosts implements ActionCosts {

    private CrawlCosts() {}

    /** Pose box heights (Player.POSES / EntityDimensions). */
    public static final double STANDING_HEIGHT = 1.8;
    public static final double CROUCHING_HEIGHT = 1.5;
    public static final double SWIMMING_HEIGHT = 0.6;

    /** Default SNEAKING_SPEED attribute value. */
    public static final double DEFAULT_SNEAK_FACTOR = 0.3;

    /**
     * Placing the trapdoor (or gate), opening it, stepping under it and closing it: three right clicks, each with a
     * look-at and the 4 tick right click delay, plus a tick for the pose to change.
     */
    public static final double ENTRY_INTERACT_TICKS = 3 * 4 + 1;

    public enum Pose { STANDING, CROUCHING, SWIMMING, NONE }

    /**
     * The pose vanilla gives a player who is not swimming, not sneaking, with {@code headroom} blocks of free space
     * above the feet (Player.updatePlayerPose). NONE means even the swimming box doesn't fit (vanilla then keeps
     * whatever pose it had, and the player is suffocating).
     */
    public static Pose poseFor(double headroom, boolean sneaking) {
        double eps = 1.0E-7; // the vanilla box is deflated by this
        boolean stand = headroom >= STANDING_HEIGHT - 2 * eps;
        boolean crouch = headroom >= CROUCHING_HEIGHT - 2 * eps;
        boolean swim = headroom >= SWIMMING_HEIGHT - 2 * eps;
        if (!swim) {
            return Pose.NONE;
        }
        if (sneaking ? crouch : stand) {
            return sneaking ? Pose.CROUCHING : Pose.STANDING;
        }
        return crouch ? Pose.CROUCHING : Pose.SWIMMING;
    }

    /**
     * Headroom above feet standing on the floor of a cell whose head cell (one up) has collision starting at
     * {@code headCellBottom} (0..1 inside that cell); {@code Double.NaN} or anything >= 1 means the head cell is empty
     * and the cell above it is solid (a plain 2-high space).
     */
    public static double headroom(double headCellBottom) {
        if (Double.isNaN(headCellBottom) || headCellBottom >= 1) {
            return 2;
        }
        return 1 + Math.max(0, headCellBottom);
    }

    /** True if a head cell whose collision starts this far up forces the crawl pose on a player below it. */
    public static boolean forcesCrawl(double headCellBottom) {
        return poseFor(headroom(headCellBottom), false) == Pose.SWIMMING;
    }

    /** Trapdoor shapes, as fractions of the block (TrapDoorBlock: 3/16 thick). */
    public static final double TRAPDOOR_THICKNESS = 3 / 16.0;

    /** Where the collision of a trapdoor starts inside its own cell; NaN if open (a side panel, no headroom loss). */
    public static double trapdoorBottom(boolean open, boolean topHalf) {
        if (open) {
            return Double.NaN;
        }
        return topHalf ? 1 - TRAPDOOR_THICKNESS : 0;
    }

    /**
     * An open trapdoor is a 3/16 panel on one side of the cell; the player box is 0.6 wide, centred, so standing in
     * the middle of the cell it clears the panel by 0.2 - 3/16.
     */
    public static boolean openTrapdoorClearsCentredPlayer() {
        return 0.5 - 0.3 > TRAPDOOR_THICKNESS;
    }

    /** Ticks to move one block crawling: walking scaled by the sneaking speed factor (exactly as sneaking). */
    public static double crawlOneBlockCost(double sneakFactor) {
        double f = Math.max(0.05, Math.min(1, sneakFactor));
        return WALK_ONE_BLOCK_COST / f;
    }

    /**
     * Ostinato: sprint-crawl. Sprint is a +30% MOVEMENT_SPEED modifier and survives the pose change; input is still
     * scaled by SNEAKING_SPEED, so it is crawl / 1.3 (11.88 ticks per block at the default 0.3).
     * <p>
     * From LocalPlayer.aiStep (26.3): canStartSprinting needs {@code !isMovingSlowly() || isUnderWater()}, so a
     * crawling player can never START a sprint; a running one is only stopped by shouldStopRunSprinting:
     * <ul>
     *     <li>!isSprintingPossible: food 6 or less (FoodData.hasEnoughFood), Blindness (isMobilityRestricted),
     *     shallow water;</li>
     *     <li>no forward impulse: releasing W, i.e. ANY stop, including stopping to mine;</li>
     *     <li>horizontalCollision that is not minor (minor = blocked less than 8 degrees off the wanted direction while
     *     still moving). Running head-on into an unmined block is a full stop: major.</li>
     * </ul>
     * Holding left click to mine does not touch the sprint (only attacking an entity does, Player.attack), nor does
     * the sneak key or right clicking a block. So the sprint lasts as long as each next block is broken before we
     * reach it, i.e. a block's mining time is at most one sprint-crawl block.
     */
    public static double sprintCrawlOneBlockCost(double sneakFactor) {
        return crawlOneBlockCost(sneakFactor) / 1.3;
    }

    /** Ostinato: place and open the trapdoor; the close is clicked on the run, in the stride under it. */
    public static final double SPRINT_ENTRY_INTERACT_TICKS = 2 * 4 + 1;

    /** Ostinato: can a block taking {@code mine} ticks be broken ahead of us without dropping the sprint. */
    public static boolean keepsSprint(double mine, double sprintStep) {
        return mine <= sprintStep;
    }

    /**
     * Ostinato: the sprint-crawl variant of {@link #tunnelCost}. Entry: mine the entry cell and the first crawl
     * cell's feet from the start (standing), place and open the trapdoor, then sprint under it and close it in
     * passing, so the sprint carries into the crawl. Every later block is mined while crawling towards it; one that
     * takes longer than a sprint-crawl block makes us bump into it, which ends the sprint for the rest of the tunnel
     * (it can't be restarted while crawling).
     *
     * @param cellMine feet mining of the crawl cells, the first one is mined from the start
     */
    public static double sprintTunnelCost(double entryMine, double[] cellMine, double exitFeet, double exitHead,
                                          double crawlStep, double sprintStep, double itemCost) {
        if (entryMine >= COST_INF || exitFeet >= COST_INF || exitHead >= COST_INF || cellMine.length == 0) {
            return COST_INF;
        }
        for (double m : cellMine) {
            if (m >= COST_INF) {
                return COST_INF;
            }
        }
        double c = WALK_ONE_BLOCK_COST + entryMine + cellMine[0] + SPRINT_ENTRY_INTERACT_TICKS + itemCost;
        c += sprintStep; // entry cell to first crawl cell, already open
        boolean sprinting = true;
        for (int i = 1; i < cellMine.length; i++) {
            if (sprinting && keepsSprint(cellMine[i], sprintStep)) {
                c += sprintStep; // mined on the way, hidden
            } else {
                sprinting = false;
                c += crawlStep + cellMine[i];
            }
        }
        if (sprinting && keepsSprint(exitFeet, sprintStep)) {
            c += sprintStep;
        } else {
            c += crawlStep + exitFeet;
        }
        return c + exitHead; // the head of the exit cell is mined from inside it, then we stand
    }

    /**
     * Total cost of one crawl tunnel move covering {@code length} blocks in a straight line: walk into the entry cell
     * (both blocks mined), place and work the trapdoor, crawl through {@code length - 2} cells mining only the feet
     * block, then crawl into the exit cell (both blocks mined) and stand.
     *
     * @param entryMine mining ticks of the entry cell (feet + head)
     * @param cellMine  mining ticks of each crawl cell's feet block (length - 2 of them)
     * @param exitMine  mining ticks of the exit cell (feet + head)
     */
    public static double tunnelCost(double entryMine, double[] cellMine, double exitMine, double crawlStep, double itemCost) {
        if (entryMine >= COST_INF || exitMine >= COST_INF) {
            return COST_INF;
        }
        double c = WALK_ONE_BLOCK_COST + entryMine + ENTRY_INTERACT_TICKS + itemCost;
        for (double m : cellMine) {
            if (m >= COST_INF) {
                return COST_INF;
            }
            c += crawlStep + m;
        }
        c += crawlStep + exitMine;
        return c;
    }

    /** What the same stretch costs as plain 2-high tunnelling (one MovementTraverse per block). */
    public static double walkTunnelCost(double feetMine, double headMine, int length) {
        return length * (WALK_ONE_BLOCK_COST + feetMine + headMine);
    }

    /**
     * Per-block head mining time above which crawling a long tunnel beats walking one: crawling skips the head block
     * but moves slower.
     */
    public static double breakEvenHeadMine(double sneakFactor) {
        return crawlOneBlockCost(sneakFactor) - WALK_ONE_BLOCK_COST;
    }
}
