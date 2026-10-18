/*
 * Ostinato: new file (spectator learning bench). Not part of upstream Baritone.
 */
package baritone.spectate;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Ostinato: local bench for spectator learning and the health estimator ({@code -Dostinato.spectbench=<rounds>},
 * single player only). We go into spectator mode beside two VexBots set on each other; every server tick the bots'
 * true inputs (the movement impulse the server ran them with, VexBot's commanded keys, jump, sprint, sneak) and
 * health are written to {@code spectbench/truth.csv} from the integrated server's own thread, while the spectator
 * decodes them from packets ({@code -Dostinato.spectate.decodeLog}) and the estimator logs its estimates
 * ({@code -Dostinato.health.log}). {@code tools/spectbench.py} matches the two by position.
 */
public final class SpectateBench implements AbstractGameEventListener {
    private static final int ROUNDS = Integer.getInteger("ostinato.spectbench", 0);
    private static final int ROUND_TICKS = Integer.getInteger("ostinato.spectbench.ticks", 20 * 90);
    /** {@code carpet} (Carpet PvP's bots, which gear themselves for their mode) or {@code vexbot}. */
    private static final String BOT = System.getProperty("ostinato.spectbench.bot", "carpet");
    private static final boolean CARPET = BOT.equals("carpet");
    /** Our kits for VexBot; for Carpet PvP its own modes, geared by the mod. */
    private static final String[] KITS = System.getProperty("ostinato.spectbench.kits", CARPET ? "sword,smp,mace,sword" : "sword,pot,sword,potion").split(",");
    private static final String[] DIFFS = System.getProperty("ostinato.spectbench.diffs", CARPET ? "average,skilled,expert,casual" : "medium,hard,expert,easy").split(",");
    private int settle;

    private final Baritone baritone;
    private int wait = 60, round = -1, ticks;
    private String a, b;
    private boolean done, armed;
    private volatile BufferedWriter truth;
    private volatile boolean recording;
    private long serverTick;

    public static void install(Baritone baritone) {
        if (ROUNDS > 0) baritone.getGameEventHandler().registerEventListener(new SpectateBench(baritone));
    }

    private SpectateBench(Baritone baritone) {
        this.baritone = baritone;
    }

    @Override
    public void onTick(TickEvent event) {
        if (done || event.getType() != TickEvent.Type.IN || event.getState() != EventState.PRE) return;
        LocalPlayer me = Minecraft.getInstance().player;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (me == null || server == null) return;
        if (wait > 0 && --wait > 0) return;
        if (round < 0) {
            Baritone.settings().spectateLearn.value = true;
            Baritone.settings().spectateSaveLogs.value = Boolean.getBoolean("ostinato.spectbench.logs");
            run(server, "gamerule spawn_mobs false", "gamerule immediate_respawn true", "gamerule advance_time false", "time set day",
                    "difficulty normal", "kill @e[type=!player]");
            if (!armed) {
                armed = true;
                server.addTickable(() -> record(server));
            }
            next(server);
            return;
        }
        ServerPlayer pa = server.getPlayerList().getPlayerByName(a), pb = server.getPlayerList().getPlayerByName(b);
        if (ticks == 0) {
            if (pa == null || pb == null) {
                if (wait-- < -400) {
                    log("bots never joined: " + a + " " + b);
                    next(server);
                }
                return;
            }
            if (CARPET && settle++ < 30) return; // the mod gears its bot a moment after it joins
            settle = 0;
            String kit = KITS[round % KITS.length];
            String me0 = me.getGameProfile().name();
            run(server, "gamemode spectator " + me0, "tp " + me0 + " 0 ~6 -9 0 30",
                    "tp " + pa.getName().getString() + " 4 ~ 0 90 0", "tp " + pb.getName().getString() + " -4 ~ 0 -90 0");
            if (CARPET) {
                for (String n : new String[]{a, b}) run(server, "effect clear " + n, "player " + n + " ai combat true", "player " + n + " ai autotarget true");
            } else {
                for (ServerPlayer p : new ServerPlayer[]{pa, pb}) {
                    String n = p.getName().getString();
                    run(server, "clear " + n, "effect clear " + n);
                    for (String k : kit(kit)) run(server, "item replace entity " + n + " " + k);
                }
                aggro(server, a, pb.getName().getString());
                aggro(server, b, pa.getName().getString());
            }
            log("round " + round + " kit=" + kit + " diff=" + DIFFS[round % DIFFS.length] + " " + pa.getName().getString() + "(" + pa.getId() + ") vs "
                    + pb.getName().getString() + "(" + pb.getId() + ")");
            recording = true;
        }
        ticks++;
        if (ticks % 200 == 0) {
            for (String s : baritone.getSpectateLearner().status()) log("status " + s);
            healthCost();
        }
        boolean over = pa == null || pb == null || !pa.isAlive() || !pb.isAlive() || ticks > ROUND_TICKS;
        if (over) {
            recording = false;
            log("round " + round + " over after " + ticks + " ticks: " + (pa == null || !pa.isAlive() ? a + " down" : pb == null || !pb.isAlive() ? b + " down" : "time"));
            next(server);
        }
    }

    private void healthCost() {
        var he = baritone.getHealthEstimator();
        log(String.format(Locale.ROOT, "health estimator main %.1f us/tick", he.ticksMeasured == 0 ? 0 : he.nanos / 1e3 / he.ticksMeasured));
    }

    private void next(MinecraftServer server) {
        String me0 = Minecraft.getInstance().player.getGameProfile().name();
        run(server, CARPET ? "kill @a[name=!" + me0 + "]" : "vexbot kill");
        round++;
        ticks = 0;
        if (round >= ROUNDS) {
            finish();
            return;
        }
        a = "vxa" + round;
        b = "vxb" + round;
        String diff = DIFFS[round % DIFFS.length], kit = KITS[round % KITS.length];
        if (CARPET) run(server, "bot spawn " + a + " " + kit + " " + diff + " at 4 ~ 0", "bot spawn " + b + " " + kit + " " + diff + " at -4 ~ 0");
        else run(server, "vexbot difficulty preset " + diff, "vexbot spawn " + a, "vexbot spawn " + b);
        wait = 40;
    }

    private void finish() {
        done = true;
        recording = false;
        for (String s : baritone.getSpectateLearner().status()) log("final " + s);
        healthCost();
        baritone.getSpectateLearner().flush();
        try {
            Thread.sleep(500);
            if (truth != null) truth.close();
        } catch (Exception ignored) {}
        log("SUMMARY done");
        if (Boolean.getBoolean("ostinato.spectbench.exit")) Minecraft.getInstance().stop();
    }

    /** Server thread, every tick: the bots' true inputs and health. */
    private void record(MinecraftServer server) {
        serverTick++;
        if (!recording) return;
        try {
            if (truth == null) {
                Path dir = Paths.get("spectbench");
                Files.createDirectories(dir);
                truth = Files.newBufferedWriter(dir.resolve("truth.csv"));
                truth.write("stick,round,id,name,x,y,z,yaw,pitch,xxa,zza,cmdF,cmdS,jumping,sprint,shift,using,onGround,hp,abs,hurtTime\n");
            }
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                String n = p.getName().getString();
                if (!n.equalsIgnoreCase(a) && !n.equalsIgnoreCase(b)) continue;
                float cf = Float.NaN, cs = Float.NaN;
                Object pack = actionPack(p);
                if (pack != null) {
                    cf = (Float) field(pack, "forward");
                    cs = (Float) field(pack, "strafing");
                }
                boolean jumping = (Boolean) field(p, "jumping");
                Vec3 v = p.position();
                truth.write(String.format(Locale.ROOT, "%d,%d,%d,%s,%.6f,%.6f,%.6f,%.2f,%.2f,%.4f,%.4f,%.3f,%.3f,%d,%d,%d,%d,%d,%.3f,%.3f,%d%n",
                        serverTick, round, p.getId(), n, v.x, v.y, v.z, p.getYRot(), p.getXRot(), p.xxa, p.zza, cf, cs, jumping ? 1 : 0,
                        p.isSprinting() ? 1 : 0, p.isShiftKeyDown() ? 1 : 0, p.isUsingItem() ? 1 : 0, p.onGround() ? 1 : 0, p.getHealth(),
                        p.getAbsorptionAmount(), p.hurtTime));
            }
            if (serverTick % 100 == 0) truth.flush();
        } catch (IOException e) {
            log("truth: " + e);
        }
    }

    private static Object actionPack(ServerPlayer p) {
        try {
            Method m = p.getClass().getMethod("getActionPack");
            return m.invoke(p);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException ignored) {
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static void aggro(MinecraftServer server, String bot, String target) {
        try {
            Class<?> bm = Class.forName("vexbot.bot.BotManager");
            String canon = (String) bm.getMethod("resolveCanonicalBotName", String.class).invoke(null, bot);
            if (canon == null) throw new IllegalStateException("not tracked: " + bot);
            server.submit(() -> {
                try {
                    bm.getMethod("setAttackTarget", String.class, String.class).invoke(null, canon, target);
                } catch (Exception e) {
                    log("aggro failed: " + e);
                }
            }).join();
            log("aggro " + canon + " -> " + target);
        } catch (Exception e) {
            log("aggro fallback: " + e);
            run(server, "vexbot attack " + target + " " + bot);
        }
    }

    private static String[] kit(String name) {
        String heal = "splash_potion[potion_contents={potion:\"minecraft:strong_healing\"}]";
        String[] armor = {"armor.head with diamond_helmet", "armor.chest with diamond_chestplate", "armor.legs with diamond_leggings", "armor.feet with diamond_boots"};
        return switch (name) {
            case "pot" -> concat(armor, "hotbar.0 with diamond_sword", "hotbar.1 with " + heal + " 1", "hotbar.2 with " + heal + " 1", "hotbar.3 with " + heal + " 1",
                    "hotbar.4 with " + heal + " 1", "hotbar.5 with " + heal + " 1", "hotbar.6 with " + heal + " 1");
            case "potion" -> concat(armor, "weapon.offhand with shield", "hotbar.0 with diamond_sword",
                    "hotbar.1 with splash_potion[potion_contents={potion:\"minecraft:strong_harming\"}] 8", "hotbar.2 with " + heal + " 8", "hotbar.3 with golden_apple 8");
            case "netherite" -> concat(new String[]{"armor.head with netherite_helmet[enchantments={\"minecraft:protection\":4}]",
                    "armor.chest with netherite_chestplate[enchantments={\"minecraft:protection\":4}]",
                    "armor.legs with netherite_leggings[enchantments={\"minecraft:protection\":4}]",
                    "armor.feet with netherite_boots[enchantments={\"minecraft:protection\":4}]"},
                    "hotbar.0 with netherite_sword[enchantments={\"minecraft:sharpness\":5}]", "hotbar.1 with golden_apple 8", "weapon.offhand with totem_of_undying");
            default -> new String[]{"armor.head with iron_helmet", "armor.chest with iron_chestplate", "armor.legs with iron_leggings",
                    "armor.feet with iron_boots", "weapon.offhand with shield", "hotbar.0 with diamond_sword", "hotbar.1 with diamond_axe",
                    "hotbar.2 with golden_apple 3", "hotbar.3 with cooked_beef 16"};
        };
    }

    private static String[] concat(String[] a, String... b) {
        String[] k = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, k, a.length, b.length);
        return k;
    }

    private static void run(MinecraftServer server, String... cmds) {
        server.submit(() -> {
            var src = server.createCommandSourceStack();
            ServerPlayer p = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
            if (p != null) src = src.withPosition(new Vec3(0, p.getY(), 0)).withLevel(p.level());
            for (String c : cmds) server.getCommands().performPrefixedCommand(src, c);
        }).join();
    }

    private static void log(String s) {
        System.out.println("SPECTBENCH " + s);
    }
}
