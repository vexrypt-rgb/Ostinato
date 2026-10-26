package baritone.training.race;

// Ostinato: collected/total for every_block_item, plus one line per stage. No API-key field.

import baritone.api.agent.RaceRun;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RaceScore {

    public final int collected;
    public final int total;
    public final int failures;
    public final long timeTicks;
    public final List<Stage> stages;
    public final List<String> credited;

    public RaceScore(int collected, int total, int failures, long timeTicks, List<Stage> stages, List<String> credited) {
        if (collected < 0 || total < 0 || failures < 0 || timeTicks < 0 || collected > total) {
            throw new IllegalArgumentException("score");
        }
        this.collected = collected;
        this.total = total;
        this.failures = failures;
        this.timeTicks = timeTicks;
        this.stages = List.copyOf(stages);
        this.credited = List.copyOf(credited);
    }

    public boolean passed() {
        return failures == 0 && total > 0 && collected == total;
    }

    public String outcome() {
        return passed() ? "pass" : "incomplete";
    }

    public double fraction() {
        return total == 0 ? 0.0 : (double) collected / (double) total;
    }

    public Stage stage(String id) {
        for (Stage stage : stages) {
            if (stage.id.equals(id)) {
                return stage;
            }
        }
        return null;
    }

    /** One line for the training result. The race file uses {@link #metrics()} instead. */
    public String summary() {
        StringBuilder text = new StringBuilder();
        text.append(collected).append('/').append(total);
        for (Stage stage : stages) {
            text.append(' ').append(stage.id).append('=').append(stage.progress());
        }
        text.append(" failures=").append(failures);
        text.append(" time=").append(timeTicks);
        return text.toString();
    }

    public Map<String, String> metrics() {
        Map<String, String> metrics = new LinkedHashMap<>();
        metrics.put("circuit", RaceCircuit.ID);
        metrics.put("collected", Integer.toString(collected));
        metrics.put("total", Integer.toString(total));
        metrics.put("progress", collected + "/" + total);
        metrics.put("timeTicks", Long.toString(timeTicks));
        metrics.put("failures", Integer.toString(failures));
        for (Stage stage : stages) {
            metrics.put("stage." + stage.id, stage.progress());
        }
        if (!credited.isEmpty()) {
            metrics.put("credited", String.join(",", credited));
        }
        return metrics;
    }

    public RaceRun toRun(String runId, String agent, String provider, String model) {
        return new RaceRun(runId, "collect every block and every item", agent, provider, model, metrics(), outcome());
    }

    public static final class Stage {
        public final String id;
        public final int collected;
        public final int total;

        public Stage(String id, int collected, int total) {
            this.id = id;
            this.collected = collected;
            this.total = total;
        }

        public String progress() {
            return collected + "/" + total;
        }
    }
}
