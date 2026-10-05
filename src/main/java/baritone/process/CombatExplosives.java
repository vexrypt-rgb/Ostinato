package baritone.process;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import static baritone.process.CombatAim.press;
import static baritone.process.CombatGeometry.*;

/**
 * Crystal and anchor PvP, and the blast reads that go with them: which explosion to set off or defuse, where to
 * lay the block that stages one, and whether one is near enough to hurt. The process owns the fight; this
 * only drives the hands through {@link Hands} and keeps its own idle/back-off counters.
 */
final class CombatExplosives {
    /** What the process does for us: pick a hotbar slot, turn toward a point, click an entity, hold a movement key. */
    interface Hands {
        boolean select(Player me, int slot);
        void look(Vec3 at);
        boolean hit(Player me, Entity e);
        void key(Input in);
    }

    private final IPlayerContext ctx;
    private final CombatInventory inv;
    private final CombatAim aimer;
    private final Hands hands;
    private LivingEntity target; // set on each crystal() call; worth() and the scans read the fight's target from it
    private boolean crystalFight;
    private int backingOff;

    CombatExplosives(IPlayerContext ctx, CombatInventory inv, CombatAim aimer, Hands hands) {
        this.ctx = ctx;
        this.inv = inv;
        this.aimer = aimer;
        this.hands = hands;
    }

    /** True while a crystal or anchor fight is on, so the process keeps a totem in the offhand and eats earlier. */
    boolean fighting() {
        return crystalFight;
    }

    /**
     * Crystal PvP: break the crystal that hurts the target most, else put a crystal on obsidian where it does,
     * else lay obsidian beside the target's feet. Anything that would hurt us more than it, or pop us, is skipped.
     */
    boolean crystal(Player me, LivingEntity target) {
        this.target = target;
        // a ray the foe's body blocks never clicks, and a stall here froze the whole fight: no swing for a while, let melee have it
        // a swing is not progress (a refused click swings too): the foe losing health is
        float tgHp = target.getHealth() + target.getAbsorptionAmount();
        if (tgHp < crystalTgHp - 0.1f) crystalIdle = 0;
        else if (crystalIdle < 400) crystalIdle++;
        crystalTgHp = tgHp;
        if (crystalIdle > 40 && crystalIdle < 100) {
            crystalFight = true;
            return false;
        }
        if (crystalIdle >= 100) crystalIdle = 30;
        return crystalWork(me);
    }

    private int crystalIdle;
    private float crystalTgHp;

