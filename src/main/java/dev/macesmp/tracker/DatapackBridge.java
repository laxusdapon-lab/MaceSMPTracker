package dev.macesmp.tracker;

import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.logging.Logger;

/**
 * Plugin -> datapack channel. The plugin writes fake-player scores on the datapack's scoreboard objective
 * and the datapack reads them every tick:
 *   #plugin  1 while this plugin is running (switches the datapack's built-in lifetime limit off)
 *   #active  number of active Maces
 *   #limit   the current global limit
 *   #locked  1 when active >= limit (the datapack then takes the Mace recipe away from players)
 */
final class DatapackBridge {
    static final String OBJECTIVE = "mace_data";

    private final Logger log;
    private boolean warnedMissing;

    DatapackBridge(Logger log) {
        this.log = log;
    }

    /** True if the datapack's objective exists and its load function has initialised #plugin. */
    boolean datapackDetected() {
        Objective o = objective();
        return o != null && o.getScore("#plugin").isScoreSet();
    }

    private Objective objective() {
        ScoreboardManager m = Bukkit.getScoreboardManager();
        if (m == null) return null;
        Scoreboard sb = m.getMainScoreboard();
        return sb.getObjective(OBJECTIVE);
    }

    void sync(int active, int limit) {
        try {
            Objective o = objective();
            if (o == null) {
                if (!warnedMissing) {
                    warnedMissing = true;
                    log.warning("Datapack objective '" + OBJECTIVE + "' not found. Is the Mace datapack installed and enabled? "
                            + "The plugin still enforces the limit by itself; creating the objective so the datapack can pick it up later.");
                }
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard objectives add " + OBJECTIVE + " dummy");
                o = objective();
                if (o == null) return;
            }
            o.getScore("#plugin").setScore(1);
            o.getScore("#active").setScore(active);
            o.getScore("#limit").setScore(limit);
            o.getScore("#locked").setScore(active >= limit ? 1 : 0);
        } catch (RuntimeException e) {
            log.warning("Could not update datapack scoreboard: " + e);
        }
    }

    /** Tells the datapack the plugin is gone so its own fallback limit takes over again. */
    void pluginOff() {
        try {
            Objective o = objective();
            if (o != null) o.getScore("#plugin").setScore(0);
        } catch (RuntimeException ignored) {
            // shutting down; nothing sensible to do
        }
    }
}
