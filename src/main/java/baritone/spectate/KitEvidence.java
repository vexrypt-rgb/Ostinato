/*
 * Ostinato: new file (spectator kit detection). Not part of upstream Baritone.
 */
package baritone.spectate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Ostinato: what one watched fighter has shown of their kit, as {@link KitSignatures} reads it. What they wear and
 * hold comes as a set of weighted tokens each time it changes ({@link #gear}); the time each token was shown is summed
 * so a token's value is the share of the watched time it was on show. Things they do (eat an apple, throw a pearl or a
 * potion, pop a totem) are counted as events. The worker thread owns it.
 */
public final class KitEvidence {
    /** A token in a hand for a fifth of the time counts fully: a sword player still holds the apple now and then. */
    static final double HELD_SCALE = 5;
    /** Gear is held to be on show for at most this long after the last update (a fighter gone from view). */
    static final int STALE = 200;

    private final Map<String, Double> shown = new HashMap<>(); // token -> weighted ticks
    private final Map<String, Integer> events = new HashMap<>();
    private Map<String, Double> current = Collections.emptyMap();
    private int since = -1;
    private double observed;
    private int lastEvent;

    /** What they wear and hold from {@code tick} on: token -> weight (a hand item 1, one armor slot's material 0.25). */
    public void gear(int tick, Map<String, Double> tokens) {
        flush(tick);
        current = tokens;
        since = tick;
    }

    /** They did something that tells of their kit ({@code ate:golden_apple}, {@code threw:ender_pearl}, ...). */
    public void event(int tick, String token) {
        events.merge(token, 1, Integer::sum);
        lastEvent = tick;
    }

    private void flush(int tick) {
        if (since < 0 || tick <= since) return;
        int dt = Math.min(tick - since, STALE);
        observed += dt;
        for (Map.Entry<String, Double> e : current.entrySet()) shown.merge(e.getKey(), e.getValue() * dt, Double::sum);
        since = tick;
    }

    /** Ticks of gear watched up to {@code now}. */
    public double observed(int now) {
        flush(now);
        return observed;
    }

    /** Token values 0..1, as the signatures read them. */
    public Map<String, Double> features(int now) {
        flush(now);
        Map<String, Double> out = new HashMap<>();
        if (observed > 0) {
            for (Map.Entry<String, Double> e : shown.entrySet()) {
                String k = e.getKey();
                double share = e.getValue() / observed;
                // what is worn is worth its share; what is held, five times it
                boolean worn = k.startsWith("armor:") || k.startsWith("glint:") || k.startsWith("effect:") || k.equals("elytra") || k.equals("trim") || k.equals("dyed");
                out.put(k, Math.min(1, worn ? share : share * HELD_SCALE));
            }
        }
        for (Map.Entry<String, Integer> e : events.entrySet()) out.merge(e.getKey(), Math.min(1, e.getValue() / 2.0), Math::max);
        return out;
    }

    public int events(String token) {
        return events.getOrDefault(token, 0);
    }

    public boolean empty() {
        return since < 0 && events.isEmpty();
    }

    /** What they wear and hold now. */
    public Map<String, Double> current() {
        return current;
    }

    /** A compact line of the strongest tokens, for logs. */
    public String summary(int now) {
        Map<String, Double> f = features(now);
        StringBuilder sb = new StringBuilder();
        f.entrySet().stream().filter(e -> e.getValue() >= 0.05).sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).limit(14)
                .forEach(e -> sb.append(e.getKey()).append('=').append(String.format(java.util.Locale.ROOT, "%.2f", e.getValue())).append(','));
        return sb.length() == 0 ? "-" : sb.substring(0, sb.length() - 1);
    }
}
