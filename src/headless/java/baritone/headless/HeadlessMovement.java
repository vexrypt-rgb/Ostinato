/*
 * Ostinato: fully headless 26.3 protocol bot (Phase 2). New file, replaces no Baritone code.
 *
 * Drives Ostinato's pure-Java PlayerSim from scripted movement intents and turns its predicted
 * position into vanilla serverbound movement packets, one per 20 TPS client tick. No KeyboardInput,
 * no InputOverrideHandler, no LocalPlayer: the sim IS the movement authority for this process.
 */
package baritone.headless;

import baritone.pathing.kinematic.BsiWorld;
import baritone.pathing.kinematic.PlayerSim;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HeadlessMovement {

    /** One contiguous run of identical key intents. */
    public static final class Step {
        public final int ticks;
        public final float yaw;
        /** Look pitch in degrees, sent to the server and used by water swimming. 0 = horizontal. */
        public final float pitch;
        /** 1 forward, -1 back, 0 none. */
        public final int forward;
        /** +1 left, -1 right, 0 none. */
        public final int strafe;
        public final boolean sprint;
        public final boolean jump;
        public final boolean shift;
        public final String label;

        public Step(int ticks, float yaw, int forward, int strafe,
                    boolean sprint, boolean jump, boolean shift, String label) {
            this(ticks, yaw, 0f, forward, strafe, sprint, jump, shift, label);
        }

        public Step(int ticks, float yaw, float pitch, int forward, int strafe,
                    boolean sprint, boolean jump, boolean shift, String label) {
            this.ticks = ticks;
            this.yaw = yaw;
            this.pitch = pitch;
            this.forward = forward;
            this.strafe = strafe;
            this.sprint = sprint;
            this.jump = jump;
            this.shift = shift;
            this.label = label;
        }

        @Override
        public String toString() {
            return ticks + " " + yaw + " " + pitch + " " + forward + " " + strafe + " "
                    + (sprint ? 1 : 0) + " " + (jump ? 1 : 0) + " " + (shift ? 1 : 0) + " #" + label;
        }
    }

    private static final Step IDLE = new Step(Integer.MAX_VALUE, 0f, 0, 0, false, false, false, "idle");

    /** Ostinato Phase 3: what ticks a movement when a live executor, not a scripted course, owns the keys. */
    public interface Driver {
        // Ostinato: null yields to the course; a Step owns this tick without sending packets.
        Step tick(HeadlessBot bot, HeadlessMovement movement);
        default void afterTick(HeadlessMovement movement) { }
        default void afterPackets(HeadlessBot bot) { }
        default float[] pollAnalog() { return null; }
    }

    private Driver driver;
    // Ostinato: PlayerSim keeps no rotation; retain the orientation of its latest input.
    private float yaw, pitch;
    private boolean shift;
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public boolean shift() { return shift; }
    public void rotation(float yaw, float pitch) { this.yaw = yaw; this.pitch = pitch; }

    public void setDriver(Driver driver) {
        this.driver = driver;
    }

    private final HeadlessBlockStateInterface bsi;
    private final PlayerSim sim;
    private final List<Step> steps;
    private int stepIndex;
    private int stepTick;
    private boolean started;
    private boolean finished;
    private Input lastInput = Input.EMPTY;
    private boolean lastSprinting;
    private long simTicks;
    private long positionPackets;
    private long inputPackets;
    private long sprintPackets;
    private long resyncs;
    private double reportedX;
    private double reportedY;
    private double reportedZ;
    private final List<String> log = Collections.synchronizedList(new ArrayList<>());

    public HeadlessMovement(HeadlessBlockStateInterface bsi, List<Step> steps) {
        this.bsi = bsi;
        this.sim = new PlayerSim(new BsiWorld(bsi));
        this.steps = List.copyOf(steps);
    }

    public static List<Step> parse(List<String> lines) {
        List<Step> out = new ArrayList<>();
        int lineNo = 0;
        for (String raw : lines) {
            lineNo++;
            String line = raw;
            int hash = line.indexOf('#');
            String label = "";
            if (hash >= 0) {
                label = line.substring(hash + 1).trim();
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] t = line.split("\\s+");
            if (t.length != 7 && t.length != 8) {
                throw new IllegalArgumentException("course line " + lineNo
                        + ": expected 'ticks yaw [pitch] forward strafe sprint jump shift', got: " + line);
            }
            int ticks = Integer.parseInt(t[0]);
            if (ticks <= 0) {
                throw new IllegalArgumentException("course line " + lineNo + ": ticks must be positive");
            }
            float yaw = Float.parseFloat(t[1]);
            // Ostinato: pitch is optional (column 2) so the flat/walk courses stay 7-column; only water
            // swimming needs a look angle, and our courses swim horizontally at pitch 0.
            float pitch = t.length == 8 ? Float.parseFloat(t[2]) : 0f;
            int arg0 = t.length == 8 ? 2 : 1;
            out.add(new Step(ticks, yaw, pitch, Integer.parseInt(t[arg0 + 1]),
                    Integer.parseInt(t[arg0 + 2]), !t[arg0 + 3].equals("0"), !t[arg0 + 4].equals("0"),
                    !t[arg0 + 5].equals("0"), label.isEmpty() ? "step" + out.size() : label));
        }
        return out;
    }

    public PlayerSim sim() {
        return sim;
    }

    public HeadlessBlockStateInterface blockStates() {
        return bsi;
    }

    public boolean finished() {
        return finished;
    }

    public boolean started() {
        return started;
    }

    /** True while a scripted course still has steps left. An empty course never runs. */
    public boolean courseRunning() {
        return !steps.isEmpty() && !finished && stepIndex < steps.size();
    }

    /** Ticks already consumed in the current course step. A live path does not consume these. */
    public int courseStepTick() {
        return stepTick;
    }

    public long simTicks() {
        return simTicks;
    }

    public long positionPackets() {
        return positionPackets;
    }

    public long inputPackets() {
        return inputPackets;
    }

    public long sprintPackets() {
        return sprintPackets;
    }

    public long resyncs() {
        return resyncs;
    }

    public double reportedX() {
        return reportedX;
    }

    public double reportedY() {
        return reportedY;
    }

    public double reportedZ() {
        return reportedZ;
    }

    public List<String> log() {
        synchronized (log) {
            return new ArrayList<>(log);
        }
    }

    private Step current() {
        if (stepIndex >= steps.size()) {
            return idleStep();
        }
        return steps.get(stepIndex);
    }

    private Step idleStep() {
        return new Step(Integer.MAX_VALUE, yaw, pitch, 0, 0, false, false, false, "idle");
    }

    /** Seed the sim from the server-confirmed spawn position before the first tick. */
    public void start(double x, double y, double z, boolean onGround) {
        sim.x = x;
        sim.y = y;
        sim.z = z;
        sim.vx = sim.vy = sim.vz = 0;
        sim.onGround = onGround;
        sim.sprinting = false;
        sim.swimming = false;
        sim.collidedH = false;
        sim.jumpTicks = 0;
        reportedX = x;
        reportedY = y;
        reportedZ = z;
        started = true;
        log.add(String.format("start sim=(%.9f,%.9f,%.9f) onGround=%s steps=%d", x, y, z, onGround, steps.size()));
    }

    /** A server teleport/correction overrides the sim; everything after it is measured from here. */
    public void resync(double x, double y, double z, boolean onGround) {
        sim.x = x;
        sim.y = y;
        sim.z = z;
        sim.vx = sim.vy = sim.vz = 0;
        sim.onGround = onGround;
        sim.sprinting = false;
        sim.jumpTicks = 0;
        reportedX = x;
        reportedY = y;
        reportedZ = z;
        resyncs++;
        log.add(String.format("resync #%d -> (%.9f,%.9f,%.9f) onGround=%s", resyncs, x, y, z, onGround));
    }

    /** Knockback: the server owns velocity, so write it straight into the sim. */
    public void applyVelocity(Vec3 v) {
        sim.vx = v.x;
        sim.vy = v.y;
        sim.vz = v.z;
        log.add("velocity -> " + v);
    }

    /** Exactly one movement packet and one ClientTickEnd per 20 TPS tick. */
    public void tick(HeadlessBot bot) {
        if (!started) {
            // Ostinato: seed the look the spawn packet confirmed before a live driver reads the body.
            rotation(bot.yRot(), bot.xRot());
            start(bot.x(), bot.y(), bot.z(), bot.onGround());
        }
        // Ostinato: live intent is selected before the one shared simulation/packet path.
        Step live = driver == null ? null : driver.tick(bot, this);
        // When pathing is idle, keep the last look and apply gravity — do not snap to the
        // placeholder IDLE yaw/pitch of 0, and do not skip the sim.
        Step step = live != null ? live : (courseRunning() ? current() : idleStep());
        yaw = step.yaw;
        pitch = step.pitch;
        shift = step.shift;
        float[] analog = live == null ? null : driver.pollAnalog();
        Input input = new Input(step.forward > 0, step.forward < 0, step.strafe > 0,
                step.strafe < 0, step.jump, step.shift, step.sprint);
        if (!input.equals(lastInput)) {
            bot.send(new ServerboundPlayerInputPacket(input));
            lastInput = input;
            inputPackets++;
        }

        // Ostinato: the vanilla client branches travel() on whether the player is in a fluid. Match it so the
        // sim predicts water physics instead of air physics; otherwise the server's authoritative position
        // (computed with travelInWater) diverges from the reported sim position and we get a correction.
        boolean inFluid = sim.inWater() || sim.swimming;
        if (inFluid) {
            sim.tickWater(step.yaw, step.pitch, step.forward > 0, step.sprint, step.jump, step.shift);
        } else if (live != null) {
            // Ostinato: retain PlayerSim physics; use its existing sneak/impulse entry point.
            float[] keys = PlayerSim.keyInput(step.forward, step.strafe, false, step.shift);
            if (analog != null) {
                double length = Math.max(1.0, Math.hypot(analog[0], analog[1]));
                float scale = step.shift ? 0.294f : 0.98f;
                keys = new float[]{(float) (analog[0] / length * scale), (float) (analog[1] / length * scale)};
            }
            sim.tickImpulse(step.yaw, keys[0], keys[1], step.forward > 0, step.sprint, step.jump, step.shift);
        } else if (courseRunning()) {
            sim.tick(step.yaw, step.forward, step.strafe, step.sprint, step.jump);
        } else {
            // Ostinato: no keys, but still fall and land. Skipping this after a finished pillar
            // left the last mid-air pose frozen at onGround=false.
            sim.tickImpulse(step.yaw, 0, 0, false, false, false, false);
        }
        simTicks++;
        // Ostinato: publish the stepped body before readers see the next tick.
        if (driver != null) driver.afterTick(this);

        if (sim.sprinting != lastSprinting) {
            bot.send(playerCommand(bot.playerId(), sim.sprinting
                    ? ServerboundPlayerCommandPacket.Action.START_SPRINTING
                    : ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            lastSprinting = sim.sprinting;
            sprintPackets++;
        }

        reportedX = sim.x;
        reportedY = sim.y;
        reportedZ = sim.z;
        bot.setReportedPosition(sim.x, sim.y, sim.z, sim.onGround, step.yaw, step.pitch);
        bot.send(new ServerboundMovePlayerPacket.PosRot(
                sim.x, sim.y, sim.z, step.yaw, step.pitch, sim.onGround, sim.collidedH));
        positionPackets++;
        // Must be last in the client tick: the server clears receivedPositionThisTick here.
        if (driver != null) {
            driver.afterPackets(bot);
        }
        bot.send(ServerboundClientTickEndPacket.INSTANCE);

        // Ostinato: pathing pauses the course at its exact current tick.
        if (live != null) return;
        stepTick++;
        if (stepTick >= step.ticks && step != IDLE) {
            stepIndex++;
            stepTick = 0;
            log.add(String.format("t=%d after %s sim=(%.9f,%.9f,%.9f) vx=%.6f vy=%.6f vz=%.6f onGround=%s",
                    simTicks, step, sim.x, sim.y, sim.z, sim.vx, sim.vy, sim.vz, sim.onGround));
            if (stepIndex >= steps.size() && !finished) {
                finished = true;
                log.add(String.format("course finished at t=%d sim=(%.9f,%.9f,%.9f)",
                        simTicks, sim.x, sim.y, sim.z));
            }
        }
    }

    /**
     * 26.3's ServerboundPlayerCommandPacket has only Entity-based public constructors, and a plain
     * JVM has no player Entity. Decode it through vanilla's own private wire constructor instead of
     * inventing a packet class or fabricating an Entity.
     */
    static ServerboundPlayerCommandPacket playerCommand(int entityId, ServerboundPlayerCommandPacket.Action action) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeVarInt(entityId);
            buf.writeEnum(action);
            buf.writeVarInt(0);
            return ServerboundPlayerCommandPacket.STREAM_CODEC.decode(buf);
        } finally {
            buf.release();
        }
    }

    public String status() {
        return String.format("movement started=%s finished=%s simTicks=%d positions=%d inputs=%d sprints=%d resyncs=%d%n"
                        + "  sim=(%.9f,%.9f,%.9f) vel=(%.6f,%.6f,%.6f) onGround=%s sprinting=%s swimming=%s collidedH=%s%n"
                        + "  reported=(%.9f,%.9f,%.9f) step=%d/%d%n",
                started, finished, simTicks, positionPackets, inputPackets, sprintPackets, resyncs,
                sim.x, sim.y, sim.z, sim.vx, sim.vy, sim.vz, sim.onGround, sim.sprinting, sim.swimming,
                sim.collidedH, reportedX, reportedY, reportedZ,
                Math.min(stepIndex + 1, steps.size()), steps.size());
    }
}
