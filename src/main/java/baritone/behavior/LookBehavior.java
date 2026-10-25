/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.behavior;

import baritone.utils.HumanInputCaps;
import baritone.utils.OstinatoRandom;
import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.behavior.look.ForkableRandom;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

public final class LookBehavior extends Behavior implements ILookBehavior {

    /**
     * The current look target, may be {@code null}.
     */
    private Target target;

    /**
     * The rotation known to the server. Returned by {@link #getEffectiveRotation()} for use in {@link IPlayerContext}.
     */
    private Rotation serverRotation;

    /**
     * The last player rotation. Used to restore the player's angle when using free look.
     *
     * @see Settings#freeLook
     */
    private Rotation prevRotation;

    private final AimProcessor processor;

    private final Deque<Float> smoothYawBuffer;
    private final Deque<Float> smoothPitchBuffer;

    public LookBehavior(Baritone baritone) {
        super(baritone);
        this.processor = new AimProcessor(baritone.getPlayerContext());
        this.smoothYawBuffer = new ArrayDeque<>();
        this.smoothPitchBuffer = new ArrayDeque<>();
    }

    @Override
    public void updateTarget(Rotation rotation, boolean blockInteract) {
        if (Baritone.settings().humanLookEverywhere.value && !ctx.player().isFallFlying()) this.human = true;
        // a humanized look is the player's own mouse: the camera turns, never a silent server-only rotation
        this.target = new Target(rotation, this.human ? Target.Mode.CLIENT : Target.Mode.resolve(ctx, blockInteract));
        this.traceTarget = rotation; // Ostinato
    }

    /** Ostinato: the last rotation asked for, before the hand or the aim jitter, for the parity trace. */
    public Rotation traceTarget;

    @Override
    public IAimProcessor getAimProcessor() {
        return this.processor;
    }

    @Override
    public void onTick(TickEvent event) {
        if (event.getType() == TickEvent.Type.IN) {
            this.processor.tick();
        }
    }

    private boolean human;

    /**
     * For this tick, the rotation that is sent is a hand's move from the last one: most of the way to the
     * target, at most 60 degrees of yaw and 45 of pitch, in whole steps of the mouse at the current
     * sensitivity. smoothLook only averages what the camera shows; the packet still carried the full snap.
     */
    public void human() {
        this.human = true;
    }

    private boolean ownMove;

    /**
     * {@link #human()}, and the movement keys act on the rotation the player really has this tick rather than on
     * the one being turned to, as they do for a player whose view is still on its way.
     */
    public void humanMove() {
        this.human = true;
        this.ownMove = true;
    }

    /**
     * Prototype (the frameLook setting): between ticks the view keeps moving toward the last aim goal, one small
     * mouse-count step per rendered frame, as a hand does, instead of arriving in one jump at the tick. The goal is
     * dropped after two ticks without a refresh, so a stale aim never drags the view.
     * <p>
     * Ostinato: the frames only draw the move. Where the view stands when a tick begins is fixed per tick
     * ({@link #tickStart}): the tick step's 60% of the gap, in whole mouse counts, whatever the frame rate. Moving
     * the real rotation per frame made every decision depend on how many frames fell between two ticks (at a high
     * frame rate the per-frame pieces rounded to zero mouse counts and the last degrees never closed; a slow client
     * closed them), so a headless client at a few frames a second fought a different fight from the same code.
     */
    private Rotation frameGoal;
    private int frameGoalTick;
    // Ostinato: the rotation the last tick left and the one the next tick begins on; frames draw the way between
    private Rotation frameFrom, frameTo;
    private long frameFromNanos;

