package baritone.process;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Singleplayer duel bench against VexBot (server-side PvP bot mod): each round spawns one VexBot
 * at the next difficulty, gives both sides the same kit and runs {@link PvpProcess} on it until
 * one dies. Enabled with -Dostinato.vexbench=N (rounds); prints "VEXBENCH" lines to stdout and
 * writes pvpbench/vexbench.csv. The spawn/difficulty commands can be overridden with
 * -Dostinato.vex.spawn / -Dostinato.vex.difficulty ({name}/{diff} placeholders).
 */
public final class VexBench implements AbstractGameEventListener {

    private static final String[] DIFFS = System.getProperty("ostinato.vex.diffs", "easy,medium,hard,expert,perfect").split(",");
    // -Dostinato.vex.styles=aggressive,safe,... cycles VexBot's play style every full difficulty sweep
    private static final String[] STYLES = System.getProperty("ostinato.vex.styles", "default").split(",");
    private static final int ROUND_TICKS = 20 * 90;
    private static final String[] SWORD_KIT = {
            "armor.head with iron_helmet", "armor.chest with iron_chestplate", "armor.legs with iron_leggings",
            "armor.feet with iron_boots", "weapon.offhand with shield", "hotbar.0 with diamond_sword",
            "hotbar.1 with diamond_axe", "hotbar.2 with golden_apple 3", "hotbar.3 with cooked_beef 16"};
    private static final String[] CRYSTAL_KIT = {
            "armor.head with diamond_helmet", "armor.chest with diamond_chestplate", "armor.legs with diamond_leggings",
            "armor.feet with diamond_boots", "weapon.offhand with totem_of_undying", "hotbar.0 with diamond_sword",
            "hotbar.1 with obsidian 64", "hotbar.2 with end_crystal 64", "hotbar.3 with golden_apple 16",
            "inventory.0 with totem_of_undying", "inventory.1 with totem_of_undying", "inventory.2 with totem_of_undying"};
    private static final String[] ANCHOR_KIT = {
            "armor.head with diamond_helmet", "armor.chest with diamond_chestplate", "armor.legs with diamond_leggings",
            "armor.feet with diamond_boots", "weapon.offhand with totem_of_undying", "hotbar.0 with diamond_sword",
            "hotbar.1 with respawn_anchor 32", "hotbar.2 with glowstone 64", "hotbar.3 with golden_apple 16", "hotbar.4 with obsidian 16",
            "inventory.0 with totem_of_undying", "inventory.1 with totem_of_undying", "inventory.2 with totem_of_undying"};
    // -Dostinato.vex.kit=sword|crystal|anchor picks which of VexBot's combat modes the round exercises
    private static final String[] KIT = switch (System.getProperty("ostinato.vex.kit", "sword")) {
        case "crystal" -> CRYSTAL_KIT;
        case "anchor" -> ANCHOR_KIT;
        default -> SWORD_KIT;
    };

    private final Baritone baritone;
    private final int rounds;
    private int round = -1, ticks, wait = 100;
    private String bot;
    private boolean seen, done, setup;
    private float botDmg;
    private int wins;
    private final List<String> rows = new ArrayList<>();

    private VexBench(Baritone baritone, int rounds) {
        this.baritone = baritone;
        this.rounds = rounds;
    }

    public static void install(Baritone baritone) {
        int n = Integer.getInteger("ostinato.vexbench", 0);
        if (n > 0) baritone.getGameEventHandler().registerEventListener(new VexBench(baritone, n));
    }

