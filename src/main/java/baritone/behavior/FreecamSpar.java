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

import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * A client-side opponent for freecam warmup. It is another player model, and it fights with the same
 * melee shape the bot uses up close: sprint in, strafe, jump for a crit, and swing on the way down.
 * Hits stay on the spar's own health. The real bot is not swung and is not damaged.
 */
final class FreecamSpar {

    private static final float REACH = 3.15f;
    private static final float HIT = 5f;

    private RemotePlayer ghost;
    private float ghostHp = 20;
    private float youHp = 20;
    private int strafeDir = 1;
    private int strafeLeft = 15;
    private int sinceSwing = 100;
    private int respawn;
    private String banner = "";
    private int bannerTicks;

    boolean active() {
        return ghost != null || respawn > 0;
    }

    void start(Player owner, Entity camera) {
        stop();
        youHp = 20;
        banner = "";
        spawn(owner, camera);
    }

    void stop() {
        if (ghost != null) {
            ghost.discard();
            ghost = null;
        }
        respawn = 0;
    }

    /** Left click while sparring. True when the click belongs to the fight, so freecam does not also path. */
    boolean attack(Entity camera) {
        if (ghost == null || respawn > 0) {
            return active();
        }
        if (camera.getEyePosition().distanceTo(ghost.getEyePosition()) <= REACH) {
            ghostHp = Math.max(0, ghostHp - HIT);
            ghost.hurtTime = 10;
            ghost.hurtDuration = 10;
            if (ghostHp <= 0) {
                win("You dropped the spar.");
            }
        }
        return true;
    }

    void tick(Entity camera, Player owner) {
        if (respawn > 0) {
            if (--respawn == 0 && owner != null && camera != null) {
                spawn(owner, camera);
            }
            return;
        }
        if (ghost == null || camera == null) {
            return;
        }
        if (ghost.isRemoved() || ghost.level() != camera.level()) {
            ghost = null;
            return;
        }
        sinceSwing++;
        if (bannerTicks > 0) {
            bannerTicks--;
        }
        Vec3 to = camera.position().subtract(ghost.position());
        double dist = Math.sqrt(to.x * to.x + to.z * to.z);
        float yaw = (float) Math.toDegrees(Mth.atan2(-to.x, to.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(camera.getEyeY() - ghost.getEyeY(), Math.max(0.05, dist)));
        ghost.setYRot(yaw);
        ghost.setYHeadRot(yaw);
        ghost.setYBodyRot(yaw);
        ghost.setXRot(Mth.clamp(pitch, -90, 90));

        if (--strafeLeft <= 0) {
            strafeDir = -strafeDir;
            strafeLeft = 12 + (ghost.tickCount % 11);
        }
        double fwd = 0;
        double strafe = 0;
        boolean sprint = false;
        boolean jump = false;
        float cd = ghost.getAttackStrengthScale(0.5f);
        boolean falling = !ghost.onGround() && ghost.getDeltaMovement().y < -0.05;
        if (dist > 2.7) {
            fwd = 1;
            sprint = true;
            if (dist > 4.2 && ghost.onGround() && !ghost.isInWater()) {
                jump = true;
            }
        } else if (dist < 1.35) {
            fwd = -0.6;
        } else {
            strafe = strafeDir;
        }
        // Same exchange as the bot's melee: a charged jump, then the swing on the fall. A ground swing if the jump never comes.
        if (dist <= REACH && cd >= 0.92f) {
            if (falling) {
                swing();
            } else if (ghost.onGround() && !ghost.isInWater() && sinceSwing > 6) {
                jump = true;
            } else if (ghost.onGround() && sinceSwing > 14) {
                swing();
            }
        }
        ghost.setSprinting(sprint && fwd > 0);
        if (jump && ghost.onGround()) {
            ghost.jumpFromGround();
        }
        ghost.travel(new Vec3(strafe, 0, fwd));
        ghost.tickCount++;
    }

    String hudLine() {
        if (!active() && bannerTicks <= 0) {
            return null;
        }
        if (bannerTicks > 0 && ghost == null) {
            return banner;
        }
        String line = "Spar " + Math.round(ghostHp) + "   You " + Math.round(youHp);
        if (bannerTicks > 0) {
            line = banner + "   " + line;
        }
        return line;
    }

    private void swing() {
        if (ghost == null) {
            return;
        }
        ghost.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
        sinceSwing = 0;
        youHp = Math.max(0, youHp - (ghost.fallDistance > 0 && !ghost.onGround() ? HIT + 2 : HIT));
        if (youHp <= 0) {
            win("The spar dropped you.");
        }
    }

    private void win(String text) {
        banner = text;
        bannerTicks = 60;
        if (ghost != null) {
            ghost.discard();
            ghost = null;
        }
        respawn = 40;
        youHp = 20;
    }

    private void spawn(Player owner, Entity camera) {
        if (!(owner.level() instanceof ClientLevel level)) {
            return;
        }
        RemotePlayer born = new RemotePlayer(level, new GameProfile(UUID.randomUUID(), "Spar"));
        Vec3 look = camera.getLookAngle();
        double x = camera.getX() + look.x * 4;
        double z = camera.getZ() + look.z * 4;
        born.snapTo(x, camera.getY(), z, camera.getYRot() + 180, 0);
        born.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.DIAMOND_SWORD));
        born.setCustomName(Component.literal("Spar"));
        born.setCustomNameVisible(true);
        born.setHealth(20);
        try {
            level.addEntity(born);
        } catch (RuntimeException ex) {
            born.discard();
            return;
        }
        ghost = born;
        ghostHp = 20;
        sinceSwing = 100;
        strafeLeft = 15;
    }
}
