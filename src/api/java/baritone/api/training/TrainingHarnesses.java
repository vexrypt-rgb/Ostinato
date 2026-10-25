package baritone.api.training;

/** Ostinato: the live harness, registered when commands load. Null until then. */
public final class TrainingHarnesses {

    private static ITrainingHarness harness;

    private TrainingHarnesses() {}

    public static void register(ITrainingHarness next) {
        harness = next;
    }

    public static ITrainingHarness get() {
        return harness;
    }
}
