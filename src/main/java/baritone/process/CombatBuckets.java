package baritone.process;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import baritone.api.process.PathingCommand;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import static baritone.process.CombatAim.press;

/**
 * The two buckets. Water at our own feet puts out a fire, washes a cobweb off us, stops lava under us and breaks a
 * long fall; lava goes under a foe that stands still for it, above all one caught in a cobweb. A source we poured is
 * taken back into its bucket as soon as it has done its work, so one bucket lasts the fight and our own lava is not
 * left where we mean to step.
 */
final class CombatBuckets {
    /** What the process does for us: hotbar (pulling the bucket up from the inventory), keys, the use key, the tick's decision label and a line in the fight log. */
    interface Hands {
        boolean select(Player me, int slot);
        void key(Input in);
        void use(boolean down);
        PathingCommand decide(String d);
        void note(String line);
    }

    private final IPlayerContext ctx;
    private final CombatInventory inv;
    private final CombatAim aimer;
    private final CombatPolicy.Buckets p;
    private final Hands hands;
    /** A source we poured and mean to take back, and the tick it was poured on. */
    private BlockPos waterAt, lavaAt;
    private int waterTick, lavaTick;
    private int nextUse, nextLava, lastTick, unburnt;
    /** The tick a try at a pour began on, 0 between tries. */
    private int tryTick;
    /** Learned in the fight: this foe stood in our lava and did not catch fire. */
    private boolean foeFireproof;
    private boolean lit;

    CombatBuckets(IPlayerContext ctx, CombatInventory inv, CombatAim aimer, CombatPolicy.Buckets p, Hands hands) {
        this.ctx = ctx;
        this.inv = inv;
        this.aimer = aimer;
        this.p = p;
        this.hands = hands;
    }

    /** A new fight: the sources of the last one are not ours to look for, and this foe has shown nothing yet. */
    void reset() {
        waterAt = lavaAt = null;
        waterTick = lavaTick = nextUse = nextLava = lastTick = unburnt = tryTick = 0;
        foeFireproof = lit = false;
    }

    /** The rules on their own, in a class that loads without the game. */
    static final class Rule {
        private Rule() {}

        /**
         * Water at our feet: for a cobweb we are caught in whatever else is true (rain washes nothing off), and for fire
         * or lava unless we do not feel them or are wet already. Never where water boils off.
         */
        static boolean douse(boolean burning, boolean inLava, boolean webbed, boolean fireproof, boolean wet, boolean boilsOff) {
            return !boilsOff && (webbed || !wet && !fireproof && (burning || inLava));
        }

        /** A fall worth a bucket: long enough to hurt, still going down, and nothing gentler already under us. */
        static boolean falling(double fall, double minFall, double vy, boolean wetBelow, boolean boilsOff) {
            return !boilsOff && !wetBelow && fall >= minFall && vy < -0.3;
        }

        /**
         * Lava under the foe: from where the bucket reaches but its flow does not reach us, at a foe that will be standing
         * in it (on the ground, or held by a cobweb), not uphill of us, and only while it can still be set alight.
         */
        static boolean pour(double dist, double lo, double hi, boolean los, boolean held, boolean alight, boolean fireproof, boolean wet, double rise) {
            return los && held && !alight && !fireproof && !wet && dist > lo && dist < hi && rise <= 0.5;
        }

        /** A try whose view has not come to rest on the foe's cell in {@code aim} ticks is given up: the foe did not stand for it. */
        static boolean missed(int ticks, int aim) {
            return ticks > aim;
        }

        /** Our water is taken back when its work is done, and not sooner than {@code hold} ticks: under a fall it has to be there when we land. */
        static boolean waterBack(int since, int hold, boolean done) {
            return done && since >= hold;
        }

        /** Our lava is taken back once the foe burns or has left it, and after {@code hold} ticks whatever it did: it is not left where we mean to step. */
        static boolean lavaBack(int since, int hold, boolean done) {
            return done || since >= hold;
        }

        /** Leave a source where it is: it could not be reached for too long, or the fight has moved away from it. */
        static boolean abandon(int since, int limit, double away, double far) {
            return since > limit || away > far;
        }
    }

    private boolean boilsOff() {
        return ctx.world().dimension() == Level.NETHER;
    }

    private boolean source(BlockPos at, boolean lava) {
        var f = ctx.world().getFluidState(at);
        return f.isSource() && f.is(lava ? FluidTags.LAVA : FluidTags.WATER);
    }

