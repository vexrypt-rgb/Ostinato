/*
 * Ostinato: new file (Grace Note). Not part of upstream Baritone.
 */
package baritone.gracenote;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.gracenote.GraceNoteOutcome;
import baritone.api.gracenote.IGraceNote;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Opt-in live check of Grace Note in a fresh, disposable survival world (-Dostinato.gracenotetest=true with the test
 * harness on). Never touches an existing save. Builds a stone floor high over spawn and drops the player onto it:
 * observe-only first, then live with no bucket (no takeover allowed), live standing, live while a path is running (the
 * hold has to stop the executor and the goal has to be reached after), and a lethal height last (a failure there kills
 * the player in the throwaway world and ends the run). Every line reports what was measured; nothing is inferred.
 */
public final class GraceNoteLiveTest implements AbstractGameEventListener {
    private static final int FLOOR = 200;
    private static final int GOAL_X = 30;

    private record Scenario(String name, IGraceNote.Mode mode, boolean bucket, int drop, boolean pathing) {}

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("observe-15", IGraceNote.Mode.OBSERVE, true, 15, false),
            new Scenario("live-no-bucket-15", IGraceNote.Mode.LIVE, false, 15, false),
            new Scenario("live-standing-15", IGraceNote.Mode.LIVE, true, 15, false),
            // A nonlethal teleport can become a legitimate MovementFall before detection; Grace Note
            // correctly defers to it. A lethal landing exercises emergency ownership in either case.
            new Scenario("live-pathing-31", IGraceNote.Mode.LIVE, true, 31, true),
            new Scenario("live-lethal-31", IGraceNote.Mode.LIVE, true, 31, false));

    private final Baritone baritone;
    private int state, wait, index;
    private volatile boolean ready;
    private long seen, setupTick, ticks;
    private final List<String> verdicts = new ArrayList<>();
    private final List<Long> timings = new ArrayList<>();
    private long previous;
    private GraceNoteOutcome outcome;

    private GraceNoteLiveTest(Baritone b) {
        baritone = b;
    }

    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.gracenotetest")) b.getGameEventHandler().registerEventListener(new GraceNoteLiveTest(b));
    }

    private void log(String message) {
        System.out.println("GRACENOTETEST " + message);
        try {
            Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("gracenotetest-result.txt"),
                    "GRACENOTETEST " + message + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public void onTick(TickEvent e) {
        if (e.getState() != EventState.PRE) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        if (state == 0 && mc.gui.screen() instanceof TitleScreen) {
            state = 1;
            mc.options.pauseOnLostFocus = false;
            CreateWorldScreen.openFresh(mc, () -> {});
            wait = 40;
        } else if (state == 1 && --wait <= 0 && mc.gui.screen() instanceof CreateWorldScreen screen) {
            screen.getUiState().setSeed("71006");
            for (var child : screen.children()) {
                if (child instanceof Button button && button.getMessage().getString().contains("Create New World")) {
                    state = 2;
                    wait = 100;
                    button.onPress(new net.minecraft.client.input.InputWithModifiers() {
                        public int input() { return 0; }
                        public int modifiers() { return 0; }
                    });
                    break;
                }
            }
        } else if (state == 2 && mc.player != null && mc.getSingleplayerServer() != null && --wait <= 0) {
            state = 3;
            ready = false;
            log("start mode=" + baritone.getGraceNote().mode() + " policy=" + baritone.getGraceNote().policy());
            server(mc, () -> {
                var level = mc.getSingleplayerServer().overworld();
                for (int x = -6; x <= GOAL_X + 6; x++) {
                    for (int z = -6; z <= 6; z++) {
                        level.setBlock(new BlockPos(x, FLOOR, z), Blocks.STONE.defaultBlockState(), 3);
                        for (int y = FLOOR + 1; y <= FLOOR + 36; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                    }
                }
                ServerPlayer p = player(mc);
                p.teleportTo(0.5, FLOOR + 1, 0.5);
            });
            wait = 60;
        } else if (state == 3 && ready && --wait <= 0) {
            next(mc);
        } else if (state == 4) {
            scenario(mc);
        }
    }

    /** Heal, stock the hotbar, put the player on the floor (or start the path), then drop. */
    private void next(Minecraft mc) {
        if (index >= SCENARIOS.size()) {
            finish();
            return;
        }
        Scenario s = SCENARIOS.get(index);
        baritone.getGraceNote().setMode(s.mode());
        outcome = null;
        GraceNoteOutcome last = baritone.getGraceNote().lastOutcome();
        seen = last == null ? 0 : last.sequence();
        ready = false;
        server(mc, () -> {
            ServerPlayer p = player(mc);
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.getInventory().clearContent();
            p.getInventory().setItem(0, new ItemStack(Items.STONE, 16));
            if (s.bucket()) p.getInventory().setItem(3, new ItemStack(Items.WATER_BUCKET));
            p.teleportTo(0.5, FLOOR + 1, 0.5);
            p.resetFallDistance();
        });
        mc.player.getInventory().setSelectedSlot(0);
        state = 4;
        wait = 40;
        setupTick = ticks;
    }

    private int step;

    private void scenario(Minecraft mc) {
        Scenario s = SCENARIOS.get(index);
        long total = baritone.getGraceNote().telemetry().totalNanos();
        if (previous != 0) timings.add(total - previous);
        previous = total;
        if (!ready) return;
        if (step == 0) {
            if (--wait > 0) return;
            if (s.pathing()) {
                baritone.getCustomGoalProcess().setGoalAndPath(new GoalBlock(GOAL_X, FLOOR + 1, 0));
                step = 1;
                wait = 100;
            } else {
                drop(mc, s);
            }
            return;
        }
        if (step == 1) {
            // walking along the floor: drop once the executor is clearly moving the player
            if (mc.player.getX() > 4 || --wait <= 0) {
                log(s.name() + " path running=" + baritone.getPathingBehavior().isPathing() + " x=" + String.format("%.2f", mc.player.getX()));
                drop(mc, s);
            }
            return;
        }
        if (step == 2) {
            GraceNoteOutcome o = baritone.getGraceNote().lastOutcome();
            if (o != null && o.sequence() > seen) {
                outcome = o;
                log(s.name() + " outcome " + o);
                log(s.name() + " trail " + baritone.getGraceNote().trail() + " health=" + mc.player.getHealth() + " alive=" + mc.player.isAlive());
                if (s.pathing()) {
                    step = 3;
                    wait = 600;
                } else {
                    judge(mc, s, null);
                }
                return;
            }
            if (ticks - setupTick > 400) {
                log(s.name() + " no outcome within 400 ticks; status " + baritone.getGraceNote().status());
                judge(mc, s, null);
            }
            return;
        }
        if (step == 3) {
            boolean active = baritone.getCustomGoalProcess().isActive();
            double dx = mc.player.getX() - (GOAL_X + 0.5), dz = mc.player.getZ() - 0.5;
            boolean there = dx * dx + dz * dz < 2.5 && mc.player.onGround();
            if (there || !active || --wait <= 0) {
                String resumed = "goalActive=" + active + " reached=" + there + " pos=" + String.format("%.1f,%.1f,%.1f", mc.player.getX(), mc.player.getY(), mc.player.getZ());
                log(s.name() + " after release: " + resumed);
                baritone.getPathingBehavior().cancelEverything();
                judge(mc, s, there);
            }
        }
    }

    private void drop(Minecraft mc, Scenario s) {
        ready = false;
        double x = mc.player.getX(), z = mc.player.getZ();
        server(mc, () -> {
            ServerPlayer p = player(mc);
            p.teleportTo(x, FLOOR + 1 + s.drop(), z);
            p.resetFallDistance();
        });
        log(s.name() + " drop " + s.drop() + " blocks mode=" + s.mode() + " bucket=" + s.bucket());
        step = 2;
        setupTick = ticks;
    }

    /** What the scenario was meant to show, against what was measured. */
    private void judge(Minecraft mc, Scenario s, Boolean reached) {
        GraceNoteOutcome o = outcome;
        String v;
        if (o == null) {
            v = "FAIL no outcome";
        } else {
            switch (s.mode()) {
                case OBSERVE -> v = o.result() == IGraceNote.Result.OBSERVED && o.requested() < 0 ? "PASS observed, no takeover" : "FAIL " + o.result();
                default -> {
                    if (!s.bucket()) {
                        v = o.requested() < 0 && o.result() == IGraceNote.Result.OBSERVED ? "PASS no takeover without a bucket" : "FAIL " + o.result();
                    } else if (o.result() == IGraceNote.Result.AVOIDED || o.result() == IGraceNote.Result.SURVIVED) {
                        boolean released = o.released() >= 0;
                        v = (released ? "PASS " : "FAIL release not observed, ") + o.result() + " measuredDamage=" + o.measuredDamage()
                                + " recovered=" + o.recovered();
                        if (s.pathing()) v += Boolean.TRUE.equals(reached) ? " resumed to goal" : " FAIL goal not reached after release";
                    } else {
                        v = "FAIL " + o.result() + " " + o.reason();
                    }
                }
            }
        }
        verdicts.add(s.name() + ": " + v);
        log(s.name() + " " + v);
        index++;
        step = 0;
        if (o != null && !o.alive() || !mc.player.isAlive()) {
            log("player died; remaining scenarios skipped");
            index = SCENARIOS.size();
        }
        state = 3;
        wait = 40;
        ready = true;
    }

    private void finish() {
        for (String v : verdicts) log("summary " + v);
        if (!timings.isEmpty()) {
            List<Long> t = new ArrayList<>(timings);
            java.util.Collections.sort(t);
            int n = t.size();
            log("grace note ns/tick samples=" + n + " p50=" + t.get(n / 2) + " p95=" + t.get(n * 95 / 100) + " p99=" + t.get(n * 99 / 100) + " max=" + t.getLast());
        }
        log("telemetry " + baritone.getGraceNote().telemetry());
        log("ppe warm-up ns (once per world, outside the telemetry's max) " + baritone.getGraceNote().warmupNanos());
        state = 5;
        if (Boolean.getBoolean("ostinato.gracenotetest.exit")) Minecraft.getInstance().stop();
    }

    private void server(Minecraft mc, Runnable r) {
        mc.getSingleplayerServer().execute(() -> {
            try {
                r.run();
            } catch (Throwable failure) {
                log("FAIL setup " + failure);
            } finally {
                ready = true;
            }
        });
    }

    private static ServerPlayer player(Minecraft mc) {
        return mc.getSingleplayerServer().getPlayerList().getPlayers().getFirst();
    }
}
