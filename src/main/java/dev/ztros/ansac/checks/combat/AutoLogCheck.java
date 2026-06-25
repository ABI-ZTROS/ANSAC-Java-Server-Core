package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AutoLog check - detects automatic disconnection when health is low.
 *
 * Cheat principle (Meteor AutoLog):
 *   Meteor AutoLog: Automatically disconnects from the server when the player's health
 *   drops below a configurable threshold (default: 6 health / 3 hearts). This prevents
 *   the player from dying and losing their items.
 *   Cheat signature: Player disconnects within a very short time after taking damage
 *   that leaves them at low health (< 6 health / 3 hearts).
 *
 * Detection logic:
 *   - processDamage() is called from PlayerListener on EntityDamageEvent.
 *   - Records the player's health after damage and the timestamp.
 *   - checkDisconnect() is called from PlayerListener on PlayerQuitEvent.
 *   - If the player's last recorded health was < 6 and they disconnected within 2000ms
 *     of taking damage, it's suspicious.
 *   - Uses a buffer system: autoLogBuffer >= 2 triggers a flag.
 *
 * Normal player reference:
 *   - Players might disconnect due to network issues at any time
 *   - However, repeatedly disconnecting right after taking lethal/near-lethal damage
 *     is statistically very unlikely for legitimate players
 *   - The 2000ms window accounts for network latency and reaction time
 *
 * Exemptions:
 *   - None (disconnecting right after taking heavy damage is inherently suspicious)
 */
public class AutoLogCheck extends Check {

    // Detection thresholds
    private static final double LOW_HEALTH_THRESHOLD = 6.0;         // 3 hearts
    private static final long DISCONNECT_WINDOW_MS = 2000L;        // 2 seconds after damage
    private static final int BUFFER_FLAG_THRESHOLD = 2;             // 2 violations before flagging
    private static final long DATA_EXPIRE_MS = 10000L;              // Expire data older than 10 seconds

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.10;

    /**
     * Internal tracker for per-player auto-log state.
     * Stored in a ConcurrentHashMap for thread safety (Folia compatibility).
     */
    static class AutoLogTracker {
        // Health after last damage
        double lastDamageHealth;
        // Timestamp of last damage
        long lastDamageTime;
        // Violation buffer
        int autoLogBuffer;

        AutoLogTracker() {
            this.lastDamageHealth = 20.0;
            this.lastDamageTime = 0;
            this.autoLogBuffer = 0;
        }

        void reset() {
            this.lastDamageHealth = 20.0;
            this.lastDamageTime = 0;
            this.autoLogBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, AutoLogTracker> trackers = new ConcurrentHashMap<>();

    public AutoLogCheck(ANSACPlugin plugin) {
        super(plugin, "AutoLog", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // No periodic processing needed for this check
        // Detection is entirely event-driven via processDamage() and checkDisconnect()
    }

    /**
     * Process a damage event (called from PlayerListener on EntityDamageEvent).
     * Records the player's health after damage for later comparison on disconnect.
     *
     * @param player The player who took damage
     * @param data   The player's anti-cheat data
     * @param health The player's health AFTER the damage was applied
     */
    public void processDamage(Player player, PlayerData data, double health) {
        if (!isEnabled() || data.hasBypass()) return;

        // Skip sleeping or dead players
        if (player.isSleeping() || player.isDead()) {
            return;
        }

        // No exemptions - we always record damage data for disconnect analysis

        long now = System.currentTimeMillis();
        AutoLogTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new AutoLogTracker());

        // Record health and time after damage
        tracker.lastDamageHealth = health;
        tracker.lastDamageTime = now;
    }

    /**
     * Check if a player's disconnect is suspicious (called from PlayerListener on PlayerQuitEvent).
     * This must be called BEFORE the player's data is cleaned up.
     *
     * @param player The player who disconnected
     */
    public void checkDisconnect(Player player) {
        if (!isEnabled()) return;

        UUID uuid = player.getUniqueId();
        AutoLogTracker tracker = trackers.get(uuid);
        if (tracker == null) return;

        long now = System.currentTimeMillis();

        // Check if the damage data is still relevant (not expired)
        if (now - tracker.lastDamageTime > DATA_EXPIRE_MS) {
            trackers.remove(uuid);
            return;
        }

        // Check if the player was at low health when they last took damage
        // and disconnected within the time window
        if (tracker.lastDamageHealth < LOW_HEALTH_THRESHOLD
                && (now - tracker.lastDamageTime) < DISCONNECT_WINDOW_MS) {
            // Suspicious: disconnected shortly after taking damage that left them at low health
            tracker.autoLogBuffer++;

            // Ping-compensated buffer threshold
            // Note: we use a simple compensation here since we may not have PlayerData at disconnect time
            int compensatedThreshold = BUFFER_FLAG_THRESHOLD;

            if (tracker.autoLogBuffer >= compensatedThreshold) {
                double severity = tracker.autoLogBuffer / (double) BUFFER_FLAG_THRESHOLD;
                // Use a simplified flag call since PlayerData might be cleaned up
                // We flag using the tracker data directly
                flagDirectly(player, severity,
                    String.format("自动断开: 受伤后血量 %.1f (< %.1f), %.0fms 后断开 (连续 %d 次)",
                        tracker.lastDamageHealth, LOW_HEALTH_THRESHOLD,
                        (double) (now - tracker.lastDamageTime),
                        tracker.autoLogBuffer));
                // Reset buffer after flagging
                tracker.autoLogBuffer = 0;
            }
        }

        // Clean up tracker on disconnect
        trackers.remove(uuid);
    }

    /**
     * Flag directly without PlayerData (used during disconnect when data may be unavailable).
     */
    private void flagDirectly(Player player, double severity, String details) {
        if (!enabled) return;

        // Log the violation
        plugin.getLogger().warning("[预警] " + player.getName() + " 触发了 " + name +
            " (断开前检测) - " + details);

        // Alert staff
        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            if (staff.hasPermission("ansac.alerts")) {
                plugin.getSchedulerAdapter().runAtEntity(staff, () -> {
                    staff.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(
                        "<gray>[<red>ANSAC</gray>] <yellow>" + player.getName() +
                        " <gray>触发了 <red>" + name +
                        " <gray>(VL: <white>" + (int) severity +
                        "<gray>) <dark_gray>| <gray>" + details +
                        " <dark_gray>| <gray>玩家已断开连接"
                    ));
                });
            }
        }
    }

    /**
     * Clean up tracker when player disconnects (called separately from checkDisconnect).
     */
    public void onPlayerQuit(UUID uuid) {
        trackers.remove(uuid);
    }
}
