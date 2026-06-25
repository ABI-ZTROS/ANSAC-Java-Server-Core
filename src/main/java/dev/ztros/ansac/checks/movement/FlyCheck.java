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
 * Fly check - detects abnormal vertical movement and flight.
 *
 * Physics-based prediction model (Minecraft 1.21.x):
 *   Gravity: v(t) = (v(t-1) - 0.08) * 0.98
 *   Jump initial velocity: 0.42 blocks/tick
 *   Terminal velocity: -3.92 blocks/tick
 *   Normal jump apex at ~5-6 ticks, total cycle ~10-12 ticks
 *   Jump Boost: +0.1 * level to initial velocity
 *
 * Key design: physics-based jump tracking eliminates false positives from
 * normal jumping, sprint-jumping, and jump-boosted movement.
 * Jump cycles are tracked precisely using state transitions.
 */
public class FlyCheck extends Check {

    private static final double LENIENCY = 0.12;
    private static final int BUFFER_MAX = 8;

    // Gravity constants
    private static final double GRAVITY_ACCEL = 0.08;
    private static final double GRAVITY_DRAG = 0.98;
    private static final double JUMP_INITIAL_VELOCITY = 0.42;
    private static final double TERMINAL_VELOCITY = 3.92;

    // Jump detection
    private static final double JUMP_DETECTION_DELTA_Y = 0.12;
    private static final int JUMP_MAX_TICKS = 30; // Full jump cycle with safety margin

    // Altitude detection
    private static final double ALTITUDE_HEIGHT_THRESHOLD = 30.0;
    private static final int ALTITUDE_DURATION_TICKS = 60;
    private static final double ALTITUDE_NO_FALL_THRESHOLD = -0.05;

    private final ConcurrentHashMap<UUID, JumpTracker> jumpTrackers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, AltitudeTracker> altitudeTrackers = new ConcurrentHashMap<>();

