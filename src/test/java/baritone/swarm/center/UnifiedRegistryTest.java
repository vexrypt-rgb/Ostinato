package baritone.swarm.center;

import baritone.api.command.CommandPermission;
import baritone.api.command.FormPlacement;
import baritone.command.defaults.CenterCommand;
import baritone.command.defaults.GotoCommand;
import baritone.command.defaults.MineCommand;
import baritone.command.defaults.SwarmCommand;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Ostinato: permission, argument, lease and button checks. A refusal must not call the dispatcher. */
public class UnifiedRegistryTest {

    private static UnifiedRegistry registry() {
        List<RegistryEntry> hash = List.of(
                Catalogs.fromDescribed(List.of("swarm"), "swarm", CommandPermission.OWNER, null,
                        SwarmCommand.declaredSchema(), SwarmCommand.windowForms()),
                Catalogs.fromDescribed(List.of("goto"), "goto", CommandPermission.SWARM, null,
                        GotoCommand.declaredSchema(), GotoCommand.windowForms()),
                Catalogs.fromDescribed(List.of("mine"), "mine", CommandPermission.SWARM, null,
                        MineCommand.declaredSchema(), MineCommand.windowForms()),
                Catalogs.fromDescribed(List.of("set"), "set", CommandPermission.OWNER, null, List.of(), List.of()),
                Catalogs.fromDescribed(List.of("agent"), "agent", CommandPermission.AI, null, List.of(), List.of()),
                Catalogs.fromDescribed(List.of("cancel"), "cancel", CommandPermission.OWNER, null, List.of(), List.of()),
                Catalogs.fromDescribed(List.of("center"), "center", CommandPermission.OWNER, null,
                        CenterCommand.declaredSchema(), CenterCommand.windowForms())
        );
        String at = """
                ---
                v1
                name=dig
                perm=swarm
                lease=true
                mutate=true
                ambiguous=true
                origin=at
                arg=count:int:required
                ---
                v1
                name=goto
                perm=owner
                lease=false
                ---
                """;
        return UnifiedRegistry.of(hash, Catalogs.parseAt(at));
    }

    @Test public void localChatDoesNotReadPermissionAndStillRuns() {
        AtomicInteger runs = new AtomicInteger();
        RegistryResult result = registry().execute(RegistryCall.localChat("set allowBreak false"), () -> ran(runs));
        assertTrue(result.executed());
        assertEquals(1, runs.get());
    }

    @Test public void ownerCommandsAreRefusedFromSwarmAiAndPublic() {
        UnifiedRegistry registry = registry();
        for (RegistryCall call : List.of(
                RegistryCall.swarm("bot", true, "swarm spawn Bot"),
                RegistryCall.ai("Steve", "Steve", true, "goto,mine,swarm", false, "swarm spawn Bot"),
                RegistryCall.pub("Steve", "set allowBreak false"),
                RegistryCall.ai("Steve", "Steve", true, "goto,mine,settings", false, "set allowBreak false"))) {
            AtomicInteger runs = new AtomicInteger();
            RegistryResult result = registry.execute(call, () -> ran(runs));
            assertFalse(call.kind + " " + result.reason(), result.admitted());
            assertFalse(result.executed());
            assertEquals(0, runs.get());
            assertEquals("unauthorized", result.reason());
        }
    }

    @Test public void windowOwnerMaySpawnAndAStrangerMayNot() {
        UnifiedRegistry registry = registry();
        AtomicInteger runs = new AtomicInteger();
        RegistryResult ok = registry.execute(RegistryCall.window("Steve", "Steve", "swarm spawn Bot"), () -> ran(runs));
        assertTrue(ok.executed());
        assertEquals(1, runs.get());
        RegistryResult denied = registry.execute(RegistryCall.window("Alex", "Steve", "swarm spawn Bot"), () -> ran(runs));
        assertEquals("unauthorized", denied.reason());
        assertEquals(1, runs.get());
    }

