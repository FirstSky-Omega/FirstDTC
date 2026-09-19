package fr.first.firstdtc.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.function.Consumer;

/**
 * Thin wrapper around Paper/Folia region schedulers so the game code stays readable.
 *
 * Paper (1.20.6+) ships the Folia scheduler API even on non-Folia servers where it
 * simply forwards to the main thread, so we can call it unconditionally.
 */
public final class FoliaScheduler {

    private final Plugin plugin;

    public FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Run once on the global region (main thread on non-Folia). */
    public void runGlobal(Runnable runnable) {
        Bukkit.getGlobalRegionScheduler().execute(plugin, runnable);
    }

    /** Run on the global region every {@code periodTicks}. Returns the task handle. */
    public ScheduledTask runGlobalRepeating(Consumer<ScheduledTask> task, long delayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, task, Math.max(1L, delayTicks), Math.max(1L, periodTicks));
    }
}
