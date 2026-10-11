package dev.mythiceditor.core.mythic;

import java.nio.file.Path;

import dev.mythiceditor.core.catalog.RuntimeCatalog;

/**
 * The only contract between MythicEditor's core and the MythicMobs plugin. All access to MythicMobs
 * classes/APIs (which vary between 5.x builds) is isolated behind this interface and implemented with
 * reflection in the platform module, so the core stays compilable and testable without MythicMobs on
 * the classpath, and keeps working across MythicMobs API revisions.
 */
public interface MythicAdapter {
    boolean isPresent();

    String version();

    /** The folder MythicMobs itself is configured to use (its plugin data folder unless overridden). */
    Path dataFolder();

    /** Mechanics/targeters/conditions/enums registered by MythicMobs and any addons, for the completion catalog. */
    RuntimeCatalog runtimeCatalog();

    /** Triggers a MythicMobs config reload. Must be invoked already on the main thread by the caller. */
    void reload();

    /** True once MythicMobs itself reports it has finished loading. */
    boolean isReady();
}
