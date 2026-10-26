package baritone.maze;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

// Ostinato: survival #maze build. Runs the schematic the way the builder does (break where desired is air, place where
// desired differs) on a stone world with an empty creative-free inventory except dug cobble, then re-checks the seal.
public class MazeSurvivalBuildTest {

    @BeforeClass
    public static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final class World {
        final Map<Long, BlockState> edits = new HashMap<>();
        static long key(int x, int y, int z) { return ((long) x & 0x3FFFFFF) << 38 | ((long) z & 0x3FFFFFF) << 12 | (y & 0xFFF); }
        BlockState get(int x, int y, int z) {
            BlockState s = edits.get(key(x, y, z));
            if (s != null) return s;
            return y < 64 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
        void set(int x, int y, int z, BlockState s) { edits.put(key(x, y, z), s); }
        BlockProbe probe() {
            return (x, y, z) -> {
                BlockState s = get(x, y, z);
                if (s.isAir()) return BlockProbe.BlockKind.AIR;
                if (!s.getFluidState().isEmpty()) return BlockProbe.BlockKind.FLUID;
                return BlockProbe.BlockKind.SOLID;
            };
        }
    }

    @Test
    public void survivalBuildCarvesRoomWithoutPlacingAnything() {
        World w = new World();
        MazePlanner.Attempt a = MazePlanner.placeOpen(42L, 0, 0, 30, -64, 320, w.probe(), null, MazePlanner.SEARCH_RADIUS);
        assertTrue("refused: " + a.refusal, a.ok());
        RoomPlan plan = a.plan;
        // a one-block air pocket in the planned wall, like the entry shaft, which the dig-only survival build leaves alone
        int gx = -1, gy = -1, gz = -1;
        outer:
        for (int x = 0; x < plan.sizeX; x++) for (int y = 0; y < plan.sizeY; y++) for (int z = 0; z < plan.sizeZ; z++) {
            if (plan.role(x, y, z).solid()) { gx = x; gy = y; gz = z; break outer; }
        }
        w.set(plan.originX + gx, plan.originY + gy, plan.originZ + gz, Blocks.AIR.defaultBlockState());

        MazeSchematic schem = new MazeSchematic(plan, LootPlanner.plan(plan, null), true);
        List<BlockState> inventory = List.of(Blocks.COBBLESTONE.defaultBlockState()); // what mining stone yields
        int broken = 0, placed = 0;
        for (int x = 0; x < plan.sizeX; x++) for (int y = 0; y < plan.sizeY; y++) for (int z = 0; z < plan.sizeZ; z++) {
            BlockState cur = w.get(plan.originX + x, plan.originY + y, plan.originZ + z);
            if (!schem.inSchematic(x, y, z, cur)) continue;
            BlockState want = schem.desiredState(x, y, z, cur, inventory);
            if (want == cur) continue;
            if (want.isAir()) broken++;
            else { assertTrue("needs block not in survival inventory: " + want, inventory.contains(want)); placed++; }
            w.set(plan.originX + x, plan.originY + y, plan.originZ + z, want);
        }
        assertTrue("dug " + broken, broken > 0);
        assertEquals("dig-only: survival never places, the gap is left for the seal pass", 0, placed);
        // idempotent: a second pass wants nothing (builder would report done)
        for (int x = 0; x < plan.sizeX; x++) for (int y = 0; y < plan.sizeY; y++) for (int z = 0; z < plan.sizeZ; z++) {
            BlockState cur = w.get(plan.originX + x, plan.originY + y, plan.originZ + z);
            if (schem.inSchematic(x, y, z, cur)) assertSame(cur, schem.desiredState(x, y, z, cur, inventory));
        }
        ShellSeal.SealVerdict v = ShellSeal.check(plan, w.probe());
        assertTrue("the one wall gap stays open until a seal pass", w.get(plan.originX + gx, plan.originY + gy, plan.originZ + gz).isAir());
        assertTrue("stone behind the gap still seals the room: " + v.reason + " " + v.breaches, v.ok());
        int air = 0;
        for (int x = 0; x < plan.sizeX; x++) for (int y = 0; y < plan.sizeY; y++) for (int z = 0; z < plan.sizeZ; z++) {
            if (plan.role(x, y, z) == BlockRole.AIR) { assertTrue(w.get(plan.originX + x, plan.originY + y, plan.originZ + z).isAir()); air++; }
        }
        System.out.println("[maze-survival] room at " + plan.originX + " " + plan.originY + " " + plan.originZ
                + " dug=" + broken + " placed=" + placed + " interiorAir=" + air + " sealed=" + v.ok());
    }
}
