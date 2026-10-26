package baritone.training.race;

// Ostinato: mutable score hooks for one every_block_item run. Unknown ids are ignored.

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class RaceProgress {

    private final CollectableCatalog catalog;
    private final Set<String> items = new LinkedHashSet<>();
    private final Set<String> blocks = new LinkedHashSet<>();
    private final Map<String, Set<String>> steps = new LinkedHashMap<>();
    private int failures;
    private long timeTicks;

    private RaceProgress(CollectableCatalog catalog) {
        this.catalog = catalog;
    }

    public static RaceProgress start(CollectableCatalog catalog) {
        if (catalog == null) {
            throw new IllegalArgumentException("catalog");
        }
        return new RaceProgress(catalog);
    }

    public void collectItem(String raw) {
        String id = CollectableCatalog.normalize(raw);
        if (catalog.hasItem(id)) {
            items.add(id);
        }
    }

    public void collectBlock(String raw) {
        String id = CollectableCatalog.normalize(raw);
        if (catalog.hasBlock(id)) {
            blocks.add(id);
        }
    }

    /**
     * Counts the id on the item and block stages when it is in the catalog. A netherite material also
     * completes that checklist step. Enchant steps stay explicit: bookshelves means 15, levels means 30.
     */
    public void noteObtained(String raw) {
        String id = CollectableCatalog.normalize(raw);
        collectItem(id);
        collectBlock(id);
        if (RaceStages.NETHERITE_ITEMS.contains(id)) {
            complete(RaceStages.NETHERITE, id);
        }
    }

    /** Checklist step. A stage with no such step, or an item/block id, is ignored. */
    public void complete(String stage, String step) {
        String id = stage == null ? "" : stage.trim().toLowerCase(Locale.ROOT);
        List<String> allowed = RaceStages.steps(id);
        if (allowed.isEmpty()) {
            return;
        }
        String name = step == null ? "" : step.trim().toLowerCase(Locale.ROOT);
        if (!allowed.contains(name)) {
            return;
        }
        steps.computeIfAbsent(id, key -> new LinkedHashSet<>()).add(name);
    }

    public void addFailures(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("failures");
        }
        this.failures += count;
    }

    public void setFailures(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("failures");
        }
        this.failures = count;
    }

    public void setTimeTicks(long ticks) {
        if (ticks < 0) {
            throw new IllegalArgumentException("time");
        }
        this.timeTicks = ticks;
    }

    public RaceScore score() {
        RaceScore.Stage itemsStage = stage(RaceStages.ITEMS, items.size(), catalog.itemCount());
        RaceScore.Stage blocksStage = stage(RaceStages.BLOCKS, blocks.size(), catalog.blockCount());
        RaceScore.Stage enchantStage = stage(RaceStages.ENCHANT, done(RaceStages.ENCHANT), RaceStages.ENCHANT_STEPS.size());
        RaceScore.Stage netheriteStage = stage(RaceStages.NETHERITE, done(RaceStages.NETHERITE), RaceStages.NETHERITE_STEPS.size());
        RaceScore.Stage interactStage = stage(RaceStages.INTERACT, done(RaceStages.INTERACT), RaceStages.steps(RaceStages.INTERACT).size());
        List<RaceScore.Stage> stages = List.of(itemsStage, blocksStage, enchantStage, netheriteStage, interactStage);
        int collected = 0;
        int total = 0;
        for (RaceScore.Stage stage : stages) {
            collected += stage.collected;
            total += stage.total;
        }
        return new RaceScore(collected, total, failures, timeTicks, stages, credited());
    }

    private static RaceScore.Stage stage(String id, int collected, int total) {
        int capped = collected < 0 ? 0 : Math.min(collected, total);
        return new RaceScore.Stage(id, capped, total);
    }

    private int done(String stage) {
        Set<String> got = steps.get(stage);
        return got == null ? 0 : got.size();
    }

    private List<String> credited() {
        List<String> list = new ArrayList<>();
        for (String stage : List.of(RaceStages.ENCHANT, RaceStages.NETHERITE, RaceStages.INTERACT)) {
            Set<String> got = steps.get(stage);
            if (got == null) {
                continue;
            }
            for (String step : RaceStages.steps(stage)) {
                if (got.contains(step)) {
                    list.add(step);
                }
            }
        }
        return List.copyOf(list);
    }
}
