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
 *   Wurst Step: Client-side modifies collision to allow walking up 1+ block heights
 *   Meteor Step: Similar, supports configurable step heights (1.0, 1.5, 2.0+)
 *   Normal MC: Player can auto-step up to 0.6 blocks without jumping
 *   Cheat signature: Consistent >0.6 block vertical rise while on ground, no jump
 *
 * Physics reference (Minecraft 1.21.x, minecraft.wiki):
 *   Normal auto-step height: 0.6 blocks
 *   Step height formula: stepHeight = 0.6 (hardcoded in client)
 *   Player bounding box: 0.6 x 1.8 x 0.6
 *   Full block: 1.0 height
 *   Slabs: 0.5, Stairs bottom: 0.5, Snow: 0.125-0.875, Carpet: 0.0625
 *   Soul Sand collision: 0.875, Farmland: 0.9375
 *   Dirt Path: 0.9375
 *
 * Detection logic (multi-layer):
 *   1. Basic: deltaY > 0.6 while on ground, no recent jump
 *   2. Height verification: Use block.getBoundingBox() for real collision height
 *   3. Velocity check: Step should have minimal vertical velocity (not a jump)
 *   4. Pattern detection: Repeated 0.6+ steps in short window = suspicious
 *   5. Instant flag for >1.0 (impossible without jump or specific blocks)
 */
public class StepCheck extends Check {

    // Source: minecraft.wiki/w/Player - auto-step height = 0.6 blocks
    private static final double NORMAL_STEP_HEIGHT = 0.6;

    // Minimum horizontal movement to consider
    private static final double MIN_HORIZONTAL_MOVE = 0.05;

    // Minimum vertical rise to enter step analysis
    private static final double MIN_RISE_ANALYSIS = 0.5;

    // Threshold for instant flag (clearly impossible without jump)
    // 0.6 < height <= 1.0: buffer path
    // > 1.0: instant flag (no legitimate scenario except very specific block combos)
    private static final double INSTANT_FLAG_THRESHOLD = 1.0;

    // Buffer threshold for borderline cases (0.6 < height <= 1.0)
    // Lowered from 3 to 2: legitimate players rarely step 0.6+ repeatedly
    private static final int STEP_BUFFER_FLAG_THRESHOLD = 2;

    // Pattern detection: window for counting repeated steps (ms)
    private static final long PATTERN_WINDOW_MS = 3000L;

    // Pattern detection: number of 0.6+ steps in window to flag
    private static final int PATTERN_STEP_THRESHOLD = 3;

    // Time window to consider a jump "recent" (ms)
    // Reduced from 500 to 350: a real jump clears step in < 200ms
    private static final long RECENT_JUMP_WINDOW_MS = 350L;

    // Knockback/damage exemption window (ms)
    // Reduced from 1000 to 400: step cheats exploit long exemption windows
    private static final long KNOCKBACK_EXEMPT_MS = 400L;

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.20;

    /**
     * Internal tracker for per-player step violations.
     */
    static class StepTracker {
        int stepBuffer;
        double lastStepHeight;
        boolean wasOnGround;

        // Pattern detection: timestamps of recent step-ups
        final long[] stepTimestamps = new long[10];
        int stepTimestampCount;

        StepTracker() {
            this.stepBuffer = 0;
            this.lastStepHeight = 0;
            this.wasOnGround = true;
        }

        void reset() {
            this.stepBuffer = 0;
            this.lastStepHeight = 0;
        }

        void recordStep(long now) {
            if (stepTimestampCount < stepTimestamps.length) {
                stepTimestamps[stepTimestampCount++] = now;
            } else {
                // Shift array left
                System.arraycopy(stepTimestamps, 1, stepTimestamps, 0, stepTimestamps.length - 1);
                stepTimestamps[stepTimestampCount - 1] = now;
            }
        }

        int countStepsInWindow(long now, long windowMs) {
            long cutoff = now - windowMs;
            int count = 0;
            for (int i = 0; i < stepTimestampCount; i++) {
                if (stepTimestamps[i] >= cutoff) count++;
            }
            return count;
        }
    }

