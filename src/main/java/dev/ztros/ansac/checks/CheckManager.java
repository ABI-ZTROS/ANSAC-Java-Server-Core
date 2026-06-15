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

/**
 * Manages all anti-cheat checks.
 * Handles registration, scheduling, and execution of checks.
 */
public class CheckManager {

    private final ANSACPlugin plugin;
    private final List<Check> checks = new ArrayList<>();

    public CheckManager(ANSACPlugin plugin) {
        this.plugin = plugin;
        registerChecks();
        startCheckTask();
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
     * Start the periodic check task
     */
    private void startCheckTask() {
        plugin.getSchedulerAdapter().runTimer(() -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
                if (data == null || data.hasBypass()) continue;

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
        }, 1L, 1L); // Run every tick
    }

    /**
     * Process a specific player through all checks (for event-driven checks)
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
