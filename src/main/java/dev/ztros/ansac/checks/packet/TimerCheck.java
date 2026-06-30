package dev.ztros.ansac.checks.packet;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

/**
 * Timer check - detects game speed manipulation (speed hack / slow motion).
 *
 * <p>基于 GrimAC TimerA 的设计原理：</p>
 * <ul>
 *   <li>每个飞行包给余额 +50ms（客户端 20 TPS 固定速率）</li>
 *   <li>余额与墙钟时间(System.nanoTime)对比，超过即 Timer</li>
 *   <li>漂移容忍可配置，默认 120ms（GrimAC 默认值）</li>
 *   <li>Ping 自适应漂移：高延迟玩家获得更大漂移余量</li>
 *   <li>传送/世界切换时重置余额</li>
 * </ul>
 *
 * <p>本地测试环境说明：双 JVM(服务端+客户端)同机会产生高抖动，
 * 建议将 drift-ms 提高到 200-300ms。真实服务器用 120ms 即可。</p>
 *
 * 参考: GrimAC TimerA (drift=120ms, setback=10)
 * https://github.com/GrimAnticheat/Grim/blob/2.0/common/src/main/java/ac/grim/grimac/checks/impl/timer/Timer.java
 */
public class TimerCheck extends Check {

    // 50ms per packet (20 TPS client-side, fixed)
    private static final long TICK_NS = 50_000_000L;
    // 初始时钟偏移：60 秒前，提供启动缓冲
    private static final long INITIAL_OFFSET_NS = 60_000_000_000L;

    // 可配置参数
    private long driftNs = 120_000_000L;       // 默认 120ms
    private double pingDriftFactor = 0.5;       // 额外漂移 = ping * factor
    private long maxPingDriftNs = 200_000_000L; // 最多额外 200ms

    public TimerCheck(ANSACPlugin plugin) {
        super(plugin, "Timer", "Packet");
        loadTimerConfig();
    }

    private void loadTimerConfig() {
        String path = "checks.packet.timer";
        driftNs = (long) (plugin.getConfig().getDouble(path + ".drift-ms", 120.0) * 1_000_000L);
        pingDriftFactor = plugin.getConfig().getDouble(path + ".ping-drift-factor", 0.5);
        maxPingDriftNs = (long) (plugin.getConfig().getDouble(path + ".max-ping-drift", 200.0) * 1_000_000L);
    }

    @Override
    public void loadConfig() {
        super.loadConfig();
        loadTimerConfig();
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
            balance = now - getEffectiveDriftNs(data);
            data.setTimerBalance(balance);
            data.setLastFlyingPacket(System.currentTimeMillis());
            return;
        }

        // 每个飞行包给余额 +50ms
        balance += TICK_NS;

        // 地板机制：余额不能落后墙钟时间超过漂移容忍
        // 漂移容忍随玩家 ping 自适应：高延迟玩家获得更大余量
        long effectiveDrift = getEffectiveDriftNs(data);
        long floor = now - effectiveDrift;
        if (balance < floor) {
            balance = floor;
        }

        data.setTimerBalance(balance);

        // 核心检测：余额超过墙钟时间 → Timer
        if (balance > now) {
            double overMs = (balance - now) / 1_000_000.0;
            flag(player, data, Math.min(5.0, overMs / 50.0),
                String.format("Timer 加速: 余额超前墙钟 %.1fms (漂移容忍: %dms, 延迟 %s)",
                    overMs, effectiveDrift / 1_000_000, data.getPingCompensator().getPingStatus()));

            // 重置一个包的违规量
            balance -= TICK_NS;
            data.setTimerBalance(balance);
        }

        data.setLastFlyingPacket(System.currentTimeMillis());
    }

    /**
     * 计算有效漂移容忍：基础值 + ping 自适应
     * 高延迟玩家（如本地双 JVM 环境）获得更大余量
     */
    private long getEffectiveDriftNs(PlayerData data) {
        int ping = data.getPing();
        if (ping <= 0) return driftNs;
        long extraDrift = (long) Math.min(ping * pingDriftFactor * 1_000_000L, maxPingDriftNs);
        return driftNs + extraDrift;
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
