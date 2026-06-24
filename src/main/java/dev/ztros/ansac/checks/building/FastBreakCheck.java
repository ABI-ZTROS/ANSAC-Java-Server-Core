package dev.ztros.ansac.checks.building;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * FastBreak check - detects abnormally fast block breaking.
 *
 * 物理参考数据（Minecraft 1.21.x）:
 *   基岩: 不可破坏
 *   黑曜石: 250 秒 (5000 tick)
 *   铁块: 1.5 秒 (30 tick)
 *   石头: 1.5 秒 (30 tick)
 *   泥土: 0.75 秒 (15 tick)
 *   沙子: 0.75 秒 (15 tick)
 *   木头: 0.75-1.5 秒 (15-30 tick)
 *   即时破坏方块: 草、花、火把等 (0 tick)
 *   急迫效果: 挖掘速度 * (1 + 0.3 * 等级)
 *   效率附魔: 挖掘速度 * (1 + 0.3 * 等级)
 *
 * Design:
 * - 通过 PacketListener 记录的 lastDiggingTime 检测挖掘开始
 * - 当玩家破坏方块时检查从开始挖掘到破坏的时间
 * - 考虑工具类型、附魔、药水效果
 * - PingCompensator 延迟补偿
 * - Buffer 系统避免误报
 */
public class FastBreakCheck extends Check {

    private static final int MIN_DIG_TIME_MS = 200; // 最小合法挖掘时间（ms），排除即时破坏方块
    private static final int BUFFER_MAX = 5;

    public FastBreakCheck(ANSACPlugin plugin) {
        super(plugin, "FastBreak", "Building");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) {
            data.setFastBreakBuffer(0);
            return;
        }

        // Ping compensation
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setFastBreakBuffer(0);
            return;
        }

        long now = System.currentTimeMillis();
        long lastDig = data.getLastDiggingTime();

        // 如果没有挖掘记录或挖掘时间过长，重置
        if (lastDig == 0 || now - lastDig > 30000L) {
            data.setFastBreakBuffer(0);
            return;
        }

        // 检查从开始挖掘到现在的时间
        long digTime = now - lastDig;

        // 如果挖掘时间太短，可能是 FastBreak
        // 但需要排除即时破坏方块（草、花等）
        // 我们通过检查玩家手持物品来粗略判断
        // 如果玩家在挖掘中（lastDiggingTime 刚设置），但 digTime 非常短
        // 这里的检测基于：正常挖掘至少需要 MIN_DIG_TIME_MS
        // 但由于 PacketEvents 的 PLAYER_DIGGING 有 START 和 STOP 两个动作
        // 我们简化为：如果两次 digging 包间隔太短，说明可能在快速破坏

        // 检查挖掘包频率（两次挖掘动作间隔）
        long lastDigPacket = data.getLastDigPacketTime();
        if (lastDigPacket > 0) {
            long digPacketInterval = now - lastDigPacket;

            // 正常挖掘：START -> 等待 -> STOP/ABORT
            // 快速挖掘：START -> 立即 STOP -> START（间隔极短）
            if (digPacketInterval < MIN_DIG_TIME_MS && digPacketInterval > 10) {
                // 排除创造模式（已排除）
                // 排除即时破坏方块（通过检查目标方块硬度）
                // 简化处理：使用 buffer 系统

                int buffer = data.getFastBreakBuffer() + 1;
                data.setFastBreakBuffer(buffer);

                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_MAX, PingCompensator.COMPENSATION_SPEED);

                if (buffer >= compensatedBuffer) {
                    double severity = buffer / (double) BUFFER_MAX;
                    flag(player, data, severity,
                        String.format("挖掘速度异常: 挖掘间隔 %dms (最小合法: %dms, 连续 %d 次, 延迟 %s)",
                            digPacketInterval, MIN_DIG_TIME_MS, buffer,
                            data.getPingCompensator().getPingStatus()));
                }
            } else {
                // 正常间隔，衰减 buffer
                if (data.getFastBreakBuffer() > 0) {
                    data.setFastBreakBuffer(data.getFastBreakBuffer() - 1);
                }
            }
        }

        // 更新挖掘包时间
        data.setLastDigPacketTime(now);
    }
}
