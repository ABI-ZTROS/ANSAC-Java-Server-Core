package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Burrow check - detects players hiding inside solid blocks (Meteor Burrow).
 *
 * Cheat principle (Meteor Burrow):
 *   Meteor Burrow: Uses packet manipulation to place the player entity inside
 *   a solid block (typically ender chest or obsidian), making them invulnerable
 *   to most forms of damage while still being able to attack.
 *   Signature: Player's center point is fully inside a solid block for an
 *   extended period (not just edge overlap).
 *
 * Physics reference (Minecraft 1.21.x, minecraft.wiki):
 *   Player bounding box: 0.6 x 1.8 x 0.6 blocks (width x height x depth)
 *   Player center: (x, y + 0.9, z) relative to feet position
 *   A normal player cannot have their center inside a solid block
 *   Burrow exploits this by sending position packets that clip into blocks
 *
 * Detection logic:
 *   - Every tick, check if the player's center point is inside a solid block
 *   - Unlike NoClipCheck which checks bounding box edge overlap, this checks
 *     if the center of the player is fully contained within a solid block
 *   - If center is inside solid block, increment burrowBuffer
 *   - If burrowBuffer > 20 (1 second at 50ms/tick), flag the player
 *   - Uses ConcurrentHashMap for thread-safe per-player tracking
 *
 * Exemptions:
 *   - Player in water or lava
 *   - Player in vehicle
 *   - Player using elytra
 *   - Recently teleported (3 seconds)
 *   - Recently joined server (5 seconds)
 *   - Recently knocked back (1 second)
 *   - Non-solid blocks (air, water, flowers, grass, etc.)
 */
public class BurrowCheck extends Check {

    // Player center Y offset from feet position
    private static final double PLAYER_CENTER_Y_OFFSET = 0.9;

    // Number of consecutive ticks inside a block before flagging
    // 20 ticks = 1 second at 50ms/tick (server tick rate)
    private static final int BURROW_BUFFER_THRESHOLD = 20;

    // Teleport exemption window (ms)
    private static final long TELEPORT_EXEMPT_MS = 3000L;

    // Join exemption window (ms)
    private static final long JOIN_EXEMPT_MS = 5000L;

    // Knockback exemption window (ms)
    private static final long KNOCKBACK_EXEMPT_MS = 1000L;

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.15;

    /**
     * Internal tracker for per-player Burrow violations.
     * Stored in a ConcurrentHashMap for thread safety.
     */
    static class BurrowTracker {
        int burrowBuffer;
        long lastBurrowTime;

        BurrowTracker() {
            this.burrowBuffer = 0;
            this.lastBurrowTime = 0;
        }

        void reset() {
            this.burrowBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, BurrowTracker> trackers = new ConcurrentHashMap<>();

    public BurrowCheck(ANSACPlugin plugin) {
        super(plugin, "Burrow", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            BurrowTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.reset();
            }
            return;
        }

        Location loc = data.getCurrentLocation();
        if (loc == null) return;

        long now = System.currentTimeMillis();

        BurrowTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new BurrowTracker());

        // --- Exemption checks ---
        if (shouldExempt(player, data, now)) {
            tracker.reset();
            return;
        }

        // --- Check if player center is inside a solid block ---
        double centerX = loc.getX();
        double centerY = loc.getY() + PLAYER_CENTER_Y_OFFSET;
        double centerZ = loc.getZ();

        // Get the block at the player's center
        Block centerBlock = loc.getWorld().getBlockAt(
            (int) Math.floor(centerX),
            (int) Math.floor(centerY),
            (int) Math.floor(centerZ)
        );

        if (isSolidForBurrow(centerBlock)) {
            tracker.burrowBuffer++;
            tracker.lastBurrowTime = now;

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BURROW_BUFFER_THRESHOLD, COMPENSATION_FACTOR);

