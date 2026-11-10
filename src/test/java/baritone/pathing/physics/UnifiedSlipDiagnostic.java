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

package baritone.pathing.physics;

import baritone.pathing.kinematic.PlayerSim;
import baritone.pathing.physics.UnifiedBatchBench.Trial;
import baritone.pathing.physics.UnifiedHarness.End;
import baritone.pathing.physics.UnifiedHarness.Result;
import baritone.pathing.unified.UnifiedExecutor.Counter;
import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Exploratory, written after the Phase 06 gate had been judged: not gate evidence. It asks why the unified arm loses
 * arrivals in the batch's slip family (H2). It re-runs the gated slip trials (seed {@link UnifiedBatchBench#SEED}),
 * prints, for every trial where only one arm arrived, the state at the empty plan next to the other arm's state at the
 * same tick, re-plans that state with larger node budgets, then re-runs the whole family at each budget.
 *
 * <p>Off unless both {@code -Postinato_bench} and the environment variable {@code OSTINATO_SLIP_DIAG=1} are set, so a
 * re-run of the gate bench never runs it. With OSTINATO_WRITE_DOCS=1 it writes to docs/ostinato2/raw/phase06.
 */
public class UnifiedSlipDiagnostic {

    private static final int[] BUDGETS = {1500, 3000, 6000, 12000};

    private static String fmt(String f, Object... args) {
        return String.format(Locale.ROOT, f, args);
    }

    private static String state(PlayerSim p) {
        if (p == null) {
            return "-";
        }
        return fmt("x=%.3f z=%.3f vx=%.4f vz=%.4f speed=%.4f ground=%b", p.x, p.z, p.vx, p.vz, Math.hypot(p.vx, p.vz), p.onGround);
    }

    private static String plans(Trial t, PlayerSim from) {
        StringBuilder b = new StringBuilder();
        for (int budget : BUDGETS) {
            List<PhysicsPathfinder.Action> plan = from == null ? null
                    : new PhysicsPathfinder(t.scenario.model, budget).plan(from, t.scenario.gx, t.scenario.gy, t.scenario.gz, UnifiedHarness.RADIUS);
            b.append(' ').append(budget).append('=').append(plan == null || plan.isEmpty() ? "none" : plan.size() + "steps");
        }
        return b.toString();
    }

    private static String counters(Result r) {
        if (r.ux == null) {
            return "";
        }
        return fmt(" divergences=%d corrections=%d accepted=%d escalations=%d", r.count(Counter.DIVERGENCES),
                r.count(Counter.CORRECTIONS), r.count(Counter.CORRECTIONS_ACCEPTED), r.count(Counter.ESCALATIONS));
    }

    @Test
    public void slip() throws IOException {
        Assume.assumeTrue("-Postinato_bench and OSTINATO_SLIP_DIAG=1 run this",
                Boolean.getBoolean("ostinato.bench") && "1".equals(System.getenv("OSTINATO_SLIP_DIAG")));
        List<Trial> slip = new ArrayList<>();
        for (Trial t : UnifiedBatchBench.generateAll(UnifiedBatchBench.SEED, UnifiedBatchBench.N)) {
            if (t.family.equals("slip")) {
                slip.add(t);
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("Exploratory, after the gate; not gate evidence. Slip family, seed ").append(UnifiedBatchBench.SEED)
                .append(", n=").append(slip.size()).append('\n');
        out.append("Per trial where only one arm arrived (node budget ").append(UnifiedHarness.NODE_BUDGET).append(", as gated):\n");
        try {
            UnifiedHarness.trace = true;
            for (Trial t : slip) {
                Result legacy = UnifiedHarness.run(t.scenario, false);
                Result unified = UnifiedHarness.run(t.scenario, true);
                if ((legacy.end == End.ARRIVED) == (unified.end == End.ARRIVED)) {
                    continue;
                }
                Result failed = legacy.end == End.ARRIVED ? unified : legacy;
                Result other = failed == unified ? legacy : unified;
                int tick = failed.ticks;
                PlayerSim same = tick <= other.states.size() ? other.states.get(tick - 1) : null;
                out.append(fmt("trial %d failed=%s end=%s tick=%d plans=%d driftReplans=%d%s | %s%n", t.index,
                        failed.unified ? "unified" : "legacy", failed.end, tick, failed.plans, failed.driftReplans,
                        counters(failed), t.params));
                out.append("  failed arm at the empty plan:     ").append(state(failed.failState)).append('\n');
                out.append("    re-planned with budget:        ").append(plans(t, failed.failState)).append('\n');
                out.append(fmt("  other arm (%s, %s in %d ticks, %d plans) at tick %d: ", other.unified ? "unified" : "legacy",
                        other.end, other.ticks, other.plans, tick)).append(state(same)).append('\n');
                out.append("    planned from there with budget:").append(plans(t, same)).append('\n');
            }
            UnifiedHarness.trace = false;

            out.append("\nWhole slip family, both arms, at each node budget (legacy, unified):\n");
            for (int budget : BUDGETS) {
                UnifiedHarness.nodeBudget = budget;
                int[] arrived = new int[2], empty = new int[2], other = new int[2];
                int onlyLegacy = 0, onlyUnified = 0;
                long[] planNanos = new long[2];
                for (Trial t : slip) {
                    Result legacy = UnifiedHarness.run(t.scenario, false);
                    Result unified = UnifiedHarness.run(t.scenario, true);
                    Result[] both = {legacy, unified};
                    for (int k = 0; k < 2; k++) {
                        if (both[k].end == End.ARRIVED) {
                            arrived[k]++;
                        } else if (both[k].end == End.EMPTY_PLAN) {
                            empty[k]++;
                        } else {
                            other[k]++;
                        }
                        planNanos[k] += both[k].planNanos;
                    }
                    if (legacy.end == End.ARRIVED && unified.end != End.ARRIVED) {
                        onlyLegacy++;
                    } else if (unified.end == End.ARRIVED && legacy.end != End.ARRIVED) {
                        onlyUnified++;
                    }
                }
                out.append(fmt("budget=%d arrived=%d,%d emptyPlan=%d,%d other=%d,%d onlyLegacyArrived=%d onlyUnifiedArrived=%d planMsTotal=%.0f,%.0f%n",
                        budget, arrived[0], arrived[1], empty[0], empty[1], other[0], other[1], onlyLegacy, onlyUnified,
                        planNanos[0] / 1e6, planNanos[1] / 1e6));
            }
        } finally {
            UnifiedHarness.trace = false;
            UnifiedHarness.nodeBudget = UnifiedHarness.NODE_BUDGET;
        }
        System.out.print(out);
        Path dir = "1".equals(System.getenv("OSTINATO_WRITE_DOCS")) ? docs() : Paths.get("build", "ostinato2", "raw", "phase06");
        Files.createDirectories(dir);
        Files.write(dir.resolve("slip-diagnostic.txt"), out.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Path docs() {
        Path path = Paths.get("docs/ostinato2/raw/phase06");
        return Files.exists(path.getParent()) ? path : Paths.get("..").resolve("docs/ostinato2/raw/phase06");
    }
}
