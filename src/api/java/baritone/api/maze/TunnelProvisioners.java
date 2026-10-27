package baritone.api.maze;

/** Ostinato: where a client mod (TenorClef) registers its {@link ITunnelProvisioner}. At most one. */
public final class TunnelProvisioners {

    private static volatile ITunnelProvisioner provisioner;

    private TunnelProvisioners() {}

    public static void register(ITunnelProvisioner value) {
        provisioner = value;
    }

    /** Null when nothing is registered. */
    public static ITunnelProvisioner get() {
        return provisioner;
    }
}
