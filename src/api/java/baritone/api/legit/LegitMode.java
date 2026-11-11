/*
 * Ostinato: legit mode for swarm mining. Not part of upstream Baritone.
 */
package baritone.api.legit;

import java.util.Locale;

/**
 * {@code #set legitMode on|off|auto}. The default is {@link Value#AUTO}: on only while the bot is connected to a
 * remote multiplayer server, and off in singleplayer, on LAN, and when no remote connection can be shown.
 * <p>
 * {@link #isActive} is the only effective-mode check. Callers that mine or target blocks must use it. When it is
 * false, pathing and mining stay as they were before this setting existed.
 */
public final class LegitMode {

    private LegitMode() {}

    public enum Value {
        AUTO,
        ON,
        OFF;

        /** Lowercase token stored by {@code #set} and {@code settings.txt}. */
        public String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Accepts {@code on}, {@code off}, and {@code auto}, ignoring case and surrounding space.
         *
         * @throws IllegalArgumentException when {@code raw} is anything else, including a blank string
         */
        public static Value parse(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("legitMode expects on, off, or auto");
            }
            String token = raw.trim().toLowerCase(Locale.ROOT);
            return switch (token) {
                case "on" -> ON;
                case "off" -> OFF;
                case "auto" -> AUTO;
                default -> throw new IllegalArgumentException(
                        "legitMode expects on, off, or auto, not \"" + raw.trim() + "\"");
            };
        }
    }

    /**
     * Where the bot is playing. {@link SessionKind#NONE} is "not a shown remote server": no client, no connection,
     * or the check failed. {@code auto} stays off in that case.
     */
    public enum SessionKind {
        /** Integrated singleplayer server, not opened to LAN. */
        SINGLEPLAYER,
        /** Integrated server published to LAN. */
        LAN,
        /** Connected to a remote multiplayer server. */
        REMOTE,
        /** No connection, or not shown to be remote. */
        NONE
    }

    /**
     * Effective legit mode. {@code on} is always active. {@code off} is never active. {@code auto} is active only
     * for {@link SessionKind#REMOTE}.
     */
    public static boolean isActive(Value setting, SessionKind session) {
        if (setting == null || setting == Value.OFF) {
            return false;
        }
        if (setting == Value.ON) {
            return true;
        }
        return session == SessionKind.REMOTE;
    }
}
