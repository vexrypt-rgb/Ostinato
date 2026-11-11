/*
 * Ostinato: legit mode session check. Not part of upstream Baritone.
 */
package baritone.legit;

import baritone.api.legit.LegitMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;

/**
 * Reads the live client once. Headless tests pass a {@link LegitMode.SessionKind} into {@link LegitMode#isActive}
 * and do not call this.
 * <p>
 * A failure, a missing client, or a missing connection is {@link LegitMode.SessionKind#NONE}, so {@code auto} stays
 * off. Only a connected client with no integrated server counts as remote.
 */
public final class LegitSessions {

    private LegitSessions() {}

    public static LegitMode.SessionKind current() {
        Minecraft mc;
        try {
            mc = Minecraft.getInstance();
        } catch (Throwable ignored) {
            return LegitMode.SessionKind.NONE;
        }
        if (mc == null) {
            return LegitMode.SessionKind.NONE;
        }
        try {
            if (mc.player == null || mc.getConnection() == null) {
                return LegitMode.SessionKind.NONE;
            }
            IntegratedServer server = mc.getSingleplayerServer();
            if (server != null) {
                return server.isPublished() ? LegitMode.SessionKind.LAN : LegitMode.SessionKind.SINGLEPLAYER;
            }
            return LegitMode.SessionKind.REMOTE;
        } catch (Throwable ignored) {
            return LegitMode.SessionKind.NONE;
        }
    }
}
