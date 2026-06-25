package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AutoClicker check - detects automated clicking (auto-clicker / trigger bot).
 *
 * Cheat principle (Wurst TriggerBot + Meteor AutoClicker):
 *   Wurst TriggerBot: Automatically attacks when the crosshair is on an entity.
 *   Meteor AutoClicker: Automatically clicks at a set CPS, supports multiple modes
 *     (random, precise, etc.).
 *   Cheat signatures:
 *     - CPS is excessively uniform (standard deviation extremely low)
 *     - CPS consistently exceeds human limits for extended periods
 *
 * CPS reference (community data, minecraft.wiki 无此数据):
 *   Normal clicking: 6-7 CPS
 *   Jitter clicking: 12-16 CPS (max ~18 CPS)
 *   Butterfly clicking: 15-25 CPS (max ~28 CPS short bursts)
 *   Drag clicking: 30-100+ CPS (hardware dependent)
 *   Normal human sustained limit: ~14-16 CPS
 *   Human click interval standard deviation: typically > 30ms
 *
 * Detection logic:
 *   - processClick() is called from PacketListener on ANIMATION packets.
 *   - Records click timestamps and intervals between consecutive clicks.
 *   - Checks two dimensions:
 *     1. CPS too high: > 20 clicks in the last 1 second (exceeds human limit)
 *     2. Intervals too uniform: standard deviation of last 20 intervals < 15ms
 *   - Uses a buffer system to avoid false positives.
 *   - Uses PingCompensator for latency compensation.
 */
public class AutoClickerCheck extends Check {

    // Detection thresholds
    // MAX_CPS = 20: butterfly clicking can reach 20-25 CPS in short bursts.
    // 18 was too strict and caused false positives for skilled players.
    private static final int MAX_CPS = 20;
    private static final int CPS_WINDOW_MS = 1000; // 1 second window for CPS calculation
    private static final double MIN_INTERVAL_STD_DEV = 15.0; // Below this is suspicious (human > 30ms)
    private static final int STD_DEV_SAMPLE_SIZE = 20; // Number of intervals for std dev calculation
    private static final int MAX_STORED_INTERVALS = 100; // Keep last 100 click intervals
    private static final long DATA_EXPIRE_MS = 5000L; // Expire data older than 5 seconds

    // Buffer thresholds
    private static final int BUFFER_FLAG_THRESHOLD = 5; // Require 5 violations before flagging

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.10;

    /**
     * Internal tracker for per-player click data.
     * Stored in a ConcurrentHashMap for thread safety (Folia compatibility).
     */
    static class AutoClickerTracker {
        // Click intervals in milliseconds (time between consecutive clicks)
        final CopyOnWriteArrayList<Long> clickIntervals = new CopyOnWriteArrayList<>();
        // Click timestamps in milliseconds
        final CopyOnWriteArrayList<Long> clickTimestamps = new CopyOnWriteArrayList<>();
        // Violation buffer
        int autoClickerBuffer;
        // Last click timestamp for interval calculation
        long lastClickTime;

        AutoClickerTracker() {
            this.autoClickerBuffer = 0;
            this.lastClickTime = 0;
        }

        void reset() {
            this.clickIntervals.clear();
            this.clickTimestamps.clear();
            this.autoClickerBuffer = 0;
            this.lastClickTime = 0;
        }
    }

    private final ConcurrentHashMap<UUID, AutoClickerTracker> trackers = new ConcurrentHashMap<>();

    public AutoClickerCheck(ANSACPlugin plugin) {
        super(plugin, "AutoClicker", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Periodic cleanup of expired data
        AutoClickerTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) return;

        long cutoff = System.currentTimeMillis() - DATA_EXPIRE_MS;

        // Remove expired timestamps
        tracker.clickTimestamps.removeIf(t -> t < cutoff);

        // If no data left, clean up entirely
        if (tracker.clickTimestamps.isEmpty() && tracker.clickIntervals.isEmpty()) {
            trackers.remove(player.getUniqueId());
        }
    }

