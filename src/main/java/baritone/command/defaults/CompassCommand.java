package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.training.ClientLegit;
import baritone.training.CompassNeedle;
import baritone.training.HeldCompass;
import net.minecraft.client.Minecraft;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Ostinato: print the compass needle. Pathing follows it only while trainingLegit and compassFollow are on. */
public class CompassCommand extends Command {

    public CompassCommand(IBaritone baritone) {
        super(baritone, "compass");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(0);
        boolean legit = Baritone.settings().trainingLegit.value;
        boolean follow = Baritone.settings().compassFollow.value;
        if (ctx.player() == null || ctx.world() == null) {
            logDirect("compass: not in a world. trainingLegit=" + legit + " compassFollow=" + follow);
            return;
        }
        CompassNeedle.Reading reading = HeldCompass.read(ctx);
        if (!reading.aimed) {
            logDirect("compass " + reading.kind + " has no client target (needle spins). trainingLegit=" + legit + " compassFollow=" + follow);
        } else {
            logDirect("compass " + reading.kind + " " + reading.bearing + " -> " + reading.x + " " + reading.y + " " + reading.z
                    + " " + reading.dimension + " trainingLegit=" + legit + " compassFollow=" + follow);
        }
        if (!legit) {
            logDirect("trainingLegit is off, so pathing still uses the cached world. compassFollow does not take a goal until both are on.");
            return;
        }
        ClientLegit cues = ClientLegit.capture(Minecraft.getInstance());
        String pie = cues.pieTop.isEmpty() ? "closed" : cues.pieTop;
        logDirect("F3 " + cues.biome + " temp " + cues.temperature + " pie " + pie + " sounds " + String.join(" ", cues.sounds));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Read the held compass needle";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Prints the lodestone, spawn, recovery, or loaded-player target of the compass in the main hand, offhand, or hotbar.",
                "The goal is that needle. A named player who is not loaded on this client is not a coordinate.",
                "Pathing follows it while trainingLegit and compassFollow are both on, and only through chunks inside render distance.",
                "",
                "Usage:",
                "> compass");
    }
}
