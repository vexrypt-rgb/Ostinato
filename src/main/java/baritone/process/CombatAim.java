package baritone.process;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Random;

/**
 * Where the view points. Every look goes out through LookBehavior.human() as a bounded, mouse-stepped move, and
 * {@link #look} adds a slow Gaussian wander so tracking is never perfect. The Random is the process's shared one,
 * so a fixed seed still reproduces a fight.
 */
final class CombatAim {
    static final boolean HUMANIZE = !"false".equals(System.getProperty("ostinato.humanize"));

    private final Baritone baritone;
    private final IPlayerContext ctx;
    private final Random rng;
    private double wanderY, wanderP, wanderVy, wanderVp;

    CombatAim(Baritone baritone, IPlayerContext ctx, Random rng) {
        this.baritone = baritone;
        this.ctx = ctx;
        this.rng = rng;
    }

    /** Look at the point; {@code targetSpeed} is the target's horizontal speed in blocks per tick (wander grows with it). */
    void look(Vec3 at, double targetSpeed) {
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
        if (HUMANIZE) {
            // a hand on a mouse never tracks perfectly: a slow wander around the aim point, bigger when the target moves fast
            double energy = 0.5 + Math.min(1.0, targetSpeed * 3);
            wanderVy = (wanderVy + rng.nextGaussian() * 0.12 * energy) * 0.82;
            wanderVp = (wanderVp + rng.nextGaussian() * 0.07 * energy) * 0.82;
            wanderY = Mth.clamp((wanderY + wanderVy) * 0.96, -1.8, 1.8);
            wanderP = Mth.clamp((wanderP + wanderVp) * 0.96, -1.0, 1.0);
            r = new Rotation(r.getYaw() + (float) wanderY, Mth.clamp(r.getPitch() + (float) wanderP, -90f, 90f));
        }
        aim(r, true);
    }

    /** Whether our view is within {@code deg} degrees of looking at the point. */
    boolean aimedAt(Player me, Vec3 at, float deg) {
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
        return Math.abs(Mth.wrapDegrees(r.getYaw() - me.getYRot())) <= deg && Math.abs(r.getPitch() - me.getXRot()) <= deg;
    }

    /** A real key press: queued like a keyboard or mouse event and handled at the start of the next client tick. */
    static void press(net.minecraft.client.KeyMapping km) {
        net.minecraft.client.KeyMapping.click(com.mojang.blaze3d.platform.InputConstants.getKey(km.saveString()));
    }

    void aim(Rotation r, boolean blockInteract) {
        baritone.getLookBehavior().updateTarget(r, blockInteract);
    }

    boolean face(float yaw, float pitch, float tol) {
        aim(new Rotation(yaw, pitch), true);
        Player me = ctx.player();
        return Math.abs(Mth.wrapDegrees(yaw - me.getYRot())) <= tol && Math.abs(pitch - me.getXRot()) <= tol;
    }

    /**
     * Foot wind-charge only. Pitch is set to 90 and use is pressed on this tick, before keybinds,
     * so the charge leaves straight down. A smoothed look makes it hit the ground ahead. Other aims stay human.
     */
    boolean throwStraightDown(Player me) {
        aim(new Rotation(me.getYRot(), 90f), true);
        if (me.getXRot() < 78f) return false; // still tipping down: a 90 degree pitch change inside one tick is a snap
        press(ctx.minecraft().options.keyUse);
        return true;
    }
}
