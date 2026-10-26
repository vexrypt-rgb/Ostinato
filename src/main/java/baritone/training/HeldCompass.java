package baritone.training;

// Ostinato: read the compass the player is holding or has on the hotbar. The needle's own target, not a server lookup.

import baritone.api.utils.IPlayerContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Main hand, then offhand, then the rest of the hotbar.
 * A lodestone tracker is the position the client uses to draw the needle, including a manhunt compass that rewrites
 * that tracker onto a player ({@code tracked} may be false; the position is still the needle). A compass that only
 * names a player is aimed only while that player entity is loaded here.
 */
public final class HeldCompass {

    private static final String[] NAME_KEYS = {"player", "target", "manhunt", "tracked", "trackedplayer"};

    private HeldCompass() {}

    public static CompassNeedle.Reading read(IPlayerContext ctx) {
        if (ctx == null || ctx.player() == null || !(ctx.world() instanceof ClientLevel level)) {
            return CompassNeedle.read(CompassNeedle.Kind.NONE, false, "", 0, 0, 0, "", 0, 0, 0);
        }
        Player player = ctx.player();
        ItemStack stack = choose(player);
        if (stack == null) {
            return CompassNeedle.read(CompassNeedle.Kind.NONE, false, "", 0, 0, 0, here(level), feet(player).x, feet(player).y, feet(player).z);
        }
        return fromStack(level, player, stack);
    }

    static ItemStack choose(Player player) {
        ItemStack main = player.getMainHandItem();
        ItemStack off = player.getOffhandItem();
        boolean[] hot = new boolean[9];
        int selected = player.getInventory().getSelectedSlot();
        var items = player.getInventory().getNonEquipmentItems();
        for (int i = 0; i < 9 && i < items.size(); i++) {
            hot[i] = i != selected && isCompass(items.get(i));
        }
        int slot = CompassNeedle.pick(isCompass(main), isCompass(off), hot);
        if (slot == CompassNeedle.HAND) {
            return main;
        }
        if (slot == CompassNeedle.OFFHAND) {
            return off;
        }
        if (slot >= 0 && slot < items.size()) {
            return items.get(slot);
        }
        return null;
    }

    static boolean isCompass(ItemStack stack) {
        return stack != null && !stack.isEmpty() && (stack.is(Items.COMPASS) || stack.is(Items.RECOVERY_COMPASS));
    }

    private static CompassNeedle.Reading fromStack(ClientLevel level, Player self, ItemStack stack) {
        Vec3 feet = feet(self);
        String here = here(level);
        if (stack.is(Items.RECOVERY_COMPASS)) {
            GlobalPos death = self.getLastDeathLocation().orElse(null);
            if (death == null) {
                return CompassNeedle.read(CompassNeedle.Kind.RECOVERY, false, "", 0, 0, 0, here, feet.x, feet.y, feet.z);
            }
            return at(CompassNeedle.Kind.RECOVERY, death, here, feet);
        }
        LodestoneTracker tracker = stack.get(DataComponents.LODESTONE_TRACKER);
        if (tracker != null) {
            // tracked=false is how a manhunt compass keeps a position after the lodestone block is gone.
            GlobalPos pos = tracker.target().orElse(null);
            if (pos == null) {
                return CompassNeedle.read(CompassNeedle.Kind.LODESTONE, false, "", 0, 0, 0, here, feet.x, feet.y, feet.z);
            }
            return at(CompassNeedle.Kind.LODESTONE, pos, here, feet);
        }
        String token = playerToken(stack);
        if (token != null) {
            Player loaded = loadedPlayer(level, self, token);
            if (loaded == null) {
                return CompassNeedle.read(CompassNeedle.Kind.PLAYER, false, "", 0, 0, 0, here, feet.x, feet.y, feet.z);
            }
            return CompassNeedle.read(CompassNeedle.Kind.PLAYER, true, here, loaded.getBlockX(), loaded.getBlockY(), loaded.getBlockZ(),
                    here, feet.x, feet.y, feet.z);
        }
        GlobalPos spawn = level.getRespawnData().globalPos();
        if (spawn == null) {
            return CompassNeedle.read(CompassNeedle.Kind.SPAWN, false, "", 0, 0, 0, here, feet.x, feet.y, feet.z);
        }
        return at(CompassNeedle.Kind.SPAWN, spawn, here, feet);
    }

    private static CompassNeedle.Reading at(CompassNeedle.Kind kind, GlobalPos pos, String here, Vec3 feet) {
        String dim = pos.dimension().identifier().toString();
        return CompassNeedle.read(kind, true, dim, pos.pos().getX(), pos.pos().getY(), pos.pos().getZ(), here, feet.x, feet.y, feet.z);
    }

    /** A player name or canonical UUID stored on the item. Absent when the item does not name anyone. */
    static String playerToken(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null || data.isEmpty()) {
            return null;
        }
        return token(data.copyTag(), 0);
    }

    private static String token(CompoundTag tag, int depth) {
        if (tag == null || depth > 2) {
            return null;
        }
        for (String key : tag.keySet()) {
            if (!nameKey(key)) {
                continue;
            }
            Optional<String> text = tag.getString(key);
            if (text.isPresent() && !text.get().isBlank()) {
                return text.get().trim();
            }
            Tag child = tag.get(key);
            if (child instanceof CompoundTag nested) {
                String inner = token(nested, depth + 1);
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    private static boolean nameKey(String key) {
        String low = key.toLowerCase(Locale.ROOT);
        for (String want : NAME_KEYS) {
            if (want.equals(low)) {
                return true;
            }
        }
        return false;
    }

    /** Loaded client players only. The tab list has no position, so a name that is not loaded is not a goal. */
    private static Player loadedPlayer(ClientLevel level, Player self, String token) {
        UUID id = null;
        try {
            id = UUID.fromString(token);
        } catch (IllegalArgumentException ignored) {
            id = null;
        }
        for (AbstractClientPlayer player : level.players()) {
            if (player == null || player == self || !player.isAlive()) {
                continue;
            }
            if (id != null && id.equals(player.getUUID())) {
                return player;
            }
            if (player.getName().getString().equalsIgnoreCase(token)) {
                return player;
            }
        }
        return null;
    }

    private static Vec3 feet(Player player) {
        return player.position();
    }

    private static String here(ClientLevel level) {
        return level.dimension().identifier().toString();
    }
}
