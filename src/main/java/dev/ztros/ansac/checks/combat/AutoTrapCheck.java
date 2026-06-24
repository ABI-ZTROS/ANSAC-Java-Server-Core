package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AutoTrap check - detects automated block-trapping cheats.
 *
 * 作弊原理（基于 Meteor Client 源码分析）:
 *   Meteor AutoTrap: 自动在目标周围放置方块（Obsidian/TNT 等）来困住目标。
 *     工作流程：
 *     1. 扫描附近敌人，选择最佳目标
 *     2. 计算目标周围的空位（头部、脚部、四周）
 *     3. 自动切换到黑曜石等方块
 *     4. 快速连续放置方块，围绕目标形成封闭空间
 *     放置速度极快（每 tick 多个方块），且位置精确围绕目标。
 *
 * 正常玩家行为:
 *   正常玩家手动放置方块速度有限（~200-600ms/个），且不会精确围绕另一个玩家放置。
 *   即使在 PVP 中，正常玩家也不会在 500ms 内围绕目标放置 4+ 个方块。
 *
 * 检测核心:
 *   1. 检测短时间内的快速方块放置频率（500ms 内 >= 4 个方块）
 *   2. 检测放置位置是否围绕某个其他玩家（>= 3 个方块在某个玩家 2 格范围内）
 *   3. 两个条件同时满足时标记为 AutoTrap
 *
 * Design:
 *   - 使用 ConcurrentHashMap<UUID, AutoTrapTracker> 存储每个玩家的追踪数据
 *   - 记录放置时间戳和位置
 *   - 分析放置频率和空间分布
 *   - Buffer 系统 + PingCompensator 延迟补偿
 *   - 豁免：创造模式玩家
 */
public class AutoTrapCheck extends Check {

    // 快速放置检测窗口（500ms）
    private static final long FAST_PLACE_WINDOW_MS = 500L;
    // 窗口内最少放置方块数触发频率检测
    private static final int MIN_FAST_PLACE_COUNT = 4;
    // 围绕目标的最少方块数
    private static final int MIN_BLOCKS_AROUND_TARGET = 3;
    // 围绕目标的距离阈值（2格）
    private static final double TRAP_RADIUS = 2.0;
    // Buffer 阈值：连续触发次数
    private static final int BUFFER_MAX = 3;
    // 旧记录清理阈值（2秒）
    private static final long RECORD_EXPIRE_MS = 2000L;
    // 延迟补偿因子
    private static final double COMPENSATION_FACTOR = PingCompensator.COMPENSATION_SPEED;

    // 线程安全的玩家追踪数据
    private final ConcurrentHashMap<UUID, AutoTrapTracker> trackers = new ConcurrentHashMap<>();

    public AutoTrapCheck(ANSACPlugin plugin) {
        super(plugin, "AutoTrap", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // 定期清理过期数据和离线玩家的追踪记录
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();

        AutoTrapTracker tracker = trackers.get(uuid);
        if (tracker == null) return;

        // 清理超过 2 秒的旧记录
        tracker.cleanup(now);

        // 如果 tracker 已空，移除以节省内存
        if (tracker.placeTimestamps.isEmpty()) {
            trackers.remove(uuid);
        }
    }

    /**
     * 处理方块放置事件（由 PacketListener 调用）。
     * 检测快速围绕其他玩家放置方块的行为。
     *
     * @param player       放置方块的玩家
     * @param data         玩家的 PlayerData
     * @param placeLocation 方块放置的位置
     */
    public void processBlockPlace(Player player, PlayerData data, Location placeLocation) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) return;

        // 跳过载具中的玩家
        if (player.isInsideVehicle()) return;

        // 跳过睡眠中的玩家
        if (player.isSleeping()) return;

        // 跳过死亡玩家
        if (player.getHealth() <= 0 || player.isDead()) return;

        // Ping compensation: 延迟过高或突变时跳过检测
        if (data.getPingCompensator().shouldSkipCheck()) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        // 获取或创建 tracker
        AutoTrapTracker tracker = trackers.computeIfAbsent(uuid, k -> new AutoTrapTracker());

        // 清理过期记录
        tracker.cleanup(now);

        // 记录当前放置
        tracker.placeTimestamps.add(now);
        tracker.placeLocations.add(placeLocation.clone());

        // 检查 500ms 窗口内的放置频率
        long windowStart = now - FAST_PLACE_WINDOW_MS;
        int recentPlaceCount = 0;
        int recentStartIndex = 0;

        for (int i = 0; i < tracker.placeTimestamps.size(); i++) {
            Long timestamp = tracker.placeTimestamps.get(i);
            if (timestamp >= windowStart) {
                if (recentPlaceCount == 0) {
                    recentStartIndex = i;
                }
                recentPlaceCount++;
            }
        }

