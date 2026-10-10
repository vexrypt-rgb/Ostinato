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


package baritone.gui.tasks.game;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.command.ICommand;
import baritone.api.command.exception.CommandException;
import baritone.api.command.manager.ICommandManager;
import baritone.api.pathing.goals.Goal;
import baritone.api.process.IBaritoneProcess;
import baritone.api.utils.BlockOptionalMetaLookup;
import baritone.api.utils.SettingsUtil;
import baritone.command.argument.ArgConsumer;
import baritone.command.argument.CommandArguments;
import baritone.gui.tasks.StepType;
import baritone.gui.tasks.TaskAdapter;
import baritone.gui.tasks.TaskStep;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;

/**
 * Drives the primary Baritone for the task runner: steps run through Baritone's own command implementations
 * (same parsing as chat, but errors come back to the runner instead of only being printed), completion is read
 * from the matching process, and "set" steps apply a setting for this session without saving settings.txt.
 */
public final class BaritoneTaskAdapter implements TaskAdapter {

    private Goal startedGoal;

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    /** Run a Baritone command, returning its error message instead of logging it. */
    static String execute(String line) {
        ICommandManager cm = baritone().getCommandManager();
        String label = line.split("\\s", 2)[0];
        ICommand cmd = cm.getCommand(label);
        if (cmd == null) {
            return "unknown command " + label;
        }
        try {
            cmd.execute(label, new ArgConsumer(cm, CommandArguments.from(line.substring(label.length()))));
            return null;
        } catch (CommandException e) {
            return e.getMessage();
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        }
    }

    @Override
    public String start(TaskStep step) {
        startedGoal = null;
        if (step.type() == StepType.SET) {
            Settings settings = BaritoneAPI.getSettings();
            String name = step.get("setting").toLowerCase(Locale.ROOT);
            Settings.Setting<?> s = settings.byLowerName.get(name);
            if (s == null) {
                return "no setting named " + step.get("setting");
            }
            if (s.isJavaOnly()) {
                return step.get("setting") + " cannot be set";
            }
            try {
                SettingsUtil.parseAndApply(settings, name, step.get("value"));
                return null;
            } catch (Throwable t) {
                return "bad value for " + s.getName() + ": " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
            }
        }
        String err = execute(step.command());
        if (err == null && step.type() == StepType.GOTO) {
            startedGoal = baritone().getCustomGoalProcess().getGoal();
        }
        return err;
    }

    private static IBaritoneProcess process(StepType t) {
        IBaritone b = baritone();
        switch (t) {
            case GOTO: return b.getCustomGoalProcess();
            case MINE: return b.getMineProcess();
            case FOLLOW: return b.getFollowProcess();
            case FARM: return b.getFarmProcess();
            case EXPLORE: return b.getExploreProcess();
            case GET_TO_BLOCK: return b.getGetToBlockProcess();
            case BUILD: return b.getBuilderProcess();
            default: return null;
        }
    }

    @Override
    public boolean busy(TaskStep step) {
        IBaritoneProcess p = process(step.type());
        if (p != null && p.isActive()) {
            return true;
        }
        // goto may hand off to the elytra process
        return step.type() == StepType.GOTO && baritone().getElytraProcess().isActive();
    }

    @Override
    public String verify(TaskStep step) {
        switch (step.type()) {
            case GOTO:
                if (startedGoal == null) {
                    return "goto did not start";
                }
                return baritone().getPlayerContext().player() != null && startedGoal.isInGoal(baritone().getPlayerContext().playerFeet())
                        ? null : "stopped before reaching the goal";
            case MINE: {
                int want = step.mineCount();
                if (want <= 0) {
                    return null;
                }
                int have = count(step);
                return have >= want ? null : "mining stopped at " + have + "/" + want;
            }
            default:
                return null;
        }
    }

    private static int count(TaskStep step) {
        if (baritone().getPlayerContext().player() == null) {
            return 0;
        }
        try {
            BlockOptionalMetaLookup filter = new BlockOptionalMetaLookup(step.blocks());
            int n = 0;
            for (ItemStack s : baritone().getPlayerContext().player().getInventory().getNonEquipmentItems()) {
                if (filter.has(s)) {
                    n += s.getCount();
                }
            }
            return n;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public String progress(TaskStep step) {
        if (step.type() == StepType.MINE && step.mineCount() > 0) {
            return count(step) + "/" + step.mineCount();
        }
        return null;
    }

    @Override
    public void pause() {
        execute("pause");
    }

    @Override
    public void resume() {
        execute("resume");
    }

    @Override
    public void cancel() {
        execute("cancel");
    }
}
