package baritone.threat;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.util.*;

/** Opt-in disposable-world observation oracle. No combat, movement controller or existing save is used. */
public final class ThreatLiveTest implements AbstractGameEventListener {
    private final Baritone baritone;
    private int state, wait, covered = -1;
    private volatile UUID zombie;
    private volatile boolean ready;
    private final List<Long> timings = new ArrayList<>();
    private long previous;
    private ThreatLiveTest(Baritone b) { baritone = b; }
    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.threattest")) b.getGameEventHandler().registerEventListener(new ThreatLiveTest(b));
    }
    private void log(String message) {
        System.out.println("THREATTEST " + message);
        try { Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("threattest-result.txt"),
                "THREATTEST " + message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    @Override public void onTick(TickEvent e) {
        if (e.getState() != EventState.PRE) return;
        Minecraft mc = Minecraft.getInstance();
        if (state == 0 && mc.gui.screen() instanceof TitleScreen) {
            state = 1; mc.options.pauseOnLostFocus = false;
            CreateWorldScreen.openFresh(mc, () -> {}); wait = 40;
        } else if (state == 1 && --wait <= 0 && mc.gui.screen() instanceof CreateWorldScreen screen) {
            screen.getUiState().setSeed("71006");
            for (var child : screen.children()) if (child instanceof Button button && button.getMessage().getString().contains("Create New World")) {
                state = 2; wait = 100;
                button.onPress(new net.minecraft.client.input.InputWithModifiers() {
                    public int input() { return 0; } public int modifiers() { return 0; }
                }); break;
            }
        } else if (state == 2 && mc.player != null && mc.getSingleplayerServer() != null && --wait <= 0) {
            state = 3; wait = 100; ready = false;
            mc.getSingleplayerServer().execute(() -> {
                try {
                    var level = mc.getSingleplayerServer().overworld();
                    for (int x=-5;x<=6;x++) for (int z=-5;z<=5;z++) {
                        level.setBlock(new BlockPos(x,279,z), Blocks.STONE.defaultBlockState(),3);
                        for (int y=280;y<284;y++) level.setBlock(new BlockPos(x,y,z),Blocks.AIR.defaultBlockState(),3);
                    }
                    for (int y=280;y<284;y++) for (int z=-3;z<=3;z++) level.setBlock(new BlockPos(2,y,z),Blocks.STONE.defaultBlockState(),3);
                    var player = mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
                    player.teleportTo(0.5,280,0.5);
                    Zombie mob = new Zombie(level); mob.setNoAi(true); mob.setNoGravity(true);
                    mob.setPos(4.5,280,0.5); mob.setPersistenceRequired(); level.addFreshEntity(mob); zombie = mob.getUUID();
                } catch (Throwable failure) { log("FAIL setup " + failure); }
                finally { ready = true; }
            });
        } else if ((state == 3 || state == 4) && ready && mc.player != null) {
            long total = baritone.getThreatAssessment().telemetry().totalNanos();
            if (previous != 0) timings.add(total - previous); previous = total;
            if (--wait > 0) return;
            var snapshot = baritone.getThreatAssessment().latest();
            var result = snapshot.sources().stream().filter(a -> a.source().equals(zombie)).findFirst();
            if (result.isEmpty()) { finish("FAIL missing controlled source status=" + snapshot.status()); return; }
            log((state == 3 ? "covered " : "near-open ") + result.get());
            if (state == 3) {
                covered = result.get().level(); state = 4; wait = 100; ready = false;
                mc.getSingleplayerServer().execute(() -> {
                    var level = mc.getSingleplayerServer().overworld();
                    for (int y=280;y<284;y++) for (int z=-3;z<=3;z++) level.setBlock(new BlockPos(2,y,z),Blocks.AIR.defaultBlockState(),3);
                    var mob = level.getEntity(zombie); if (mob != null) mob.setPos(1.3,280,0.5);
                    ready = true;
                });
            } else finish(result.get().level() > covered ? "PASS covered < near-open; observation only, no attack outcome tested" : "FAIL no contextual distinction");
        }
    }
    private void finish(String result) {
        log(result);
        if (!timings.isEmpty()) {
            Collections.sort(timings); int n=timings.size();
            log("service ns samples="+n+" p50="+timings.get(n/2)+" p95="+timings.get(n*95/100)+" p99="+timings.get(n*99/100)+" max="+timings.getLast());
        }
        log("telemetry " + baritone.getThreatAssessment().telemetry());
        state=5;
        if (Boolean.getBoolean("ostinato.threattest.exit")) Minecraft.getInstance().stop();
    }
}
