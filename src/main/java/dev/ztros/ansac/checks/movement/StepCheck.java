package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.util.ServerVersionAdapter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Step check - detects automated step-up cheats (walking up blocks without jumping).
 *
 * Cheat principle (Wurst Step + Meteor Step):
 *   Wurst Step: Automatically walks up blocks higher than 1 block (normal max auto-step is 0.6 blocks)
 *   Meteor Step: Similar, supports multiple height modes
 *   Normal MC: Player can auto-step up to 0.6 blocks high without jumping
 *   Cheat: Player walks up 1.0, 1.5 or even higher blocks without jumping
 *
 * Physics reference (Minecraft 1.21.x, minecraft.wiki):
 *   Normal auto-step height: 0.6 blocks (full block height is 1.0)
 *   With Jump Boost: auto-step height remains 0.6, but jump height increases
 *   Soul Sand: 0.875 block height (slows but doesn't change step height)
 *   Snow layers: variable height (0.125 per layer, up to 0.875 for 7 layers)
 *   Slabs: 0.5 blocks (bottom/top slab)
 *   Stairs: 0.5 blocks (bottom step)
 *   Carpets: 0.0625 blocks
 *
 * Detection logic:
 *   - Every tick, check if player moved horizontally AND vertically upward
 *   - If player is on ground and vertical rise > 0.6 without having jumped recently
 *   - Verify by checking block height difference at player's feet position
 *   - Two-tier flagging: >1.0 blocks = instant flag, >0.6 blocks = buffer-based flag
 *   - Uses ConcurrentHashMap for thread-safe per-player tracking
 */
public class StepCheck extends Check {

    // Normal auto-step height limit
    private static final double NORMAL_STEP_HEIGHT = 0.6;

    // Minimum horizontal movement to consider (filter out vertical-only movement)
    private static final double MIN_HORIZONTAL_MOVE = 0.1;

    // Minimum vertical rise to be suspicious (above normal step)
    private static final double MIN_SUSPICIOUS_RISE = 0.6;

    // Threshold for instant flag (clearly impossible without jump)
    private static final double INSTANT_FLAG_THRESHOLD = 1.0;

    // Time window to consider a jump "recent" (ms)
    private static final long RECENT_JUMP_WINDOW_MS = 500L;

    // Buffer threshold for borderline cases (0.6 < height < 1.0)
    private static final int STEP_BUFFER_FLAG_THRESHOLD = 3;

    // Knockback exemption window (ms)
    private static final long KNOCKBACK_EXEMPT_MS = 1000L;

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.25;

    /**
     * Internal tracker for per-player step violations.
     * Stored in a ConcurrentHashMap for thread safety.
     */
    static class StepTracker {
        int stepBuffer;
        double lastStepHeight;
        boolean wasOnGround;

        StepTracker() {
            this.stepBuffer = 0;
            this.lastStepHeight = 0;
            this.wasOnGround = true;
        }

        void reset() {
            this.stepBuffer = 0;
            this.lastStepHeight = 0;
        }
    }

    private final ConcurrentHashMap<UUID, StepTracker> trackers = new ConcurrentHashMap<>();

    public StepCheck(ANSACPlugin plugin) {
        super(plugin, "Step", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            StepTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.reset();
            }
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        // Skip teleport-like movements
        if (from.distanceSquared(to) > 16.0) {
            StepTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.reset();
            }
            return;
        }

        double horizontalDist = data.getHorizontalDistance();
        double deltaY = data.getVerticalDistance();
        boolean onGround = player.isOnGround();
        long now = System.currentTimeMillis();

        StepTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new StepTracker());

        // --- Pre-condition checks ---
        // Must be moving horizontally
        if (horizontalDist < MIN_HORIZONTAL_MOVE) {
            tracker.stepBuffer = Math.max(0, tracker.stepBuffer - 1);
            tracker.wasOnGround = onGround;
            return;
        }

        // Must be moving upward
        if (deltaY <= MIN_SUSPICIOUS_RISE) {
            tracker.stepBuffer = Math.max(0, tracker.stepBuffer - 1);
            tracker.wasOnGround = onGround;
            return;
        }

        // Must be on ground (or was on ground last tick)
        if (!onGround && !tracker.wasOnGround) {
            tracker.wasOnGround = onGround;
            return;
        }

        // --- Exemption checks ---
        if (shouldExempt(player, data, now)) {
            tracker.stepBuffer = Math.max(0, tracker.stepBuffer - 1);
            tracker.wasOnGround = onGround;
            return;
        }

        // --- Verify step height by checking block geometry ---
        double stepHeight = verifyStepHeight(from, to, deltaY);

        if (stepHeight <= NORMAL_STEP_HEIGHT) {
            // Legitimate step (e.g., slab, snow layer)
            tracker.stepBuffer = Math.max(0, tracker.stepBuffer - 1);
            tracker.wasOnGround = onGround;
            return;
        }

        tracker.lastStepHeight = stepHeight;

        // --- Two-tier detection ---
        if (stepHeight > INSTANT_FLAG_THRESHOLD) {
            // Clearly impossible: > 1.0 block step without jumping
            double severity = stepHeight / NORMAL_STEP_HEIGHT;
            flag(player, data, severity,
                String.format("异常台阶: %.2f 格 (上限: %.1f 格, 无跳跃, 延迟 %s)",
                    stepHeight, NORMAL_STEP_HEIGHT,
                    data.getPingCompensator().getPingStatus()));
            tracker.reset();
        } else {
            // Borderline: 0.6 < height <= 1.0, use buffer
            tracker.stepBuffer++;
            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                STEP_BUFFER_FLAG_THRESHOLD, COMPENSATION_FACTOR);

            if (tracker.stepBuffer >= compensatedBuffer) {
                double severity = stepHeight / NORMAL_STEP_HEIGHT;
                flag(player, data, severity,
                    String.format("可疑台阶: %.2f 格 (连续 %d 次, 延迟 %s)",
                        stepHeight, tracker.stepBuffer,
                        data.getPingCompensator().getPingStatus()));
                tracker.reset();
            }
        }

        tracker.wasOnGround = onGround;
    }

    /**
     * Verify the actual step height by checking block heights at player's feet.
     * This prevents false positives from half-slabs, stairs, snow layers, etc.
     * that are legitimately less than 0.6 blocks but may report higher deltaY
     * due to tick timing.
     *
     * @return The verified step height, or the raw deltaY if verification is inconclusive.
     */
    private double verifyStepHeight(Location from, Location to, double rawDeltaY) {
        // Check the block at the destination position (player's feet)
        Block destBlock = to.getBlock();
        Material destType = destBlock.getType();
        String destName = destType.name();

        // Slabs: 0.5 block height (legitimate step)
        if (destName.contains("SLAB")) {
            return 0.5;
        }

        // Stairs: bottom is 0.5 (legitimate step)
        if (destName.contains("STAIRS")) {
            return 0.5;
        }

        // Snow layers: 0.125 per layer, max 8 layers = 1.0 (but 7 layers = 0.875)
        if (destType == Material.SNOW) {
            // Snow height is stored in block data; approximate
            return Math.min(rawDeltaY, 0.875);
        }

        // Carpet: 0.0625 blocks
        if (destName.contains("CARPET")) {
            return 0.0625;
        }

        // Check if the player stepped onto a block with a non-full top surface
        Block below = destBlock.getRelative(BlockFace.DOWN);
        Material belowType = below.getType();
        String belowName = belowType.name();

        // Soul sand: 0.875 height, but player stands at 0.875 (not a step-up)
        if (belowType == Material.SOUL_SAND) {
            return 0.0; // Not a step, just standing on soul sand
        }

        // Farmland: 0.9375 height
        if (belowType == Material.FARMLAND) {
            return 0.0;
        }

        // Check the block the player was standing on before
        Block fromBelow = from.getBlock().getRelative(BlockFace.DOWN);
        double fromTopY = fromBelow.getY() + getBlockTopHeight(fromBelow);
        double toTopY = below.getY() + getBlockTopHeight(below);

        // Calculate actual height difference between the two surfaces
        double actualStepHeight = toTopY - fromTopY;

        // If we can determine actual block heights, use them
        if (actualStepHeight > 0) {
            return actualStepHeight;
        }

        // Fallback to raw deltaY
        return rawDeltaY;
    }

    /**
     * Get the effective top height of a block.
     * Most full blocks are 1.0, but some have non-standard heights.
     */
    private double getBlockTopHeight(Block block) {
        Material type = block.getType();
        String name = type.name();

        // Full blocks: 1.0
        if (type.isSolid() && !name.contains("SLAB") && !name.contains("STAIRS")
                && !name.contains("WALL") && type != Material.SNOW
                && type != Material.SOUL_SAND && type != Material.FARMLAND
                && !name.contains("CARPET") && !name.contains("DAYLIGHT")) {
            return 1.0;
        }

        // Slabs: 0.5 (bottom or top)
        if (name.contains("SLAB")) {
            return 0.5;
        }

        // Stairs: 0.5 at the lower step
        if (name.contains("STAIRS")) {
            return 0.5;
        }

        // Snow: approximate 0.125 per layer
        if (type == Material.SNOW) {
            return 0.125; // Minimum, actual depends on layers
        }

        // Soul sand: 0.875
        if (type == Material.SOUL_SAND) {
            return 0.875;
        }

        // Farmland: 0.9375
        if (type == Material.FARMLAND) {
            return 0.9375;
        }

        // Carpet: 0.0625
        if (name.contains("CARPET")) {
            return 0.0625;
        }

        // Walls, fences, etc.: 1.0 but player can't normally step onto them
        return 1.0;
    }

    /**
     * Check if the player should be exempted from this check.
     */
    private boolean shouldExempt(Player player, PlayerData data, long now) {
        // Recently jumped: legitimate step-up after jump
        if ((now - data.getLastJumpTime()) < RECENT_JUMP_WINDOW_MS) {
            return true;
        }

        // Wind charge / explosion knockback
        if ((now - data.getLastKnockbackTime()) < KNOCKBACK_EXEMPT_MS) {
            return true;
        }

        // Jump Boost effect: increases jump height, might affect step feel
        PotionEffectType jumpBoost = ServerVersionAdapter.getJumpBoost();
        if (jumpBoost != null && player.hasPotionEffect(jumpBoost)) {
            // Jump Boost doesn't change auto-step height, but high levels
            // can cause the player to barely clear a block and land on top,
            // which looks like a step. Exempt to avoid false positives.
            int level = player.getPotionEffect(jumpBoost).getAmplifier() + 1;
            if (level >= 2) {
                return true;
            }
        }

        // Levitation effect
        PotionEffectType levitation = ServerVersionAdapter.getLevitation();
        if (levitation != null && player.hasPotionEffect(levitation)) {
            return true;
        }

        // Player in water or lava (different physics)
        if (player.isInWater() || player.isInLava()) {
            return true;
        }

        // Player climbing
        if (player.isClimbing()) {
            return true;
        }

        // Player using elytra
        if (player.isGliding()) {
            return true;
        }

        // Player recently took damage (knockback)
        if (player.getNoDamageTicks() > 0) {
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
