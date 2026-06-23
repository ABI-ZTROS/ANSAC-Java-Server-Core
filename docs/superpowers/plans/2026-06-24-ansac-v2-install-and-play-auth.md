# ANSAC v2.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make ANSAC install-and-play by bundling PacketEvents, and add a full-featured login module (password auth, SQLite, BCrypt, login restrictions, auto-kick, same-IP login, BungeeCord/Velocity proxy support) deeply integrated with the anti-cheat system.

**Architecture:** Three-part change: (1) Bundle PacketEvents into the JAR via Shadow (compileOnly -> implementation + relocate), (2) Add `dev.ztros.ansac.auth` package as an independent module with AuthService facade, (3) Integrate auth state into anti-cheat checks so unauthenticated players are skipped. All new code follows existing patterns: Adventure Component API for messages, SchedulerAdapter for Folia thread safety, ConcurrentHashMap for thread-safe state.

**Tech Stack:** Java 21, Folia API 1.21.4, FoliaLib 0.5.1, PacketEvents 2.12.2, Shadow 9.0.0-beta12, jBCrypt 0.4.4, HikariCP 5.1.0, SQLite JDBC, Adventure Component API

---

## File Structure Map

### Files to CREATE (new files)

| File | Responsibility |
|------|---------------|
| `src/main/java/dev/ztros/ansac/auth/AuthService.java` | Auth facade: login state management, API for anti-cheat integration |
| `src/main/java/dev/ztros/ansac/auth/AuthConfig.java` | Load auth section from config.yml |
| `src/main/java/dev/ztros/ansac/auth/AuthCommand.java` | /login, /register, /changepassword, /logout commands |
| `src/main/java/dev/ztros/ansac/auth/AuthListener.java` | Login-pre restrictions: movement, interaction, chat, commands, inventory |
| `src/main/java/dev/ztros/ansac/auth/database/AuthDatabase.java` | Database interface |
| `src/main/java/dev/ztros/ansac/auth/database/SQLiteDatabase.java` | SQLite implementation with HikariCP |
| `src/main/java/dev/ztros/ansac/auth/crypto/BCryptHasher.java` | BCrypt password hashing wrapper |
| `src/main/java/dev/ztros/ansac/auth/session/LoginSession.java` | Per-player login session data |
| `src/main/java/dev/ztros/ansac/auth/session/SessionManager.java` | Session lifecycle: timeout kick, same-IP auto-login |
| `src/main/java/dev/ztros/ansac/auth/proxy/ProxyManager.java` | Proxy server communication manager |
| `src/main/java/dev/ztros/ansac/auth/proxy/BungeeCordHandler.java` | BungeeCord plugin message channel handler |
| `src/main/java/dev/ztros/ansac/auth/proxy/VelocityHandler.java` | Velocity plugin message channel handler |

### Files to MODIFY (existing files)

| File | Change |
|------|--------|
| `build.gradle` | PacketEvents compileOnly->implementation, add jbcrypt+hikari+sqlite deps, add relocate rules |
| `src/main/resources/plugin.yml` | Remove softdepend, add login/register/changepassword/logout commands |
| `src/main/resources/config.yml` | Add `auth:` configuration section |
| `src/main/java/dev/ztros/ansac/ANSACPlugin.java` | Add AuthService field, init in onEnable, shutdown in onDisable, reload support |
| `src/main/java/dev/ztros/ansac/checks/Check.java` | Add auth check in process() callers (via CheckManager) |
| `src/main/java/dev/ztros/ansac/checks/CheckManager.java` | Add auth guard in startCheckTask() and processPlayer() |
| `src/main/java/dev/ztros/ansac/listeners/PacketListener.java` | Add auth guard in onPacketReceive() |
| `src/main/java/dev/ztros/ansac/listeners/PlayerListener.java` | Add auth integration in onPlayerJoin/onPlayerQuit |
| `src/main/java/dev/ztros/ansac/ANSACCommand.java` | Add auth status info to /ansac status output |

---

### Task 1: Bundle PacketEvents (Install-and-Play)

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/main/java/dev/ztros/ansac/ANSACPlugin.java`

- [ ] **Step 1: Update build.gradle dependencies**

Replace the PacketEvents `compileOnly` lines with `implementation`, and add new dependencies:

```groovy
dependencies {
    // Server API (provided at runtime) - Folia 1.21.4
    compileOnly 'dev.folia:folia-api:1.21.4-R0.1-SNAPSHOT'

    // PacketEvents - bundled into the plugin JAR via Shadow (install-and-play)
    implementation 'com.github.retrooper:packetevents-api:2.12.2'
    implementation 'com.github.retrooper:packetevents-spigot:2.12.2'

    // FoliaLib - bundled into the plugin JAR via Shadow
    implementation 'com.tcoded:FoliaLib:0.5.1'

    // Auth module dependencies - bundled via Shadow
    implementation 'org.mindrot:jbcrypt:0.4.4'
    implementation 'com.zaxxer:HikariCP:5.1.0'
    implementation 'org.xerial:sqlite-jdbc:3.45.2.0'

    // Lombok - compile time only
    compileOnly 'org.projectlombok:lombok:1.18.30'
    annotationProcessor 'org.projectlombok:lombok:1.18.30'
}
```

- [ ] **Step 2: Update shadowJar block with relocate rules**

Add PacketEvents, jBCrypt, HikariCP, and SQLite relocate rules:

```groovy
shadowJar {
    archiveClassifier = ''
    archiveFileName = 'ANSAC-AntiCheat-' + project.version + '.jar'

    // Relocate FoliaLib to prevent conflicts
    relocate 'com.tcoded.folialib', 'dev.ztros.ansac.lib.folialib'

    // Relocate PacketEvents to prevent conflicts
    relocate 'io.github.retrooper.packetevents', 'dev.ztros.ansac.lib.packetevents'
    relocate 'com.github.retrooper.packetevents', 'dev.ztros.ansac.lib.packetevents'

    // Relocate auth dependencies to prevent conflicts
    relocate 'org.mindrot.jbcrypt', 'dev.ztros.ansac.lib.jbcrypt'
    relocate 'com.zaxxer.hikari', 'dev.ztros.ansac.lib.hikari'
    relocate 'org.sqlite', 'dev.ztros.ansac.lib.sqlite'

    // Exclude unnecessary metadata
    exclude 'META-INF/**'
}
```

- [ ] **Step 3: Update plugin.yml - remove softdepend**

Remove the `softdepend` section since PacketEvents is now bundled:

```yaml
name: ANSAC
version: ${version}
main: dev.ztros.ansac.ANSACPlugin
description: 'Advanced Network Security Anti-Cheat for Folia'
author: ZTROS-Team
website: https://github.com/ABI-ZTROS/ANSAC-Java-Server-Core
folia-supported: true
api-version: '1.21'

