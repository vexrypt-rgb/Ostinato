/*
 * Ostinato: new file (spectator learning, health estimator). Not part of upstream Baritone.
 */
package baritone.spectate;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ostinato: what the server says about other entities, handed on by {@code MixinSpectatePackets} once each packet has
 * been applied on the game thread. Positions are the packet's own (the entity's position codec base), not the
 * interpolated entity, so listeners see the server's numbers at the moment they arrive.
 * <p>
 * Arrival time on the game thread is only good to a frame or a client tick (packets wait in a queue until the next
 * frame or tick), which is about the length of the server tick being counted. So {@code MixinSpectateRecv} stamps
 * move packets as the network thread reads them, both with the time and with a count of server-tick bursts: a server
 * flushes what each tick sent together, so a quiet gap longer than {@link #BURST_GAP_NS} starts a new tick's burst.
 * {@link #stamp()} is the stamp of the packet being handled while listeners run.
 */
public final class PacketTap {
    private PacketTap() {}

    public interface Listener {
        /** A position and/or rotation update ({@code pos} null for rotation only); {@code full} for an absolute sync rather than a delta. */
        default void move(Entity e, Vec3 pos, float yRot, float xRot, boolean hasRot, boolean onGround, boolean full) {}

        default void teleport(Entity e) {}

        default void motion(Entity e, Vec3 velocity) {}

        default void animate(Entity e, int action) {}

        default void damage(Entity e, ClientboundDamageEventPacket p) {}

        default void entityEvent(Entity e, byte event) {}

        default void entityData(Entity e) {}

        default void equipment(Entity e) {}

        default void added(Entity e) {}

        /** Called before the entity leaves the world, while it can still be read. */
        default void removing(Entity e) {}

        default void levelEvent(int type, BlockPos pos, int data) {}
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    /** A quiet spell between packets longer than this ends one server tick's burst; ticks are 50 ms apart. */
    public static final long BURST_GAP_NS = 12_000_000L;
    private static long lastRecv, bursts; // network thread only
    private static final Map<Object, long[]> STAMPS = new IdentityHashMap<>(); // guarded by itself
    private static long[] current;

    /** Network thread: a clientbound packet was read. */
    public static void received(Object packet) {
        if (!any()) return;
        long now = System.nanoTime();
        if (now - lastRecv > BURST_GAP_NS) bursts++;
        lastRecv = now;
        if (packet instanceof ClientboundBundlePacket b) {
            for (Object sub : b.subPackets()) stampIfMove(sub, now);
        } else {
            stampIfMove(packet, now);
        }
    }

    private static void stampIfMove(Object packet, long now) {
        if (!(packet instanceof ClientboundMoveEntityPacket) && !(packet instanceof ClientboundEntityPositionSyncPacket)) return;
        synchronized (STAMPS) {
            if (STAMPS.size() > 4096) STAMPS.clear(); // handled packets take theirs; this only drops ones never handled
            STAMPS.put(packet, new long[]{now, bursts});
        }
    }

    /** Game thread, around the listeners of one packet: makes its network stamp {@link #stamp()}. */
    public static void begin(Object packet) {
        synchronized (STAMPS) {
            current = STAMPS.remove(packet);
        }
    }

    public static void end() {
        current = null;
    }

    /** {receive nanoTime, burst count} of the move packet being handled, or null if it was not stamped. */
    public static long[] stamp() {
        return current;
    }

    public static void add(Listener l) {
        if (!LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void remove(Listener l) {
        LISTENERS.remove(l);
    }

    public static boolean any() {
        return !LISTENERS.isEmpty();
    }

    public static List<Listener> listeners() {
        return LISTENERS;
    }
}
