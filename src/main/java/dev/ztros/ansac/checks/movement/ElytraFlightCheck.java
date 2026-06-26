package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.physics.IPhysicsCheck;
import dev.ztros.ansac.physics.InferenceResult;
import dev.ztros.ansac.physics.PhysicsConstants;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ElytraFlight check - detects illegal elytra flight hacks.
 *
 * Minecraft Elytra Physics (1.21.x):
 *   Pitch -90° (nose dive): max ~3.365 b/t (67.3 m/s)
 *   Pitch -45° (dive):      ~2.5 b/t
 *   Pitch 0° (level):       ~1.5 b/t (30 m/s) - equilibrium speed
 *   Pitch +30° (climb):     min ~0.36 b/t (7.2 m/s) - stall threshold
 *   Firework boost:         +1.675 b/t (33.5 m/s, wiki: minecraft.wiki/w/Firework_rocket)
 *   Horizontal friction:    0.99 per tick (speed decays slowly)
 *   Glide ratio:            ~10:1 horizontal:vertical
 *
 * Key insight: LEGITIMATE elytra flight is NEVER linear.
 * The trajectory is always a curve determined by pitch angle.
 * ElytraFly hacks (Meteor ElytraFly Packet/Control mode, Wurst ExtraElytra)
 * fly in perfectly straight lines at constant speed, violating physics.
 *
 * Detects:
 * 1. Hover: gliding but barely moving
 * 2. Instant stop: sudden stop without collision
 * 3. Speed hack: exceeding pitch-corrected maximum speed
 * 4. Pitch-speed mismatch: speed inconsistent with pitch angle
 * 5. Linear trajectory: perfectly straight flight (no natural wobble)
 * 6. Speed constancy: speed unchanged for too many ticks
 */
public class ElytraFlightCheck extends Check implements IPhysicsCheck {

    private static final double HOVER_SPEED_THRESHOLD = 0.15;
    private static final int HOVER_BUFFER_MAX = 10;

    private static final double MAX_GLIDE_SPEED = PhysicsConstants.ELYTRA_MAX_LEVEL_SPEED;
    private static final double FIREWORK_MAX_SPEED = PhysicsConstants.ELYTRA_FIREWORK_BOOST;
    private static final double MAX_DIVE_SPEED = PhysicsConstants.ELYTRA_MAX_DIVE_SPEED;
    private static final double MIN_CLIMB_SPEED = PhysicsConstants.ELYTRA_MIN_CLIMB_SPEED;
    private static final double GLIDE_FRICTION = PhysicsConstants.ELYTRA_FRICTION;
    private static final double MIN_DECEL_RATE = 0.005;
    private static final int STOP_BUFFER_MAX = 5;
    private static final double STOP_SPEED_THRESHOLD = 0.05;
    private static final double BOOST_DECEL_EXEMPT = 0.5;

    // --- Physics-based detection thresholds ---
    private static final double PITCH_SPEED_TOLERANCE = 0.25;  // ±0.25 b/t tolerance
    private static final int PITCH_SPEED_BUFFER_MAX = 8;
    private static final int LINEAR_TRAJECTORY_TICKS = 20;   // 20 ticks straight = suspicious
    private static final double LINEAR_ANGLE_THRESHOLD = 0.5; // yaw/pitch change < 0.5°
    private static final int SPEED_CONSTANCY_TICKS = 15;      // 15 ticks same speed = suspicious
    private static final double SPEED_CONSTANCY_STD_DEV = 0.02;

    private final ConcurrentHashMap<UUID, ElytraTracker> trackers = new ConcurrentHashMap<>();

