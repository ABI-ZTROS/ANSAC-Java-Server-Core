package dev.ztros.ansac.scheduler;

import com.tcoded.folialib.FoliaLib;
import dev.ztros.ansac.ANSACPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.TimeUnit;

/**
 * Cross-platform scheduler adapter for Folia and Paper/Spigot.
 * Uses FoliaLib internally to handle platform differences.
 * Adapted for FoliaLib 0.5.1 API (getScheduler() with direct Runnable methods).
 */
public class SchedulerAdapter {

    private final ANSACPlugin plugin;
    private final FoliaLib foliaLib;

    public SchedulerAdapter(ANSACPlugin plugin) {
        this.plugin = plugin;
        this.foliaLib = plugin.getFoliaLib();
    }

    /**
     * Check if running on Folia
     */
    public boolean isFolia() {
        return foliaLib.isFolia();
    }

    /**
     * Run task on the next tick (GlobalRegionScheduler on Folia, main thread on Paper)
     */
    public void runNextTick(Runnable task) {
        foliaLib.getScheduler().runNextTick(task);
    }

    /**
     * Run task asynchronously
     */
    public void runAsync(Runnable task) {
        foliaLib.getScheduler().runAsync(task);
    }

    /**
     * Run task at a specific location (uses RegionScheduler on Folia)
     */
    public void runAtLocation(Location location, Runnable task) {
        foliaLib.getScheduler().runAtLocation(location, task);
    }

    /**
     * Run task tied to an entity (uses EntityScheduler on Folia)
     */
    public void runAtEntity(Entity entity, Runnable task) {
        foliaLib.getScheduler().runAtEntity(entity, task);
    }

    /**
     * Run delayed task (ticks)
     */
    public void runLater(Runnable task, long delayTicks) {
        foliaLib.getScheduler().runLater(task, delayTicks);
    }

    /**
     * Run delayed task at location (ticks)
     */
    public void runLaterAtLocation(Location location, Runnable task, long delayTicks) {
        foliaLib.getScheduler().runAtLocationLater(location, task, delayTicks);
    }

    /**
     * Run repeating timer task (ticks)
     */
    public void runTimer(Runnable task, long delayTicks, long periodTicks) {
        foliaLib.getScheduler().runTimer(task, delayTicks, periodTicks);
    }

    /**
     * Run repeating timer task at location (ticks)
     */
    public void runTimerAtLocation(Location location, Runnable task, long delayTicks, long periodTicks) {
        foliaLib.getScheduler().runAtLocationTimer(location, task, delayTicks, periodTicks);
    }

    /**
     * Run timer with TimeUnit (for async operations)
     */
    public void runTimerAsync(Runnable task, long delay, long period, TimeUnit unit) {
        foliaLib.getScheduler().runTimerAsync(task, delay, period, unit);
    }

    /**
     * Cancel all tasks for this plugin
     */
    public void cancelAllTasks() {
        foliaLib.getScheduler().cancelAllTasks();
    }
}
