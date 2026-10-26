package baritone.agent;

// Ostinato: one in-flight provider call per client. A second #agent waits until this one finishes.

import java.util.concurrent.atomic.AtomicBoolean;

public final class AgentSession {

    public static final AgentSession CLIENT = new AgentSession();

    private final AtomicBoolean busy = new AtomicBoolean();

    public boolean tryStart() {
        return busy.compareAndSet(false, true);
    }

    public void finish() {
        busy.set(false);
    }

    public boolean busy() {
        return busy.get();
    }
}
