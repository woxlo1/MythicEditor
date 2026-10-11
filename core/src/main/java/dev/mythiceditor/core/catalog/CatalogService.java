package dev.mythiceditor.core.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.mythiceditor.core.Json;

/**
 * Serves the MythicMobs knowledge base (mechanics, targeters, triggers, conditions, options, enums, placeholders).
 * Layers: bundled reference JSON  ->  names discovered from the running MythicMobs / Bukkit registries.
 * Runtime-only entries are flagged {@code "source":"runtime"} so the UI can tell them apart.
 */
public final class CatalogService {
    private static final String RESOURCE = "/catalog/mythicmobs-5.json";

    private final Map<String, Object> bundled;
    private volatile String json;
    private volatile String etag;
    /** lower-case mechanic name/alias -> lower-case names of params holding skill references */
    private volatile Map<String, Set<String>> skillParams = Map.of();
    private volatile Map<String, Set<String>> mobParams = Map.of();
    private volatile Set<String> mechanicNames = Set.of();

    public CatalogService() {
        this(null);
    }

    /** @param overrideFile optional user supplied catalog (same format) replacing the bundled one */
    public CatalogService(Path overrideFile) {
        JsonObject root;
        try {
            root = readCatalog(overrideFile);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load bundled catalog", e);
        }
        this.bundled = normalize(root);
        rebuild(RuntimeCatalog.empty());
    }

