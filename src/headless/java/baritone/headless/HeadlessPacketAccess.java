
/*
 * Ostinato: fully headless 26.3 protocol bot. New file, replaces no Baritone code.
 *
 * 26.3 movement and entity-event packets keep the entity id protected/private, with
 * no public getter. Combat tracking has to read that id.
 */
package baritone.headless;

import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

final class HeadlessPacketAccess {

    private static final VarHandle MOVE_ID = field(ClientboundMoveEntityPacket.class, "entityId");
    private static final VarHandle EVENT_ID = field(ClientboundEntityEventPacket.class, "entityId");

    private HeadlessPacketAccess() {
    }

    static int moveEntityId(ClientboundMoveEntityPacket packet) {
        return (int) MOVE_ID.get(packet);
    }

    static int entityEventId(ClientboundEntityEventPacket packet) {
        return (int) EVENT_ID.get(packet);
    }

    private static VarHandle field(Class<?> type, String name) {
        try {
            return MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                    .findVarHandle(type, name, int.class);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
