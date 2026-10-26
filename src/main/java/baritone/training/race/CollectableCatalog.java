package baritone.training.race;

// Ostinato: collectable ids for the every_block_item race. Built only when that circuit runs.
// Pathfinding does not reference this class. A block with no inventory item is not collectable.

import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Item and block ids a race may collect. Paths in {@link #EXCLUDED} are never targets, and a block whose
 * {@link Block#asItem()} is air is not a block target (portals, fluids, fire, piston heads, farmland).
 */
public final class CollectableCatalog {

    /**
     * Registry paths that are not survival collectables. Match is the path only, so {@code light} does not
     * drop {@code light_blue_wool}. Mod ids use the same path rule.
     */
    public static final Set<String> EXCLUDED = Set.of(
            "air", "cave_air", "void_air",
            "bedrock", "barrier", "reinforced_deepslate",
            "command_block", "chain_command_block", "repeating_command_block", "command_block_minecart",
            "structure_block", "structure_void", "jigsaw",
            "spawner", "trial_spawner", "ominous_trial_spawner", "vault", "ominous_vault",
            "end_portal", "end_portal_frame", "end_gateway", "nether_portal",
            "light", "moving_piston", "piston_head",
            "debug_stick", "knowledge_book", "petrified_oak_slab",
            "test_block", "test_instance_block");

    private static volatile CollectableCatalog registry;

    private final List<String> items;
    private final Set<String> itemSet;
    private final List<String> blocks;
    private final Set<String> blockSet;

    private CollectableCatalog(List<String> items, List<String> blocks) {
        this.items = items;
        this.itemSet = Set.copyOf(items);
        this.blocks = blocks;
        this.blockSet = Set.copyOf(blocks);
    }

    /** Hand list for tests. Excluded paths and blanks are dropped. */
    public static CollectableCatalog of(Collection<String> items, Collection<String> blocks) {
        return new CollectableCatalog(clean(items), clean(blocks));
    }

    /**
     * Live registries. Safe to call again. In a headless run this boots the game registries once;
     * in a client they are already up and the bootstrap call returns.
     */
    public static CollectableCatalog fromRegistry() {
        CollectableCatalog cached = registry;
        if (cached != null) {
            return cached;
        }
        synchronized (CollectableCatalog.class) {
            if (registry == null) {
                SharedConstants.tryDetectVersion();
                Bootstrap.bootStrap();
                registry = scan();
            }
            return registry;
        }
    }

    public List<String> items() {
        return items;
    }

    public List<String> blocks() {
        return blocks;
    }

    public int itemCount() {
        return items.size();
    }

    public int blockCount() {
        return blocks.size();
    }

    public boolean hasItem(String raw) {
        return itemSet.contains(normalize(raw));
    }

    public boolean hasBlock(String raw) {
        return blockSet.contains(normalize(raw));
    }

    /** {@code minecraft:stone} and {@code Stone} both become {@code stone}. Other namespaces keep the prefix. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return "";
        }
        int colon = text.indexOf(':');
        String namespace = "minecraft";
        String path = text;
        if (colon >= 0) {
            namespace = text.substring(0, colon);
            path = text.substring(colon + 1);
        }
        if (namespace.isEmpty() || path.isEmpty() || path.indexOf(':') >= 0) {
            return "";
        }
        return "minecraft".equals(namespace) ? path : namespace + ":" + path;
    }

    public static boolean excluded(String raw) {
        String id = normalize(raw);
        if (id.isEmpty()) {
            return true;
        }
        int colon = id.indexOf(':');
        String path = colon < 0 ? id : id.substring(colon + 1);
        return EXCLUDED.contains(path);
    }

    private static CollectableCatalog scan() {
        TreeSet<String> items = new TreeSet<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == null || item == Items.AIR) {
                continue;
            }
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null || excluded(id.getPath())) {
                continue;
            }
            items.add(store(id));
        }
        TreeSet<String> blocks = new TreeSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block == null || block.defaultBlockState().isAir()) {
                continue;
            }
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null || excluded(id.getPath())) {
                continue;
            }
            Item asItem = block.asItem();
            if (asItem == null || asItem == Items.AIR) {
                continue;
            }
            Identifier itemId = BuiltInRegistries.ITEM.getKey(asItem);
            if (itemId != null && excluded(itemId.getPath())) {
                continue;
            }
            blocks.add(store(id));
        }
        return new CollectableCatalog(List.copyOf(items), List.copyOf(blocks));
    }

    private static List<String> clean(Collection<String> raw) {
        TreeSet<String> set = new TreeSet<>();
        if (raw != null) {
            for (String id : raw) {
                String normalized = normalize(id);
                if (!normalized.isEmpty() && !excluded(normalized)) {
                    set.add(normalized);
                }
            }
        }
        return List.copyOf(set);
    }

    private static String store(Identifier id) {
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }
}
