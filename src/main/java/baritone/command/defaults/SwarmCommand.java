/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */


package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.ArgDecl;
import baritone.api.command.Command;
import baritone.api.command.FormDecl;
import baritone.api.command.FormPlacement;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.api.utils.BetterBlockPos;
import baritone.behavior.SwarmBehavior;
import baritone.swarm.SwarmBuild;
import baritone.swarm.SwarmControl;
import baritone.swarm.accounts.AccountStore;
import baritone.swarm.accounts.AzureClientConfig;
import baritone.swarm.accounts.DpapiProtector;
import baritone.swarm.accounts.JdkHttpExchange;
import baritone.swarm.accounts.MicrosoftAuth;
import baritone.swarm.accounts.SecretProtector;
import baritone.swarm.accounts.SpawnAccountChoice;
// Ostinato: the OWNER command retains the unified registry and delegates process mechanics to pure helpers.
import baritone.swarm.spawn.*;
import baritone.swarm.SwarmKeys;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** {@code #swarm ping [group]}, {@code #swarm status}, {@code #swarm reload}, {@code #swarm build}, {@code #swarm stop}. */
public class SwarmCommand extends Command {

    // Ostinato: tab-complete, the registry schema, and the command-center buttons share this list.
    public static final List<String> ACTIONS = List.of(
            "ping", "status", "reload", "build", "stop", "spawn", "despawn", "bots");
    public static final List<String> PROTOCOL_ACTIONS = List.of("send", "outcome", "cancel", "lease"); // Ostinato: + lease

    public SwarmCommand(IBaritone baritone) {
        super(baritone, "swarm");
    }

    // Ostinato: spawn and despawn stay on this OWNER command. The window does not grow a second spawn path.
    public static List<ArgDecl> declaredSchema() {
        List<String> actions = new ArrayList<>(ACTIONS);
        actions.addAll(PROTOCOL_ACTIONS);
        return List.of(ArgDecl.enu("action", true, actions), ArgDecl.rest("rest", false));
    }

    public static List<FormDecl> windowForms() {
        return List.of(
                FormDecl.of("spawn", "Spawn", "swarm spawn", FormPlacement.WINDOW,
                        ArgDecl.token("name", true), ArgDecl.integer("count", false),
                        ArgDecl.token("server", false), ArgDecl.token("account", false)),
                FormDecl.of("despawn", "Despawn", "swarm despawn", FormPlacement.TILE, ArgDecl.token("name", true)),
                FormDecl.of("cancel", "Cancel", "swarm cancel", FormPlacement.TILE, ArgDecl.token("id", false)),
                FormDecl.of("stop-all", "Stop all", "swarm despawn all", FormPlacement.HEADER)
        );
    }

    @Override
    public List<ArgDecl> argumentSchema() {
        return declaredSchema();
    }

    @Override
    public List<FormDecl> forms() {
        return windowForms();
    }

    private SwarmBehavior swarm() throws CommandInvalidStateException {
        if (!(baritone instanceof Baritone)) {
            throw new CommandInvalidStateException("swarm is not available");
        }
        return ((Baritone) baritone).getSwarmBehavior();
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "status";
        SwarmBehavior swarm = swarm();
        switch (action) {
            // Ostinato: local child lifecycle uses the same OWNER permission as the rest of this command.
            case "spawn": {
                List<String> words = new ArrayList<>();
                while (args.hasAny()) words.add(args.getString());
                try {
                    spawn(SwarmSpawnArgs.parse(words), swarm);
                } catch (Exception e) {
                    throw new CommandInvalidStateException("swarm spawn: " + safeSpawnError(e));
                }
                return;
            }
            case "despawn": {
                args.requireExactly(1);
                try {
                    String target = SwarmSpawnArgs.despawn(List.of(args.getString()));
                    logDirect("swarm: stopped " + SwarmSpawnRegistry.INSTANCE.despawn(target) + " child process(es)");
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
                return;
            }
            case "bots": {
                args.requireMax(0);
                List<SwarmSpawnRegistry.Child> children = SwarmSpawnRegistry.INSTANCE.list();
                if (children.isEmpty()) logDirect("swarm: no spawned bots");
                children.forEach(child -> logDirect(child.status()));
                return;
            }
            case "status": {
                args.requireMax(0);
                swarm.statusLines().forEach(this::logDirect);
                return;
            }
            case "ping": {
                args.requireMax(1);
                String group = args.hasAny() ? args.getString() : null;
                SwarmControl control = swarm.control();
                if (control == null) {
                    throw new CommandInvalidStateException("swarm link is not running (" + swarm.state() + ")");
                }
                int n;
                try {
                    n = control.ping(group);
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
                logDirect("swarm: pinged " + n + " group(s); replies arrive as rate limits allow, see #swarm status");
                return;
            }
            case "reload": {
                args.requireMax(0);
                swarm.reload();
                logDirect("swarm: reloading roster and keyring");
                return;
            }
            case "build": {
                args.requireMin(2);
                String group = args.getString();
                String file = args.getString();
                BetterBlockPos origin = ctx.playerFeet();
                if (args.has(3)) {
                    origin = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
                }
                args.requireMax(0);
                Settings s = Baritone.settings();
                try {
                    running(swarm).start(group, file, origin.x, origin.y, origin.z, s.buildPartitionStrategy.value,
                            s.buildPartitionAxis.value, s.buildPartitionSeamWidth.value, s.buildPartitionGridColumns.value);
                } catch (IllegalArgumentException e) {
                    throw new CommandInvalidStateException(e.getMessage());
                }
                return;
            }
            case "stop": {
                args.requireMax(0);
                running(swarm).stop();
                logDirect("swarm: build stopped");
                return;
            }
            case "send": {
                // Ostinato: one registry command, sealed, with an id. Flag off keeps the old error.
                if (!Baritone.settings().swarmCommandProtocol.value) {
                    throw new CommandInvalidTypeException(args.consumed(), "ping, status, reload, build or stop");
                }
                args.requireMin(2);
                String target = args.getString();
                StringBuilder line = new StringBuilder();
                while (args.hasAny()) {
                    if (line.length() > 0) {
                        line.append(' ');
                    }
                    line.append(args.getString());
                }
                logDirect(swarm.submitCommand(target, line.toString()));
                return;
            }
            case "outcome": {
                if (!Baritone.settings().swarmCommandProtocol.value) {
                    throw new CommandInvalidTypeException(args.consumed(), "ping, status, reload, build or stop");
                }
                args.requireExactly(1);
                logDirect(swarm.commandOutcome(args.getString()));
                return;
            }
            case "cancel": {
                if (!Baritone.settings().swarmCommandProtocol.value) {
                    throw new CommandInvalidTypeException(args.consumed(), "ping, status, reload, build or stop");
                }
                args.requireExactly(1);
                logDirect(swarm.cancelCommand(args.getString()));
                return;
            }
            case "lease": {
                // Ostinato: #swarm lease status | revoke <resource|id> | <bot> <resource> <command...>.
                Settings ls = Baritone.settings();
                if (!ls.swarmLeases.value && !ls.swarmCoordinator.value) {
                    throw new CommandInvalidStateException("swarm lease: swarmLeases and swarmCoordinator are both false");
                }
                String sub = args.hasAny() ? args.getString() : "status";
                if (sub.equalsIgnoreCase("status")) {
                    args.requireMax(0);
                    List<String> lines = swarm.leaseStatusLines();
                    if (lines.isEmpty()) {
                        logDirect("swarm-lease: no guard or coordinator yet (link not up?)");
                    }
                    lines.forEach(this::logDirect);
                    return;
                }
                if (sub.equalsIgnoreCase("revoke")) {
                    args.requireExactly(1);
                    logDirect(swarm.leaseRevoke(args.getString()));
                    return;
                }
                args.requireMin(2);
                String resource = args.getString();
                StringBuilder line = new StringBuilder();
                while (args.hasAny()) {
                    if (line.length() > 0) {
                        line.append(' ');
                    }
                    line.append(args.getString());
                }
                logDirect(swarm.leaseOffer(sub, resource, line.toString()));
                return;
            }
            default:
                // Ostinato: the extra words appear only while swarmCommandProtocol is on.
                String expected = Baritone.settings().swarmCommandProtocol.value
                        ? "ping, status, reload, build, stop, spawn, despawn, bots, send, outcome, cancel or lease" // Ostinato:
                        : "ping, status, reload, build, stop, spawn, despawn or bots"; // Ostinato:
                throw new CommandInvalidTypeException(args.consumed(), expected);
        }
    }

    // Ostinato: only this thin adapter reads Minecraft state; helpers can be tested without a client.
    private void spawn(SwarmSpawnArgs request, SwarmBehavior swarm) throws Exception {
        var mc = ctx.minecraft();
        if (ctx.player() == null || mc.getConnection() == null || mc.level == null) {
            throw new IllegalArgumentException("Join a world with a connection before spawning bots");
        }
        var integrated = mc.getSingleplayerServer();
        if (integrated != null && !integrated.isPublished()) {
            throw new IllegalArgumentException("Singleplayer is not published; open the world to LAN first");
        }
        String server = request.server();
        if (server == null) {
            if (integrated != null) server = "127.0.0.1:" + integrated.getPort();
            else if (mc.getCurrentServer() != null) server = mc.getCurrentServer().ip;
        }
        if (server == null || server.isBlank()) throw new IllegalArgumentException("Cannot determine current server; specify host:port");
        String parent = ctx.player().getName().getString();
        SwarmSpawnRegistry registry = SwarmSpawnRegistry.INSTANCE;
        registry.checkNames(request.names(), parent);
        SwarmSpawnMemory.require(SwarmSpawnMemory::freeCommit, request.names().size());
        Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
        Path userHome = Path.of(System.getProperty("user.home"));
        SwarmSpawnCommandBuilder.Snapshot snapshot = SwarmSpawnCommandBuilder.current(game);
        boolean offline = SwarmSpawnAccounts.offline(server, parent, ctx.player().getUUID());
        List<Path> accountFiles = SwarmSpawnAccounts.launcherFiles(game);
        SecretProtector protector = null;
        try {
            protector = DpapiProtector.platform();
        } catch (RuntimeException ignored) {
            protector = null;
        }
        Path config = baritone instanceof Baritone ? ((Baritone) baritone).getDirectory() : game.resolve("baritone");
        AccountStore store = new AccountStore(config.resolve("accounts"), protector);
        if (!offline && protector == null) {
            throw new IllegalArgumentException("Windows DPAPI is not available");
        }
        String clientId = AzureClientConfig.clientId(config);
        SecretProtector refreshProtector = protector;
        List<SpawnSession> accounts = SpawnAccountChoice.choose(
                request, offline, store, accountFiles, (accountName, refresh) -> {
                    if (clientId.isBlank()) {
                        throw new IllegalArgumentException(AzureClientConfig.MISSING);
                    }
                    MicrosoftAuth.SignedIn signed = MicrosoftAuth.refresh(
                            clientId, refresh, new JdkHttpExchange());
                    store.saveMicrosoft(signed.name(), signed.uuid(), signed.minecraftAccess(), signed.refreshToken(), signed.expiryEpochMs());
                    if (!signed.name().equalsIgnoreCase(accountName)) {
                        store.remove(accountName); // Ostinato: a renamed profile must not keep its old refresh token
                    }
                    return new SpawnSession(signed.name(), signed.uuid(), signed.minecraftAccess());
                });
        Settings settings = Baritone.settings();
        Path roster = Path.of(settings.swarmRosterFile.value);
        if (!roster.isAbsolute()) roster = ((Baritone) baritone).getDirectory().resolve(roster);
        SwarmSpawnProvision.Link link = SwarmSpawnProvision.link(game, roster,
                SwarmKeys.sigilHome(settings.swarmSigilHome.value, System.getenv("SIGIL_HOME")), userHome, parent);
        settings.swarmRosterFile.value = link.roster().toString();
        settings.swarmSigilHome.value = link.home().toString();
        settings.swarmLocalSpoolDir.value = link.spool().toString();
        settings.swarmEnabled.value = true;
        SwarmSpawnProvision.persistParentLink(game, link);
        try {
            for (int i = 0; i < request.names().size(); i++) {
                SwarmSpawnMemory.require(SwarmSpawnMemory::freeCommit, 1);
                String name = request.names().get(i);
                Path childGame = game.resolve("ostinato-bots").resolve(name);
                Path signet = SwarmSpawnProvision.child(game, childGame, name, link, userHome);
                Path logs = childGame.resolve("logs");
                Files.createDirectories(logs);
                Path log = logs.resolve("spawn-" + System.currentTimeMillis() + ".log");
                ProcessBuilder builder = SwarmSpawnCommandBuilder.build(snapshot, name, server, childGame, log, signet, accounts.get(i), refreshProtector);
                logDirect("swarm: " + registry.start(name, server, childGame, log, builder).status());
            }
        } finally {
            swarm.reload();
        }
    }

    // Ostinato: parser/process exceptions must never print launch argv or account JSON into chat.
    private static String safeSpawnError(Exception error) {
        if (error instanceof IllegalArgumentException || error instanceof java.io.IOException
                || error instanceof baritone.swarm.crypto.SigilException
                || error instanceof baritone.swarm.roster.SwarmRosterException) {
            return error.getMessage();
        }
        return "Could not prepare child process (" + error.getClass().getSimpleName() + ")";
    }

    private static SwarmBuild running(SwarmBehavior swarm) throws CommandInvalidStateException {
        SwarmControl control = swarm.control();
        if (control == null || control.build() == null) {
            throw new CommandInvalidStateException("swarm link is not running (" + swarm.state() + ")");
        }
        return control.build();
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            TabCompleteHelper help = new TabCompleteHelper().append(ACTIONS.toArray(String[]::new)); // Ostinato:
            if (Baritone.settings().swarmCommandProtocol.value) {
                help.append(PROTOCOL_ACTIONS.toArray(String[]::new));
            }
            return help.filterPrefix(args.getString()).stream();
        }
        if (args.hasExactly(2) && (args.peekString().equalsIgnoreCase("ping") || args.peekString().equalsIgnoreCase("build"))) {
            SwarmControl control = baritone instanceof Baritone ? ((Baritone) baritone).getSwarmBehavior().control() : null;
            args.get();
            List<String> groups = control == null ? Collections.emptyList() : control.myGroups();
            return new TabCompleteHelper().append(groups.stream()).filterPrefix(args.getString()).stream();
        }
        // Ostinato: complete only names owned by this process registry.
        if (args.hasExactly(2) && args.peekString().equalsIgnoreCase("despawn")) {
            args.get();
            return new TabCompleteHelper().append("all")
                    .append(SwarmSpawnRegistry.INSTANCE.list().stream().map(SwarmSpawnRegistry.Child::name))
                    .filterPrefix(args.getString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Swarm link: ping members, show status, split builds";
    }

    @Override
    public List<String> getLongDesc() {
        List<String> lines = new ArrayList<>(Arrays.asList(
                "Controls the encrypted swarm link (needs swarmEnabled, a roster file and a sigil keyring).",
                "Every line sent is a sealed sigil token; there is no plaintext mode.",
                "",
                "Usage:",
                "> swarm spawn <name> [count] [server] [account:name] - launch up to 16 headless bots", // Ostinato:
                "> swarm despawn <name|all> - stop only this client's spawned bots", // Ostinato:
                "> swarm bots - list child names, PIDs, alive/dead state, servers and logs", // Ostinato:
                "  Spawn needs about 1.5 GB free commit per bot; remote online servers need launcher accounts.", // Ostinato:
                "> swarm status - roster, last-seen times and link health",
                "> swarm ping - ping every member of each of your groups",
                "> swarm ping <group> - ping the members of one group",
                "> swarm reload - reload the roster and keyring",
                "> swarm build <group> <file> [x y z] - as the group's lead, split a schematic (in the schematics",
                "  folder) over the group, one region per member, using the buildPartition* settings",
                "> swarm stop - stop the build job you lead, and your own region"
        ));
        if (Baritone.settings().swarmCommandProtocol.value) {
            lines.add("> swarm send <bot> <command> - send one command and wait for its ack");
            lines.add("> swarm outcome <id> - the ack and execution state for that command id");
            lines.add("> swarm cancel <id> - ask that bot to stop the command; canceled only after it confirms");
        }
        return lines;
    }
}
