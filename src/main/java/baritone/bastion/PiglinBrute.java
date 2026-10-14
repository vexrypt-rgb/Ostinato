package baritone.bastion;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.monster.MonsterEntity;
import net.minecraft.world.World;

/**
 * 1.16.1 has no piglin brutes (added in 1.16.2), and the real class does not exist at runtime there. This never-instantiated
 * stand-in keeps the shared brute code compiling: {@code instanceof PiglinBrute} is always false and a search for it is always
 * empty, so every brute branch is inert.
 */
public abstract class PiglinBrute extends MonsterEntity {
    private PiglinBrute(EntityType<? extends MonsterEntity> t, World w) { super(t, w); }
}
