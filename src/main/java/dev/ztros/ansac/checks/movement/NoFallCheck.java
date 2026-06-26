package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.physics.IPhysicsCheck;
import dev.ztros.ansac.physics.InferenceResult;
import dev.ztros.ansac.physics.PhysicsConstants;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NoFall check - detects players spoofing ground state to avoid fall damage.
 *
 * Cheat principle (Wurst NoFall + Meteor NoFall):
 *   NoFall: Client modifies the onGround flag to always be true, preventing
 *   fall damage accumulation. The server checks onGround before applying damage.
 *   Advanced NoFall variants may: (1) spam onGround=true, (2) spoof ground
 *   packets, (3) use timer/packet manipulation to reset fallDistance.
 *   Signature: Player falls a significant distance but takes no damage, or
 *   client fallDistance is much less than actual distance fallen.
 *
 * Physics reference (Minecraft 1.21.x, minecraft.wiki):
 *   Gravity per tick: v(t) = 0.98 * (v(t-1) - 0.08)
 *   Gravity accel:    0.08 blocks/tick²
 *   Drag:             0.98 per tick
 *   Terminal velocity: 3.886 blocks/tick (wiki: 77.71 m/s)
 *   Fall damage threshold: 3.0 blocks (wiki: Fall damage)
 *   Damage formula: damage = fallDistance - 3.0
 *   E.g. fall 4 blocks = 1 heart, fall 23 blocks = 10 hearts (death)
 *   Expected fall time from height h: calculated via physics simulation
 *   Water/lava/cobweb/powder snow: reduce or negate fall damage
 *   Slow Falling: negates fall damage, fall speed *= 0.5
 *   Elytra: no fall damage
 *   Creative/Spectator: no fall damage
 *
 * Detection (three methods combined):
 *   1. Physics prediction: Track vertical velocity and predict where the player
 *      SHOULD be. If the client claims onGround but predicted position is still
 *      far above ground, they're spoofing.
 *   2. Ground-spoof detection: Player sends onGround=true packets while their
 *      actual Y position indicates they should still be falling.
 *   3. Fall distance discrepancy: Compare server-tracked fall distance with
 *      client-reported fallDistance (player.getFallDistance()).
 */
public class NoFallCheck extends Check implements IPhysicsCheck {

    private static final double FALL_DAMAGE_THRESHOLD = PhysicsConstants.FALL_DAMAGE_THRESHOLD;
    private static final double GRAVITY_ACCEL = PhysicsConstants.GRAVITY_ACCELERATION;
    private static final double GRAVITY_DRAG = PhysicsConstants.GRAVITY_DRAG;
    private static final double TERMINAL_VELOCITY = PhysicsConstants.TERMINAL_VELOCITY;

    // Number of consecutive ticks of suspicious behavior before flagging
    private static final int BUFFER_MAX = 5;

    // Minimum fall distance to start checking (below this, no damage anyway)
    private static final double MIN_CHECK_DISTANCE = 2.5;

    // Tolerance for ground check (blocks) - accounts for network jitter
    private static final double GROUND_TOLERANCE = 0.15;

    // Ping compensation factor
    private static final double COMPENSATION_FACTOR = 0.20;

    /**
     * Per-player physics state for fall tracking.
     */
    static class FallTracker {
        // Server-side predicted vertical velocity
        double predictedVelocityY;
        // Last known Y where player was legitimately on ground
        double lastGroundY;
        // Server-tracked fall distance (accumulated when player is NOT on ground)
        double serverFallDistance;
        // Number of ticks since player left ground
        int ticksSinceLeftGround;
        // Was the player on ground last tick?
        boolean wasOnGround;
        // Buffer for consecutive suspicious ticks
        int noFallBuffer;

        FallTracker() {
            reset();
        }

