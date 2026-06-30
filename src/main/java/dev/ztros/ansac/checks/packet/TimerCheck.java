package dev.ztros.ansac.checks.packet;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

/**
 * Timer check - detects game speed manipulation (speed hack / slow motion).
 *
 * <p>基于 GrimAC TimerA 的设计原理重写：</p>
 * <ul>
 *   <li>每个飞行包给余额 +50ms（客户端 20 TPS 固定速率）</li>
 *   <li>余额与墙钟时间(System.nanoTime)对比，超过即 Timer</li>
 *   <li>漂移容忍 120ms（地板机制，防止余额过度下探被利用）</li>
 *   <li>不使用服务器 TPS 补偿——客户端始终以 20 TPS 发包，与服务器 TPS 无关</li>
 *   <li>不使用玩家包间隔平均值——Timer 作弊者的平均值也会缩短，导致永远检测不到</li>
 *   <li>传送/世界切换时重置余额</li>
 * </ul>
 *
 * 参考: GrimAC TimerA (drift=120ms, setback=10, 50ms expected)
 * https://github.com/GrimAnticheat/Grim/blob/2.0/common/src/main/java/ac/grim/grimac/checks/impl/timer/Timer.java
 */
public class TimerCheck extends Check {

    // 50ms per packet (20 TPS client-side, fixed)
    private static final long TICK_NS = 50_000_000L;
    // 漂移容忍：余额不能落后墙钟时间超过 120ms
    // 这是 GrimAC 的默认值，足以吸收网络抖动
    private static final long DRIFT_NS = 120_000_000L;
    // 初始时钟偏移：60 秒前，提供启动缓冲
    private static final long INITIAL_OFFSET_NS = 60_000_000_000L;
    // lag spike 阈值：超过此间隔视为网络中断，重置
    private static final long LAG_SPIKE_NS = 500_000_000L; // 500ms

    public TimerCheck(ANSACPlugin plugin) {
        super(plugin, "Timer", "Packet");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Timer check is event-driven via onFlyingPacket()
    }

    public void onFlyingPacket(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // Ping 补偿器要求跳过检查（极端高延迟）
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setLastFlyingPacket(System.currentTimeMillis());
            data.setTimerBalance(System.nanoTime() - INITIAL_OFFSET_NS);
            return;
        }

        long now = System.nanoTime();
        long balance = data.getTimerBalance();

        // 首次初始化：余额设为 60 秒前，提供启动缓冲
        if (balance == 0) {
            balance = now - INITIAL_OFFSET_NS;
            data.setTimerBalance(balance);
            data.setLastFlyingPacket(System.currentTimeMillis());
            return;
        }

        // 检测 lag spike：如果距上一个包超过 500ms，重置
        long lastMs = data.getLastFlyingPacket();
        if (lastMs > 0 && (System.currentTimeMillis() - lastMs) > 500) {
            // 重置到地板位置
            balance = now - DRIFT_NS;
            data.setTimerBalance(balance);
            data.setLastFlyingPacket(System.currentTimeMillis());
            return;
        }

        // 每个飞行包给余额 +50ms
        balance += TICK_NS;

        // 地板机制：余额不能落后墙钟时间超过 120ms
        // 防止作弊者"攒额度"后爆发，也防止卡顿导致余额过度下探
        long floor = now - DRIFT_NS;
        if (balance < floor) {
            balance = floor;
        }

        data.setTimerBalance(balance);

        // 核心检测：余额超过墙钟时间 → Timer
        if (balance > now) {
            double overMs = (balance - now) / 1_000_000.0;
            flag(player, data, Math.min(5.0, overMs / 50.0),
                String.format("Timer 加速: 余额超前墙钟 %.1fms (漂移容忍: %dms)",
                    overMs, DRIFT_NS / 1_000_000));

            // 重置一个包的违规量（不是完全重置，保留余量继续检测）
            balance -= TICK_NS;
            data.setTimerBalance(balance);
        }

        data.setLastFlyingPacket(System.currentTimeMillis());
    }

    /**
     * 世界切换/传送时调用，重置 Timer 余额
     */
    public void onWorldChange(PlayerData data) {
        if (data != null) {
            data.setTimerBalance(System.nanoTime() - INITIAL_OFFSET_NS);
            data.setLastFlyingPacket(System.currentTimeMillis());
        }
    }

    @Override
    public void onPlayerQuit(java.util.UUID uuid) {
        // PlayerData 会被清理，无需额外操作
    }
}
