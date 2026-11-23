package baritone.swarm.center.app;

import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.TileRoute;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.channel.CommandPort;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Ostinato: Phase 3 window, the only way a view issues a command. It sends one existing registry form
 * ({@link #press}) or one registry line ({@link #line}) to the command host, off the Swing thread, and hands the
 * signed reply back on it. {@link #preview} is the equivalent {@code #} line, computed with the host's own routing
 * rule ({@link TileRoute}), so what the window shows before a click is what the host runs.
 */
public final class Commands {

    public static final int HISTORY = 20;

    private final CommandPort port;
    private final Executor background;
    private final Executor ui;
    private final LongSupplier clock;
    private final Deque<CenterReply> history = new ArrayDeque<>();
    private final List<Consumer<CenterReply>> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param background where {@link CommandPort#send} runs (a single daemon thread in the app; direct in tests)
     * @param ui         where replies are delivered (the Swing thread in the app; direct in tests)
     */
    public Commands(CommandPort port, Executor background, Executor ui, LongSupplier clock) {
        this.port = port;
        this.background = background;
        this.ui = ui;
        this.clock = clock == null ? System::currentTimeMillis : clock;
    }

    /** The {@code #} line this form would run on {@code host}, or the form's own reason (for example "missing target"). */
    public static String preview(ButtonModel button, Map<String, String> values, String bot, String host) {
        try {
            return TileRoute.preview(button, bot, TileRoute.fill(button, values, bot), host);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    /** Whether the form can be sent as filled in. */
    public static boolean ready(ButtonModel button, Map<String, String> values, String bot) {
        try {
            Map<String, String> filled = TileRoute.fill(button, values, bot);
            button.line(filled);
            TileRoute.route(button, bot, filled); // Ostinato: a route can refuse too (a cancel with no id)
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public void press(ButtonModel button, Map<String, String> values, String bot, Consumer<CenterReply> done) {
        send(CenterRequest.press(button.command(), button.form().id(), TileRoute.fill(button, values, bot),
                bot, clock.getAsLong()), done);
    }

    public void line(String line, String bot, Consumer<CenterReply> done) {
        send(CenterRequest.line(line, bot, clock.getAsLong()), done);
    }

    private void send(CenterRequest request, Consumer<CenterReply> done) {
        background.execute(() -> {
            CenterReply reply;
            try {
                reply = port.send(request);
            } catch (RuntimeException e) {
                reply = CenterReply.refused(request.id(), "send failed", clock.getAsLong());
            }
            CenterReply r = reply == null ? CenterReply.refused(request.id(), "no reply", clock.getAsLong()) : reply;
            ui.execute(() -> {
                synchronized (history) {
                    history.addFirst(r);
                    while (history.size() > HISTORY) {
                        history.removeLast();
                    }
                }
                if (done != null) {
                    done.accept(r);
                }
                for (Consumer<CenterReply> l : listeners) {
                    l.accept(r);
                }
            });
        });
    }

    /** Called on the ui executor for every reply (the footer uses it). */
    public void onReply(Consumer<CenterReply> listener) {
        listeners.add(listener);
    }

    /** Newest first. */
    public List<CenterReply> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }
}
