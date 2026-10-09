package baritone.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Every vanilla structure id (one per variant), with its family and home dimension. */
public final class StructureInfo {
    public static final String OVERWORLD = "overworld", NETHER = "the_nether", END = "the_end";

    public final String id;
    public final String family;
    public final String dimension;

    private StructureInfo(String id, String family, String dimension) {
        this.id = id;
        this.family = family;
        this.dimension = dimension;
    }

    public static final List<StructureInfo> ALL = new ArrayList<>();

    private static void add(String family, String dim, String... ids) {
        for (String id : ids) ALL.add(new StructureInfo(id, family, dim));
    }

    static {
        add("village", OVERWORLD, "village_plains", "village_desert", "village_savanna", "village_snowy", "village_taiga");
        add("mineshaft", OVERWORLD, "mineshaft", "mineshaft_mesa");
        add("ruined_portal", OVERWORLD, "ruined_portal", "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_mountain",
                "ruined_portal_ocean", "ruined_portal_swamp");
        add("ocean_ruin", OVERWORLD, "ocean_ruin_cold", "ocean_ruin_warm");
        add("shipwreck", OVERWORLD, "shipwreck", "shipwreck_beached");
        add("temple", OVERWORLD, "desert_pyramid", "jungle_pyramid", "igloo", "swamp_hut");
        add("pillager_outpost", OVERWORLD, "pillager_outpost");
        add("mansion", OVERWORLD, "mansion");
        add("monument", OVERWORLD, "monument");
        add("stronghold", OVERWORLD, "stronghold");
        add("ancient_city", OVERWORLD, "ancient_city");
        add("trial_chambers", OVERWORLD, "trial_chambers");
        add("trail_ruins", OVERWORLD, "trail_ruins");
        add("buried_treasure", OVERWORLD, "buried_treasure");
        add("fortress", NETHER, "fortress");
        add("bastion_remnant", NETHER, "bastion_remnant");
        add("nether_fossil", NETHER, "nether_fossil");
        add("ruined_portal", NETHER, "ruined_portal_nether");
        add("end_city", END, "end_city");
    }

    public static StructureInfo byId(String id) {
        String k = strip(id);
        for (StructureInfo s : ALL) if (s.id.equals(k)) return s;
        return null;
    }

    /** True when {@code query} names this structure's id or its family ("village" matches every village variant). */
    public static boolean matches(String structureId, String query) {
        String q = strip(query);
        if (q.equals(structureId)) return true;
        StructureInfo s = byId(structureId);
        return s != null && s.family.equals(q);
    }

    public static String strip(String id) {
        String k = id.toLowerCase(Locale.ROOT);
        return k.startsWith("minecraft:") ? k.substring(10) : k;
    }
}
