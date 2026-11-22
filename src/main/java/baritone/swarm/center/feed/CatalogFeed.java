package baritone.swarm.center.feed;

import baritone.api.command.ArgDecl;
import baritone.api.command.ArgKind;
import baritone.api.command.CommandPermission;
import baritone.api.command.FormDecl;
import baritone.api.command.FormPlacement;
import baritone.swarm.center.ButtonModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ostinato: Phase 3 read API v1, {@code catalog.json}. The command host's registry buttons ({@code ICommand.forms()} as
 * {@code UnifiedRegistry.buttons()} returns them), so the window draws exactly the forms the host will admit and can
 * show the equivalent {@code #} line before anything is sent. This is a copy of the existing catalog, not a new one.
 */
public record CatalogFeed(String host, long writtenAtMs, long seq, List<Button> buttons) {

    public CatalogFeed {
        host = host == null ? "" : host;
        buttons = buttons == null ? List.of() : List.copyOf(buttons);
    }

    /** One form of one registry command, with the command's permission tag. */
    public record Button(String command, CommandPermission permission, FormDecl form) {
        public ButtonModel model() {
            return new ButtonModel(command, form);
        }

        Map<String, Object> toJson() {
            List<Object> args = new ArrayList<>();
            for (ArgDecl a : form.args()) {
                args.add(Json.map("name", a.name(), "kind", a.kind().name().toLowerCase(Locale.ROOT),
                        "required", a.required(), "choices", a.choices()));
            }
            return Json.map("command", command, "permission", permission.name().toLowerCase(Locale.ROOT),
                    "id", form.id(), "label", form.label(), "prefix", form.prefix(),
                    "placement", form.placement().name().toLowerCase(Locale.ROOT), "args", args);
        }

        static Button fromJson(Map<String, Object> m) {
            List<ArgDecl> args = new ArrayList<>();
            for (Map<String, Object> a : Json.objs(m, "args")) {
                args.add(new ArgDecl(Json.str(a, "name", "arg"), kind(Json.str(a, "kind", "token")),
                        Json.bool(a, "required", false), Json.strs(a, "choices")));
            }
            FormDecl form = new FormDecl(Json.str(m, "id", "?"), Json.str(m, "label", ""), Json.str(m, "prefix", "?"),
                    args, placement(Json.str(m, "placement", "tile")));
            CommandPermission permission;
            try {
                permission = CommandPermission.parse(Json.str(m, "permission", "owner"));
            } catch (IllegalArgumentException e) {
                permission = CommandPermission.OWNER;
            }
            return new Button(Json.str(m, "command", ""), permission, form);
        }
    }

    /** The buttons with this placement, in catalog order. */
    public List<ButtonModel> models(FormPlacement placement) {
        List<ButtonModel> out = new ArrayList<>();
        for (Button b : buttons) {
            if (b.form().placement() == placement) {
                out.add(b.model());
            }
        }
        return out;
    }

    public static CatalogFeed of(String host, long writtenAtMs, long seq, List<ButtonModel> models,
                                 java.util.function.Function<String, CommandPermission> permissionOf) {
        List<Button> out = new ArrayList<>();
        for (ButtonModel m : models) {
            CommandPermission p = permissionOf == null ? null : permissionOf.apply(m.command());
            out.add(new Button(m.command(), p == null ? CommandPermission.OWNER : p, m.form()));
        }
        return new CatalogFeed(host, writtenAtMs, seq, out);
    }

    public String toJson() {
        Map<String, Object> m = CenterFeed.envelope(CenterFeed.KIND_CATALOG, writtenAtMs, seq);
        m.put("host", host);
        List<Object> bs = new ArrayList<>();
        for (Button b : buttons) {
            bs.add(b.toJson());
        }
        m.put("buttons", bs);
        return Json.write(m);
    }

    public static CatalogFeed fromJson(String text) {
        Map<String, Object> m = Json.object(text);
        String bad = CenterFeed.check(m, CenterFeed.KIND_CATALOG);
        if (bad != null) {
            throw new IllegalArgumentException(bad);
        }
        List<Button> bs = new ArrayList<>();
        for (Map<String, Object> b : Json.objs(m, "buttons")) {
            try {
                bs.add(Button.fromJson(b));
            } catch (IllegalArgumentException skipped) {
                // Ostinato: one malformed form must not hide the others.
            }
        }
        return new CatalogFeed(Json.str(m, "host", ""), Json.num(m, "writtenAtMs", 0), Json.num(m, "seq", 0), bs);
    }

    private static ArgKind kind(String raw) {
        try {
            return ArgKind.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return ArgKind.TOKEN;
        }
    }

    private static FormPlacement placement(String raw) {
        try {
            return FormPlacement.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return FormPlacement.TILE;
        }
    }
}