    /** What the view rests on, as a bucket sees it: a full bucket looks through liquid, an empty one stops at a source. */
    private BlockHitResult ray(Player me, boolean empty) {
        Vec3 eye = me.getEyePosition(), end = eye.add(me.getViewVector(1f).scale(me.blockInteractionRange()));
        BlockHitResult h = ctx.world().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, empty ? ClipContext.Fluid.SOURCE_ONLY : ClipContext.Fluid.NONE, me));
        return h.getType() == HitResult.Type.BLOCK ? h : null;
    }

    /**
     * The cell a full bucket would empty into with the view as it is, null when it rests on nothing in reach. As
     * BucketItem.use has it: the cell on the near side of the block looked at, except that water goes into a block that
     * holds water itself (a slab, a stair).
     */
    private BlockPos landing(Player me, boolean water) {
        BlockHitResult h = ray(me, false);
        if (h == null) return null;
        boolean into = water && ctx.world().getBlockState(h.getBlockPos()).getBlock() instanceof LiquidBlockContainer;
        return into ? h.getBlockPos() : h.getBlockPos().relative(h.getDirection());
    }

    private boolean webbed(Player me) {
        BlockPos feet = me.blockPosition();
        return ctx.world().getBlockState(feet).is(Blocks.COBWEB) || ctx.world().getBlockState(feet.above()).is(Blocks.COBWEB);
    }

    /**
     * Water for ourselves: under a long fall, on a fire, a cobweb or lava, and back into the bucket afterwards. Null when
     * there is nothing for it to do. {@code falls} is false while the fall is somebody else's (a mace dive, wings).
     */
    PathingCommand water(Player me, int eatTicks, boolean falls) {
        if (me.tickCount < lastTick) reset(); // respawned: the tick count started over under the old stamps
        lastTick = me.tickCount;
        boolean inLava = me.isInLava(), webbed = webbed(me), fireproof = me.hasEffect(MobEffects.FIRE_RESISTANCE);
        boolean need = Rule.douse(me.isOnFire(), inLava, webbed, fireproof, me.isInWaterOrRain(), boilsOff());
        if (waterAt != null) {
            PathingCommand back = takeBack(me, false, !need && (me.onGround() || me.isInWater()));
            if (back != null || waterAt != null) return back;
        }
        int slot = inv.slotOf(me, Items.WATER_BUCKET);
        if (slot < 0) return null;
        if (falls && !me.onGround() && !me.hasEffect(MobEffects.SLOW_FALLING)
                && Rule.falling(me.fallDistance, p.minFall, me.getDeltaMovement().y, false, boilsOff())) {
            if (me.isUsingItem()) hands.use(false);
            if (!hands.select(me, slot)) return hands.decide("swap");
            aimer.aim(new Rotation(me.getYRot(), 90f), true);
            BlockPos land = me.getXRot() >= 78f ? landing(me, true) : null; // null until the ground is in the bucket's reach
            if (land != null && !ctx.world().getFluidState(land).is(FluidTags.WATER) && me.tickCount >= nextUse) {
                press(ctx.minecraft().options.keyUse);
                waterAt = land;
                waterTick = me.tickCount;
                nextUse = me.tickCount + 2; // a second press on the next tick would be the emptied bucket taking the water back
            }
            return hands.decide("mlg");
        }
        if (!need || eatTicks > 0 && !inLava && !webbed) return null; // a bite going down is finished first: the fire costs less than the apple gives
        if (me.isUsingItem()) { // a raised shield holds the use key: the bucket needs it free
            hands.use(false);
            return hands.decide("water");
        }
        if (!hands.select(me, slot)) return hands.decide("swap");
        aimer.aim(new Rotation(me.getYRot(), 90f), true);
        BlockPos land = me.getXRot() >= 78f ? landing(me, true) : null;
        if (land != null && me.tickCount >= nextUse) {
            press(ctx.minecraft().options.keyUse);
            waterAt = land;
            waterTick = me.tickCount;
            nextUse = me.tickCount + p.gap;
        }
        return hands.decide("water");
    }

    /**
     * Lava for the foe, and back into the bucket once it burns. Null when there is nothing for it to do. {@code free} is
     * false while something else owns our movement.
     */
    PathingCommand lava(Player me, LivingEntity target, double dist, boolean los, boolean free) {
        if (lavaAt != null) {
            boolean in = target.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(lavaAt));
            if (in && !target.isOnFire() && me.tickCount - lavaTick > 5 && ++unburnt >= 10) foeFireproof = true;
            if (target.isOnFire() && !lit) {
                lit = true;
                hands.note("lava lit after=" + (me.tickCount - lavaTick));
            }
            boolean done = target.isOnFire() || foeFireproof || !in && me.tickCount - lavaTick > 5;
            PathingCommand back = takeBack(me, true, done);
            if (back != null) return back;
            if (lavaAt != null && !me.hasEffect(MobEffects.FIRE_RESISTANCE)
                    && me.position().subtract(Vec3.atBottomCenterOf(lavaAt)).horizontalDistance() < p.keepOff) {
                look(me, Vec3.atCenterOf(lavaAt)); // our own lava is between us: step back from it rather than into it
                hands.key(Input.MOVE_BACK);
                return hands.decide("lava");
            }
            return null;
        }
        int slot = free && me.tickCount >= nextLava ? inv.slotOf(me, Items.LAVA_BUCKET) : -1;
        BlockPos cell = slot < 0 ? null : pourCell(me, target, dist, los);
        if (cell == null) return missed(me, "lapsed");
        if (tryTick == 0) tryTick = me.tickCount;
        if (Rule.missed(me.tickCount - tryTick, p.lavaAim)) return missed(me, "aim");
        look(me, Vec3.atCenterOf(cell.below()).add(0, 0.5, 0));
        // the bucket comes out only with the view at rest on the cell: a change of hands starts the swing's charge over,
        // and a foe that steps off while we turn would cost that for nothing
        if (!cell.equals(landing(me, false))) return hands.decide("lava");
        if (me.isUsingItem()) {
            hands.use(false);
            return hands.decide("lava");
        }
        if (!hands.select(me, slot)) return hands.decide("swap");
        if (me.tickCount >= nextUse) {
            press(ctx.minecraft().options.keyUse);
            hands.note("lava pour aim=" + (me.tickCount - tryTick) + " dist=" + String.format("%.1f", dist));
            lavaAt = cell;
            lavaTick = me.tickCount;
            unburnt = tryTick = 0;
            lit = false;
            nextUse = me.tickCount + p.gap;
            nextLava = me.tickCount + p.lavaGap;
        }
        return hands.decide("lava");
    }

    /** The cell lava would be poured into for this foe as it stands, null while a pour is not worth trying. */
    private BlockPos pourCell(Player me, LivingEntity target, double dist, boolean los) {
        Level w = ctx.world();
        BlockPos feet = target.blockPosition();
        boolean held = w.getBlockState(feet).is(Blocks.COBWEB);
        BlockPos cell = held ? feet.above() : feet; // a cobweb cannot be poured into: on top of it the lava is at the foe's head
        BlockState there = w.getBlockState(cell);
        if (!there.canBeReplaced() || !there.getFluidState().isEmpty() || w.getBlockState(cell.below()).canBeReplaced()) return null;
        BlockPos mine = me.blockPosition();
        if (Math.abs(cell.getX() - mine.getX()) + Math.abs(cell.getZ() - mine.getZ()) < 2) return null; // beside us it is ours too
        boolean fireproof = foeFireproof || target.fireImmune() || target.hasEffect(MobEffects.FIRE_RESISTANCE);
        return Rule.pour(dist, p.lavaLo, p.lavaHi, los, held || target.onGround(), target.isOnFire(), fireproof, target.isInWaterOrRain(),
                target.getY() - me.getY()) ? cell : null;
    }

    /** No pour this tick. A try that was under way ends here, and the next one waits: a foe that will not stand for it costs a look now and then, not a swing each time. */
    private PathingCommand missed(Player me, String why) {
        if (tryTick != 0) {
            hands.note("lava miss " + why + " ticks=" + (me.tickCount - tryTick));
            tryTick = 0;
            nextLava = me.tickCount + p.lavaRetry;
        }
        return null;
    }

    /**
     * One tick of fetching a source we poured. Null with the source still remembered means it is left to work; null with
     * it forgotten means it is gone, into the bucket or for good.
     */
    private PathingCommand takeBack(Player me, boolean lava, boolean done) {
        BlockPos at = lava ? lavaAt : waterAt;
        int since = me.tickCount - (lava ? lavaTick : waterTick);
        int bucket = inv.slotOf(me, Items.BUCKET);
        double away = me.getEyePosition().distanceTo(Vec3.atCenterOf(at));
        // no source where we poured a few ticks on: the pour never happened, or it is already back in the bucket
        // (the first ticks are the pour's own: the click is still on its way and the bucket still shows full)
        if (since > 3 && (!source(at, lava) || bucket < 0) || Rule.abandon(since, p.limit, away, p.far)) {
            if (lava) lavaAt = null;
            else waterAt = null;
            return null;
        }
        boolean now = lava ? Rule.lavaBack(since, p.lavaHold, done) : Rule.waterBack(since, p.waterHold, done);
        if (!now || since <= 3 || away > me.blockInteractionRange()) return null;
        if (me.isUsingItem()) {
            hands.use(false);
            return hands.decide(lava ? "lava" : "water");
        }
        if (!hands.select(me, bucket)) return hands.decide("swap");
        look(me, Vec3.atCenterOf(at));
        BlockHitResult h = ray(me, true);
        if (h != null && h.getBlockPos().equals(at) && me.tickCount >= nextUse) {
            press(ctx.minecraft().options.keyUse);
            nextUse = me.tickCount + p.gap;
        }
        return hands.decide(lava ? "lava" : "water");
    }

    private void look(Player me, Vec3 at) {
        aimer.aim(RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations()), true);
    }
}
