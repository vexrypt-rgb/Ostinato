/*
 * Ostinato: PlayerSim as a physics transition. Does not change PlayerSim. Not part of upstream Baritone.
 */
package baritone.contract;

import baritone.pathing.kinematic.PlayerSim;

/** One tick of {@link PlayerSim}. Land travel has no sneak; a sneak tick is rejected rather than dropped. */
public final class PlayerSimPhysics implements PhysicsTransition {

    /** 26.3 land tick (physics-263, DECISIONS D12). Not the predictive physics engine. */
    public static final String SEMANTICS = "PlayerSim-26.3-land-tick";

    private final PlayerSim sim;
    private float yaw;

    public PlayerSimPhysics(PlayerSim sim) {
        this.sim = sim;
    }

    /** Feet at y=0, then three idle ticks, matching {@code PhysicsPathfinderTest.standingAt}. */
    public static PlayerSimPhysics settled(PlayerSim.World world, double x, double z) {
        PlayerSim s = new PlayerSim(world);
        s.x = x;
        s.y = 0;
        s.z = z;
        for (int i = 0; i < 3; i++) {
            s.tick(0, false, false, false);
        }
        return new PlayerSimPhysics(s);
    }

    public PlayerSim sim() {
        return sim;
    }

    public PlayerSimPhysics copy() {
        PlayerSimPhysics copy = new PlayerSimPhysics(new PlayerSim(sim.world()).copyFrom(sim));
        copy.yaw = yaw;
        return copy;
    }

    /** Records yaw for the next control. Does not tick and does not change position or velocity. */
    public void face(float yaw) {
        this.yaw = yaw;
    }

    @Override
    public String semantics() {
        return SEMANTICS;
    }

    @Override
    public PlayerState state() {
        return new PlayerState(sim.x, sim.y, sim.z, sim.vx, sim.vy, sim.vz, sim.onGround, yaw);
    }

    @Override
    public PlayerState step(ControlTick control) {
        if (control.sneak()) {
            throw new IllegalArgumentException("PlayerSim land tick has no sneak");
        }
        if (control.forward() && control.back()) {
            throw new IllegalArgumentException("forward and back together");
        }
        if (control.left() && control.right()) {
            throw new IllegalArgumentException("left and right together");
        }
        int forward = control.forward() ? 1 : control.back() ? -1 : 0;
        int strafe = control.left() ? 1 : control.right() ? -1 : 0;
        yaw = control.yaw();
        sim.tick(yaw, forward, strafe, control.sprint(), control.jump());
        return state();
    }
}
