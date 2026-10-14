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

    private static final double PENALTY = 2; // blockBreakAdditionalPenalty default

    /** Baritone's mining ticks: 1/strVsBlock + penalty, strVsBlock = speed / hardness / 30 with the right tool. */
    private static double mine(double toolSpeed, double hardness) {
        return hardness * 30 / toolSpeed + PENALTY;
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
        // stone with a diamond pickaxe (speed 8): head mining is cheaper than the slower crawl
        double m = mine(8, 1.5);
        assertTrue(crawl(16, m) > walkTunnelCost(m, m, 16));
        // netherrack with a diamond or wooden pickaxe: walk
        assertTrue(crawl(16, mine(8, 0.4)) > walkTunnelCost(mine(8, 0.4), mine(8, 0.4), 16));
        assertTrue(crawl(16, mine(2, 0.4)) > walkTunnelCost(mine(2, 0.4), mine(2, 0.4), 16));
    }

    @Test
    public void slowToolsCrawl() {
        // stone with a stone pickaxe (4), wooden (2); deepslate / end stone (3.0) with iron (6); netherrack by hand (100/s)
        double[][] cases = {{4, 1.5}, {2, 1.5}, {6, 3.0}};
        for (double[] k : cases) {
            double m = mine(k[0], k[1]);
            assertTrue(crawl(16, m) < walkTunnelCost(m, m, 16));
        }
        double hand = 0.4 * 100 + PENALTY;
        assertTrue(crawl(16, hand) < walkTunnelCost(hand, hand, 16));
    }

    @Test
    public void setupMakesShortTunnelsLose() {
        // just past break even per block, the setup is not paid back over 4 blocks but is over 16
        double m = breakEvenHeadMine(0.3) + 3;
        assertTrue(crawl(4, m) > walkTunnelCost(m, m, 4));
        assertTrue(crawl(16, m) < walkTunnelCost(m, m, 16));
    }
}
