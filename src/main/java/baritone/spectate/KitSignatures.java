/*
 * Ostinato: new file (spectator kit detection). Not part of upstream Baritone.
 */
package baritone.spectate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: the table of kit signatures that spectator kit detection classifies watched fighters by, read from
 * {@code assets/baritone/ostinato/spectate-kits.txt} (or {@code baritone/spectate-kits.txt} in the game folder, which
 * replaces it). Each kit is a list of weighted clues over the evidence tokens of {@link KitEvidence}; the kit with the
 * highest score wins if it reaches its minimum, and the softmax of the scores gives the confidence. Adding a kit is
 * one line in the file, no code.
 */
public final class KitSignatures {
    public static final String RESOURCE = "/assets/baritone/ostinato/spectate-kits.txt";
    public static final String UNKNOWN = "unknown";
    /** How sharp the softmax over kit scores is. */
    static final double BETA = 1.2;
    static final double DEFAULT_MIN = 2.5;
    /** Watched ticks before a label is trusted fully; confidence grows linearly to there. */
    static final double FULL_TICKS = 100;

    public static final class Kit {
        public final String label;
        final double min;
        final List<String> clues = new ArrayList<>();
        final List<Double> weights = new ArrayList<>();

        Kit(String label, double min) {
            this.label = label;
            this.min = min;
        }
    }

    public static final class Result {
        public final String label;
        /** 0..1: the winning kit's softmax share, times how much was seen. */
        public final double confidence;
        /** The best kit's score, even when it fell short and the label is unknown. */
        public final String best;
        public final double score;

        Result(String label, double confidence, String best, double score) {
            this.label = label;
            this.confidence = confidence;
            this.best = best;
            this.score = score;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s %.2f", label, confidence);
        }
    }

    private final List<Kit> kits;

    private KitSignatures(List<Kit> kits) {
        this.kits = Collections.unmodifiableList(kits);
    }

    public List<Kit> kits() {
        return kits;
    }

    private static volatile KitSignatures defaults;

    /** The table in use: the game folder's copy if there is one, else the one in the jar. */
    public static KitSignatures get() {
        KitSignatures d = defaults;
        if (d != null) return d;
        synchronized (KitSignatures.class) {
            if (defaults != null) return defaults;
            KitSignatures k = null;
            Path user = Paths.get("baritone", "spectate-kits.txt");
            try {
                if (Files.isRegularFile(user)) k = parse(Files.newBufferedReader(user, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                System.out.println("Ostinato spectate: " + user + " unreadable, using the built-in kits: " + e);
            }
            if (k == null || k.kits.isEmpty()) {
                try (InputStream in = KitSignatures.class.getResourceAsStream(RESOURCE)) {
                    if (in != null) k = parse(new InputStreamReader(in, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    k = null;
                }
            }
            // not in the jar (unit tests run without the launch resources): the project's copy
            if (k == null) {
                try {
                    k = parse(Files.newBufferedReader(Paths.get("src/launch/resources" + RESOURCE), StandardCharsets.UTF_8));
                } catch (IOException e) {
                    k = new KitSignatures(new ArrayList<>());
                }
            }
            defaults = k;
            return k;
        }
    }

    public static KitSignatures parse(String text) {
        try {
            return parse(new StringReader(text));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One kit a line: {@code label [min=score] clue=weight ...}; {@code #} starts a comment. */
    public static KitSignatures parse(Reader r) throws IOException {
        List<Kit> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(r)) {
            String line;
            while ((line = br.readLine()) != null) {
                int hash = line.indexOf('#');
                if (hash >= 0) line = line.substring(0, hash);
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2 || parts[0].isEmpty()) continue;
                double min = DEFAULT_MIN;
                List<String[]> clues = new ArrayList<>();
                for (int i = 1; i < parts.length; i++) {
                    int eq = parts[i].lastIndexOf('=');
                    if (eq <= 0) throw new IllegalArgumentException("kit " + parts[0] + ": no weight in " + parts[i]);
                    String clue = parts[i].substring(0, eq).toLowerCase(Locale.ROOT);
                    if (clue.equals("min")) min = Double.parseDouble(parts[i].substring(eq + 1));
                    else clues.add(new String[]{clue, parts[i].substring(eq + 1)});
                }
                Kit k = new Kit(parts[0].toLowerCase(Locale.ROOT), min);
                for (String[] c : clues) {
                    k.clues.add(c[0]);
                    k.weights.add(Double.parseDouble(c[1]));
                }
                out.add(k);
            }
        }
        return new KitSignatures(out);
    }

    /** The value of one clue: the token's own, or for a glob the largest of the tokens it matches. */
    static double value(String clue, Map<String, Double> features) {
        if (clue.indexOf('*') < 0) {
            Double v = features.get(clue);
            return v == null ? 0 : v;
        }
        double best = 0;
        for (Map.Entry<String, Double> e : features.entrySet()) if (e.getValue() > best && glob(clue, e.getKey())) best = e.getValue();
        return best;
    }

    static boolean glob(String pattern, String s) {
        int star = pattern.indexOf('*');
        if (star < 0) return pattern.equals(s);
        String head = pattern.substring(0, star), rest = pattern.substring(star + 1);
        if (!s.startsWith(head)) return false;
        if (rest.isEmpty()) return true;
        for (int i = head.length(); i <= s.length(); i++) if (glob(rest, s.substring(i))) return true;
        return false;
    }

    /** Each kit's score for these features, in table order. */
    public Map<String, Double> scores(Map<String, Double> features) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Kit k : kits) {
            double s = 0;
            for (int i = 0; i < k.clues.size(); i++) s += k.weights.get(i) * value(k.clues.get(i), features);
            out.put(k.label, s);
        }
        return out;
    }

    /** Classifies features seen over {@code observedTicks}. */
    public Result classify(Map<String, Double> features, double observedTicks) {
        if (kits.isEmpty() || observedTicks <= 0) return new Result(UNKNOWN, 0, UNKNOWN, 0);
        Map<String, Double> sc = scores(features);
        String best = null;
        double bs = Double.NEGATIVE_INFINITY, bmin = DEFAULT_MIN;
        for (Kit k : kits) {
            double s = sc.get(k.label);
            if (s > bs) {
                bs = s;
                best = k.label;
                bmin = k.min;
            }
        }
        double z = 0;
        for (double s : sc.values()) z += Math.exp(BETA * (s - bs));
        double share = 1 / z;
        double seen = Math.min(1, observedTicks / FULL_TICKS);
        if (bs < bmin) return new Result(UNKNOWN, (1 - share * Math.min(1, bs / bmin)) * seen, best, bs);
        return new Result(best, share * seen, best, bs);
    }

    public Result classify(KitEvidence ev, int now) {
        return classify(ev.features(now), ev.observed(now));
    }
}