            if (tracker.burrowBuffer >= compensatedBuffer) {
                double severity = 1.0 + (tracker.burrowBuffer / (double) BURROW_BUFFER_THRESHOLD);
                flag(player, data, severity,
                    String.format("卡方块检测: 玩家中心位于 %s 内部 (连续 %d tick, 延迟 %s)",
                        centerBlock.getType().name(), tracker.burrowBuffer,
                        data.getPingCompensator().getPingStatus()));
                tracker.reset();
            }
        } else {
            // Gradually decay buffer when not burrowed
            tracker.burrowBuffer = Math.max(0, tracker.burrowBuffer - 1);
        }
    }

    /**
     * Check if a block should be treated as solid for burrow detection.
     * This is more strict than NoClipCheck - we only care about blocks
     * that a player could actually hide inside for invulnerability.
     */
    private boolean isSolidForBurrow(Block block) {
        Material type = block.getType();
        String name = type.name();

        // Non-solid blocks: player can be inside these normally
        if (!type.isSolid()) {
            return false;
        }

        // Liquids
        if (type == Material.WATER || type == Material.LAVA
                || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS
                || type == Material.KELP || type == Material.KELP_PLANT) {
            return false;
        }

        // Passable vegetation
        if (name.contains("FLOWER") || name.contains("TALL_GRASS")
                || name.contains("FERN") || name.contains("BUSH")
                || name.contains("DEAD_BUSH") || name.contains("MUSHROOM")) {
            return false;
        }

        // Thin plants
        if (type == Material.SUGAR_CANE || type == Material.BAMBOO
                || type == Material.BAMBOO_SAPLING || type == Material.SAPLING) {
            return false;
        }

        // Coral fans (not blocks)
        if (name.contains("CORAL") && !name.contains("BLOCK")) {
            return false;
        }

        // Signs
        if (name.contains("SIGN")) {
            return false;
        }

        // Torches
        if (name.contains("TORCH")) {
            return false;
        }

        // Buttons
        if (name.contains("BUTTON")) {
            return false;
        }

        // Pressure plates
        if (name.contains("PRESSURE_PLATE")) {
            return false;
        }

        // Rails
        if (name.contains("RAIL")) {
            return false;
        }

        // Carpets
        if (name.contains("CARPET")) {
            return false;
        }

        // Openable blocks
        if (name.contains("DOOR") || name.contains("TRAPDOOR")
                || name.contains("FENCE_GATE")) {
            return false;
        }

        // Leaves (passable)
        if (name.contains("LEAVES")) {
            return false;
        }

        // Single slabs (0.5 height, not full block)
        if (name.contains("SLAB") && !name.contains("DOUBLE")) {
            return false;
        }

        // Stairs (complex collision)
        if (name.contains("STAIRS")) {
            return false;
        }

        // Walls and fences (irregular hitbox)
        if (name.contains("WALL") || name.contains("FENCE")) {
            return false;
        }

        // Thin blocks
        if (name.contains("IRON_BARS") || name.contains("GLASS_PANE")
                || name.contains("CHAIN")) {
            return false;
        }

        // Thin decorative
        if (name.contains("END_ROD") || name.contains("LIGHTNING_ROD")
                || name.contains("POINTED_DRIPSTONE")) {
            return false;
        }

        // Climbable
        if (name.contains("LADDER") || name.contains("VINE")) {
            return false;
        }

        // Scaffolding
        if (name.contains("SCAFFOLDING")) {
            return false;
        }

        // Powder snow (player sinks)
        if (type == Material.POWDER_SNOW) {
            return false;
        }

        // Cobweb (slows but doesn't block)
        if (type == Material.COBWEB) {
            return false;
        }

        // Snow layers (not full blocks)
        if (type == Material.SNOW) {
            return false;
        }

        return true;
    }

    /**
     * Check if the player should be exempted from this check.
     */
    private boolean shouldExempt(Player player, PlayerData data, long now) {
        // Player in water or lava
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

        // Recently teleported (3 seconds)
        if (now - data.getLastMoveTime() < 100 && data.getLastLocation() != null) {
            double distSq = data.getLastLocation().distanceSquared(data.getCurrentLocation());
            if (distSq > 16.0) { // 4+ block jump = likely teleport
                return true;
            }
        }

        // Recently joined server (5 seconds)
        if ((now - data.getJoinTime()) < JOIN_EXEMPT_MS) {
            return true;
        }

        // Knockback exemption (1 second)
        if ((now - data.getLastKnockbackTime()) < KNOCKBACK_EXEMPT_MS) {
            return true;
        }

        return false;
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isSleeping() || player.isDead();
    }
}
