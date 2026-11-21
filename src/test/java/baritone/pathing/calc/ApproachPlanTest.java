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

package baritone.pathing.calc;

import baritone.Baritone;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.pathing.calc.bench.SyntheticWorld;
import baritone.pathing.movement.CalculationContext;
import baritone.utils.pathing.Favoring;
import net.minecraft.world.level.block.Blocks;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.PrintStream;
import java.util.Optional;

import static org.junit.Assert.*;

/**
 * Ostinato: Phase 2c part 2, walk-to-verify. An approach context plans only what an approaching swarm bot may do
 * while the lease fence denies every break and place: no breaking, no placing, and no fall over
 * {@link CalculationContext#APPROACH_MAX_FALL} blocks (p2c2 slice 1: a bot dropped ~87 blocks off a y=150 strip into
 * water). Each scenario first checks that the normal context does take the forbidden route, so the approach result is
 * the limit's doing.
 */
public class ApproachPlanTest {

    static final int Y = 40;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    private boolean breakWas;
    private boolean placeWas;

    @After
    public void restore() {
        if (placeWas || breakWas) {
            Baritone.settings().allowBreak.value = breakWas;
            Baritone.settings().allowPlace.value = placeWas;
        }
    }

    private void allowBreakAndPlace() {
        breakWas = Baritone.settings().allowBreak.value;
        placeWas = Baritone.settings().allowPlace.value;
        Baritone.settings().allowBreak.value = true;
        Baritone.settings().allowPlace.value = true;
    }

    static SyntheticWorld floor(int sx, int sz) {
        SyntheticWorld w = new SyntheticWorld(sx, sz);
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                w.set(x, 0, z, Blocks.BEDROCK.defaultBlockState());
                for (int y = 1; y < Y; y++) {
                    w.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        return w;
    }

    static Optional<IPath> plan(CalculationContext ctx, BetterBlockPos start, Goal goal) {
        AStarPathFinder f = new AStarPathFinder(start, start.x, start.y, start.z, goal, new Favoring(null, ctx), ctx);
        PrintStream out = System.out;
        System.setOut(new PrintStream(PrintStream.nullOutputStream()));
        try {
            return f.calculate0(5_000, 5_000);
        } finally {
            System.setOut(out);
        }
    }

    static boolean reaches(Optional<IPath> p, Goal goal) {
        return p.isPresent() && goal.isInGoal(p.get().getDest());
    }

    static int lowest(Optional<IPath> p, int startY) {
        int low = startY;
        if (p.isPresent()) {
            for (BetterBlockPos q : p.get().positions()) {
                low = Math.min(low, q.y);
            }
        }
        return low;
    }

    /** A 20-block tower standing in a 3-deep pool; the goal is on dry ground beyond the pool. */
    static SyntheticWorld towerInWater() {
        SyntheticWorld w = floor(40, 40);
        for (int x = 0; x < 30; x++) {
            for (int z = 0; z < 40; z++) {
                for (int y = Y - 3; y < Y; y++) {
                    w.set(x, y, z, Blocks.WATER.defaultBlockState());
                }
            }
        }
        for (int y = Y - 3; y < Y + 20; y++) {
            w.set(10, y, 10, Blocks.STONE.defaultBlockState());
        }
        return w;
    }

    @Test
    public void anApproachNeverPlansAFallOverTheLimitEvenOntoWater() {
        SyntheticWorld w = towerInWater();
        BetterBlockPos start = new BetterBlockPos(10, Y + 20, 10);
        Goal goal = new GoalBlock(35, Y, 20);
        Optional<IPath> normal = plan(PathBench.context(w), start, goal);
        assertTrue("precondition: the normal planner takes the fall into the water", reaches(normal, goal));
        assertTrue("precondition: that route drops more than the limit",
                start.y - lowest(normal, start.y) > CalculationContext.APPROACH_MAX_FALL);
        Optional<IPath> approach = plan(PathBench.context(w).restrictForApproach(), start, goal);
        assertFalse("no approach route off the tower", reaches(approach, goal));
        assertTrue("an approach never drops more than " + CalculationContext.APPROACH_MAX_FALL + " blocks",
                start.y - lowest(approach, start.y) <= CalculationContext.APPROACH_MAX_FALL);
    }

    @Test
    public void anApproachMayStillStepDownUpToTheLimit() {
        SyntheticWorld w = floor(40, 40);
        for (int x = 0; x < 10; x++) { // a 3-high ledge, then flat ground
            for (int z = 0; z < 40; z++) {
                for (int y = Y; y < Y + 3; y++) {
                    w.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        BetterBlockPos start = new BetterBlockPos(5, Y + 3, 20);
        Goal goal = new GoalBlock(30, Y, 20);
        assertTrue("a 3-block drop is within the limit", reaches(plan(PathBench.context(w).restrictForApproach(), start, goal), goal));
    }

    /** A pit: the start cell is walled in by stone {@code h} high on every side. */
    static SyntheticWorld pit(int h) {
        SyntheticWorld w = floor(30, 30);
        for (int x = 9; x <= 11; x++) {
            for (int z = 9; z <= 11; z++) {
                if (x == 10 && z == 10) {
                    continue;
                }
                for (int y = Y; y < Y + h; y++) {
                    w.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        return w;
    }

    @Test
    public void anApproachNeverPlansAPlaceToGetOut() {
        allowBreakAndPlace();
        Baritone.settings().allowBreak.value = false; // only placing could get the bot out of this pit
        SyntheticWorld w = pit(2);
        BetterBlockPos start = new BetterBlockPos(10, Y, 10);
        Goal goal = new GoalBlock(25, Y, 25);
        assertTrue("precondition: the normal planner pillars out", reaches(plan(PathBench.context(w), start, goal), goal));
        assertFalse("an approach never places", reaches(plan(PathBench.context(w).restrictForApproach(), start, goal), goal));
    }

    @Test
    public void anApproachNeverPlansABreakToGetOut() {
        allowBreakAndPlace();
        Baritone.settings().allowPlace.value = false; // only breaking could get the bot out of this pit
        SyntheticWorld w = pit(6);
        BetterBlockPos start = new BetterBlockPos(10, Y, 10);
        Goal goal = new GoalBlock(25, Y, 25);
        assertTrue("precondition: the normal planner breaks out", reaches(plan(PathBench.context(w), start, goal), goal));
        assertFalse("an approach never breaks", reaches(plan(PathBench.context(w).restrictForApproach(), start, goal), goal));
    }

    @Test
    public void theNormalContextIsUnchanged() {
        CalculationContext c = PathBench.context(floor(8, 8));
        assertFalse(c.approach);
        assertEquals(Integer.MAX_VALUE, c.approachMaxFall);
        assertEquals(Baritone.settings().allowBreak.value, c.allowBreak);
    }
}
