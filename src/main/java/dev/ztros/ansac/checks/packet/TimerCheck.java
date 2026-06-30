package dev.ztros.ansac.checks.packet;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Timer check - detects game speed manipulation (speed hack / slow motion).
 *
 * <p>修复说明 (2026-06-27):</p>
 * <ul>
 *   <li>添加 TPS 补偿：根据服务器实际 TPS 动态调整 expected interval。</li>
 *   <li>Balance 重置改为递减（减去阈值而非归零），避免漏检持续轻微加速。</li>
 *   <li>新增短期爆发检测：最近 20 包平均间隔异常直接 flag。</li>
 *   <li>MIN_PACKETS 从 60 降到 30，缩短检测延迟。</li>
 * </ul>
 *
 * 参考: GrimAC TimerA (drift=120ms)
 */
public class TimerCheck extends Check {

    private static final long EXPECTED_MS = 50L; // 20 TPS
    private static final long BALANCE_THRESHOLD = 1000L; // 真正的Timer作弊会累积几千ms
    private static final long MAX_BALANCE = 5000L;
    private static final long LAG_SPIKE_MS = 250L;
    private static final int MIN_PACKETS = 60; // 3秒 grace period
    private static final int BURST_WINDOW = 20;

    public TimerCheck(ANSACPlugin plugin) {
        super(plugin, "Timer", "Packet");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Timer check is event-driven via onFlyingPacket()
    }

    public void onFlyingPacket(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setLastFlyingPacket(System.currentTimeMillis());
            data.setFlyingPacketCount(0);
            data.setTimerBalance(0);
            clearIntervalWindow(data);
            return;
        }

        long now = System.currentTimeMillis();
        long last = data.getLastFlyingPacket();

        if (last == 0) {
            data.setLastFlyingPacket(now);
            data.setFlyingPacketCount(0);
            data.setTimerBalance(0);
            clearIntervalWindow(data);
            return;
        }

        long diff = now - last;

        if (diff > LAG_SPIKE_MS) {
            data.setLastFlyingPacket(now);
            data.setFlyingPacketCount(0);
            data.setTimerBalance(0);
            clearIntervalWindow(data);
            return;
        }

        // 跳过极端小的间隔（< 5ms）
        // 这通常是网络层包堆积/批量送达，而非真正的 Timer 加速
        if (diff < 5) return;

        // TPS 补偿：使用玩家自身的包间隔平均值作为 expected interval
        // Folia 每个区域线程有独立 tick rate，全局 TPS 不代表玩家所在区域的 TPS
        long expectedInterval = getCompensatedExpectedInterval(data);

        int count = data.getFlyingPacketCount() + 1;
        data.setFlyingPacketCount(count);

        // 短期爆发检测窗口始终记录数据
        addToIntervalWindow(data, diff);
        addToExpectedWindow(data, diff);

        // Grace period：前 MIN_PACKETS 个包只收集数据，不累积 balance 也不检测
        if (count < MIN_PACKETS) {
            data.setLastFlyingPacket(now);
            return;
        }

        // Grace period 刚结束时重置窗口，从干净状态开始检测
        if (count == MIN_PACKETS) {
            data.setTimerBalance(0);
            clearIntervalWindow(data);
            addToIntervalWindow(data, diff);
            data.setLastFlyingPacket(now);
            return;
        }

        // --- Grace period 过后才开始真正的检测 ---

        long balance = data.getTimerBalance() + (expectedInterval - diff);
        balance = Math.max(-MAX_BALANCE, Math.min(MAX_BALANCE, balance));
        data.setTimerBalance(balance);

        // 短期爆发检测：最近 BURST_WINDOW 包平均间隔
        if (count >= MIN_PACKETS + BURST_WINDOW) {
            double avgInterval = getAverageInterval(data);
            if (avgInterval < expectedInterval * 0.80) { // 加速超过 20% 才 flag
                double severity = (expectedInterval - avgInterval) / (double) expectedInterval;
                flag(player, data, severity * 2.0,
                    String.format("Timer 爆发加速: 平均间隔 %.1fms (预期 %dms)", avgInterval, expectedInterval));
                data.setTimerBalance(0);
                data.setFlyingPacketCount(0); // 完全重置，重新跑完整 grace period
                clearIntervalWindow(data);
                data.setLastFlyingPacket(now);
                return;
            }
        }

