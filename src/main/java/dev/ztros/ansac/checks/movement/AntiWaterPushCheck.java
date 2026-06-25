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
 * AntiWaterPush check - detects players canceling water flow push and water slowdown.
 *
 * 作弊原理: Wurst AntiWaterPush - 取消水流推力和水中减速。
 * 正常情况下水中移动速度大幅降低（约 0.1 格/刻行走，0.15 疾跑），
 * 且水流会对玩家施加推力。作弊者可以取消这些效果，保持陆地移动速度。
 *
 * 物理参考数据（Minecraft 1.21.x, minecraft.wiki）:
 *   水中行走速度: 0.02 格/刻 (无海豚恩典)
 *   水中疾跑速度: 0.15 格/刻 (无海豚恩典)
 *   水中跳跃速度: 0.1 格/刻 (水平分量)
 *   海豚恩典: 水中速度 * 5.0 → 疾跑水中约 0.75 格/刻
 *   速度药水: 基础速度 * (1 + 0.2 * 等级)，水中同样生效
 *   水流推力: 水平 0.014~0.05 格/刻（取决于水流强度）
 *   深海探索者: 水中速度 * 1.0（不影响基础速度，仅影响挖掘速度）
 *
 * Design notes:
 * - 仅在玩家在水中时检测
 * - 没有海豚恩典但水中速度 > 0.22 → 可疑
 * - waterSpeedBuffer > 15 → flag
 * - 豁免：海豚恩典效果、速度药水（动态调整阈值）、被击退
 * - 使用内部类 AntiWaterPushTracker + ConcurrentHashMap 保证线程安全
 * - 使用 PingCompensator 进行延迟补偿
 */
public class AntiWaterPushCheck extends Check {

    // 水中正常行走水平速度（无加成）
    private static final double WATER_WALK_SPEED = 0.02;
    // 水中正常疾跑水平速度（无加成）
    private static final double WATER_SPRINT_SPEED = 0.15;
    // 基础检测阈值：正常水中速度 * 1.5
    private static final double BASE_WATER_THRESHOLD = 0.22;
    // 持续超过此 tick 数才视为可疑
    private static final int WATER_SPEED_BUFFER_BASE = 15;
    // 击退后豁免时间（毫秒）
    private static final long KNOCKBACK_EXEMPT_MS = 1000L;

    private final ConcurrentHashMap<UUID, AntiWaterPushTracker> trackers = new ConcurrentHashMap<>();

    public AntiWaterPushCheck(ANSACPlugin plugin) {
        super(plugin, "AntiWaterPush", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 仅在水中检测
        if (!player.isInWater()) {
            resetTracker(player.getUniqueId());
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        double horizontalDist = data.getHorizontalDistance();

        // 移动量太小，跳过
        if (horizontalDist < 0.01) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 豁免：海豚恩典效果（大幅提升水中速度）
        PotionEffectType dolphinsGrace = ServerVersionAdapter.getDolphinsGrace();
        if (dolphinsGrace != null && player.hasPotionEffect(dolphinsGrace)) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 豁免：被击退后
        long now = System.currentTimeMillis();
        if ((now - data.getLastKnockbackTime()) < KNOCKBACK_EXEMPT_MS) {
            resetTracker(player.getUniqueId());
            return;
        }

        // 计算动态阈值（考虑速度药水）
        double threshold = calculateThreshold(player);

        // Ping-compensated threshold
        threshold = data.getPingCompensator().getCompensatedThreshold(
            threshold, PingCompensator.COMPENSATION_SPEED);

        // 检测：水中水平移动速度 > 阈值
        if (horizontalDist > threshold) {
            UUID uuid = player.getUniqueId();
            AntiWaterPushTracker tracker = trackers.computeIfAbsent(uuid, k -> new AntiWaterPushTracker());

            // Ping-compensated buffer
            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                WATER_SPEED_BUFFER_BASE, PingCompensator.COMPENSATION_SPEED);

            tracker.waterSpeedBuffer++;

            if (tracker.waterSpeedBuffer >= compensatedBuffer) {
                double severity = horizontalDist / (player.isSprinting() ? WATER_SPRINT_SPEED : WATER_WALK_SPEED);
                flag(player, data, severity,
                    String.format("水流推力取消: 速度=%.3f / 阈值=%.3f (持续 %d tick, 延迟 %s)",
                        horizontalDist, threshold, tracker.waterSpeedBuffer,
                        data.getPingCompensator().getPingStatus()));
                // Flag 后重置
                resetTracker(uuid);
            }
        } else {
            // 正常水中速度，重置 buffer
            resetTracker(player.getUniqueId());
        }
    }

    /**
     * 计算动态阈值（考虑速度药水）
     * 水中正常速度 * 1.5 + 速度药水加成
     */
    private double calculateThreshold(Player player) {
        double threshold = BASE_WATER_THRESHOLD;

        // 速度药水：水中速度 = 基础水中速度 * (1 + 0.2 * level)
        // 有速度药水时适当提高阈值
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            double baseWaterSpeed = player.isSprinting() ? WATER_SPRINT_SPEED : WATER_WALK_SPEED;
            double waterSpeedWithPotion = baseWaterSpeed * (1.0 + 0.2 * level);
            // 阈值 = 有药水时的正常水中速度 * 1.5
            threshold = Math.max(threshold, waterSpeedWithPotion * 1.5);
        }

        return threshold;
    }

    /**
     * Clean up tracker when player disconnects.
     */
    public void onPlayerQuit(UUID uuid) {
        trackers.remove(uuid);
    }

    private void resetTracker(UUID uuid) {
        trackers.remove(uuid);
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isFlying()
            || player.isInsideVehicle()
            || player.isSleeping()
            || player.isDead();
    }

    /**
     * Internal tracker for AntiWaterPush detection.
     * Tracks how long a player has been moving abnormally fast in water.
     */
    private static class AntiWaterPushTracker {
        int waterSpeedBuffer;    // 连续高速水中移动 tick 数

        AntiWaterPushTracker() {
            this.waterSpeedBuffer = 0;
        }
    }
}
