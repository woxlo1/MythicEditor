package dev.mythiceditor.core.yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a MythicMobs YAML file into its top-level entries (one Skill or Mob each) while keeping exact
 * character offsets. All edits are done as text splices on these offsets, so comments, blank lines,
 * unknown keys, key order and line endings outside the edited entry stay byte-identical.
 */
public final class YamlBlockParser {
    private static final Pattern TOP_KEY = Pattern.compile(
        "^(?:\"((?:[^\"\\\\]|\\\\.)*)\"|'((?:[^']|'')*)'|([^\\s#'\"\\-?:!&*\\[\\]{}|>%@`,][^:#]*?))\\s*:(?:[ \\t].*)?$");

    /** start/end are char offsets; end excludes the line terminator of the last body line. Lines are 0-based. */
    public record Block(String id, int start, int end, int startLine, int endLine) {}

    public record Parsed(List<Block> blocks, List<String> problems, String eol, boolean endsWithNewline) {}

    private YamlBlockParser() {}

    public static Parsed parse(String raw) {
        List<Block> blocks = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int base = raw.startsWith("\uFEFF") ? 1 : 0;

        int crlf = 0;
        int lf = 0;
        for (int i = base; i < raw.length(); i++) {
            if (raw.charAt(i) == '\n') {
                if (i > 0 && raw.charAt(i - 1) == '\r') crlf++;
                else lf++;
            }
        }
        String eol = crlf > lf ? "\r\n" : "\n";

        String curId = null;
        int curStart = -1;
        int curStartLine = -1;
        int lastBodyEnd = -1;
        int lastBodyLine = -1;
        int pos = base;
        int lineNo = 0;
        while (pos < raw.length()) {
            int nl = raw.indexOf('\n', pos);
            int lineEndWithEol = nl < 0 ? raw.length() : nl + 1;
            int contentEnd = nl < 0 ? raw.length() : nl;
            if (contentEnd > pos && raw.charAt(contentEnd - 1) == '\r') contentEnd--;
            String line = raw.substring(pos, contentEnd);

            boolean blank = line.isBlank();
            boolean col0 = !line.isEmpty() && !Character.isWhitespace(line.charAt(0));
            boolean col0Comment = col0 && line.charAt(0) == '#';
            boolean docMarker = col0 && (line.startsWith("---") || line.startsWith("..."));

            if (docMarker) {
                if (curId != null) {
                    blocks.add(new Block(curId, curStart, lastBodyEnd, curStartLine, lastBodyLine));
                    curId = null;
                }
            } else if (col0 && !col0Comment) {
                Matcher m = TOP_KEY.matcher(line);
                if (m.matches()) {
                    if (curId != null) blocks.add(new Block(curId, curStart, lastBodyEnd, curStartLine, lastBodyLine));
                    curId = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2).replace("''", "'") : m.group(3).trim();
                    curStart = pos;
                    curStartLine = lineNo;
                    lastBodyEnd = contentEnd;
                    lastBodyLine = lineNo;
                } else {
                    problems.add("Line " + (lineNo + 1) + ": unexpected top-level content");
                    if (curId != null) {
                        blocks.add(new Block(curId, curStart, lastBodyEnd, curStartLine, lastBodyLine));
                        curId = null;
                    }
                }
            } else if (curId != null && !blank && !col0Comment) {
                lastBodyEnd = contentEnd;
                lastBodyLine = lineNo;
            }
            pos = lineEndWithEol;
            lineNo++;
        }
        if (curId != null) blocks.add(new Block(curId, curStart, lastBodyEnd, curStartLine, lastBodyLine));
        return new Parsed(blocks, problems, eol, raw.endsWith("\n"));
    }

    /** Normalise line endings to \n and strip trailing whitespace at the very end (used for hashing / comparison). */
    public static String canonical(String text) {
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        int end = t.length();
        while (end > 0 && (t.charAt(end - 1) == '\n' || t.charAt(end - 1) == ' ' || t.charAt(end - 1) == '\t')) end--;
        return t.substring(0, end);
    }
}
