/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * Instant aim: a requested rotation is written to the player the same tick, so the movement packet for
 * this tick carries it. Vanilla's LookBehavior slews and caps the turn; headless there is no hand and
 * no visual continuity to preserve, and the aim processor reports every requested rotation as final.
 */
package baritone.headless;

import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.utils.Rotation;
import net.minecraft.util.Mth;

public final class HeadlessLookBehavior implements ILookBehavior {

    private final HeadlessPlayer player;
    private final AimProcessor aim = new AimProcessor();

    public HeadlessLookBehavior(HeadlessPlayer player) {
        this.player = player;
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract) {
        // Vanilla applies the target at the start of the next tick; the headless tick applies it now, before
        // the movement packet is written, so the server sees the same yaw the movement was computed for.
        player.setYRot(Mth.wrapDegrees(rotation.getYaw()));
        player.setXRot(Mth.clamp(rotation.getPitch(), -90f, 90f));
    }

    @Override
    public IAimProcessor getAimProcessor() {
        return aim;
    }

    private static final class AimProcessor implements ITickableAimProcessor {
        @Override
        public Rotation peekRotation(Rotation desired) {
            return desired;
        }

        @Override
        public Rotation peekAim(Rotation desired) {
            return desired;
        }

        @Override
        public Rotation peekSettledRotation(Rotation desired) {
            return desired;
        }

        @Override
        public ITickableAimProcessor fork() {
            return this;
        }

        @Override
        public void tick() {
        }

        @Override
        public void advance(int ticks) {
        }

        @Override
        public Rotation nextRotation(Rotation rotation) {
            return rotation;
        }
    }
}
