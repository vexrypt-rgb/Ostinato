package baritone.process;

import java.util.Random;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;

/**
 * Walking in a fight: the strafe that changes direction every so often, the w-tap, the sprint-jump run-down of a
 * retreating foe, and the zig-zag toward an aimed shooter. Draws share the process's seeded random so a seed still
 * replays the same fight.
 */
final class CombatMovement {
    /** What the process does for us: press a key for this tick. */
    interface Hands {
        void key(Input in);
    }

    private static final boolean KINEMATIC = !"false".equals(System.getProperty("ostinato.kinematic"));

    /** Ticks of forward to hold released so the sprint drops before a hit; the process's hit logic sets it. */
    int wtap;

    private int strafeDir = 1, strafeLeft, dodgeLeft, dodgeDir = 1;
    private baritone.pathing.kinematic.KinematicController kin;

    private final IPlayerContext ctx;
    private final Random rng;
    private final Hands hands;

    CombatMovement(IPlayerContext ctx, Random rng, Hands hands) {
        this.ctx = ctx;
        this.rng = rng;
        this.hands = hands;
    }

    void steer(Player me, LivingEntity target, double dist, boolean chase, boolean critArmed) {
        if (--strafeLeft <= 0) {
            strafeDir = rng.nextBoolean() ? 1 : -1;
            strafeLeft = 10 + rng.nextInt(20);
        }
        if (chase) {
            wtap = 0;
            hands.key(Input.MOVE_FORWARD);
            if (me.getFoodData().getFoodLevel() > 6) hands.key(Input.SPRINT);
            if (dist > 3.5 && me.onGround() && me.isSprinting() && !me.isInWater()) hands.key(Input.JUMP);
            return;
        }
        if (wtap > 0) {
            wtap--;
        } else if (dist > 2.4) {
            hands.key(Input.MOVE_FORWARD);
            if (!critArmed && me.getFoodData().getFoodLevel() > 6) hands.key(Input.SPRINT);
        } else if (dist < 1.2) {
            hands.key(Input.MOVE_BACK);
        }
        if (dist > 3.5) {
            // it's backing off to heal: run it down in a straight line, sprint-jumping for speed
            if (me.onGround() && me.isSprinting() && !me.isInWater() ) hands.key(Input.JUMP);
            return;
        }
        hands.key(strafeDir > 0 ? Input.MOVE_RIGHT : Input.MOVE_LEFT);
    }

    /** Close in on an aimed shooter, reversing sideways often enough that its lead on our velocity is wrong. */
    void dodgeRanged(Player me) {
        if (--dodgeLeft <= 0) {
            dodgeDir = -dodgeDir;
            dodgeLeft = 4 + rng.nextInt(6);
        }
        hands.key(Input.MOVE_FORWARD);
        hands.key(dodgeDir > 0 ? Input.MOVE_RIGHT : Input.MOVE_LEFT);
        if (me.getFoodData().getFoodLevel() > 6) hands.key(Input.SPRINT);
        if (me.onGround() && rng.nextInt(12) == 0) hands.key(Input.JUMP);
    }
}
