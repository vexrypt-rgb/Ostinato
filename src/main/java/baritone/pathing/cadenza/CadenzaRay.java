/*
 * Ostinato: new file (Cadenza). Not part of upstream Baritone.
 */
package baritone.pathing.cadenza;

import baritone.utils.BlockStateInterface;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The client's pick ray, run against a {@link BlockStateInterface} instead of a live level, so it works for a player
 * who is not there yet (and on the calc thread). Same rules as the crosshair: block outline shapes, fluids ignored,
 * the first block whose outline the ray enters is what gets hit.
 */
public final class CadenzaRay {

    private CadenzaRay() {}

    /** What a ray hit; reused by the caller. */
    public static final class Hit {
        public int x, y, z;
        public Direction face;
        public double distance;
        public boolean hit;

        public boolean is(BlockPos pos) {
            return hit && x == pos.getX() && y == pos.getY() && z == pos.getZ();
        }
    }

    /** The outline the crosshair would see; a block whose shape needs a real level counts as a full cube. */
    public static VoxelShape outline(BlockState state, BlockPos pos) {
        try {
            return state.getShape(EmptyBlockGetter.INSTANCE, pos);
        } catch (RuntimeException e) {
            return Shapes.block();
        }
    }

    /**
     * Casts from {@code eye} toward {@code aim}, out to {@code reach} blocks, and fills {@code out} with the first block
     * hit. Unloaded cells stop the ray as a miss (the hit cannot be known).
     */
    public static Hit cast(BlockStateInterface bsi, Vec3 eye, Vec3 aim, double reach, Hit out) {
        out.hit = false;
        Vec3 dir = aim.subtract(eye);
        double len = dir.length();
        if (len < 1e-6) {
            return out;
        }
        Vec3 to = eye.add(dir.scale(reach / len));
        BlockHitResult r = BlockGetter.traverseBlocks(eye, to, bsi, (b, pos) -> {
            if (!b.worldContainsLoadedChunk(pos.getX(), pos.getZ())) {
                return BlockHitResult.miss(eye, Direction.UP, pos.immutable());
            }
            BlockState state = b.get0(pos.getX(), pos.getY(), pos.getZ());
            if (state.isAir()) {
                return null;
            }
            VoxelShape shape = outline(state, pos);
            return shape.isEmpty() ? null : shape.clip(eye, to, pos);
        }, b -> null);
        if (r == null || r.getType() != BlockHitResult.Type.BLOCK) {
            return out;
        }
        out.hit = true;
        out.x = r.getBlockPos().getX();
        out.y = r.getBlockPos().getY();
        out.z = r.getBlockPos().getZ();
        out.face = r.getDirection();
        out.distance = r.getLocation().distanceTo(eye);
        return out;
    }
}
