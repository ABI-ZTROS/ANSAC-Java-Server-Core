package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * AutoArmor check - detects automated armor equipping (auto-armor cheat).
 *
 * Cheat principle (Wurst AutoArmor + Meteor AutoArmor):
 *   Wurst AutoArmor: Automatically equips the best armor from inventory to equipment slots.
 *   Meteor AutoArmor: Similar, supports more options (delay, priority, etc.).
 *   Cheat signature: Multiple inventory open/close and armor equip actions in extremely
 *     short time periods. Normal players cannot operate this fast.
 *
 * Detection logic:
 *   - processInventoryAction() is called from PlayerListener on InventoryClickEvent.
 *   - Records inventory action timestamps and armor change timestamps.
 *   - Compares current equipment with previously recorded equipment to detect armor changes.
 *   - Checks two dimensions:
 *     1. Frequent armor changes: >= 4 armor changes within 2 seconds
 *     2. Instant armor changes: >= 2 armor changes within 500ms (impossible for humans)
 *   - Uses a buffer system to avoid false positives.
 *   - Uses PingCompensator for latency compensation.
 *
 * Normal player reference:
 *   - Opening inventory: ~200-500ms
 *   - Moving item to armor slot: ~300-800ms per piece
 *   - Full armor equip (4 pieces): ~2-5 seconds minimum
 *   - Fast player (experienced PvP): ~1-2 seconds for full armor swap
 */
public class AutoArmorCheck extends Check {

    // Detection thresholds
    private static final int ARMOR_CHANGES_2S_THRESHOLD = 4; // >= 4 changes in 2 seconds
    private static final long ARMOR_WINDOW_2S_MS = 2000L; // 2 second window
    private static final int ARMOR_CHANGES_INSTANT_THRESHOLD = 2; // >= 2 changes in 500ms
    private static final long INSTANT_WINDOW_MS = 500L; // 500ms window (impossible for humans)
    private static final int BUFFER_FLAG_THRESHOLD = 3; // Require 3 violations before flagging
    private static final long DATA_EXPIRE_MS = 5000L; // Expire data older than 5 seconds

    // Ping compensation factor for this check
    private static final double COMPENSATION_FACTOR = 0.10;

    /**
     * Internal tracker for per-player auto-armor state.
     * Stored in a ConcurrentHashMap for thread safety (Folia compatibility).
     */
    static class AutoArmorTracker {
        // Inventory action timestamps
        final CopyOnWriteArrayList<Long> actionTimestamps = new CopyOnWriteArrayList<>();
        // Armor change timestamps
        final CopyOnWriteArrayList<Long> armorChangeTimestamps = new CopyOnWriteArrayList<>();
        // Violation buffer
        int autoArmorBuffer;
        // Last recorded armor state (hash codes of armor items)
        int[] lastArmorHashes;

        AutoArmorTracker() {
            this.autoArmorBuffer = 0;
            this.lastArmorHashes = new int[4]; // helmet, chestplate, leggings, boots
        }

        void reset() {
            this.actionTimestamps.clear();
            this.armorChangeTimestamps.clear();
            this.autoArmorBuffer = 0;
            this.lastArmorHashes = new int[4];
        }
    }

    private final ConcurrentHashMap<UUID, AutoArmorTracker> trackers = new ConcurrentHashMap<>();

    public AutoArmorCheck(ANSACPlugin plugin) {
        super(plugin, "AutoArmor", "Combat");
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Periodic cleanup of expired data
        AutoArmorTracker tracker = trackers.get(player.getUniqueId());
        if (tracker == null) return;

        long cutoff = System.currentTimeMillis() - DATA_EXPIRE_MS;

        // Remove expired timestamps
        tracker.actionTimestamps.removeIf(t -> t < cutoff);
        tracker.armorChangeTimestamps.removeIf(t -> t < cutoff);

        // If no data left, clean up entirely
        if (tracker.actionTimestamps.isEmpty() && tracker.armorChangeTimestamps.isEmpty()) {
            trackers.remove(player.getUniqueId());
        }
    }

    /**
     * Process an inventory action (called from PlayerListener on InventoryClickEvent).
     * This is the main detection entry point for auto-armor detection.
     *
     * @param player The player who performed the inventory action
     * @param data   The player's anti-cheat data
     */
    public void processInventoryAction(Player player, PlayerData data) {
        if (!isEnabled() || data.hasBypass()) return;

        // Skip creative mode (creative players can instantly swap armor legitimately)
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) {
            return;
        }

        // Skip vehicles, sleeping, dead players
        if (player.isInsideVehicle() || player.isSleeping() || player.isDead()) {
            return;
        }

