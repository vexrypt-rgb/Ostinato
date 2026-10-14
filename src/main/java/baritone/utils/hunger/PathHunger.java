package baritone.utils.hunger;

import baritone.api.pathing.movement.IMovement;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.movements.*;
import baritone.utils.ExperimentalMovement;

import java.util.ArrayList;
import java.util.List;

/** Ostinato code: turns a planned path into {@link HungerModel.Segment}s from its move types. */
public final class PathHunger {
    private PathHunger() {}

    public static List<HungerModel.Segment> segments(List<IMovement> movements, int from, boolean sprint) {
        List<HungerModel.Segment> out = new ArrayList<>();
        for (int i = Math.max(0, from); i < movements.size(); i++) {
            IMovement mv = movements.get(i);
            double c = mv.getCost();
            int ticks = (int) Math.max(1, Math.min(2000, Double.isFinite(c) ? c : 20));
            double dx = mv.getDest().x - mv.getSrc().x, dz = mv.getDest().z - mv.getSrc().z;
            double horiz = Math.sqrt(dx * dx + dz * dz);
            int dy = mv.getDest().y - mv.getSrc().y;
            float ex = 0;
            if (mv instanceof MovementSwim) {
                ex += HungerModel.swimExhaustion(Math.sqrt(horiz * horiz + dy * dy));
            } else if (sprint && (mv instanceof MovementTraverse || mv instanceof MovementDiagonal || mv instanceof MovementDescend
                    || mv instanceof MovementParkour || mv instanceof MovementJump || mv instanceof MovementChainJump || mv instanceof MovementClimbJump)) {
                ex += HungerModel.sprintExhaustion(horiz);
            }
            if (mv instanceof MovementAscend || mv instanceof MovementPillar) {
                ex += HungerModel.jumpExhaustion(sprint && mv instanceof MovementAscend);
            } else if (mv instanceof MovementParkour || mv instanceof MovementJump || mv instanceof MovementClimbJump) {
                ex += HungerModel.jumpExhaustion(sprint);
            } else if (mv instanceof MovementChainJump) {
                ex += 2 * HungerModel.jumpExhaustion(sprint);
            }
            if (mv instanceof Movement) {
                ex += HungerModel.mineExhaustion(((Movement) mv).toBreakAll().length);
            }
            float dmg = 0;
            if ((mv instanceof MovementFall || mv instanceof MovementDescend) && dy < -ExperimentalMovement.SAFE_FALL && ExperimentalMovement.on()) {
                dmg = ExperimentalMovement.fallDamage(-dy);
            }
            out.add(new HungerModel.Segment(ticks, ex, dmg, 0));
        }
        return out;
    }
}
