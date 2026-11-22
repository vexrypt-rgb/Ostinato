package baritone.swarm.center.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ostinato: Phase 3 read API v1, {@code bots/<name>.json}. What one bot says about itself, written by that bot. Unknown
 * numbers are -1, unknown strings are empty, and a bot that is not in a world has a null {@link #pos}. Lists are
 * bounded by the writer ({@link #MAX_NODES}, {@link #MAX_EXECUTIONS}, {@link #MAX_ITEMS}).
 *
 * @param name         roster member name (also the file name)
 * @param server       server address as the client knows it, or empty
 * @param runMode      visible, hidden or headless
 * @param commandHost  this bot serves the command channel ({@code host.json})
 * @param coordinator  this bot is the lease coordinator ({@code coordinator.json})
 * @param process      the Baritone process in control (its display name), or {@code idle}
 * @param gatherUs     what building this feed cost on the client thread, in microseconds (self-measured)
 */
public record BotFeed(String name, long writtenAtMs, long seq, String server, String runMode, boolean commandHost,
                      boolean coordinator, Pos pos, int health, int maxHealth, int hunger, boolean dead, String process,
                      Route path, Inventory inventory, List<Execution> executions, List<HeldLease> leases, Perf perf,
                      Link link, long gatherUs) {

    public static final int MAX_NODES = 32;
    public static final int MAX_EXECUTIONS = 8;
    public static final int MAX_ITEMS = 4;

    public BotFeed {
        name = name == null ? "" : name;
        server = server == null ? "" : server;
        runMode = runMode == null ? "" : runMode;
        process = process == null || process.isBlank() ? "idle" : process;
        path = path == null ? Route.IDLE : path;
        inventory = inventory == null ? Inventory.UNKNOWN : inventory;
        executions = executions == null ? List.of() : List.copyOf(executions);
        leases = leases == null ? List.of() : List.copyOf(leases);
        perf = perf == null ? Perf.UNKNOWN : perf;
        link = link == null ? Link.DOWN : link;
    }

    /** Where the bot stands. {@code dim} is o, n or e (the reservation-key letters), or ? for another dimension. */
    public record Pos(char dim, double x, double y, double z, float yaw) {
        public int blockX() {
            return (int) Math.floor(x);
        }

        public int blockY() {
            return (int) Math.floor(y);
        }

        public int blockZ() {
            return (int) Math.floor(z);
        }

        Map<String, Object> toJson() {
            return Json.map("dim", String.valueOf(dim), "x", round(x), "y", round(y), "z", round(z), "yaw", round(yaw));
        }

        static Pos fromJson(Map<String, Object> m) {
            if (m == null) {
                return null;
            }
            String d = Json.str(m, "dim", "?");
            return new Pos(d.isEmpty() ? '?' : d.charAt(0), Json.dbl(m, "x", 0), Json.dbl(m, "y", 0),
                    Json.dbl(m, "z", 0), (float) Json.dbl(m, "yaw", 0));
        }
    }

    /**
     * The path the bot is following. {@code state} is idle, calculating, pathing or waiting (PathStatus). {@code goal}
     * is the goal's own text, {@code goalPos} its block when it has one. {@code nodes} are the next path positions
     * from where the bot stands, at most {@link #MAX_NODES}.
     */
    public record Route(String state, String goal, int[] goalPos, double distance, int position, int length,
                       List<int[]> nodes) {
        public static final Route IDLE = new Route("idle", "", null, -1, 0, 0, List.of());

        public Route {
            state = state == null || state.isBlank() ? "idle" : state;
            goal = goal == null ? "" : goal;
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
        }

        Map<String, Object> toJson() {
            return Json.map("state", state, "goal", goal, "goalPos", goalPos, "distance", round(distance),
                    "position", position, "length", length, "nodes", nodes);
        }

        static Route fromJson(Map<String, Object> m) {
            if (m == null) {
                return IDLE;
            }
            List<int[]> nodes = new ArrayList<>();
            Object raw = m.get("nodes");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof List<?> xyz && xyz.size() == 3 && xyz.stream().allMatch(Number.class::isInstance)) {
                        nodes.add(new int[]{((Number) xyz.get(0)).intValue(), ((Number) xyz.get(1)).intValue(),
                                ((Number) xyz.get(2)).intValue()});
                    }
                }
            }
            int[] goalPos = Json.ints(m, "goalPos");
            return new Route(Json.str(m, "state", "idle"), Json.str(m, "goal", ""),
                    goalPos != null && goalPos.length == 3 ? goalPos : null, Json.dbl(m, "distance", -1),
                    Json.i32(m, "position", 0), Json.i32(m, "length", 0), nodes);
        }
    }

    /**
     * Inventory summary (the owner's choice for a tile): free main slots out of the total, food items carried, the
     * {@link #MAX_ITEMS} largest stacks by item, and the lowest durability of any tool or weapon carried, as a percent
     * (-1 when none).
     */
    public record Inventory(int freeSlots, int totalSlots, int food, List<Item> top, int lowestToolPct,
                            String lowestTool) {
        public static final Inventory UNKNOWN = new Inventory(-1, -1, -1, List.of(), -1, "");

        public Inventory {
            top = top == null ? List.of() : List.copyOf(top);
            lowestTool = lowestTool == null ? "" : lowestTool;
        }

        Map<String, Object> toJson() {
            List<Object> items = new ArrayList<>();
            for (Item i : top) {
                items.add(Json.map("id", i.id(), "count", i.count()));
            }
            return Json.map("freeSlots", freeSlots, "totalSlots", totalSlots, "food", food, "top", items,
                    "lowestToolPct", lowestToolPct, "lowestTool", lowestTool);
        }

        static Inventory fromJson(Map<String, Object> m) {
            if (m == null) {
                return UNKNOWN;
            }
            List<Item> items = new ArrayList<>();
            for (Map<String, Object> i : Json.objs(m, "top")) {
                items.add(new Item(Json.str(i, "id", "?"), Json.i32(i, "count", 0)));
            }
            return new Inventory(Json.i32(m, "freeSlots", -1), Json.i32(m, "totalSlots", -1), Json.i32(m, "food", -1),
                    items, Json.i32(m, "lowestToolPct", -1), Json.str(m, "lowestTool", ""));
        }
    }

    /** One item id (namespace dropped when it is minecraft) and how many the bot carries in total. */
    public record Item(String id, int count) {}

    /**
     * One swarm command this bot knows about (its command-protocol ledger). {@code execution} is accepted, running,
     * succeeded, failed or canceled; {@code observation} is confirmed, stale or unknown (lower-case enum names, not the
     * display labels).
     */
    public record Execution(String id, String command, String issuer, String execution, String observation, long ageMs,
                            String endReason) {
        public Execution {
            id = id == null ? "" : id;
            command = command == null ? "" : command;
            issuer = issuer == null ? "" : issuer;
            execution = execution == null ? "" : execution;
            observation = observation == null ? "unknown" : observation;
            endReason = endReason == null ? "" : endReason;
        }

        Map<String, Object> toJson() {
            return Json.map("id", id, "command", command, "issuer", issuer, "execution", execution,
                    "observation", observation, "ageMs", ageMs, "endReason", endReason);
        }

        static Execution fromJson(Map<String, Object> m) {
            return new Execution(Json.str(m, "id", ""), Json.str(m, "command", ""), Json.str(m, "issuer", ""),
                    Json.str(m, "execution", ""), Json.str(m, "observation", "unknown"), Json.num(m, "ageMs", -1),
                    Json.str(m, "endReason", ""));
        }
    }

    /** A lease this bot holds or is being offered (its lease guard). {@code phase} is the guard's lower-case phase. */
    public record HeldLease(String id, long gen, String resource, String phase, long leftMs, boolean active) {
        Map<String, Object> toJson() {
            return Json.map("id", id, "gen", gen, "resource", resource, "phase", phase, "leftMs", leftMs,
                    "active", active);
        }

        static HeldLease fromJson(Map<String, Object> m) {
            return new HeldLease(Json.str(m, "id", ""), Json.num(m, "gen", 0), Json.str(m, "resource", ""),
                    Json.str(m, "phase", ""), Json.num(m, "leftMs", -1), Json.bool(m, "active", false));
        }
    }

    /** The performance strip figures (SwarmSample), with the same -1 for unknown. */
    public record Perf(long pathMs, int tpsX10, int pingMs, int ramUsedMb, int ramMaxMb, int chatUsed, int chatCap,
                       long dropped, long retried) {
        public static final Perf UNKNOWN = new Perf(-1, -1, -1, -1, -1, -1, -1, -1, -1);

        Map<String, Object> toJson() {
            return Json.map("pathMs", pathMs, "tpsX10", tpsX10, "pingMs", pingMs, "ramUsedMb", ramUsedMb,
                    "ramMaxMb", ramMaxMb, "chatUsed", chatUsed, "chatCap", chatCap, "dropped", dropped,
                    "retried", retried);
        }

        static Perf fromJson(Map<String, Object> m) {
            if (m == null) {
                return UNKNOWN;
            }
            return new Perf(Json.num(m, "pathMs", -1), Json.i32(m, "tpsX10", -1), Json.i32(m, "pingMs", -1),
                    Json.i32(m, "ramUsedMb", -1), Json.i32(m, "ramMaxMb", -1), Json.i32(m, "chatUsed", -1),
                    Json.i32(m, "chatCap", -1), Json.num(m, "dropped", -1), Json.num(m, "retried", -1));
        }
    }

    /** The swarm link: whether it is up, peers heard from in the last 30 s, and the start failure if it is down. */
    public record Link(boolean up, int peersSeen, String failure) {
        public static final Link DOWN = new Link(false, 0, "");

        public Link {
            failure = failure == null ? "" : failure;
        }

        Map<String, Object> toJson() {
            return Json.map("up", up, "peersSeen", peersSeen, "failure", failure);
        }

        static Link fromJson(Map<String, Object> m) {
            return m == null ? DOWN : new Link(Json.bool(m, "up", false), Json.i32(m, "peersSeen", 0),
                    Json.str(m, "failure", ""));
        }
    }

    // ---- codec ----------------------------------------------------------------------------------------------------

    public String toJson() {
        Map<String, Object> m = CenterFeed.envelope(CenterFeed.KIND_BOT, writtenAtMs, seq);
        m.put("name", name);
        m.put("server", server);
        m.put("runMode", runMode);
        m.put("commandHost", commandHost);
        m.put("coordinator", coordinator);
        m.put("pos", pos == null ? null : pos.toJson());
        m.put("health", health);
        m.put("maxHealth", maxHealth);
        m.put("hunger", hunger);
        m.put("dead", dead);
        m.put("process", process);
        m.put("path", path.toJson());
        m.put("inventory", inventory.toJson());
        List<Object> ex = new ArrayList<>();
        for (Execution e : executions) {
            ex.add(e.toJson());
        }
        m.put("executions", ex);
        List<Object> ls = new ArrayList<>();
        for (HeldLease l : leases) {
            ls.add(l.toJson());
        }
        m.put("leases", ls);
        m.put("perf", perf.toJson());
        m.put("link", link.toJson());
        m.put("gatherUs", gatherUs);
        return Json.write(m);
    }

    /** Throws {@link IllegalArgumentException} with {@link CenterFeed#check}'s reason for a file it must not read. */
    public static BotFeed fromJson(String text) {
        Map<String, Object> m = Json.object(text);
        String bad = CenterFeed.check(m, CenterFeed.KIND_BOT);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        List<Execution> ex = new ArrayList<>();
        for (Map<String, Object> e : Json.objs(m, "executions")) {
            ex.add(Execution.fromJson(e));
        }
        List<HeldLease> ls = new ArrayList<>();
        for (Map<String, Object> l : Json.objs(m, "leases")) {
            ls.add(HeldLease.fromJson(l));
        }
        return new BotFeed(Json.str(m, "name", ""), Json.num(m, "writtenAtMs", 0), Json.num(m, "seq", 0),
                Json.str(m, "server", ""), Json.str(m, "runMode", ""), Json.bool(m, "commandHost", false),
                Json.bool(m, "coordinator", false), Pos.fromJson(Json.obj(m, "pos")), Json.i32(m, "health", -1),
                Json.i32(m, "maxHealth", -1), Json.i32(m, "hunger", -1), Json.bool(m, "dead", false),
                Json.str(m, "process", "idle"), Route.fromJson(Json.obj(m, "path")),
                Inventory.fromJson(Json.obj(m, "inventory")), ex, ls, Perf.fromJson(Json.obj(m, "perf")),
                Link.fromJson(Json.obj(m, "link")), Json.num(m, "gatherUs", -1));
    }

    static double round(double v) {
        return Double.isFinite(v) ? Math.round(v * 100.0) / 100.0 : -1;
    }
}