    private static JsonObject readCatalog(Path overrideFile) throws IOException {
        if (overrideFile != null && Files.isRegularFile(overrideFile)) {
            return JsonParser.parseString(Files.readString(overrideFile, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        try (InputStream in = CatalogService.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("Missing resource " + RESOURCE);
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    public synchronized void applyRuntime(RuntimeCatalog rc) {
        rebuild(rc == null ? RuntimeCatalog.empty() : rc);
    }

    public String json() {
        return json;
    }

    public String etag() {
        return etag;
    }

    public Set<String> skillParamNames(String mechanic) {
        return skillParams.getOrDefault(mechanic.toLowerCase(Locale.ROOT), Set.of());
    }

    public Set<String> mobParamNames(String mechanic) {
        return mobParams.getOrDefault(mechanic.toLowerCase(Locale.ROOT), Set.of());
    }

    public boolean isMechanic(String name) {
        return mechanicNames.contains(name.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void rebuild(RuntimeCatalog rc) {
        Map<String, Object> out = new LinkedHashMap<>(bundled);
        out.put("mythicmobsVersion", rc.mythicVersion() != null ? rc.mythicVersion() : bundled.get("mythicmobsVersion"));
        out.put("runtimeDetected", rc.mythicVersion() != null);

        out.put("mechanics", mergeNamed((List<Map<String, Object>>) bundled.get("mechanics"), rc.mechanics()));
        out.put("targeters", mergeNamed((List<Map<String, Object>>) bundled.get("targeters"), rc.targeters()));
        out.put("conditions", mergeNamed((List<Map<String, Object>>) bundled.get("conditions"), rc.conditions()));

        Map<String, Object> enums = new LinkedHashMap<>((Map<String, Object>) bundled.get("enums"));
        for (Map.Entry<String, List<String>> e : rc.enums().entrySet()) {
            Set<String> merged = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            Object base = enums.get(e.getKey());
            if (base instanceof List<?> l) for (Object o : l) merged.add(String.valueOf(o));
            merged.addAll(e.getValue());
            // runtime data is authoritative for enumerations it knows completely
            enums.put(e.getKey(), new ArrayList<>(e.getValue().isEmpty() ? merged : e.getValue()));
        }
        out.put("enums", enums);

        Map<String, Set<String>> sp = new HashMap<>();
        Map<String, Set<String>> mp = new HashMap<>();
        Set<String> names = new HashSet<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) out.get("mechanics")) {
            List<String> all = new ArrayList<>();
            all.add(String.valueOf(m.get("name")));
            all.addAll((List<String>) m.get("aliases"));
            Set<String> skillP = new HashSet<>();
            Set<String> mobP = new HashSet<>();
            for (Map<String, Object> p : (List<Map<String, Object>>) m.get("params")) {
                String type = String.valueOf(p.get("type"));
                for (String n : (List<String>) p.get("names")) {
                    if (type.equals("skill") || type.equals("skilllist")) skillP.add(n.toLowerCase(Locale.ROOT));
                    if (type.equals("mob")) mobP.add(n.toLowerCase(Locale.ROOT));
                }
            }
            for (String n : all) {
                String k = n.toLowerCase(Locale.ROOT);
                names.add(k);
                if (!skillP.isEmpty()) sp.put(k, skillP);
                if (!mobP.isEmpty()) mp.put(k, mobP);
            }
        }
        this.skillParams = sp;
        this.mobParams = mp;
        this.mechanicNames = names;

        String j = Json.toJson(out);
        this.json = j;
        this.etag = "\"" + sha1(j) + "\"";
    }

    private static List<Map<String, Object>> mergeNamed(List<Map<String, Object>> base, List<RuntimeCatalog.Named> runtime) {
        List<Map<String, Object>> out = new ArrayList<>(base);
        Set<String> known = new HashSet<>();
        for (Map<String, Object> m : base) {
            known.add(String.valueOf(m.get("name")).toLowerCase(Locale.ROOT));
            for (Object a : (List<?>) m.get("aliases")) known.add(String.valueOf(a).toLowerCase(Locale.ROOT));
        }
        for (RuntimeCatalog.Named n : runtime) {
            if (n.name() == null || n.name().isBlank() || known.contains(n.name().toLowerCase(Locale.ROOT))) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n.name());
            List<String> aliases = new ArrayList<>();
            for (String a : n.aliases()) {
                if (a != null && !a.isBlank() && !known.contains(a.toLowerCase(Locale.ROOT))) aliases.add(a);
            }
            m.put("aliases", aliases);
            m.put("desc", n.description() == null || n.description().isBlank() ? "Detected from the installed MythicMobs (no bundled documentation)." : n.description());
            m.put("params", List.of());
            m.put("source", "runtime");
            out.add(m);
            known.add(n.name().toLowerCase(Locale.ROOT));
            for (String a : aliases) known.add(a.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ---- normalisation of the compact bundled format ------------------------------------------------

    private static Map<String, Object> normalize(JsonObject root) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mythicmobsVersion", str(root, "mythicmobsVersion", "5.x"));
        for (String section : List.of("mechanics", "targeters", "conditions")) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (JsonElement e : arr(root, section)) list.add(normalizeEntry(e.getAsJsonObject(), true));
            out.put(section, list);
        }
        List<Map<String, Object>> triggers = new ArrayList<>();
        for (JsonElement e : arr(root, "triggers")) triggers.add(normalizeEntry(e.getAsJsonObject(), false));
        out.put("triggers", triggers);
        for (String section : List.of("skillKeys", "mobKeys", "mobOptions", "aiGoals", "aiTargets", "placeholders")) {
            List<Object> list = new ArrayList<>();
            for (JsonElement e : arr(root, section)) list.add(Json.parse(e.toString()).getAsJsonObject().asMap());
            out.put(section, list);
        }
        // attributes shared by every mechanic / targeter, in the same compact "names|type|default|desc" format
        for (String section : List.of("mechanicAttributes", "targeterAttributes")) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (JsonElement e : arr(root, section)) list.add(parseParam(e.getAsString()));
            out.put(section, list);
        }
        for (String section : List.of("conditionActions", "colorCodes")) {
            List<Object> list = new ArrayList<>();
            for (JsonElement e : arr(root, section)) list.add(Json.parse(e.toString()).getAsJsonObject().asMap());
            out.put(section, list);
        }
        Map<String, Object> enums = new LinkedHashMap<>();
        if (root.has("enums")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("enums").entrySet()) {
                List<String> vals = new ArrayList<>();
                for (JsonElement v : e.getValue().getAsJsonArray()) vals.add(v.getAsString());
                enums.put(e.getKey(), vals);
            }
        }
        out.put("enums", enums);
        return out;
    }

    private static Map<String, Object> normalizeEntry(JsonObject o, boolean withParams) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", str(o, "name", ""));
        List<String> aliases = new ArrayList<>();
        for (JsonElement a : arr(o, "aliases")) aliases.add(a.getAsString());
        m.put("aliases", aliases);
        m.put("desc", str(o, "desc", ""));
        if (o.has("cat")) m.put("cat", o.get("cat").getAsString());
        if (o.has("arg")) m.put("arg", o.get("arg").getAsString());
        m.put("source", "bundled");
        if (withParams) {
            List<Map<String, Object>> params = new ArrayList<>();
            for (JsonElement p : arr(o, "params")) params.add(parseParam(p.getAsString()));
            m.put("params", params);
            List<String> main = new ArrayList<>();
            for (JsonElement a : arr(o, "main")) main.add(a.getAsString());
            m.put("main", main);
        }
        return m;
    }

    /** {@code names|type|default|description} */
    static Map<String, Object> parseParam(String spec) {
        String[] parts = spec.split("\\|", 4);
        Map<String, Object> p = new LinkedHashMap<>();
        List<String> names = new ArrayList<>();
        for (String n : parts[0].split(",")) if (!n.isBlank()) names.add(n.trim());
        p.put("names", names);
        String type = parts.length > 1 ? parts[1].trim() : "string";
        boolean required = type.endsWith("!");
        if (required) type = type.substring(0, type.length() - 1);
        if (type.startsWith("enum(") || type.startsWith("enum~(")) {
            boolean soft = type.startsWith("enum~(");
            String inner = type.substring(type.indexOf('(') + 1, type.lastIndexOf(')'));
            p.put("type", "enum");
            p.put("values", new ArrayList<>(List.of(inner.split(","))));
            p.put("soft", soft);
        } else if (type.startsWith("enum:")) {
            p.put("type", "enum");
            p.put("enumRef", type.substring(5));
            p.put("soft", true);
        } else {
            p.put("type", type);
        }
        p.put("required", required);
        p.put("default", parts.length > 2 ? parts[2] : "");
        p.put("desc", parts.length > 3 ? parts[3] : "");
        return p;
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static String str(JsonObject o, String k, String d) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d;
    }

    private static String sha1(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
