package dev.ztros.anspackage dev.ztros.ansac.auth.database;

import com.zaxxer.hikari.HikariConfig;
import com.zpackage dev.ztros.ansac.auth.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.ztros.ansac.ANSACPlugin;

import java.sql.Connection;
import javapackage dev.ztros.ansac.auth.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.ztros.ansac.ANSACPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javapackage dev.ztros.ansac.auth.database;

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

    public SQLiteDatabase(package dev.ztros.ansac.auth.database;

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

    private HikariDataSource createDataSourcepackage dev.ztros.ansac.auth.database;

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
        HikariConfig config = new Hpackage dev.ztros.ansac.auth.database;

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
        config.setJdbcUrl("jdbc:sqlite:" + plugin.getDataFolder().package dev.ztros.ansac.auth.database;

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
        config.setConnectionTimeoutpackage dev.ztros.ansac.auth.database;

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
        config.addpackage dev.ztros.ansac.auth.database;

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
                    "uuid TEXT PRIMARYpackage dev.ztros.ansac.auth.database;

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
                    "lastpackage dev.ztros.ansac.auth.database;

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
            try (Connection conn = dataSourcepackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().infopackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + epackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @Override
    public CompletableFuture<Optionalpackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<String>> getPasswordHash(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT password_hash FROM ansac_auth WHEREpackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<String>> getPasswordHash(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT password_hash FROM ansac_auth WHERE uuid = ?";
            try (Connection conn = dataSourcepackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hashpackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
            }
            return Optional.empty();
        });
    }

    @Overridepackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Optional<String>> getPasswordHashByName(String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT passwordpackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Optional<String>> getPasswordHashByName(String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT password_hash FROM ansac_auth WHERE username = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatementpackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
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
                try (ResultSet rspackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
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
                        return Optional.of(rs.getString("passwordpackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
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
                plugin.getLogger().severepackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash by name: " +package dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash by name: " + e.getMessage());
            }
            return Optional.empty();
        });
    }

    @Override
    publicpackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash by name: " + e.getMessage());
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Boolean> isRegistered(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            Stringpackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash by name: " + e.getMessage());
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<Boolean> isRegistered(UUID uuid) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT 1 FROM ansac_auth WHERE uuid = ?";
            try (Connection conn =package dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash by name: " + e.getMessage());
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
                stmt.setString(1, uuid.toStringpackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash by name: " + e.getMessage());
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
                plugin.getLogger().package dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash by name: " + e.getMessage());
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
                plugin.getLogger().severe("Error checking registration: " + e.getMessage());
            }
            return false;
        });
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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash by name: " + e.getMessage());
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
                plugin.getLogger().severe("Error checking registration: " + e.getMessage());
            }
            return false;
        });
    }

    @Override
    public CompletableFuture<Boolean> isRegisteredByName(String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sqlpackage dev.ztros.ansac.auth.database;

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
                plugin.getLogger().info("Auth database initialized successfully.");
            } catch (SQLException e) {
                plugin.getLogger().severe("Failed to initialize auth database: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching password hash by name: " + e.getMessage());
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
                plugin.getLogger().severe("Error checking registration: " + e.getMessage());
            }
            return false;
        });
    }

    @Override
    public CompletableFuture<Boolean> isRegisteredByName(String username) {
        return CompletableFuture.supplyAsync(() -> {
            String sql = "SELECT 1 FROM ansac_auth WHERE username = ?";
            try (Connection conn = dataSource