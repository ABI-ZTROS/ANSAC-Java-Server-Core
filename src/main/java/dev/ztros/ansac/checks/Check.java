package dev.ztros.ansac.checks;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.physics.DetectionMode;
import dev.ztros.ansac.physics.PhysicsInferenceService;
import dev.ztros.ansac.physics.PlayerPhysicsState;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.punishment.PunishmentType;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Base class for all anti-cheat checks.
 * Each check monitors a specific type of cheat behavior.
 * Uses Adventure Component API for messaging (required for Paper/Folia 1.21+).
 */
public abstract class Check {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /**
     * Called when a player quits the server.
     * Subclasses should override this to clean up per-player state.
     */
    public void onPlayerQuit(UUID uuid) {
        // Default: no-op. Subclasses with per-player state should override.
    }

    @Getter
    protected final ANSACPlugin plugin;

    @Getter
    protected final String name;

    @Getter
    protected final String type;

    @Getter
    protected boolean enabled;

    @Getter
    protected boolean punishable;

    @Getter
    protected int maxVL;

    @Getter
    protected double setbackThreshold;

    @Getter
    protected double alertThreshold;

    @Getter
    protected PunishmentType punishmentType;

    public Check(ANSACPlugin plugin, String name, String type) {
        this.plugin = plugin;
        this.name = name;
        this.type = type;
        loadConfig();
    }

    /**
     * Load configuration for this check
     */
    public void loadConfig() {
        String path = "checks." + type.toLowerCase() + "." + name.toLowerCase();
        this.enabled = plugin.getConfig().getBoolean(path + ".enabled", true);
        this.punishable = plugin.getConfig().getBoolean(path + ".punishable", true);
        this.maxVL = plugin.getConfig().getInt(path + ".max-vl", 20);
        this.setbackThreshold = plugin.getConfig().getDouble(path + ".setback-threshold", 5.0);
        this.alertThreshold = plugin.getConfig().getDouble(path + ".alert-threshold", 1.0);

        String ptStr = plugin.getConfig().getString(path + ".punishment-type", "kick").toUpperCase();
        try {
            this.punishmentType = PunishmentType.valueOf(ptStr);
        } catch (IllegalArgumentException e) {
            this.punishmentType = PunishmentType.KICK;
        }
    }

    /**
     * Process a player - called periodically or on events
     */
    public abstract void process(Player player, PlayerData data);

    /**
     * Handle a violation detected by this check
     */
    protected void flag(Player player, PlayerData data, double severity, String details) {
        if (!enabled || data.hasBypass()) return;
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();

        // 受信任玩家豁免：照常收集行为画像数据，但不累加VL、不报警、不处罚
        if (svc != null && svc.isTrusted(player.getUniqueId())) return;

        // 纯模型模式：规则层只记录微量参考，不处罚、不回拉、不报警
        if (svc != null && svc.getDetectionMode() == DetectionMode.MODEL_ONLY) {
            data.addViolation(name, 0.01);
            return;
        }

        double effectiveSeverity = severity;
        // 混合双打模式：模型异常分数放大规则 severity
        if (svc != null && svc.getDetectionMode() == DetectionMode.HYBRID) {
            PlayerPhysicsState state = svc.getState(player.getUniqueId());
            double anomalyScore = (state != null) ? state.getLastAnomalyScore() : 0.0;
            // anomalyScore 0~1，0.5 时 severity 翻倍，1.0 时三倍
            effectiveSeverity = severity * (1.0 + anomalyScore * 2.0);
        }

        data.addViolation(name, effectiveSeverity);
        int vl = data.getViolation(name) != null ? data.getViolation(name).getTotalVL() : 0;

        // Alert if above threshold
        if (vl >= alertThreshold) {
            alert(player, vl, details);
        }

        // Setback if above threshold
        if (vl >= setbackThreshold) {
            setback(player, data);
        }

        // Punish if max VL reached
        if (punishable && vl >= maxVL) {
            punish(player, data, vl);
        }
    }

    /**
     * Flag a violation but ONLY send an alert, do NOT add to violation level.
     * Used for low-confidence detections that warrant monitoring but not punishment.
     */
    protected void flagAlertOnly(Player player, PlayerData data, String details) {
        if (!enabled || data == null || data.hasBypass()) return;
        // 受信任玩家豁免观察报警
        PhysicsInferenceService inferenceService = plugin.getPhysicsInferenceService();
        if (inferenceService != null && inferenceService.isTrusted(player.getUniqueId())) return;

        Component message = MINI_MESSAGE.deserialize(
            "<gray>[<dark_green>ANSAC-观察</gray>] <yellow>" + player.getName() +
            " <gray>[观察] <green>" + name +
            " <dark_gray>| <gray>" + details
        );

        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            if (staff.hasPermission("ansac.alerts")) {
                plugin.getSchedulerAdapter().runAtEntity(staff, () -> {
                    staff.sendMessage(message);
                });
            }
        }

        plugin.getLogger().info("[观察] " + player.getName() + " - " + name + " - " + details);
    }

    /**
     * Send alert to staff using Adventure Component API.
     * Uses runAtEntity for each staff player to ensure Folia thread safety.
     */
    protected void alert(Player player, int vl, String details) {
        Component message = MINI_MESSAGE.deserialize(
            "<gray>[<red>ANSAC</gray>] <yellow>" + player.getName() +
            " <gray>触发了 <red>" + name +
            " <gray>(VL: <white>" + vl +
            "<gray>) <dark_gray>| <gray>" + details
        );

        // Use runAtEntity for each staff player to ensure Folia thread safety
        for (Player staff : plugin.getServer().getOnlinePlayers()) {
            if (staff.hasPermission("ansac.alerts")) {
                plugin.getSchedulerAdapter().runAtEntity(staff, () -> {
                    staff.sendMessage(message);
                });
            }
        }

        plugin.getLogger().info("[预警] " + player.getName() + " 触发了 " + name + " (VL: " + vl + ") - " + details);
    }

    /**
     * Setback player (teleport back to safe position).
     * Uses teleportAsync() for Folia 1.21.4+ compatibility (Entity#teleport is deprecated).
     */
    protected void setback(Player player, PlayerData data) {
        if (data.getLastLocation() != null) {
            plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                player.teleportAsync(data.getLastLocation());
            });
        }
    }

    /**
     * Punish player via PunishmentManager.
     * Supports both kick and ban based on check's punishment-type config.
     */
    protected void punish(Player player, PlayerData data, int vl) {
        if (!plugin.getAnsacConfig().isPunishmentsEnabled()) return;
        plugin.getPunishmentManager().punish(player, "使用作弊程序被检测", name, vl);
    }

    /**
     * Check if player is on ground (safe method)
     */
    protected boolean isOnGround(Player player) {
        return player.isOnGround();
    }

    /**
     * Get player's ping
     */
    protected int getPing(Player player) {
        return player.getPing();
    }
}
