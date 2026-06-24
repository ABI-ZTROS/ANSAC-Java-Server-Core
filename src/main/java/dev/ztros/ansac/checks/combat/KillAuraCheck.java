package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

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
 */
public class KillAuraCheck extends Check {

    private static final long MIN_CLICK_INTERVAL = 45; // ms
    private static final double MAX_CPS = 16.0; // 正常人类极限（之前 18 太宽松）
    private static final int CPS_WINDOW_MS = 1000;
    private static final int BUFFER_MAX = 3; // Require 3 consecutive violations

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
     */
    public void processAttack(Player player, PlayerData data) {
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

        data.setLastAttackTime(now);
    }
}
