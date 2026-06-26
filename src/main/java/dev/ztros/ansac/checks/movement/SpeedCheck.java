package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.physics.IPhysicsCheck;
import dev.ztros.ansac.physics.InferenceResult;
import dev.ztros.ansac.physics.PhysicsConstants;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.util.ServerVersionAdapter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speed check - detects abnormal horizontal movement speed.
 *
 * Physics-based prediction model (Minecraft 1.21.x):
 *   Walk speed:     0.21585 blocks/tick (wiki: 4.317 m/s)
 *   Sprint speed:   0.2806  blocks/tick (wiki: 5.612 m/s)
 *   Sprint-jump:    0.35635 blocks/tick (wiki: 7.127 m/s)
 *   Ice multiplier: 1.4x (estimate, wiki only documents boat speeds: 40/72.73 m/s)
 *   Blue ice:       1.6x (estimate, player physics differs from boats)
 *   Speed potion:   base * (1 + 0.2 * level)
 *   Soul Speed:     speed *= (1.3 + level * 0.105) (wiki: I=+40.5%, II=+51%, III=+61.5%)
 *   Dolphin Grace:  1.75x (wiki: 9.8 m/s underwater, vs sprint 5.612 m/s)
 *
 * Key design: physics-based jump tracking eliminates sprint-jump false positives.
 * When a player jumps, horizontal speed in air should not exceed takeoff speed
 * (no horizontal acceleration in air in vanilla MC).
 */
public class SpeedCheck extends Check implements IPhysicsCheck {

    // Base speed constants - now delegated to PhysicsConstants
    private static final double BASE_WALK = PhysicsConstants.BASE_WALK_SPEED;
    private static final double BASE_SPRINT = PhysicsConstants.BASE_SPRINT_SPEED;
    private static final double BASE_SPRINT_JUMP = PhysicsConstants.BASE_SPRINT_JUMP_SPEED;

    private static final double ICE_MULTIPLIER = PhysicsConstants.ICE_SPEED_MULTIPLIER;
    private static final double BLUE_ICE_MULTIPLIER = PhysicsConstants.BLUE_ICE_SPEED_MULTIPLIER;

    private static final double SOUL_SPEED_BASE_MULT = PhysicsConstants.SOUL_SPEED_BASE_MULTIPLIER;
    private static final double SOUL_SPEED_PER_LEVEL_MULT = PhysicsConstants.SOUL_SPEED_PER_LEVEL;

    private static final double DOLPHIN_GRACE_MULTIPLIER = PhysicsConstants.DOLPHINS_GRACE_MULTIPLIER;

    // Detection thresholds
    private static final double LENIENCY = 0.08;
    private static final int BUFFER_MAX = 8;

    // Jump detection
    private static final double JUMP_DETECTION_DELTA_Y = 0.12; // Jump starts when dy > 0.12
    private static final int JUMP_MAX_TICKS = 25; // Maximum jump cycle (1.25s)
    private static final double AIR_SPEED_TOLERANCE = 1.12; // 12% tolerance in air

    // Strafe detection thresholds
    private static final int STRAFE_HIGH_SPEED_THRESHOLD_TICKS = 40;
    private static final double STRAFE_SPEED_RATIO = 0.9;
    private static final double STRAFE_AVG_SPEED_RATIO = 0.95;

    private final ConcurrentHashMap<UUID, PhysicsTracker> trackers = new ConcurrentHashMap<>();

    public SpeedCheck(ANSACPlugin plugin) {
        super(plugin, "Speed", "Movement");
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
        if (shouldSkip(player)) return;

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        double horizontalDist = data.getHorizontalDistance();
        boolean onGround = player.isOnGround();
        double deltaY = data.getVerticalDistance();
        long now = System.currentTimeMillis();

        // Teleport detection - large position jump
        double teleportDistSq = from.distanceSquared(to);
        if (teleportDistSq > 16.0) { // 4 blocks squared
            resetTracker(player.getUniqueId());
            data.setSpeedBuffer(0);
            return;
        }

        // Not moving significantly
        if (horizontalDist < 0.03) {
            data.setSpeedBuffer(0);
            return;
        }

        // Ping compensation
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setSpeedBuffer(0);
            return;
        }

        UUID uuid = player.getUniqueId();
        PhysicsTracker tracker = trackers.computeIfAbsent(uuid, k -> new PhysicsTracker());

