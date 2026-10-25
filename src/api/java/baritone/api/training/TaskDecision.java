package baritone.api.training;

/**
 * Ostinato: one tick of a task model. Gear fields are the task/gear matrix.
 * Pathfinding features are not carried here.
 */
public final class TaskDecision {

    public final String act;
    public final String gearTool;
    public final String gearArmor;
    public final String gearReason;

    public TaskDecision(String act, String gearTool, String gearArmor, String gearReason) {
        this.act = act;
        this.gearTool = gearTool;
        this.gearArmor = gearArmor;
        this.gearReason = gearReason;
    }

    public static TaskDecision act(String act) {
        return new TaskDecision(act, null, null, null);
    }

    public TaskDecision withGear(String tool, String armor, String reason) {
        return new TaskDecision(act, tool, armor, reason);
    }
}
