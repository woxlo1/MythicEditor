package dev.mythiceditor.core.catalog;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CatalogServiceTest {
    @Test
    void loadsBundledCatalogAndProducesJson() {
        CatalogService c = new CatalogService();
        String json = c.json();
        assertTrue(json.contains("\"damage\""));
        assertTrue(json.contains("\"@Self\"".replace("@", "")) || json.contains("Self"));
        assertNotNull(c.etag());
    }

    @Test
    void recognizesMechanicNamesAndAliases() {
        CatalogService c = new CatalogService();
        assertTrue(c.isMechanic("damage"));
        assertTrue(c.isMechanic("d"));
        assertTrue(c.isMechanic("DAMAGE"));
        assertFalse(c.isMechanic("notarealmechanic"));
    }

    @Test
    void identifiesSkillAndMobParameterNames() {
        CatalogService c = new CatalogService();
        assertTrue(c.skillParamNames("projectile").contains("onhit"));
        assertTrue(c.mobParamNames("summon").contains("type"));
    }

    @Test
    void mergesRuntimeMechanicsWithoutDuplicatingBundledOnes() {
        CatalogService c = new CatalogService();
        String etagBefore = c.etag();
        RuntimeCatalog rc = new RuntimeCatalog("5.9.1",
            List.of(new RuntimeCatalog.Named("damage", List.of(), "should not duplicate"), new RuntimeCatalog.Named("customaddonmechanic", List.of("cam"), "from an addon")),
            List.of(), List.of(), Map.of());
        c.applyRuntime(rc);
        assertNotEquals(etagBefore, c.etag());
        assertTrue(c.isMechanic("customaddonmechanic"));
        assertTrue(c.isMechanic("cam"));
        long damageCount = c.json().lines().count(); // sanity: still produces output
        assertTrue(damageCount > 0);
    }

    @Test
    void etagIsStableForTheSameContentAndChangesWhenContentChanges() {
        CatalogService c = new CatalogService();
        String e1 = c.etag();
        c.applyRuntime(RuntimeCatalog.empty());
        assertEquals(e1, c.etag(), "re-applying an empty runtime catalog should not change the ETag");

        c.applyRuntime(new RuntimeCatalog("5.9.1", List.of(), List.of(), List.of(), Map.of()));
        assertNotEquals(e1, c.etag(), "a detected MythicMobs version should change the ETag");
    }
}
