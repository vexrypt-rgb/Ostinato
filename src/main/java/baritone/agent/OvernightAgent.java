package baritone.agent;

// Ostinato: one agent turn for a hypothesis. The harness scores the result and does not path.

public interface OvernightAgent {

    void begin(HypothesisRun run);

    AgentTick poll(long nowMs);

    void cancel();

    final class AgentTick {
        public enum Status {
            PENDING, DONE, SKIPPED, ERROR
        }

        public final Status status;
        public final String provider;
        public final String model;
        public final String detail;

        public AgentTick(Status status, String provider, String model, String detail) {
            this.status = status == null ? Status.ERROR : status;
            this.provider = provider == null ? "" : provider;
            this.model = model == null ? "" : model;
            this.detail = detail == null ? "" : detail;
        }

        public static AgentTick pending() {
            return new AgentTick(Status.PENDING, "", "", "");
        }
    }
}
