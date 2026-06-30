package dev.ztros.ansac.physics;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.physics.mlp.DualInferenceResult;
import dev.ztros.ansac.physics.mlp.ModelSelector;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 推理分数板管理器。
 * <p>
 * 将双模型 AB 架构的推理结果以 Scoreboard 侧边栏形式实时展示给管理员。
 * 每 2 秒（40 tick）更新一次，管理员可同时监控不同目标玩家。
 * </p>
 * <p>
 * Folia 兼容性：Scoreboard 操作在全局区域线程执行（通过 runTimer），
 * 不涉及实体区域调度，因此与 Folia 的多线程架构完全兼容。
 * </p>
 *
 * @author ANSAC Physics Engine
 */
public class InferenceScoreboardManager {

    private final ANSACPlugin plugin;

    /** 管理员 UUID -> 分数板数据 */
    private final ConcurrentHashMap<UUID, BoardData> activeBoards = new ConcurrentHashMap<>();

    /** 更新间隔（tick）—— 40 tick = 2 秒 */
    private static final int UPDATE_INTERVAL_TICKS = 40;

    /** 侧边栏最大行数 */
    private static final int MAX_LINES = 15;

    /** 不可见条目（颜色代码作为唯一标识，渲染为空） */
    private static final String[] INVISIBLE_ENTRIES;

    static {
        INVISIBLE_ENTRIES = new String[MAX_LINES];
        ChatColor[] colors = {
            ChatColor.BLACK, ChatColor.DARK_BLUE, ChatColor.DARK_GREEN,
            ChatColor.DARK_AQUA, ChatColor.DARK_RED, ChatColor.DARK_PURPLE,
            ChatColor.GOLD, ChatColor.GRAY, ChatColor.DARK_GRAY,
            ChatColor.BLUE, ChatColor.GREEN, ChatColor.AQUA,
            ChatColor.RED, ChatColor.LIGHT_PURPLE, ChatColor.YELLOW
        };
        for (int i = 0; i < MAX_LINES; i++) {
            INVISIBLE_ENTRIES[i] = colors[i].toString();
        }
    }

    /** 每个管理员的分数板数据 */
    private static class BoardData {
        final UUID targetUuid;
        final Scoreboard savedScoreboard;
        Scoreboard board;
        Objective objective;
        Team[] teams;

        BoardData(UUID targetUuid, Scoreboard savedScoreboard) {
            this.targetUuid = targetUuid;
            this.savedScoreboard = savedScoreboard;
        }
    }

    public InferenceScoreboardManager(ANSACPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 启动定时更新器（全局区域线程，Folia 兼容）。
     */
    public void start() {
        plugin.getSchedulerAdapter().runTimer(this::updateAll, 10L, UPDATE_INTERVAL_TICKS);
    }

    /**
     * 为管理员开启目标玩家的推理分数板。
     *
     * @param admin  执行命令的管理员
     * @param target 被监控的目标玩家
     * @return true 如果成功开启，false 如果已经在监控
     */
    public boolean startWatching(Player admin, Player target) {
        UUID adminUuid = admin.getUniqueId();
        if (activeBoards.containsKey(adminUuid)) {
            // 切换目标
            stopWatching(adminUuid);
        }

        // 保存管理员原始分数板
        Scoreboard saved = admin.getScoreboard();
        Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();

        BoardData data = new BoardData(target.getUniqueId(), saved);
        data.board = board;

        // 创建 Objective
        Objective obj = board.registerNewObjective("ansac_inf", "dummy",
            ChatColor.DARK_AQUA + "" + ChatColor.BOLD + "ANSAC AI 推理");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);
        data.objective = obj;

        // 为每行创建 Team + 不可见条目
        data.teams = new Team[MAX_LINES];
        for (int i = 0; i < MAX_LINES; i++) {
            int score = MAX_LINES - i; // 15, 14, ..., 1
            String teamName = "l" + score;
            Team team = board.registerNewTeam(teamName);
            String entry = INVISIBLE_ENTRIES[i];
            team.addEntry(entry);
            obj.getScore(entry).setScore(score);
            data.teams[i] = team;
        }

        activeBoards.put(adminUuid, data);
        admin.setScoreboard(board);

        // 立即更新一次
        updateScoreboard(adminUuid, data, target.getName());
        return true;
    }

