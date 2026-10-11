package dev.mythiceditor.core.index;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.mythiceditor.core.catalog.CatalogService;

/**
 * Lightweight, allocation-friendly scan of one entry's text that extracts what other tools need:
 * which Skills / Mobs it references and which mechanics it uses. The real language service (completion,
 * diagnostics) lives in the browser; this is only for the server side index and the relation graph.
 */
public final class RefScanner {
    public record Refs(Set<String> skills, Set<String> mobs, Set<String> mechanics, Set<String> bare, String typeValue, String display) {
        public static final Refs EMPTY = new Refs(Set.of(), Set.of(), Set.of(), Set.of(), null, null);
    }

    private static final Pattern KEY = Pattern.compile("^(\\s*)([A-Za-z0-9_]+)\\s*:\\s*(.*)$");
    private static final Pattern ITEM = Pattern.compile("^(\\s*)-\\s+(.*)$");
    private static final Pattern NAME = Pattern.compile("^([A-Za-z0-9_:.\\-]+)");
    private static final Set<String> SKILL_SCALAR_KEYS = Set.of("oncooldownskill", "failedconditionsskill");
    private static final Set<String> SKILL_LINE_SECTIONS = Set.of("skills");

    private final CatalogService catalog;

    public RefScanner(CatalogService catalog) {
        this.catalog = catalog;
    }

    public Refs scan(String blockText) {
        Set<String> skills = new LinkedHashSet<>();
        Set<String> mobs = new LinkedHashSet<>();
        Set<String> mechanics = new LinkedHashSet<>();
        Set<String> bare = new LinkedHashSet<>();
        String type = null;
        String display = null;

        int bodyIndent = -1;
        String section = "";
        String[] lines = blockText.split("\r?\n", -1);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            int indent = line.length() - line.stripLeading().length();
            if (bodyIndent < 0) bodyIndent = indent;

            Matcher k = KEY.matcher(line);
            if (indent == bodyIndent && k.matches()) {
                section = k.group(2).toLowerCase(Locale.ROOT);
                String v = stripComment(k.group(3)).trim();
                if (SKILL_SCALAR_KEYS.contains(section) && !v.isEmpty()) skills.add(unquote(v));
                if (section.equals("type") && !v.isEmpty()) type = unquote(v);
                if (section.equals("display") && !v.isEmpty()) display = unquote(v);
                if (section.equals("template") && !v.isEmpty()) {
                    for (String t : unquote(v).split(",")) if (!t.isBlank()) mobs.add(t.trim());
                }
                continue;
            }
            Matcher it = ITEM.matcher(line);
            if (it.matches() && SKILL_LINE_SECTIONS.contains(section)) {
                scanSkillLine(stripComment(it.group(2)).trim(), skills, mobs, mechanics, bare);
            }
        }
        return new Refs(skills, mobs, mechanics, bare, type, display);
    }

    private void scanSkillLine(String raw, Set<String> skills, Set<String> mobs, Set<String> mechanics, Set<String> bare) {
        String line = unquote(raw);
        Matcher m = NAME.matcher(line);
        if (!m.find()) return;
        String name = m.group(1);
        String lower = name.toLowerCase(Locale.ROOT);
        String rest = line.substring(m.end());

        int colon = lower.indexOf(':');
        if (colon > 0) {
            String prefix = lower.substring(0, colon);
            if (prefix.equals("skill") || prefix.equals("metaskill") || prefix.equals("meta")) {
                String ref = name.substring(colon + 1);
                if (!ref.isEmpty()) skills.add(ref);
                mechanics.add("skill");
                return;
            }
        }
        if (catalog.isMechanic(lower)) mechanics.add(lower);
        else bare.add(name);

        if (rest.startsWith("{")) {
            List<String> args = balancedArgs(rest);
            if (!args.isEmpty()) {
                Set<String> skillP = catalog.skillParamNames(lower);
                Set<String> mobP = catalog.mobParamNames(lower);
                for (String kv : args) {
                    int eq = kv.indexOf('=');
                    if (eq <= 0) continue;
                    String key = kv.substring(0, eq).trim().toLowerCase(Locale.ROOT);
                    String val = kv.substring(eq + 1).trim();
                    if (skillP.contains(key)) addList(val, skills);
                    else if (mobP.contains(key)) addList(val, mobs);
                }
            }
        }
    }

    private static void addList(String value, Set<String> into) {
        for (String s : unquote(value).split(",")) {
            String t = s.trim();
            if (!t.isEmpty() && !t.contains("<") && !t.contains("{")) into.add(t);
        }
    }

    /** Splits the top-level "k=v;k=v" pairs of a "{...}" group, honouring nesting and quotes. */
    static List<String> balancedArgs(String s) {
        List<String> out = new ArrayList<>();
        if (s.isEmpty() || s.charAt(0) != '{') return out;
        int depth = 0;
        char quote = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
            } else if (c == '{' || c == '[') {
                if (depth > 0) cur.append(c);
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) {
                    if (cur.length() > 0) out.add(cur.toString());
                    return out;
                }
                cur.append(c);
            } else if (c == ';' && depth == 1) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    static String stripComment(String s) {
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(s.charAt(i - 1)))) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2 && ((t.startsWith("\"") && t.endsWith("\"")) || (t.startsWith("'") && t.endsWith("'")))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
