package baritone.api.command;

/** Ostinato: where a registry form is drawn. The command center does not invent buttons outside this. */
public enum FormPlacement {
    /** One control on each bot tile. */
    TILE,
    /** The window header (layout, stop-all). */
    HEADER,
    /** The window, not repeated on every tile (spawn). */
    WINDOW
}
