package baritone.pathing.kinematic;

import baritone.api.utils.IPlayerContext;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ai.attributes.Attributes;
import net.minecraft.potion.EffectInstance;
import net.minecraft.potion.Effects;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collision boxes and slipperiness read straight from the client world, memoised for one tick. */
public final class ClientWorld implements PlayerSim.World {
    private final Long2ObjectOpenHashMap<List<double[]>> cache = new Long2ObjectOpenHashMap<>();
    private final BlockPos.Mutable pos = new BlockPos.Mutable();

    private final IPlayerContext ctx;

    public ClientWorld(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    public void reset() {
        cache.clear();
    }

    private static final double WALK_SPEED = 0.1F, SPRINT_BONUS = 0.3F;

    /**
     * Whether the player falls the plain way. Levitation and Slow Falling do not, and {@link PlayerSim} does not
     * follow them: anything that rolls it forward from the player is wrong then.
     */
    public static boolean plainGravity(IPlayerContext ctx) {
        return !ctx.player().isPotionActive(Effects.LEVITATION) && !ctx.player().isPotionActive(Effects.SLOW_FALLING);
    }

    /** The player's ground speed against the plain walk: above 1 under Speed, below it under Slowness. */
    private static double walkScale(IPlayerContext ctx) {
        // the attribute carries the sprint bonus while sprinting, which the simulator adds itself
        double walk = ctx.player().getAttributeValue(Attributes.MOVEMENT_SPEED)
                / (ctx.player().isSprinting() ? 1 + SPRINT_BONUS : 1);
        // this close to the plain walk it is the plain walk: what the simulator does by default stays bit for bit
        return Math.abs(walk / WALK_SPEED - 1) < 1e-4 ? 1 : walk / WALK_SPEED;
    }

    /**
     * Whether the player walks at least as fast as the plain walk. The jump templates are rolled out at that pace:
     * faster only reaches further, and the search at the jump steers it, but slower falls short.
     */
    public static boolean fullPace(IPlayerContext ctx) {
        return walkScale(ctx) >= 1;
    }

    /** Loads what potion effects change about the real player's movement into {@code sim}. */
    public static void readEffects(IPlayerContext ctx, PlayerSim sim) {
        sim.speedScale = walkScale(ctx);
        EffectInstance jump = ctx.player().getActivePotionEffect(Effects.JUMP_BOOST);
        sim.jumpBoost = jump == null ? 0 : 0.1F * (jump.getAmplifier() + 1);
    }

    @Override
    public void collect(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<double[]> out) {
        for (int x = PlayerSim.floor(minX); x <= PlayerSim.floor(maxX); x++) {
            for (int y = PlayerSim.floor(minY) - 1; y <= PlayerSim.floor(maxY); y++) { // -1: fences stick up 1.5
                for (int z = PlayerSim.floor(minZ); z <= PlayerSim.floor(maxZ); z++) {
                    out.addAll(boxes(x, y, z));
                }
            }
        }
    }

    private List<double[]> boxes(int x, int y, int z) {
        long key = BlockPos.pack(x, y, z);
        List<double[]> got = cache.get(key);
        if (got != null) {
            return got;
        }
        pos.setPos(x, y, z);
        BlockState state = ctx.world().getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(ctx.world(), pos);
        if (shape.isEmpty()) {
            got = Collections.emptyList();
        } else {
            got = new ArrayList<>();
            for (AxisAlignedBB bb : shape.toBoundingBoxList()) {
                got.add(new double[]{bb.minX + x, bb.minY + y, bb.minZ + z, bb.maxX + x, bb.maxY + y, bb.maxZ + z});
            }
        }
        cache.put(key, got);
        return got;
    }

    @Override
    public float slipperiness(int x, int y, int z) {
        pos.setPos(x, y, z);
        return ctx.world().getBlockState(pos).getBlock().getSlipperiness();
    }

    @Override
    public boolean bouncy(int x, int y, int z) {
        pos.setPos(x, y, z);
        return ctx.world().getBlockState(pos).getBlock() == Blocks.SLIME_BLOCK;
    }

    @Override
    public float speedFactor(int x, int y, int z) {
        pos.setPos(x, y, z);
        BlockState state = ctx.world().getBlockState(pos);
        float f = state.getBlock().getSpeedFactor();
        // vanilla LivingEntity.getSpeedFactor: Soul Speed walks a soul block at the full pace
        if (f != 1 && state.isIn(BlockTags.SOUL_SPEED_BLOCKS)
                && EnchantmentHelper.getMaxEnchantmentLevel(Enchantments.SOUL_SPEED, ctx.player()) > 0) {
            return 1;
        }
        return f;
    }

    @Override
    public float jumpFactor(int x, int y, int z) {
        pos.setPos(x, y, z);
        return ctx.world().getBlockState(pos).getBlock().getJumpFactor();
    }

    @Override
    public boolean sticky(int x, int y, int z) {
        pos.setPos(x, y, z);
        return ctx.world().getBlockState(pos).getBlock() == Blocks.HONEY_BLOCK;
    }
}
