package baritone.process;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.GZIPOutputStream;

/**
 * Records every PvP fight as one gzipped text file under {@code pvplogs/}, one line per tick, so a fight against a real
 * person (or a bot) can be read back and used to tune the combat. Disable with -Dostinato.record=false.
 * <pre>
 * #fight  header: label, version, both loadouts
 * t | me: x,y,z,vx,vy,vz,yaw,pitch,hp,abs,gnd,item,flags | tg: same | dist | cd=0.00..1.00 | dec=token | state | events
 * @t ...   opponent events and per-pass features, one line per change (see {@link PvpOpponent}); #opp is their summary
 * #end    result, ticks, damage dealt/taken, attacks
 * </pre>
 * Column 0 is the fight-local tick and is never dropped. {@code cd} is {@code getAttackStrengthScale(0)}.
 * {@code dec} is the action {@link PvpProcess} chose that tick. Flags: B blocking, U using item, S swinging,
 * P sprinting, F fall distance&gt;1.5, W wet. Events: A=we attacked, X=target hurt flash (hit landed),
 * D=damage taken, H=damage dealt.
 */
public final class PvpRecorder {
    public static final boolean ENABLED = !"false".equals(System.getProperty("ostinato.record"));
    private BufferedWriter out;
    private Path file;
    private int ticks;
    private float dealt, taken, lastTargetHp, lastMyHp;
    private int attacks, lastAttacks, hits, lastTargetHurt, lastOffDmg;
    private net.minecraft.world.item.Item lastOff;
    private String targetName;
    private boolean targetDied;
    private PvpOpponent opp;

    public void markWin() {
        targetDied = true;
    }

    public boolean active() {
        return out != null;
    }

