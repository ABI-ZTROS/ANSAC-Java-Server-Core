package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.util.ServerVersionAdapter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HighJump check - detects abnormally high jumps.
 *
 * Cheat principle (Wurst HighJump + Meteor HighJump):
 *   Wurst HighJump: Modifies jump height, default jump to 1.25 blocks (normal 1.25 -> cheat 2-5+ blocks)
 *   Meteor HighJump: Similar, supports multiple height modes
 *   Normal jump physics: initial velocity 0.42, Jump Boost adds +0.1 per level, max height ~1.25 blocks
 *   Cheat jump: single jump height exceeds normal limits
 *
 * Physics reference (Minecraft 1.21.x, minecraft.wiki):
 *   Jump initial velocity: 0.42 blocks/tick (Y-axis)
 *   Gravity formula: v(t) = 0.98 * (v(t-1) - 0.08)
 *   Max jump height (no boost): ~1.252 blocks
 *   Jump Boost I: initial velocity 0.52 -> max height ~1.518 blocks
 *   Jump Boost II: initial velocity 0.62 -> max height ~1.835 blocks
 *   Jump Boost III+: initial velocity 0.42 + 0.1 * level
 *   Levitation: overrides gravity with upward velocity of 0.05 * (amplifier + 1)
 *   Wind charge knockback: can launch player ~6 blocks high
 *
 * Detection logic:
 *   - Tracks jump lifecycle: ground -> airborne -> peak -> landing
 *   - Records jumpStartY when player leaves ground with positive deltaY
 *   - Tracks peakY during ascent
 *   - Calculates total jump height when player lands or begins descending
 *   - Compares against dynamic threshold based on Jump Boost level + ping compensation
 *   - Uses buffer system to avoid false positives from single-tick anomalies
 */
public class HighJumpCheck extends Check {

    // Normal jump physics constants
    private static final double BASE_JUMP_INITIAL_VELOCITY = 0.42;
    private static final double JUMP_BOOST_PER_LEVEL = 0.1;
    private static final double GRAVITY_SUBTRACT = 0.08;
    private static final double GRAVITY_MULTIPLIER = 0.98;

    // Pre-calculated max heights for common jump boost levels
    private static final double MAX_HEIGHT_NO_BOOST = 1.252;
    private static final double MAX_HEIGHT_BOOST_I = 1.518;
    private static final double MAX_HEIGHT_BOOST_II = 1.835;

    // Tolerance for network latency and tick imprecision
    private static final double TOLERANCE = 0.3;

    // Time window to consider a jump "recent" (ms)
    private static final long JUMP_LIFETIME_MS = 2000L;

    // Minimum deltaY to consider a jump start (filter out micro-movements)
    private static final double MIN_JUMP_DELTA_Y = 0.2;

    // Buffer threshold before flagging
    private static final int BUFFER_FLAG_THRESHOLD = 3;

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.20;

    /**
     * Internal tracker for per-player jump state.
     * Stored in a ConcurrentHashMap for thread safety.
     */
    static class JumpTracker {
        double jumpStartY;
        double peakY;
        boolean isJumping;
        int highJumpBuffer;
        boolean wasOnGround;
        long jumpStartTime;

        JumpTracker() {
            this.jumpStartY = Double.MIN_VALUE;
            this.peakY = Double.MIN_VALUE;
            this.isJumping = false;
            this.highJumpBuffer = 0;
            this.wasOnGround = true;
            this.jumpStartTime = 0;
        }

        void reset() {
            this.jumpStartY = Double.MIN_VALUE;
            this.peakY = Double.MIN_VALUE;
            this.isJumping = false;
            this.jumpStartTime = 0;
        }
    }

    private final ConcurrentHashMap<UUID, JumpTracker> trackers = new ConcurrentHashMap<>();

    public HighJumpCheck(ANSACPlugin plugin) {
        super(plugin, "HighJump", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            JumpTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.highJumpBuffer = 0;
            }
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        // Skip teleport-like movements
        if (from.distanceSquared(to) > 16.0) {
            cleanupTracker(player.getUniqueId());
            return;
        }

        double deltaY = data.getVerticalDistance();
        boolean onGround = player.isOnGround();
        long now = System.currentTimeMillis();

        JumpTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new JumpTracker());

        // Exemption checks
        if (shouldExempt(player, data, now)) {
            tracker.highJumpBuffer = 0;
            tracker.reset();
            tracker.wasOnGround = onGround;
            return;
        }

        // --- Phase 1: Detect jump start ---
        // Player leaves ground with upward movement
        if (tracker.wasOnGround && !onGround && deltaY > MIN_JUMP_DELTA_Y) {
            tracker.jumpStartY = from.getY();
            tracker.peakY = to.getY();
            tracker.isJumping = true;
            tracker.jumpStartTime = now;
            data.setLastJumpTime(now);
        }

