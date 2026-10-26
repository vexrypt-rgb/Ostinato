package baritone.api.agent;

// Ostinato: one hypothesis-race result. There is no API-key field.

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RaceRun {

    public final String runId;
    public final String hypothesis;
    public final String agent;
    public final String provider;
    public final String model;
    public final Map<String, String> metrics;
    public final String outcome;

    public RaceRun(String runId, String hypothesis, String agent, String provider, String model,
            Map<String, String> metrics, String outcome) {
        this.runId = required(runId, "runId");
        this.hypothesis = required(hypothesis, "hypothesis");
        this.agent = required(agent, "agent");
        this.provider = required(provider, "provider");
        this.model = model == null ? "" : model;
        this.outcome = required(outcome, "outcome");
        Map<String, String> copy = new LinkedHashMap<>();
        if (metrics != null) {
            for (Map.Entry<String, String> entry : metrics.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(entry.getKey(), entry.getValue());
                }
            }
        }
        this.metrics = Collections.unmodifiableMap(copy);
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }
}
