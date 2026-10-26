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
import baritone.api.command.helpers.TabCompleteHelper;
import baritone.process.MazeProcess;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class MazeCommand extends Command {

    public MazeCommand(IBaritone baritone) {
        super(baritone, "maze");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        MazeProcess process = ((Baritone) baritone).getMazeProcess();
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.US) : "build";
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
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper().append("build", "plan", "status", "cancel").filterPrefix(args.getString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Plan or build one sealed underground maze room";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Builds one Backrooms-style room under you, plus a corridor stub that is plugged shut.",
                "The room is refused or shifted when air or fluid touches the shell, so it does not open into a cave.",
                "Off unless mazeMode is true, and it does not change normal pathing while it is off.",
                "",
                "Each build claims one cell in the maze map so a later build, or another bot sharing that file, does not dig it again.",
                "Containers are placed empty; their seed-stable contents are written to the map and not refilled.",
                "",
                "Usage:",
                "> maze - plan a sealed room and, after the stealth delay, hand it to the builder.",
                "> maze build - the same.",
                "> maze plan - report the room and do not build or claim it.",
                "> maze status - whether maze mode is on, and what it is doing.",
                "> maze cancel - stop a wait, or cancel a maze build the builder has taken."
        );
    }
}
