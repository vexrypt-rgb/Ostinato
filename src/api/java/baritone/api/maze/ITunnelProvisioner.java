package baritone.api.maze;

import java.util.Map;

/**
 * Ostinato: survival kit for {@code #maze.tunnels}. Something that gathers tools, armor, food and torches and places a
 * bed for spawn, usually TenorClef's task system. Ostinato only decides what is missing; it never gathers itself.
 * <p>
 * Need keys: a catalogue item name with the total count wanted ({@code "iron_pickaxe" -> 2, "torch" -> 32}),
 * {@code "food" -> hunger units}, {@code "equip:<armor item>" -> 1} to obtain and wear it, and {@code "bed" -> 1} to
 * place (or reuse) a bed next to the player and set spawn there.
 */
public interface ITunnelProvisioner {

    /** Starts gathering. Returns false to refuse (busy, not in a world). Called on the client thread. */
    boolean provision(Map<String, Integer> needs);

    /** True while the last accepted provision job is still running. */
    boolean busy();

    default String name() {
        return getClass().getSimpleName();
    }
}
