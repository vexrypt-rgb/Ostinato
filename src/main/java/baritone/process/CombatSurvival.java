package baritone.process;

import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import static baritone.process.CombatAim.press;
import static baritone.process.CombatGeometry.*;

/**
 * Staying alive: the totem in the offhand, eating a golden apple, and running (or pearling away) when low with
 * nothing left to heal with. The eat and flee clocks live here because the process's tick reads them.
 */
final class CombatSurvival {
    /** What the process does for us: hotbar, aim, keys, the tick's decision label and the gapple count. */
    interface Hands {
        boolean select(Player me, int slot);
        void look(Vec3 at);
        void use(boolean down);
        void key(Input in);
        PathingCommand decide(String d);
        PathingCommand decide(String d, PathingCommand cmd);
        void gappleEaten();
        void abortCharge();
    }

    int eatTicks, fleeTicks;

    private final IPlayerContext ctx;
    private final CombatInventory inv;
    private final CombatAim aimer;
    private final CombatExplosives explosives;
    private final CombatPhase phase;
    private final Hands hands;
    private LivingEntity target; // set on each entry point; the routines below read the fight's target from it

    CombatSurvival(IPlayerContext ctx, CombatInventory inv, CombatAim aimer, CombatExplosives explosives, CombatPhase phase, Hands hands) {
        this.ctx = ctx;
        this.inv = inv;
        this.aimer = aimer;
        this.explosives = explosives;
        this.phase = phase;
        this.hands = hands;
    }

    boolean canHeal(Player me) {
        return me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING || inv.slotOf(me, Items.GOLDEN_APPLE) >= 0
                || inv.slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0 || inv.potion(me, MobEffects.INSTANT_HEALTH) >= 0;
    }

