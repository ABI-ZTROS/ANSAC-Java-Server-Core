package dev.ztros.ansac.checks.packet;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

/**
 * Timer check - detects game speed manipulation (speed hack).
 * Reference: GrimAC's 1.005 timer precision detection.
 */
public class TimerCheck extends Check {

    private static final long EXPECTED_TICK_TIME = 50; // 20 TPS = 50ms per tick
    private static final double MAX_TIMER_SPEED = 1.1; // 10% faster than normal
    private static final double MIN_TIMER_SPEED = 0.8; // 20% slower than normal
    private static final int SAMPLE_SIZE = 20; // Number of samples for averaging

    public TimerCheck(ANSACPlugin plugin) {
        super(plugin, "Timer", "Packet");
    }

    @Override
    public void process(Player player, PlayerData data) {
        long now = System.currentTimeMillis();
        long lastFlying = data.getLastFlyingPacket();

        if (lastFlying == 0) {
            data.setLastFlyingPacket(now);
            return;
        }

        long timeDiff = now - lastFlying;

        // Skip if too much time has passed (lag spike, etc.)
        if (timeDiff > EXPECTED_TICK_TIME * 4) {
            data.setLastFlyingPacket(now);
            data.setFlyingPacketCount(0);
            return;
        }

        // Calculate timer speed ratio
        double timerSpeed = (double) EXPECTED_TICK_TIME / timeDiff;

        // Check for speed timer (playing faster than normal)
        if (timerSpeed > MAX_TIMER_SPEED && timeDiff > 0) {
            double severity = timerSpeed / MAX_TIMER_SPEED;
            flag(player, data, severity,
                String.format("Timer speed: %.3fx (expected: 1.0x)", timerSpeed));
        }

        // Check for slow timer (playing slower than normal - could be lag, but consistent slow is suspicious)
        if (timerSpeed < MIN_TIMER_SPEED && timeDiff > 0) {
            // Only flag if consistently slow (not just lag)
            int count = data.getFlyingPacketCount();
            if (count > SAMPLE_SIZE) {
                double severity = MIN_TIMER_SPEED / timerSpeed;
                flag(player, data, severity,
                    String.format("Slow timer: %.3fx (expected: 1.0x)", timerSpeed));
            }
        }

        data.setLastFlyingPacket(now);
        data.setFlyingPacketCount(data.getFlyingPacketCount() + 1);
    }
}
