/*
 * Ostinato: new file (spectator kit detection and fight type bench). Not part of upstream Baritone.
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
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ostinato: local bench for spectator kit detection and fight types ({@code -Dostinato.kitbench=<scenarios>}, single
 * player with Carpet PvP). We watch from spectator mode while Carpet bots fight with known kits, one scenario a round:
 * {@code 1v1:<kit>} (two bots), {@code teams<n>:<kit>} ({@code /bot match teams n}: two sides of n, allies never hit
 * each other; {@code teams<n>t} also puts them in coloured scoreboard teams the client can see) and
 * {@code ffa<n>:<kit>} ({@code /bot match ffa n}). Kits are Carpet's styles (sword, smp, mace, crystal, ranged) and ours
 * on top of them (axe = axe+shield, pot, nethpot = netherite-pot, uhc). At the end each closed fight group is scored
 * against the truth: the fight type and shape, and every fighter's kit label. Lines start with {@code KITBENCH}.
 */
public final class KitBench implements AbstractGameEventListener {
    private static final String SCENARIOS = System.getProperty("ostinato.kitbench", "");
    private static final int ROUND_TICKS = Integer.getInteger("ostinato.kitbench.ticks", 20 * 45);
    private static final String DIFF = System.getProperty("ostinato.kitbench.diff", "skilled");

    private final Baritone baritone;
    private final List<String> scenarios = new ArrayList<>();
    private int round = -1, ticks, wait = 80, settle;
    private boolean done, started;
    private String scenario;
    private final Map<String, String> truthKit = new LinkedHashMap<>(); // name -> kit label, this round
    private int resultsAtStart, expected;
    private final Map<String, String> side = new LinkedHashMap<>(); // bot -> faction (team scenarios)
    private final List<Object[]> rounds = new ArrayList<>(); // {scenario, truth kits, first result, last result}
    private long kit0Nanos, kit0Ticks;

    public static void install(Baritone baritone) {
        if (!SCENARIOS.isEmpty()) baritone.getGameEventHandler().registerEventListener(new KitBench(baritone));
    }

    private KitBench(Baritone baritone) {
        this.baritone = baritone;
        for (String s : SCENARIOS.split(",")) if (!s.isBlank()) scenarios.add(s.trim());
    }

    /** Our kit name to its label and the Carpet style that fights it. */
    static String label(String kit) {
        return switch (kit) {
            case "axe" -> "axe+shield";
            case "nethpot" -> "netherite-pot";
            case "odd" -> "learned"; // no kit in the table: should become a learned kit, the same one each time
            default -> kit;
        };
    }

    static String style(String kit) {
        return switch (kit) {
            case "axe" -> "sword";
            case "pot", "nethpot", "uhc" -> "smp";
            case "odd" -> "sword";
            default -> kit;
        };
    }

