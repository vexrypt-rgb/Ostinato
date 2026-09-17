package baritone.movement;

import baritone.api.movement.IMovementBackend;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.utils.Helper;
import baritone.api.utils.interfaces.IGoalRenderPos;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reflection bridge to {@code kaptainwutax.tungsten} (3ndetz/Tungsten altoclef-compat).
 * Soft-fails when the Tungsten jar is absent so Ostinato still runs Baritone-only.
 *
 * Responsibility split (same as TenorClef):
 * - Tungsten = physics A* travel / parkour / chase when selected
 * - Baritone = mining, digging, inventory, schematics (unchanged)
 *
 * Real API (vendor commit matching TenorClef pin):
 * - TungstenModDataContainer.PATHFINDER.find(WorldView/LevelReader, Vec3d/Vec3, Player)
 * - PATHFINDER.active / PATHFINDER.stop (AtomicBoolean)
 * - EXECUTOR.isRunning() / EXECUTOR.stop
 * - FollowEntityTask.start(Entity, double) / stop() / isActive()
 */
public final class TungstenMovementBackend implements IMovementBackend, Helper {

    public static final TungstenMovementBackend INSTANCE = new TungstenMovementBackend();

    private static final String MOD = "kaptainwutax.tungsten.TungstenMod";
    private static final String DATA = "kaptainwutax.tungsten.TungstenModDataContainer";
    private static final String FOLLOW = "kaptainwutax.tungsten.task.FollowEntityTask";

    private Boolean present;
    private String presentDetail = "not probed";

    private Object pathfinder;
    private Field pathfinderActive;
    private Field pathfinderStop;
    private Method pathfinderFind;
    private Field executorField;
    private Method executorIsRunning;
    private Field executorStop;
    private Field targetField;

    private Method followStart;
    private Method followStop;
    private Method followIsActive;

    private TungstenMovementBackend() {}

    @Override
    public String id() {
        return "tungsten";
    }

    @Override
    public synchronized boolean isAvailable() {
        return probe();
    }

    public String detail() {
        probe();
        return presentDetail;
    }

    private synchronized boolean probe() {
        if (present != null) {
            return present;
        }
        present = false;
        try {
            Class.forName(MOD, false, TungstenMovementBackend.class.getClassLoader());
            Class<?> dataClass = Class.forName(DATA, false, TungstenMovementBackend.class.getClassLoader());
            Class<?> followClass = Class.forName(FOLLOW, false, TungstenMovementBackend.class.getClassLoader());

            Field pfField = dataClass.getField("PATHFINDER");
            pathfinder = pfField.get(null);
            if (pathfinder == null) {
                presentDetail = "PATHFINDER null";
                return false;
            }
            Class<?> pfClass = pathfinder.getClass();
            pathfinderActive = pfClass.getField("active");
            pathfinderStop = pfClass.getField("stop");
            // Mojmap names; at runtime both Ostinato and remapped Tungsten share intermediary types.
            pathfinderFind = pfClass.getMethod("find", LevelReader.class, Vec3.class, Player.class);

            executorField = dataClass.getField("EXECUTOR");
            targetField = Class.forName(MOD).getField("TARGET");

            followStart = followClass.getMethod("start", Entity.class, double.class);
            followStop = followClass.getMethod("stop");
            followIsActive = followClass.getMethod("isActive");

            present = true;
            presentDetail = "kaptainwutax.tungsten bound";
            logDirect("Ostinato Tungsten backend: " + presentDetail);
        } catch (Throwable t) {
            presentDetail = "missing: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            present = false;
        }
        return present;
    }

    @Override
    public boolean pathTo(Goal goal) {
        BlockPos pos = goalToBlock(goal);
        return pos != null && pathTo(pos);
    }

    @Override
    public boolean pathTo(BlockPos pos) {
        if (!isAvailable()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) {
            return false;
        }
        try {
            cancelPathingOnly();
            Vec3 target = new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            targetField.set(null, target);
            pathfinderFind.invoke(pathfinder, mc.level, target, mc.player);
            return true;
        } catch (Throwable t) {
            logDirect("Tungsten pathTo failed: " + t);
            return false;
        }
    }

    public boolean follow(Entity entity, double maintainDistance) {
        if (!isAvailable() || entity == null) {
            return false;
        }
        try {
            cancel();
            followStart.invoke(null, entity, maintainDistance);
            return true;
        } catch (Throwable t) {
            logDirect("Tungsten follow failed: " + t);
            return false;
        }
    }

    @Override
    public boolean isPathing() {
        if (!isAvailable()) {
            return false;
        }
        try {
            if (followIsActive != null && Boolean.TRUE.equals(followIsActive.invoke(null))) {
                return true;
            }
            AtomicBoolean active = (AtomicBoolean) pathfinderActive.get(pathfinder);
            if (active != null && active.get()) {
                return true;
            }
            Object exec = executorField.get(null);
            if (exec != null) {
                if (executorIsRunning == null) {
                    executorIsRunning = exec.getClass().getMethod("isRunning");
                }
                if (Boolean.TRUE.equals(executorIsRunning.invoke(exec))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void cancel() {
        if (!isAvailable()) {
            return;
        }
        try {
            if (followIsActive != null && Boolean.TRUE.equals(followIsActive.invoke(null))) {
                followStop.invoke(null);
            }
        } catch (Throwable ignored) {
        }
        cancelPathingOnly();
    }

    private void cancelPathingOnly() {
        try {
            AtomicBoolean stop = (AtomicBoolean) pathfinderStop.get(pathfinder);
            if (stop != null) {
                stop.set(true);
            }
            Object exec = executorField.get(null);
            if (exec != null) {
                if (executorStop == null) {
                    executorStop = exec.getClass().getField("stop");
                }
                executorStop.setBoolean(exec, true);
            }
        } catch (Throwable ignored) {
        }
    }

    public static BlockPos goalToBlock(Goal goal) {
        if (goal == null) {
            return null;
        }
        if (goal instanceof GoalBlock gb) {
            return new BlockPos(gb.x, gb.y, gb.z);
        }
        if (goal instanceof GoalGetToBlock g) {
            return new BlockPos(g.x, g.y, g.z);
        }
        if (goal instanceof GoalNear near) {
            return near.getGoalPos();
        }
        if (goal instanceof IGoalRenderPos renderPos) {
            return renderPos.getGoalPos();
        }
        if (goal instanceof GoalXZ xz) {
            Minecraft mc = Minecraft.getInstance();
            int y = mc != null && mc.player != null ? mc.player.blockPosition().getY() : 64;
            return new BlockPos(xz.getX(), y, xz.getZ());
        }
        return null;
    }
}




