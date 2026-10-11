package dev.mythiceditor.core.mythic;

import java.nio.file.Path;

import dev.mythiceditor.core.catalog.RuntimeCatalog;

/** Used only by standalone/dev tooling and tests where no real MythicMobs plugin is available. */
public final class NoopMythicAdapter implements MythicAdapter {
    private final Path dataFolder;

    public NoopMythicAdapter(Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    @Override
    public boolean isPresent() {
        return false;
    }

    @Override
    public String version() {
        return null;
    }

    @Override
    public Path dataFolder() {
        return dataFolder;
    }

    @Override
    public RuntimeCatalog runtimeCatalog() {
        return RuntimeCatalog.empty();
    }

    @Override
    public void reload() {
        // no-op
    }

    @Override
    public boolean isReady() {
        return false;
    }
}