    private final ConcurrentHashMap<UUID, StepTracker> trackers = new ConcurrentHashMap<>();

    public StepCheck(ANSACPlugin plugin) {
        super(plugin, "Step", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation
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
            decayBuffer(tracker);
            tracker.wasOnGround = onGround;
            return;
        }

        // Must be moving upward at all
        if (deltaY <= 0) {
            decayBuffer(tracker);
            tracker.wasOnGround = onGround;
            return;
        }

        // Must be on ground (or was on ground last tick - transitional)
        if (!onGround && !tracker.wasOnGround) {
            tracker.wasOnGround = onGround;
            return;
        }

        // Only analyze if deltaY is above analysis threshold
        if (deltaY < MIN_RISE_ANALYSIS) {
            decayBuffer(tracker);
            tracker.wasOnGround = onGround;
            return;
        }

        // --- Exemption checks ---
        if (shouldExempt(player, data, now)) {
            decayBuffer(tracker);
            tracker.wasOnGround = onGround;
            return;
        }

        // --- Verify step height using real block collision ---
        double stepHeight = verifyStepHeight(from, to, deltaY);

        if (stepHeight <= NORMAL_STEP_HEIGHT) {
            // Legitimate step (slab, snow layer, carpet, etc.)
            decayBuffer(tracker);
            tracker.wasOnGround = onGround;
            return;
        }

        tracker.lastStepHeight = stepHeight;
        tracker.recordStep(now);

        // --- Pattern detection ---
        // Even individual steps that pass the threshold are suspicious if
        // they happen repeatedly. Count 0.6+ steps in the pattern window.
        int stepsInWindow = tracker.countStepsInWindow(now, PATTERN_WINDOW_MS);
        boolean isPattern = stepsInWindow >= PATTERN_STEP_THRESHOLD;

        // --- Three-tier detection ---
        if (stepHeight > INSTANT_FLAG_THRESHOLD) {
            // Tier 1: Clearly impossible (>1.0 block step without jump)
            double severity = 1.5 + (stepHeight - INSTANT_FLAG_THRESHOLD);
            flag(player, data, severity,
                String.format("异常台阶: %.2f 格 (上限 %.1f, 无跳跃, %d次/3s, 延迟 %s)",
                    stepHeight, NORMAL_STEP_HEIGHT, stepsInWindow,
                    data.getPingCompensator().getPingStatus()));
            tracker.reset();
        } else if (isPattern) {
            // Tier 2: Pattern detected (repeated 0.6+ steps)
            // Each individual step is borderline but the pattern is unmistakable
            double severity = 1.0 + (stepsInWindow - PATTERN_STEP_THRESHOLD) * 0.3;
            flag(player, data, severity,
                String.format("台阶模式: %.2f 格 x%d次/3s (上限 %.1f, 延迟 %s)",
                    stepHeight, stepsInWindow, NORMAL_STEP_HEIGHT,
                    data.getPingCompensator().getPingStatus()));
            tracker.reset();
        } else {
            // Tier 3: Borderline single step (0.6 < height <= 1.0)
            tracker.stepBuffer++;
            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                STEP_BUFFER_FLAG_THRESHOLD, COMPENSATION_FACTOR);

            if (tracker.stepBuffer >= compensatedBuffer) {
                double severity = stepHeight / NORMAL_STEP_HEIGHT;
                flag(player, data, severity,
                    String.format("可疑台阶: %.2f 格 (连续 %d 次, %d次/3s, 延迟 %s)",
                        stepHeight, tracker.stepBuffer, stepsInWindow,
                        data.getPingCompensator().getPingStatus()));
                tracker.reset();
            }
        }