    @Override
    public void onTick(TickEvent event) {
        if (done || event.getType() != TickEvent.Type.IN) return;
        LocalPlayer me = Minecraft.getInstance().player;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (me == null || server == null) return;
        if (me.isDeadOrDying() && wait > 0) { // round over: respawn before the next one starts
            me.respawn();
            wait = 20;
            return;
        }
        if (wait > 0) {
            if (--wait == 0 && setup) {
                setup();
                return;
            }
            if (wait == 0) {
                if (round < 0) {
                    run("gamerule doMobSpawning false", "gamerule doImmediateRespawn true", "gamerule doDaylightCycle false",
                            "time set day", "difficulty normal", "gamemode survival @a", "kill @e[type=!player]");
                    next();
                } else {
                    run("tp " + bot + " 8 ~ 0 90 0", "clear " + bot);
                    for (String k : KIT) run("item replace entity " + bot + " " + k);
                    if (!"default".equals(style()))
                        run(System.getProperty("ostinato.vex.style", "vexbot playstyle {style} {name}").replace("{style}", style()).replace("{name}", bot));
                    baritone.getPvpProcess().attackPlayer(bot);
                }
            }
            return;
        }
        ticks++;
        ServerPlayer b = server.getPlayerList().getPlayerByName(bot);
        if (b != null && b.isAlive()) {
            seen = true;
            botDmg = b.getMaxHealth() - b.getHealth();
        }
        boolean dead = me.isDeadOrDying();
        boolean botDead = seen && (b == null || !b.isAlive());
        if (!seen && ticks > 100) {
            log("no bot named " + bot + " spawned; check -Dostinato.vex.spawn");
            finish();
            return;
        }
        if (dead || botDead || ticks >= ROUND_TICKS) {
            PvpProcess p = baritone.getPvpProcess();
            String result = dead ? "death" : botDead ? "win" : "timeout";
            if (botDead && !dead) wins++;
            String row = String.format("%d,vexbot_%s_%s,%s,%d,%.1f,%.1f,%d,%d,%d,%d,%d,%d", round, DIFFS[round % DIFFS.length], style(), result, ticks,
                    p.damageTaken, botDmg, p.attacks, p.crits, p.sprintHits, p.axeHits, p.blocks, p.gapples);
            rows.add(row);
            log(row);
            baritone.getPathingControlManager().cancelEverything();
            next();
        }
    }

    private void next() {
        if (bot != null) run(System.getProperty("ostinato.vex.kill", "vexbot kill {name}").replace("{name}", bot));
        if (++round >= rounds) {
            finish();
            return;
        }
        bot = "vex" + round; // VexBot lowercases names
        run("kill @e[type=!player]", "kill @a[name=!" + Minecraft.getInstance().player.getGameProfile().getName() + "]", "kill @e[type=item]",
                // explosive rounds leave craters, anchors and obsidian; rebuild the superflat arena
                "fill -16 -60 -16 16 -50 16 air", "fill -16 -63 -16 16 -62 16 dirt", "fill -16 -61 -16 16 -61 16 grass_block");
        setup = true; // gear up once we're alive again: a kit given to a corpse is lost on respawn
        wait = 20;
        ticks = 0;
        seen = false;
        botDmg = 0;
    }

    private void setup() {
        setup = false;
        run("clear @a", "effect clear @a", "tp @a 0 ~ 0 -90 0",
                "effect give @a instant_health 1 10", "effect give @a saturation 1 10");
        for (String k : KIT) run("item replace entity @a " + k);
        String diff = DIFFS[round % DIFFS.length];

        run(System.getProperty("ostinato.vex.difficulty", "vexbot difficulty preset {diff}").replace("{diff}", diff),
                System.getProperty("ostinato.vex.spawn", "vexbot spawn {name}").replace("{name}", bot));
        wait = 20; // VexBot's fake player joins, then gets geared and teleported
    }

    private String style() {
        return STYLES[round / DIFFS.length % STYLES.length];
    }

    private void finish() {
        done = true;
        try {
            Path dir = Paths.get("pvpbench");
            Files.createDirectories(dir);
            List<String> out = new ArrayList<>();
            out.add("round,opponent,result,ticks,dmgTaken,dmgDealtLastLife,attacks,crits,sprintHits,axeHits,blocks,gapples");
            out.addAll(rows);
            Files.write(dir.resolve("vexbench.csv"), out);
        } catch (Exception e) {
            log("csv: " + e);
        }
        log("SUMMARY wins=" + wins + "/" + rows.size());
        if (Boolean.getBoolean("ostinato.vexbench.exit")) Minecraft.getInstance().stop();
    }

    private static void run(String... cmds) {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
            var src = server.createCommandSourceStack();
            if (p != null) src = src.withPosition(new Vec3(0, p.getY(), 0)).withLevel(p.serverLevel());
            for (String c : String.join(";", cmds).split(";")) { // templates may chain commands with ;
                log("cmd " + c);
                server.getCommands().performPrefixedCommand(src, c);
            }
        }).join();
    }

    private static void log(String s) {
        System.out.println("VEXBENCH " + s);
    }
}
