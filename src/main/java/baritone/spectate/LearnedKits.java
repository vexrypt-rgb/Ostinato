/*
 * Ostinato: new file (spectator kit detection: kits learned from unknown loadouts). Not part of upstream Baritone.
 */
package baritone.spectate;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: kits nobody wrote down. A fighter watched long enough whose loadout fits none of {@link KitSignatures}
 * becomes a new kit, {@code learned-1}, {@code learned-2}, ..., kept in {@code baritone/spectate-kits-learned.txt}
 * beside the built-in table: its signature is the mean of the evidence tokens of every fighter put in it, with a short
 * description ("netherite + mace + wind charge"). A loadout seen again that is close enough (cosine similarity of
 * the token values at least {@link #SIMILAR}) joins that kit instead of making another. The label is used like any
 * other, so a learned kit gets its own 1v1 tallies and its own ACS priors once it has the fights.
 * <p>
 * File lines: {@code learned-1 n=<fighters> token=value ...} with the description on a {@code #} line above.
 */
public final class LearnedKits {
    public static final double SIMILAR = 0.85;
    /** Ticks a fighter must be watched before an unknown loadout may become a kit. */
    public static final double MIN_OBSERVED = 200;
    /** At most this many learned kits; past it, unknown stays unknown. */
    public static final int MAX = 64;

    public static final class Kit {
        public final String label;
        public int n;
        public String description;
        final Map<String, Double> mean = new LinkedHashMap<>();

        Kit(String label) {
            this.label = label;
        }
    }

    private final Path file;
    private final List<Kit> kits = new ArrayList<>();
    private int next = 1;

    public LearnedKits(Path file) {
        this.file = file;
        load();
    }

    private static volatile LearnedKits instance;

    public static LearnedKits get() {
        if (instance == null) {
            synchronized (LearnedKits.class) {
                if (instance == null) instance = new LearnedKits(Paths.get("baritone", "spectate-kits-learned.txt"));
            }
        }
        return instance;
    }

    public synchronized List<Kit> kits() {
        return new ArrayList<>(kits);
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            String desc = null;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.startsWith("#")) {
                    desc = line.substring(1).trim();
                    continue;
                }
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                Kit k = new Kit(parts[0]);
                k.description = desc;
                desc = null;
                for (int i = 1; i < parts.length; i++) {
                    int eq = parts[i].lastIndexOf('=');
                    if (eq <= 0) continue;
                    String key = parts[i].substring(0, eq);
                    double v = Double.parseDouble(parts[i].substring(eq + 1));
                    if (key.equals("n")) k.n = (int) v;
                    else k.mean.put(key, v);
                }
                kits.add(k);
                if (k.label.startsWith("learned-")) {
                    try {
                        next = Math.max(next, Integer.parseInt(k.label.substring(8)) + 1);
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException | RuntimeException e) {
            System.out.println("Ostinato spectate: " + file + " unreadable: " + e);
        }
    }

    private void save() {
        if (file == null) return;
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write("# Ostinato: kits learned by spectator kit detection from loadouts no built-in kit fits. Rename a label freely.\n");
                for (Kit k : kits) {
                    w.write("# " + k.description + "\n");
                    StringBuilder sb = new StringBuilder(k.label).append(" n=").append(k.n);
                    for (Map.Entry<String, Double> e : k.mean.entrySet()) sb.append(' ').append(e.getKey()).append('=').append(String.format(Locale.ROOT, "%.3f", e.getValue()));
                    w.write(sb.append('\n').toString());
                }
            }
        } catch (IOException e) {
            System.out.println("Ostinato spectate: " + file + ": " + e);
        }
    }

    static double cosine(Map<String, Double> a, Map<String, Double> b) {
        double dot = 0, na = 0, nb = 0;
        for (Map.Entry<String, Double> e : a.entrySet()) {
            na += e.getValue() * e.getValue();
            Double o = b.get(e.getKey());
            if (o != null) dot += e.getValue() * o;
        }
        for (double v : b.values()) nb += v * v;
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    /** The closest learned kit and its similarity, or null when none is close enough. */
    public synchronized Object[] match(Map<String, Double> features) {
        Kit best = null;
        double bs = 0;
        for (Kit k : kits) {
            double s = cosine(features, k.mean);
            if (s > bs) {
                bs = s;
                best = k;
            }
        }
        return best != null && bs >= SIMILAR ? new Object[]{best, bs} : null;
    }

    /**
     * One fighter whose loadout fit no built-in kit: joins the closest learned kit (its signature moves to the mean)
     * or becomes a new one. Returns the kit, or null when there is no room for another.
     */
    public synchronized Kit learn(Map<String, Double> features) {
        Map<String, Double> f = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : features.entrySet()) if (e.getValue() >= 0.05) f.put(e.getKey(), e.getValue());
        if (f.isEmpty()) return null;
        Object[] m = match(f);
        Kit k;
        if (m != null) {
            k = (Kit) m[0];
            // running mean over the fighters put in it; a token missing from one counts as 0 there
            java.util.Set<String> keys = new java.util.HashSet<>(k.mean.keySet());
            keys.addAll(f.keySet());
            for (String key : keys) k.mean.put(key, (k.mean.getOrDefault(key, 0.0) * k.n + f.getOrDefault(key, 0.0)) / (k.n + 1));
            k.mean.values().removeIf(v -> v < 0.02);
            k.n++;
        } else {
            if (kits.size() >= MAX) return null;
            k = new Kit("learned-" + next++);
            k.mean.putAll(f);
            k.n = 1;
            kits.add(k);
        }
        k.description = describe(k.mean);
        save();
        return k;
    }

    /** "netherite + mace + wind charge": the armor most worn, then the strongest held items and things used. */
    static String describe(Map<String, Double> mean) {
        String armor = null;
        double ab = 0.3;
        List<Map.Entry<String, Double>> rest = new ArrayList<>();
        for (Map.Entry<String, Double> e : mean.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("armor:")) {
                if (e.getValue() > ab) {
                    ab = e.getValue();
                    armor = k.substring(6);
                }
            } else if (!k.startsWith("glint:") && !k.equals("trim") && !k.equals("dyed") && e.getValue() >= 0.3) {
                rest.add(e);
            }
        }
        rest.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
        StringBuilder sb = new StringBuilder(armor == null ? "no armor" : armor);
        for (int i = 0; i < rest.size() && i < 4; i++) {
            String k = rest.get(i).getKey();
            int colon = k.indexOf(':');
            if (colon >= 0) k = k.substring(colon + 1);
            sb.append(" + ").append(k.replace("totem_of_undying", "totem").replace('_', ' '));
        }
        return sb.toString();
    }
}
