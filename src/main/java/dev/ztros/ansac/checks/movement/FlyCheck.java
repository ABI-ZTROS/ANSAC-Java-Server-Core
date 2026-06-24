package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.util.ServerVersionAdapter;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fly check - detects abnormal vertical movement and flight.
 *
 * 物理参考数据（Minecraft 1.21.x, minecraft.wiki）:
 *   跳跃初速: 0.42 格/刻 (Y轴)
 *   重力公式: v(t) = 0.98 * (v(t-1) - 0.08), 即每刻先减 0.08 再乘 0.98
 *   终端速度: 3.92 格/刻 (78.4 m/s), 实际约 3.709 格/刻
 *   跳跃提升: 初速 + 0.1 * 等级
 *   风弹击退: ~6格高度, 2.5格水平
 *
 * Design notes:
 * - Creative/Spectator mode players are skipped entirely.
 * - Players in vehicles, sleeping, or dead are skipped.
 * - Normal jumping, falling, climbing, swimming, and elytra gliding are exempted.
 * - Uses a buffer system to avoid false positives from single-tick anomalies.
 * - Ground proximity check prevents edge-standing false positives.
 * - Layer 2: Sustained abnormal altitude detection for non-elytra flight cheats.
 */
public class FlyCheck extends Check {

    private static final double LENIENCY = 0.15;
    private static final int BUFFER_MAX = 8; // Was 6, increase for more leniency
    private static final double JUMP_INITIAL_VELOCITY = 0.42; // Normal jump initial dy
    private static final int JUMP_EXEMPT_TICKS = 20;          // 增加到 20 tick（跳跃上升约 0.7 秒）
    private static final double GRAVITY_INITIAL = 0.08;        // 初始重力减速
    private static final double GRAVITY_MULTIPLIER = 0.98;     // 重力乘数
    private static final double TERMINAL_VELOCITY = 3.92;      // 终端速度

    // Sustained altitude detection thresholds
    private static final double ALTITUDE_HEIGHT_THRESHOLD = 30.0; // 30格高于起始地面
    private static final int ALTITUDE_DURATION_TICKS = 60;        // 3秒持续高海拔
    private static final double ALTITUDE_NO_FALL_THRESHOLD = -0.05; // deltaY > -0.05 视为无明显下落

    private final ConcurrentHashMap<UUID, AltitudeTracker> altitudeTrackers = new ConcurrentHashMap<>();

