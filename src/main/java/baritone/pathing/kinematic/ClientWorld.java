package baritone.pathing.kinematic;

import baritone.api.utils.IPlayerContext;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Collision boxes and slipperiness read straight from the client world, memoised for one tick. */
public final class ClientWorld implements PlayerSim.World {
    private final Long2ObjectOpenHashMap<List<double[]>> cache = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private final IPlayerContext ctx;

    public ClientWorld(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /** Whether the boots carry Soul Speed: looked up once between resets, null until asked. */
    private Boolean soulSpeed;

    public void reset() {
        cache.clear();
        soulSpeed = null;
    }

    // vanilla's own numbers are floats: walking speed, the sprint modifier, jump strength
    private static final double WALK_SPEED = 0.1F, SPRINT_BONUS = 0.3F, JUMP_STRENGTH = 0.42F;

    /**
     * Whether the player falls the plain way. Levitation and Slow Falling (and a changed gravity attribute) do not,
     * and {@link PlayerSim} does not follow them: anything that rolls it forward from the player is wrong then.
     */
    public static boolean plainGravity(IPlayerContext ctx) {
        return !ctx.player().hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)
                && !ctx.player().hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING)
                && Math.abs(ctx.player().getGravity() - 0.08) < 1e-6;
    }

    /** The player's ground speed against the plain walk: above 1 under Speed, below it under Slowness. */
    private static double walkScale(IPlayerContext ctx) {
        // the attribute carries the sprint bonus while sprinting, which the simulator adds itself
        double walk = ctx.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED)
                / (ctx.player().isSprinting() ? 1 + SPRINT_BONUS : 1);
        return near(walk / WALK_SPEED, 1);
    }

    /**
     * Whether the player walks at least as fast as the plain walk. The jump templates are rolled out at that pace:
     * faster only reaches further, and the search at the jump steers it, but slower falls short.
     */
    public static boolean fullPace(IPlayerContext ctx) {
        return walkScale(ctx) >= 1;
    }

    /** A value this close to the plain one is the plain one: what the simulator does by default stays bit for bit. */
    private static double near(double v, double plain) {
        return Math.abs(v - plain) < 1e-4 ? plain : v;
    }

    /** Loads what effects, attributes and enchantments change about the real player's movement into {@code sim}. */
    public static void readEffects(IPlayerContext ctx, PlayerSim sim) {
        sim.speedScale = walkScale(ctx);
        double jump = ctx.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH);
        sim.jumpStrength = near(jump, JUMP_STRENGTH) == JUMP_STRENGTH ? 0.42 : jump;
        sim.jumpBoost = ctx.player().getJumpBoostPower();
        // Soul Speed raises this only while the player stands on a soul block: read from anywhere else it is 0
        sim.movementEfficiency = ctx.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_EFFICIENCY);
    }

    /** Loads the real player's position, velocity, collision flags, speed and jump strength into {@code sim}. */
    public static void readPlayer(IPlayerContext ctx, PlayerSim sim) {
        readEffects(ctx, sim);
        Vec3 p = ctx.player().position();
        Vec3 m = ctx.player().getDeltaMovement();
        sim.x = p.x;
        sim.y = p.y;
        sim.z = p.z;
        sim.vx = m.x;
        sim.vy = m.y;
        sim.vz = m.z;
        sim.onGround = ctx.player().onGround();
        sim.sprinting = ctx.player().isSprinting();
        sim.collidedH = ctx.player().horizontalCollision;
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
        long key = BlockPos.asLong(x, y, z);
        List<double[]> got = cache.get(key);
        if (got != null) {
            return got;
        }
        pos.set(x, y, z);
        BlockState state = ctx.world().getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(ctx.world(), pos);
        if (shape.isEmpty()) {
            got = Collections.emptyList();
        } else {
            got = new ArrayList<>();
            for (AABB bb : shape.toAabbs()) {
                got.add(new double[]{bb.minX + x, bb.minY + y, bb.minZ + z, bb.maxX + x, bb.maxY + y, bb.maxZ + z});
            }
        }
        cache.put(key, got);
        return got;
    }

    @Override
    public float slipperiness(int x, int y, int z) {
        pos.set(x, y, z);
        return ctx.world().getBlockState(pos).getBlock().getFriction();
    }

    @Override
    public boolean bouncy(int x, int y, int z) {
        pos.set(x, y, z);
        return ctx.world().getBlockState(pos).getBlock() == Blocks.SLIME_BLOCK;
    }

    @Override
    public boolean climbable(int x, int y, int z) {
        pos.set(x, y, z);
        return ctx.world().getBlockState(pos).is(net.minecraft.tags.BlockTags.CLIMBABLE);
    }

    @Override
    public float speedFactor(int x, int y, int z) {
        pos.set(x, y, z);
        BlockState state = ctx.world().getBlockState(pos);
        float f = state.getBlock().getSpeedFactor();
        // Soul Speed takes a soul block's drag away. The attribute that does it is only raised while the player
        // stands on one, so a rollout that starts anywhere else has to be told here
        return f != 1 && state.is(net.minecraft.tags.BlockTags.SOUL_SPEED_BLOCKS) && soulSpeed() ? 1 : f;
    }

    private boolean soulSpeed() {
        if (soulSpeed == null) {
            soulSpeed = false;
            for (net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> e
                    : ctx.player().getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).getEnchantments().keySet()) {
                if (e.is(net.minecraft.world.item.enchantment.Enchantments.SOUL_SPEED)) soulSpeed = true;
            }
        }
        return soulSpeed;
    }

    @Override
    public float jumpFactor(int x, int y, int z) {
        pos.set(x, y, z);
        return ctx.world().getBlockState(pos).getBlock().getJumpFactor();
    }

    @Override
    public boolean sticky(int x, int y, int z) {
        pos.set(x, y, z);
        return ctx.world().getBlockState(pos).getBlock() == Blocks.HONEY_BLOCK;
    }
}
