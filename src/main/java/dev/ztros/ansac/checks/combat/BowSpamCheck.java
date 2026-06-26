package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * BowSpam check - detects rapid consecutive bow shots bypassing charge time.
 *
 * Cheat principle (Meteor BowSpam):
 *   Meteor BowSpam: Removes or bypasses the bow charge-up time, allowing
 *   the player to fire arrows at machine-gun speed without proper charging.
 *   This gives a massive advantage in PvP by overwhelming opponents with
 *   projectiles while maintaining mobility.
 *   Signature: Multiple arrows shot within a very short time window (faster
 *   than the minimum 1-second charge time for a fully-charged arrow).
 *
 * Physics reference (Minecraft Wiki):
 *   Bow minimum charge time: ~0.5s (10 ticks) for any arrow to fire
 *   Bow full charge time: 1.0s (20 ticks)
 *   Normal fast shooting: ~1.5 seconds per arrow (human reaction + aiming)
 *   Cheat shooting: <0.3 seconds per arrow (3+ arrows per second)
 *
 * Detection logic:
 *   - processBowShoot() is called from PlayerListener's EntityShootBowEvent
 *   - Records each shot timestamp in a CopyOnWriteArrayList
 *   - process() runs every tick and checks the 1-second window
 *   - If > 3 shots in 1 second, increment bowSpamBuffer
 *   - If bowSpamBuffer >= 3, flag the player
 *   - Uses ConcurrentHashMap for thread-safe per-player tracking
 *
 * Exemptions:
 *   - None (rapid fire is an unambiguous cheat signature)
 */
public class BowSpamCheck extends Check {

    // Time window to check for rapid shots (ms)
    private static final long CHECK_WINDOW_MS = 1000L;

    // Maximum allowed shots in the check window (normal ~1-2 shots/sec)
    private static final int MAX_SHOTS_IN_WINDOW = 3;

    // Buffer threshold for flagging
    private static final int BUFFER_THRESHOLD = 3;

    // Ping compensation factor
    private static final double COMPENSATION_FACTOR = 0.10;

    /**
     * Internal tracker for per-player BowSpam violations.
     * Stored in a ConcurrentHashMap for thread safety.
     */
    static class BowSpamTracker {
        final CopyOnWriteArrayList<Long> shootTimestamps = new CopyOnWriteArrayList<>();
        int bowSpamBuffer;

        BowSpamTracker() {
            this.bowSpamBuffer = 0;
        }

        void reset() {
            this.bowSpamBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, BowSpamTracker> trackers = new ConcurrentHashMap<>();

    public BowSpamCheck(ANSACPlugin plugin) {
        super(plugin, "BowSpam", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            BowSpamTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.reset();
            }
            return;
        }

        BowSpamTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) return;

        long now = System.currentTimeMillis();
        long windowStart = now - CHECK_WINDOW_MS;

        // Clean up expired timestamps
        // Note: CopyOnWriteArrayList iterator does NOT support remove().
        tracker.shootTimestamps.removeIf(t -> t < windowStart);

        // Check shot count in window
        int shotsInWindow = tracker.shootTimestamps.size();

        if (shotsInWindow > MAX_SHOTS_IN_WINDOW) {
            tracker.bowSpamBuffer++;

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_THRESHOLD, COMPENSATION_FACTOR);

            if (tracker.bowSpamBuffer >= compensatedBuffer) {
                double severity = 1.0 + (shotsInWindow - MAX_SHOTS_IN_WINDOW) * 0.5;
                flag(player, data, severity,
                    String.format("弓箭连射检测: %d秒内射出 %d 箭 (缓冲 %d, 延迟 %s)",
                        CHECK_WINDOW_MS / 1000.0, shotsInWindow,
                        tracker.bowSpamBuffer,
                        data.getPingCompensator().getPingStatus()));
                tracker.reset();
            }
        } else {
            // Gradually decay buffer when not spamming
            tracker.bowSpamBuffer = Math.max(0, tracker.bowSpamBuffer - 1);
        }
    }

    /**
     * Called from PlayerListener's EntityShootBowEvent.
     * Records the timestamp of each bow shot.
     *
     * @param player The player who shot the bow
     * @param data   The player's data
     */
    public void processBowShoot(Player player, PlayerData data) {
        if (shouldSkip(player)) return;

        BowSpamTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new BowSpamTracker());

        tracker.shootTimestamps.add(System.currentTimeMillis());
    }

    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isSleeping() || player.isDead();
    }
}
