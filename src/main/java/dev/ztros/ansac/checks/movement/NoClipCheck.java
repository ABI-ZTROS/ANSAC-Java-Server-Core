package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NoClip check - detects players moving through solid blocks.
 *
 * Cheat principle (Wurst NoClip + Meteor Freecam/NoClip):
 *   Wurst NoClip: Modifies collision detection so the player can pass through blocks
 *   Meteor Freecam/NoClip: In Freecam mode, the player entity can move through blocks
 *   Signature: Player's bounding box overlaps with solid blocks
 *
 * Physics reference (Minecraft 1.21.x, minecraft.wiki):
 *   Player bounding box: 0.6 x 1.8 x 0.6 blocks (width x height x depth)
 *   Bounding box center: (x, y + 0.9, z) relative to feet position
 *   Bounding box half-width: 0.3 blocks
 *   Bounding box extends from y to y + 1.8
 *   A normal player cannot enter any block that isPassable() == false
 *   Exception: Water, lava, cobweb, powder snow, scaffolding (some have special interaction)
 *
 * Detection logic:
 *   - Every tick, check if the player's bounding box overlaps with any solid block
 *   - Player bounding box: center at (x, y+0.9, z), half-width 0.3, height 1.8
 *   - Checks all block positions covered by the bounding box (~2x4x2 = 16 blocks)
 *   - If overlap detected, increment noClipBuffer
 *   - If noClipBuffer > 5 (250ms at 50ms/tick), flag the player
 *   - Uses ConcurrentHashMap for thread-safe per-player tracking
 *
 * Exemptions:
 *   - Player in water (can enter water-logged blocks)
 *   - Player in vehicle
 *   - Player using elytra
 *   - Recently teleported (3 seconds)
 *   - Recently knocked back (1 second)
 *   - Blocks that are passable or have special interaction
 */
public class NoClipCheck extends Check {

    // Player bounding box dimensions
    private static final double PLAYER_HALF_WIDTH = 0.3;
    private static final double PLAYER_HEIGHT = 1.8;

    // Number of consecutive ticks inside a block before flagging
    // 8 ticks = 400ms at 50ms/tick (raised from 5 to reduce false positives)
    private static final int NOCLIP_BUFFER_THRESHOLD = 8;

    // Teleport exemption window (ms)
    private static final long TELEPORT_EXEMPT_MS = 3000L;

    // Knockback exemption window (ms)
    private static final long KNOCKBACK_EXEMPT_MS = 1000L;

    // Minimum distance moved to consider it actual movement (not just standing)
    private static final double MIN_MOVE_DISTANCE = 0.01;

    // Minimum distance the player must have moved to be eligible for NoClip flagging.
    // If the player is stationary inside a block, it's almost certainly a false positive
    // from a non-full-height block (dirt path, farmland, etc.)
    private static final double MIN_MOVE_FOR_FLAG = 0.05;

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.15;

    /**
     * Internal tracker for per-player NoClip violations.
     * Stored in a ConcurrentHashMap for thread safety.
     */
    static class NoClipTracker {
        int noClipBuffer;
        long lastNoClipTime;
        Location lastCheckedLocation;

        NoClipTracker() {
            this.noClipBuffer = 0;
            this.lastNoClipTime = 0;
            this.lastCheckedLocation = null;
        }

        void reset() {
            this.noClipBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, NoClipTracker> trackers = new ConcurrentHashMap<>();

    public NoClipCheck(ANSACPlugin plugin) {
        super(plugin, "NoClip", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            NoClipTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.reset();
            }
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        // Check if player has moved significantly
        double moveDistSq = from.distanceSquared(to);
        boolean hasMovedForFlag = moveDistSq >= MIN_MOVE_FOR_FLAG * MIN_MOVE_FOR_FLAG;

        long now = System.currentTimeMillis();

        NoClipTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new NoClipTracker());

        // --- Exemption checks ---
        if (shouldExempt(player, data, now)) {
            tracker.reset();
            tracker.lastCheckedLocation = to.clone();
            return;
        }

        // --- Check bounding box overlap with solid blocks ---
        boolean insideBlock = isBoundingBoxOverlappingSolid(player, to);

        if (insideBlock) {
            tracker.noClipBuffer++;
            tracker.lastNoClipTime = now;

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                NOCLIP_BUFFER_THRESHOLD, COMPENSATION_FACTOR);

            if (tracker.noClipBuffer >= compensatedBuffer) {
                // Only flag if the player has moved significantly.
                // Stationary NoClip while standing on a non-full-height block is
                // a false positive; real NoClip always involves movement.
                if (!hasMovedForFlag) {
                    tracker.reset();
                    return;
                }
                // Count how many solid blocks the player is inside
                int overlapCount = countSolidBlockOverlaps(to);

                double severity = 1.0 + (overlapCount * 0.5);
                flag(player, data, severity,
                    String.format("穿墙检测: 碰撞箱与 %d 个实体方块重叠 (连续 %d tick, 延迟 %s)",
                        overlapCount, tracker.noClipBuffer,
                        data.getPingCompensator().getPingStatus()));
                tracker.reset();
            }
        } else {
            // Gradually decay buffer when not clipping
            tracker.noClipBuffer = Math.max(0, tracker.noClipBuffer - 1);
        }