    /**
     * Decide whether this tick is a bite: low enough, safe enough, nothing in the way. Null when it is not (or the
     * bite did not start), else the "eat" decision. The caller hands over the foe's state; a charge in progress is
     * let go of here so the use key is free for the apple.
     */
    PathingCommand tend(Player me, LivingEntity target, boolean targetEating, boolean overhead, boolean safe, boolean charging, int sinceSwing, int swingGap) {
        // 211909 medium balanced: four apples started inside its sword reach, each dropped
        // when its crit jump read as overhead, each bite costing a 6. 212743 hard aggressive: a
        // bite started at 4.9 took two more. It covers 9 blocks in the 32 ticks. Only a real dive (2 up)
        // stops a bite, and with a shield in hand a bite does not start inside its reach.
        boolean pressed = !targetEating && eyeToBox(me, target) < 9 && target.getMainHandItem().getItem() != Items.MACE
                && (me.getOffhandItem().getItem() == Items.SHIELD && !me.getCooldowns().isOnCooldown(me.getOffhandItem()) || inv.slotOf(me, Items.SHIELD) >= 0);
        // 232733 axe expert safe: 76 ticks at 2.9 HP behind a shield with eight apples, taking 35 damage all fight
        // while it ate its way back to 20 four times. A slow weapon that has just swung cannot swing again
        // before most of a bite is down: that is the opening, shield or no shield.
                boolean opening = sinceSwing >= 1 && sinceSwing <= 5 && (swingGap > 0 ? swingGap : target instanceof Player tp ? tp.getCurrentItemAttackStrengthDelay() : 20) >= 16
                && !(target instanceof Player hp2 && hp2.getCurrentItemAttackStrengthDelay() < 16); // a foe that swapped back to a sword has no slow swing to wait out
        pressed &= !opening;
        // two critical sword hits (4.52 each) take 9.04: the line to eat at, when the foe gives room, is two hits, not one
        // far from the foe the bite is cheap, so top up earlier: a bite that starts at 9 with the foe in reach is a coin flip
        boolean roomy = eyeToBox(me, target) > 6;
        boolean critical = me.getHealth() <= (roomy ? 12 : 9) && (eatTicks > 0 || !pressed);
        boolean longFall = !me.onGround() && me.fallDistance > 3; // a long fall is the whole problem: no time to eat through it
        if (eatTicks > 0 && (overhead && target.getY() > me.getY() + 2.0 || longFall)) {
            hands.use(false);
            eatTicks = 0;
        } else if (!longFall && !(eatTicks == 0 && overhead && target.getY() > me.getY() + 2.0) // cancelling a bite for a dive and restarting it next tick flickered the shield (it needs ~5 steady ticks)
            && !explosives.blastThreat(me) && (!charging || critical) && (eatTicks > 0 || (critical || me.getHealth() <= 11 && (safe || opening) && !pressed || explosives.fighting() && me.getAbsorptionAmount() == 0 && me.getHealth() <= (inv.slotOf(me, Items.RESPAWN_ANCHOR) >= 0 ? 12 : 16)) && (!me.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION) || me.getHealth() <= 8) // regen is too slow to trust when one hit finishes us
                && (inv.slotOf(me, Items.GOLDEN_APPLE) >= 0 || inv.slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0))) {
            if (charging) {
                hands.use(false);
                hands.abortCharge();
            }
            if (eat(me, target)) return hands.decide("eat");
        }
        return null;
    }

    /** Low on health with nothing to heal: pearl away from the target, else run. */
    PathingCommand flee(Player me, LivingEntity target, double dist) {
        this.target = target;
        hands.use(false);
        // the landing costs about 3 HP in this kit: a pearl thrown at 2 HP is a suicide (pillar perfect, tick 243)
        if (dist < 10 && phase.pearlCool == 0 && me.getHealth() + me.getAbsorptionAmount() > 3.5f && inv.slotOf(me, Items.ENDER_PEARL) >= 0) {
            Vec3 away = new Vec3(me.getX() - target.getX(), 0, me.getZ() - target.getZ());
            away = away.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : away.normalize();
            Vec3 at = me.getEyePosition().add(away.scale(24)).add(0, 7, 0);
            Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
            if (!hands.select(me, inv.slotOf(me, Items.ENDER_PEARL))) return hands.decide("swap");
            if (!aimer.face(r.getYaw(), r.getPitch(), 2.5f)) return hands.decide("pearl");
            press(ctx.minecraft().options.keyUse);
            phase.pearlCool = 160;
            return hands.decide("pearl");
        }
        if (dist > 16) {
            fleeTicks = 0;
            return hands.decide("flee"); // clear of it: stand and regenerate
        }
        int blk = inv.blockSlot(me);
        if (++fleeTicks > 40 && dist < 6 && blk >= 0 && me.getY() - target.getY() < 5) {
            // can't shake it: tower up out of melee
            if (!hands.select(me, blk)) return hands.decide("swap");
            aimer.aim(new Rotation(me.getYRot(), 90f), true);
            if (me.onGround()) hands.key(Input.JUMP);
            else if (me.getDeltaMovement().y < 0.1 && ctx.world().getBlockState(me.blockPosition().below()).isAir()) explosives.click(me, me.blockPosition().below().below());
            return hands.decide("pillar");
        }
        return hands.decide("flee", new PathingCommand(new baritone.api.pathing.goals.GoalRunAway(18, target.blockPosition()), PathingCommandType.REVALIDATE_GOAL_AND_PATH));
    }

    boolean eat(Player me, LivingEntity target) {
        this.target = target;
        Item apple = me.getHealth() <= 6 && inv.slotOf(me, Items.ENCHANTED_GOLDEN_APPLE) >= 0 ? Items.ENCHANTED_GOLDEN_APPLE : Items.GOLDEN_APPLE;
        if (inv.slotOf(me, apple) < 0) apple = Items.ENCHANTED_GOLDEN_APPLE;
        if (inv.slotOf(me, apple) < 0) {
            eatTicks = 0;
            return false;
        }
        if (!hands.select(me, inv.slotOf(me, apple))) return true;
        if (me.getMainHandItem().getItem() != apple) return true;
        // a raised offhand shield stays in use across a hotbar switch, so the apple never starts: let go first
        if (me.isUsingItem() && me.getUseItem().getItem() != apple) {
            hands.use(false);
            return true;
        }
        if (eatTicks++ == 0) hands.gappleEaten();
        hands.key(Input.MOVE_BACK); // back off while chewing
        hands.use(true);
        hands.look(target.getEyePosition());
        if (eatTicks > 36) {
            hands.use(false);
            eatTicks = 0;
        }
        return true;
    }

    void keepTotem(Player me) {
        if (me.getHealth() > 8 && !explosives.fighting() || me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING) return;
        inv.toOffhand(me, Items.TOTEM_OF_UNDYING);
    }
}