    public void frame() {
        if (!Baritone.settings().frameLook.value) return;
        net.minecraft.client.player.LocalPlayer p = ctx.player();
        if (frameGoal == null || frameFrom == null || frameTo == null || p == null) return;
        if (p.tickCount - frameGoalTick > 2 || ctx.minecraft().gui.screen() != null) return; // tickStart drops it
        double frac = Math.max(0.0, Math.min(1.0, (System.nanoTime() - frameFromNanos) / 50_000_000.0));
        float step = mouseStep();
        float dy = net.minecraft.util.Mth.wrapDegrees(frameTo.getYaw() - frameFrom.getYaw()), dp = frameTo.getPitch() - frameFrom.getPitch();
        float wy = frameFrom.getYaw() + Math.round(dy * frac / step) * step;
        float wp = net.minecraft.util.Mth.clamp(frameFrom.getPitch() + Math.round(dp * frac / step) * step, -90f, 90f);
        float my = net.minecraft.util.Mth.wrapDegrees(wy - p.getYRot()), mp = wp - p.getXRot();
        if (my == 0 && mp == 0) return;
        turnBy(p, my, mp);
    }

    /**
     * Ostinato: called as each client tick begins, before anything reads the rotation. The view is put exactly where
     * the per-tick model says the frames should have brought it, and the next tick's step is planned from there when
     * no fresh aim arrives. Deterministic per tick, so a client's frame rate cannot change a fight.
     */
    public void tickStart() {
        if (!Baritone.settings().frameLook.value) {
            frameGoal = null;
            return;
        }
        net.minecraft.client.player.LocalPlayer p = ctx.player();
        if (frameGoal == null || p == null) return;
        if (frameTo != null) {
            float my = net.minecraft.util.Mth.wrapDegrees(frameTo.getYaw() - p.getYRot()), mp = frameTo.getPitch() - p.getXRot();
            if (my != 0 || mp != 0) turnBy(p, my, mp);
        }
        if (p.tickCount - frameGoalTick > 2 || ctx.minecraft().gui.screen() != null) {
            frameGoal = null;
            frameFrom = frameTo = null;
            return;
        }
        planFrameStep(p);
    }

    /** Ostinato: from the current view, this tick's step toward the goal: 60% of the gap in whole mouse counts. */
    private void planFrameStep(net.minecraft.client.player.LocalPlayer p) {
        float cy = p.getYRot(), cp = p.getXRot();
        float dy = net.minecraft.util.Mth.wrapDegrees(frameGoal.getYaw() - cy), dp = frameGoal.getPitch() - cp;
        float step = mouseStep();
        float my = Math.round(dy * 0.6f / step) * step, mp = Math.round(dp * 0.6f / step) * step;
        frameFrom = new Rotation(cy, cp);
        frameTo = new Rotation(cy + my, net.minecraft.util.Mth.clamp(cp + mp, -90f, 90f));
        frameFromNanos = System.nanoTime();
    }

    private float mouseStep() {
        double f = ctx.minecraft().options.sensitivity().get() * 0.6 + 0.2;
        return (float) (f * f * f * 8.0 * 0.15);
    }

    private static void turnBy(net.minecraft.client.player.LocalPlayer p, float my, float mp) {
        p.setYRot(p.getYRot() + my);
        p.setXRot(net.minecraft.util.Mth.clamp(p.getXRot() + mp, -90f, 90f));
        p.yRotO += my;
        p.xRotO = net.minecraft.util.Mth.clamp(p.xRotO + mp, -90f, 90f);
    }

    private float turnCap;

    /**
     * Ostinato: for this tick only, the look may turn up to {@code deg} degrees instead of the usual 32. A parkour
     * plan can swing the yaw 50° between two air ticks; at 32 the box drifts a few centimetres off it, enough to
     * clip a landing block's side.
     */
    public void turnCap(float deg) {
        this.turnCap = deg;
    }

    private float velYaw, velPitch;
    private final java.util.Random handNoise = OstinatoRandom.create("look.hand"); // Ostinato: seedable
    /** Ostinato: draws taken from the hand's tremor so far, for the parity trace. */
    public long handDraws;

    /** Yaw speed of the simulated hand, degrees per tick, for predicting where the camera will be. */
    public float yawVelocity() {
        return velYaw;
    }

