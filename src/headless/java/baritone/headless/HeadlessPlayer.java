/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 3). New file, replaces no Baritone code.
 *
 * The bot's own body: a plain common-side Player over the bot's protocol-owned Level. It is not a
 * LocalPlayer (there is no client), and it is never ticked by the game: the bot steps a PlayerSim and
 * copies the result in here before writing the movement packet, so every reader (Baritone movements,
 * reach checks, collisions) sees the authoritative body for this tick.
 */
package baritone.headless;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;

public final class HeadlessPlayer extends Player {

    private GameType gameType = GameType.SURVIVAL;

    public HeadlessPlayer(Level level, GameProfile profile) {
        super(level, profile);
    }

    @Override
    public GameType gameMode() {
        return gameType;
    }

    public void setGameType(GameType gameType) {
        this.gameType = gameType;
    }

    /** Ostinato: the entity is never ticked, so the attack-strength ticker must be advanced by the bot. */
    public void tickAttackStrength() {
        this.attackStrengthTicker++;
    }

    // Ostinato: the entity is never ticked, so vanilla dimensions/eyeHeight stay at the
    // standing value after setPose. playerHead(), objectMouseOver and placement rays
    // must see the current pose; otherwise a sneak-bridge look at a face center misses
    // and READY_TO_PLACE / isLookingAt never fire.
    @Override
    public void setPose(Pose pose) {
        Pose previous = getPose();
        super.setPose(pose);
        if (previous != pose) {
            refreshDimensions();
        }
    }
}
