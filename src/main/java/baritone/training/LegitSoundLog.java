package baritone.training;

// Ostinato: recent client sounds for the legit track. Off, play() returns before this records anything.

import baritone.api.BaritoneAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/** A fixed ring of the last few classified sounds. Created on the first sound while the legit track is on. */
public final class LegitSoundLog {

    private static final int CAP = 8;
    private static String[] ring;
    private static int count;
    private static int next;

    private LegitSoundLog() {}

    public static boolean enabled() {
        return BaritoneAPI.getSettings().trainingLegit.value;
    }

    public static void hear(SoundInstance sound) {
        if (sound == null || !enabled()) {
            return;
        }
        String kind = LegitCues.soundKind(pathOf(sound));
        if (kind == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String bearing = "SELF";
        if (mc != null && mc.player != null) {
            bearing = LegitCues.bearing(sound.getX() - mc.player.getX(), sound.getZ() - mc.player.getZ());
        }
        push(kind + "@" + bearing);
    }

    /** Newest last. Empty when the legit track is off, and the ring is dropped. */
    public static synchronized List<String> recent() {
        if (!enabled()) {
            ring = null;
            count = 0;
            next = 0;
            return List.of();
        }
        if (ring == null || count == 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>(count);
        int start = (next - count + CAP) % CAP;
        for (int i = 0; i < count; i++) {
            out.add(ring[(start + i) % CAP]);
        }
        return out;
    }

    private static synchronized void push(String token) {
        if (!enabled()) {
            return;
        }
        if (ring == null) {
            ring = new String[CAP];
        }
        ring[next] = token;
        next = (next + 1) % CAP;
        if (count < CAP) {
            count++;
        }
    }

    /** Sound-event path ({@code entity.zombie.ambient}). The resolved file path is only a fallback. */
    private static String pathOf(SoundInstance sound) {
        try {
            Identifier id = sound.getIdentifier();
            if (id != null && id.getPath() != null && !id.getPath().isEmpty()) {
                return id.getPath();
            }
            if (sound.getSound() != null && sound.getSound().getLocation() != null) {
                return sound.getSound().getLocation().getPath();
            }
        } catch (RuntimeException ex) {
            return "";
        }
        return "";
    }
}