    @Test public void unauthenticatedCallerDoesNotRun() {
        UnifiedRegistry registry = registry();
        RegistryCall call = new RegistryCall(RegistryCall.Kind.WINDOW, false, "", "", false, true, "", false,
                "goto 1 2 3", null, RegistryCall.Scope.LOCAL);
        AtomicInteger runs = new AtomicInteger();
        RegistryResult result = registry.execute(call, () -> ran(runs));
        assertEquals("unauthenticated", result.reason());
        assertEquals(0, runs.get());
    }

    @Test public void swarmMemberMayGotoAndANonMemberMayNot() {
        UnifiedRegistry registry = registry();
        AtomicInteger runs = new AtomicInteger();
        assertTrue(registry.execute(RegistryCall.swarm("bot", true, "goto 1 2 3"), () -> ran(runs)).executed());
        RegistryResult denied = registry.execute(RegistryCall.swarm("bot", false, "goto 1 2 3"), () -> ran(runs));
        assertEquals("unauthorized", denied.reason());
        assertEquals(1, runs.get());
    }

    @Test public void aiRunsWhitelistedSwarmCommandsAndRefusesTheRest() {
        UnifiedRegistry registry = registry();
        AtomicInteger runs = new AtomicInteger();
        RegistryCall allowed = RegistryCall.ai("Steve", "Steve", true, "goto,mine", false, "goto 1 2 3");
        assertTrue(registry.execute(allowed, () -> ran(runs)).executed());
        RegistryCall wrongOwner = RegistryCall.ai("Alex", "Steve", true, "goto,mine", false, "mine stone");
        assertEquals("unauthorized", registry.execute(wrongOwner, () -> ran(runs)).reason());
        RegistryCall notATool = RegistryCall.ai("Steve", "Steve", true, "mine", false, "goto 1 2 3");
        assertEquals("unauthorized", registry.execute(notATool, () -> ran(runs)).reason());
        RegistryCall agent = RegistryCall.ai("Steve", "Steve", true, "goto,mine", false, "agent hello");
        assertEquals("unauthorized", registry.execute(agent, () -> ran(runs)).reason());
        assertEquals(1, runs.get());
    }

    @Test public void aiDryRunDoesNotDispatch() {
        AtomicInteger runs = new AtomicInteger();
        RegistryCall call = RegistryCall.ai("Steve", "Steve", true, "goto", true, "goto 1 2 3");
        RegistryResult result = registry().execute(call, () -> ran(runs));
        assertEquals("preview", result.reason());
        assertFalse(result.executed());
        assertEquals(0, runs.get());
    }

    @Test public void invalidArgumentsDoNotDispatch() {
        AtomicInteger runs = new AtomicInteger();
        RegistryResult empty = registry().execute(RegistryCall.window("Steve", "", "goto"), () -> ran(runs));
        assertEquals("invalid", empty.reason());
        RegistryResult bad = registry().execute(RegistryCall.window("Steve", "", "swarm explode"), () -> ran(runs));
        assertEquals("invalid", bad.reason());
        assertEquals(0, runs.get());
    }

    @Test public void leaseAndMultiBotMutationDoNotDispatch() {
        UnifiedRegistry registry = registry();
        AtomicInteger runs = new AtomicInteger();
        RegistryCall lease = RegistryCall.swarm("bot", true, "dig 3");
        assertEquals("lease", registry.execute(lease, () -> ran(runs)).reason());
        RegistryCall preview = new RegistryCall(RegistryCall.Kind.SWARM, true, "bot", "", true, true, "", true,
                "dig 3", "bot", RegistryCall.Scope.ONE);
        assertEquals("preview", registry.execute(preview, () -> ran(runs)).reason());
        RegistryCall all = new RegistryCall(RegistryCall.Kind.SWARM, true, "bot", "", true, true, "", false,
                "mine stone", "*", RegistryCall.Scope.ALL);
        assertEquals("phase1", registry.execute(all, () -> ran(runs)).reason());
        assertEquals(0, runs.get());
    }

