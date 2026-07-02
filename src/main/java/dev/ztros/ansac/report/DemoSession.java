package dev.ztros.ansac.report;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * 单个玩家的 Demo 录制会话。
 * <p>
 * 从玩家进服开始录制，退服时保存到 YAML 文件。
 * 录制内容：
 * <ul>
 *   <li>移动数据：坐标变化、高度变化（每秒采样一次）</li>
 *   <li>战斗数据：被攻击目标名称/ID、相对距离、攻击次数</li>
 *   <li>玩家自身数据：Buff/Debuff、OP/特权状态</li>
 *   <li>地形数据：玩家周围方块快照</li>
 * </ul>
 * </p>
 */
public class DemoSession {

    private final Player player;
    private final long startTime;
    private volatile boolean active = true;

    // ========== 移动采样（每秒一次） ==========
    private final List<MovementSample> movementSamples = new ArrayList<>();
    private Location lastLocation;

    // ========== 战斗采样 ==========
    private final List<CombatEntry> combatEntries = new ArrayList<>();

    // ========== 玩家状态采样（每10秒一次） ==========
    private final List<PlayerStateSample> playerStateSamples = new ArrayList<>();

    // ========== 地形快照（每30秒一次） ==========
    private final List<TerrainSnapshot> terrainSnapshots = new ArrayList<>();

    // 采样限制（防止内存爆炸）
    private static final int MAX_MOVEMENT_SAMPLES = 600;  // 10分钟 @ 1秒/次
    private static final int MAX_COMBAT_ENTRIES = 500;
    private static final int MAX_PLAYER_STATE_SAMPLES = 60; // 10分钟 @ 10秒/次
    private static final int MAX_TERRAIN_SNAPSHOTS = 20;    // 10分钟 @ 30秒/次

    public DemoSession(Player player) {
        this.player = player;
        this.startTime = System.currentTimeMillis();
        this.lastLocation = player.getLocation().clone();
    }

    /**
     * 采样移动数据（由定时任务每秒调用）。
     */
    public void sampleMovement() {
        if (!active || movementSamples.size() >= MAX_MOVEMENT_SAMPLES) return;

        Location current = player.getLocation();
        if (lastLocation != null && current.getWorld() != null) {
            double deltaX = current.getX() - lastLocation.getX();
            double deltaY = current.getY() - lastLocation.getY();
            double deltaZ = current.getZ() - lastLocation.getZ();
            double horizontalDist = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);
            double totalDist = Math.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);