    /** Our own kits, given on top of the style's: item replace arguments. */
    static String[] inventory(String kit) {
        String heal = "splash_potion[potion_contents={potion:\"minecraft:strong_healing\"}]";
        String speed = "splash_potion[potion_contents={potion:\"minecraft:swiftness\"}]";
        String prot = "[enchantments={\"minecraft:protection\":4}]";
        String[] dia = {"armor.head with diamond_helmet" + prot, "armor.chest with diamond_chestplate" + prot, "armor.legs with diamond_leggings" + prot, "armor.feet with diamond_boots" + prot};
        String[] neth = {"armor.head with netherite_helmet" + prot, "armor.chest with netherite_chestplate" + prot, "armor.legs with netherite_leggings" + prot, "armor.feet with netherite_boots" + prot};
        List<String> out = new ArrayList<>();
        switch (kit) {
            case "axe" -> {
                out.addAll(List.of(dia));
                out.addAll(List.of("hotbar.0 with diamond_axe", "hotbar.1 with diamond_sword", "weapon.offhand with shield", "hotbar.2 with golden_apple 6", "hotbar.3 with cooked_beef 16"));
            }
            case "pot" -> {
                out.addAll(List.of(dia));
                out.add("hotbar.0 with diamond_sword[enchantments={\"minecraft:sharpness\":3}]");
                for (int i = 1; i <= 6; i++) out.add("hotbar." + i + " with " + heal + " 1");
                out.add("hotbar.7 with " + speed + " 2");
                out.add("hotbar.8 with cooked_beef 16");
            }
            case "nethpot" -> {
                out.addAll(List.of(neth));
                out.add("hotbar.0 with netherite_sword[enchantments={\"minecraft:sharpness\":5}]");
                for (int i = 1; i <= 6; i++) out.add("hotbar." + i + " with " + heal + " 1");
                out.add("hotbar.7 with " + speed + " 2");
                out.add("hotbar.8 with cooked_beef 16");
            }
            case "uhc" -> {
                out.addAll(List.of("armor.head with diamond_helmet", "armor.chest with diamond_chestplate", "armor.legs with iron_leggings", "armor.feet with diamond_boots"));
                out.addAll(List.of("hotbar.0 with diamond_sword", "hotbar.1 with diamond_axe", "hotbar.2 with bow", "hotbar.3 with golden_apple 6", "hotbar.4 with lava_bucket",
                        "hotbar.5 with water_bucket", "hotbar.6 with cobweb 8", "hotbar.7 with cobblestone 64", "hotbar.8 with fishing_rod", "inventory.0 with arrow 32",
                        "weapon.offhand with shield"));
            }
            case "odd" -> out.addAll(List.of("armor.head with chainmail_helmet", "armor.chest with chainmail_chestplate", "armor.legs with chainmail_leggings",
                    "armor.feet with chainmail_boots", "hotbar.0 with iron_sword", "hotbar.1 with golden_carrot 32"));
            default -> {
                return null;
            }
        }
        return out.toArray(new String[0]);
    }

    @Override
    public void onTick(TickEvent event) {
        if (done || event.getType() != TickEvent.Type.IN || event.getState() != EventState.PRE) return;
        LocalPlayer me = Minecraft.getInstance().player;
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (me == null || server == null) return;
        if (wait > 0) {
            wait--;
            return;
        }
        String me0 = me.getGameProfile().name();
        if (!started) {
            started = true;
            Baritone.settings().spectateLearn.value = true;
            Baritone.settings().spectateSaveLogs.value = Boolean.getBoolean("ostinato.kitbench.logs");
            run(server, null, "gamerule spawn_mobs false", "gamerule immediate_respawn true", "gamerule advance_time false", "time set day",
                    "difficulty normal", "kill @e[type=!player]", "gamemode spectator " + me0);
            kit0Nanos = baritone.getSpectateLearner().kitNanos();
            kit0Ticks = baritone.getSpectateLearner().kitTicks();
            next(server, me0);
            return;
        }
        if (ticks == 0) {
            // the bots are geared by the mod a moment after they join; ours go on top
            List<ServerPlayer> bots = bots(server, me0);
            if (settle++ < 40 || bots.size() < expected && settle < 400) return;
            settle = 0;
            if (bots.isEmpty()) {
                log("no bots for " + scenario);
                next(server, me0);
                return;
            }
            String kit = scenario.substring(scenario.indexOf(':') + 1);
            String[] inv = inventory(kit);
            for (ServerPlayer b : bots) {
                String n = b.getName().getString();
                if (inv != null) {
                    run(server, null, "clear " + n);
                    for (String k : inv) run(server, null, "item replace entity " + n + " " + k);
                }
                truthKit.put(n, label(kit));
            }
            for (Map.Entry<String, String> e : side.entrySet()) run(server, null, "player " + e.getKey() + " faction join kb" + e.getValue());
            for (ServerPlayer b : bots) {
                String n = b.getName().getString();
                run(server, null, "player " + n + " ai combat true", "player " + n + " ai autotarget true");
            }
            if (scenario.startsWith("teams") && scenario.substring(0, scenario.indexOf(':')).endsWith("t")) {
                run(server, null, "team add kbred", "team add kbblue", "team modify kbred color red", "team modify kbblue color blue");
                for (Map.Entry<String, String> e : side.entrySet()) run(server, null, "team join kb" + e.getValue() + " " + e.getKey());
            }
            baritone.getSpectateLearner().forgetKits(); // what they wore before our kit went on is not their kit
            log("round " + round + " " + scenario + " bots " + truthKit.keySet());
            ticks++;
            return;
        }
        ticks++;
        if (ticks % 300 == 0) for (String s : baritone.getSpectateLearner().status()) log("status " + s);
        List<ServerPlayer> bots = bots(server, me0);
        // Ostinato: the spectator follows the fight, as a watching player would, so bots that chase or kite stay in range
        if (ticks % 100 == 0 && !bots.isEmpty()) {
            double cx = 0, cy = 0, cz = 0;
            StringBuilder where = new StringBuilder();
            for (ServerPlayer b : bots) {
                cx += b.getX() / bots.size();
                cy += b.getY() / bots.size();
                cz += b.getZ() / bots.size();
                where.append(String.format(Locale.ROOT, " %s@%.0f,%.0f,%.0f", b.getGameProfile().name(), b.getX(), b.getY(), b.getZ()));
            }
            if (ticks % 300 == 0) log("where" + where);
            run(server, null, String.format(Locale.ROOT, "tp %s %.1f %.1f %.1f 0 35", me0, cx, cy + 8, cz - 10));
        }
        if (ticks > ROUND_TICKS || bots.size() < (scenario.startsWith("1v1") ? 2 : 2)) {
            log("round " + round + " over after " + ticks + " ticks, " + bots.size() + " standing");
            next(server, me0);
        }
    }

