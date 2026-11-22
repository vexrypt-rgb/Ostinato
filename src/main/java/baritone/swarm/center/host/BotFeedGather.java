package baritone.swarm.center.host;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import baritone.api.run.RunMode;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.interfaces.IGoalRenderPos;
import baritone.behavior.PathingBehavior;
import baritone.cache.CachedChunk;
import baritone.cache.CachedRegion;
import baritone.cache.CachedWorld;
import baritone.cache.WorldData;
import baritone.gui.hud.PathStatus;
import baritone.pathing.path.PathExecutor;
import baritone.swarm.SwarmSample;
import baritone.swarm.center.feed.BotFeed;
import baritone.swarm.center.feed.InventorySummary;
import baritone.swarm.center.feed.TerrainBuilder;
import baritone.swarm.center.feed.TerrainFeed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ostinato: Phase 3 read feed, bot side. Reads this client's state into a {@link BotFeed}. Client thread only, read
 * only: every call here is a getter that the HUD, the status view or {@code #swarm status} already makes. Nothing is
 * changed, so turning the feed on cannot change what the bot does.
 */
final class BotFeedGather {

    private BotFeedGather() {}

    static BotFeed gather(Baritone b, String name, long nowWallMs, long seq, CenterFeedService.SwarmPart swarm) {
        long t0 = System.nanoTime();
        LocalPlayer p = b.getPlayerContext().player();
        BotFeed.Pos pos = null;
        int health = -1, maxHealth = -1, hunger = -1;
        boolean dead = false;
        BotFeed.Inventory inv = BotFeed.Inventory.UNKNOWN;
        if (p != null) {
            pos = new BotFeed.Pos(dim(p.level()), p.getX(), p.getY(), p.getZ(), p.getYRot());
            health = (int) Math.ceil(p.getHealth());
            maxHealth = (int) Math.ceil(p.getMaxHealth());
            hunger = p.getFoodData().getFoodLevel();
            dead = p.isDeadOrDying();
            inv = inventory(p);
        }
        String process = b.getPathingControlManager().mostRecentInControl().map(IBaritoneProcess::displayName)
                .orElse("idle");
        SwarmSample s = swarm.sample() == null ? SwarmSample.UNKNOWN : swarm.sample();
        BotFeed.Perf perf = new BotFeed.Perf(s.pathMs(), s.tpsX10(), s.pingMs(), s.ramUsedMb(), s.ramMaxMb(),
                s.chatUsed(), s.chatCap(), s.dropped(), s.retried());
        BotFeed.Route route = route(b);
        long gatherUs = (System.nanoTime() - t0) / 1000;
        return new BotFeed(name, nowWallMs, seq, server(), RunMode.current().name().toLowerCase(Locale.ROOT),
                swarm.commandHost(), swarm.coordinator(), pos, health, maxHealth, hunger, dead, process, route, inv,
                swarm.executions(), swarm.leases(), perf, swarm.link(), gatherUs);
    }

    static char dim(Level level) {
        if (level == null) {
            return '?';
        }
        if (level.dimension() == Level.OVERWORLD) {
            return 'o';
        }
        if (level.dimension() == Level.NETHER) {
            return 'n';
        }
        return level.dimension() == Level.END ? 'e' : '?';
    }

    static String server() {
        try {
            Minecraft mc = Minecraft.getInstance();
            return mc.getCurrentServer() == null || mc.getCurrentServer().ip == null ? "" : mc.getCurrentServer().ip;
        } catch (RuntimeException e) {
            return "";
        }
    }

    static BotFeed.Inventory inventory(LocalPlayer p) {
        List<InventorySummary.Slot> slots = new ArrayList<>();
        for (ItemStack st : p.getInventory().getNonEquipmentItems()) {
            if (st.isEmpty()) {
                slots.add(null);
                continue;
            }
            slots.add(new InventorySummary.Slot(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath(), st.getCount(),
                    st.get(DataComponents.FOOD) != null, st.isDamageableItem() ? st.getDamageValue() : 0,
                    st.isDamageableItem() ? st.getMaxDamage() : 0));
        }
        return InventorySummary.of(slots);
    }

    static BotFeed.Route route(Baritone b) {
        PathStatus st = PathStatus.capture(b);
        PathingBehavior pb = b.getPathingBehavior();
        Goal goal = pb.getGoal();
        int[] goalPos = null;
        if (goal instanceof IGoalRenderPos g) {
            BlockPos gp = g.getGoalPos();
            goalPos = new int[]{gp.getX(), gp.getY(), gp.getZ()};
        }
        List<int[]> nodes = new ArrayList<>();
        PathExecutor cur = pb.getCurrent();
        if (cur != null) {
            List<BetterBlockPos> all = cur.getPath().positions();
            for (int i = Math.max(0, cur.getPosition()); i < all.size() && nodes.size() < BotFeed.MAX_NODES; i++) {
                BetterBlockPos n = all.get(i);
                nodes.add(new int[]{n.x, n.y, n.z});
            }
        }
        return new BotFeed.Route(st.state.name().toLowerCase(Locale.ROOT), goal == null ? "" : goal.toString(),
                goalPos, Double.isNaN(st.distance) ? -1 : st.distance, st.position, st.length, nodes);
    }

    /**
     * The terrain slice around (x, z) from this bot's own cache. Runs on the feed writer thread: the cache's regions
     * are read the same unsynchronized way the path threads read them, and nothing is loaded or written.
     */
    static TerrainFeed terrain(String name, long nowWallMs, long seq, char dim, int x, int z, WorldData world) {
        CachedWorld cache = world.cache;
        java.util.Map<Long, Object[]> memo = new java.util.HashMap<>(); // chunk key -> {CachedChunk, minY}
        return TerrainBuilder.around(name, nowWallMs, seq, dim, x, z, new TerrainBuilder.Columns() {
            private Object[] chunk(int bx, int bz) {
                long key = ((long) (bx >> 4) << 32) ^ ((bz >> 4) & 0xFFFFFFFFL);
                return memo.computeIfAbsent(key, k -> {
                    CachedRegion r = cache.getRegion(bx >> 9, bz >> 9);
                    CachedChunk c = r == null ? null : r.chunkAt((bx >> 4) & 31, (bz >> 4) & 31);
                    return new Object[]{c, r == null ? 0 : r.minY()};
                });
            }

            @Override
            public String block(int bx, int bz) {
                CachedChunk c = (CachedChunk) chunk(bx, bz)[0];
                if (c == null) {
                    return null;
                }
                BlockState s = c.getOverview()[(bz & 15) << 4 | (bx & 15)];
                return s == null ? null : BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
            }

            @Override
            public int y(int bx, int bz) {
                Object[] e = chunk(bx, bz);
                CachedChunk c = (CachedChunk) e[0];
                return c == null ? TerrainFeed.UNKNOWN_Y : c.surfaceY(bx & 15, bz & 15) + (int) e[1];
            }
        });
    }
}
