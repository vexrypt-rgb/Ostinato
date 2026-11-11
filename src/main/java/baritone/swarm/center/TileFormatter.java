package baritone.swarm.center;

import java.util.List;

/** Ostinato: tile text. Unknown vitals and missing memory are the words {@code n/a}, not a blank. */
public final class TileFormatter {

    private TileFormatter() {}

    public static String health(BotSnapshot bot) {
        return bot.health() < 0 ? "n/a" : Integer.toString(bot.health());
    }

    public static String hunger(BotSnapshot bot) {
        return bot.hunger() < 0 ? "n/a" : Integer.toString(bot.hunger());
    }

    public static String task(BotSnapshot bot) {
        return bot.task().isBlank() ? "n/a" : bot.task();
    }

    public static String outcome(BotSnapshot bot) {
        return bot.execution() == null ? "n/a" : bot.execution().label();
    }

    public static String observation(BotSnapshot bot) {
        return bot.observation().label();
    }

    public static String link(BotSnapshot bot) {
        return bot.link().isBlank() ? "n/a" : bot.link();
    }

    public static String memory(BotSnapshot bot) {
        return bot.memory().isBlank() ? "n/a" : bot.memory();
    }

    public static List<String> lines(BotSnapshot bot) {
        return List.of(
                bot.name(),
                "health " + health(bot),
                "hunger " + hunger(bot),
                "task " + task(bot),
                "outcome " + outcome(bot),
                "observation " + observation(bot),
                "link " + link(bot),
                "memory " + memory(bot)
        );
    }
}
