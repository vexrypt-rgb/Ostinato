package baritone.maze;

import baritone.api.maze.IMazeFurnisher;
import baritone.api.maze.MazeFurnishJob;
import baritone.api.maze.MazeFurnishJob.Step;
import baritone.api.maze.MazeFurnishJob.StepKind;
import baritone.api.maze.MazeFurnishers;
import baritone.maze.BlockProbe.BlockKind;
import baritone.maze.LootPlanner.LootPlacement;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MazeFurnishPlannerTest {

    private static RoomPlan roomWithLoot() {
        for (int i = 0; i < 200; i++) {
            RoomPlan plan = MazePlanner.layout(2000L + i, 0, 0, 8, 20, 8);
            if (!LootPlanner.plan(plan, LootPlanner.PlacedLedger.EMPTY).isEmpty()) {
                return plan;
            }
        }
        throw new AssertionError("no room with loot");
    }

    /** The dug room as the world sees it: interior air, shell solid, plus whatever extra holes are given. */
    private static BlockProbe dug(RoomPlan plan, int[]... holes) {
        return (x, y, z) -> {
            for (int[] h : holes) {
                if (h[0] == x && h[1] == y && h[2] == z) {
                    return BlockKind.AIR;
                }
            }
            int rx = x - plan.originX, ry = y - plan.originY, rz = z - plan.originZ;
            if (plan.inRelative(rx, ry, rz) && plan.role(rx, ry, rz) == BlockRole.AIR) {
                return BlockKind.AIR;
            }
            return BlockKind.SOLID;
        };
    }

    @Test
    public void survivalJobLightsContainersFillsAndNoSealWhenIntact() {
        RoomPlan plan = roomWithLoot();
        List<LootPlacement> loot = LootPlanner.plan(plan, LootPlanner.PlacedLedger.EMPTY);
        MazeFurnishJob job = MazeFurnishPlanner.plan(plan, loot, dug(plan), true);
        assertEquals(1, job.steps(StepKind.LIGHT).stream().filter(s -> s.blockIds().equals(MazeFurnishPlanner.lightIds(plan.theme))).toList().size()); // Ostinato: other LIGHT-kind steps are cladding
        assertTrue(job.steps(StepKind.LIGHT).size() > 100);
        assertEquals(loot.size(), job.steps(StepKind.CONTAINER).size());
        assertEquals(loot.size(), job.steps(StepKind.FILL).size());
        assertTrue(job.steps(StepKind.SEAL).isEmpty());
        Step light = job.steps(StepKind.LIGHT).stream().filter(s -> s.blockIds().equals(MazeFurnishPlanner.lightIds(plan.theme))).toList().get(0);
        assertEquals(plan.originX + 3, light.x());
        assertEquals(plan.originY + 2, light.y()); // Ostinato: floor torch
        assertEquals(MazeFurnishPlanner.lightIds(plan.theme), light.blockIds());
        Map<String, Integer> materials = job.materials();
        assertEquals(Integer.valueOf(1), materials.get(light.blockIds().get(0)));
        int lootItems = loot.stream().flatMap(p -> p.stacks().stream()).mapToInt(LootPlanner.LootStack::count).sum();
        int listed = materials.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(job.steps(StepKind.LIGHT).size() + loot.size() + lootItems, listed); // Ostinato: torch + cladding
    }

    @Test
    public void entryShaftThroughTheCeilingIsSealedLastAndBottomUp() {
        RoomPlan plan = roomWithLoot();
        List<LootPlacement> loot = LootPlanner.plan(plan, LootPlanner.PlacedLedger.EMPTY);
        int sx = plan.originX + 2, sz = plan.originZ + 3;
        int ceiling = plan.originY + MazePlanner.SIZE_Y - 1;
        int[][] shaft = new int[MazeSeed.COVER + 1][];
        for (int i = 0; i <= MazeSeed.COVER; i++) {
            shaft[i] = new int[]{sx, ceiling + i, sz};
        }
        MazeFurnishJob job = MazeFurnishPlanner.plan(plan, loot, dug(plan, shaft), true);
        List<Step> seals = job.steps(StepKind.SEAL);
        assertEquals(MazeSeed.COVER + 1, seals.size());
        for (int i = 0; i < seals.size(); i++) {
            assertEquals(ceiling + i, seals.get(i).y());
            assertEquals(MazeFurnishPlanner.sealIds(plan.theme), seals.get(i).blockIds());
        }
        List<Step> steps = job.steps;
        int firstSeal = steps.indexOf(seals.get(0));
        for (int i = 0; i < firstSeal; i++) {
            assertFalse(steps.get(i).kind() == StepKind.SEAL);
        }
        for (int i = firstSeal; i < steps.size(); i++) {
            assertEquals(StepKind.SEAL, steps.get(i).kind());
        }
    }

    @Test
    public void creativeOnlyFillsBecauseTheSchematicPlacedTheRest() {
        RoomPlan plan = roomWithLoot();
        List<LootPlacement> loot = LootPlanner.plan(plan, LootPlanner.PlacedLedger.EMPTY);
        MazeFurnishJob job = MazeFurnishPlanner.plan(plan, loot, dug(plan, new int[]{plan.originX + 2, plan.originY + 4, plan.originZ + 3}), false);
        assertFalse(job.survival);
        assertEquals(loot.size(), job.steps.size());
        for (Step step : job.steps) {
            assertEquals(StepKind.FILL, step.kind());
        }
    }

    @Test
    public void furnisherRegistry() {
        try {
            MazeFurnishers.clear();
            assertNull(MazeFurnishers.get());
            IMazeFurnisher f = job -> true;
            MazeFurnishers.register(f);
            assertSame(f, MazeFurnishers.get());
        } finally {
            MazeFurnishers.clear();
        }
    }
}
