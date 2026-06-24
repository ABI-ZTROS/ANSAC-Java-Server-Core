package dev.ztros.ansac.checks.combat;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.player.PingCompensator;
import dev.ztros.ansac.player.PlayerData;
import org.bukkit.Material;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CrystalAura check - detects automated end crystal placement and destruction.
 *
 * 作弊原理（基于 Wurst/Meteor Client CrystalAura 源码分析）:
 *   Meteor: 每秒最多 25 次攻击, placeDelay/breakDelay 默认 0
 *   Wurst: 每 tick 放置+破坏水晶
 *   自动从背包切换到末影水晶物品
 *   精确计算伤害最优放置位置
 *   fastBreak: 水晶刚生成立即被攻击 (EntityAddedEvent HIGH priority)
 *   支撑方块模式: 空中放置黑曜石再放水晶
 *
 * 物理参考数据（Minecraft Wiki）:
 *   末影水晶碰撞箱: 2.0 x 2.0 x 2.0 格
 *   爆炸威力: 6 (与带电苦力怕相同)
 *   放置条件: 黑曜石或基岩上方, 上方 2 格空气
 *   一击必杀: 任何伤害都会触发爆炸
 *   水晶不是固体实体: 可以穿过
 *
 * 检测策略:
 * 1. 追踪对 EndCrystal 的攻击频率 (正常人类约 2-4 次/秒)
 * 2. 追踪末影水晶放置频率
 * 3. 检测 hotbar 快速切换到末影水晶
 * 4. 检测攻击-放置-攻击的循环模式
 * 5. 检测水晶放置在黑曜石/基岩上的精确模式
 */
public class CrystalAuraCheck extends Check {

    private static final int MAX_CRYSTAL_ATTACKS_PER_SECOND = 6; // 正常人类最多 6 次/秒
    private static final int MAX_CRYSTAL_PLACES_PER_SECOND = 4; // 正常人类最多 4 次/秒
    private static final int BUFFER_MAX = 5;
    private static final int SAMPLE_WINDOW_MS = 1000; // 1秒窗口
    private static final int SWITCH_WINDOW_MS = 100; // 切换到攻击的时间窗口

    // Per-player tracking data (avoids modifying PlayerData)
    private final ConcurrentHashMap<UUID, CrystalTracker> trackers = new ConcurrentHashMap<>();

    public CrystalAuraCheck(ANSACPlugin plugin) {
        super(plugin, "CrystalAura", "Combat");
    }

    /**
     * Get or create tracker for a player
     */
    private CrystalTracker getTracker(UUID uuid) {
        return trackers.computeIfAbsent(uuid, k -> new CrystalTracker());
    }

    /**
     * 处理攻击事件（在 PlayerListener 中调用）
     */
    public void processAttack(Player player, PlayerData data, Entity target) {
        if (!isEnabled() || data.hasBypass()) return;

        // 跳过创造/旁观模式
        String gm = player.getGameMode().name();
        if (gm.contains("CREATIVE") || gm.contains("SPECTATOR")) return;

        // Ping compensation
        if (data.getPingCompensator().shouldSkipCheck()) return;

        // 只检测对末影水晶的攻击
        if (!(target instanceof EnderCrystal)) return;

        long now = System.currentTimeMillis();
        CrystalTracker tracker = getTracker(player.getUniqueId());

        // 清理过期样本
        tracker.attackTimestamps.removeIf(t -> now - t > SAMPLE_WINDOW_MS);

        // 记录此次攻击
        tracker.attackTimestamps.add(now);

        // 检查攻击频率
        int attacksInWindow = tracker.attackTimestamps.size();
        if (attacksInWindow > MAX_CRYSTAL_ATTACKS_PER_SECOND) {
            tracker.auraBuffer++;

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_MAX, PingCompensator.COMPENSATION_KILLAURA);

            if (tracker.auraBuffer >= compensatedBuffer) {
                double severity = attacksInWindow / (double) MAX_CRYSTAL_ATTACKS_PER_SECOND;
                flag(player, data, severity,
                    String.format("水晶攻击频率异常: %d 次/秒 (上限: %d, 连续 %d 次检测, 延迟 %s)",
                        attacksInWindow, MAX_CRYSTAL_ATTACKS_PER_SECOND, tracker.auraBuffer,
                        data.getPingCompensator().getPingStatus()));
            }
        } else {
            // 频率正常，衰减
            if (tracker.auraBuffer > 0) {
                tracker.auraBuffer--;
            }
        }

