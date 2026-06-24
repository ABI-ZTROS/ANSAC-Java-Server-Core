package dev.ztros.ansac.checks.movement;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.util.ServerVersionAdapter;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

/**
 * Fly check - detects abnormal vertical movement and flight.
 *
 * Design notes:
 * - Creative/Spectator mode players are skipped entirely.
 * - Players with active elytra, in vehicles, or with levitation are skipped.
 * - The check looks for sustained impossible vertical motion, not single-tick anomalies.
 * - A buffer counts consecutive violations; only sustained behavior triggers a flag.
 */
public class FlyCheck extends Check {

    private static final double LENIENCY = 0.15;
    private static final int BUFFER_MAX = 6; // Require 6 consecutive violations to flag

    public FlyCheck(ANSACPlugin plugin) {
        super(plugin, "Fly", "Movement");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        Location from = data.getLastLocation();
        Location to = data.getCurrentLocation();
        if (from == null || to == null) return;

        double deltaY = data.getVerticalDistance();
        boolean onGround = player.isOnGround();

        // Check 1: sustained hover (not on ground, not moving vertically, not in liquid)
        if (!onGround && Math.abs(deltaY) < 0.001
                && !player.isInWater() && !player.isInLava()
                && !player.isClimbing()) {
            int hoverBuffer = data.getHoverBuffer() + 1;
            data.setHoverBuffer(hoverBuffer);
            if (hoverBuffer >= BUFFER_MAX) {
                flag(player, data, 1.5, "空中悬停（连续 " + hoverBuffer + " tick）");
            }
            return;
        } else {
            data.setHoverBuffer(0);
        }

        // Check 2: ascending while not on ground (no jump boost, no levitation, no climbing, not in liquid)
        if (!onGround && deltaY > LENIENCY) {
            PotionEffectType levitation = ServerVersionAdapter.getLevitation();
            boolean hasLevitation = levitation != null && player.hasPotionEffect(levitation);
            PotionEffectType jumpBoost = ServerVersionAdapter.getJumpBoost();
            boolean hasJumpBoost = jumpBoost != null && player.hasPotionEffect(jumpBoost);
            if (!hasLevitation && !hasJumpBoost && !player.isClimbing()
                    && !player.isInWater() && !player.isInLava()) {
                int ascendBuffer = data.getAscendBuffer() + 1;
                data.setAscendBuffer(ascendBuffer);
                if (ascendBuffer >= BUFFER_MAX) {
                    flag(player, data, deltaY / LENIENCY,
                        String.format("空中异常上升: dy=%.3f (连续 %d tick)", deltaY, ascendBuffer));
                }
                return;
            } else {
                data.setAscendBuffer(0);
            }
        } else {
            data.setAscendBuffer(0);
        }

        // Check 3: falling too slowly (less than gravity)
        if (!onGround && deltaY < -LENIENCY) {
            // Normal gravity fall is about -0.08 per tick initially, accelerating
            // If player is falling slower than -0.03 for multiple ticks, suspicious
            if (deltaY > -0.03 && !player.isInWater() && !player.isInLava() && !player.isClimbing()) {
                int fallBuffer = data.getFallBuffer() + 1;
                data.setFallBuffer(fallBuffer);
                if (fallBuffer >= BUFFER_MAX) {
                    flag(player, data, 1.2,
                        String.format("下落过慢: dy=%.3f (连续 %d tick)", deltaY, fallBuffer));
                }
                return;
            } else {
                data.setFallBuffer(0);
            }
        } else {
            data.setFallBuffer(0);
        }
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isFlying() || player.isInsideVehicle() || player.isGliding()
            || player.isSleeping() || player.isDead();
    }
}
