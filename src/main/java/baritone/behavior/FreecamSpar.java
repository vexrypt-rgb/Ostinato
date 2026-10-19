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

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.health.DamageMath;
import baritone.pathing.kinematic.ClientWorld;
import baritone.pathing.kinematic.PlayerSim;
import baritone.process.PvpRecorder;
import baritone.process.SparBrain;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Ostinato: a client-side opponent for freecam warmup, now driven by the bot's own melee decisions
 * ({@link SparBrain}: CombatMelee, CombatMovement and CombatShield with the tuned policy) and moved on a
 * {@link PlayerSim}. Both sides wear one kit from the kit table, and every hit goes through vanilla 26.3's rules
 * ({@link SparRules}): charge, crits, the mace, armour, Protection, shields and axes, the hurt cooldown, absorption,
 * apples, totems, healing potions and knockback. All of it is client-side: the ghost is a RemotePlayer added to the
 * client level only, its keys never reach the real player, and nothing is sent to the server (FreecamBehavior counts
 * any attack, use or hotbar packet sent while a spar runs as a leak). The camera's health is the spar's own.
 */
final class FreecamSpar {

    private static final double REACH = 3.0;

    private RemotePlayer ghost;
    private SparBrain brain;
    private PlayerSim sim;
    private SparKit kit = SparKit.of("sword");
    private SparRules.Fighter g, you;
    private int ghostSince = 100, youSince = 100, weapon, respawn, bannerTicks, tick, healGap, crystalGap;
    private int scoreYou, scoreGhost, round;
    private String banner = "", dec = "-";
    private float fallDist;
    private PvpRecorder recorder;
    private final List<int[]> roundHits = new ArrayList<>(); // {tick, 1 ghost hit / 0 you hit, crit}
    private Baritone baritone;
    /** Ostinato: packets that would have touched the server while sparring (attack, use, hotbar, container); should stay 0. */
    static int leaks;
    static int landedYou, landedGhost; // Ostinato: hits that registered, for the headless check

    boolean active() {
        return ghost != null || respawn > 0;
    }

    String kitName() {
        return kit.name;
    }

    /** False, and nothing started, for an unknown kit. */
    boolean start(Baritone b, Player owner, Entity camera, String kitName) {
        SparKit k = SparKit.of(kitName);
        if (k == null) return false;
        stop();
        kit = k;
        baritone = b;
        g = new SparRules.Fighter("Spar", kit);
        you = new SparRules.Fighter("You", kit);
        scoreYou = scoreGhost = round = 0;
        banner = "";
        leaks = 0;
        landedYou = landedGhost = 0;
        spawn(owner, camera);
        return true;
    }

    void stop() {
        endRound("stopped", -1);
        if (ghost != null) {
            ghost.discard();
            ghost = null;
        }
        respawn = 0;
    }

    private SparKit.Weapon weapon() {
        return kit.weapons.get(Math.min(weapon, kit.weapons.size() - 1));
    }

    /** Left click while sparring. True when the click belongs to the fight, so freecam does not also path. */
    boolean attack(Entity camera) {
        if (ghost == null || respawn > 0) return active();
        SparKit.Weapon w = kit.weapons.get(0); // the camera swings the kit's main weapon
        float scale = DamageMath.strengthScale(youSince, w.speed);
        youSince = 0;
        Vec3 eye = camera.getEyePosition(), look = camera.getLookAngle();
        AABB box = ghost.getBoundingBox();
        var clip = box.clip(eye, eye.add(look.scale(REACH)));
        if (clip.isEmpty() && !box.contains(eye)) return true; // a swing at air still costs the charge
        boolean falling = !camera.onGround() && camera.getDeltaMovement().y < 0 && camera.fallDistance > 0;
        boolean sprinting = camera.isSprinting();
        boolean facing = facing(ghost.getYRot(), ghost.position(), camera.position());
        SparRules.Hit h = SparRules.melee(w, scale, falling && !sprinting && !camera.isInWater(), sprinting, camera.onGround(), camera.fallDistance, g, facing);
        if (!h.blocked && !h.ignored) {
            ghost.hurtTime = ghost.hurtDuration = 10;
            push(sim, camera.position(), ghost.position(), h, kit.knockbackResist);
            roundHits.add(new int[]{tick, 0, h.crit ? 1 : 0});
            landedYou++;
        }
        note("you " + describe(h, scale));
        return true;
    }