        // 检测 hotbar 切换到末影水晶
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (mainHand.getType() == Material.END_CRYSTAL) {
            // 玩家手持末影水晶攻击 = 可疑（正常应该用武器/工具攻击水晶）
            // 但也可能是手动操作，使用较低权重
            if (tracker.lastHotbarSwitchTime > 0 && now - tracker.lastHotbarSwitchTime < SWITCH_WINDOW_MS) {
                // SWITCH_WINDOW_MS 内切换到末影水晶并攻击 = 高度可疑
                tracker.switchBuffer++;

                int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                    BUFFER_MAX + 2, PingCompensator.COMPENSATION_KILLAURA);

                if (tracker.switchBuffer >= compensatedBuffer) {
                    flag(player, data, 1.2,
                        String.format("水晶自动切换: 攻击前 %dms 切换到末影水晶 (连续 %d 次, 延迟 %s)",
                            now - tracker.lastHotbarSwitchTime, tracker.switchBuffer,
                            data.getPingCompensator().getPingStatus()));
                }
            }
        }
    }

    /**
     * 处理方块放置事件（在 PlayerListener 中调用）
     * 检测末影水晶放置频率
     */
    public void processBlockPlace(Player player, PlayerData data, Material placedMaterial) {
        if (!isEnabled() || data.hasBypass()) return;

        if (placedMaterial != Material.END_CRYSTAL) return;

        long now = System.currentTimeMillis();
        CrystalTracker tracker = getTracker(player.getUniqueId());

        // 清理过期样本
        tracker.placeTimestamps.removeIf(t -> now - t > SAMPLE_WINDOW_MS);

        // 记录此次放置
        tracker.placeTimestamps.add(now);

        // 检查放置频率
        int placesInWindow = tracker.placeTimestamps.size();
        if (placesInWindow > MAX_CRYSTAL_PLACES_PER_SECOND) {
            tracker.auraBuffer++;

            int compensatedBuffer = data.getPingCompensator().getCompensatedBuffer(
                BUFFER_MAX, PingCompensator.COMPENSATION_KILLAURA);

            if (tracker.auraBuffer >= compensatedBuffer) {
                double severity = placesInWindow / (double) MAX_CRYSTAL_PLACES_PER_SECOND;
                flag(player, data, severity,
                    String.format("水晶放置频率异常: %d 次/秒 (上限: %d, 连续 %d 次检测, 延迟 %s)",
                        placesInWindow, MAX_CRYSTAL_PLACES_PER_SECOND, tracker.auraBuffer,
                        data.getPingCompensator().getPingStatus()));
            }
        }
    }

    /**
     * 处理 hotbar 切换事件（在 PlayerListener 中调用）
     * 记录玩家切换到末影水晶的时间
     */
    public void processHotbarSwitch(Player player, PlayerData data, int newSlot) {
        if (!isEnabled() || data.hasBypass()) return;

        ItemStack item = player.getInventory().getItem(newSlot);
        if (item != null && item.getType() == Material.END_CRYSTAL) {
            CrystalTracker tracker = getTracker(player.getUniqueId());
            tracker.lastHotbarSwitchTime = System.currentTimeMillis();
        }
    }

    /**
     * 清理玩家数据（玩家退出时调用）
     */
    public void cleanupPlayer(UUID uuid) {
        trackers.remove(uuid);
    }

    @Override
    public void process(Player player, PlayerData data) {
        // Periodic cleanup of expired timestamps
        long now = System.currentTimeMillis();
        CrystalTracker tracker = trackers.get(player.getUniqueId());
        if (tracker != null) {
            tracker.attackTimestamps.removeIf(t -> now - t > SAMPLE_WINDOW_MS);
            tracker.placeTimestamps.removeIf(t -> now - t > SAMPLE_WINDOW_MS);
        }
    }

    /**
     * Internal per-player tracking data for CrystalAura detection.
     * Uses CopyOnWriteArrayList for thread safety with Folia.
     */
    private static class CrystalTracker {
        final List<Long> attackTimestamps = new CopyOnWriteArrayList<>();
        final List<Long> placeTimestamps = new CopyOnWriteArrayList<>();
        int auraBuffer = 0;
        int switchBuffer = 0;
        long lastHotbarSwitchTime = 0;
    }
}