    /**
     * 停止管理员的推理分数板。
     */
    public void stopWatching(UUID adminUuid) {
        BoardData data = activeBoards.remove(adminUuid);
        if (data == null) return;

        Player admin = plugin.getServer().getPlayer(adminUuid);
        if (admin != null && admin.isOnline()) {
            // 恢复原始分数板
            admin.setScoreboard(data.savedScoreboard);
        }
    }

    /** 管理员是否正在查看推理分数板 */
    public boolean isWatching(UUID adminUuid) {
        return activeBoards.containsKey(adminUuid);
    }

    /** 获取管理员当前监控的目标 UUID */
    public UUID getWatchTarget(UUID adminUuid) {
        BoardData data = activeBoards.get(adminUuid);
        return data != null ? data.targetUuid : null;
    }

    /** 获取所有正在查看分数板的管理员 UUID */
    public Set<UUID> getWatchingAdmins() {
        return activeBoards.keySet();
    }

    /** 关闭所有分数板 */
    public void shutdown() {
        for (UUID uuid : Set.copyOf(activeBoards.keySet())) {
            stopWatching(uuid);
        }
    }

    // ==================== 内部更新逻辑 ====================

    private void updateAll() {
        for (UUID adminUuid : Set.copyOf(activeBoards.keySet())) {
            Player admin = plugin.getServer().getPlayer(adminUuid);
            if (admin == null || !admin.isOnline()) {
                stopWatching(adminUuid);
                continue;
            }

            BoardData data = activeBoards.get(adminUuid);
            if (data == null) continue;

            Player target = plugin.getServer().getPlayer(data.targetUuid);
            if (target == null || !target.isOnline()) {
                // 目标下线，通知管理员
                admin.sendMessage(ChatColor.RED + "目标玩家已下线，推理分数板已关闭。");
                stopWatching(adminUuid);
                continue;
            }

            updateScoreboard(adminUuid, data, target.getName());
        }
    }

    private void updateScoreboard(UUID adminUuid, BoardData data, String targetName) {
        // 获取双模型推理结果
        DualInferenceResult result = plugin.getPhysicsInferenceService()
            .getDualInferenceResult(data.targetUuid);

        Team[] teams = data.teams;
        int line = 0;

        // 行: 目标玩家
        setLine(teams, line++, ChatColor.GRAY + "目标: " + ChatColor.WHITE + truncate(targetName, 16));

        // 空行
        setLine(teams, line++, "");

        // A模型标题
        setLine(teams, line++, ChatColor.YELLOW + "" + ChatColor.BOLD + "A模型(正常)");

        if (result == DualInferenceResult.EMPTY) {
            setLine(teams, line++, ChatColor.GRAY + " 等待数据...");
            // 跳过剩余行，设为空
            while (line < MAX_LINES) setLine(teams, line++, "");
            return;
        }

        // A模型分数
        setLine(teams, line++, formatScoreLine(" 移动", result.normalMovementScore(), true));
        setLine(teams, line++, formatScoreLine(" 战斗", result.normalCombatScore(), true));
        setLine(teams, line++, formatScoreLine(" 异常", result.normalAnomalyScore(), false));

        // 空行
        setLine(teams, line++, "");

        if (plugin.getPhysicsInferenceService().isDualModelEnabled()) {
            // B模型标题
            setLine(teams, line++, ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "B模型(威胁)");

            // B模型分数
            setLine(teams, line++, formatScoreLine(" 移动", result.threatMovementScore(), false));
            setLine(teams, line++, formatScoreLine(" 战斗", result.threatCombatScore(), false));
            setLine(teams, line++, formatScoreLine(" 威胁", result.threatFusionScore(), false));

            // 空行
            setLine(teams, line++, "");

            // 综合评估
            if (result.selectorResult() != null) {
                ModelSelector.ModelSelectorResult sr = result.selectorResult();
                String sourceLabel = getSourceLabel(sr.source());
                ChatColor sourceColor = getSourceColor(sr.source());
                setLine(teams, line++, ChatColor.GOLD + "来源: " + sourceColor + sourceLabel);
                setLine(teams, line++, ChatColor.GOLD + "置信: " +
                    (sr.confidence() >= 0.75 ? ChatColor.RED : ChatColor.YELLOW) +
                    String.format("%.1f%%", sr.confidence() * 100) +
                    ChatColor.GRAY + "(" + ModelSelector.getConfidenceLabel(sr.confidence()) + ")");
            } else {
                setLine(teams, line++, ChatColor.GOLD + "来源: " + ChatColor.GRAY + "N/A");
                setLine(teams, line++, ChatColor.GOLD + "置信: " + ChatColor.GRAY + "N/A");
            }
        } else {
            setLine(teams, line++, ChatColor.GRAY + "B模型未启用");
            setLine(teams, line++, "");
            setLine(teams, line++, "");
            setLine(teams, line++, "");
            setLine(teams, line++, "");
        }

        // 状态标记
        StringBuilder status = new StringBuilder();
        if (result.isHighRisk()) status.append(ChatColor.DARK_RED).append("[高危] ");
        if (result.isRealtimeInference()) status.append(ChatColor.AQUA).append("[实时]");
        if (status.length() == 0) status.append(ChatColor.DARK_GRAY).append("● 正常监控中");
        setLine(teams, line++, status.toString());

        // 剩余行清空
        while (line < MAX_LINES) setLine(teams, line++, "");
    }

