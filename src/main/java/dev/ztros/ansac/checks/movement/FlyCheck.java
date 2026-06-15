package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Fly check - detects abnormal vertical movement and flight.
 * Reference: GrimAC's prediction engine approach.
 */
public class FlyCheck extends Check {

    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double LADDER_CLIMB = 0.1176;
    private static final double WATER_ASCENT = 0.04;
    private static final double LENIENCY = 0.08;

    public FlyCheck(ANSACPlugin plugin) {
        super(plugin, "Fly", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (player.isFlying() || player.isInsideVehicle() || player.isGliding()) return;

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();

        if (from == null || to == null) return;

        double deltaY = data.getVerticalDistance();
        boolean onGround = player.isOnGround();

        // Skip if in special states
        if (isInSpecialState(player)) return;

        // Predict expected vertical movement
        double expectedDeltaY = predictVerticalMovement(player, data, onGround);

        // Account for ping
        int ping = data.getPing();
        double pingLeniency = (ping / 1000.0) * 0.1;

        // Check for abnormal vertical movement
        if (Math.abs(deltaY - expectedDeltaY) > LENIENCY + pingLeniency && Math.abs(deltaY) > 0.1) {
            double severity = Math.abs(deltaY - expectedDeltaY) / LENIENCY;
            flag(player, data, severity,
                String.format("Fly: dy=%.3f expected=%.3f (ping: %dms)", deltaY, expectedDeltaY, ping));
        }

        // Check for impossible hover (staying at same Y without being on ground)
        if (!onGround && Math.abs(deltaY) < 0.001 && !player.isInWater() && !player.isInLava()) {
            // Player is hovering in mid-air
            if (data.getLastDeltaY() != 0 && Math.abs(data.getLastDeltaY()) < 0.001) {
                flag(player, data, 2.0, "Hover detection (impossible stationary Y)");
            }
        }

        data.setLastDeltaY(deltaY);
    }

    /**
     * Predict expected vertical movement based on player state
     */
    private double predictVerticalMovement(Player player, PlayerData data, boolean onGround) {
        double predicted = 0.0;

        if (onGround) {
            // On ground - can jump
            if (player.isSprinting() || player.isJumping()) {
                predicted = JUMP_VELOCITY;

                // Jump boost potion
                if (player.hasPotionEffect(PotionEffectType.JUMP)) {
                    int level = player.getPotionEffect(PotionEffectType.JUMP).getAmplifier() + 1;
                    predicted += level * 0.1;
                }
            }
        } else {
            // In air - apply gravity
            double lastMotionY = data.getLastMotionY();
            predicted = (lastMotionY - GRAVITY) * DRAG;

            // Climbing
            if (player.isClimbing()) {
                if (player.isSneaking()) {
                    predicted = -LADDER_CLIMB; // Descending ladder slowly
                } else {
                    predicted = LADDER_CLIMB; // Ascending ladder
                }
            }

            // In water
            if (player.isInWater()) {
                if (player.isSneaking()) {
                    predicted = -WATER_ASCENT; // Sinking
                } else {
                    predicted = WATER_ASCENT; // Rising
                }
            }
        }

        return predicted;
    }

    /**
     * Check if player is in a special state that exempts from fly check
     */
    private boolean isInSpecialState(Player player) {
        return player.isInWater()
            || player.isInLava()
            || player.isClimbing()
            || player.isInsideVehicle()
            || player.isSleeping()
            || player.isDead();
    }
}
