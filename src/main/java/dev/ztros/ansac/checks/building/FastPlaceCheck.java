package dev.ztros.ansac.checks.building;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * FastPlace check - detects removal of block placement delay (Wurst FastPlace).
 *
 * Cheat principle (Wurst FastPlace):
 *   Wurst FastPlace: Removes the client-side placement cooldown, allowing
 *   the player to place blocks instantly and continuously without the normal
 *   delay between placements. This is used for fast building, auto-bridge,
 *   and combat advantages (placing blocks mid-fight).
 *   Signature: Block placements occurring at superhuman speed (>10/sec),
 *   far exceeding the normal rate of 4-5 blocks per second.
 *
 * Physics reference (Minecraft 1.21.x):
 *   Normal block placement cooldown: ~200ms (5 blocks/second max)
 *   Creative mode: No cooldown (instant placement)
 *   Survival with haste effect: Slightly faster but still limited
 *   Cheat placement rate: 10-20+ blocks per second
 *   Server processes placements at tick rate (50ms), so theoretical
 *   max is 20 placements/second, but network latency limits humans to ~5/sec
 *
 * Detection logic:
 *   - processBlockPlace() is called from PacketListener on block placement
 *   - Records each placement timestamp in a CopyOnWriteArrayList
 *   - process() runs every tick and checks the 1-second window
 *   - If > 10 placements in 1 second, increment fastPlaceBuffer
 *   - If fastPlaceBuffer >= 5, flag the player
 *   - process() also cleans up expired data
 *   - Uses ConcurrentHashMap for thread-safe per-player tracking
 *
 * Exemptions:
 *   - Creative mode (instant place is normal)
 */
public class FastPlaceCheck extends Check {

    // Time window to check for rapid placements (ms)
    private static final long CHECK_WINDOW_MS = 1000L;

    // Maximum allowed placements in the check window
    // Normal: ~4-5 blocks/second, we allow up to 10 for latency tolerance
    private static final int MAX_PLACES_IN_WINDOW = 10;

    // Buffer threshold for flagging
    private static final int BUFFER_THRESHOLD = 5;

    // Ping compensation factor
    private static final double COMPENSATION_FACTOR = 0.15;

    /**
     * Internal tracker for per-player FastPlace violations.
     * Stored in a ConcurrentHashMap for thread safety.
     */
    static class FastPlaceTracker {
        final CopyOnWriteArrayList<Long> placeTimestamps = new CopyOnWriteArrayList<>();
        int fastPlaceBuffer;

        FastPlaceTracker() {
            this.fastPlaceBuffer = 0;
        }

        void reset() {
            this.fastPlaceBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, FastPlaceTracker> trackers = new ConcurrentHashMap<>();

    public FastPlaceCheck(ANSACPlugin plugin) {
        super(plugin, "FastPlace", "Building");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            FastPlaceTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.reset();
            }
            return;
        }

        FastPlaceTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) return;

        long now = System.currentTimeMillis();
        long windowStart = now - CHECK_WINDOW_MS;

        // Clean up expired timestamps
        // Note: CopyOnWriteArrayList iterator does NOT support remove().
        // Use removeIf which operates on the list directly.
        tracker.placeTimestamps.removeIf(t -> t < windowStart);
    }

    /**
     * Called from PacketListener on block placement.
     * Records the timestamp of each block placement.
     *
     * @param player The player who placed the block
     * @param data   The player's data
     */
    public void processBlockPlace(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) return;

        FastPlaceTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new FastPlaceTracker());

        long now = System.currentTimeMillis();
        long windowStart = now - CHECK_WINDOW_MS;

        // Clean up expired timestamps first
        // Note: CopyOnWriteArrayList iterator does NOT support remove().
        tracker.placeTimestamps.removeIf(t -> t < windowStart);

        // Add new placement timestamp
        tracker.placeTimestamps.add(now);

        // Check placement count in window
        int placesInWindow = tracker.placeTimestamps.size();

        if (placesInWindow > MAX_PLACES_IN_WINDOW) {
            tracker.fastPlaceBuffer++;

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_THRESHOLD, COMPENSATION_FACTOR);

            if (tracker.fastPlaceBuffer >= compensatedBuffer) {
                double severity = 1.0 + (placesInWindow - MAX_PLACES_IN_WINDOW) * 0.2;
                flag(player, data, severity,
                    String.format("快速放置检测: %d秒内放置了 %d 个方块 (缓冲 %d, 延迟 %s)",
                        CHECK_WINDOW_MS / 1000.0, placesInWindow,
                        tracker.fastPlaceBuffer,
                        data.getPingCompensator().getPingStatus()));
                tracker.reset();
            }
        } else {
            // Gradually decay buffer when not fast-placing
            tracker.fastPlaceBuffer = Math.max(0, tracker.fastPlaceBuffer - 1);
        }
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isSleeping() || player.isDead();
    }
}
