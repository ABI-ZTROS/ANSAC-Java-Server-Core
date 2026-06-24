package dev.ztros.ansac.checks.packet;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

/**
 * Timer check - detects game speed manipulation (speed hack).
 * Reference: GrimAC's 1.005 timer precision detection.
 *
 * IMPORTANT: This check is driven by packet events, not by periodic process() calls.
 * The process() method is a no-op; actual detection happens in onFlyingPacket().
 */
public class TimerCheck extends Check {

    private static final long EXPECTED_TICK_TIME = 50; // 20 TPS = 50ms per tick
    private static final double MAX_TIMER_SPEED = 1.15; // 15% faster than normal (was 1.1, too strict)
    private static final double MIN_TIMER_SPEED = 0.75; // 25% slower than normal (was 0.8)
    private static final int SAMPLE_SIZE = 40; // Number of samples for averaging (was 20, too sensitive)
    private static final long LAG_SPIKE_THRESHOLD = EXPECTED_TICK_TIME * 5; // Skip if > 250ms

    public TimerCheck(ANSACPlugin plugin) {
        super(plugin, "Timer", "Packet");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Timer check is event-driven via onFlyingPacket()
        // Periodic process() is intentionally a no-op
    }

    /**
     * Called from PacketListener when a flying packet is received.
     * This is the actual timer detection logic.
     */
    public void onFlyingPacket(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        long now = System.currentTimeMillis();
        long lastFlying = data.getLastFlyingPacket();

        // First packet since join/reset
        if (lastFlying == 0) {
            data.setLastFlyingPacket(now);
            data.setFlyingPacketCount(0);
            return;
        }

        long timeDiff = now - lastFlying;

        // Skip if too much time has passed (lag spike, server freeze, etc.)
        if (timeDiff > LAG_SPIKE_THRESHOLD) {
            data.setLastFlyingPacket(now);
            data.setFlyingPacketCount(0);
            return;
        }

        // Skip if timeDiff is unreasonably small (could be packet batching)
        if (timeDiff < 5) {
            return;
        }

        // Calculate timer speed ratio
        double timerSpeed = (double) EXPECTED_TICK_TIME / timeDiff;

        // Check for speed timer (playing faster than normal)
        if (timerSpeed > MAX_TIMER_SPEED) {
            int count = data.getFlyingPacketCount();
            // Only flag after enough consistent samples to avoid false positives
            if (count >= SAMPLE_SIZE) {
                double severity = timerSpeed / MAX_TIMER_SPEED;
                flag(player, data, severity,
                    String.format("Timer speed: %.3fx (expected: 1.0x)", timerSpeed));
            }
        }

        // Check for slow timer (playing slower than normal)
        if (timerSpeed < MIN_TIMER_SPEED) {
            int count = data.getFlyingPacketCount();
            if (count >= SAMPLE_SIZE) {
                double severity = MIN_TIMER_SPEED / timerSpeed;
                flag(player, data, severity,
                    String.format("Slow timer: %.3fx (expected: 1.0x)", timerSpeed));
            }
        }

        // Update tracking
        data.setLastFlyingPacket(now);
        data.setFlyingPacketCount(data.getFlyingPacketCount() + 1);
    }
}
