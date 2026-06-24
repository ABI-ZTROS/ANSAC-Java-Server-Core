package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * BowAimbot check - detects automated bow/crossbow aiming.
 *
 * 作弊原理（基于 Wurst BowAimbot / Meteor BowAimbot 源码分析）:
 *   使用抛物线弹道方程精确计算 pitch:
 *     pitch = -atan((v² - sqrt(v⁴ - g*(g*d² + 2*y*v²))) / (g*d))
 *   g = 0.006 (弓箭重力常数)
 *   Yaw 精确指向目标中心
 *   拉弓时视角无抖动
 *   对移动目标使用固定预测系数 (Wurst: 0.2)
 *
 * 物理参考数据（Minecraft Wiki）:
 *   弓箭重力: 0.006/tick² (即 -0.05 m/tick² 但代码中使用 0.006)
 *   弓箭阻力: 0.99/tick
 *   弓满蓄力时间: 1 秒 (20 tick)
 *   弓箭初速: 满蓄力 ~3.0 格/tick (60 m/s)
 *   弩蓄力时间: 1.25 秒 (25 tick)
 *   弩箭矢初速: ~3.15 格/tick (63 m/s)
 *
 * 检测策略:
 * 1. 拉弓时追踪 pitch 角度变化
 * 2. 如果 pitch 与弹道公式计算值误差 < 3°，高度可疑
 * 3. 拉弓过程中 pitch 变化过于平滑（无人类抖动）
 * 4. 连续多次精确瞄准则 flag
 */
public class BowAimbotCheck extends Check {

    private static final double ARROW_GRAVITY = 0.006; // 弓箭重力常数
    private static final double MAX_ANGLE_ERROR = 3.0; // 最大允许角度误差（度）
    private static final double MIN_CHARGE_TICKS = 10; // 最小蓄力 tick 数（0.5秒）
    private static final int BUFFER_MAX = 5;
    private static final double MIN_JITTER = 0.5; // 正常人类最小 pitch 抖动（度/tick）

    public BowAimbotCheck(ANSACPlugin plugin) {
        super(plugin, "BowAimbot", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) {
            data.setBowAimbotBuffer(0);
            return;
        }

        // Ping compensation
        if (data.getPingCompensator().shouldSkipCheck()) return;

        // 检查玩家是否在拉弓/弩
        if (!player.isHandRaised()) {
            data.setBowAimbotBuffer(0);
            data.setLastBowPitch(0);
            data.setBowChargeTicks(0);
            return;
        }

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        boolean holdingBow = isBow(mainHand) || isBow(offHand);
        boolean holdingCrossbow = isCrossbow(mainHand) || isCrossbow(offHand);

        if (!holdingBow && !holdingCrossbow) {
            data.setBowAimbotBuffer(0);
            return;
        }

        // 追踪蓄力时间
        data.setBowChargeTicks(data.getBowChargeTicks() + 1);
        if (data.getBowChargeTicks() < MIN_CHARGE_TICKS) return;

        // 计算当前 pitch
        float currentPitch = player.getLocation().getPitch();
        float lastPitch = data.getLastBowPitch();

        if (lastPitch != 0) {
            // 检测 pitch 抖动（正常人类拉弓时会有微小抖动）
            double pitchDelta = Math.abs(currentPitch - lastPitch);

            // 如果 pitch 变化极小（< 0.1°/tick），可能是在精确锁定
            if (pitchDelta < 0.1) {
                // 寻找最近的实体目标
                LivingEntity nearestTarget = findNearestTarget(player, 30);
                if (nearestTarget != null) {
                    // 计算弹道公式期望的 pitch
                    double expectedPitch = calculateExpectedPitch(player, nearestTarget, data.getBowChargeTicks());

                    if (!Double.isNaN(expectedPitch)) {
                        double angleError = Math.abs(currentPitch - expectedPitch);

                        if (angleError < MAX_ANGLE_ERROR) {
                            int buffer = data.getBowAimbotBuffer() + 1;
                            data.setBowAimbotBuffer(buffer);

                            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                                BUFFER_MAX, PingCompensator.COMPENSATION_KILLAURA);

                            if (buffer >= compensatedBuffer) {
                                double severity = 1.0 - (angleError / MAX_ANGLE_ERROR);
                                flag(player, data, severity,
                                    String.format("弓箭自动瞄准: pitch=%.2f° 期望=%.2f° 误差=%.2f° (蓄力 %d tick, 目标: %s, 连续 %d 次, 延迟 %s)",
                                        currentPitch, expectedPitch, angleError,
                                        data.getBowChargeTicks(), nearestTarget.getName(), buffer,
                                        data.getPingCompensator().getPingStatus()));
                            }
                        } else {
                            // 误差正常，衰减
                            if (data.getBowAimbotBuffer() > 0) {
                                data.setBowAimbotBuffer(data.getBowAimbotBuffer() - 1);
                            }
                        }
                    }
                }
            }
        }

        data.setLastBowPitch(currentPitch);
    }

    /**
     * 计算弹道公式期望的 pitch 角度
     * 基于抛物线弹道方程求解
     */
    private double calculateExpectedPitch(Player player, LivingEntity target, int chargeTicks) {
        // 计算初速度 (0-1.0)
        double power = Math.min(chargeTicks / 20.0, 1.0);
        double velocity = power * 3.0; // 满蓄力 ~3.0 格/tick

        if (velocity < 0.1) return Double.NaN;

        // 计算相对位置
        Location playerLoc = player.getLocation();
        Location targetLoc = target.getLocation();

        double relativeX = targetLoc.getX() + target.getWidth() / 2 - playerLoc.getX();
        double relativeY = targetLoc.getY() + target.getHeight() / 2 - (playerLoc.getY() + player.getEyeHeight());
        double relativeZ = targetLoc.getZ() + target.getWidth() / 2 - playerLoc.getZ();

        double hDistance = Math.sqrt(relativeX * relativeX + relativeZ * relativeZ);
        if (hDistance < 1.0) return Double.NaN;

        double hDistanceSq = hDistance * hDistance;
        double velocitySq = velocity * velocity;
        double velocityPow4 = velocitySq * velocitySq;
        double g = ARROW_GRAVITY;

        // 抛物线弹道方程求解 pitch
        double discriminant = velocityPow4 - g * (g * hDistanceSq + 2 * relativeY * velocitySq);
        if (discriminant < 0) return Double.NaN; // 目标超出射程

        double pitchRad = Math.atan(
            (velocitySq - Math.sqrt(discriminant))
            / (g * hDistance)
        );

        return -Math.toDegrees(pitchRad);
    }

    /**
     * 寻找最近的 LivingEntity 目标
     */
    private LivingEntity findNearestTarget(Player player, double maxRange) {
        LivingEntity nearest = null;
        double minDist = maxRange * maxRange;

        for (Entity entity : player.getNearbyEntities(maxRange, maxRange, maxRange)) {
            if (entity instanceof LivingEntity target
                    && target != player
                    && target.isValid()
                    && !target.isDead()) {
                double dist = player.getLocation().distanceSquared(target.getLocation());
                if (dist < minDist) {
                    minDist = dist;
                    nearest = target;
                }
            }
        }
        return nearest;
    }

    private boolean isBow(ItemStack item) {
        return item != null && item.getType() == Material.BOW;
    }

    private boolean isCrossbow(ItemStack item) {
        return item != null && item.getType().name().equals("CROSSBOW");
    }
}