permissions:
  ansac.bypass:
    description: Bypass all ANSAC checks
    default: op
  ansac.admin:
    description: Access to ANSAC admin commands
    default: op
  ansac.alerts:
    description: Receive ANSAC alert notifications
    default: op
  ansac.command.reload:
    description: Reload ANSAC configuration
    default: op
  ansac.command.status:
    description: View ANSAC status
    default: op
```

- [ ] **Step 4: Update ANSACPlugin.onEnable() - remove optional PacketEvents check**

Replace the conditional PacketEvents initialization with direct initialization:

```java
// Register packet listener (PacketEvents is now bundled)
new PacketListener(this).register();
getLogger().info("PacketEvents integration enabled.");
```

Remove the old `if/else` block that checked for PacketEvents availability.

- [ ] **Step 5: Build and verify**

Run: `cd /workspace/ANSAC-Java-Server-Core && gradle fatJar --no-daemon`
Expected: BUILD SUCCESSFUL, JAR contains relocated PacketEvents classes.

- [ ] **Step 6: Commit**

```bash
git add build.gradle src/main/resources/plugin.yml src/main/java/dev/ztros/ansac/ANSACPlugin.java
git commit -m "feat: bundle PacketEvents for install-and-play experience"
```

---

### Task 2: Create Auth Config and Database Layer

**Files:**
- Create: `src/main/java/dev/ztros/ansac/auth/AuthConfig.java`
- Create: `src/main/java/dev/ztros/ansac/auth/database/AuthDatabase.java`
- Create: `src/main/java/dev/ztros/ansac/auth/database/SQLiteDatabase.java`
- Create: `src/main/java/dev/ztros/ansac/auth/crypto/BCryptHasher.java`
- Modify: `src/main/resources/config.yml`

- [ ] **Step 1: Add auth section to config.yml**

Append the following section at the end of config.yml:

```yaml
# ==========================================
# Authentication Module
# ==========================================

auth:
  # Enable or disable the authentication module
  enabled: true

  # Login timeout in seconds (auto-kick if not logged in)
  login-timeout: 120

  # Same-IP auto-login timeout in minutes
  # If another account from the same IP logged in within this time, auto-login
  same-ip-timeout: 5

  # Auto-kick players who haven't logged in within the timeout
  auto-kick: true

  # Restrict movement before login
  restrict-movement: true

  # Restrict block/entity interaction before login
  restrict-interaction: true

  # Restrict chat before login
  restrict-chat: true

  # Restrict commands before login (whitelist excluded)
  restrict-commands: true

  # Restrict inventory operations before login
  restrict-inventory: true

  # Restrict item pickup/drop before login
  restrict-items: true

  # Command whitelist (regex patterns allowed before login)
  command-whitelist:
    - "^/(login|l|register|reg|changepassword|changepwd|logout)$"

  # Proxy server settings
  proxy:
    # Enable proxy server communication
    enabled: false
    # Proxy type: auto, bungeecord, velocity
    type: auto
```

- [ ] **Step 2: Create AuthConfig.java**

```java
package dev.ztros.ansac.auth;

import dev.ztros.ansac.ANSACPlugin;
import lombok.Getter;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Loads and caches authentication module configuration from config.yml.
 */
public class AuthConfig {

    @Getter
    private final boolean enabled;

    @Getter
    private final int loginTimeout;

    @Getter
    private final int sameIpTimeout;

    @Getter
    private final boolean autoKick;

    @Getter
    private final boolean restrictMovement;

    @Getter
    private final boolean restrictInteraction;

    @Getter
    private final boolean restrictChat;

    @Getter
    private final boolean restrictCommands;

    @Getter
    private final boolean restrictInventory;

    @Getter
    private final boolean restrictItems;

    @Getter
    private final List<Pattern> commandWhitelist;

    @Getter
    private final boolean proxyEnabled;

    @Getter
    private final String proxyType;

    public AuthConfig(ANSACPlugin plugin) {
        load(plugin);
    }

    public void load(ANSACPlugin plugin) {
        // Use local variables for @Getter fields - reload by re-reading config
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

        // Compile regex patterns for command whitelist
        this.commandWhitelist = config.getStringList("auth.command-whitelist").stream()
                .map(Pattern::compile)
                .collect(Collectors.toList());
    }

    /**
     * Check if a command string matches the whitelist.
     */
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
```

- [ ] **Step 3: Create AuthDatabase.java interface**

```java
package dev.ztros.ansac.auth.database;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Database interface for authentication data storage.
 * All operations are async (return CompletableFuture) to avoid blocking the server thread.
 */
public interface AuthDatabase {

    /**
     * Initialize the database (create tables if needed).
     */
    CompletableFuture<Void> initialize();

    /**
     * Get the stored password hash for a player.
     */
    CompletableFuture<Optional<String>> getPasswordHash(UUID uuid);

    /**
     * Get the stored password hash by username.
     */
    CompletableFuture<Optional<String>> getPasswordHashByName(String username);

    /**
     * Check if a player is registered.
     */
    CompletableFuture<Boolean> isRegistered(UUID uuid);

    /**
     * Check if a username is registered.
     */
    CompletableFuture<Boolean> isRegisteredByName(String username);

    /**
     * Save or update a player's password hash.
     */
    CompletableFuture<Void> savePassword(UUID uuid, String username, String passwordHash, String ip);

    /**
     * Update the last login time and IP.
     */
    CompletableFuture<Void> updateLogin(UUID uuid, String ip);

    /**
     * Get the last login IP for a player.
     */
    CompletableFuture<Optional<String>> getLastIp(UUID uuid);

    /**
     * Remove a player's registration data.
     */
    CompletableFuture<Void> removeRegistration(UUID uuid);

