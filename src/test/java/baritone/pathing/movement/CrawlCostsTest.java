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
import org.junit.Test;

import static baritone.pathing.movement.CrawlCosts.*;
import static org.junit.Assert.*;

/** Ostinato: crawl pose and 1-high tunnel costs. */
public class CrawlCostsTest {

    private static final double PENALTY = 0.5; // blockBreakAdditionalPenalty default

    /** Mining ticks as planned: DigTime (whole ticks + the 6 tick gap) + penalty, with the right tool. */
    private static double mine(double toolSpeed, double hardness) {
        return mine(toolSpeed, 0, hardness);
    }

    private static double mine(double toolSpeed, int efficiency, double hardness) {
        return DigTime.ticks(DigTime.progress(toolSpeed, efficiency, hardness, true), 6) + PENALTY;
    }

    private static double[] cells(int n, double m) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, m);
        return a;
    }

    @Test
    public void poseFollowsUpdatePlayerPose() {
        assertEquals(Pose.STANDING, poseFor(2, false));
        assertEquals(Pose.CROUCHING, poseFor(2, true));
        assertEquals(Pose.CROUCHING, poseFor(1.6, false)); // under a 1.5+ gap the game crouches, not crawls
        assertEquals(Pose.SWIMMING, poseFor(1.0, false));  // 1-high: crawl
        assertEquals(Pose.SWIMMING, poseFor(1.0, true));
        assertEquals(Pose.NONE, poseFor(0.5, false));
    }

    @Test
    public void trapdoorHalves() {
        // a closed bottom-half trapdoor in the head cell leaves 1 + 0 headroom: crawl
        assertTrue(forcesCrawl(trapdoorBottom(false, false)));
        // a closed top-half one leaves 1.8125, more than a standing player needs
        assertFalse(forcesCrawl(trapdoorBottom(false, true)));
        assertEquals(Pose.STANDING, poseFor(headroom(trapdoorBottom(false, true)), false));
        // open: a side panel, no headroom lost, and a centred player clears it
        assertFalse(forcesCrawl(trapdoorBottom(true, false)));
        assertTrue(openTrapdoorClearsCentredPlayer());
    }

    @Test
    public void headBlocksThatForceCrawl() {
        assertTrue(forcesCrawl(0));          // full block
        assertTrue(forcesCrawl(0.0));        // bottom slab (0..0.5)
        assertTrue(forcesCrawl(0.49));
        assertFalse(forcesCrawl(0.5));       // top slab: 1.5 of room, the game crouches
        assertFalse(forcesCrawl(Double.NaN)); // air
        assertFalse(forcesCrawl(1));
    }

    @Test
    public void crawlIsSneakSpeed() {
        // modifyInput scales by SNEAKING_SPEED (0.3) exactly as sneaking does
        assertEquals(ActionCosts.WALK_ONE_BLOCK_COST / 0.3, crawlOneBlockCost(DEFAULT_SNEAK_FACTOR), 1e-9);
        assertEquals(15.44, crawlOneBlockCost(DEFAULT_SNEAK_FACTOR), 0.01);
        assertEquals(ActionCosts.SNEAK_ONE_BLOCK_COST, crawlOneBlockCost(DEFAULT_SNEAK_FACTOR), 0.1);
        // Swift Sneak III: 0.3 + 3 * 0.15
        assertEquals(ActionCosts.WALK_ONE_BLOCK_COST / 0.75, crawlOneBlockCost(0.75), 1e-9);
        assertTrue(sprintCrawlOneBlockCost(0.3) < crawlOneBlockCost(0.3));
        // clamped
        assertEquals(ActionCosts.WALK_ONE_BLOCK_COST, crawlOneBlockCost(5), 1e-9);
    }

    @Test
    public void breakEven() {
        assertEquals(10.81, breakEvenHeadMine(DEFAULT_SNEAK_FACTOR), 0.01);
    }

    private static double crawl(int length, double m) {
        return tunnelCost(2 * m, cells(length - 2, m), 2 * m, crawlOneBlockCost(DEFAULT_SNEAK_FACTOR), 10);
    }

    @Test
    public void tunnelCostAddsUp() {
        double m = 10;
        double c = crawl(4, m);
        double expect = ActionCosts.WALK_ONE_BLOCK_COST + 20 + ENTRY_INTERACT_TICKS + 10 + 2 * (crawlOneBlockCost(0.3) + 10) + crawlOneBlockCost(0.3) + 20;
        assertEquals(expect, c, 1e-9);
        assertTrue(tunnelCost(ActionCosts.COST_INF, cells(2, 1), 1, 15, 0) >= ActionCosts.COST_INF);
        assertTrue(tunnelCost(1, new double[]{1, ActionCosts.COST_INF}, 1, 15, 0) >= ActionCosts.COST_INF);
    }

    @Test
    public void fastToolsKeepWalking() {
        // stone with a diamond pickaxe: 6 + 6 + 0.5 = 12.5 a block, just too slow to keep a sprint, and the plain
        // crawl is slower than walking: 2-high it is. Same for netherrack with a wooden pick (6 ticks).
        for (double m : new double[]{mine(8, 1.5), mine(2, 0.4)}) {
            assertEquals(12.5, m, 1e-9);
            assertTrue(crawl(16, m) > walkTunnelCost(m, m, 16));
            assertTrue(sprintCrawl(16, m) > walkTunnelCost(m, m, 16));
        }
        // instant breaks (netherrack with gold, 1 tick, no gap): walking is far faster
        double g = mine(12, 0.4);
        assertEquals(1 + PENALTY, g, 1e-9);
        assertTrue(sprintCrawl(16, g) > walkTunnelCost(g, g, 16));
    }

    @Test
    public void slowToolsCrawl() {
        // stone with a stone pickaxe (4), wooden (2); deepslate / end stone (3.0) with iron (6); netherrack by hand
        double[][] cases = {{4, 1.5}, {2, 1.5}, {6, 3.0}};
        for (double[] k : cases) {
            double m = mine(k[0], k[1]);
            assertTrue(crawl(16, m) < walkTunnelCost(m, m, 16));
        }
        double hand = DigTime.ticks(DigTime.progress(1, 0, 0.4, false), 6) + PENALTY;
        assertTrue(crawl(16, hand) < walkTunnelCost(hand, hand, 16));
    }

    @Test
    public void setupMakesShortTunnelsLose() {
        // just past break even per block, the setup is not paid back over 4 blocks but is over 16
        double m = breakEvenHeadMine(0.3) + 3;
        assertTrue(crawl(4, m) > walkTunnelCost(m, m, 4));
        assertTrue(crawl(16, m) < walkTunnelCost(m, m, 16));
    }

    // ---- Ostinato: sprint-crawl ----

    private static final double CRAWL = crawlOneBlockCost(DEFAULT_SNEAK_FACTOR);
    private static final double SPRINT = sprintCrawlOneBlockCost(DEFAULT_SNEAK_FACTOR);

    private static double sprintCrawl(int length, double m) {
        return sprintTunnelCost(2 * m, cells(length - 2, m), m, m, CRAWL, SPRINT, 10);
    }

    /** What the planner pays for a tunnel stretch: the cheapest of 2-high, crawl and sprint-crawl. */
    private static double best(int length, double m) {
        return Math.min(walkTunnelCost(m, m, length), Math.min(crawl(length, m), sprintCrawl(length, m)));
    }

    @Test
    public void sprintCrawlIsThirtyPercentFaster() {
        assertEquals(11.88, SPRINT, 0.01);
        assertEquals(CRAWL / 1.3, SPRINT, 1e-9);
    }

    @Test
    public void sprintSurvivesOnlyBlocksBrokenInTime() {
        assertTrue(keepsSprint(0, SPRINT));
        assertTrue(keepsSprint(SPRINT, SPRINT));
        assertFalse(keepsSprint(SPRINT + 0.01, SPRINT));
    }

    @Test
    public void sprintTunnelHidesMining() {
        double m = 5;
        double expect = ActionCosts.WALK_ONE_BLOCK_COST + 10 + 5 + SPRINT_ENTRY_INTERACT_TICKS + 10 + SPRINT + 2 * SPRINT + SPRINT + 5;
        assertEquals(expect, sprintCrawl(5, m), 1e-9);
    }

    @Test
    public void slowBlockEndsTheSprintForGood() {
        double[] c = {1, 1, 30, 1, 1};
        double cost = sprintTunnelCost(2, c, 1, 1, CRAWL, SPRINT, 0);
        double expect = ActionCosts.WALK_ONE_BLOCK_COST + 2 + 1 + SPRINT_ENTRY_INTERACT_TICKS
                + SPRINT + SPRINT                // first cell, then cell 2 mined on the run
                + (CRAWL + 30)                   // bump: sprint gone
                + 2 * (CRAWL + 1)                // can't restart while crawling
                + (CRAWL + 1) + 1;               // exit feet, exit head
        assertEquals(expect, cost, 1e-9);
        assertTrue(sprintTunnelCost(2, new double[]{1, ActionCosts.COST_INF}, 1, 1, CRAWL, SPRINT, 0) >= ActionCosts.COST_INF);
    }

    @Test
    public void sprintCrawlBeatsWalkingWhenEveryBlockKeepsUp() {
        // netherrack with stone/diamond picks (2-3 ticks + gap) and stone with diamond Efficiency I (5 + gap)
        for (double m : new double[]{mine(4, 0.4), mine(8, 0.4), mine(8, 1, 1.5)}) {
            assertTrue(keepsSprint(m, SPRINT));
            assertTrue(sprintCrawl(16, m) < crawl(16, m));
            assertTrue(sprintCrawl(16, m) < walkTunnelCost(m, m, 16));
        }
    }

    @Test
    public void slowToolsStillTunnelAndPickTheFastestOption() {
        // stone with stone and wooden picks, deepslate with wood, stone by hand: the sprint is lost at the first slow
        // block, but the tunnel is still costed (finite) and the cheapest option is never worse than 2-high
        double[] ms = {mine(4, 1.5), mine(2, 1.5), mine(2, 3.0), DigTime.ticks(DigTime.progress(1, 0, 1.5, false), 6) + PENALTY};
        for (double m : ms) {
            assertFalse(keepsSprint(m, SPRINT));
            assertTrue(sprintCrawl(16, m) < ActionCosts.COST_INF);
            assertTrue(best(16, m) <= walkTunnelCost(m, m, 16));
            assertTrue(best(16, m) < walkTunnelCost(m, m, 16) * 0.9); // a real saving, not a detour bait
            assertTrue(Math.abs(sprintCrawl(16, m) - crawl(16, m)) < 2 * m);
        }
        // slower tools never make a crawl tunnel relatively worse: the saving grows with mining time
        double prev = -1;
        for (double m = 12; m < 200; m += 10) {
            double saving = walkTunnelCost(m, m, 16) - best(16, m);
            assertTrue(saving >= prev);
            prev = saving;
        }
    }
}
