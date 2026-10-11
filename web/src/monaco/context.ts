/**
 * Lightweight, regex-based context detection for one Skill/Mob entry buffer. This mirrors the pragmatic,
 * "no full AST" approach used server-side (RefScanner/YamlBlockParser): MythicMobs' skill-line DSL is not
 * plain YAML, so a hand-rolled scanner tuned to its actual syntax is more robust than a generic YAML AST.
 */
export type EditorKind = "skill" | "mob" | "item" | "droptable";

export type ParamOwner = "mechanic" | "targeter" | "condition";

export type Ctx =
  | { area: "topKey" }
  | { area: "topKeyValue"; key: string }
  | { area: "skillsListStart" }
  | { area: "mechanicName"; partial: string }
  | { area: "targeter"; partial: string }
  | { area: "trigger"; hasColon: boolean; partial: string }
  | { area: "inlineCondition"; partial: string }
  | { area: "mechanicParamName"; owner: ParamOwner; mechanic: string; open: number; used: string[]; partial: string }
  | { area: "mechanicParamValue"; owner: ParamOwner; mechanic: string; param: string; open: number; used: string[]; partial: string; inString: boolean }
  | { area: "conditionsListMechanic"; partial: string }
  | { area: "conditionAction"; partial: string }
  | { area: "conditionActionSkill"; partial: string }
  | { area: "mobOptionKey" }
  | { area: "mobOptionValue"; key: string }
  | { area: "aiGoal" }
  | { area: "aiTarget" }
  | { area: "placeholder"; partial: string }
  | { area: "none" };


/** Returns true when the cursor is inside a YAML/skill-line comment, not inside a quoted value. */
export function isCommentPosition(line: string, cursorOffset: number): boolean {
  const limit = Math.max(0, Math.min(cursorOffset, line.length));
  let quote: string | null = null;
  let escaped = false;
  for (let i = 0; i < limit; i++) {
    const c = line[i];
    if (quote) {
      if (escaped) escaped = false;
      else if (c === "\\") escaped = true;
      else if (c === quote) quote = null;
      continue;
    }
    if ((c === "\"" || c === "'") && (i === 0 || /[=:,\s]/.test(line[i - 1]))) {
      quote = c;
      continue;
    }
    if (c === "#" && (i === 0 || /\s/.test(line[i - 1]))) return true;
  }
  return false;
}

/** Removes an inline comment while preserving # inside quoted strings and unspaced hex colors. */
export function stripInlineComment(line: string): string {
  let quote: string | null = null;
  let escaped = false;
  for (let i = 0; i < line.length; i++) {
    const c = line[i];
    if (quote) {
      if (escaped) escaped = false;
      else if (c === "\\") escaped = true;
      else if (c === quote) quote = null;
      continue;
    }
    if ((c === "\"" || c === "'") && (i === 0 || /[=:,\s]/.test(line[i - 1]))) {
      quote = c;
      continue;
    }
    if (c === "#" && (i === 0 || /\s/.test(line[i - 1]))) return line.slice(0, i).replace(/[ \t]+$/, "");
  }
  return line;
}

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

/** A `{...}` argument block that is still open at the cursor: which mechanic/targeter/condition owns it. */
export interface CallSite {
  owner: ParamOwner;
  name: string;
  open: number;
  /** lower-cased keys already written earlier in this block (so they are not offered again) */
  used: string[];
  /** lower-cased key whose value is being typed, or null while a key is being typed */
  key: string | null;
  /** text typed so far for the current key (key === null) or value (key !== null) */
  partial: string;
  /** the cursor is inside a quoted value, where no catalog suggestions make sense */
  inString: boolean;
}

/**
 * Finds the innermost unclosed `{` of a skill/condition line (cursor is at the end of `body`) and describes it.
 * Quotes only open after `=` (so an apostrophe in plain text such as `m=Don't` cannot hide the rest of the line),
 * and `;` inside quotes or nested braces does not separate arguments.
 */
