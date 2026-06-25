package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Quiver check - detects self-buffing by shooting arrows with effects at oneself.
 *
 * Cheat principle (Meteor Quiver):
 *   Meteor Quiver: Shoots arrows with effects (e.g., Strength, Speed) straight down at the player's feet.
 *   The arrow effect applies to the shooter, providing free buffs. This is done by aiming at -90 degrees
 *   pitch (straight down) and shooting. The arrow lands at the player's feet and applies its effect.
 *   Cheat signature: Consistent -90 degree (or near -90 degree) pitch when shooting a bow.
 *
 * Detection logic:
 *   - processBowShoot() is called from PlayerListener on EntityShootBowEvent.
 *   - Checks the pitch angle at the time of shooting.
 *   - If pitch is near 90 degrees (downward, Math.abs(pitch - 90) < 15), it's suspicious.
 *   - Normal players almost never shoot straight down.
 *   - Uses a buffer system: quiverBuffer >= 3 triggers a flag.
 *
 * Normal player reference:
 *   - Normal bow usage pitch: -90 to 90 degrees (horizontal to straight up)
 *   - Pitch near 90 means looking straight down
 *   - Shooting straight down is extremely rare in normal gameplay
 *   - The only legitimate scenario might be shooting down a cliff, but repeated downward shots
 *     strongly indicate Quiver usage
 *
 * Exemptions:
 *   - None (shooting straight down is inherently a cheat signature for Quiver)
 */
public class QuiverCheck extends Check {

    // Detection thresholds
    private static final double PITCH_THRESHOLD = 15.0;            // Degrees from 90 (straight down)
    private static final int BUFFER_FLAG_THRESHOLD = 3;             // 3 violations before flagging
    private static final long COOLDOWN_MS = 1000L;                 // Cooldown between flags to avoid spam

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.10;

    /**
     * Internal tracker for per-player quiver state.
     * Stored in a ConcurrentHashMap for thread safety (Folia compatibility).
     */
    static class QuiverTracker {
        // Violation buffer
        int quiverBuffer;
        // Last quiver detection time (for cooldown)
        long lastQuiverTime;

        QuiverTracker() {
            this.quiverBuffer = 0;
            this.lastQuiverTime = 0;
        }

        void reset() {
            this.quiverBuffer = 0;
            this.lastQuiverTime = 0;
        }
    }

    private final ConcurrentHashMap<UUID, QuiverTracker> trackers = new ConcurrentHashMap<>();

    public QuiverCheck(ANSACPlugin plugin) {
        super(plugin, "Quiver", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // No periodic processing needed for this check
        // Detection is entirely event-driven via processBowShoot()
    }

    /**
     * Process a bow shoot action (called from PlayerListener on EntityShootBowEvent).
     * This is the main detection entry point for quiver detection.
     *
     * @param player The player who shot the bow
     * @param data   The player's anti-cheat data
     * @param pitch  The pitch angle of the player when shooting (in degrees)
     */
    public void processBowShoot(Player player, PlayerData data, float pitch) {
        if (!isEnabled() || data.hasBypass()) return;

        // Skip sleeping or dead players
        if (player.isSleeping() || player.isDead()) {
            return;
        }

        // No exemptions - shooting straight down is inherently a cheat signature for Quiver

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            QuiverTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.quiverBuffer = 0;
            }
            return;
        }

        // Check if pitch is near 90 degrees (straight down)
        // In Minecraft, pitch 90 = looking straight down, pitch -90 = looking straight up
        // We use Math.abs(pitch - 90) to detect looking straight down
        double pitchDiff = Math.abs(pitch - 90.0);

        if (pitchDiff < PITCH_THRESHOLD) {
            // Suspicious: shooting with near -90 degree pitch (straight down)
            long now = System.currentTimeMillis();
            QuiverTracker tracker = trackers.computeIfAbsent(
                player.getUniqueId(), k -> new QuiverTracker());

            // Check cooldown to avoid flag spam
            if (now - tracker.lastQuiverTime < COOLDOWN_MS) {
                return;
            }

            tracker.quiverBuffer++;

            // Ping-compensated buffer threshold
            int compensatedThreshold = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_FLAG_THRESHOLD, COMPENSATION_FACTOR);

            if (tracker.quiverBuffer >= compensatedThreshold) {
                double severity = tracker.quiverBuffer / (double) BUFFER_FLAG_THRESHOLD;
                flag(player, data, severity,
                    String.format("Quiver自增益: 射箭俯角 %.1f 度 (接近垂直向下, 连续 %d 次, 延迟 %s)",
                        pitch, tracker.quiverBuffer,
                        data.getPingCompensator().getPingStatus()));
                // Reset buffer and set cooldown after flagging
                tracker.quiverBuffer = 0;
                tracker.lastQuiverTime = now;
            } else {
                tracker.lastQuiverTime = now;
            }
        } else {
            // Legitimate pitch angle - gradually decay buffer
            QuiverTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null && tracker.quiverBuffer > 0) {
                tracker.quiverBuffer = Math.max(0, tracker.quiverBuffer - 1);
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
