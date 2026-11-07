package baritone.scherzo;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/** Local walking only: checks every intermediate footprint; the existing combat controller remains the input owner. */
public final class ManeuverAdvice {
    private final Scherzo scherzo;
    public ManeuverAdvice(Scherzo scherzo) { this.scherzo = scherzo; }
    public ManeuverChoice.Candidate choose(Player me, LivingEntity target) {
        CombatSnapshot snapshot = scherzo.snapshot();
        if (!scherzo.live() || snapshot == null || snapshot.targetId() != target.getId() || snapshot.tick() != me.level().getGameTime()
                || !me.onGround() || me.isInWater() || me.isInLava() || me.isFallFlying()) return null;
        Prediction prediction = scherzo.predict(3);
        double yaw = Math.toRadians(me.getYRot());
        // Vanilla forward (-sin,cos), right (-cos,-sin); 0.3 blocks is a short walking probe, not a path promise.
        int[][] inputs = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        ManeuverChoice.Candidate[] candidates = new ManeuverChoice.Candidate[inputs.length];
        for (int i = 0; i < inputs.length; i++) {
            int f = inputs[i][0], s = inputs[i][1];
            double dx = (-Math.sin(yaw) * f - Math.cos(yaw) * s) * 0.3;
            double dz = (Math.cos(yaw) * f - Math.sin(yaw) * s) * 0.3;
            boolean safe = true;
            for (int step = 1; step <= 3 && safe; step++) {
                var box = me.getBoundingBox().move(dx * step / 3, 0, dz * step / 3);
                safe = me.level().noCollision(me, box);
                for (double x : new double[]{box.minX, box.maxX}) for (double z : new double[]{box.minZ, box.maxZ}) {
                    BlockPos floor = BlockPos.containing(x, box.minY - 0.05, z);
                    var state = me.level().getBlockState(floor);
                    safe &= state.isFaceSturdy(me.level(), floor, Direction.UP)
                            && state.getFluidState().isEmpty()
                            && !state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                            && !state.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                            && !state.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE);
                }
            }
            candidates[i] = new ManeuverChoice.Candidate(f, s, me.getX() + dx, me.getZ() + dz, safe);
        }
        return ManeuverChoice.choose(prediction, 1.5 + target.getBbWidth() / 2, 2.8 + target.getBbWidth() / 2, candidates);
    }
}
