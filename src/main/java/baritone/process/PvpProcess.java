package baritone.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.BlockHitResult;
import baritone.Baritone;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Melee PvP. Far away we path to the target; inside {@link #DRIVE} we pause pathing and steer
 * ourselves: crit chaining (jump, drop sprint near the apex, hit falling), sprint hits with a
 * W-tap, hit select (no swings into hurt immunity), jump resets on knockback, random strafing,
 * axe against a raised shield, shield against drawn bows and incoming arrows, a bow at range,
 * golden apples and a totem in the offhand when low.
 */
public final class PvpProcess extends BaritoneProcessHelper {

    private static final double REACH = 3.0, DRIVE = 7, BOW_MIN = 10, CHASE = 48;
    private static final Item[] SWORDS = {Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.GOLDEN_SWORD, Items.WOODEN_SWORD};
    private static final Item[] AXES = {Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.GOLDEN_AXE, Items.WOODEN_AXE};

    private Predicate<LivingEntity> filter;
    private String label;
    private LivingEntity target;
    private final Random rng = new Random(7);
    private int strafeDir = 1, strafeLeft, wtap, eatTicks, groundedJumps, blockTicks;
    private boolean crystalFight;
    private int backingOff;
    private int targetSwingTick, lastAxeTick = -1000;
    private boolean critArmed;
    private float lastHealth = -1;

    public int attacks, crits, sprintHits, axeHits, blocks, gapples;
    public float damageTaken;

    public PvpProcess(Baritone baritone) {
        super(baritone);
    }

    public void attack(Predicate<LivingEntity> filter, String label) {
        this.filter = filter;
        this.label = label;
        target = null;
        lastHealth = -1;
        attacks = crits = sprintHits = axeHits = blocks = gapples = 0;
        damageTaken = 0;
    }

    public void attackPlayer(String name) {
        attack(e -> e instanceof Player && e.getName().getString().equalsIgnoreCase(name), name);
    }

    public void attackPlayers() {
        attack(e -> e instanceof Player, "players");
    }

    public void attackHostiles() {
        attack(e -> e instanceof Enemy, "hostiles");
    }

    public LivingEntity getTarget() {
        return target;
    }

    public String stats() {
        return String.format("attacks=%d crits=%d sprintHits=%d axeHits=%d blocks=%d gapples=%d dmgTaken=%.1f",
                attacks, crits, sprintHits, axeHits, blocks, gapples, damageTaken);
    }

    @Override
    public boolean isActive() {
        return filter != null && ctx.player() != null;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        Player me = ctx.player();
        float hp = me.getHealth() + me.getAbsorptionAmount();
        if (lastHealth >= 0 && hp < lastHealth) damageTaken += lastHealth - hp;
        lastHealth = hp;
        // tickCount starts again with a new player entity (respawn, another dimension)
        if (me.tickCount < lastAxeTick) lastAxeTick = -1000;
        if (me.tickCount < targetSwingTick) targetSwingTick = 0;

        if (target == null || !target.isAlive() || target.isRemoved() || me.distanceTo(target) > CHASE) target = pick(me);
        baritone.getInputOverrideHandler().clearAllKeys();
        if (target == null) {
            use(false);
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }
        keepTotem(me);

        boolean targetEating = target.isUsingItem() && target.getUseItem().has(net.minecraft.core.component.DataComponents.FOOD);
        boolean safe = eyeToBox(me, target) > 4.5 || targetEating;
        if (eatTicks > 0 || (me.getHealth() <= 5 || me.getHealth() <= 11 && safe || crystalFight && me.getAbsorptionAmount() == 0 && me.getHealth() <= (has(me, Items.RESPAWN_ANCHOR) ? 19 : 16)) && !me.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)
                && (has(me, Items.GOLDEN_APPLE) || has(me, Items.ENCHANTED_GOLDEN_APPLE))) {
            if (eat(me)) return pause();
        }

        double dist = eyeToBox(me, target);
        boolean los = me.hasLineOfSight(target);

        if (shouldBlock(me, dist)) {
            if (me.getOffhandItem().getItem() != Items.SHIELD) toOffhand(me, Items.SHIELD);
            look(target.getEyePosition());
            use(true);
            if (blockTicks++ == 0) blocks++;
            return pause();
        }
        if (blockTicks > 0) {
            use(false);
            blockTicks = 0;
        }

        // crystals and anchors reach further than a sword, and blowing them is also how we clear a wall of them
        if (crystal(me)) {
            if (dist <= DRIVE) steer(me, dist);
            return pause();
        }
        if (!los && dist <= 3 && Baritone.settings().allowBreak.value) { // right there but walled off (a crawl gap under our feet, a hole): dig through
            BlockHitResult wall = ctx.world().clip(new net.minecraft.world.level.ClipContext(me.getEyePosition(), target.getEyePosition(),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, me));
            if (wall.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                look(wall.getLocation());
                ctx.minecraft().gameMode.continueDestroyBlock(wall.getBlockPos(), wall.getDirection());
                me.swing(InteractionHand.MAIN_HAND);
                return pause();
            }
        }
        if (dist > DRIVE || !los) {
            if (los && dist > BOW_MIN && has(me, Items.BOW) && has(me, Items.ARROW)) return bow(me);
            use(false);
            return new PathingCommand(new GoalNear(target.blockPosition(), 2), PathingCommandType.REVALIDATE_GOAL_AND_PATH);
        }
        if (me.isUsingItem()) use(false);

        boolean inReach = exactReach(me, target) <= REACH - 0.05;
        // a shield being raised blocks before isBlocking() shows it; only swap in reach, since any swap drains the charge
        boolean shieldUp = target.isBlocking() || target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
        // a disabled shield stays "raised" for its 5s cooldown; don't keep throwing uncharged axe swings at it
        boolean axeTime = shieldUp && inReach && me.tickCount - lastAxeTick > 60 && best(me, AXES) >= 0;
        select(me, axeTime ? best(me, AXES) : weapon(me));
        look(aimPoint(me, target));

        float cd = me.getAttackStrengthScale(0.5f);
        if (target.swinging && target.swingTime == 0) targetSwingTick = me.tickCount;
        boolean targetReady = me.tickCount - targetSwingTick >= 10; // its sword is charged: whoever swings first wins the exchange
        boolean immune = target.hurtTime > 1;
        boolean falling = !me.onGround() && me.getDeltaMovement().y < -0.05;
        boolean canJump = me.onGround() && !me.isInWater() && !me.isInLava() && !me.onClimbable();

        steer(me, dist);
        if (me.hurtTime == me.hurtDuration - 1 && canJump) me.jumpFromGround(); // jump reset

        if (axeTime && inReach) { // an axe disables a raised shield whatever the charge
            hit(me);
            axeHits++;
            lastAxeTick = me.tickCount;
            return pause();
        }
        if (!me.onGround() && !falling && targetReady && inReach && cd >= 0.95f && !immune) {
            hit(me); // don't hang in the air waiting for a crit while it swings first
            critArmed = false;
            return pause();
        }
        if (critArmed && falling && inReach && cd >= 0.9f && !immune) {
            hit(me);
            crits++;
            critArmed = false;
            return pause();
        }
        if (!me.onGround() && me.getDeltaMovement().y < 0.08 && dist <= REACH + 0.6 && cd >= 0.75f) {
            me.setSprinting(false); // a sprinting hit is never a crit
            critArmed = true;
        }
        if (!me.onGround() && !critArmed && inReach && cd >= 0.95f && !immune) {
            hit(me); // knocked airborne without a crit set up: don't waste the cooldown
            return pause();
        }
        if (me.onGround()) critArmed = false;
        else groundedJumps = 0;

        if (canJump && dist <= REACH + 0.8 && cd >= 0.55f && !immune && groundedJumps < 4) {
            me.jumpFromGround();
            groundedJumps++;
            return pause();
        }
        if (me.onGround() && inReach && cd >= 0.95f && !immune && (!canJump || groundedJumps >= 4)) {
            boolean sprint = me.isSprinting();
            hit(me);
            if (sprint) {
                sprintHits++;
                wtap = 2;
            }
            groundedJumps = 0;
        }
        return pause();
    }

    private PathingCommand pause() {
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    private void steer(Player me, double dist) {
        if (--strafeLeft <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeLeft = 10 + rng.nextInt(20);
        }
        if (wtap > 0) {
            wtap--;
            me.setSprinting(false);
        } else if (dist > 2.4) {
            key(Input.MOVE_FORWARD);
            if (!critArmed && me.getFoodData().getFoodLevel() > 6) me.setSprinting(true);
        } else if (dist < 1.2) {
            key(Input.MOVE_BACK);
        }
        if (dist > 3.5) {
            // it's backing off to heal: run it down in a straight line, sprint-jumping for speed
            if (me.onGround() && me.isSprinting() && !me.isInWater()) me.jumpFromGround();
            return;
        }
        key(strafeDir > 0 ? Input.MOVE_RIGHT : Input.MOVE_LEFT);
    }

    private boolean shouldBlock(Player me, double dist) {
        if (me.getOffhandItem().getItem() != Items.SHIELD && !has(me, Items.SHIELD)) return false;
        // the totem keeps the offhand while it is what keeps us alive: swapping the two every tick does neither job
        if (me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING && (me.getHealth() <= 8 || crystalFight)) return false;
        ItemStack using = target.getUseItem();
        if (target.isUsingItem() && (using.getItem() == Items.BOW || using.getItem() == Items.CROSSBOW) && dist > 4) return true;
        AABB around = me.getBoundingBox().inflate(6);
        for (AbstractArrow a : ctx.world().getEntitiesOfClass(AbstractArrow.class, around, x -> true)) {
            Vec3 v = a.getDeltaMovement();
            if (v.lengthSqr() < 0.25) continue;
            Vec3 to = me.position().add(0, 1, 0).subtract(a.position());
            if (to.normalize().dot(v.normalize()) > 0.9) return true;
        }
        return false;
    }

    private PathingCommand bow(Player me) {
        select(me, slotOf(me, Items.BOW));
        if (me.getMainHandItem().getItem() != Items.BOW) return pause();
        // lead: arrow ~3 b/t at full draw, gravity 0.05
        double d = me.distanceTo(target), t = d / 3.0;
        Vec3 at = target.getEyePosition().add(target.getDeltaMovement().scale(t)).add(0, 0.5 * 0.05 * t * t, 0);
        look(at);
        if (me.isUsingItem() && me.getTicksUsingItem() >= 21) {
            use(false);
            attacks++;
        } else {
            use(true);
        }
        return pause();
    }

    private boolean eat(Player me) {
        Item apple = me.getHealth() <= 6 && has(me, Items.ENCHANTED_GOLDEN_APPLE) ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE;
        if (!has(me, apple)) apple = Items.ENCHANTED_GOLDEN_APPLE;
        if (!has(me, apple)) {
            eatTicks = 0;
            return false;
        }
        select(me, slotOf(me, apple));
        if (me.getMainHandItem().getItem() != apple) return true;
        if (eatTicks++ == 0) gapples++;
        key(Input.MOVE_BACK); // back off while chewing
        me.setSprinting(false);
        use(true);
        look(target.getEyePosition());
        if (eatTicks > 36) {
            use(false);
            eatTicks = 0;
        }
        return true;
    }

    private void keepTotem(Player me) {
        if (me.getHealth() > 8 && !crystalFight || me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) return;
        toOffhand(me, Items.TOTEM_OF_UNDYING);
    }

    /** Swap an inventory item into the offhand (button 40 = offhand swap). */
    private void toOffhand(Player me, Item item) {
        int slot = -1;
        for (int i = 0; i < 36; i++) if (me.getInventory().getItem(i).getItem() == item) { slot = i; break; }
        if (slot < 0) return;
        int menuSlot = slot < 9 ? 36 + slot : slot;
        ctx.playerController().windowClick(me.inventoryMenu.containerId, menuSlot, 40, ClickType.SWAP, me);
    }

    /** Whether we carry the item at all. Asking moves nothing; {@link #slotOf} does, and is for the moment of use. */
    private boolean has(Player me, Item item) {
        for (int i = 0; i < 36; i++) if (me.getInventory().getItem(i).getItem() == item) return true;
        return false;
    }

    /** Hotbar slot of the item, pulling it into the hotbar if it's only in the main inventory. */
    private int slotOf(Player me, Item item) {
        for (int i = 0; i < 9; i++) if (me.getInventory().getItem(i).getItem() == item) return i;
        for (int i = 9; i < 36; i++) {
            if (me.getInventory().getItem(i).getItem() == item) {
                int to = spare(me);
                ctx.playerController().windowClick(me.inventoryMenu.containerId, i, to, ClickType.SWAP, me);
                return to;
            }
        }
        return -1;
    }

    /** Hotbar slot to pull an item into: an empty one, else the last that holds no weapon. */
    private int spare(Player me) {
        int kept = -1;
        for (int i = 8; i >= 0; i--) {
            ItemStack st = me.getInventory().getItem(i);
            if (st.isEmpty()) return i;
            if (kept < 0 && !Arrays.asList(SWORDS).contains(st.getItem()) && !Arrays.asList(AXES).contains(st.getItem())) kept = i;
        }
        return kept < 0 ? 8 : kept;
    }

    private int best(Player me, Item[] tiers) {
        for (Item it : tiers) {
            for (int i = 0; i < 9; i++) if (me.getInventory().getItem(i).getItem() == it) return i;
        }
        return -1;
    }

    /** Crit play wants damage per swing: a sword, else an axe. */
    private int weapon(Player me) {
        int s = best(me, SWORDS);
        return s >= 0 ? s : best(me, AXES);
    }

    private void select(Player me, int slot) {
        if (slot >= 0) me.getInventory().selected = slot;
    }

    private void hit(Player me) {
        ctx.minecraft().gameMode.attack(me, target);
        me.swing(InteractionHand.MAIN_HAND);
        attacks++;
    }

    private void key(Input in) {
        baritone.getInputOverrideHandler().setInputForceState(in, true);
    }

    private void use(boolean down) {
        ctx.minecraft().options.keyUse.setDown(down);
    }

    private void look(Vec3 at) {
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(r, true);
    }

    private LivingEntity pick(Player me) {
        return ctx.world().getEntitiesOfClass(LivingEntity.class, me.getBoundingBox().inflate(CHASE),
                        e -> e != me && e.isAlive() && !e.isRemoved() && !e.isSpectator() && filter.test(e))
                .stream().filter(e -> me.distanceTo(e) <= CHASE)
                .min(Comparator.comparingDouble(me::distanceToSqr)).orElse(null);
    }

    private static Vec3 aimPoint(Player me, LivingEntity t) {
        Vec3 eye = me.getEyePosition();
        AABB b = t.getBoundingBox().deflate(0.05);
        return new Vec3(Mth.clamp(eye.x, b.minX, b.maxX), Mth.clamp(eye.y, b.minY + 0.2, b.maxY - 0.1), Mth.clamp(eye.z, b.minZ, b.maxZ));
    }

    /**
     * Crystal PvP: break the crystal that hurts the target most, else put a crystal on obsidian where it does,
     * else lay obsidian beside the target's feet. Anything that would hurt us more than it, or pop us, is skipped.
     */
    private boolean crystal(Player me) {
        crystalFight = has(me, Items.END_CRYSTAL) || has(me, Items.RESPAWN_ANCHOR) || !ctx.world().getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(8)).isEmpty();
        if (anchor(me)) return true;
        if (!has(me, Items.END_CRYSTAL) || me.distanceTo(target) > 7) return false;
        float myHp = me.getHealth() + me.getAbsorptionAmount();
        EndCrystal hitIt = null;
        float best = 0;
        for (EndCrystal c : ctx.world().getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(6))) {
            if (exactReach(me, c) > REACH) continue;
            float score = worth(me, c.position(), myHp);
            if (score > best) {
                best = score;
                hitIt = c;
            }
        }
        if (hitIt == null && has(me, Items.OBSIDIAN)) {
            // a crystal that would hurt us and that we won't pop: wall it off at leg height
            EndCrystal danger = null;
            float worst = 6;
            for (EndCrystal c : ctx.world().getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(6))) {
                float d = blast(me, c.position(), 12);
                if (d >= worst) { worst = d; danger = c; }
            }
            if (danger != null && shield(me, danger.blockPosition())) return true;
        }
        if (hitIt != null) {
            look(hitIt.position());
            ctx.minecraft().gameMode.attack(me, hitIt);
            me.swing(InteractionHand.MAIN_HAND);
            return true;
        }
        Level w = ctx.world();
        BlockPos base = null;
        best = 0;
        BlockPos t = target.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(t.offset(-3, -2, -3), t.offset(3, 1, 3))) {
            if (!w.getBlockState(p).is(Blocks.OBSIDIAN) && !w.getBlockState(p).is(Blocks.BEDROCK)) continue;
            if (!w.isEmptyBlock(p.above()) || !w.getEntities(null, new AABB(p.above())).isEmpty()) continue;
            Vec3 at = Vec3.atBottomCenterOf(p.above());
            if (me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5 || me.getEyePosition().distanceTo(at.add(0, 1, 0)) > REACH + 0.8) continue;
            float score = worth(me, at, myHp);
            if (score > best) {
                best = score;
                base = p.immutable();
            }
        }
        if (base != null) return place(me, Items.END_CRYSTAL, base);
        if (!has(me, Items.OBSIDIAN)) return false;
        BlockPos floor = null;
        best = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (BlockPos p : new BlockPos[]{t.relative(d), t.relative(d).below()}) {
                if (!w.getBlockState(p).canBeReplaced() || w.getBlockState(p.below()).canBeReplaced()) continue;
                if (!w.getEntities(null, new AABB(p)).isEmpty() || me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5) continue;
                float score = worth(me, Vec3.atBottomCenterOf(p.above()), myHp);
                if (score > best) {
                    best = score;
                    floor = p.below();
                }
            }
        }
        return floor != null && place(me, Items.OBSIDIAN, floor);
    }

    /**
     * Anchor PvP (overworld): blow a charged anchor that hurts the target, else charge an anchor near it with
     * glowstone, else put an anchor down beside its feet.
     */
    private boolean anchor(Player me) {
        if (!has(me, Items.RESPAWN_ANCHOR) && !has(me, Items.GLOWSTONE) || me.distanceTo(target) > 7) return false;
        Level w = ctx.world();
        float myHp = me.getHealth() + me.getAbsorptionAmount();
        BlockPos t = target.blockPosition(), boom = null, charge = null, backOff = null;
        float bestBoom = 0, bestCharge = 0, bestBack = 0;
        for (BlockPos p : BlockPos.betweenClosed(t.offset(-3, -1, -3), t.offset(3, 2, 3))) {
            if (!w.getBlockState(p).is(Blocks.RESPAWN_ANCHOR) || me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5) continue;
            Vec3 at = Vec3.atCenterOf(p);
            float score = worth(me, at, myHp, 10), dmg = blast(target, at, 10);
            boolean charged = w.getBlockState(p).getValue(RespawnAnchorBlock.CHARGE) > 0;
            if (!charged && score > 0) {
                // charging hurts nobody, so charge anything that would hurt the target
                if (dmg > bestCharge) { bestCharge = dmg; charge = p.immutable(); }
            } else if (charged && score > bestBoom) {
                bestBoom = score;
                boom = p.immutable();
            } else if (score <= 0 && blast(me, at, 10) >= 8 && blast(me, at, 10) > bestBack) {
                bestBack = blast(me, at, 10); // theirs or ours, it can go off in our face
                backOff = p.immutable();
            }
        }
        if (boom != null) {
            int slot = -1;
            for (int i = 0; i < 9; i++) {
                Item it = me.getInventory().getItem(i).getItem();
                if (it != Items.GLOWSTONE && it != Items.RESPAWN_ANCHOR) { slot = i; break; }
            }
            if (slot < 0) return false;
            select(me, slot);
            return click(me, boom);
        }
        if (charge != null && has(me, Items.GLOWSTONE)) {
            select(me, slotOf(me, Items.GLOWSTONE));
            return me.getMainHandItem().getItem() == Items.GLOWSTONE && click(me, charge);
        }
        // a charged anchor that would hurt us: wall it off at leg height, which is where most of the blast lands
        if (backOff != null && shield(me, backOff)) return true;
        backingOff = backOff == null ? 0 : backingOff + 1;
        // a charged anchor that would hurt us too much from here: step away, then blow it (unless a wall keeps us pinned)
        if (backOff != null && backingOff < 40) {
            look(Vec3.atCenterOf(backOff));
            key(Input.MOVE_BACK);
            return true;
        }
        if (!has(me, Items.RESPAWN_ANCHOR) || !has(me, Items.GLOWSTONE)) return false;
        BlockPos spot = null;
        float best = 0;
        // not just beside them: a target down a one-wide hole has no free side, only the rim
        for (BlockPos q : BlockPos.betweenClosed(t.offset(-2, -1, -2), t.offset(2, 2, 2))) {
            {
                BlockPos p = q.immutable();
                if (!w.getBlockState(p).canBeReplaced() || w.getBlockState(p.below()).canBeReplaced()) continue;
                if (!w.getEntities(null, new AABB(p)).isEmpty() || me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5) continue;
                float score = worth(me, Vec3.atCenterOf(p), myHp, 10);
                if (score > best) { best = score; spot = p; }
            }
        }
        return spot != null && place(me, Items.RESPAWN_ANCHOR, spot.below());
    }

    /** Put a block in the cell between our feet and {@code threat} so the explosion's rays hit it instead of our legs. */
    private boolean shield(Player me, BlockPos threat) {
        Item block = has(me, Items.OBSIDIAN) ? Items.OBSIDIAN : has(me, Items.COBBLESTONE) ? Items.COBBLESTONE
                : has(me, Items.RESPAWN_ANCHOR) ? Items.RESPAWN_ANCHOR : null;
        if (block == null) return false;
        BlockPos feet = me.blockPosition();
        int dx = Integer.signum(threat.getX() - feet.getX()), dz = Integer.signum(threat.getZ() - feet.getZ());
        Level w = ctx.world();
        for (BlockPos c : new BlockPos[]{feet.offset(dx, 0, dz), feet.offset(dx, 0, 0), feet.offset(0, 0, dz)}) {
            if (c.equals(feet) || c.equals(threat) || !w.getBlockState(c).canBeReplaced() || w.getBlockState(c.below()).canBeReplaced()) continue;
            if (!w.getEntities(null, new AABB(c)).isEmpty() || me.getEyePosition().distanceTo(Vec3.atCenterOf(c)) > 4.5) continue;
            return place(me, block, c.below());
        }
        return false;
    }

    /** Right-click the top face of a block with whatever is in hand. */
    private boolean click(Player me, BlockPos on) {
        Vec3 face = Vec3.atCenterOf(on).add(0, 0.5, 0);
        look(face);
        ctx.minecraft().gameMode.useItemOn(ctx.minecraft().player, InteractionHand.MAIN_HAND, new BlockHitResult(face, Direction.UP, on, false));
        me.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    /** Right-click the top of {@code on} with {@code item}. */
    private boolean place(Player me, Item item, BlockPos on) {
        select(me, slotOf(me, item));
        if (me.getMainHandItem().getItem() != item) return false;
        return click(me, on);
    }

    /** How good a crystal blowing up at {@code at} is for us: its damage to the target minus ours, 0 if not worth it. */
    private float worth(Player me, Vec3 at, float myHp) {
        return worth(me, at, myHp, 12);
    }

    private float worth(Player me, Vec3 at, float myHp, double size) {
        float dmg = blast(target, at, size), self = blast(me, at, size);
        boolean totem = me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING;
        if (self >= myHp - (totem ? 0 : 2) && dmg < target.getHealth() + target.getAbsorptionAmount()) return 0;
        // once they're low an even trade wins the race
        if (dmg < 3 || dmg < self * (size == 10 ? (target.getHealth() + target.getAbsorptionAmount() > 10 ? 1.5f : 1) : (target.getHealth() + target.getAbsorptionAmount() <= 10 ? 0.8f : 1))) return 0;
        if (size == 10 && self >= myHp - 4 && dmg < target.getHealth() + target.getAbsorptionAmount()) return 0; // don't pop our own totem
        return dmg - self * 0.6f;
    }

    /** Vanilla end crystal (power 6) damage to {@code e} after armour. */
    private static float blast(LivingEntity e, Vec3 at, double size) {
        double d = Math.sqrt(e.distanceToSqr(at)) / size;
        if (d > 1) return 0;
        // an anchor is removed before it blows, but would block its own rays here, so take it as fully exposed
        double impact = (1 - d) * (size == 12 ? ServerExplosion.getSeenPercent(at, e) : 1);
        float raw = (float) ((impact * impact + impact) / 2 * 7 * size + 1);
        return CombatRules.getDamageAfterAbsorb(e, raw, e.damageSources().generic(), e.getArmorValue(), (float) e.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
    }

    private static double exactReach(Player me, Entity t) {
        Vec3 eye = me.getEyePosition();
        AABB b = t.getBoundingBox();
        return eye.distanceTo(new Vec3(Mth.clamp(eye.x, b.minX, b.maxX), Mth.clamp(eye.y, b.minY, b.maxY), Mth.clamp(eye.z, b.minZ, b.maxZ)));
    }

    private static double eyeToBox(Player me, LivingEntity t) {
        return me.getEyePosition().distanceTo(aimPoint(me, t));
    }

    @Override
    public void onLostControl() {
        filter = null;
        target = null;
        eatTicks = blockTicks = 0;
        if (ctx.minecraft().options != null) use(false);
        baritone.getInputOverrideHandler().clearAllKeys();
    }

    @Override
    public String displayName0() {
        return "PvP " + label + (target == null ? "" : " -> " + target.getName().getString());
    }

    @Override
    public double priority() {
        return 2;
    }
}
