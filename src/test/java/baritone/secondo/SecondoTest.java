/*
 * Ostinato: new file (Secondo). Not part of upstream Baritone.
 */

package baritone.secondo;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.gui.model.SettingCategorizer;
import baritone.gui.model.SettingCategory;
import baritone.secondo.SecondoFeatures.Id;
import baritone.secondo.SecondoFeatures.Lookup;
import baritone.secondo.SecondoFeatures.State;
import baritone.secondo.SecondoPolicy.Config;
import baritone.secondo.SecondoPolicy.Decision;
import baritone.secondo.SecondoPolicy.Mode;
import baritone.secondo.SecondoPolicy.Observation;
import baritone.secondo.SecondoPolicy.Request;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Policy, goals, chat words, and feature gates. No world and no second player. */
public class SecondoTest {

    @Test
    public void defaultsMatchTheSettingsSourceAndStayOff() throws Exception {
        Config d = Config.defaults();
        assertFalse(d.enabled());
        assertEquals(4, d.followDistance());
        assertFalse(d.assist());
        assertEquals(6, d.assistRadius());
        assertEquals(2, d.assistStand());
        assertTrue(d.pickup());
        assertEquals(6, d.pickupRadius());
        assertFalse(d.combatAssist());

        String source = Files.readString(settingsFile(), StandardCharsets.UTF_8);
        assertTrue(source.contains("secondoEnabled = new Setting<>(false)"));
        assertTrue(source.contains("secondoFollowDistance = new Setting<>(4)"));
        assertTrue(source.contains("secondoAssist = new Setting<>(false)"));
        assertTrue(source.contains("secondoAssistRadius = new Setting<>(6)"));
        assertTrue(source.contains("secondoAssistStand = new Setting<>(2)"));
        assertTrue(source.contains("secondoPickup = new Setting<>(true)"));
        assertTrue(source.contains("secondoPickupRadius = new Setting<>(6)"));
        assertTrue(source.contains("secondoCombatAssist = new Setting<>(false)"));
        for (String name : new String[]{
                "secondoEnabled", "secondoFollowDistance", "secondoAssist", "secondoAssistRadius",
                "secondoAssistStand", "secondoPickup", "secondoPickupRadius", "secondoCombatAssist"}) {
            assertEquals(name, SettingCategory.ADVANCED, SettingCategorizer.categorize(name));
        }
    }

    @Test
    public void disabledSelectsNoGoal() {
        Decision d = SecondoPolicy.decide(Config.defaults(), near());
        assertEquals(Mode.OFF, d.mode());
        assertNull(SecondoGoals.toGoal(d));
    }

    @Test
    public void enabledFollowIsGoalNearAtSecondoDistance() {
        Decision d = SecondoPolicy.decide(Config.defaults().enabled(true), near());
        assertEquals(Mode.FOLLOW, d.mode());
        assertEquals(Request.NONE, d.request());
        Goal goal = SecondoGoals.toGoal(d);
        assertTrue(goal instanceof GoalNear);
        assertTrue(goal.isInGoal(3, 64, 0));
        assertTrue(goal.isInGoal(3, 64, 4));
        assertFalse(goal.isInGoal(3, 64, 5));
    }

    @Test
    public void assistOffIgnoresANearbyBreak() {
        Decision d = SecondoPolicy.decide(Config.defaults().enabled(true), breaking(4, 64, 0));
        assertEquals(Mode.FOLLOW, d.mode());
        assertEquals(3, d.x());
        assertEquals(Request.NONE, d.request());
    }

    @Test
    public void assistBreakPathsNearThatBlock() {
        Decision d = SecondoPolicy.decide(Config.defaults().enabled(true).assist(true), breaking(4, 64, 0));
        assertEquals(Mode.ASSIST_BREAK, d.mode());
        assertEquals(Request.BREAK, d.request());
        assertEquals(4, d.x());
        assertEquals(2, d.range());
        Goal goal = SecondoGoals.toGoal(d);
        assertTrue(goal instanceof GoalNear);
        assertTrue(goal.isInGoal(4, 64, 2));
        assertFalse(goal.isInGoal(4, 64, 3));
    }

