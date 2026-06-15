package dev.ztros.ansac.scheduler;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import dev.ztros.ansac.ANSACPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.function.Consumer;

/**
 * Cross-platform scheduler adapter for Folia and Paper/Spigot.
 * Uses FoliaLib internally to handle platform differences.
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
    public WrappedTask runNextTick(Runnable task) {
        return foliaLib.getScheduler().runNextTick(wrappedTask -> task.run());
    }

    /**
     * Run task asynchronously
     */
    public WrappedTask runAsync(Runnable task) {
        return foliaLib.getScheduler().runAsync(wrappedTask -> task.run());
    }

    /**
     * Run task at a specific location (uses RegionScheduler on Folia)
     */
    public WrappedTask runAtLocation(Location location, Runnable task) {
        return foliaLib.getScheduler().runAtLocation(location, wrappedTask -> task.run());
    }

    /**
     * Run task tied to an entity (uses EntityScheduler on Folia)
     */
    public WrappedTask runAtEntity(Entity entity, Runnable task) {
        return foliaLib.getScheduler().runAtEntity(entity, wrappedTask -> task.run());
    }

    /**
     * Run delayed task
     */
    public WrappedTask runLater(Runnable task, long delayTicks) {
        return foliaLib.getScheduler().runLater(wrappedTask -> task.run(), delayTicks);
    }

    /**
     * Run delayed task at location
     */
    public WrappedTask runLaterAtLocation(Location location, Runnable task, long delayTicks) {
        return foliaLib.getScheduler().runAtLocationTimer(location, delayTicks, 1L, wrappedTask -> {
            task.run();
            wrappedTask.cancel();
        });
    }

    /**
     * Run repeating timer task
     */
    public WrappedTask runTimer(Runnable task, long delayTicks, long periodTicks) {
        return foliaLib.getScheduler().runTimer(wrappedTask -> task.run(), delayTicks, periodTicks);
    }

    /**
     * Run repeating timer task at location
     */
    public WrappedTask runTimerAtLocation(Location location, Runnable task, long delayTicks, long periodTicks) {
        return foliaLib.getScheduler().runAtLocationTimer(location, delayTicks, periodTicks, wrappedTask -> task.run());
    }

    /**
     * Cancel all tasks for this plugin
     */
    public void cancelAllTasks() {
        foliaLib.getScheduler().cancelAllTasks();
    }
}
