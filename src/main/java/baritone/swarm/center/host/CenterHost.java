package baritone.swarm.center.host;

import baritone.swarm.accounts.PrivateFiles;
import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.RegistryCall;
import baritone.swarm.center.RegistryResult;
import baritone.swarm.center.TileActions;
import baritone.swarm.center.TileRoute;
import baritone.swarm.center.UnifiedRegistry;
import baritone.swarm.center.channel.CenterAuth;
import baritone.swarm.center.channel.CenterHostCore;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.channel.HostInfo;
import baritone.swarm.center.feed.CatalogFeed;
import baritone.swarm.center.feed.CenterFeed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Ostinato: Phase 3 command host, bot side ({@code swarmCenterHost}). Owns the loopback {@link CenterHostCore},
 * writes {@code host.key} (user-only), {@code host.json} and {@code catalog.json}, and runs each verified request on
 * the client thread ({@link #drain}) through the unified registry as a {@link RegistryCall.Kind#WINDOW} call: a
 * {@link CenterRequest.Op#PRESS} goes through {@link TileActions#press}, the same admission and {@code swarm send}
 * routing as an in-game window button; a {@link CenterRequest.Op#LINE} through {@link UnifiedRegistry#execute}. There
 * is no other path from the window into a bot.
 *
 * <p>Holding the key is what makes the caller the owner, so the call's actor is the owner's name
 * ({@code agentOwner}), or {@code center} when none is set (then any window call counts as the owner, as in the
 * in-game window).
 */
public final class CenterHost implements AutoCloseable {

    /** How long a request waits for the client thread before the window is told "timeout". */
    public static final long WAIT_MS = 5_000;

    private record Pending(CenterRequest request, CompletableFuture<CenterReply> reply) {}

    private final Path dir;
    private final String self;
    private final Supplier<UnifiedRegistry> registry;
    private final Predicate<String> run;
    private final Supplier<String> owner;
    private final Consumer<String> log;
    private final LongSupplier clock;
    private final ConcurrentLinkedQueue<Pending> queue = new ConcurrentLinkedQueue<>();
    private final CenterHostCore core;
    private long seq;

    /**
     * @param run      runs one registry line (no prefix) on this bot, as the in-game window's sink does
     * @param owner    the {@code agentOwner} setting
     */
    public CenterHost(Path dir, String self, Supplier<UnifiedRegistry> registry, Predicate<String> run,
                      Supplier<String> owner, Consumer<String> log, LongSupplier clock) throws IOException {
        this.dir = dir;
        this.self = self;
        this.registry = registry;
        this.run = run;
        this.owner = owner;
        this.log = log;
        this.clock = clock;
        byte[] key = CenterAuth.newKey();
        PrivateFiles.writeRestricted(CenterFeed.keyFile(dir), CenterAuth.encodeKey(key).getBytes(StandardCharsets.US_ASCII));
        core = new CenterHostCore(key, this::enqueue, clock);
        long now = clock.getAsLong();
        CenterFeed.writeAtomic(CenterFeed.hostFile(dir),
                new HostInfo(self, now, ++seq, core.port(), ProcessHandle.current().pid(), now).toJson());
        writeCatalog();
        log.accept("center-host: listening on 127.0.0.1:" + core.port() + " for " + dir);
    }

    public String self() {
        return self;
    }

    public int port() {
        return core.port();
    }

    /** Socket thread: hand the request to the client thread and wait for its answer. */
    private CenterReply enqueue(CenterRequest r) {
        Pending p = new Pending(r, new CompletableFuture<>());
        queue.add(p);
        try {
            return p.reply().get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            queue.remove(p);
            return CenterReply.refused(r.id(), "timeout", clock.getAsLong());
        }
    }

    /** Client thread, every tick: run what the window sent. */
    public void drain() {
        Pending p;
        while ((p = queue.poll()) != null) {
            CenterReply reply;
            try {
                reply = execute(p.request());
            } catch (RuntimeException e) {
                reply = CenterReply.refused(p.request().id(), "failed", clock.getAsLong());
            }
            log.accept("center-host: " + p.request().op().name().toLowerCase(java.util.Locale.ROOT) + " "
                    + (reply.admitted() ? "admitted" : "refused") + " " + reply.summary());
            p.reply().complete(reply);
        }
    }

    CenterReply execute(CenterRequest r) {
        long now = clock.getAsLong();
        UnifiedRegistry reg = registry.get();
        if (reg == null) {
            return CenterReply.refused(r.id(), "no registry", now);
        }
        String own = owner.get() == null ? "" : owner.get().trim();
        RegistryCall caller = RegistryCall.window(own.isEmpty() ? "center" : own, own, "");
        if (r.op() == CenterRequest.Op.LINE) {
            String line = r.line().startsWith("#") ? r.line().substring(1).trim() : r.line().trim();
            if (r.dryRun()) {
                return new CenterReply(r.id(), now, false, false, "preview", line, self);
            }
            RegistryResult res = reg.execute(caller.withLine(line), () -> run.test(line));
            return CenterReply.of(r.id(), res, line, self, now);
        }
        ButtonModel button = null;
        for (ButtonModel b : reg.buttons()) {
            if (b.command().equals(r.command()) && b.form().id().equals(r.formId())) {
                button = b;
            }
        }
        if (button == null) {
            return CenterReply.refused(r.id(), "unknown form", now);
        }
        String target = TileRoute.target(r.bot(), self);
        String line;
        try {
            line = TileRoute.route(button, target, r.values());
        } catch (IllegalArgumentException e) {
            return new CenterReply(r.id(), now, false, false, "invalid", "", self);
        }
        if (r.dryRun()) {
            return new CenterReply(r.id(), now, false, false, "preview", line, self);
        }
        RegistryResult res = TileActions.press(reg, caller, button, r.values(), target, run::test);
        return CenterReply.of(r.id(), res, line, self, now);
    }

    /** The registry buttons, so the window draws only forms this host admits. */
    public void writeCatalog() throws IOException {
        UnifiedRegistry reg = registry.get();
        if (reg == null) {
            return;
        }
        CatalogFeed cat = CatalogFeed.of(self, clock.getAsLong(), ++seq, reg.buttons(), cmd -> {
            var e = reg.find("#" + cmd);
            return e == null ? null : e.permission;
        });
        CenterFeed.writeAtomic(CenterFeed.catalogFile(dir), cat.toJson());
    }

    /** Stops listening and removes host.json and host.key, so the window says "no command host". */
    @Override
    public void close() {
        core.close();
        Pending p;
        while ((p = queue.poll()) != null) {
            p.reply().complete(CenterReply.refused(p.request().id(), "host closed", clock.getAsLong()));
        }
        try {
            java.nio.file.Files.deleteIfExists(CenterFeed.hostFile(dir));
            java.nio.file.Files.deleteIfExists(CenterFeed.keyFile(dir));
        } catch (IOException ignored) {
            // the next host overwrites them
        }
    }
}
