package dev.ztros.ansac.checks.building;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Nuker check - detects rapid destruction of surrounding blocks (Wurst SpeedNuker + Meteor Nuker).
 *
 * Cheat principle (Wurst SpeedNuker / Meteor Nuker):
 *   Wurst SpeedNuker: Instantly breaks all blocks within a configurable radius
 *   around the player, ignoring normal mining speed and tool requirements.
 *   Meteor Nuker: Similar functionality, can break blocks at extreme speed
 *   with optional filter for specific block types.
 *   Signature: Abnormally high block break rate (>8 breaks/second in survival),
 *   far exceeding normal mining speed of 2-3 breaks/second.
 *
 * Physics reference (Minecraft 1.21.x):
 *   Normal mining speed (survival): ~2-3 blocks/second (varies by tool and block)
 *   Instant-break blocks (grass, flowers, etc.): Can be broken very quickly
 *   Creative mode: Instant break (1 click = 1 break, no cooldown)
 *   Nuker break rate: 10-20+ blocks per second
 *   Server processes breaks at tick rate (50ms), so theoretical max is 20/sec
 *
 * Detection logic:
 *   - processBlockBreak() is called from PlayerListener's BlockBreakEvent
 *   - Records each break timestamp in a CopyOnWriteArrayList
 *   - Excludes instant-break blocks (grass, flowers, etc.) from count
 *   - process() runs every tick and checks the 1-second window
 *   - If > 8 non-instant breaks in 1 second, increment nukerBuffer
 *   - If nukerBuffer >= 5, flag the player
 *   - process() also cleans up expired data
 *   - Uses ConcurrentHashMap for thread-safe per-player tracking
 *
 * Exemptions:
 *   - Creative mode (instant break is normal)
 *   - Instant-break blocks (excluded from count)
 */
public class NukerCheck extends Check {

    // Time window to check for rapid breaks (ms)
    private static final long CHECK_WINDOW_MS = 1000L;

    // Maximum allowed non-instant breaks in the check window
    // Normal mining: ~2-3 blocks/second, we allow up to 8 for tolerance
    private static final int MAX_BREAKS_IN_WINDOW = 8;

    // Buffer threshold for flagging
    private static final int BUFFER_THRESHOLD = 5;

    // Ping compensation factor
    private static final double COMPENSATION_FACTOR = 0.15;

    /**
     * Internal tracker for per-player Nuker violations.
     * Stored in a ConcurrentHashMap for thread safety.
     */
    static class NukerTracker {
        final CopyOnWriteArrayList<Long> breakTimestamps = new CopyOnWriteArrayList<>();
        int nukerBuffer;

        NukerTracker() {
            this.nukerBuffer = 0;
        }

        void reset() {
            this.nukerBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, NukerTracker> trackers = new ConcurrentHashMap<>();

    public NukerCheck(ANSACPlugin plugin) {
        super(plugin, "Nuker", "Building");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            NukerTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.reset();
            }
            return;
        }

        NukerTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) return;

        long now = System.currentTimeMillis();
        long windowStart = now - CHECK_WINDOW_MS;

        // Clean up expired timestamps
        // Note: CopyOnWriteArrayList iterator does NOT support remove().
        tracker.breakTimestamps.removeIf(t -> t < windowStart);
    }

    /**
     * Called from PlayerListener's BlockBreakEvent.
     * Records the timestamp of each block break, excluding instant-break blocks.
     *
     * @param player The player who broke the block
     * @param data   The player's data
     */
    public void processBlockBreak(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) return;

        NukerTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new NukerTracker());

        long now = System.currentTimeMillis();
        long windowStart = now - CHECK_WINDOW_MS;

        // Clean up expired timestamps first
        // Note: CopyOnWriteArrayList iterator does NOT support remove().
        tracker.breakTimestamps.removeIf(t -> t < windowStart);

        // Add new break timestamp
        tracker.breakTimestamps.add(now);

        // Check break count in window
        int breaksInWindow = tracker.breakTimestamps.size();

        if (breaksInWindow > MAX_BREAKS_IN_WINDOW) {
            tracker.nukerBuffer++;

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_THRESHOLD, COMPENSATION_FACTOR);

            if (tracker.nukerBuffer >= compensatedBuffer) {
                double severity = 1.0 + (breaksInWindow - MAX_BREAKS_IN_WINDOW) * 0.2;
                flag(player, data, severity,
                    String.format("自动破坏检测: %d秒内破坏了 %d 个方块 (缓冲 %d, 延迟 %s)",
                        CHECK_WINDOW_MS / 1000.0, breaksInWindow,
                        tracker.nukerBuffer,
                        data.getPingCompensator().getPingStatus()));
                tracker.reset();
            }
        } else {
            // Gradually decay buffer when not nuking
            tracker.nukerBuffer = Math.max(0, tracker.nukerBuffer - 1);
        }
    }

    /**
     * Check if a block is an instant-break block (can be broken in one click).
     * These blocks should not count toward the nuker detection since they
     * can be broken very quickly even by legitimate players.
     */
    private boolean isInstantBreakBlock(Block block) {
        Material type = block.getType();
        String name = type.name();

        // Vegetation and plants (instant break)
        if (name.contains("FLOWER") || name.contains("TALL_GRASS")
                || name.contains("FERN") || name.contains("DEAD_BUSH")
                || name.contains("MUSHROOM") || name.contains("SEAGRASS")
                || name.contains("KELP")) {
            return true;
        }

        // Thin plants
        if (type == Material.SUGAR_CANE || type == Material.BAMBOO
                || type == Material.BAMBOO_SAPLING || name.contains("SAPLING")) {
            return true;
        }

        // Instant-break blocks
        if (type == Material.TORCH || type == Material.SOUL_TORCH
                || type == Material.REDSTONE_TORCH || type == Material.REDSTONE_WIRE
                || type == Material.SNOW || type == Material.FIRE
                || type == Material.SOUL_FIRE) {
            return true;
        }

        // Crops and farming (instant break)
        if (type == Material.WHEAT || type == Material.CARROTS
                || type == Material.POTATOES || type == Material.BEETROOTS
                || type == Material.NETHER_WART || type == Material.COCOA
                || type == Material.SWEET_BERRY_BUSH || type == Material.CAVE_VINES
                || type == Material.CAVE_VINES_PLANT) {
            return true;
        }

        // Signs and buttons (instant break)
        if (name.contains("SIGN") || name.contains("BUTTON")) {
            return true;
        }

        // Rails and carpets (instant break)
        if (name.contains("RAIL") || name.contains("CARPET")) {
            return true;
        }

        // Pressure plates (instant break)
        if (name.contains("PRESSURE_PLATE")) {
            return true;
        }

        // Coral fans (instant break)
        if (name.contains("CORAL") && !name.contains("BLOCK")) {
            return true;
        }

        // Lily pad
        if (type == Material.LILY_PAD) {
            return true;
        }

        // Cobweb (instant break with shears/sword)
        // Note: cobweb takes time without tools, but we'll be lenient

        return false;
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isSleeping() || player.isDead();
    }
}
