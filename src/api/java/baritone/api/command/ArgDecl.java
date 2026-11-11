package baritone.api.command;

import java.util.List;
import java.util.Locale;

/**
 * Ostinato: one declared argument. Menus and the command center build fields from these
 * so they stay on the same command the chat line uses.
 */
public record ArgDecl(String name, ArgKind kind, boolean required, List<String> choices) {

    public ArgDecl {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("argument name");
        }
        kind = kind == null ? ArgKind.TOKEN : kind;
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    public static ArgDecl token(String name, boolean required) {
        return new ArgDecl(name, ArgKind.TOKEN, required, List.of());
    }

    public static ArgDecl integer(String name, boolean required) {
        return new ArgDecl(name, ArgKind.INT, required, List.of());
    }

    public static ArgDecl rest(String name, boolean required) {
        return new ArgDecl(name, ArgKind.REST, required, List.of());
    }

    public static ArgDecl enu(String name, boolean required, List<String> choices) {
        return new ArgDecl(name, ArgKind.ENUM, required, choices);
    }

    /** Null when {@code value} is acceptable. */
    public String reject(String value) {
        if (value == null || value.isBlank()) {
            return required ? "missing " + name : null;
        }
        String text = value.trim();
        return switch (kind) {
            case INT -> text.matches("[0-9]{1,6}") ? null : "bad int";
            case ENUM -> choices.stream().anyMatch(c -> c.equalsIgnoreCase(text)) ? null : "bad " + name;
            case TOKEN -> text.matches("[A-Za-z0-9_.:\\-]{1,64}") ? null : "bad " + name;
            case REST -> (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.indexOf(';') >= 0
                    || text.length() > 200) ? "bad " + name : null;
        };
    }

    public boolean matchesEnum(String value) {
        if (value == null || kind != ArgKind.ENUM) {
            return false;
        }
        String text = value.trim().toLowerCase(Locale.ROOT);
        for (String choice : choices) {
            if (choice.toLowerCase(Locale.ROOT).equals(text)) {
                return true;
            }
        }
        return false;
    }
}