    /**
     * Ostinato: -Dostinato.flick=user holds the hand to vex's own recorded flicks (mouse-session-1, 41 mcturn trials):
     * the turn per tick is capped at the 90th percentile of that hand's peak speed for the flick's size (about
     * 26 deg/tick at 40 deg, 33 at 90, 50 at 200; the default model reaches 54 at 90), and a jump of the aim point
     * by more than 25 deg is answered after 3 ticks (150 ms, that hand's fastest decile of reaction times).
     */
    static final boolean FLICK_USER = "user".equals(System.getProperty("ostinato.flick"));

    /** Ostinato: the user-profile cap on one tick's turn for a move of {@code d} degrees still to go. */
    static float flickCap(float d) {
        return 20f + 0.15f * Math.abs(d);
    }

    private Rotation heldWant; // Ostinato: the aim point the hand still goes for while it reacts to a jump
    private int reactLeft;

    /** One tick of the hand's yaw (the same model as {@link #humanStep}), for rollouts: returns the new camera yaw, speed in outVel[0]. */
    public static float modelYawStep(float cam, float vel, float target, float[] outVel) {
        float dy = net.minecraft.util.Mth.wrapDegrees(target - cam);
        float want = Math.abs(dy) > 1.5f ? net.minecraft.util.Mth.clamp(dy * 0.6f, -60f, 60f) : dy;
        float acc = 12f + Math.abs(dy) * 0.5f;
        float out = vel + net.minecraft.util.Mth.clamp(want - vel, -acc, acc);
        if (Math.abs(dy) <= 1.5f) out = dy;
        if (Math.abs(dy) > 25f) out += vel * 0.12f;
        if (FLICK_USER) out = net.minecraft.util.Mth.clamp(out, -flickCap(dy), flickCap(dy)); // Ostinato
        outVel[0] = out;
        return cam + out;
    }

    /**
     * A hand does not jump to its target: the speed ramps up (limited acceleration), the move closes most of
     * the gap each tick (so a long flick still lands in a few ticks), carries a little momentum past the
     * target, and the wrist arc leaks a bit of pitch into a yaw sweep. Near the target a tremor of under a
     * mouse count rides on top; the result is rounded to whole mouse counts as before.
     */
    private Rotation humanStep(Rotation want) {
        if (FLICK_USER) { // Ostinato: a hand reacts to a jumped aim point a few ticks late
            if (heldWant != null && reactLeft == 0 && Math.abs(net.minecraft.util.Mth.wrapDegrees(want.getYaw() - heldWant.getYaw())) > 25f) reactLeft = 4;
            if (reactLeft > 0 && --reactLeft > 0) want = heldWant; // 3 ticks on the old point, then the new one
            else heldWant = want;
        }
        float cy = ctx.player().getYRot(), cp = ctx.player().getXRot();
        float dy = net.minecraft.util.Mth.wrapDegrees(want.getYaw() - cy), dp = want.getPitch() - cp;
        float wantY = Math.abs(dy) > 1.5f ? net.minecraft.util.Mth.clamp(dy * 0.6f, -60f, 60f) : dy;
        float wantP = Math.abs(dp) > 1.5f ? net.minecraft.util.Mth.clamp(dp * 0.6f, -45f, 45f) : dp;
        // acceleration limit scales with the size of the move: a flick may start hard, a nudge starts soft
        float accY = 12f + Math.abs(dy) * 0.5f, accP = 9f + Math.abs(dp) * 0.5f;
        float outY = velYaw + net.minecraft.util.Mth.clamp(wantY - velYaw, -accY, accY);
        float outP = velPitch + net.minecraft.util.Mth.clamp(wantP - velPitch, -accP, accP);
        if (Math.abs(dy) <= 1.5f) outY = dy; // fine aim lands exactly, never lags behind a moving target
        if (Math.abs(dp) <= 1.5f) outP = dp;
        // momentum carries a fast move slightly past; the next tick corrects it
        if (Math.abs(dy) > 25f) outY += velYaw * 0.12f;
        if (FLICK_USER) { // Ostinato: no faster than the user's own hand
            outY = net.minecraft.util.Mth.clamp(outY, -flickCap(dy), flickCap(dy));
            outP = net.minecraft.util.Mth.clamp(outP, -0.75f * flickCap(dp), 0.75f * flickCap(dp));
        }
        outP += outY * 0.06f * (float) Math.signum(dp == 0 ? 1 : dp) * (Math.abs(dy) > 10f ? 1 : 0);
        double tremor = Math.min(1.0, Math.abs(dy) / 6.0) < 1.0 ? 0.08 : 0.0;
        handDraws += 2; // Ostinato
        outY += (float) (handNoise.nextGaussian() * tremor);
        outP += (float) (handNoise.nextGaussian() * tremor);
        velYaw = outY;
        velPitch = outP;
        double f = ctx.minecraft().options.sensitivity().get() * 0.6 + 0.2;
        float step = (float) (f * f * f * 8.0 * 0.15); // degrees per mouse count, as MouseHandler turns the player
        outY = Math.round(outY / step) * step;
        outP = Math.round(outP / step) * step;
        return new Rotation(cy + outY, net.minecraft.util.Mth.clamp(cp + outP, -90f, 90f));
    }

