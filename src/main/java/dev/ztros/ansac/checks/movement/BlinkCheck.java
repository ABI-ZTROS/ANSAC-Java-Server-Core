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
 * Blink check - detects packet-delay / teleport cheats.
 *
 * 作弊原理（Wurst Blink + Meteor Blink）:
 *   Wurst Blink: 暂停发送位置包，积攒移动后一次性发送所有位置，造成瞬移效果。
 *   Meteor Blink: 类似，但支持取消（积攒的包一次性释放）。
 *   特征：长时间无位置更新，然后突然大距离位移。
 *
 * 检测逻辑：
 *   1. 每 tick 检查玩家水平移动距离。
 *   2. 如果水平移动 < 0.1 且不在地面，递增 pauseBuffer（可能暂停发包）。
 *   3. 如果 pauseBuffer > 阈值（约 500ms）后突然出现大水平位移（> 3.0），判定为 Blink。
 *   4. 豁免：载具中、鞘翅滑翔、被击退、TP 命令。
 *
 * Design notes:
 *   - 使用内部类 BlinkTracker + ConcurrentHashMap 保证线程安全。
 *   - 使用 PingCompensator 进行延迟补偿，避免高延迟误判。
 *   - 跳过创造/旁观模式、载具中、睡眠、死亡玩家。
 */
public class BlinkCheck extends Check {

    // 暂停判定阈值：水平移动小于此值视为"无明显移动"
    private static final double PAUSE_THRESHOLD = 0.1;
    // 瞬移判定阈值：水平移动大于此值视为"突然大位移"
    private static final double BLINK_TELEPORT_THRESHOLD = 3.0;
    // 基础暂停缓冲上限（tick 数），超过此值视为暂停发包
    private static final int BASE_PAUSE_BUFFER = 10; // 10 tick = 500ms
    // Blink 延迟补偿因子
    private static final double COMPENSATION_BLINK = 0.25;
    // 清理过期 tracker 的间隔（ms）
    private static final long TRACKER_EXPIRE_MS = 30000L;

    // 线程安全的 tracker 存储
    private final ConcurrentHashMap<UUID, BlinkTracker> trackers = new ConcurrentHashMap<>();

    public BlinkCheck(ANSACPlugin plugin) {
        super(plugin, "Blink", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式、载具中、睡眠、死亡玩家
        if (shouldSkip(player)) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 延迟补偿：延迟过高或突变时跳过检测
        if (data.getPingCompensator().shouldSkipCheck()) {
            BlinkTracker tracker = getTracker(player.getUniqueId());
            if (tracker != null) {
                tracker.pauseBuffer = 0;
            }
            return;
        }

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        // 豁免：鞘翅滑翔中
        if (player.isGliding()) {
            removeTracker(player.getUniqueId());
            return;
        }

        // 豁免：被击退后 1 秒内
        long now = System.currentTimeMillis();
        if ((now - data.getLastKnockbackTime()) < 1000L) {
            BlinkTracker tracker = getTracker(player.getUniqueId());
            if (tracker != null) {
                tracker.pauseBuffer = 0;
            }
            return;
        }

        // 豁免：疑似 TP 命令（单次位移超过 16 格，与 SpeedCheck 一致）
        double distSquared = from.distanceSquared(to);
        if (distSquared > 256.0) { // 16 格的平方
            removeTracker(player.getUniqueId());
            return;
        }

        double horizontalDist = data.getHorizontalDistance();
        boolean onGround = player.isOnGround();

        // 获取或创建 tracker
        BlinkTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new BlinkTracker(now, from.clone()));

        // 延迟补偿后的暂停缓冲上限
        int compensatedPauseBuffer = data.getPingCompensator().getCompensatedBuffer(
            BASE_PAUSE_BUFFER, COMPENSATION_BLINK);

        // 延迟补偿后的瞬移判定阈值
        double compensatedTeleportThreshold = data.getPingCompensator().getCompensatedThreshold(
            BLINK_TELEPORT_THRESHOLD, COMPENSATION_BLINK);

        // --- 阶段 1：检测"暂停发包" ---
        // 水平移动很小且不在地面 → 可能暂停了位置包发送
        if (horizontalDist < PAUSE_THRESHOLD && !onGround) {
            tracker.pauseBuffer++;
            tracker.lastCheckTime = now;
            tracker.lastCheckLocation = to.clone();

            // 如果暂停时间过长（超过补偿后的阈值），记录预期位置
            if (tracker.pauseBuffer > compensatedPauseBuffer) {
                tracker.expectedPosition = to.clone();
            }
            return;
        }

        // --- 阶段 2：检测"突然大位移"（Blink 释放） ---
        if (tracker.pauseBuffer > compensatedPauseBuffer
                && horizontalDist > compensatedTeleportThreshold) {
            // 玩家在暂停期间积累了大量移动，然后一次性释放
            // 计算严重程度：位移越大越严重
            double severity = horizontalDist / compensatedTeleportThreshold;

            flag(player, data, severity,
                String.format("包延迟瞬移: 暂停 %d tick 后瞬移 %.2f 格 (阈值: %.2f, 延迟 %s)",
                    tracker.pauseBuffer, horizontalDist, compensatedTeleportThreshold,
                    data.getPingCompensator().getPingStatus()));

            // 重置 tracker
            tracker.pauseBuffer = 0;
            tracker.expectedPosition = null;
            return;
        }

        // --- 正常移动：递减暂停缓冲 ---
        // 如果玩家在正常移动（有合理位移），逐渐减少暂停缓冲
        if (horizontalDist >= PAUSE_THRESHOLD) {
            // 正常移动，重置暂停缓冲
            tracker.pauseBuffer = 0;
            tracker.expectedPosition = null;
        }

        tracker.lastCheckTime = now;
        tracker.lastCheckLocation = to.clone();
    }

    /**
     * 判断是否应跳过检测。
     * 跳过创造/旁观模式、载具中、睡眠、死亡玩家。
     */
    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isInsideVehicle()
            || player.isSleeping()
            || player.isDead();
    }

    /**
     * 获取玩家的 BlinkTracker，不存在则返回 null。
     */
    private BlinkTracker getTracker(UUID uuid) {
        return trackers.get(uuid);
    }

    /**
     * 移除玩家的 BlinkTracker。
     */
    private void removeTracker(UUID uuid) {
        trackers.remove(uuid);
    }

    /**
     * 内部类：Blink 追踪器。
     * 存储每个玩家的暂停发包检测状态。
     */
    private static class BlinkTracker {
        /** 上次检查时间 */
        long lastCheckTime;
        /** 上次检查位置 */
        Location lastCheckLocation;
        /** 暂停缓冲计数（连续无明显移动的 tick 数） */
        int pauseBuffer;
        /** 暂停期间记录的预期位置 */
        Location expectedPosition;

        BlinkTracker(long lastCheckTime, Location lastCheckLocation) {
            this.lastCheckTime = lastCheckTime;
            this.lastCheckLocation = lastCheckLocation;
            this.pauseBuffer = 0;
            this.expectedPosition = null;
        }
    }
}
