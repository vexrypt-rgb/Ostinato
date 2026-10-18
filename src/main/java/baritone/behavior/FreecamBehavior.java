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
import baritone.api.event.events.RenderEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.selection.ISelection;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import net.minecraft.client.Options;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Detached camera for picking a destination. On the ground it calls the same travel path a living entity does
 * (swim, lava, slime bounce, ladders, ice, honey). Double-tap jump flies, and while it is flying the camera
 * phases through blocks. The bot is never touched: its abilities and collision stay its own whatever the camera does.
 * Left click: follow the entity under the crosshair, else travel to the block under it. In a spar, left click hits.
 * Right click: travel to the camera's own position.
 */
public final class FreecamBehavior extends Behavior implements Helper {

    /** Active camera of the primary bot, read by MixinMouseHandler to steer the camera instead of the player. */
    private static Camera active;

    /** One line for the freecam HUD while a spar is on. */
    private static String sparLine;

    /** One line for the flat canvas or edit mode. */
    private static String canvasLine;

    private Camera camera;
    private final FreecamSpar spar = new FreecamSpar();
    private final FreecamCanvas canvas = new FreecamCanvas();
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

    /** Spar score line, or null when no spar is running. */
    public static String sparHud() {
        return sparLine;
    }

    /** Flat or edit status, or null when that canvas is off. */
    public static String canvasHud() {
        return canvasLine;
    }

    public boolean isActive() {
        return camera != null;
    }

    /** Flight phases the camera only. Ground movement, including parkour beside the bot, does not. */
    public boolean isFlying() {
        return camera != null && flying;
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
        logDirect("Freecam on. Left click: go to block / follow entity. Right click: go to camera. #freecam spar, #freecam flat, #freecam edit.");
    }

    public void toggleFlat() {
        if (canvas.mode() == FreecamCanvas.Mode.FLAT) {
            canvas.discard();
            canvasLine = null;
            logDirect("Flat canvas off. Nothing was added to the world.");
            return;
        }
        if (camera == null) {
            enable();
        }
        if (camera == null) {
            return;
        }
        logDirect(canvas.startFlat(camera, ctx.player()));
        canvasLine = canvas.hud();
    }

    public void toggleEdit() {
        if (canvas.mode() == FreecamCanvas.Mode.EDIT) {
            canvas.discard();
            canvasLine = null;
            logDirect("Edit off. The world was not changed.");
            return;
        }
        if (camera == null) {
            enable();
        }
        if (camera == null || ctx.world() == null) {
            return;
        }
        ISelection selection = null;
        ISelection[] selections = baritone.getSelectionManager().getSelections();
        if (selections.length > 0) {
            selection = selections[0];
        }
        logDirect(canvas.startEdit(camera, ctx.world(), selection));
        canvasLine = canvas.hud();
    }

    public void applyCanvas() {
        int n = canvas.apply((Baritone) baritone);
        logDirect(canvas.status());
        if (n == 0) {
            canvasLine = canvas.hud();
        }
    }

    public void toggleCanvasLive() {
        canvas.toggleLive();
        logDirect(canvas.status());
        canvasLine = canvas.hud();
    }

    public void finishCanvas() {
        int n = canvas.apply((Baritone) baritone);
        String built = canvas.status();
        canvas.discard();
        canvasLine = null;
        logDirect(n > 0 ? built + " Canvas closed." : "Canvas closed. " + built);
    }

    public void clearCanvas() {
        canvas.discard();
        canvasLine = null;
        logDirect("Canvas discarded. The world was not changed.");
    }

    public void undoCanvas() {
        canvas.undo();
        logDirect(canvas.status());
        canvasLine = canvas.hud();
    }

    public void canvasTool(String name) {
        canvas.tool(name);
        logDirect(canvas.status());
        canvasLine = canvas.hud();
    }

    public void canvasBrush(String name) {
        canvas.brush(name);
        logDirect(canvas.status());
        canvasLine = canvas.hud();
    }

    public void canvasRadius(int radius) {
        canvas.radius(radius);
        logDirect(canvas.status());
        canvasLine = canvas.hud();
    }

    /** Starts a mock fight, turning freecam on if it was off. */
    public void toggleSpar() {
        if (camera == null) {
            enable();
        }
        if (camera == null) {
            return;
        }
        if (spar.active()) {
            spar.stop();
            sparLine = null;
            logDirect("Spar off");
        } else {
            LocalPlayer p = ctx.player();
            if (p == null) {
                return;
            }
            spar.start(p, camera);
            logDirect("Spar on. Left click hits the ghost. It strafes, jumps, and crits the way the bot does. #freecam spar to stop.");
        }
    }

