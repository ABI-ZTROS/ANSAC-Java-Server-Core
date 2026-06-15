package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Speed check - detects abnormal movement speed.
 * Reference: GrimAC's movement simulation approach.
 */
public class SpeedCheck extends Check {

    private static final double WALK_SPEED = 0.215;
    private static final double SPRINT_SPEED = 0.280;
    private static final double SPRINT_JUMP_SPEED = 0.327;
    private static final double ICE_SPEED_MULTIPLIER = 2.5;
    private static final double LENIENCY = 0.15;

    public SpeedCheck(ANSACPlugin plugin) {
        super(plugin, "Speed", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();

        if (from == null || to == null) return;

        double horizontalDist = data.getHorizontalDistance();
        double verticalDist = data.getVerticalDistance();

        // Skip if player is flying, in vehicle, or teleporting
        if (player.isFlying() || player.isInsideVehicle() || player.isGliding()) return;

        // Calculate expected max speed
        double expectedSpeed = getExpectedSpeed(player, data);

        // Account for ping (higher ping = more leniency)
        int ping = data.getPing();
        double pingFactor = 1.0 + (ping / 1000.0) * 0.5;
        expectedSpeed *= pingFactor;

        // Check if speed exceeds expected
        if (horizontalDist > expectedSpeed * LENIENCY && horizontalDist > 0.5) {
            double severity = horizontalDist / expectedSpeed;
            flag(player, data, severity,
                String.format("Speed: %.3f / %.3f (ping: %dms)", horizontalDist, expectedSpeed, ping));
        }
    }

    /**
     * Calculate expected maximum speed based on player state
     */
    private double getExpectedSpeed(Player player, PlayerData data) {
        double speed = WALK_SPEED;

        // Sprinting
        if (player.isSprinting()) {
            speed = SPRINT_SPEED;
        }

        // Jumping while sprinting
        if (player.isSprinting() && !player.isOnGround()) {
            speed = SPRINT_JUMP_SPEED;
        }

        // Speed potion effect
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            int level = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier() + 1;
            speed *= (1.0 + 0.2 * level);
        }

        // On ice
        if (isOnIce(player)) {
            speed *= ICE_SPEED_MULTIPLIER;
        }

        // Sneaking
        if (player.isSneaking()) {
            speed *= 0.3;
        }

        // Blocking (sword/ shield)
        if (player.isBlocking()) {
            speed *= 0.2;
        }

        return speed;
    }

    /**
     * Check if player is on ice
     */
    private boolean isOnIce(Player player) {
        Location loc = player.getLocation().clone().subtract(0, 1, 0);
        String blockType = loc.getBlock().getType().name();
        return blockType.contains("ICE") && !blockType.contains("PACKED");
    }
}
