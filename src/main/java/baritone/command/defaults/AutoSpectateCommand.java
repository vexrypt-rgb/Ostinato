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
import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;
import baritone.spectate.ServerSpectate;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Ostinato: watch-only auto-spectate on a server ({@link ServerSpectate}). */
public class AutoSpectateCommand extends Command {

    public AutoSpectateCommand(IBaritone baritone) {
        super(baritone, "autospectate", "autospec");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        ServerSpectate ss = ((Baritone) baritone).getServerSpectate();
        String sub = args.hasAny() ? args.getString().toLowerCase(Locale.ROOT) : (ss.running() ? "stop" : "start");
        switch (sub) {
            case "start" -> {
                String host = args.hasAny() ? args.getString() : BaritoneAPI.getSettings().autoSpectateServer.value;
                args.requireMax(0);
                ss.start(host);
            }
            case "stop" -> ss.stop("stopped by user");
            case "status" -> {
                logDirect(ss.summary());
                for (String m : ss.menus()) logDirect("menu " + m);
            }
            default -> logDirect("Usage: autospectate [start [host] | stop | status]");
        }
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String p = args.getString().toLowerCase(Locale.ROOT);
            return Stream.of("start", "stop", "status").filter(s -> s.startsWith(p));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Join a server and watch fights (watch only)";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "Joins autoSpectateServer, answers /register or /login with a local random password, walks the lobby",
                "menus to a spectator spot, and follows fights so spectateLearn records them. It never attacks or chats.",
                "It leaves and never rejoins on a captcha, a staff message about bots, a kick or a ban.",
                "",
                "Usage:",
                "> autospectate - start, or stop when running",
                "> autospectate start [host]",
                "> autospectate stop",
                "> autospectate status - fights recorded and the menus seen"
        );
    }
}
