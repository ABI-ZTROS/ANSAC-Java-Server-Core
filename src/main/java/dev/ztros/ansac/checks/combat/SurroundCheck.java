package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Surround check - detects automated block placement around the player (Meteor Surround).
 *
 * Cheat principle (Meteor Surround):
 *   Meteor Surround: Automatically places blocks around the player to create
 *   a protective shell, typically used in crystal PvP to prevent opponents
 *   from placing crystals near them. The cheat instantly places blocks in
 *   all directions around the player at superhuman speed.
 *   Signature: Multiple blocks placed very quickly in a small radius around
 *   the player's position (within 2 blocks), forming a surround pattern.
 *
 * Physics reference (Minecraft 1.21.x):
 *   Normal block placement speed: ~4-5 blocks/second (human)
 *   Surround requires 4-5 blocks placed in specific positions
 *   Human surround time: ~2-3 seconds (placing blocks one by one)
 *   Cheat surround time: <500ms (instant placement)
 *   Surround radius: 1-2 blocks from player center
 *
 * Detection logic:
 *   - processBlockPlace() is called from PacketListener on block placement
 *   - Records placement location and timestamp
 *   - Checks if >= 4 blocks were placed within 500ms in a 2-block radius
 *   - If pattern detected, increment surroundBuffer
 *   - If surroundBuffer >= 3, flag the player
 *   - process() cleans up expired data every tick
 *   - Uses ConcurrentHashMap for thread-safe per-player tracking
 *
 * Exemptions:
 *   - Creative mode (instant place is normal)
 */
public class SurroundCheck extends Check {

    // Time window to check for rapid placements (ms)
    private static final long CHECK_WINDOW_MS = 500L;

    // Maximum radius from player to consider "around player" (blocks)
    private static final double MAX_PLACE_RADIUS = 2.0;

    // Minimum number of blocks placed in window to trigger
    private static final int MIN_PLACES_IN_WINDOW = 4;

    // Buffer threshold for flagging
    private static final int BUFFER_THRESHOLD = 3;

    // Ping compensation factor
    private static final double COMPENSATION_FACTOR = 0.10;

    /**
     * Internal tracker for per-player Surround violations.
     * Stored in a ConcurrentHashMap for thread safety.
     */
    static class SurroundTracker {
        final CopyOnWriteArrayList<Long> placeTimestamps = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<Location> placeLocations = new CopyOnWriteArrayList<>();
        int surroundBuffer;

        SurroundTracker() {
            this.surroundBuffer = 0;
        }

        void reset() {
            this.surroundBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, SurroundTracker> trackers = new ConcurrentHashMap<>();

    public SurroundCheck(ANSACPlugin plugin) {
        super(plugin, "Surround", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            SurroundTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.reset();
            }
            return;
        }

        SurroundTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) return;

        long now = System.currentTimeMillis();
        long windowStart = now - CHECK_WINDOW_MS;

        // Clean up expired entries.
        // Note: CopyOnWriteArrayList iterator does NOT support remove().
        // Remove from back to front so indices remain valid.
        for (int i = tracker.placeTimestamps.size() - 1; i >= 0; i--) {
            if (tracker.placeTimestamps.get(i) < windowStart) {
                tracker.placeTimestamps.remove(i);
                tracker.placeLocations.remove(i);
            }
        }
    }

    /**
     * Called from PacketListener on block placement.
     * Records the placement location and timestamp.
     *
     * @param player        The player who placed the block
     * @param data          The player's data
     * @param placeLocation The location where the block was placed
     */
    public void processBlockPlace(Player player, PlayerData data, Location placeLocation) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) return;

        SurroundTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new SurroundTracker());

        long now = System.currentTimeMillis();
        long windowStart = now - CHECK_WINDOW_MS;

        // Clean up expired entries first.
        // Note: CopyOnWriteArrayList iterator does NOT support remove().
        // Timestamps are in chronological order, so expired ones are at the front.
        while (!tracker.placeTimestamps.isEmpty()
                && tracker.placeTimestamps.get(0) < windowStart) {
            tracker.placeTimestamps.remove(0);
            tracker.placeLocations.remove(0);
        }

        // Add new placement
        tracker.placeTimestamps.add(now);
        tracker.placeLocations.add(placeLocation.clone());

        // Count how many placements are within the player's radius in the window
        Location playerLoc = player.getLocation();
        int placesNearPlayer = 0;

        for (Location loc : tracker.placeLocations) {
            if (loc.getWorld() != null && loc.getWorld().equals(playerLoc.getWorld())) {
                double dist = loc.distance(playerLoc);
                if (dist <= MAX_PLACE_RADIUS) {
                    placesNearPlayer++;
                }
            }
        }

        if (placesNearPlayer >= MIN_PLACES_IN_WINDOW) {
            tracker.surroundBuffer++;

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_THRESHOLD, COMPENSATION_FACTOR);

            if (tracker.surroundBuffer >= compensatedBuffer) {
                double severity = 1.0 + (placesNearPlayer - MIN_PLACES_IN_WINDOW) * 0.3;
                flag(player, data, severity,
                    String.format("自动包围检测: %dms内在周围 %.1f 格放置了 %d 个方块 (缓冲 %d, 延迟 %s)",
                        CHECK_WINDOW_MS, MAX_PLACE_RADIUS, placesNearPlayer,
                        tracker.surroundBuffer,
                        data.getPingCompensator().getPingStatus()));
                tracker.reset();
            }
        } else {
            // Gradually decay buffer when not surrounding
            tracker.surroundBuffer = Math.max(0, tracker.surroundBuffer - 1);
        }
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isSleeping() || player.isDead();
    }
}