        // 如果 500ms 内放置了 >= 4 个方块，检查是否围绕某个玩家
        if (recentPlaceCount >= MIN_FAST_PLACE_COUNT) {
            // 检查附近是否有其他玩家被这些方块围绕
            boolean isTrapping = checkBlocksAroundPlayer(
                player, tracker, recentStartIndex, recentPlaceCount);

            if (isTrapping) {
                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_MAX, COMPENSATION_FACTOR);

                tracker.autoTrapBuffer++;
                if (tracker.autoTrapBuffer >= compensatedBuffer) {
                    double severity = tracker.autoTrapBuffer / (double) BUFFER_MAX;
                    flag(player, data, severity,
                        String.format("自动陷阱: %dms 内放置 %d 个方块且围绕目标 (连续 %d 次, 延迟 %s)",
                            FAST_PLACE_WINDOW_MS, recentPlaceCount, tracker.autoTrapBuffer,
                            data.getPingCompensator().getPingStatus()));
                    // flag 后重置 buffer
                    tracker.autoTrapBuffer = 0;
                }
            } else {
                // 放置频率快但未围绕目标，衰减 buffer
                if (tracker.autoTrapBuffer > 0) {
                    tracker.autoTrapBuffer--;
                }
            }
        } else {
            // 正常放置频率，衰减 buffer
            if (tracker.autoTrapBuffer > 0) {
                tracker.autoTrapBuffer--;
            }
        }
    }

    /**
     * 检查最近放置的方块是否围绕某个其他玩家。
     * 遍历附近的所有其他玩家，检查是否有 >= MIN_BLOCKS_AROUND_TARGET 个方块
     * 在某个玩家的 TRAP_RADIUS 范围内。
     *
     * @param placer          放置方块的玩家（排除自身）
     * @param tracker         放置者的追踪数据
     * @param startIndex      最近放置记录的起始索引
     * @param count           最近放置的方块数量
     * @return 是否有方块围绕某个其他玩家
     */
    private boolean checkBlocksAroundPlayer(Player placer, AutoTrapTracker tracker,
                                            int startIndex, int count) {
        // 遍历附近的所有其他玩家
        for (Player nearbyPlayer : plugin.getServer().getOnlinePlayers()) {
            // 排除放置者自身
            if (nearbyPlayer.getUniqueId().equals(placer.getUniqueId())) continue;

            // 排除死亡玩家
            if (nearbyPlayer.getHealth() <= 0 || nearbyPlayer.isDead()) continue;

            // 排除距离过远的玩家（优化性能，只检查 10 格内的玩家）
            if (!nearbyPlayer.getWorld().equals(placer.getWorld())) continue;
            double playerDist = nearbyPlayer.getLocation().distance(placer.getLocation());
            if (playerDist > 10.0) continue;

            // 计算在这个玩家 TRAP_RADIUS 范围内的方块数量
            Location targetLoc = nearbyPlayer.getLocation();
            int blocksNearTarget = 0;

            for (int i = startIndex; i < tracker.placeLocations.size() && i < startIndex + count; i++) {
                Location blockLoc = tracker.placeLocations.get(i);
                if (blockLoc == null) continue;

                // 检查方块位置是否在目标玩家的 TRAP_RADIUS 范围内
                double dist = blockLoc.distance(targetLoc);
                if (dist <= TRAP_RADIUS) {
                    blocksNearTarget++;
                }
            }

            // 如果 >= 3 个方块在目标玩家 2 格范围内，判定为陷阱
            if (blocksNearTarget >= MIN_BLOCKS_AROUND_TARGET) {
                return true;
            }
        }

        return false;
    }

    /**
     * 内部类：存储每个玩家的自动陷阱追踪数据。
     * 使用 CopyOnWriteArrayList 保证线程安全。
     */
    private static class AutoTrapTracker {
        // 每次放置的时间戳
        final CopyOnWriteArrayList<Long> placeTimestamps = new CopyOnWriteArrayList<>();
        // 每次放置的位置（与 placeTimestamps 一一对应）
        final CopyOnWriteArrayList<Location> placeLocations = new CopyOnWriteArrayList<>();
        // 违规 buffer
        int autoTrapBuffer = 0;

        /**
         * 清理超过 RECORD_EXPIRE_MS 的旧记录
         */
        void cleanup(long now) {
            long cutoff = now - RECORD_EXPIRE_MS;

            // 从头部移除过期记录（时间戳是有序的）
            while (!placeTimestamps.isEmpty() && placeTimestamps.get(0) < cutoff) {
                placeTimestamps.remove(0);
                if (!placeLocations.isEmpty()) {
                    placeLocations.remove(0);
                }
            }
        }
    }
}
