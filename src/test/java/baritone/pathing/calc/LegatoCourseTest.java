/*
 * Ostinato Legato: every leg of the live course plans in every mode, and what smoothing does to each, headless.
 */
package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.legato.LegatoCourse;
import baritone.pathing.legato.LegatoFailures;
import baritone.pathing.legato.LegatoSmoother;
import net.minecraft.world.level.block.Blocks;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Locale;

import static baritone.pathing.calc.LegatoSearchTest.Y;
import static org.junit.Assert.*;

public class LegatoCourseTest {

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @After
    public void offAgain() {
        Baritone.settings().legato.value = false;
        Baritone.settings().legatoSearch.value = false;
        Baritone.settings().legatoSmoothing.value = true;
        LegatoFailures.clear();
    }

    static SyntheticWorld course() {
        SyntheticWorld w = LegatoSearchTest.floor(LegatoCourse.SIZE + 1, LegatoCourse.SIZE + 1);
        for (int x = 0; x <= LegatoCourse.SIZE; x++) {
            for (int z = 0; z <= LegatoCourse.SIZE; z++) {
                if (LegatoCourse.wall(x, z)) {
                    LegatoSearchTest.wall(w, x, z);
                }
            }
        }
        return w;
    }

    @Test
    public void startsAndGoalsAreOpen() {
        for (LegatoCourse.Leg leg : LegatoCourse.LEGS) {
            assertFalse(leg.name(), LegatoCourse.wall(leg.sx(), leg.sz()));
            assertFalse(leg.name(), LegatoCourse.wall(leg.gx(), leg.gz()));
            assertTrue(LegatoCourse.solid(leg.sx(), LegatoCourse.F, leg.sz()));
            assertFalse(LegatoCourse.solid(leg.sx(), LegatoCourse.F + 1, leg.sz()));
            assertNotEquals("off the 45 degree grid", Math.abs(leg.gx() - leg.sx()), Math.abs(leg.gz() - leg.sz()));
        }
    }

    @Test
    public void everyLegPlansInEveryMode() {
        SyntheticWorld w = course();
        for (LegatoCourse.Leg leg : LegatoCourse.LEGS) {
            BetterBlockPos start = new BetterBlockPos(leg.sx(), Y, leg.sz());
            GoalBlock goal = new GoalBlock(leg.gx(), Y, leg.gz());
            Path grid = LegatoSearchTest.search(w, start, goal, false);
            Path theta = LegatoSearchTest.search(w, start, goal, true);
            LegatoSearchTest.allStepsWalkable(w, theta);
            LegatoSmoother.Plan smooth = LegatoSearchTest.smoothed(w, grid);
            LegatoSmoother.Plan both = LegatoSearchTest.smoothed(w, theta);
            double straight = Math.hypot(leg.gx() - leg.sx(), leg.gz() - leg.sz());
            System.out.printf(Locale.ROOT, "LEGATO course %s: straight %.1f | A len %.1f pos %d | B len %.1f pos %d | C len %.1f pos %d | D len %.1f pos %d%n",
                    leg.name(), straight, LegatoSearchTest.length(grid), grid.positions().size(), LegatoSearchTest.length(smooth), smooth.size,
                    LegatoSearchTest.length(theta), theta.positions().size(), LegatoSearchTest.length(both), both.size);
            assertTrue(LegatoSearchTest.length(smooth) <= LegatoSearchTest.length(grid) + 1e-9);
            assertTrue(LegatoSearchTest.length(both) <= LegatoSearchTest.length(theta) + 1e-9);
        }
    }
}
