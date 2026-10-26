package baritone.api.agent;

// Ostinato: one singleplayer race world. The live client opens an existing world and leaves it.
// Creating a new world through the Create World screen is stubbed. Tests use a folder host.

/**
 * Creates, watches, and leaves one race world. Deletion stays on {@link WorldCleanup}.
 */
public interface RaceWorldHost {

    enum Status {
        /** The world is in and the agent may run. */
        READY,
        /** Still opening or still leaving. Poll again. */
        PENDING,
        /** No Minecraft world was created. The queue records {@code stubbed} and moves on. */
        STUBBED,
        /** The create or leave failed. */
        FAILED
    }

    final class Result {
        public final Status status;
        public final String detail;

        public Result(Status status, String detail) {
            this.status = status == null ? Status.FAILED : status;
            this.detail = detail == null ? "" : detail;
        }

        public static Result ready() {
            return new Result(Status.READY, "");
        }

        public static Result pending() {
            return new Result(Status.PENDING, "");
        }

        public static Result stubbed(String detail) {
            return new Result(Status.STUBBED, detail);
        }

        public static Result failed(String detail) {
            return new Result(Status.FAILED, detail);
        }
    }

    /** Start creating or opening {@code world}. Seed and flat are recorded for a live create. */
    void beginCreate(String world, String seed, boolean flat);

    /** Result of the create started by {@link #beginCreate}. */
    Result pollCreate();

    /** Leave the current world without deleting it. */
    void beginLeave();

    /** Result of {@link #beginLeave}. {@link Status#READY} means no world is open. */
    Result pollLeave();

    /** True while a world this host opened is still loaded. */
    boolean inWorld();

    /** Folder name of the open world, or empty. */
    String currentWorld();
}
