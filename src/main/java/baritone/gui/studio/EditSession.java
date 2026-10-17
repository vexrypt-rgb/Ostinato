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

package baritone.gui.studio;

/**
 * Tool state on top of one volume: selection corners, line anchor, clipboard, and undo.
 * {@link #note} is a hint for the player. Successful single-block edits stay quiet.
 */
public final class EditSession {

    private static final EditOps.Tool[] HOT = {
            EditOps.Tool.BRUSH, EditOps.Tool.SELECT, EditOps.Tool.FILL, EditOps.Tool.REPLACE,
            EditOps.Tool.LINE, EditOps.Tool.SPHERE, EditOps.Tool.HOLLOW, EditOps.Tool.COPY, EditOps.Tool.PASTE
    };

    private final int[][] history = new int[20][];
    private int histCount;
    private int histHead;

    public StudioVolume volume;
    public EditOps.Tool tool = EditOps.Tool.BRUSH;
    public int brush;
    public int radius = 3;
    public boolean hasA;
    public boolean hasB;
    public int ax, ay, az, bx, by, bz;
    public boolean hasLine;
    public int lx, ly, lz;
    public EditOps.Clipboard clipboard;
    public String note;

    public void bind(StudioVolume volume) {
        this.volume = volume;
        hasA = false;
        hasB = false;
        hasLine = false;
        histCount = 0;
        histHead = 0;
        clipboard = null;
        note = null;
    }

    public EditOps.Box selection() {
        if (!hasA || !hasB || volume == null) {
            return null;
        }
        return EditOps.Box.of(ax, ay, az, bx, by, bz);
    }

    public boolean hotkey(int index) {
        if (index < 0 || index >= HOT.length) {
            return false;
        }
        tool = HOT[index];
        note = tool.wire;
        return true;
    }

    public void cycle() {
        EditOps.Tool[] all = EditOps.Tool.values();
        tool = all[(tool.ordinal() + 1) % all.length];
        note = tool.wire;
    }

    public void bumpRadius(int delta) {
        radius = Math.max(1, Math.min(8, radius + delta));
        note = "radius " + radius;
    }

    /** True when the volume changed. */
    public boolean click(StudioPick hit, int button) {
        note = null;
        if (volume == null || hit == null) {
            return false;
        }
        switch (tool) {
            case BRUSH -> {
                if (button == 0 && hit.canPlace) {
                    return paint(hit.placeX, hit.placeY, hit.placeZ, brush);
                }
                if (button == 1 && hit.solid) {
                    return paint(hit.x, hit.y, hit.z, 0);
                }
                return false;
            }
            case SELECT -> {
                int[] cell = aim(hit);
                if (cell == null) {
                    return false;
                }
                if (button == 1) {
                    bx = cell[0];
                    by = cell[1];
                    bz = cell[2];
                    hasB = true;
                } else {
                    ax = cell[0];
                    ay = cell[1];
                    az = cell[2];
                    hasA = true;
                }
                note = hasA && hasB ? "Selection set" : "Corner set";
                return false;
            }
            case FILL -> {
                return boxed((vol, box) -> EditOps.fill(vol, box, brush));
            }
            case REPLACE -> {
                if (!hit.solid) {
                    note = "Click the block to replace";
                    return false;
                }
                int from = volume.get(hit.x, hit.y, hit.z);
                return boxed((vol, box) -> EditOps.replace(vol, box, from, brush));
            }
            case WALL -> {
                return boxed((vol, box) -> EditOps.wall(vol, box, brush));
            }
            case LINE -> {
                if (button == 1) {
                    hasLine = false;
                    note = "Line cancelled";
                    return false;
                }
                int[] cell = aim(hit);
                if (cell == null || button != 0) {
                    return false;
                }
                if (!hasLine) {
                    lx = cell[0];
                    ly = cell[1];
                    lz = cell[2];
                    hasLine = true;
                    note = "Line start set";
                    return false;
                }
                hasLine = false;
                int x0 = lx, y0 = ly, z0 = lz;
                return apply(() -> EditOps.line(volume, x0, y0, z0, cell[0], cell[1], cell[2], brush));
            }
            case SPHERE, HOLLOW -> {
                int[] cell = aim(hit);
                if (cell == null || button != 0) {
                    return false;
                }
                boolean hollow = tool == EditOps.Tool.HOLLOW;
                return apply(() -> EditOps.sphere(volume, cell[0], cell[1], cell[2], radius, brush, hollow));
            }
            case COPY -> {
                EditOps.Box box = selection();
                if (box == null) {
                    note = "Select two corners first";
                    return false;
                }
                clipboard = EditOps.copy(volume, box);
                note = "Copied " + clipboard.sx + "x" + clipboard.sy + "x" + clipboard.sz;
                return false;
            }
            case PASTE -> {
                if (clipboard == null) {
                    note = "Copy a selection first";
                    return false;
                }
                int[] cell = aim(hit);
                if (cell == null || button != 0) {
                    return false;
                }
                return apply(() -> EditOps.paste(volume, cell[0], cell[1], cell[2], clipboard));
            }
            case ROTATE -> {
                if (selection() != null && button == 0) {
                    return boxed(EditOps::rotateSelection);
                }
                if (clipboard != null) {
                    clipboard = clipboard.rotateY();
                    note = "Clipboard rotated";
                } else {
                    note = "Select two corners first";
                }
                return false;
            }
            case FLIP -> {
                if (selection() != null && button == 0) {
                    return boxed(EditOps::flipSelection);
                }
                if (clipboard != null) {
                    clipboard = clipboard.flipX();
                    note = "Clipboard flipped";
                } else {
                    note = "Select two corners first";
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    public boolean undo() {
        if (histCount <= 0 || volume == null) {
            note = "Nothing to undo";
            return false;
        }
        histHead = (histHead - 1 + history.length) % history.length;
        histCount--;
        volume.restore(history[histHead]);
        history[histHead] = null;
        note = "Undone";
        return true;
    }

    public void clearVolume() {
        if (volume == null) {
            return;
        }
        int[] before = volume.snapshot();
        volume.clear();
        push(before);
        hasA = false;
        hasB = false;
        hasLine = false;
    }

    private boolean paint(int x, int y, int z, int cell) {
        if (volume.get(x, y, z) == cell) {
            return false;
        }
        int[] before = volume.snapshot();
        volume.set(x, y, z, cell);
        push(before);
        return true;
    }

    private boolean boxed(BoxOp op) {
        EditOps.Box box = selection();
        if (box == null) {
            note = "Select two corners first";
            return false;
        }
        return apply(() -> op.run(volume, box));
    }

    private boolean apply(IntOp op) {
        int[] before = volume.snapshot();
        int n = op.run();
        if (n < 0) {
            volume.restore(before);
            note = "That does not fit";
            return false;
        }
        if (n == 0) {
            volume.restore(before);
            return false;
        }
        push(before);
        return true;
    }

    private void push(int[] before) {
        history[histHead] = before;
        histHead = (histHead + 1) % history.length;
        if (histCount < history.length) {
            histCount++;
        }
    }

    private static int[] aim(StudioPick hit) {
        if (hit.solid) {
            return new int[]{hit.x, hit.y, hit.z};
        }
        if (hit.canPlace) {
            return new int[]{hit.placeX, hit.placeY, hit.placeZ};
        }
        return null;
    }

    @FunctionalInterface
    private interface IntOp {
        int run();
    }

    @FunctionalInterface
    private interface BoxOp {
        int run(StudioVolume volume, EditOps.Box box);
    }
}
