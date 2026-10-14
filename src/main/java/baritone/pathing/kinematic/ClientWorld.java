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

    // Ostinato: an effect that runs out within this many ticks is planned as if it were gone already: a path is
    // costed now and walked over the next several seconds, and a jump that needs the boost must not outlive it
    public static final int EFFECT_PLAN_TICKS = 200;

    /** Ostinato: whether {@code effect} is on and lasts at least {@link #EFFECT_PLAN_TICKS} more. */
    public static boolean lasting(IPlayerContext ctx, net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        net.minecraft.world.effect.MobEffectInstance e = ctx.player().getEffect(effect);
        return e != null && (e.isInfiniteDuration() || e.getDuration() >= EFFECT_PLAN_TICKS);
    }

    /**
     * Ostinato: the SAFE_FALL_DISTANCE attribute (3, plus 1 per Jump Boost level in 26.3) as whole blocks, with a
     * Jump Boost about to end taken off again so a drop is not planned on it.
     */
    public static int safeFall(IPlayerContext ctx) {
        double safe = ctx.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.SAFE_FALL_DISTANCE);
        net.minecraft.world.effect.MobEffectInstance jb = ctx.player().getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST);
        if (jb != null && !lasting(ctx, net.minecraft.world.effect.MobEffects.JUMP_BOOST)) safe -= jb.getAmplifier() + 1;
        return Math.max(0, (int) Math.floor(safe + 1e-6));
    }

    /**
     * Ostinato: the ground speed to plan with: {@link #walkScale}, less a Speed that runs out within
     * {@link #EFFECT_PLAN_TICKS}. A Slowness about to end stays in: planning slower than we go is only pessimistic.
     */
    public static double planSpeedScale(IPlayerContext ctx) {
        double s = walkScale(ctx);
        net.minecraft.world.effect.MobEffectInstance sp = ctx.player().getEffect(net.minecraft.world.effect.MobEffects.SPEED);
        if (sp != null && !lasting(ctx, net.minecraft.world.effect.MobEffects.SPEED)) s /= 1 + 0.2F * (sp.getAmplifier() + 1);
        return near(s, 1);
    }

    /** Ostinato: Jump Boost levels (amplifier + 1) on now, 0 for none. */
    public static int jumpBoostLive(IPlayerContext ctx) {
        net.minecraft.world.effect.MobEffectInstance e = ctx.player().getEffect(net.minecraft.world.effect.MobEffects.JUMP_BOOST);
        return e == null ? 0 : e.getAmplifier() + 1;
    }

    /** Ostinato: Jump Boost levels to plan a jump on: 0 unless it lasts {@link #EFFECT_PLAN_TICKS} more. */
    public static int jumpBoostLasting(IPlayerContext ctx) {
        return lasting(ctx, net.minecraft.world.effect.MobEffects.JUMP_BOOST) ? jumpBoostLive(ctx) : 0;
    }

    /**
     * Ostinato: what is left of a fall's damage after Resistance, LivingEntity.getDamageAfterMagicAbsorb: 5 of 25
     * a level off (fall damage is not in bypasses_resistance), so V and up take none. 1 if it may run out first.
     */
    public static double resistanceFactor(IPlayerContext ctx) {
        net.minecraft.world.effect.MobEffectInstance e = ctx.player().getEffect(net.minecraft.world.effect.MobEffects.RESISTANCE);
        if (e == null || !lasting(ctx, net.minecraft.world.effect.MobEffects.RESISTANCE)) return 1;
        return resistanceFactor(e.getAmplifier());
    }

    /** Ostinato: vanilla's (25 - 5 * (amplifier + 1)) / 25, never below 0. */
    public static double resistanceFactor(int amplifier) {
        return Math.max(0, 25 - (amplifier + 1) * 5) / 25.0;
    }

    /**
     * Ostinato: how many ticks of a fall Slow Falling is sure to cover if the fall starts within
     * {@link #EFFECT_PLAN_TICKS}: -1 without it. Fall distance is reset every tick under it, so no such fall hurts.
     */
    public static int slowFallTicks(IPlayerContext ctx) {
        net.minecraft.world.effect.MobEffectInstance e = ctx.player().getEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING);
        if (e == null) return -1;
        if (e.isInfiniteDuration()) return Integer.MAX_VALUE;
        int left = e.getDuration() - EFFECT_PLAN_TICKS;
        return left < 0 ? -1 : left;
    }

    /** Ostinato: Player.isMobilityRestricted (Blindness) stops and forbids a sprint in LocalPlayer.aiStep. */
    public static boolean sprintAllowed(IPlayerContext ctx) {
        return !ctx.player().isMobilityRestricted();
    }

    /** A value this close to the plain one is the plain one: what the simulator does by default stays bit for bit. */
    private static double near(double v, double plain) {
        return Math.abs(v - plain) < 1e-4 ? plain : v;
    }

    /**
     * Loads what effects, attributes and enchantments change about the real player's movement into {@code sim}.
     * For a simulator whose position and velocity the caller sets by hand; {@link #readPlayer} does both.
     */
    public static void readEffects(IPlayerContext ctx, PlayerSim sim) {
        sim.speedScale = walkScale(ctx);
        double jump = ctx.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.JUMP_STRENGTH);
        sim.jumpStrength = near(jump, JUMP_STRENGTH) == JUMP_STRENGTH ? 0.42 : jump;
        sim.jumpBoost = ctx.player().getJumpBoostPower();
        // Soul Speed raises this only while the player stands on a soul block: read from anywhere else it is 0
        sim.movementEfficiency = ctx.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_EFFICIENCY);
        // Ostinato: gravity effects, so a rollout under them can be told (the controllers still stand down for them)
        sim.gravity = ctx.player().getGravity();
        sim.slowFalling = ctx.player().hasEffect(net.minecraft.world.effect.MobEffects.SLOW_FALLING);
        net.minecraft.world.effect.MobEffectInstance lev = ctx.player().getEffect(net.minecraft.world.effect.MobEffects.LEVITATION);
        sim.levitation = lev == null ? -1 : lev.getAmplifier();
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
    public boolean water(int x, int y, int z) {
        pos.set(x, y, z);
        return ctx.world().getBlockState(pos).getFluidState().is(net.minecraft.tags.FluidTags.WATER);
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
