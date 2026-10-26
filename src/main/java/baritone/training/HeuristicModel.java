package baritone.training;

// Ostinato: default task policy. It reads only ITaskView, so legit mode cannot leak hidden blocks.

import baritone.api.training.ITaskView;
import baritone.api.training.ITrainingModel;
import baritone.api.training.TaskDecision;

/** Hand, then a wooden pick when the goal is cobble. Turns toward what it can see, or toward a sound. */
public final class HeuristicModel implements ITrainingModel {

    private final String id;
    private boolean geared;
    private boolean dodgedMob;

    public HeuristicModel(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public TaskDecision decide(ITaskView view) {
        TaskDecision gear = null;
        if (!geared) {
            geared = true;
            String reason = "COMPASS".equals(view.goalItem())
                    ? "follow the compass needle the player can see"
                    : "COBBLE".equals(view.goalItem())
                    ? "start with the hand; cobble needs a wooden pick crafted from the logs"
                    : "a log breaks by hand; the kit has no armor";
            gear = TaskDecision.act("WAIT").withGear("HAND", "NONE", reason);
        }
        TaskDecision next;
        if ("COMPASS".equals(view.goalItem())) {
            next = followCompass(view);
        } else if ("COBBLE".equals(view.goalItem()) && view.pickDurability() < 0) {
            if (view.planks() >= 3 && view.sticks() >= 2) {
                int[] table = find(view, "TABLE");
                if (table != null && Math.abs(table[0]) + Math.abs(table[2]) == 1) {
                    next = facing(view, table) ? TaskDecision.act("CRAFT").withGear("WOOD_PICK", null, "wooden pick, because cobble does not break by hand")
                            : TaskDecision.act(turn(view.yaw(), yawToward(table[0], table[2])));
                } else {
                    next = approach(view, "TABLE");
                }
            } else if (view.sticks() < 2 && view.planks() >= 2) {
                next = TaskDecision.act("CRAFT");
            } else if (view.logs() >= 1 && view.planks() < 3) {
                next = TaskDecision.act("CRAFT");
            } else {
                next = approach(view, "LOG");
            }
        } else if (view.cobble() >= 1 || ("PLANKS".equals(view.goalItem()) && view.planks() >= 1)) {
            next = approach(view, "DEPOSIT");
        } else if ("COBBLE".equals(view.goalItem())) {
            next = approach(view, "STONE");
        } else if (view.logs() >= 1) {
            next = TaskDecision.act("CRAFT");
        } else {
            next = approach(view, "LOG");
        }
        if (gear == null) {
            return next;
        }
        return next.withGear(gear.gearTool, gear.gearArmor, gear.gearReason);
    }

    /** Lodestone in view, otherwise the cardinal the needle shows. No absolute coordinate in legit mode. */
    private TaskDecision followCompass(ITaskView view) {
        if (find(view, "LODESTONE") != null) {
            return approach(view, "LODESTONE");
        }
        String bearing = sound(view, "COMPASS");
        if (bearing == null || bearing.isEmpty() || "SELF".equals(bearing)) {
            return TaskDecision.act("WAIT");
        }
        int want = yawOfBearing(bearing);
        if (view.yaw() != want) {
            return TaskDecision.act(turn(view.yaw(), want));
        }
        return TaskDecision.act("WALK");
    }

    private TaskDecision approach(ITaskView view, String name) {
        int[] rel = find(view, name);
        if (rel == null) {
            if (!dodgedMob && sound(view, "MOB_AMBIENT") != null) {
                dodgedMob = true;
                String away = opposite(sound(view, "MOB_AMBIENT"));
                return TaskDecision.act(turn(view.yaw(), yawOfBearing(away)));
            }
            return TaskDecision.act("TURN_LEFT");
        }
        boolean frontSolid = false;
        for (String token : view.visible()) {
            if (token.startsWith("FRONT:") && !token.endsWith("AIR") && !token.endsWith(name)) {
                frontSolid = true;
            }
        }
        int want = yawToward(rel[0], rel[2]);
        boolean adjacent = Math.abs(rel[0]) + Math.abs(rel[2]) == 1 && Math.abs(rel[1]) <= 1;
        if (adjacent && view.yaw() == want) {
            if ("DEPOSIT".equals(name)) {
                return TaskDecision.act("PLACE");
            }
            if ("TABLE".equals(name) || "LODESTONE".equals(name)) {
                return TaskDecision.act("WAIT");
            }
            return TaskDecision.act("MINE");
        }
        if (frontSolid && rel[1] > 0) {
            if (view.yaw() != want) {
                return TaskDecision.act(turn(view.yaw(), want));
            }
            return TaskDecision.act("JUMP");
        }
        if (view.yaw() != want && !(adjacent && Math.abs(rel[0]) + Math.abs(rel[2]) == 0)) {
            return TaskDecision.act(turn(view.yaw(), want));
        }
        return TaskDecision.act("WALK");
    }

    private static boolean facing(ITaskView view, int[] rel) {
        return view.yaw() == yawToward(rel[0], rel[2]);
    }

    static int[] find(ITaskView view, String name) {
        int[] seen = fromTokens(view.visible(), name, true, view);
        if (seen != null) {
            return seen;
        }
        if ("FULL".equals(view.mode())) {
            return fromTokens(view.fullBlocks(), name, false, view);
        }
        return null;
    }

    private static int[] fromTokens(java.util.List<String> tokens, String name, boolean relative, ITaskView view) {
        String prefix = name + "@";
        for (String token : tokens) {
            if (!token.startsWith(prefix)) {
                continue;
            }
            String[] parts = token.substring(prefix.length()).split(",");
            int ax = Integer.parseInt(parts[0]);
            int ay = Integer.parseInt(parts[1]);
            int az = Integer.parseInt(parts[2]);
            if (relative) {
                return new int[]{ax, ay, az};
            }
            return new int[]{ax - view.x(), ay - view.y(), az - view.z()};
        }
        return null;
    }

    static int yawToward(int dx, int dz) {
        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? 3 : 1;
        }
        if (dz > 0) {
            return 0;
        }
        if (dz < 0) {
            return 2;
        }
        return 0;
    }

    static String turn(int yaw, int want) {
        int leftSteps = (want - yaw + 4) & 3;
        if (leftSteps == 0) {
            return "WAIT";
        }
        return leftSteps <= 2 ? "TURN_LEFT" : "TURN_RIGHT";
    }

    private static String sound(ITaskView view, String kind) {
        String prefix = kind + "@";
        for (String token : view.sounds()) {
            if (token.startsWith(prefix)) {
                return token.substring(prefix.length());
            }
        }
        return null;
    }

    private static String opposite(String bearing) {
        return switch (bearing) {
            case "N" -> "S";
            case "S" -> "N";
            case "E" -> "W";
            default -> "E";
        };
    }

    private static int yawOfBearing(String bearing) {
        return switch (bearing) {
            case "S" -> 0;
            case "W" -> 1;
            case "N" -> 2;
            default -> 3;
        };
    }
}