    @Test public void aBusyTargetRefusesASecondMineUntilCancel() {
        UnifiedRegistry registry = registry();
        AtomicInteger runs = new AtomicInteger();
        assertTrue(registry.execute(RegistryCall.window("Steve", "", "mine stone").withScope(RegistryCall.Scope.ONE, "Bot"),
                () -> ran(runs)).executed());
        RegistryResult peer = registry.execute(RegistryCall.swarm("bot", true, "goto 1 2 3")
                .withScope(RegistryCall.Scope.ONE, "Bot"), () -> ran(runs));
        assertEquals("busy", peer.reason());
        assertEquals(1, runs.get());
        assertTrue(registry.execute(RegistryCall.window("Steve", "", "goto 1 2 3")
                .withScope(RegistryCall.Scope.ONE, "Bot"), () -> ran(runs)).executed());
        assertEquals(2, runs.get());
        assertTrue(registry.execute(RegistryCall.swarm("bot", true, "cancel").withScope(RegistryCall.Scope.ONE, "Bot"),
                () -> ran(runs)).executed());
        assertTrue(registry.execute(RegistryCall.swarm("bot", true, "goto 1 2 3")
                .withScope(RegistryCall.Scope.ONE, "Bot"), () -> ran(runs)).executed());
        assertEquals(4, runs.get());
    }

    @Test public void dispatcherFailureDoesNotEchoTheException() {
        RegistryResult result = registry().execute(RegistryCall.window("Steve", "", "goto 1 2 3"), () -> {
            throw new IllegalStateException("TOKEN-SECRET");
        });
        assertEquals("failed", result.reason());
        assertFalse(result.reason().contains("TOKEN"));
    }

    @Test public void hashPermissionWinsOverTheAtCatalog() {
        RegistryEntry gotoCommand = registry().find("goto");
        assertEquals(CommandPermission.SWARM, gotoCommand.permission);
        assertEquals(RegistryEntry.Origin.HASH, gotoCommand.origin);
        assertEquals(CommandPermission.SWARM, registry().find("cancel").permission);
        assertTrue(registry().find("dig").leaseNeeding);
        assertEquals(RegistryEntry.Origin.AT, registry().find("dig").origin);
        UnifiedRegistry missingPerm = UnifiedRegistry.of(List.of(), Catalogs.parseAt("""
                ---
                v1
                name=custom
                ---
                """));
        assertEquals(CommandPermission.OWNER, missingPerm.find("custom").permission);
    }

    @Test public void buttonsComeFromRegistryForms() {
        UnifiedRegistry registry = registry();
        List<String> ids = registry.buttons().stream().map(button -> button.form().id()).toList();
        assertTrue(ids.contains("spawn"));
        assertTrue(ids.contains("goto"));
        assertTrue(ids.contains("mine"));
        assertTrue(ids.contains("layout-wall"));
        assertFalse(ids.contains("dig"));
        ButtonModel spawn = registry.buttons().stream().filter(button -> "spawn".equals(button.form().id())).findFirst().orElseThrow();
        assertEquals(FormPlacement.WINDOW, spawn.form().placement());
        assertEquals("swarm spawn Bot", spawn.line(Map.of("name", "Bot")));
        assertEquals("swarm spawn Bot 2 example.org account:Steve",
                spawn.line(Map.of("name", "Bot", "count", "2", "server", "example.org", "account", "Steve")));
        UnifiedRegistry bare = UnifiedRegistry.of(List.of(
                Catalogs.fromDescribed(List.of("help"), "help", CommandPermission.OWNER, null, List.of(), List.of())
        ), List.of());
        assertTrue(bare.buttons().isEmpty());
    }

    @Test public void buttonPressUsesTheSameExecuteAndRefusesOwnerFromAi() {
        UnifiedRegistry registry = registry();
        ButtonModel spawn = registry.buttons().stream().filter(button -> "spawn".equals(button.form().id())).findFirst().orElseThrow();
        String line = spawn.line(Map.of("name", "Bot"));
        AtomicInteger runs = new AtomicInteger();
        RegistryResult refused = registry.execute(RegistryCall.ai("Steve", "Steve", true, "goto,swarm", false, line),
                () -> ran(runs));
        assertEquals("unauthorized", refused.reason());
        assertEquals(0, runs.get());
        assertTrue(registry.execute(RegistryCall.window("Steve", "Steve", line), () -> ran(runs)).executed());
        assertEquals(1, runs.get());
    }

