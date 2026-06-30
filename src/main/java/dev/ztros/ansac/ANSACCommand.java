package dev.ztros.ansac;

import dev.ztros.ansac.physics.DetectionMode;
import dev.ztros.ansac.physics.PhysicsInferenceService;
import dev.ztros.ansac.physics.InferenceResult;
import dev.ztros.ansac.physics.PlayerPhysicsState;
import dev.ztros.ansac.physics.mlp.BehaviorFeatureExtractor;
import dev.ztros.ansac.physics.mlp.InferenceInterpreter;

import dev.ztros.ansac.physics.mlp.MLPInferenceDetail;
import dev.ztros.ansac.physics.mlp.MLPSamplingSession;
import dev.ztros.ansac.physics.mlp.profile.PlayerBehaviorProfile;
import dev.ztros.ansac.player.PlayerData;
import dev.ztros.ansac.punishment.PunishmentEntry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * ANSAC command handler.
 * Uses Adventure Component API (required for Paper/Folia 1.21+).
 */
public class ANSACCommand implements CommandExecutor {

    private final ANSACPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public ANSACCommand(ANSACPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                if (!sender.hasPermission("ansac.command.reload")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                plugin.reload();
                sender.sendMessage(Component.text("ANSAC 配置重载成功。", NamedTextColor.GREEN));
                break;

            case "status":
                if (!sender.hasPermission("ansac.command.status")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                sendStatus(sender);
                break;

            case "info":
                if (!sender.hasPermission("ansac.admin")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("用法：/ansac info <玩家名>", NamedTextColor.RED));
                    return true;
                }
                sendPlayerInfo(sender, args[1]);
                break;

            case "ban":
                if (!sender.hasPermission("ansac.command.ban")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                handleBan(sender, args);
                break;

            case "kick":
                if (!sender.hasPermission("ansac.command.kick")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                handleKick(sender, args);
                break;

            case "unban":
                if (!sender.hasPermission("ansac.command.unban")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                handleUnban(sender, args);
                break;

            case "banlist":
                if (!sender.hasPermission("ansac.command.banlist")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                handleBanlist(sender);
                break;

            case "trust":
                if (!sender.hasPermission("ansac.command.trust")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("用法：/ansac trust <玩家名>", NamedTextColor.RED));
                    return true;
                }
                handleTrust(sender, args[1]);
                break;

            case "untrust":
                if (!sender.hasPermission("ansac.command.trust")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("用法：/ansac untrust <玩家名>", NamedTextColor.RED));
                    return true;
                }
                handleUntrust(sender, args[1]);
                break;

            case "trustlist":
                if (!sender.hasPermission("ansac.command.trust")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                handleTrustlist(sender);
                break;

            case "baseline":
                if (!sender.hasPermission("ansac.command.baseline")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length >= 2) {
                    handleBaselineSub(sender, args[1]);
                } else {
                    handleBaseline(sender);
                }
                break;

            case "inference":
                if (!sender.hasPermission("ansac.command.inference")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length >= 2) {
                    if (args[1].equalsIgnoreCase("stop")) {
                        handleInferenceScoreboardStop(sender);
                    } else if (args[1].equalsIgnoreCase("list")) {
                        handleInferenceScoreboardList(sender);
                    } else {
                        handleInferenceScoreboardStart(sender, args[1]);
                    }
                } else {
                    handleInferenceStatus(sender);
                }
                break;

            case "sampling":
                if (!sender.hasPermission("ansac.admin")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    handleSamplingStatus(sender);
                    return true;
                }
                handleSamplingSub(sender, args[1]);
                break;

            case "mode":
                if (!sender.hasPermission("ansac.admin")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    handleModeStatus(sender);
                    return true;
                }
                handleModeSet(sender, args[1]);
                break;

            case "watch":
                if (!sender.hasPermission("ansac.admin")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    handleWatchList(sender);
                    return true;
                }
                handleWatchSub(sender, args);
                break;

            case "mark":
                if (!sender.hasPermission("ansac.command.mark")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    handleMarkList(sender);
                    return true;
                }
                handleMarkSub(sender, args);
                break;

            case "unmark":
                if (!sender.hasPermission("ansac.command.mark")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("用法：/ansac unmark <玩家名>", NamedTextColor.RED));
                    return true;
                }
                handleUnmark(sender, args[1]);
                break;

            case "realtime":
                if (!sender.hasPermission("ansac.command.realtime")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    handleRealtimeList(sender);
                    return true;
                }
                handleRealtimeSub(sender, args);
                break;

            case "unrealtime":
                if (!sender.hasPermission("ansac.command.realtime")) {
                    sender.sendMessage(Component.text("你没有使用此命令的权限。", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("用法：/ansac unrealtime <玩家名>", NamedTextColor.RED));
                    return true;
                }
                handleUnrealtime(sender, args[1]);
                break;

            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("========================================", NamedTextColor.DARK_AQUA));
        sender.sendMessage(Component.text("  ANSAC 反作弊系统 v" + plugin.getDescription().getVersion(), NamedTextColor.GOLD));
        sender.sendMessage(Component.text("========================================", NamedTextColor.DARK_AQUA));

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("── 基础命令 ──", NamedTextColor.AQUA));
        sendHelpEntry(sender, "/ansac reload", "重载配置文件");
        sendHelpEntry(sender, "/ansac status", "查看插件运行状态");

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("── 玩家命令 ──", NamedTextColor.AQUA));
        sendHelpEntry(sender, "/ansac info <玩家>", "查看玩家数据(延迟/VL/触发检测数)");
        sendHelpEntry(sender, "/ansac ban <玩家> [时长] [原因]", "封禁玩家 (forever=永久)");
        sendHelpEntry(sender, "/ansac kick <玩家> [原因]", "踢出玩家");
        sendHelpEntry(sender, "/ansac unban <玩家/UUID>", "解封玩家");
        sendHelpEntry(sender, "/ansac banlist", "查看活跃封禁列表");
        sender.sendMessage(Component.text("  封禁时长: 10s 20s 1min 30min 1h 8h 24h 3d 7d 15d 30d 180d 360d forever", NamedTextColor.DARK_GRAY));

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("── 信任与学习 ──", NamedTextColor.AQUA));
        sendHelpEntry(sender, "/ansac trust <玩家>", "标记为受信任（数据用于MLP自学习，豁免处罚）");
        sendHelpEntry(sender, "/ansac untrust <玩家>", "取消信任标记");
        sendHelpEntry(sender, "/ansac trustlist", "查看受信任玩家列表");
        sendHelpEntry(sender, "/ansac baseline [reset|save]", "查看/重置/保存物理基准模型");

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("── AI 神经网络 ──", NamedTextColor.AQUA));
        sendHelpEntry(sender, "/ansac inference <玩家>", "开启推理分数板实时监控目标玩家");
        sendHelpEntry(sender, "/ansac inference stop", "关闭推理分数板");
        sendHelpEntry(sender, "/ansac inference list", "查看所有推理分数板");
        sendHelpEntry(sender, "/ansac sampling <start|stop>", "开启/关闭MLP持续自学习");
        sendHelpEntry(sender, "/ansac mode <rule|model|hybrid>", "切换检测模式");

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("── 双模型 AB 架构 ──", NamedTextColor.LIGHT_PURPLE));
        sendHelpEntry(sender, "/ansac mark <玩家>", "标记为高危玩家(已确认作弊, 数据用于B模型训练)");
        sendHelpEntry(sender, "/ansac unmark <玩家>", "取消高危标记");
        sendHelpEntry(sender, "/ansac marklist", "查看高危玩家列表");
        sendHelpEntry(sender, "/ansac realtime <玩家>", "启用实时同步推理(逐tick在线学习)");
        sendHelpEntry(sender, "/ansac unrealtime <玩家>", "禁用实时同步推理");
        sendHelpEntry(sender, "/ansac realtimelist", "查看实时推理玩家列表");

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("── 实时监控 ──", NamedTextColor.AQUA));
        sendHelpEntry(sender, "/ansac watch start <玩家>", "开启AI思维实时监控(ActionBar推送)");
        sendHelpEntry(sender, "/ansac watch stop <玩家>", "停止监控指定玩家");
        sendHelpEntry(sender, "/ansac watch stopall", "停止所有监控");
        sendHelpEntry(sender, "/ansac watch", "查看当前监控列表");

        sender.sendMessage(Component.empty());
        sender.sendMessage(Component.text("── 检测模式说明 ──", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("  RULE_ONLY", NamedTextColor.YELLOW)
            .append(Component.text("  纯规则检测，MLP仅作为观察参考", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  MODEL_ONLY", NamedTextColor.YELLOW)
            .append(Component.text("  AI全权接管判罪，规则层只记录参考", NamedTextColor.GRAY)));
        sender.sendMessage(Component.text("  HYBRID", NamedTextColor.YELLOW)
            .append(Component.text("  规则+模型融合双打，异常分数放大severity (默认)", NamedTextColor.GRAY)));
    }

    private void sendHelpEntry(CommandSender sender, String cmd, String desc) {
        sender.sendMessage(
            Component.text("  " + cmd, NamedTextColor.YELLOW)
                .append(Component.text(" - " + desc, NamedTextColor.GRAY))
        );
    }

    private void handleModeStatus(CommandSender sender) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        DetectionMode mode = svc.getDetectionMode();
        sender.sendMessage(Component.text("=== 检测模式 ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("当前模式：", NamedTextColor.YELLOW)
            .append(Component.text(mode.name(), NamedTextColor.WHITE)));
        String desc = switch (mode) {
            case RULE_ONLY -> "传统 if/else 规则检测，MLP 仅作为观察参考";
            case MODEL_ONLY -> "AI 模型全权接管判罪，规则层只记录参考";
            case HYBRID -> "规则 + 模型融合双打，异常分数放大 severity";
        };
        sender.sendMessage(Component.text("模式说明：", NamedTextColor.YELLOW)
            .append(Component.text(desc, NamedTextColor.GRAY)));
    }

    private void handleModeSet(CommandSender sender, String modeArg) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        DetectionMode mode = DetectionMode.fromString(modeArg);
        svc.setDetectionMode(mode);
        sender.sendMessage(Component.text("检测模式已切换为：", NamedTextColor.GREEN)
            .append(Component.text(mode.name(), NamedTextColor.YELLOW)));
        String tip = switch (mode) {
            case RULE_ONLY -> "规则检测已接管，MLP 不再参与处罚决策";
            case MODEL_ONLY -> "AI 模型已接管，异常度 >70% 将自动处罚";
            case HYBRID -> "混合双打启动，模型异常分数将放大规则 severity";
        };
        sender.sendMessage(Component.text(tip, NamedTextColor.GRAY));
    }

    // ============================================================
    // Real-time AI Watch Commands
    // ============================================================

    private void handleWatchSub(CommandSender sender, String[] args) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        String sub = args[1].toLowerCase();
        if (sub.equals("start") && args.length >= 3) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(Component.text("找不到该玩家或玩家不在线。", NamedTextColor.RED));
                return;
            }
            if (svc.isWatching(target.getUniqueId())) {
                sender.sendMessage(Component.text("该玩家已在监控中。", NamedTextColor.YELLOW));
                return;
            }
            svc.startWatch(target.getUniqueId());
        } else if (sub.equals("stop") && args.length >= 3) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null || !target.isOnline()) {
                sender.sendMessage(Component.text("找不到该玩家或玩家不在线。", NamedTextColor.RED));
                return;
            }
            svc.stopWatch(target.getUniqueId());
        } else if (sub.equals("stopall") || sub.equals("clear")) {
            for (java.util.UUID uuid : new java.util.ArrayList<>(svc.getWatchedPlayers())) {
                svc.stopWatch(uuid);
            }
            sender.sendMessage(Component.text("已停止所有监控。", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("用法: /ansac watch start <玩家> | stop <玩家> | stopall | list", NamedTextColor.RED));
        }
    }

    private void handleWatchList(CommandSender sender) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        java.util.Set<java.util.UUID> watched = svc.getWatchedPlayers();
        if (watched.isEmpty()) {
            sender.sendMessage(Component.text("当前没有正在监控的玩家。", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("用法: /ansac watch start <玩家名>", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("=== 实时监控列表 (" + watched.size() + "人) ===", NamedTextColor.GOLD));
        for (java.util.UUID uuid : watched) {
            Player p = Bukkit.getPlayer(uuid);
            String name = p != null ? p.getName() : uuid.toString().substring(0, 8);
            sender.sendMessage(Component.text("- " + name, NamedTextColor.YELLOW));
        }
        sender.sendMessage(Component.text("AI思维状态每2秒推送到 ActionBar。", NamedTextColor.GRAY));
    }

    // ==================== 高危玩家标记 ====================

    private void handleMarkSub(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/ansac mark <玩家名>", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("找不到玩家: " + args[1], NamedTextColor.RED));
            return;
        }
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (!svc.isDualModelEnabled()) {
            sender.sendMessage(Component.text("双模型架构未启用，请在配置中开启 dual-model.enabled", NamedTextColor.RED));
            return;
        }
        boolean added = svc.markHighRisk(target.getUniqueId());
        if (added) {
            sender.sendMessage(Component.text(
                "已将 " + target.getName() + " 标记为高危玩家。", NamedTextColor.LIGHT_PURPLE));
            sender.sendMessage(Component.text(
                "其行为数据将自动喂入B模型(威胁模型)进行训练。", NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text(
                target.getName() + " 已经是高危玩家。", NamedTextColor.YELLOW));
        }
    }

    private void handleUnmark(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(Component.text("找不到玩家: " + playerName, NamedTextColor.RED));
            return;
        }
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        boolean removed = svc.unmarkHighRisk(target.getUniqueId());
        if (removed) {
            sender.sendMessage(Component.text(
                "已取消 " + target.getName() + " 的高危标记。", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text(
                target.getName() + " 未被标记为高危玩家。", NamedTextColor.YELLOW));
        }
    }

    private void handleMarkList(CommandSender sender) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        java.util.Set<java.util.UUID> marked = svc.getHighRiskPlayers();
        if (marked.isEmpty()) {
            sender.sendMessage(Component.text("当前没有高危标记玩家。", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("用法: /ansac mark <玩家名>", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("=== 高危玩家列表 (" + marked.size() + "人) ===",
            NamedTextColor.LIGHT_PURPLE));
        for (java.util.UUID uuid : marked) {
            Player p = Bukkit.getPlayer(uuid);
            String name = p != null ? p.getName() : uuid.toString().substring(0, 8);
            sender.sendMessage(Component.text("- " + name + " [B模型训练中]", NamedTextColor.LIGHT_PURPLE));
        }
    }

    // ==================== 实时同步推理 ====================

    private void handleRealtimeSub(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/ansac realtime <玩家名>", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayer(args[1]);
        if (target == null) {
            sender.sendMessage(Component.text("找不到玩家: " + args[1], NamedTextColor.RED));
            return;
        }
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (!svc.isDualModelEnabled()) {
            sender.sendMessage(Component.text("双模型架构未启用，请在配置中开启 dual-model.enabled", NamedTextColor.RED));
            return;
        }
        boolean enabled = svc.enableRealtimeInference(target.getUniqueId());
        if (enabled) {
            sender.sendMessage(Component.text(
                "已为 " + target.getName() + " 启用实时同步推理。", NamedTextColor.AQUA));
            sender.sendMessage(Component.text(
                "模型将逐tick实时推理该玩家行为，并进行在线学习。", NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text(
                target.getName() + " 已启用实时同步推理。", NamedTextColor.YELLOW));
        }
    }

    private void handleUnrealtime(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayer(playerName);
        if (target == null) {
            sender.sendMessage(Component.text("找不到玩家: " + playerName, NamedTextColor.RED));
            return;
        }
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        boolean disabled = svc.disableRealtimeInference(target.getUniqueId());
        if (disabled) {
            sender.sendMessage(Component.text(
                "已为 " + target.getName() + " 禁用实时同步推理。", NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text(
                target.getName() + " 未启用实时同步推理。", NamedTextColor.YELLOW));
        }
    }

    private void handleRealtimeList(CommandSender sender) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        java.util.Set<java.util.UUID> players = svc.getRealtimeInferencePlayers();
        if (players.isEmpty()) {
            sender.sendMessage(Component.text("当前没有实时同步推理玩家。", NamedTextColor.GRAY));
            sender.sendMessage(Component.text("用法: /ansac realtime <玩家名>", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("=== 实时同步推理列表 (" + players.size() + "人) ===",
            NamedTextColor.AQUA));
        for (java.util.UUID uuid : players) {
            Player p = Bukkit.getPlayer(uuid);
            String name = p != null ? p.getName() : uuid.toString().substring(0, 8);
            sender.sendMessage(Component.text("- " + name + " [逐tick实时推理+在线学习]",
                NamedTextColor.AQUA));
        }
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(Component.text("=== ANSAC 状态 ===", NamedTextColor.GOLD));
        sender.sendMessage(
            Component.text("版本：", NamedTextColor.YELLOW)
                .append(Component.text(plugin.getDescription().getVersion(), NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("在线玩家：", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(plugin.getPlayerDataManager().getPlayerCount()), NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("已启用检测：", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(plugin.getCheckManager().getEnabledChecksCount()), NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("服务器类型：", NamedTextColor.YELLOW)
                .append(Component.text(plugin.getSchedulerAdapter().isFolia() ? "Folia" : "Paper/Spigot", NamedTextColor.WHITE))
        );

        // Auth module status
        sender.sendMessage(Component.text("--- 认证模块 ---", NamedTextColor.GOLD));
        sender.sendMessage(
            Component.text("认证状态：", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(plugin.getAuthService().isEnabled()), NamedTextColor.WHITE))
        );
        if (plugin.getAuthService().isEnabled()) {
            sender.sendMessage(
                Component.text("认证模式：", NamedTextColor.YELLOW)
                    .append(Component.text("即装即用", NamedTextColor.WHITE))
            );
        }
    }

    private void sendPlayerInfo(CommandSender sender, String playerName) {
        Player player = Bukkit.getPlayer(playerName);
        if (player == null) {
            sender.sendMessage(Component.text("找不到该玩家。", NamedTextColor.RED));
            return;
        }

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null) {
            sender.sendMessage(Component.text("未找到该玩家的数据。", NamedTextColor.RED));
            return;
        }

        sender.sendMessage(Component.text("=== 玩家信息：" + playerName + " ===", NamedTextColor.GOLD));
        sender.sendMessage(
            Component.text("延迟：", NamedTextColor.YELLOW)
                .append(Component.text(data.getPing() + "ms", NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("违规等级（VL）：", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(data.getTotalVL()), NamedTextColor.WHITE))
        );
        sender.sendMessage(
            Component.text("触发检测次数：", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(data.getFailedChecksCount()), NamedTextColor.WHITE))
        );
    }

    // ============================================================
    // Punishment Commands
    // ============================================================

    private void handleBan(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/ansac ban <玩家> [时长] [原因]", NamedTextColor.RED));
            sender.sendMessage(Component.text("时长支持: 10s, 20s, 1min, 30min, 1h, 8h, 24h, 3d, 7d, 15d, 30d, 180d, 360d, 3600d, forever", NamedTextColor.GRAY));
            return;
        }

        String targetName = args[1];
        long durationSeconds = -1;
        String reason = "违反服务器规则";

        if (args.length >= 3) {
            long parsed = parseDuration(args[2]);
            if (parsed != Long.MIN_VALUE) {
                durationSeconds = parsed;
                if (args.length >= 4) {
                    reason = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length));
                }
            } else {
                // Third arg is part of reason, not duration
                durationSeconds = -1;
                reason = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length));
            }
        }

        String operator = sender instanceof Player ? sender.getName() : "CONSOLE";

        String durationLabel = durationSeconds >= 0 ? formatDurationLabel(durationSeconds) : "永久";
        Player target = Bukkit.getPlayerExact(targetName);
        if (target != null && target.isOnline()) {
            plugin.getPunishmentManager().ban(target, reason, durationSeconds, operator, null, 0);
            sender.sendMessage(Component.text("已封禁玩家 " + targetName
                + " (" + durationLabel + ")"
                + " | 原因: " + reason, NamedTextColor.GREEN));
        } else {
            plugin.getPunishmentManager().banOffline(targetName, reason, durationSeconds, operator);
            sender.sendMessage(Component.text("已封禁离线玩家 " + targetName
                + " (" + durationLabel + ")"
                + " | 原因: " + reason, NamedTextColor.GREEN));
        }
    }

    /**
     * Parse duration string to seconds.
     * Supports: 10s, 20s, 1min, 30min, 1h, 8h, 24h, 3d, 7d, 15d, 30d, 180d, 360d, 3600d, forever
     * Also supports raw numbers (treated as minutes for backward compatibility).
     * Returns Long.MIN_VALUE if parsing fails.
     */
    private long parseDuration(String input) {
        String s = input.trim().toLowerCase();
        if (s.equals("forever") || s.equals("perm") || s.equals("permanent")) {
            return -1;
        }
        // Number + unit
        if (s.matches("\\d+s")) {
            return Long.parseLong(s.substring(0, s.length() - 1));
        }
        if (s.matches("\\d+min")) {
            return Long.parseLong(s.substring(0, s.length() - 3)) * 60;
        }
        if (s.matches("\\d+h")) {
            return Long.parseLong(s.substring(0, s.length() - 1)) * 3600;
        }
        if (s.matches("\\d+d")) {
            return Long.parseLong(s.substring(0, s.length() - 1)) * 86400;
        }
        // Raw number: treat as minutes for backward compatibility
        try {
            return Long.parseLong(s) * 60;
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private String formatDurationLabel(long seconds) {
        if (seconds < 0) return "永久";
        if (seconds < 60) return seconds + "秒";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "分钟";
        long hours = minutes / 60;
        if (hours < 24) return hours + "小时";
        long days = hours / 24;
        return days + "天";
    }

    private void handleKick(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/ansac kick <玩家> [原因]", NamedTextColor.RED));
            return;
        }

        String targetName = args[1];
        String reason = args.length >= 3
            ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
            : "违反服务器规则";

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(Component.text("找不到该玩家或玩家不在线。", NamedTextColor.RED));
            return;
        }

        String operator = sender instanceof Player ? sender.getName() : "CONSOLE";
        plugin.getPunishmentManager().kick(target, reason, operator, null, 0);
        sender.sendMessage(Component.text("已踢出玩家 " + targetName + " | 原因: " + reason, NamedTextColor.GREEN));
    }

    private void handleUnban(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法：/ansac unban <玩家/UUID>", NamedTextColor.RED));
            return;
        }

        String identifier = args[1];
        boolean success = plugin.getPunishmentManager().unban(identifier);
        if (success) {
            sender.sendMessage(Component.text("已成功解封 " + identifier, NamedTextColor.GREEN));
        } else {
            sender.sendMessage(Component.text("未找到 " + identifier + " 的封禁记录。", NamedTextColor.RED));
        }
    }

    private void handleBanlist(CommandSender sender) {
        var bans = plugin.getPunishmentManager().getActiveBans();
        if (bans.isEmpty()) {
            sender.sendMessage(Component.text("当前没有活跃的封禁记录。", NamedTextColor.GRAY));
            return;
        }

        sender.sendMessage(Component.text("=== 活跃封禁列表 (" + bans.size() + "条) ===", NamedTextColor.GOLD));
        for (PunishmentEntry entry : bans) {
            String info = entry.getPlayerName()
                + " | 原因: " + entry.getReason()
                + " | 操作者: " + entry.getOperator()
                + " | 时长: " + (entry.isPermanent() ? "永久" : formatDurationLabel(entry.getDurationSeconds()))
                + " | 剩余: " + (entry.isPermanent() ? "永久" : formatRemaining(entry));
            sender.sendMessage(Component.text(info, NamedTextColor.YELLOW));
        }
    }

    private String formatRemaining(PunishmentEntry entry) {
        long remainingMs = entry.getExpiryTime() - System.currentTimeMillis();
        if (remainingMs <= 0) return "已过期";
        long seconds = remainingMs / 1000L;
        if (seconds < 60) return seconds + "秒";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + "分钟";
        long hours = minutes / 60;
        if (hours < 24) return hours + "小时" + (minutes % 60) + "分钟";
        return (hours / 24) + "天" + ((hours % 24) > 0 ? (hours % 24) + "小时" : "");
    }

    // ============================================================
    // Physics Inference Commands
    // ============================================================

    private void handleTrust(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(Component.text("找不到该玩家或玩家不在线。", NamedTextColor.RED));
            return;
        }
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        svc.markPlayerTrusted(target.getUniqueId());
        sender.sendMessage(Component.text("已将玩家 " + playerName + " 标记为受信任，其移动数据将用于自学习。", NamedTextColor.GREEN));
    }

    private void handleUntrust(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(Component.text("找不到该玩家或玩家不在线。", NamedTextColor.RED));
            return;
        }
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        svc.unmarkPlayerTrusted(target.getUniqueId());
        sender.sendMessage(Component.text("已取消玩家 " + playerName + " 的信任标记。", NamedTextColor.GREEN));
    }

    private void handleTrustlist(CommandSender sender) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        var trusted = svc.getTrustedPlayers();
        if (trusted.isEmpty()) {
            sender.sendMessage(Component.text("当前没有受信任的玩家。", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("=== 受信任玩家列表 (" + trusted.size() + "人) ===", NamedTextColor.GOLD));
        for (java.util.UUID uuid : trusted.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            String name = p != null ? p.getName() : uuid.toString().substring(0, 8);
            sender.sendMessage(Component.text("- " + name, NamedTextColor.YELLOW));
        }
    }

    private void handleBaseline(CommandSender sender) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("=== 基准模型状态 ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("场景数量：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getScenarioCount()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("总采样数：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getBaselineModel().getTotalSamples()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("学习进度：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.1f%%", svc.getLearningProgressPercent()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("受信任玩家：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getTrustedPlayerCount()), NamedTextColor.WHITE)));
    }

    private void handleBaselineSub(CommandSender sender, String sub) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        switch (sub.toLowerCase()) {
            case "reset":
                svc.getBaselineModel().reset();
                sender.sendMessage(Component.text("基准模型已重置，所有学习数据已清空。", NamedTextColor.GREEN));
                break;
            case "save":
                svc.saveBaseline();
                sender.sendMessage(Component.text("基准模型已保存。", NamedTextColor.GREEN));
                break;
            default:
                sender.sendMessage(Component.text("未知子命令。用法: /ansac baseline [reset|save]", NamedTextColor.RED));
                break;
        }
    }

    // ==================== 推理分数板 ====================

    private void handleInferenceScoreboardStart(CommandSender sender, String playerName) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(Component.text("此命令只能由玩家执行。", NamedTextColor.RED));
            return;
        }
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(Component.text("找不到玩家或玩家不在线: " + playerName, NamedTextColor.RED));
            return;
        }

        var sbManager = plugin.getInferenceScoreboardManager();
        UUID existingTarget = sbManager.getWatchTarget(admin.getUniqueId());
        if (existingTarget != null) {
            // 已在监控，切换目标
            sbManager.stopWatching(admin.getUniqueId());
        }

        sbManager.startWatching(admin, target);
        sender.sendMessage(Component.text(
            "已开启 " + target.getName() + " 的推理分数板。", NamedTextColor.AQUA));
        sender.sendMessage(Component.text(
            "分数板每 2 秒实时更新双模型 AB 架构推理结果。", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(
            "使用 /ansac inference stop 关闭分数板。", NamedTextColor.GRAY));
    }

    private void handleInferenceScoreboardStop(CommandSender sender) {
        if (!(sender instanceof Player admin)) {
            sender.sendMessage(Component.text("此命令只能由玩家执行。", NamedTextColor.RED));
            return;
        }
        var sbManager = plugin.getInferenceScoreboardManager();
        if (!sbManager.isWatching(admin.getUniqueId())) {
            sender.sendMessage(Component.text("你没有开启任何推理分数板。", NamedTextColor.YELLOW));
            return;
        }
        sbManager.stopWatching(admin.getUniqueId());
        sender.sendMessage(Component.text("推理分数板已关闭。", NamedTextColor.GREEN));
    }

    private void handleInferenceScoreboardList(CommandSender sender) {
        var sbManager = plugin.getInferenceScoreboardManager();
        var admins = sbManager.getWatchingAdmins();
        if (admins.isEmpty()) {
            sender.sendMessage(Component.text("当前没有管理员开启推理分数板。", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("=== 推理分数板列表 (" + admins.size() + ") ===",
            NamedTextColor.AQUA));
        for (UUID adminUuid : admins) {
            Player admin = Bukkit.getPlayer(adminUuid);
            UUID targetUuid = sbManager.getWatchTarget(adminUuid);
            Player target = targetUuid != null ? Bukkit.getPlayer(targetUuid) : null;
            String adminName = admin != null ? admin.getName() : adminUuid.toString().substring(0, 8);
            String targetName = target != null ? target.getName() : "离线";
            sender.sendMessage(Component.text("- " + adminName + " → " + targetName,
                NamedTextColor.AQUA));
        }
    }

    private void handleInferenceStatus(CommandSender sender) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        sender.sendMessage(Component.text("=== 物理推理服务状态 ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("服务状态：", NamedTextColor.YELLOW)
            .append(Component.text("始终启用", NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("检测模式：", NamedTextColor.YELLOW)
            .append(Component.text(svc.getDetectionMode().name(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("受信任玩家：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getTrustedPlayerCount()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("学习次数：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getLearningCount()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("修正次数：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getCorrectionCount()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("迭代次数：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(svc.getIterationCount()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("学习进度：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.1f%%", svc.getLearningProgressPercent()), NamedTextColor.WHITE)));

        // 双模型 AB 架构状态
        if (svc.isDualModelEnabled()) {
            sender.sendMessage(Component.text("━━━ 双模型 AB 架构 ━━━", NamedTextColor.LIGHT_PURPLE));
            sender.sendMessage(Component.text("双模型架构：", NamedTextColor.YELLOW)
                .append(Component.text("已启用", NamedTextColor.GREEN)));
            sender.sendMessage(Component.text("高危玩家数：", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(svc.getHighRiskPlayers().size()),
                    svc.getHighRiskPlayers().isEmpty() ? NamedTextColor.GRAY : NamedTextColor.RED)));
            sender.sendMessage(Component.text("实时推理玩家数：", NamedTextColor.YELLOW)
                .append(Component.text(String.valueOf(svc.getRealtimeInferencePlayers().size()),
                    svc.getRealtimeInferencePlayers().isEmpty() ? NamedTextColor.GRAY : NamedTextColor.AQUA)));

            // B模型训练状态
            var threatSession = svc.getThreatSamplingSession();
            if (threatSession != null) {
                sender.sendMessage(Component.text("B模型采样状态：", NamedTextColor.YELLOW)
                    .append(Component.text(threatSession.getState().name(), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("B模型采样进度：", NamedTextColor.YELLOW)
                    .append(Component.text(String.valueOf(threatSession.getSampleCount())
                        + " / " + threatSession.getTargetSamples(), NamedTextColor.WHITE)));
            }

            // ModelSelector 权重
            var selector = svc.getModelSelector();
            if (selector != null) {
                sender.sendMessage(Component.text("A模型权重：", NamedTextColor.YELLOW)
                    .append(Component.text(String.format("%.2f", selector.getModelAWeight()), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("B模型权重：", NamedTextColor.YELLOW)
                    .append(Component.text(String.format("%.2f", selector.getModelBWeight()), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("规则权重：", NamedTextColor.YELLOW)
                    .append(Component.text(String.format("%.2f", selector.getRuleWeight()), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("高危B权重加成：", NamedTextColor.YELLOW)
                    .append(Component.text(String.format("x%.1f", selector.getHighRiskBWeightBoost()), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("双确认阈值：", NamedTextColor.YELLOW)
                    .append(Component.text(String.format("%.2f", selector.getDualConfirmThreshold()), NamedTextColor.WHITE)));
                sender.sendMessage(Component.text("定罪阈值：", NamedTextColor.YELLOW)
                    .append(Component.text(String.format("%.2f", selector.getSingleConvictThreshold()), NamedTextColor.WHITE)));
            }
        } else {
            sender.sendMessage(Component.text("双模型架构：", NamedTextColor.YELLOW)
                .append(Component.text("未启用 (config: dual-model.enabled=false)", NamedTextColor.GRAY)));
        }

        sender.sendMessage(Component.text("提示：使用 /ansac inference <玩家名> 查看玩家完整推理报告。", NamedTextColor.GRAY));
    }

    private void handleInferencePlayer(CommandSender sender, String playerName) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(Component.text("找不到该玩家或玩家不在线。", NamedTextColor.RED));
            return;
        }
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        InferenceResult result = svc.getInferenceResult(target.getUniqueId());
        if (result == InferenceResult.EMPTY) {
            sender.sendMessage(Component.text("该玩家暂无推理数据（可能刚上线或尚未移动）。", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("=== 玩家物理快照：" + playerName + " ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("水平速度：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.3f", result.horizontalSpeed()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Y轴速度：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.3f", result.velocityY()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("预测Y速度：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.3f", result.predictedVelocityY()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("预期最大水平速度：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.3f", result.expectedMaxHorizontalSpeed()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("速度偏差比：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.3f", result.getSpeedDeviationRatio()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("垂直偏差：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.3f", result.getVerticalDeviation()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("跳跃阶段：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(result.jumpPhase()), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("空中状态：", NamedTextColor.YELLOW)
            .append(Component.text(result.inAir() ? "是" : "否", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("跌落距离：", NamedTextColor.YELLOW)
            .append(Component.text(String.format("%.2f", result.fallDistance()), NamedTextColor.WHITE)));

        // ===== MLP 神经网络推理详情 =====
        {
            MLPInferenceDetail detail = svc.getDetailedMlpResult(target.getUniqueId());
            if (detail != null) {
                sender.sendMessage(Component.text("━━━ MLP 神经网络推理 ━━━", NamedTextColor.DARK_AQUA));

                // 判定结果
                double score = detail.getOutputScore();
                String verdict = detail.getVerdictLabel();
                NamedTextColor verdictColor = score >= 0.6 ? NamedTextColor.GREEN
                    : score >= 0.4 ? NamedTextColor.YELLOW : NamedTextColor.RED;
                sender.sendMessage(Component.text("判定结果：", NamedTextColor.YELLOW)
                    .append(Component.text(verdict, verdictColor))
                    .append(Component.text(" (" + String.format("%.4f", score) + ")", NamedTextColor.GRAY)));

                // 网络结构概览
                sender.sendMessage(Component.text("网络结构：", NamedTextColor.YELLOW)
                    .append(Component.text(
                        svc.getMovementMLP().getInputSize() + "(输入) → "
                        + svc.getMovementMLP().getHidden1Size() + "(ReLU) → "
                        + svc.getMovementMLP().getHidden2Size() + "(ReLU) → 1(Sigmoid)",
                        NamedTextColor.GRAY)));

                // 隐藏层1激活值热力图
                sender.sendMessage(Component.text("隐藏层1 激活值 (24神经元)：", NamedTextColor.YELLOW));
                sender.sendMessage(miniMessage.deserialize(
                    formatActivationBar(detail.getHidden1Activations(), "h1")));

                // 隐藏层2激活值热力图
                sender.sendMessage(Component.text("隐藏层2 激活值 (16神经元)：", NamedTextColor.YELLOW));
                sender.sendMessage(miniMessage.deserialize(
                    formatActivationBar(detail.getHidden2Activations(), "h2")));

                // 关键输入特征（显示偏离零值最大的前6个）
                sender.sendMessage(Component.text("关键输入特征（Top 6）：", NamedTextColor.YELLOW));
                int[] topIndices = getTopActiveIndices(detail.getInputFeatures(), 6);
                for (int idx : topIndices) {
                    String name = idx < BehaviorFeatureExtractor.FEATURE_NAMES.length
                        ? BehaviorFeatureExtractor.FEATURE_NAMES[idx] : "F" + idx;
                    double val = detail.getInputFeatures()[idx];
                    String bar = buildMiniBar(val, 10);
                    NamedTextColor barColor = Math.abs(val) > 0.7 ? NamedTextColor.GOLD
                        : Math.abs(val) > 0.3 ? NamedTextColor.AQUA : NamedTextColor.DARK_GRAY;
                    sender.sendMessage(miniMessage.deserialize(
                        "  <gray>" + name + "</gray> <" +
                        (barColor == NamedTextColor.GOLD ? "gold" : barColor == NamedTextColor.AQUA ? "aqua" : "dark_gray") +
                        ">" + bar + "</" +
                        (barColor == NamedTextColor.GOLD ? "gold" : barColor == NamedTextColor.AQUA ? "aqua" : "dark_gray") +
                        "> <white>" + String.format("%.3f", val) + "</white>"
                    ));
                }
            } else {
                sender.sendMessage(Component.text("MLP 推理数据暂不可用。", NamedTextColor.GRAY));
            }
        }

        // ===== 多模型融合决策 =====
        {
            PlayerPhysicsState pstate = svc.getState(target.getUniqueId());
            if (pstate != null) {
                sender.sendMessage(Component.text("━━━ 多模型融合决策 ━━━", NamedTextColor.DARK_AQUA));
                double moveScore = pstate.getLastNormalScore();
                double anomalyScore = pstate.getLastAnomalyScore();
                sender.sendMessage(Component.text("MovementMLP 正常度：", NamedTextColor.YELLOW)
                    .append(Component.text(String.format("%.4f", moveScore), moveScore >= 0.5 ? NamedTextColor.GREEN : NamedTextColor.RED)));
                // CombatMLP 正常度
                PlayerData cdata = plugin.getPlayerDataManager().getPlayerData(target);
                if (cdata != null) {
                    double[] cfeatures = BehaviorFeatureExtractor.extractCombatSlice(
                        BehaviorFeatureExtractor.extract(pstate, cdata.getBehaviorProfile()));
                    double combatScore = svc.getCombatMLP().forward(cfeatures);
                    sender.sendMessage(Component.text("CombatMLP 正常度：", NamedTextColor.YELLOW)
                        .append(Component.text(String.format("%.4f", combatScore), combatScore >= 0.5 ? NamedTextColor.GREEN : NamedTextColor.RED)));
                }
                String fusionVerdict = dev.ztros.ansac.physics.mlp.CausalFusion.getVerdictLabel(anomalyScore);
                NamedTextColor fusionColor = anomalyScore >= 0.65 ? NamedTextColor.DARK_RED
                    : anomalyScore >= 0.45 ? NamedTextColor.RED
                    : anomalyScore >= 0.25 ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
                sender.sendMessage(Component.text("CausalFusion 异常度：", NamedTextColor.YELLOW)
                    .append(Component.text(String.format("%.4f (%s)", anomalyScore, fusionVerdict), fusionColor)));
                sender.sendMessage(Component.text("检测模式：", NamedTextColor.YELLOW)
                    .append(Component.text(svc.getDetectionMode().name(), NamedTextColor.WHITE)));

                // AI 思维链（人类可读的自然语言推理过程）
                sender.sendMessage(miniMessage.deserialize(
                    InferenceInterpreter.buildDetailedThought(
                        moveScore, anomalyScore, anomalyScore, pstate)));
            }
        }

        // ===== 双模型 AB 架构推理 =====
        if (svc.isDualModelEnabled()) {
            dev.ztros.ansac.physics.mlp.DualInferenceResult dual = svc.getDualInferenceResult(target.getUniqueId());
            if (dual != dev.ztros.ansac.physics.mlp.DualInferenceResult.EMPTY) {
                sender.sendMessage(Component.text("━━━ 双模型 AB 架构推理 ━━━", NamedTextColor.LIGHT_PURPLE));

                // A模型（正常模型）
                sender.sendMessage(Component.text("A模型移动正常度：", NamedTextColor.YELLOW)
                    .append(Component.text(String.format("%.2f%%", dual.normalMovementScore() * 100),
                        dual.normalMovementScore() >= 0.5 ? NamedTextColor.GREEN : NamedTextColor.RED)));
                sender.sendMessage(Component.text("A模型战斗正常度：", NamedTextColor.YELLOW)
                    .append(Component.text(String.format("%.2f%%", dual.normalCombatScore() * 100),
                        dual.normalCombatScore() >= 0.5 ? NamedTextColor.GREEN : NamedTextColor.RED)));
                sender.sendMessage(Component.text("A模型融合异常度：", NamedTextColor.YELLOW)
                    .append(Component.text(String.format("%.2f%%", dual.normalAnomalyScore() * 100),
                        dual.normalAnomalyScore() >= 0.5 ? NamedTextColor.RED : NamedTextColor.GREEN)));

                // B模型（威胁模型）
                sender.sendMessage(Component.text("B模型移动威胁匹配：", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(String.format("%.2f%%", dual.threatMovementScore() * 100),
                        dual.threatMovementScore() >= 0.5 ? NamedTextColor.RED : NamedTextColor.GREEN)));
                sender.sendMessage(Component.text("B模型战斗威胁匹配：", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(String.format("%.2f%%", dual.threatCombatScore() * 100),
                        dual.threatCombatScore() >= 0.5 ? NamedTextColor.RED : NamedTextColor.GREEN)));
                sender.sendMessage(Component.text("B模型融合威胁度：", NamedTextColor.LIGHT_PURPLE)
                    .append(Component.text(String.format("%.2f%%", dual.threatFusionScore() * 100),
                        dual.threatFusionScore() >= 0.5 ? NamedTextColor.RED : NamedTextColor.GREEN)));

                // ModelSelector 综合评估
                if (dual.selectorResult() != null) {
                    var sr = dual.selectorResult();
                    NamedTextColor srcColor = switch (sr.source()) {
                        case DUAL_CONFIRM -> NamedTextColor.DARK_RED;
                        case MODEL_B_ONLY -> NamedTextColor.RED;
                        case MODEL_A_ONLY -> NamedTextColor.YELLOW;
                        case INSUFFICIENT -> NamedTextColor.GREEN;
                    };
                    sender.sendMessage(Component.text("定罪来源：", NamedTextColor.GOLD)
                        .append(Component.text(sr.source().name(), srcColor)));
                    sender.sendMessage(Component.text("综合置信度：", NamedTextColor.GOLD)
                        .append(Component.text(
                            String.format("%.2f%% (%s)", sr.confidence() * 100,
                                dev.ztros.ansac.physics.mlp.ModelSelector.getConfidenceLabel(sr.confidence())),
                            sr.confidence() >= 0.75 ? NamedTextColor.RED : NamedTextColor.YELLOW)));
                    sender.sendMessage(Component.text("建议定罪：", NamedTextColor.GOLD)
                        .append(Component.text(sr.shouldConvict() ? "是" : "否",
                            sr.shouldConvict() ? NamedTextColor.RED : NamedTextColor.GREEN)));
                    sender.sendMessage(miniMessage.deserialize(
                        "<gray>推理: " + sr.reasoning() + "</gray>"));
                }

                // 状态标记
                if (dual.isHighRisk()) {
                    sender.sendMessage(Component.text("[高危玩家] B模型权重已自动提升。",
                        NamedTextColor.DARK_RED));
                }
                if (dual.isRealtimeInference()) {
                    sender.sendMessage(Component.text("[实时同步推理] 逐tick在线学习中。",
                        NamedTextColor.AQUA));
                }
            }
        }

        // ===== 玩家行为完整画像 =====
        PlayerData pdata = plugin.getPlayerDataManager().getPlayerData(target);
        if (pdata != null) {
            PlayerBehaviorProfile profile = pdata.getBehaviorProfile();
            sender.sendMessage(Component.text("━━━ 玩家行为画像 ━━━", NamedTextColor.DARK_AQUA));

            // 战斗维度
            sender.sendMessage(Component.text("【战斗】", NamedTextColor.RED)
                .append(Component.text(
                    " CPS=" + String.format("%.1f", profile.getCombatCpsMean())
                    + "±" + String.format("%.1f", profile.getCombatCpsStd())
                    + " | 暴击率=" + String.format("%.1f%%", profile.getCritRate() * 100)
                    + " | reach=" + String.format("%.2f", profile.getReachMean())
                    + " | 连击=" + profile.getComboCount()
                    + " | 平滑=" + String.format("%.2f", profile.getAimSmoothness()),
                    NamedTextColor.GRAY)));

            // 建造维度
            sender.sendMessage(Component.text("【建造】", NamedTextColor.GREEN)
                .append(Component.text(
                    " 放置间隔=" + String.format("%.0f", profile.getPlaceIntervalMean()) + "ms"
                    + " | 空中放置率=" + String.format("%.1f%%", profile.getAirPlaceRate() * 100)
                    + " | 方向一致=" + String.format("%.2f", profile.getDirectionConsistencyMean()),
                    NamedTextColor.GRAY)));

            // 交互维度
            sender.sendMessage(Component.text("【交互】", NamedTextColor.YELLOW)
                .append(Component.text(
                    " 吃速=" + String.format("%.1f", profile.getEatDurationMean()) + "tick"
                    + " | 格挡=" + String.format("%.1f", profile.getBlockDurationMean()) + "tick"
                    + " | 快速使用率=" + String.format("%.1f%%", profile.getFastUseRate() * 100),
                    NamedTextColor.GRAY)));

            // 网络维度
            sender.sendMessage(Component.text("【网络】", NamedTextColor.AQUA)
                .append(Component.text(
                    " 飞行包间隔=" + String.format("%.1f", profile.getFlyingIntervalMean()) + "ms"
                    + " | 丢包=" + String.format("%.2f%%", profile.getPacketLossMean() * 100)
                    + " | 计时器=" + String.format("%.0f", profile.getTimerBalanceMean()),
                    NamedTextColor.GRAY)));

            // 会话统计
            sender.sendMessage(Component.text("【会话】", NamedTextColor.LIGHT_PURPLE)
                .append(Component.text(
                    " 总攻击=" + profile.getTotalAttacks()
                    + " | 总放置=" + profile.getTotalBlocksPlaced()
                    + " | 总破坏=" + profile.getTotalBlocksBroken()
                    + " | 总进食=" + profile.getTotalEats()
                    + " | 在线=" + profile.getSessionDurationMinutes() + "min",
                    NamedTextColor.GRAY)));
        }

        boolean isTrusted = svc.isTrusted(target.getUniqueId());
        sender.sendMessage(Component.text("信任状态：", NamedTextColor.YELLOW)
            .append(Component.text(isTrusted ? "受信任" : "未信任", isTrusted ? NamedTextColor.GREEN : NamedTextColor.GRAY)));
    }

    /**
     * 格式化激活值为可视化文本条。
     */
    private String formatActivationBar(double[] activations, String prefix) {
        StringBuilder sb = new StringBuilder();
        sb.append("<gray>");
        for (int i = 0; i < activations.length; i++) {
            double v = activations[i];
            String block;
            if (v > 1.0) block = "<red>X</red>";
            else if (v > 0.5) block = "<gold>\u2588</gold>";
            else if (v > 0.0) block = "<aqua>\u2593</aqua>";
            else block = "<dark_gray>\u2591</dark_gray>";
            sb.append(block);
            if ((i + 1) % 8 == 0 && i < activations.length - 1) sb.append("\n ");
        }
        sb.append("</gray>");
        return sb.toString();
    }

    /**
     * 获取偏离零值最大的前N个特征索引。
     */
    private static int[] getTopActiveIndices(double[] features, int count) {
        int n = Math.min(count, features.length);
        int[] indices = new int[n];
        boolean[] used = new boolean[features.length];
        for (int k = 0; k < n; k++) {
            double maxVal = Double.MIN_VALUE;
            int maxIdx = 0;
            for (int i = 0; i < features.length; i++) {
                if (!used[i] && Math.abs(features[i]) > maxVal) {
                    maxVal = Math.abs(features[i]);
                    maxIdx = i;
                }
            }
            indices[k] = maxIdx;
            used[maxIdx] = true;
        }
        return indices;
    }

    /**
     * 构建MiniMessage格式的微型进度条。
     */
    private static String buildMiniBar(double value, int length) {
        double absVal = Math.min(Math.abs(value), 1.0);
        int filled = (int) Math.round(absVal * length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            sb.append(i < filled ? "\u25a0" : "\u25a1");
        }
        return sb.toString();
    }

    private void handleSamplingStatus(CommandSender sender) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        MLPSamplingSession session = svc.getSamplingSession();
        sender.sendMessage(Component.text("=== MLP 学习状态 ===", NamedTextColor.GOLD));
        sender.sendMessage(Component.text("运行模式：", NamedTextColor.YELLOW)
            .append(Component.text(session.isContinuousMode() ? "持续自动" : "手动确认", NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("会话状态：", NamedTextColor.YELLOW)
            .append(Component.text(session.getState().name(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("样本进度：", NamedTextColor.YELLOW)
            .append(Component.text(session.getSampleCount() + " / " + session.getTargetSamples(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("训练轮次：", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(session.getTrainRound()), NamedTextColor.WHITE)));
    }

    private void handleSamplingSub(CommandSender sender, String sub) {
        PhysicsInferenceService svc = plugin.getPhysicsInferenceService();
        if (svc == null) {
            sender.sendMessage(Component.text("物理推理服务未启动。", NamedTextColor.RED));
            return;
        }
        MLPSamplingSession session = svc.getSamplingSession();
        switch (sub.toLowerCase()) {
            case "start":
                if (session.getState() == MLPSamplingSession.State.COLLECTING) {
                    sender.sendMessage(Component.text("MLP 持续学习已在运行中。", NamedTextColor.YELLOW));
                    return;
                }
                session.startCollecting();
                sender.sendMessage(Component.text(
                    "已开启 MLP 持续自学习：将自动采集受信任玩家数据并持续训练。", NamedTextColor.GREEN));
                sender.sendMessage(Component.text(
                    "使用 /ansac sampling stop 可随时停止学习。", NamedTextColor.GRAY));
                break;
            case "stop":
                session.adminStop();
                sender.sendMessage(Component.text("已停止 MLP 持续学习。", NamedTextColor.YELLOW));
                break;
            case "status":
                handleSamplingStatus(sender);
                break;
            default:
                sender.sendMessage(Component.text("未知子命令。用法: /ansac sampling <start|stop|status>", NamedTextColor.RED));
                break;
        }
    }
}
