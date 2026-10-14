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
    // Ostinato: humanReactionDelay, when the last fight look was and where it pointed
    private int lastLookTick = Integer.MIN_VALUE / 2;
    private float lastAimYaw, lastAimPitch;

    CombatAim(Baritone baritone, IPlayerContext ctx, Random rng) {
        this.baritone = baritone;
        this.ctx = ctx;
        this.rng = rng;
    }

    /** Look at the point; {@code targetSpeed} is the target's horizontal speed in blocks per tick (wander grows with it). */
    void look(Vec3 at, double targetSpeed) {
        Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), at, ctx.playerRotations());
        noticeSurprise(r);
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

    /**
     * Ostinato: a target that is new (no fight look for a second) or that jumped far from the last aim (a new threat,
     * a switch after being hit) is a surprise, so the hand waits a reaction time before turning (humanReactionDelay).
     * A target already near the crosshair is not a surprise.
     */
    private void noticeSurprise(Rotation r) {
        Player me = ctx.player();
        if (me == null) return;
        int t = me.tickCount;
        boolean fresh = t - lastLookTick > 20;
        boolean jumped = !fresh && Math.hypot(Mth.wrapDegrees(r.getYaw() - lastAimYaw), r.getPitch() - lastAimPitch) > 60;
        boolean offView = Math.hypot(Mth.wrapDegrees(r.getYaw() - me.getYRot()), r.getPitch() - me.getXRot()) > 10;
        if (HUMANIZE && (fresh || jumped) && offView) baritone.getLookBehavior().surprise();
        lastLookTick = t;
        lastAimYaw = r.getYaw();
        lastAimPitch = r.getPitch();
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
        baritone.getLookBehavior().humanReactive(); // Ostinato: reactive, so a surprise hold applies
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
