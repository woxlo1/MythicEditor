package dev.mythiceditor.core.index;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.mythiceditor.core.Kinds;
import dev.mythiceditor.core.catalog.CatalogService;
import dev.mythiceditor.core.config.EditorSettings;
import dev.mythiceditor.core.security.PathGuard;

class ProjectIndexTest {
    @TempDir
    Path tmp;
    ProjectIndex index;

    @BeforeEach
    void setup() throws IOException {
        Files.createDirectories(tmp.resolve("Skills"));
        Files.createDirectories(tmp.resolve("Mobs"));
        Files.writeString(tmp.resolve("Skills/Fire.yml"),
            "FireBall:\n  Skills:\n  - damage{amount=10} @Target\n  - skill:Heal @Self\n");
        Files.writeString(tmp.resolve("Mobs/Boss.yml"),
            "ExampleBoss:\n  Type: ZOMBIE\n  Skills:\n  - skill:FireBall @Target ~onAttack\n  - summon{type=Minion;amount=2} @Self ~onTimer:100\n");
        EditorSettings settings = new EditorSettings();
        PathGuard guard = new PathGuard(tmp);
        ProjectLayout layout = new ProjectLayout(settings, guard.root());
        index = new ProjectIndex(layout, guard, new RefScanner(new CatalogService()));
        index.scan();
    }

    @Test
    void findsEntriesByExactId() {
        assertEquals(1, index.find(Kinds.SKILL, "FireBall").size());
        assertEquals(1, index.find(Kinds.MOB, "ExampleBoss").size());
    }

    @Test
    void extractsSkillReferencesFromMob() {
        var mob = index.find(Kinds.MOB, "ExampleBoss").get(0);
        assertTrue(mob.refs().skills().contains("FireBall"));
    }

    @Test
    void extractsMobReferencesFromSummon() {
        var mob = index.find(Kinds.MOB, "ExampleBoss").get(0);
        assertTrue(mob.refs().mobs().contains("Minion"));
    }

    @Test
    void extractsSkillReferenceFromSkillMechanic() {
        var skill = index.find(Kinds.SKILL, "FireBall").get(0);
        assertTrue(skill.refs().skills().contains("Heal"));
    }

    @Test
    void searchIsPrefixThenSubstringThenFuzzy() {
        var page = index.search(Kinds.SKILL, "fire", null, 10, 0);
        assertEquals(1, page.total());
        assertEquals("FireBall", page.items().get(0).id());
    }

    @Test
    void incrementalRescanOnlyReparsesChangedFiles() throws IOException, InterruptedException {
        long v1 = index.version();
        Thread.sleep(1100); // ensure a different mtime second on coarse file systems
        Files.writeString(tmp.resolve("Skills/Fire.yml"), Files.readString(tmp.resolve("Skills/Fire.yml")) + "\nHeal:\n  Skills: []\n");
        var changes = index.scan();
        assertEquals(1, changes.size());
        assertTrue(index.version() > v1);
        assertEquals(1, index.find(Kinds.SKILL, "Heal").size());
    }

    @Test
    void removingFileRemovesItsEntries() throws IOException {
        Files.delete(tmp.resolve("Mobs/Boss.yml"));
        index.scan();
        assertTrue(index.find(Kinds.MOB, "ExampleBoss").isEmpty());
    }
}