    private boolean crystalWork(Player me) {
        crystalFight = inv.slotOf(me, Items.END_CRYSTAL) >= 0 || inv.slotOf(me, Items.RESPAWN_ANCHOR) >= 0 || !ctx.world().getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(8)).isEmpty();
        if (anchor(me)) return true;
        if (inv.slotOf(me, Items.END_CRYSTAL) < 0 || me.distanceTo(target) > 7) return false;
        float myHp = me.getHealth() + me.getAbsorptionAmount();
        EndCrystal hitIt = null;
        float best = 0;
        for (EndCrystal c : ctx.world().getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(6))) {
            if (exactReach(me, c) > REACH) continue;
            float score = worth(me, c.position(), myHp);
            if (score > best) {
                best = score;
                hitIt = c;
            }
        }
        if (hitIt == null && inv.slotOf(me, Items.OBSIDIAN) >= 0) {
            // a crystal that would hurt us and that we won't pop: wall it off at leg height
            EndCrystal danger = null;
            float worst = 6;
            for (EndCrystal c : ctx.world().getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(6))) {
                float d = blast(me, c.position(), 12);
                if (d >= worst) { worst = d; danger = c; }
            }
            if (danger != null && shield(me, danger.blockPosition())) return true;
        }
        if (hitIt != null) {
            hands.look(hitIt.position());
            if (aimer.aimedAt(me, hitIt.getBoundingBox().getCenter(), 6f)) hands.hit(me, hitIt);
            return true;
        }
        Level w = ctx.world();
        BlockPos base = null;
        best = 0;
        BlockPos t = target.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(t.offset(-3, -2, -3), t.offset(3, 1, 3))) {
            if (!w.getBlockState(p).is(Blocks.OBSIDIAN) && !w.getBlockState(p).is(Blocks.BEDROCK)) continue;
            if (!w.isEmptyBlock(p.above()) || !w.getEntities(null, new AABB(p.above())).isEmpty()) continue;
            Vec3 at = Vec3.atBottomCenterOf(p.above());
            if (me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5 || me.getEyePosition().distanceTo(at.add(0, 1, 0)) > REACH + 0.8) continue;
            float score = worth(me, at, myHp);
            if (score > best) {
                best = score;
                base = p.immutable();
            }
        }
        if (base != null) return place(me, Items.END_CRYSTAL, base);
        if (inv.slotOf(me, Items.OBSIDIAN) < 0) return false;
        BlockPos floor = null;
        best = 0;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (BlockPos p : new BlockPos[]{t.relative(d), t.relative(d).below()}) {
                if (!w.getBlockState(p).canBeReplaced() || w.getBlockState(p.below()).canBeReplaced()) continue;
                if (!w.getEntities(null, new AABB(p)).isEmpty() || me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5) continue;
                float score = worth(me, Vec3.atBottomCenterOf(p.above()), myHp);
                if (score > best) {
                    best = score;
                    floor = p.below();
                }
            }
        }
        return floor != null && place(me, Items.OBSIDIAN, floor);
    }

    /**
     * Anchor PvP (overworld): blow a charged anchor that hurts the target, else charge an anchor near it with
     * glowstone, else put an anchor down beside its feet.
     */
    private boolean anchor(Player me) {
        if (inv.slotOf(me, Items.RESPAWN_ANCHOR) < 0 && inv.slotOf(me, Items.GLOWSTONE) < 0 || me.distanceTo(target) > 7) return false;
        Level w = ctx.world();
        float myHp = me.getHealth() + me.getAbsorptionAmount();
        BlockPos t = target.blockPosition(), boom = null, charge = null, backOff = null;
        float bestBoom = 0, bestCharge = 0, bestBack = 0;
        for (BlockPos p : BlockPos.betweenClosed(t.offset(-3, -1, -3), t.offset(3, 2, 3))) {
            if (!w.getBlockState(p).is(Blocks.RESPAWN_ANCHOR) || me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5) continue;
            Vec3 at = Vec3.atCenterOf(p);
            float score = worth(me, at, myHp, 10), dmg = blast(target, at, 10);
            boolean charged = w.getBlockState(p).getValue(RespawnAnchorBlock.CHARGE) > 0;
            if (!charged && score > 0) {
                // charging hurts nobody, so charge anything that would hurt the target
                if (dmg > bestCharge) { bestCharge = dmg; charge = p.immutable(); }
            } else if (charged && score > bestBoom) {
                bestBoom = score;
                boom = p.immutable();
            } else if (score <= 0 && blast(me, at, 10) >= 8 && blast(me, at, 10) > bestBack) {
                bestBack = blast(me, at, 10); // theirs or ours, it can go off in our face
                backOff = p.immutable();
            }
        }
        if (boom != null) {
            int slot = -1;
            for (int i = 0; i < 9; i++) {
                Item it = me.getInventory().getItem(i).getItem();
                if (it != Items.GLOWSTONE && it != Items.RESPAWN_ANCHOR) { slot = i; break; }
            }
            if (slot < 0) return false;
            if (!hands.select(me, slot)) return true;
            return click(me, boom);
        }
        if (charge != null && inv.slotOf(me, Items.GLOWSTONE) >= 0) {
            if (!hands.select(me, inv.slotOf(me, Items.GLOWSTONE))) return true;
            return me.getMainHandItem().getItem() == Items.GLOWSTONE && click(me, charge);
        }
        // a charged anchor that would hurt us: wall it off at leg height, which is where most of the blast lands
        if (backOff != null && shield(me, backOff)) return true;
        backingOff = backOff == null ? 0 : backingOff + 1;
        // a charged anchor that would hurt us too much from here: step away, then blow it (unless a wall keeps us pinned)
        if (backOff != null && backingOff < 40) {
            hands.look(Vec3.atCenterOf(backOff));
            hands.key(Input.MOVE_BACK);
            return true;
        }
        if (inv.slotOf(me, Items.RESPAWN_ANCHOR) < 0 || inv.slotOf(me, Items.GLOWSTONE) < 0) return false;
        BlockPos spot = null;
        float best = 0;
        // not just beside them: a target down a one-wide hole has no free side, only the rim
        for (BlockPos q : BlockPos.betweenClosed(t.offset(-2, -1, -2), t.offset(2, 2, 2))) {
            {
                BlockPos p = q.immutable();
                if (!w.getBlockState(p).canBeReplaced() || w.getBlockState(p.below()).canBeReplaced()) continue;
                if (!w.getEntities(null, new AABB(p)).isEmpty() || me.getEyePosition().distanceTo(Vec3.atCenterOf(p)) > 4.5) continue;
                float score = worth(me, Vec3.atCenterOf(p), myHp, 10);
                if (score > best) { best = score; spot = p; }
            }
        }
        return spot != null && place(me, Items.RESPAWN_ANCHOR, spot.below());
    }

    /** A charged anchor or a crystal close enough to hurt: 27 ticks of chewing beside one is how a bite becomes a death. */
    boolean blastThreat(Player me) {
        if (me.getHealth() <= 4) return false; // nothing left to lose by eating
        Level w = ctx.world();
        for (EndCrystal c : w.getEntitiesOfClass(EndCrystal.class, me.getBoundingBox().inflate(8))) {
            if (blast(me, c.position(), 12) >= 6) return true;
        }
        BlockPos f = me.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(f.offset(-6, -3, -6), f.offset(6, 3, 6))) {
            if (w.getBlockState(p).is(Blocks.RESPAWN_ANCHOR) && w.getBlockState(p).getValue(RespawnAnchorBlock.CHARGE) > 0
                    && blast(me, Vec3.atCenterOf(p), 10) >= 6) return true;
        }
        return false;
    }

    /** Put a block in the cell between our feet and {@code threat} so the explosion's rays hit it instead of our legs. */
    boolean shield(Player me, BlockPos threat) {
        Item block = inv.slotOf(me, Items.OBSIDIAN) >= 0 ? Items.OBSIDIAN : inv.slotOf(me, Items.COBBLESTONE) >= 0 ? Items.COBBLESTONE
                : inv.slotOf(me, Items.RESPAWN_ANCHOR) >= 0 ? Items.RESPAWN_ANCHOR : null;
        if (block == null) return false;
        BlockPos feet = me.blockPosition();
        int dx = Integer.signum(threat.getX() - feet.getX()), dz = Integer.signum(threat.getZ() - feet.getZ());
        Level w = ctx.world();
        for (BlockPos c : new BlockPos[]{feet.offset(dx, 0, dz), feet.offset(dx, 0, 0), feet.offset(0, 0, dz)}) {
            if (c.equals(feet) || c.equals(threat) || !w.getBlockState(c).canBeReplaced() || w.getBlockState(c.below()).canBeReplaced()) continue;
            if (!w.getEntities(null, new AABB(c)).isEmpty() || me.getEyePosition().distanceTo(Vec3.atCenterOf(c)) > 4.5) continue;
            return place(me, block, c.below());
        }
        return false;
    }

    /** Right-click the top face of a block with whatever is in hand. */
    boolean click(Player me, BlockPos on) {
        Vec3 face = Vec3.atCenterOf(on).add(0, 0.5, 0);
        hands.look(face);
        if (ctx.minecraft().hitResult instanceof BlockHitResult b && b.getBlockPos().equals(on)) press(ctx.minecraft().options.keyUse);
        return true;
    }

    /** Right-click the top of {@code on} with {@code item}. */
    boolean place(Player me, Item item, BlockPos on) {
        if (!hands.select(me, inv.slotOf(me, item))) return true;
        if (me.getMainHandItem().getItem() != item) return false;
        return click(me, on);
    }

    /** How good a crystal blowing up at {@code at} is for us: its damage to the target minus ours, 0 if not worth it. */
    private float worth(Player me, Vec3 at, float myHp) {
        return worth(me, at, myHp, 12);
    }

    private float worth(Player me, Vec3 at, float myHp, double size) {
        float dmg = blast(target, at, size), self = blast(me, at, size);
        boolean totem = me.getOffhandItem().getItem() == Items.TOTEM_OF_UNDYING;
        if (self >= myHp - (totem ? 0 : 2) && dmg < target.getHealth() + target.getAbsorptionAmount()) return 0;
        // once they're low an even trade wins the race
        if (dmg < 3 || dmg < self * (size == 10 ? (target.getHealth() + target.getAbsorptionAmount() > 10 ? 1.5f : 1) : (target.getHealth() + target.getAbsorptionAmount() <= 10 ? 0.8f : 1))) return 0;
        if (size == 10 && self >= myHp - 4 && dmg < target.getHealth() + target.getAbsorptionAmount()) return 0; // don't pop our own totem
        // our own blasts were landing 7-16 on us right after a place or a boom: unless it kills them, keep our share small
        // and weigh it heavier than the foe's (self 1.4 weight, small safe self-damage)
        boolean kills = dmg >= target.getHealth() + target.getAbsorptionAmount();
        if (!kills && self > (totem ? 3f : 4f)) return 0;
        return Math.max(0, dmg - self * 1.4f);
    }
}
