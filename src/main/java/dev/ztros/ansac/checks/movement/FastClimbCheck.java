package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * FastClimb check - detects players climbing ladders/vines abnormally fast.
 *
 * 作弊原理: Wurst FastLadder + Meteor FastClimb - 爬梯子/藤蔓速度大幅提升。
 * 正常爬梯子速度约 0.087 格/刻，作弊可达到 0.287 格/刻甚至更快。
 * 通过修改客户端发送的垂直位置数据实现。
 *
 * 物理参考数据（Minecraft 1.21.x, minecraft.wiki）:
 *   正常爬梯速度: 0.087 格/刻 (1.74 格/秒)
 *   疾跑爬梯速度: 0.087 格/刻（疾跑不影响爬梯速度）
 *   作弊 FastLadder: 0.287 格/刻 (5.74 格/秒)，约 3.3 倍正常速度
 *   作弊 FastClimb: 可达 0.5+ 格/刻
 *   跳跃提升: 不影响爬梯速度
 *   速度药水: 不影响爬梯速度
 *
 * Design notes:
 * - 快速爬梯本身就是明确的作弊特征，无特殊豁免
 * - deltaY > 0.15（正常约 0.087，作弊约 0.287）视为可疑
 * - fastClimbBuffer > 10（500ms 持续快速爬梯）→ flag
 * - 使用内部类 FastClimbTracker + ConcurrentHashMap 保证线程安全
 * - 使用 PingCompensator 进行延迟补偿
 */
public class FastClimbCheck extends Check {

    // 正常爬梯 deltaY ≈ 0.087，作弊约 0.287，阈值取中间值
    private static final double FAST_CLIMB_THRESHOLD = 0.15;
    // 持续快速爬梯超过此 tick 数才视为可疑（500ms = 10 tick）
    private static final int FAST_CLIMB_BUFFER_BASE = 10;

    private final ConcurrentHashMap<UUID, FastClimbTracker> trackers = new ConcurrentHashMap<>();

    public FastClimbCheck(ANSACPlugin plugin) {
        super(plugin, "FastClimb", "Movement");
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

        // 仅在玩家正在爬梯子/藤蔓时检测
        if (!player.isClimbing()) {
            resetTracker(player.getUniqueId());
            return;
        }

        double deltaY = data.getVerticalDistance();

        // 检测：deltaY > 阈值（爬梯速度异常快）
        if (deltaY > FAST_CLIMB_THRESHOLD) {
            UUID uuid = player.getUniqueId();
            FastClimbTracker tracker = trackers.computeIfAbsent(uuid, k -> new FastClimbTracker());

            // Ping-compensated buffer
            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                FAST_CLIMB_BUFFER_BASE, PingCompensator.COMPENSATION_FLY);

            tracker.fastClimbBuffer++;

            if (tracker.fastClimbBuffer >= compensatedBuffer) {
                double severity = deltaY / FAST_CLIMB_THRESHOLD;
                flag(player, data, severity,
                    String.format("快速爬梯检测: deltaY=%.3f (持续 %d tick, 延迟 %s)",
                        deltaY, tracker.fastClimbBuffer,
                        data.getPingCompensator().getPingStatus()));
                // Flag 后重置
                resetTracker(uuid);
            }
        } else {
            // 正常爬梯速度，重置 buffer
            resetTracker(player.getUniqueId());
        }
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
     * Internal tracker for FastClimb detection.
     * Tracks how long a player has been climbing abnormally fast.
     */
    private static class FastClimbTracker {
        int fastClimbBuffer;      // 连续快速爬梯 tick 数

        FastClimbTracker() {
            this.fastClimbBuffer = 0;
        }
    }
}
