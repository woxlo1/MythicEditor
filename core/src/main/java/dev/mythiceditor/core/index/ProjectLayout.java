package dev.mythiceditor.core.index;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.mythiceditor.core.config.EditorSettings;

/**
 * Resolves the configured folder patterns ("Mobs", "Packs/*&#47;Mobs") to real directories below the
 * MythicMobs folder. Nothing here is hard-coded: the patterns come from config.yml.
 */
public final class ProjectLayout {
    private final EditorSettings settings;
    private final Path root;

    public ProjectLayout(EditorSettings settings, Path realMythicRoot) {
        this.settings = settings;
        this.root = realMythicRoot;
    }

    public Path root() {
        return root;
    }

    public EditorSettings settings() {
        return settings;
    }

    /** kind ("mob", "skill", ...) -> existing directories */
    public Map<String, List<Path>> resolveAll() {
        Map<String, List<Path>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : settings.paths.entrySet()) {
            List<Path> dirs = new ArrayList<>();
            for (String pattern : e.getValue()) expand(pattern, dirs);
            out.put(EditorSettings.kindOf(e.getKey()), dirs);
        }
        return out;
    }

    public List<Path> dirsFor(String kind) {
        return resolveAll().getOrDefault(kind, List.of());
    }

    /** Directories where a new file of this kind could be created (existing or the first literal pattern). */
    public List<Path> writableDirsFor(String kind) {
        List<Path> dirs = new ArrayList<>(dirsFor(kind));
        for (Map.Entry<String, List<String>> e : settings.paths.entrySet()) {
            if (!EditorSettings.kindOf(e.getKey()).equals(kind)) continue;
            for (String p : e.getValue()) {
                if (!p.contains("*") && !p.contains("..")) {
                    Path d = root.resolve(p).normalize();
                    if (d.startsWith(root) && !dirs.contains(d)) dirs.add(d);
                }
            }
        }
        return dirs;
    }

    private void expand(String pattern, List<Path> out) {
        if (pattern == null || pattern.isBlank() || pattern.startsWith("/") || pattern.contains("..") || pattern.contains("\\")) return;
        List<Path> current = new ArrayList<>(List.of(root));
        for (String seg : pattern.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            List<Path> next = new ArrayList<>();
            for (Path base : current) {
                if (seg.contains("*")) {
                    if (!Files.isDirectory(base)) continue;
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(base, seg)) {
                        for (Path p : ds) if (Files.isDirectory(p) && !p.getFileName().toString().startsWith(".")) next.add(p);
                    } catch (IOException | RuntimeException ignored) {
                        // unreadable directory: skip
                    }
                } else {
                    next.add(base.resolve(seg));
                }
            }
            current = next;
        }
        for (Path p : current) {
            Path n = p.normalize();
            if (n.startsWith(root) && Files.isDirectory(n) && !out.contains(n)) out.add(n);
        }
    }
}
