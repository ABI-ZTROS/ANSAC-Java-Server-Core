package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

/**
 * KillAura check - detects automated combat (aimbot/clicker).
 * Reference: GrimAC's heuristic detection approach.
 */
public class KillAuraCheck extends Check {

    private static final long MIN_CLICK_DELAY = 50; // Minimum 50ms between clicks
    private static final long MAX_CLICK_DELAY = 1000; // Maximum 1s between clicks for consistency check
    private static final double MAX_CPS = 20.0; // Maximum clicks per second
    private static final int CONSISTENCY_WINDOW = 10; // Number of clicks to check consistency

    public KillAuraCheck(ANSACPlugin plugin) {
        super(plugin, "KillAura", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        long now = System.currentTimeMillis();
        long lastSwing = data.getLastSwingTime();
        long timeDiff = now - lastSwing;

        // Check for impossibly fast clicks
        if (timeDiff > 0 && timeDiff < MIN_CLICK_DELAY && lastSwing > 0) {
            flag(player, data, 2.0,
                String.format("Click speed: %dms (min: %dms)", timeDiff, MIN_CLICK_DELAY));
        }

        // Check CPS (Clicks Per Second)
        int attackCount = data.getAttackCount();
        if (attackCount > MAX_CPS) {
            flag(player, data, attackCount / MAX_CPS,
                String.format("CPS: %d (max: %.0f)", attackCount, MAX_CPS));
        }
    }

    /**
     * Process arm swing (called from packet listener)
     */
    public void processSwing(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        long now = System.currentTimeMillis();
        long lastSwing = data.getLastSwingTime();

        if (lastSwing > 0) {
            long timeDiff = now - lastSwing;

            // Check for inhuman consistency (bot-like clicking)
            if (timeDiff > 0 && timeDiff < MIN_CLICK_DELAY) {
                flag(player, data, 1.5,
                    String.format("Superhuman click speed: %dms", timeDiff));
            }
        }

        data.setLastSwingTime(now);
    }

    /**
     * Process attack (called from packet listener)
     */
    public void processAttack(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        long now = System.currentTimeMillis();
        long lastAttack = data.getLastAttackTime();

        if (lastAttack > 0) {
            long timeDiff = now - lastAttack;

            // Check for no swing between attacks (some killauras don't swing)
            long lastSwing = data.getLastSwingTime();
            if (lastSwing < lastAttack) {
                flag(player, data, 1.0, "Attack without arm swing");
            }

            // Check for inhuman reaction time
            if (timeDiff < MIN_CLICK_DELAY) {
                flag(player, data, 1.2,
                    String.format("Inhuman reaction time: %dms", timeDiff));
            }
        }

        data.setLastAttackTime(now);
        data.setAttackCount(data.getAttackCount() + 1);
    }
}
