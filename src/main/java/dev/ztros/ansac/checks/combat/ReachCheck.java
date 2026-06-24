package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Reach check - detects abnormal attack distance.
 *
 * 作弊原理（基于 Wurst/Meteor Client 源码分析）:
 *   Wurst ReachHack: Mixin 覆写 LocalPlayer.entityInteractionRange()，
 *     默认扩展到 6.0 格（最大 10.0 格），纯客户端修改，不发送额外包
 *   Meteor: 集成在 KillAura 中，默认 4.5 格，配合 Hitboxes 模块膨胀碰撞箱 +0.5
 *
 * 物理参考数据（Minecraft Wiki + GrimAC）:
 *   生存模式攻击距离: 3.0 格
 *   创造模式攻击距离: 5.0 格
 *   玩家碰撞箱: 0.6 x 1.8 x 0.6 格
 *   末影水晶碰撞箱: 2.0 x 2.0 x 2.0 格
 *   服务端命中检测: 眼睛位置到实体碰撞箱最近点的距离
 *
 * 参考 GrimAC Reach 实现:
 *   1. 即时层: 眼睛到碰撞箱最近点距离 > 3.03 则立即标记
 *   2. 精确层: Raytrace 验证视线是否命中碰撞箱
 *   3. 考虑实体碰撞箱大小（非中心距离）
 *   4. 考虑网络延迟导致的实体位置不确定性
 *   5. 1.9+ 客户端额外 0.03 容差（移动不确定性）
 */
public class ReachCheck extends Check {

    private static final double SURVIVAL_REACH = 3.0;
    private static final double CREATIVE_REACH = 5.0;
    private static final double INSTANT_CANCEL_THRESHOLD = 3.5; // 立即标记的阈值
    private static final double HITBOX_EXPANSION = 0.03; // 1.9+ 客户端移动不确定性
    private static final double DESYNC_TOLERANCE = 0.06; // 网络延迟容差（替代旧的 0.3）
    private static final int BUFFER_MAX = 5;

    public ReachCheck(ANSACPlugin plugin) {
        super(plugin, "Reach", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Periodic cleanup: decay attack count
        long now = System.currentTimeMillis();
        long lastAttack = data.getLastAttackTime();
        if (now - lastAttack > 1000) {
            data.setAttackCount(0);
        }
    }

    /**
     * Process an attack event (called from packet listener).
     * Uses GrimAC-style reach detection: eye to hitbox nearest point.
     */
    public void processAttack(Player player, PlayerData data, Entity target) {
        if (!isEnabled() || data.hasBypass()) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setLastAttackTime(System.currentTimeMillis());
            return;
        }

        // --- Layer 1: Distance check (eye to hitbox nearest point) ---
        // This is how the server validates reach, not center-to-center
        double reachDistance = getEyeToHitboxDistance(player, target);

        // Determine max allowed reach based on game mode
        double maxReach = player.getGameMode().name().contains("CREATIVE") ? CREATIVE_REACH : SURVIVAL_REACH;

        // Apply ping-compensated reach multiplier
        double compensatedMaxReach = data.getPingCompensator().getCompensatedSpeed(
            maxReach, PingCompensator.COMPENSATION_REACH);

        // Add tolerances:
        // 1. Hitbox expansion for 1.9+ client movement uncertainty (0.03)
        // 2. Network desync tolerance (0.06, much tighter than old 0.3)
        double totalTolerance = HITBOX_EXPANSION + DESYNC_TOLERANCE;
        double compensatedTolerance = data.getPingCompensator().getCompensatedThreshold(
            totalTolerance, PingCompensator.COMPENSATION_REACH);

        double effectiveMax = compensatedMaxReach + compensatedTolerance;

        // Instant cancel threshold: if distance is way beyond reasonable, flag immediately
        if (reachDistance > INSTANT_CANCEL_THRESHOLD) {
            int buffer = data.getReachBuffer() + 2; // Double increment for extreme violations
            data.setReachBuffer(buffer);

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_MAX, PingCompensator.COMPENSATION_REACH);

            if (buffer >= compensatedBuffer) {
                double severity = reachDistance / maxReach;
                flag(player, data, severity,
                    String.format("攻击距离严重异常: %.2f / %.2f (目标: %s, 延迟 %s)",
                        reachDistance, maxReach, target.getName(),
                        data.getPingCompensator().getPingStatus()));
            }

            data.setLastAttackTime(System.currentTimeMillis());
            data.setAttackCount(data.getAttackCount() + 1);
            return;
        }

        // Normal threshold check
        if (reachDistance > effectiveMax) {
            int buffer = data.getReachBuffer() + 1;
            data.setReachBuffer(buffer);

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_MAX, PingCompensator.COMPENSATION_REACH);

