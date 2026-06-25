package dev.ztros.ansac.checks.player;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FastUse check - detects accelerated item usage (eating food, drinking potions, drawing bow, etc.).
 *
 * Cheat principle (Meteor FastUse):
 *   Meteor FastUse: Accelerates item usage by bypassing the normal use duration.
 *   Normally, food/potion usage takes ~1.6 seconds (32 ticks). FastUse allows near-instant usage.
 *   Cheat signature: Multiple item use actions in extremely short intervals (< 500ms).
 *
 * Detection logic:
 *   - processItemUse() is called from PacketListener on USE_ITEM packet.
 *   - Records item use timestamps in a CopyOnWriteArrayList.
 *   - Checks if two consecutive uses are within 500ms of each other.
 *   - Uses a buffer system: fastUseBuffer >= 5 triggers a flag.
 *   - process() periodically cleans up expired data (older than 5 seconds).
 *
 * Normal player reference:
 *   - Food/potion use duration: ~1.6 seconds (32 ticks)
 *   - Bow draw duration: varies, but minimum ~0.5 seconds for any meaningful shot
 *   - Shield raise: near-instant, but only once per interaction
 */
public class FastUseCheck extends Check {

    // Detection thresholds
    private static final long FAST_USE_INTERVAL_MS = 500L;       // Two uses within 500ms is suspicious
    private static final int BUFFER_FLAG_THRESHOLD = 5;            // 5 violations before flagging
    private static final long DATA_EXPIRE_MS = 5000L;              // Expire data older than 5 seconds

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.10;

    /**
     * Internal tracker for per-player fast-use state.
     * Stored in a ConcurrentHashMap for thread safety (Folia compatibility).
     */
    static class FastUseTracker {
        // Item use timestamps
        final CopyOnWriteArrayList<Long> useTimestamps = new CopyOnWriteArrayList<>();
        // Violation buffer
        int fastUseBuffer;

        FastUseTracker() {
            this.fastUseBuffer = 0;
        }

        void reset() {
            this.useTimestamps.clear();
            this.fastUseBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, FastUseTracker> trackers = new ConcurrentHashMap<>();

    public FastUseCheck(ANSACPlugin plugin) {
        super(plugin, "FastUse", "Player");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Periodic cleanup of expired data
        FastUseTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) return;

        long cutoff = System.currentTimeMillis() - DATA_EXPIRE_MS;

        // Remove expired timestamps
        tracker.useTimestamps.removeIf(t -> t < cutoff);

        // If no data left, clean up entirely
        if (tracker.useTimestamps.isEmpty()) {
            trackers.remove(player.getUniqueId());
        }
    }

    /**
     * Process an item use action (called from PacketListener on USE_ITEM packet).
     * This is the main detection entry point for fast-use detection.
     *
     * @param player The player who used an item
     * @param data   The player's anti-cheat data
     */
    public void processItemUse(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // Skip creative mode (creative players can use items instantly legitimately)
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) {
            return;
        }

        // Skip sleeping or dead players
        if (player.isSleeping() || player.isDead()) {
            return;
        }

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            FastUseTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.fastUseBuffer = 0;
            }
            return;
        }

        long now = System.currentTimeMillis();
        FastUseTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new FastUseTracker());

        // Clean up old timestamps
        long cutoff = now - DATA_EXPIRE_MS;
        tracker.useTimestamps.removeIf(t -> t < cutoff);

        // Record item use timestamp
        tracker.useTimestamps.add(now);

        // Check: if there was a recent use within FAST_USE_INTERVAL_MS
        // Look for the most recent use before this one
        if (tracker.useTimestamps.size() >= 2) {
            // Get the second-to-last timestamp (the previous use)
            int size = tracker.useTimestamps.size();
            long previousUse = tracker.useTimestamps.get(size - 2);
            long interval = now - previousUse;

            if (interval < FAST_USE_INTERVAL_MS) {
                // Suspicious: two item uses within 500ms
                tracker.fastUseBuffer++;

                // Ping-compensated buffer threshold
                int compensatedThreshold = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_FLAG_THRESHOLD, COMPENSATION_FACTOR);

                if (tracker.fastUseBuffer >= compensatedThreshold) {
                    double severity = (double) BUFFER_FLAG_THRESHOLD / tracker.fastUseBuffer;
                    flag(player, data, severity,
                        String.format("加速使用物品: 两次使用间隔 %dms (连续 %d 次, 延迟 %s)",
                            interval, tracker.fastUseBuffer,
                            data.getPingCompensator().getPingStatus()));
                    // Reset buffer after flagging to avoid spam
                    tracker.fastUseBuffer = 0;
                }
            } else {
                // Legitimate interval, gradually decay buffer
                if (tracker.fastUseBuffer > 0) {
                    tracker.fastUseBuffer = Math.max(0, tracker.fastUseBuffer - 1);
                }
            }
        }
    }

    /**
     * Clean up tracker when player disconnects.
     */
    public void onPlayerQuit(UUID uuid) {
        trackers.remove(uuid);
    }
}
