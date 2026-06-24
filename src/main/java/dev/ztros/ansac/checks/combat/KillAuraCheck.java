package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * KillAura check - detects automated combat (aimbot/clicker).
 *
 * CPS 参考（cpsmeter.com）:
 *   普通点击: 6-7 CPS
 *   Jitter 点击: 12-16 CPS (最高约 18 CPS)
 *   Butterfly 点击: 15-25 CPS (最高约 28 CPS 短爆发)
 *   Drag 点击: 30-100+ CPS (依赖硬件)
 *   正常人类持续稳定极限: ~14-16 CPS
 *
 * Design notes:
 * - processSwing() ONLY records swing timestamps, NEVER flags.
 * - processAttack() checks for suspicious attack patterns.
 * - Clicker detection: CPS during actual combat (attacks with entities).
 * - No-swing detection: attack without recent arm swing.
 * - Inhuman consistency: attack intervals too uniform (bots have near-perfect timing).
 * - Pattern analysis: attack interval consistency and target switching detection.
 */
public class KillAuraCheck extends Check {

    private static final long MIN_CLICK_INTERVAL = 45; // ms
    private static final double MAX_CPS = 16.0; // 正常人类极限（之前 18 太宽松）
    private static final int CPS_WINDOW_MS = 1000;
    private static final int BUFFER_MAX = 3; // Require 3 consecutive violations

    // Pattern detection thresholds
    private static final int PATTERN_INTERVAL_SAMPLE_SIZE = 10; // 最近10次攻击间隔
    private static final double PATTERN_INTERVAL_STD_DEV_THRESHOLD = 20.0; // 标准差 < 20ms 可疑
    private static final int PATTERN_INTERVAL_BUFFER_MAX = 3; // 间隔一致性 buffer
    private static final long TARGET_SWITCH_WINDOW_MS = 100; // 100ms 内切换目标
    private static final int TARGET_SWITCH_BUFFER_MAX = 3; // 目标切换 buffer

    private final ConcurrentHashMap<UUID, PatternTracker> patternTrackers = new ConcurrentHashMap<>();

    public KillAuraCheck(ANSACPlugin plugin) {
        super(plugin, "KillAura", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Periodic cleanup of old click timestamps
        long cutoff = System.currentTimeMillis() - CPS_WINDOW_MS;
        data.getClickTimestamps().removeIf(t -> t < cutoff);
    }

    /**
     * Process arm swing (called from packet listener).
     * ONLY records the swing time. Never flags here — swinging at air is normal.
     */
    public void processSwing(Player player, PlayerData data) {
        data.setLastSwingTime(System.currentTimeMillis());
    }

    /**
     * Process attack event (called from packet listener).
     * This is where actual killaura detection happens.
     * Backward-compatible overload without target entity (target switching detection skipped).
     */
    public void processAttack(Player player, PlayerData data) {
        processAttack(player, data, null);
    }

    /**
     * Process attack event with target entity information.
     * Enables full pattern analysis including target switching detection.
     */
    public void processAttack(Player player, PlayerData data, Entity target) {
        if (!isEnabled() || data.hasBypass()) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            data.setNoSwingBuffer(0);
            data.setFastClickBuffer(0);
            data.setLastAttackTime(System.currentTimeMillis());
            return;
        }

        long now = System.currentTimeMillis();
        long lastAttack = data.getLastAttackTime();

        // Ping-compensated thresholds
        int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
            BUFFER_MAX, PingCompensator.COMPENSATION_KILLAURA);
        double compensatedMaxCps = data.getPingCompensator().getCompensatedSpeed(
            MAX_CPS, PingCompensator.COMPENSATION_KILLAURA);
        long compensatedMinInterval = (long) data.getPingCompensator().getCompensatedThreshold(
            MIN_CLICK_INTERVAL, PingCompensator.COMPENSATION_KILLAURA);

        // --- Check 1: Attack without recent arm swing ---
        long lastSwing = data.getLastSwingTime();
        if (lastSwing < lastAttack || now - lastSwing > 150) {
            // No swing within 150ms before this attack
            int noSwingBuffer = data.getNoSwingBuffer() + 1;
            data.setNoSwingBuffer(noSwingBuffer);
            if (noSwingBuffer >= compensatedBuffer) {
                flag(player, data, 1.0,
                    "攻击无挥臂动作 (连续 " + noSwingBuffer + " 次，延迟 "
                    + data.getPingCompensator().getPingStatus() + ")");
            }
        } else {
            data.setNoSwingBuffer(0);
        }

        // --- Check 2: Inhuman attack interval ---
        if (lastAttack > 0) {
            long interval = now - lastAttack;
            if (interval > 0 && interval < compensatedMinInterval) {
                int fastBuffer = data.getFastClickBuffer() + 1;
                data.setFastClickBuffer(fastBuffer);
                if (fastBuffer >= compensatedBuffer) {
                    flag(player, data, 1.2,
                        String.format("攻击间隔过短: %dms (连续 %d 次，延迟 %s)",
                            interval, fastBuffer, data.getPingCompensator().getPingStatus()));
                }
            } else {
                data.setFastClickBuffer(0);
            }
        }

