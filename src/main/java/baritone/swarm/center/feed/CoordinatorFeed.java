package baritone.swarm.center.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 3 read API v1, {@code coordinator.json}. Written only by the bot that runs the lease coordinator.
 * {@code leases} are the coordinator's live records plus recently ended ones still inside their not-before window
 * (exactly what {@code #swarm lease status} prints). {@code observations} are the newest shared world observations, at
 * most {@link #MAX_OBSERVATIONS}; {@code observationTotal} is how many the store holds.
 *
 * @param mode coordinator mode, lower case (idle, reconciling, active, refused)
 */
public record CoordinatorFeed(String self, long writtenAtMs, long seq, String mode, String world, List<Lease> leases,
                              List<Observation> observations, int observationTotal) {

    public static final int MAX_OBSERVATIONS = 200;

    public CoordinatorFeed {
        self = self == null ? "" : self;
        mode = mode == null ? "" : mode;
        world = world == null ? "" : world;
        leases = leases == null ? List.of() : List.copyOf(leases);
        observations = observations == null ? List.of() : List.copyOf(observations);
    }

    /**
     * One lease. {@code type} is vein, chest or region for a reservation (then {@code dim} and {@code box} =
     * x1,y1,z1,x2,y2,z2 inclusive are set) and key for an untyped resource (box null). {@code state} is offered,
     * granted, revoking, released or expired; {@code execution} and {@code observation} are the command's lower-case
     * states, empty until known.
     */
    public record Lease(String id, String resource, String type, char dim, int[] box, String bot, long gen,
                        String state, String command, String execution, String observation, String endReason) {
        public Lease {
            id = id == null ? "" : id;
            resource = resource == null ? "" : resource;
            type = type == null ? "key" : type;
            bot = bot == null ? "" : bot;
            state = state == null ? "" : state;
            command = command == null ? "" : command;
            execution = execution == null ? "" : execution;
            observation = observation == null ? "" : observation;
            endReason = endReason == null ? "" : endReason;
        }

        public boolean live() {
            return "offered".equals(state) || "granted".equals(state) || "revoking".equals(state);
        }

        Map<String, Object> toJson() {
            return Json.map("id", id, "resource", resource, "type", type, "dim", String.valueOf(dim), "box", box,
                    "bot", bot, "gen", gen, "state", state, "command", command, "execution", execution,
                    "observation", observation, "endReason", endReason);
        }

        static Lease fromJson(Map<String, Object> m) {
            String d = Json.str(m, "dim", "?");
            int[] box = Json.ints(m, "box");
            return new Lease(Json.str(m, "id", ""), Json.str(m, "resource", ""), Json.str(m, "type", "key"),
                    d.isEmpty() ? '?' : d.charAt(0), box != null && box.length == 6 ? box : null, Json.str(m, "bot", ""),
                    Json.num(m, "gen", 0), Json.str(m, "state", ""), Json.str(m, "command", ""),
                    Json.str(m, "execution", ""), Json.str(m, "observation", ""), Json.str(m, "endReason", ""));
        }
    }

    /**
     * One shared observation, a hint the reporting bot verifies before acting. {@code kind} is vein, chest, block,
     * waypoint, structure or entity; {@code box} is set for the kinds with a place (a block or structure is a 1-block
     * box); {@code state} is confirmed, stale or gone; {@code suspect} means a lease on it ended observation-unknown.
     */
    public record Observation(String key, String kind, char dim, int[] box, String bot, int confidence, String detail,
                              String state, boolean suspect, long ageMs) {
        public Observation {
            key = key == null ? "" : key;
            kind = kind == null ? "" : kind;
            bot = bot == null ? "" : bot;
            detail = detail == null ? "" : detail;
            state = state == null ? "" : state;
        }

        Map<String, Object> toJson() {
            return Json.map("key", key, "kind", kind, "dim", String.valueOf(dim), "box", box, "bot", bot,
                    "confidence", confidence, "detail", detail, "state", state, "suspect", suspect, "ageMs", ageMs);
        }

        static Observation fromJson(Map<String, Object> m) {
            String d = Json.str(m, "dim", "?");
            int[] box = Json.ints(m, "box");
            return new Observation(Json.str(m, "key", ""), Json.str(m, "kind", ""), d.isEmpty() ? '?' : d.charAt(0),
                    box != null && box.length == 6 ? box : null, Json.str(m, "bot", ""), Json.i32(m, "confidence", 0),
                    Json.str(m, "detail", ""), Json.str(m, "state", ""), Json.bool(m, "suspect", false),
                    Json.num(m, "ageMs", -1));
        }
    }

    public String toJson() {
        Map<String, Object> m = CenterFeed.envelope(CenterFeed.KIND_COORDINATOR, writtenAtMs, seq);
        m.put("self", self);
        m.put("mode", mode);
        m.put("world", world);
        List<Object> ls = new ArrayList<>();
        for (Lease l : leases) {
            ls.add(l.toJson());
        }
        m.put("leases", ls);
        List<Object> os = new ArrayList<>();
        for (Observation o : observations) {
            os.add(o.toJson());
        }
        m.put("observations", os);
        m.put("observationTotal", observationTotal);
        return Json.write(m);
    }

    public static CoordinatorFeed fromJson(String text) {
        Map<String, Object> m = Json.object(text);
        String bad = CenterFeed.check(m, CenterFeed.KIND_COORDINATOR);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        List<Lease> ls = new ArrayList<>();
        for (Map<String, Object> l : Json.objs(m, "leases")) {
            ls.add(Lease.fromJson(l));
        }
        List<Observation> os = new ArrayList<>();
        for (Map<String, Object> o : Json.objs(m, "observations")) {
            os.add(Observation.fromJson(o));
        }
        return new CoordinatorFeed(Json.str(m, "self", ""), Json.num(m, "writtenAtMs", 0), Json.num(m, "seq", 0),
                Json.str(m, "mode", ""), Json.str(m, "world", ""), ls, os, Json.i32(m, "observationTotal", os.size()));
    }
}
