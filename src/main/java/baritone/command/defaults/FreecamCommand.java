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
import baritone.behavior.FreecamBehavior;
import baritone.behavior.SparKit;
import baritone.gui.studio.BlockPalette;
import baritone.gui.studio.EditOps;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class FreecamCommand extends Command {

    private static final String[] ACTIONS = {
            "spar", "flat", "edit", "apply", "live", "done", "clear", "undo", "tool", "brush", "radius"
    };

    public FreecamCommand(IBaritone baritone) {
        super(baritone, "freecam");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        FreecamBehavior freecam = ((Baritone) baritone).getFreecamBehavior();
        if (!args.hasAny()) {
            freecam.toggle();
            return;
        }
        String sub = args.getString().toLowerCase(Locale.ROOT);
        switch (sub) {
            case "spar" -> {
                args.requireMax(1); // Ostinato: an optional kit
                freecam.toggleSpar(args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : "sword");
            }
            case "flat" -> {
                args.requireMax(0);
                freecam.toggleFlat();
            }
            case "edit" -> {
                args.requireMax(0);
                freecam.toggleEdit();
            }
            case "apply" -> {
                args.requireMax(0);
                freecam.applyCanvas();
            }
            case "live" -> {
                args.requireMax(0);
                freecam.toggleCanvasLive();
            }
            case "done" -> {
                args.requireMax(0);
                freecam.finishCanvas();
            }
            case "clear" -> {
                args.requireMax(0);
                freecam.clearCanvas();
            }
            case "undo" -> {
                args.requireMax(0);
                freecam.undoCanvas();
            }
            case "tool" -> {
                freecam.canvasTool(args.getString());
                args.requireMax(0);
            }
            case "brush" -> {
                freecam.canvasBrush(args.getString());
                args.requireMax(0);
            }
            case "radius" -> {
                freecam.canvasRadius(args.getAs(Integer.class));
                args.requireMax(0);
            }
            default -> throw new CommandInvalidStateException("Unknown freecam action. See #help freecam");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String prefix = args.getString().toLowerCase(Locale.ROOT);
            return Arrays.stream(ACTIONS).filter(s -> s.startsWith(prefix));
        }
        if (args.hasExactly(2)) {
            String sub = args.getString().toLowerCase(Locale.ROOT);
            String prefix = args.getString().toLowerCase(Locale.ROOT);
            if (sub.equals("tool")) {
                return Arrays.stream(EditOps.Tool.values()).map(tool -> tool.wire).filter(s -> s.startsWith(prefix));
            }
            if (sub.equals("spar")) { // Ostinato
                return Arrays.stream(SparKit.NAMES).filter(s -> s.startsWith(prefix));
            }
            if (sub.equals("brush")) {
                return Arrays.stream(BlockPalette.placeable()).mapToObj(BlockPalette::name).filter(s -> s.startsWith(prefix)).limit(40);
            }
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Fly a detached camera, spar, or build on a client canvas";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Toggles a camera body that walks, swims, and slime-jumps with the game's own movement.",
                "Double-tap jump to fly. Flight phases the camera and the bot through blocks.",
                "Leaving flight (double-tap again) puts collision back, so parkour beside the bot is solid.",
                "The camera cannot leave the bot's render distance.",
                "",
                "Left click: follow the entity under the crosshair, else travel to the block under it.",
                "Right click: travel to the camera's position.",
                "The bot's hotbar, and any open container, is drawn above its head.",
                "",
                "Flat is a client superflat under the camera. The bot keeps its task. The pad is not in the world.",
                "Edit copies the selection, or 16x12x16 around the block you look at, and lets you restyle that copy.",
                "Neither mode writes blocks until apply. Live hands each edit to the builder and takes over building.",
                "On a flat canvas, apply places only blocks you added. Edit apply also breaks blocks you removed.",
                "Tools: brush, select, fill, replace, line, sphere, hollow, wall, copy, paste, rotate, flip.",
                "The brush follows the held block. Middle click picks a block. brush hand goes back to the hotbar.",
                "Clicks in flat or edit do not send the bot anywhere. Flight still phases the bot, on a singleplayer world.",
                "",
                "Usage:",
                "> freecam - toggle freecam",
                "> freecam spar [kit] - client-side fight against a ghost running the bot's PvP logic, vanilla damage",
                ">   kits: sword, axe+shield, mace, crystal, pot, uhc, smp, netherite-pot (both sides; default sword)",
                "> freecam flat - superflat canvas under the camera",
                "> freecam edit - edit the selection, or the build you are looking at",
                "> freecam tool <name> - brush, select, fill, replace, line, sphere, hollow, wall, copy, paste, rotate, flip",
                "> freecam brush <block> - lock the brush. freecam brush hand follows the hotbar",
                "> freecam radius <1-8> - sphere size",
                "> freecam live - toggle building each edit as you make it",
                "> freecam apply - build the difference now and keep editing",
                "> freecam done - build the difference and close the canvas",
                "> freecam undo / clear"
        );
    }
}
