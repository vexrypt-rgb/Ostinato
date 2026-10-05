package baritone.process;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import baritone.api.process.PathingCommand;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import static baritone.process.CombatAim.press;
import static baritone.process.CombatGeometry.*;
import static baritone.process.CombatInventory.*;

/**
 * The mace on foot: hop on a wind charge under the feet, steer the flight to the foe, and land the smash on the last
 * tick of the fall. The phase it flies is kept in {@link CombatPhase}; the process asks {@link #run} each tick the
 * kit carries a mace and acts on the command it returns.
 */
final class CombatMace {
    /** What the process does for us: hotbar, aim, keys, the click, the dive check, the tick's label, and the axe and spear clocks. */
    interface Hands {
        boolean select(Player me, int slot);
        void look(Vec3 at);
        void key(Input in);
        boolean hit(Player me);
        boolean shieldDive(Player me, double dist);
        PathingCommand decide(String d);
        int lastAxe();
        void axeHit(int tick);
        int spearCool();
        void spearCool(int ticks);
    }

    /** Ticks from the wind charge leaving the hand to the jump under it. */
    private static final int HOP_JUMP_DELAY = Integer.getInteger("ostinato.pvp.hopJumpDelay", 1);

    private final IPlayerContext ctx;
    private final CombatInventory inv;
    private final CombatAim aimer;
    private final CombatTargeting targeting;
    private final CombatPhase phase;
    private final Hands hands;

    CombatMace(IPlayerContext ctx, CombatInventory inv, CombatAim aimer, CombatTargeting targeting, CombatPhase phase, Hands hands) {
        this.ctx = ctx;
        this.inv = inv;
        this.aimer = aimer;
        this.targeting = targeting;
        this.phase = phase;
        this.hands = hands;
    }

