package dev.ztros.ansac.report;

import dev.ztros.ansac.ANSACPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Demo 录制管理器。
 * <p>
 * 管理所有在线玩家的 Demo 录制会话。
 * 玩家进服时自动开始录制，退服时自动保存到 YAML 文件。
 * </p>
 * <ul>
 *   <li>移动数据：每秒采样（坐标变化、高度变化）</li>
 *   <li>玩家状态：每 10 秒采样（Buff/Debuff、OP/特权）</li>
 *   <li>地形数据：每 30 秒采样（周围方块快照）</li>
 *   <li>战斗数据：事件驱动（攻击时实时记录）</li>
 * </ul>
 */
public class DemoRecorderManager {

    private final ANSACPlugin plugin;
    private final File demoDir;
    private final ConcurrentHashMap<UUID, DemoSession> sessions = new ConcurrentHashMap<>();

    public DemoRecorderManager(ANSACPlugin plugin) {
        this.plugin = plugin;
        this.demoDir = new File(plugin.getDataFolder(), "demos");
        if (!demoDir.exists()) {
            demoDir.mkdirs();
        }
    }

    /**
     * 启动定时采样任务。
     */
    public void start() {
        // 移动采样：每秒（20 tick）
        plugin.getSchedulerAdapter().runTimer(() -> {
            for (DemoSession session : sessions.values()) {
                try {
                    session.sampleMovement();
                } catch (Exception ignored) {}
            }
        }, 20L, 20L);

        // 玩家状态采样：每 10 秒（200 tick）
        plugin.getSchedulerAdapter().runTimer(() -> {
            for (DemoSession session : sessions.values()) {
                try {
                    session.samplePlayerState();
                } catch (Exception ignored) {}
            }
        }, 200L, 200L);

        // 地形采样：每 30 秒（600 tick）
        plugin.getSchedulerAdapter().runTimer(() -> {
            for (DemoSession session : sessions.values()) {
                try {
                    session.sampleTerrain();
                } catch (Exception ignored) {}
            }
        }, 600L, 600L);
    }

    /**
     * 玩家进服时开始录制。
     */
    public void startRecording(Player player) {
        DemoSession session = new DemoSession(player);
        sessions.put(player.getUniqueId(), session);
    }

    /**
     * 玩家退服时保存并停止录制。
     */
    public void stopAndSave(UUID uuid) {
        DemoSession session = sessions.remove(uuid);
        if (session == null) return;

        session.stop();
        saveDemo(session);
    }

    /**
     * 记录战斗事件。
     */
    public void recordCombat(UUID uuid, String targetName, int targetEntityId,
                              double distance, int attackCount, double cps,
                              boolean isCrit, float yawDelta, float pitchDelta) {
        DemoSession session = sessions.get(uuid);
        if (session != null) {
            session.recordCombat(targetName, targetEntityId, distance,
                attackCount, cps, isCrit, yawDelta, pitchDelta);
        }
    }

    /**
     * 获取玩家的录制会话。
     */
    public DemoSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    /**
     * 手动保存指定玩家的 Demo（如举报时触发）。
     */
    public String saveDemoForPlayer(UUID uuid) {
        DemoSession session = sessions.get(uuid);
        if (session == null) return null;
        return saveDemo(session);
    }

    /**
     * 将录制会话保存到 YAML 文件。
     */
    private String saveDemo(DemoSession session) {
        Player player = session.getPlayer();
        String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date(session.getStartTime()));
        String fileName = timestamp + "_" + player.getName() + ".yml";
        File file = new File(demoDir, fileName);

        YamlConfiguration yaml = new YamlConfiguration();