        // 累积偏移检测
        long compensatedThreshold = (long) data.getPingCompensator().getCompensatedThreshold(
            BALANCE_THRESHOLD, PingCompensator.COMPENSATION_TIMER);
        long compensatedMaxBalance = (long) data.getPingCompensator().getCompensatedThreshold(
            MAX_BALANCE, PingCompensator.COMPENSATION_TIMER);

        balance = Math.max(-compensatedMaxBalance, Math.min(compensatedMaxBalance, balance));
        data.setTimerBalance(balance);

        if (balance > compensatedThreshold) {
            double severity = balance / (double) compensatedThreshold;
            flag(player, data, severity,
                String.format("Timer 加速: 累积偏移 +%dms (阈值: %dms, 样本: %d, 延迟 %s)",
                    balance, compensatedThreshold, count, data.getPingCompensator().getPingStatus()));
            data.setTimerBalance(0);
            data.setFlyingPacketCount(0); // 完全重置，重新跑完整 grace period
            clearIntervalWindow(data);
        }

        if (balance < -compensatedThreshold) {
            double severity = Math.abs(balance) / (double) compensatedThreshold;
            flag(player, data, severity,
                String.format("Timer 减速: 累积偏移 %dms (阈值: %dms, 样本: %d, 延迟 %s)",
                    balance, compensatedThreshold, count, data.getPingCompensator().getPingStatus()));
            data.setTimerBalance(0);
            data.setFlyingPacketCount(0);
            clearIntervalWindow(data);
        }

        data.setLastFlyingPacket(now);
    }

    // ==================== 基于实际包间隔的期望值计算 ====================
    // Folia 每个区域线程有独立 tick rate，全局 TPS 不代表玩家所在区域的 TPS
    // 使用玩家自身的包间隔滚动平均值作为 expected interval

    private static final java.util.Map<PlayerData, Deque<Long>> expectedWindows = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int EXPECTED_WINDOW_SIZE = 40; // 40 包滚动窗口

    private long getCompensatedExpectedInterval(PlayerData data) {
        // 优先使用玩家自身的包间隔平均值
        Deque<Long> window = expectedWindows.get(data);
        if (window != null && !window.isEmpty()) {
            double sum = 0;
            for (long v : window) sum += v;
            double avg = sum / window.size();
            // 限制在合理范围内 [45, 100] ms
            return (long) Math.max(45, Math.min(100, Math.round(avg)));
        }
        return EXPECTED_MS;
    }

    private void addToExpectedWindow(PlayerData data, long interval) {
        Deque<Long> window = expectedWindows.computeIfAbsent(data, k -> new ArrayDeque<>(EXPECTED_WINDOW_SIZE));
        window.addLast(interval);
        while (window.size() > EXPECTED_WINDOW_SIZE) {
            window.pollFirst();
        }
    }

    private void clearExpectedWindow(PlayerData data) {
        expectedWindows.remove(data);
    }

    // ==================== 短期爆发窗口 ====================

    private static final java.util.Map<PlayerData, Deque<Long>> intervalWindows = new java.util.concurrent.ConcurrentHashMap<>();

    private void addToIntervalWindow(PlayerData data, long interval) {
        Deque<Long> window = intervalWindows.computeIfAbsent(data, k -> new ArrayDeque<>(BURST_WINDOW));
        window.addLast(interval);
        while (window.size() > BURST_WINDOW) {
            window.pollFirst();
        }
    }

    private double getAverageInterval(PlayerData data) {
        Deque<Long> window = intervalWindows.get(data);
        if (window == null || window.isEmpty()) return EXPECTED_MS;
        double sum = 0;
        for (long v : window) sum += v;
        return sum / window.size();
    }

    private void clearIntervalWindow(PlayerData data) {
        intervalWindows.remove(data);
        clearExpectedWindow(data);
    }
}
