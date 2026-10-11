package dev.mythiceditor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import dev.mythiceditor.command.MythicEditorCommand;
import dev.mythiceditor.core.config.EditorSettings;
import dev.mythiceditor.core.mythic.MythicAdapter;
import dev.mythiceditor.core.mythic.NoopMythicAdapter;
import dev.mythiceditor.core.service.EditorCore;
import dev.mythiceditor.platform.BukkitMainThreadRunner;
import dev.mythiceditor.platform.BukkitPermissionChecker;
import dev.mythiceditor.platform.PlayerQuitListener;
import dev.mythiceditor.platform.ReflectiveMythicAdapter;

public final class MythicEditorPlugin extends JavaPlugin {
    /** What /mythiceditor reload ended up doing. */
    public enum Outcome {
        /** config.yml was unchanged: only the MythicMobs files were rescanned. */
        REINDEXED,
        /** config.yml changed: the editor (web server, sessions, ...) was restarted with the new settings. */
        RESTARTED,
        /** config.yml could not be read: the running editor was left untouched. */
        INVALID_CONFIG,
        /** The new settings failed to start, so the previous settings were started again. */
        ROLLED_BACK,
        /** The editor could not be started at all; see the console. */
        FAILED
    }

    public record ReloadResult(Outcome outcome, int changedFiles) {}

    private volatile EditorCore core;
    private EditorSettings settings = new EditorSettings();
    private String lastConfigYaml = "";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        EditorSettings parsed = parseSettings();
        settings = parsed != null ? parsed : new EditorSettings();
        lastConfigYaml = getConfig().saveToString();

        if (!startCore(settings)) {
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        MythicEditorCommand command = new MythicEditorCommand(this);
        var pluginCommand = getCommand("mythiceditor");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }
        Bukkit.getPluginManager().registerEvents(new PlayerQuitListener(this::core), this);
    }

    @Override
    public void onDisable() {
        EditorCore c = core;
        if (c != null) {
            c.shutdown();
            core = null;
        }
    }

    /** The running editor, or null if it is not running (e.g. a reload failed to restart it). */
    public EditorCore core() {
        return core;
    }

    /**
     * Re-reads config.yml. If it changed, the whole editor is restarted so that every setting (port, bind
     * address, public-url, session limits, paths, ...) takes effect; active web sessions end in that case.
     * If it did not change, only the MythicMobs files are rescanned. Must be called from the main thread.
     */
    public synchronized ReloadResult reloadEditor() {
        reloadConfig();
        // A config.yml that fails to parse comes back as an empty config; never restart on that.
        if (getConfig().getKeys(false).isEmpty()) {
            getLogger().severe("config.yml is empty or could not be parsed; keeping the current settings.");
            return new ReloadResult(Outcome.INVALID_CONFIG, 0);
        }
        String newYaml = getConfig().saveToString();
        EditorCore current = core;

        if (current != null && newYaml.equals(lastConfigYaml)) {
            int changed = current.watcher.rescanNow().size();
            current.refreshMythicRuntimeCatalog();
            return new ReloadResult(Outcome.REINDEXED, changed);
        }

        EditorSettings next = parseSettings();
        if (next == null) return new ReloadResult(Outcome.INVALID_CONFIG, 0);

        EditorSettings previous = settings;
        if (current != null) {
            current.shutdown();
            core = null;
        }
        if (startCoreWithRetry(next)) {
            lastConfigYaml = newYaml;
            return new ReloadResult(Outcome.RESTARTED, 0);
        }
        getLogger().severe("The editor failed to start with the new config.yml; trying the previous settings.");
        if (startCoreWithRetry(previous)) return new ReloadResult(Outcome.ROLLED_BACK, 0);
        return new ReloadResult(Outcome.FAILED, 0);
    }

    /**
     * Reads config.yml straight from disk. Do NOT use getConfig().getValues(true) here: Bukkit returns nested
     * sections as ConfigurationSection objects rather than Maps, so every "web:", "session:", ... block would be
     * silently ignored and the defaults used instead.
     */
    private EditorSettings parseSettings() {
        try {
            return EditorSettings.load(getDataFolder().toPath().resolve("config.yml"));
        } catch (IOException | RuntimeException e) {
            getLogger().log(Level.SEVERE, "config.yml could not be parsed", e);
            return null;
        }
    }

    /** Right after the old web server was stopped the port can take a moment to become free again. */
    private boolean startCoreWithRetry(EditorSettings s) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            if (startCore(s)) return true;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean startCore(EditorSettings s) {
        Plugin mythicPlugin = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mythicPlugin == null) {
            // depend: [MythicMobs] in plugin.yml means Bukkit will already have refused to enable us
            // without it, but fail loudly and cleanly if that is ever bypassed.
            getLogger().severe("MythicMobs is not installed or failed to enable. MythicEditor requires MythicMobs and cannot start.");
            return false;
        }
        MythicAdapter mythic = new ReflectiveMythicAdapter(mythicPlugin, s.reloadCommand, getLogger());

        Path mythicFolder = resolveMythicFolder(s, mythic);
        if (mythicFolder == null) {
            getLogger().severe("Could not resolve the MythicMobs data folder. Set 'mythicmobs.folder' in MythicEditor/config.yml.");
            return false;
        }

        EditorCore created = null;
        try {
            BukkitMainThreadRunner runner = new BukkitMainThreadRunner(this);
            created = new EditorCore(s, mythicFolder, mythic, new BukkitPermissionChecker(runner), runner,
                getLogger(), "MythicEditor v" + getPluginMeta().getVersion());
            created.start();
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "MythicEditor failed to start its web server.", e);
            if (created != null) created.shutdown();
            return false;
        } catch (RuntimeException e) {
            getLogger().log(Level.SEVERE, "MythicEditor failed to start (invalid configuration?).", e);
            if (created != null) created.shutdown();
            return false;
        }

        core = created;
        settings = s;
        getLogger().info("MythicEditor enabled. Public URL base: " + created.publicUrl() + "  (bound port " + created.port() + ")");
        getLogger().info("Serving MythicMobs content from: " + mythicFolder);
        return true;
    }

    private Path resolveMythicFolder(EditorSettings settings, MythicAdapter mythic) {
        if (!settings.mythicFolder.isBlank()) {
            return getDataFolder().toPath().resolve("..").resolve(settings.mythicFolder).normalize().toAbsolutePath();
        }
        Path fromPlugin = mythic.dataFolder();
        if (fromPlugin != null) {
            try {
                Files.createDirectories(fromPlugin);
                return fromPlugin.toAbsolutePath();
            } catch (IOException e) {
                getLogger().log(Level.WARNING, "Could not create the MythicMobs data folder", e);
            }
        }
        return null;
    }

    /** Exposed for the standalone dev server / tests; not used by Bukkit itself. */
    MythicAdapter noopAdapterForTests() {
        return new NoopMythicAdapter(getDataFolder().toPath());
    }
}