        tracker.lastCheckedLocation = to.clone();
    }

    /**
     * Check if the player's bounding box overlaps with any solid (non-passable) block.
     * Player bounding box: 0.6 x 1.8 x 0.6, centered horizontally at (x, z).
     *
     * Exempts the block the player is standing on (feet block), since players
     * naturally overlap with non-full-height blocks like dirt path, farmland, etc.
     *
     * @param player The player to check
     * @param loc The player's current location (feet position)
     * @return true if the bounding box overlaps with at least one solid block
     */
    private boolean isBoundingBoxOverlappingSolid(Player player, Location loc) {
        double centerX = loc.getX();
        double minY = loc.getY();
        double centerZ = loc.getZ();

        // Bounding box boundaries
        double minX = centerX - PLAYER_HALF_WIDTH;
        double maxX = centerX + PLAYER_HALF_WIDTH;
        double maxY = minY + PLAYER_HEIGHT;
        double minZ = centerZ - PLAYER_HALF_WIDTH;
        double maxZ = centerZ + PLAYER_HALF_WIDTH;

        // Player's feet block coordinates
        int feetBlockY = loc.getBlockY();

        // Iterate over all block positions covered by the bounding box
        // X range: ~2 blocks, Z range: ~2 blocks, Y range: ~2 blocks (1.8 height)
        int startX = (int) Math.floor(minX);
        int endX = (int) Math.floor(maxX);
        int startY = (int) Math.floor(minY);
        int endY = (int) Math.floor(maxY);
        int startZ = (int) Math.floor(minZ);
        int endZ = (int) Math.floor(maxZ);

        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    Block block = loc.getWorld().getBlockAt(x, y, z);
                    if (isSolidForCollision(block)) {
                        // Skip the block the player is standing on.
                        // Non-full-height blocks (dirt path, farmland, soul sand)
                        // cause the player's feet to be inside the block vertically.
                        // If overlap is only in the bottom 0.2 blocks, it's normal standing.
                        if (y == feetBlockY && isStandingOnBlock(block, minY)) {
                            continue;
                        }
                        // Verify actual geometric overlap (not just block grid overlap)
                        if (blockOverlapsBoundingBox(block, minX, maxX, minY, maxY, minZ, maxZ)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    /**
     * Count the number of solid blocks the player's bounding box overlaps with.
     */
    private int countSolidBlockOverlaps(Location loc) {
        double centerX = loc.getX();
        double minY = loc.getY();
        double centerZ = loc.getZ();

        double minX = centerX - PLAYER_HALF_WIDTH;
        double maxX = centerX + PLAYER_HALF_WIDTH;
        double maxY = minY + PLAYER_HEIGHT;
        double minZ = centerZ - PLAYER_HALF_WIDTH;
        double maxZ = centerZ + PLAYER_HALF_WIDTH;

        int feetBlockY = loc.getBlockY();

        int count = 0;
        int startX = (int) Math.floor(minX);
        int endX = (int) Math.floor(maxX);
        int startY = (int) Math.floor(minY);
        int endY = (int) Math.floor(maxY);
        int startZ = (int) Math.floor(minZ);
        int endZ = (int) Math.floor(maxZ);

        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                for (int z = startZ; z <= endZ; z++) {
                    Block block = loc.getWorld().getBlockAt(x, y, z);
                    if (isSolidForCollision(block)) {
                        if (y == feetBlockY && isStandingOnBlock(block, minY)) {
                            continue;
                        }
                        if (blockOverlapsBoundingBox(block, minX, maxX, minY, maxY, minZ, maxZ)) {
                            count++;
                        }
                    }
                }
            }
        }

        return count;
    }

    /**
     * Check if the player is merely standing on top of (or inside the upper
     * portion of) the given block. This is normal behaviour for non-full-height
     * blocks like dirt path, farmland, soul sand, etc.
     *
     * @param block The block to check
     * @param playerFeetY The player's feet Y coordinate
     * @return true if the player is standing on this block
     */
    private boolean isStandingOnBlock(Block block, double playerFeetY) {
        double blockTopY = block.getY() + 1.0;
        double verticalOverlap = blockTopY - playerFeetY;
        // If the player's feet are within 0.2 blocks of the block's top,
        // they are standing on it (not clipping through it)
        return verticalOverlap > 0 && verticalOverlap < 0.2;
    }

    /**
     * Check if a block should be treated as solid for collision purposes.
     * Uses the block's actual collision shape (via getBoundingBox()) rather
     * than a hardcoded block name list. This naturally handles ALL non-full-height
     * blocks (slabs, stairs, dirt path, farmland, soul sand, fences, walls, etc.)
     * without needing to enumerate them.
     *
     * Only truly passable blocks (air, water, signs, torches, etc.) are excluded.
     * Blocks with non-full bounding boxes (e.g. 15/16 height dirt path) will
     * still be checked but won't overlap with the player's feet-level AABB
     * during normal standing — handled by blockOverlapsBoundingBox().
     */
    private boolean isSolidForCollision(Block block) {
        // Must be collidable (has collision at all)
        if (!block.isCollidable()) {
            return false;
        }

        // Must have a non-empty bounding box
        BoundingBox box = block.getBoundingBox();
        return box != null && box.getVolumeX() > 0
            && box.getVolumeY() > 0 && box.getVolumeZ() > 0;
    }

    /**
     * Check if a block's actual collision shape overlaps with the player's bounding box.
     * Uses Block#getBoundingBox() which returns the REAL collision shape accounting for
     * slab heights, stair steps, fence widths, etc. — not an assumed 1x1x1 cube.
     *
     * This eliminates the need for any hardcoded block exemptions. Non-full-height blocks
     * (dirt path 15/16, slabs 1/2, soul sand 14/16, farmland 15/16, etc.) have their
     * true bounding boxes checked directly, so a player standing on top of them won't
     * have overlapping AABBs.
     *
     * For blocks with multiple collision boxes (stairs, fences), getBoundingBox() returns
     * the overall bounding envelope. If needed, getCollisionShape().getBoundingBoxes()
     * can be used for per-component checks, but the envelope is sufficient here since
     * we're checking if the player's center/body area overlaps with ANY part of the block.
     */
    private boolean blockOverlapsBoundingBox(Block block,
            double bbMinX, double bbMaxX, double bbMinY, double bbMaxY,
            double bbMinZ, double bbMaxZ) {
        BoundingBox playerBox = new BoundingBox(bbMinX, bbMinY, bbMinZ,
            bbMaxX, bbMaxY, bbMaxZ);
        BoundingBox blockBox = block.getBoundingBox();
        return playerBox.overlaps(blockBox);
    }

    /**
     * Check if the player should be exempted from this check.
     */
    private boolean shouldExempt(Player player, PlayerData data, long now) {
        // Player in water (can be inside waterlogged blocks)
        if (player.isInWater() || player.isInLava()) {
            return true;
        }

        // Player in vehicle
        if (player.isInsideVehicle()) {
            return true;
        }

        // Player using elytra (gliding)
        if (player.isGliding()) {
            return true;
        }

        // Player is flying (creative/spectator - though shouldSkip handles game mode)
        if (player.isFlying()) {
            return true;
        }

        // Recently teleported (3 seconds)
        // Check by seeing if the position jumped significantly
        NoClipTracker tracker = trackers.get(player.getUniqueId());
        if (tracker != null && tracker.lastCheckedLocation != null) {
            double distSq = tracker.lastCheckedLocation.distanceSquared(data.getCurrentLocation());
            if (distSq > 16.0) { // 4+ block jump = likely teleport
                return true;
            }
        }

        // Recently joined server (3 seconds for spawn loading)
        if ((now - data.getJoinTime()) < TELEPORT_EXEMPT_MS) {
            return true;
        }

        // Wind charge / explosion knockback (1 second)
        if ((now - data.getLastKnockbackTime()) < KNOCKBACK_EXEMPT_MS) {
            return true;
        }

        // Player recently took damage (knockback from entities)
        if (player.getNoDamageTicks() > 0) {
            return true;
        }

        // Player is dead
        if (player.isDead()) {
            return true;
        }

        // Player is sleeping
        if (player.isSleeping()) {
            return true;
        }

        return false;
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isFlying() || player.isInsideVehicle()
            || player.isSleeping() || player.isDead();
    }
}