        // --- Check 3: CPS during combat (only count attacks, not swings) ---
        data.getClickTimestamps().add(now);
        long cutoff = now - CPS_WINDOW_MS;
        data.getClickTimestamps().removeIf(t -> t < cutoff);
        int cps = data.getClickTimestamps().size();
        if (cps > compensatedMaxCps) {
            flag(player, data, cps / compensatedMaxCps,
                String.format("战斗点击频率过高: %d CPS (上限 %.0f，延迟 %s)",
                    cps, compensatedMaxCps, data.getPingCompensator().getPingStatus()));
        }

        // --- Check 4 & 5: Pattern analysis (attack interval consistency & target switching) ---
        checkAttackPatterns(player, data, now, lastAttack, target);

        data.setLastAttackTime(now);
    }

    /**
     * Pattern analysis layer - detects automated attack patterns.
     * Check 4: Attack interval consistency (standard deviation too low = bot-like timing).
     * Check 5: Target switching detection (rapid target changes = KillAura signature).
     */
    private void checkAttackPatterns(Player player, PlayerData data, long now, long lastAttack, Entity target) {
        UUID uuid = player.getUniqueId();
        PatternTracker tracker = patternTrackers.computeIfAbsent(uuid, k -> new PatternTracker());

        // Record attack interval
        if (lastAttack > 0) {
            long interval = now - lastAttack;
            if (interval > 0) {
                tracker.attackIntervals.add(interval);
                // Keep only recent intervals
                while (tracker.attackIntervals.size() > PATTERN_INTERVAL_SAMPLE_SIZE + 5) {
                    tracker.attackIntervals.remove(0);
                }
            }
        }

        // --- Check 4: Attack interval consistency ---
        // If we have enough samples, check standard deviation
        if (tracker.attackIntervals.size() >= PATTERN_INTERVAL_SAMPLE_SIZE) {
            // Get the most recent N intervals
            int size = tracker.attackIntervals.size();
            CopyOnWriteArrayList<Long> recentIntervals = new CopyOnWriteArrayList<>(
                tracker.attackIntervals.subList(size - PATTERN_INTERVAL_SAMPLE_SIZE, size));

            double stdDev = calculateStandardDeviation(recentIntervals);

            if (stdDev < PATTERN_INTERVAL_STD_DEV_THRESHOLD && stdDev > 0) {
                tracker.intervalConsistencyBuffer++;
                if (tracker.intervalConsistencyBuffer >= PATTERN_INTERVAL_BUFFER_MAX) {
                    flag(player, data, 1.3,
                        String.format("攻击间隔过于规律: 标准差=%.1fms (连续 %d 次, 延迟 %s)",
                            stdDev, tracker.intervalConsistencyBuffer,
                            data.getPingCompensator().getPingStatus()));
                    tracker.intervalConsistencyBuffer = 0;
                }
            } else {
                tracker.intervalConsistencyBuffer = 0;
            }
        }

        // --- Check 5: Target switching detection ---
        // Only check if target entity is available
        if (target != null) {
            int currentEntityId = target.getEntityId();
            if (tracker.lastAttackEntityId != -1 && currentEntityId != -1
                    && tracker.lastAttackEntityId != currentEntityId) {
                // Entity ID changed - check if it happened within the switch window
                long timeSinceLastAttack = lastAttack > 0 ? now - lastAttack : 0;
                if (timeSinceLastAttack > 0 && timeSinceLastAttack <= TARGET_SWITCH_WINDOW_MS) {
                    tracker.targetSwitchBuffer++;
                    if (tracker.targetSwitchBuffer >= TARGET_SWITCH_BUFFER_MAX) {
                        flag(player, data, 1.1,
                            String.format("可疑目标切换: %d -> %d (间隔 %dms, 连续 %d 次, 延迟 %s)",
                                tracker.lastAttackEntityId, currentEntityId,
                                timeSinceLastAttack, tracker.targetSwitchBuffer,
                                data.getPingCompensator().getPingStatus()));
                        tracker.targetSwitchBuffer = 0;
                    }
                } else {
                    tracker.targetSwitchBuffer = 0;
                }
            } else {
                tracker.targetSwitchBuffer = 0;
            }

            tracker.lastAttackEntityId = currentEntityId;
        }
    }

    /**
     * Calculate the standard deviation of a list of attack intervals.
     */
    private double calculateStandardDeviation(CopyOnWriteArrayList<Long> intervals) {
        if (intervals.size() < 2) return 0;

        double sum = 0;
        for (long interval : intervals) {
            sum += interval;
        }
        double mean = sum / intervals.size();

        double variance = 0;
        for (long interval : intervals) {
            double diff = interval - mean;
            variance += diff * diff;
        }
        variance /= intervals.size();

        return Math.sqrt(variance);
    }

    /**
     * Clean up tracker when player disconnects.
     */
    public void onPlayerQuit(UUID uuid) {
        patternTrackers.remove(uuid);
    }

    /**
     * Internal tracker for attack pattern analysis.
     * Monitors attack interval consistency and target switching behavior.
     */
    private static class PatternTracker {
        CopyOnWriteArrayList<Long> attackIntervals = new CopyOnWriteArrayList<>(); // 攻击间隔记录
        int lastAttackEntityId = -1;       // 上次攻击的实体ID
        int intervalConsistencyBuffer = 0; // 攻击间隔一致性 buffer
        int targetSwitchBuffer = 0;        // 目标切换 buffer
    }
}
