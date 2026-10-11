package dev.mythiceditor.core.yaml;

import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Structural safety checks with SnakeYAML's SafeConstructor: no custom tags, bounded aliases and nesting,
 * duplicate keys rejected. The parsed object graph is only inspected, never used to write files.
 */
public final class YamlValidator {
    public record Result(boolean ok, String message, int line, int column) {
        static Result good() {
            return new Result(true, null, -1, -1);
        }
    }

    private YamlValidator() {}

    private static Yaml newYaml() {
        LoaderOptions lo = new LoaderOptions();
        // Duplicate keys are allowed.
        // SnakeYAML keeps the last value when the same key appears again.
        lo.setAllowDuplicateKeys(true);
        // Prevent excessive alias expansion.
        lo.setMaxAliasesForCollections(20);
        // Prevent excessively deep YAML structures.
        lo.setNestingDepthLimit(40);
        // Limit the maximum YAML input size.
        lo.setCodePointLimit(8 * 1024 * 1024);
        return new Yaml(new SafeConstructor(lo));
    }

    /** Validates a whole file (possibly multiple documents). */
    public static Result validateFile(String text) {
        try {
            for (Object ignored : newYaml().loadAll(text)) {
                // iterate to force parsing
            }
            return Result.good();
        } catch (MarkedYAMLException e) {
            return fail(e);
        } catch (YAMLException | ClassCastException e) {
            return new Result(false, String.valueOf(e.getMessage()), -1, -1);
        }
    }

    /** Validates one entry: exactly one top-level key equal to {@code expectedId}, holding a mapping (or nothing). */
    public static Result validateBlock(String text, String expectedId) {
        try {
            Object doc = newYaml().load(text);
            if (!(doc instanceof Map<?, ?> m)) return new Result(false, "Entry must be a YAML mapping", 1, 1);
            if (m.size() != 1) return new Result(false, "Entry must contain exactly one top-level key (found " + m.size() + ")", 1, 1);
            Object key = m.keySet().iterator().next();
            if (!String.valueOf(key).equals(expectedId)) {
                return new Result(false, "Top-level key '" + key + "' does not match the entry name '" + expectedId + "'", 1, 1);
            }
            Object body = m.get(key);
            if (body != null && !(body instanceof Map<?, ?>)) return new Result(false, "'" + expectedId + "' must contain a mapping", 1, 1);
            return Result.good();
        } catch (MarkedYAMLException e) {
            return fail(e);
        } catch (YAMLException | ClassCastException e) {
            return new Result(false, String.valueOf(e.getMessage()), -1, -1);
        }
    }

    private static Result fail(MarkedYAMLException e) {
        var mark = e.getProblemMark();
        String p = e.getProblem();
        String c = e.getContext();
        String msg = (c == null ? "" : c + ": ") + (p == null ? "invalid YAML" : p);
        return new Result(false, msg, mark == null ? -1 : mark.getLine() + 1, mark == null ? -1 : mark.getColumn() + 1);
    }
}