    private static boolean facing(float yaw, Vec3 from, Vec3 at) {
        float to = (float) Math.toDegrees(Mth.atan2(-(at.x - from.x), at.z - from.z));
        return Math.abs(Mth.wrapDegrees(to - yaw)) < 90;
    }

    private static void push(PlayerSim s, Vec3 from, Vec3 to, SparRules.Hit h, float resist) {
        double dx = to.x - from.x, dz = to.z - from.z;
        double[] v = SparRules.knockback(s.vx, s.vy, s.vz, s.onGround, h.knockback, resist, dx, dz);
        if (h.sprintKnock) v = SparRules.knockback(v[0], v[1], v[2], s.onGround, 0.5, resist, dx, dz);
        s.vx = v[0];
        s.vy = v[1];
        s.vz = v[2];
        s.sprinting = false;
    }

    void tick(Entity camera, Player owner) {
        if (respawn > 0) {
            if (--respawn == 0 && owner != null && camera != null) spawn(owner, camera);
            if (bannerTicks > 0) bannerTicks--;
            return;
        }
        if (ghost == null || camera == null) return;
        if (ghost.isRemoved() || ghost.level() != camera.level()) {
            if (Boolean.getBoolean("ostinato.spar.log")) System.out.println("[SPAR] ghost gone: " + ghost.getRemovalReason()); // Ostinato
            ghost = null;
            return;
        }
        tick++;
        ghostSince++;
        youSince++;
        if (bannerTicks > 0) bannerTicks--;
        if (healGap > 0) healGap--;
        if (crystalGap > 0) crystalGap--;
        g.tick();
        you.tick();
        // Ostinato: the freecam body is never ticked by the level, so its hurt flash would never run out (and the brain
        // would wait forever for the hurt cooldown it reads from it)
        if (camera instanceof LivingEntity le && le.hurtTime > 0) le.hurtTime--;
        sync(camera);

        // the bot's own melee decides for the ghost
        brain.eatTicks = g.eating;
        SparBrain.Keys k = brain.tick(ghost, (LivingEntity) camera);
        dec = k.dec;
        lastKeys = (k.forward ? "W" : "") + (k.back ? "S" : "") + (k.left ? "A" : "") + (k.right ? "D" : "") + (k.jump ? "J" : "") + (k.sprint ? "R" : "") + (k.attack ? "X" : "") + (k.use ? "U" : "");
        if (k.select >= 0 && k.select < kit.weapons.size() && k.select != weapon) {
            weapon = k.select;
            equip();
            ghostSince = 0; // a new item in the hand starts its charge over
        }
        double dist = Math.sqrt(ghost.distanceToSqr(camera));
        // apples, potions: the policy's eat line (heal.eatNear), and a healing splash at pots.healAt
        float life = g.hp + g.absorption;
        if (g.eating == 0 && healGap == 0) {
            if (kit.heals > 0 && g.heals > 0 && g.hp <= 9) {
                g.splashHeal();
                healGap = 10;
                dec = "pot";
            } else if (life <= 9 && (g.egapples > 0 || g.gapples > 0)) {
                g.startBite(g.egapples > 0);
                dec = "eat";
            }
        }
        boolean eating = g.eating > 0;
        // shield: up while the brain holds use, blocking after its warm-up; an axe hit takes it down for 5 s
        if (k.use && kit.shield && g.shieldDisabled == 0 && !eating) {
            if (g.shieldUp++ == 0) ghost.startUsingItem(InteractionHand.OFF_HAND);
        } else if (g.shieldUp > 0 || ghost.isUsingItem() && !eating) {
            g.shieldUp = 0;
            ghost.stopUsingItem();
        }
        look(k, camera);
        // move on the sim: the ghost's keys, never the player's
        int fwd = k.forward ? 1 : k.back ? -1 : 0, strafe = k.left ? 1 : k.right ? -1 : 0;
        boolean sprint = k.sprint && fwd > 0 && !eating && g.shieldUp == 0;
        sim.tick(ghost.getYRot(), eating || g.shieldUp > 0 ? Math.min(fwd, 0) : fwd, strafe, sprint, k.jump);
        if (!sim.onGround && sim.vy < 0) fallDist += (float) -sim.vy;
        else if (sim.onGround) fallDist = 0;
        ghost.setPos(sim.x, sim.y, sim.z);
        ghost.setDeltaMovement(sim.vx, sim.vy, sim.vz);
        ghost.setOnGround(sim.onGround);
        ghost.setSprinting(sim.sprinting);
        ghost.fallDistance = fallDist;
        // the swing: it connects in reach, on aim, and lands by the same rules as yours
        if (k.attack && !eating) {
            ghost.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            SparKit.Weapon w = weapon();
            float scale = DamageMath.strengthScale(ghostSince, w.speed);
            ghostSince = 0;
            Vec3 eye = ghost.getEyePosition();
            AABB box = camera.getBoundingBox();
            if (box.clip(eye, eye.add(Vec3.directionFromRotation(gPitch, gYaw).scale(REACH))).isPresent()) {
                boolean critOk = !sim.onGround && sim.vy < 0 && fallDist > 0 && !sim.sprinting;
                SparRules.Hit h = SparRules.melee(w, scale, critOk, sim.sprinting, sim.onGround, fallDist, you, false);
                if (!h.ignored) {
                    ((LivingEntity) camera).hurtTime = 10;
                    knockCamera(camera, h);
                    roundHits.add(new int[]{tick, 1, h.crit ? 1 : 0});
                    landedGhost++;
                }
                note("spar " + describe(h, scale));
                if (h.crit) dec = "crit";
            } else if (Boolean.getBoolean("ostinato.spar.log")) {
                Vec3 to = camera.getBoundingBox().getCenter().subtract(eye);
                double err = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, to.normalize().dot(Vec3.directionFromRotation(gPitch, gYaw))))));
                System.out.println(String.format(Locale.ROOT, "[SPAR] ghost miss: aim off %.1f deg, eye to box %.2f, box %s", err,
                        Math.sqrt(box.distanceToSqr(eye)), box)); // Ostinato
            }
        } else if (kit.crystals > 0 && g.crystals > 0 && crystalGap == 0 && !eating && dist > 2.0 && dist < 5.0) {
            // crystal kit: a crystal popped at the foe's feet every second it stands in range (simplified: no blocks are placed)
            g.crystals--;
            crystalGap = 20;
            SparRules.Hit h = SparRules.blast(you, 1.0);
            note("spar crystal " + describe(h, 1));
            dec = "crystal";
        }
        sync(camera);
        if (recorder != null) recorder.tick(ghost, (LivingEntity) camera, dist, "kit=" + kit.name + " r" + round, dec, brain.hits);
        if (g.dead()) roundOver(owner, camera, "You took the round.", 0);
        else if (you.dead()) roundOver(owner, camera, "The spar took the round.", 1);
    }

    private void look(SparBrain.Keys k, Entity camera) {
        Vec3 at = k.look != null ? k.look : camera.getEyePosition();
        Vec3 d = at.subtract(ghost.getEyePosition());
        float yaw = (float) Math.toDegrees(Mth.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.max(0.05, Math.hypot(d.x, d.z))));
        float[] vel = {yawVel};
        float ny = LookBehavior.modelYawStep(gYaw, yawVel, yaw, vel); // the bot's own hand model
        yawVel = vel[0];
        float np = gPitch + Mth.clamp(pitch - gPitch, -20, 20);
        ghost.setYRot(ny);
        ghost.setYHeadRot(ny);
        ghost.setYBodyRot(ny);
        ghost.setXRot(Mth.clamp(np, -90, 90));
        gYaw = ny;
        gPitch = Mth.clamp(np, -90, 90);
    }

    // Ostinato: the ghost's own aim; a RemotePlayer's tick may pull its rotation back toward the last network value
    private float gYaw, gPitch;

    private float yawVel;

    private void knockCamera(Entity camera, SparRules.Hit h) {
        Vec3 v = camera.getDeltaMovement();
        double dx = camera.getX() - ghost.getX(), dz = camera.getZ() - ghost.getZ();
        double[] r = SparRules.knockback(v.x, v.y, v.z, camera.onGround(), h.knockback, kit.knockbackResist, dx, dz);
        if (h.sprintKnock) r = SparRules.knockback(r[0], r[1], r[2], camera.onGround(), 0.5, kit.knockbackResist, dx, dz);
        camera.setDeltaMovement(r[0], r[1], r[2]);
    }

    /** The entities show the spar's own numbers (the recorder and the brain read them). */
    private void sync(Entity camera) {
        ghost.setHealth(Math.max(0.01f, g.hp));
        ghost.setAbsorptionAmount(g.absorption);
        if (camera instanceof LivingEntity le) {
            le.setHealth(Math.max(0.01f, you.hp));
            le.setAbsorptionAmount(you.absorption);
        }
        try { // the brain reads the ghost's charge; RemotePlayers do not count it themselves
            java.lang.reflect.Field f = LivingEntity.class.getDeclaredField("attackStrengthTicker");
            f.setAccessible(true);
            f.setInt(ghost, ghostSince);
        } catch (Throwable ignored) {
        }
    }

    private static String describe(SparRules.Hit h, float scale) {
        if (h.blocked) return "blocked" + (h.shieldDisabled ? " (shield disabled)" : "");
        if (h.ignored) return "absorbed by the hurt cooldown";
        return String.format(Locale.ROOT, "hit %.2f -> %.2f (charge %.2f%s%s%s%s)", h.raw, h.dealt, scale, h.crit ? ", crit" : "", h.sweep ? ", sweep" : "",
                h.totem ? ", TOTEM" : "", h.killed ? ", dead" : "");
    }

    private void note(String s) {
        if (recorder != null) recorder.note(s);
        if (Boolean.getBoolean("ostinato.spar.log")) System.out.println("[SPAR] " + s);
    }

    private String lastKeys = "";

    /** Ostinato: one line for the headless check. */
    String debug(Entity camera) {
        if (ghost == null) return "no ghost";
        return String.format(Locale.ROOT, "ghost %.2f %.2f %.2f cam %.2f %.2f %.2f dist %.2f dec=%s keys=%s cd=%.2f tc=%d camHurt=%d", ghost.getX(), ghost.getY(), ghost.getZ(),
                camera.getX(), camera.getY(), camera.getZ(), Math.sqrt(ghost.distanceToSqr(camera)), dec, lastKeys, ghost.getAttackStrengthScale(0.5f), ghost.tickCount,
                camera instanceof LivingEntity le ? le.hurtTime : -1);
    }

    Entity ghost() {
        return ghost;
    }

    String hudLine() {
        if (!active() && bannerTicks <= 0) return null;
        if (bannerTicks > 0 && ghost == null) return banner + "   " + score();
        if (g == null) return null;
        String line = String.format(Locale.ROOT, "Spar [%s] %s   You %s   %s", kit.name, hp(g), hp(you), score());
        if (bannerTicks > 0) line = banner + "   " + line;
        return line;
    }

    private String score() {
        return "Score " + scoreYou + "-" + scoreGhost;
    }

    private static String hp(SparRules.Fighter f) {
        String s = String.format(Locale.ROOT, "%.1f", Math.max(0, f.hp));
        if (f.absorption > 0) s += String.format(Locale.ROOT, "+%.1f", f.absorption);
        if (f.totems > 0) s += " T" + f.totems;
        if (f.gapples + f.egapples > 0) s += " G" + (f.gapples + f.egapples);
        if (f.heals > 0) s += " P" + f.heals;
        if (f.eating > 0) s += " eating";
        if (f.blocking()) s += " shield";
        return s;
    }

    private void roundOver(Player owner, Entity camera, String text, int ghostWon) {
        if (ghostWon == 1) scoreGhost++;
        else scoreYou++;
        endRound(ghostWon == 1 ? "spar-win" : "spar-loss", ghostWon);
        banner = text;
        bannerTicks = 60;
        if (ghost != null) {
            ghost.discard();
            ghost = null;
        }
        respawn = 40;
    }

    /** Closes the round's log, tagged spar, and seeds priors only with sparSeedPriors. */
    private void endRound(String result, int ghostWon) {
        if (recorder != null) {
            recorder.meta("#type spar kit=" + kit.name + " round=" + round + " result=" + result + " score=" + scoreYou + "-" + scoreGhost);
            if (ghostWon == 1) recorder.markWin();
            if (ghost != null) recorder.end(ghost, result);
            recorder = null;
        }
        if (ghostWon >= 0 && baritone != null && BaritoneAPI.getSettings().sparSeedPriors.value && roundHits.size() >= 3) {
            baritone.getSpectateLearner().sparRound(kit.name, new ArrayList<>(roundHits), ghostWon == 1);
        }
        roundHits.clear();
    }

    private void spawn(Player owner, Entity camera) {
        if (!(owner.level() instanceof ClientLevel level)) return;
        RemotePlayer born = new RemotePlayer(level, new GameProfile(UUID.randomUUID(), "Spar")) {
            @Override
            public boolean isPushable() {
                return false; // Ostinato: the real player must never be shoved by the ghost
            }

            @Override
            protected void pushEntities() {
                // Ostinato: nor does the ghost shove anyone it walks through
            }

            @Override
            public void push(Entity e) {
            }
        };
        Vec3 look = camera.getLookAngle();
        double x = camera.getX() + look.x * 4, z = camera.getZ() + look.z * 4;
        born.snapTo(x, camera.getY(), z, camera.getYRot() + 180, 0);
        born.setCustomName(Component.literal("Spar [" + kit.name + "]"));
        born.setCustomNameVisible(true);
        born.setId(-2_000_000 - round); // Ostinato: 26.3 leaves client-made entities without an id; a negative one never meets the server's
        try {
            level.addEntity(born);
        } catch (RuntimeException ex) {
            System.out.println("Ostinato spar: could not add the ghost: " + ex); // Ostinato
            born.discard();
            return;
        }
        ghost = born;
        gYaw = born.getYRot();
        gPitch = 0;
        if (Boolean.getBoolean("ostinato.spar.log")) System.out.println("[SPAR] ghost " + born.getId() + " at " + born.position()); // Ostinato
        weapon = 0;
        equip();
        brain = new SparBrain(baritone);
        sim = new PlayerSim(new ClientWorld(baritone.getPlayerContext()));
        sim.x = born.getX();
        sim.y = born.getY();
        sim.z = born.getZ();
        g.reset();
        you.reset();
        ghostSince = youSince = 100;
        fallDist = 0;
        round++;
        tick = 0;
        recorder = new PvpRecorder(Paths.get("pvplogs", "spar")); // Ostinato: spar fights have their own folder, never the 1v1 tuner's
        if (camera instanceof LivingEntity le) {
            recorder.begin(ghost, le, "spar");
            recorder.meta("#type spar kit=" + kit.name + " round=" + round);
        }
    }

    /** The kit on the ghost's model and hotbar: armour, the weapons in slots 0.., shield or totem in the offhand. */
    private void equip() {
        String m = kit.armorMaterial.equals("mixed") ? "diamond" : kit.armorMaterial;
        ghost.setItemSlot(EquipmentSlot.HEAD, item(m + "_helmet"));
        ghost.setItemSlot(EquipmentSlot.CHEST, item(m + "_chestplate"));
        ghost.setItemSlot(EquipmentSlot.LEGS, item(kit.armorMaterial.equals("mixed") ? "iron_leggings" : m + "_leggings"));
        ghost.setItemSlot(EquipmentSlot.FEET, item(m + "_boots"));
        for (int i = 0; i < kit.weapons.size() && i < 9; i++) ghost.getInventory().setItem(i, item(kit.weapons.get(i).item));
        ghost.getInventory().setSelectedSlot(Math.min(weapon, kit.weapons.size() - 1));
        ghost.setItemSlot(EquipmentSlot.MAINHAND, item(weapon().item));
        ghost.setItemSlot(EquipmentSlot.OFFHAND, kit.shield ? new ItemStack(Items.SHIELD) : kit.totems > 0 ? new ItemStack(Items.TOTEM_OF_UNDYING) : ItemStack.EMPTY);
        // its charge time is the weapon's: whatever the client did with the item's modifiers, the attribute ends at the weapon's speed
        var speed = ghost.getAttribute(Attributes.ATTACK_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() + weapon().speed - ghost.getAttributeValue(Attributes.ATTACK_SPEED));
    }

    private static ItemStack item(String id) {
        Item it = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id));
        return it == null ? ItemStack.EMPTY : new ItemStack(it);
    }
}
