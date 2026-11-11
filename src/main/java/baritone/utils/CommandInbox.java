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

package baritone.utils;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.utils.Helper;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Ostinato: when {@code -Dostinato.commandInbox=true}, drain {@code <gameDir>/command-inbox.txt}
 * on in-game ticks and run each line through the Baritone command manager. Results append to
 * {@code command-outbox.txt}. A no-op unless the property is set, so flag-off clients are unchanged.
 */
public final class CommandInbox implements AbstractGameEventListener, Helper {

    public static final boolean ENABLED = Boolean.getBoolean("ostinato.commandInbox");

    private final Baritone baritone;
    private boolean busy;

    private CommandInbox(Baritone baritone) {
        this.baritone = baritone;
    }

    public static void install(Baritone baritone) {
        if (!ENABLED) {
            return;
        }
        baritone.getGameEventHandler().registerEventListener(new CommandInbox(baritone));
    }

    @Override
    public void onPostTick(TickEvent event) {
        // Ostinato: GameEventHandler delivers PRE on onTick and POST on onPostTick.
        if (event.getType() != TickEvent.Type.IN) {
            return;
        }
        drain();
    }

    private void drain() {
        if (busy) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) {
            return;
        }
        Path dir = mc.gameDirectory.toPath();
        Path inbox = dir.resolve("command-inbox.txt");
        Path staging = dir.resolve("command-inbox.processing");
        Path outbox = dir.resolve("command-outbox.txt");
        if (!Files.exists(staging) && !Files.exists(inbox)) {
            return;
        }
        busy = true;
        try {
            if (!Files.exists(staging)) {
                try {
                    Files.move(inbox, staging, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    return;
                }
            }
            String text = new String(Files.readAllBytes(staging), StandardCharsets.UTF_8);
            Files.deleteIfExists(staging);
            StringBuilder report = new StringBuilder();
            for (String cmd : CommandInboxText.commands(text)) {
                boolean ok;
                try {
                    ok = baritone.getCommandManager().execute(cmd);
                } catch (Throwable t) {
                    report.append("error cmd=").append(cmd).append(' ').append(t).append(System.lineSeparator());
                    logDirect("command-inbox error: " + cmd + " " + t);
                    continue;
                }
                report.append(ok ? "accepted" : "rejected").append(" cmd=").append(cmd).append(System.lineSeparator());
                logDirect("command-inbox " + (ok ? "accepted" : "rejected") + ": " + cmd);
            }
            if (report.length() > 0) {
                Files.write(outbox, report.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            logDirect("command-inbox io: " + e);
        } finally {
            busy = false;
        }
    }
}
