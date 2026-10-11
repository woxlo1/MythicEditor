package dev.mythiceditor.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackupManagerTest {
    @Test
    void writesBackupUnderRelativePath(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("Skills"));
        Path original = tmp.resolve("Skills/Fire.yml");
        Files.writeString(original, "FireBall:\n  Skills: []\n");
        BackupManager bm = new BackupManager(tmp, "backups", 10);
        Path backup = bm.backup(original, "FireBall:\n  Skills: []\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(Files.exists(backup));
        assertTrue(backup.startsWith(bm.backupRoot()));
        assertEquals("FireBall:\n  Skills: []\n", Files.readString(backup));
    }

    @Test
    void prunesOldBackupsBeyondRetention() throws IOException, InterruptedException {
        Files.createDirectories(tmp2());
        Path root = tmp2();
        Path original = root.resolve("Skills/Fire.yml");
        Files.createDirectories(original.getParent());
        Files.writeString(original, "x");
        BackupManager bm = new BackupManager(root, "backups", 2);
        for (int i = 0; i < 5; i++) {
            bm.backup(original, ("v" + i).getBytes(StandardCharsets.UTF_8));
            Thread.sleep(5);
        }
        assertTrue(bm.list(100).size() <= 2);
    }

    private Path tmp2() throws IOException {
        return Files.createTempDirectory("mebackup");
    }

    @Test
    void rejectsBackupDirOutsideRoot(@TempDir Path tmp) {
        assertThrows(IllegalArgumentException.class, () -> new BackupManager(tmp, "../escape", 5));
    }
}
