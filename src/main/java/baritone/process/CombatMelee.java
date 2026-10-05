package baritone.process;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
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
        PathingCommand decide(String d);
        void axeHit();
        void crit();
        void sprintHit();
    }

    boolean critArmed;
    int groundedJumps;

    // this tick's facts, set by the process just before exchange()
    boolean inReach, chase, duel, canJump, axeTime, targetReady, falling, rushed, immune, run, hurry;
    float cd;

    private final CombatInventory inv;
    private final CombatTargeting targeting;
    private final CombatShield shield;
    private final CombatMovement movement;
    private final Hands hands;

    CombatMelee(CombatInventory inv, CombatTargeting targeting, CombatShield shield, CombatMovement movement, Hands hands) {
        this.inv = inv;
        this.targeting = targeting;
        this.shield = shield;
        this.movement = movement;
        this.hands = hands;
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