    @Test
    public void aFarBreakStaysOnFollow() {
        Decision d = SecondoPolicy.decide(Config.defaults().enabled(true).assist(true), breaking(40, 64, 0));
        assertEquals(Mode.FOLLOW, d.mode());
        assertEquals(3, d.x());
    }

    @Test
    public void assistPickupUsesGoalBlock() {
        Observation o = new Observation(true, true, 0, 64, 0, 3, 64, 0, false, 0, 0, 0, true, 2, 64, 0, false, null);
        Decision d = SecondoPolicy.decide(Config.defaults().enabled(true).assist(true), o);
        assertEquals(Mode.ASSIST_PICKUP, d.mode());
        assertEquals(Request.PICKUP, d.request());
        Goal goal = SecondoGoals.toGoal(d);
        assertTrue(goal instanceof GoalBlock);
        assertTrue(goal.isInGoal(2, 64, 0));
        assertFalse(goal.isInGoal(3, 64, 0));
    }

    @Test
    public void pickupOffLeavesTheDrop() {
        Observation o = new Observation(true, true, 0, 64, 0, 3, 64, 0, false, 0, 0, 0, true, 2, 64, 0, false, null);
        Decision d = SecondoPolicy.decide(Config.defaults().enabled(true).assist(true).pickup(false), o);
        assertEquals(Mode.FOLLOW, d.mode());
    }

    @Test
    public void combatAssistRecordsTheThreatAndStaysOnPrimo() {
        Observation o = new Observation(true, true, 0, 64, 0, 3, 64, 0, true, 4, 64, 0, true, 2, 64, 0, true, "Steve");
        Decision off = SecondoPolicy.decide(Config.defaults().enabled(true).assist(true), o);
        assertEquals(Mode.ASSIST_BREAK, off.mode());
        Decision on = SecondoPolicy.decide(Config.defaults().enabled(true).assist(true).combat(true), o);
        assertEquals(Mode.ASSIST_COMBAT, on.mode());
        assertEquals(Request.COMBAT, on.request());
        assertEquals("Steve", on.detail());
        assertEquals(3, on.x());
        assertEquals(4, on.range());
        assertTrue(SecondoGoals.toGoal(on) instanceof GoalNear);
    }

    @Test
    public void unboundOrDeadIsOff() {
        Observation unbound = new Observation(false, true, 0, 64, 0, 3, 64, 0, false, 0, 0, 0, false, 0, 0, 0, false, null);
        Observation dead = new Observation(true, false, 0, 64, 0, 3, 64, 0, false, 0, 0, 0, false, 0, 0, 0, false, null);
        assertEquals(Mode.OFF, SecondoPolicy.decide(Config.defaults().enabled(true), unbound).mode());
        assertEquals(Mode.OFF, SecondoPolicy.decide(Config.defaults().enabled(true), dead).mode());
    }

    @Test
    public void chatWords() {
        assertEquals(SecondoCommands.Verb.STATUS, SecondoCommands.parse(List.of()).verb());
        SecondoCommands.Call near = SecondoCommands.parse(List.of("follow"));
        assertEquals(SecondoCommands.Verb.FOLLOW, near.verb());
        assertNull(near.arg());
        assertEquals("Steve", SecondoCommands.parse(List.of("FOLLOW", "Steve")).arg());
        assertEquals(SecondoCommands.Verb.STOP, SecondoCommands.parse(List.of("cancel")).verb());
        assertEquals("on", SecondoCommands.parse(List.of("assist", "ON")).arg());
        assertNull(SecondoCommands.parse(List.of("assist")).arg());
        assertBad(List.of("follow", "a", "b"));
        assertBad(List.of("mine"));
        assertBad(List.of("assist", "maybe"));
    }

