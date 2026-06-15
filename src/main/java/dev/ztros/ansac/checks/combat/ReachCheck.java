package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * Reach check - detects abnormal attack distance.
 * Reference: GrimAC's 3.01 reach detection.
 */
public class ReachCheck extends Check {

    private static final double MAX_REACH = 3.0;
    private static final double CREATIVE_REACH = 5.0;
    private static final double LENIENCY = 0.1;
    private static final double PING_FACTOR = 0.001;

    public ReachCheck(ANSACPlugin plugin) {
        super(plugin, "Reach", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // This check is event-driven, processed via packet listener
        // The periodic check handles decay and validation

        long now = System.currentTimeMillis();
        long lastAttack = data.getLastAttackTime();

        // Decay attack count if no recent attacks
        if (now - lastAttack > 1000) {
            data.setAttackCount(0);
        }
    }

    /**
     * Process an attack event (called from packet listener)
     */
    public void processAttack(Player player, PlayerData data, Entity target) {
        if (!isEnabled() || data.hasBypass()) return;

        Location playerLoc = player.getLocation();
        Location targetLoc = target.getLocation();

        // Calculate horizontal distance
        double horizontalDist = Math.sqrt(
            Math.pow(playerLoc.getX() - targetLoc.getX(), 2) +
            Math.pow(playerLoc.getZ() - targetLoc.getZ(), 2)
        );

        // Calculate 3D distance
        double distance = playerLoc.distance(targetLoc);

        // Account for eye height
        double eyeHeightDiff = Math.abs(
            (playerLoc.getY() + player.getEyeHeight()) -
            (targetLoc.getY() + (target instanceof Player ? ((Player) target).getEyeHeight() : target.getHeight() / 2))
        );

        // Effective reach
        double effectiveReach = Math.sqrt(horizontalDist * horizontalDist + eyeHeightDiff * eyeHeightDiff);

        // Determine max allowed reach
        double maxReach = player.getGameMode().name().contains("CREATIVE") ? CREATIVE_REACH : MAX_REACH;

        // Account for ping
        int ping = data.getPing();
        maxReach += ping * PING_FACTOR;

        // Account for client-server desync (target may have moved)
        maxReach += 0.3; // Extra leniency for movement desync

        // Check if reach exceeds limit
        if (effectiveReach > maxReach + LENIENCY) {
            double severity = effectiveReach / maxReach;
            flag(player, data, severity,
                String.format("Reach: %.2f / %.2f (target: %s, ping: %dms)",
                    effectiveReach, maxReach, target.getName(), ping));
        }

        // Track attack
        data.setLastAttackTime(System.currentTimeMillis());
        data.setAttackCount(data.getAttackCount() + 1);
    }
}
