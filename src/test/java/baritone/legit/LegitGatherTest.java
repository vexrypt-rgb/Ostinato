/*
 * Ostinato: legit gather decisions. Headless. No game client.
 */
package baritone.legit;

import baritone.api.legit.SeenOnly;
import baritone.pathing.calc.PathBench;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class LegitGatherTest {

    private static final int HIDDEN_X = 40;
    private static final int HIDDEN_Z = 40;

    @BeforeClass
    public static void boot() {
        PathBench.boot();
    }

    @Test
    public void yLevelsMatch263Peaks() {
        assertEquals(-59, LegitGather.branchY("diamond_ore"));
        assertEquals(-59, LegitGather.branchY("block.minecraft.deepslate_diamond_ore"));
        assertEquals(-59, LegitGather.branchY("redstone_ore"));
        assertEquals(-16, LegitGather.branchY("gold_ore"));
        assertEquals(0, LegitGather.branchY("lapis_ore"));
        assertEquals(48, LegitGather.branchY("copper_ore"));
        assertEquals(96, LegitGather.branchY("coal_ore"));
        assertEquals(232, LegitGather.branchY("emerald_ore"));
        assertEquals(15, LegitGather.branchY("ancient_debris"));
        assertEquals(48, LegitGather.branchY("nether_quartz_ore"));
        assertEquals(48, LegitGather.branchY("nether_gold_ore"));
        assertEquals(16, LegitGather.branchY("andesite"));
        assertEquals(16, LegitGather.branchY("iron_ore"));
        assertEquals(16, LegitGather.peaks("iron_ore")[0]);
        assertEquals(232, LegitGather.peaks("iron_ore")[1]);
        assertEquals(96, LegitGather.peaks("coal_ore")[0]);
        assertEquals(136, LegitGather.peaks("coal_ore")[1]);
        assertTrue(LegitGather.branchY("nether_quartz_ore") >= 10 && LegitGather.branchY("nether_quartz_ore") <= 117);
        assertNotEquals(-59, LegitGather.branchY("andesite"));
    }

    @Test
    public void branchCellsExposeFacesAndDoNotAimAtAHiddenOre() {
        assertEquals(0, LegitGather.heading(0));
        assertEquals(LegitGather.heading(4), LegitGather.heading(0));
        assertEquals(1, LegitGather.cellX(0, 0, 0, 0, 0));
        assertEquals(0, LegitGather.cellZ(0, 0, 0, 0, 0));
        assertEquals(3, LegitGather.SPACING);
        for (int step = 0; step < 31; step++) {
            int tunnelZ = LegitGather.cellZ(0, 0, 0, 0, step);
            int nextZ = LegitGather.cellZ(0, 0, 0, 1, step);
            assertEquals(3, nextZ - tunnelZ);
            int x = LegitGather.cellX(0, 0, 0, 0, step);
            assertTrue(faceAdjacent(x, tunnelZ, x, tunnelZ + 1));
            assertTrue(faceAdjacent(x, nextZ, x, nextZ - 1));
            for (int branch = 0; branch < 3; branch++) {
                int cx = LegitGather.cellX(0, 0, 0, branch, step);
                int cz = LegitGather.cellZ(0, 0, 0, branch, step);
                assertFalse(cx == HIDDEN_X && cz == HIDDEN_Z);
            }
        }
        assertNotEquals(LegitGather.runLength(0, 0), LegitGather.runLength(1, 0));
        assertNotEquals(LegitGather.lookPeriod(0), LegitGather.lookPeriod(1));
    }

    @Test
    public void openingABranchFaceMakesTheNeighborKnowable() {
        Grid buried = stone();
        assertFalse(SeenOnly.knowable(buried, 0.5, 1.62, 0.5, 1, 1, 1));
        Grid dug = stone();
        dug.set(0, 1, 0, SeenOnly.Cell.AIR, 12);
        dug.set(LegitGather.cellX(0, 0, 0, 0, 0), 1, LegitGather.cellZ(0, 0, 0, 0, 0), SeenOnly.Cell.AIR, 12);
        assertTrue(SeenOnly.knowable(dug, 0.5, 1.62, 0.5, 1, 1, 1));
    }

    @Test
    public void aSingleBotBranchesBesideItselfAndNotAtTheHiddenOre() {
        LegitGather.Facts facts = ready();
        facts.feetY = 70;
        LegitGather.Plan down = LegitGather.plan(facts);
        assertEquals(LegitGather.Strategy.BRANCH, down.strategy);
        assertEquals(0, down.x);
        assertEquals(69, down.y);
        assertEquals(0, down.z);
        assertFalse(down.x == HIDDEN_X && down.z == HIDDEN_Z);

        facts.feetY = -59;
        LegitGather.Plan mined = LegitGather.plan(facts);
        assertEquals(LegitGather.Strategy.BRANCH, mined.strategy);
        assertEquals(LegitGather.cellX(0, 0, 0, 0, 0), mined.x);
        assertEquals(-59, mined.y);
        assertEquals(LegitGather.cellZ(0, 0, 0, 0, 0), mined.z);
        assertFalse(mined.x == HIDDEN_X && mined.y == -59 && mined.z == HIDDEN_Z);
    }

    @Test
    public void multiBotGatherIsRefusedAndDoesNotPlan() {
        LegitGather.Facts facts = ready();
        facts.multiBot = true;
        facts.open[0] = true;
        facts.light[0] = 12;
        LegitGather.Plan plan = LegitGather.plan(facts);
        assertEquals(LegitGather.Strategy.REJECT, plan.strategy);
        assertEquals(LegitGather.MULTI_BOT_REFUSAL, plan.refusal);
        assertFalse(plan.hasGoal);
        assertEquals("swarm: coordinated work is not available in phase 1", plan.refusal);
    }

    @Test
    public void graceNoteYieldsBeforeWaterOrMining() {
        LegitGather.Facts facts = ready();
        facts.graceBusy = true;
        facts.headInWater = true;
        facts.air = 10;
        facts.doors = 0;
        LegitGather.Plan plan = LegitGather.plan(facts);
        assertEquals(LegitGather.Strategy.YIELD, plan.strategy);
        assertFalse(plan.hasGoal);
    }

    @Test
    public void lowAirRetreatsAndADoorIsChosenWhenThereIsAFloor() {
        LegitGather.Facts sinking = ready();
        sinking.feetY = 0;
        sinking.headInWater = true;
        sinking.air = 30;
        sinking.doors = 4;
        sinking.floor = false;
        sinking.surface = -1;
        LegitGather.Plan retreat = LegitGather.plan(sinking);
        assertEquals(LegitGather.Strategy.RETREAT, retreat.strategy);
        assertEquals(1, retreat.y);
        assertEquals(0, retreat.x);
        assertEquals(0, retreat.z);

        LegitGather.Facts door = ready();
        door.feetY = 0;
        door.headInWater = true;
        door.air = 200;
        door.doors = 2;
        door.floor = true;
        door.surface = -1;
        door.inPocket = false;
        LegitGather.Plan pocket = LegitGather.plan(door);
        assertEquals(LegitGather.Strategy.WATER_DOOR, pocket.strategy);
        assertEquals(0, pocket.x);
        assertEquals(0, pocket.y);

        LegitGather.Facts deeper = ready();
        deeper.feetY = 0;
        deeper.headInWater = true;
        deeper.air = 200;
        deeper.doors = 2;
        deeper.floor = false;
        deeper.surface = -1;
        LegitGather.Plan sink = LegitGather.plan(deeper);
        assertEquals(LegitGather.Strategy.SINK_FOR_DOOR, sink.strategy);
        assertEquals(-1, sink.y);
        assertFalse(sink.x == HIDDEN_X && sink.z == HIDDEN_Z);
    }

    @Test
    public void noPickDoesNotStartABranch() {
        LegitGather.Facts bare = ready();
        bare.holdingPick = false;
        bare.pickInInventory = false;
        bare.pickaxe = false;
        assertEquals(LegitGather.Strategy.YIELD, LegitGather.plan(bare).strategy);

        LegitGather.Facts stowed = ready();
        stowed.holdingPick = false;
        stowed.pickInInventory = true;
        assertEquals(LegitGather.Strategy.SWAP_TOOL, LegitGather.plan(stowed).strategy);
        assertFalse(LegitGather.plan(stowed).hasGoal);
    }

    @Test
    public void aDarkFrontierIsLitBeforeItIsWalked() {
        LegitGather.Facts facts = ready();
        facts.open[2] = true;
        facts.light[2] = 0;
        facts.open[0] = false;
        facts.light[0] = 15;
        LegitGather.Plan plan = LegitGather.plan(facts);
        assertEquals(LegitGather.Strategy.LIGHT, plan.strategy);
        assertEquals(0, plan.x);
        assertEquals(1, plan.z);
        assertFalse(plan.x == HIDDEN_X && plan.z == HIDDEN_Z);
    }

    @Test
    public void aLitOpenFrontierIsACaveStep() {
        LegitGather.Facts facts = ready();
        facts.salt = 1;
        facts.open[1] = true;
        facts.light[1] = 12;
        LegitGather.Plan plan = LegitGather.plan(facts);
        assertEquals(LegitGather.Strategy.CAVE, plan.strategy);
        assertEquals(1, plan.x);
        assertEquals(0, plan.z);
    }

    @Test
    public void lookTicksPauseOnASaltCadence() {
        LegitGather.Facts facts = ready();
        facts.tick = LegitGather.lookPeriod(0);
        assertEquals(LegitGather.Strategy.LOOK, LegitGather.plan(facts).strategy);
        facts.tick = 1;
        assertEquals(LegitGather.Strategy.BRANCH, LegitGather.plan(facts).strategy);
    }

    @Test
    public void mineUsesGatherOnlyWhileLegitIsActive() throws Exception {
        String mine = Files.readString(project("src/main/java/baritone/process/MineProcess.java"), StandardCharsets.UTF_8);
        int gather = mine.indexOf("return legitGather()");
        assertTrue(gather > 0);
        String before = mine.substring(Math.max(0, gather - 240), gather);
        assertTrue(before.contains("LegitTargets.active()"));
        assertTrue(mine.contains("new GoalRunAway"));
        assertTrue(mine.contains("LegitGather.plan"));
        assertTrue(mine.contains("getLookBehavior().updateTarget"));
    }

    private static LegitGather.Facts ready() {
        LegitGather.Facts facts = new LegitGather.Facts();
        facts.holdingPick = true;
        facts.pickInInventory = true;
        facts.pickaxe = true;
        facts.feetX = 0;
        facts.feetY = -59;
        facts.feetZ = 0;
        facts.originX = 0;
        facts.originZ = 0;
        facts.block = "diamond_ore";
        facts.tick = 1;
        facts.salt = 0;
        return facts;
    }

    private static boolean faceAdjacent(int x, int z, int ox, int oz) {
        return Math.abs(x - ox) + Math.abs(z - oz) == 1;
    }

    private static Grid stone() {
        return new Grid(SeenOnly.Cell.OPAQUE, 15);
    }

    private static Path project(String rel) {
        Path path = Path.of(rel);
        return Files.exists(path) ? path : Path.of("ostinato").resolve(rel);
    }

    private static final class Grid implements SeenOnly.View {
        private final Map<Long, SeenOnly.Cell> cells = new HashMap<>();
        private final Map<Long, Integer> lights = new HashMap<>();
        private final SeenOnly.Cell fill;
        private final int fillLight;

        Grid(SeenOnly.Cell fill, int fillLight) {
            this.fill = fill;
            this.fillLight = fillLight;
        }

        void set(int x, int y, int z, SeenOnly.Cell cell, int light) {
            long key = SeenOnly.key(x, y, z);
            cells.put(key, cell);
            lights.put(key, light);
        }

        @Override
        public SeenOnly.Cell at(int x, int y, int z) {
            return cells.getOrDefault(SeenOnly.key(x, y, z), fill);
        }

        @Override
        public int light(int x, int y, int z) {
            return lights.getOrDefault(SeenOnly.key(x, y, z), fillLight);
        }
    }
}
