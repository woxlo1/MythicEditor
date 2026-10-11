/**
 * Line-splice based structural view over one Skill/Mob entry's raw text, mirroring the server's philosophy
 * (dev.mythiceditor.core.storage.FileStore / yaml.YamlBlockParser): editing one field only ever rewrites
 * the lines that field owns, so Visual Editor changes never disturb comments, unknown keys or unrelated
 * sections. This is what keeps the Visual Editor and the Code Editor as two views over one shared model
 * (the raw text) instead of two independent copies of the data.
 */
export class EntryDoc {
  lines: string[];

  constructor(text: string) {
    this.lines = text.replace(/\r\n/g, "\n").split("\n");
  }

  toText(): string {
    return this.lines.join("\n");
  }

  clone(): EntryDoc {
    const d = new EntryDoc("");
    d.lines = [...this.lines];
    return d;
  }

  private indentOf(line: string): number {
    const m = /^(\s*)/.exec(line);
    return m ? m[1].length : 0;
  }

  private isBlankOrComment(line: string): boolean {
    const t = line.trim();
    return t === "" || t.startsWith("#");
  }

  /** end index (exclusive) of the block that starts right after `headerIndex`, at indent strictly greater than `indent`. */
  private blockEnd(headerIndex: number, indent: number): number {
    for (let i = headerIndex + 1; i < this.lines.length; i++) {
      const line = this.lines[i];
      if (this.isBlankOrComment(line)) continue;
      if (this.indentOf(line) <= indent) return i;
    }
    return this.lines.length;
  }

  private findKeyLine(key: string, indent: number, fromIndex = 1, toIndex = this.lines.length): number {
    const re = new RegExp(`^\\s{${indent}}${escapeRe(key)}\\s*:`, "i");
    for (let i = fromIndex; i < toIndex; i++) {
      if (this.indentOf(this.lines[i]) !== indent) continue;
      if (re.test(this.lines[i])) return i;
    }
    return -1;
  }

  // ---- top-level (indent 2) scalar fields --------------------------------------------------------

  scalar(key: string): string | null {
    const i = this.findKeyLine(key, 2);
    if (i < 0) return null;
    const m = /^\s*[A-Za-z0-9_]+\s*:\s?(.*)$/.exec(this.lines[i]);
    const raw = m ? m[1] : "";
    if (raw.trim() === "" && this.blockEnd(i, 2) > i + 1) return null; // it's a block key, not a scalar
    return unquote(raw.trim());
  }

  setScalar(key: string, value: string) {
    const i = this.findKeyLine(key, 2);
    const quoted = quoteIfNeeded(value);
    if (i >= 0) {
      if (value === "") {
        this.lines.splice(i, 1);
      } else {
        this.lines[i] = `  ${key}: ${quoted}`;
      }
      return;
    }
    if (value === "") return;
    const insertAt = this.firstTopSectionLine();
    this.lines.splice(insertAt, 0, `  ${key}: ${quoted}`);
  }

  /** Index right after the header (line 0), before the first existing indent-2 key. Used to group new scalars together. */
  private firstTopSectionLine(): number {
    for (let i = 1; i < this.lines.length; i++) {
      if (this.isBlankOrComment(this.lines[i])) continue;
      if (this.indentOf(this.lines[i]) === 2) return i;
    }
    return this.lines.length;
  }

  // ---- list sections (Skills / Equipment / Drops / AIGoalSelectors / ...) -----------------------

  listItems(key: string): string[] {
    const i = this.findKeyLine(key, 2);
    if (i < 0) return [];
    const end = this.blockEnd(i, 2);
    const items: string[] = [];
    for (let j = i + 1; j < end; j++) {
      const line = this.lines[j];
      if (this.isBlankOrComment(line)) continue;
      const m = /^\s*-\s?(.*)$/.exec(line);
      if (m) items.push(m[1]);
    }
    return items;
  }

  setListItems(key: string, items: string[]) {
    const i = this.findKeyLine(key, 2);
    const rendered = items.map((it) => `  - ${it}`);
    if (i >= 0) {
      const end = this.blockEnd(i, 2);
      if (items.length === 0) {
        this.lines.splice(i, end - i);
      } else {
        this.lines.splice(i + 1, end - i - 1, ...rendered);
      }
      return;
    }
    if (items.length === 0) return;
    this.lines.push(`  ${key}:`, ...rendered);
  }

  hasSection(key: string): boolean {
    return this.findKeyLine(key, 2) >= 0;
  }

  // ---- Options (indent 2 "Options:" containing indent 4 "Key: value") ----------------------------

  optionValue(name: string): string | null {
    const opt = this.findKeyLine("Options", 2);
    if (opt < 0) return null;
    const end = this.blockEnd(opt, 2);
    const ki = this.findKeyLine(name, 4, opt + 1, end);
    if (ki < 0) return null;
    const m = /^\s*[A-Za-z0-9_]+\s*:\s?(.*)$/.exec(this.lines[ki]);
    return m ? unquote(m[1].trim()) : null;
  }

