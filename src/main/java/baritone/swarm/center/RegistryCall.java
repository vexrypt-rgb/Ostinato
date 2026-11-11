package baritone.swarm.center;

/**
 * Ostinato: one request into {@link UnifiedRegistry#execute}. Local chat, the window, swarm, AI and public
 * paths all use this. {@link Kind#LOCAL_CHAT} does not read the permission tag.
 */
public final class RegistryCall {

    public enum Kind {
        LOCAL_CHAT,
        WINDOW,
        SWARM,
        AI,
        PUBLIC
    }

    public enum Scope {
        LOCAL,
        ONE,
        GROUP,
        ALL
    }

    public final Kind kind;
    public final boolean authenticated;
    public final String actor;
    public final String ownerName;
    public final boolean swarmMember;
    public final boolean ownerOnly;
    public final String tools;
    public final boolean dryRun;
    public final String line;
    public final String target;
    public final Scope scope;

    public RegistryCall(Kind kind, boolean authenticated, String actor, String ownerName, boolean swarmMember,
                        boolean ownerOnly, String tools, boolean dryRun, String line, String target, Scope scope) {
        this.kind = kind == null ? Kind.PUBLIC : kind;
        this.authenticated = authenticated;
        this.actor = actor == null ? "" : actor;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.swarmMember = swarmMember;
        this.ownerOnly = ownerOnly;
        this.tools = tools == null ? "" : tools;
        this.dryRun = dryRun;
        this.line = line == null ? "" : line.trim();
        this.target = target;
        this.scope = scope == null ? Scope.LOCAL : scope;
    }

    public RegistryCall withLine(String next) {
        return new RegistryCall(kind, authenticated, actor, ownerName, swarmMember, ownerOnly, tools, dryRun,
                next, target, scope);
    }

    public RegistryCall withScope(Scope next, String nextTarget) {
        return new RegistryCall(kind, authenticated, actor, ownerName, swarmMember, ownerOnly, tools, dryRun,
                line, nextTarget, next);
    }

    public static RegistryCall localChat(String line) {
        return new RegistryCall(Kind.LOCAL_CHAT, true, "local", "", false, false, "", false, line, null, Scope.LOCAL);
    }

    public static RegistryCall window(String actor, String ownerName, String line) {
        return new RegistryCall(Kind.WINDOW, true, actor, ownerName, false, true, "", false, line, null, Scope.LOCAL);
    }

    public static RegistryCall swarm(String actor, boolean member, String line) {
        return new RegistryCall(Kind.SWARM, actor != null && !actor.isBlank(), actor, "", member, true, "", false,
                line, actor, Scope.ONE);
    }

    public static RegistryCall ai(String actor, String ownerName, boolean ownerOnly, String tools, boolean dryRun, String line) {
        return new RegistryCall(Kind.AI, actor != null && !actor.isBlank(), actor, ownerName, false, ownerOnly, tools,
                dryRun, line, null, Scope.LOCAL);
    }

    public static RegistryCall pub(String actor, String line) {
        return new RegistryCall(Kind.PUBLIC, actor != null && !actor.isBlank(), actor, "", false, true, "", false,
                line, null, Scope.LOCAL);
    }
}
