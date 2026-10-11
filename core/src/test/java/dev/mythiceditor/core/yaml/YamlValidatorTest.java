package dev.mythiceditor.core.yaml;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class YamlValidatorTest {
    @Test
    void acceptsValidBlock() {
        var r = YamlValidator.validateBlock("Foo:\n  Skills:\n  - damage{amount=10} @Target\n", "Foo");
        assertTrue(r.ok(), r.message());
    }

    @Test
    void rejectsMismatchedKey() {
        var r = YamlValidator.validateBlock("Foo:\n  X: 1\n", "Bar");
        assertFalse(r.ok());
    }

    @Test
    void rejectsMultipleTopLevelKeys() {
        var r = YamlValidator.validateBlock("Foo:\n  X: 1\nBar:\n  Y: 2\n", "Foo");
        assertFalse(r.ok());
    }

    @Test
    void reportsLineOnSyntaxError() {
        var r = YamlValidator.validateFile("Foo:\n  X: [1, 2\n");
        assertFalse(r.ok());
        assertTrue(r.line() > 0);
    }

    @Test
    void loadsFileWithRepeatedKeyUsingLastValue() {
        // SnakeYAML's SafeConstructor does not reject duplicate keys across supported versions; this
        // documents the actual (last-value-wins) behaviour so validation is not relied on to catch it.
        // MythicEditor's own YamlBlockParser instead treats a second identical top-level entry name as a
        // conflict when creating/renaming entries (see FileStoreTest).
        var r = YamlValidator.validateFile("Foo:\n  X: 1\n  X: 2\n");
        assertTrue(r.ok());
    }
}
