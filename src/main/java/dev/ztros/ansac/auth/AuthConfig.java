package dev.ztros.ansac.auth;

import dev.ztros.ansac.ANSACPlugin;
import lombok.Getter;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class AuthConfig {

    @Getter
    private boolean enabled;

    @Getter
    private int loginTimeout;

    @Getter
    private int sameIpTimeout;

    @Getter
    private boolean autoKick;

    @Getter
    private boolean restrictMovement;

    @Getter
    private boolean restrictInteraction;

    @Getter
    private boolean restrictChat;

    @Getter
    private boolean restrictCommands;

    @Getter
    private boolean restrictInventory;

    @Getter
    private boolean restrictItems;

    @Getter
    private List<Pattern> commandWhitelist;

    @Getter
    private boolean proxyEnabled;

    @Getter
    private String proxyType;

    private final ANSACPlugin plugin;

    public AuthConfig(ANSACPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        var config = plugin.getConfig();

        this.enabled = config.getBoolean("auth.enabled", true);
        this.loginTimeout = config.getInt("auth.login-timeout", 120);
        this.sameIpTimeout = config.getInt("auth.same-ip-timeout", 5);
        this.autoKick = config.getBoolean("auth.auto-kick", true);
        this.restrictMovement = config.getBoolean("auth.restrict-movement", true);
        this.restrictInteraction = config.getBoolean("auth.restrict-interaction", true);
        this.restrictChat = config.getBoolean("auth.restrict-chat", true);
        this.restrictCommands = config.getBoolean("auth.restrict-commands", true);
        this.restrictInventory = config.getBoolean("auth.restrict-inventory", true);
        this.restrictItems = config.getBoolean("auth.restrict-items", true);
        this.proxyEnabled = config.getBoolean("auth.proxy.enabled", false);
        this.proxyType = config.getString("auth.proxy.type", "auto");

        this.commandWhitelist = config.getStringList("auth.command-whitelist").stream()
                .map(pattern -> {
                    try {
                        return java.util.regex.Pattern.compile(pattern);
                    } catch (java.util.regex.PatternSyntaxException e) {
                        plugin.getLogger().warning("auth.command-whitelist 中的正则表达式无效: " + pattern + " (" + e.getMessage() + ")");
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());
    }

    public boolean isCommandAllowed(String command) {
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        for (Pattern pattern : commandWhitelist) {
            if (pattern.matcher(command).matches()) {
                return true;
            }
        }
        return false;
    }
}