    /**
     * Ostinato 2.0 Phase 10: this test was written (2f4176049) when none of the planned types was on the branch; the
     * combine/main-maze-ppe merges brought all six in, so the old "absent" line failed (STATUS B5). It now asserts the
     * merged tree. Everything else is unchanged: with the types hidden the probe still reads ABSENT, and invoke is still a
     * no-op whatever the state, so nothing in Secondo calls the planned APIs yet.
     */
    @Test
    public void plannedApisArePresentAfterTheMergeAndInvokeIsStillANoop() {
        for (Id id : Id.values()) {
            assertTrue(id.typeName, SecondoFeatures.classPresent(id.typeName));
            assertEquals(id.name(), State.ABSENT, SecondoFeatures.probe(id, lookup(false, false, null)));
            assertFalse(SecondoFeatures.invoke(id, State.ABSENT));
            assertFalse(SecondoFeatures.invoke(id, State.OFF));
            assertFalse(SecondoFeatures.invoke(id, State.READY));
        }
        Map<Id, State> here = SecondoFeatures.probeAll(lookup(false, false, null));
        assertFalse(SecondoFeatures.consultPpe(here));
        assertTrue(SecondoFeatures.report(here).contains("invoke=noop"));
        assertTrue(SecondoFeatures.report(here).contains("ppe=absent"));
    }

    @Test
    public void gatesDegradeAndAReadyFlagDoesNotChangeTheGoal() {
        assertEquals(State.OFF, SecondoFeatures.probe(Id.CADENZA, lookup(true, false, null)));
        assertEquals(State.READY, SecondoFeatures.probe(Id.CADENZA, lookup(true, true, null)));
        assertEquals(State.OFF, SecondoFeatures.probe(Id.PRESTO, lookup(true, false, null)));
        assertEquals(State.OFF, SecondoFeatures.probe(Id.BOMBARD, lookup(true, true, null)));
        assertEquals(State.READY, SecondoFeatures.probe(Id.BOMBARD, lookup(true, true, "live")));
        assertEquals(State.READY, SecondoFeatures.probe(Id.GRACE_NOTE, lookup(true, false, null)));
        assertEquals(State.OFF, SecondoFeatures.probe(Id.GRACE_NOTE, lookup(true, false, "off")));
        assertEquals(State.READY, SecondoFeatures.probe(Id.SCHERZO, lookup(true, false, null)));
        assertEquals(State.READY, SecondoFeatures.probe(Id.PPE, lookup(true, false, null)));

        Map<Id, State> prestoOn = SecondoFeatures.probeAll(new Lookup() {
            @Override
            public boolean typePresent(String typeName) {
                return true;
            }

            @Override
            public boolean settingOn(String settingName) {
                return settingName.equals("presto");
            }

            @Override
            public String property(String key) {
                return "off";
            }
        });
        assertEquals(State.READY, prestoOn.get(Id.PRESTO));
        assertEquals(State.OFF, prestoOn.get(Id.GRACE_NOTE));
        assertTrue(SecondoFeatures.consultPpe(prestoOn));
        assertFalse(SecondoFeatures.invoke(Id.PRESTO, State.READY));

        Decision d = SecondoPolicy.decide(Config.defaults().enabled(true), near());
        Goal goal = SecondoGoals.toGoal(d);
        assertTrue(goal instanceof GoalNear);
        assertTrue(goal.isInGoal(3, 64, 4));
        assertFalse(goal.isInGoal(3, 64, 5));
    }

    private static Observation near() {
        return Observation.follow(0, 64, 0, 3, 64, 0);
    }

    private static Observation breaking(int x, int y, int z) {
        return new Observation(true, true, 0, 64, 0, 3, 64, 0, true, x, y, z, false, 0, 0, 0, false, null);
    }

    private static void assertBad(List<String> args) {
        try {
            SecondoCommands.parse(args);
            throw new AssertionError("expected reject: " + args);
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    /** Every id shares one answer so a single test can force absent, off, or ready. */
    private static Lookup lookup(boolean present, boolean setting, String property) {
        return new Lookup() {
            @Override
            public boolean typePresent(String typeName) {
                return present;
            }

            @Override
            public boolean settingOn(String settingName) {
                return setting;
            }

            @Override
            public String property(String key) {
                return property;
            }
        };
    }

    private static Path settingsFile() {
        Path path = Path.of("src/api/java/baritone/api/Settings.java");
        return Files.exists(path) ? path : Path.of("..").resolve(path);
    }
}
