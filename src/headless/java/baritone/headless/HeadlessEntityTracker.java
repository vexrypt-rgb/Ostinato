
/*
 * Ostinato: fully headless 26.3 protocol bot. New file, replaces no Baritone code.
 *
 * Tracks nearby entities from play packets so combat can look, walk in, and swing.
 * No client world entity getter exists headlessly.
 */
package baritone.headless;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HeadlessEntityTracker {

    /** Vanilla living-entity death event. */
    private static final byte DEATH_EVENT = 3;

    public static final class Tracked {
        public final int id;
        public final UUID uuid;
        public final EntityType<?> type;
        public final VecDeltaCodec codec = new VecDeltaCodec();
        public double x, y, z;
        public double vx, vy, vz;
        public float yaw, pitch;
        public boolean onGround;
        public boolean alive = true;
        public float health = -1f;

        Tracked(int id, UUID uuid, EntityType<?> type, double x, double y, double z, float yaw, float pitch) {
            this.id = id;
            this.uuid = uuid;
            this.type = type;
            setPos(x, y, z);
            this.yaw = yaw;
            this.pitch = pitch;
        }

        void setPos(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            codec.setBase(new Vec3(x, y, z));
        }

        public AABB box() {
            EntityDimensions dimensions = type.getDimensions();
            return dimensions.makeBoundingBox(x, y, z);
        }

        public Vec3 position() {
            return new Vec3(x, y, z);
        }

        public Vec3 eye() {
            return new Vec3(x, y + type.getDimensions().eyeHeight(), z);
        }

        public String typeId() {
            return BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        }
    }

    private final Map<Integer, Tracked> entities = new ConcurrentHashMap<>();

    /**
     * @return true when this packet is an entity-tracking packet (consumed even if it
     * referred to the bot itself and was ignored here).
     */
    public boolean handle(Object packet, int selfId) {
        if (packet instanceof ClientboundAddEntityPacket add) {
            if (add.getId() != selfId) {
                add(add);
            }
            return true;
        }
        if (packet instanceof ClientboundRemoveEntitiesPacket remove) {
            for (int id : remove.entityIds()) {
                if (id != selfId) {
                    entities.remove(id);
                }
            }
            return true;
        }
        if (packet instanceof ClientboundMoveEntityPacket move) {
            int id = HeadlessPacketAccess.moveEntityId(move);
            if (id != selfId) {
                Tracked tracked = entities.get(id);
                if (tracked != null) {
                    applyMove(tracked, move);
                }
            }
            return true;
        }
        if (packet instanceof ClientboundTeleportEntityPacket teleport) {
            if (teleport.id() != selfId) {
                Tracked tracked = entities.get(teleport.id());
                if (tracked != null) {
                    PositionMoveRotation change = teleport.change();
                    Vec3 pos = change.position();
                    tracked.setPos(pos.x, pos.y, pos.z);
                    tracked.vx = change.deltaMovement().x;
                    tracked.vy = change.deltaMovement().y;
                    tracked.vz = change.deltaMovement().z;
                    tracked.yaw = change.yRot();
                    tracked.pitch = change.xRot();
                    tracked.onGround = teleport.onGround();
                }
            }
            return true;
        }
        if (packet instanceof ClientboundEntityPositionSyncPacket sync) {
            if (sync.id() != selfId) {
                Tracked tracked = entities.get(sync.id());
                if (tracked != null) {
                    Vec3 pos = sync.position().endPosition();
                    tracked.setPos(pos.x, pos.y, pos.z);
                    tracked.yaw = sync.yRot();
                    tracked.pitch = sync.xRot();
                    tracked.onGround = sync.onGround();
                }
            }
            return true;
        }
        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            if (motion.id() != selfId) {
                Tracked tracked = entities.get(motion.id());
                if (tracked != null) {
                    Vec3 v = motion.movement();
                    tracked.vx = v.x;
                    tracked.vy = v.y;
                    tracked.vz = v.z;
                }
            }
            return true;
        }
        if (packet instanceof ClientboundEntityEventPacket event) {
            int id = HeadlessPacketAccess.entityEventId(event);
            if (id != selfId && event.getEventId() == DEATH_EVENT) {
                Tracked tracked = entities.get(id);
                if (tracked != null) {
                    tracked.alive = false;
                    tracked.health = 0f;
                }
            }
            return true;
        }
        return false;
    }

    private void add(ClientboundAddEntityPacket packet) {
        Tracked tracked = new Tracked(
                packet.getId(), packet.getUUID(), packet.getType(),
                packet.getX(), packet.getY(), packet.getZ(),
                packet.getYRot(), packet.getXRot());
        Vec3 movement = packet.getMovement();
        tracked.vx = movement.x;
        tracked.vy = movement.y;
        tracked.vz = movement.z;
        entities.put(tracked.id, tracked);
    }

    private static void applyMove(Tracked tracked, ClientboundMoveEntityPacket packet) {
        if (packet.hasPosition()) {
            PositionPath path = packet.getPositionDelta().decode(tracked.codec);
            Vec3 end = path.endPosition();
            tracked.setPos(end.x, end.y, end.z);
        }
        if (packet.hasRotation()) {
            tracked.yaw = packet.getYRot();
            tracked.pitch = packet.getXRot();
        }
        tracked.onGround = packet.isOnGround();
    }

    public Tracked get(int id) {
        return entities.get(id);
    }

    public int size() {
        return entities.size();
    }

    public void clear() {
        entities.clear();
    }

    /**
     * Nearest living target matching an optional type path ({@code zombie}) or any monster
     * when {@code typeFilter} is empty.
     */
    public Tracked nearest(Vec3 from, String typeFilter, double maxDistance) {
        String want = typeFilter == null ? "" : typeFilter.toLowerCase(Locale.ROOT);
        if (want.startsWith("minecraft:")) {
            want = want.substring("minecraft:".length());
        }
        Tracked best = null;
        double bestDist = maxDistance * maxDistance;
        for (Tracked tracked : entities.values()) {
            if (!tracked.alive || !isCombatTarget(tracked, want)) {
                continue;
            }
            double d = from.distanceToSqr(tracked.position());
            if (d < bestDist) {
                bestDist = d;
                best = tracked;
            }
        }
        return best;
    }

    public List<Tracked> matching(String typeFilter) {
        String want = typeFilter == null ? "" : typeFilter.toLowerCase(Locale.ROOT);
        if (want.startsWith("minecraft:")) {
            want = want.substring("minecraft:".length());
        }
        List<Tracked> out = new ArrayList<>();
        for (Tracked tracked : entities.values()) {
            if (tracked.alive && isCombatTarget(tracked, want)) {
                out.add(tracked);
            }
        }
        return out;
    }

    static boolean isCombatTarget(Tracked tracked, String typeFilter) {
        if (!typeFilter.isEmpty()) {
            return tracked.typeId().equals(typeFilter);
        }
        return tracked.type.getCategory() == MobCategory.MONSTER;
    }
}
