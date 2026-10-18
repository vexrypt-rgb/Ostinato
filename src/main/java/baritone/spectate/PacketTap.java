/*
 * Ostinato: new file (spectator learning, health estimator). Not part of upstream Baritone.
 */
package baritone.spectate;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ostinato: what the server says about other entities, handed on by {@code MixinSpectatePackets} once each packet has
 * been applied on the game thread. Positions are the packet's own (the entity's position codec base), not the
 * interpolated entity, so listeners see the server's numbers at the moment they arrive. Arrival time is
 * {@link System#nanoTime()} at the call, which on the game thread is within a frame of the packet.
 */
public final class PacketTap {
    private PacketTap() {}

    public interface Listener {
        /** A position (and maybe rotation) update; {@code full} for an absolute sync rather than a delta. */
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
