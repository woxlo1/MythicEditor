package dev.mythiceditor.core.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The only component that turns user supplied strings into file system paths.
 * Everything is resolved relative to the MythicMobs folder and must stay inside one of
 * the allowed content folders (checked on the real path, so symlinks cannot escape).
 */
public final class PathGuard {
    private final Path mythicRoot;
    private final Path realRoot;

    public PathGuard(Path mythicRoot) throws IOException {
        this.mythicRoot = mythicRoot.toAbsolutePath().normalize();
        Files.createDirectories(this.mythicRoot);
        this.realRoot = this.mythicRoot.toRealPath();
    }

    public Path root() {
        return realRoot;
    }

    /** @return path relative to the MythicMobs folder using '/' separators */
    public String relativize(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path rel = abs.startsWith(realRoot) ? realRoot.relativize(abs) : mythicRoot.relativize(abs);
        return rel.toString().replace('\\', '/');
    }

    /**
     * Resolve a user supplied relative path to an absolute YAML file path inside one of {@code allowedDirs}.
     *
     * @throws SecurityException for anything suspicious (message is safe to show to the user)
     */
    public Path resolveYamlFile(String userPath, List<Path> allowedDirs) {
        if (userPath == null || userPath.isBlank()) throw new SecurityException("Empty path");
        if (userPath.length() > 260) throw new SecurityException("Path too long");
        for (int i = 0; i < userPath.length(); i++) {
            char c = userPath.charAt(i);
            if (c < 0x20 || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                throw new SecurityException("Illegal character in path");
            }
        }
        if (userPath.startsWith("/") || userPath.startsWith("~")) throw new SecurityException("Absolute paths are not allowed");
        for (String seg : userPath.split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..") || seg.startsWith(".") || seg.endsWith(" ") || seg.endsWith(".")) {
                throw new SecurityException("Illegal path segment");
            }
        }
        String lower = userPath.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".yml") && !lower.endsWith(".yaml")) throw new SecurityException("Only .yml/.yaml files are allowed");

        Path candidate;
        try {
            candidate = realRoot.resolve(userPath).normalize();
        } catch (InvalidPathException e) {
            throw new SecurityException("Invalid path");
        }
        if (!candidate.startsWith(realRoot)) throw new SecurityException("Path escapes the MythicMobs folder");

        Path checked = realAncestor(candidate);
        boolean allowed = false;
        for (Path dir : allowedDirs) {
            Path d = dir.toAbsolutePath().normalize();
            if (candidate.startsWith(d) && !candidate.equals(d) && checked.startsWith(realOf(d))) {
                allowed = true;
                break;
            }
        }
        if (!allowed) throw new SecurityException("Path is outside the configured MythicMobs content folders");
        return candidate;
    }

    /** True if the (already resolved) path is inside one of the allowed dirs by real path. */
    public boolean isInside(Path file, List<Path> allowedDirs) {
        try {
            Path real = realAncestor(file.toAbsolutePath().normalize());
            for (Path d : allowedDirs) {
                if (real.startsWith(realOf(d.toAbsolutePath().normalize()))) return true;
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    private Path realOf(Path p) {
        try {
            return realAncestor(p);
        } catch (RuntimeException e) {
            return p;
        }
    }

    /** Real path of the deepest existing ancestor, with the non-existing remainder re-appended. */
    private Path realAncestor(Path p) {
        Path existing = p;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new SecurityException("Invalid path");
        try {
            Path real = existing.toRealPath();
            Path rest = existing.relativize(p);
            Path out = rest.toString().isEmpty() ? real : real.resolve(rest).normalize();
            if (!out.startsWith(realRoot)) throw new SecurityException("Path escapes the MythicMobs folder");
            return out;
        } catch (IOException e) {
            throw new SecurityException("Cannot resolve path");
        }
    }
}