        tracker.wasOnGround = onGround;
    }

    /**
     * Verify the actual step height by checking block collision shapes.
     * Uses Block.getBoundingBox() for real height data instead of hardcoded values.
     *
     * @return The verified step height, or raw deltaY if verification is inconclusive.
     */
    private double verifyStepHeight(Location from, Location to, double rawDeltaY) {
        Block destBlock = to.getWorld().getBlockAt(
            to.getBlockX(), to.getBlockY(), to.getBlockZ());
        Material destType = destBlock.getType();

        // Quick check: if the destination block is air/passable, the player
        // stepped onto whatever is below it.
        Block belowDest = destBlock.getRelative(BlockFace.DOWN);
        Material belowDestType = belowDest.getType();
        String belowName = belowDestType.name();

        // Check block below from position
        Block fromBlock = from.getWorld().getBlockAt(
            from.getBlockX(), from.getBlockY(), from.getBlockZ());
        Block belowFrom = fromBlock.getRelative(BlockFace.DOWN);

        // Calculate actual height difference using real bounding box heights
        double fromSurfaceY = belowFrom.getY() + getBlockCollisionTop(belowFrom);
        double toSurfaceY = belowDest.getY() + getBlockCollisionTop(belowDest);

        double actualStep = toSurfaceY - fromSurfaceY;

        // Sanity: if calculated step is negative or zero but raw deltaY is positive,
        // the bounding box method failed. Fall back to raw deltaY.
        if (actualStep > 0) {
            return actualStep;
        }

        return rawDeltaY;
    }

    /**
     * Get the real collision top height of a block using getBoundingBox().
     * This accurately handles all block types without hardcoding.
     */
    private double getBlockCollisionTop(Block block) {
        try {
            org.bukkit.util.BoundingBox box = block.getBoundingBox();
            if (box != null && box.getHeight() > 0) {
                // getBoundingBox() returns local coordinates relative to block position
                return box.getMaxY() - block.getY();
            }
        } catch (Exception ignored) {
            // Fallback to hardcoded check
        }

        // Fallback: estimate from material type
        Material type = block.getType();
        String name = type.name();

        if (name.contains("SLAB")) return 0.5;
        if (name.contains("STAIRS")) return 0.5;
        if (type == Material.SNOW) return 0.875; // max layers
        if (name.contains("CARPET")) return 0.0625;
        if (type == Material.SOUL_SAND) return 0.875;
        if (type == Material.FARMLAND) return 0.9375;
        if (type == Material.DIRT_PATH) return 0.9375;
        if (type == Material.MUD) return 0.9375;

        // Walls, fences, gates, etc. have 1.5 collision but player can't step onto them
        // normally. If somehow on top, treat as 1.0.
        return 1.0;
    }

    /**
     * Check if the player should be exempted from this check.
     * Narrow exemptions to prevent cheat exploitation.
     */
    private boolean shouldExempt(Player player, PlayerData data, long now) {
        // Recently jumped: a real jump can cause a brief "step" appearance
        if ((now - data.getLastJumpTime()) < RECENT_JUMP_WINDOW_MS) {
            return true;
        }

        // Wind charge / explosion knockback
        if ((now - data.getLastKnockbackTime()) < KNOCKBACK_EXEMPT_MS) {
            return true;
        }

        // Levitation effect (completely overrides normal movement)
        PotionEffectType levitation = ServerVersionAdapter.getLevitation();
        if (levitation != null && player.hasPotionEffect(levitation)) {
            return true;
        }

        // Slow Falling (reduces fall speed, can affect step timing)
        // No exemption needed as it doesn't increase step height

        // Player in water or lava (different physics)
        if (player.isInWater() || player.isInLava()) {
            return true;
        }

        // Player climbing (ladders, vines, etc.)
        if (player.isClimbing()) {
            return true;
        }

        // Player using elytra
        if (player.isGliding()) {
            return true;
        }

        // Player in vehicle
        if (player.isInsideVehicle()) {
            return true;
        }

        // NOTE: Jump Boost does NOT change auto-step height (wiki: always 0.6).
        // Removed Jump Boost exemption to prevent cheat exploitation.
        //
        // NOTE: NoDamageTicks exemption removed. Step cheats exploit damage
        // immunity to bypass checks. Knockback exemption via lastKnockbackTime
        // is more precise.

        return false;
    }

    private void decayBuffer(StepTracker tracker) {
        if (tracker.stepBuffer > 0) {
            tracker.stepBuffer = Math.max(0, tracker.stepBuffer - 1);
        }
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isFlying()
            || player.isSleeping() || player.isDead();
    }
}