    private void setLine(Team[] teams, int index, String text) {
        if (index >= MAX_LINES || index < 0) return;
        Team team = teams[index];
        if (team == null) return;

        // 拆分 prefix/suffix（每个最多 16 字符，避免截断）
        if (text.isEmpty()) {
            team.setPrefix("");
            team.setSuffix("");
        } else if (text.length() <= 16) {
            team.setPrefix(text);
            team.setSuffix("");
        } else {
            // 在 16 字符处拆分，但不要切断颜色代码
            int split = 16;
            if (split > 0 && split < text.length() &&
                    text.charAt(split - 1) == ChatColor.COLOR_CHAR) {
                split--;
            }
            String prefix = text.substring(0, split);
            String suffix = text.substring(split);
            // 继承前缀最后的颜色到后缀
            ChatColor lastColor = getLastColor(prefix);
            if (lastColor != null && lastColor != ChatColor.RESET) {
                suffix = lastColor + suffix;
            }
            team.setPrefix(prefix);
            team.setSuffix(suffix.length() > 16 ? suffix.substring(0, 16) : suffix);
        }
    }

    private String formatScoreLine(String label, double score, boolean higherIsBetter) {
        ChatColor color = ((score >= 0.5) == higherIsBetter) ? ChatColor.GREEN : ChatColor.RED;
        return color + label + ": " + String.format("%.1f%%", score * 100);
    }

    private String getSourceLabel(ModelSelector.VerdictSource source) {
        return switch (source) {
            case DUAL_CONFIRM -> "双重确认";
            case MODEL_B_ONLY -> "B模型命中";
            case MODEL_A_ONLY -> "A模型异常";
            case INSUFFICIENT -> "证据不足";
        };
    }

    private ChatColor getSourceColor(ModelSelector.VerdictSource source) {
        return switch (source) {
            case DUAL_CONFIRM -> ChatColor.DARK_RED;
            case MODEL_B_ONLY -> ChatColor.RED;
            case MODEL_A_ONLY -> ChatColor.YELLOW;
            case INSUFFICIENT -> ChatColor.GREEN;
        };
    }

    private ChatColor getLastColor(String text) {
        for (int i = text.length() - 2; i >= 0; i--) {
            if (text.charAt(i) == ChatColor.COLOR_CHAR && i + 1 < text.length()) {
                return ChatColor.getByChar(text.charAt(i + 1));
            }
        }
        return null;
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }
}
