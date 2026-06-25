package dev.ztros.ansac.checks.player;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AntiHunger check - detects modified onGround=false packets to prevent hunger consumption.
 *
 * Cheat principle (Wurst AntiHunger):
 *   Wurst AntiHunger: Modifies movement packets to send onGround=false, preventing the server
 *   from calculating hunger depletion. The player appears to be sprinting/jumping but the server
 *   thinks they are airborne, skipping hunger drain calculations.
 *   Cheat signature: Player moves on the ground for extended periods but food level never decreases.
 *
 * Detection logic:
 *   - process() is called every tick.
 *   - Tracks how long a player has been moving on the ground.
 *   - Records the player's food level at the start of ground movement.
 *   - After 100 ticks (5 seconds) of continuous ground movement, checks if food level has decreased.
 *   - If food level has NOT decreased despite ground movement, increments antiHungerBuffer.
 *   - antiHungerBuffer >= 3 triggers a flag.
 *
 * Exemptions:
 *   - Creative/Spectator mode (no hunger depletion in these modes)
 *   - Saturation effect (prevents hunger depletion naturally)
 *   - Player in water (hunger depletion works differently)
 *   - Player not moving horizontally (standing still doesn't deplete hunger from sprint)
 *
 * Normal player reference:
 *   - Sprinting hunger drain: 0.6 exhaustion per meter (1 point every ~50 blocks)
 *   - Jumping hunger drain: 0.2 exhaustion per jump
 *   - Normal food level: 20 (full), depletes over time while sprinting
 */
public class AntiHungerCheck extends Check {

    // Detection thresholds
    private static final int GROUND_MOVE_TICKS_THRESHOLD = 100;  // 5 seconds (100 ticks) of ground movement
    private static final double MIN_HORIZONTAL_MOVE = 0.1;       // Minimum horizontal movement per tick
    private static final int BUFFER_FLAG_THRESHOLD = 3;           // 3 violations before flagging
    private static final double DELTA_Y_STABILITY = 0.05;         // deltaY near 0 indicates stable Y position

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.10;

    /**
     * Internal tracker for per-player anti-hunger state.
     * Stored in a ConcurrentHashMap for thread safety (Folia compatibility).
     */
    static class AntiHungerTracker {
        // Consecutive ticks of ground movement
        int groundMoveTicks;
        // Last food level recorded at start of ground movement
        int lastFoodLevel;
        // Time of last hunger check
        long lastHungerCheck;
        // Whether we've already checked hunger for this ground movement streak
        boolean hungerChecked;
        // Violation buffer
        int antiHungerBuffer;

        AntiHungerTracker() {
            this.groundMoveTicks = 0;
            this.lastFoodLevel = 20;
            this.lastHungerCheck = 0;
            this.hungerChecked = false;
            this.antiHungerBuffer = 0;
        }

        void reset() {
            this.groundMoveTicks = 0;
            this.lastFoodLevel = 20;
            this.lastHungerCheck = 0;
            this.hungerChecked = false;
            this.antiHungerBuffer = 0;
        }
    }

    private final ConcurrentHashMap<UUID, AntiHungerTracker> trackers = new ConcurrentHashMap<>();

    public AntiHungerCheck(ANSACPlugin plugin) {
        super(plugin, "AntiHunger", "Player");
    }

    @Override
    public void process(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // Skip creative/spectator mode
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) {
            return;
        }

        // Skip sleeping or dead players
        if (player.isSleeping() || player.isDead()) {
            return;
        }

        // Skip if player has saturation effect (prevents hunger depletion naturally)
        if (player.hasPotionEffect(PotionEffectType.SATURATION)) {
            return;
        }

        // Skip if player is in water
        if (player.isInWater() || player.isInLava()) {
            return;
        }

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            AntiHungerTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.groundMoveTicks = 0;
            }
            return;
        }

        // Check if player is on the ground and moving horizontally
        boolean onGround = player.isOnGround();
        double horizontalDist = data.getHorizontalDistance();
        double deltaY = data.getVerticalDistance();

        AntiHungerTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new AntiHungerTracker());

        if (onGround && horizontalDist > MIN_HORIZONTAL_MOVE && Math.abs(deltaY) < DELTA_Y_STABILITY) {
            // Player is on ground and moving horizontally with stable Y
            if (tracker.groundMoveTicks == 0) {
                // Start of a new ground movement streak - record food level
                tracker.lastFoodLevel = player.getFoodLevel();
                tracker.hungerChecked = false;
            }

            tracker.groundMoveTicks++;

            // After 100 ticks (5 seconds), check if hunger has decreased
            if (tracker.groundMoveTicks >= GROUND_MOVE_TICKS_THRESHOLD && !tracker.hungerChecked) {
                tracker.hungerChecked = true;
                tracker.lastHungerCheck = System.currentTimeMillis();

                int currentFoodLevel = player.getFoodLevel();

                // If food level hasn't decreased (or increased, which is also suspicious)
                // while the player has been sprinting on the ground for 5 seconds
                if (currentFoodLevel >= tracker.lastFoodLevel && player.isSprinting()) {
                    // Suspicious: sprinting for 5 seconds without any hunger depletion
                    tracker.antiHungerBuffer++;

                    // Ping-compensated buffer threshold
                    int compensatedThreshold = data.getPingCompensator().getCompensatedBuffer(
                        BUFFER_FLAG_THRESHOLD, COMPENSATION_FACTOR);

                    if (tracker.antiHungerBuffer >= compensatedThreshold) {
                        double severity = tracker.antiHungerBuffer / (double) BUFFER_FLAG_THRESHOLD;
                        flag(player, data, severity,
                            String.format("反饥饿: 地面疾跑 %d tick 后饥饿值未减少 (初始=%d, 当前=%d, 连续 %d 次, 延迟 %s)",
                                tracker.groundMoveTicks, tracker.lastFoodLevel, currentFoodLevel,
                                tracker.antiHungerBuffer,
                                data.getPingCompensator().getPingStatus()));
                        // Reset buffer after flagging
                        tracker.antiHungerBuffer = 0;
                    }
                } else if (currentFoodLevel < tracker.lastFoodLevel) {
                    // Hunger decreased normally - reset buffer
                    tracker.antiHungerBuffer = 0;
                }
            }
        } else {
            // Player not on ground, not moving enough, or Y is unstable - reset ground movement counter
            tracker.groundMoveTicks = 0;
        }
    }

    /**
     * Clean up tracker when player disconnects.
     */
    public void onPlayerQuit(UUID uuid) {
        trackers.remove(uuid);
    }
}
