package baritone.swarm.center.channel;

import baritone.swarm.center.RegistryResult;
import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.feed.Json;

import java.util.Map;

/**
 * Ostinato: Phase 3 command channel, the host's answer to one {@link CenterRequest}. {@code admitted} and
 * {@code executed} are {@link RegistryResult}'s; {@code reason} is its reason word (accepted, preview, unauthorized,
 * busy, lease, invalid, ...) or a channel refusal (bad mac, replay, stale, unknown form, timeout). {@code line} is the
 * exact {@code #} line that ran or would run on {@code host}, which the window shows as the equivalent command.
 */
public record CenterReply(String id, long atMs, boolean admitted, boolean executed, String reason, String line,
                          String host) {

    public CenterReply {
        id = id == null ? "" : id;
        reason = reason == null ? "" : reason;
        line = line == null ? "" : line;
        host = host == null ? "" : host;
    }

    public static CenterReply refused(String id, String reason, long nowMs) {
        return new CenterReply(id, nowMs, false, false, reason, "", "");
    }

    public static CenterReply of(String id, RegistryResult r, String line, String host, long nowMs) {
        return new CenterReply(id, nowMs, r.admitted(), r.executed(), r.reason(), line, host);
    }

    /** The window's one-line summary, for example {@code Copper: #swarm send Azalea goto 10 64 3 - accepted}. */
    public String summary() {
        String where = host.isEmpty() ? "" : host + ": ";
        String what = line.isEmpty() ? "" : "#" + line + " - ";
        return where + what + reason;
    }

    public String canonical() {
        return Json.write(Json.map("v", CenterFeed.VERSION, "id", id, "atMs", atMs, "admitted", admitted,
                "executed", executed, "reason", reason, "line", line, "host", host));
    }

    public String toWire(byte[] key) {
        Map<String, Object> m = Json.object(canonical());
        m.put("mac", CenterAuth.sign(key, canonical()));
        return Json.write(m);
    }

    /** Parses and checks the MAC; a reply that fails it is returned as refused "bad reply mac". */
    public static CenterReply fromWire(String text, byte[] key, long nowMs) {
        Map<String, Object> m = Json.object(text);
        CenterReply r = new CenterReply(Json.str(m, "id", ""), Json.num(m, "atMs", 0), Json.bool(m, "admitted", false),
                Json.bool(m, "executed", false), Json.str(m, "reason", ""), Json.str(m, "line", ""),
                Json.str(m, "host", ""));
        if (!CenterAuth.verify(key, r.canonical(), Json.str(m, "mac", null))) {
            return refused(r.id(), "bad reply mac", nowMs);
        }
        return r;
    }
}
