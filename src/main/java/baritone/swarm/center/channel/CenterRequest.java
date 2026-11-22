package baritone.swarm.center.channel;

import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.feed.Json;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Ostinato: Phase 3 command channel, one request from the window to the command host. It names an existing registry
 * form (or, for {@link Op#LINE}, one registry line); the host runs it through the same {@code UnifiedRegistry} admission
 * and {@code TileActions} routing as an in-game window button. There is no other way in: no request carries code, a
 * setting or a file path.
 *
 * <ul>
 *   <li>{@link Op#PRESS}: {@code command} + {@code formId} of a catalog button, its {@code values}, and the target
 *   {@code bot} (empty or the host's own name for the host itself).</li>
 *   <li>{@link Op#LINE}: one {@code #} line typed or composed in the window (for example an area order from the map),
 *   run as {@code RegistryCall.window} on the host.</li>
 *   <li>{@link Op#PING}: nothing runs; the reply proves the channel and key work.</li>
 * </ul>
 * {@code dryRun} asks the registry for a preview: the reply carries the line and admission, nothing runs.
 */
public record CenterRequest(String id, long atMs, Op op, String command, String formId, Map<String, String> values,
                            String bot, String line, boolean dryRun) {

    public enum Op { PRESS, LINE, PING }

    /** Largest request line the host reads. */
    public static final int MAX_CHARS = 8 * 1024;

    public CenterRequest {
        id = id == null ? "" : id;
        op = op == null ? Op.PING : op;
        command = command == null ? "" : command;
        formId = formId == null ? "" : formId;
        values = values == null ? Map.of() : Map.copyOf(values);
        bot = bot == null ? "" : bot;
        line = line == null ? "" : line;
    }

    public static CenterRequest press(String command, String formId, Map<String, String> values, String bot,
                                      long nowMs) {
        return new CenterRequest(UUID.randomUUID().toString(), nowMs, Op.PRESS, command, formId, values, bot, "", false);
    }

    public static CenterRequest line(String line, String bot, long nowMs) {
        return new CenterRequest(UUID.randomUUID().toString(), nowMs, Op.LINE, "", "", Map.of(), bot, line, false);
    }

    public static CenterRequest ping(long nowMs) {
        return new CenterRequest(UUID.randomUUID().toString(), nowMs, Op.PING, "", "", Map.of(), "", "", false);
    }

    public CenterRequest dry() {
        return new CenterRequest(id, atMs, op, command, formId, values, bot, line, true);
    }

    /** The exact text the MAC covers: every field, in a fixed order, values sorted by key. */
    public String canonical() {
        Map<String, Object> m = Json.map("v", CenterFeed.VERSION, "id", id, "atMs", atMs,
                "op", op.name().toLowerCase(Locale.ROOT), "command", command, "formId", formId,
                "values", new TreeMap<>(values), "bot", bot, "line", line, "dryRun", dryRun);
        return Json.write(m);
    }

    /** One line of JSON: the canonical fields plus {@code mac}. */
    public String toWire(byte[] key) {
        Map<String, Object> m = Json.object(canonical());
        m.put("mac", CenterAuth.sign(key, canonical()));
        return Json.write(m);
    }

    /** Parses a wire line. Does not check the MAC; {@link #verified} does. */
    public static CenterRequest fromWire(String text) {
        Map<String, Object> m = Json.object(text);
        if (Json.num(m, "v", -1) != CenterFeed.VERSION) {
            throw new IllegalArgumentException("version");
        }
        Op op;
        try {
            op = Op.valueOf(Json.str(m, "op", "").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("op");
        }
        Map<String, String> values = new TreeMap<>();
        Map<String, Object> raw = Json.obj(m, "values");
        if (raw != null) {
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getValue() instanceof String s) {
                    values.put(e.getKey(), s);
                } else {
                    throw new IllegalArgumentException("values");
                }
            }
        }
        return new CenterRequest(Json.str(m, "id", ""), Json.num(m, "atMs", 0), op, Json.str(m, "command", ""),
                Json.str(m, "formId", ""), values, Json.str(m, "bot", ""), Json.str(m, "line", ""),
                Json.bool(m, "dryRun", false));
    }

    /** Whether the wire line's MAC matches this request's canonical form under {@code key}. */
    public static boolean verified(String wire, CenterRequest parsed, byte[] key) {
        try {
            return CenterAuth.verify(key, parsed.canonical(), Json.str(Json.object(wire), "mac", null));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
