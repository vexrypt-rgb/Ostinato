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
    private static final String[] DIAMOND = {
            "armor.head with diamond_helmet", "armor.chest with diamond_chestplate", "armor.legs with diamond_leggings",
            "armor.feet with diamond_boots", "weapon.offhand with shield"};

    private static String[] gear(String... extra) {
        String[] k = java.util.Arrays.copyOf(DIAMOND, DIAMOND.length + extra.length);
        System.arraycopy(extra, 0, k, DIAMOND.length, extra.length);
        return k;
    }

    // -Dostinato.vex.kit=sword|crystal|anchor|axe|bow|crossbow|cobweb|potion|mace|elytramace|spear|trident picks which of VexBot's combat modes the round exercises
    private static final String[] KIT = enchant(switch (System.getProperty("ostinato.vex.kit", "sword")) {
        case "crystal" -> CRYSTAL_KIT;
        case "anchor" -> ANCHOR_KIT;
        case "axe" -> gear("hotbar.0 with diamond_axe", "hotbar.1 with golden_apple 8");
        case "bow" -> gear("hotbar.0 with diamond_sword", "hotbar.1 with bow", "hotbar.2 with arrow 64", "hotbar.3 with golden_apple 8");
        case "deflect" -> gear("hotbar.0 with diamond_sword", "hotbar.1 with bow", "hotbar.2 with arrow 64", "hotbar.3 with wind_charge 64", "hotbar.4 with golden_apple 8");
        case "crossbow" -> gear("hotbar.0 with diamond_sword", "hotbar.1 with crossbow", "hotbar.2 with arrow 64", "hotbar.3 with golden_apple 8");
        case "cobweb" -> gear("hotbar.0 with diamond_sword", "hotbar.1 with cobweb 64", "hotbar.2 with golden_apple 8");
        case "potion" -> gear("hotbar.0 with diamond_sword", "hotbar.1 with splash_potion[potion_contents={potion:\"minecraft:strong_harming\"}] 8",
                "hotbar.2 with splash_potion[potion_contents={potion:\"minecraft:strong_healing\"}] 8", "hotbar.3 with golden_apple 8");
        case "fire" -> gear("hotbar.0 with diamond_sword", "hotbar.1 with bow", "hotbar.2 with arrow 64", "hotbar.3 with soul_sand 16",
                "hotbar.4 with flint_and_steel", "hotbar.5 with golden_apple 8");
        case "pillar" -> gear("hotbar.0 with diamond_sword", "hotbar.1 with cobblestone 64", "hotbar.2 with ender_pearl 4");
        case "pearlmace" -> gear("hotbar.0 with mace", "hotbar.1 with wind_charge 64", "hotbar.2 with ender_pearl 16", "hotbar.3 with golden_apple 8");
        case "mace" -> gear("hotbar.0 with mace", "hotbar.1 with wind_charge 64", "hotbar.2 with diamond_sword", "hotbar.3 with golden_apple 8");
        case "elytramace" -> new String[]{"armor.head with diamond_helmet", "armor.chest with elytra", "armor.legs with diamond_leggings",
                "armor.feet with diamond_boots", "weapon.offhand with shield", "hotbar.0 with mace", "hotbar.1 with wind_charge 64",
                "hotbar.2 with firework_rocket 64", "hotbar.3 with golden_apple 8"};
        case "spear" -> gear("hotbar.0 with diamond_spear", "hotbar.1 with mace", "hotbar.2 with wind_charge 64", "hotbar.3 with golden_apple 8");
        case "trident" -> gear("hotbar.0 with trident", "hotbar.1 with mace", "hotbar.2 with wind_charge 64", "hotbar.3 with golden_apple 8");
        default -> SWORD_KIT;
    });

    // Max-level damage enchantments on both sides' weapons, so a round resolves inside ROUND_TICKS.
    // Armour stays plain. -Dostinato.vex.enchant=false gives the bare kits.
    private static String[] enchant(String[] kit) {
        if ("false".equals(System.getProperty("ostinato.vex.enchant"))) return kit;
        // Built here: KIT's initializer runs before any static field declared below it.
        java.util.Map<String, String> enchants = java.util.Map.of(
                "diamond_sword", "{\"minecraft:sharpness\":5}",
                "diamond_axe", "{\"minecraft:sharpness\":5}",
                "diamond_spear", "{\"minecraft:sharpness\":5}",
                "mace", "{\"minecraft:density\":5}",
                "bow", "{\"minecraft:power\":5}",
                "crossbow", "{\"minecraft:quick_charge\":3,\"minecraft:piercing\":4}",
                "trident", "{\"minecraft:loyalty\":3,\"minecraft:impaling\":5}");
        String[] out = kit.clone();
        for (int i = 0; i < out.length; i++) {
            String[] t = out[i].split(" ", 4); // slot, "with", item, optional count
            String e = enchants.get(t[2]);
            if (e != null) out[i] = t[0] + " with " + t[2] + "[enchantments=" + e + "]" + (t.length > 3 ? " " + t[3] : "");
        }
        return out;
    }

    private final Baritone baritone;
    private final int rounds;
    private int round = -1, ticks, wait = 100;
    private String bot;
    private static final int COUNT = Integer.getInteger("ostinato.vex.count", 1);
    private final List<String> bots = new ArrayList<>();
    private boolean seen, done, setup;
    private float botDmg;
    private static int fixedPadY = Integer.MIN_VALUE;
    private int settle;
    private LocalPlayer roundMe; // the player entity the round began with: immediate respawn swaps it, which is a death
    private int padY; // grass top Y for the arena pad (absolute)
    private int spawnWait;
    private int wins;
    private boolean loggedSwing;
    private boolean worldAsked;
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
        if (done) return;
        LocalPlayer me = Minecraft.getInstance().player;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        // Title-screen ticks are OUT (no player yet). Open the existing vexflat save once.
        if (me == null || server == null) {
            if (!worldAsked && server == null && Minecraft.getInstance().screen instanceof net.minecraft.client.gui.screens.TitleScreen) {
                worldAsked = true;
                log("opening vexflat");
                Minecraft.getInstance().createWorldOpenFlows().openWorld("vexflat", () -> {});
            }
            return;
        }
        if (event.getType() != TickEvent.Type.IN) return;
        reequip(me);
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
                    // 1.21.11 renamed doMobSpawning; the old command is rejected and hostiles keep spawning.
                    clearHostiles();
                    run("time set day", "difficulty normal", "gamemode survival @a");
                    next();
                } else {
                    syncBotNames(); // wait until VexBot's fake player is actually online before gear/tp
                    boolean missing = false;
                    for (String b : bots) if (findPlayer(Minecraft.getInstance().getSingleplayerServer(), b) == null) missing = true;
                    if (missing) {
                        if (++spawnWait > 50) {
                            log("no bot named " + bot + " spawned; check -Dostinato.vex.spawn");
                            finish();
                            return;
                        }
                        wait = 2;
                        return;
                    }
                    spawnWait = 0;
                    int standY = padY + 1;
                    // Close start (default 2): spear kit has mace+wind; at dist>2.5 special() mace-opens forever and
                    // REQUEST_PAUSE pathing, while GoalNear chase melts into the surrounding ocean (millions of nodes).
                    int dist = Integer.getInteger("ostinato.vex.dist", 2);
                    for (int i = 0; i < bots.size(); i++) {
                        String b = bots.get(i);
                        double z = (i - (bots.size() - 1) / 2.0) * 5;
                        run("tp " + b + " " + dist + " " + standY + " " + z + " 90 0", "clear " + b);
                        gear(b);
                        if (!"default".equals(style()))
                            run(System.getProperty("ostinato.vex.style", "vexbot playstyle {style} {name}").replace("{style}", style()).replace("{name}", b));
                    }
                    // Keep Ostinato on the pad facing the bot; absolute Y so ocean worlds cannot leave us swimming.
                    String meName = Minecraft.getInstance().player.getGameProfile().name();
                    run("tp " + meName + " 0 " + standY + " 0 -90 0");
                    arenaPathSettings();
                    for (String b : bots) aggro(b);
                    final String prefix = bot.toLowerCase();
                    if (!Boolean.getBoolean("ostinato.vex.passive")) {
                        PvpProcess pvp = baritone.getPvpProcess();
                        pvp.attack(e -> e instanceof net.minecraft.world.entity.player.Player && e.getName().getString().toLowerCase().startsWith(prefix), prefix);
                        MinecraftServer srv = Minecraft.getInstance().getSingleplayerServer();
                        for (String b : bots) {
                            ServerPlayer sp = findPlayer(srv, b);
                            if (sp != null && Minecraft.getInstance().level != null) {
                                for (net.minecraft.world.entity.player.Player cl : Minecraft.getInstance().level.players()) {
                                    if (cl.getUUID().equals(sp.getUUID())) { pvp.addEnemy(cl); break; }
                                }
                            }
                        }
                        log("pvp on " + prefix + " enemies=" + pvp.enemyCount() + " dist=" + dist);
                    }

                }
            }
            return;
        }
        ticks++;
        int alive = 0;
        botDmg = 0;
        for (String n : bots) {
            ServerPlayer b = findPlayer(server, n);
            if (b != null && b.isAlive()) {
                seen = true;
                alive++;
                botDmg += b.getMaxHealth() - b.getHealth();
            }
        }
        if (roundMe == null) roundMe = me;
        boolean dead = me.isDeadOrDying() || me != roundMe;
        boolean botDead = seen && alive == 0;
        if (!seen && ticks > 100) {
            log("no bot named " + bot + " spawned; check -Dostinato.vex.spawn");
            finish();
            return;
        }
        PvpProcess live = baritone.getPvpProcess();
        if (!loggedSwing && (live.attacks > 0 || botDmg > 0.5f)) {
            loggedSwing = true;
            log("SWING attacks=" + live.attacks + " botDmg=" + botDmg + " taken=" + live.damageTaken + " y=" + (int) Math.floor(me.getY()));
        }
        // a kill while airborne can still end in a fatal fall: settle before scoring the win
        if (botDead && !dead && ticks < ROUND_TICKS && settle < 100 && (!me.onGround() || me.fallDistance > 3)) {
            settle++;
            return;
        }
        if (dead || botDead || ticks >= ROUND_TICKS) {
            PvpProcess p = baritone.getPvpProcess();
            String result = dead ? "death" : botDead ? "win" : "timeout";
            if (botDead && !dead) wins++;
            String diff = DIFFS[round % DIFFS.length];
            String row = String.format("%d,vexbot_%s_%s,%s,%d,%.1f,%.1f,%d,%d,%d,%d,%d,%d", round, diff, style(), result, ticks,
                    p.damageTaken, botDmg, p.attacks, p.crits, p.sprintHits, p.axeHits, p.blocks, p.gapples);
            rows.add(row);
            log(row);
            int losses = 0;
            for (String r : rows) if (r.contains(",death,")) losses++;
            // For the Grok Bot chat relay (not Minecraft chat): round, result, difficulty, running W-L
            log("SCORE " + (round + 1) + "/" + rounds + " " + result + " " + diff + " " + wins + "-" + losses);
            baritone.getPathingControlManager().cancelEverything();
            next();
        }
    }

    private void next() {
        // bare "vexbot kill" clears all bots; named kill re-resolves via display name (Vex0) and misses tracked key vex0
        run(System.getProperty("ostinato.vex.kill", "vexbot kill"));
        bots.clear();
        if (++round >= rounds) {
            finish();
            return;
        }
        bot = "vex" + round; // spawn request is lowercase; syncBotNames() rewrites to the exact GameProfile after join
        for (int i = 0; i < COUNT; i++) bots.add(COUNT == 1 ? bot : bot + (char) ('a' + i));
        // Grass under the player's feet (superflat surface). Do not lift the pad to sea level.
        LocalPlayer me = Minecraft.getInstance().player;
        // The world is a superflat save: fight on its own ground, no platform, no walls, nothing to fall off. The first
        // grass block above the bottom of the world at the origin is that ground (old test platforms sit above it).
        int forced = Integer.getInteger("ostinato.vex.padY", Integer.MIN_VALUE);
        boolean first = fixedPadY == Integer.MIN_VALUE;
        if (forced != Integer.MIN_VALUE) padY = forced;
        else if (!first) padY = fixedPadY;
        else {
            padY = me.level().getMinY();
            for (int y = me.level().getMinY(); y < me.level().getMaxY(); y++) {
                if (me.level().getBlockState(new net.minecraft.core.BlockPos(0, y, 0)).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)) {
                    padY = y;
                    break;
                }
            }
        }
        fixedPadY = padY;
        String meName = me.getGameProfile().name();
        clearHostiles();
        run("kill @a[name=!" + meName + "]", "kill @e[type=item]");
        // explosive kits blast holes in the single ground layer; relay it so no round fights over the last one's craters
        run("fill -40 " + padY + " -40 40 " + padY + " 40 grass_block");
        if (first) { // sweep away platforms left in the save by earlier benches
            for (int y = padY + 1; y < padY + 120; y += 4) run("fill -40 " + y + " -40 40 " + (y + 3) + " 40 air");
        }
        log("ground y=" + padY + " stand=" + (padY + 1));
        setup = true; // gear up once we're alive again: a kit given to a corpse is lost on respawn
        wait = 20;
        ticks = 0;
        seen = false;
        botDmg = 0;
        spawnWait = 0;
        loggedSwing = false;
    }

    /** Hostiles off for every round. Leaves players, items, and armor stands. Not peaceful. */
    private void clearHostiles() {
        run("gamerule spawn_mobs false", "gamerule spawn_monsters false", "gamerule spawn_phantoms false",
                "gamerule spawn_patrols false", "gamerule spawn_wardens false",
                "gamerule immediate_respawn true", "gamerule keep_inventory true", "gamerule advance_time false",
                "difficulty normal",
                "kill @e[type=!player,type=!item,type=!armor_stand]",
                "gamerule spawn_monsters");
    }

    private void setup() {
        setup = false;
        roundMe = null;
        settle = 0;
        clearHostiles();
        int standY = padY + 1;
        run("clear @a", "effect clear @a", "tp @a 0 " + standY + " 0 -90 0",
                "effect give @a instant_health 1 10", "effect give @a saturation 1 10");
        gear(Minecraft.getInstance().player.getGameProfile().name());
        String diff = DIFFS[round % DIFFS.length];

        run(System.getProperty("ostinato.vex.difficulty", "vexbot difficulty preset {diff}").replace("{diff}", diff));
        for (String n : bots) run(System.getProperty("ostinato.vex.spawn", "vexbot spawn {name}").replace("{name}", n));
        wait = 40; // VexBot spawn is async; gear/tp retry via spawnWait if still missing
    }

    private static final boolean RANDOM = "random".equals(System.getProperty("ostinato.vex.kit"));

    /** Gives {@code name} the bench kit; "random" uses VexBot's own generator, seeded per round so both sides get the same gear. */
    private float lastLifeHp = -1;

    /** Full kit after every respawn, even with keep_inventory. A naked tick must not start a fight. */
    private void reequip(LocalPlayer me) {
        if (me == null || me.isDeadOrDying()) return;
        float hp = me.getHealth();
        boolean snapped = lastLifeHp >= 0 && lastLifeHp < 5 && hp > lastLifeHp + 8;
        boolean naked = me.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty();
        lastLifeHp = hp;
        if (!snapped && !naked) return;
        log(snapped ? "respawn reequip" : "naked reequip");
        gear(me.getGameProfile().name());
    }

    private void gear(String name) {
        if (!RANDOM) {
            for (String k : KIT) run("item replace entity " + name + " " + k);
            MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();

            return;
        }
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        long seed = Long.getLong("ostinato.vex.seed", 1000) + round;
        server.submit(() -> {
            ServerPlayer p = findPlayer(server, name);
            if (p == null) { log("gear miss " + name); return; }
            try {
                Class.forName("vexbot.bot.loadout.RandomGearGenerator").getMethod("apply", ServerPlayer.class, java.util.Random.class)
                        .invoke(null, p, new java.util.Random(seed));
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                    var st = p.getInventory().getItem(i);
                    if (!st.isEmpty()) sb.append(i).append('=').append(st.getCount()).append('x').append(st.getItem()).append(' ');
                }
                log("kit " + name + " seed=" + seed + " " + sb);
            } catch (Exception e) {
                log("random gear failed: " + e);
            }
        }).join();
    }

    private String style() {
        return STYLES[round / DIFFS.length % STYLES.length];
    }

    /** Rewrite bots[] to each fake player's exact GameProfile.name() (item replace / tp are case-sensitive). */
    private void syncBotNames() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        for (int i = 0; i < bots.size(); i++) {
            ServerPlayer p = findPlayer(server, bots.get(i));
            if (p != null) {
                String exact = p.getGameProfile().name();
                if (!exact.equals(bots.get(i))) log("rename " + bots.get(i) + " -> " + exact);
                bots.set(i, exact);
            } else {
                log("sync miss " + bots.get(i));
            }
        }
        if (!bots.isEmpty()) bot = bots.get(0);
    }

    private static ServerPlayer findPlayer(MinecraftServer server, String name) {
        ServerPlayer exact = server.getPlayerList().getPlayerByName(name);
        if (exact != null) return exact;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.getGameProfile().name().equalsIgnoreCase(name)) return p;
        }
        try { // VexBot fake players are tracked even when getPlayerByName misses casing
            Object p = Class.forName("vexbot.bot.BotManager")
                    .getMethod("resolveTrackedBotPlayer", MinecraftServer.class, String.class)
                    .invoke(null, server, name);
            if (p instanceof ServerPlayer sp) return sp;
        } catch (Exception ignored) {}
        return null;
    }

    /** Tell bot to attack the local player using VexBot's tracked name (vex0), not the nametag (Vex0). */
    private void aggro(String botName) {
        String me = Minecraft.getInstance().player.getGameProfile().name();
        try {
            Class<?> bm = Class.forName("vexbot.bot.BotManager");
            String canon = (String) bm.getMethod("resolveCanonicalBotName", String.class).invoke(null, botName);
            if (canon == null) throw new IllegalStateException("not tracked: " + botName);
            bm.getMethod("setAttackTarget", String.class, String.class).invoke(null, canon, me);
            log("aggro " + canon + " -> " + me);
        } catch (Exception e) {
            log("aggro failed: " + e);
            run(System.getProperty("ostinato.vex.aggro", "vexbot attack {me} {name}").replace("{name}", botName).replace("{me}", me));
        }
    }

    /** Constrain Baritone so chase cannot wander the ocean around the grass pad. */
    private void arenaPathSettings() {
        var s = baritone.settings();
        // Move/aim like a vanilla player so server anticheats (Grim etc.) do not flag snap/speed.
        s.antiCheatCompatibility.value = true;
        s.smoothLook.value = true;
        s.smoothLookTicks.value = 5;
        s.allowBreak.value = false;
        s.allowPlace.value = false;
        s.allowParkour.value = false;
        s.allowParkourAscend.value = false;
        s.allowParkourPlace.value = false;
        s.allowWaterBucketFall.value = false;
        s.assumeWalkOnWater.value = false;
        s.assumeWalkOnLava.value = false;
        s.maxFallHeightNoWater.value = 2;
        s.primaryTimeoutMS.value = 2000L;
        s.failureTimeoutMS.value = 3000L;
        s.costHeuristic.value = 1.6;
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
            if (p != null) src = src.withPosition(new Vec3(0, p.getY(), 0)).withLevel(p.level());
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
