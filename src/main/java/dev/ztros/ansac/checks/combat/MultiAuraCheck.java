package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * MultiAura check - detects multi-target attack cheats.
 *
 * 作弊原理（基于 Wurst/Meteor Client 源码分析）:
 *   Wurst MultiAura: 同一 tick 内攻击多个实体，绕过单目标 KillAura 检测。
 *     客户端在 PlayerTick 中遍历所有可见实体，对每个目标发送 INTERACT_ENTITY ATTACK 包。
 *   Wurst ClickAura: 类似 MultiAura，但使用更智能的目标选择（距离排序、优先级过滤）。
 *   Meteor KillAura (多目标模式): 同一 tick 切换目标攻击多个实体，
 *     默认单目标但可配置多目标模式，攻击间隔极短。
 *
 * 检测核心:
 *   正常 Minecraft 客户端每个 tick 只能攻击一个实体（服务端限制）。
 *   作弊器通过发送多个 INTERACT_ENTITY ATTACK 包在同一 tick 内攻击多个目标。
 *   即使不在同一 tick，极短时间内（50ms）攻击不同目标也是明确的作弊特征。
 *
 * Design:
 *   - 使用 ConcurrentHashMap<UUID, MultiAuraTracker> 存储每个玩家的攻击记录
 *   - 记录每次攻击的时间戳和目标 entity ID
 *   - 检查 50ms 窗口内是否攻击了 >= 2 个不同目标
 *   - Buffer 系统 + PingCompensator 延迟补偿
 *   - 无豁免（多目标攻击本身就是明确的作弊特征）
 */
public class MultiAuraCheck extends Check {

    // 检测窗口：50ms 内攻击不同目标视为可疑
    private static final long MULTI_ATTACK_WINDOW_MS = 50L;
    // 最少不同目标数触发检测
    private static final int MIN_DIFFERENT_TARGETS = 2;
    // Buffer 阈值：连续触发次数
    private static final int BUFFER_MAX = 3;
    // 旧记录清理阈值（1秒）
    private static final long RECORD_EXPIRE_MS = 1000L;
    // 延迟补偿因子
    private static final double COMPENSATION_FACTOR = PingCompensator.COMPENSATION_KILLAURA;

    // 线程安全的玩家追踪数据
    private final ConcurrentHashMap<UUID, MultiAuraTracker> trackers = new ConcurrentHashMap<>();

    public MultiAuraCheck(ANSACPlugin plugin) {
        super(plugin, "MultiAura", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // 定期清理过期数据和离线玩家的追踪记录
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();

        MultiAuraTracker tracker = trackers.get(uuid);
        if (tracker == null) return;

        // 清理超过 1 秒的旧记录
        tracker.cleanup(now);

        // 如果 tracker 已空，移除以节省内存
        if (tracker.attackTimestamps.isEmpty()) {
            trackers.remove(uuid);
        }
    }

    /**
     * 处理攻击事件（由 PacketListener 调用）。
     * 记录攻击时间戳和目标 entity ID，检测多目标攻击模式。
     *
     * @param attacker 攻击者
     * @param data     攻击者的 PlayerData
     * @param target   被攻击的实体
     */
    public void processAttack(Player attacker, PlayerData data, Entity target) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式
        String gm = attacker.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) return;

        // 跳过载具中的玩家
        if (attacker.isInsideVehicle()) return;

        // 跳过睡眠中的玩家
        if (attacker.isSleeping()) return;

        // 跳过死亡玩家
        if (attacker.getHealth() <= 0 || attacker.isDead()) return;

        // Ping compensation: 延迟过高或突变时跳过检测
        if (data.getPingCompensator().shouldSkipCheck()) return;

        UUID uuid = attacker.getUniqueId();
        long now = System.currentTimeMillis();

        // 获取或创建 tracker
        MultiAuraTracker tracker = trackers.computeIfAbsent(uuid, k -> new MultiAuraTracker());

        // 清理过期记录
        tracker.cleanup(now);

        // 记录当前攻击
        int targetEntityId = target.getEntityId();
        tracker.attackTimestamps.add(now);
        tracker.targetIds.add(targetEntityId);

        // 检查 50ms 窗口内是否攻击了多个不同目标
        long windowStart = now - MULTI_ATTACK_WINDOW_MS;
        Set<Integer> recentTargets = new HashSet<>();

        // 遍历最近的攻击记录，收集窗口内的不同目标
        for (int i = 0; i < tracker.attackTimestamps.size(); i++) {
            Long timestamp = tracker.attackTimestamps.get(i);
            if (timestamp >= windowStart) {
                Integer tid = tracker.targetIds.get(i);
                if (tid != null) {
                    recentTargets.add(tid);
                }
            }
        }

        // 如果 50ms 内攻击了 >= 2 个不同目标，增加 buffer
        if (recentTargets.size() >= MIN_DIFFERENT_TARGETS) {
            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_MAX, COMPENSATION_FACTOR);

            tracker.multiAuraBuffer++;
            if (tracker.multiAuraBuffer >= compensatedBuffer) {
                double severity = tracker.multiAuraBuffer / (double) BUFFER_MAX;
                flag(attacker, data, severity,
                    String.format("多目标攻击: %dms 内攻击了 %d 个不同目标 (连续 %d 次, 延迟 %s)",
                        MULTI_ATTACK_WINDOW_MS, recentTargets.size(), tracker.multiAuraBuffer,
                        data.getPingCompensator().getPingStatus()));
                // flag 后重置 buffer，避免重复报告
                tracker.multiAuraBuffer = 0;
            }
        } else {
            // 正常攻击，逐渐衰减 buffer
            if (tracker.multiAuraBuffer > 0) {
                tracker.multiAuraBuffer--;
            }
        }
    }

    /**
     * 内部类：存储每个玩家的多目标攻击追踪数据。
     * 使用 CopyOnWriteArrayList 保证线程安全。
     */
    private static class MultiAuraTracker {
        // 每次攻击的时间戳
        final CopyOnWriteArrayList<Long> attackTimestamps = new CopyOnWriteArrayList<>();
        // 每次攻击的目标 entity ID（与 attackTimestamps 一一对应）
        final CopyOnWriteArrayList<Integer> targetIds = new CopyOnWriteArrayList<>();
        // 违规 buffer
        int multiAuraBuffer = 0;

        /**
         * 清理超过 RECORD_EXPIRE_MS 的旧记录
         */
        void cleanup(long now) {
            long cutoff = now - RECORD_EXPIRE_MS;

            // 从头部移除过期记录（时间戳是有序的）
            while (!attackTimestamps.isEmpty() && attackTimestamps.get(0) < cutoff) {
                attackTimestamps.remove(0);
                if (!targetIds.isEmpty()) {
                    targetIds.remove(0);
                }
            }
        }
    }
}