    public FlyCheck(ANSACPlugin plugin) {
        super(plugin, "Fly", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setHoverBuffer(0);
            data.setAscendBuffer(0);
            data.setFallBuffer(0);
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        double deltaY = data.getVerticalDistance();
        boolean onGround = player.isOnGround();
        long now = System.currentTimeMillis();

        // --- Jump tracking ---
        // If player just jumped (large positive deltaY), mark them as jumping
        if (onGround && deltaY > 0.3) {
            data.setLastJumpTime(now);
        }
        boolean recentlyJumped = (now - data.getLastJumpTime()) < (JUMP_EXEMPT_TICKS * 50L);

        // --- Wind Charge / explosion knockback detection ---
        // Wind charge gives a sudden velocity boost; exempt for a short time
        boolean recentKnockback = (now - data.getLastKnockbackTime()) < 1000L; // 1 second

        // --- Elytra / Firework rocket check ---
        // Player might be using firework with elytra (gives upward boost)
        boolean hasElytra = player.getInventory().getChestplate() != null
                && player.getInventory().getChestplate().getType().name().contains("ELYTRA");
        boolean usingFirework = player.isGliding() || hasElytra && deltaY > 0.3;

        // --- Ground proximity check ---
        // If player is near ground, they might be edge-standing or about to land
        double distToGround = distanceToGround(player);
        boolean nearGround = distToGround >= 0 && distToGround < 1.5;

        // Ping-compensated buffer
        int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
            BUFFER_MAX, PingCompensator.COMPENSATION_FLY);

        // --- Check 1: sustained hover ---
        // Not on ground, not moving vertically, not in liquid, not climbing, not near ground
        if (!onGround && Math.abs(deltaY) < 0.001
                && !player.isInWater() && !player.isInLava()
                && !player.isClimbing()
                && !nearGround
                && !recentlyJumped
                && !usingFirework
                && !recentKnockback) {
            int hoverBuffer = data.getHoverBuffer() + 1;
            data.setHoverBuffer(hoverBuffer);
            if (hoverBuffer >= compensatedBuffer) {
                flag(player, data, 1.5,
                    "空中悬停（连续 " + hoverBuffer + " tick，延迟 "
                    + data.getPingCompensator().getPingStatus() + "）");
            }
            return;
        } else {
            data.setHoverBuffer(0);
        }

        // --- Check 2: ascending while not on ground ---
        // Skip if recently jumped, has jump boost, levitation, climbing, in liquid, near ground, using firework, or recent knockback
        if (!onGround && deltaY > LENIENCY) {
            if (recentlyJumped || usingFirework || nearGround || recentKnockback) {
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
                            String.format("空中异常上升: dy=%.3f (连续 %d tick, 延迟 %s)",
                                deltaY, ascendBuffer, data.getPingCompensator().getPingStatus()));
                    }
                    return;
                } else {
                    data.setAscendBuffer(0);
                }
            }
        } else {
            data.setAscendBuffer(0);
        }

        // --- Check 3: falling too slowly ---
        // 正常下落第一刻: deltaY ≈ -0.08 * 0.98 = -0.0784
        // 之后加速到 -3.92（终端速度）
        // 如果 deltaY > -0.05（绝对值太小），持续多 tick 才可疑
        if (!onGround && deltaY < -LENIENCY) {
            if (deltaY > -0.05 && !player.isInWater() && !player.isInLava()
                    && !player.isClimbing() && !recentlyJumped && !usingFirework && !recentKnockback) {
                int fallBuffer = data.getFallBuffer() + 1;
                data.setFallBuffer(fallBuffer);
                if (fallBuffer >= compensatedBuffer) {
                    flag(player, data, 1.2,
                        String.format("下落过慢: dy=%.3f (连续 %d tick, 延迟 %s)",
                            deltaY, fallBuffer, data.getPingCompensator().getPingStatus()));
                }
                return;
            } else {
                data.setFallBuffer(0);
            }
        } else {
            data.setFallBuffer(0);
        }

        // --- Check 4: Sustained abnormal altitude (non-elytra flight) ---
        checkSustainedAltitude(player, data, deltaY, onGround, usingFirework, recentKnockback, recentlyJumped);
    }

    /**
     * Sustained altitude detection layer - detects non-elytra flight cheats.
     * Monitors players who remain at abnormally high altitude for extended periods
     * without showing natural falling behavior.
     */
    private void checkSustainedAltitude(Player player, PlayerData data, double deltaY,
                                          boolean onGround, boolean usingFirework,
                                          boolean recentKnockback, boolean recentlyJumped) {
        UUID uuid = player.getUniqueId();

        // Exemptions
        if (onGround || usingFirework || recentKnockback || recentlyJumped) {
            resetAltitudeTracker(uuid);
            return;
        }

        // Exempt: elytra gliding
        if (player.isGliding()) {
            resetAltitudeTracker(uuid);
            return;
        }

        // Exempt: levitation or jump boost + slow falling combination
        PotionEffectType levitation = ServerVersionAdapter.getLevitation();
        boolean hasLevitation = levitation != null && player.hasPotionEffect(levitation);
        PotionEffectType jumpBoost = ServerVersionAdapter.getJumpBoost();
        boolean hasJumpBoost = jumpBoost != null && player.hasPotionEffect(jumpBoost);
        // Slow Falling is available since 1.13, use name-based lookup for compatibility
        PotionEffectType slowFalling = getPotionEffectTypeByName("SLOW_FALLING");
        boolean hasSlowFalling = slowFalling != null && player.hasPotionEffect(slowFalling);

        if (hasLevitation || (hasJumpBoost && hasSlowFalling)) {
            resetAltitudeTracker(uuid);
            return;
        }

        // Exempt: in liquid (swimming/water climbing)
        if (player.isInWater() || player.isInLava()) {
            resetAltitudeTracker(uuid);
            return;
        }

        // Exempt: climbing (ladders, vines, etc.)
        if (player.isClimbing()) {
            resetAltitudeTracker(uuid);
            return;
        }

        // Exempt: The End dimension (natural high platforms)
        if (player.getWorld().getEnvironment().name().contains("THE_END")) {
            resetAltitudeTracker(uuid);
            return;
        }

        // Exempt: riding entity (horses, striders, etc.)
        if (player.isInsideVehicle()) {
            resetAltitudeTracker(uuid);
            return;
        }

        AltitudeTracker tracker = altitudeTrackers.computeIfAbsent(uuid, k -> new AltitudeTracker());

        // Initialize start altitude if not set
        if (!tracker.hasStartAltitude) {
            tracker.startAltitudeY = player.getLocation().getY();
            tracker.hasStartAltitude = true;
        }

        double currentY = player.getLocation().getY();
        double heightAboveStart = currentY - tracker.startAltitudeY;

        // Check if player is at abnormally high altitude
        if (heightAboveStart > ALTITUDE_HEIGHT_THRESHOLD) {
            // Check if there's no significant falling trend
            if (deltaY > ALTITUDE_NO_FALL_THRESHOLD) {
                tracker.highAltitudeTicks++;
            } else {
                // Player is falling - reset
                tracker.highAltitudeTicks = 0;
            }
        } else {
            // Not high enough - reset
            tracker.highAltitudeTicks = 0;
        }

        if (tracker.highAltitudeTicks > ALTITUDE_DURATION_TICKS) {
            double severity = heightAboveStart / ALTITUDE_HEIGHT_THRESHOLD;
            flag(player, data, severity,
                String.format("持续异常高度: 高于起点 %.1f 格 (持续 %d tick, 延迟 %s)",
                    heightAboveStart, tracker.highAltitudeTicks,
                    data.getPingCompensator().getPingStatus()));
            // Reset after flagging to avoid spam
            resetAltitudeTracker(uuid);
        }
    }

    private void resetAltitudeTracker(UUID uuid) {
        altitudeTrackers.remove(uuid);
    }

    /**
     * Clean up tracker when player disconnects.
     */
    public void onPlayerQuit(UUID uuid) {
        altitudeTrackers.remove(uuid);
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isFlying() || player.isInsideVehicle()
            || player.isSleeping() || player.isDead();
    }

    /**
     * Calculate distance from player's feet to the nearest solid block below.
     * Returns -1 if no ground found within reasonable distance.
     */
    private double distanceToGround(Player player) {
        Location loc = player.getLocation().clone();
        double startY = loc.getY();
        // Check up to 5 blocks down
        for (int i = 0; i < 10; i++) {
            loc.subtract(0, 0.5, 0);
            if (loc.getBlock().getType().isSolid()) {
                return startY - loc.getY();
            }
        }
        return -1;
    }

    /**
     * Get a PotionEffectType by name, returning null if not found.
     * Used for effects not yet in ServerVersionAdapter.
     */
    private static PotionEffectType getPotionEffectTypeByName(String name) {
        try {
            return PotionEffectType.getByName(name);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Internal tracker for sustained altitude detection.
     * Monitors how long a player remains at abnormally high altitude
     * without natural falling behavior.
     */
    private static class AltitudeTracker {
        int highAltitudeTicks;      // 连续高海拔 tick 数
        double startAltitudeY;     // 开始高海拔的 Y 坐标
        boolean hasStartAltitude;   // 是否已记录起始高度

        AltitudeTracker() {
            this.highAltitudeTicks = 0;
            this.startAltitudeY = 0.0;
            this.hasStartAltitude = false;
        }
    }
}
