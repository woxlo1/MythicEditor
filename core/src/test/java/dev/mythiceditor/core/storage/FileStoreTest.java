package dev.mythiceditor.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mythiceditor.core.Kinds;
import dev.mythiceditor.core.audit.ChangeLog;
import dev.mythiceditor.core.catalog.CatalogService;
import dev.mythiceditor.core.config.EditorSettings;
import dev.mythiceditor.core.index.ProjectIndex;
import dev.mythiceditor.core.index.ProjectLayout;
import dev.mythiceditor.core.index.RefScanner;
import dev.mythiceditor.core.security.PathGuard;

class FileStoreTest {
    @TempDir
    Path tmp;
    FileStore store;
    ProjectIndex index;
    PathGuard guard;

    @BeforeEach
    void setup() throws IOException {
        Files.createDirectories(tmp.resolve("Skills"));
        Files.createDirectories(tmp.resolve("Mobs"));
        EditorSettings settings = new EditorSettings();
        guard = new PathGuard(tmp);
        ProjectLayout layout = new ProjectLayout(settings, guard.root());
        CatalogService catalog = new CatalogService();
        index = new ProjectIndex(layout, guard, new RefScanner(catalog));
        BackupManager backups = new BackupManager(guard.root(), settings.backupDir, settings.backupRetention);
        ChangeLog changes = new ChangeLog(50);
        store = new FileStore(settings, layout, guard, index, backups, changes, Logger.getLogger("test"));
        index.scan();
    }

    @Test
    void createsThenReadsEntry() throws StoreException {
        FileStore.Entry created = store.create(Kinds.SKILL, "FireBall", "Skills/Fire.yml",
            "FireBall:\n  Skills:\n  - damage{amount=10} @Target\n", "tester");
        assertEquals("FireBall", created.id());
        FileStore.Entry read = store.read(Kinds.SKILL, "FireBall");
        assertTrue(read.text().contains("damage{amount=10}"));
    }

    @Test
    void createRejectsDuplicateId() throws StoreException {
        store.create(Kinds.SKILL, "FireBall", "Skills/Fire.yml", "FireBall:\n  Skills: []\n", "tester");
        assertThrows(StoreException.class, () -> store.create(Kinds.SKILL, "FireBall", "Skills/Other.yml", "FireBall:\n  Skills: []\n", "tester"));
    }

    @Test
    void createRejectsInvalidId() {
        assertThrows(StoreException.class, () -> store.create(Kinds.SKILL, "bad id!", null, "x:\n  Skills: []\n", "tester"));
    }

    @Test
    void updatePreservesUnrelatedContentInTheFile() throws StoreException, IOException {
        Files.writeString(tmp.resolve("Skills/Fire.yml"),
            "# top comment\nFireBall:\n  Skills:\n  - damage{amount=10} @Target\n\n# between\nHeal:\n  Skills:\n  - heal{amount=5} @Self\n# trailing comment\n");
        index.scan();
        FileStore.Entry current = store.read(Kinds.SKILL, "FireBall");
        FileStore.Entry saved = store.save(Kinds.SKILL, "FireBall", "FireBall:\n  Skills:\n  - damage{amount=99} @Target\n", current.hash(), false, "tester");
        assertTrue(saved.text().contains("amount=99"));
        String whole = Files.readString(tmp.resolve("Skills/Fire.yml"));
        assertTrue(whole.contains("# top comment"));
        assertTrue(whole.contains("# between"));
        assertTrue(whole.contains("# trailing comment"));
        assertTrue(whole.contains("heal{amount=5}"));
    }

