/*
 * Ostinato: combat footing (knockback, edge and lava safety). New file, replaces no Baritone code.
 */
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

    static final boolean ON = !"false".equals(System.getProperty("ostinato.footing")); // Ostinato: default since 4 pairs (192 rounds each, SMP lava ring): taken 27.9 vs 31.5 p≈0.0005, fewer lava entries, wins 59 vs 48
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
    private int run(float yaw, int f, int s, boolean sprint, boolean jump, double kx, double kz, double groundY, int hold) {
        sim.copyFrom(start);
        if (kx != 0 || kz != 0) { // LivingEntity.knockback: half the velocity, minus the push, a hop off the ground
            sim.vx = sim.vx / 2 + kx;
            sim.vz = sim.vz / 2 + kz;
            if (sim.onGround) sim.vy = Math.min(0.4, sim.vy / 2 + 0.4);
            sim.onGround = false;
        }
        for (int t = 0; t < HORIZON; t++) {
            boolean held = t < hold; // the wanted keys for a few ticks, then we react; a candidate is kept up
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
    /**
     * True when the foe's next hit, with no keys after it, would carry us into a hazard: no place to start a bite. Logs
     * (lava ring, SMP bots): most lava deaths began with an apple at the rim, a hit, and an airborne drift that no key
     * steers; the guard can only keep us off the rim while we still walk at full pace.
     */
    boolean exposed(Player me, LivingEntity foe, double groundY) {
        if (Double.isNaN(groundY) || me.distanceTo(foe) > 5) return false; // airborne too: logs had most bites start mid-hop
        prep(me);
        double knock = foe.isSprinting() ? KNOCK : 0.5;
        double dx = me.getX() - foe.getX(), dz = me.getZ() - foe.getZ(), len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) return false;
        return run(me.getYRot(), 0, 0, false, false, dx / len * knock, dz / len * knock, groundY, 0) <= HORIZON;
    }

    private void prep(Player me) {
        if (world == null) {
            world = new ClientWorld(ctx);
            start = new PlayerSim(world);
            sim = new PlayerSim(world);
            start2 = new PlayerSim(world);
        }
        world.reset();
        ClientWorld.readPlayer(ctx, start);
        if (me.isUsingItem()) start.speedScale *= 0.2;
    }

    int[] guard(Player me, LivingEntity foe, int f, int s, boolean jump, boolean armed, double groundY) {
        prep(me);
        float yaw = me.getYRot();
        boolean sprint = me.isSprinting() && !me.isUsingItem();
        // a sprint hit pushes 0.9, a plain one 0.4: plan for what this foe is doing, with a little margin
        double knock = foe.isSprinting() ? KNOCK : 0.5;
        double kx = 0, kz = 0;
        if (armed) {
            double dx = me.getX() - foe.getX(), dz = me.getZ() - foe.getZ(), len = Math.sqrt(dx * dx + dz * dz);
            if (len > 1e-4) {
                kx = dx / len * knock;
                kz = dz / len * knock;
            }
        }
        // a crit-jump toward the rim is airborne when the hit lands, and no key steers there: keep the feet down instead
        if (jump && armed && me.onGround()) {
            int up = placeScore(yaw, f, s, sprint, true, foe.getX(), foe.getZ(), knock, groundY);
            if (up <= HORIZON && placeScore(yaw, f, s, sprint, false, foe.getX(), foe.getZ(), knock, groundY) > up) {
                saves++;
                return new int[]{f, s, 0, up, -1};
            }
        }
        int want = score(yaw, f, s, sprint, jump, kx, kz, groundY, 3);
        if (want > HORIZON) return null;
        // on the ground the keys still steer: pick the place that a hit leaves us dry in; in the air, only the next ticks
        boolean place = armed && me.onGround();
        int wantPlace = place ? placeScore(yaw, f, s, sprint, jump, foe.getX(), foe.getZ(), knock, groundY) : want;
        int[] best = null;
        int bestScore = -99, bestDiff = 99;
        for (int jf = 1; jf >= -1; jf--) for (int js = -1; js <= 1; js++) for (int jj = jump ? 1 : 0; jj >= 0; jj--) {
            int sc = place ? placeScore(yaw, jf, js, sprint, jj == 1, foe.getX(), foe.getZ(), knock, groundY)
                    : score(yaw, jf, js, sprint, jj == 1, kx, kz, groundY, HORIZON);
            int diff = Math.abs(jf - f) + Math.abs(js - s) + ((jj == 1) != jump ? 1 : 0);
            if (sc > bestScore || sc == bestScore && diff < bestDiff) {
                best = new int[]{jf, js, jj, wantPlace, sc};
                bestScore = sc;
                bestDiff = diff;
            }
        }
        if (bestScore <= wantPlace) return null; // nothing better than what the fight wanted
        saves++;
        return best;
    }

    /**
     * Where the keys take us matters more than the next hit: after {@link #MOVE} ticks of these keys (and no hazard on
     * the way), how long a hit from the foe's side, there, keeps us dry. A hit carries 2.5-4 blocks through the air,
     * where no key steers, so the useful move is circling until the foe is no longer between us and open ground.
     */
    private int placeScore(float yaw, int f, int s, boolean sprint, boolean jump, double foeX, double foeZ, double knock, double groundY) {
        sim.copyFrom(start);
        for (int t = 0; t < MOVE; t++) {
            sim.tick(yaw, f, s, sprint, jump && t == 0);
            if (hazard(groundY)) return t - MOVE; // worse than any dry place
        }
        double dx = sim.x - foeX, dz = sim.z - foeZ, len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4) return HORIZON + 1;
        start2.copyFrom(sim);
        PlayerSim keep = start;
        start = start2;
        int r = run(yaw, 0, 0, false, false, dx / len * knock, dz / len * knock, groundY, 0);
        start = keep;
        return r;
    }

    /** Ostinato: -Dostinato.pushFoe=true strafes to the side from which our next hit knocks the foe into a hazard. */
    static final boolean PUSH = Boolean.getBoolean("ostinato.pushFoe");
    private PlayerSim foeSim, selfSim;

    /**
     * Ostinato: +1 (right) or -1 (left): the strafe side that, after {@link #MOVE} ticks, lines our hit up so its knockback
     * carries the foe into lava or off a drop soonest; 0 when neither side gets it there (the strafe stays random).
     * The foe is played forward on PlayerSim from where it stands, pushed by a plain hit, keys let go.
     */
    int pushSide(Player me, LivingEntity foe, double groundY) {
        if (!PUSH || Double.isNaN(groundY) || me.distanceTo(foe) > 4) return 0;
        prep(me);
        if (foeSim == null) foeSim = new PlayerSim(world);
        double foeGround = foe.onGround() ? foe.getY() : groundY;
        int best = 0, bestT = HORIZON + 1;
        float yaw = me.getYRot();
        for (int side = -1; side <= 1; side += 2) {
            sim.copyFrom(start);
            boolean dry = true;
            for (int t = 0; t < MOVE && dry; t++) {
                sim.tick(yaw, 0, -side, false, false); // PlayerSim: +1 strafe is A (left)
                if (hazard(groundY)) dry = false;
            }
            if (!dry) continue;
            double dx = foe.getX() - sim.x, dz = foe.getZ() - sim.z, len = Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-4) continue;
            // Ostinato: the spot we strafe to must itself survive the foe's hit (pushing from a rim gets us knocked in first)
            if (selfSim == null) selfSim = new PlayerSim(world);
            selfSim.copyFrom(sim);
            PlayerSim mine = start;
            start = selfSim;
            double k = foe.isSprinting() ? KNOCK : 0.5;
            int ours = run(yaw, 0, 0, false, false, -dx / len * k, -dz / len * k, groundY, 0);
            start = mine;
            if (ours <= HORIZON) continue;
            foeSim.x = foe.getX();
            foeSim.y = foe.getY();
            foeSim.z = foe.getZ();
            var v = foe.getDeltaMovement();
            foeSim.vx = v.x;
            foeSim.vy = v.y;
            foeSim.vz = v.z;
            foeSim.onGround = foe.onGround();
            foeSim.sprinting = false;
            foeSim.collidedH = false;
            PlayerSim keep = start;
            start = foeSim;
            int t = run(yaw, 0, 0, false, false, dx / len * 0.4, dz / len * 0.4, foeGround, 0);
            start = keep;
            if (t < bestT) {
                bestT = t;
                best = side;
            }
        }
        return bestT <= HORIZON ? best : 0;
    }

    static final int MOVE = 6;
    private PlayerSim start2;

    /** The worse of the plain run and the knocked run. */
    private int score(float yaw, int f, int s, boolean sprint, boolean jump, double kx, double kz, double groundY, int hold) {
        int a = run(yaw, f, s, sprint, jump, 0, 0, groundY, hold);
        if (kx == 0 && kz == 0) return a;
        return Math.min(a, run(yaw, f, s, sprint, jump, kx, kz, groundY, hold));
    }
}
