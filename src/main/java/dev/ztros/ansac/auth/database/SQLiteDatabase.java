package dev.ztros.ansac.auth.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.ztros.ansac.ANSACPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SQLiteDatabase implements AuthDatabase {

    private final ANSACPlugin plugin;
    private final HikariDataSource dataSource;

    public SQLiteDatabase(ANSACPlugin plugin) {
        this.plugin = plugin;
        this.dataSource = createDataSource();
    }

    private HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + plugin.getDataFolder().getAbsolutePath() + "/auth.db");
        config.setPoolName("ANSAC-AuthPool");
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10000);
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        return new HikariDataSource(config);
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            String sql = "CREATE TABLE IF NOT EXISTS ansac_auth (" +
                    "uuid TEXT PRIMARY KEY," +
                    "username TEXT NOT NULL UNIQUE," +
                    "password_hash TEXT NOT NULL," +
                    "ip TEXT," +
                    "last_login INTEGER," +
                    "last_join INTEGER" +
                    ")";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.execute();
                plugin.getLogger().info("认证数据库初始化完成。");
            } catch (SQLException e) {
                plugin.getLogger().severe("认证数据库初始化失败：" + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<String>> getPasswordHash(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT password_hash FROM ansac_auth WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString("password_hash"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取密码哈希失败：" + e.getMessage());
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Optional<String>> getPasswordHashByName(String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT password_hash FROM ansac_auth WHERE username = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, username);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString("password_hash"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("通过用户名获取密码哈希失败：" + e.getMessage());
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Boolean> isRegistered(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT 1 FROM ansac_auth WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("检查注册状态失败：" + e.getMessage());
            }
            return false;
        });
    }

    @Override
    public CompletableFuture<Boolean> isRegisteredByName(String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT 1 FROM ansac_auth WHERE username = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, username);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("通过用户名检查注册状态失败：" + e.getMessage());
            }
            return false;
        });
    }

    @Override
    public CompletableFuture<Void> savePassword(UUID uuid, String username, String passwordHash, String ip) {
        return CompletableFuture.runAsync(() -> {
            String sql = "INSERT INTO ansac_auth (uuid, username, password_hash, ip, last_login, last_join) " +
                    "VALUES (?, ?, ?, ?, ?, ?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET " +
                    "password_hash = excluded.password_hash, " +
                    "ip = excluded.ip, " +
                    "last_login = excluded.last_login, " +
                    "last_join = excluded.last_join";
            long now = System.currentTimeMillis();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.setString(2, username);
                stmt.setString(3, passwordHash);
                stmt.setString(4, ip);
                stmt.setLong(5, now);
                stmt.setLong(6, now);
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("保存密码失败：" + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Void> updateLogin(UUID uuid, String ip) {
        return CompletableFuture.runAsync(() -> {
            String sql = "UPDATE ansac_auth SET ip = ?, last_login = ? WHERE uuid = ?";
            long now = System.currentTimeMillis();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, ip);
                stmt.setLong(2, now);
                stmt.setString(3, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("更新登录信息失败：" + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Optional<String>> getLastIp(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT ip FROM ansac_auth WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(rs.getString("ip"));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("获取上次登录 IP 失败：" + e.getMessage());
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Void> removeRegistration(UUID uuid) {
        return CompletableFuture.runAsync(() -> {
            String sql = "DELETE FROM ansac_auth WHERE uuid = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, uuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("删除注册信息失败：" + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                plugin.getLogger().info("认证数据库连接已关闭。");
            }
        });
    }
}
