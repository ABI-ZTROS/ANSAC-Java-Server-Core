package dev.ztros.ansac.scheduler;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import dev.ztros.ansac.ANSACPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Cross-platform scheduler adapter for Folia and Paper/Spigot.
 * Uses FoliaLib internally to handle platform differences.
 * Adapted for FoliaLib 0.4.3 API.
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
        foliaLib.getImpl().runNextTick(wrappedTask -> task.run());
    }

    /**
     * Run task asynchronously
     */
    public void runAsync(Runnable task) {
        foliaLib.getImpl().runAsync(wrappedTask -> task.run());
    }

    /**
     * Run task at a specific location (uses RegionScheduler on Folia)
     */
    public void runAtLocation(Location location, Runnable task) {
        foliaLib.getImpl().runAtLocation(location, wrappedTask -> task.run());
    }

    /**
     * Run task tied to an entity (uses EntityScheduler on Folia)
     */
    public void runAtEntity(Entity entity, Runnable task) {
        foliaLib.getImpl().runAtEntity(entity, wrappedTask -> task.run());
    }

    /**
     * Run delayed task (ticks). Returns a CompletableFuture that completes when the task finishes.
     */
    public CompletableFuture<Void> runLater(Runnable task, long delayTicks) {
        return foliaLib.getImpl().runLater(wrappedTask -> task.run(), delayTicks);
    }

    /**
     * Run delayed task at location (ticks). Returns a CompletableFuture that completes when the task finishes.
     */
    public CompletableFuture<Void> runLaterAtLocation(Location location, Runnable task, long delayTicks) {
        return foliaLib.getImpl().runAtLocationLater(location, wrappedTask -> task.run(), delayTicks);
    }

    /**
     * Run repeating timer task (ticks).
     * Note: FoliaLib timer methods return void; the task runs until cancelled.
     */
    public void runTimer(Runnable task, long delayTicks, long periodTicks) {
        foliaLib.getImpl().runTimer(wrappedTask -> task.run(), delayTicks, periodTicks);
    }

    /**
     * Run repeating timer task at location (ticks).
     * Note: FoliaLib timer methods return void; the task runs until cancelled.
     */
    public void runTimerAtLocation(Location location, Runnable task, long delayTicks, long periodTicks) {
        foliaLib.getImpl().runAtLocationTimer(location, wrappedTask -> task.run(), delayTicks, periodTicks);
    }

    /**
     * Run timer with TimeUnit (for async operations).
     * Note: FoliaLib timer methods return void; the task runs until cancelled.
     */
    public void runTimerAsync(Runnable task, long delay, long period, TimeUnit unit) {
        foliaLib.getImpl().runTimerAsync(wrappedTask -> task.run(), delay, period, unit);
    }

    /**
     * Cancel all tasks for this plugin
     */
    public void cancelAllTasks() {
        foliaLib.getImpl().cancelAllTasks();
    }
}