            if (buffer >= compensatedBuffer) {
                double severity = reachDistance / maxReach;
                flag(player, data, severity,
                    String.format("攻击距离异常: %.2f / %.2f (容差: %.2f, 目标: %s, 延迟 %s)",
                        reachDistance, maxReach, compensatedTolerance,
                        target.getName(), data.getPingCompensator().getPingStatus()));
            }
        } else {
            // Normal reach, decay buffer
            if (data.getReachBuffer() > 0) {
                data.setReachBuffer(data.getReachBuffer() - 1);
            }
        }

        // --- Layer 2: Raytrace verification (eye direction vs target) ---
        // Verify the player is actually looking at the target
        // This catches KillAura attacking targets behind them
        if (reachDistance > maxReach - 0.5) {
            double lookAngle = getLookToTargetAngle(player, target);
            if (lookAngle > 40.0) { // Player looking >40° away from target
                // Possible KillAura with 360° FOV
                int angleBuffer = data.getReachAngleBuffer() + 1;
                data.setReachAngleBuffer(angleBuffer);

                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_MAX, PingCompensator.COMPENSATION_KILLAURA);

                if (angleBuffer >= compensatedBuffer) {
                    flag(player, data, 0.8,
                        String.format("攻击视线异常: 看向角度 %.1f° 但命中目标 (距离: %.2f, 目标: %s, 延迟 %s)",
                            lookAngle, reachDistance, target.getName(),
                            data.getPingCompensator().getPingStatus()));
                }
            } else {
                if (data.getReachAngleBuffer() > 0) {
                    data.setReachAngleBuffer(data.getReachAngleBuffer() - 1);
                }
            }
        }

        // Track attack
        data.setLastAttackTime(System.currentTimeMillis());
        data.setAttackCount(data.getAttackCount() + 1);
    }

    /**
     * Calculate distance from player's eye to the nearest point on target's hitbox.
     * This matches how the Minecraft server validates reach.
     *
     * Reference: GrimAC Reach.java - uses VectorUtils.cutBoxToVector()
     */
    private double getEyeToHitboxDistance(Player player, Entity target) {
        Location eyeLoc = player.getLocation().clone();
        eyeLoc.setY(eyeLoc.getY() + player.getEyeHeight());

        // Target bounding box
        double halfWidth = target.getWidth() / 2.0;
        double height = target.getHeight();

        Location targetLoc = target.getLocation();
        double minX = targetLoc.getX() - halfWidth;
        double maxX = targetLoc.getX() + halfWidth;
        double minY = targetLoc.getY();
        double maxY = targetLoc.getY() + height;
        double minZ = targetLoc.getZ() - halfWidth;
        double maxZ = targetLoc.getZ() + halfWidth;

        // Clamp eye position to bounding box to find nearest point
        double closestX = clamp(eyeLoc.getX(), minX, maxX);
        double closestY = clamp(eyeLoc.getY(), minY, maxY);
        double closestZ = clamp(eyeLoc.getZ(), minZ, maxZ);

        // Calculate distance from eye to nearest point on hitbox
        double dx = eyeLoc.getX() - closestX;
        double dy = eyeLoc.getY() - closestY;
        double dz = eyeLoc.getZ() - closestZ;

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Calculate angle between player's look direction and direction to target.
     * Used to detect KillAura 360° attacks.
     */
    private double getLookToTargetAngle(Player player, Entity target) {
        Location eyeLoc = player.getLocation();
        eyeLoc.setY(eyeLoc.getY() + player.getEyeHeight());

        // Target center
        double targetCenterX = target.getLocation().getX();
        double targetCenterY = target.getLocation().getY() + target.getHeight() / 2.0;
        double targetCenterZ = target.getLocation().getZ();

        // Direction to target
        double toTargetX = targetCenterX - eyeLoc.getX();
        double toTargetY = targetCenterY - eyeLoc.getY();
        double toTargetZ = targetCenterZ - eyeLoc.getZ();

        // Player look direction (from yaw/pitch)
        double yawRad = Math.toRadians(eyeLoc.getYaw());
        double pitchRad = Math.toRadians(eyeLoc.getPitch());
        double lookX = -Math.sin(yawRad) * Math.cos(pitchRad);
        double lookY = -Math.sin(pitchRad);
        double lookZ = Math.cos(yawRad) * Math.cos(pitchRad);

        // Dot product for angle
        double lookLen = Math.sqrt(lookX * lookX + lookY * lookY + lookZ * lookZ);
        double targetLen = Math.sqrt(toTargetX * toTargetX + toTargetY * toTargetY + toTargetZ * toTargetZ);

        if (lookLen < 0.001 || targetLen < 0.001) return 0;

        double dot = (lookX * toTargetX + lookY * toTargetY + lookZ * toTargetZ)
                / (lookLen * targetLen);
        return Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, dot))));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
