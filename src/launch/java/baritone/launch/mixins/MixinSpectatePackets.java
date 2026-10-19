/*
 * Ostinato: new file (spectator learning, health estimator). Not part of upstream Baritone.
 */
package baritone.launch.mixins;

import baritone.spectate.PacketTap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ostinato: hands entity packets to {@link PacketTap} after vanilla applied them. Each handler first runs on the
 * network thread and throws itself over to the game thread, so a TAIL inject only ever runs on the game thread.
 * Nothing here changes what the packets do.
 */
@Mixin(ClientPacketListener.class)
public abstract class MixinSpectatePackets {
    @Shadow
    private ClientLevel level;

    @Inject(method = "handleMoveEntity", at = @At("TAIL"))
    private void ostinato$move(ClientboundMoveEntityPacket p, CallbackInfo ci) {
        if (!PacketTap.any() || !p.hasPosition() && !p.hasRotation()) return;
        Entity e = p.getEntity(level);
        if (e == null) return;
        PacketTap.begin(p);
        for (PacketTap.Listener l : PacketTap.listeners()) {
            l.move(e, p.hasPosition() ? e.getPositionCodec().getBase() : null, p.getyRot(), p.getxRot(), p.hasRotation(), p.isOnGround(), false);
        }
        PacketTap.end();
    }

    @Inject(method = "handleEntityPositionSync", at = @At("TAIL"))
    private void ostinato$sync(ClientboundEntityPositionSyncPacket p, CallbackInfo ci) {
        if (!PacketTap.any()) return;
        Entity e = level.getEntity(p.id());
        if (e == null) return;
        PacketTap.begin(p);
        for (PacketTap.Listener l : PacketTap.listeners()) {
            l.move(e, p.values().position(), p.values().yRot(), p.values().xRot(), true, p.onGround(), true); // Ostinato: 1.21.4/1.21.11 packet shape
        }
        PacketTap.end();
    }

    @Inject(method = "handleTeleportEntity", at = @At("TAIL"))
    private void ostinato$teleport(ClientboundTeleportEntityPacket p, CallbackInfo ci) {
        if (!PacketTap.any()) return;
        Entity e = level.getEntity(p.id());
        if (e != null) for (PacketTap.Listener l : PacketTap.listeners()) l.teleport(e);
    }

    @Inject(method = "handleSetEntityMotion", at = @At("TAIL"))
    private void ostinato$motion(ClientboundSetEntityMotionPacket p, CallbackInfo ci) {
        if (!PacketTap.any()) return;
        Entity e = level.getEntity(p.getId()); // Ostinato: 1.21.11 getters
        if (e != null) for (PacketTap.Listener l : PacketTap.listeners()) l.motion(e, new net.minecraft.world.phys.Vec3(p.getXa(), p.getYa(), p.getZa())); // Ostinato: 1.21.4 getters
    }

    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void ostinato$animate(ClientboundAnimatePacket p, CallbackInfo ci) {
        if (!PacketTap.any()) return;
        Entity e = level.getEntity(p.getId());
        if (e != null) for (PacketTap.Listener l : PacketTap.listeners()) l.animate(e, p.getAction());
    }

    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void ostinato$damage(ClientboundDamageEventPacket p, CallbackInfo ci) {
        if (!PacketTap.any()) return;
        Entity e = level.getEntity(p.entityId());
        if (e != null) for (PacketTap.Listener l : PacketTap.listeners()) l.damage(e, p);
    }

    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void ostinato$entityEvent(ClientboundEntityEventPacket p, CallbackInfo ci) {
        if (!PacketTap.any()) return;
        Entity e = p.getEntity(level);
        if (e != null) for (PacketTap.Listener l : PacketTap.listeners()) l.entityEvent(e, p.getEventId());
    }

    @Inject(method = "handleSetEntityData", at = @At("TAIL"))
    private void ostinato$data(ClientboundSetEntityDataPacket p, CallbackInfo ci) {
        if (!PacketTap.any()) return;
        Entity e = level.getEntity(p.id());
        if (e != null) for (PacketTap.Listener l : PacketTap.listeners()) l.entityData(e);
    }

    @Inject(method = "handleSetEquipment", at = @At("TAIL"))
    private void ostinato$equipment(ClientboundSetEquipmentPacket p, CallbackInfo ci) {
        if (!PacketTap.any()) return;
        Entity e = level.getEntity(p.getEntity());
        if (e != null) for (PacketTap.Listener l : PacketTap.listeners()) l.equipment(e);
    }

    @Inject(method = "handleAddEntity", at = @At("TAIL"))
    private void ostinato$add(ClientboundAddEntityPacket p, CallbackInfo ci) {
        if (!PacketTap.any()) return;
        Entity e = level.getEntity(p.getId());
        if (e != null) for (PacketTap.Listener l : PacketTap.listeners()) l.added(e);
    }

    @Inject(method = "handleRemoveEntities", at = @At("HEAD"))
    private void ostinato$remove(ClientboundRemoveEntitiesPacket p, CallbackInfo ci) {
        if (!PacketTap.any() || !Minecraft.getInstance().isSameThread()) return; // the network thread's pass is thrown over
        p.getEntityIds().forEach(id -> {
            Entity e = level.getEntity(id);
            if (e != null) for (PacketTap.Listener l : PacketTap.listeners()) l.removing(e);
        });
    }

    @Inject(method = "handleLevelEvent", at = @At("TAIL"))
    private void ostinato$levelEvent(ClientboundLevelEventPacket p, CallbackInfo ci) {
        if (!PacketTap.any()) return;
        for (PacketTap.Listener l : PacketTap.listeners()) l.levelEvent(p.getType(), p.getPos(), p.getData());
    }
}
