/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * The common-side IPlayerContext: a HeadlessPlayer over the bot's Level, with no Minecraft, no client
 * level, no entity tracker and no world cache. Baritone's movements and drivers are written against
 * IPlayerContext, so they run here unchanged; the few client-only call sites go through
 * localPlayer()/minecraft() and are never reached by this context.
 */
package baritone.headless;

import baritone.api.cache.IWorldData;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.IPlayerController;
import baritone.api.utils.RayTraceUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;

import java.util.List;

public final class HeadlessPlayerContext implements IPlayerContext {

    private final HeadlessPlayer player;
    private final HeadlessLevel world;
    private final HeadlessPlayerController controller;

    public HeadlessPlayerContext(HeadlessPlayer player, HeadlessLevel world, HeadlessPlayerController controller) {
        this.player = player;
        this.world = world;
        this.controller = controller;
    }

    @Override
    public Minecraft minecraft() {
        return null;
    }

    @Override
    public LocalPlayer localPlayer() {
        // Ostinato: there is no client player. Callers must null-check before touching connection/input.
        return null;
    }

    @Override
    public HeadlessPlayer entity() {
        return player;
    }

    @Override
    public IPlayerController playerController() {
        return controller;
    }

    @Override
    public Level world() {
        return world;
    }

    @Override
    public Iterable<Entity> entities() {
        // No entity tracking headlessly: nothing in the world but the bot itself, which cannot block itself.
        return List.of();
    }

    @Override
    public IWorldData worldData() {
        return null;
    }

    @Override
    public HitResult objectMouseOver() {
        // Ostinato: same as client BaritonePlayerContext — trace from the player's own
        // eye and rotation. Passing isShiftKeyDown() used a sneaked-eye start while
        // playerHead() still read the standing cache, so a stock sneak-bridge look
        // never satisfied isLookingAt / READY_TO_PLACE.
        return RayTraceUtils.rayTraceTowards(player, playerRotations(),
                controller.getBlockReachDistance());
    }

    @Override
    public BetterBlockPos viewerPos() {
        // The bot renders nothing; the "viewer" is the body.
        return playerFeet();
    }
}
