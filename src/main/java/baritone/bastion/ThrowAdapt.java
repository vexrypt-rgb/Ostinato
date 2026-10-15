package baritone.bastion;

/**
 * Pitch correction for pickup-hole throws. When an ingot comes back into our own column it hit the shared corner:
 * lob higher next time. When it lands past the target, come down again. Pure logic.
 */
public final class ThrowAdapt {
    public static final float STEP = 4f, MAX = 24f;
    private float bias;

    /** Degrees to subtract from the aimed pitch (more negative pitch = higher lob). */
    public float bias() {
        return bias;
    }

    /** Record where the ingot came to rest relative to our block and the target block (block coordinates). */
    public void landed(int lx, int lz, int ourX, int ourZ, int tx, int tz) {
        if (lx == ourX && lz == ourZ) bias = Math.min(MAX, bias + STEP);
        else if (lx != tx || lz != tz) {
            // beyond the target, away from us: ease off
            long dOur = (long) (lx - ourX) * (lx - ourX) + (long) (lz - ourZ) * (lz - ourZ);
            long dT = (long) (tx - ourX) * (tx - ourX) + (long) (tz - ourZ) * (tz - ourZ);
            if (dOur > dT) bias = Math.max(0, bias - STEP / 2);
        }
    }

    public float pitch(float aimed) {
        return Math.max(-89f, aimed - bias);
    }
}