        // ========== 基本信息 ==========
        yaml.set("info.player", player.getName());
        yaml.set("info.uuid", player.getUniqueId().toString());
        yaml.set("info.start-time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(session.getStartTime())));
        yaml.set("info.duration-ms", System.currentTimeMillis() - session.getStartTime());

        // ========== 移动数据 ==========
        List<DemoSession.MovementSample> movements = session.getMovementSamples();
        for (int i = 0; i < movements.size(); i++) {
            DemoSession.MovementSample m = movements.get(i);
            String path = "movement." + i;
            yaml.set(path + ".t", m.timestamp());
            yaml.set(path + ".x", String.format("%.3f", m.x()));
            yaml.set(path + ".y", String.format("%.3f", m.y()));
            yaml.set(path + ".z", String.format("%.3f", m.z()));
            yaml.set(path + ".dx", String.format("%.4f", m.deltaX()));
            yaml.set(path + ".dy", String.format("%.4f", m.deltaY()));
            yaml.set(path + ".dz", String.format("%.4f", m.deltaZ()));
            yaml.set(path + ".h-dist", String.format("%.4f", m.horizontalDist()));
            yaml.set(path + ".total-dist", String.format("%.4f", m.totalDist()));
            yaml.set(path + ".yaw", String.format("%.2f", m.yaw()));
            yaml.set(path + ".pitch", String.format("%.2f", m.pitch()));
            yaml.set(path + ".on-ground", m.onGround());
            yaml.set(path + ".world", m.world());
        }
        yaml.set("movement.count", movements.size());

        // ========== 战斗数据 ==========
        List<DemoSession.CombatEntry> combats = session.getCombatEntries();
        for (int i = 0; i < combats.size(); i++) {
            DemoSession.CombatEntry c = combats.get(i);
            String path = "combat." + i;
            yaml.set(path + ".t", c.timestamp());
            yaml.set(path + ".target", c.targetName());
            yaml.set(path + ".target-id", c.targetEntityId());
            yaml.set(path + ".distance", String.format("%.3f", c.distance()));
            yaml.set(path + ".attack-count", c.attackCount());
            yaml.set(path + ".cps", String.format("%.2f", c.cps()));
            yaml.set(path + ".crit", c.isCrit());
            yaml.set(path + ".yaw-delta", String.format("%.2f", c.yawDelta()));
            yaml.set(path + ".pitch-delta", String.format("%.2f", c.pitchDelta()));
        }
        yaml.set("combat.count", combats.size());

        // ========== 玩家状态数据 ==========
        List<DemoSession.PlayerStateSample> states = session.getPlayerStateSamples();
        for (int i = 0; i < states.size(); i++) {
            DemoSession.PlayerStateSample s = states.get(i);
            String path = "player-state." + i;
            yaml.set(path + ".t", s.timestamp());

            // Buff/Debuff
            List<DemoSession.PotionInfo> potions = s.potions();
            for (int j = 0; j < potions.size(); j++) {
                yaml.set(path + ".potions." + j + ".name", potions.get(j).name());
                yaml.set(path + ".potions." + j + ".amplifier", potions.get(j).amplifier());
                yaml.set(path + ".potions." + j + ".duration", potions.get(j).duration());
            }
            yaml.set(path + ".potions-count", potions.size());

            yaml.set(path + ".is-op", s.isOp());
            yaml.set(path + ".has-bypass", s.hasBypass());
            yaml.set(path + ".has-admin", s.hasAdmin());
            yaml.set(path + ".health", String.format("%.2f", s.health()));
            yaml.set(path + ".max-health", String.format("%.2f", s.maxHealth()));
            yaml.set(path + ".food", s.foodLevel());
            yaml.set(path + ".fall-distance", String.format("%.3f", s.fallDistance()));
            yaml.set(path + ".fire-ticks", s.fireTicks());
            yaml.set(path + ".sprinting", s.isSprinting());
            yaml.set(path + ".sneaking", s.isSneaking());
            yaml.set(path + ".gliding", s.isGliding());
            yaml.set(path + ".blocking", s.isBlocking());
            yaml.set(path + ".in-water", s.isInWater());
            yaml.set(path + ".flying", s.isFlying());
            yaml.set(path + ".allow-flight", s.allowFlight());
            yaml.set(path + ".helmet", s.helmet());
            yaml.set(path + ".chestplate", s.chestplate());
            yaml.set(path + ".leggings", s.leggings());
            yaml.set(path + ".boots", s.boots());
            yaml.set(path + ".main-hand", s.mainHand());
            yaml.set(path + ".ping", s.ping());
        }
        yaml.set("player-state.count", states.size());

        // ========== 地形数据 ==========
        List<DemoSession.TerrainSnapshot> terrains = session.getTerrainSnapshots();
        for (int i = 0; i < terrains.size(); i++) {
            DemoSession.TerrainSnapshot t = terrains.get(i);
            String path = "terrain." + i;
            yaml.set(path + ".t", t.timestamp());
            yaml.set(path + ".world", t.world());
            yaml.set(path + ".block-x", t.blockX());
            yaml.set(path + ".block-y", t.blockY());
            yaml.set(path + ".block-z", t.blockZ());
            yaml.set(path + ".ground-block", t.groundBlock());
            yaml.set(path + ".head-block", t.headBlock());

            List<String> blocks = t.surroundingBlocks();
            for (int j = 0; j < blocks.size(); j++) {
                yaml.set(path + ".blocks." + j, blocks.get(j));
            }
            yaml.set(path + ".blocks-count", blocks.size());
        }
        yaml.set("terrain.count", terrains.size());

        // 保存文件
        try {
            yaml.save(file);
            plugin.getLogger().info("[Demo] 已保存 " + player.getName() + " 的录制: " + fileName
                + " (移动=" + movements.size() + " 战斗=" + combats.size()
                + " 状态=" + states.size() + " 地形=" + terrains.size() + ")");
            return fileName;
        } catch (IOException e) {
            plugin.getLogger().warning("[Demo] 保存失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 关闭所有录制会话并保存。
     */
    public void shutdown() {
        for (UUID uuid : sessions.keySet()) {
            stopAndSave(uuid);
        }
        sessions.clear();
    }

    public File getDemoDir() {
        return demoDir;
    }
}
