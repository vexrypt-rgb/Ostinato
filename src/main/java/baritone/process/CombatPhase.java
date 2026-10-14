package baritone.process;

import net.minecraft.world.item.Item;
import net.minecraft.world.phys.Vec3;

/**
 * The state the mace, wind-charge, pearl and elytra plays share. Plain fields on purpose: the plays run as one
 * hand-off chain (a pearl strike ends in a mace smash, a hop ends in a dive), so each reads where the others left off.
 * Nothing here decides anything; it only holds where the chain is.
 */
final class CombatPhase {
    // mace smash: phase 0 idle, then the charge, throw, hop and drop steps
    int macePhase, maceTicks, maceCool;
    int windCool, feetCool, feetTicks, hopThrown;
    int flickLeft, flickAge;
    boolean diveBlock;
    // pearl strike and pearl lift: stage 0 idle
    int pearlStage, pearlTicks, pearlCool;
    boolean pearlDive;
    Vec3 pearlFrom, pearlLast;
    // stranded below the target: a pearl up, or a dig down onto it
    boolean digDown;
    int strandTicks;
    float liftYaw, liftPitch;
    boolean liftSet;
    // elytra: the chestplate to put back, and the rocket boost in flight
    Item chestSaved;
    boolean boosted;
}
