package baritone.process;

import baritone.api.utils.IPlayerContext;
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.kinematic.PlayerSim;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Ostinato: knockback, edge and lava safety on {@link PlayerSim}. The keys a combat decision pressed are played forward
 * for {@link #HORIZON} ticks, once as they are and once after the foe's hardest hit lands now (a sprint hit's
 * knockback, away from it). If either run ends in lava or fire or a fall past {@link #MAX_DROP}, the nearest keys whose
 * runs stay dry replace them; if no keys are safe after a hit, the ones that put it off longest. A floor scan alone
 * missed lava: lava has no collision box, so the dirt under the lake counted as floor.
 */
final class CombatFooting {

    static final boolean ON = Boolean.getBoolean("ostinato.footing");
    static final int HORIZON = 12;
    static final double MAX_DROP = 3.5;
    /** Knockback of a sprint hit: 0.4 base plus 0.5 for sprinting (LivingEntity.knockback, Player.attack). */
    static final double KNOCK = 0.9;

    private final IPlayerContext ctx;
    private ClientWorld world;
    private PlayerSim start, sim;
    /** Ticks the guard changed the keys (for the fight log and the bench). */
    int saves;

    CombatFooting(IPlayerContext ctx) {
        this.ctx = ctx;
    }

    /** Result of a run: ticks until the hazard, HORIZON + 1 when none came. */
    private int run(float yaw, int f, int s, boolean sprint, boolean jump, double kx, double kz, double groundY) {
        sim.copyFrom(start);
        if (kx != 0 || kz != 0) { // LivingEntity.knockback: half the velocity, minus the push, a hop off the ground
            sim.vx = sim.vx / 2 + kx;
            sim.vz = sim.vz / 2 + kz;
            if (sim.onGround) sim.vy = Math.min(0.4, sim.vy / 2 + 0.4);
            sim.onGround = false;
        }
        for (int t = 0; t < HORIZON; t++) {
            boolean held = t < 3; // a few ticks of the same keys, then we react: let go
            sim.tick(yaw, held ? f : 0, held ? s : 0, sprint && held, jump && t == 0);
            if (hazard(groundY)) return t;
        }
        return HORIZON + 1;
    }

    private final BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

    private boolean hazard(double groundY) {
        if (sim.y < groundY - MAX_DROP) return true;
        int fy = Mth.floor(sim.y);
        for (int dx = -1; dx <= 1; dx += 2) for (int dz = -1; dz <= 1; dz += 2) {
            int x = Mth.floor(sim.x + dx * 0.299), z = Mth.floor(sim.z + dz * 0.299);
            // the feet's cell: standing on the rim beside a lake, the lava is a cell lower and never touches us
            BlockState st = ctx.world().getBlockState(p.set(x, fy, z));
            if (st.getFluidState().is(FluidTags.LAVA) && sim.y < fy + 0.9) return true;
            if (st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE)) return true;
        }
        return false;
    }

    /**
     * Returns {f, s, jump} to press instead of the wanted keys, or null when the wanted keys are safe. {@code armed}:
     * the foe can hit us within the horizon, so its knockback counts.
     */
    int[] guard(Player me, LivingEntity foe, int f, int s, boolean jump, boolean armed, double groundY) {
        if (world == null) {
            world = new ClientWorld(ctx);
            start = new PlayerSim(world);
            sim = new PlayerSim(world);
        }
        world.reset();
        ClientWorld.readPlayer(ctx, start);
        float yaw = me.getYRot();
        boolean sprint = me.isSprinting();
        double kx = 0, kz = 0;
        if (armed) {
            double dx = me.getX() - foe.getX(), dz = me.getZ() - foe.getZ(), len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1e-4) {
                kx = dx / len * KNOCK;
                kz = dz / len * KNOCK;
            }
        }
        if (score(yaw, f, s, sprint, jump, kx, kz, groundY) > HORIZON) return null;
        int[] best = null;
        int bestScore = -1, bestDiff = 99;
        for (int jf = 1; jf >= -1; jf--) for (int js = -1; js <= 1; js++) for (int jj = jump ? 1 : 0; jj >= 0; jj--) {
            int sc = score(yaw, jf, js, sprint, jj == 1, kx, kz, groundY);
            int diff = Math.abs(jf - f) + Math.abs(js - s) + ((jj == 1) != jump ? 1 : 0);
            if (sc > bestScore || sc == bestScore && diff < bestDiff) {
                best = new int[]{jf, js, jj};
                bestScore = sc;
                bestDiff = diff;
            }
        }
        saves++;
        return best;
    }

    /** The worse of the plain run and the knocked run. */
    private int score(float yaw, int f, int s, boolean sprint, boolean jump, double kx, double kz, double groundY) {
        int a = run(yaw, f, s, sprint, jump, 0, 0, groundY);
        if (kx == 0 && kz == 0) return a;
        return Math.min(a, run(yaw, f, s, sprint, jump, kx, kz, groundY));
    }
}