    @Test
    void detectsExternalConflictOnSave() throws StoreException, IOException {
        FileStore.Entry created = store.create(Kinds.SKILL, "FireBall", "Skills/Fire.yml", "FireBall:\n  Skills:\n  - damage{amount=10} @Target\n", "tester");
        // simulate an external edit made by MythicMobs / another admin directly on disk
        Files.writeString(tmp.resolve("Skills/Fire.yml"), "FireBall:\n  Skills:\n  - damage{amount=55} @Target\n");
        index.scan();
        StoreException ex = assertThrows(StoreException.class,
            () -> store.save(Kinds.SKILL, "FireBall", "FireBall:\n  Skills:\n  - damage{amount=10;ignorearmor=true} @Target\n", created.hash(), false, "tester"));
        assertEquals("conflict", ex.code());
        assertTrue(ex.extra().containsKey("server"));
    }

    @Test
    void forceSaveOverridesConflict() throws StoreException, IOException {
        FileStore.Entry created = store.create(Kinds.SKILL, "FireBall", "Skills/Fire.yml", "FireBall:\n  Skills:\n  - damage{amount=10} @Target\n", "tester");
        Files.writeString(tmp.resolve("Skills/Fire.yml"), "FireBall:\n  Skills:\n  - damage{amount=55} @Target\n");
        index.scan();
        FileStore.Entry saved = store.save(Kinds.SKILL, "FireBall", "FireBall:\n  Skills:\n  - damage{amount=10} @Target\n", created.hash(), true, "tester");
        assertTrue(saved.text().contains("amount=10"));
    }

    @Test
    void renameChangesTopLevelKey() throws StoreException {
        FileStore.Entry created = store.create(Kinds.SKILL, "OldName", "Skills/Fire.yml", "OldName:\n  Skills: []\n", "tester");
        FileStore.Entry renamed = store.save(Kinds.SKILL, "OldName", "NewName:\n  Skills: []\n", created.hash(), false, "tester");
        assertEquals("NewName", renamed.id());
        assertTrue(store.read(Kinds.SKILL, "NewName") != null);
        assertThrows(StoreException.class, () -> store.read(Kinds.SKILL, "OldName"));
    }

    @Test
    void deleteRemovesOnlyThatEntry() throws StoreException, IOException {
        Files.writeString(tmp.resolve("Skills/Fire.yml"), "A:\n  Skills: []\nB:\n  Skills: []\n");
        index.scan();
        FileStore.Entry a = store.read(Kinds.SKILL, "A");
        store.delete(Kinds.SKILL, "A", a.hash(), false, "tester");
        assertThrows(StoreException.class, () -> store.read(Kinds.SKILL, "A"));
        assertNotNull(store.read(Kinds.SKILL, "B"));
    }

    @Test
    void duplicateCreatesIndependentCopy() throws StoreException {
        store.create(Kinds.SKILL, "FireBall", "Skills/Fire.yml", "FireBall:\n  Skills:\n  - damage{amount=10} @Target\n", "tester");
        FileStore.Entry dup = store.duplicate(Kinds.SKILL, "FireBall", "FireBall2", null, "tester");
        assertEquals("FireBall2", dup.id());
        assertTrue(dup.text().contains("damage{amount=10}"));
        assertNotNull(store.read(Kinds.SKILL, "FireBall"));
    }

    @Test
    void rejectsInvalidYamlBody() {
        assertThrows(StoreException.class, () -> store.create(Kinds.SKILL, "Broken", null, "Broken:\n  X: [1, 2\n", "tester"));
    }

    @Test
    void readonlyKindIsRejected() throws IOException {
        EditorSettings settings = new EditorSettings();
        settings.editable = new java.util.LinkedHashSet<>(List.of("skill"));
        ProjectLayout layout = new ProjectLayout(settings, guard.root());
        CatalogService catalog = new CatalogService();
        ProjectIndex idx2 = new ProjectIndex(layout, guard, new RefScanner(catalog));
        FileStore restricted = new FileStore(settings, layout, guard, idx2, new BackupManager(guard.root(), "backups2", 5), new ChangeLog(10), Logger.getLogger("test2"));
        assertThrows(StoreException.class, () -> restricted.create(Kinds.MOB, "Boss", null, "Boss:\n  Type: ZOMBIE\n", "tester"));
    }
}
