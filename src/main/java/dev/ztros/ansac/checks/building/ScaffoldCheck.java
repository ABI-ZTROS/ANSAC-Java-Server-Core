package dev.ztros.ansac.checks.building;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

/**
 * Scaffold check - detects automated block placement (auto-scaffold/bridge).
 *
 * 物理参考数据（Minecraft 1.21.x）:
 *   正常方块放置间隔: 200-600ms (人类波动大)
 *   作弊器放置间隔: ~50ms (每刻一个，非常规律)
 *   搭路视角: pitch 接近 -90 (向下看)
 *   搭路时水平移动: 玩家在移动的同时放置方块
 *
 * Design:
 * - 通过 PacketListener 记录的 lastBlockPlaceTime 和 blockPlaceCount 检测放置频率
 * - 通过 PlayerMoveEvent 更新的位置数据检测是否在移动中放置
 * - PingCompensator 延迟补偿
 * - Buffer 系统避免误报
 */
public class ScaffoldCheck extends Check {

    private static final long MIN_PLACE_INTERVAL = 80; // ms, 最小合法放置间隔
    private static final long BOT_PLACE_INTERVAL = 60; // ms, 作弊器典型间隔
    private static final double MIN_SCAFFOLD_PITCH = -70.0; // 搭路时 pitch 应低于此值
    private static final int CONSECUTIVE_PLACES_MAX = 4; // 连续放置计数阈值
    private static final int BUFFER_MAX = 6;
    private static final int PLACE_WINDOW_MS = 1000; // 1秒窗口

    public ScaffoldCheck(ANSACPlugin plugin) {
        super(plugin, "Scaffold", "Building");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) {
            data.setScaffoldBuffer(0);
            return;
        }

        // Ping compensation
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setScaffoldBuffer(0);
            return;
        }

        long now = System.currentTimeMillis();
        long lastPlace = data.getLastBlockPlaceTime();

        // 如果最近没有放置方块，重置
        if (lastPlace == 0 || now - lastPlace > PLACE_WINDOW_MS) {
            data.setScaffoldBuffer(0);
            data.setBlockPlaceCount(0);
            return;
        }

        // 检查放置频率
        long interval = now - lastPlace;

        // 检查视角是否适合搭路
        float pitch = player.getLocation().getPitch();
        boolean scaffoldPitch = pitch < MIN_SCAFFOLD_PITCH;

        // 检查是否在移动中（搭路时玩家应该是在移动的）
        double horizontalDist = data.getHorizontalDistance();
        boolean isMoving = horizontalDist > 0.1;

        // 核心检测：快速规律放置 + 视角向下 + 移动中
        if (interval < MIN_PLACE_INTERVAL && scaffoldPitch && isMoving) {
            // 非常规律的快速放置 = 高度可疑
            if (interval < BOT_PLACE_INTERVAL) {
                int buffer = data.getScaffoldBuffer() + 1;
                data.setScaffoldBuffer(buffer);

                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_MAX, PingCompensator.COMPENSATION_SPEED);

                if (buffer >= compensatedBuffer) {
                    double severity = buffer / (double) BUFFER_MAX;
                    flag(player, data, severity,
                        String.format("自动搭路: 放置间隔 %dms (pitch=%.1f, 移动=%.2f, 连续 %d tick, 延迟 %s)",
                            interval, pitch, horizontalDist, buffer,
                            data.getPingCompensator().getPingStatus()));
                }
            } else {
                // 较快但不极端，使用较低权重
                int buffer = data.getScaffoldBuffer() + 1;
                data.setScaffoldBuffer(buffer);

                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_MAX + 3, PingCompensator.COMPENSATION_SPEED); // 更高阈值

                if (buffer >= compensatedBuffer) {
                    double severity = buffer / (double) (BUFFER_MAX + 3);
                    flag(player, data, severity,
                        String.format("疑似搭路: 放置间隔 %dms (pitch=%.1f, 移动=%.2f, 连续 %d tick, 延迟 %s)",
                            interval, pitch, horizontalDist, buffer,
                            data.getPingCompensator().getPingStatus()));
                }
            }
        } else {
            // 正常放置模式，逐渐衰减 buffer
            if (data.getScaffoldBuffer() > 0) {
                data.setScaffoldBuffer(data.getScaffoldBuffer() - 1);
            }
        }
    }
}
