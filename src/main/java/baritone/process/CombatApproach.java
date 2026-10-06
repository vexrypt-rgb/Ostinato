package baritone.process;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.input.Input;
import static baritone.process.CombatGeometry.*;

/**
 * Getting to the fight: dig through a wall the target is behind, walk off an edge after a target that fell, or path
 * toward (or shoot at) one that is out of drive range. Null once the target is close and in the open, which is when
 * the exchange itself starts.
 */
final class CombatApproach {
    /** What the process does for us: hotbar, aim, keys, the tick's decision label and the eat clock. */
    interface Hands {
        boolean select(Player me, int slot);
        void look(Vec3 at);
        void use(boolean down);
        void key(Input in);
        PathingCommand decide(String d);
        PathingCommand decide(String d, PathingCommand cmd);
        int eatTicks();
    }

    private final IPlayerContext ctx;
    private final CombatInventory inv;
    private final CombatTools tools;
    private final CombatPhase phase;
    private final Hands hands;

    CombatApproach(IPlayerContext ctx, CombatInventory inv, CombatTools tools, CombatPhase phase, Hands hands) {
        this.ctx = ctx;
        this.inv = inv;
        this.tools = tools;
        this.phase = phase;
        this.hands = hands;
    }

    /** One tick of closing in; {@code spearUseCool} is the spear's charge cooldown, which decides whether a spear runs up on foot. */
    PathingCommand close(Player me, LivingEntity target, double dist, boolean los, int spearUseCool) {
        if (!los && dist <= 3) { // right there but walled off (a crawl gap under our feet, a hole): dig through
            BlockHitResult wall = ctx.world().clip(new net.minecraft.world.level.ClipContext(me.getEyePosition(), target.getEyePosition(),
                    net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE, me));
            // obsidian and anchors take minutes by hand: the bench sat 1800 ticks left-clicking one
            if (wall.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                    && ctx.world().getBlockState(wall.getBlockPos()).getDestroySpeed(ctx.world(), wall.getBlockPos()) < 10) {
                hands.look(wall.getLocation());
                hands.key(Input.CLICK_LEFT); // hold the attack key on the wall
                return hands.decide("dig");
            }
        }
        // 1907 bench: they fell 8 blocks off the platform and no path follows a drop that deep, so
        // 945 ticks went to standing at the edge. Walk off after them; with a mace the fall is a dive.
        double drop = me.getY() - target.getY();
        if (drop > 3.5 && drop < 20 && horizontalBoxDist(me, target) < 8 && hands.eatTicks() == 0
                && (inv.slotOf(me, Items.MACE) >= 0 || me.getHealth() > drop + 4)) {
            hands.use(false);
            int mace = inv.slotOf(me, Items.MACE);
            hands.select(me, mace >= 0 ? mace : inv.weapon(me));
            hands.look(target.getEyePosition());
            hands.key(Input.MOVE_FORWARD);
            hands.key(Input.SPRINT);
            if (!me.onGround() && mace >= 0) {
                phase.macePhase = 2;
                phase.maceTicks = 5;
            }
            return hands.decide("drop");
        }
        if (dist > DRIVE || !los) {
            // Charge needs a sprint runway. Baritone chase from 7 blocks never reaches 4.6 blocks/s
            // before the pierce window, so a plain spear closes that gap on foot.
            boolean spearRush = inv.spearSlot(me) >= 0 && los && dist < 14 && hands.eatTicks() == 0 && spearUseCool == 0
                    && me.getFoodData().getFoodLevel() > 6;
            if (!spearRush) {
                if (los && dist > BOW_MIN && inv.slotOf(me, Items.BOW) >= 0 && inv.slotOf(me, Items.ARROW) >= 0) return hands.decide("bow", tools.bow(me, target));
                hands.use(false);
                // Spear chase stops in the jab band, not inside the 2-block dead zone.
                int near = inv.spearSlot(me) >= 0 ? 3 : 2;
                return hands.decide("chase", new PathingCommand(new GoalNear(target.blockPosition(), near), PathingCommandType.REVALIDATE_GOAL_AND_PATH));
            }
        }
        return null;
    }
}
