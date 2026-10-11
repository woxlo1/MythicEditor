package dev.mythiceditor.platform;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import dev.mythiceditor.core.catalog.RuntimeCatalog;
import dev.mythiceditor.core.mythic.MythicAdapter;

/**
 * Talks to the installed MythicMobs plugin without a compile-time dependency on its classes, since the
 * internal package layout of MythicMobs has changed between 5.x releases. Two tiers of access:
 *  1) Stable, documented Bukkit {@link Plugin} API (name, version, data folder, enabled state, console
 *     commands) - always used, never reflection.
 *  2) Best-effort reflection into MythicMobs' internal registries to enrich the completion catalog with
 *     mechanics/targeters/conditions registered by MythicMobs itself or by addons. Every reflective call is
 *     wrapped so that any failure (missing class, renamed method, security manager) degrades to "not
 *     available" instead of breaking the editor - the bundled catalog already covers the documented API.
 */
public final class ReflectiveMythicAdapter implements MythicAdapter {
    private static final String MAIN_CLASS = "io.lumine.mythic.bukkit.MythicBukkit";

    private final Plugin mythicPlugin;
    private final String reloadCommand;
    private final Logger log;
    private final Object mythicInstance; // io.lumine.mythic.bukkit.MythicBukkit instance, or null

    public ReflectiveMythicAdapter(Plugin mythicPlugin, String reloadCommand, Logger log) {
        this.mythicPlugin = mythicPlugin;
        this.reloadCommand = (reloadCommand == null || reloadCommand.isBlank()) ? "mythicmobs reload" : reloadCommand;
        this.log = log;
        this.mythicInstance = mythicPlugin == null ? null : safeInstance();
    }

    @Override
    public boolean isPresent() {
        return mythicPlugin != null;
    }

    @Override
    public String version() {
        return mythicPlugin == null ? null : mythicPlugin.getPluginMeta().getVersion();
    }

    @Override
    public Path dataFolder() {
        return mythicPlugin == null ? null : mythicPlugin.getDataFolder().toPath();
    }

    @Override
    public boolean isReady() {
        if (mythicPlugin == null) return false;
        if (!mythicPlugin.isEnabled()) return false;
        // Best-effort: some builds expose isLoaded()/isReady() on the main instance.
        for (String name : List.of("isLoaded", "isReady", "isPluginLoaded")) {
            Object v = tryInvokeNoArg(mythicInstance, name);
            if (v instanceof Boolean b) return b;
        }
        return true;
    }

    @Override
    public void reload() {
        if (mythicPlugin == null) return;
        // Dispatching the public command is far more stable across versions than calling an internal
        // reload method by reflection, and it is exactly what an operator would type in console.
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reloadCommand);
    }

    @Override
    public RuntimeCatalog runtimeCatalog() {
        if (mythicInstance == null) return RuntimeCatalog.empty();
        try {
            List<RuntimeCatalog.Named> mechanics = new ArrayList<>();
            List<RuntimeCatalog.Named> targeters = new ArrayList<>();
            List<RuntimeCatalog.Named> conditions = new ArrayList<>();
            collectRegistry(mechanics, "getMechanicManager", "getSkillManager");
            collectRegistry(targeters, "getTargeterManager");
            collectRegistry(conditions, "getConditionManager");
            Map<String, List<String>> enums = Map.of();
            return new RuntimeCatalog(version(), mechanics, targeters, conditions, enums);
        } catch (RuntimeException e) {
            log.log(Level.FINE, "Could not read the MythicMobs runtime registry; using the bundled catalog only", e);
            return RuntimeCatalog.empty();
        }
    }

    // ---- reflection helpers ---------------------------------------------------------------------

    private Object safeInstance() {
        try {
            Class<?> cls = Class.forName(MAIN_CLASS);
            Method inst = cls.getMethod("inst");
            return inst.invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            log.fine("MythicMobs main class not found by reflection (this is fine; static plugin info is still used): " + e);
            return null;
        }
    }

    /** Tries each manager getter on the MythicBukkit instance, then a handful of common collection getters on the manager. */
    private void collectRegistry(List<RuntimeCatalog.Named> out, String... managerGetters) {
        for (String getter : managerGetters) {
            Object manager = tryInvokeNoArg(mythicInstance, getter);
            if (manager == null) continue;
            for (String namesGetter : List.of("getTypes", "getNames", "getRegisteredNames", "getMechanics", "getTargeters", "getConditions", "getRegistered")) {
                Object result = tryInvokeNoArg(manager, namesGetter);
                if (addNamesFrom(out, result)) return;
            }
            // Some registries expose a Map<String, Class<?>> via a field-backed getter called keySet()/entrySet() indirectly through Map itself.
            if (manager instanceof Map<?, ?> map) {
                addNamesFrom(out, map.keySet());
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean addNamesFrom(List<RuntimeCatalog.Named> out, Object result) {
        if (result instanceof Map<?, ?> map) {
            for (Object k : map.keySet()) addName(out, k);
            return !map.isEmpty();
        }
        if (result instanceof Iterable<?> it) {
            boolean any = false;
            for (Object o : it) {
                addName(out, o);
                any = true;
            }
            return any;
        }
        return false;
    }

    private static void addName(List<RuntimeCatalog.Named> out, Object o) {
        if (o == null) return;
        String s = String.valueOf(o).trim();
        if (!s.isEmpty() && s.length() < 64 && !s.contains(" ")) {
            out.add(new RuntimeCatalog.Named(s, List.of(), null));
        }
    }

    private Object tryInvokeNoArg(Object target, String method) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
