/*
 * Ostinato: regression suite, combat bench placeholder stage. New file, replaces no Baritone code.
 */
package baritone.regress;

import org.junit.Assume;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Ostinato: slot for the combat bench (VexBench, PvpProcess). VexBench is a live-client harness and is not on main yet,
 * so this stage records itself as skipped, saying why, unless a live runner already dropped its metrics file
 * ({@code combat:vexbench.json}, the StageResult shape) into the metrics folder. See docs/REGRESSION.md, "Live stages".
 */
public class CombatStage {

    @Test
    public void combat() throws Exception {
        Assume.assumeTrue("set -Dostinato.regress=true (./gradlew regress)", Regress.stage("combat"));
        Path out = Regress.out().resolve(StageResult.fileName("combat:vexbench"));
        if (Files.exists(out)) return; // a live runner wrote it
        boolean present;
        try {
            Class.forName("baritone.process.VexBench", false, CombatStage.class.getClassLoader());
            present = true;
        } catch (ClassNotFoundException e) {
            present = false;
        }
        StageResult r = new StageResult("combat:vexbench", "combat");
        r.commit = Regress.commit();
        r.status = "skipped";
        r.note = present ? "VexBench is on this commit but needs a live client; run the live combat stage (scripts/regress.sh --live)"
                : "VexBench is not on this commit (combat bench lives on the combat branches)";
        r.write(Regress.out());
    }
}
