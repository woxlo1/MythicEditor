package dev.mythiceditor.core.web;

import java.util.UUID;

/** Bridges to the platform's permission system (Bukkit permissions in production). */
@FunctionalInterface
public interface PermissionChecker {
    boolean has(UUID playerId, String permission);
}
