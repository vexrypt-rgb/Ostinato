package baritone.bombard;

/** Require consecutive settled observations before a targetless STAB packet relies on the previous sent rotation. */
public final class AimWindow {
    private long tick = Long.MIN_VALUE;
    private int id, settled;
    private float yaw, pitch;
    public boolean ready(long now, int target, float currentYaw, float currentPitch, float headYaw) {
        boolean stable = now == tick + 1 && target == id && Math.abs(angle(currentYaw - yaw)) <= 2
                && Math.abs(currentPitch - pitch) <= 2 && Math.abs(angle(headYaw - currentYaw)) <= 2;
        settled = stable ? settled + 1 : 0;
        tick = now; id = target; yaw = currentYaw; pitch = currentPitch;
        return settled >= 2;
    }
    private static float angle(float angle) { return ((angle % 360) + 540) % 360 - 180; }
}
