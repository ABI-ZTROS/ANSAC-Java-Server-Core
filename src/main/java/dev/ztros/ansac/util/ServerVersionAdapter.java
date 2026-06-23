package dev.ztros.ansac.util;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Runtime version-adaptive utility for Folia compatibility.
 * Detects server API capabilities at runtime and provides safe fallbacks.
 * Supports ALL Folia versions from 1.19.4 to 26.1.2+.
 */
public final class ServerVersionAdapter {

    private static final String PACKAGE_VERSION;

    private static final boolean HAS_KICK_COMPONENT;
    private static final boolean HAS_JUMP_BOOST;

    private static Method KICK_COMPONENT_METHOD;

    static {
        // Detect Bukkit API version
        String version = "";
        try {
            Package pkg = ServerVersionAdapter.class.getPackage();
            // Try to get version from Bukkit
            Class<?> bukkitClass = Class.forName("org.bukkit.Bukkit");
            Method getVersion = bukkitClass.getMethod("getVersion");
            version = (String) getVersion.invoke(null);
        } catch (Exception e) {
            version = "unknown";
        }
        PACKAGE_VERSION = version;

        // Detect kick(Component) availability
        boolean hasKickComponent = false;
        try {
            KICK_COMPONENT_METHOD = Player.class.getMethod("kick", Component.class);
            hasKickComponent = true;
        } catch (NoSuchMethodException e) {
            hasKickComponent = false;
        }
        HAS_KICK_COMPONENT = hasKickComponent;

        // Detect PotionEffectType.JUMP_BOOST availability (renamed in 1.20.6+)
        boolean hasJumpBoost = false;
        try {
            PotionEffectType.class.getField("JUMP_BOOST");
            hasJumpBoost = true;
        } catch (NoSuchFieldException e) {
            hasJumpBoost = false;
        }
        HAS_JUMP_BOOST = hasJumpBoost;
    }

    private ServerVersionAdapter() {}

    /**
     * Get the detected server version string.
     */
    public static String getServerVersion() {
        return PACKAGE_VERSION;
    }

    /**
     * Kick a player with version-adaptive API.
     * Uses kick(Component) on 1.20+, falls back to kickPlayer(String) on older versions.
     */
    public static void kickPlayer(Player player, Component message) {
        if (HAS_KICK_COMPONENT) {
            try {
                KICK_COMPONENT_METHOD.invoke(player, message);
                return;
            } catch (Exception e) {
                // Fall through to legacy method
            }
        }
        // Fallback: use kickPlayer with legacy text
        String legacyText = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                .legacyAmpersand()
                .serialize(message);
        player.kickPlayer(legacyText);
    }

    /**
     * Get the JUMP_BOOST potion effect type.
     * Uses JUMP_BOOST on 1.20.6+, falls back to JUMP on older versions.
     */
    public static PotionEffectType getJumpBoost() {
        if (HAS_JUMP_BOOST) {
            return PotionEffectType.JUMP_BOOST;
        }
        // Fallback for pre-1.20.6
        try {
            return PotionEffectType.JUMP;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if the server supports the modern kick(Component) API.
     */
    public static boolean hasKickComponent() {
        return HAS_KICK_COMPONENT;
    }

    /**
     * Check if the server uses the JUMP_BOOST naming (1.20.6+).
     */
    public static boolean hasJumpBoost() {
        return HAS_JUMP_BOOST;
    }
}
