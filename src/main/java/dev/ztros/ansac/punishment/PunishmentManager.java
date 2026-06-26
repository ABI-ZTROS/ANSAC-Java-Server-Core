package dev.ztros.ansac.punishment;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.checks.Check;
import dev.ztros.ansac.util.ServerVersionAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages bans, kicks, and punishment records.
 * Uses YAML persistence (inspired by AdvancedBan design).
 * Renders rich-text kick/ban screens via Adventure MiniMessage.
 *
 * Supports customizable "background" using Unicode block characters and gradients.
 */
public class PunishmentManager {

    private final ANSACPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Active bans by UUID
    private final ConcurrentHashMap<UUID, PunishmentEntry> activeBans = new ConcurrentHashMap<>();
    // Name -> UUID lookup for command convenience
    private final ConcurrentHashMap<String, UUID> nameToUuid = new ConcurrentHashMap<>();
    // Recently punished players (prevent duplicate punishment within short window)
    private final Set<UUID> recentlyPunished = ConcurrentHashMap.newKeySet();

    // Message templates from config
    private List<String> banMessageTemplate;
    private List<String> kickMessageTemplate;

    // Config values (stored in seconds internally; config file uses minutes for user convenience)
    private long autoBanDurationSeconds;
    private boolean autoBanEnabled;

    private final File punishmentsFile;
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public PunishmentManager(ANSACPlugin plugin) {
        this.plugin = plugin;
        this.punishmentsFile = new File(plugin.getDataFolder(), "punishments.yml");
        loadConfig();
        load();
    }

    /**
     * Load punishment-related configuration values.
     */
    public void loadConfig() {
        var config = plugin.getConfig();
        String path = "punishments";
        this.autoBanEnabled = config.getBoolean(path + ".auto-ban-enabled", false);
        this.autoBanDurationSeconds = config.getLong(path + ".auto-ban-duration", 1440) * 60;
        this.banMessageTemplate = config.getStringList(path + ".ban-message");
        this.kickMessageTemplate = config.getStringList(path + ".kick-message");

        // Use default templates if not configured
        if (banMessageTemplate.isEmpty()) {
            banMessageTemplate = getDefaultBanMessage();
        }
        if (kickMessageTemplate.isEmpty()) {
            kickMessageTemplate = getDefaultKickMessage();
        }
    }

    /**
     * Load punishment records from YAML file.
     */
    public void load() {
        if (!punishmentsFile.exists()) return;

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(punishmentsFile);
        var entries = yaml.getStringList("bans");
        int loaded = 0;
        for (String entryStr : entries) {
            String[] parts = entryStr.split(";");
            PunishmentEntry entry = PunishmentEntry.fromStringArray(parts);
            if (entry != null && entry.isActive() && !entry.hasExpired()) {
                activeBans.put(entry.getUuid(), entry);
                nameToUuid.put(entry.getPlayerName().toLowerCase(), entry.getUuid());
                loaded++;
            }
        }
        plugin.getLogger().info("已从 punishments.yml 加载 " + loaded + " 条有效封禁记录。");
    }

    /**
     * Save active punishment records to YAML file.
     */
    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> entries = new ArrayList<>();
        for (PunishmentEntry entry : activeBans.values()) {
            if (entry.isActive()) {
                entries.add(String.join(";", entry.toStringArray()));
            }
        }
        yaml.set("bans", entries);
        try {
            yaml.save(punishmentsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("保存封禁记录失败: " + e.getMessage());
        }
    }

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Check if a player is currently banned.
     */
    public boolean isBanned(UUID uuid) {
        PunishmentEntry entry = activeBans.get(uuid);
        if (entry == null) return false;
        if (!entry.isActive() || entry.hasExpired()) {
            activeBans.remove(uuid);
            nameToUuid.remove(entry.getPlayerName().toLowerCase());
            return false;
        }
        return true;
    }

    /**
     * Get active ban entry for a player, or null if not banned.
     */
    public PunishmentEntry getActiveBan(UUID uuid) {
        PunishmentEntry entry = activeBans.get(uuid);
        if (entry == null) return null;
        if (!entry.isActive() || entry.hasExpired()) {
            activeBans.remove(uuid);
            nameToUuid.remove(entry.getPlayerName().toLowerCase());
            return null;
        }
        return entry;
    }

    /**
     * Get active ban by player name (case-insensitive).
     */
    public PunishmentEntry getActiveBan(String playerName) {
        UUID uuid = nameToUuid.get(playerName.toLowerCase());
        if (uuid == null) return null;
        return getActiveBan(uuid);
    }

    /**
     * Ban a player with given parameters.
     * If player is online, kicks them with the ban screen.
     * @param durationSeconds -1 for permanent, otherwise seconds
     */
    public void ban(Player player, String reason, long durationSeconds,
                    String operator, String checkName, int vl) {
        ban(player.getUniqueId(), player.getName(), reason, durationSeconds,
            operator, checkName, vl, getPlayerIp(player));
    }

