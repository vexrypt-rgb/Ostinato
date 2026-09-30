/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.api.BaritoneAPI;
import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import net.minecraft.client.Options;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Detached camera for picking a destination. Walks with gravity or, after a double-tapped jump, flies like creative flight; collides with blocks; and is clamped to the bot's render distance.
 * Left click: follow the entity under the crosshair, else travel to the block under it.
 * Right click: travel to the camera's own position.
 */
public final class FreecamBehavior extends Behavior implements Helper {

    /** Active camera of the primary bot, read by MixinMouseHandler to steer the camera instead of the player. */
    private static Camera active;

    private Camera camera;
    private boolean flying;
    private boolean jumpHeld;
    private int jumpTapTicks;

    public FreecamBehavior(Baritone baritone) {
        super(baritone);
    }

    /**
     * Optional status line for the ghost player's tag while freecam is on. A mod driving Baritone (TenorClef) can
     * set this to describe its current task; otherwise the tag shows Baritone's active process.
     */
    public static volatile java.util.function.Supplier<String> statusSupplier;

    /** Render state of the bot's ghost body this frame (set by the player renderer mixin; only it gets tinted). */
    public static Object ghostState;

    /** Text for the tag above the translucent player while freecam is on. */
    public static String botStatus() {
        java.util.function.Supplier<String> s = statusSupplier;
        if (s != null) {
            try {
                String t = s.get();
                if (t != null && !t.isEmpty()) {
                    return t;
                }
            } catch (Throwable ignored) {
            }
        }
        return BaritoneAPI.getProvider().getPrimaryBaritone().getPathingControlManager().mostRecentInControl()
                .map(p -> p.displayName()).orElse("Idle");
    }

    public static Entity activeCamera() {
        return active;
    }

    public boolean isActive() {
        return camera != null;
    }

    public void toggle() {
        if (camera == null) {
            enable();
        } else {
            disable();
        }
    }

    private LocalPlayer owner;