        // --- Phase 2: Track peak during ascent ---
        if (tracker.isJumping && deltaY > 0) {
            if (to.getY() > tracker.peakY) {
                tracker.peakY = to.getY();
            }
        }

        // --- Phase 3: Evaluate jump when player lands or begins falling ---
        if (tracker.isJumping) {
            boolean jumpExpired = (now - tracker.jumpStartTime) > JUMP_LIFETIME_MS;
            boolean startedDescending = deltaY < -0.05;
            boolean landed = onGround;

            if (jumpExpired || startedDescending || landed) {
                double jumpHeight = tracker.peakY - tracker.jumpStartY;

                if (jumpHeight > 0) {
                    double maxAllowed = getMaxAllowedHeight(player);
                    double compensatedMax = data.getPingCompensator().getCompensatedThreshold(
                        maxAllowed + TOLERANCE, COMPENSATION_FACTOR);

                    if (jumpHeight > compensatedMax) {
                        tracker.highJumpBuffer++;
                        int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                            BUFFER_FLAG_THRESHOLD, COMPENSATION_FACTOR);

                        if (tracker.highJumpBuffer >= compensatedBuffer) {
                            double severity = jumpHeight / maxAllowed;
                            flag(player, data, severity,
                                String.format("异常高跳: %.2f 格 (上限: %.2f 格, 连续 %d 次, 延迟 %s)",
                                    jumpHeight, maxAllowed, tracker.highJumpBuffer,
                                    data.getPingCompensator().getPingStatus()));
                        }
                    } else {
                        // Gradually decay buffer on legitimate jumps
                        tracker.highJumpBuffer = Math.max(0, tracker.highJumpBuffer - 1);
                    }
                }

                tracker.reset();
            }
        }

        tracker.wasOnGround = onGround;
    }

    /**
     * Calculate the maximum allowed jump height based on player's effects.
     * Uses precise physics simulation for arbitrary Jump Boost levels.
     */
    private double getMaxAllowedHeight(Player player) {
        PotionEffectType jumpBoostType = ServerVersionAdapter.getJumpBoost();
        int jumpBoostLevel = 0;
        if (jumpBoostType != null && player.hasPotionEffect(jumpBoostType)) {
            jumpBoostLevel = player.getPotionEffect(jumpBoostType).getAmplifier() + 1;
        }

        // Use pre-calculated values for common levels, simulate for others
        switch (jumpBoostLevel) {
            case 0: return MAX_HEIGHT_NO_BOOST;
            case 1: return MAX_HEIGHT_BOOST_I;
            case 2: return MAX_HEIGHT_BOOST_II;
            default:
                // Simulate jump height for arbitrary boost level
                return simulateMaxJumpHeight(
                    BASE_JUMP_INITIAL_VELOCITY + JUMP_BOOST_PER_LEVEL * jumpBoostLevel);
        }
    }

    /**
     * Simulate the maximum jump height using Minecraft's gravity formula.
     * v(t) = 0.98 * (v(t-1) - 0.08), sum all positive deltaY values.
     */
    private double simulateMaxJumpHeight(double initialVelocity) {
        double velocity = initialVelocity;
        double totalHeight = 0;
        int maxTicks = 100; // Safety limit

        while (velocity > 0 && maxTicks-- > 0) {
            totalHeight += velocity;
            velocity = GRAVITY_MULTIPLIER * (velocity - GRAVITY_SUBTRACT);
        }

        return totalHeight;
    }

    /**
     * Check if the player should be exempted from this check.
     */
    private boolean shouldExempt(Player player, PlayerData data, long now) {
        // Wind charge / explosion knockback: exempt for 1 second
        if ((now - data.getLastKnockbackTime()) < 1000L) {
            return true;
        }

        // Levitation effect: player floats upward, not a jump cheat
        PotionEffectType levitation = ServerVersionAdapter.getLevitation();
        if (levitation != null && player.hasPotionEffect(levitation)) {
            return true;
        }

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

        // Player has slow falling (reduces fall speed, might affect jump feel)
        PotionEffectType slowFalling = getPotionEffectType("SLOW_FALLING");
        if (slowFalling != null && player.hasPotionEffect(slowFalling)) {
            return true;
        }

        return false;
    }

    /**
     * Safely get a PotionEffectType by name via reflection.
     * Used for effects not yet in ServerVersionAdapter.
     */
    private PotionEffectType getPotionEffectType(String name) {
        try {
            java.lang.reflect.Field field = PotionEffectType.class.getField(name);
            return (PotionEffectType) field.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isFlying() || player.isInsideVehicle()
            || player.isSleeping() || player.isDead();
    }

    private void cleanupTracker(UUID uuid) {
        trackers.remove(uuid);
    }
}