    /** One tick of the ground mace play for a kit that carries a mace; null means nothing to do this tick. */
    PathingCommand run(Player me, LivingEntity target, double dist, boolean los, boolean overhead, int mace, int wind) {
        boolean spearKit = inv.spearSlot(me) >= 0;
        boolean canJump = me.onGround() && !me.isInWater();
        // Spear kit starts the smash from spearTools, and only from just outside the jab.
        // The bot stands inside 2.5 for half the fight. The hop goes straight up, so it starts there too.
        // 002307 t1527: it stood at 6 health eating 3.6 away and the next 40 ticks went to a hop; two sword hits end it.
        boolean finish = target.getHealth() + target.getAbsorptionAmount() <= 7 && dist < 5;
        if (!spearKit && phase.macePhase == 0 && canJump && phase.maceCool == 0 && !overhead && !finish && dist > 1.0 && dist < 24 && los && wind >= 0) {
            if (!hands.select(me, wind)) return hands.decide("swap");
            aimer.aim(new Rotation(me.getYRot(), 90f), true);
            phase.hopThrown = -1;
            phase.flickLeft = 0;
            phase.flickAge = 100;
            phase.macePhase = 1;
            phase.maceTicks = 0;
        }
        if (phase.macePhase == 1) { // hop: charge leaves straight down this tick, under the feet
            phase.maceTicks++;
            // Spear kit: swapping onto the mace zeroes its charge, and the hop lands at cd~0.5
            // (fight 051702 ticks 45-48 had the fall flag and never clicked). Charge first, then
            // throw the wind charge from the offhand so the hotbar slot never changes.
            if (inv.spearSlot(me) >= 0) {
                if (!hands.select(me, mace)) return hands.decide("swap");
                if (me.getAttackStrengthScale(0f) < 0.99f) {
                    // 05:32: standing still to charge let the bot walk into us, and the throw at dist 0.5 never fell.
                    double hr = horizontalBoxDist(me, target);
                    hands.look(aimPoint(me, target));
                    if (hr < 3.6) hands.key(Input.MOVE_BACK);
                    else if (hr > 4.2) hands.key(Input.MOVE_FORWARD);
                    if (phase.maceTicks > 50 || hr < 1.2) {
                        phase.macePhase = 0;
                        phase.maceCool = 40;
                        if (me.getOffhandItem().getItem() != Items.SHIELD) inv.toOffhand(me, Items.SHIELD);
                    }
                    return hands.decide("mace");
                }
                if (horizontalBoxDist(me, target) < 3.2) {
                    phase.macePhase = 0;
                    phase.maceCool = 40;
                    if (me.getOffhandItem().getItem() != Items.SHIELD) inv.toOffhand(me, Items.SHIELD);
                    return hands.decide("mace");
                }
                if (me.getOffhandItem().getItem() != Items.WIND_CHARGE) {
                    inv.toOffhand(me, Items.WIND_CHARGE);
                    return hands.decide("mace");
                }
                if (me.onGround()) {
                    hands.key(Input.JUMP);
                    return hands.decide("mace");
                }
                if (aimer.throwStraightDown(me)) {
                    phase.macePhase = 2;
                    phase.maceTicks = 0;
                }
                return hands.decide("mace");
            }
            // Do not look at the target or walk in. A 2.5 deg/tick look throws into the ground ahead.
            if (!hands.select(me, wind)) return hands.decide("swap");
            // 1740 fight ticks 249-262: jumped, threw two ticks later, and the burst met the feet a block
            // up for +0.84 and a 7 block hop. The burst is strongest at the feet: tip down on the ground,
            // throw, and jump as it lands so the jump adds to it.
            aimer.aim(new Rotation(me.getYRot(), 90f), true);
            if (phase.hopThrown < 0) {
                if (me.getXRot() >= 78f && me.onGround()) {
                    press(ctx.minecraft().options.keyUse);
                    phase.hopThrown = phase.maceTicks;
                } else if (phase.maceTicks > 10) {
                    phase.macePhase = 0;
                    phase.maceCool = 20;
                }
            } else if (phase.maceTicks - phase.hopThrown >= HOP_JUMP_DELAY) {
                hands.key(Input.JUMP);
                phase.macePhase = 2;
                phase.maceTicks = 0;
            }
            return hands.decide("mace");
        }
        if (phase.macePhase == 2) { // flying: steer to the target, smash while falling
            phase.maceTicks++;
            // A wind hop forgives its own fall and a pearl does not: 18 blocks onto bare ground is most of a health
            // bar. When the fall will end out of reach of them, burst a charge under the feet to break it.
            if (phase.pearlDive && me.onGround()) phase.pearlDive = false;
            if (phase.pearlDive && wind >= 0 && me.fallDistance > 5 && exactReach(me, target) > REACH + 2.5
                    && !ctx.world().noCollision(me, me.getBoundingBox().expandTowards(0, -6.5, 0))) {
                if (!hands.select(me, wind)) return hands.decide("swap");
                if (aimer.throwStraightDown(me)) phase.pearlDive = false;
                return hands.decide("windbreak");
            }
            // Abort a hop that is not a smash when their dive is the one that will land.
            if (spearKit && me.fallDistance < 1.2 && hands.shieldDive(me, dist)) {
                phase.macePhase = 0;
                phase.maceCool = 8;
                return hands.decide("block");
            }
            phase.flickAge++;
            if (phase.flickLeft > 0 && hands.select(me, wind)) { // two ticks, so the server sees it
                phase.flickLeft--;
                return hands.decide("flick");
            }
            if (!hands.select(me, mace)) return hands.decide("swap");
            // The fall is 0.9 a tick, 15 degrees of pitch at this range, and a look set now is read
            // from the next tick's eye. Aim from there, or hands.hit() finds the aim 10 degrees off and does not click.
            Vec3 lead = spearKit ? aimPoint(me, target) : aimPoint(me, target).subtract(me.getDeltaMovement());
            hands.look(lead);
            // VexBot's SwordPvpController.isIncomingFallAttack raises its shield when a mace is 3 above it,
            // inside 3.5 and falling at 0.3, and the shield blocks 5 ticks later. A dive that drops in
            // slow and unsprinted meets it less often (4/5 at medium against 1/5 sprinting in).
            boolean quiet = !spearKit && me.distanceTo(target) < 7;
            // A shield covers the half-circle its holder faces and nothing behind it. 235605 t104 and 235404
            // t591 came down 0.9 and 0.4 behind a raised shield and killed through it; 000432 t151 came down
            // in front and did nothing. Against a shield the dive is flown to the far side of its back.
            Vec3 facing = target.calculateViewVector(0f, target.getYHeadRot());
            Vec3 offset = me.position().subtract(target.position()).multiply(1, 0, 1);
            boolean theirShield = target.getOffhandItem().getItem() == Items.SHIELD || target.getMainHandItem().getItem() == Items.SHIELD;
            boolean behind = offset.dot(facing) < -0.1;
            boolean around = !spearKit && theirShield && me.getY() > target.getY() + 1.5;
            // 002307 t1533-1566: it walked off eating at 0.2 a tick and the dive came down 2.8 behind where it
            // had been. Fly to where it will be when the fall reaches its height.
            int fallTicks = 0;
            for (double v = me.getDeltaMovement().y, dy = me.getY() - target.getY() - 1.5; dy > 0 && fallTicks < 30; fallTicks++) {
                v = (v - 0.08) * 0.98;
                dy += v;
            }
            Vec3 ahead = target.position().add(targeting.velocity(target).multiply(fallTicks, 0, fallTicks));
            boolean moving = !spearKit && targeting.velocity(target).horizontalDistance() > 0.1;
            if (around || moving) {
                Vec3 to = (around ? ahead.subtract(facing.scale(1.6)) : ahead).subtract(me.position()).multiply(1, 0, 1);
                if (to.horizontalDistance() > 0.15) {
                    float rel = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(-to.x, to.z)) - me.getYRot());
                    if (Math.abs(rel) < 67.5f) hands.key(Input.MOVE_FORWARD);
                    else if (Math.abs(rel) > 112.5f) hands.key(Input.MOVE_BACK);
                    if (rel > 22.5f && rel < 157.5f) hands.key(Input.MOVE_RIGHT);
                    else if (rel < -22.5f && rel > -157.5f) hands.key(Input.MOVE_LEFT);
                    if (around || to.horizontalDistance() > 2.5) hands.key(Input.SPRINT);
                }
            } else if (!quiet) {
                hands.key(Input.MOVE_FORWARD);
                hands.key(Input.SPRINT);
            } else if (!me.isSprinting() && (horizontalBoxDist(me, target) > 2.2 || me.getDeltaMovement().horizontalDistance() < 0.08)) {
                hands.key(Input.MOVE_FORWARD);
            }
            if (!spearKit && wind >= 0 && !me.onGround() && phase.flickAge > 40) {
                // The cooldown restarts when the held item changes: flick off the mace so the fall ends near 0.8.
                int n = 0;
                double v = me.getDeltaMovement().y, dy = me.getY() - target.getY() - 1.5;
                while (dy > 0 && n < 60) {
                    v = (v - 0.08) * 0.98;
                    dy += v;
                    n++;
                }
                if (n >= 14 && n <= 26 && me.getAttackStrengthScale(0f) + n / 33f >= 0.84f) {
                    phase.flickAge = 0;
                    phase.flickLeft = 1;
                    hands.select(me, wind);
                    return hands.decide("flick");
                }
            }
            int sp = inv.spearSlot(me), axe = inv.best(me, AXES);
            boolean shielded = target.isBlocking() || target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
            if (shielded && axe >= 0 && me.fallDistance > 1.5 && me.tickCount - hands.lastAxe() > 20 && exactReach(me, target) <= REACH - 0.05) {
                if (!hands.select(me, axe)) return hands.decide("swap"); // breach slam: the axe drops the shield, the mace lands on the next tick
                hands.hit(me);
                hands.axeHit(me.tickCount);
                return hands.decide("axe");
            }
            // Lunge ONLY if the held spear has minecraft:lunge 1-3. Plain spears never lunge.
            // Vanilla impulse scales ~0.458 per level; we only jab farther out at higher levels.
            int lungeLvl = sp >= 0 ? inv.spearLungeLevel(me.getInventory().getItem(sp)) : 0;
            double lungeMax = 6.0 + lungeLvl * 5.0; // L1~11, L2~16, L3~21
            if (sp >= 0 && lungeLvl >= 1 && hands.spearCool() == 0 && !me.onGround()
                    && dist > 4 && dist < lungeMax && me.getFoodData().getFoodLevel() >= 7) {
                if (!hands.select(me, sp)) return hands.decide("swap");
                hands.hit(me); // piercing jab; vanilla post_piercing_attack applies Lunge impulse by level
                hands.spearCool(45 - lungeLvl * 5);
                return hands.decide("lunge");
            }
            // 1740 fight ticks 249-277: the swap back to the mace zeroes a 33-tick cooldown and the
            // hop lands 28 ticks later at 0.78, so a 0.99 gate never opened. The fall bonus scales
            // with the charge: swing on the last tick before the ground with whatever is there.
            boolean landing = !ctx.world().noCollision(me, me.getBoundingBox().move(0, -1.3, 0));
            float sc = me.getAttackStrengthScale(0f);
            // 234854 mace easy defensive: it launched after our hop and the dive met it ten blocks up at 0.5 charge,
            // dropped past and landed under its mace. A target in the air that the fall is about to pass is the
            // same last tick as the ground.
            // 235231 ticks 606/952: passing it just under the apex smashed for 5 with nothing fallen yet.
            boolean passing = !spearKit && !target.onGround() && me.fallDistance >= 4 && me.getY() + me.getDeltaMovement().y * 2 < target.getY() + 0.5;
            if (me.fallDistance > 1.5 && me.getDeltaMovement().y < -0.05
                    && (spearKit ? sc >= 0.99f : sc >= 0.6f || (landing || passing) && sc >= 0.4f) && target.hurtTime <= 0
                    && exactReach(me, target) <= REACH - 0.05
                    // 1745 fight tick 91: the aim was off on the landing tick, hands.hit() did not click, and the
                    // hop was written off as spent.
                    && (spearKit || aimer.aimedAt(me, aimPoint(me, target), 10f)
                    || ctx.minecraft().hitResult instanceof net.minecraft.world.phys.EntityHitResult on && on.getEntity() == target)
                    && (!around || behind || landing || !shielded)
                    && (me.fallDistance >= 3 || landing || passing)) {
                // 235231 ticks 238 and 297: hands.hit() refused the click (crosshair off its own swing point) and
                // the dive was closed anyway, three ticks above a target it then fell onto unarmed.
                boolean clicked = hands.hit(me);
                if (!spearKit) hands.look(lead);
                if (clicked || spearKit) {
                    phase.macePhase = 0;
                    phase.maceCool = 14;
                    if (!spearKit && me.getOffhandItem().getItem() != Items.SHIELD) inv.toOffhand(me, Items.SHIELD);
                }
            } else if (me.onGround() && (spearKit || phase.maceTicks > 4) || phase.maceTicks > (spearKit ? 40 : 80)) {
                // A hop that did not smash must not restart. Walk into the jab band first.
                if (me.getOffhandItem().getItem() != Items.SHIELD) inv.toOffhand(me, Items.SHIELD);
                phase.macePhase = 0;
                phase.maceCool = spearKit ? 300 : 20; // 300 after every missed hop left 8 smashes in a 90s round
            }
            return hands.decide("mace");
        }
        if (wind < 0 || phase.maceCool > 0 || dist <= 3) {
            int alt = inv.weapon(me);
            if (alt < 0) alt = mace;
            if (!hands.select(me, alt)) return hands.decide("swap");
        }
        return null;
    }

    /** They are far below with no path down: step off over them, or dig down through the floor, and fall on them. */
    PathingCommand drop(Player me, LivingEntity target, boolean los, int mace, int wind) {
        // They are down a drop no path leads down. With a mace the drop is the attack: step off over them and fall on
        // it, and if the fall is going to miss, a wind charge at the feet takes the landing.
        double below = me.getY() - target.getY();
        double gap = me.position().subtract(target.position()).horizontalDistance();
        if (below <= 6 || phase.macePhase != 0) phase.digDown = false;
        if (mace >= 0 && wind >= 0 && phase.macePhase == 0 && phase.pearlStage == 0 && below > 6 && (target.onGround() || phase.digDown)
                && gap < (los ? 2 + below * 0.15 : 12)) {
            if (!me.onGround() && me.getDeltaMovement().y < 0) {
                phase.macePhase = 2;
                phase.maceTicks = 0;
                phase.pearlDive = true;
                return hands.decide("drop");
            }
            if ((!los || phase.digDown) && me.onGround()) {
                // The floor we stand on is what separates us. Walk over them and dig down through it: the hole drops
                // us on them from above, which is the mace's whole attack.
                if (gap > 3.5 && !phase.digDown) { // once the hole is started it stays: they pace about and the fall steers
                    if (!hands.select(me, mace)) return hands.decide("swap");
                    hands.look(target.position());
                    hands.key(Input.MOVE_FORWARD);
                    return hands.decide("drop");
                }
                phase.digDown = true;
                // A hole we cut beside our feet is no use until we step into it: walk to the open column with ordinary
                // movement keys and a smoothed look, like a player stepping off an edge.
                net.minecraft.core.BlockPos feet = me.blockPosition();
                net.minecraft.world.phys.Vec3 hole = null;
                for (int dx = -2; dx <= 2 && hole == null; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        net.minecraft.core.BlockPos c = feet.offset(dx, -1, dz);
                        if (ctx.world().getBlockState(c).getCollisionShape(ctx.world(), c).isEmpty()
                                && ctx.world().getBlockState(c.below()).getCollisionShape(ctx.world(), c.below()).isEmpty()) {
                            hole = net.minecraft.world.phys.Vec3.atBottomCenterOf(c);
                            break;
                        }
                    }
                }
                if (hole != null) {
                    Rotation r = RotationUtils.calcRotationFromVec3d(me.getEyePosition(), hole.add(0, 0.5, 0), ctx.playerRotations());
                    aimer.aim(new Rotation(r.getYaw(), Math.min(r.getPitch(), 60f)), true);
                    if (Math.abs(net.minecraft.util.Mth.wrapDegrees(r.getYaw() - me.getYRot())) < 30) hands.key(Input.MOVE_FORWARD);
                    return hands.decide("dig");
                }
                if (!hands.select(me, mace)) return hands.decide("swap");
                aimer.aim(new Rotation(me.getYRot(), 90f), true);
                if (me.getXRot() > 80f) hands.key(Input.CLICK_LEFT);
                return hands.decide("dig");
            }
            Rotation r = RotationUtils.calcRotationFromVec3d(me.getEyePosition(), target.getBoundingBox().getCenter(), ctx.playerRotations());
            aimer.aim(new Rotation(r.getYaw(), Math.min(r.getPitch(), 60f)), true);
            if (Math.abs(net.minecraft.util.Mth.wrapDegrees(r.getYaw() - me.getYRot())) < 30) hands.key(Input.MOVE_FORWARD);
            return hands.decide("drop");
        }
        return null;
    }

    /**
     * Spear kit, and only when a jab is not available.
     * A straight-down wind charge is a hop: a mace smash just outside the jab band, or a shove out of the dead zone.
     * It is not aimed past the target and it is not used to walk in.
     */
    PathingCommand spearTools(Player me, LivingEntity target, boolean los, int wind, int mace) {
        double hr = horizontalBoxDist(me, target);
        // A hop only just outside the jab, and not again for 15s. Repeating it kept the fight
        // out of the band (easy/medium timed out with one swing). Farther than 4: walk in.
        if (mace >= 0 && wind >= 0 && phase.maceCool == 0 && me.onGround() && !me.isInWater() && los
                && target.onGround() && hr > SPEAR_JAB_HI && hr <= 4.0 && target.getY() <= me.getY() + 1.5) {
            // Phase 1 charges the mace on the ground, then throws. Do not swap to the wind charge here.
            phase.macePhase = 1;
            phase.maceTicks = 0;
            phase.maceCool = 300;
            return hands.decide("mace");
        }
        return null;
    }
}
