/* Ostinato: comparison against the actual target Minecraft classes, not a second formula copy. */
package baritone.physics;

import baritone.api.physics.BodyState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.Test;
import java.util.List;
import java.util.Random;
import static org.junit.Assert.*;

public class PpeVanillaTest {
    @Test
    public void collisionsMatchVanillaForSlabsWallsAndCorners() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var collide = Entity.class.getDeclaredMethod("collideWithShapes", Vec3.class, AABB.class, List.class);
        collide.setAccessible(true);
        Random random = new Random(263);
        for (double height : new double[]{0.5, 1.0, 1.5}) {
            BlockInfo obstacle = new BlockInfo.Builder("oracle").boxes(0, 0, 0, 1, height, 1).build();
            Terrain terrain = PpeWorlds.flat((x, y, z) -> y == 64 && (x == 2 && z == 0 || x == 0 && z == 2) ? obstacle : null);
            List<VoxelShape> shapes = List.of(Shapes.create(new AABB(-32, 32, -32, 32, 64, 32)),
                    Shapes.create(new AABB(2, 64, 0, 3, 64 + height, 1)),
                    Shapes.create(new AABB(0, 64, 2, 1, 64 + height, 3)));
            for (int i = 0; i < 200; i++) {
                BodyState state = BodyState.builder(BodyState.Type.PLAYER).pos(0.5, 64 + random.nextDouble() * 2, 0.5).stepHeight(0).build();
                double dx = random.nextDouble() * 4 - 1, dy = random.nextDouble() * 4 - 3, dz = random.nextDouble() * 4 - 1;
                double hw = state.width / 2f;
                AABB box = new AABB(state.x - hw, state.y, state.z - hw, state.x + hw, state.y + state.height, state.z + hw);
                Vec3 expected = (Vec3) collide.invoke(null, new Vec3(dx, dy, dz), box, shapes);
                Body actual = new Body().set(state);
                TerrainView view = new TerrainView();
                view.reset(terrain);
                actual.move(view, dx, dy, dz);
                assertEquals("x case " + i, state.x + expected.x, actual.x, 1e-9);
                assertEquals("y case " + i, state.y + expected.y, actual.y, 1e-9);
                assertEquals("z case " + i, state.z + expected.z, actual.z, 1e-9);
            }
        }
    }
}
