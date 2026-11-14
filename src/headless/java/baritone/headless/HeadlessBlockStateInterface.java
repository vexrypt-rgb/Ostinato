/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 2/3). New file, replaces no Baritone code.
 *
 * Adapts the bot's own decoded chunk store to the real BlockStateInterface API, so BsiWorld,
 * CalculationContext and Ostinato's A* can run unchanged with no ClientLevel / Minecraft instance.
 *
 * This is the headless counterpart of the client's BlockStateInterface(IPlayerContext); it uses the
 * protected headless constructor (no world, no chunk provider) and overrides every method that
 * would otherwise dereference one.
 */
package baritone.headless;

import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class HeadlessBlockStateInterface extends BlockStateInterface {

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final HeadlessChunkStore store;

    public HeadlessBlockStateInterface(HeadlessChunkStore store, int minY, int height) {
        // A world border wide enough that it never constrains a bot on a normal survival world.
        super(minY, height, new BetterWorldBorder(-30_000_000.0, 30_000_000.0, -30_000_000.0, 30_000_000.0));
        this.store = store;
    }

    public HeadlessBlockStateInterface(HeadlessChunkStore store) {
        this(store, store.minSection() << 4, store.expectedSections() << 4);
    }

    @Override
    public BlockState get0(int x, int y, int z) {
        if (y < minY() || y >= minY() + height()) {
            return AIR;
        }
        BlockState state = store.getBlockState(x, y, z);
        // VOID_AIR marks a cell whose chunk was never sent. For collision and pathing that is air,
        // not an unbreakable void block.
        return state.is(Blocks.VOID_AIR) ? AIR : state;
    }

    @Override
    public boolean isLoaded(int x, int z) {
        return isKnown(x, z);
    }

    @Override
    public boolean isKnown(int x, int z) {
        return store.isChunkLoaded(x >> 4, z >> 4);
    }

    @Override
    public boolean worldContainsLoadedChunk(int x, int z) {
        return store.isChunkLoaded(x >> 4, z >> 4);
    }

    public HeadlessChunkStore store() {
        return store;
    }
}
