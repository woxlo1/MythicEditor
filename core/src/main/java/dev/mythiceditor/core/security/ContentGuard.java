package dev.mythiceditor.core.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Finds skill lines that use "restricted" mechanics (the ones that run server commands).
 *
 * <p>Anyone with {@code mythiceditor.edit} can write arbitrary Skills, and a Skill that runs a console command is
 * effectively server-admin power. Players without {@code mythiceditor.unrestricted} may therefore not ADD such a
 * line; lines that already exist and are left untouched stay editable around.
 *
 * <p>Detection works on the <em>decoded</em> YAML scalars (not the raw text), so YAML escapes ({@code "\u0063ommand{"}),
 * folded/block scalars and flow lists cannot be used to hide a line. This is a deny-list, not a sandbox: it only
 * knows the mechanic names it is configured with (see {@code security.restricted-mechanics}).
 */
public final class ContentGuard {
    // optional list dash / quotes, the mechanic name, followed by {, [, whitespace+@/~/? , or end of line (conservative)
    private static final Pattern LINE = Pattern.compile("^[\\s\\-'\"]*([A-Za-z0-9_]+)\\s*(\\{|\\[|[ \\t]+[@~?]|$)");

    private final Set<String> restricted = new HashSet<>();
    private final Set<String> allowed = new HashSet<>();

    public ContentGuard(Collection<String> names) {
        this(names, java.util.Collections.emptyList());
    }

    public ContentGuard(Collection<String> names, Collection<String> allowedList) {
        for (String n : names) {
            if (n != null && !n.isBlank()) restricted.add(n.trim().toLowerCase(Locale.ROOT));
        }
        for (String n : allowedList == null ? java.util.Collections.emptyList() : allowedList) {
            if (n != null && !n.isBlank()) allowed.add(n.trim().toLowerCase(Locale.ROOT));
        }
    }

    public boolean enabled() {
        return !restricted.isEmpty();
    }

    public boolean hasAllowList() {
        return !allowed.isEmpty();
    }

    public Set<String> allowedMechanics() {
        return allowed;
    }

    /** Every restricted line (trimmed) found in the YAML text, duplicates included. Unparseable YAML yields none. */
    public List<String> restrictedLines(String yaml) {
        List<String> out = new ArrayList<>();
        if (yaml == null || yaml.isBlank()) return out;
        if (!enabled() && !hasAllowList()) return out;
        Object doc;
        try {
            doc = newYaml().load(yaml);
        } catch (RuntimeException e) {
            return out;
        }
        walk(doc, out, 0);
        return out;
    }

    /** Restricted lines present in {@code newText} beyond those already in {@code oldText} (empty = nothing new). */
    public List<String> added(String oldText, String newText) {
        Map<String, Integer> have = new HashMap<>();
        for (String l : restrictedLines(oldText)) have.merge(l, 1, Integer::sum);
        List<String> added = new ArrayList<>();
        for (String l : restrictedLines(newText)) {
            int n = have.getOrDefault(l, 0);
            if (n > 0) have.put(l, n - 1);
            else added.add(l);
        }
        return added;
    }

    /** The mechanic name a restricted line uses (for error messages). */
    public String mechanicOf(String line) {
        Matcher m = LINE.matcher(line);
        return m.find() ? m.group(1) : line;
    }

    private void walk(Object node, List<String> out, int depth) {
        if (depth > 60 || node == null) return;
        if (node instanceof String s) {
            for (String line : s.split("\\R")) {
                Matcher m = LINE.matcher(line);
                if (!m.find()) continue;
                String mech = m.group(1).toLowerCase(Locale.ROOT);
                boolean isRestricted = restricted.contains(mech);
                boolean blockedByAllowList = hasAllowList() && !allowed.contains(mech);
                if (isRestricted || blockedByAllowList) {
                    out.add(line.trim());
                }
            }
        } else if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                walk(e.getKey(), out, depth + 1);
                walk(e.getValue(), out, depth + 1);
            }
        } else if (node instanceof Iterable<?> it) {
            for (Object o : it) walk(o, out, depth + 1);
        }
    }

    private static Yaml newYaml() {
        LoaderOptions lo = new LoaderOptions();
        lo.setAllowDuplicateKeys(true);
        lo.setMaxAliasesForCollections(20);
        lo.setNestingDepthLimit(40);
        lo.setCodePointLimit(8 * 1024 * 1024);
        return new Yaml(new SafeConstructor(lo));
    }
}