    public ElytraFlightCheck(ANSACPlugin plugin) {
        super(plugin, "ElytraFlight", "Movement");
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

        boolean useInference = inference != null;

        if (data.getPingCompensator().shouldSkipCheck()) {
            resetBuffers(data);
            return;
        }

        // Inference-driven gliding check
        if (useInference && !inference.isGliding()) {
            resetBuffers(data);
            resetTracker(player.getUniqueId());
            return;
        }

        if (!player.isGliding()) {
            resetBuffers(data);
            resetTracker(player.getUniqueId());
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        double horizontalSpeed = data.getHorizontalDistance();
        double verticalSpeed = data.getVerticalDistance();
        float pitch = player.getLocation().getPitch();   // -90 (down) to +90 (up)
        float yaw = player.getLocation().getYaw();
        long now = System.currentTimeMillis();

        UUID uuid = player.getUniqueId();
        ElytraTracker tracker = trackers.computeIfAbsent(uuid, k -> new ElytraTracker());

        // Ping-compensated thresholds
        double compensatedHoverThreshold = data.getPingCompensator().getCompensatedThreshold(
            HOVER_SPEED_THRESHOLD, PingCompensator.COMPENSATION_ELYTRA);
        int compensatedHoverBuffer = data.getPingCompensator().getCompensatedBuffer(
            HOVER_BUFFER_MAX, PingCompensator.COMPENSATION_ELYTRA);
        int compensatedStopBuffer = data.getPingCompensator().getCompensatedBuffer(
            STOP_BUFFER_MAX, PingCompensator.COMPENSATION_ELYTRA);
        double compensatedMaxSpeed = data.getPingCompensator().getCompensatedSpeed(
            MAX_GLIDE_SPEED, PingCompensator.COMPENSATION_ELYTRA);
        double compensatedFireworkSpeed = data.getPingCompensator().getCompensatedSpeed(
            FIREWORK_MAX_SPEED, PingCompensator.COMPENSATION_ELYTRA);

        // --- Check 1: Elytra hover ---
        if (horizontalSpeed < compensatedHoverThreshold && Math.abs(verticalSpeed) < 0.05) {
            int buffer = data.getElytraHoverBuffer() + 1;
            data.setElytraHoverBuffer(buffer);
            if (buffer >= compensatedHoverBuffer) {
                flag(player, data, 1.5,
                    "鞘翅空中悬停（连续 " + buffer + " tick，水平速度: "
                    + String.format("%.3f", horizontalSpeed)
                    + "，延迟 " + data.getPingCompensator().getPingStatus() + "）");
            }
        } else {
            data.setElytraHoverBuffer(0);
        }

        // --- Check 2: Instant stop ---
        double lastSpeed = data.getLastGlideSpeed();
        if (lastSpeed > compensatedMaxSpeed * 0.5) {
            if (horizontalSpeed < STOP_SPEED_THRESHOLD) {
                if (!didCollide(player)) {
                    int buffer = data.getElytraStopBuffer() + 1;
                    data.setElytraStopBuffer(buffer);
                    if (buffer >= compensatedStopBuffer) {
                        flag(player, data, 1.8,
                            "鞘翅瞬间停止（从 " + String.format("%.2f", lastSpeed)
                            + " 突然降至 " + String.format("%.3f", horizontalSpeed)
                            + "，未检测到碰撞，连续 " + buffer + " tick，延迟 "
                            + data.getPingCompensator().getPingStatus() + "）");
                    }
                } else {
                    data.setElytraStopBuffer(0);
                }
            } else {
                data.setElytraStopBuffer(0);
            }
        } else {
            data.setElytraStopBuffer(0);
        }

        // --- Check 3: Speed hack (raw max) ---
        boolean recentlyBoosted = lastSpeed > BOOST_DECEL_EXEMPT;
        double effectiveMax = recentlyBoosted ? compensatedFireworkSpeed : compensatedMaxSpeed;
        if (horizontalSpeed > effectiveMax) {
            // Pitch-corrected check: if diving, allow higher speed
            double pitchCorrectedMax = getPitchCorrectedMaxSpeed(pitch);
            if (horizontalSpeed > pitchCorrectedMax + PITCH_SPEED_TOLERANCE) {
                double severity = horizontalSpeed / pitchCorrectedMax;
                flag(player, data, severity,
                    String.format("鞘翅速度异常: %.3f > %.2f (pitch=%.1f°, %s, 延迟 %s)",
                        horizontalSpeed, pitchCorrectedMax, pitch,
                        recentlyBoosted ? "烟花加速中" : "正常滑翔",
                        data.getPingCompensator().getPingStatus()));
            }
        }

        // --- Check 4: Pitch-speed mismatch ---
        // Core physics check: speed MUST correlate with pitch angle
        checkPitchSpeedMismatch(player, data, horizontalSpeed, pitch, tracker);

        // --- Check 5: Linear trajectory (perfectly straight flight) ---
        checkLinearTrajectory(player, data, yaw, pitch, horizontalSpeed, tracker);

        // --- Check 6: Speed constancy (unnaturally constant speed) ---
        checkSpeedConstancy(player, data, horizontalSpeed, tracker);

        // Update tracking state
        tracker.lastYaw = yaw;
        tracker.lastPitch = pitch;
        tracker.lastSpeed = horizontalSpeed;
        tracker.lastTime = now;
        data.setLastGlideSpeed(horizontalSpeed);
    }

    /**
     * Check 4: Pitch-speed mismatch.
     * In vanilla MC, pitch angle directly determines glide speed:
     *   pitch = -90° → speed ~3.365
     *   pitch = -45° → speed ~2.5
     *   pitch =   0° → speed ~1.5
     *   pitch = +30° → speed ~0.36 (stall)
     * If actual speed deviates significantly from pitch-predicted speed, flag.
     */
    private void checkPitchSpeedMismatch(Player player, PlayerData data,
                                          double horizontalSpeed, float pitch,
                                          ElytraTracker tracker) {
        // Only check when moving at meaningful speed
        if (horizontalSpeed < 0.1) return;

        // Compute expected speed from pitch using simplified glide physics
        double expectedSpeed = computeExpectedSpeedFromPitch(pitch);

        // Apply tolerance
        double tolerance = PITCH_SPEED_TOLERANCE;
        // Pitch near 0 (level flight) has more variance
        if (Math.abs(pitch) < 10) tolerance = 0.35;
        // Steep dives also have more variance
        if (pitch < -60) tolerance = 0.4;

        double deviation = Math.abs(horizontalSpeed - expectedSpeed);

        if (deviation > tolerance) {
            tracker.pitchMismatchBuffer++;
            if (tracker.pitchMismatchBuffer >= PITCH_SPEED_BUFFER_MAX) {
                double severity = deviation / tolerance;
                flag(player, data, severity,
                    String.format("鞘翅俯角-速度不匹配: 实际=%.3f 预期=%.3f (pitch=%.1f°, 偏差=%.3f, 连续%d tick)",
                        horizontalSpeed, expectedSpeed, pitch, deviation,
                        tracker.pitchMismatchBuffer));
                tracker.pitchMismatchBuffer = 0;
            }
        } else {
            tracker.pitchMismatchBuffer = Math.max(0, tracker.pitchMismatchBuffer - 1);
        }
    }

    /**
     * Check 5: Linear trajectory detection (OBSERVATION ONLY - no VL added).
     * Legitimate elytra flight ALWAYS has some natural wobble in yaw/pitch.
     * ElytraFly automation flies in perfectly straight lines.
     *
     * Note: This check only alerts staff, does NOT increase violation level.
     * Straight flight alone is low-confidence; combined with speed constancy
     * (Check 6) it becomes high-confidence.
     */
    private void checkLinearTrajectory(Player player, PlayerData data,
                                        float yaw, float pitch, double horizontalSpeed,
                                        ElytraTracker tracker) {
        // Only check when moving at decent speed
        if (horizontalSpeed < 0.3) {
            tracker.linearTicks = 0;
            return;
        }

        float yawDelta = Math.abs(normalizeAngle(yaw - tracker.lastYaw));
        float pitchDelta = Math.abs(pitch - tracker.lastPitch);

        // Both yaw and pitch change very little = suspiciously straight
        if (yawDelta < LINEAR_ANGLE_THRESHOLD && pitchDelta < LINEAR_ANGLE_THRESHOLD) {
            tracker.linearTicks++;
            if (tracker.linearTicks >= LINEAR_TRAJECTORY_TICKS) {
                // Alert-only: no VL increase, just staff notification
                flagAlertOnly(player, data,
                    String.format("鞘翅轨迹过于直线: yaw变化=%.2f° pitch变化=%.2f° (连续%d tick, 速度=%.3f)",
                        yawDelta, pitchDelta, tracker.linearTicks, horizontalSpeed));
                tracker.linearTicks = 0;
            }
        } else {
            tracker.linearTicks = 0;
        }
    }

    /**
     * Check 6: Speed constancy detection.
     * Natural elytra speed fluctuates due to minor pitch changes and air physics.
     * ElytraFly maintains unnaturally constant speed.
     */
    private void checkSpeedConstancy(Player player, PlayerData data,
                                      double horizontalSpeed, ElytraTracker tracker) {
        if (horizontalSpeed < 0.3) {
            tracker.speedSamples.clear();
            return;
        }

        tracker.speedSamples.add(horizontalSpeed);
        if (tracker.speedSamples.size() > SPEED_CONSTANCY_TICKS) {
            tracker.speedSamples.remove(0);
        }

        if (tracker.speedSamples.size() >= SPEED_CONSTANCY_TICKS) {
            double stdDev = computeStdDev(tracker.speedSamples);
            if (stdDev < SPEED_CONSTANCY_STD_DEV) {
                flag(player, data, 1.4,
                    String.format("鞘翅速度过于恒定: 标准差=%.4f (连续%d tick, 平均=%.3f)",
                        stdDev, tracker.speedSamples.size(),
                        tracker.speedSamples.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
                tracker.speedSamples.clear();
            }
        }
    }

    /**
     * Compute expected horizontal speed from pitch angle.
     * Simplified model based on vanilla elytra physics.
     */
    private double computeExpectedSpeedFromPitch(float pitch) {
        // Normalize pitch: -90 to +90
        // At 0° (level): equilibrium speed ~1.5
        // At -90° (dive): max ~3.365
        // At +30° (climb): min ~0.36 (stall)
        // At +30°+ : player loses altitude fast, speed drops

        if (pitch <= -90f) return MAX_DIVE_SPEED;
        if (pitch >= 30f) return MIN_CLIMB_SPEED;

        // Piecewise linear approximation of the pitch-speed curve
        if (pitch < 0f) {
            // Diving: -90 to 0
            // -90° → 3.365, 0° → 1.5
            double t = (-pitch) / 90.0; // 0 to 1
            return 1.5 + t * (MAX_DIVE_SPEED - 1.5);
        } else {
            // Climbing: 0 to +30
            // 0° → 1.5, +30° → 0.36
            double t = pitch / 30.0; // 0 to 1
            return 1.5 - t * (1.5 - MIN_CLIMB_SPEED);
        }
    }

    /**
     * Get pitch-corrected absolute maximum speed.
     * Used for the raw speed check to allow diving speeds.
     */
    private double getPitchCorrectedMaxSpeed(float pitch) {
        if (pitch < -45f) {
            // Steep dive - allow up to MAX_DIVE_SPEED
            return MAX_DIVE_SPEED;
        } else if (pitch < -20f) {
            // Moderate dive
            return 2.5;
        } else if (pitch < 0f) {
            // Slight dive
            return 2.0;
        } else {
            // Level or climbing
            return MAX_GLIDE_SPEED;
        }
    }

    /**
     * Normalize angle difference to [-180, 180].
     */
    private float normalizeAngle(float angle) {
        while (angle > 180f) angle -= 360f;
        while (angle < -180f) angle += 360f;
        return angle;
    }

    /**
     * Compute standard deviation of a list of doubles.
     */
    private double computeStdDev(java.util.List<Double> samples) {
        if (samples.size() < 2) return 0.0;
        double mean = samples.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double variance = samples.stream()
            .mapToDouble(v -> (v - mean) * (v - mean))
            .average().orElse(0);
        return Math.sqrt(variance);
    }

    private boolean didCollide(Player player) {
        Location loc = player.getLocation();
        if (player.isOnGround()) return true;

        Location below = loc.clone().subtract(0, 0.5, 0);
        if (below.getBlock().getType().isSolid()) return true;

        Vector dir;
        if (player.getVelocity().lengthSquared() > 0.001) {
            dir = player.getVelocity().clone().normalize();
        } else {
            float yaw = loc.getYaw();
            dir = new Vector(
                -Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))
            ).normalize();
        }

        Location feetAhead = loc.clone().add(dir.clone().multiply(0.6));
        Location headAhead = feetAhead.clone().add(0, 1.5, 0);
        if (feetAhead.getBlock().getType().isSolid()) return true;
        if (headAhead.getBlock().getType().isSolid()) return true;
        if (player.isInWater() || player.isInLava()) return true;
        if (loc.getBlock().getType().name().contains("COBWEB")) return true;

        return false;
    }

    private void resetBuffers(PlayerData data) {
        data.setElytraHoverBuffer(0);
        data.setElytraStopBuffer(0);
        data.setLastGlideSpeed(0);
    }

    private void resetTracker(UUID uuid) {
        trackers.remove(uuid);
    }

    @Override
    public void onPlayerQuit(UUID uuid) {
        trackers.remove(uuid);
    }

    /**
     * Per-player elytra tracking state.
     */
    private static class ElytraTracker {
        float lastYaw = 0f;
        float lastPitch = 0f;
        double lastSpeed = 0.0;
        long lastTime = 0;

        int pitchMismatchBuffer = 0;
        int linearTicks = 0;
        java.util.ArrayList<Double> speedSamples = new java.util.ArrayList<>();
    }
}
