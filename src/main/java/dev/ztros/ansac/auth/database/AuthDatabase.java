package dev.ztros.ansac.auth.database;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface AuthDatabase {

    CompletableFuture<Void> initialize();

    CompletableFuture<Optional<String>> getPasswordHash(UUID uuid);

    CompletableFuture<Optional<String>> getPasswordHashByName(String username);

    CompletableFuture<Boolean> isRegistered(UUID uuid);

    CompletableFuture<Boolean> isRegisteredByName(String username);

    CompletableFuture<Void> savePassword(UUID uuid, String username, String passwordHash, String ip);

    CompletableFuture<Void> updateLogin(UUID uuid, String ip);

    CompletableFuture<Optional<String>> getLastIp(UUID uuid);

    CompletableFuture<Void> removeRegistration(UUID uuid);

    CompletableFuture<Void> shutdown();
}
