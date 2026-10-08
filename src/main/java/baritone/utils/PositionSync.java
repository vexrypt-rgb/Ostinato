package baritone.utils;

import net.minecraft.client.player.LocalPlayer;

import java.lang.reflect.Field;

/**
 * Gets the server told where the player stands, for takeoffs that start from a standstill.
 * <p>
 * The client only reports a move over 0.03, so a player that has barely moved keeps a stale position on the server and the
 * jump would be replayed from there. But the server (26.x) kicks a client that sends two position packets in one tick, and
 * the client sends its own at the end of any tick that moved more than that. So the sync asks the client to
 * report this tick itself, and never sends a packet of its own.
 */
public final class PositionSync {

    private static final Field X_LAST, Y_LAST, Z_LAST, REMINDER;

    static {
        Field[] f = new Field[4];
        try {
            String[] names = {"xLast", "yLast", "zLast", "positionReminder"};
            for (int i = 0; i < 4; i++) {
                f[i] = LocalPlayer.class.getDeclaredField(names[i]);
                f[i].setAccessible(true);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            f = new Field[4];
        }
        X_LAST = f[0];
        Y_LAST = f[1];
        Z_LAST = f[2];
        REMINDER = f[3];
    }

    private PositionSync() {
    }

    /** True if the vanilla end-of-tick sendPosition will send a position packet for the player as it stands now. */
    public static boolean clientWillReport(LocalPlayer p) {
        if (X_LAST == null) {
            return true; // unknown layout: never risk a second packet in the tick
        }
        try {
            double dx = p.getX() - X_LAST.getDouble(p), dy = p.getY() - Y_LAST.getDouble(p), dz = p.getZ() - Z_LAST.getDouble(p);
            return dx * dx + dy * dy + dz * dz > 4.0E-8 || REMINDER.getInt(p) + 1 >= 20;
        } catch (IllegalAccessException e) {
            return true;
        }
    }

    /**
     * Makes the client's own end-of-tick sendPosition report this tick (by running its reminder counter out) instead of sending
     * a packet of our own: a second position packet can reach the server inside one server tick when the client catches up
     * after a slow tick, and the server kicks for that.
     */
    public static void sync(LocalPlayer p) {
        if (REMINDER == null || clientWillReport(p)) {
            return;
        }
        try {
            REMINDER.setInt(p, 19);
        } catch (IllegalAccessException ignored) {
        }
    }
}
