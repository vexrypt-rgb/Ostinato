/*
 * Ostinato: motion primitives simulated through PlayerSimPhysics. Not part of upstream Baritone.
 */
package baritone.lattice;

import baritone.contract.ControlTick;
import baritone.contract.PlayerSimPhysics;
import baritone.contract.PlayerState;

import java.util.ArrayList;
import java.util.List;

/**
 * Walking, turning, sprinting, one-block ascent, and short sprint-jumps.
 * A primitive is returned only after the adapter simulates it and the robustness rule passes.
 */
public final class PrimitiveCatalog {
    /** Same-level moves are rejected when the feet drop more than this. */
    public static final double FALL_LIMIT = 1.5;
    /** A turn may slide this far while the keys are up. */
    public static final double TURN_SLIDE = 0.55;
    /** Sprint speed used only as a heuristic divisor. The primitive itself is simulated. */
    public static final double HEURISTIC_SPEED = 0.28;
    private static final int COAST_LIMIT = 18;

    public static final class Template {
        public final String name;
        public final String kind;
        public final float yawDelta;
        public final int movingTicks;
        public final boolean sprint;
        public final boolean jump;
        public final int minTicks;
        public final double minProgress;
        final String preconditions;

        Template(String name, String kind, float yawDelta, int movingTicks, boolean sprint, boolean jump,
                 int minTicks, double minProgress, String preconditions) {
            this.name = name;
            this.kind = kind;
            this.yawDelta = yawDelta;
            this.movingTicks = movingTicks;
            this.sprint = sprint;
            this.jump = jump;
            this.minTicks = minTicks;
            this.minProgress = minProgress;
            this.preconditions = preconditions;
        }
    }

    /** A feasible primitive and the physics body at its last tick. */
    public static final class Sim {
        public final MotionPrimitive primitive;
        public final PlayerSimPhysics end;

        Sim(MotionPrimitive primitive, PlayerSimPhysics end) {
            this.primitive = primitive;
            this.end = end;
        }
    }

    private static final List<Template> TEMPLATES = List.of(
            move("sprint-2", "SPRINT", 2, true, 2, 0.20,
                    "onGround; forward and sprint at the current yaw"),
            move("sprint-4", "SPRINT", 4, true, 4, 0.45,
                    "onGround; forward and sprint at the current yaw"),
            move("sprint-8", "SPRINT", 8, true, 8, 0.90,
                    "onGround; forward and sprint at the current yaw"),
            move("walk-2", "WALK", 2, false, 2, 0.12,
                    "onGround; forward, no sprint; one release tick first if the sprint latch is on"),
            move("walk-5", "WALK", 5, false, 5, 0.35,
                    "onGround; forward, no sprint; one release tick first if the sprint latch is on"),
            new Template("turn-45", "TURN", -45f, 2, false, false, 2, 0,
                    "onGround; yaw decreases by 45 degrees; movement keys up"),
            new Template("turn+45", "TURN", 45f, 2, false, false, 2, 0,
                    "onGround; yaw increases by 45 degrees; movement keys up"),
            jump("sprint-jump-0", "SPRINT_JUMP", 0, 2, 0.8,
                    "onGround; sprint-jump immediately, then sprint until the feet land on the same level"),
            jump("sprint-jump-2", "SPRINT_JUMP", 2, 4, 0.8,
                    "onGround; two sprint ticks, one sprint-jump, sprint until the feet land on the same level"),
            jump("sprint-jump-4", "SPRINT_JUMP", 4, 6, 0.8,
                    "onGround; four sprint ticks, one sprint-jump, sprint until the feet land on the same level"),
            jump("ascend-0", "ASCEND", 0, 2, 0.25,
                    "onGround; sprint-jump immediately, sprint until the feet land one block higher"),
            jump("ascend-2", "ASCEND", 2, 4, 0.25,
                    "onGround; two sprint ticks, one sprint-jump, sprint until the feet land one block higher"),
            jump("ascend-4", "ASCEND", 4, 6, 0.25,
                    "onGround; four sprint ticks, one sprint-jump, sprint until the feet land one block higher")
    );

    private PrimitiveCatalog() {
    }

    public static List<Template> templates() {
        return TEMPLATES;
    }

    public static float normalize(float yaw) {
        float y = yaw % 360f;
        if (y <= -180f) {
            y += 360f;
        }
        if (y > 180f) {
            y -= 360f;
        }
        return y;
    }

    /** Eight headings. 0 is +Z, and -90 (sector 6) is +X. */
    public static int sector(float yaw) {
        return Math.floorMod(Math.round(normalize(yaw) / 45f), 8);
    }

    /**
     * Simulates one template from {@code parent}. A negative {@code radius} disables the goal.
     * Returns null when a precondition or robustness rule fails.
     */
    public static Sim simulate(PlayerSimPhysics parent, Template template, double gx, double gy, double gz, double radius) {
        PlayerState origin = parent.state();
        if (!origin.onGround()) {
            return null;
        }
        float yaw = normalize(origin.yaw() + template.yawDelta);
        PlayerSimPhysics sim = parent.copy();
        List<ControlTick> controls = new ArrayList<>();
        List<PlayerState> predicted = new ArrayList<>();
        if (template.kind.equals("WALK") && parent.sim().sprinting) {
            if (!push(sim, idle(yaw), controls, predicted, origin.y())) {
                return null;
            }
            Sim truncated = truncated(template, controls, predicted, sim, gx, gy, gz, radius);
            if (truncated != null) {
                return truncated;
            }
        }
        for (int i = 0; i < template.movingTicks; i++) {
            ControlTick tick = template.kind.equals("TURN") ? idle(yaw) : forward(yaw, template.sprint, false);
            if (!push(sim, tick, controls, predicted, origin.y())) {
                return null;
            }
            Sim truncated = truncated(template, controls, predicted, sim, gx, gy, gz, radius);
            if (truncated != null) {
                return truncated;
            }
            if (!sim.state().onGround()) {
                return null;
            }
        }
        if (template.jump) {
            if (!push(sim, forward(yaw, true, true), controls, predicted, origin.y())) {
                return null;
            }
            Sim truncated = truncated(template, controls, predicted, sim, gx, gy, gz, radius);
            if (truncated != null) {
                return truncated;
            }
            int guard = 0;
            while (!sim.state().onGround() && guard++ < COAST_LIMIT) {
                if (!push(sim, forward(yaw, true, false), controls, predicted, origin.y())) {
                    return null;
                }
                truncated = truncated(template, controls, predicted, sim, gx, gy, gz, radius);
                if (truncated != null) {
                    return truncated;
                }
            }
            if (!sim.state().onGround()) {
                return null;
            }
        }
        return accept(template, controls, predicted, sim, origin);
    }