    /**
     * Close the database connection.
     */
    CompletableFuture<Void> shutdown();
}
```

- [ ] **Step 4: Create SQLiteDatabase.java**

```java
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

/**
 * SQLite implementation of AuthDatabase using HikariCP connection pool.
 * All operations run on async threads via CompletableFuture.supplyAsync.
 */
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
        // SQLite optimizations
        config.addDataSourceProperty("journal_mode", "WAL");
        config.addDataSourceProperty("synchronous", "NORMAL");
        return new HikariDataSource(config);
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                CREATE TABLE IF NOT EXISTS ansac_auth (
                    uuid TEXT PRIMARY KEY,
                    username TEXT NOT NULL UNIQUE,
                    password_hash TEXT NOT NULL,
                    ip TEXT,
                    last_login INTEGER,
                    last_join INTEGER
                )
                """;
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
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, username);
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next();
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Error checking registration by name: " + e.getMessage());
            }
            return false;
        });
    }

    @Override
    public CompletableFuture<Void> savePassword(UUID uuid, String username, String passwordHash, String ip) {
        return CompletableFuture.runAsync(() -> {
            String sql = """
                INSERT INTO ansac_auth (uuid, username, password_hash, ip, last_login, last_join)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    password_hash = excluded.password_hash,
                    ip = excluded.ip,
                    last_login = excluded.last_login,
                    last_join = excluded.last_join
                """;
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
                plugin.getLogger().severe("Error saving password: " + e.getMessage());
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
                plugin.getLogger().severe("Error updating login: " + e.getMessage());
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
                plugin.getLogger().severe("Error fetching last IP: " + e.getMessage());
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
                plugin.getLogger().severe("Error removing registration: " + e.getMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                plugin.getLogger().info("Auth database connection closed.");
            }
        });
    }
}
```

- [ ] **Step 5: Create BCryptHasher.java**

```java
package dev.ztros.ansac.auth.crypto;

import org.mindrot.jbcrypt.BCrypt;

/**
 * BCrypt password hashing utility.
 * Uses jBCrypt library for secure password storage.
 */
public class BCryptHasher {

    /**
     * Hash a plaintext password using BCrypt.
     *
     * @param plaintext the raw password
     * @return the BCrypt hash string
     */
    public static String hash(String plaintext) {
        return BCrypt.hashpw(plaintext, BCrypt.gensalt());
    }

    /**
     * Verify a plaintext password against a BCrypt hash.
     *
     * @param plaintext the raw password
     * @param hash      the stored BCrypt hash
     * @return true if the password matches
     */
    public static boolean verify(String plaintext, String hash) {
        try {
            return BCrypt.checkpw(plaintext, hash);
        } catch (Exception e) {
            return false;
        }
    }
}
```

- [ ] **Step 6: Compile and verify**

Run: `cd /workspace/ANSAC-Java-Server-Core && gradle compileJava --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/ztros/ansac/auth/AuthConfig.java \
        src/main/java/dev/ztros/ansac/auth/database/AuthDatabase.java \
        src/main/java/dev/ztros/ansac/auth/database/SQLiteDatabase.java \
        src/main/java/dev/ztros/ansac/auth/crypto/BCryptHasher.java \
        src/main/resources/config.yml
git commit -m "feat(auth): add config, database layer, and BCrypt hasher"
```

---

### Task 3: Create Session Manager and Login Session

**Files:**
- Create: `src/main/java/dev/ztros/ansac/auth/session/LoginSession.java`
- Create: `src/main/java/dev/ztros/ansac/auth/session/SessionManager.java`

- [ ] **Step 1: Create LoginSession.java**

```java
package dev.ztros.ansac.auth.session;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Per-player login session data.
 * Tracks authentication state, timing, and IP for same-IP auto-login.
 */
public class LoginSession {

    @Getter
    private final UUID uuid;

    @Getter
    @Setter
    private String playerName;

    @Getter
    @Setter
    private String ip;

    @Getter
    @Setter
    private boolean authenticated;

    @Getter
    @Setter
    private boolean registered;

    @Getter
    @Setter
    private long joinTime;

    @Getter
    @Setter
    private long loginTime;

    @Getter
    @Setter
    private int failedAttempts;

    public LoginSession(UUID uuid, String playerName, String ip) {
        this.uuid = uuid;
        this.playerName = playerName;
        this.ip = ip;
        this.joinTime = System.currentTimeMillis();
        this.loginTime = 0;
        this.authenticated = false;
        this.registered = false;
        this.failedAttempts = 0;
    }

    /**
     * Get the elapsed time since join in seconds.
     */
    public long getElapsedSeconds() {
        return (System.currentTimeMillis() - joinTime) / 1000;
    }

    /**
     * Mark this session as authenticated.
     */
    public void markAuthenticated() {
        this.authenticated = true;
        this.loginTime = System.currentTimeMillis();
    }

    /**
     * Increment failed login attempt counter.
     */
    public void incrementFailedAttempts() {
        this.failedAttempts++;
    }
}
```

- [ ] **Step 2: Create SessionManager.java**

```java
package dev.ztros.ansac.auth.session;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.auth.AuthConfig;
import dev.ztros.ansac.auth.database.AuthDatabase;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages login sessions for all online players.
 * Handles: session creation/removal, auto-kick timeout, same-IP auto-login.
 * Uses SchedulerAdapter for Folia-compatible scheduling.
 */
public class SessionManager {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final ANSACPlugin plugin;
    private final AuthConfig authConfig;
    private final AuthDatabase database;
    private final Map<UUID, LoginSession> sessions = new ConcurrentHashMap<>();

    // Track authenticated IPs for same-IP auto-login: IP -> last login timestamp
    private final Map<String, Long> authenticatedIps = new ConcurrentHashMap<>();

    public SessionManager(ANSACPlugin plugin, AuthConfig authConfig, AuthDatabase database) {
        this.plugin = plugin;
        this.authConfig = authConfig;
        this.database = database;
        startAutoKickTask();
        startIpCleanupTask();
    }

    /**
     * Create a new login session for a joining player.
     */
    public LoginSession createSession(UUID uuid, String playerName, String ip) {
        LoginSession session = new LoginSession(uuid, playerName, ip);
        sessions.put(uuid, session);

        // Check if player is registered
        database.isRegistered(uuid).thenAccept(registered -> {
            session.setRegistered(registered);
            if (registered) {
                // Check same-IP auto-login
                checkSameIpAutoLogin(session);
            }
        });

        return session;
    }

    /**
     * Remove a player's login session.
     */
    public void removeSession(UUID uuid) {
        sessions.remove(uuid);
    }

    /**
     * Get a player's login session.
     */
    public LoginSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    /**
     * Check if a player is authenticated.
     */
    public boolean isAuthenticated(UUID uuid) {
        LoginSession session = sessions.get(uuid);
        return session != null && session.isAuthenticated();
    }

    /**
     * Mark a player as authenticated.
     */
    public void markAuthenticated(UUID uuid) {
        LoginSession session = sessions.get(uuid);
        if (session != null) {
            session.markAuthenticated();
            // Record this IP as authenticated for same-IP auto-login
            authenticatedIps.put(session.getIp(), System.currentTimeMillis());
            // Update database
            database.updateLogin(uuid, session.getIp());
        }
    }

    /**
     * Check if same-IP auto-login should apply.
     * If another account from the same IP successfully logged in within the timeout window,
     * auto-authenticate this player.
     */
    private void checkSameIpAutoLogin(LoginSession session) {
        if (session.isAuthenticated()) return;

        Long lastAuthTime = authenticatedIps.get(session.getIp());
        if (lastAuthTime != null) {
            long elapsedMinutes = (System.currentTimeMillis() - lastAuthTime) / (60 * 1000);
            if (elapsedMinutes < authConfig.getSameIpTimeout()) {
                // Auto-login via same IP
                session.markAuthenticated();
                var player = plugin.getServer().getPlayer(session.getUuid());
                if (player != null && player.isOnline()) {
                    plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                        player.sendMessage(MINI_MESSAGE.deserialize(
                            "<gray>[<aqua>ANSAC</gray>] <green>Same-IP auto-login successful."
                        ));
                    });
                }
                plugin.getLogger().info("Same-IP auto-login: " + session.getPlayerName() + " from " + session.getIp());
                return;
            }
        }

        // Send login prompt
        var promptPlayer = plugin.getServer().getPlayer(session.getUuid());
        if (promptPlayer != null && promptPlayer.isOnline()) {
            plugin.getSchedulerAdapter().runAtEntity(promptPlayer, () -> {
                promptPlayer.sendMessage(MINI_MESSAGE.deserialize(
                    "<gray>[<aqua>ANSAC</gray>] <yellow>Please login with <white>/login <password><yellow>."
                ));
            });
        }
    }

    /**
     * Start the auto-kick task for unauthenticated players.
     */
    private void startAutoKickTask() {
        plugin.getSchedulerAdapter().runTimerAsync(() -> {
            if (!authConfig.isAutoKick()) return;

            int timeout = authConfig.getLoginTimeout();
            for (LoginSession session : sessions.values()) {
                if (!session.isAuthenticated() && session.getElapsedSeconds() >= timeout) {
                    var player = plugin.getServer().getPlayer(session.getUuid());
                    if (player != null && player.isOnline()) {
                        plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                            player.kick(MINI_MESSAGE.deserialize(
                                "<red>[ANSAC] <gray>Login timed out. Please reconnect."
                            ));
                        });
                        plugin.getLogger().info("Auto-kicked unauthenticated player: " + session.getPlayerName());
                    }
                }
            }
        }, 5L, 5L, TimeUnit.SECONDS);
    }

    /**
     * Periodically clean up expired IP entries from the same-IP map.
     */
    private void startIpCleanupTask() {
        long timeoutMillis = authConfig.getSameIpTimeout() * 60 * 1000L;
        plugin.getSchedulerAdapter().runTimerAsync(() -> {
            long now = System.currentTimeMillis();
            authenticatedIps.entrySet().removeIf(entry -> (now - entry.getValue()) > timeoutMillis);
        }, 60L, 60L, TimeUnit.SECONDS);
    }

    /**
     * Shutdown the session manager.
     */
    public void shutdown() {
        sessions.clear();
        authenticatedIps.clear();
    }
}
```

- [ ] **Step 3: Compile and verify**

Run: `cd /workspace/ANSAC-Java-Server-Core && gradle compileJava --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/dev/ztros/ansac/auth/session/LoginSession.java \
        src/main/java/dev/ztros/ansac/auth/session/SessionManager.java
git commit -m "feat(auth): add session manager with auto-kick and same-IP login"
```

---

### Task 4: Create AuthService (Core Facade)

**Files:**
- Create: `src/main/java/dev/ztros/ansac/auth/AuthService.java`

- [ ] **Step 1: Create AuthService.java**

```java
package dev.ztros.ansac.auth;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.auth.crypto.BCryptHasher;
import dev.ztros.ansac.auth.database.AuthDatabase;
import dev.ztros.ansac.auth.database.SQLiteDatabase;
import dev.ztros.ansac.auth.proxy.ProxyManager;
import dev.ztros.ansac.auth.session.LoginSession;
import dev.ztros.ansac.auth.session.SessionManager;
import lombok.Getter;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Authentication service facade.
 * Central entry point for the auth module.
 * Anti-cheat system calls isAuthenticated(UUID) to check login state.
 */
public class AuthService {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    @Getter
    private final ANSACPlugin plugin;

    @Getter
    private final AuthConfig authConfig;

    private final AuthDatabase database;
    private final SessionManager sessionManager;
    private ProxyManager proxyManager;

    public AuthService(ANSACPlugin plugin) {
        this.plugin = plugin;
        this.authConfig = new AuthConfig(plugin);
        this.database = new SQLiteDatabase(plugin);
        this.sessionManager = new SessionManager(plugin, authConfig, database);

        if (authConfig.isProxyEnabled()) {
            this.proxyManager = new ProxyManager(plugin, this);
        }

        // Initialize database asynchronously
        database.initialize().thenRun(() -> {
            plugin.getLogger().info("Authentication module initialized.");
        });
    }

    /**
     * Check if a player is authenticated.
     * This is the primary API called by the anti-cheat system.
     */
    public boolean isAuthenticated(UUID uuid) {
        return sessionManager.isAuthenticated(uuid);
    }

    /**
     * Check if the auth module is enabled.
     */
    public boolean isEnabled() {
        return authConfig.isEnabled();
    }

    /**
     * Handle player join: create login session.
     */
    public void handlePlayerJoin(UUID uuid, String playerName, String ip) {
        if (!authConfig.isEnabled()) return;
        sessionManager.createSession(uuid, playerName, ip);
    }

    /**
     * Handle player quit: remove login session.
     */
    public void handlePlayerQuit(UUID uuid) {
        if (!authConfig.isEnabled()) return;
        sessionManager.removeSession(uuid);
    }

    /**
     * Attempt to register a player with the given password.
     * Returns a result message Component.
     */
    public CompletableFuture<String> register(UUID uuid, String playerName, String password, String confirmPassword) {
        CompletableFuture<String> future = new CompletableFuture<>();

        if (password == null || password.length() < 4) {
            future.complete("<red>[ANSAC] Password must be at least 4 characters.");
            return future;
        }

        if (!password.equals(confirmPassword)) {
            future.complete("<red>[ANSAC] Passwords do not match.");
            return future;
        }

        if (password.length() > 64) {
            future.complete("<red>[ANSAC] Password is too long (max 64 characters).");
            return future;
        }

        LoginSession session = sessionManager.getSession(uuid);
        if (session == null) {
            future.complete("<red>[ANSAC] Session not found. Please reconnect.");
            return future;
        }

        database.isRegisteredByName(playerName).thenAccept(registered -> {
            if (registered) {
                future.complete("<red>[ANSAC] You are already registered. Use <white>/login <password><red>.");
                return;
            }

            String hash = BCryptHasher.hash(password);
            database.savePassword(uuid, playerName, hash, session.getIp()).thenRun(() -> {
                session.setRegistered(true);
                sessionManager.markAuthenticated(uuid);
                future.complete("<green>[ANSAC] <gray>Registration successful. You are now logged in.");
            });
        });

        return future;
    }

    /**
     * Attempt to login a player with the given password.
     * Returns a result message Component.
     */
    public CompletableFuture<String> login(UUID uuid, String password) {
        CompletableFuture<String> future = new CompletableFuture<>();

        LoginSession session = sessionManager.getSession(uuid);
        if (session == null) {
            future.complete("<red>[ANSAC] Session not found. Please reconnect.");
            return future;
        }

        if (session.isAuthenticated()) {
            future.complete("<yellow>[ANSAC] You are already logged in.");
            return future;
        }

        if (!session.isRegistered()) {
            future.complete("<red>[ANSAC] You are not registered. Use <white>/register <password> <confirm><red>.");
            return future;
        }

        database.getPasswordHash(uuid).thenAccept(hashOpt -> {
            if (hashOpt.isEmpty()) {
                future.complete("<red>[ANSAC] Account data not found. Please contact an admin.");
                return;
            }

            if (BCryptHasher.verify(password, hashOpt.get())) {
                sessionManager.markAuthenticated(uuid);
                future.complete("<green>[ANSAC] <gray>Login successful. Welcome back!");
            } else {
                session.incrementFailedAttempts();
                int attempts = session.getFailedAttempts();
                if (attempts >= 5) {
                    // Kick after 5 failed attempts
                    var player = plugin.getServer().getPlayer(uuid);
                    if (player != null && player.isOnline()) {
                        plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                            player.kick(MINI_MESSAGE.deserialize(
                                "<red>[ANSAC] <gray>Too many failed login attempts."
                            ));
                        });
                    }
                    future.complete("<red>[ANSAC] Too many failed login attempts.");
                } else {
                    future.complete("<red>[ANSAC] <gray>Incorrect password. Attempts remaining: <white>" + (5 - attempts));
                }
            }
        });

        return future;
    }

    /**
     * Change a player's password.
     */
    public CompletableFuture<String> changePassword(UUID uuid, String oldPassword, String newPassword) {
        CompletableFuture<String> future = new CompletableFuture<>();

        if (newPassword == null || newPassword.length() < 4) {
            future.complete("<red>[ANSAC] New password must be at least 4 characters.");
            return future;
        }

        if (newPassword.length() > 64) {
            future.complete("<red>[ANSAC] New password is too long (max 64 characters).");
            return future;
        }

        database.getPasswordHash(uuid).thenAccept(hashOpt -> {
            if (hashOpt.isEmpty()) {
                future.complete("<red>[ANSAC] Account data not found.");
                return;
            }

            if (!BCryptHasher.verify(oldPassword, hashOpt.get())) {
                future.complete("<red>[ANSAC] Old password is incorrect.");
                return;
            }

            String newHash = BCryptHasher.hash(newPassword);
            LoginSession session = sessionManager.getSession(uuid);
            String ip = session != null ? session.getIp() : "unknown";
            String name = session != null ? session.getPlayerName() : "unknown";

            database.savePassword(uuid, name, newHash, ip).thenRun(() -> {
                future.complete("<green>[ANSAC] <gray>Password changed successfully.");
            });
        });

        return future;
    }

    /**
     * Logout a player (mark as unauthenticated).
     */
    public CompletableFuture<String> logout(UUID uuid) {
        CompletableFuture<String> future = new CompletableFuture<>();

        LoginSession session = sessionManager.getSession(uuid);
        if (session == null) {
            future.complete("<red>[ANSAC] Session not found.");
            return future;
        }

        if (!session.isAuthenticated()) {
            future.complete("<yellow>[ANSAC] You are not logged in.");
            return future;
        }

        session.setAuthenticated(false);
        future.complete("<yellow>[ANSAC] <gray>You have been logged out. Use <white>/login <password><gray> to log in again.");
        return future;
    }

    /**
     * Reload auth configuration.
     */
    public void reload() {
        authConfig.load(plugin);
        plugin.getLogger().info("Auth configuration reloaded.");
    }

    /**
     * Shutdown auth service.
     */
    public void shutdown() {
        sessionManager.shutdown();
        database.shutdown().join();
        if (proxyManager != null) {
            proxyManager.shutdown();
        }
        plugin.getLogger().info("Authentication module shut down.");
    }
}
```

- [ ] **Step 2: Compile and verify**

Run: `cd /workspace/ANSAC-Java-Server-Core && gradle compileJava --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/ztros/ansac/auth/AuthService.java
git commit -m "feat(auth): add AuthService facade with register/login/changepassword/logout"
```

---

### Task 5: Create Auth Command Handler

**Files:**
- Create: `src/main/java/dev/ztros/ansac/auth/AuthCommand.java`

- [ ] **Step 1: Create AuthCommand.java**

```java
package dev.ztros.ansac.auth;

import dev.ztros.ansac.ANSACPlugin;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/**
 * Handles authentication commands: /login, /register, /changepassword, /logout.
 * Uses Adventure MiniMessage for messages (Paper/Folia 1.21+).
 * All database operations are async to avoid blocking.
 */
public class AuthCommand implements CommandExecutor {

    private final ANSACPlugin plugin;
    private final AuthService authService;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public AuthCommand(ANSACPlugin plugin, AuthService authService) {
        this.plugin = plugin;
        this.authService = authService;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(miniMessage.deserialize("<red>[ANSAC] This command can only be used by players."));
            return true;
        }

        if (!authService.isEnabled()) {
            player.sendMessage(miniMessage.deserialize("<gray>[<aqua>ANSAC</gray>] <yellow>Authentication is disabled on this server."));
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "login", "l" -> handleLogin(player, args);
            case "register", "reg" -> handleRegister(player, args);
            case "changepassword", "changepwd" -> handleChangePassword(player, args);
            case "logout" -> handleLogout(player);
            default -> player.sendMessage(miniMessage.deserialize("<red>[ANSAC] Unknown command."));
        }

        return true;
    }

    private void handleLogin(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(miniMessage.deserialize("<red>[ANSAC] Usage: /login <password>"));
            return;
        }

        String password = args[0];
        authService.login(player.getUniqueId(), password).thenAccept(message -> {
            plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                player.sendMessage(miniMessage.deserialize(message));
            });
        });
    }

    private void handleRegister(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(miniMessage.deserialize("<red>[ANSAC] Usage: /register <password> <confirm>"));
            return;
        }

        String password = args[0];
        String confirm = args[1];
        authService.register(player.getUniqueId(), player.getName(), password, confirm).thenAccept(message -> {
            plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                player.sendMessage(miniMessage.deserialize(message));
            });
        });
    }

    private void handleChangePassword(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(miniMessage.deserialize("<red>[ANSAC] Usage: /changepassword <old> <new>"));
            return;
        }

        String oldPassword = args[0];
        String newPassword = args[1];
        authService.changePassword(player.getUniqueId(), oldPassword, newPassword).thenAccept(message -> {
            plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                player.sendMessage(miniMessage.deserialize(message));
            });
        });
    }

    private void handleLogout(Player player) {
        authService.logout(player.getUniqueId()).thenAccept(message -> {
            plugin.getSchedulerAdapter().runAtEntity(player, () -> {
                player.sendMessage(miniMessage.deserialize(message));
            });
        });
    }
}
```

- [ ] **Step 2: Compile and verify**

Run: `cd /workspace/ANSAC-Java-Server-Core && gradle compileJava --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/ztros/ansac/auth/AuthCommand.java
git commit -m "feat(auth): add auth command handler for login/register/changepwd/logout"
```

---

### Task 6: Create Auth Listener (Login-Pre Restrictions)

**Files:**
- Create: `src/main/java/dev/ztros/ansac/auth/AuthListener.java`

- [ ] **Step 1: Create AuthListener.java**

```java
package dev.ztros.ansac.auth;

import dev.ztros.ansac.ANSACPlugin;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;

/**
 * Restricts player actions before authentication.
 * All checks go through AuthService.isAuthenticated() to determine if a player is logged in.
 * Uses EventPriority.LOW to run before other plugins' handlers.
 */
public class AuthListener implements Listener {

    private final ANSACPlugin plugin;
    private final AuthService authService;
    private final AuthConfig authConfig;

    public AuthListener(ANSACPlugin plugin, AuthService authService) {
        this.plugin = plugin;
        this.authService = authService;
        this.authConfig = authService.getAuthConfig();
    }

    /**
     * Block movement before login.
     * Allows: looking around (rotation only), natural falling.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!authConfig.isRestrictMovement()) return;

        Player player = event.getPlayer();
        if (authService.isAuthenticated(player.getUniqueId())) return;

        Location from = event.getFrom();
        Location to = event.getTo();

        // Allow rotation-only and natural falling
        if (from.getX() == to.getX() && from.getZ() == to.getZ() && from.getY() - to.getY() >= 0.0) {
            return;
        }

        event.setCancelled(true);
    }

    /**
     * Block chat before login.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        if (!authConfig.isRestrictChat()) return;

        if (!authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Block commands before login (except whitelisted ones).
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!authConfig.isRestrictCommands()) return;

        Player player = event.getPlayer();
        if (authService.isAuthenticated(player.getUniqueId())) return;

        // Check whitelist
        if (authConfig.isCommandAllowed(event.getMessage())) {
            return;
        }

        event.setCancelled(true);
    }

    /**
     * Block block interaction before login.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!authConfig.isRestrictInteraction()) return;

        if (!authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Block entity interaction before login.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (!authConfig.isRestrictInteraction()) return;

        if (!authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Block attacking entities before login.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!authConfig.isRestrictInteraction()) return;

        if (!authService.isAuthenticated(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Block item pickup before login.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!authConfig.isRestrictItems()) return;

        if (!authService.isAuthenticated(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Block item drop before login.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        if (!authConfig.isRestrictItems()) return;

        if (!authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Block inventory click before login.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!authConfig.isRestrictInventory()) return;

        if (!authService.isAuthenticated(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Block inventory open before login.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (!authConfig.isRestrictInventory()) return;

        if (!authService.isAuthenticated(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Block block placement before login.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!authConfig.isRestrictInteraction()) return;

        if (!authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Block block break before login.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!authConfig.isRestrictInteraction()) return;

        if (!authService.isAuthenticated(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /**
     * Force spawn location on respawn if not authenticated.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (authService.isAuthenticated(event.getPlayer().getUniqueId())) return;
        // Default respawn location is fine - no special handling needed
    }
}
```

- [ ] **Step 2: Compile and verify**

Run: `cd /workspace/ANSAC-Java-Server-Core && gradle compileJava --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/java/dev/ztros/ansac/auth/AuthListener.java
git commit -m "feat(auth): add login-pre restriction listener"
```

---

### Task 7: Create Proxy Handlers (BungeeCord + Velocity)

**Files:**
- Create: `src/main/java/dev/ztros/ansac/auth/proxy/ProxyManager.java`
- Create: `src/main/java/dev/ztros/ansac/auth/proxy/BungeeCordHandler.java`
- Create: `src/main/java/dev/ztros/ansac/auth/proxy/VelocityHandler.java`

- [ ] **Step 1: Create ProxyManager.java**

```java
package dev.ztros.ansac.auth.proxy;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.auth.AuthService;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Manages proxy server (BungeeCord/Velocity) communication.
 * Auto-detects proxy type and registers appropriate handlers.
 */
public class ProxyManager {

    private final ANSACPlugin plugin;
    private final AuthService authService;
    private PluginMessageListener activeHandler;

    public ProxyManager(ANSACPlugin plugin, AuthService authService) {
        this.plugin = plugin;
        this.authService = authService;

        String proxyType = authService.getAuthConfig().getProxyType();
        switch (proxyType.toLowerCase()) {
            case "bungeecord" -> initBungeeCord();
            case "velocity" -> initVelocity();
            default -> autoDetect();
        }
    }

    private void autoDetect() {
        // Folia does not expose spigot() API, so we cannot detect BungeeCord from config.
        // Users should explicitly set proxy.type to "bungeecord" or "velocity" in config.yml.
        plugin.getLogger().info("No proxy auto-detected. Set 'auth.proxy.type' explicitly in config.yml.");
    }

    private void initBungeeCord() {
        BungeeCordHandler handler = new BungeeCordHandler(plugin, authService);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "BungeeCord", handler);
        this.activeHandler = handler;
        plugin.getLogger().info("BungeeCord communication enabled.");
    }

    private void initVelocity() {
        VelocityHandler handler = new VelocityHandler(plugin, authService);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "velocity:main");
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, "velocity:main", handler);
        this.activeHandler = handler;
        plugin.getLogger().info("Velocity communication enabled.");
    }

    /**
     * Send login status to proxy server.
     */
    public void sendLoginStatus(String playerName, boolean authenticated) {
        if (activeHandler instanceof BungeeCordHandler bc) {
            bc.sendLoginStatus(playerName, authenticated);
        } else if (activeHandler instanceof VelocityHandler vel) {
            vel.sendLoginStatus(playerName, authenticated);
        }
    }

    public void shutdown() {
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, "BungeeCord");
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, "BungeeCord");
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, "velocity:main");
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, "velocity:main");
    }
}
```

- [ ] **Step 2: Create BungeeCordHandler.java**

```java
package dev.ztros.ansac.auth.proxy;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.auth.AuthService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Handles BungeeCord plugin message channel communication.
 * Forwards login/logout status to the BungeeCord proxy.
 */
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
        // Handle incoming messages from BungeeCord if needed
        // Currently we only send outgoing login status
    }

    /**
     * Send login status to BungeeCord using ForwardToPlayer message.
     */
    public void sendLoginStatus(String playerName, boolean authenticated) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            dos.writeUTF("ForwardToPlayer");
            dos.writeUTF(playerName);
            dos.writeUTF("ANSACLogin");
            // Write login status as a short message
            String status = authenticated ? "LOGIN_SUCCESS" : "LOGOUT";
            dos.writeUTF(status);

            byte[] payload = baos.toByteArray();
            // Send to all players on the server (BungeeCord will route)
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                p.sendPluginMessage(plugin, "BungeeCord", payload);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to send BungeeCord login status: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Create VelocityHandler.java**

```java
package dev.ztros.ansac.auth.proxy;

import dev.ztros.ansac.ANSACPlugin;
import dev.ztros.ansac.auth.AuthService;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Handles Velocity plugin message channel communication.
 * Uses the modern velocity:main channel for login status forwarding.
 */
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
        // Handle incoming messages from Velocity if needed
    }

    /**
     * Send login status to Velocity using the modern plugin message format.
     */
    public void sendLoginStatus(String playerName, boolean authenticated) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream dos = new DataOutputStream(baos)) {
            // Velocity modern forwarding format
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
```

- [ ] **Step 4: Compile and verify**

Run: `cd /workspace/ANSAC-Java-Server-Core && gradle compileJava --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/ztros/ansac/auth/proxy/ProxyManager.java \
        src/main/java/dev/ztros/ansac/auth/proxy/BungeeCordHandler.java \
        src/main/java/dev/ztros/ansac/auth/proxy/VelocityHandler.java
git commit -m "feat(auth): add BungeeCord and Velocity proxy handlers"
```

---

### Task 8: Wire Everything Into ANSACPlugin

**Files:**
- Modify: `src/main/java/dev/ztros/ansac/ANSACPlugin.java`
- Modify: `src/main/resources/plugin.yml`

- [ ] **Step 1: Update ANSACPlugin.java**

Add AuthService field, initialization, shutdown, and reload:

```java
package dev.ztros.ansac;

import com.tcoded.folialib.FoliaLib;
import dev.ztros.ansac.auth.AuthCommand;
import dev.ztros.ansac.auth.AuthListener;
import dev.ztros.ansac.auth.AuthService;
import dev.ztros.ansac.checks.CheckManager;
import dev.ztros.ansac.config.ANSACConfig;
import dev.ztros.ansac.listeners.PacketListener;
import dev.ztros.ansac.listeners.PlayerListener;
import dev.ztros.ansac.player.PlayerDataManager;
import dev.ztros.ansac.scheduler.SchedulerAdapter;
import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;

public class ANSACPlugin extends JavaPlugin {

    @Getter
    private static ANSACPlugin instance;

    @Getter
    private FoliaLib foliaLib;

    @Getter
    private SchedulerAdapter schedulerAdapter;

    @Getter
    private PlayerDataManager playerDataManager;

    @Getter
    private CheckManager checkManager;

    @Getter
    private ANSACConfig ansacConfig;

    @Getter
    private AuthService authService;

    @Override
    public void onEnable() {
        instance = this;

        getLogger().info("========================================");
        getLogger().info("  ANSAC - Advanced Network Security");
        getLogger().info("  Anti-Cheat System for Folia");
        getLogger().info("  Version: " + getDescription().getVersion());
        getLogger().info("========================================");

        // Initialize FoliaLib for cross-platform compatibility
        this.foliaLib = new FoliaLib(this);
        this.schedulerAdapter = new SchedulerAdapter(this);

        // Load configuration
        saveDefaultConfig();
        this.ansacConfig = new ANSACConfig(this);

        // Initialize authentication module
        this.authService = new AuthService(this);
        if (authService.isEnabled()) {
            getServer().getPluginManager().registerEvents(
                new AuthListener(this, authService), this
            );
            getLogger().info("Authentication module enabled.");
        } else {
            getLogger().info("Authentication module disabled.");
        }

        // Initialize managers
        this.playerDataManager = new PlayerDataManager(this);
        this.checkManager = new CheckManager(this);

        // Register listeners
        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        // Register packet listener (PacketEvents is now bundled)
        new PacketListener(this).register();
        getLogger().info("PacketEvents integration enabled.");

        // Register commands
        getCommand("ansac").setExecutor(new ANSACCommand(this));

        // Register auth commands
        if (authService.isEnabled()) {
            var authCommand = new AuthCommand(this, authService);
            var loginCmd = getCommand("login");
            var registerCmd = getCommand("register");
            var changepwdCmd = getCommand("changepassword");
            var logoutCmd = getCommand("logout");
            if (loginCmd != null) loginCmd.setExecutor(authCommand);
            if (registerCmd != null) registerCmd.setExecutor(authCommand);
            if (changepwdCmd != null) changepwdCmd.setExecutor(authCommand);
            if (logoutCmd != null) logoutCmd.setExecutor(authCommand);
        }

        getLogger().info("ANSAC has been enabled successfully!");
    }

    @Override
    public void onDisable() {
        if (authService != null) {
            authService.shutdown();
        }

        if (playerDataManager != null) {
            playerDataManager.shutdown();
        }

        if (checkManager != null) {
            checkManager.shutdown();
        }

        getLogger().info("ANSAC has been disabled.");
    }

    public void reload() {
        reloadConfig();
        ansacConfig.load();
        checkManager.reload();
        if (authService != null) {
            authService.reload();
        }
        getLogger().info("ANSAC configuration reloaded.");
    }
}
```

- [ ] **Step 2: Update plugin.yml with auth commands**

```yaml
name: ANSAC
version: ${version}
main: dev.ztros.ansac.ANSACPlugin
description: 'Advanced Network Security Anti-Cheat for Folia with built-in authentication'
author: ZTROS-Team
website: https://github.com/ABI-ZTROS/ANSAC-Java-Server-Core
folia-supported: true
api-version: '1.21'

commands:
  ansac:
    description: ANSAC anti-cheat commands
    usage: /<command> [reload|status|info]
  login:
    description: Login to your account
    usage: /<command> <password>
    aliases: [l]
  register:
    description: Register a new account
    usage: /<command> <password> <confirm>
    aliases: [reg]
  changepassword:
    description: Change your password
    usage: /<command> <old> <new>
    aliases: [changepwd]
  logout:
    description: Logout from your account
    usage: /<command>

permissions:
  ansac.bypass:
    description: Bypass all ANSAC checks
    default: op
  ansac.admin:
    description: Access to ANSAC admin commands
    default: op
  ansac.alerts:
    description: Receive ANSAC alert notifications
    default: op
  ansac.command.reload:
    description: Reload ANSAC configuration
    default: op
  ansac.command.status:
    description: View ANSAC status
    default: op
```

- [ ] **Step 3: Compile and verify**

Run: `cd /workspace/ANSAC-Java-Server-Core && gradle compileJava --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add src/main/java/dev/ztros/ansac/ANSACPlugin.java src/main/resources/plugin.yml
git commit -m "feat: wire auth module into ANSACPlugin with commands and lifecycle"
```

---

### Task 9: Integrate Auth State Into Anti-Cheat System

**Files:**
- Modify: `src/main/java/dev/ztros/ansac/checks/CheckManager.java`
- Modify: `src/main/java/dev/ztros/ansac/listeners/PacketListener.java`
- Modify: `src/main/java/dev/ztros/ansac/listeners/PlayerListener.java`
- Modify: `src/main/java/dev/ztros/ansac/ANSACCommand.java`

- [ ] **Step 1: Add auth guard to CheckManager.startCheckTask()**

In `startCheckTask()`, add an auth check before processing each player. Insert after the `data.hasBypass()` check:

```java
// In startCheckTask(), inside the for loop, after bypass check:
if (plugin.getAuthService().isEnabled() && !plugin.getAuthService().isAuthenticated(player.getUniqueId())) {
    continue;
}
```

The full updated loop body:

```java
plugin.getSchedulerAdapter().runTimer(() -> {
    for (Player player : plugin.getServer().getOnlinePlayers()) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data == null || data.hasBypass()) continue;

        // Skip anti-cheat checks for unauthenticated players
        if (plugin.getAuthService().isEnabled() && !plugin.getAuthService().isAuthenticated(player.getUniqueId())) {
            continue;
        }

        // Use runAtEntity to ensure thread safety on Folia
        plugin.getSchedulerAdapter().runAtEntity(player, () -> {
            for (Check check : checks) {
                if (check.isEnabled()) {
                    try {
                        check.process(player, data);
                    } catch (Exception e) {
                        plugin.getLogger().warning("Error in check " + check.getName() + ": " + e.getMessage());
                    }
                }
            }
        });
    }
}, 1L, 1L);
```

- [ ] **Step 2: Add auth guard to CheckManager.processPlayer()**

Add the same auth check in `processPlayer()`:

```java
public void processPlayer(Player player) {
    PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
    if (data == null || data.hasBypass()) return;

    // Skip anti-cheat checks for unauthenticated players
    if (plugin.getAuthService().isEnabled() && !plugin.getAuthService().isAuthenticated(player.getUniqueId())) {
        return;
    }

    for (Check check : checks) {
        if (check.isEnabled()) {
            try {
                check.process(player, data);
            } catch (Exception e) {
                plugin.getLogger().warning("Error in check " + check.getName() + ": " + e.getMessage());
            }
        }
    }
}
```

- [ ] **Step 3: Add auth guard to PacketListener.onPacketReceive()**

Add auth check after the `data.hasBypass()` check:

```java
// In onPacketReceive(), after bypass check:
if (plugin.getAuthService().isEnabled() && !plugin.getAuthService().isAuthenticated(player.getUniqueId())) {
    return;
}
```

- [ ] **Step 4: Add auth integration to PlayerListener**

Update `onPlayerJoin` to notify AuthService, and `onPlayerQuit` to clean up:

```java
@EventHandler(priority = EventPriority.LOWEST)
public void onPlayerJoin(PlayerJoinEvent event) {
    plugin.getSchedulerAdapter().runNextTick(() -> {
        plugin.getPlayerDataManager().createPlayerData(event.getPlayer());
        plugin.getLogger().info("Tracking player: " + event.getPlayer().getName());

        // Notify auth service
        if (plugin.getAuthService().isEnabled()) {
            String ip = event.getPlayer().getAddress().getAddress().getHostAddress();
            plugin.getAuthService().handlePlayerJoin(
                event.getPlayer().getUniqueId(),
                event.getPlayer().getName(),
                ip
            );
        }
    });
}

@EventHandler(priority = EventPriority.MONITOR)
public void onPlayerQuit(PlayerQuitEvent event) {
    plugin.getSchedulerAdapter().runNextTick(() -> {
        plugin.getPlayerDataManager().removePlayerData(event.getPlayer());

        // Notify auth service
        if (plugin.getAuthService().isEnabled()) {
            plugin.getAuthService().handlePlayerQuit(event.getPlayer().getUniqueId());
        }
    });
}
```

- [ ] **Step 5: Add auth status to ANSACCommand /ansac status**

Add auth module status info to the `sendStatus` method:

```java
private void sendStatus(CommandSender sender) {
    sender.sendMessage(Component.text("=== ANSAC Status ===", NamedTextColor.GOLD));
    sender.sendMessage(
        Component.text("Version: ", NamedTextColor.YELLOW)
            .append(Component.text(plugin.getDescription().getVersion(), NamedTextColor.WHITE))
    );
    sender.sendMessage(
        Component.text("Active Players: ", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(plugin.getPlayerDataManager().getPlayerCount()), NamedTextColor.WHITE))
    );
    sender.sendMessage(
        Component.text("Checks Enabled: ", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(plugin.getCheckManager().getEnabledChecksCount()), NamedTextColor.WHITE))
    );
    sender.sendMessage(
        Component.text("Server Type: ", NamedTextColor.YELLOW)
            .append(Component.text(plugin.getSchedulerAdapter().isFolia() ? "Folia" : "Paper/Spigot", NamedTextColor.WHITE))
    );

    // Auth module status
    sender.sendMessage(Component.text("--- Auth Module ---", NamedTextColor.GOLD));
    sender.sendMessage(
        Component.text("Auth Enabled: ", NamedTextColor.YELLOW)
            .append(Component.text(String.valueOf(plugin.getAuthService().isEnabled()), NamedTextColor.WHITE))
    );
    if (plugin.getAuthService().isEnabled()) {
        sender.sendMessage(
            Component.text("Auth Mode: ", NamedTextColor.YELLOW)
                .append(Component.text("Install-and-Play (bundled)", NamedTextColor.WHITE))
        );
    }
}
```

- [ ] **Step 6: Compile and verify**

Run: `cd /workspace/ANSAC-Java-Server-Core && gradle compileJava --no-daemon`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/ztros/ansac/checks/CheckManager.java \
        src/main/java/dev/ztros/ansac/listeners/PacketListener.java \
        src/main/java/dev/ztros/ansac/listeners/PlayerListener.java \
        src/main/java/dev/ztros/ansac/ANSACCommand.java
git commit -m "feat: integrate auth state into anti-cheat checks and player lifecycle"
```

---

### Task 10: Full Build, Test, and Push

**Files:**
- All files (final verification)

- [ ] **Step 1: Full shadow build**

Run: `cd /workspace/ANSAC-Java-Server-Core && gradle fatJar --no-daemon`
Expected: BUILD SUCCESSFUL, JAR at `build/libs/ANSAC-AntiCheat-1.1.0-SNAPSHOT.jar`

- [ ] **Step 2: Verify JAR contents**

Run: `cd /workspace/ANSAC-Java-Server-Core && jar tf build/libs/ANSAC-AntiCheat-1.1.0-SNAPSHOT.jar | grep -E "(packetevents|folialib|jbcrypt|hikari|sqlite)" | head -20`
Expected: All relocated packages present under `dev/ztros/ansac/lib/`

- [ ] **Step 3: Verify no compileOnly PacketEvents leak**

Run: `cd /workspace/ANSAC-Java-Server-Core && jar tf build/libs/ANSAC-AntiCheat-1.1.0-SNAPSHOT.jar | grep "io/github/retrooper" | head -5`
Expected: No results (all relocated to `dev/ztros/ansac/lib/packetevents/`)

- [ ] **Step 4: Push all commits**

Run: `cd /workspace/ANSAC-Java-Server-Core && git push origin main`

- [ ] **Step 5: Verify CI workflows pass**

Check GitHub Actions for all 3 workflows (Build & Release, Code Quality, Folia Test) to pass.
