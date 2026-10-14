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

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.behavior.ILookBehavior;
import baritone.api.behavior.look.IAimProcessor;
import baritone.api.behavior.look.ITickableAimProcessor;
import baritone.api.event.events.*;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.Rotation;
import baritone.behavior.look.ForkableRandom;
import baritone.api.utils.Helper;
import baritone.behavior.look.HumanMouseModel;
import baritone.behavior.look.ReactionGate;
import baritone.behavior.look.ReactionModel;
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
    }

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

    // Ostinato: conditioning for the humanMouseProfile model, for this tick's look; reset after every tick
    private float aimWidthDeg = 3f, aimUrgency = 0.5f;

    /**
     * Ostinato: {@link #human()} with what a profile-driven move is conditioned on: the target's angular width
     * (a nearby block is easy to hit, a far one needs care) and urgency (0 calm .. 1 hurried). Without a
     * humanMouseProfile this is the same as {@link #human()}.
     */
    public void humanAim(float targetWidthDeg, float urgency) {
        this.human = true;
        this.aimWidthDeg = Math.max(0.2f, targetWidthDeg);
        this.aimUrgency = Math.max(0f, Math.min(1f, urgency));
    }

    // Ostinato: humanReactionDelay. Only reactive looks (combat aim) can be held; every other look cancels a hold.
    private final ReactionGate reaction = new ReactionGate();
    private final java.util.Random reactionRng = new java.util.Random();
    private final ReactionModel defaultReaction = ReactionModel.defaults(); // the user's measured tired and peak sets
    private final long sessionStartNanos = System.nanoTime();
    private boolean reactive;

    /** Ostinato: {@link #human()} for a look that reacts to the world (combat aim), so a surprise hold applies to it. */
    public void humanReactive() {
        this.human = true;
        this.reactive = true;
    }

    /**
     * Ostinato: a new, unexpected look target just appeared (a mob showing up, being hit, a new threat): with
     * humanReactionDelay on, the camera holds for a reaction time sampled from the profile's reaction block, or
     * the user's measured tired reaction times when no profile is loaded, at the humanEnergy state. Never call
     * this for planned looks.
     */
    public void surprise() {
        HumanMouseModel m = ctx.player() == null ? null : humanProfile();
        double now = System.nanoTime() / 1e9, session = (System.nanoTime() - sessionStartNanos) / 1e9;
        reaction.surprise(Baritone.settings().humanReactionDelay.value, m != null ? m.reactionModel() : defaultReaction,
                ReactionModel.Energy.parse(Baritone.settings().humanEnergy.value), now, session, reactionRng);
    }

    /** Ostinato: whether a surprise reaction hold is running. */
    public boolean reacting() {
        return reaction.holding();
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
     */
        private Rotation frameGoal;
    private int frameGoalTick;
    private long lastFrameNanos;

    public void frame() {
        if (!Baritone.settings().frameLook.value) return;
        long now = System.nanoTime();
        double ticks = lastFrameNanos == 0 ? 0 : Math.min(2.0, (now - lastFrameNanos) / 50_000_000.0);
        lastFrameNanos = now;
        net.minecraft.client.player.LocalPlayer p = ctx.player();
        if (frameGoal == null || p == null || ticks <= 0) return;
        if (p.tickCount - frameGoalTick > 2 || ctx.minecraft().gui.screen() != null) {
            frameGoal = null;
            return;
        }
        float dy = net.minecraft.util.Mth.wrapDegrees(frameGoal.getYaw() - p.getYRot()), dp = frameGoal.getPitch() - p.getXRot();
        // the tick step closes 60% of the gap per tick: the same rate, in frame-sized pieces
        float k = (float) (1 - Math.pow(0.4, ticks));
        double f = ctx.minecraft().options.sensitivity().get() * 0.6 + 0.2;
        float step = (float) (f * f * f * 8.0 * 0.15);
        float my = Math.round(dy * k / step) * step, mp = Math.round(dp * k / step) * step;
        if (my == 0 && mp == 0) return;
        p.setYRot(p.getYRot() + my);
        p.setXRot(net.minecraft.util.Mth.clamp(p.getXRot() + mp, -90f, 90f));
        p.yRotO += my;
        p.xRotO = net.minecraft.util.Mth.clamp(p.xRotO + mp, -90f, 90f);
    }

    private float velYaw, velPitch;
    private final java.util.Random handNoise = new java.util.Random();

    /** Yaw speed of the simulated hand, degrees per tick, for predicting where the camera will be. */
    public float yawVelocity() {
        return velYaw;
    }

    /** One tick of the hand's yaw (the same model as {@link #humanStep}), for rollouts: returns the new camera yaw, speed in outVel[0]. */
    public static float modelYawStep(float cam, float vel, float target, float[] outVel) {
        float dy = net.minecraft.util.Mth.wrapDegrees(target - cam);
        float want = Math.abs(dy) > 1.5f ? net.minecraft.util.Mth.clamp(dy * 0.6f, -60f, 60f) : dy;
        float acc = 12f + Math.abs(dy) * 0.5f;
        float out = vel + net.minecraft.util.Mth.clamp(want - vel, -acc, acc);
        if (Math.abs(dy) <= 1.5f) out = dy;
        if (Math.abs(dy) > 25f) out += vel * 0.12f;
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
        if (reaction.hold(this.reactive)) { // Ostinato: surprise reaction delay, reactive looks only
            velYaw = 0;
            velPitch = 0;
            plan = null;
            return new Rotation(ctx.player().getYRot(), ctx.player().getXRot());
        }
        Rotation profiled = profileStep(want); // Ostinato: a fitted human mouse profile, when one is set
        if (profiled != null) return profiled;
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
        outP += outY * 0.06f * (float) Math.signum(dp == 0 ? 1 : dp) * (Math.abs(dy) > 10f ? 1 : 0);
        double tremor = Math.min(1.0, Math.abs(dy) / 6.0) < 1.0 ? 0.08 : 0.0;
        outY += (float) (handNoise.nextGaussian() * tremor);
        outP += (float) (handNoise.nextGaussian() * tremor);
        velYaw = outY;
        velPitch = outP;
        float step = degreesPerCount(); // degrees per mouse count, as MouseHandler turns the player
        outY = Math.round(outY / step) * step;
        outP = Math.round(outP / step) * step;
        return new Rotation(cy + outY, net.minecraft.util.Mth.clamp(cp + outP, -90f, 90f));
    }

    /** Degrees the camera turns per raw mouse count at the current sensitivity, as MouseHandler does it. */
    private float degreesPerCount() {
        double f = ctx.minecraft().options.sensitivity().get() * 0.6 + 0.2;
        return (float) (f * f * f * 8.0 * 0.15);
    }

    // ---- Ostinato: humanMouseProfile, a generative model of the user's own mouse (see HumanMouseModel) ----
    private HumanMouseModel profile;
    private String profileSetting, profileStamp;
    private int profileCheckTick = Integer.MIN_VALUE / 2;
    private HumanMouseModel.Movement plan;
    private float planYaw0, planPitch0, planGoalYaw, planGoalPitch;
    private double planT;
    private final java.util.Random planRng = new java.util.Random();

    /** The profile named by the setting, reloaded when the setting or the file changes; null for the built-in curve. */
    private HumanMouseModel humanProfile() {
        String path = Baritone.settings().humanMouseProfile.value;
        path = path == null ? "" : path.trim();
        if (path.isEmpty()) {
            profile = null;
            profileSetting = null;
            profileStamp = null;
            return null;
        }
        int tick = ctx.player().tickCount;
        if (path.equals(profileSetting) && Math.abs(tick - profileCheckTick) < 100) return profile;
        profileCheckTick = tick;
        boolean changed = !path.equals(profileSetting);
        profileSetting = path;
        try {
            if (path.equalsIgnoreCase("example")) {
                if (!"example".equals(profileStamp)) {
                    try (java.io.InputStream in = LookBehavior.class.getResourceAsStream("/assets/baritone/ostinato/human-mouse-example.json")) {
                        if (in == null) throw new java.io.FileNotFoundException("bundled example profile");
                        profile = HumanMouseModel.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
                    }
                    profileStamp = "example";
                    Helper.HELPER.logDirect("Human mouse: using the bundled synthetic example profile");
                }
                return profile;
            }
            java.nio.file.Path file = java.nio.file.Paths.get(path);
            if (!file.isAbsolute()) file = ctx.minecraft().gameDirectory.toPath().resolve(file);
            String stamp = file + "@" + java.nio.file.Files.getLastModifiedTime(file).toMillis();
            if (!stamp.equals(profileStamp)) {
                profile = HumanMouseModel.load(file);
                profileStamp = stamp;
                Helper.HELPER.logDirect("Human mouse: loaded " + file.getFileName() + " (" + profile.templateCount() + " recorded movements"
                        + (profile.isSynthetic() ? ", synthetic" : "") + ")");
            }
        } catch (Exception e) {
            if (changed || profile != null) Helper.HELPER.logDirect("Human mouse: can't use profile " + path + " (" + e.getMessage() + "); using the built-in curve");
            profile = null;
            profileStamp = null;
        }
        return profile;
    }

    /**
     * One tick of a profile-driven move: a whole movement is generated from the model when a new aim starts (no
     * reaction delay: it starts this tick), then played back tick by tick against the camera; when the target
     * moves away from the planned end, the move is re-planned from where the camera is.
     * Returns null when no profile is in use.
     */
    private Rotation profileStep(Rotation want) {
        HumanMouseModel m = humanProfile();
        if (m == null) {
            plan = null;
            return null;
        }
        net.minecraft.client.player.LocalPlayer p = ctx.player();
        float cy = p.getYRot(), cp = p.getXRot();
        float dy = net.minecraft.util.Mth.wrapDegrees(want.getYaw() - cy), dp = want.getPitch() - cp;
        float step = degreesPerCount();
        if (plan != null) {
            double moved = Math.hypot(net.minecraft.util.Mth.wrapDegrees(want.getYaw() - planGoalYaw), want.getPitch() - planGoalPitch);
            if (moved > Math.max(1.0, 0.2 * Math.hypot(dy, dp))) plan = null;
        }
        float outY, outP;
        if (plan == null && Math.abs(dy) <= 1.5f && Math.abs(dp) <= 1.5f) {
            outY = dy; // fine aim lands exactly, as the built-in curve does
            outP = dp;
        } else {
            if (plan == null) {
                plan = m.generate(dy / step, dp / step, aimWidthDeg / step, aimUrgency, planRng);
                planYaw0 = cy;
                planPitch0 = cp;
                planGoalYaw = want.getYaw();
                planGoalPitch = want.getPitch();
                planT = 0;
            }
            planT += 50;
            double[] pos = plan.at(planT);
            outY = net.minecraft.util.Mth.wrapDegrees((float) (planYaw0 + pos[0] * step) - cy);
            outP = (float) (planPitch0 + pos[1] * step) - cp;
            if (planT >= plan.durationMs()) plan = null;
        }
        outY = Math.round(outY / step) * step;
        outP = Math.round(outP / step) * step;
        velYaw = outY;
        velPitch = outP;
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
                final Rotation actual = this.human ? humanStep(this.target.rotation) : this.processor.peekRotation(this.target.rotation);
                ctx.player().setYRot(actual.getYaw());
                ctx.player().setXRot(actual.getPitch());
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
                if (!this.human) { velYaw = 0; velPitch = 0; plan = null; reaction.cancel(); }
                else if (!this.reactive) reaction.cancel(); // a planned look this tick: no hold carries over
                this.reactive = false;
                this.human = false;
                this.aimWidthDeg = 3f; // Ostinato
                this.aimUrgency = 0.5f;
                this.ownMove = false;
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
            ctx.player().setYRot(actual.getYaw());
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

        public AbstractAimProcessor(IPlayerContext ctx) {
            this.ctx = ctx;
            this.rand = new ForkableRandom();
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
