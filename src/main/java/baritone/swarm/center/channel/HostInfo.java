package baritone.swarm.center.channel;

import baritone.swarm.center.feed.CenterFeed;
import baritone.swarm.center.feed.Json;

import java.util.Map;

/**
 * Ostinato: Phase 3, {@code host.json}. Where the command host listens: a loopback port only. The key is not here; it is
 * in {@code host.key} next to it, readable by the owner's user only.
 */
public record HostInfo(String bot, long writtenAtMs, long seq, int port, long pid, long startedAtMs) {

    public HostInfo {
        bot = bot == null ? "" : bot;
    }

    public String toJson() {
        Map<String, Object> m = CenterFeed.envelope(CenterFeed.KIND_HOST, writtenAtMs, seq);
        m.put("bot", bot);
        m.put("port", port);
        m.put("pid", pid);
        m.put("startedAtMs", startedAtMs);
        return Json.write(m);
    }

    public static HostInfo fromJson(String text) {
        Map<String, Object> m = Json.object(text);
        String bad = CenterFeed.check(m, CenterFeed.KIND_HOST);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        return new HostInfo(Json.str(m, "bot", ""), Json.num(m, "writtenAtMs", 0), Json.num(m, "seq", 0),
                Json.i32(m, "port", 0), Json.num(m, "pid", -1), Json.num(m, "startedAtMs", 0));
    }
}
