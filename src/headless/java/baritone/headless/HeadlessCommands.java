/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * Parses operator commands from commands.txt, stdin, and owner whispers. `stop` / `quit` / `exit`
 * end the process. `cancel` and `stop path` only drop the current path, mine, or place.
 */
package baritone.headless;

import java.util.Locale;

public final class HeadlessCommands {

    public enum Kind {
        STOP, CANCEL, GOTO, MINE, PLACE, STATUS, PROBE, COURSE, KILL, CRAFT, SMELT, CHEST, DROP, PICKUP, EQUIP
    }

    /** A parsed command. {@code error} is set when the verb was recognized but the arguments were not. */
    public record Parsed(Kind kind, int x, int y, int z, String text, String error) {
    }

    private HeadlessCommands() {
    }

    /** Blank lines and comments are ignored and return null. */
    public static Parsed parse(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        String[] parts = line.split("\\s+");
        String verb = parts[0].toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "quit", "exit" -> parts.length == 1
                    ? ok(Kind.STOP)
                    : bad("usage: quit | exit");
            case "stop" -> {
                if (parts.length == 1) {
                    yield ok(Kind.STOP);
                }
                if (parts.length == 2 && parts[1].equalsIgnoreCase("path")) {
                    yield ok(Kind.CANCEL);
                }
                yield bad("usage: stop | stop path");
            }
            case "cancel" -> parts.length == 1 ? ok(Kind.CANCEL) : bad("usage: cancel");
            case "status" -> parts.length == 1 ? ok(Kind.STATUS) : bad("usage: status");
            case "goto" -> {
                if (parts.length != 4) {
                    yield bad("usage: goto <x> <y> <z>");
                }
                try {
                    yield new Parsed(Kind.GOTO, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]), "", null);
                } catch (NumberFormatException e) {
                    yield bad("usage: goto <x> <y> <z> (integers)");
                }
            }
            case "mine" -> {
                if (parts.length != 2) {
                    yield bad("usage: mine <block>");
                }
                yield new Parsed(Kind.MINE, 0, 0, 0, parts[1].toLowerCase(Locale.ROOT), null);
            }
            case "place" -> {
                if (parts.length != 4) {
                    yield bad("usage: place <x> <y> <z>");
                }
                try {
                    yield new Parsed(Kind.PLACE, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]), "", null);
                } catch (NumberFormatException e) {
                    yield bad("usage: place <x> <y> <z> (integers)");
                }
            }
            case "probe" -> {
                if (parts.length != 4) {
                    yield bad("usage: probe <x> <y> <z>");
                }
                try {
                    yield new Parsed(Kind.PROBE, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                            Integer.parseInt(parts[3]), "", null);
                } catch (NumberFormatException e) {
                    yield bad("usage: probe <x> <y> <z> (integers)");
                }
            }
            case "craft" -> itemCount(Kind.CRAFT, parts, "usage: craft <item> [count]");
            case "smelt" -> itemCount(Kind.SMELT, parts, "usage: smelt <item> [count]");
            case "drop" -> itemCount(Kind.DROP, parts, "usage: drop <item> [count]");
            case "pickup" -> {
                if (parts.length == 1) {
                    yield new Parsed(Kind.PICKUP, 1, 0, 0, "", null);
                }
                if (parts.length == 2) {
                    yield new Parsed(Kind.PICKUP, 1, 0, 0, parts[1].toLowerCase(Locale.ROOT), null);
                }
                yield bad("usage: pickup [item]");
            }
            case "equip" -> {
                if (parts.length == 1) {
                    yield new Parsed(Kind.EQUIP, 1, 0, 0, "", null);
                }
                if (parts.length == 2) {
                    yield new Parsed(Kind.EQUIP, 1, 0, 0, parts[1].toLowerCase(Locale.ROOT), null);
                }
                yield bad("usage: equip [item]");
            }
            case "chest" -> {
                if (parts.length < 3 || parts.length > 4) {
                    yield bad("usage: chest <deposit|withdraw> <item> [count]");
                }
                String action = parts[1].toLowerCase(Locale.ROOT);
                if (!action.equals("deposit") && !action.equals("withdraw")) {
                    yield bad("usage: chest <deposit|withdraw> <item> [count]");
                }
                int count = 1;
                if (parts.length == 4) {
                    try {
                        count = Integer.parseInt(parts[3]);
                    } catch (NumberFormatException e) {
                        yield bad("usage: chest <deposit|withdraw> <item> [count]");
                    }
                }
                if (count < 1) {
                    yield bad("count must be at least 1");
                }
                yield new Parsed(Kind.CHEST, count, action.equals("withdraw") ? 1 : 0, 0,
                        parts[2].toLowerCase(Locale.ROOT), null);
            }
            case "kill", "attack" -> {
                if (parts.length == 1) {
                    yield new Parsed(Kind.KILL, 0, 0, 0, "", null);
                }
                if (parts.length == 2) {
                    yield new Parsed(Kind.KILL, 0, 0, 0, parts[1].toLowerCase(Locale.ROOT), null);
                }
                yield bad("usage: kill [type] | attack [type]");
            }
            case "course" -> {
                int space = line.indexOf(' ');
                if (space < 0) {
                    yield bad("usage: course <file>");
                }
                String path = line.substring(space + 1).trim();
                if (path.isEmpty()) {
                    yield bad("usage: course <file>");
                }
                yield new Parsed(Kind.COURSE, 0, 0, 0, path, null);
            }
            default -> bad("unknown command: " + parts[0]);
        };
    }

    /**
     * Ostinato: pull a command out of a system-chat / whisper line from the local dedicated server.
     * Public chat is ignored. Returns null when the text is not one of our commands.
     */
    public static String commandFromChat(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String flat = text.replaceAll("\u00a7.", "").trim();
        String lower = flat.toLowerCase(Locale.ROOT);
        if (lower.startsWith("you whisper")) {
            return null;
        }
        String body = null;
        int whisper = lower.indexOf("whispers to you:");
        if (whisper >= 0) {
            body = flat.substring(whisper + "whispers to you:".length()).trim();
        }
        if (body == null) {
            int me = lower.indexOf("-> me]");
            int you = lower.indexOf("-> you]");
            if (me >= 0 || you >= 0) {
                boolean useMe = me >= 0 && (you < 0 || me < you);
                int at = useMe ? me : you;
                String token = useMe ? "-> me]" : "-> you]";
                body = flat.substring(at + token.length()).trim();
            }
        }
        if (body == null && lower.startsWith("[server]")) {
            body = flat.substring(flat.indexOf(']') + 1).trim();
        }
        if (body == null && lower.startsWith("<server>")) {
            body = flat.substring("<server>".length()).trim();
        }
        if (body == null) {
            body = flat;
        }
        if (!startsWithCommand(body)) {
            return null;
        }
        return body;
    }

    private static boolean startsWithCommand(String body) {
        if (body.isEmpty()) {
            return false;
        }
        String word = body.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        return switch (word) {
            case "stop", "quit", "exit", "cancel", "goto", "mine", "place", "status", "probe", "course", "kill", "attack", "craft", "smelt", "chest", "drop", "pickup", "equip" -> true;
            default -> false;
        };
    }

    private static Parsed itemCount(Kind kind, String[] parts, String usage) {
        if (parts.length != 2 && parts.length != 3) {
            return bad(usage);
        }
        int count = 1;
        if (parts.length == 3) {
            try {
                count = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                return bad(usage);
            }
        }
        if (count < 1) {
            return bad("count must be at least 1");
        }
        return new Parsed(kind, count, 0, 0, parts[1].toLowerCase(Locale.ROOT), null);
    }

    private static Parsed ok(Kind kind) {
        return new Parsed(kind, 0, 0, 0, "", null);
    }

    private static Parsed bad(String error) {
        return new Parsed(null, 0, 0, 0, "", error);
    }
}
