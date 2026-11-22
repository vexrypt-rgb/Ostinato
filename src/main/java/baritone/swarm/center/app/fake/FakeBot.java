package baritone.swarm.center.app.fake;

import baritone.swarm.center.feed.BotFeed;

import java.util.ArrayList;
import java.util.List;

/**
 * Ostinato: Phase 3 test scaffolding. A mutable builder for one bot's {@link BotFeed}, with sane live-looking
 * defaults (in the overworld, full health, idle, link up). Tests change only what they check:
 * {@code swarm.bot("Azalea").health(4).process("Mine")}.
 */
public final class FakeBot {

    String name;
    long ageMs;
    String server = "localhost:25565";
    String runMode = "headless";
    boolean commandHost, coordinator, dead, inWorld = true;
    char dim = 'o';
    double x, y = 64, z;
    float yaw;
    int health = 20, hunger = 20;
    String process = "idle";
    BotFeed.Route path = BotFeed.Route.IDLE;
    BotFeed.Inventory inventory = new BotFeed.Inventory(30, 36, 4,
            List.of(new BotFeed.Item("cobblestone", 64), new BotFeed.Item("oak_log", 12)), 80, "iron_pickaxe");
    final List<BotFeed.Execution> executions = new ArrayList<>();
    final List<BotFeed.HeldLease> leases = new ArrayList<>();
    BotFeed.Perf perf = new BotFeed.Perf(12, 200, 3, 900, 2048, 2, 30, 0, 0);
    BotFeed.Link link = new BotFeed.Link(true, 2, "");
    long gatherUs = 40;

    FakeBot(String name) {
        this.name = name;
    }

    /** How old the feed is when the snapshot is taken (0 = just written). */
    public FakeBot age(long ms) { this.ageMs = ms; return this; }
    public FakeBot host() { return host(true); }
    public FakeBot host(boolean on) { this.commandHost = on; return this; }
    public FakeBot coordinator() { this.coordinator = true; return this; }
    public FakeBot runMode(String mode) { this.runMode = mode; return this; }
    public FakeBot server(String s) { this.server = s; return this; }
    public FakeBot at(double x, double y, double z) { this.x = x; this.y = y; this.z = z; this.inWorld = true; return this; }
    public FakeBot yaw(float yaw) { this.yaw = yaw; return this; }
    public FakeBot perf(BotFeed.Perf p) { this.perf = p == null ? BotFeed.Perf.UNKNOWN : p; return this; }
    public FakeBot gather(long us) { this.gatherUs = us; return this; }
    public FakeBot dim(char d) { this.dim = d; return this; }
    public FakeBot notInWorld() { this.inWorld = false; return this; }
    public FakeBot health(int h) { this.health = h; return this; }
    public FakeBot hunger(int h) { this.hunger = h; return this; }
    public FakeBot dead(boolean d) { this.dead = d; return this; }
    public FakeBot process(String p) { this.process = p; return this; }
    public FakeBot path(BotFeed.Route r) { this.path = r; return this; }
    public FakeBot inventory(BotFeed.Inventory inv) { this.inventory = inv; return this; }
    public FakeBot link(boolean up) { this.link = new BotFeed.Link(up, up ? 2 : 0, up ? "" : "not started"); return this; }

    /** Adds a ledger entry: {@code execution("goto", "running", 2000)}. */
    public FakeBot execution(String command, String state, long ageMs) {
        return execution(command, state, ageMs, "");
    }

    public FakeBot execution(String command, String state, long ageMs, String endReason) {
        executions.add(new BotFeed.Execution("cmd-" + executions.size(), command, "Copper", state, "unknown", ageMs,
                endReason));
        return this;
    }

    public FakeBot lease(String resource, String phase) {
        return lease(resource, 1, phase, 20_000);
    }

    public FakeBot lease(String resource, long gen, String phase, long leftMs) {
        leases.add(new BotFeed.HeldLease("lease-" + leases.size(), gen, resource, phase, leftMs, true));
        return this;
    }

    public String name() {
        return name;
    }

    /** The feed as the bot would have written it {@link #ageMs} before {@code nowMs}. */
    public BotFeed build(long nowMs, long seq) {
        return new BotFeed(name, nowMs - ageMs, seq, server, runMode, commandHost, coordinator,
                inWorld ? new BotFeed.Pos(dim, x, y, z, yaw) : null, health, 20, hunger, dead, process, path, inventory,
                executions, leases, perf, link, gatherUs);
    }
}