    public void begin(Player me, LivingEntity target, String label) {
        if (!ENABLED || out != null) return;
        try {
            Path dir = Paths.get("pvplogs");
            Files.createDirectories(dir);
            targetName = target.getName().getString();
            file = dir.resolve("fight-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + targetName.replaceAll("[^A-Za-z0-9_]", "") + ".log.gz");
            out = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file), true), StandardCharsets.UTF_8));
            ticks = attacks = lastAttacks = hits = lastTargetHurt = 0;
            dealt = taken = 0;
            targetDied = false;
            lastTargetHp = target.getHealth() + target.getAbsorptionAmount();
            lastMyHp = me.getHealth() + me.getAbsorptionAmount();
            opp = new PvpOpponent(this::quiet);
            line("#fight v2 label=" + label + " target=" + targetName);
            line("#me " + gear(me));
            line("#tg " + gear(target));
        } catch (Exception e) {
            out = null;
            System.out.println("PvpRecorder: " + e);
        }
    }

    /**
     * @param dec short token for the action chosen this tick (hit, spear, lunge, block, …); never prose
     */
    public void tick(Player me, LivingEntity target, double dist, String state, String dec, int totalAttacks) {
        if (out == null) return;
        try {
            ticks++;
            float myHp = me.getHealth() + me.getAbsorptionAmount(), tHp = target.getHealth() + target.getAbsorptionAmount();
            StringBuilder ev = new StringBuilder();
            if (totalAttacks > lastAttacks) {
                ev.append('A');
                attacks += totalAttacks - lastAttacks;
            }
            lastAttacks = totalAttacks;
            if (myHp < lastMyHp - 0.01f) {
                ev.append("D").append(f(lastMyHp - myHp));
                taken += lastMyHp - myHp;
            }
            if (tHp < lastTargetHp - 0.01f) {
                ev.append("H").append(f(lastTargetHp - tHp));
                dealt += lastTargetHp - tHp;
            }
            // servers that don't sync other players' health leave tHp frozen: count hits off the hurt flash instead
            if (target.hurtTime > lastTargetHurt && target.hurtTime >= target.hurtDuration - 1) {
                hits++;
                ev.append('X');
            }
            lastTargetHurt = target.hurtTime;
            net.minecraft.world.item.ItemStack off = me.getOffhandItem();
            if (off.getItem() != lastOff) ev.append("O:").append(off.isEmpty() ? "none" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(off.getItem()).getPath());
            lastOff = off.getItem();
            if (off.isDamageableItem() && off.getDamageValue() != lastOffDmg) ev.append("dur").append(off.getMaxDamage() - off.getDamageValue());
            lastOffDmg = off.getDamageValue();
            if (opp != null) opp.observe(ticks, me, target, myHp < lastMyHp - 0.01f);
            lastMyHp = myHp;
            lastTargetHp = tHp;
            float cd = me.getAttackStrengthScale(0.0f);
            String token = dec == null || dec.isEmpty() ? "-" : dec;
            line(ticks + "|" + ent(me) + "|" + ent(target) + "|" + f(dist) + "|cd=" + f(cd) + "|dec=" + token + "|" + state + "|" + ev);
            if (ticks % 100 == 0) out.flush();
        } catch (Exception e) {
            out = null;
        }
    }

    public void end(Player me, String reason) {
        if (out == null) return;
        try {
            // a respawn (health snapping back from near zero) means the fight was lost even if the death screen never ticked
            boolean died = me.isDeadOrDying() || me.getHealth() <= 0 || lastMyHp < 5 && me.getHealth() + me.getAbsorptionAmount() > lastMyHp + 8;
            String result = died ? "death" : targetDied ? "win" : reason;
            if (opp != null) line(opp.summary());
            line("#end result=" + result + " ticks=" + ticks + " dealt=" + f(dealt) + " taken=" + f(taken) + " attacks=" + attacks + " hits=" + hits);
            out.close();
            System.out.println("PvpRecorder: " + file + " (" + result + ", " + ticks + " ticks)");
        } catch (Exception e) {
            System.out.println("PvpRecorder: " + e);
        }
        out = null;
    }

    /** A derived-feature line (for example one spear pass), written as {@code @tick text} among the opponent events. */
    public void note(String s) {
        if (out != null) quiet("@" + ticks + " " + s);
    }

    private void quiet(String s) {
        try {
            line(s);
        } catch (java.io.IOException e) {
            out = null;
        }
    }

    private void line(String s) throws java.io.IOException {
        out.write(s);
        out.write('\n');
    }

    private static String f(double v) {
        return String.format("%.2f", v);
    }

    private static String ent(LivingEntity e) {
        StringBuilder fl = new StringBuilder();
        if (e.isBlocking()) fl.append('B');
        if (e.isUsingItem()) fl.append('U');
        if (e.swinging) fl.append('S');
        if (e.isSprinting()) fl.append('P');
        if (e.fallDistance > 1.5) fl.append('F');
        if (e.isInWater()) fl.append('W');
        var v = e.getDeltaMovement();
        return f(e.getX()) + "," + f(e.getY()) + "," + f(e.getZ()) + "," + f(v.x) + "," + f(v.y) + "," + f(v.z) + ","
                + f(e.getYRot()) + "," + f(e.getXRot()) + "," + f(e.getHealth()) + "," + f(e.getAbsorptionAmount()) + ","
                + (e.onGround() ? 1 : 0) + "," + e.getMainHandItem().getItem().toString().replace("minecraft:", "") + "," + fl;
    }

    private static String gear(LivingEntity e) {
        StringBuilder sb = new StringBuilder();
        for (EquipmentSlot s : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND}) {
            ItemStack st = e.getItemBySlot(s);
            sb.append(s.getName()).append('=').append(st.isEmpty() ? "-" : st.getItem().toString().replace("minecraft:", "")).append(' ');
        }
        if (e instanceof Player p)
            for (int i = 0; i < 9; i++) {
                ItemStack st = p.getInventory().getItem(i);
                if (!st.isEmpty()) sb.append("h").append(i).append('=').append(st.getCount()).append('x').append(st.getItem().toString().replace("minecraft:", "")).append(' ');
            }
        return sb.toString().trim();
    }
}
