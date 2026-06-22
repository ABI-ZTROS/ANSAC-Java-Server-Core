package dev.ztros.ansac.player;

import dev.ztros.ansac.ANSACPlugin;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages PlayerData instances for all online players.
 * Thread-safe for Folia multithreaded environment.
 */
public class PlayerDataManager {

    private final ANSACPlugin plugin;
    private final Map<UUID, PlayerData> playerDataMap = new ConcurrentHashMap<>();

    public PlayerDataManager(ANSACPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Create and store PlayerData for a player
     */
    public PlayerData createPlayerData(Player player) {
        PlayerData data = new PlayerData(player, plugin);
        playerDataMap.put(player.getUniqueId(), data);
        return data;
    }

    /**
     * Get PlayerData for a player
     */
    public PlayerData getPlayerData(Player player) {
        return playerDataMap.get(player.getUniqueId());
    }

    /**
     * Get PlayerData by UUID
     */
    public PlayerData getPlayerData(UUID uuid) {
        return playerDataMap.get(uuid);
    }

    /**
     * Remove PlayerData when player quits
     */
    public void removePlayerData(Player player) {
        playerDataMap.remove(player.getUniqueId());
    }

    /**
     * Remove PlayerData by UUID
     */
    public void removePlayerData(UUID uuid) {
        playerDataMap.remove(uuid);
    }

    /**
     * Check if player has data
     */
    public boolean hasPlayerData(Player player) {
        return playerDataMap.containsKey(player.getUniqueId());
    }

    /**
     * Get total tracked player count
     */
    public int getPlayerCount() {
        return playerDataMap.size();
    }

    /**
     * Update ping for all players
     */
    public void updateAllPings() {
        for (PlayerData data : playerDataMap.values()) {
            data.updatePing();
        }
    }

    /**
     * Get all player data values for iteration (thread-safe snapshot)
     */
    public Collection<PlayerData> playerDataMapValues() {
        return playerDataMap.values();
    }

    /**
     * Clear all player data
     */
    public void clearAll() {
        playerDataMap.clear();
    }

    /**
     * Shutdown manager
     */
    public void shutdown() {
        clearAll();
    }
}