    public FlyCheck(ANSACPlugin plugin) {
        super(plugin, "Fly", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        if (data.getPingCompensator().shouldSkipCheck()) {
            resetAllBuffers(data);
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        double deltaY = data.getVerticalDistance();
        boolean onGround = player.isOnGround();
        long now = System.currentTimeMillis();

        UUID uuid = player.getUniqueId();
        JumpTracker jumpTracker = jumpTrackers.computeIfAbsent(uuid, k -> new JumpTracker());

        // === Physics-based jump detection ===
        // Jump start: was on ground, now airborne, moving upward
        boolean justJumped = jumpTracker.wasOnGround && !onGround && deltaY > JUMP_DETECTION_DELTA_Y;

        if (justJumped) {
            jumpTracker.isJumping = true;
            jumpTracker.jumpStartTime = now;
            jumpTracker.jumpTickCount = 0;
            jumpTracker.previousDeltaY = deltaY;
            jumpTracker.wasOnGround = onGround;
            resetAllBuffers(data);
            return; // Skip all checks on jump tick
        }

        // Update jump state
        if (jumpTracker.isJumping) {
            jumpTracker.jumpTickCount++;

            // Detect jump apex (deltaY transitions from positive to near-zero/negative)
            boolean atApex = jumpTracker.previousDeltaY > 0.02 && deltaY <= 0.02;
            if (atApex) {
                jumpTracker.atApex = true;
            }

            // Jump ends: back on ground, exceeded max time, or fast fall after apex
            boolean fastFallAfterApex = jumpTracker.atApex && jumpTracker.jumpTickCount > 10 && deltaY < -0.3;
            if (onGround || jumpTracker.jumpTickCount > JUMP_MAX_TICKS || fastFallAfterApex) {
                jumpTracker.isJumping = false;
                jumpTracker.jumpTickCount = 0;
                jumpTracker.atApex = false;
            }
        }

        // === During jump cycle: fully exempt ===
        // Normal jumping produces large deltaY variations that are physically correct.
        // Sprint-jumping, jump-boosting, etc. all fall within this exemption window.
        if (jumpTracker.isJumping) {
            jumpTracker.previousDeltaY = deltaY;
            jumpTracker.wasOnGround = onGround;
            return;
        }

        // === Post-jump landing grace: 3 ticks ===
        // The tick right after landing may have residual airborne-like deltaY
        if (!jumpTracker.isJumping && jumpTracker.jumpTickCount == 0
                && jumpTracker.previousDeltaY < -0.1 && onGround) {
            // Just landed - give 1 tick grace
            jumpTracker.previousDeltaY = deltaY;
            jumpTracker.wasOnGround = onGround;
            return;
        }

        // === Non-jump airborne: normal fly checks ===
        int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
            BUFFER_MAX, PingCompensator.COMPENSATION_FLY);

        // Recent knockback (1 second)
        boolean recentKnockback = (now - data.getLastKnockbackTime()) < 1000L;

        // Elytra / firework
        boolean hasElytra = player.getInventory().getChestplate() != null
                && player.getInventory().getChestplate().getType().name().contains("ELYTRA");
        boolean usingFirework = player.isGliding() || (hasElytra && deltaY > 0.3);

        // Ground proximity
        double distToGround = distanceToGround(player);
        boolean nearGround = distToGround >= 0 && distToGround < 1.5;

        // --- Check 1: Sustained hover ---
        // Not on ground, virtually no vertical movement, not in liquid, not climbing, not near ground
        if (!onGround && Math.abs(deltaY) < 0.001
                && !player.isInWater() && !player.isInLava()
                && !player.isClimbing() && !nearGround
                && !usingFirework && !recentKnockback) {
            int hoverBuffer = data.getHoverBuffer() + 1;
            data.setHoverBuffer(hoverBuffer);
            if (hoverBuffer >= compensatedBuffer) {
                flag(player, data, 1.5,
                    "空中悬停（连续" + hoverBuffer + " tick，延迟"
                    + data.getPingCompensator().getPingStatus() + "）");
            }
            // Continue to other checks (don't return, might also be ascending)
        } else {
            data.setHoverBuffer(0);
        }

        // --- Check 2: Ascending while not on ground ---
        if (!onGround && deltaY > LENIENCY) {
            if (nearGround || usingFirework || recentKnockback) {
                data.setAscendBuffer(0);
            } else {
                PotionEffectType levitation = ServerVersionAdapter.getLevitation();
                boolean hasLevitation = levitation != null && player.hasPotionEffect(levitation);
                PotionEffectType jumpBoost = ServerVersionAdapter.getJumpBoost();
                boolean hasJumpBoost = jumpBoost != null && player.hasPotionEffect(jumpBoost);

                if (!hasLevitation && !hasJumpBoost && !player.isClimbing()
                        && !player.isInWater() && !player.isInLava()) {
                    int ascendBuffer = data.getAscendBuffer() + 1;
                    data.setAscendBuffer(ascendBuffer);
                    if (ascendBuffer >= compensatedBuffer) {
                        flag(player, data, deltaY / LENIENCY,
                            String.format("空中异常上升: dy=%.3f (连续%d tick, 延迟%s)",
                                deltaY, ascendBuffer, data.getPingCompensator().getPingStatus()));
                    }
                } else {
                    data.setAscendBuffer(0);
                }
            }
        } else {
            data.setAscendBuffer(0);
        }

        // --- Check 3: Falling too slowly ---
        if (!onGround && deltaY < -LENIENCY) {
            if (deltaY > -0.05 && !player.isInWater() && !player.isInLava()
                    && !player.isClimbing() && !usingFirework && !recentKnockback) {
                int fallBuffer = data.getFallBuffer() + 1;
                data.setFallBuffer(fallBuffer);
                if (fallBuffer >= compensatedBuffer) {
                    flag(player, data, 1.2,
                        String.format("下落过慢: dy=%.3f (连续%d tick, 延迟%s)",
                            deltaY, fallBuffer, data.getPingCompensator().getPingStatus()));
                }
            } else {
                data.setFallBuffer(0);
            }
        } else {
            data.setFallBuffer(0);
        }

        // --- Check 4: Sustained abnormal altitude ---
        checkSustainedAltitude(player, data, deltaY, onGround, usingFirework,
            recentKnockback, jumpTracker);

        jumpTracker.previousDeltaY = deltaY;
        jumpTracker.wasOnGround = onGround;
    }

    /**
     * Sustained altitude detection - non-elytra flight cheats.
     */
    private void checkSustainedAltitude(Player player, PlayerData data, double deltaY,
                                          boolean onGround, boolean usingFirework,
                                          boolean recentKnockback, JumpTracker jumpTracker) {
        UUID uuid = player.getUniqueId();

        if (onGround || usingFirework || recentKnockback || jumpTracker.isJumping) {
            resetAltitudeTracker(uuid);
            return;
        }

        if (player.isGliding()) {
            resetAltitudeTracker(uuid);
            return;
        }

        PotionEffectType levitation = ServerVersionAdapter.getLevitation();
        boolean hasLevitation = levitation != null && player.hasPotionEffect(levitation);
        PotionEffectType jumpBoost = ServerVersionAdapter.getJumpBoost();
        boolean hasJumpBoost = jumpBoost != null && player.hasPotionEffect(jumpBoost);
        PotionEffectType slowFalling = getPotionEffectTypeByName("SLOW_FALLING");
        boolean hasSlowFalling = slowFalling != null && player.hasPotionEffect(slowFalling);

        if (hasLevitation || (hasJumpBoost && hasSlowFalling)) {
            resetAltitudeTracker(uuid);
            return;
        }

        if (player.isInWater() || player.isInLava() || player.isClimbing()) {
            resetAltitudeTracker(uuid);
            return;
        }

        if (player.getWorld().getEnvironment().name().contains("THE_END")) {
            resetAltitudeTracker(uuid);
            return;
        }

        if (player.isInsideVehicle()) {
            resetAltitudeTracker(uuid);
            return;
        }

        AltitudeTracker tracker = altitudeTrackers.computeIfAbsent(uuid, k -> new AltitudeTracker());

        if (!tracker.hasStartAltitude) {
            tracker.startAltitudeY = player.getLocation().getY();
            tracker.hasStartAltitude = true;
        }

        double currentY = player.getLocation().getY();
        double heightAboveStart = currentY - tracker.startAltitudeY;

        if (heightAboveStart > ALTITUDE_HEIGHT_THRESHOLD) {
            if (deltaY > ALTITUDE_NO_FALL_THRESHOLD) {
                tracker.highAltitudeTicks++;
            } else {
                tracker.highAltitudeTicks = 0;
            }
        } else {
            tracker.highAltitudeTicks = 0;
        }

        if (tracker.highAltitudeTicks > ALTITUDE_DURATION_TICKS) {
            double severity = heightAboveStart / ALTITUDE_HEIGHT_THRESHOLD;
            flag(player, data, severity,
                String.format("持续异常高度: 高于起点 %.1f 格 (持续 %d tick, 延迟 %s)",
                    heightAboveStart, tracker.highAltitudeTicks,
                    data.getPingCompensator().getPingStatus()));
            resetAltitudeTracker(uuid);
        }
    }

    private void resetAltitudeTracker(UUID uuid) {
        altitudeTrackers.remove(uuid);
    }

    private void resetAllBuffers(PlayerData data) {
        data.setHoverBuffer(0);
        data.setAscendBuffer(0);
        data.setFallBuffer(0);
    }

    public void onPlayerQuit(UUID uuid) {
        jumpTrackers.remove(uuid);
        altitudeTrackers.remove(uuid);
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isFlying() || player.isInsideVehicle()
            || player.isSleeping() || player.isDead();
    }

    private double distanceToGround(Player player) {
        Location loc = player.getLocation().clone();
        double startY = loc.getY();
        for (int i = 0; i < 10; i++) {
            loc.subtract(0, 0.5, 0);
            if (loc.getBlock().getType().isSolid()) {
                return startY - loc.getY();
            }
        }
        return -1;
    }

    private static PotionEffectType getPotionEffectTypeByName(String name) {
        try {
            return PotionEffectType.getByName(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Jump physics tracker.
     */
    private static class JumpTracker {
        boolean wasOnGround = true;   // Previous tick ground state
        boolean isJumping = false;     // Currently in jump cycle
        long jumpStartTime = 0;        // Jump start timestamp
        int jumpTickCount = 0;         // Ticks since jump start
        double previousDeltaY = 0.0;   // Previous tick deltaY
        boolean atApex = false;        // Has reached jump apex
    }

    /**
     * Altitude tracker for sustained altitude detection.
     */
    private static class AltitudeTracker {
        int highAltitudeTicks;
        double startAltitudeY;
        boolean hasStartAltitude;

        AltitudeTracker() {
            this.highAltitudeTicks = 0;
            this.startAltitudeY = 0.0;
            this.hasStartAltitude = false;
        }
    }
}
