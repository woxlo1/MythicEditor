/**
 * Lightweight, regex-based context detection for one Skill/Mob entry buffer. This mirrors the pragmatic,
 * "no full AST" approach used server-side (RefScanner/YamlBlockParser): MythicMobs' skill-line DSL is not
 * plain YAML, so a hand-rolled scanner tuned to its actual syntax is more robust than a generic YAML AST.
 */
export type EditorKind = "skill" | "mob" | "item" | "droptable";

export type Ctx =
  | { area: "topKey" }
  | { area: "topKeyValue"; key: string }
  | { area: "skillsListStart" }
  | { area: "mechanicName" }
  | { area: "targeter" }
  | { area: "trigger"; hasColon: boolean }
  | { area: "mechanicParamName"; mechanic: string; open: number }
  | { area: "mechanicParamValue"; mechanic: string; param: string; open: number }
  | { area: "conditionsListMechanic" }
  | { area: "mobOptionKey" }
  | { area: "mobOptionValue"; key: string }
  | { area: "aiGoal" }
  | { area: "aiTarget" }
  | { area: "placeholder" }
  | { area: "none" };

export interface Line {
  text: string;
  indent: number;
}

function lineInfo(text: string): Line {
  const m = /^(\s*)/.exec(text);
  return { text, indent: m ? m[1].length : 0 };
}

/** Finds the nearest ancestor "Key:" line (indent 2, i.e. directly under the entry name) above `lineNumber`. */
function findSection(lines: string[], lineNumber: number): string | null {
  for (let i = lineNumber - 1; i >= 1; i--) {
    const raw = lines[i];
    const info = lineInfo(raw);
    if (info.indent === 2) {
      const m = /^\s*([A-Za-z0-9_]+)\s*:/.exec(raw);
      if (m) return m[1];
    }
    if (info.indent === 0 && raw.trim() !== "") break;
  }
  return null;
}

/** Splits "- damage{amount=10;ia=true} @Target ~onAttack" into its recognizable pieces, cursor-aware. */
function analyzeSkillLine(textBeforeCursor: string): Ctx {
  // strip leading "- "
  const dash = /^\s*-\s?/.exec(textBeforeCursor);
  const body = dash ? textBeforeCursor.slice(dash[0].length) : textBeforeCursor;

  const lastAt = body.lastIndexOf("@");
  const lastTilde = body.lastIndexOf("~");
  const lastOpenBrace = body.lastIndexOf("{");
  const lastCloseBrace = body.lastIndexOf("}");
  const lastOpenAngle = body.lastIndexOf("<");
  const lastCloseAngle = body.lastIndexOf(">");
  const lastSpace = Math.max(body.lastIndexOf(" "), body.lastIndexOf(";"), body.lastIndexOf("{"));

  if (lastOpenAngle > lastCloseAngle && lastOpenAngle >= 0) return { area: "placeholder" };

  if (lastOpenBrace > lastCloseBrace && lastOpenBrace >= 0) {
    const mechMatch = /([A-Za-z0-9_:]+)\s*\{[^{}]*$/.exec(body.slice(0, lastOpenBrace + 1));
    const mechanic = (mechMatch ? mechMatch[1] : "").split(":").pop() ?? "";
    const inner = body.slice(lastOpenBrace + 1);
    const lastSemi = Math.max(inner.lastIndexOf(";"), 0);
    const currentArg = inner.slice(lastOpenBrace >= 0 ? lastSemi === 0 && !inner.includes(";") ? 0 : lastSemi + (inner[lastSemi] === ";" ? 1 : 0) : 0);
    const eq = currentArg.indexOf("=");
    if (eq >= 0) {
      const param = currentArg.slice(0, eq).trim().toLowerCase();
      return { area: "mechanicParamValue", mechanic, param, open: lastOpenBrace };
    }
    return { area: "mechanicParamName", mechanic, open: lastOpenBrace };
  }

  if (lastAt > lastTilde && lastAt > lastSpace - 1 && lastAt === body.length - 1 - (body.length - 1 - lastAt) && lastAt >= 0 && /^@[A-Za-z]*$/.test(body.slice(lastAt))) {
    return { area: "targeter" };
  }
  if (lastTilde >= 0 && /^~[A-Za-z]*(:[A-Za-z0-9_]*)?$/.test(body.slice(lastTilde))) {
    return { area: "trigger", hasColon: body.slice(lastTilde).includes(":") };
  }
  // still typing the mechanic name at the start of the line (no space yet)
  if (!/\s/.test(body.trim()) && lastAt < 0 && lastTilde < 0) {
    return { area: "mechanicName" };
  }
  return { area: "none" };
}

export function parseContext(fullText: string, offset: number, kind: EditorKind): Ctx {
  const before = fullText.slice(0, offset);
  const lines = before.split("\n");
  const lineNumber = lines.length; // 1-based, matches split index+1 semantics used in findSection
  const currentLine = lines[lines.length - 1];
  const info = lineInfo(currentLine);
  const allLines = fullText.split("\n");

  if (lineNumber === 1 || info.indent === 0) {
    // top-level "Key:" line (the entry name itself is line 1; further indent-0 lines shouldn't normally occur)
    return { area: "none" };
  }

  const section = findSection(allLines, lineNumber);
  const trimmed = currentLine.trimStart();
  const isListItem = /^-\s?/.test(trimmed) || currentLine.trimEnd().endsWith("-");

  if (info.indent === 2 && trimmed.length <= 1) {
    return { area: "topKey" };
  }

  const sectionLower = (section ?? "").toLowerCase();

  if (sectionLower === "skills" || sectionLower === "oncooldownskill" || sectionLower === "failedconditionsskill") {
    if (isListItem) return analyzeSkillLine(currentLine);
    return { area: "none" };
  }
  if (sectionLower === "conditions" || sectionLower === "targetconditions" || sectionLower === "triggerconditions") {
    if (isListItem) {
      const body = trimmed.replace(/^-\s?/, "");
      if (body.includes("{")) {
        const open = body.lastIndexOf("{");
        const close = body.lastIndexOf("}");
        if (open > close) {
          const mechMatch = /([A-Za-z0-9_]+)\s*\{[^{}]*$/.exec(body.slice(0, open + 1));
          return { area: "mechanicParamName", mechanic: mechMatch ? mechMatch[1] : "", open };
        }
      }
      return { area: "conditionsListMechanic" };
    }
    return { area: "none" };
  }
  if (sectionLower === "options" && kind === "mob") {
    const eq = trimmed.indexOf(":");
    if (eq < 0) return { area: "mobOptionKey" };
    return { area: "mobOptionValue", key: trimmed.slice(0, eq).trim() };
  }
  if (sectionLower === "aigoalselectors") return { area: "aiGoal" };
  if (sectionLower === "aitargetselectors") return { area: "aiTarget" };
  if (sectionLower === "type" && kind === "mob") return { area: "topKeyValue", key: "Type" };

  if (info.indent === 2) {
    const m = /^([A-Za-z0-9_]*)/.exec(trimmed);
    if (m && !trimmed.includes(":")) return { area: "topKey" };
  }

  return { area: "none" };
}
