package dev.mythiceditor.core.catalog;

import java.util.List;
import java.util.Map;

/** Information discovered from the running server (MythicMobs registrations, Bukkit registries). */
public record RuntimeCatalog(
    String mythicVersion,
    List<Named> mechanics,
    List<Named> targeters,
    List<Named> conditions,
    Map<String, List<String>> enums) {

    public record Named(String name, List<String> aliases, String description) {}

    public static RuntimeCatalog empty() {
        return new RuntimeCatalog(null, List.of(), List.of(), List.of(), Map.of());
    }
}
