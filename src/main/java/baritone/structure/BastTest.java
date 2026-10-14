package baritone.structure;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.bastion.LavaFlow;
import net.minecraft.block.Blocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.command.CommandSource;
import net.minecraft.command.ICommandSource;
import net.minecraft.entity.LivingEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Direction;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector2f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.gen.feature.structure.Structure;
import net.minecraft.world.gen.feature.structure.StructureStart;
import net.minecraft.world.server.ServerWorld;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.16.1 live bastion harness (-Dostinato.basttest=true). Rides on TenorClef's fresh Easy world (commands off: every command
 * here runs from the integrated server's own op-4 source, so no cheats are needed). Finds a bastion of
 * -Dostinato.basttest.variant=treasure|housing|stables|bridge|any with /locate from a grid of points, tells the type apart by
 * the start piece, drops us on reachable lava-free netherrack 45+ blocks out, kits us and runs #bastion. Logs BAST lines.
 */
public final class BastTest implements AbstractGameEventListener {
    private final Baritone b;
    private int state, ticks;
    private BlockPos target;
    private Vector3d stallAt;
    private int stallTick, stallCarried;
    private static final String VARIANT = System.getProperty("ostinato.basttest.variant", "any");
    private static final RegistryKey<World> NETHER = World.THE_NETHER;

