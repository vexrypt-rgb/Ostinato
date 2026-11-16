package baritone.utils;

import baritone.Baritone;
import baritone.api.event.events.PacketEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Ostinato: outbound (and selected inbound) packet log so a real client pillar can be
 * compared tick-by-tick with the headless bot. Off unless {@code -Dostinato.packetLog=FILE}.
 */
public final class PacketLog {

    private static final String FILE = System.getProperty("ostinato.packetLog", "");
    public static final boolean ON = FILE != null && !FILE.isEmpty();

    private static final Object LOCK = new Object();
    private static BufferedWriter out;
    private static int tick = -1;

    private PacketLog() {
    }

    public static void install(Baritone baritone) {
        if (!ON) {
            return;
        }
        baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener() {
            @Override
            public void onSendPacket(PacketEvent event) {
                if (event.getState() == EventState.PRE) {
                    outbound(event.getPacket());
                }
            }

            @Override
            public void onReceivePacket(PacketEvent event) {
                if (event.getState() == EventState.PRE) {
                    inbound(event.getPacket());
                }
            }

            @Override
            public void onPostTick(TickEvent event) {
                if (event.getType() != TickEvent.Type.IN) {
                    return;
                }
                var p = Minecraft.getInstance().player;
                if (p != null) {
                    tick(p.tickCount);
                }
            }
        });
    }

    public static void tick(int t) {
        tick = t;
    }

    public static void outbound(Packet<?> packet) {
        write("OUT", packet);
    }

    public static void inbound(Packet<?> packet) {
        write("IN", packet);
    }

    /** Ostinato: a diagnostic line in the same tick stream as the packets (place Y/AABB vs dest). */
    public static void note(String line) {
        if (!ON || line == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                if (out == null) {
                    Path f = Paths.get(FILE);
                    if (f.getParent() != null) {
                        Files.createDirectories(f.getParent());
                    }
                    out = Files.newBufferedWriter(f, StandardCharsets.UTF_8);
                }
                out.write(String.format(Locale.ROOT, "t=%d NOTE %s%n", tick, line));
                out.flush();
            } catch (Exception e) {
                System.err.println("[PacketLog] " + e);
            }
        }
    }

    private static void write(String dir, Packet<?> packet) {
        if (!ON || packet == null) {
            return;
        }
        String line = describe(packet);
        if (line == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                if (out == null) {
                    Path f = Paths.get(FILE);
                    if (f.getParent() != null) {
                        Files.createDirectories(f.getParent());
                    }
                    out = Files.newBufferedWriter(f, StandardCharsets.UTF_8);
                }
                out.write(String.format(Locale.ROOT, "t=%d %s %s%n", tick, dir, line));
                out.flush();
            } catch (Exception e) {
                System.err.println("[PacketLog] " + e);
            }
        }
    }

    private static String describe(Packet<?> p) {
        if (p instanceof ServerboundMovePlayerPacket.PosRot m) {
            return String.format(Locale.ROOT, "PosRot x=%.6f y=%.6f z=%.6f yaw=%.3f pitch=%.3f onGround=%s hz=%s",
                    m.getX(0), m.getY(0), m.getZ(0), m.getYRot(0), m.getXRot(0), m.isOnGround(), m.horizontalCollision());
        }
        if (p instanceof ServerboundMovePlayerPacket.Pos m) {
            return String.format(Locale.ROOT, "Pos x=%.6f y=%.6f z=%.6f onGround=%s hz=%s",
                    m.getX(0), m.getY(0), m.getZ(0), m.isOnGround(), m.horizontalCollision());
        }
        if (p instanceof ServerboundMovePlayerPacket.Rot m) {
            return String.format(Locale.ROOT, "Rot yaw=%.3f pitch=%.3f onGround=%s hz=%s",
                    m.getYRot(0), m.getXRot(0), m.isOnGround(), m.horizontalCollision());
        }
        if (p instanceof ServerboundMovePlayerPacket.StatusOnly m) {
            return String.format(Locale.ROOT, "StatusOnly onGround=%s hz=%s", m.isOnGround(), m.horizontalCollision());
        }
        if (p instanceof ServerboundUseItemOnPacket u) {
            BlockHitResult hit = u.hitResult();
            BlockPos pos = hit.getBlockPos();
            Vec3 loc = hit.getLocation();
            return String.format(Locale.ROOT, "UseItemOn hand=%s seq=%d block=%d,%d,%d face=%s hit=%.4f,%.4f,%.4f inside=%s",
                    u.hand(), u.sequence(), pos.getX(), pos.getY(), pos.getZ(), hit.getDirection(),
                    loc.x, loc.y, loc.z, hit.isInside());
        }
        if (p instanceof ServerboundUseItemPacket u) {
            return "UseItem hand=" + u.hand() + " seq=" + u.sequence()
                    + String.format(Locale.ROOT, " yaw=%.3f pitch=%.3f", u.yRot(), u.xRot());
        }
        if (p instanceof ServerboundPunchPacket s) {
            return "Punch " + s;
        }
        if (p instanceof ServerboundPlayerActionPacket a) {
            BlockPos pos = a.getPos();
            Direction face = a.getDirection();
            return "Action " + a.getAction() + " pos=" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + " face=" + face + " seq=" + a.getSequence();
        }
        if (p instanceof ServerboundSetCarriedItemPacket c) {
            return "SetCarriedItem slot=" + c.getSlot();
        }
        if (p instanceof ServerboundPlayerInputPacket i) {
            return "PlayerInput " + i.input();
        }
        if (p instanceof ServerboundClientTickEndPacket) {
            return "ClientTickEnd";
        }
        if (p instanceof ServerboundAcceptTeleportationPacket a) {
            return "AcceptTeleport id=" + a.id()
                    + String.format(Locale.ROOT, " x=%.6f y=%.6f z=%.6f yaw=%.3f pitch=%.3f",
                    a.x(), a.y(), a.z(), a.yRot(), a.xRot());
        }
        if (p instanceof ClientboundPlayerPositionPacket pos) {
            return "PlayerPosition id=" + pos.id() + " " + pos.change();
        }
        if (p instanceof ClientboundEntityPositionSyncPacket sync) {
            return "EntityPositionSync id=" + sync.id() + " " + sync.position() + " onGround=" + sync.onGround();
        }
        if (p instanceof ClientboundSetEntityMotionPacket m) {
            Vec3 v = m.movement();
            return String.format(Locale.ROOT, "SetEntityMotion id=%d vx=%.6f vy=%.6f vz=%.6f", m.id(), v.x, v.y, v.z);
        }
        if (p instanceof ClientboundExplodePacket) {
            return "Explode";
        }
        if (p instanceof ClientboundBlockUpdatePacket b) {
            BlockPos pos = b.getPos();
            return "BlockUpdate " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " " + b.getBlockState();
        }
        String name = p.getClass().getSimpleName();
        if (name.contains("KeepAlive") || name.contains("ClientTickEnd") || name.contains("Ping")
                || name.contains("Pong") || name.contains("CustomPayload") || name.contains("Chunk")
                || name.contains("Light") || name.contains("LevelChunk") || name.contains("Section")
                || name.contains("Entity") || name.contains("Sound") || name.contains("Particle")
                || name.contains("SetTime") || name.contains("SetHealth") || name.contains("SetExperience")
                || name.contains("AwardStats") || name.contains("Recipe") || name.contains("Advancement")
                || name.contains("Boss") || name.contains("Border") || name.contains("GameEvent")
                || name.contains("Ticking") || name.contains("PlayerInfo") || name.contains("SystemChat")
                || name.contains("Disguised") || name.contains("TabList") || name.contains("Commands")
                || name.contains("Initialize") || name.contains("UpdateTags") || name.contains("UpdateAttributes")
                || name.contains("SetEquipment") || name.contains("AddEntity") || name.contains("RemoveEntities")
                || name.contains("SetEntityData") || name.contains("TeleportEntity") || name.contains("RotateHead")
                || name.contains("MoveEntity") || name.contains("Bundle") || name.contains("Container")
                || name.contains("Cooldown") || name.contains("SetPassengers") || name.contains("TakeItem")
                || name.contains("HurtAnimation") || name.contains("Damage") || name.contains("ForgetLevel")
                || name.contains("SetChunk") || name.contains("BlockChanged") || name.contains("SectionBlocks")
                || name.contains("SetHeld") || name.contains("SetCursor") || name.contains("Merchant")
                || name.contains("OpenScreen") || name.contains("Horse") || name.contains("MapItem")
                || name.contains("Explode") || name.contains("Respawn") || name.contains("ChangeDifficulty")
                || name.contains("Abilities") || name.contains("ServerData") || name.contains("Store")
                || name.contains("Cookie") || name.contains("Transfer") || name.contains("CustomReport")
                || name.contains("Chat") || name.contains("DisguisedChat") || name.contains("PlayerChat")
                || name.contains("SetDefaultSpawn") || name.contains("PostEffects") || name.contains("SimulationDistance")
                || name.contains("Debug")) {
            return null;
        }
        if (name.contains("ClientTickEnd") || p.getClass().getName().contains("ClientTickEnd")) {
            return "ClientTickEnd";
        }
        return name;
    }
}
