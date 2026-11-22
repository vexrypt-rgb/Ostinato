package baritone.swarm.center.app;

/**
 * Ostinato: Phase 3 window, a stable identity color per bot name. The same name always maps to the same slot of a
 * 12-color palette, so a marker stays recognizable across refreshes. The colorblind-safe theme replaces this palette
 * later; until then every theme shares it and the marker shape still carries the status.
 */
public final class BotColors {

    public static final int[] PALETTE = {
            0x5B8DEF, 0xE07A3D, 0x3DDC8A, 0xD6C04A, 0xC46BD0, 0x4ECDC4,
            0xE06B8A, 0x8A7AE0, 0x7DDE4A, 0xE0A05A, 0x5AA0E0, 0xE07070
    };

    private BotColors() {}

    public static int of(String name) {
        int h = 0;
        String n = name == null ? "" : name;
        for (int i = 0; i < n.length(); i++) {
            h = 31 * h + n.charAt(i);
        }
        return PALETTE[Math.floorMod(h, PALETTE.length)];
    }
}
