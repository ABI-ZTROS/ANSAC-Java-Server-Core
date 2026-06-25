package dev.ztros.ansac.checks.player;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AutoEat check - detects automated food/golden apple consumption when health is low.
 *
 * Cheat principle (Wurst AutoEat + Meteor AutoEat/AutoGap):
 *   Wurst AutoEat: Automatically eats food when hunger is low or health is below a threshold.
 *   Meteor AutoEat/AutoGap: Similar, supports golden apples and specific health thresholds.
 *   Cheat signature: Food consumption triggered immediately when health drops below a threshold,
 *   with very short intervals between item switch and consumption (< 2 seconds).
 *
 * Detection logic:
 *   - processItemConsume() is called from PlayerListener on PlayerItemConsumeEvent.
 *   - Records item consumption timestamps and the player's health at the time.
 *   - Checks if the player's health is < 14 (7 hearts) when consuming food.
 *   - Checks if consumption intervals are < 2 seconds (fast switch + use pattern).
 *   - Uses a buffer system: autoEatBuffer >= 3 triggers a flag.
 *   - process() periodically cleans up expired data (older than 5 seconds).
 *
 * Normal player reference:
 *   - Eating food: Requires holding right-click for ~1.6 seconds (32 ticks)
 *   - Switching to food: ~200-500ms to find food in hotbar
 *   - Total reaction time: ~2-3 seconds minimum from health drop to eating
 *   - AutoEat can do this in < 500ms consistently, which is inhumanly fast
 *
 * Exemptions:
 *   - Creative mode (no hunger/health concerns in creative)
 */
public class AutoEatCheck extends Check {

    // Detection thresholds
    private static final double LOW_HEALTH_THRESHOLD = 14.0;        // 7 hearts
    private static final long FAST_CONSUME_INTERVAL_MS = 2000L;    // < 2 seconds between consumptions
    private static final int BUFFER_FLAG_THRESHOLD = 3;             // 3 violations before flagging
    private static final long DATA_EXPIRE_MS = 5000L;               // Expire data older than 5 seconds

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.10;

    /**
     * Internal tracker for per-player auto-eat state.
     * Stored in a ConcurrentHashMap for thread safety (Folia compatibility).
     */
    static class AutoEatTracker {
        // Item consumption timestamps
        final CopyOnWriteArrayList<Long> consumeTimestamps = new CopyOnWriteArrayList<>();
        // Health at last consumption
        double lastHealthBeforeEat;
        // Violation buffer
        int autoEatBuffer;

        AutoEatTracker() {
            this.lastHealthBeforeEat = 20.0;
            this.autoEatBuffer = 0;
        }

        void reset() {
            this.consumeTimestamps.clear();
            this.lastHealthBeforeEat = 20.0;
            this.autoEatBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, AutoEatTracker> trackers = new ConcurrentHashMap<>();

    public AutoEatCheck(ANSACPlugin plugin) {
        super(plugin, "AutoEat", "Player");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Periodic cleanup of expired data
        AutoEatTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) return;

        long cutoff = System.currentTimeMillis() - DATA_EXPIRE_MS;

        // Remove expired timestamps
        tracker.consumeTimestamps.removeIf(t -> t < cutoff);

        // If no data left, clean up entirely
        if (tracker.consumeTimestamps.isEmpty()) {
            trackers.remove(player.getUniqueId());
        }
    }

    /**
     * Process an item consumption action (called from PlayerListener on PlayerItemConsumeEvent).
     * This is the main detection entry point for auto-eat detection.
     *
     * @param player The player who consumed an item
     * @param data   The player's anti-cheat data
     */
    public void processItemConsume(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // Skip creative mode (creative players don't need to eat)
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) {
            return;
        }

        // Skip sleeping or dead players
        if (player.isSleeping() || player.isDead()) {
            return;
        }

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            AutoEatTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.autoEatBuffer = 0;
            }
            return;
        }

        long now = System.currentTimeMillis();
        AutoEatTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new AutoEatTracker());

        // Clean up old timestamps
        long cutoff = now - DATA_EXPIRE_MS;
        tracker.consumeTimestamps.removeIf(t -> t < cutoff);

        // Record consumption timestamp and current health
        tracker.consumeTimestamps.add(now);
        double currentHealth = player.getHealth();
        tracker.lastHealthBeforeEat = currentHealth;

        // Check: is the player at low health (< 14 / 7 hearts)?
        if (currentHealth < LOW_HEALTH_THRESHOLD) {
            // Check if there was a recent consumption (fast switch + eat pattern)
            if (tracker.consumeTimestamps.size() >= 2) {
                int size = tracker.consumeTimestamps.size();
                long previousConsume = tracker.consumeTimestamps.get(size - 2);
                long interval = now - previousConsume;

                if (interval < FAST_CONSUME_INTERVAL_MS) {
                    // Suspicious: low health + fast consumption pattern
                    tracker.autoEatBuffer++;

                    // Ping-compensated buffer threshold
                    int compensatedThreshold = data.getPingCompensator().getCompensatedBuffer(
                        BUFFER_FLAG_THRESHOLD, COMPENSATION_FACTOR);

                    if (tracker.autoEatBuffer >= compensatedThreshold) {
                        double severity = tracker.autoEatBuffer / (double) BUFFER_FLAG_THRESHOLD;
                        flag(player, data, severity,
                            String.format("自动进食: 血量 %.1f (< %.1f), 两次消耗间隔 %dms (连续 %d 次, 延迟 %s)",
                                currentHealth, LOW_HEALTH_THRESHOLD, interval,
                                tracker.autoEatBuffer,
                                data.getPingCompensator().getPingStatus()));
                        // Reset buffer after flagging to avoid spam
                        tracker.autoEatBuffer = 0;
                    }
                } else {
                    // Interval was long enough - legitimate eating
                    // Gradually decay buffer
                    if (tracker.autoEatBuffer > 0) {
                        tracker.autoEatBuffer = Math.max(0, tracker.autoEatBuffer - 1);
                    }
                }
            }
            // If only 1 consumption recorded, no interval check yet - wait for next one
        } else {
            // Health is not low - this is normal eating, decay buffer
            if (tracker.autoEatBuffer > 0) {
                tracker.autoEatBuffer = Math.max(0, tracker.autoEatBuffer - 1);
            }
        }
    }

    /**
     * Clean up tracker when player disconnects.
     */
    public void onPlayerQuit(UUID uuid) {
        trackers.remove(uuid);
    }
}