    /**
     * Process a click event (called from PacketListener on ANIMATION packets).
     * This is the main detection entry point for auto-clicker detection.
     *
     * @param player The player who clicked
     * @param data   The player's anti-cheat data
     */
    public void processClick(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // Skip creative/spectator mode, vehicles, sleeping, dead players
        if (shouldSkip(player)) return;

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            AutoClickerTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.autoClickerBuffer = 0;
            }
            return;
        }

        long now = System.currentTimeMillis();
        AutoClickerTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new AutoClickerTracker());

        // Record timestamp
        tracker.clickTimestamps.add(now);

        // Calculate interval from last click
        if (tracker.lastClickTime > 0) {
            long interval = now - tracker.lastClickTime;
            if (interval > 0) {
                tracker.clickIntervals.add(interval);

                // Trim to keep only the most recent intervals
                while (tracker.clickIntervals.size() > MAX_STORED_INTERVALS) {
                    tracker.clickIntervals.remove(0);
                }
            }
        }
        tracker.lastClickTime = now;

        // --- Check 1: CPS too high ---
        long cpsCutoff = now - CPS_WINDOW_MS;
        // Remove expired timestamps for CPS calculation
        tracker.clickTimestamps.removeIf(t -> t < cpsCutoff);
        int cps = tracker.clickTimestamps.size();

        // Ping-compensated CPS threshold
        double compensatedMaxCps = data.getPingCompensator().getCompensatedSpeed(
            MAX_CPS, COMPENSATION_FACTOR);

        boolean cpsViolation = false;
        if (cps > compensatedMaxCps) {
            cpsViolation = true;
        }

        // --- Check 2: Click intervals too uniform (standard deviation too low) ---
        boolean uniformViolation = false;
        double stdDev = calculateStandardDeviation(tracker.clickIntervals, STD_DEV_SAMPLE_SIZE);

        // Ping-compensated std dev threshold (high ping adds some jitter naturally)
        double compensatedMinStdDev = data.getPingCompensator().getCompensatedThreshold(
            MIN_INTERVAL_STD_DEV, COMPENSATION_FACTOR);

        if (stdDev >= 0 && stdDev < compensatedMinStdDev) {
            uniformViolation = true;
        }

        // --- Evaluate violations ---
        if (cpsViolation || uniformViolation) {
            tracker.autoClickerBuffer++;

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_FLAG_THRESHOLD, COMPENSATION_FACTOR);

            if (tracker.autoClickerBuffer >= compensatedBuffer) {
                StringBuilder details = new StringBuilder();
                if (cpsViolation) {
                    details.append(String.format("CPS过高: %d (上限 %.0f)", cps, compensatedMaxCps));
                }
                if (uniformViolation) {
                    if (details.length() > 0) details.append(", ");
                    details.append(String.format("点击间隔过于均匀: 标准差=%.1fms (阈值: %.1fms)",
                        stdDev, compensatedMinStdDev));
                }
                details.append(", 延迟 ").append(data.getPingCompensator().getPingStatus());

                double severity = 1.0;
                if (cpsViolation) {
                    severity = Math.max(severity, cps / compensatedMaxCps);
                }
                if (uniformViolation) {
                    severity = Math.max(severity, compensatedMinStdDev / Math.max(stdDev, 0.1));
                }

                flag(player, data, severity,
                    String.format("自动点击器: %s (连续 %d 次, 延迟 %s)",
                        details.toString(), tracker.autoClickerBuffer,
                        data.getPingCompensator().getPingStatus()));
            }
        } else {
            // Gradually decay buffer on legitimate clicks
            if (tracker.autoClickerBuffer > 0) {
                tracker.autoClickerBuffer = Math.max(0, tracker.autoClickerBuffer - 1);
            }
        }
    }

    /**
     * Calculate the standard deviation of the most recent N click intervals.
     * Formula: sqrt(sum((xi - mean)^2) / n)
     *
     * @param intervals All stored intervals
     * @param sampleSize Number of most recent intervals to consider
     * @return Standard deviation in milliseconds, or -1 if not enough data
     */
    private double calculateStandardDeviation(CopyOnWriteArrayList<Long> intervals, int sampleSize) {
        if (intervals.size() < sampleSize) {
            return -1; // Not enough data
        }

        // Get the most recent N intervals
        int size = intervals.size();
        int start = size - sampleSize;

        // Calculate mean
        double sum = 0;
        for (int i = start; i < size; i++) {
            sum += intervals.get(i);
        }
        double mean = sum / sampleSize;

        // Calculate variance
        double varianceSum = 0;
        for (int i = start; i < size; i++) {
            double diff = intervals.get(i) - mean;
            varianceSum += diff * diff;
        }
        double variance = varianceSum / sampleSize;

        return Math.sqrt(variance);
    }

    /**
     * Check if the player should be skipped entirely (non-cheat reasons).
     */
    private boolean shouldSkip(Player player) {
        String gm = player.getGameMode().name();
        return gm.contains("CREATIVE") || gm.contains("SPECTATOR")
            || player.isInsideVehicle()
            || player.isSleeping() || player.isDead();
    }
}