            movementSamples.add(new MovementSample(
                System.currentTimeMillis() - startTime,
                current.getX(), current.getY(), current.getZ(),
                deltaX, deltaY, deltaZ,
                horizontalDist, totalDist,
                current.getYaw(), current.getPitch(),
                player.isOnGround(),
                current.getWorld().getName()
            ));
        }
        lastLocation = current.clone();
    }

    /**
     * 记录战斗事件（由攻击事件调用）。
     */
    public void recordCombat(String targetName, int targetEntityId, double distance,
                              int attackCount, double cps, boolean isCrit,
                              float yawDelta, float pitchDelta) {
        if (!active || combatEntries.size() >= MAX_COMBAT_ENTRIES) return;

        combatEntries.add(new CombatEntry(
            System.currentTimeMillis() - startTime,
            targetName, targetEntityId, distance,
            attackCount, cps, isCrit, yawDelta, pitchDelta
        ));
    }

    /**
     * 采样玩家自身状态（每10秒调用）。
     */
    public void samplePlayerState() {
        if (!active || playerStateSamples.size() >= MAX_PLAYER_STATE_SAMPLES) return;

        List<PotionInfo> potions = new ArrayList<>();
        for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
            potions.add(new PotionInfo(
                effect.getType().getName(),
                effect.getAmplifier(),
                effect.getDuration()
            ));
        }

        boolean isOp = player.isOp();
        boolean hasBypass = player.hasPermission("ansac.bypass");
        boolean hasAdmin = player.hasPermission("ansac.admin");

        double health = player.getHealth();
        double maxHealth = player.getMaxHealth();
        int foodLevel = player.getFoodLevel();
        float fallDistance = player.getFallDistance();
        int fireTicks = player.getFireTicks();
        boolean isSprinting = player.isSprinting();
        boolean isSneaking = player.isSneaking();
        boolean isGliding = player.isGliding();
        boolean isBlocking = player.isBlocking();
        boolean isInWater = player.isInWater();
        boolean isFlying = player.isFlying();
        boolean allowFlight = player.getAllowFlight();

        // 装备信息
        String helmet = player.getInventory().getHelmet() != null
            ? player.getInventory().getHelmet().getType().name() : "NONE";
        String chestplate = player.getInventory().getChestplate() != null
            ? player.getInventory().getChestplate().getType().name() : "NONE";
        String leggings = player.getInventory().getLeggings() != null
            ? player.getInventory().getLeggings().getType().name() : "NONE";
        String boots = player.getInventory().getBoots() != null
            ? player.getInventory().getBoots().getType().name() : "NONE";

        // 主手物品
        String mainHand = player.getInventory().getItemInMainHand() != null
            ? player.getInventory().getItemInMainHand().getType().name() : "AIR";

        playerStateSamples.add(new PlayerStateSample(
            System.currentTimeMillis() - startTime,
            potions, isOp, hasBypass, hasAdmin,
            health, maxHealth, foodLevel, fallDistance, fireTicks,
            isSprinting, isSneaking, isGliding, isBlocking, isInWater, isFlying, allowFlight,
            helmet, chestplate, leggings, boots, mainHand,
            player.getPing()
        ));
    }

    /**
     * 采样地形数据（每30秒调用）。
     */
    public void sampleTerrain() {
        if (!active || terrainSnapshots.size() >= MAX_TERRAIN_SNAPSHOTS) return;

        Location loc = player.getLocation();
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : "UNKNOWN";
        int blockX = loc.getBlockX();
        int blockY = loc.getBlockY();
        int blockZ = loc.getBlockZ();

        // 采集周围 5x5x5 方块快照
        List<String> blocks = new ArrayList<>();
        int radius = 2;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    try {
                        org.bukkit.block.Block block = loc.getWorld().getBlockAt(
                            blockX + dx, blockY + dy, blockZ + dz);
                        if (block.getType() != org.bukkit.Material.AIR) {
                            blocks.add(dx + "," + dy + "," + dz + ":" + block.getType().name());
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        // 脚下方块
        String groundBlock = "AIR";
        try {
            org.bukkit.block.Block below = loc.getWorld().getBlockAt(blockX, blockY - 1, blockZ);
            groundBlock = below.getType().name();
        } catch (Exception ignored) {}

        // 头顶方块
        String headBlock = "AIR";
        try {
            org.bukkit.block.Block above = loc.getWorld().getBlockAt(blockX, blockY + 2, blockZ);
            headBlock = above.getType().name();
        } catch (Exception ignored) {}

        terrainSnapshots.add(new TerrainSnapshot(
            System.currentTimeMillis() - startTime,
            worldName, blockX, blockY, blockZ,
            groundBlock, headBlock, blocks
        ));
    }

    public void stop() {
        active = false;
    }

    public Player getPlayer() { return player; }
    public long getStartTime() { return startTime; }
    public List<MovementSample> getMovementSamples() { return movementSamples; }
    public List<CombatEntry> getCombatEntries() { return combatEntries; }
    public List<PlayerStateSample> getPlayerStateSamples() { return playerStateSamples; }
    public List<TerrainSnapshot> getTerrainSnapshots() { return terrainSnapshots; }

    // ==================== 内部数据类 ====================

    public record MovementSample(
        long timestamp,
        double x, double y, double z,
        double deltaX, double deltaY, double deltaZ,
        double horizontalDist, double totalDist,
        float yaw, float pitch,
        boolean onGround,
        String world
    ) {}

    public record CombatEntry(
        long timestamp,
        String targetName, int targetEntityId,
        double distance, int attackCount,
        double cps, boolean isCrit,
        float yawDelta, float pitchDelta
    ) {}

    public record PlayerStateSample(
        long timestamp,
        List<PotionInfo> potions,
        boolean isOp, boolean hasBypass, boolean hasAdmin,
        double health, double maxHealth, int foodLevel,
        float fallDistance, int fireTicks,
        boolean isSprinting, boolean isSneaking, boolean isGliding,
        boolean isBlocking, boolean isInWater, boolean isFlying, boolean allowFlight,
        String helmet, String chestplate, String leggings, String boots,
        String mainHand,
        int ping
    ) {}

    public record PotionInfo(String name, int amplifier, int duration) {}

    public record TerrainSnapshot(
        long timestamp,
        String world, int blockX, int blockY, int blockZ,
        String groundBlock, String headBlock,
        List<String> surroundingBlocks
    ) {}
}
