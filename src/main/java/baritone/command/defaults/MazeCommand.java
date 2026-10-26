/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.command.exception.CommandInvalidTypeException;
import baritone.process.MazeProcess;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Ostinato: secret maze easter egg. Only reachable as #maze.backrooms; hidden from help and tab-complete. */
public class MazeCommand extends Command {

    public MazeCommand(IBaritone baritone) {
        super(baritone, "maze.backrooms");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        MazeProcess process = ((Baritone) baritone).getMazeProcess();
        if (!args.hasAny()) {
            boolean on = !Baritone.settings().mazeMode.value;
            Baritone.settings().mazeMode.value = on;
            if (!on) {
                process.cancel();
            }
            logDirect(on ? "The walls hum." : "The hum fades.");
            return;
        }
        String action = args.getString().toLowerCase(Locale.US);
        switch (action) {
            case "build" -> {
                MazeProcess.Outcome outcome = process.buildHere();
                if (!outcome.ok()) {
                    throw new CommandInvalidStateException(outcome.error);
                }
                logDirect(outcome.message);
            }
            case "plan" -> {
                MazeProcess.Outcome outcome = process.planHere();
                if (!outcome.ok()) {
                    throw new CommandInvalidStateException(outcome.error);
                }
                logDirect(outcome.message);
            }
            case "status" -> logDirect(process.status());
            case "cancel" -> {
                process.cancel();
                logDirect("Stopped");
            }
            default -> throw new CommandInvalidTypeException(args.consumed(), "build, plan, status or cancel");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) {
        return Stream.empty(); // Ostinato: nothing to discover
    }

    @Override
    public boolean hiddenFromHelp() {
        return true;
    }

    @Override
    public String getShortDesc() {
        return "";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList("> maze.backrooms - toggle", "> maze.backrooms build|plan|status|cancel");
    }
}