    @Test public void atAndHashLookUpSeparatelyAndListOneEntryPerName() {
        UnifiedRegistry registry = registry();
        assertEquals(RegistryEntry.Origin.AT, registry.find("@goto").origin);
        assertEquals(CommandPermission.OWNER, registry.find("@goto").permission);
        assertEquals(RegistryEntry.Origin.HASH, registry.find("#goto").origin);
        assertEquals(RegistryEntry.Origin.HASH, registry.find("goto").origin);
        assertEquals(null, registry.find("#dig"));
        assertEquals(1, registry.entries().stream().filter(e -> "goto".equals(e.name)).count());
        assertTrue(registry.entries().stream().anyMatch(e -> "dig".equals(e.name)));
    }

    @Test public void anAliasNeverTakesAnotherEntrysName() {
        UnifiedRegistry registry = UnifiedRegistry.of(List.of(
                Catalogs.fromDescribed(List.of("mine", "m"), "mine", CommandPermission.SWARM, null, List.of(), List.of()),
                Catalogs.fromDescribed(List.of("goto", "mine", "m"), "goto", CommandPermission.SWARM, null, List.of(), List.of()),
                Catalogs.fromDescribed(List.of("m"), "m", CommandPermission.OWNER, null, List.of(), List.of())
        ), List.of());
        assertEquals("mine", registry.find("mine").name);
        assertEquals("m", registry.find("m").name);
        assertEquals("goto", registry.find("goto").name);
        UnifiedRegistry twice = UnifiedRegistry.of(List.of(
                Catalogs.fromDescribed(List.of("mine", "dig2"), "old", CommandPermission.SWARM, null, List.of(), List.of()),
                Catalogs.fromDescribed(List.of("mine"), "new", CommandPermission.SWARM, null, List.of(), List.of())
        ), List.of());
        assertEquals(1, twice.entries().size());
        assertEquals(null, twice.find("dig2"));
    }

    @Test public void aLocalCancelFreesTheLocalSlot() {
        UnifiedRegistry registry = registry();
        AtomicInteger runs = new AtomicInteger();
        assertTrue(registry.execute(RegistryCall.window("Steve", "", "mine stone"), () -> ran(runs)).executed());
        assertTrue(registry.inFlight("local"));
        assertEquals("busy", registry.execute(RegistryCall.ai("Steve", "Steve", true, "goto,mine", false, "goto 1 2 3"), () -> ran(runs)).reason());
        assertTrue(registry.execute(RegistryCall.localChat("#cancel"), () -> ran(runs)).executed());
        assertFalse(registry.inFlight("local"));
        assertTrue(registry.execute(RegistryCall.ai("Steve", "Steve", true, "goto,mine", false, "goto 1 2 3"), () -> ran(runs)).executed());
    }

    @Test public void anIdleProbeFreesAFinishedSlotAndAFailingProbeDoesNot() {
        UnifiedRegistry registry = registry();
        AtomicInteger runs = new AtomicInteger();
        assertTrue(registry.execute(RegistryCall.window("Steve", "", "mine stone"), () -> ran(runs)).executed());
        registry.idleProbe(target -> {
            throw new IllegalStateException("probe");
        });
        assertEquals("busy", registry.execute(RegistryCall.ai("Steve", "Steve", true, "goto,mine", false, "goto 1 2 3"), () -> ran(runs)).reason());
        registry.idleProbe("local"::equals);
        assertTrue(registry.execute(RegistryCall.ai("Steve", "Steve", true, "goto,mine", false, "goto 1 2 3"), () -> ran(runs)).executed());
        assertEquals(2, runs.get());
    }

    private static boolean ran(AtomicInteger runs) {
        runs.incrementAndGet();
        return true;
    }
}
