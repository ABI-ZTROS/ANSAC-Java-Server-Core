package dev.ztros.ansac.scheduler;

import dev.ztros.ansac.ANSACPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;

/**
 * Cross-platform scheduler adapter for Folia and Paper/Spigot.
 * Detects Folia at runtime and uses the appropriate scheduler.
 */
public class SchedulerAdapter {

    private final ANSACPlugin plugin;
    private final boolean isFolia;

    // Folia scheduler methods (cached via reflection)
    private Method globalRegionScheduler_execute;
    private Method asyncScheduler_execute;
    private Method regionScheduler_execute;
    private Method entityScheduler_execute;

    public SchedulerAdapter(ANSACPlugin plugin) {
        this.plugin = plugin;
        this.isFolia = detectFolia();

        if (isFolia) {
            initFoliaSchedulers();
        }
    }

    /**
     * Detect if running on Folia
     */
    private boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Initialize Folia scheduler methods via reflection
     */
    private void initFoliaSchedulers() {
        try {
            // Global Region Scheduler
            Object globalScheduler = Bukkit.getServer().getClass()
                    .getMethod("getGlobalRegionScheduler")
                    .invoke(Bukkit.getServer());
            globalRegionScheduler_execute = globalScheduler.getClass()
                    .getMethod("execute", org.bukkit.plugin.Plugin.class, Runnable.class);

            // Async Scheduler
            Object asyncScheduler = Bukkit.getServer().getClass()
                    .getMethod("getAsyncScheduler")
                    .invoke(Bukkit.getServer());
            asyncScheduler_execute = asyncScheduler.getClass()
                    .getMethod("runNow", org.bukkit.plugin.Plugin.class, Runnable.class);

            // Region Scheduler
            regionScheduler_execute = Location.class
                    .getMethod("getRegionScheduler")
                    .invoke(new Location(Bukkit.getWorlds().get(0), 0, 0, 0))
                    .getClass().getMethod("execute", org.bukkit.plugin.Plugin.class, Runnable.class);

            // Entity Scheduler
            entityScheduler_execute = Entity.class
                    .getMethod("getScheduler")
                    .invoke(Bukkit.getWorlds().get(0).getEntities().stream().findFirst().orElse(null))
                    .getClass().getMethod("execute", org.bukkit.plugin.Plugin.class, Runnable.class, Runnable.class);

        } catch (Exception e) {
            plugin.getLogger().warning("Failed to initialize Folia schedulers: " + e.getMessage());
        }
    }

    /**
     * Check if running on Folia
     */
    public boolean isFolia() {
        return isFolia;
    }

    /**
     * Run task on the next tick
     */
    public void runNextTick(Runnable task) {
        if (isFolia && globalRegionScheduler_execute != null) {
            try {
                globalRegionScheduler_execute.invoke(
                        Bukkit.getServer().getClass().getMethod("getGlobalRegionScheduler").invoke(Bukkit.getServer()),
                        plugin, task);
                return;
            } catch (Exception e) {
                // Fall through to Bukkit scheduler
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Run task asynchronously
     */
    public void runAsync(Runnable task) {
        if (isFolia && asyncScheduler_execute != null) {
            try {
                asyncScheduler_execute.invoke(
                        Bukkit.getServer().getClass().getMethod("getAsyncScheduler").invoke(Bukkit.getServer()),
                        plugin, task);
                return;
            } catch (Exception e) {
                // Fall through to Bukkit scheduler
            }
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    /**
     * Run task at a specific location
     */
    public void runAtLocation(Location location, Runnable task) {
        if (isFolia) {
            try {
                Object regionScheduler = location.getClass().getMethod("getRegionScheduler").invoke(location);
                regionScheduler.getClass().getMethod("execute", org.bukkit.plugin.Plugin.class, Runnable.class)
                        .invoke(regionScheduler, plugin, task);
                return;
            } catch (Exception e) {
                // Fall through to Bukkit scheduler
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Run task tied to an entity
     */
    public void runAtEntity(Entity entity, Runnable task) {
        if (isFolia) {
            try {
                Object entityScheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
                entityScheduler.getClass().getMethod("execute", org.bukkit.plugin.Plugin.class, Runnable.class, Runnable.class)
                        .invoke(entityScheduler, plugin, task, null);
                return;
            } catch (Exception e) {
                // Fall through to Bukkit scheduler
            }
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    /**
     * Run delayed task
     */
    public void runLater(Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    /**
     * Run delayed task at location
     */
    public void runLaterAtLocation(Location location, Runnable task, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    /**
     * Run repeating timer task
     */
    public BukkitTask runTimer(Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    /**
     * Run repeating timer task at location
     */
    public BukkitTask runTimerAtLocation(Location location, Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
    }

    /**
     * Cancel all tasks for this plugin
     */
    public void cancelAllTasks() {
        Bukkit.getScheduler().cancelTasks(plugin);
    }
}
