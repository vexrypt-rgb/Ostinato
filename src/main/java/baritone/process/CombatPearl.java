package baritone.process;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import baritone.api.process.PathingCommand;
import net.minecraft.world.item.Items;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import static baritone.process.CombatAim.press;

/**
 * The two pearl plays that get us up to a foe out of reach: a throw that lands on the ledge they stand on, and the
 * pearl-and-wind-charge lift under a mace. Both keep their progress in {@link CombatPhase}, where the mace play and
 * the reset read it too.
 */
final class CombatPearl {
    /** What the process does for us: hotbar and the tick's decision label. */
    interface Hands {
        boolean select(Player me, int slot);
        void look(Vec3 at);
        PathingCommand decide(String d);
    }

    private final IPlayerContext ctx;
    private final CombatInventory inv;
    private final CombatAim aimer;
    private final CombatTargeting targeting;
    private final CombatPhase phase;
    private final Hands hands;
    /** Set by {@link #lift} and {@link #strike}: true when it took the tick, even if it took it by returning null. */
    boolean handled;

    CombatPearl(IPlayerContext ctx, CombatInventory inv, CombatAim aimer, CombatTargeting targeting, CombatPhase phase, Hands hands) {
        this.ctx = ctx;
        this.inv = inv;
        this.aimer = aimer;
        this.targeting = targeting;
        this.phase = phase;
        this.hands = hands;
    }