        // === Physics-based jump detection ===
        // Jump start: was on ground / near ground, now airborne, moving upward
        boolean justJumped = tracker.wasOnGround && !onGround && deltaY > JUMP_DETECTION_DELTA_Y;

        if (justJumped) {
            tracker.isJumping = true;
            tracker.jumpStartTime = now;
            tracker.jumpTakeoffSpeed = horizontalDist;
            tracker.jumpTickCount = 0;
            tracker.wasOnGround = onGround;
            data.setSpeedBuffer(0);
            return; // Skip detection on the jump tick
        }

        // Update jump state
        if (tracker.isJumping) {
            tracker.jumpTickCount++;
            // Jump ends: back on ground, exceeded max time, or started falling fast after apex
            boolean fallingAfterApex = tracker.jumpTickCount > 8 && deltaY < -0.25;
            if (onGround || tracker.jumpTickCount > JUMP_MAX_TICKS || fallingAfterApex) {
                tracker.isJumping = false;
                tracker.jumpTickCount = 0;
            }
        }

        // === Compute expected maximum speed ===
        double expected;
        boolean useInference = inference != null;

        if (useInference && inference.expectedMaxHorizontalSpeed() > 0) {
            // Use physics engine computed expected speed
            expected = inference.expectedMaxHorizontalSpeed();
        } else if (tracker.isJumping) {
            // In air: horizontal speed should not exceed takeoff speed
            expected = tracker.jumpTakeoffSpeed * AIR_SPEED_TOLERANCE;

            // Ensure minimum threshold - sprint-jump with speed potion
            double minAirSpeed = BASE_SPRINT_JUMP * getSpeedPotionMultiplier(player);
            expected = Math.max(expected, minAirSpeed);

            // If player has Jump Boost, they stay in air longer
            PotionEffectType jumpBoost = ServerVersionAdapter.getJumpBoost();
            if (jumpBoost != null && player.hasPotionEffect(jumpBoost)) {
                int level = player.getPotionEffect(jumpBoost).getAmplifier() + 1;
                expected += 0.03 * level;
            }

        } else {
            // On ground: normal speed calculation
            expected = getExpectedMaxSpeed(player);
        }

        // Apply ping compensation
        expected = data.getPingCompensator().getCompensatedSpeed(
            expected, PingCompensator.COMPENSATION_SPEED);

        // Knockback exemption (1 second)
        if ((now - data.getLastKnockbackTime()) < 1000L) {
            data.setSpeedBuffer(0);
            tracker.wasOnGround = onGround;
            return;
        }

        // Damage ticks = possible knockback
        if (player.getNoDamageTicks() > 0) {
            expected += 0.5;
        }

