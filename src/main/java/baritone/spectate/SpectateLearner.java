/*
 * Ostinato: new file (spectate learning, ported to 1.16.1). Not part of upstream Baritone.
 */
package baritone.spectate;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.behavior.Behavior;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.AbstractClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Effects;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.text.Color;
import net.minecraft.util.text.ITextComponent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Ostinato: watch-only spectate recorder for 1.16.1. Watches nearby players (lobby NPCs skipped), works out who hit
 * whom from swings and hurt flashes, and feeds {@link FightBook}: kit detection and learned kits from what they wear
 * and hold, FFA/team shape from scoreboard teams and name colours, joined-late fights, and {@link CheatWatch} suspects
 * (kept out of the tallies). Unlike the 1.21.x/26.x learner it reads entity state each tick instead of packets, and
 * has no key decoder or priors (those need the newer PvP code). Nothing is ever sent to the server.
 */
public final class SpectateLearner extends Behavior {

    static final int LATE_ARRIVAL = 60, LATE_COMBO = 10; // ticks, as the 26.3 learner
    private static final EquipmentSlotType[] ARMOR = {EquipmentSlotType.HEAD, EquipmentSlotType.CHEST, EquipmentSlotType.LEGS, EquipmentSlotType.FEET};
    private static final String[] MATERIALS = {"leather", "chainmail", "iron", "golden", "diamond", "netherite", "turtle"};

    private static final class Seen {
        final int firstTick;
        int lastSwing = -100, lastHurt = -100000, prevHurtTime, prevSwingInt;
        boolean prevSwing, dead;
        double x, y, z;

        Seen(int t) {
            firstTick = t;
        }
    }

    private final FightBook book;
    private final CheatWatch cheat = new CheatWatch();
    private final Map<Integer, Seen> seen = new HashMap<>();
    private final Map<Integer, String> flagged = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private final List<String> lastLines = new ArrayList<>();
    private ClientWorld lastWorld;
    private int tick;

    public SpectateLearner(Baritone baritone) {
        super(baritone);
        book = new FightBook(f -> { }, (f, winner, why) -> { });
        book.onSettled(this::settled);
        book.onGroup(g -> System.out.println("Ostinato spectate group: " + g.describe()));
    }

    private static Settings s() {
        return BaritoneAPI.getSettings();
    }

    /** Why a fight was joined late, or null (shared with the 26.3 learner and its tests). */
    static String lateReason(String name, float hp, float maxHp, float absorption, boolean hurtNow, int sinceHurt, boolean gapple, int sinceSeen) {
        if (sinceSeen < LATE_ARRIVAL) return "arrived:" + name;
        if (hp < maxHp - 0.5f) return "hurt:" + name;
        if (absorption > 0) return "absorption:" + name;
        if (hurtNow || sinceHurt <= LATE_COMBO) return "combo:" + name;
        if (gapple) return "gapple:" + name;
        return null;
    }