    private BastTest(Baritone b) { this.b = b; }

    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.basttest")) b.getGameEventHandler().registerEventListener(new BastTest(b));
    }

    private void say(String m) {
        System.out.println(m);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.execute(() -> baritone.api.BaritoneAPI.getSettings().logger.value.accept(new StringTextComponent(m)));
    }

    /** Runs a command as the server (op 4) at the player, collecting its feedback. */
    private List<String> run(String cmd) {
        MinecraftServer s = Minecraft.getInstance().getIntegratedServer();
        List<String> out = Collections.synchronizedList(new ArrayList<>());
        ICommandSource sink = new ICommandSource() {
            public void sendMessage(ITextComponent t, UUID u) { out.add(t.getString()); }
            public boolean shouldReceiveFeedback() { return true; }
            public boolean shouldReceiveErrors() { return true; }
            public boolean allowLogging() { return false; }
        };
        s.runAsync(() -> {
            net.minecraft.entity.player.ServerPlayerEntity p = s.getPlayerList().getPlayers().get(0);
            CommandSource src = new CommandSource(sink, p.getPositionVec(), Vector2f.ZERO, (ServerWorld) p.world, 4, "bast", new StringTextComponent("bast"), s, p);
            s.getCommandManager().handleCommand(src, cmd);
        }).join();
        return out;
    }

    private static final Pattern LOC = Pattern.compile("\\[(-?\\d+), ~, (-?\\d+)\\]");

    @Override
    public void onTick(TickEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getIntegratedServer() == null || e.getType() != TickEvent.Type.IN) return;
        ticks++;
        if (state == 0) {
            if (ticks < 200) return;
            run("gamerule doDaylightCycle false");
            run("gamerule doMobSpawning false");
            run("gamerule sendCommandFeedback false");
            run("gamemode creative @p");
            target = findBastion(mc);
            if (target == null) { say("BAST END no " + VARIANT + " bastion found"); state = 9; mc.execute(mc::shutdown); return; }
            BlockPos spot = startSpot(mc.getIntegratedServer().getWorld(NETHER), target);
            say("BAST target " + target.getCoordinatesAsString() + " variant " + VARIANT + " start " + (spot == null ? "?" : spot.getCoordinatesAsString()) + " seed " + mc.getIntegratedServer().getWorld(World.OVERWORLD).getSeed());
            if (spot == null) spot = new BlockPos(target.getX() + 50, 90, target.getZ());
            run("execute in minecraft:the_nether run tp @p " + spot.getX() + " " + spot.getY() + " " + spot.getZ());
            b.getStructureBehavior().setMode(StructureBehavior.Mode.BOTH);
            state = 1; ticks = 0;
            return;
        }
        if (state == 1) {
            if (ticks < 200) return;
            run("clear @p");
            // kit presets: "run" (default) is a realistic 1.16.1 speedrun nether entry, gold comes from the bastion; "rich" is the old test kit
            for (String c : BastKit.commands(System.getProperty("ostinato.basttest.kit", "run"))) run(c);
            run("gamemode survival @p");
            run("difficulty easy");
            if (Integer.getInteger("ostinato.basttest.gold", 0) > 0) { baritone.bastion.BastionSettings.TARGETS.put("ender_pearl", 999); baritone.bastion.BastionSettings.exitWhenDone = false; }
            if (Integer.getInteger("ostinato.basttest.gold", 0) > 0) for (int g = Integer.getInteger("ostinato.basttest.gold"); g > 0; g -= 64) run("give @p gold_ingot " + Math.min(64, g));
            if (System.getProperty("ostinato.basttest.budget") != null) baritone.bastion.BastionSettings.timeBudget = Integer.getInteger("ostinato.basttest.budget");
            baritone.api.BaritoneAPI.getSettings().chatDebug.value = true;
            say("BAST setup done; starting #bastion " + VARIANT);
            b.getCommandManager().execute("bastion " + VARIANT);
            state = 2; ticks = 0;
            return;
        }
        if (state == 2) monitor(mc);
    }

    private void monitor(Minecraft mc) {
        ClientPlayerEntity p = mc.player;
        String st = b.getBastionProcess().status();
        if (p.getHealth() <= 0) { say("BAST END died t=" + ticks + " st=" + st + " pearls=" + p.inventory.count(net.minecraft.item.Items.ENDER_PEARL)); state = 9; mc.execute(mc::shutdown); return; }
        if (ticks % 20 == 0) {
            List<LivingEntity> near = mc.world.getEntitiesWithinAABB(LivingEntity.class, p.getBoundingBox().grow(16), x -> x != p);
            List<String> ns = new ArrayList<>();
            for (LivingEntity x : near) ns.add(x.getType().getName().getString() + "@" + (int) p.getDistance(x));
            System.out.println("BAST dbg t=" + ticks + " hp=" + p.getHealth() + " st=" + st + " food=" + p.getFoodStats().getFoodLevel() + " lava=" + p.isInLava()
                    + " fire=" + p.isBurning() + " pos=" + p.getPosition().getCoordinatesAsString() + " near=" + ns);
        }
        if (ticks % 60 == 0) say("BAST t=" + ticks + " hp=" + (int) p.getHealth() + " " + st + " pos=" + p.getPosition().getCoordinatesAsString());
        boolean ended = !b.getBastionProcess().isActive() && ticks > 40;
        int carried = 0;
        for (int i = 0; i < p.inventory.getSizeInventory(); i++) carried += p.inventory.getStackInSlot(i).getCount();
        if (stallAt == null || stallTick > ticks || p.getPositionVec().distanceTo(stallAt) > 3 || carried != stallCarried) { stallAt = p.getPositionVec(); stallTick = ticks; stallCarried = carried; }
        boolean stalled = ticks - stallTick > Integer.getInteger("ostinato.basttest.stallticks", 3600);
        if (stalled) say("BAST STALL t=" + ticks + " since=" + stallTick + " pos=" + p.getPosition().getCoordinatesAsString() + " st=" + st);
        if (ticks >= Integer.getInteger("ostinato.basttest.maxticks", Math.max(14000, (Integer.getInteger("ostinato.basttest.budget", 0) + 120) * 20)) || ended || stalled) {
            Map<String, Integer> m = new TreeMap<>();
            for (int i = 0; i < 36; i++) { ItemStack s = p.inventory.getStackInSlot(i); if (!s.isEmpty()) m.merge(baritone.process.BastionProcess.itemIdPublic(s), s.getCount(), Integer::sum); }
            List<DetectedStructure> d = b.getStructureBehavior().find("bastion_remnant");
            say("BAST END t=" + ticks + " ended=" + ended + " hp=" + p.getHealth() + " layout=" + (d.isEmpty() ? "?" : d.get(0).variant) + " st=" + st + " inv=" + m);
            state = 9; mc.execute(mc::shutdown);
        }
    }

    /** /locate from a widening grid; the variant comes from the start piece, read on the server once its chunk has its starts. */
    private BlockPos findBastion(Minecraft mc) {
        MinecraftServer sv = mc.getIntegratedServer();
        ServerWorld nether = sv.getWorld(NETHER);
        String want = VARIANT.equals("housing") ? "units" : VARIANT.equals("stables") ? "hoglin_stable" : VARIANT;
        Set<BlockPos> seen = new HashSet<>();
        for (int ring = 0; ring <= 8; ring++) for (int i = -ring; i <= ring; i++) for (int j = -ring; j <= ring; j++) {
            if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
            List<String> out = run("execute in minecraft:the_nether positioned " + (i * 600) + " 64 " + (j * 600) + " run locate bastion_remnant");
            BlockPos at = null;
            for (String o : out) { Matcher m = LOC.matcher(o); if (m.find()) at = new BlockPos(Integer.parseInt(m.group(1)), 64, Integer.parseInt(m.group(2))); }
            if (at == null || !seen.add(at)) continue;
            if (want.equals("any")) return at;
            final BlockPos fat = at;
            String[] piece = new String[1];
            int[] ys = new int[1];
            sv.runAsync(() -> {
                net.minecraft.world.chunk.IChunk c = nether.getChunk(fat.getX() >> 4, fat.getZ() >> 4, net.minecraft.world.chunk.ChunkStatus.STRUCTURE_STARTS);
                for (Map.Entry<Structure<?>, StructureStart<?>> e : c.getStructureStarts().entrySet()) {
                    StructureStart<?> s = e.getValue();
                    if (!s.isValid() || s.getComponents().isEmpty() || !String.valueOf(e.getKey().getStructureName()).toLowerCase(java.util.Locale.ROOT).contains("bastion")) continue;
                    piece[0] = ServerStructureSource.pieceName(s.getComponents().get(0));
                    ys[0] = (s.getBoundingBox().minY + s.getBoundingBox().maxY) / 2;
                }
            }).join();
            System.out.println("BAST candidate " + at.getCoordinatesAsString() + " " + piece[0]);
            if (piece[0] != null && piece[0].contains("bastion/" + want)) return new BlockPos(at.getX(), ys[0], at.getZ());
        }
        return null;
    }

    /** Lava-free open netherrack 45-105 out with a walkable way in (ported from the 26.3 harness, runs 34/82/104/110). */
    private BlockPos startSpot(ServerWorld lvl, BlockPos target) {
        BlockPos[] spot = new BlockPos[3];
        Minecraft.getInstance().getIntegratedServer().runAsync(() -> {
            for (int half : new int[]{3, 1, 0}) for (int side = 0; side < 4 && spot[0] == null; side++) for (int r = 0; r < 60 && spot[0] == null; r++) for (int dz = -r; dz <= r && spot[0] == null; dz++) {
                int ax = 45 + r, x = target.getX() + (side == 0 ? ax : side == 1 ? -ax : dz), z = target.getZ() + (side == 2 ? ax : side == 3 ? -ax : dz);
                lvl.getChunk(x >> 4, z >> 4);
                for (int y = 110; y > 40; y--) {
                    BlockPos q = new BlockPos(x, y, z);
                    boolean open = true;
                    for (int ox = -half; ox <= half && open; ox++) for (int oz = -half; oz <= half && open; oz++) {
                        BlockPos f = q.add(ox, 0, oz);
                        open = lvl.getBlockState(f).getBlock() == Blocks.NETHERRACK && lvl.getBlockState(f.up()).isAir() && lvl.getBlockState(f.up(2)).isAir() && lvl.getBlockState(f.up(3)).isAir();
                    }
                    if (open && spot[1] == null) spot[1] = q.up();
                    for (int lx = -8; lx <= 8 && open; lx++) for (int ly = -3; ly <= 6 && open; ly++) for (int lz = -8; lz <= 8 && open; lz++)
                        if (lvl.getFluidState(q.add(lx, ly, lz)).isTagged(FluidTags.LAVA)) open = false;
                    if (open) {
                        final BlockPos qq = q;
                        LavaFlow.Grid g = new LavaFlow.Grid() {
                            public int lava(int x, int y, int z) { FluidState fs = lvl.getFluidState(new BlockPos(x, y, z)); return fs.isTagged(FluidTags.LAVA) ? (fs.isSource() ? 0 : Math.max(0, 8 - fs.getLevel())) : -1; }
                            public boolean open(int x, int y, int z) { net.minecraft.block.BlockState s = lvl.getBlockState(new BlockPos(x, y, z)); return s.getFluidState().isEmpty() && (s.isAir() || s.getMaterial().isReplaceable()); }
                        };
                        for (long k : LavaFlow.forecast(g, qq.getX(), qq.getY(), qq.getZ(), 12, 200).keySet()) {
                            int ky = (int) (k << 52 >> 52), kx = (int) (k >> 38), kz = (int) (k << 26 >> 38);
                            if (Math.abs(kx - qq.getX()) <= 4 && Math.abs(kz - qq.getZ()) <= 4 && Math.abs(ky - qq.getY()) <= 3) { open = false; break; }
                        }
                    }
                    if (open && spot[2] == null) spot[2] = q.up();
                    if (open && connected(lvl, q.up(), target)) { spot[0] = q.up(); break; }
                    if (open) break;
                }
            }
        }).join();
        return spot[0] != null ? spot[0] : spot[2] != null ? spot[2] : spot[1];
    }

    private static boolean connected(ServerWorld lvl, BlockPos start, BlockPos target) {
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        HashSet<BlockPos> seen = new HashSet<>();
        q.add(start); seen.add(start);
        while (!q.isEmpty() && seen.size() < 40000) {
            BlockPos p = q.poll();
            if (Math.abs(p.getX() - target.getX()) <= 10 && Math.abs(p.getZ() - target.getZ()) <= 10 && (target.getY() <= 0 || Math.abs(p.getY() - target.getY()) <= 20)) return true;
            for (Direction d : Direction.Plane.HORIZONTAL) for (int dy = 1; dy >= -3; dy--) {
                BlockPos n = p.offset(d).up(dy);
                if (!stand(lvl, n)) continue;
                if (dy == 1 && !lvl.getBlockState(p.up(2)).getCollisionShape(lvl, p.up(2)).isEmpty()) break;
                if (dy < 0) { boolean clear = true; for (int k = 0; k > dy; k--) if (!lvl.getBlockState(p.offset(d).up(k + 1)).getCollisionShape(lvl, p).isEmpty()) clear = false; if (!clear) break; }
                if (seen.add(n)) q.add(n);
                break;
            }
        }
        return false;
    }

    private static boolean stand(ServerWorld lvl, BlockPos f) {
        if (lvl.getBlockState(f.down()).getCollisionShape(lvl, f.down()).isEmpty()) return false;
        if (!lvl.getBlockState(f).getCollisionShape(lvl, f).isEmpty() || !lvl.getBlockState(f.up()).getCollisionShape(lvl, f.up()).isEmpty()) return false;
        for (Direction d : Direction.values()) if (lvl.getFluidState(f.offset(d)).isTagged(FluidTags.LAVA)) return false;
        return !lvl.getFluidState(f).isTagged(FluidTags.LAVA);
    }

}