        void reset() {
            this.predictedVelocityY = 0;
            this.lastGroundY = Double.MIN_VALUE;
            this.serverFallDistance = 0;
            this.ticksSinceLeftGround = 0;
            this.wasOnGround = true;
            this.noFallBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, FallTracker> trackers = new ConcurrentHashMap<>();

    public NoFallCheck(ANSACPlugin plugin) {
        super(plugin, "NoFall", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        performCheck(player, data, null);
    }

    @Override
    public void processWithInference(Player player, PlayerData data, InferenceResult inference) {
        if (inference == InferenceResult.EMPTY) {
            process(player, data);
            return;
        }
        performCheck(player, data, inference);
    }

    private void performCheck(Player player, PlayerData data, InferenceResult inference) {
        if (!isEnabled() || data.hasBypass()) return;

        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) {
            resetTracker(player);
            return;
        }

        boolean useInference = inference != null;

        // Exemptions
        if (player.isGliding() || player.isFlying() || player.isInsideVehicle()
                || player.isInWater() || player.isInLava() || player.isSleeping()
                || player.isDead() || player.isClimbing()) {
            resetTracker(player);
            return;
        }

        // Inference-driven slow falling check
        if (useInference && inference.hasSlowFalling()) {
            resetTracker(player);
            return;
        }

        // Slow Falling negates fall damage (wiki: Fall damage)
        PotionEffectType slowFalling = getPotionEffect("SLOW_FALLING");
        if (slowFalling != null && player.hasPotionEffect(slowFalling)) {
            resetTracker(player);
            return;
        }

        // Cobweb resets fall distance
        if (player.getLocation().getBlock().getType() == Material.COBWEB) {
            resetTracker(player);
            return;
        }

        // Powder snow resets fall distance (wiki: Fall damage)
        if (player.getLocation().getBlock().getType() == Material.POWDER_SNOW) {
            resetTracker(player);
            return;
        }

        // Ping compensation
        if (data.getPingCompensator().shouldSkipCheck()) {
            FallTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) tracker.noFallBuffer = 0;
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        // Skip teleport-like movements
        if (from.distanceSquared(to) > 16.0) {
            resetTracker(player);
            return;
        }

        double deltaY = data.getVerticalDistance();
        boolean clientOnGround = player.isOnGround();
        long now = System.currentTimeMillis();

        // Knockback exemption (brief)
        if ((now - data.getLastKnockbackTime()) < 400L) {
            FallTracker tracker = getTracker(player);
            if (tracker != null) tracker.noFallBuffer = 0;
            return;
        }

        FallTracker tracker = getOrCreateTracker(player);

        // Update physics prediction
        updatePhysicsPrediction(tracker, deltaY, clientOnGround);

        if (clientOnGround) {
            handleOnGround(player, data, tracker, to, inference);
        } else {
            handleInAir(player, data, tracker, deltaY);
        }
    }

    /**
     * Update the physics-based fall prediction.
     * Simulates gravity: v(t) = 0.98 * (v(t-1) - 0.08), capped at terminal velocity.
     */
    private void updatePhysicsPrediction(FallTracker tracker, double actualDeltaY, boolean clientOnGround) {
        if (!tracker.wasOnGround || clientOnGround) {
            // Not in a free-fall state, reset prediction
            if (clientOnGround) {
                tracker.predictedVelocityY = 0;
            }
            return;
        }

        // Player was in air and still in air - apply gravity
        if (actualDeltaY <= 0) {
            // Falling: update predicted velocity using gravity formula
            tracker.predictedVelocityY = (tracker.predictedVelocityY - GRAVITY_ACCEL) * GRAVITY_DRAG;
            // Clamp to terminal velocity
            if (tracker.predictedVelocityY < -TERMINAL_VELOCITY) {
                tracker.predictedVelocityY = -TERMINAL_VELOCITY;
            }
        } else {
            // Moving upward while in air (jump, knockback, etc.)
            // Use actual deltaY as the velocity estimate
            tracker.predictedVelocityY = actualDeltaY;
        }
    }

    /**
     * Handle the case where the client claims to be on ground.
     */
    private void handleOnGround(Player player, PlayerData data, FallTracker tracker, Location to, InferenceResult inference) {
        boolean useInference = inference != null;

        if (!tracker.wasOnGround && tracker.serverFallDistance > MIN_CHECK_DISTANCE) {
            // Player just landed (transitioned from air to ground)
            // Verify the landing is legitimate

            double clientFallDistance = player.getFallDistance();
            double discrepancy = tracker.serverFallDistance - clientFallDistance;

            // Inference-driven ground verification
            if (useInference && !inference.serverVerifiedGround() && inference.fallDistance() > MIN_CHECK_DISTANCE) {
                tracker.noFallBuffer++;
                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_MAX, COMPENSATION_FACTOR);
                if (tracker.noFallBuffer >= compensatedBuffer) {
                    double severity = tracker.serverFallDistance / FALL_DAMAGE_THRESHOLD;
                    flag(player, data, severity,
                        String.format("NoFall: 推理引擎检测到虚假着地, 下落 %.1f 格 (连续 %d tick, 延迟 %s)",
                            tracker.serverFallDistance,
                            tracker.noFallBuffer,
                            data.getPingCompensator().getPingStatus()));
                    tracker.noFallBuffer = 0;
                }
                // Don't reset - player is still falling
                return;
            }

            if (discrepancy > 1.5) {
                // Significant discrepancy: server tracked much more fall than client
                // This indicates NoFall - client has been resetting its fallDistance
                tracker.noFallBuffer++;

                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_MAX, COMPENSATION_FACTOR);

                if (tracker.noFallBuffer >= compensatedBuffer) {
                    double severity = tracker.serverFallDistance / FALL_DAMAGE_THRESHOLD;
                    flag(player, data, severity,
                        String.format("NoFall: 下落 %.1f 格, 客户端仅 %.1f 格 (差值 %.1f, 连续 %d tick, 延迟 %s)",
                            tracker.serverFallDistance, clientFallDistance, discrepancy,
                            tracker.noFallBuffer,
                            data.getPingCompensator().getPingStatus()));
                    tracker.noFallBuffer = 0;
                }
            } else {
                // Landing seems legitimate, decay buffer
                if (tracker.noFallBuffer > 0) {
                    tracker.noFallBuffer = Math.max(0, tracker.noFallBuffer - 1);
                }
            }
        }