    private static Sim truncated(Template template, List<ControlTick> controls, List<PlayerState> predicted,
                                 PlayerSimPhysics sim, double gx, double gy, double gz, double radius) {
        PlayerState now = predicted.get(predicted.size() - 1);
        if (!hit(now, gx, gy, gz, radius)) {
            return null;
        }
        double slack = radius - dist(now, gx, gy, gz);
        return pack(template, controls, predicted, sim, true, slack, "truncated inside the goal");
    }

    private static Sim accept(Template template, List<ControlTick> controls, List<PlayerState> predicted,
                              PlayerSimPhysics sim, PlayerState origin) {
        PlayerState end = predicted.get(predicted.size() - 1);
        if (!end.onGround()) {
            return null;
        }
        double horiz = Math.hypot(end.x() - origin.x(), end.z() - origin.z());
        double dy = end.y() - origin.y();
        return switch (template.kind) {
            case "TURN" -> {
                float turned = Math.abs(normalize(end.yaw() - origin.yaw()));
                if (turned < 44f || turned > 46f || horiz > TURN_SLIDE || Math.abs(dy) > 0.35) {
                    yield null;
                }
                yield pack(template, controls, predicted, sim, false, TURN_SLIDE - horiz,
                        "turn slide <= 0.55 blocks and the feet stay on the same level");
            }
            case "WALK", "SPRINT" -> sameLevel(template, controls, predicted, sim, horiz, dy,
                    "same level (|dy| < 0.35) and forward progress at least " + template.minProgress);
            case "SPRINT_JUMP" -> sameLevel(template, controls, predicted, sim, horiz, dy,
                    "same-level sprint-jump, no fall, forward progress at least " + template.minProgress);
            case "ASCEND" -> {
                if (dy <= 0.6 || dy >= 1.4 || horiz < template.minProgress) {
                    yield null;
                }
                yield pack(template, controls, predicted, sim, false, Math.min(dy - 0.6, 1.4 - dy),
                        "one-block ascent: end y in (start+0.6, start+1.4)");
            }
            default -> null;
        };
    }

    private static Sim sameLevel(Template template, List<ControlTick> controls, List<PlayerState> predicted,
                                 PlayerSimPhysics sim, double horiz, double dy, String rule) {
        if (Math.abs(dy) > 0.35 || horiz < template.minProgress) {
            return null;
        }
        double margin = Math.min(0.35 - Math.abs(dy), horiz - template.minProgress);
        return pack(template, controls, predicted, sim, false, margin, rule);
    }

    private static Sim pack(Template template, List<ControlTick> controls, List<PlayerState> predicted,
                            PlayerSimPhysics sim, boolean reachedGoal, double margin, String rule) {
        if (predicted.isEmpty() || !predicted.get(predicted.size() - 1).onGround()) {
            return null;
        }
        MotionPrimitive primitive = new MotionPrimitive(template.name, template.kind, template.preconditions,
                controls, predicted, controls.size(), rule, margin, reachedGoal);
        return new Sim(primitive, sim);
    }

    /** @return false when this tick fell through the fall limit or produced a non-finite position */
    private static boolean push(PlayerSimPhysics sim, ControlTick tick, List<ControlTick> controls,
                                List<PlayerState> predicted, double y0) {
        controls.add(tick);
        PlayerState next = sim.step(tick);
        predicted.add(next);
        if (next.y() < y0 - FALL_LIMIT) {
            return false;
        }
        return !Double.isNaN(next.x()) && !Double.isNaN(next.y()) && !Double.isNaN(next.z());
    }

    private static ControlTick idle(float yaw) {
        return new ControlTick(yaw, false, false, false, false, false, false, false);
    }

    private static ControlTick forward(float yaw, boolean sprint, boolean jump) {
        return new ControlTick(yaw, true, false, false, false, sprint, jump, false);
    }

    public static boolean hit(PlayerState state, double gx, double gy, double gz, double radius) {
        if (radius < 0 || !state.onGround()) {
            return false;
        }
        return dist(state, gx, gy, gz) <= radius;
    }

    private static double dist(PlayerState state, double gx, double gy, double gz) {
        double dx = state.x() - gx;
        double dy = state.y() - gy;
        double dz = state.z() - gz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static Template move(String name, String kind, int ticks, boolean sprint, int minTicks,
                                 double minProgress, String preconditions) {
        return new Template(name, kind, 0f, ticks, sprint, false, minTicks, minProgress, preconditions);
    }

    private static Template jump(String name, String kind, int windup, int minTicks, double minProgress, String preconditions) {
        return new Template(name, kind, 0f, windup, true, true, minTicks, minProgress, preconditions);
    }
}
