package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AutoTotem check - detects automated offhand totem swapping.
 *
 * Cheat principle (Wurst AutoTotem + Meteor AutoTotem/Offhand):
 *   Wurst AutoTotem: Automatically moves a totem of undying to the offhand slot when health is low.
 *   Meteor AutoTotem/Offhand: Similar functionality, automatically swaps items to offhand.
 *   Cheat signature: Extremely frequent offhand swap actions in very short time periods.
 *   Normal players rarely swap offhand items, and certainly not multiple times per second.
 *
 * Detection logic:
 *   - processOffhandSwap() is called from PlayerListener on PlayerSwapHandItemsEvent.
 *   - Records offhand swap timestamps in a CopyOnWriteArrayList.
 *   - Checks if >= 4 swaps occurred within 1 second (1000ms).
 *   - Uses a buffer system: autoTotemBuffer >= 3 triggers a flag.
 *   - process() periodically cleans up expired data (older than 5 seconds).
 *
 * Normal player reference:
 *   - Offhand swap: Usually 0-1 times per minute during normal gameplay
 *   - PvP offhand swap: At most 1-2 times per second for experienced players
 *   - 4+ swaps per second is nearly impossible for humans and strongly indicates automation
 *
 * Exemptions:
 *   - None (frequent offhand swapping is inherently an automation signature)
 */
public class AutoTotemCheck extends Check {

    // Detection thresholds
    private static final int SWAPS_PER_SECOND_THRESHOLD = 4;     // >= 4 swaps in 1 second
    private static final long SWAP_WINDOW_MS = 1000L;             // 1 second window
    private static final int BUFFER_FLAG_THRESHOLD = 3;             // 3 violations before flagging
    private static final long DATA_EXPIRE_MS = 5000L;              // Expire data older than 5 seconds

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.10;

    /**
     * Internal tracker for per-player auto-totem state.
     * Stored in a ConcurrentHashMap for thread safety (Folia compatibility).
     */
    static class AutoTotemTracker {
        // Offhand swap timestamps
        final CopyOnWriteArrayList<Long> swapTimestamps = new CopyOnWriteArrayList<>();
        // Violation buffer
        int autoTotemBuffer;

        AutoTotemTracker() {
            this.autoTotemBuffer = 0;
        }

        void reset() {
            this.swapTimestamps.clear();
            this.autoTotemBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, AutoTotemTracker> trackers = new ConcurrentHashMap<>();

    public AutoTotemCheck(ANSACPlugin plugin) {
        super(plugin, "AutoTotem", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Periodic cleanup of expired data
        AutoTotemTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) return;

        long cutoff = System.currentTimeMillis() - DATA_EXPIRE_MS;

        // Remove expired timestamps
        tracker.swapTimestamps.removeIf(t -> t < cutoff);

        // If no data left, clean up entirely
        if (tracker.swapTimestamps.isEmpty()) {
            trackers.remove(player.getUniqueId());
        }
    }

    /**
     * Process an offhand swap action (called from PlayerListener on PlayerSwapHandItemsEvent).
     * This is the main detection entry point for auto-totem detection.
     *
     * @param player The player who swapped offhand items
     * @param data   The player's anti-cheat data
     */
    public void processOffhandSwap(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // No exemptions - frequent offhand swapping is inherently an automation signature

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            AutoTotemTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.autoTotemBuffer = 0;
            }
            return;
        }

        long now = System.currentTimeMillis();
        AutoTotemTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new AutoTotemTracker());

        // Clean up old timestamps
        long cutoff = now - DATA_EXPIRE_MS;
        tracker.swapTimestamps.removeIf(t -> t < cutoff);

        // Record offhand swap timestamp
        tracker.swapTimestamps.add(now);

        // Count swaps within the 1-second window
        long windowCutoff = now - SWAP_WINDOW_MS;
        int swapsInWindow = 0;
        for (Long timestamp : tracker.swapTimestamps) {
            if (timestamp >= windowCutoff) {
                swapsInWindow++;
            }
        }

        // Ping-compensated threshold
        int compensatedThreshold = data.getPingCompensator().getCompensatedBuffer(
            SWAPS_PER_SECOND_THRESHOLD, COMPENSATION_FACTOR);

        if (swapsInWindow >= compensatedThreshold) {
            tracker.autoTotemBuffer++;

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_FLAG_THRESHOLD, COMPENSATION_FACTOR);

            if (tracker.autoTotemBuffer >= compensatedBuffer) {
                double severity = swapsInWindow / (double) SWAPS_PER_SECOND_THRESHOLD;
                flag(player, data, severity,
                    String.format("自动不死图腾: %d 次副手切换在 1 秒内 (连续 %d 次, 延迟 %s)",
                        swapsInWindow, tracker.autoTotemBuffer,
                        data.getPingCompensator().getPingStatus()));
                // Reset buffer after flagging to avoid spam
                tracker.autoTotemBuffer = 0;
            }
        } else {
            // Gradually decay buffer on legitimate swaps
            if (tracker.autoTotemBuffer > 0) {
                tracker.autoTotemBuffer = Math.max(0, tracker.autoTotemBuffer - 1);
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