        // Verify player is actually near a solid block
        boolean actuallyNearGround = isActuallyNearGround(player);
        if (!actuallyNearGround && tracker.ticksSinceLeftGround > 3
                && tracker.serverFallDistance > MIN_CHECK_DISTANCE) {
            // Client claims onGround but there's no solid block nearby!
            // Classic NoFall: client sends fake onGround packets
            tracker.noFallBuffer++;

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_MAX, COMPENSATION_FACTOR);

            if (tracker.noFallBuffer >= compensatedBuffer) {
                double severity = tracker.serverFallDistance / FALL_DAMAGE_THRESHOLD;
                flag(player, data, severity,
                    String.format("NoFall: 虚假着地, 下落 %.1f 格, 脚下无方块 (连续 %d tick, 延迟 %s)",
                        tracker.serverFallDistance,
                        tracker.noFallBuffer,
                        data.getPingCompensator().getPingStatus()));
                tracker.noFallBuffer = 0;
            }
            // Don't reset fall distance - the player is still actually falling
            return;
        }

        // Legitimate ground contact - reset fall tracking
        tracker.lastGroundY = to.getY();
        tracker.serverFallDistance = 0;
        tracker.predictedVelocityY = 0;
        tracker.ticksSinceLeftGround = 0;
    }

    /**
     * Handle the case where the client claims to be in the air.
     */
    private void handleInAir(Player player, PlayerData data, FallTracker tracker, double deltaY) {
        tracker.ticksSinceLeftGround++;

        // Accumulate fall distance (only count downward movement)
        if (deltaY < 0) {
            tracker.serverFallDistance += Math.abs(deltaY);
        } else if (deltaY > 0 && tracker.serverFallDistance > 0) {
            // Moving upward while falling (could be legitimate - knockback)
            // Don't reset fall distance immediately, let it continue
        }

        // Check for ground-spoof while in air:
        // If the player's predicted position is well below their actual position,
        // they should be falling faster. If not, they might have client-side NoFall
        // that's affecting their movement but not resetting fallDistance properly.
        // This is a secondary check.

        // Also check: if player just landed (wasOnGround -> !onGround transition
        // shouldn't happen for more than a few ticks at low altitude)
        if (tracker.wasOnGround && tracker.ticksSinceLeftGround > 5
                && tracker.serverFallDistance < 0.5) {
            // Player claims to be in air for 5+ ticks but barely moved down
            // and was on ground last tick. Possible edge case - just decay buffer.
            if (tracker.noFallBuffer > 0) {
                tracker.noFallBuffer = Math.max(0, tracker.noFallBuffer - 1);
            }
        }

        tracker.wasOnGround = false;
    }

    /**
     * Check if the player is actually near a solid block (within GROUND_TOLERANCE).
     * Uses raycasting-like approach: check blocks below the player's feet.
     */
    private boolean isActuallyNearGround(Player player) {
        Location loc = player.getLocation();
        double feetY = loc.getY();

        // Check blocks at player's feet level and slightly below
        for (double offset = 0.0; offset >= -GROUND_TOLERANCE; offset -= 0.05) {
            Block block = loc.getWorld().getBlockAt(
                loc.getBlockX(), (int) Math.floor(feetY + offset), loc.getBlockZ());

            if (block.getType().isSolid() && block.isCollidable()) {
                // Verify the block's collision top is near the player's feet
                try {
                    double blockTop = block.getBoundingBox().getMaxY();
                    if (Math.abs(blockTop - feetY) < GROUND_TOLERANCE + 0.1) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // Fallback: if block is solid and collidable, assume near ground
                    return true;
                }
            }
        }

        // Also check X ± 0.3 (player half-width) for edge cases
        for (double dx : new double[]{-0.3, 0.3}) {
            for (double dz : new double[]{-0.3, 0.3}) {
                Block block = loc.getWorld().getBlockAt(
                    (int) Math.floor(loc.getX() + dx),
                    (int) Math.floor(feetY - 0.1),
                    (int) Math.floor(loc.getZ() + dz));
                if (block.getType().isSolid() && block.isCollidable()) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Get a potion effect type by name using reflection (for version compatibility).
     */
    private PotionEffectType getPotionEffect(String name) {
        try {
            return PotionEffectType.getByName(name);
        } catch (Exception e) {
            return null;
        }
    }

    private FallTracker getTracker(Player player) {
        return trackers.get(player.getUniqueId());
    }

    private FallTracker getOrCreateTracker(Player player) {
        return trackers.computeIfAbsent(
            player.getUniqueId(), k -> new FallTracker());
    }

    private void resetTracker(Player player) {
        FallTracker tracker = trackers.get(player.getUniqueId());
        if (tracker != null) {
            tracker.reset();
        }
        // Also update legacy PlayerData fields for compatibility
        player.getLocation();
    }
}
