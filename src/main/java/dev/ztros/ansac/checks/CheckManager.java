package dev.ztros.ansac.checks;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.combat.KillAuraCheck;
import dev.ztros.ansac.checks.combat.ReachCheck;
import dev.ztros.ansac.checks.movement.FlyCheck;
import dev.ztros.ansac.checks.movement.SpeedCheck;
import dev.ztros.ansac.checks.packet.BadPacketsCheck;
import dev.ztros.ansac.checks.packet.TimerCheck;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Manages all anti-cheat checks.
 * Handles registration, scheduling, and execution of checks.
 * On Folia, uses runAtEntity for each player to ensure thread safety.
 */
public class CheckManager {

    private final ANSACPlugin plugin;
    private final List<Check> checks = new ArrayList<>();

    public CheckManager(ANSACPlugin plugin) {
        this.plugin = plugin;
        registerChecks();
        startCheckTask();
        startMaintenanceTask();
    }

    /**
     * Register all checks
     */
    private void registerChecks() {
        // Movement checks
        checks.add(new SpeedCheck(plugin));
        checks.add(new FlyCheck(plugin));

        // Combat checks
        checks.add(new ReachCheck(plugin));
        checks.add(new KillAuraCheck(plugin));

        // Packet checks
        checks.add(new TimerCheck(plugin));
        checks.add(new BadPacketsCheck(plugin));

        plugin.getLogger().info("Registered " + checks.size() + " checks.");
    }

    /**
     * Start the periodic check task.
     * On Folia, schedules each player's check execution on their entity region thread.
     */
    private void startCheckTask() {
        plugin.getSchedulerAdapter().runTimer(() -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
                if (data == null || data.hasBypass()) continue;

                // Use runAtEntity to ensure thread safety on Folia
                plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                    for (Check check : checks) {
                        if (check.isEnabled()) {
                            try {
                                check.process(player, data);
                            } catch (Exception e) {
                                plugin.getLogger().warning("Error in check " + check.getName() + ": " + e.getMessage());
                            }
                        }
                    }
                });
            }
        }, 1L, 1L); // Run every tick
    }

    /**
     * Start maintenance tasks: violation decay and ping updates.
     */
    private void startMaintenanceTask() {
        final int decayInterval = plugin.getAnsacConfig().getViolationDecayInterval();
        final double decayFactor = plugin.getAnsacConfig().getViolationDecayFactor();
        final int pingInterval = plugin.getAnsacConfig().getPingCheckInterval();

        // Violation decay task (runs every N seconds)
        plugin.getSchedulerAdapter().runTimerAsync(() -> {
            long decayMillis = decayInterval * 1000L;
            for (PlayerData data : plugin.getPlayerDataManager().playerDataMapValues()) {
                data.getViolationsView().values().forEach(v -> {
                    if (v.shouldDecay(decayMillis)) {
                        v.decay(decayFactor);
                    }
                });
            }
        }, decayInterval, decayInterval, TimeUnit.SECONDS);

        // Ping update task (runs every N seconds)
        plugin.getSchedulerAdapter().runTimerAsync(() -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
                if (data != null) {
                    plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                        data.updatePing();
                    });
                }
            }
        }, pingInterval, pingInterval, TimeUnit.SECONDS);
    }

    /**
     * Process a specific player through all checks (for event-driven checks).
     * Assumes this is called from the correct region thread (e.g., PlayerMoveEvent).
     */
    public void processPlayer(Player player) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null || data.hasBypass()) return;

        for (Check check : checks) {
            if (check.isEnabled()) {
                try {
                    check.process(player, data);
                } catch (Exception e) {
                    plugin.getLogger().warning("Error in check " + check.getName() + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Get a check by name
     */
    public Check getCheck(String name) {
        return checks.stream()
                .filter(c -> c.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get number of enabled checks
     */
    public int getEnabledChecksCount() {
        return (int) checks.stream().filter(Check::isEnabled).count();
    }

    /**
     * Get total number of checks
     */
    public int getTotalChecksCount() {
        return checks.size();
    }

    /**
     * Reload all checks
     */
    public void reload() {
        for (Check check : checks) {
            check.loadConfig();
        }
        plugin.getLogger().info("Reloaded " + checks.size() + " checks.");
    }

    /**
     * Shutdown check manager
     */
    public void shutdown() {
        checks.clear();
    }
}