    /**
     * Ban an offline player by name.
     * @param durationSeconds -1 for permanent, otherwise seconds
     */
    public void banOffline(String playerName, String reason, long durationSeconds, String operator) {
        UUID uuid = resolveUuid(playerName);
        if (uuid == null) {
            plugin.getLogger().warning("无法解析玩家 " + playerName + " 的 UUID，封禁失败。");
            return;
        }
        ban(uuid, playerName, reason, durationSeconds, operator, null, 0, null);
    }

    private void ban(UUID uuid, String playerName, String reason, long durationSeconds,
                     String operator, String checkName, int vl, String ip) {
        // Remove any existing ban first
        activeBans.remove(uuid);

        PunishmentEntry entry = new PunishmentEntry(
            uuid, playerName, reason, checkName, vl, operator,
            System.currentTimeMillis(), durationSeconds, ip
        );
        activeBans.put(uuid, entry);
        nameToUuid.put(playerName.toLowerCase(), uuid);
        save();

        // Kick if online
        Player onlinePlayer = Bukkit.getPlayer(uuid);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            Component message = renderMessage(banMessageTemplate, entry);
            plugin.getSchedulerAdapter().runAtEntity(onlinePlayer, () -> {
                ServerVersionAdapter.kickPlayer(onlinePlayer, message);
            });
        }

