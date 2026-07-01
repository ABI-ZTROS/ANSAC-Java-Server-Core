package dev.ztros.ansac;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ANSAC 命令智能补全。
 * 为 /ansac 提供按 Tab 自动补全子命令和参数。
 */
public class ANSACTabCompleter implements TabCompleter {

    private static final List<String> MAIN_SUBS = Arrays.asList(
        "reload", "status", "info", "ban", "kick", "unban", "banlist",
        "trust", "untrust", "trustlist", "baseline", "inference",
        "sampling", "mode", "watch",
        "mark", "marklist"
    );

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("ansac")) {
            return List.of();
        }

        // 第一个参数：补全子命令
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return MAIN_SUBS.stream()
                .filter(s -> s.startsWith(prefix))
                .filter(s -> hasPermission(sender, s))
                .collect(Collectors.toList());
        }

        // 第二个参数及以后：根据子命令提供补全
        String sub = args[0].toLowerCase();
        String prefix = args.length >= 2 ? args[args.length - 1].toLowerCase() : "";

        switch (sub) {
            case "info":
            case "trust":
            case "untrust":
                // 玩家名补全
                if (args.length == 2) {
                    return filterPlayers(prefix);
                }
                break;

            case "ban":
            case "kick":
                // 玩家名
                if (args.length == 2) {
                    return filterPlayers(prefix);
                }
                // ban 时长
                if (args.length == 3 && sub.equals("ban")) {
                    List<String> durations = Arrays.asList(
                        "10s", "20s", "1min", "30min", "1h", "8h", "24h",
                        "3d", "7d", "15d", "30d", "180d", "360d", "forever"
                    );
                    return durations.stream().filter(d -> d.startsWith(prefix)).collect(Collectors.toList());
                }
                break;

            case "unban":
                // 不补全（UUID 或离线玩家名）
                break;

            case "baseline":
                if (args.length == 2) {
                    List<String> subs = Arrays.asList("reset", "save");
                    return subs.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
                }
                break;

            case "inference":
                if (args.length == 2) {
                    List<String> subs = Arrays.asList("stop", "list");
                    List<String> matched = subs.stream().filter(s -> s.startsWith(prefix.toLowerCase())).collect(Collectors.toList());
                    List<String> players = filterPlayers(prefix);
                    matched.addAll(players);
                    return matched;
                }
                break;

            case "sampling":
                if (args.length == 2) {
                    List<String> subs = Arrays.asList("start", "stop", "status");
                    return subs.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
                }
                break;

            case "mode":
                if (args.length == 2) {
                    List<String> modes = Arrays.asList("rule", "model", "hybrid");
                    return modes.stream().filter(m -> m.startsWith(prefix)).collect(Collectors.toList());
                }
                break;

            case "watch":
                if (args.length == 2) {
                    List<String> subs = Arrays.asList("start", "stop", "stopall");
                    return subs.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
                }
                // watch start/stop 后面补全玩家名
                if (args.length == 3 && (args[1].equalsIgnoreCase("start") || args[1].equalsIgnoreCase("stop"))) {
                    return filterPlayers(prefix);
                }
                break;

            case "mark":
                if (args.length == 2) {
                    List<String> markSubs = Arrays.asList("add", "remove");
                    List<String> matched = markSubs.stream().filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
                    matched.addAll(filterPlayers(prefix));
                    return matched;
                }
                // mark add/remove 后面补全玩家名
                if (args.length == 3 && (args[1].equalsIgnoreCase("add") || args[1].equalsIgnoreCase("remove"))) {
                    return filterPlayers(prefix);
                }
                break;

            case "marklist":
                // 无参数子命令
                break;
        }

        return List.of();
    }

    private List<String> filterPlayers(String prefix) {
        return Bukkit.getOnlinePlayers().stream()
            .map(Player::getName)
            .filter(name -> name.toLowerCase().startsWith(prefix))
            .collect(Collectors.toList());
    }

    private boolean hasPermission(CommandSender sender, String sub) {
        return switch (sub) {
            case "reload" -> sender.hasPermission("ansac.command.reload");
            case "status" -> sender.hasPermission("ansac.command.status");
            case "info", "ban", "kick", "unban", "banlist" -> sender.hasPermission("ansac.admin");
            default -> sender.hasPermission("ansac.admin");
        };
    }
}
