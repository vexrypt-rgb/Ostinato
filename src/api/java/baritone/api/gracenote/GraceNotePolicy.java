/*
 * Ostinato: new file (Grace Note). Not part of upstream Baritone.
 */
package baritone.api.gracenote;

/**
 * What Grace Note may spend and when it may interrupt. The default is the conservative local policy Ostinato uses on its
 * own; a caller with a strategy (TenorClef) replaces it.
 *
 * @param allowWaterBucket      the water bucket may be emptied to break a fall
 * @param minDamage             act on a landing that costs at least this much health (half hearts); a lethal one always counts
 * @param recoverWater          pick the water back up afterwards, while still holding control
 * @param respectInteractionPause stay out while the agent has paused interaction (AltoClefSettings), unless lethal
 * @param cooldownTicks         after an episode that took control, the next may only for a lethal landing until this passes
 * @param maxActionsPerWindow   at most this many control takeovers in any {@link #WINDOW_TICKS} ticks, lethal or not
 * @param source                who set it, for the record
 */
public record GraceNotePolicy(boolean allowWaterBucket, float minDamage, boolean recoverWater,
                              boolean respectInteractionPause, int cooldownTicks, int maxActionsPerWindow, String source) {

    public static final int WINDOW_TICKS = 200;

    public static final GraceNotePolicy DEFAULT = new GraceNotePolicy(true, 4f, true, true, 10, 4, "ostinato-default");

    public GraceNotePolicy {
        if (!(minDamage >= 0) || !Float.isFinite(minDamage)) throw new IllegalArgumentException("minDamage " + minDamage);
        if (cooldownTicks < 0 || cooldownTicks > 1200) throw new IllegalArgumentException("cooldownTicks " + cooldownTicks);
        if (maxActionsPerWindow < 0 || maxActionsPerWindow > 64) throw new IllegalArgumentException("maxActionsPerWindow " + maxActionsPerWindow);
        source = source == null ? "?" : source;
    }
}
