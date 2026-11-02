/*
 * Ostinato: new file (Secondo). Not part of upstream Baritone.
 */

package baritone.secondo;

import java.util.List;
import java.util.Locale;

/**
 * Ostinato: {@code #secondo} words, shared by the chat command and the tests.
 * {@code follow} with no name binds the nearest other player.
 */
public final class SecondoCommands {

    public enum Verb { FOLLOW, STOP, STATUS, ASSIST }

    /** {@code arg} is a player name, {@code on}/{@code off}, or null. */
    public record Call(Verb verb, String arg) {
    }

    private SecondoCommands() {
    }

    public static Call parse(List<String> args) {
        if (args == null || args.isEmpty()) {
            return new Call(Verb.STATUS, null);
        }
        if (args.size() > 2) {
            throw new IllegalArgumentException("follow [name], stop, status, or assist [on|off]");
        }
        String verb = args.get(0).toLowerCase(Locale.ROOT);
        String extra = args.size() == 2 ? args.get(1) : null;
        return switch (verb) {
            case "follow" -> {
                if (extra != null && extra.isBlank()) {
                    throw new IllegalArgumentException("follow needs a player name or no name at all");
                }
                yield new Call(Verb.FOLLOW, extra);
            }
            case "stop", "cancel" -> {
                if (extra != null) {
                    throw new IllegalArgumentException("stop takes no name");
                }
                yield new Call(Verb.STOP, null);
            }
            case "status" -> {
                if (extra != null) {
                    throw new IllegalArgumentException("status takes no name");
                }
                yield new Call(Verb.STATUS, null);
            }
            case "assist" -> {
                if (extra == null) {
                    yield new Call(Verb.ASSIST, null);
                }
                String bit = extra.toLowerCase(Locale.ROOT);
                if (!bit.equals("on") && !bit.equals("off")) {
                    throw new IllegalArgumentException("assist is on, off, or alone to flip");
                }
                yield new Call(Verb.ASSIST, bit);
            }
            default -> throw new IllegalArgumentException("follow [name], stop, status, or assist [on|off]");
        };
    }
}
