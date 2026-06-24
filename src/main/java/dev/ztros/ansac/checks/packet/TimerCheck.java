package dev.ztros.ansac.checks.packet;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

/**
 * Timer check - detects game speed manipulation (speed hack / slow motion).
 *
 * 参考: GrimAC TimerA (drift=120ms), TimerLimit (ping-abuse-limit=1000ms)
 *   GrimAC 精度: 1.005 timer (0.5% 偏差可检测)
 *   System.currentTimeMillis() 精度: 1-15ms (取决于操作系统)
 *   正常客户端飞行包间隔: ~50ms (20 TPS), 实际波动 40-60ms
 *
 * Design: Cumulative balance approach (inspired by GrimAC).
 * - Each expected tick adds +50ms to balance.
 * - Each actual packet interval subtracts the real time from balance.
 * - Positive balance = client is sending faster than expected (speed hack).
 * - Negative balance = client is sending slower than expected (slow motion).
 * - This naturally smooths out single-packet jitter.
 */
public class TimerCheck extends Check {

    private static final long EXPECTED_MS = 50L; // 20 TPS
    private static final long BALANCE_THRESHOLD = 120L; // 参考 GrimAC TimerA drift=120ms
    private static final long MAX_BALANCE = 600L; // 参考 GrimAC NegativeTimer drift=1200ms 的一半
    private static final long LAG_SPIKE_MS = 250L; // Reset on lag spike
    private static final int MIN_PACKETS = 60; // 约 3 秒样本（之前 100 太保守）

    public TimerCheck(ANSACPlugin plugin) {
        super(plugin, "Timer", "Packet");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Timer check is event-driven via onFlyingPacket()
    }

    /**
     * Called from PacketListener when a flying packet is received.
     */
    public void onFlyingPacket(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setLastFlyingPacket(System.currentTimeMillis());
            data.setFlyingPacketCount(0);
            data.setTimerBalance(0);
            return;
        }

        long now = System.currentTimeMillis();
        long last = data.getLastFlyingPacket();

        // First packet since join / reset
        if (last == 0) {
            data.setLastFlyingPacket(now);
            data.setFlyingPacketCount(0);
            data.setTimerBalance(0);
            return;
        }

        long diff = now - last;

        // Lag spike: reset everything
        if (diff > LAG_SPIKE_MS) {
            data.setLastFlyingPacket(now);
            data.setFlyingPacketCount(0);
            data.setTimerBalance(0);
            return;
        }

        // Ignore unreasonably small intervals (packet batching)
        if (diff < 5) {
            return;
        }

        // Update balance: expected - actual
        // If client is speeding (sending more often), diff < 50, balance goes up
        // If client is slowing (sending less often), diff > 50, balance goes down
        long balance = data.getTimerBalance() + (EXPECTED_MS - diff);

        // Clamp balance to prevent extreme runaway values
        balance = Math.max(-MAX_BALANCE, Math.min(MAX_BALANCE, balance));
        data.setTimerBalance(balance);

        int count = data.getFlyingPacketCount() + 1;
        data.setFlyingPacketCount(count);

        // Only flag after enough packets to establish a trend
        if (count < MIN_PACKETS) {
            data.setLastFlyingPacket(now);
            return;
        }

        // Ping-compensated thresholds
        long compensatedThreshold = (long) data.getPingCompensator().getCompensatedThreshold(
            BALANCE_THRESHOLD, PingCompensator.COMPENSATION_TIMER);
        long compensatedMaxBalance = (long) data.getPingCompensator().getCompensatedThreshold(
            MAX_BALANCE, PingCompensator.COMPENSATION_TIMER);

        // Re-clamp with compensated max balance
        balance = Math.max(-compensatedMaxBalance, Math.min(compensatedMaxBalance, balance));
        data.setTimerBalance(balance);

        // Speed timer: balance too positive (client sending faster than expected)
        if (balance > compensatedThreshold) {
            double severity = balance / (double) compensatedThreshold;
            flag(player, data, severity,
                String.format("Timer 加速: 累积偏移 +%dms (阈值: %dms, 样本: %d, 延迟 %s)",
                    balance, compensatedThreshold, count, data.getPingCompensator().getPingStatus()));
            // Reset balance after flag to avoid repeated flags
            data.setTimerBalance(0);
        }

        // Slow timer: balance too negative (client sending slower than expected)
        if (balance < -compensatedThreshold) {
            double severity = Math.abs(balance) / (double) compensatedThreshold;
            flag(player, data, severity,
                String.format("Timer 减速: 累积偏移 %dms (阈值: %dms, 样本: %d, 延迟 %s)",
                    balance, compensatedThreshold, count, data.getPingCompensator().getPingStatus()));
            data.setTimerBalance(0);
        }

        data.setLastFlyingPacket(now);
    }
}