  allOptions(): { key: string; value: string }[] {
    const opt = this.findKeyLine("Options", 2);
    if (opt < 0) return [];
    const end = this.blockEnd(opt, 2);
    const out: { key: string; value: string }[] = [];
    for (let i = opt + 1; i < end; i++) {
      if (this.indentOf(this.lines[i]) !== 4) continue;
      const m = /^\s*([A-Za-z0-9_]+)\s*:\s?(.*)$/.exec(this.lines[i]);
      if (m) out.push({ key: m[1], value: unquote(m[2].trim()) });
    }
    return out;
  }

  setOption(name: string, value: string | null) {
    let opt = this.findKeyLine("Options", 2);
    if (opt < 0) {
      if (value === null) return;
      const at = this.firstTopSectionLine();
      this.lines.splice(at, 0, "  Options:");
      opt = at;
    }
    const end = this.blockEnd(opt, 2);
    const ki = this.findKeyLine(name, 4, opt + 1, end);
    if (value === null) {
      if (ki >= 0) this.lines.splice(ki, 1);
      if (this.blockEnd(opt, 2) === opt + 1) this.lines.splice(opt, 1); // drop an now-empty Options:
      return;
    }
    const rendered = `    ${name}: ${quoteIfNeeded(value)}`;
    if (ki >= 0) this.lines[ki] = rendered;
    else this.lines.splice(end, 0, rendered);
  }
}

// ---- skill-line parsing (mechanic{args} @targeter ~trigger) --------------------------------------

export interface SkillLineArg {
  key: string;
  value: string;
}

export interface SkillLine {
  mechanic: string;
  args: SkillLineArg[];
  targeter: string;
  trigger: string;
  triggerArg: string;
  raw: string;
}

export function parseSkillLine(raw: string): SkillLine {
  let rest = raw;
  let mechanic = "";
  let argsPart = "";
  const braceOpen = rest.indexOf("{");
  if (braceOpen >= 0) {
    const braceClose = findMatchingBrace(rest, braceOpen);
    mechanic = rest.slice(0, braceOpen).trim();
    argsPart = braceClose > braceOpen ? rest.slice(braceOpen + 1, braceClose) : "";
    rest = rest.slice((braceClose > braceOpen ? braceClose : rest.length) + 1);
  } else {
    const sp = rest.search(/\s/);
    mechanic = (sp >= 0 ? rest.slice(0, sp) : rest).trim();
    rest = sp >= 0 ? rest.slice(sp) : "";
  }
  const atMatch = /@([^\s~]+)/.exec(rest);
  const tildeMatch = /~([A-Za-z][A-Za-z0-9_]*)(?::([^\s]*))?/.exec(rest);
  return {
    mechanic,
    args: argsPart ? splitArgsTopLevel(argsPart).map(toArg) : [],
    targeter: atMatch ? atMatch[1] : "",
    trigger: tildeMatch ? tildeMatch[1] : "",
    triggerArg: tildeMatch && tildeMatch[2] !== undefined ? tildeMatch[2] : "",
    raw
  };
}

export function serializeSkillLine(s: Omit<SkillLine, "raw">): string {
  let out = s.mechanic;
  if (s.args.length) out += `{${s.args.map((a) => `${a.key}=${a.value}`).join(";")}}`;
  if (s.targeter) out += ` @${s.targeter}`;
  if (s.trigger) out += ` ~${s.trigger}${s.triggerArg ? ":" + s.triggerArg : ""}`;
  return out;
}

function findMatchingBrace(s: string, openIndex: number): number {
  let depth = 0;
  for (let i = openIndex; i < s.length; i++) {
    if (s[i] === "{") depth++;
    else if (s[i] === "}") {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

function splitArgsTopLevel(inner: string): string[] {
  const out: string[] = [];
  let depth = 0, cur = "";
  for (const c of inner) {
    if (c === "{" || c === "[") depth++;
    if (c === "}" || c === "]") depth--;
    if (c === ";" && depth === 0) {
      out.push(cur);
      cur = "";
    } else cur += c;
  }
  if (cur.trim()) out.push(cur);
  return out;
}

function toArg(pair: string): SkillLineArg {
  const eq = pair.indexOf("=");
  return eq < 0 ? { key: pair.trim(), value: "" } : { key: pair.slice(0, eq).trim(), value: pair.slice(eq + 1).trim() };
}

function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function unquote(s: string): string {
  if (s.length >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
    return s.slice(1, -1);
  }
  return s;
}

function quoteIfNeeded(v: string): string {
  if (v === "") return "''";
  if (/^[A-Za-z0-9_.\-]+$/.test(v)) return v; // simple token, no quoting needed
  if (/^(true|false|null|~)$/i.test(v)) return v;
  if (/^-?\d+(\.\d+)?$/.test(v)) return v;
  if (v.includes("'")) return `"${v.replace(/"/g, '\\"')}"`;
  return `'${v}'`;
}
