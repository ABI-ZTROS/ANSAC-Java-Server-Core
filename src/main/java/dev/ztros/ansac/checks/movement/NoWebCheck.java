package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NoWeb check - detects players moving through cobwebs without the normal slowdown.
 *
 * 作弊原理: Wurst NoWeb - 消除蜘蛛网的减速效果。
 * 正常情况下蜘蛛网会将移动速度降低到基础速度的 5%（约 0.05 格/刻），
 * 作弊者可以完全取消此减速效果，保持正常移动速度。
 *
 * 物理参考数据（Minecraft 1.21.x, minecraft.wiki）:
 *   蜘蛛网中正常速度: 基础速度 * 0.05 ≈ 0.05 格/刻
 *   蜘蛛网中疾跑速度: 疾跑速度 * 0.05 ≈ 0.014 格/刻
 *   蜘蛛网中跳跃: 不可跳跃（在网中无法跳跃）
 *   速度药水: 仍然受蜘蛛网减速影响（乘以 0.05）
 *   作弊 NoWeb: 速度恢复到正常值（0.2+ 格/刻）
 *
 * Design notes:
 * - 检测玩家所在方块或脚下方块是否为蜘蛛网
 * - 水平移动速度 > 0.15（蜘蛛网中正常速度极低，约 0.05）
 * - noWebBuffer > 10 → flag
 * - 豁免：速度药水（动态调整阈值）
 * - 使用内部类 NoWebTracker + ConcurrentHashMap 保证线程安全
 * - 使用 PingCompensator 进行延迟补偿
 */
public class NoWebCheck extends Check {

    // 蜘蛛网中正常水平移动速度（基础速度 * 0.05）
    private static final double WEB_NORMAL_SPEED = 0.05;
    // 检测阈值：蜘蛛网中速度超过此值视为可疑
    private static final double NO_WEB_THRESHOLD = 0.15;
    // 持续超过此 tick 数才视为可疑
    private static final int NO_WEB_BUFFER_BASE = 10;

    private final ConcurrentHashMap<UUID, NoWebTracker> trackers = new ConcurrentHashMap<>();

    public NoWebCheck(ANSACPlugin plugin) {
        super(plugin, "NoWeb", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            resetTracker(player.getUniqueId());
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        // 检查玩家所在方块或脚下方块是否为蜘蛛网
        if (!isInCobweb(player)) {
            resetTracker(player.getUniqueId());
            return;
        }

        double horizontalDist = data.getHorizontalDistance();

        // 计算动态阈值（考虑速度药水）
        double threshold = calculateThreshold(player);

        // Ping-compensated threshold
        threshold = data.getPingCompensator().getCompensatedThreshold(
            threshold, PingCompensator.COMPENSATION_SPEED);

        // 检测：水平移动速度 > 阈值
        if (horizontalDist > threshold) {
            UUID uuid = player.getUniqueId();
            NoWebTracker tracker = trackers.computeIfAbsent(uuid, k -> new NoWebTracker());

            // Ping-compensated buffer
            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                NO_WEB_BUFFER_BASE, PingCompensator.COMPENSATION_SPEED);

            tracker.noWebBuffer++;

            if (tracker.noWebBuffer >= compensatedBuffer) {
                double severity = horizontalDist / WEB_NORMAL_SPEED;
                flag(player, data, severity,
                    String.format("蜘蛛网减速取消: 速度=%.3f / 阈值=%.3f (持续 %d tick, 延迟 %s)",
                        horizontalDist, threshold, tracker.noWebBuffer,
                        data.getPingCompensator().getPingStatus()));
                // Flag 后重置
                resetTracker(uuid);
            }
        } else {
            // 正常速度，重置 buffer
            resetTracker(player.getUniqueId());
        }
    }

    /**
     * 检查玩家是否在蜘蛛网中（所在方块或脚下方块）
     */
    private boolean isInCobweb(Player player) {
        Location loc = player.getLocation();
        // 检查玩家所在方块
        if (loc.getBlock().getType().name().contains("COBWEB")) {
            return true;
        }
        // 检查脚下方块
        Location below = loc.clone().subtract(0, 0.5, 0);
        return below.getBlock().getType().name().contains("COBWEB");
    }

    /**
     * 计算动态阈值（考虑速度药水）
     * 蜘蛛网中速度 = 基础速度 * 0.05 * (1 + 0.2 * speedLevel)
     * 但作弊者取消减速后速度 = 基础速度 * (1 + 0.2 * speedLevel)
     * 阈值取中间值：蜘蛛网正常速度 * 3（有速度药水时适当提高）
     */
    private double calculateThreshold(Player player) {
        double threshold = NO_WEB_THRESHOLD;

        // 速度药水：动态调整阈值
        // 有速度药水时，蜘蛛网中的正常速度也会提高
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            // 速度药水使蜘蛛网中正常速度 = WEB_NORMAL_SPEED * (1 + 0.2 * level)
            // 但作弊者取消后速度 = 基础速度 * (1 + 0.2 * level)
            // 阈值 = 蜘蛛网正常速度 * 3 + 速度药水加成
            double webSpeedWithPotion = WEB_NORMAL_SPEED * (1.0 + 0.2 * level);
            threshold = Math.max(threshold, webSpeedWithPotion * 3.0);
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
     * Internal tracker for NoWeb detection.
     * Tracks how long a player has been moving abnormally fast in cobwebs.
     */
    private static class NoWebTracker {
        int noWebBuffer;         // 连续高速移动 tick 数

        NoWebTracker() {
            this.noWebBuffer = 0;
        }
    }
}
