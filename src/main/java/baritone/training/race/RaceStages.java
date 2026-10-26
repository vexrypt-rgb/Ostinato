package baritone.training.race;

// Ostinato: stage list for every_block_item. Checklist steps are score hooks.
// TODO: the overnight runner fills these. Only crafting.use is credited from an existing circuit today.

import baritone.api.training.TrainingResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RaceStages {

    public static final String ITEMS = "items";
    public static final String BLOCKS = "blocks";
    public static final String ENCHANT = "enchant";
    public static final String NETHERITE = "netherite";
    public static final String INTERACT = "interact";

    public static final List<String> ORDER = List.of(ITEMS, BLOCKS, ENCHANT, NETHERITE, INTERACT);

    /** Table, bookshelves (15 around the table), lapis, level 30, then one enchant. */
    public static final List<String> ENCHANT_STEPS = List.of(
            "enchanting_table", "bookshelves", "lapis", "levels", "enchant");

    /** Debris, scrap, ingot, smithing template, then any netherite upgrade. */
    public static final List<String> NETHERITE_STEPS = List.of(
            "ancient_debris", "netherite_scrap", "netherite_ingot", "netherite_upgrade_smithing_template", "upgrade");

    /** The four netherite steps that are also registry ids. {@code upgrade} stays a score hook. */
    public static final Set<String> NETHERITE_ITEMS = Set.of(
            "ancient_debris", "netherite_scrap", "netherite_ingot", "netherite_upgrade_smithing_template");

    public static final List<String> INTERACT_FAMILIES = List.of(
            "door", "button", "lever", "chest", "furnace", "crafting", "bed");

    public static final List<String> INTERACT_ACTIONS = List.of("place", "break", "use");

    /** stone-step already crafts at a crafting table. Other interactions have no circuit yet. */
    public static final String WIRED_CRAFTING_USE = "crafting.use";

    private static final List<String> INTERACT_STEPS = interactSteps();

    private RaceStages() {}

    public static List<String> steps(String stage) {
        if (ENCHANT.equals(stage)) {
            return ENCHANT_STEPS;
        }
        if (NETHERITE.equals(stage)) {
            return NETHERITE_STEPS;
        }
        if (INTERACT.equals(stage)) {
            return INTERACT_STEPS;
        }
        return List.of();
    }

    /**
     * Steps the existing harness can already score. Inventory crafts (log-planks) are not a table interaction.
     * TODO: door, button, lever, chest, furnace, bed, and crafting place/break. Call
     * {@link RaceProgress#complete} for those.
     */
    public static List<String> fromTraining(TrainingResult result) {
        if (result != null && "stone-step".equals(result.circuitId) && result.craftTicks >= 0) {
            return List.of(WIRED_CRAFTING_USE);
        }
        return List.of();
    }

    private static List<String> interactSteps() {
        List<String> steps = new ArrayList<>();
        for (String family : INTERACT_FAMILIES) {
            for (String action : INTERACT_ACTIONS) {
                steps.add(family + "." + action);
            }
        }
        return List.copyOf(steps);
    }
}
