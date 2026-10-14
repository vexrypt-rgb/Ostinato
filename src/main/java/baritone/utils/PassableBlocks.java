package baritone.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Ostinato code: blocks the pathfinder treats exactly like air. Detected by an empty collision shape (grass, ferns,
 * flowers, saplings, crops, sugar cane, dead bush, mushrooms, roots, sprouts, torches, redstone dust, buttons, levers,
 * rails, signs, banners, ...), minus everything with a movement or damage effect: cobweb, sweet berry bush, powder
 * snow, fire, wither rose, anything climbable (ladders, vines, scaffolding), fluids and bubble columns, pressure plates
 * and tripwire (they trigger), portals, snow layers and moving pistons.
 * <p>
 * They still have an outline, so the crosshair stops on them: attack and placement rays have to look around them
 * ({@link #clearAim}) and break one only when there is no other angle.
 */
public final class PassableBlocks {
    private PassableBlocks() {}

    public static boolean isFreeAir(BlockState state) {
        Block b = state.getBlock();
        if (b instanceof AirBlock) {
            return true;
        }
        if (!state.getFluidState().isEmpty() || b == Blocks.BUBBLE_COLUMN) {
            return false;
        }
        if (b == Blocks.COBWEB || b == Blocks.SWEET_BERRY_BUSH || b == Blocks.POWDER_SNOW || b instanceof BaseFireBlock
                || b == Blocks.WITHER_ROSE || b == Blocks.SCAFFOLDING || state.is(BlockTags.CLIMBABLE)
                || b instanceof BasePressurePlateBlock || b == Blocks.TRIPWIRE || b instanceof NetherPortalBlock
                || b instanceof EndPortalBlock || b instanceof EndGatewayBlock || b instanceof SnowLayerBlock
                || b instanceof MovingPistonBlock || b instanceof BigDripleafBlock || b instanceof PointedDripstoneBlock) {
            return false;
        }
        try {
            return state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty();
        } catch (RuntimeException e) {
            return false; // shape needs the real world: not one of ours
        }
    }

    /** First free-air block an outline ray from {@code from} to {@code to} stops on, or null if nothing passable is in the way. */
    public static BlockPos blockingFreeAir(Level level, Entity viewer, Vec3 from, Vec3 to) {
        HitResult h = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
        if (h instanceof BlockHitResult bh && h.getType() == HitResult.Type.BLOCK && isFreeAir(level.getBlockState(bh.getBlockPos()))) {
            return bh.getBlockPos();
        }
        return null;
    }

    /** Is the outline ray clear all the way to {@code to}? */
    public static boolean clear(Level level, Entity viewer, Vec3 from, Vec3 to) {
        HitResult h = level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, viewer));
        return h.getType() == HitResult.Type.MISS || h.getLocation().distanceToSqr(from) >= to.distanceToSqr(from) - 1e-4;
    }

    /**
     * An aim point on {@code target}'s box we can see without a passable block (or anything else) in the way, the
     * preferred point first, then its centre, head, feet and the box's side midpoints. Null when every angle is blocked.
     */
    public static Vec3 clearAim(Level level, Entity viewer, Vec3 eye, Entity target, Vec3 preferred) {
        AABB bb = target.getBoundingBox().deflate(0.05);
        Vec3 c = bb.getCenter();
        Vec3[] pts = {
                preferred, c,
                new Vec3(c.x, bb.maxY, c.z), new Vec3(c.x, bb.minY + 0.1, c.z),
                new Vec3(bb.minX, c.y, c.z), new Vec3(bb.maxX, c.y, c.z), new Vec3(c.x, c.y, bb.minZ), new Vec3(c.x, c.y, bb.maxZ),
                new Vec3(bb.minX, bb.maxY, bb.minZ), new Vec3(bb.maxX, bb.maxY, bb.maxZ),
        };
        for (Vec3 p : pts) {
            if (p != null && clear(level, viewer, eye, p)) {
                return p;
            }
        }
        return null;
    }
}
