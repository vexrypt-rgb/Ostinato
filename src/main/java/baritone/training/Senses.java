package baritone.training;

// Ostinato: legit view is render distance, a forward cone, F3 cues, and sounds. Full mode adds the hidden task blocks.

import baritone.api.training.TrainingMode;

import java.util.ArrayList;
import java.util.List;

/** Builds a {@link TaskView}. The model never receives the world. */
public final class Senses {

    /** Blocks, not chunks. A player-scale cone so the tiny circuit still hides what is behind the camera. */
    public static final int RENDER_BLOCKS = 8;
    public static final int HEAR_MOB = 12;
    public static final int HEAR_FLUID = 8;
    private static final double FOV_COS = 0.64; // about 50 degrees off the look axis

    private Senses() {}

    public static TaskView observe(TrainingWorld world, boolean legit) {
        List<String> visible = new ArrayList<>();
        List<String> sounds = new ArrayList<>();
        List<String> fullBlocks = new ArrayList<>();
        List<String> fullEntities = new ArrayList<>();
        int fx = world.yaw == 1 ? -1 : world.yaw == 3 ? 1 : 0;
        int fz = world.yaw == 0 ? 1 : world.yaw == 2 ? -1 : 0;
        byte front = world.at(world.x + fx, world.y, world.z + fz);
        if (front != TrainingWorld.AIR) {
            visible.add("FRONT:" + TrainingWorld.nameOf(front));
        }
        for (int ix = 0; ix < TrainingWorld.SX; ix++) {
            for (int iy = 0; iy < TrainingWorld.SY; iy++) {
                for (int iz = 0; iz < TrainingWorld.SX; iz++) {
                    byte id = world.at(ix, iy, iz);
                    if (!interesting(id)) {
                        continue;
                    }
                    String name = TrainingWorld.nameOf(id);
                    if (!legit) {
                        fullBlocks.add(name + "@" + ix + "," + iy + "," + iz);
                    }
                    int dx = ix - world.x;
                    int dy = iy - world.y;
                    int dz = iz - world.z;
                    if (inView(dx, dy, dz, fx, fz)) {
                        visible.add(name + "@" + dx + "," + dy + "," + dz);
                    }
                    if ((id == TrainingWorld.WATER || id == TrainingWorld.LAVA) && dist(dx, dz) <= HEAR_FLUID) {
                        sounds.add((id == TrainingWorld.WATER ? "WATER@" : "LAVA@") + TrainingWorld.bearing(dx, dz));
                    }
                }
            }
        }
        if (world.hasMob() && dist(world.mobX() - world.x, world.mobZ() - world.z) <= HEAR_MOB) {
            sounds.add("MOB_AMBIENT@" + TrainingWorld.bearing(world.mobX() - world.x, world.mobZ() - world.z));
            if (!legit) {
                fullEntities.add("ZOMBIE@" + world.mobX() + "," + world.mobY() + "," + world.mobZ());
            }
        }
        if (world.hasNeedle()) {
            String needle = TrainingWorld.bearing(world.markX() - world.x, world.markZ() - world.z);
            if (!"SELF".equals(needle)) {
                sounds.add("COMPASS@" + needle);
            }
        }
        if (!world.lastCue.isEmpty()) {
            for (String cue : world.lastCue.split(";")) {
                if (!sounds.contains(cue)) {
                    sounds.add(cue);
                }
            }
        }
        String pie = world.mining() ? "chunkBuilder" : sounds.isEmpty() ? "gameRenderer" : "soundEngine";
        return new TaskView(legit ? TrainingMode.LEGIT.name() : TrainingMode.FULL.name(),
                world.x, world.y, world.z, world.yaw, world.biome, world.temperature, pie, world.goalItem,
                world.logs, world.planks, world.sticks, world.cobble, world.pick, world.dirt >= 8,
                List.copyOf(visible), List.copyOf(sounds),
                legit ? List.of() : List.copyOf(fullBlocks),
                legit ? List.of() : List.copyOf(fullEntities));
    }

    private static boolean interesting(byte id) {
        return id == TrainingWorld.LOG || id == TrainingWorld.STONE || id == TrainingWorld.TABLE
                || id == TrainingWorld.DEPOSIT || id == TrainingWorld.WATER || id == TrainingWorld.LAVA
                || id == TrainingWorld.PIT || id == TrainingWorld.LODESTONE;
    }

    private static boolean inView(int dx, int dy, int dz, int fx, int fz) {
        double len = Math.sqrt(dx * (double) dx + dz * (double) dz);
        if (len > RENDER_BLOCKS || Math.abs(dy) > 3) {
            return false;
        }
        if (len < 0.5) {
            return true;
        }
        double cos = (fx * dx + fz * dz) / len;
        return cos >= FOV_COS;
    }

    private static double dist(int dx, int dz) {
        return Math.sqrt(dx * (double) dx + dz * (double) dz);
    }

}
