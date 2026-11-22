package baritone.swarm.center.app;

import baritone.swarm.center.CenterTheme;

/**
 * Ostinato: Phase 3 window, a stable identity color per bot name. The map reads the color from the active
 * {@link CenterTheme}; this palette is the one every theme uses except Colorblind safe.
 */
public final class BotColors {

    public static final int[] PALETTE = CenterTheme.IDENTITY;

    private BotColors() {}

    public static int of(String name) {
        return CenterTheme.identityAt(PALETTE, name);
    }
}
