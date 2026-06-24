package dev.ztros.ansac.auth.proxy;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.auth.AuthService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class BungeeCordHandler implements PluginMessageListener {

    private final ANSACPlugin plugin;
    private final AuthService authService;

    public BungeeCordHandler(ANSACPlugin plugin, AuthService authService) {
        this.plugin = plugin;
        this.authService = authService;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] data) {
        if (!channel.equals("BungeeCord")) return;
    }

    public void sendLoginStatus(String playerName, boolean authenticated) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeUTF("ForwardToPlayer");
            dos.writeUTF(playerName);
            dos.writeUTF("ANSACLogin");
            String status = authenticated ? "LOGIN_SUCCESS" : "LOGOUT";
            dos.writeUTF(status);

            byte[] payload = baos.toByteArray();
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                p.sendPluginMessage(plugin, "BungeeCord", payload);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("BungeeCord 发送登录状态失败：" + e.getMessage());
        }
    }
}
