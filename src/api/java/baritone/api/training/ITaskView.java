package baritone.api.training;

import java.util.List;

/**
 * Ostinato: what a task model may read. In legit mode {@link #fullBlocks()} and {@link #fullEntities()} are empty.
 * Self position, biome temperature and the pie slice are the F3-style cues. Visible tokens are blocks inside the
 * render view. Sound tokens are client-audible cues with a compass bearing, not an entity id.
 */
public interface ITaskView {

    String mode();

    int x();

    int y();

    int z();

    int yaw();

    String biome();

    double temperature();

    String pieTop();

    String goalItem();

    int logs();

    int planks();

    int sticks();

    int cobble();

    /** -1 when the bot has no pick. */
    int pickDurability();

    boolean messyHotbar();

    /** Tokens {@code NAME@dx,dy,dz} or {@code FRONT:NAME}. Relative to the player. */
    List<String> visible();

    /** Tokens {@code KIND@bearing}. Bearing is N, E, S, W, or SELF. */
    List<String> sounds();

    /** Absolute {@code NAME@x,y,z}. Empty in legit mode. */
    List<String> fullBlocks();

    /** Absolute {@code KIND@x,y,z}. Empty in legit mode. */
    List<String> fullEntities();
}
