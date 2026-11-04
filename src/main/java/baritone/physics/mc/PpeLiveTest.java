/* Ostinato: opt-in disposable-world oracle for 26.3 TNT. */
package baritone.physics.mc;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.physics.*;
import baritone.physics.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;

/** Creates a fresh world; never runs in an already-open save. All oracle world reads occur on the server thread. */
public final class PpeLiveTest implements AbstractGameEventListener {
    private final Baritone baritone;
    private int state, wait, samples;
    private long tickStart;
    private final long[] idle = new long[200], active = new long[200], calls = new long[200];
    private PpeLiveTest(Baritone baritone) { this.baritone = baritone; }

    private volatile boolean done;

    public static void install(Baritone b) {
        if (Boolean.getBoolean("ostinato.ppetest")) b.getGameEventHandler().registerEventListener(new PpeLiveTest(b));
    }

    private static void log(String message) {
        String line = "PPETEST " + message;
        System.out.println(line);
        try {
            Files.writeString(Minecraft.getInstance().gameDirectory.toPath().resolve("ppetest-result.txt"),
                    line + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    @Override public void onPostTick(TickEvent event) { onTick(event); }

    @Override public void onTick(TickEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getState() == EventState.POST) {
            if (state == 2 && tickStart != 0) {
                long duration = System.nanoTime() - tickStart;
                if (samples >= 100 && samples < 300) idle[samples - 100] = duration;
                if (samples >= 300 && samples < 500) active[samples - 300] = duration;
                samples++;
                tickStart = 0;
            }
            return;
        }
        if (state == 0 && mc.gui.screen() instanceof TitleScreen) {
            state = 1;
            mc.options.pauseOnLostFocus = false;
            CreateWorldScreen.openFresh(mc, () -> {});
            wait = 40;
        } else if (state == 1 && --wait <= 0 && mc.gui.screen() instanceof CreateWorldScreen screen) {
            screen.getUiState().setSeed("71006");
            for (var child : screen.children()) if (child instanceof Button button
                    && button.getMessage().getString().contains("Create New World")) {
                state = 2;
                button.onPress(new net.minecraft.client.input.InputWithModifiers() {
                    public int input() { return 0; }
                    public int modifiers() { return 0; }
                });
                break;
            }
        } else if (state == 2 && mc.player != null && mc.getSingleplayerServer() != null) {
            if (samples < 500) {
                tickStart = System.nanoTime();
                if (samples >= 300) {
                    long start = System.nanoTime();
                    var engine = baritone.getPredictivePhysics();
                    var request = PredictionRequest.fall(engine.capture(mc.player), 60).build();
                    for (int consumer = 0; consumer < 5; consumer++) engine.predict(request);
                    calls[samples - 300] = System.nanoTime() - start;
                }
                return;
            }
            summary("idle client tick", idle);
            summary("active client tick (5 equivalent fall requests)", active);
            summary("capture + 5 inline calls", calls);
            log("LIVE " + baritone.getPredictivePhysics().telemetry().summary());
            state = 3;
            mc.getSingleplayerServer().submit(() -> {
                try {
                    ServerLevel level = mc.getSingleplayerServer().overworld();
                    runCase(level, "stone", Blocks.STONE.defaultBlockState());
                    runCase(level, "slab", Blocks.STONE_SLAB.defaultBlockState());
                    runCase(level, "slime", Blocks.SLIME_BLOCK.defaultBlockState());
                    log("RESULT PASS 3 surfaces x 80 vanilla TNT ticks; position/velocity and fuse");
                } catch (Throwable failure) {
                    log("RESULT FAIL " + failure);
                    failure.printStackTrace();
                } finally { done = true; }
            });
        } else if (done && state == 3) {
            state = 4;
            if (Boolean.getBoolean("ostinato.ppetest.exit")) mc.stop();
        }
    }

    private static void summary(String name, long[] values) {
        long sum = 0;
        for (long value : values) sum += value;
        java.util.Arrays.sort(values);
        log(String.format(java.util.Locale.ROOT, "%s n=%d us avg=%.1f p95=%.1f p99=%.1f max=%.1f", name,
                values.length, sum / 1000.0 / values.length, values[189] / 1000.0, values[197] / 1000.0, values[199] / 1000.0));
    }

    private static void runCase(ServerLevel level, String name, BlockState floor) {
        for (int x = -8; x < 8; x++) for (int z = -8; z < 8; z++)
            level.setBlock(new BlockPos(x, 280, z), floor, 2);
        Terrain terrain = snapshot(level);
        PrimedTnt tnt = new PrimedTnt(level, 0.5, 289, 0.5, null);
        tnt.setDeltaMovement(0.08, 0.2, 0.03);
        // Capture the exact starting server state; no interpolation or client capture is being validated here.
        BodyState initial = BodyCapture.read(tnt, true, null);
        Predictor predictor = new Predictor();
        double maxError = 0;
        for (int tick = 1; tick <= 80; tick++) {
            PredictionRequest request = PredictionRequest.trajectory(initial, tick).fidelity(PredictionRequest.Fidelity.HIGH).build();
            PredictionResult result = predictor.run(request, terrain, request.fidelity, () -> false);
            tnt.tick();
            double error = Math.max(Math.max(Math.abs(result.x - tnt.getX()), Math.abs(result.y - tnt.getY())), Math.abs(result.z - tnt.getZ()));
            var velocity = tnt.getDeltaMovement();
            error = Math.max(error, Math.max(Math.max(Math.abs(result.vx - velocity.x), Math.abs(result.vy - velocity.y)), Math.abs(result.vz - velocity.z)));
            maxError = Math.max(error, maxError);
            if (result.status != PredictionResult.Status.OK || error > 1e-8 || tnt.getFuse() != 80 - tick
                    || (result.explosion != null) != (tick == 80))
                throw new AssertionError(name + " tick=" + tick + " error=" + error + " " + result);
        }
        log(name + " PASS maxError=" + maxError + " removed=" + tnt.isRemoved());
    }

    private static Terrain snapshot(ServerLevel level) {
        Terrain.Builder builder = Terrain.builder(level.getMinY(), level.getMinY() + level.getHeight(), 0, 16);
        BlockInfos infos = new BlockInfos();
        for (int sx = -1; sx <= 0; sx++) for (int sz = -1; sz <= 0; sz++) for (int sy = 16; sy <= 19; sy++) {
            var palette = new ArrayList<BlockInfo>();
            var ids = new IdentityHashMap<BlockState, Integer>();
            short[] cells = new short[4096];
            for (int i = 0; i < 4096; i++) {
                BlockPos pos = new BlockPos(sx * 16 + (i & 15), sy * 16 + (i >> 8), sz * 16 + (i >> 4 & 15));
                BlockState state = level.getBlockState(pos);
                Integer id = ids.get(state);
                if (id == null) {
                    id = palette.size();
                    ids.put(state, id);
                    palette.add(infos.get(state, level, pos));
                }
                cells[i] = id.shortValue();
            }
            builder.put(sx, sy, sz, new Terrain.Section(palette.toArray(BlockInfo[]::new), cells, 0, 0));
        }
        return builder.build();
    }
}
