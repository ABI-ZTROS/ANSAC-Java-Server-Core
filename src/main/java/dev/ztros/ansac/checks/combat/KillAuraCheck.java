package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

/**
 * KillAura check - detects automated combat (aimbot/clicker).
 *
 * Design notes:
 * - Uses a time-windowed CPS counter instead of an ever-increasing counter.
 * - Tracks click consistency via a small sample window.
 * - The periodic process() only decays old data; all flagging is event-driven.
 */
public class KillAuraCheck extends Check {

    private static final long MIN_CLICK_INTERVAL = 45; // ms, slightly below legit double-click
    private static final double MAX_CPS = 18.0; // was 20, give a small buffer
    private static final int CPS_WINDOW_MS = 1000; // 1 second window

    public KillAuraCheck(ANSACPlugin plugin) {
        super(plugin, "KillAura", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Periodic process only cleans up old click timestamps
        long cutoff = System.currentTimeMillis() - CPS_WINDOW_MS;
        data.getClickTimestamps().removeIf(t -> t < cutoff);
    }

    /**
     * Process arm swing (called from packet listener)
     */
    public void processSwing(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        long now = System.currentTimeMillis();
        data.getClickTimestamps().add(now);

        // Clean old timestamps
        long cutoff = now - CPS_WINDOW_MS;
        data.getClickTimestamps().removeIf(t -> t < cutoff);

        // Check CPS
        int cps = data.getClickTimestamps().size();
        if (cps > MAX_CPS) {
            flag(player, data, cps / MAX_CPS,
                String.format("点击频率过高: %d CPS (上限 %.0f)", cps, MAX_CPS));
        }
    }

    /**
     * Process attack (called from packet listener)
     */
    public void processAttack(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        long now = System.currentTimeMillis();
        long lastAttack = data.getLastAttackTime();

        if (lastAttack > 0) {
            long interval = now - lastAttack;

            // Attack without recent arm swing (some killauras don't swing)
            long lastSwing = data.getLastSwingTime();
            if (lastSwing < lastAttack) {
                int noSwingBuffer = data.getNoSwingBuffer() + 1;
                data.setNoSwingBuffer(noSwingBuffer);
                if (noSwingBuffer >= 3) {
                    flag(player, data, 1.0, "攻击无挥臂动作 (连续 " + noSwingBuffer + " 次)");
                }
            } else {
                data.setNoSwingBuffer(0);
            }

            // Inhuman reaction time (too fast between attacks)
            if (interval > 0 && interval < MIN_CLICK_INTERVAL) {
                int fastBuffer = data.getFastClickBuffer() + 1;
                data.setFastClickBuffer(fastBuffer);
                if (fastBuffer >= 3) {
                    flag(player, data, 1.2,
                        String.format("攻击间隔过短: %dms (连续 %d 次)", interval, fastBuffer));
                }
            } else {
                data.setFastClickBuffer(0);
            }
        }

        data.setLastAttackTime(now);
    }
}