    /** The throw that carries us up to a foe well above; null when it isn't the moment. */
    PathingCommand strand(Player me, LivingEntity target, int pearlSlot, float myHp, int eatTicks) {
        // Stranded below: they are well above and nothing walkable leads up. A pearl that lands on solid ground at their
        // level carries us there, so look for a throw whose flight ends on a top face up there and take it.
        if (pearlSlot >= 0 && phase.macePhase == 0 && phase.pearlStage == 0 && eatTicks == 0 && me.onGround() && myHp >= 10
                && target.getY() - me.getY() >= 8) {
            phase.strandTicks++;
        } else {
            phase.strandTicks = 0;
            phase.liftSet = false;
        }
        if (phase.strandTicks > 60 && phase.pearlCool == 0) {
            if (!phase.liftSet) {
                Vec3 eye = me.getEyePosition();
                double bestScore = 1e9;
                for (float yaw = -180; yaw < 180; yaw += 12) {
                    for (float pitch = -85; pitch <= -10; pitch += 5) {
                        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
                        Vec3 v = new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr)).scale(1.5);
                        Vec3 pos = eye;
                        for (int t = 0; t < 90; t++) {
                            Vec3 next = pos.add(v);
                            net.minecraft.world.phys.BlockHitResult hit = ctx.world().clip(new net.minecraft.world.level.ClipContext(pos, next,
                                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, me));
                            if (hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                                if (hit.getDirection() == net.minecraft.core.Direction.UP && hit.getLocation().y >= target.getY() - 1.5) {
                                    double score = Math.hypot(hit.getLocation().x - target.getX(), hit.getLocation().z - target.getZ());
                                    if (score < bestScore) {
                                        bestScore = score;
                                        phase.liftYaw = yaw;
                                        phase.liftPitch = pitch;
                                    }
                                }
                                break;
                            }
                            pos = next;
                            v = v.scale(0.99).add(0, -0.03, 0);
                            if (pos.y < eye.y - 40) break;
                        }
                    }
                }
                if (bestScore > 20) {
                    phase.pearlCool = 200;
                    phase.strandTicks = 0;
                } else {
                    phase.liftSet = true;
                }
            }
            if (phase.liftSet) {
                if (!hands.select(me, pearlSlot)) return hands.decide("swap");
                if (!aimer.face(phase.liftYaw, phase.liftPitch, 2.0f)) return hands.decide("pearl");
                press(ctx.minecraft().options.keyUse);
                phase.pearlCool = 300;
                phase.strandTicks = 0;
                phase.liftSet = false;
                return hands.decide("pearl");
            }
        }
        return null;
    }

    /** The pearl lift; check {@link #handled} afterwards, since a null result can still mean it took the tick. */
    PathingCommand lift(Player me, LivingEntity target, double dist, boolean los, boolean overhead, int mace, int wind, int pearlSlot, float myHp) {
        handled = false;
        // Pearl lift: a pearl thrown straight up loses speed and a wind charge thrown after it does not. The pearl
        // lands on the charge some 18 blocks up and that is where we are, with a full fall onto the mace below.
        if (mace >= 0 && wind >= 0 && phase.macePhase == 0 && (phase.pearlStage == 3 || phase.pearlStage == 4 || phase.pearlStage == 0 && pearlSlot >= 0 && phase.pearlCool == 0
                && phase.maceCool == 0 && me.onGround() && los && dist > 3.5 && dist <= 7 && myHp >= 15 && !overhead && target.onGround()
                && me.getDeltaMovement().horizontalDistance() < 0.12
                && ctx.world().clip(new net.minecraft.world.level.ClipContext(me.getEyePosition(), me.getEyePosition().add(0, 26, 0),
                        net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, me)).getType() == net.minecraft.world.phys.HitResult.Type.MISS)) {
            handled = true;
            phase.pearlTicks++;
            if (phase.pearlStage == 0) {
                phase.pearlStage = 3;
                phase.pearlTicks = 0;
                phase.pearlFrom = me.position();
            }
            if (phase.pearlTicks > 30 || me.position().distanceTo(phase.pearlFrom) > 0.4 || phase.pearlStage == 3 && overhead) { // shoved off the line: the two no longer meet
                boolean thrown = phase.pearlStage == 4;
                phase.pearlStage = thrown ? 2 : 0;
                phase.pearlCool = thrown ? 0 : 60;
                phase.pearlTicks = 0;
                return thrown ? hands.decide("pearl") : null;
            }
            if (!hands.select(me, phase.pearlStage == 3 ? pearlSlot : wind)) return hands.decide("swap");
            // both leave on the same rotation, so whatever the pitch is short of 90 they still share a line
            if (!aimer.face(me.getYRot(), -90f, 1.0f)) return hands.decide("pearl");
            press(ctx.minecraft().options.keyUse);
            if (phase.pearlStage == 3) {
                phase.pearlStage = 4;
            } else {
                phase.pearlStage = 2;
                phase.pearlTicks = 0;
            }
            return hands.decide("pearl");
        }
        return null;
    }

    /** The pearl strike; check {@link #handled} afterwards, as with {@link #lift}. */
    PathingCommand strike(Player me, LivingEntity target, double dist, boolean los, boolean overhead, int mace, int wind) {
        handled = false;
        // pearl strike: lob a pearl so it peaks above the target, pop it mid-air with a wind charge to teleport there, then drop the mace
        if (mace >= 0 && (wind >= 0 || phase.pearlStage == 2) && phase.macePhase == 0 && (phase.pearlStage > 0 || phase.pearlCool == 0 && phase.maceCool == 0 && me.onGround() && los && dist > 7 && dist < 22
                && inv.slotOf(me, Items.ENDER_PEARL) >= 0 && target.onGround() && !overhead)) {
            handled = true;
            if (phase.pearlStage == 0) {
                float bestPitch = 0;
                double bestErr = 1e9;
                Vec3 eye = me.getEyePosition();
                Vec3 flat = new Vec3(target.getX() - me.getX(), 0, target.getZ() - me.getZ()).normalize();
                for (float pitch = -80; pitch <= -25; pitch += 1.5f) {
                    double pr = Math.toRadians(pitch);
                    Vec3 v = new Vec3(flat.x * Math.cos(pr), -Math.sin(pr), flat.z * Math.cos(pr)).scale(1.5);
                    Vec3 p = eye;
                    for (int t = 0; t < 80; t++) {
                        p = p.add(v);
                        v = v.scale(0.99).add(0, -0.03, 0);
                        Vec3 tp = target.position().add(targeting.velocity(target).scale(t + 1));
                        double h = Math.hypot(p.x - tp.x, p.z - tp.z);
                        if (p.y > target.getY() + 5 && p.y < target.getY() + 14 && h < bestErr) {
                            bestErr = h;
                            bestPitch = pitch;
                        }
                        if (p.y < eye.y - 2 && v.y < 0) break;
                    }
                }
                if (bestErr > 2.0) {
                    phase.pearlCool = 80;
                    return null;
                }
                if (!hands.select(me, inv.slotOf(me, Items.ENDER_PEARL))) return hands.decide("swap");
                Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), eye.add(flat.scale(10)), ctx.playerRotations());
                if (!aimer.face(r.getYaw(), bestPitch, 2.5f)) return hands.decide("pearl");
                press(ctx.minecraft().options.keyUse);
                phase.pearlStage = 1;
                phase.pearlTicks = 0;
                phase.pearlFrom = me.position();
                return hands.decide("pearl");
            }
            phase.pearlTicks++;
            if (phase.pearlStage == 1) {
                net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl pearl = null;
                for (net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl e : ctx.world().getEntitiesOfClass(net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl.class, me.getBoundingBox().inflate(60), x -> x.getOwner() == me)) pearl = e;
                if (pearl != null) phase.pearlLast = pearl.position();
                if (pearl == null || phase.pearlTicks > 90 || dist < 4) {
                   
                    phase.pearlStage = 0;
                    phase.pearlCool = pearl == null && phase.pearlTicks <= 3 ? 0 : 120;
                    return null;
                }
                if (!hands.select(me, wind)) return hands.decide("swap");
                Vec3 pv = pearl.getDeltaMovement();
                Vec3 pp = pearl.position(), vv = pv;
                int n = 1;
                boolean ok = false;
                for (; n < 80; n++) { // first tick the pearl is over the target, high enough
                    pp = pp.add(vv);
                    vv = vv.scale(0.99).add(0, -0.03, 0);
                    Vec3 tpn = target.position().add(targeting.velocity(target).scale(n));
                    if (Math.hypot(pp.x - tpn.x, pp.z - tpn.z) < 1.3 && pp.y > tpn.y + 4) {
                        ok = true;
                        break;
                    }
                    if (pp.y < me.getY() - 3) break;
                }
                hands.look(pearl.position());
                if (ok && pp.distanceTo(me.getEyePosition()) / 1.5 >= n - 1) { // the charge needs about as long to arrive as the pearl does
                    Rotation r = RotationUtils.calcRotationFromVec3d(ctx.playerHead(), pp, ctx.playerRotations());
                    if (!aimer.face(r.getYaw(), r.getPitch(), 2.5f)) return hands.decide("pearl");
                    press(ctx.minecraft().options.keyUse);
                    phase.pearlStage = 2;
                    phase.pearlTicks = 0;
                }
                return hands.decide("pearl");
            }
            // stage 2: wait for the teleport, then fall on it
            if (me.position().distanceTo(phase.pearlFrom) > 3.5) {
                phase.pearlStage = 0;
                phase.pearlCool = 200;
                phase.macePhase = 2;
                phase.maceTicks = 0;
                phase.pearlDive = true;
            } else if (phase.pearlTicks > 40) {
                phase.pearlStage = 0;
                phase.pearlCool = 200;
            }
            return hands.decide("pearl");
        }
        return null;
    }
}