    public void enable() {
        LocalPlayer p = ctx.player();
        if (camera != null || p == null || baritone != BaritoneAPI.getProvider().getPrimaryBaritone()) {
            return;
        }
        camera = new Camera(p.level());
        owner = p;
        flying = p.getAbilities().flying;
        jumpHeld = true;
        jumpTapTicks = 0;
        camera.snapTo(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
        camera.syncPrev();
        active = camera;
        mc.setCameraEntity(camera);
        p.input = new ClientInput();
        logDirect("Freecam on. Left click: go to block / follow entity. Right click: go to camera. #freecam to exit.");
    }

    public void disable() {
        if (camera == null) {
            return;
        }
        camera = null;
        active = null;
        owner = null;
        LocalPlayer p = ctx.player();
        if (p != null) {
            mc.setCameraEntity(p);
            if (p.input.getClass() == ClientInput.class) {
                p.input = new KeyboardInput(mc.options);
            }
        }
        logDirect("Freecam off");
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        if (camera != null) {
            disable();
        }
    }

    @Override
    public void onTick(TickEvent event) {
        if (camera == null) {
            return;
        }
        LocalPlayer p = ctx.player();
        // death/respawn replaces the LocalPlayer in the same level: drop freecam rather than drive a dead player
        if (p != null && p != owner && p.isAlive() && camera.level() == p.level() && event.getType() != TickEvent.Type.OUT) {
            disable(); // respawned: rebuild freecam around the new player
            enable();
            return;
        }
        if (event.getType() == TickEvent.Type.OUT || p == null || camera.level() != p.level()) {
            disable();
            return;
        }
        if (!p.isAlive()) {
            if (mc.getCameraEntity() != p) {
                mc.setCameraEntity(p); // let the death screen and respawn work on the real player
            }
            return;
        }
        // Keys drive the camera, never the bot; the pathing input (PlayerMovementInput) is left alone.
        if (p.input.getClass() == KeyboardInput.class) {
            p.input = new ClientInput();
        }
        if (mc.getCameraEntity() != camera) {
            mc.setCameraEntity(camera);
        }
        move(mc.options);
        clamp(p);
        if (mc.gui.screen() == null) {
            handleClicks(mc.options);
        }
    }

    /**
     * Walks like a survival player (gravity, jump, sprint) with block collision; double-tap jump
     * toggles creative flight, as in creative mode. Approximates vanilla player physics.
     */
    private void move(Options gs) {
        camera.syncPrev();
        boolean jump = held(gs.keyJump);
        if (jump && !jumpHeld) {
            flying = jumpTapTicks > 0 ? !flying : flying;
            jumpTapTicks = 7;
        }
        jumpHeld = jump;
        if (jumpTapTicks > 0) {
            jumpTapTicks--;
        }
        double fwd = (held(gs.keyUp) ? 1 : 0) - (held(gs.keyDown) ? 1 : 0);
        double strafe = (held(gs.keyLeft) ? 1 : 0) - (held(gs.keyRight) ? 1 : 0);
        boolean sneak = held(gs.keyShift);
        double len = Math.sqrt(fwd * fwd + strafe * strafe);
        if (len > 1) {
            fwd /= len;
            strafe /= len;
        }
        boolean sprint = held(gs.keySprint) && fwd > 0;
        double speed = Baritone.settings().freecamSpeed.value;
        boolean onGround = camera.onGround();
        double accel;
        if (flying) {
            accel = 0.05 * (sprint ? 2 : 1);
        } else if (onGround) {
            accel = 0.1 * (sprint ? 1.3 : 1) * (sneak ? 0.3 : 1) * 0.21600002 / (0.6 * 0.6 * 0.6);
        } else {
            accel = sprint ? 0.026 : 0.02;
        }
        accel *= speed;
        float yaw = camera.getYRot() * ((float) Math.PI / 180F);
        double sin = Mth.sin(yaw);
        double cos = Mth.cos(yaw);
        Vec3 m = camera.getDeltaMovement();
        double mx = m.x + (strafe * cos - fwd * sin) * accel;
        double mz = m.z + (fwd * cos + strafe * sin) * accel;
        double my = m.y;
        if (flying) {
            my += ((jump ? 1 : 0) - (sneak ? 1 : 0)) * 0.15 * speed;
        } else if (jump && onGround) {
            my = 0.42;
            if (sprint) {
                mx -= sin * 0.2;
                mz += cos * 0.2;
            }
        }
        Vec3 want = new Vec3(mx, my, mz);
        camera.move(MoverType.SELF, want);
        Vec3 moved = new Vec3(camera.getX() - camera.xo, camera.getY() - camera.yo, camera.getZ() - camera.zo);
        if (Math.abs(moved.x - want.x) > 1e-4) {
            mx = 0;
        }
        if (Math.abs(moved.z - want.z) > 1e-4) {
            mz = 0;
        }
        if (Math.abs(moved.y - want.y) > 1e-4) {
            my = 0;
        }
        if (flying) {
            if (camera.onGround() && !jump) {
                flying = false; // landing ends flight, as in creative
            }
            camera.setDeltaMovement(mx * 0.91, my * 0.6, mz * 0.91);
        } else {
            double friction = camera.onGround() ? 0.6 * 0.91 : 0.91;
            camera.setDeltaMovement(mx * friction, (my - 0.08) * 0.98, mz * friction);
        }
    }

    /**
     * Physical key state. The key mappings' own isDown() can't be trusted here: bot controllers
     * (TenorClef, InputOverrideHandler) release movement mappings every tick while they drive the player.
     */
    private boolean held(net.minecraft.client.KeyMapping km) {
        if (mc.gui.screen() != null) { // typing in chat or a GUI must not fly the camera
            return false;
        }
        com.mojang.blaze3d.platform.InputConstants.Key key = com.mojang.blaze3d.platform.InputConstants.getKey(km.saveString());
        if (key.getType() != com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD) {
            return km.isDown();
        }
        return com.mojang.blaze3d.platform.InputConstants.isKeyDown(key.getValue());
    }

    /** Keeps the camera within the bot's render distance (horizontal circle) and the world's height. */
    private void clamp(LocalPlayer p) {
        double max = renderRadius();
        double dx = camera.getX() - p.getX();
        double dz = camera.getZ() - p.getZ();
        double d = Math.sqrt(dx * dx + dz * dz);
        double x = camera.getX();
        double z = camera.getZ();
        if (d > max) {
            x = p.getX() + dx * max / d;
            z = p.getZ() + dz * max / d;
        }
        double y = Mth.clamp(camera.getY(), p.level().getMinY(), p.level().getMaxY() + 1);
        if (x != camera.getX() || y != camera.getY() || z != camera.getZ()) {
            camera.setPos(x, y, z);
            camera.setDeltaMovement(Vec3.ZERO);
        }
    }

    private double renderRadius() {
        return mc.options.getEffectiveRenderDistance() * 16;
    }

    private void handleClicks(Options gs) {
        boolean left = false;
        boolean right = false;
        while (gs.keyAttack.consumeClick()) {
            left = true;
        }
        while (gs.keyUse.consumeClick()) {
            right = true;
        }
        // Never let a held click reach the bot's controller (it would dig/use at the camera's crosshair).
        gs.keyAttack.setDown(false);
        gs.keyUse.setDown(false);
        if (left) {
            select();
        } else if (right) {
            BetterBlockPos pos = new BetterBlockPos(camera.getX(), camera.getY(), camera.getZ());
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
            logDirect("Going to " + pos);
        }
    }

    private void select() {
        LocalPlayer p = ctx.player();
        Vec3 start = camera.getEyePosition(1);
        Vec3 end = start.add(camera.getViewVector(1).scale(renderRadius() * 2));
        HitResult block = p.level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));
        double blockDist = block.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : block.getLocation().distanceToSqr(start);