        plugin.getLogger().warning("[封禁] " + playerName + " 被 " + operator + " 封禁"
            + (durationSeconds >= 0 ? " (" + formatDuration(durationSeconds) + ")" : " (永久)")
            + " | 原因: " + reason);
    }

    /**
     * Kick a player with rich-text kick screen.
     */
    public void kick(Player player, String reason, String operator, String checkName, int vl) {
        UUID uuid = player.getUniqueId();

        // Prevent duplicate punishment within 5 seconds
        if (recentlyPunished.contains(uuid)) return;
        recentlyPunished.add(uuid);
        plugin.getSchedulerAdapter().runLater(() -> recentlyPunished.remove(uuid), 100L); // 5s

        PunishmentEntry entry = new PunishmentEntry(
            uuid, player.getName(), reason, checkName, vl, operator,
            System.currentTimeMillis(), 0, getPlayerIp(player)
        );

        Component message = renderMessage(kickMessageTemplate, entry);
        plugin.getSchedulerAdapter().runAtEntity(player, () -> {
            ServerVersionAdapter.kickPlayer(player, message);
        });

        plugin.getLogger().warning("[踢出] " + player.getName() + " 被 " + operator + " 踢出 | 原因: " + reason
            + (checkName != null ? " | 检测项: " + checkName : ""));
    }

    /**
     * Automatic punishment triggered by a check reaching max VL.
     * Decides between kick and ban based on check configuration.
     */
    public void punish(Player player, String reason, String checkName, int vl) {
        if (!plugin.getAnsacConfig().isPunishmentsEnabled()) return;
        if (recentlyPunished.contains(player.getUniqueId())) return;

        Check check = plugin.getCheckManager().getCheck(checkName);
        PunishmentType type = (check != null) ? check.getPunishmentType() : PunishmentType.KICK;

        if (type == PunishmentType.BAN && autoBanEnabled) {
            ban(player, reason, autoBanDurationSeconds, "ANSAC-自动", checkName, vl);
        } else {
            kick(player, reason, "ANSAC-自动", checkName, vl);
        }
    }

    /**
     * Unban a player by UUID or name.
     * Returns true if a ban was removed.
     */
    public boolean unban(String identifier) {
        UUID uuid = null;
        try {
            uuid = UUID.fromString(identifier);
        } catch (IllegalArgumentException e) {
            // Not a UUID, treat as player name
            uuid = nameToUuid.get(identifier.toLowerCase());
        }

        if (uuid == null) {
            // Try offline lookup
            uuid = resolveUuid(identifier);
        }

        if (uuid == null) return false;

        PunishmentEntry entry = activeBans.get(uuid);
        if (entry != null) {
            entry.setActive(false);
            activeBans.remove(uuid);
            nameToUuid.remove(entry.getPlayerName().toLowerCase());
            save();
            plugin.getLogger().info("[解封] " + entry.getPlayerName() + " 已被解封。");
            return true;
        }
        return false;
    }

    /**
     * Get all active bans as a list.
     */
    public List<PunishmentEntry> getActiveBans() {
        List<PunishmentEntry> result = new ArrayList<>();
        Iterator<Map.Entry<UUID, PunishmentEntry>> it = activeBans.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PunishmentEntry> e = it.next();
            PunishmentEntry entry = e.getValue();
            if (!entry.isActive() || entry.hasExpired()) {
                it.remove();
                nameToUuid.remove(entry.getPlayerName().toLowerCase());
            } else {
                result.add(entry);
            }
        }
        return result;
    }

    // ============================================================
    // Message Rendering (MiniMessage rich text)
    // ============================================================

    /**
     * Render a ban screen Component for a given entry.
     */
    public Component getBanScreen(PunishmentEntry entry) {
        return renderMessage(banMessageTemplate, entry);
    }

    /**
     * Render a kick screen Component for a given entry.
     */
    public Component getKickScreen(PunishmentEntry entry) {
        return renderMessage(kickMessageTemplate, entry);
    }

    /**
     * Render a message template with variable substitution.
     * Joins template lines with newlines and deserializes via MiniMessage.
     */
    private Component renderMessage(List<String> template, PunishmentEntry entry) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < template.size(); i++) {
            if (i > 0) sb.append("\n");
            sb.append(template.get(i));
        }
        String text = replaceVariables(sb.toString(), entry);
        return miniMessage.deserialize(text);
    }

    /**
     * Replace template variables with actual values.
     * Available variables: %player%, %uuid%, %reason%, %check%, %vl%,
     * %operator%, %date%, %duration%, %expiry%, %time_remaining%
     */
    private String replaceVariables(String text, PunishmentEntry entry) {
        String dateStr = DATE_FORMAT.format(new Date(entry.getBanTime()));
        String expiryStr = entry.isPermanent() ? "永久" : DATE_FORMAT.format(new Date(entry.getExpiryTime()));
        String durationStr = entry.isPermanent() ? "永久" : formatDuration(entry.getDurationSeconds());
        String remainingStr = entry.isPermanent() ? "永久" : formatDuration(
            Math.max(0, (entry.getExpiryTime() - System.currentTimeMillis()) / 1000L)
        );

        return text
            .replace("%player%", entry.getPlayerName())
            .replace("%uuid%", entry.getUuid().toString())
            .replace("%reason%", entry.getReason())
            .replace("%check%", entry.getCheckName() != null ? entry.getCheckName() : "N/A")
            .replace("%vl%", String.valueOf(entry.getVl()))
            .replace("%operator%", entry.getOperator())
            .replace("%date%", dateStr)
            .replace("%duration%", durationStr)
            .replace("%expiry%", expiryStr)
            .replace("%time_remaining%", remainingStr);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private String getPlayerIp(Player player) {
        if (player.getAddress() == null) return null;
        return player.getAddress().getAddress().getHostAddress();
    }

    private UUID resolveUuid(String playerName) {
        // Try online player first
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) return online.getUniqueId();

        // Try from active bans
        UUID uuid = nameToUuid.get(playerName.toLowerCase());
        if (uuid != null) return uuid;

        // Try Bukkit offline players (may be slow on large servers)
        var offline = Bukkit.getOfflinePlayerIfCached(playerName);
        if (offline != null) return offline.getUniqueId();

        return null;
    }

    private String formatDuration(long seconds) {
        if (seconds < 0) return "永久";
        if (seconds < 60) return seconds + "秒";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "分钟";
        long hours = minutes / 60;
        if (hours < 24) {
            long m = minutes % 60;
            return hours + "小时" + (m > 0 ? m + "分钟" : "");
        }
        long days = hours / 24;
        long h = hours % 24;
        return days + "天" + (h > 0 ? h + "小时" : "");
    }

    // ============================================================
    // Default Templates
    // ============================================================

    private List<String> getDefaultBanMessage() {
        return Arrays.asList(
            "<#8B0000><bold>哟，开挂被逮到了吧！</bold>",
            "",
            "<black>玩家 <white>%player%</white> 已被永久 <black><bold>BAN</bold><black> 咯~",
            "",
            "<black>原因: %reason%",
            "<black>检测: %check% (VL:%vl%)",
            "",
            "<black>不会吧不会吧，真有人觉得不会被发现？",
            "<black>建议: 卸载作弊器，重新做人。",
            "",
            "<black>不服？憋着。找管理哭唧唧",
            "",
            "<black>到期: %expiry%",
            "",
            "<black><hover:show_text:'点也没用，乖~'><click:open_url:'https://example.com'>[ 申诉入口（假的）]</click></hover>"
        );
    }

    private List<String> getDefaultKickMessage() {
        return Arrays.asList(
            "<#8B0000><bold>嗖~ 的一下你就飞出去了！</bold>",
            "",
            "<black>玩家 <white>%player%</white> 已被一脚踢飞",
            "",
            "<black>原因: %reason%",
            "<black>检测: %check% (VL:%vl%)",
            "",
            "<black>系统检测到你的操作太下饭了",
            "<black>建议: 手残就练，别老想走捷径",
            "",
            "<black><hover:show_text:'再试试？我赌你还会触发'><click:open_url:'https://example.com'>[ 不服？点我 ]</click></hover>"
        );
    }
}
