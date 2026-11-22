package baritone.swarm.center.app.fake;

import baritone.swarm.center.ButtonModel;
import baritone.swarm.center.TileRoute;
import baritone.swarm.center.channel.CenterReply;
import baritone.swarm.center.channel.CenterRequest;
import baritone.swarm.center.channel.CommandPort;
import baritone.swarm.center.feed.CatalogFeed;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Ostinato: Phase 3 test scaffolding, a {@link CommandPort} with no host. Records every request and answers like an
 * admitting host would: the line is the host's routing of the named form ({@link TileRoute}), reason "accepted".
 * {@link #answer} replaces that, for refusal tests ({@code r -> CenterReply.refused(r.id(), "busy", 0)}).
 */
public final class FakeCommandPort implements CommandPort {

    private final String host;
    private final List<CenterRequest> sent = new ArrayList<>();
    private Function<CenterRequest, CenterReply> answer;

    public FakeCommandPort(String host) {
        this.host = host == null ? "" : host;
    }

    public FakeCommandPort answer(Function<CenterRequest, CenterReply> fn) {
        this.answer = fn;
        return this;
    }

    @Override
    public synchronized CenterReply send(CenterRequest request) {
        sent.add(request);
        if (answer != null) {
            return answer.apply(request);
        }
        return new CenterReply(request.id(), request.atMs(), true, request.op() != CenterRequest.Op.PING, "accepted",
                lineFor(request), host);
    }

    /** The line an admitting host would run for this request, or empty for an unknown form. */
    public String lineFor(CenterRequest r) {
        if (r.op() == CenterRequest.Op.LINE) {
            return r.line();
        }
        for (CatalogFeed.Button b : FakeSwarm.CATALOG) {
            if (b.command().equals(r.command()) && b.form().id().equals(r.formId())) {
                ButtonModel m = b.model();
                return TileRoute.route(m, TileRoute.target(r.bot(), host), r.values());
            }
        }
        return "";
    }

    public synchronized List<CenterRequest> sent() {
        return List.copyOf(sent);
    }
}