        Entity hit = null;
        double hitDist = blockDist;
        for (Entity e : ctx.entities()) {
            if (e == p || e == camera || !e.isAlive()) {
                continue;
            }
            AABB box = e.getBoundingBox().inflate(e.getPickRadius() + 0.3);
            Optional<Vec3> v = box.clip(start, end);
            if (v.isPresent() && v.get().distanceToSqr(start) < hitDist) {
                hitDist = v.get().distanceToSqr(start);
                hit = e;
            }
        }
        if (hit != null) {
            Entity target = hit;
            baritone.getFollowProcess().follow(target::equals);
            logDirect("Following " + target.getName().getString());
            return;
        }
        if (block.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = ((BlockHitResult) block).getBlockPos();
            if (horizontalDistSq(pos, p) > renderRadius() * renderRadius()) {
                logDirect("That block is outside render distance");
                return;
            }
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(pos));
            logDirect("Going to block " + new BetterBlockPos(pos));
            return;
        }
        logDirect("Nothing selected");
    }

    private static double horizontalDistSq(BlockPos pos, Entity e) {
        double dx = pos.getX() + 0.5 - e.getX();
        double dz = pos.getZ() + 0.5 - e.getZ();
        return dx * dx + dz * dz;
    }

    private static final class Camera extends Entity {

        Camera(Level world) {
            super(EntityTypes.PLAYER, world);
        }

        void syncPrev() {
            xo = xOld = getX();
            yo = yOld = getY();
            zo = zOld = getZ();
            yRotO = getYRot();
            xRotO = getXRot();
        }

        @Override
        protected void defineSynchedData(SynchedEntityData.Builder builder) {
        }

        @Override
        protected void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput nbt) {
        }

        @Override
        protected void addAdditionalSaveData(net.minecraft.world.level.storage.ValueOutput nbt) {
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            return false;
        }
    }
}
