/*
 * Ostinato: new file. Throwaway spar harness for the Scherzo LIVE vs OBSERVE A/B: a chasing zombie, not a standing one.
 */
package baritone.scherzo;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Ostinato: -Dostinato.spartest=true [-Dostinato.spartest.rounds=6] [-Dostinato.spartest.exit=true]. Fresh world, a
 * 41x41 obsidian arena walled 3 high, the bot (diamond sword, or spear with -Dostinato.spartest.kit=spear: in a walled box spear kiting corners itself, iron armour, free to move) against an AI zombie (60 hp, speed 0.3,
 * iron armour) that chases it. Each round ends on a kill (WIN), the bot's death (LOSS) or 600 ticks (TIMEOUT). Per round:
 * ticks, bot damage taken, server hits landed on the zombie, client swings (attack-strength resets). The zombie wears a helmet so it does not burn. One Scherzo mode per JVM (-Dostinato.scherzo).
 * Measures the whole fight policy against one simple mover: not a player, not strafing, not a human.
 */
public final class ScherzoSparTest implements AbstractGameEventListener {
    private static final int LIMIT = 600;
    private final Baritone baritone;
    private final int rounds = Integer.getInteger("ostinato.spartest.rounds", 6);
    private int state, wait, round, ticks, swings, hits;
    private volatile boolean ready;
    private volatile int targetId = -1;
    private volatile float zHp, pHp = 20, lastZHp;
    private volatile boolean zDead, pDead;
    private boolean swinging;
    private final int[] result = new int[3];
    private long ttkSum; private int ttkN; private double dmgSum; private long swingSum, hitSum;

    private ScherzoSparTest(Baritone b) { this.baritone = b; }

    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.spartest")) b.getGameEventHandler().registerEventListener(new ScherzoSparTest(b));
    }

    private static void log(String s) { System.out.println("SPARTEST " + s); }

    @Override public void onTick(TickEvent event) {
        if (event.getState() != EventState.PRE) return;
        Minecraft mc = Minecraft.getInstance();
        if (state == 0 && mc.gui.screen() instanceof TitleScreen) {
            state = 1; wait = 40; mc.options.pauseOnLostFocus = false;
            CreateWorldScreen.openFresh(mc, () -> {});
        } else if (state == 1 && --wait <= 0 && mc.gui.screen() instanceof CreateWorldScreen screen) {
            screen.getUiState().setSeed("71007");
            for (var child : screen.children()) if (child instanceof Button button && button.getMessage().getString().contains("Create New World")) {
                state = 2;
                button.onPress(new net.minecraft.client.input.InputWithModifiers() { public int input() { return 0; } public int modifiers() { return 0; } });
                break;
            }
        } else if (state == 2 && mc.player != null && mc.getSingleplayerServer() != null && mc.level != null) {
            if (++wait < 60) return; // let the world settle
            state = 3; setup(mc, true);
        } else if (state == 3 && ready && mc.level.getEntity(targetId) != null && mc.gui.screen() == null) {
            ((baritone.process.PvpProcess) baritone.getPvpProcess()).attack(e -> e.getId() == targetId, "spartest");
            state = 4; ticks = 0; swings = 0; hits = 0; lastZHp = 60; swinging = false; zDead = pDead = false; pHp = 20; zHp = 60;
        } else if (state == 4) {
            ticks++;
            boolean s = mc.player.getAttackStrengthScale(0) < 0.3f; // attack strength resets on every swing
            if (s && !swinging) swings++;
            swinging = s;
            var server = mc.getSingleplayerServer(); var id = mc.player.getUUID(); int tid = targetId;
            server.execute(() -> {
                var p = server.getPlayerList().getPlayer(id);
                var z = server.overworld().getEntity(tid);
                if (p != null) { pHp = p.getHealth(); pDead = p.isDeadOrDying(); }
                if (tid != targetId) return; // a poll queued for the previous round's zombie
                if (z instanceof Zombie zz) { zHp = zz.getHealth(); zDead = zz.isDeadOrDying(); } else zDead = true;
            });
            if (zHp < lastZHp - 0.01f) hits++;
            lastZHp = zHp;
            if (ticks < 5) return; // polls lag a tick; never end on the previous round's state
            String end = zDead ? "WIN" : pDead || pHp <= 0 ? "LOSS" : ticks >= LIMIT ? "TIMEOUT" : null;
            if (end == null) return;
            baritone.getPvpProcess().onLostControl();
            float taken = 20 - pHp;
            result[end.equals("WIN") ? 0 : end.equals("LOSS") ? 1 : 2]++;
            if (end.equals("WIN")) { ttkSum += ticks; ttkN++; }
            dmgSum += taken; swingSum += swings; hitSum += hits;
            log(String.format(java.util.Locale.ROOT, "ROUND %d %s ticks=%d taken=%.1f hits=%d swings=%d zombieHp=%.1f mode=%s",
                    round + 1, end, ticks, taken, hits, swings, Math.max(0, zHp), System.getProperty("ostinato.scherzo", "observe")));
            if (++round >= rounds) {
                log(String.format(java.util.Locale.ROOT, "SUMMARY mode=%s rounds=%d win=%d loss=%d timeout=%d meanTtkWin=%.1f meanTaken=%.2f hits=%d swings=%d",
                        System.getProperty("ostinato.scherzo", "observe"), rounds, result[0], result[1], result[2],
                        ttkN == 0 ? Double.NaN : (double) ttkSum / ttkN, dmgSum / rounds, hitSum, swingSum));
                state = 9;
                if (Boolean.getBoolean("ostinato.spartest.exit")) mc.stop();
            } else { state = 3; ready = false; zDead = pDead = false; pHp = 20; zHp = 60; setup(mc, false); }
        }
    }

    private void setup(Minecraft mc, boolean arena) {
        ready = false;
        var server = mc.getSingleplayerServer(); var id = mc.player.getUUID();
        server.execute(() -> {
            var p = server.getPlayerList().getPlayer(id);
            var level = p.level();
            server.setDifficulty(Difficulty.NORMAL, true);
            if (arena) for (int x = -21; x <= 21; x++) for (int z = -21; z <= 21; z++) {
                level.setBlock(new BlockPos(x, 249, z), Blocks.OBSIDIAN.defaultBlockState(), 3);
                boolean wall = Math.abs(x) == 21 || Math.abs(z) == 21;
                for (int y = 250; y <= 252; y++) level.setBlock(new BlockPos(x, y, z), (wall ? Blocks.OBSIDIAN : Blocks.AIR).defaultBlockState(), 3);
            }
            for (var e : level.getEntitiesOfClass(Zombie.class, new net.minecraft.world.phys.AABB(-30, 240, -30, 30, 260, 30))) e.discard();
            p.teleportTo(0.5, 250, -6.5);
            p.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            p.setHealth(20); p.getFoodData().setFoodLevel(20); p.removeAllEffects();
            p.getInventory().clearContent();
            p.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack("spear".equals(System.getProperty("ostinato.spartest.kit", "sword")) ? Items.DIAMOND_SPEAR : Items.DIAMOND_SWORD));
            p.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            p.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            p.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            var zombie = new Zombie(level);
            zombie.getAttribute(Attributes.MAX_HEALTH).setBaseValue(60); zombie.setHealth(60);
            zombie.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0.3);
            zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            zombie.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            zombie.setPersistenceRequired();
            zombie.setPos(0.5, 250, 6.5);
            level.addFreshEntity(zombie);
            zombie.setTarget(p);
            targetId = zombie.getId();
            zHp = 60;
            ready = true;
        });
    }
}