        // === Detection ===
        double compensatedLeniency = data.getPingCompensator().getCompensatedThreshold(
            LENIENCY, PingCompensator.COMPENSATION_SPEED);
        int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
            BUFFER_MAX, PingCompensator.COMPENSATION_SPEED);

        boolean isSpeeding;
        if (useInference && inference.getSpeedDeviationRatio() > 0) {
            // Inference-driven detection: use deviation ratio
            isSpeeding = inference.getSpeedDeviationRatio() > 1.15; // 15% over expected
        } else {
            isSpeeding = horizontalDist > expected + compensatedLeniency;
        }

        if (isSpeeding) {
            int buffer = data.getSpeedBuffer() + 1;
            data.setSpeedBuffer(buffer);
            if (buffer >= compensatedBuffer) {
                double severity = horizontalDist / expected;
                String phase = tracker.isJumping ? "空中" : "地面";
                flag(player, data, severity,
                    String.format("速度异常(%s): %.3f / %.3f (连续%d tick, 延迟%s)",
                        phase, horizontalDist, expected, buffer,
                        data.getPingCompensator().getPingStatus()));
            }
        } else {
            // Decay buffer on good behavior
            data.setSpeedBuffer(Math.max(0, data.getSpeedBuffer() - 1));
        }

        // --- Layer 2: Strafe detection ---
        checkStrafe(player, data, horizontalDist, expected, tracker);

        tracker.wasOnGround = onGround;
    }

    /**
     * Strafe detection - detects sustained abnormally consistent high speed.
     */
    private void checkStrafe(Player player, PlayerData data, double horizontalDist,
                              double expected, PhysicsTracker tracker) {
        tracker.strafeTickCount++;
        tracker.strafeTotalDistance += horizontalDist;

        if (horizontalDist > expected * STRAFE_SPEED_RATIO) {
            tracker.strafeHighSpeedTicks++;
        } else {
            tracker.strafeHighSpeedTicks = 0;
            tracker.strafeTotalDistance = 0;
            tracker.strafeTickCount = 0;
            return;
        }

        if (tracker.strafeHighSpeedTicks > STRAFE_HIGH_SPEED_THRESHOLD_TICKS) {
            double avgSpeed = tracker.strafeTotalDistance / tracker.strafeTickCount;
            if (avgSpeed > expected * STRAFE_AVG_SPEED_RATIO) {
                double severity = avgSpeed / expected;
                flag(player, data, severity,
                    String.format("持续高速移动(Strafe): 平均=%.3f / 预期=%.3f (持续%d tick)",
                        avgSpeed, expected, tracker.strafeHighSpeedTicks));
                // Reset after flag
                tracker.strafeHighSpeedTicks = 0;
                tracker.strafeTotalDistance = 0;
                tracker.strafeTickCount = 0;
            }
        }
    }

    private void resetTracker(UUID uuid) {
        trackers.remove(uuid);
    }

    public void onPlayerQuit(UUID uuid) {
        trackers.remove(uuid);
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isFlying() || player.isInsideVehicle() || player.isGliding()
            || player.isSleeping() || player.isDead();
    }

    /**
     * Calculate expected maximum horizontal speed on ground.
     */
    private double getExpectedMaxSpeed(Player player) {
        double speed = BASE_WALK;

        if (player.isSprinting()) {
            speed = BASE_SPRINT;
        }

        // Speed potion
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            speed *= (1.0 + 0.2 * level);
        }

        // Dolphin's Grace (water only)
        PotionEffectType dolphinsGrace = ServerVersionAdapter.getDolphinsGrace();
        if (dolphinsGrace != null && player.hasPotionEffect(dolphinsGrace) && player.isInWater()) {
            speed *= DOLPHIN_GRACE_MULTIPLIER;
        }

        // Soul Speed - wiki: multiplier = 1.3 + level * 0.105
        PotionEffectType soulSpeed = ServerVersionAdapter.getSoulSpeed();
        if (soulSpeed != null && player.hasPotionEffect(soulSpeed)) {
            int level = player.getPotionEffect(soulSpeed).getAmplifier() + 1;
            speed *= (SOUL_SPEED_BASE_MULT + level * SOUL_SPEED_PER_LEVEL_MULT);
        }

        // Ice surfaces
        if (isOnBlueIce(player)) {
            speed *= BLUE_ICE_MULTIPLIER;
        } else if (isOnIce(player)) {
            speed *= ICE_MULTIPLIER;
        }

        // Sneaking
        if (player.isSneaking()) {
            speed *= 0.3;
        }

        // Blocking / using item
        if (player.isBlocking() || player.isHandRaised()) {
            speed *= 0.2;
        }

        // Cobweb
        if (player.getLocation().getBlock().getType().name().contains("COBWEB")) {
            speed *= 0.05;
        }

        return speed;
    }

    /**
     * Get speed potion multiplier only (for air speed minimum).
     */
    private double getSpeedPotionMultiplier(Player player) {
        double mult = 1.0;
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            mult *= (1.0 + 0.2 * level);
        }
        return mult;
    }

    private boolean isOnIce(Player player) {
        Location loc = player.getLocation().clone().subtract(0, 1, 0);
        String type = loc.getBlock().getType().name();
        return type.contains("ICE") && !type.contains("BLUE");
    }

    private boolean isOnBlueIce(Player player) {
        Location loc = player.getLocation().clone().subtract(0, 1, 0);
        return loc.getBlock().getType().name().contains("BLUE_ICE");
    }

    /**
     * Physics tracker for per-player movement state.
     */
    private static class PhysicsTracker {
        boolean wasOnGround = true;     // Previous tick ground state
        boolean isJumping = false;       // Currently in jump cycle
        long jumpStartTime = 0;          // Jump start timestamp
        double jumpTakeoffSpeed = 0.0;   // Horizontal speed at jump takeoff
        int jumpTickCount = 0;           // Ticks since jump start

        // Strafe tracking
        int strafeHighSpeedTicks = 0;
        double strafeTotalDistance = 0.0;
        int strafeTickCount = 0;
    }
}