    private static List<ServerPlayer> bots(MinecraftServer server, String me0) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) if (!p.getGameProfile().name().equals(me0) && p.isAlive()) out.add(p);
        return out;
    }

    private void next(MinecraftServer server, String me0) {
        if (round >= 0) rounds.add(new Object[]{scenario, new LinkedHashMap<>(truthKit), resultsAtStart, new LinkedHashMap<>(side)});
        run(server, null, "kill @a[name=!" + me0 + "]", "kill @e[type=!player]", "team remove kbred", "team remove kbblue", "player " + me0 + " faction delete kbred",
                "player " + me0 + " faction delete kbblue");
        round++;
        ticks = 0;
        truthKit.clear();
        if (round >= scenarios.size()) {
            wait = 200; // let the last groups close
            done = true;
            Thread t = new Thread(() -> {
                try {
                    Thread.sleep(12000);
                } catch (InterruptedException ignored) {}
                Minecraft.getInstance().execute(this::finish);
            });
            t.setDaemon(true);
            t.start();
            return;
        }
        scenario = scenarios.get(round);
        resultsAtStart = baritone.getSpectateLearner().results().size();
        String head = scenario.substring(0, scenario.indexOf(':')), kit = scenario.substring(scenario.indexOf(':') + 1);
        String st = style(kit);
        expected = head.equals("1v1") ? 2 : head.startsWith("teams") ? 2 * Integer.parseInt(head.replaceAll("[^0-9]", "")) : Integer.parseInt(head.replaceAll("[^0-9]", ""));
        if (Double.isNaN(baseY)) baseY = Math.floor(Minecraft.getInstance().player.getY()); // Ostinato: watch from a fixed height
        // Ostinato: crystal rounds blow holes in the floor: put the floor back as it was at the start, and clear what
        // bots placed above it
        if (floor == null) {
            floor = new ArrayList<>();
            net.minecraft.server.level.ServerLevel lv = server.overworld();
            for (int y = lv.getMinY(); y < baseY; y++) {
                net.minecraft.world.level.block.state.BlockState b = lv.getBlockState(new net.minecraft.core.BlockPos(0, y, 0));
                if (b.isAir()) break;
                floor.add(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b.getBlock()).toString());
                floorTop = y;
            }
            log("floor " + floor + " up to y " + floorTop);
        }
        int y0 = floorTop - floor.size() + 1;
        for (int i = 0; i < floor.size(); i++) run(server, null, String.format(Locale.ROOT, "fill -24 %d -24 24 %d 24 %s", y0 + i, y0 + i, floor.get(i)));
        run(server, null, String.format(Locale.ROOT, "fill -24 %d -24 24 %d 24 air", floorTop + 1, floorTop + 8));
        // each bot on its own; a team side is a Carpet faction (allies never target each other), which the client
        // cannot see: only the "t" variant adds scoreboard teams on top
        side.clear();
        boolean teams = head.startsWith("teams");
        for (int i = 0; i < expected; i++) {
            String n = "kb" + round + (teams ? (i < expected / 2 ? "r" : "b") : "p") + i;
            double ang = 2 * Math.PI * i / expected;
            run(server, null, String.format(Locale.ROOT, "bot spawn %s %s %s at %.1f ~ %.1f", n, st, DIFF, 5 * Math.cos(ang), 5 * Math.sin(ang)));
            if (teams) side.put(n, i < expected / 2 ? "red" : "blue");
        }
        run(server, null, String.format(Locale.ROOT, "tp %s 0 %.1f -10 0 35", me0, baseY + 8));
        wait = 60;
    }

    private List<String> floor; // Ostinato: the floor column at 0,0, bottom up
    private int floorTop;
    private double baseY = Double.NaN; // Ostinato: the spectator's starting height, so it does not climb each round

    /** Scores each round's groups against what the bots were given. */
    private void finish() {
        List<SpectateLearner.GroupResult> res = baritone.getSpectateLearner().results();
        int teamRight = 0, teamN = 0, typeRight = 0, typeN = 0, shapeRight = 0, kitRight = 0, kitN = 0, fighterSure = 0, roundsWhole = 0;
        Map<String, int[]> perKit = new TreeMap<>(); // truth kit -> {right, n}
        Map<String, Integer> confusion = new TreeMap<>();
        double confRight = 0, confWrong = 0;
        int nRight = 0, nWrong = 0;
        for (int r = 0; r < rounds.size(); r++) {
            Object[] rd = rounds.get(r);
            String sc = (String) rd[0];
            @SuppressWarnings("unchecked") Map<String, String> truth = (Map<String, String>) rd[1];
            int from = (Integer) rd[2], to = r + 1 < rounds.size() ? (Integer) rounds.get(r + 1)[2] : res.size();
            String head = sc.substring(0, sc.indexOf(':'));
            String wantType = head.equals("1v1") ? "1v1" : head.startsWith("teams") ? "team" : "ffa";
            int n = head.equals("1v1") ? 2 : head.startsWith("teams") ? 2 * Integer.parseInt(head.replaceAll("[^0-9]", "")) : Integer.parseInt(head.replaceAll("[^0-9]", ""));
            String wantShape = wantType.equals("1v1") ? "1v1" : wantType.equals("team") ? (n / 2) + "v" + (n / 2) : "ffa" + n;
            boolean whole = false;
            Map<String, KitSignatures.Result> lastKit = new LinkedHashMap<>();
            for (int i = from; i < res.size(); i++) { // groups close late: any later one with this round's bots
                SpectateLearner.GroupResult g = res.get(i);
                boolean ours = false;
                for (String name : g.kits.keySet()) ours |= truth.containsKey(name);
                if (!ours || g.hits < 5) continue;
                typeN++;
                if (g.type.equals(wantType)) typeRight++;
                if (g.shape.equals(wantShape)) shapeRight++;
                if (g.shape.equals(wantShape) && g.kits.size() == n) whole = true;
                if (wantType.equals("team")) {
                    @SuppressWarnings("unchecked") Map<String, String> sides = (Map<String, String>) rd[3];
                    boolean ok = g.type.equals("team");
                    String[] parts = g.teams.split("\\|");
                    if (parts.length != 2) ok = false;
                    for (String part : parts) {
                        java.util.Set<String> ss = new java.util.HashSet<>();
                        for (String nm : part.split(",")) ss.add(sides.get(nm));
                        if (ss.size() != 1) ok = false;
                    }
                    teamN++;
                    if (ok) teamRight++;
                }
                log(String.format(Locale.ROOT, "score round %d %s: got %s %s, want %s %s; teams %s; kits %s", r, sc, g.type, g.shape, wantType, wantShape, g.teams, g.kits));
                lastKit.putAll(g.kits);
            }
            if (whole) roundsWhole++;
            // each fighter once a round: the label from the last group they fought in
            for (Map.Entry<String, String> e : truth.entrySet()) {
                KitSignatures.Result k = lastKit.get(e.getKey());
                if (k == null) continue;
                kitN++;
                int[] pk = perKit.computeIfAbsent(e.getValue(), x -> new int[2]);
                pk[1]++;
                boolean ok = k.label.equals(e.getValue()) || e.getValue().equals("learned") && k.label.startsWith("learned-");
                if (ok) {
                    kitRight++;
                    pk[0]++;
                    confRight += k.confidence;
                    nRight++;
                } else {
                    confusion.merge(e.getValue() + "->" + k.label, 1, Integer::sum);
                    confWrong += k.confidence;
                    nWrong++;
                }
                if (k.confidence >= FightBook.KIT_SURE) fighterSure++;
            }
        }
        log(String.format(Locale.ROOT, "SUMMARY kit labels %d/%d right (%.3f); sure (conf>=%.2f) %d; mean conf right %.2f, wrong %.2f", kitRight, kitN,
                kitN == 0 ? 0 : (double) kitRight / kitN, FightBook.KIT_SURE, fighterSure, nRight == 0 ? 0 : confRight / nRight, nWrong == 0 ? 0 : confWrong / nWrong));
        for (Map.Entry<String, int[]> e : perKit.entrySet()) log(String.format(Locale.ROOT, "SUMMARY kit %s %d/%d", e.getKey(), e.getValue()[0], e.getValue()[1]));
        log("SUMMARY kit confusions " + confusion);
        log(String.format(Locale.ROOT, "SUMMARY fight type %d/%d groups right (%.3f), shape %d/%d; rounds seen as one whole group of the right shape %d/%d", typeRight, typeN,
                typeN == 0 ? 0 : (double) typeRight / typeN, shapeRight, typeN, roundsWhole, rounds.size()));
        log(String.format(Locale.ROOT, "SUMMARY team partition %d/%d team groups split right", teamRight, teamN));
        long kn = baritone.getSpectateLearner().kitNanos() - kit0Nanos, kt = baritone.getSpectateLearner().kitTicks() - kit0Ticks;
        log(String.format(Locale.ROOT, "SUMMARY kit detection main thread %.1f us/tick over %d ticks", kt == 0 ? 0 : kn / 1e3 / kt, kt));
        for (String s : baritone.getSpectateLearner().status()) log("final " + s);
        log("SUMMARY done");
        if (Boolean.getBoolean("ostinato.kitbench.exit")) Minecraft.getInstance().stop();
    }

    /** Runs commands on the server thread, as the named player when given (some commands want a player). */
    private static void run(MinecraftServer server, String as, String... cmds) {
        server.submit(() -> {
            var src = server.createCommandSourceStack();
            ServerPlayer asP = as == null ? null : server.getPlayerList().getPlayerByName(as);
            ServerPlayer p = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
            if (asP != null) src = asP.createCommandSourceStack();
            else if (p != null) src = src.withPosition(new Vec3(0, p.getY(), 0)).withLevel(p.level());
            for (String c : cmds) server.getCommands().performPrefixedCommand(src, c);
        }).join();
    }

    private static void log(String s) {
        System.out.println("KITBENCH " + s);
    }
}
