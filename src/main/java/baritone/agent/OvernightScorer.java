package baritone.agent;

// Ostinato: the harness score for one overnight run. The agent already drove the bot.

import java.util.Map;

public interface OvernightScorer {

    OvernightScore score(HypothesisRun run, OvernightAgent.AgentTick agent, long elapsedMs);

    final class OvernightScore {
        public final String score;
        public final String outcome;
        public final Map<String, String> metrics;

        public OvernightScore(String score, String outcome, Map<String, String> metrics) {
            this.score = score == null || score.isBlank() ? "n/a" : score;
            this.outcome = outcome == null || outcome.isBlank() ? "incomplete" : outcome;
            this.metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        }
    }
}