    public void disable() {
        if (camera == null) {
            return;
        }
        spar.stop();
        sparLine = null;
        canvasLine = null;
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
        canvas.discard();
        canvasLine = null;
        if (camera != null) {
            disable();
        }
    }

    @Override
    public void onRenderPass(RenderEvent event) {
        if (camera != null && baritone == BaritoneAPI.getProvider().getPrimaryBaritone()) {
            canvas.render(event, camera.getEyePosition());
        }
    }

    @Override
    public void onTick(TickEvent event) {
        pollToggleKey();
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
        markAttackers(p);
        move(mc.options);
        spar.tick(camera, p);
        sparLine = spar.hudLine();
        if (canvas.tickLive(p)) {
            int n = canvas.apply((Baritone) baritone);
            if (n > 0) {
                logDirect(canvas.status());
            }
        }
        canvasLine = canvas.hud();
        clamp(p);
        if (mc.gui.screen() == null) {
            handleClicks(mc.options);
        }
    }


    private boolean toggleKeyHeld;

    /** The freecam toggle key (setting freecamKey); only the primary bot reacts, and never while a screen is open. */
    private void pollToggleKey() {
        if (baritone != BaritoneAPI.getProvider().getPrimaryBaritone() || mc.gui.screen() != null || mc.getWindow() == null) {
            return;
        }
        boolean down = false;
        try {
            com.mojang.blaze3d.platform.InputConstants.Key key = com.mojang.blaze3d.platform.InputConstants.getKey(Baritone.settings().freecamKey.value);
            down = key.getType() == com.mojang.blaze3d.platform.InputConstants.Type.KEYBOARD && key.getValue() > 0
                    && com.mojang.blaze3d.platform.InputConstants.isKeyDown(key.getValue());
        } catch (RuntimeException ignored) {
            // bad key name in the setting: treat as unbound
        }
        if (down && !toggleKeyHeld && ctx.player() != null) {
            toggle();
        }
        toggleKeyHeld = down;
    }

    /** While freecam is on, whoever just hurt the bot becomes an enemy. */
    private void markAttackers(LocalPlayer p) {
        net.minecraft.world.entity.LivingEntity by = p.getLastHurtByMob();
        if (by != null && by != p && markable(by) && p.tickCount - p.getLastHurtByMobTimestamp() < 5
                && (by instanceof net.minecraft.world.entity.player.Player ? ((Baritone) baritone).getPvpProcess().addEnemy(by) : ((Baritone) baritone).getPveProcess().addEnemy(by))) {
            logDirect(by.getName().getString() + " attacked the bot: added to enemies");
        }
    }

    /**
     * Ground movement goes through {@link LivingEntity#travel}, which is the swim, lava, slime, ladder, and
     * friction path. Flight is creative-style and phases, so a landing never ends it; double-tap jump does.
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
        camera.step(fwd, strafe, jump, sneak, sprint, flying, Baritone.settings().freecamSpeed.value);
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
        boolean middle = false;
        while (gs.keyPickItem.consumeClick()) {
            middle = true;
        }
        while (gs.keyAttack.consumeClick()) {
            left = true;
        }
        while (gs.keyUse.consumeClick()) {
            right = true;
        }
        // Never let a held click reach the bot's controller (it would dig/use at the camera's crosshair).
        gs.keyAttack.setDown(false);
        gs.keyUse.setDown(false);
        gs.keyPickItem.setDown(false);
        if (canvas.active()) {
            String note = null;
            if (middle) {
                note = canvas.click(camera, ctx.world(), 2);
            } else if (left) {
                note = canvas.click(camera, ctx.world(), 0);
            } else if (right) {
                note = canvas.click(camera, ctx.world(), 1);
            }
            if (note != null && !note.isEmpty()) {
                logDirect(note);
            }
            canvasLine = canvas.hud();
            return;
        }
        if (middle) {
            markEnemy();
        } else if (left) {
            if (!spar.attack(camera)) {
                select();
            }
        } else if (right) {
            BetterBlockPos pos = new BetterBlockPos(camera.getX(), camera.getY(), camera.getZ());
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(pos));
            logDirect("Going to " + pos);
        }
    }

    /** Players always; other living things only with the enemyMobs setting. */
    private static boolean markable(net.minecraft.world.entity.LivingEntity e) {
        return e instanceof net.minecraft.world.entity.player.Player || Baritone.settings().enemyMobs.value;
    }