    /** Lobby NPCs: no tab-list entry, or a §-scrambled name. They hover and never fight. */
    static boolean npc(Minecraft mc, PlayerEntity p) {
        if (mc.getConnection() != null && mc.getConnection().getPlayerInfo(p.getUniqueID()) == null) return true;
        return p.getName().getString().indexOf('\u00a7') >= 0;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getState() != EventState.PRE || event.getType() != TickEvent.Type.IN) return;
        Minecraft mc = Minecraft.getInstance();
        if (!s().spectateLearn.value || mc.world == null || mc.player == null) return;
        if (!mc.isSingleplayer() && !s().spectateLearnOnServers.value) return;
        if (mc.world != lastWorld) { // entity ids start over
            lastWorld = mc.world;
            seen.clear();
            flagged.clear();
            cheat.clear();
            book.resetPlayers();
        }
        tick++;
        double r = s().spectateRadius.value;
        List<PlayerEntity> near = new ArrayList<>();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.getEntityId() < 0 || p.isSpectator() || npc(mc, p) || p.getDistanceSq(mc.player) > r * r) continue;
            near.add(p);
        }
        Set<Integer> here = new HashSet<>();
        for (PlayerEntity p : near) {
            int id = p.getEntityId();
            here.add(id);
            names.put(id, p.getName().getString());
            Seen sn = seen.get(id);
            if (sn == null) {
                sn = new Seen(tick);
                sn.x = p.getPosX();
                sn.y = p.getPosY();
                sn.z = p.getPosZ();
                seen.put(id, sn);
            }
            boolean swungNow = p.isSwingInProgress && (!sn.prevSwing || p.swingProgressInt < sn.prevSwingInt);
            sn.prevSwing = p.isSwingInProgress;
            sn.prevSwingInt = p.swingProgressInt;
            if (swungNow) {
                sn.lastSwing = tick;
                cheat.swing(id, tick);
            }
            boolean hurtNow = p.hurtTime > sn.prevHurtTime;
            sn.prevHurtTime = p.hurtTime;
            boolean exempt = p.isElytraFlying() || p.isPassenger() || p.isInWater() || p.isInLava() || p.isOnLadder()
                    || p.isPotionActive(Effects.LEVITATION) || p.isPotionActive(Effects.SLOW_FALLING) || p.isPotionActive(Effects.SPEED)
                    || p.isPotionActive(Effects.JUMP_BOOST) || p.isCreative() || p.isSpinAttacking();
            cheat.move(id, tick, p.getPosX(), p.getPosY(), p.getPosZ(), p.rotationYaw, p.isOnGround(), exempt, hurtNow);
            if (hurtNow) hurt(mc, p, sn, near);
            sn.x = p.getPosX();
            sn.y = p.getPosY();
            sn.z = p.getPosZ();
            if (!sn.dead && (p.getHealth() <= 0 || !p.isAlive())) {
                sn.dead = true;
                book.died(tick, id);
            }
        }
        for (Iterator<Map.Entry<Integer, Seen>> it = seen.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, Seen> e = it.next();
            if (!here.contains(e.getKey())) {
                cheat.forget(e.getKey());
                it.remove();
            }
        }
        Map<Integer, double[]> where = new HashMap<>();
        final Map<Integer, PlayerEntity> byId = new HashMap<>();
        for (PlayerEntity p : near) {
            where.put(p.getEntityId(), new double[]{p.getPosX(), p.getPosY(), p.getPosZ(), p.getHealth()});
            byId.put(p.getEntityId(), p);
        }
        book.multiApart(s().spectateFightTypes.value);
        book.includeLate(s().spectateLearnLateFights.value);
        book.includeCheats(s().spectateLearnSuspectedCheats.value);
        book.learnKits(s().spectateLearnKits.value);
        book.track(tick, where);
        book.tick(tick, (a, b) -> {
            PlayerEntity pa = byId.get(a), pb = byId.get(b);
            return pa == null || pb == null ? Double.NaN : pa.getDistance(pb);
        }, id -> {
            PlayerEntity p = byId.get(id);
            return p == null ? Double.NaN : p.getHealth();
        });
        if (tick % 20 == 0) {
            for (PlayerEntity p : near) {
                int id = p.getEntityId();
                book.gear(tick, id, tokens(p));
                book.clue(id, clue(p));
                String why = cheat.reason(id);
                if (why != null && !why.equals(flagged.get(id))) {
                    flagged.put(id, why);
                    String name = p.getName().getString();
                    System.out.println("Ostinato spectate: suspected cheat " + name + " " + why + " (watch-only: kept out of learning)");
                    book.suspect(id, name, why);
                }
            }
        }
    }

    /** A hurt flash: the attacker is whoever swung in the last 2 ticks closest to the victim, within 6 blocks. */
    private void hurt(Minecraft mc, PlayerEntity victim, Seen vs, List<PlayerEntity> near) {
        PlayerEntity att = null;
        double best = 6.0;
        for (PlayerEntity o : near) {
            if (o == victim) continue;
            Seen os = seen.get(o.getEntityId());
            if (os == null || tick - os.lastSwing > 2) continue;
            double d = reach(o, victim.getBoundingBox());
            if (d < best) {
                best = d;
                att = o;
            }
        }
        int sinceHurt = tick - vs.lastHurt;
        vs.lastHurt = tick;
        if (att == null) return;
        int a = att.getEntityId(), v = victim.getEntityId();
        cheat.hit(a, v, tick, best, CheatWatch.offAngle(att.rotationYaw, att.getPosX(), att.getPosZ(), victim.getPosX(), victim.getPosZ()), -1);
        boolean crit = att.fallDistance > 0 && !att.isOnGround() && !att.isOnLadder() && !att.isInWater();
        String late = null;
        if (book.fightsOf(a).stream().noneMatch(f -> f.other(a) == v)) { // a new pair: was it joined late?
            Seen as = seen.get(a);
            late = lateReason(victim.getName().getString(), victim.getHealth() + 1, victim.getMaxHealth(), victim.getAbsorptionAmount(), false,
                    sinceHurt, false, tick - vs.firstTick);
            if (late == null && as != null) late = lateReason(att.getName().getString(), att.getHealth(), att.getMaxHealth(), att.getAbsorptionAmount(),
                    att.hurtTime > 0, tick - as.lastHurt, false, tick - as.firstTick);
        }
        book.hit(tick, a, att.getName().getString(), v, victim.getName().getString(), crit, late);
    }

    /** The attacker's eye to the nearest point of the victim's box. */
    static double reach(PlayerEntity att, AxisAlignedBB b) {
        double ex = att.getPosX(), ey = att.getPosY() + att.getEyeHeight(), ez = att.getPosZ();
        double dx = Math.max(Math.max(b.minX - ex, 0), ex - b.maxX), dy = Math.max(Math.max(b.minY - ey, 0), ey - b.maxY), dz = Math.max(Math.max(b.minZ - ez, 0), ez - b.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static String material(String path) {
        for (String m : MATERIALS) if (path.startsWith(m + "_")) return m;
        return null;
    }

    /** What the player wears and holds, as the weighted tokens {@link KitSignatures} reads. */
    static Map<String, Double> tokens(PlayerEntity p) {
        Map<String, Double> t = new HashMap<>();
        hand(t, p.getHeldItemMainhand(), true);
        hand(t, p.getHeldItemOffhand(), false);
        String first = null;
        boolean mixed = false;
        for (EquipmentSlotType s : ARMOR) {
            ItemStack st = p.getItemStackFromSlot(s);
            if (st.isEmpty()) continue;
            String path = Registry.ITEM.getKey(st.getItem()).getPath();
            if (path.equals("elytra")) t.merge("elytra", 1.0, Double::sum);
            String m = material(path);
            if (m != null) t.merge("armor:" + m, 0.25, Double::sum);
            if (m != null && first == null) first = m;
            else if (m != null && !m.equals(first)) mixed = true;
            if (st.hasEffect()) t.merge("glint:armor", 0.25, Double::sum);
            if (st.getTag() != null && st.getTag().contains("display") && st.getTag().getCompound("display").contains("color")) t.merge("dyed", 0.25, Double::sum);
        }
        if (mixed) t.put("armor:mixed", 1.0);
        for (net.minecraft.potion.EffectInstance e : p.getActivePotionEffects()) { // 1.16.1 keeps effects on the entity it shows
            net.minecraft.util.ResourceLocation k = Registry.EFFECTS.getKey(e.getPotion());
            if (k != null) t.put("effect:" + k.getPath(), 1.0);
        }
        return t;
    }

    private static void hand(Map<String, Double> t, ItemStack st, boolean main) {
        if (st.isEmpty()) return;
        t.put(Registry.ITEM.getKey(st.getItem()).getPath(), 1.0);
        if (main && st.hasEffect()) t.put("glint:weapon", 1.0);
    }

    /** What the client shows of the player's team. */
    static FightGroup.Clue clue(PlayerEntity p) {
        Team team = p.getTeam();
        String name = team == null ? "" : team.getName();
        int teamColor = team instanceof ScorePlayerTeam ? ((ScorePlayerTeam) team).getColor().ordinal() : -1;
        int dye = -1;
        for (EquipmentSlotType s : ARMOR) {
            ItemStack st = p.getItemStackFromSlot(s);
            if (st.getTag() != null && st.getTag().getCompound("display").contains("color")) {
                dye = st.getTag().getCompound("display").getInt("color") & 0xFFFFFF;
                break;
            }
        }
        return new FightGroup.Clue(name, teamColor, color(p.getDisplayName()), dye, null);
    }

    static int color(ITextComponent c) {
        Color tc = c.getStyle().getColor();
        if (tc != null && (tc.getColor() & 0xFFFFFF) != 0xFFFFFF) return tc.getColor() & 0xFFFFFF;
        for (ITextComponent s : c.getSiblings()) {
            int v = color(s);
            if (v >= 0) return v;
        }
        return -1;
    }

    /** The fight's group closed: one summary block in {@code baritone/spectate/fights.log}. */
    private void settled(FightBook.Fight f, boolean counts) {
        FightGroup g = f.group;
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%s #type %s shape=%s players=%d%s", LocalDateTime.now(), f.type(), g == null ? "1v1" : g.shape,
                g == null ? 2 : g.members.size(), counts ? "" : " short")
                + (f.late == null ? "" : " late=" + f.late)
                + (f.cheat == null ? "" : " suspected-cheat=" + f.cheat));
        if (g != null && !g.type.equals("1v1")) lines.add("#teams " + g.teamsLine());
        lines.add(kitLine(f.nameA, f.kitA, f.a));
        lines.add(kitLine(f.nameB, f.kitB, f.b));
        for (String l : lines) System.out.println("Ostinato spectate: " + l);
        synchronized (lastLines) {
            lastLines.addAll(lines);
            while (lastLines.size() > 60) lastLines.remove(0);
        }
        if (!s().spectateSaveLogs.value) return;
        try {
            Path dir = Minecraft.getInstance().gameDir.toPath().resolve("baritone").resolve("spectate");
            Files.createDirectories(dir);
            Files.write(dir.resolve("fights.log"), lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private String kitLine(String name, KitSignatures.Result r, int id) {
        KitEvidence ev = book.evidence(id);
        return String.format(Locale.ROOT, "#kit name=%s label=%s conf=%.2f evidence=%s", name, r == null ? KitSignatures.UNKNOWN : r.label,
                r == null ? 0 : r.confidence, ev == null ? "-" : ev.summary(tick));
    }

    /** The last fight summaries, newest last. */
    public List<String> recent() {
        synchronized (lastLines) {
            return new ArrayList<>(lastLines);
        }
    }
}
