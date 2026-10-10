package baritone.process;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import baritone.api.process.PathingCommand;
import baritone.api.utils.input.Input;
import static baritone.process.CombatGeometry.*;
import static baritone.process.CombatInventory.*;

/**
 * The melee exchange once nothing special applies: close in or make space, break a raised shield with an axe, and
 * pick the moment to swing: a crit on the fall, a jump to set one up, a ground hit with a w-tap. The process works
 * out the tick's facts (reach, charge, immunity, rush) and hands them over as fields before calling
 * {@link #exchange}; the crit and jump clocks live here.
 */
final class CombatMelee {
    /** What the process does for us: keys, a swing that reports whether it connected, the decision label and the counters. */
    interface Hands {
        void key(Input in);
        boolean hit(Player me);
        boolean select(Player me, int slot);
        void look(Vec3 at);
        PathingCommand decide(String d);
        void axeHit();
        void crit();
        void sprintHit();
    }

    boolean critArmed;
    int groundedJumps;

    // this tick's facts, set by prepare()
    boolean inReach, chase, duel, canJump, axeTime, targetReady, falling, rushed, immune, run, hurry;
    float cd;

    private final CombatInventory inv;
    private final CombatSwing swing;
    private final CombatTargeting targeting;
    private final CombatShield shield;
    private final CombatMovement movement;
    private final Hands hands;

    CombatMelee(CombatInventory inv, CombatSwing swing, CombatTargeting targeting, CombatShield shield, CombatMovement movement, Hands hands) {
        this.inv = inv;
        this.swing = swing;
        this.targeting = targeting;
        this.shield = shield;
        this.movement = movement;
        this.hands = hands;
    }

    /**
     * Read the tick's facts about the foe and our own state (shield, charge, immunity, rush) and equip for the swing.
     * Null once ready, else the swap decision. Runs before {@link #exchange}, which reads the facts as fields.
     */
    PathingCommand prepare(Player me, LivingEntity target, boolean inReach, boolean chase, boolean targetEating) {
        this.inReach = inReach;
        this.chase = chase;
        cd = me.getAttackStrengthScale(0.5f);
        // a disabled shield stays "raised" for its 5s cooldown; don't keep throwing uncharged axe swings at it
        // only a shield past its 5-tick warm-up is disabled by the axe; a swing into the raise is a wasted cooldown
        // VexBot's shield blocks from its first tick (no warm-up), so the use flag is the shield
        boolean tgShield = target.isBlocking() || target.isUsingItem() && target.getUseItem().getItem() == Items.SHIELD;
        if (tgShield && me.tickCount - shield.lastAxeTick >= 4) shield.axeHeld = false; // it is back up: that swing did not disable it
        // the axe drew blood: there was no shield in its way, so none is on cooldown either
        if (shield.axeHeld && me.tickCount - shield.lastAxeTick <= 3 && target.hurtTime >= 8) shield.axeHeld = false;
        this.duel = target.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS) && target.getOffhandItem().getItem() == Items.SHIELD;
        // 224436 expert adaptive: swords at t17 and t32 did nothing against a target showing no use flag.
        // A blockhitter's shield goes up with its swing, flag or no flag; the window is learned from bounced hits.
        if (duel && shield.hiddenShield(me, target)) tgShield = true;
        this.axeTime = tgShield && inReach && me.tickCount - shield.lastAxeTick > 8 && inv.best(me, AXES) >= 0;
        if (!hands.select(me, axeTime ? inv.best(me, AXES) : inv.weapon(me))) return hands.decide("swap");
        hands.look(swing.swingPoint(me, target, targeting.velocity(target)));

