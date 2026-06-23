package dev.ztros.ansac.auth.proxy;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.auth.AuthService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class VelocityHandler implements PluginMessageListener {

    private final ANSACPlugin plugin;
    private final AuthService authService;

    public VelocityHandler(ANSACPlugin plugin, AuthService authService) {
        this.plugin = plugin;
        this.authService = authService;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!channel.equals("velocity:main")) return;
    }

    public void sendLoginStatus(String playerName, boolean authenticated) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            String status = authenticated ? "LOGIN_SUCCESS" : "LOGOUT";
            dos.writeUTF("ANSACLogin");
            byte[] messageBytes = status.getBytes(StandardCharsets.UTF_8);
            dos.writeShort(messageBytes.length);
            dos.write(messageBytes);

            byte[] payload = baos.toByteArray();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                p.sendPluginMessage(plugin, "velocity:main", payload);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to send Velocity login status: " + e.getMessage());
        }
    }
}
