package baritone.structure;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

/** TEMP live harness: bastion + piglins + a brute, then #bastion. Never committed. */
public final class BastTest implements AbstractGameEventListener {
    private final Baritone b;
    private int state, wait, ticks;
    private BlockPos target;
    private static final boolean REAL = System.getenv("BAST_REAL") != null;
    private int duelSummons;
    private static final String VARIANT = System.getProperty("ostinato.basttest.variant", "any");

    private BastTest(Baritone b) { this.b = b; }

    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.basttest")) b.getGameEventHandler().registerEventListener(new BastTest(b));
    }

    private void say(String m) {
        System.out.println(m);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.execute(() -> mc.gui.getChat().addMessage(net.minecraft.network.chat.Component.literal(m)));
    }

    private void run(String cmd) {
        MinecraftServer s = Minecraft.getInstance().getSingleplayerServer();
        s.submit(() -> {
            var p = s.getPlayerList().getPlayers().get(0);
            var src = s.createCommandSourceStack().withPosition(p.position()).withLevel(p.level());
            s.getCommands().performPrefixedCommand(src, cmd);
        }).join();
    }

    private String moveDesc() {
        try {
            var cur = b.getPathingBehavior().getCurrent();
            if (cur == null) return "none";
            var m = cur.getPath().movements().get(cur.getPosition());
            var d = m.getDest();
            var bs = Minecraft.getInstance().level.getBlockState(new BlockPos(d.x, d.y, d.z));
            var bs1 = Minecraft.getInstance().level.getBlockState(new BlockPos(d.x, d.y + 1, d.z));
            var bsb = Minecraft.getInstance().level.getBlockState(new BlockPos(d.x, d.y - 1, d.z));
            return m.getClass().getSimpleName() + "@" + cur.getPosition() + "/" + cur.getPath().movements().size()
                    + "->" + d.x + "," + d.y + "," + d.z + "[" + bs.getBlock().getDescriptionId().replace("block.minecraft.", "") + "," + bs1.getBlock().getDescriptionId().replace("block.minecraft.", "") + "," + bsb.getBlock().getDescriptionId().replace("block.minecraft.", "") + "]";
        } catch (Throwable t) { return "?"; }
    }

    @Override
    public void onTick(TickEvent e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getSingleplayerServer() == null) {
            if (state == 0 && mc.screen instanceof TitleScreen) {
                state = 1;
                say("BAST creating world");
                CreateWorldScreen.openFresh(mc, () -> {});
                wait = 80;
            } else if (state == 1 && --wait <= 0 && mc.screen instanceof CreateWorldScreen) {
                for (var c : mc.screen.children()) {
                    if (c instanceof Button bt && bt.getMessage().getString().contains("Create New World")) {
                        state = 2;
                        bt.onPress(new net.minecraft.client.input.InputWithModifiers() {
                            public int input() { return 0; }
                            public int modifiers() { return 0; }
                        });
                    }
                }
            }
            return;
        }
        if (e.getType() != TickEvent.Type.IN) return;
        if (state == 2) {
            if (--wait > -200) return;
            run("gamemode creative @p");
            run("gamerule advance_time false");
            run("gamerule spawn_mobs false");
            run("gamerule send_command_feedback false");
            MinecraftServer sv = mc.getSingleplayerServer();
            BlockPos[] found = new BlockPos[1];
            sv.submit(() -> {
                var level = sv.getLevel(net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("the_nether")));
                var reg = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
                var h = reg.get(Identifier.withDefaultNamespace("bastion_remnant"));
                // -Dostinato.basttest.variant=bridge|treasure|housing|stables: walk outwards until a bastion of that type turns up
                String want = VARIANT.equals("housing") ? "units" : VARIANT.equals("stables") ? "hoglin_stable" : VARIANT;
                java.util.Set<BlockPos> seen = new java.util.HashSet<>();
                outer:
                for (int ring = 0; ring <= 12; ring++) for (int i = -ring; i <= ring; i++) for (int j = -ring; j <= ring; j++) {
                    if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                    var r = level.getChunkSource().getGenerator().findNearestMapStructure(level, HolderSet.direct(h.get()), new BlockPos(i * 600, 64, j * 600), 20, false);
                    if (r == null || !seen.add(r.getFirst())) continue;
                    BlockPos at = r.getFirst();
                    if (want.equals("any")) { found[0] = at; break outer; }
                    var chunk = level.getChunk(at.getX() >> 4, at.getZ() >> 4, net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS);
                    var start = level.structureManager().getStartForStructure(net.minecraft.core.SectionPos.of(at), h.get().value(), chunk);
                    if (start == null || start.getPieces().isEmpty()) continue;
                    String piece = String.valueOf(start.getPieces().get(0) instanceof net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece pe ? pe.getElement() : start.getPieces().get(0));
                    System.out.println("BAST candidate " + at + " " + piece);
                    if (piece.contains("bastion/" + want)) { found[0] = at; break outer; }
                }
            }).join();
            target = found[0];
            say("BAST target " + target);
            if (REAL) {
                run("execute in minecraft:the_nether run forceload add " + (target.getX() + 90) + " " + target.getZ());
                BlockPos[] spot = new BlockPos[2];
                MinecraftServer sv2 = mc.getSingleplayerServer();
                sv2.submit(() -> {
                    var lvl = sv2.getLevel(net.minecraft.resources.ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("the_nether")));
                    for (int half : new int[]{3, 1, 0}) for (int r = 0; r < 60 && spot[0] == null; r++) for (int dz = -r; dz <= r && spot[0] == null; dz++) {
                        int x = target.getX() + 45 + r /* runs 74, 77: 90 out the spawn was often cut off from the bastion */, z = target.getZ() + dz;
                        lvl.getChunk(x >> 4, z >> 4);
                        for (int y = 110; y > 40; y--) {
                            BlockPos q = new BlockPos(x, y, z);
                            boolean open = true;
                            for (int ox = -half; ox <= half && open; ox++) for (int oz = -half; oz <= half && open; oz++) {
                                BlockPos f = q.offset(ox, 0, oz);
                                open = lvl.getBlockState(f).getBlock() == net.minecraft.world.level.block.Blocks.NETHERRACK && lvl.getBlockState(f.above()).isAir() && lvl.getBlockState(f.above(2)).isAir() && lvl.getBlockState(f.above(3)).isAir();
                            }
                            if (open && spot[1] == null) spot[1] = q.above();
                            // no lava within 8 (runs 34 and lava7 started next to a lavafall and burned before the test began)
                            for (int lx = -8; lx <= 8 && open; lx++) for (int ly = -3; ly <= 6 && open; ly++) for (int lz = -8; lz <= 8 && open; lz++)
                                if (lvl.getFluidState(q.offset(lx, ly, lz)).is(net.minecraft.tags.FluidTags.LAVA)) open = false;
                            if (open) { spot[0] = q.above(); break; }
                        }
                    }
                }).join();
                if (spot[0] == null) spot[0] = spot[1]; // no lava-free spot in range (lava9 crashed on null): take any open one
                say("BAST start " + spot[0]);
                run("execute as @p in minecraft:the_nether run tp @p " + spot[0].getX() + " " + spot[0].getY() + " " + spot[0].getZ());
            } else run("execute as @p in minecraft:the_nether run tp @p " + target.getX() + " 80 " + target.getZ());
            b.getStructureBehavior().setMode(StructureBehavior.Mode.BOTH);
            state = 3;
            ticks = 0;
            return;
        }
        if (state == 3) {
            ticks++;
            if (ticks == 1 && !REAL) {
                // flat stone arena well above the structure so the trade/trap logic is tested without terrain or lava
                run("execute in minecraft:the_nether run forceload add " + target.getX() + " " + target.getZ());
                run("execute in minecraft:the_nether run fill " + (target.getX() - 40) + " 200 " + (target.getZ() - 40) + " " + (target.getX() + 40) + " 200 " + (target.getZ() + 40) + " stone");
                run("execute as @p in minecraft:the_nether run tp @p " + target.getX() + " 201 " + target.getZ());
            }
            if (ticks < 260) return;
            run("clear @p");
            run("give @p iron_pickaxe");
            if (!REAL) {
            run("execute at @p run fill ~-12 ~ ~-12 ~12 ~5 ~12 air replace lava");
            run("execute at @p run fill ~-10 ~-1 ~-10 ~10 ~-1 ~10 stone");
            run("execute at @p run fill ~3 ~-5 ~-1 ~7 ~-1 ~1 stone");
            run("execute at @p run fill ~5 ~-3 ~0 ~5 ~-1 ~0 air");
            run("execute at @p run setblock ~-8 ~ ~0 gold_block");
            run("execute at @p run setblock ~-8 ~ ~2 gold_block");
            }
            run("item replace entity @p armor.feet with golden_boots");
            run("item replace entity @p armor.head with golden_helmet");
            run("item replace entity @p armor.chest with iron_chestplate");
            run("item replace entity @p armor.legs with iron_leggings");
            run("item replace entity @p weapon.offhand with shield");
            run("give @p diamond_sword");
            run("give @p cooked_beef 16");
            run("give @p gold_ingot 24"); run("give @p gold_block 2"); run("give @p netherrack 64"); run("give @p soul_sand 32"); run("item replace entity @p armor.legs with golden_leggings");
            if (Boolean.getBoolean("ostinato.basttest.lava")) run("give @p oak_door 6"); run("give @p golden_apple 2");
            run("give @p lava_bucket");
            run("give @p bucket");
            run("gamemode survival @p");
            if (!REAL) {
            run("execute at @p run summon piglin ~4 ~ ~3");
            run("execute at @p run summon piglin ~-4 ~ ~3");
            run("execute at @p run summon piglin_brute ~3 ~ ~-6");
            }
            if (Boolean.getBoolean("ostinato.basttest.duel")) { run("give @p iron_axe"); run("item replace entity @p weapon.offhand with shield"); run("execute at @p run summon piglin_brute ~5 ~ ~ {Tags:[\"duel\"]}"); }
            if (Boolean.getBoolean("ostinato.basttest.shield")) { run("give @p iron_axe"); run("item replace entity @p weapon.offhand with shield"); }
            say("BAST setup done; starting #bastion " + VARIANT);
            if (System.getProperty("ostinato.basttest.budget") != null) baritone.bastion.BastionSettings.timeBudget = Integer.getInteger("ostinato.basttest.budget");
            b.getCommandManager().execute("bastion " + VARIANT);
            state = 4;
            ticks = 0;
            return;
        }
        if (state == 4) {
            ticks++;
            if (Boolean.getBoolean("ostinato.basttest.lava") && ticks == 100) {
                // deep lava pool around us, netherrack floor 3 below, shore 6+ blocks away
                run("execute at @p run fill ~-6 ~-9 ~-6 ~6 ~-4 ~6 netherrack");
                run("execute at @p run fill ~-6 ~-3 ~-6 ~6 ~1 ~6 lava");
                run("execute at @p run tp @p ~ ~-2 ~");
                say("BAST lava test: dropped into deep lava");
            }
            if (Boolean.getBoolean("ostinato.basttest.lava") && ticks == 900) { say("BAST END lava test hp=" + mc.player.getHealth() + " fire=" + mc.player.isOnFire() + " inLava=" + mc.player.isInLava() + " st=" + b.getBastionProcess().status()); state = 9; mc.execute(mc::stop); return; }
            if (Boolean.getBoolean("ostinato.basttest.duel") && state > 0 && ticks % 300 == 150 && duelSummons < 4) { duelSummons++; run("execute unless entity @e[type=minecraft:piglin_brute,tag=duel] at @p run summon piglin_brute ^ ^ ^4 {Tags:[\"duel\"]}"); }
        if (Boolean.getBoolean("ostinato.basttest.duel") && ticks % 20 == 1) run("execute in minecraft:the_nether run kill @e[type=minecraft:piglin_brute,tag=!duel]");
        if (Boolean.getBoolean("ostinato.basttest.nobrutes") && ticks % 20 == 1) run("execute in minecraft:the_nether run kill @e[type=minecraft:piglin_brute]"); // 1.16.1 has no brutes
            if (mc.player.getHealth() <= 0) { say("BAST END died t=" + ticks + " st=" + b.getBastionProcess().status()); state = 9; mc.execute(mc::stop); return; }
            if ((REAL || ticks <= 120 || b.getBastionProcess().status().contains("perch") || b.getBastionProcess().status().contains("eating") || mc.player.getHealth() < 20 || mc.player.isInLava()) && ticks % 3 == 0) {
                var br = mc.level.getEntitiesOfClass(net.minecraft.world.entity.monster.piglin.PiglinBrute.class, mc.player.getBoundingBox().inflate(40));
                System.out.println("BAST dbg t=" + ticks + " hp=" + mc.player.getHealth() + " st=" + b.getBastionProcess().status()
                        + " y=" + String.format("%.2f", mc.player.getY()) + " gnd=" + mc.player.onGround()
                        + " food=" + mc.player.getFoodData().getFoodLevel() + " lava=" + mc.player.isInLava() + " vc=" + mc.player.verticalCollision + " feetB=" + mc.level.getBlockState(mc.player.blockPosition()).getBlock() + " b2=" + mc.level.getBlockState(mc.player.blockPosition().below(2)).getBlock() + " head=" + mc.level.getBlockState(mc.player.blockPosition().above(2)).getBlock() + " vy=" + mc.player.getDeltaMovement().y + " using=" + mc.player.isUsingItem() + " sel=" + mc.player.getInventory().getSelectedSlot() + " fire=" + mc.player.isOnFire() + " held=" + mc.player.getMainHandItem().getItem() + " below=" + mc.level.getBlockState(mc.player.blockPosition().below()).getBlock()
                        + " dec=" + b.getPveProcess().lastDecision()
                        + " pos=" + mc.player.blockPosition().toShortString() + " fd=" + String.format("%.1f", mc.player.fallDistance)
                        + " mv=" + moveDesc()
                        + " brutes=" + br.size() + " nearest=" + br.stream().mapToDouble(x -> mc.player.distanceTo(x)).min().orElse(-1)
                        + " near=" + mc.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class, mc.player.getBoundingBox().inflate(16), x -> x != mc.player)
                                .stream().map(x -> x.getType().toShortString() + "@" + (int) mc.player.distanceTo(x)).toList());
            }
            if (ticks % 60 == 0) {
                var p = mc.player;
                say("BAST t=" + ticks + " hp=" + (int) p.getHealth() + " " + b.getBastionProcess().status() + " pos=" + p.blockPosition().toShortString());
            }
            boolean ended = !b.getBastionProcess().isActive() && ticks > 40;
            if (ticks >= 12000 || ended) {
                var inv = mc.player.getInventory(); java.util.Map<String,Integer> m = new java.util.TreeMap<>();
                for (int i = 0; i < 36; i++) { var s = inv.getItem(i); if (!s.isEmpty()) m.merge(baritone.process.BastionProcess.itemIdPublic(s), s.getCount(), Integer::sum); }
                var d = b.getStructureBehavior().find("bastion_remnant");
                say("BAST END t=" + ticks + " ended=" + ended + " hp=" + mc.player.getHealth() + " layout=" + (d.isEmpty() ? "?" : d.get(0).variant) + " st=" + b.getBastionProcess().status() + " inv=" + m);
                state = 9; mc.execute(mc::stop);
            }
        }
    }
}