        // Ping compensation: skip check if latency is too high or spiking
        if (data.getPingCompensator().shouldSkipCheck()) {
            AutoArmorTracker tracker = trackers.get(player.getUniqueId());
            if (tracker != null) {
                tracker.autoArmorBuffer = 0;
            }
            return;
        }

        long now = System.currentTimeMillis();
        AutoArmorTracker tracker = trackers.computeIfAbsent(
            player.getUniqueId(), k -> new AutoArmorTracker());

        // Record inventory action timestamp
        tracker.actionTimestamps.add(now);

        // Check if armor has changed by comparing current equipment with last recorded state
        int[] currentArmorHashes = getCurrentArmorHashes(player);
        boolean armorChanged = false;

        if (tracker.lastArmorHashes[0] != -1) {
            // We have a previous state to compare against
            for (int i = 0; i < 4; i++) {
                if (currentArmorHashes[i] != tracker.lastArmorHashes[i]) {
                    armorChanged = true;
                    break;
                }
            }
        }

        // Update last known armor state
        System.arraycopy(currentArmorHashes, 0, tracker.lastArmorHashes, 0, 4);

        if (armorChanged) {
            // Record armor change timestamp
            tracker.armorChangeTimestamps.add(now);

            // Clean up old armor change timestamps (keep last 5 seconds)
            long cutoff = now - DATA_EXPIRE_MS;
            tracker.armorChangeTimestamps.removeIf(t -> t < cutoff);

            // --- Check 1: Instant armor change (impossible for humans) ---
            // >= 2 armor changes within 500ms
            long instantCutoff = now - INSTANT_WINDOW_MS;
            int instantChanges = 0;
            for (Long timestamp : tracker.armorChangeTimestamps) {
                if (timestamp >= instantCutoff) {
                    instantChanges++;
                }
            }

            if (instantChanges >= ARMOR_CHANGES_INSTANT_THRESHOLD) {
                // Direct flag - this is impossible for humans
                flag(player, data, 2.0,
                    String.format("瞬间换甲: %d 次护甲切换在 %dms 内 (人类不可能如此快速, 延迟 %s)",
                        instantChanges, INSTANT_WINDOW_MS,
                        data.getPingCompensator().getPingStatus()));
                // Reset buffer after direct flag to avoid spam
                tracker.autoArmorBuffer = 0;
                return;
            }

            // --- Check 2: Frequent armor changes ---
            // >= 4 armor changes within 2 seconds
            long windowCutoff = now - ARMOR_WINDOW_2S_MS;
            int windowChanges = 0;
            for (Long timestamp : tracker.armorChangeTimestamps) {
                if (timestamp >= windowCutoff) {
                    windowChanges++;
                }
            }

            // Ping-compensated threshold
            int compensatedThreshold = data.getPingCompensator().getCompensatedBuffer(
                ARMOR_CHANGES_2S_THRESHOLD, COMPENSATION_FACTOR);

            if (windowChanges >= compensatedThreshold) {
                tracker.autoArmorBuffer++;

                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_FLAG_THRESHOLD, COMPENSATION_FACTOR);

                if (tracker.autoArmorBuffer >= compensatedBuffer) {
                    double severity = windowChanges / (double) ARMOR_CHANGES_2S_THRESHOLD;
                    flag(player, data, severity,
                        String.format("自动换甲: %d 次护甲切换在 2 秒内 (连续 %d 次, 延迟 %s)",
                            windowChanges, tracker.autoArmorBuffer,
                            data.getPingCompensator().getPingStatus()));
                }
            } else {
                // Gradually decay buffer on legitimate armor changes
                if (tracker.autoArmorBuffer > 0) {
                    tracker.autoArmorBuffer = Math.max(0, tracker.autoArmorBuffer - 1);
                }
            }
        }
    }

    /**
     * Get the current armor equipment as hash codes for comparison.
     * Returns an array of 4 integers: [helmet, chestplate, leggings, boots].
     * Uses System.identityHashCode for ItemStack comparison (type + durability + enchantments).
     *
     * @param player The player to check
     * @return Array of 4 hash codes representing armor pieces
     */
    private int[] getCurrentArmorHashes(Player player) {
        int[] hashes = new int[4];
        ItemStack[] armorContents = player.getInventory().getArmorContents();

        for (int i = 0; i < 4 && i < armorContents.length; i++) {
            ItemStack item = armorContents[i];
            if (item == null || item.getType().isAir()) {
                hashes[i] = 0; // Empty slot
            } else {
                // Create a composite hash from type, durability, and enchantments
                int hash = item.getType().name().hashCode();
                if (item.hasItemMeta()) {
                    hash = hash * 31 + item.getItemMeta().hashCode();
                }
                hashes[i] = hash;
            }
        }

        return hashes;
    }
}
