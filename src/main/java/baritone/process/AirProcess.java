package baritone.process;

import baritone.pathing.movement.Moves;
import baritone.pathing.movement.CalculationContext;
import baritone.api.utils.BetterBlockPos;
import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.movements.MovementSwim;
import baritone.utils.BaritoneProcessHelper;
import net.minecraft.util.math.BlockPos;

/**
 * Breath: when swimming low on air, path to the surface (around ceilings like a shipwreck hull,
 * which pressing jump in place cannot get past), wait there until the bar is full, then hand
 * control back to whatever process was running.
 */
public final class AirProcess extends BaritoneProcessHelper {

    private boolean active;
    private int surfaceY;
    /** Built once per surfacing: a fresh Goal each tick would look like a goal change and restart the search. */
    private Goal goal;

    public AirProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public boolean isActive() {
        if (ctx.player() == null || ctx.world() == null || !Baritone.settings().swimInWater.value) {
            return active = false;
        }
        int air = ctx.player().getAir(), max = ctx.player().getMaxAir();
        if (!active && air < max / 3 && ctx.player().isInWater()) {
            active = true;
            surfaceY = findSurfaceY();
            goal = surfaceGoal(surfaceY);
            logDebug("Low on air (" + air + "), surfacing to y=" + surfaceY + " " + probe());
        } else if (active && air >= max) {
            active = false;
        }
        return active;
    }

    private String probe() {
        CalculationContext c = new CalculationContext(baritone);
        BetterBlockPos p = ctx.playerFeet();
        StringBuilder b = new StringBuilder("feet=" + p + " ");
        for (Moves m : Moves.values()) {
            if (m.name().startsWith("SWIM")) {
                double v = m.cost(c, p.x, p.y, p.z);
                b.append(m.name().substring(5)).append('=').append(v >= 1e6 ? "INF" : String.format("%.1f", v)).append(' ');
            }
        }
        for (int dy = -1; dy <= 2; dy++) b.append("b").append(dy).append('=').append(ctx.world().getBlockState(p.up(dy)).getBlock().getTranslationKey().replace("block.minecraft.", "")).append(' ');
        return b.toString();
    }

    /** Top of the water nearby: highest y over a 9x9 area whose block is water with a non-water block above. */
    private int findSurfaceY() {
        BlockPos feet = ctx.playerFeet();
        int best = feet.getY();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int y = feet.getY(); y < feet.getY() + 64 && y < 255; y++) {
                    BlockPos p = new BlockPos(feet.getX() + dx, y, feet.getZ() + dz);
                    if (!MovementHelper.isWater(ctx.world().getBlockState(p))) {
                        if (y - 1 > best && MovementHelper.isWater(ctx.world().getBlockState(p.down()))) best = y - 1;
                        break;
                    }
                }
            }
        }
        return best;
    }

    private static Goal surfaceGoal(int target) {
        return new Goal() {
            @Override
            public boolean isInGoal(int x, int y, int z) {
                return y >= target;
            }

            @Override
            public double heuristic(int x, int y, int z) {
                return Math.max(0, target - y) * MovementSwim.SWIM_ONE_BLOCK_COST;
            }

            @Override
            public String toString() {
                return "Surface{y=" + target + "}";
            }
        };
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (goal.isInGoal(ctx.playerFeet())) {
            // Bob at the surface while breathing; holding jump keeps the head out of the water.
            baritone.getInputOverrideHandler().setInputForceState(Input.JUMP, true);
            return new PathingCommand(goal, PathingCommandType.REQUEST_PAUSE);
        }
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        active = false;
    }

    @Override
    public String displayName0() {
        return "Surfacing for air";
    }

    @Override
    public boolean isTemporary() {
        return true;
    }

    @Override
    public double priority() {
        return 4;
    }
}
