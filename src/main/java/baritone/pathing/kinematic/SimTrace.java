package baritone.pathing.kinematic;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * Measures how well {@link PlayerSim} predicts the real client. Each tick a mover reports the real player's state and the
 * controls it is about to apply; the recorder holds two predictions to compare the next state against:
 * <ul>
 * <li><b>one step</b>: the real state of the last tick run forward one tick. Its error is the model's own, with no drift
 * stacked on top: wrong friction, collision or input handling shows up here.</li>
 * <li><b>open loop</b>: the state at the start of the movement run forward on the same controls and never corrected. Its error
 * at landing is what a template found offline would be off by.</li>
 * </ul>
 * Yaw is logged too: the sim assumes the commanded yaw is applied at once, and the real look lags it.
 * Pure Java, so it is tested without a game; the movers switch it on with the {@code kinematicTrace} setting.
 */
public final class SimTrace {

    /** A step error past this many blocks counts as the model having diverged. */
    public static final double DIVERGED = 0.05;

    /** Off unless a mover turns it on, so tests and tools do not write into the working directory. */
    public static volatile boolean files;
    private static boolean csvFailed;
    /** The trace of the movement that finished last, for benches that want its numbers. */
    public static volatile SimTrace last;

    private final String label;
    private final PlayerSim pred, open;
    private boolean havePred, haveOpen;
    private float cmdYaw;
    private int tick, mismatches, firstDiverged = -1;
    private double maxStep, sumSq, openErr, maxYaw;

    public SimTrace(String label, PlayerSim.World world) {
        this.label = label;
        this.pred = new PlayerSim(world);
        this.open = new PlayerSim(world);
    }

    /** One tick's comparison; every field is real minus predicted. */
    public static final class Row {
        public final int tick;
        public final double dx, dy, dz, dvx, dvy, dvz, openDx, openDy, openDz, yaw;
        public final boolean groundPredicted, groundActual;

        Row(int tick, PlayerSim actual, PlayerSim pred, PlayerSim open, double yaw) {
            this.tick = tick;
            dx = actual.x - pred.x;
            dy = actual.y - pred.y;
            dz = actual.z - pred.z;
            dvx = actual.vx - pred.vx;
            dvy = actual.vy - pred.vy;
            dvz = actual.vz - pred.vz;
            openDx = actual.x - open.x;
            openDy = actual.y - open.y;
            openDz = actual.z - open.z;
            this.yaw = yaw;
            groundPredicted = pred.onGround;
            groundActual = actual.onGround;
        }

        public double step() {
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        public double openError() {
            return Math.sqrt(openDx * openDx + openDy * openDy + openDz * openDz);
        }
    }

    /** Compare the real state now with what the last tick's controls should have produced. Null on the first tick. */
    public Row observe(PlayerSim actual, float actualYaw) {
        Row r = null;
        if (havePred) {
            r = new Row(tick, actual, pred, open, wrap(actualYaw - cmdYaw));
            double e = r.step();
            maxStep = Math.max(maxStep, e);
            sumSq += e * e;
            openErr = r.openError();
            maxYaw = Math.max(maxYaw, Math.abs(r.yaw));
            if (r.groundPredicted != r.groundActual) mismatches++;
            if (firstDiverged < 0 && e > DIVERGED) firstDiverged = tick;
            write(r);
        }
        tick++;
        return r;
    }

    /** The controls the mover applies this tick, from the state just observed. */
    public void commit(PlayerSim actual, float yaw, int input, boolean sprint, boolean jump) {
        pred.copyFrom(actual);
        pred.tick(yaw, input, sprint, jump);
        havePred = true;
        if (!haveOpen) {
            open.copyFrom(actual);
            haveOpen = true;
        }
        open.tick(yaw, input, sprint, jump);
        cmdYaw = yaw;
    }

    /** Whether the model stayed within {@link #DIVERGED} of the real player on every tick. */
    public boolean held() {
        return firstDiverged < 0;
    }

    public int ticks() {
        return tick;
    }

    public double maxStep() {
        return maxStep;
    }

    public double rmsStep() {
        return tick > 1 ? Math.sqrt(sumSq / (tick - 1)) : 0;
    }

    public double openLoopError() {
        return openErr;
    }

    public int firstDiverged() {
        return firstDiverged;
    }

    public int groundMismatches() {
        return mismatches;
    }

    public double maxYawLag() {
        return maxYaw;
    }

    public String summary(boolean ok) {
        return String.format(Locale.ROOT, "SIMTRACE %s ok=%b ticks=%d maxStep=%.4f rmsStep=%.4f firstOver%.2f=%d openLoopEnd=%.3f groundMismatch=%d maxYawLag=%.1f",
                label, ok, tick, maxStep, rmsStep(), DIVERGED, firstDiverged, openErr, mismatches, maxYaw);
    }

    /** Print the summary and append it to {@code simtrace/summary.csv}. */
    public void finish(boolean ok) {
        if (tick == 0) return;
        last = this;
        System.out.println(summary(ok));
        append("summary.csv", String.format(Locale.ROOT, "%s,%b,%d,%.4f,%.4f,%d,%.3f,%d,%.1f", label, ok, tick, maxStep, rmsStep(), firstDiverged, openErr, mismatches, maxYaw));
    }

    private void write(Row r) {
        append("simtrace.csv", String.format(Locale.ROOT, "%s,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.1f,%b,%b",
                label, r.tick, r.dx, r.dy, r.dz, r.dvx, r.dvy, r.dvz, r.openDx, r.openDy, r.openDz, r.yaw, r.groundPredicted, r.groundActual));
    }

    private static synchronized void append(String file, String line) {
        if (!files || csvFailed) return;
        try {
            Path dir = Paths.get("simtrace");
            Files.createDirectories(dir);
            Path p = dir.resolve(file);
            boolean fresh = !Files.exists(p);
            try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(p, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND))) {
                if (fresh) {
                    w.println(file.equals("summary.csv") ? "label,ok,ticks,maxStep,rmsStep,firstDiverged,openLoopEnd,groundMismatch,maxYawLag"
                            : "label,tick,dx,dy,dz,dvx,dvy,dvz,openDx,openDy,openDz,yawLag,groundPredicted,groundActual");
                }
                w.println(line);
            }
        } catch (IOException e) {
            csvFailed = true;
        }
    }

    private static double wrap(double deg) {
        deg %= 360;
        return deg > 180 ? deg - 360 : deg < -180 ? deg + 360 : deg;
    }
}
