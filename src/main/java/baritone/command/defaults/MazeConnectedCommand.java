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

/**
 * Ostinato: run107, the backrooms joined by 3x3 tunnels. Rooms and tunnels are dug, clad, furnished and lit (no
 * hostile spawns, as few torches as that takes) one segment after another, forever, out of sight underground. Only
 * reachable as #maze.backrooms.connected; hidden from help and tab-complete.
 */
public class MazeConnectedCommand extends Command {

    public MazeConnectedCommand(IBaritone baritone) {
        super(baritone, "maze.backrooms.connected");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        MazeProcess process = ((Baritone) baritone).getMazeProcess();
        if (!args.hasAny()) {
            if (process.connectedRunning()) {
                process.stopConnected();
                logDirect("The hum fades.");
                return;
            }
            MazeProcess.Outcome outcome = process.startConnected();
            if (!outcome.ok() && !process.connectedRunning()) {
                throw new CommandInvalidStateException(outcome.error);
            }
            logDirect("The walls hum.");
            return;
        }
        String action = args.getString().toLowerCase(Locale.US);
        switch (action) {
            case "build" -> report(process.buildConnected(null));
            case "plan" -> report(process.planConnected());
            case "furnish" -> report(process.furnishHere());
            case "status" -> logDirect(process.connectedStatus() + (process.connectedRunning() ? " Run: " + process.driveState() : ""));
            case "cancel" -> {
                process.stopConnected();
                logDirect("Stopped");
            }
            default -> throw new CommandInvalidTypeException(args.consumed(), "build, plan, furnish, status or cancel");
        }
    }

    private void report(MazeProcess.Outcome outcome) throws CommandException {
        if (!outcome.ok()) {
            throw new CommandInvalidStateException(outcome.error);
        }
        logDirect(outcome.message);
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
        return Arrays.asList("> maze.backrooms.connected - start or stop the endless connected run",
                "> maze.backrooms.connected build|plan|furnish|status|cancel");
    }
}