        this.targetReady = me.tickCount - shield.targetSwingTick >= 10; // its sword is charged: whoever swings first wins the exchange
        // the tick the use key comes up, an attack click is still swallowed by the item in use
        this.immune = target.hurtTime > 1 || me.tickCount - shield.lastShieldTick < (shield.counter ? 2 : 3)
                || tgShield && inv.best(me, AXES) >= 0; // a sword into a raised shield is a wasted cooldown
        this.falling = !me.onGround() && me.getDeltaMovement().y < -0.05;
        this.canJump = me.onGround() && !me.isInWater() && !me.isInLava() && !me.onClimbable();
        this.hurry = me.getCurrentItemAttackStrengthDelay() < 14;
        // 233514 axe expert adaptive: it ate from 4 HP back to 20 under plain 3.84s swung on the run, and an apple
        // is worth 8. A target chewing in reach is not going anywhere: it gets the crit like any other.
        this.run = chase && (hurry || !targetEating);
        // Same fight, t544: jumped fully charged and hung in the air for the fall while its axe, due, landed first.
        // With its next swing due inside the jump, the charged swing goes now.
        int swingAge = me.tickCount - shield.targetSwingTick;
        this.rushed = !hurry && !targetEating && cd >= 0.95f
                && swingAge >= (shield.swingGap > 0 ? shield.swingGap : target instanceof Player tp ? (int) tp.getCurrentItemAttackStrengthDelay() : 20) - 6;
        return null;
    }

    PathingCommand exchange(Player me, LivingEntity target, double dist) {
        if (!inReach) movement.steer(me, target, dist, chase, critArmed);
        else if (dist < 1.4) hands.key(Input.MOVE_BACK); // sword: too close, make space
        // its reach is measured centre to centre, a little shorter than ours: recharge just outside it
        else if (duel && cd < 0.75f && dist < 2.9) hands.key(Input.MOVE_BACK);
        if (me.hurtTime == me.hurtDuration - 1 && canJump) hands.key(Input.JUMP); // jump reset

        if (axeTime && inReach && me.tickCount - shield.lastShieldTick >= 2) { // an axe disables a raised shield whatever the charge
            if (hands.hit(me)) { // only a click that connected starts the wait for its shield
                hands.axeHit();
                shield.lastAxeTick = me.tickCount;
                shield.axeHeld = true;
            }
            return hands.decide("axe");
        }
        // Only in the first two ticks of a jump. A sword jumped at 0.55 is charged five ticks
        // later, still rising, and this swing took the crit that was two ticks away.
        if (!me.onGround() && (me.getDeltaMovement().y > 0.25 && targetReady || !falling && rushed) && inReach && cd >= 0.95f && !immune) {
            hands.hit(me); // don't hang in the air waiting for a crit while it swings first
            critArmed = false;
            return hands.decide("hit");
        }
        // 1740 fight tick 334: swung on the first tick with y speed below zero, before the move
        // that makes fallDistance positive. Vanilla wants fallDistance > 0, so it was a plain 1.56.
        if (critArmed && falling && me.fallDistance > 0 && inReach && cd >= 0.9f && !immune) {
            hands.hit(me);
            hands.crit();
            critArmed = false;
            return hands.decide("hit");
        }
        if ((run || shield.counter) && inReach && cd >= 0.9f && !immune) {
            hands.hit(me);
            return hands.decide("hit");
        }
        if (!me.onGround() && me.getDeltaMovement().y < 0.08 && dist <= REACH + 0.6 && cd >= 0.5f && (inReach || !chase)) {
            movement.wtap = Math.max(movement.wtap, 1); // let go of forward for a tick so the sprint drops: a sprinting hit is never a crit
            critArmed = true;
        }
        if (!me.onGround() && !critArmed && me.getDeltaMovement().y < 0.08 && inReach && cd >= 0.95f && !immune) {
            hands.hit(me); // knocked airborne without a crit set up: don't waste the cooldown
            return hands.decide("hit");
        }
        if (me.onGround()) critArmed = false;
        else groundedJumps = 0;

        boolean breached = shield.axeHeld && me.tickCount - shield.lastAxeTick < 100;
        // 232146 axe expert adaptive: 47 ground hits of 3.65 into an opponent eating apples, two jumps all fight.
        // A slow weapon is charged by the time the jump falls, so the crit costs nothing: only a sword hurries.
        if (breached && hurry && inReach && cd >= 0.95f && !immune) {
            hands.hit(me); // its shield is on cooldown: land the follow-up as soon as the sword is charged
            return hands.decide("hit");
        }
        boolean diving = !target.onGround() && targeting.velocity(target).y < -0.2 && target.getY() > me.getY() + 1.5;
        // The fall starts six ticks after the jump. A mace or axe jumped at 0.55 landed before it was charged.
        float jumpCd = Math.max(0.55f, 1 - 6f / me.getCurrentItemAttackStrengthDelay());
        if (!(breached && hurry) && !duel && !diving && !run && !shield.counter && !(rushed && inReach) && canJump && dist <= REACH + 0.8 && cd >= jumpCd && !immune && groundedJumps < 4) {
            hands.key(Input.JUMP);
            groundedJumps++;
            return hands.decide("jump");
        }
        if (me.onGround() && inReach && cd >= (duel ? 0.9f : 0.95f) && !immune && (duel || !canJump || groundedJumps >= 4 || rushed)) {
            boolean sprint = me.isSprinting();
            hands.hit(me);
            if (sprint) {
                hands.sprintHit();
                movement.wtap = 2;
            }
            groundedJumps = 0;
            return hands.decide("hit");
        }
        return hands.decide("strafe");
    }
}
