package dev.mythiceditor.core.security;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathGuardTest {
    @TempDir
    Path tmp;
    PathGuard guard;
    Path skillsDir;

    @BeforeEach
    void setup() throws IOException {
        guard = new PathGuard(tmp);
        skillsDir = Files.createDirectories(tmp.resolve("Skills"));
        Files.createDirectories(tmp.resolve("Mobs"));
    }

    @Test
    void resolvesFileInsideAllowedDir() {
        Path p = guard.resolveYamlFile("Skills/Fire.yml", List.of(skillsDir));
        assertEquals(skillsDir.resolve("Fire.yml"), p);
    }

    @Test
    void rejectsDotDotTraversal() {
        assertThrows(SecurityException.class, () -> guard.resolveYamlFile("Skills/../../../etc/passwd.yml", List.of(skillsDir)));
    }

    @Test
    void rejectsAbsolutePath() {
        assertThrows(SecurityException.class, () -> guard.resolveYamlFile("/etc/passwd.yml", List.of(skillsDir)));
    }

    @Test
    void rejectsPathOutsideAllowedDirs() {
        assertThrows(SecurityException.class, () -> guard.resolveYamlFile("Mobs/Boss.yml", List.of(skillsDir)));
    }

    @Test
    void rejectsNonYamlExtension() {
        assertThrows(SecurityException.class, () -> guard.resolveYamlFile("Skills/Fire.txt", List.of(skillsDir)));
    }

    @Test
    void rejectsHiddenSegments() {
        assertThrows(SecurityException.class, () -> guard.resolveYamlFile("Skills/.hidden/Fire.yml", List.of(skillsDir)));
    }

    @Test
    void rejectsSymlinkEscape() throws IOException {
        Path outside = Files.createDirectories(tmp.getParent().resolve("outside-" + System.nanoTime()));
        Path evil = Files.writeString(outside.resolve("secret.yml"), "Secret: true\n");
        Path link = skillsDir.resolve("link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return; // symlinks unsupported on this file system; nothing to assert
        }
        assertThrows(SecurityException.class, () -> guard.resolveYamlFile("Skills/link/secret.yml", List.of(skillsDir)));
    }

    @Test
    void rejectsBackslashAndControlChars() {
        assertThrows(SecurityException.class, () -> guard.resolveYamlFile("Skills\\Fire.yml", List.of(skillsDir)));
        assertThrows(SecurityException.class, () -> guard.resolveYamlFile("Skills/Fire\u0000.yml", List.of(skillsDir)));
    }
}
