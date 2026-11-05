/*
 * Ostinato: the Phase 02 gate on one flat sprint. Not part of upstream Baritone.
 */
package baritone.contract;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

public class ComparisonHarnessTest {

    @Test
    public void flatSprintIsReproducibleAndLabelsNonEquivalentRows() throws Exception {
        List<Measurement> first = ComparisonHarness.measureFlat();
        List<Measurement> second = ComparisonHarness.measureFlat();
        String report = ComparisonHarness.report(first);
        assertEquals(report, ComparisonHarness.report(second));
        String golden;
        try (var in = ComparisonHarnessTest.class.getResourceAsStream("/contract/flat-sprint.txt")) {
            assertNotNull(in);
            golden = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r", "");
        }
        assertEquals(golden, report);

        Measurement physics = row(first, "physics-search");
        Measurement trivial = row(first, "trivial-sprint");
        assertEquals(Equivalence.EQUIVALENT, physics.equivalence());
        assertEquals(Equivalence.EQUIVALENT, trivial.equivalence());
        assertEquals(PlayerSimPhysics.SEMANTICS, physics.semantics());
        assertEquals(physics.semantics(), trivial.semantics());
        assertTrue(physics.executed());
        assertTrue(trivial.executed());
        assertTrue(physics.reached());
        assertTrue(trivial.reached());
        assertTrue("five blocks of sprinting, not a detour: " + physics.durationTicks(), physics.durationTicks() <= 30);
        assertEquals(physics.trajectory().initial(), trivial.trajectory().initial());
        assertEquals(physics.trajectory().initialRequirements(), trivial.trajectory().initialRequirements());
        assertNull(physics.trajectory().robustness());
        assertNull(trivial.trajectory().robustness());
        assertFalse(physics.trajectory().controls().ticks().isEmpty());
        assertFalse(physics.trajectory().predicted().isEmpty());

        Measurement ppe = row(first, "ppe-held-sprint");
        assertEquals(Equivalence.NOT_EQUIVALENT, ppe.equivalence());
        assertEquals(ComparisonHarness.PPE_SEMANTICS, ppe.semantics());
        assertTrue(ppe.line(), ppe.executed());
        assertNotNull(ppe.trajectory());
        assertNull(ppe.trajectory().robustness());

        Measurement stock = row(first, "stock-astar");
        assertEquals(Equivalence.NOT_EQUIVALENT, stock.equivalence());
        assertEquals(ComparisonHarness.PLANNER_SEMANTICS, stock.semantics());
        assertFalse(stock.executed());
        assertNull(stock.trajectory());
    }

    private static Measurement row(List<Measurement> rows, String name) {
        return rows.stream().filter(row -> row.navigator().equals(name)).findFirst().orElseThrow();
    }
}
