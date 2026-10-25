package baritone.training;

import baritone.api.training.ITaskView;

import java.util.List;

/** Ostinato: immutable snapshot handed to the task model. */
public final class TaskView implements ITaskView {

    private final String mode;
    private final int x;
    private final int y;
    private final int z;
    private final int yaw;
    private final String biome;
    private final double temperature;
    private final String pieTop;
    private final String goalItem;
    private final int logs;
    private final int planks;
    private final int sticks;
    private final int cobble;
    private final int pickDurability;
    private final boolean messyHotbar;
    private final List<String> visible;
    private final List<String> sounds;
    private final List<String> fullBlocks;
    private final List<String> fullEntities;

    public TaskView(String mode, int x, int y, int z, int yaw, String biome, double temperature, String pieTop,
            String goalItem, int logs, int planks, int sticks, int cobble, int pickDurability, boolean messyHotbar,
            List<String> visible, List<String> sounds, List<String> fullBlocks, List<String> fullEntities) {
        this.mode = mode;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.biome = biome;
        this.temperature = temperature;
        this.pieTop = pieTop;
        this.goalItem = goalItem;
        this.logs = logs;
        this.planks = planks;
        this.sticks = sticks;
        this.cobble = cobble;
        this.pickDurability = pickDurability;
        this.messyHotbar = messyHotbar;
        this.visible = visible;
        this.sounds = sounds;
        this.fullBlocks = fullBlocks;
        this.fullEntities = fullEntities;
    }

    @Override public String mode() { return mode; }
    @Override public int x() { return x; }
    @Override public int y() { return y; }
    @Override public int z() { return z; }
    @Override public int yaw() { return yaw; }
    @Override public String biome() { return biome; }
    @Override public double temperature() { return temperature; }
    @Override public String pieTop() { return pieTop; }
    @Override public String goalItem() { return goalItem; }
    @Override public int logs() { return logs; }
    @Override public int planks() { return planks; }
    @Override public int sticks() { return sticks; }
    @Override public int cobble() { return cobble; }
    @Override public int pickDurability() { return pickDurability; }
    @Override public boolean messyHotbar() { return messyHotbar; }
    @Override public List<String> visible() { return visible; }
    @Override public List<String> sounds() { return sounds; }
    @Override public List<String> fullBlocks() { return fullBlocks; }
    @Override public List<String> fullEntities() { return fullEntities; }
}
