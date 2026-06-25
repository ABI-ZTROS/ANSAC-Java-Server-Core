package dev.ztros.ansac.checks.building;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PacketMine check - detects mining blocks from a distance via packets (Meteor PacketMine).
 *
 * Cheat principle (Meteor PacketMine):
 *   Meteor PacketMine: Sends mining start/stop packets to the server for a
 *   specific block, allowing the player to continue "mining" that block even
 *   when looking away or moving far from it. The block breaks automatically
 *   when the mining progress completes, regardless of player position or
 *   line of sight. This is used in crystal PvP to pre-mine obsidian
 *   while fighting.
 *   Signature: Player is mining a block but is more than 6 blocks away from
 *   the target, or not facing the target block.
 *
 * Physics reference (Minecraft 1.21.x):
 *   Normal mining range: Player must be within ~6 blocks of target
 *   Normal mining requirement: Player must face the target block
 *   Mining progress: Determined by tool type and block hardness
 *   Server validates: Player position and facing direction on each dig packet
 *   PacketMine exploits: Sends dig packets while moving away, server
 *   continues mining progress even though player is no longer at the block
 *
 * Detection logic:
 *   - processDigAction() is called from PacketListener on dig actions
 *   - Records the dig target location and player's current position
 *   - Checks if player is > 6 blocks from the dig target
 *   - If distance > 6, increment packetMineBuffer
 *   - If packetMineBuffer >= 5, flag the player
 *   - process() handles timeout cleanup (10 seconds no activity = reset)
 *   - Uses ConcurrentHashMap for thread-safe per-player tracking
 *
 * Exemptions:
 *   - Creative mode (instant break, no mining process)
 */
public class PacketMineCheck extends Check {

    // Maximum allowed distance from dig target (blocks)
    private static final double MAX_DIG_DISTANCE = 6.0;

    // Buffer threshold for flagging
    private static final int BUFFER_THRESHOLD = 5;

    // Timeout for mining activity (ms) - reset tracker if no activity
    private static final long MINING_TIMEOUT_MS = 10000L;

    // Ping compensation factor
    private static final double COMPENSATION_FACTOR = 0.15;

    /**
     * Internal tracker for per-player PacketMine violations.
     * Stored in a ConcurrentHashMap for thread safety.
     */
    static class PacketMineTracker {
        Location digTargetLocation;
        long lastDigTime;
        Location lastPlayerLocation;
        int packetMineBuffer;

        PacketMineTracker() {
            this.digTargetLocation = null;
            this.lastDigTime = 0;
            this.lastPlayerLocation = null;
            this.packetMineBuffer = 0;
        }

        void reset() {
            this.digTargetLocation = null;
            this.lastDigTime = 0;
            this.lastPlayerLocation = null;
            this.packetMineBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, PacketMineTracker> trackers = new ConcurrentHashMap<>();

    public PacketMineCheck(ANSACPlugin plugin) {
        super(plugin, "PacketMine", "Building");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            PacketMineTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.reset();
            }
            return;
        }

        PacketMineTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) return;

        long now = System.currentTimeMillis();

        // Timeout cleanup: if no dig activity for 10 seconds, reset
        if (tracker.lastDigTime > 0 && (now - tracker.lastDigTime) > MINING_TIMEOUT_MS) {
            tracker.reset();
        }
    }

    /**
     * Called from PacketListener on dig actions (PLAYER_DIGGING START).
     * Records the dig target location and checks distance from player.
     *
     * @param player      The player who is digging
     * @param data        The player's data
     * @param digLocation The location of the block being mined
     */
    public void processDigAction(Player player, PlayerData data, Location digLocation) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) return;

        PacketMineTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new PacketMineTracker());

        long now = System.currentTimeMillis();

        // Update tracker with current dig information
        tracker.digTargetLocation = digLocation.clone();
        tracker.lastDigTime = now;
        tracker.lastPlayerLocation = player.getLocation().clone();

        // Check distance from player to dig target
        Location playerLoc = player.getLocation();

        if (playerLoc.getWorld() != null && digLocation.getWorld() != null
                && playerLoc.getWorld().equals(digLocation.getWorld())) {
            double distance = playerLoc.distance(digLocation);

            if (distance > MAX_DIG_DISTANCE) {
                tracker.packetMineBuffer++;

                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_THRESHOLD, COMPENSATION_FACTOR);

                if (tracker.packetMineBuffer >= compensatedBuffer) {
                    double severity = 1.0 + (distance - MAX_DIG_DISTANCE) * 0.2;
                    flag(player, data, severity,
                        String.format("远程挖掘检测: 距离挖掘目标 %.1f 格 (最大允许 %.1f 格, 缓冲 %d, 延迟 %s)",
                            distance, MAX_DIG_DISTANCE,
                            tracker.packetMineBuffer,
                            data.getPingCompensator().getPingStatus()));
                    tracker.reset();
                }
            } else {
                // Player is within range, gradually decay buffer
                tracker.packetMineBuffer = Math.max(0, tracker.packetMineBuffer - 1);
            }
        }
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isSleeping() || player.isDead();
    }
}