    @Override
    public void onPlayerUpdate(PlayerUpdateEvent event) {

        if (this.target == null) {
            return;
        }

        switch (event.getState()) {
            case PRE: {
                if (this.target.mode == Target.Mode.NONE) {
                    // Just return for PRE, we still want to set target to null on POST
                    return;
                }

                this.prevRotation = new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
                this.processor.capOverride = this.turnCap;
                final Rotation actual = this.human ? humanStep(this.target.rotation) : this.processor.peekRotation(this.target.rotation);
                this.processor.capOverride = 0;
                // Ostinato: the human-input gate; no single-tick turn past a fast flick, pitch kept in range
                ctx.player().setYRot(HumanInputCaps.capYaw(this.prevRotation.getYaw(), actual.getYaw(), HumanInputCaps.maxYaw()));
                ctx.player().setXRot(HumanInputCaps.capPitch(this.prevRotation.getPitch(), actual.getPitch(), HumanInputCaps.maxPitch()));
                break;
            }
            case POST: {
                // Reset the player's rotations back to their original values
                if (this.prevRotation != null) {
                    this.smoothYawBuffer.addLast(this.target.rotation.getYaw());
                    while (this.smoothYawBuffer.size() > Baritone.settings().smoothLookTicks.value) {
                        this.smoothYawBuffer.removeFirst();
                    }
                    this.smoothPitchBuffer.addLast(this.target.rotation.getPitch());
                    while (this.smoothPitchBuffer.size() > Baritone.settings().smoothLookTicks.value) {
                        this.smoothPitchBuffer.removeFirst();
                    }
                    if (this.human) {
                        // the camera stays on the rotation that was sent
                        this.frameGoal = this.target.rotation;
                        this.frameGoalTick = ctx.player().tickCount;
                        planFrameStep(ctx.player()); // Ostinato
                    } else if (this.target.mode == Target.Mode.SERVER) {
                        ctx.player().setYRot(this.prevRotation.getYaw());
                        ctx.player().setXRot(this.prevRotation.getPitch());
                    } else if (ctx.player().isFallFlying() ? Baritone.settings().elytraSmoothLook.value : Baritone.settings().smoothLook.value) {
                        ctx.player().setYRot((float) this.smoothYawBuffer.stream().mapToDouble(d -> d).average().orElse(this.prevRotation.getYaw()));
                        if (ctx.player().isFallFlying()) {
                            ctx.player().setXRot((float) this.smoothPitchBuffer.stream().mapToDouble(d -> d).average().orElse(this.prevRotation.getPitch()));
                        }
                    }
                    //ctx.player().xRotO = prevRotation.getPitch();
                    //ctx.player().yRotO = prevRotation.getYaw();
                    this.prevRotation = null;
                }
                // The target is done being used for this game tick, so it can be invalidated
                this.target = null;
                if (!this.human) { velYaw = 0; velPitch = 0; }
                this.human = false;
                this.ownMove = false;
                this.turnCap = 0;
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onSendPacket(PacketEvent event) {
        if (!(event.getPacket() instanceof ServerboundMovePlayerPacket)) {
            return;
        }

        final ServerboundMovePlayerPacket packet = (ServerboundMovePlayerPacket) event.getPacket();
        if (packet instanceof ServerboundMovePlayerPacket.Rot || packet instanceof ServerboundMovePlayerPacket.PosRot) {
            this.serverRotation = new Rotation(packet.getYRot(0.0f), packet.getXRot(0.0f));
        }
    }

    @Override
    public void onWorldEvent(WorldEvent event) {
        this.serverRotation = null;
        this.target = null;
    }

    public void pig() {
        if (this.target != null) {
            final Rotation actual = this.processor.peekRotation(this.target.rotation);
            ctx.player().setYRot(HumanInputCaps.capYaw(ctx.player().getYRot(), actual.getYaw(), HumanInputCaps.maxYaw())); // Ostinato: capped
        }
    }

    public Optional<Rotation> getEffectiveRotation() {
        if (Baritone.settings().freeLook.value) {
            return Optional.ofNullable(this.serverRotation);
        }
        // If freeLook isn't on, just defer to the player's actual rotations
        return Optional.empty();
    }

    @Override
    public void onPlayerRotationMove(RotationMoveEvent event) {
        if (this.target != null && !this.ownMove) {
            final Rotation actual = this.processor.peekRotation(this.target.rotation);
            event.setYaw(actual.getYaw());
            event.setPitch(actual.getPitch());
        }
    }

    private static final class AimProcessor extends AbstractAimProcessor {

        public AimProcessor(final IPlayerContext ctx) {
            super(ctx);
        }

        @Override
        protected Rotation getPrevRotation() {
            // Implementation will use LookBehavior.serverRotation
            return ctx.playerRotations();
        }
    }

    private static abstract class AbstractAimProcessor implements ITickableAimProcessor {

        protected final IPlayerContext ctx;
        private final ForkableRandom rand;
        private double randomYawOffset;
        private double randomPitchOffset;
        /** Above zero, this tick's turn cap in place of the 32° one ({@link LookBehavior#turnCap}). */
        float capOverride;

        public AbstractAimProcessor(IPlayerContext ctx) {
            this.ctx = ctx;
            this.rand = new ForkableRandom(OstinatoRandom.seed("look.aim")); // Ostinato: seedable
        }

        private AbstractAimProcessor(final AbstractAimProcessor source) {
            this.ctx = source.ctx;
            this.rand = source.rand.fork();
            this.randomYawOffset = source.randomYawOffset;
            this.randomPitchOffset = source.randomPitchOffset;
        }

        @Override
        public final Rotation peekRotation(final Rotation rotation) {
            final Rotation prev = this.getPrevRotation();

            float desiredYaw = rotation.getYaw();
            float desiredPitch = rotation.getPitch();

            // In other words, the target doesn't care about the pitch, so it used playerRotations().getPitch()
            // and it's safe to adjust it to a normal level
            if (desiredPitch == prev.getPitch()) {
                desiredPitch = nudgeToLevel(desiredPitch);
            }

            desiredYaw += this.randomYawOffset;
            desiredPitch += this.randomPitchOffset;

            return new Rotation(
                    this.calculateMouseMove(prev.getYaw(), desiredYaw),
                    this.calculateMouseMove(prev.getPitch(), desiredPitch)
            ).clamp();
        }

        @Override
        public final void tick() {
            // randomLooking
            this.randomYawOffset = (this.rand.nextDouble() - 0.5) * Baritone.settings().randomLooking.value;
            this.randomPitchOffset = (this.rand.nextDouble() - 0.5) * Baritone.settings().randomLooking.value;

            // randomLooking113
            double random = this.rand.nextDouble() - 0.5;
            if (Math.abs(random) < 0.1) {
                random *= 4;
            }
            this.randomYawOffset += random * Baritone.settings().randomLooking113.value;
        }

        @Override
        public final void advance(int ticks) {
            for (int i = 0; i < ticks; i++) {
                this.tick();
            }
        }

        @Override
        public Rotation nextRotation(final Rotation rotation) {
            final Rotation actual = this.peekRotation(rotation);
            this.tick();
            return actual;
        }

        @Override
        public final ITickableAimProcessor fork() {
            return new AbstractAimProcessor(this) {

                private Rotation prev = AbstractAimProcessor.this.getPrevRotation();

                @Override
                public Rotation nextRotation(final Rotation rotation) {
                    return (this.prev = super.nextRotation(rotation));
                }

                @Override
                protected Rotation getPrevRotation() {
                    return this.prev;
                }
            };
        }

        protected abstract Rotation getPrevRotation();

        /**
         * Nudges the player's pitch to a regular level. (Between {@code -20} and {@code 10}, increments are by {@code 1})
         */
        private float nudgeToLevel(float pitch) {
            if (pitch < -20) {
                return pitch + 1;
            } else if (pitch > 10) {
                return pitch - 1;
            }
            return pitch;
        }

        private float calculateMouseMove(float current, float target) {
            // Shortest-path turn, capped so PvP aim tracks instead of snapping.
            float delta = net.minecraft.util.Mth.wrapDegrees(target - current);
            float cap = 32f;
            try {
                cap = Float.parseFloat(System.getProperty("ostinato.aim.deg", "32"));
            } catch (NumberFormatException ignored) {}
            if (capOverride > 0) cap = capOverride;
            if (cap < 1f) cap = 1f;
            delta = net.minecraft.util.Mth.clamp(delta, -cap, cap);
            final double deltaPx = angleToMouse(delta); // yes, even the mouse movements use double
            return current + mouseToAngle(deltaPx);
        }

        private double angleToMouse(float angleDelta) {
            final float minAngleChange = mouseToAngle(1);
            return Math.round(angleDelta / minAngleChange);
        }

        private float mouseToAngle(double mouseDelta) {
            // casting float literals to double gets us the precise values used by mc
            final double f = ctx.minecraft().options.sensitivity().get() * (double) 0.6f + (double) 0.2f;
            return (float) (mouseDelta * f * f * f * 8.0d) * 0.15f; // yes, one double and one float scaling factor
        }
    }

    private static class Target {

        public final Rotation rotation;
        public final Mode mode;

        public Target(Rotation rotation, Mode mode) {
            this.rotation = rotation;
            this.mode = mode;
        }

        enum Mode {
            /**
             * Rotation will be set client-side and is visual to the player
             */
            CLIENT,

            /**
             * Rotation will be set server-side and is silent to the player
             */
            SERVER,

            /**
             * Rotation will remain unaffected on both the client and server
             */
            NONE;

            static Mode resolve(IPlayerContext ctx, boolean blockInteract) {
                final Settings settings = Baritone.settings();
                final boolean antiCheat = settings.antiCheatCompatibility.value;
                final boolean blockFreeLook = settings.blockFreeLook.value;

                if (ctx.player().isFallFlying()) {
                    // always need to set angles while flying
                    return settings.elytraFreeLook.value ? SERVER : CLIENT;
                } else if (settings.freeLook.value) {
                    // Regardless of if antiCheatCompatibility is enabled, if a blockInteract is requested then the player
                    // rotation needs to be set somehow, otherwise Baritone will halt since objectMouseOver() will just be
                    // whatever the player is mousing over visually. Let's just settle for setting it silently.
                    if (blockInteract) {
                        return blockFreeLook ? SERVER : CLIENT;
                    }
                    return antiCheat ? SERVER : NONE;
                }

                // all freeLook settings are disabled so set the angles
                return CLIENT;
            }
        }
    }
}