    private void markEnemy() {
        Entity hit = entityUnderCrosshair();
        if (hit instanceof net.minecraft.world.entity.LivingEntity pl && markable(pl)) {
            if ((pl instanceof net.minecraft.world.entity.player.Player ? ((Baritone) baritone).getPvpProcess().addEnemy(pl) : ((Baritone) baritone).getPveProcess().addEnemy(pl))) {
                logDirect("Enemy added: " + pl.getName().getString());
            } else {
                logDirect(pl.getName().getString() + " is already an enemy");
            }
        } else {
            logDirect("Middle-click a player to mark an enemy" + (Baritone.settings().enemyMobs.value ? " or mob" : ""));
        }
    }

    private Entity entityUnderCrosshair() {
        LocalPlayer p = ctx.player();
        Vec3 start = camera.getEyePosition(1);
        Vec3 end = start.add(camera.getViewVector(1).scale(renderRadius() * 2));
        HitResult block = p.level().clip(new ClipContext(start, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera));
        double hitDist = block.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : block.getLocation().distanceToSqr(start);
        Entity hit = null;
        for (Entity e : ctx.entities()) {
            if (e == p || e == camera || !e.isAlive()) {
                continue;
            }
            Optional<Vec3> v = e.getBoundingBox().inflate(e.getPickRadius() + 0.3).clip(start, end);
            if (v.isPresent() && v.get().distanceToSqr(start) < hitDist) {
                hitDist = v.get().distanceToSqr(start);
                hit = e;
            }
        }
        return hit;
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

    /**
     * A living body that is not added to the world. {@link #step} calls {@link #travel} so swim, slime bounce,
     * ladders, and block friction are the game's, not a copy of them. Flight sets {@link #noPhysics}.
     */
    private static final class Camera extends LivingEntity {

        private int jumpCooldown;

        Camera(Level world) {
            super(EntityTypes.ZOMBIE, world);
            setAttribute(Attributes.MOVEMENT_SPEED, 0.1);
            setAttribute(Attributes.JUMP_STRENGTH, 0.42);
            setAttribute(Attributes.GRAVITY, 0.08);
            setAttribute(Attributes.SAFE_FALL_DISTANCE, 3);
            setAttribute(Attributes.STEP_HEIGHT, 0.6);
            setAttribute(Attributes.WATER_MOVEMENT_EFFICIENCY, 0);
            refreshDimensions();
        }

        private void setAttribute(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
            var inst = getAttribute(attribute);
            if (inst != null) {
                inst.setBaseValue(value);
            }
        }

        @Override
        public HumanoidArm getMainArm() {
            return HumanoidArm.RIGHT;
        }

        /**
         * A normal living entity on the client is not allowed to simulate movement, so landing never
         * restitutes and a slime block cannot bounce. This body exists only on the client.
         */
        @Override
        public net.minecraft.world.entity.MoveSimulationType getMoveSimulationType() {
            return net.minecraft.world.entity.MoveSimulationType.SERVER_AND_CLIENT;
        }

        @Override
        public EntityDimensions getDefaultDimensions(Pose pose) {
            if (pose == Pose.SWIMMING || pose == Pose.FALL_FLYING) {
                return EntityDimensions.fixed(0.6f, 0.6f).withEyeHeight(0.4f);
            }
            if (pose == Pose.CROUCHING) {
                return EntityDimensions.fixed(0.6f, 1.5f).withEyeHeight(1.27f);
            }
            return EntityDimensions.fixed(0.6f, 1.8f).withEyeHeight(1.62f);
        }

        @Override
        public boolean causeFallDamage(double fallDistance, float damageMultiplier, DamageSource source) {
            return false;
        }

        @Override
        public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
            return false;
        }

        @Override
        public boolean isSuppressingBounce() {
            return isShiftKeyDown() || noPhysics;
        }

        void syncPrev() {
            xo = xOld = getX();
            yo = yOld = getY();
            zo = zOld = getZ();
            yRotO = getYRot();
            xRotO = getXRot();
        }

        /**
         * One tick of movement. {@code fwd} is positive for W and {@code strafe} is positive for A, matching
         * {@link LivingEntity#travel}. Sneak scales the input by 0.3, as the player does before travel.
         */
        void step(double fwd, double strafe, boolean jump, boolean sneak, boolean sprint, boolean fly, double speedScale) {
            noPhysics = fly;
            setShiftKeyDown(sneak && !fly);
            setSprinting(sprint && !fly && fwd > 0);
            if (fly) {
                setSwimming(false);
                pose(Pose.STANDING);
                fly(fwd, strafe, jump, sneak, sprint, speedScale);
                return;
            }
            updateFluidInteraction();
            if (sneak) {
                fwd *= 0.3;
                strafe *= 0.3;
            }
            fwd *= speedScale;
            strafe *= speedScale;
            setAttribute(Attributes.MOVEMENT_SPEED, sprint ? 0.13000001 : 0.1);
            stepJump(jump);
            boolean swim = sprint && !sneak && eyeInWater();
            setSwimming(swim);
            pose(swim ? Pose.SWIMMING : sneak ? Pose.CROUCHING : Pose.STANDING);
            if (swim) {
                swimBoost(jump);
            }
            stuckInBlocks();
            travel(new Vec3(strafe, 0, fwd));
        }

        private void pose(Pose want) {
            if (getPose() != want) {
                setPose(want);
                refreshDimensions();
            }
        }

        private void fly(double fwd, double strafe, boolean jump, boolean sneak, boolean sprint, double speedScale) {
            double accel = 0.05 * (sprint ? 2 : 1) * speedScale;
            float yaw = getYRot() * ((float) Math.PI / 180F);
            double sin = Mth.sin(yaw);
            double cos = Mth.cos(yaw);
            Vec3 m = getDeltaMovement();
            double mx = m.x + (strafe * cos - fwd * sin) * accel;
            double mz = m.z + (fwd * cos + strafe * sin) * accel;
            double my = m.y + ((jump ? 1 : 0) - (sneak ? 1 : 0)) * 0.15 * speedScale;
            setDeltaMovement(mx, my, mz);
            move(MoverType.SELF, getDeltaMovement());
            m = getDeltaMovement();
            setDeltaMovement(m.x * 0.91, m.y * 0.6, m.z * 0.91);
        }

        /** The jump block of LivingEntity#aiStep: liquid hops every tick, a ground jump, then a 10 tick gap. */
        private void stepJump(boolean jump) {
            if (jumpCooldown > 0) {
                jumpCooldown--;
            }
            if (!jump) {
                jumpCooldown = 0;
                return;
            }
            double water = getFluidHeight(FluidTags.WATER);
            boolean deepWater = isInWater() && water > 0 && (!onGround() || water > getFluidJumpThreshold());
            if (deepWater) {
                jumpInLiquid(FluidTags.WATER);
                return;
            }
            if (isInLava() && !(onGround() && getFluidHeight(FluidTags.LAVA) > 0 && getFluidHeight(FluidTags.LAVA) <= getFluidJumpThreshold())) {
                jumpInLiquid(FluidTags.LAVA);
                return;
            }
            if (onGround() && jumpCooldown == 0 && !isInWater() && !isInLava()) {
                jumpFromGround();
                jumpCooldown = 10;
            }
        }

        /** Player#travel's swim lift: look up to rise, look down to dive, unless that would leave the surface. */
        private void swimBoost(boolean jumping) {
            double lookY = getLookAngle().y;
            double boost = lookY < -0.2 ? 0.085 : 0.06;
            if (lookY > 0 && !jumping && level().getFluidState(BlockPos.containing(getX(), getY() + 0.9, getZ())).isEmpty()) {
                return;
            }
            Vec3 m = getDeltaMovement();
            setDeltaMovement(m.add(0, (lookY - m.y) * boost, 0));
        }

        private boolean eyeInWater() {
            return level().getFluidState(BlockPos.containing(getX(), getEyeY(), getZ())).is(FluidTags.WATER);
        }

        /** Cobwebs and sweet berry bushes slow the body, as Block#entityInside does for a ticked entity. */
        private void stuckInBlocks() {
            AABB box = getBoundingBox().deflate(1.0E-7);
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
                net.minecraft.world.level.block.state.BlockState st = level().getBlockState(pos);
                if (st.is(net.minecraft.world.level.block.Blocks.COBWEB)) {
                    makeStuckInBlock(st, new Vec3(0.25, 0.05, 0.25));
                } else if (st.is(net.minecraft.world.level.block.Blocks.SWEET_BERRY_BUSH)) {
                    makeStuckInBlock(st, new Vec3(0.8, 0.75, 0.8));
                }
            }
        }
    }
}
