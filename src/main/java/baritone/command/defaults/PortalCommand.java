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
import baritone.process.PortalBuildProcess;
import baritone.process.portal.PortalSiteFinder.Candidate;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class PortalCommand extends Command {

    public PortalCommand(IBaritone baritone) {
        super(baritone, "portal");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(2);
        PortalBuildProcess process = ((Baritone) baritone).getPortalBuildProcess();
        String action = args.hasAny() ? args.getString().toLowerCase(Locale.US) : "build";
        switch (action) {
            case "build": {
                boolean enter = false;
                if (args.hasAny()) {
                    String option = args.getString();
                    if (!option.equalsIgnoreCase("enter")) {
                        throw new CommandInvalidTypeException(args.consumed(), "enter");
                    }
                    enter = true;
                }
                String problem = process.build(enter);
                if (problem != null) {
                    throw new CommandInvalidStateException("Cannot build a portal: " + problem);
                }
                logDirect("Building a portal: " + process.site());
                break;
            }
            case "find": {
                List<Candidate> found = process.find();
                if (found.isEmpty()) {
                    logDirect("No lava pool shore nearby that the portal fits on");
                    break;
                }
                for (int i = 0; i < Math.min(5, found.size()); i++) {
                    logDirect((i + 1) + ". " + found.get(i));
                }
                break;
            }
            case "status": {
                if (process.isActive()) {
                    logDirect(process.displayName());
                } else if (process.lastResult() != null) {
                    logDirect(process.lastResult().toString());
                } else {
                    logDirect("No portal has been built");
                }
                break;
            }
            case "cancel": {
                if (!process.isActive()) {
                    throw new CommandInvalidStateException("No portal is being built");
                }
                baritone.getPathingBehavior().cancelEverything();
                logDirect("Stopped");
                break;
            }
            default:
                throw new CommandInvalidTypeException(args.consumed(), "build, find, status or cancel");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            return new TabCompleteHelper().append("build", "find", "status", "cancel").filterPrefix(args.getString()).stream();
        }
        if (args.hasExactly(2) && args.getString().equalsIgnoreCase("build")) {
            return new TabCompleteHelper().append("enter").filterPrefix(args.getString()).stream();
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Build a nether portal in a lava pool";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Builds a nether portal in a lava pool with a water bucket: the water sets the base, a few blocks",
                "on the shore hold it above the frame, and lava from the pool is poured against it cell by cell.",
                "",
                "Needs a water bucket, a second bucket, flint and steel and seven blocks to build with. A pickaxe",
                "is needed where the pool is one block deep, and to put right a cell that set as cobblestone.",
                "",
                "Usage:",
                "> portal build - walks to the best stretch of shore nearby and builds there.",
                "> portal build enter - the same, then walks into the portal.",
                "> portal find - lists the sites a build would consider.",
                "> portal status - what the build is doing, or how the last one went.",
                "> portal cancel - stops."
        );
    }
}