export function findCallSite(body: string, fallbackOwner: ParamOwner = "mechanic"): CallSite | null {
  const stack: number[] = [];
  let quote: string | null = null;
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (quote) {
      if (c === quote) quote = null;
      continue;
    }
    if ((c === '"' || c === "'") && body[i - 1] === "=") { quote = c; continue; }
    if (c === "{") stack.push(i);
    else if (c === "}") stack.pop();
  }
  if (!stack.length) return null;
  const open = stack[stack.length - 1];

  const head = /([@?!~]*)([A-Za-z0-9_:.\-]+)$/.exec(body.slice(0, open));
  if (!head) return null;
  const prefix = head[1];
  const owner: ParamOwner = prefix.includes("@") ? "targeter" : prefix.includes("?") ? "condition" : fallbackOwner;
  let name = head[2];
  if (owner === "mechanic") {
    const lower = name.toLowerCase();
    name = lower.startsWith("skill:") || lower.startsWith("metaskill:") ? "skill" : (name.split(":").pop() ?? name);
  }

  // split the arguments written so far on top-level ';'
  const pieces: string[] = [];
  let cur = "", depth = 0, q: string | null = null;
  const inner = body.slice(open + 1);
  for (let i = 0; i < inner.length; i++) {
    const c = inner[i];
    if (q) { cur += c; if (c === q) q = null; continue; }
    if ((c === '"' || c === "'") && inner[i - 1] === "=") { q = c; cur += c; continue; }
    if (c === "{" || c === "[") depth++;
    if (c === "}" || c === "]") depth--;
    if (c === ";" && depth === 0) { pieces.push(cur); cur = ""; continue; }
    cur += c;
  }
  const used = pieces.map((p) => p.split("=")[0].trim().toLowerCase()).filter(Boolean);
  const eq = cur.indexOf("=");
  if (eq < 0) return { owner, name, open, used, key: null, partial: cur.trimStart(), inString: false };
  return { owner, name, open, used, key: cur.slice(0, eq).trim().toLowerCase(), partial: cur.slice(eq + 1).trimStart(), inString: q !== null };
}

function paramCtx(site: CallSite): Ctx {
  return site.key === null
    ? { area: "mechanicParamName", owner: site.owner, mechanic: site.name, open: site.open, used: site.used, partial: site.partial }
    : { area: "mechanicParamValue", owner: site.owner, mechanic: site.name, param: site.key, open: site.open, used: site.used, partial: site.partial, inString: site.inString };
}

/** Splits "- damage{amount=10;ia=true} @Target ~onAttack" into its recognizable pieces, cursor-aware. */
export function analyzeSkillLine(textBeforeCursor: string): Ctx {
  // strip leading "- "
  const dash = /^\s*-\s?/.exec(textBeforeCursor);
  const body = dash ? textBeforeCursor.slice(dash[0].length) : textBeforeCursor;

  // <placeholder>  — but not a comparison such as `health{a=<50}`
  const ph = /<([A-Za-z][A-Za-z0-9_.]*)?$/.exec(body);
  if (ph && (ph[1] || !/[=!]$/.test(body.slice(0, ph.index)))) return { area: "placeholder", partial: ph[1] ?? "" };

  const site = findCallSite(body, "mechanic");
  if (site) return paramCtx(site);

  const tg = /(?:^|\s)@([A-Za-z0-9_]*)$/.exec(body);
  if (tg) return { area: "targeter", partial: tg[1] };
  const tr = /(?:^|\s)~([A-Za-z0-9_]*)(:[^\s]*)?$/.exec(body);
  if (tr) return { area: "trigger", hasColon: tr[2] !== undefined, partial: tr[1] };
  const ic = /(?:^|\s)\?[!~]?([A-Za-z0-9_]*)$/.exec(body);
  if (ic) return { area: "inlineCondition", partial: ic[1] };
  // still typing the mechanic name at the start of the line (e.g. "dama" or "effect:part")
  if (/^[A-Za-z0-9_:.\-]*$/.test(body)) return { area: "mechanicName", partial: body };
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
      const site = findCallSite(body, "condition");
      if (site) return paramCtx(site);
      // "- sunny orElseCast <skill>": the argument of a cast-style action is a skill name
      const skillArg = /\s(?:cast|castinstead|orelsecast)\s+([A-Za-z0-9_\-]*)$/i.exec(body);
      if (skillArg) return { area: "conditionActionSkill", partial: skillArg[1] };
      // "- health{h=>10} po": the action word that follows a complete condition
      const action = /^[A-Za-z0-9_:.\-!~?]+(?:\{[^{}]*\})?\s+([A-Za-z]*)$/.exec(body);
      if (action) return { area: "conditionAction", partial: action[1] };
      if (/^[A-Za-z0-9_:.\-!~?]*$/.test(body)) return { area: "conditionsListMechanic", partial: body.replace(/^[!~?]+/, "") };
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
