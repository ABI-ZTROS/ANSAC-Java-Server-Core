package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

/**
 * KillAura check - detects automated combat (aimbot/clicker).
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
    private static final double MAX_CPS = 18.0;
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

        long now = System.currentTimeMillis();
        long lastAttack = data.getLastAttackTime();

        // --- Check 1: Attack without recent arm swing ---
        long lastSwing = data.getLastSwingTime();
        if (lastSwing < lastAttack || now - lastSwing > 150) {
            // No swing within 150ms before this attack
            int noSwingBuffer = data.getNoSwingBuffer() + 1;
            data.setNoSwingBuffer(noSwingBuffer);
            if (noSwingBuffer >= BUFFER_MAX) {
                flag(player, data, 1.0,
                    "攻击无挥臂动作 (连续 " + noSwingBuffer + " 次)");
            }
        } else {
            data.setNoSwingBuffer(0);
        }

        // --- Check 2: Inhuman attack interval ---
        if (lastAttack > 0) {
            long interval = now - lastAttack;
            if (interval > 0 && interval < MIN_CLICK_INTERVAL) {
                int fastBuffer = data.getFastClickBuffer() + 1;
                data.setFastClickBuffer(fastBuffer);
                if (fastBuffer >= BUFFER_MAX) {
                    flag(player, data, 1.2,
                        String.format("攻击间隔过短: %dms (连续 %d 次)", interval, fastBuffer));
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
        if (cps > MAX_CPS) {
            flag(player, data, cps / MAX_CPS,
                String.format("战斗点击频率过高: %d CPS (上限 %.0f)", cps, MAX_CPS));
        }

        data.setLastAttackTime(now);
    }
}
