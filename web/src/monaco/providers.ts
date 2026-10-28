import * as monaco from "monaco-editor/esm/vs/editor/editor.api";
import { LANGUAGE_ID } from "./language";
import { parseContext, findCallSite, isCommentPosition, stripInlineComment, type EditorKind, type ParamOwner } from "./context";
import { CatalogIndex, NameIndex } from "./catalogIndex";
import type { CatalogEntry, CatalogParam } from "../types";

const VALUE_TYPE_ENUM_KEY: Record<string, string> = {
  particle: "particle", potion: "potion", material: "material", sound: "sound",
  entitytype: "entitytype", color: "" // color uses #RRGGBB, no fixed list
};

/** `name{a=${1:default};b=${2}}` for the entry's main parameters (case-insensitive match), or just `name`. */
function snippetForParams(name: string, entry: CatalogEntry | undefined, maxMain = 2): string {
  if (!entry || !entry.params || entry.params.length === 0) return name;
  const main = (entry.main && entry.main.length ? entry.main : [entry.params[0].names[0]]).map((m) => m.toLowerCase());
  const chosen = entry.params.filter((p) => p.required || p.names.some((n) => main.includes(n.toLowerCase()))).slice(0, Math.max(maxMain, 1) + 2);
  if (chosen.length === 0) return name;
  let i = 1;
  const esc = (v: string) => v.replace(/[\\$}]/g, "\\$&");
  const parts = chosen.map((p) => `${p.names[0]}=\${${i++}:${esc(p.default || (p.type === "boolean" ? "false" : ""))}}`);
  return `${name}{${parts.join(";")}}$0`;
}

function paramDoc(p: CatalogParam, owner?: string): monaco.IMarkdownString {
  let md = `**${p.names[0]}**${p.names.length > 1 ? `  \`${p.names.slice(1).join(", ")}\`` : ""}  *(${p.type}${p.required ? ", required" : ""})*`;
  if (p.default) md += `\n\nDefault: \`${p.default}\``;
  if (p.desc) md += `\n\n${p.desc}`;
  if (owner) md += `\n\n_${owner}_`;
  return { value: md, isTrusted: false };
}

function docFor(entry: CatalogEntry): monaco.IMarkdownString {
  let md = `**${entry.name}**`;
  if (entry.aliases.length) md += `  \`${entry.aliases.join(", ")}\``;
  md += `\n\n${entry.desc}`;
  if (entry.source === "runtime") md += `\n\n_Detected from the installed MythicMobs (no bundled documentation)._`;
  if (entry.params && entry.params.length) {
    md += `\n\n**Parameters**\n`;
    for (const p of entry.params) {
      md += `- \`${p.names.join(", ")}\` *(${p.type}${p.required ? ", required" : ""})*${p.default ? ` — default \`${p.default}\`` : ""}${p.desc ? ` — ${p.desc}` : ""}\n`;
    }
  }
  return { value: md, isTrusted: false };
}

function kindOfCompletion(m: typeof monaco): typeof monaco.languages.CompletionItemKind {
  return m.languages.CompletionItemKind;
}

/** Inside a Conditions-style list `{...}` belongs to a condition, anywhere else to a mechanic. */
function sectionOwner(model: monaco.editor.ITextModel, lineNumber: number): ParamOwner {
  for (let n = lineNumber; n >= 1; n--) {
    const m = /^\s{2}([A-Za-z0-9_]+)\s*:/.exec(model.getLineContent(n));
    if (m) return /^(conditions|targetconditions|triggerconditions)$/i.test(m[1]) ? "condition" : "mechanic";
  }
  return "mechanic";
}

/** Range covering the `partial` characters just before the cursor (what the suggestion replaces). */
function backRange(position: monaco.Position, partial: string): monaco.Range {
  return new monaco.Range(position.lineNumber, Math.max(1, position.column - partial.length), position.lineNumber, position.column);
}

const RETRIGGER: monaco.languages.Command = { id: "editor.action.triggerSuggest", title: "Suggest values" };

export function registerProviders(kind: EditorKind, catalogIndex: () => CatalogIndex | null, names: NameIndex) {
  const disposables: monaco.IDisposable[] = [];

  disposables.push(
    monaco.languages.registerCompletionItemProvider(LANGUAGE_ID, {
      triggerCharacters: ["@", "~", "{", ";", "=", "<", ".", ":", " ", "?", ","],
      provideCompletionItems(model, position) {
        const ci = catalogIndex();
        if (!ci || isCommentPosition(model.getLineContent(position.lineNumber), position.column - 1)) return { suggestions: [] };
        const text = model.getValue();
        const offset = model.getOffsetAt(position);
        const ctx = parseContext(text, offset, kind);
        const word = model.getWordUntilPosition(position);
        const wordRange = new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
        const CK = monaco.languages.CompletionItemKind;
        const SNIPPET = monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet;

        const suggestions: monaco.languages.CompletionItem[] = [];

        /** mechanic / condition names: one item per name, one per alias (an alias inserts the canonical name) */
        const pushEntries = (entries: CatalogEntry[], iconKind: monaco.languages.CompletionItemKind, range: monaco.Range) => {
          for (const e of entries) {
            const insertText = snippetForParams(e.name, e);
            suggestions.push({
              label: { label: e.name, description: e.cat ?? e.source },
              // Include aliases in Monaco's fuzzy-search corpus, so partial input such as "dama"
              // can still surface the canonical mechanic and aliases resolve to that canonical entry.
              filterText: [e.name, ...e.aliases].join(" "),
              kind: iconKind,
              insertText,
              insertTextRules: SNIPPET,
              documentation: docFor(e),
              detail: e.desc,
              range,
              sortText: "0" + e.name.toLowerCase()
            });
            for (const alias of e.aliases) {
              suggestions.push({
                label: { label: alias, description: `alias of ${e.name}` },
                kind: iconKind,
                insertText,
                insertTextRules: SNIPPET,
                documentation: docFor(e),
                filterText: alias,
                range,
                sortText: "2" + alias.toLowerCase()
              });
            }
          }
        };

        switch (ctx.area) {
          case "topKey": {
            const keyNames = kind === "mob" ? ci.mobKeys : ci.skillKeys;
            for (const k of keyNames) {
              suggestions.push({ label: k, kind: CK.Field, insertText: k + ": ", range: wordRange, sortText: "0" + k });
            }
            break;
          }
          case "mechanicName": {
            pushEntries(ci.mechanics, CK.Function, backRange(position, ctx.partial));
            break;
          }
          case "inlineCondition":
          case "conditionsListMechanic": {
            pushEntries(ci.conditions, CK.Operator, backRange(position, ctx.partial));
            break;
          }
          case "conditionAction": {
            const range = backRange(position, ctx.partial);
            for (const a of ci.catalog.conditionActions ?? []) {
              const word = a.name.split(" ")[0];
              const hasArg = a.name.includes(" ");
              suggestions.push({
                label: { label: word, description: hasArg ? a.name.slice(word.length + 1) : "condition action" },
                kind: CK.Keyword,
                insertText: hasArg ? word + " " : word,
                command: hasArg && word.toLowerCase() !== "power" ? RETRIGGER : undefined,
                documentation: { value: a.desc },
                detail: a.desc,
                range
              });
            }
            break;
          }
          case "conditionActionSkill": {
            const range = backRange(position, ctx.partial);
            for (const v of names.skills) suggestions.push({ label: v, kind: CK.Class, insertText: v, range });
            break;
          }
          case "targeter": {
            const range = backRange(position, ctx.partial);
            for (const t of ci.targeters) {
              const snippet = snippetForParams(t.name, t, 1);
              const insert = (n: string) => snippet.replace(t.name, n);
              suggestions.push({
                label: { label: t.name, description: "@ " + (t.cat ?? "targeter") },
                kind: CK.Reference,
                insertText: snippet,
                insertTextRules: SNIPPET,
                documentation: docFor(t),
                detail: t.desc,
                range,
                sortText: "0" + t.name.toLowerCase()
              });
              for (const alias of t.aliases) {
                suggestions.push({
                  label: { label: alias, description: `alias of @${t.name}` },
                  kind: CK.Reference,
                  insertText: insert(alias),
                  insertTextRules: SNIPPET,
                  documentation: docFor(t),
                  filterText: alias,
                  range,
                  sortText: "1" + alias.toLowerCase()
                });
              }
            }
            break;
          }
          case "trigger": {
            if (ctx.hasColon) break; // the part after ':' is a free argument (ticks, name, ...)
            const range = backRange(position, ctx.partial);
            for (const t of ci.triggers) {
              const arg = (t as any).arg as string | undefined;
              suggestions.push({
                label: { label: t.name, description: "~ trigger" },
                kind: CK.Event,
                insertText: arg ? `${t.name}:\${1:${arg}}` : t.name,
                insertTextRules: SNIPPET,
                documentation: { value: t.desc },
                detail: t.desc,
                range,
                sortText: "0" + t.name.toLowerCase()
              });
            }
            break;
          }
          case "mechanicParamName": {
            const entry = ci.entryFor(ctx.owner, ctx.mechanic);
            const used = new Set(ctx.used);
            const range = backRange(position, ctx.partial);
            const own = new Set((entry?.params ?? []).map((p) => p.names[0].toLowerCase()));
            for (const p of ci.paramsFor(ctx.owner, entry)) {
              if (p.names.some((n) => used.has(n.toLowerCase()))) continue;
              const shared = !own.has(p.names[0].toLowerCase());
              p.names.forEach((n, idx) => {
                suggestions.push({
                  label: { label: n, description: idx === 0 ? p.type + (p.required ? " (required)" : "") : `alias of ${p.names[0]}` },
                  kind: shared ? CK.Variable : CK.Property,
                  insertText: `${n}=`,
                  command: RETRIGGER,
                  detail: p.names.length > 1 ? p.names.join(", ") : undefined,
                  documentation: paramDoc(p, shared ? "applies to every " + ctx.owner : undefined),
                  range,
                  sortText: (p.required ? "0" : shared ? "2" : "1") + (idx === 0 ? "0" : "1") + n.toLowerCase()
                });
              });
            }
            break;
          }
          case "mechanicParamValue": {
            if (ctx.inString) break;
            const entry = ci.entryFor(ctx.owner, ctx.mechanic);
            const param = ci.paramsFor(ctx.owner, entry).find((p: CatalogParam) => p.names.some((n) => n.toLowerCase() === ctx.param));
            if (!param) break;
            // comma separated lists (target=PLAYER,MOB): only the segment after the last comma is replaced
            const seg = ctx.partial.slice(ctx.partial.lastIndexOf(",") + 1);
            const range = backRange(position, seg);
            const add = (values: string[], itemKind: monaco.languages.CompletionItemKind) => {
              for (const v of values) suggestions.push({ label: v, kind: itemKind, insertText: v, range });
            };
            if (param.type === "boolean") add(["true", "false"], CK.Value);
            else if (param.type === "enum") add(ci.paramValues(param), CK.EnumMember);
            else if (param.type === "skill" || param.type === "skilllist") add(names.skills, CK.Class);
            else if (param.type === "mob") add(names.mobs, CK.Class);
            else if (param.type === "item") add(names.items, CK.Class);
            else if (VALUE_TYPE_ENUM_KEY[param.type]) add(ci.enumValues(VALUE_TYPE_ENUM_KEY[param.type]), CK.EnumMember);
            if (param.default && param.default.length <= 40 && !suggestions.some((x) => x.label === param.default)) {
              suggestions.push({ label: { label: param.default, description: "default" }, kind: CK.Constant, insertText: param.default, range, sortText: "9" + param.default });
            }
            break;
          }
          case "mobOptionKey": {
            for (const k of ci.mobOptions) suggestions.push({ label: k, kind: CK.Property, insertText: k + ": ", range: wordRange });
            break;
          }
          case "mobOptionValue": {
            for (const v of ["true", "false"]) suggestions.push({ label: v, kind: CK.Value, insertText: v, range: wordRange });
            break;
          }
          case "aiGoal": {
            for (const g of ci.aiGoals) suggestions.push({ label: g, kind: CK.Function, insertText: g, range: wordRange });
            break;
          }
          case "aiTarget": {
            for (const g of ci.aiTargets) suggestions.push({ label: g, kind: CK.Function, insertText: g, range: wordRange });
            break;
          }
          case "placeholder": {
            const range = backRange(position, ctx.partial);
            for (const p of ci.catalog.placeholders) {
              suggestions.push({
                label: { label: p.name, description: p.type },
                kind: CK.Variable,
                insertText: p.name + ">",
                documentation: { value: p.desc },
                detail: p.desc,
                range
              });
            }
            break;
          }
          case "topKeyValue": {
            if (ctx.key === "Type") {
              for (const v of ci.enumValues("entitytype")) suggestions.push({ label: v, kind: CK.EnumMember, insertText: v, range: wordRange });
              for (const v of names.mobs) suggestions.push({ label: v, kind: CK.Class, detail: "extend this mob", insertText: v, range: wordRange });
            }
            break;
          }
          default:
            break;
        }
        return { suggestions };
      }
    })
  );

  disposables.push(
    monaco.languages.registerHoverProvider(LANGUAGE_ID, {
      provideHover(model, position) {
        const ci = catalogIndex();
        if (!ci) return null;
        const word = model.getWordAtPosition(position);
        if (!word) return null;
        const w = word.word;
        const wordRange = new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
        // a parameter key inside {...}: show its own documentation
        const lineText = model.getLineContent(position.lineNumber);
        if (lineText[word.endColumn - 1] === "=") {
          const before = lineText.slice(0, word.endColumn - 1);
          const body = before.replace(/^\s*-\s?/, "");
          const site = findCallSite(body, sectionOwner(model, position.lineNumber));
          if (site && site.key === null) {
            const p = ci.paramsFor(site.owner, ci.entryFor(site.owner, site.name)).find((x) => x.names.some((n) => n.toLowerCase() === w.toLowerCase()));
            if (p) return { range: wordRange, contents: [paramDoc(p)] };
          }
        }
        const entry = ci.mechanic(w) ?? ci.targeter(w) ?? ci.condition(w);
        if (entry) {
          return { range: new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn), contents: [docFor(entry)] };
        }
        const trig = ci.catalog.triggers.find((t) => t.name.toLowerCase() === w.toLowerCase());
        if (trig) {
          return { range: new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn), contents: [{ value: `**~${trig.name}**\n\n${trig.desc}` }] };
        }
        if (names.skills.includes(w) || names.mobs.includes(w)) {
          return { range: new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn),
            contents: [{ value: `**${w}**\n\nPress **F12** or **Ctrl+Click** to go to its definition.` }] };
        }
        return null;
      }
    })
  );

  disposables.push(
    monaco.languages.registerColorProvider(LANGUAGE_ID, {
      provideDocumentColors(model) {
        const text = model.getValue();
        const results: monaco.languages.IColorInformation[] = [];
        const re = /#([0-9a-fA-F]{6})\b/g;
        let m: RegExpExecArray | null;
        while ((m = re.exec(text))) {
          const start = model.getPositionAt(m.index);
          const end = model.getPositionAt(m.index + m[0].length);
          const hex = m[1];
          results.push({
            range: new monaco.Range(start.lineNumber, start.column, end.lineNumber, end.column),
            color: {
              red: parseInt(hex.slice(0, 2), 16) / 255,
              green: parseInt(hex.slice(2, 4), 16) / 255,
              blue: parseInt(hex.slice(4, 6), 16) / 255,
              alpha: 1
            }
          });
        }
        return results;
      },
      provideColorPresentations(_model, colorInfo) {
        const toHex = (v: number) => Math.round(v * 255).toString(16).padStart(2, "0");
        const hex = `#${toHex(colorInfo.color.red)}${toHex(colorInfo.color.green)}${toHex(colorInfo.color.blue)}`.toUpperCase();
        return [{ label: hex }];
      }
    })
  );

  disposables.push(
    monaco.languages.registerDocumentFormattingEditProvider(LANGUAGE_ID, {
      provideDocumentFormattingEdits(model) {
        return [{ range: model.getFullModelRange(), text: formatMythicYaml(model.getValue()) }];
      }
    })
  );

  return { dispose: () => disposables.forEach((d) => d.dispose()) };
}

/**
 * Conservative formatter: normalises indentation to a consistent 2-space step per nesting level and
 * trims trailing whitespace, without touching comments, key order, quoting or blank-line placement.
 * This intentionally does not rewrite skill-line syntax, so nothing a user (or MythicMobs) wrote is lost.
 */
export function formatMythicYaml(text: string): string {
  const lines = text.replace(/\r\n/g, "\n").split("\n");
  const out: string[] = [];
  for (const line of lines) {
    if (line.trim() === "") {
      out.push("");
      continue;
    }
    const m = /^(\s*)(.*)$/.exec(line)!;
    const indentChars = m[1];
    const rest = m[2].replace(/[ \t]+$/, "");
    // round indentation to the nearest multiple of 2 spaces, treating tabs as one level
    const levels = Math.round(indentChars.replace(/\t/g, "  ").length / 2);
    out.push("  ".repeat(Math.max(0, levels)) + rest);
  }
  return out.join("\n");
}

export interface Diagnostic {
  severity: monaco.MarkerSeverity;
  message: string;
  startLineNumber: number;
  startColumn: number;
  endLineNumber: number;
  endColumn: number;
}

/** Client-side diagnostics: unknown mechanic/targeter/trigger/condition, unresolved Skill/Mob references. */
export function computeDiagnostics(text: string, kind: EditorKind, ci: CatalogIndex, names: NameIndex): Diagnostic[] {
  const diags: Diagnostic[] = [];
  const lines = text.split("\n");
  let section = "";
  // Works for one entry (line 1 is its name) and for a whole file (several entries): every indent-0 line starts a new entry.
  for (let i = 0; i < lines.length; i++) {
    const raw = lines[i];
    const active = stripInlineComment(raw);
    const indentMatch = /^(\s*)/.exec(active)!;
    const indent = indentMatch[1].length;
    const trimmed = active.trim();
    if (trimmed === "" || trimmed.startsWith("#")) continue;
    if (indent === 0) {
      section = "";
      continue;
    }
    if (indent === 2) {
      const km = /^([A-Za-z0-9_]+)\s*:/.exec(trimmed);
      if (km) section = km[1].toLowerCase();
      continue;
    }
    if (!trimmed.startsWith("-")) continue;
    const body = trimmed.replace(/^-\s?/, "");
    const col = raw.indexOf(body) + 1;

    if (section === "skills") {
      const mechMatch = /^([A-Za-z0-9_:.\-]+)/.exec(body);
      if (mechMatch) {
        const rawName = mechMatch[1];
        const isSkillCall = rawName.toLowerCase().startsWith("skill:") || rawName.toLowerCase().startsWith("metaskill:");
        if (isSkillCall) {
          const ref = rawName.split(":")[1];
          if (ref && !names.skills.includes(ref)) {
            diags.push(warn(`Unknown skill '${ref}'`, i + 1, col, col + rawName.length));
          }
        } else if (!ci.isKnownMechanic(rawName)) {
          diags.push(warn(`Unknown mechanic '${rawName}'`, i + 1, col, col + rawName.length));
        }
      }
      const atMatch = /@([A-Za-z][A-Za-z0-9_]*)/.exec(body);
      if (atMatch && !ci.isKnownTargeter(atMatch[1])) {
        diags.push(warn(`Unknown targeter '@${atMatch[1]}'`, i + 1, raw.indexOf(atMatch[0]) + 1, raw.indexOf(atMatch[0]) + 1 + atMatch[0].length));
      }
      const tildeMatch = /~([A-Za-z][A-Za-z0-9_]*)/.exec(body);
      if (tildeMatch && !ci.isKnownTrigger(tildeMatch[1])) {
        diags.push(warn(`Unknown trigger '~${tildeMatch[1]}'`, i + 1, raw.indexOf(tildeMatch[0]) + 1, raw.indexOf(tildeMatch[0]) + 1 + tildeMatch[0].length));
      }
      const paramBlock = /\{([^{}]*)\}/.exec(body);
      if (paramBlock && mechMatch) {
        const entry = ci.mechanic(mechMatch[1]);
        if (entry) {
          const used = new Set<string>();
          const allParams = ci.paramsFor("mechanic", entry);
          for (const pair of splitArgs(paramBlock[1])) {
            const eq = pair.indexOf("=");
            if (eq < 0) continue;
            const key = pair.slice(0, eq).trim().toLowerCase();
            const val = pair.slice(eq + 1).trim();
            if (!key) continue;
            used.add(key);
            const param = allParams.find((p) => p.names.some((n) => n.toLowerCase() === key));
            if (!param) {
              // Missing documentation is not proof of invalid syntax: keep this informational.
              diags.push(info(`Unrecognized attribute '${key}' for ${entry.name}; verify MythicMobs version or addon`, i + 1, col, col + body.length));
              continue;
            }
            if (param.type === "boolean" && val && !/^(true|false)$/i.test(val) && !val.startsWith("<")) {
              diags.push(warn(`'${key}' expects true/false, got '${val}'`, i + 1, col, col + body.length));
            }
            if ((param.type === "number" || param.type === "integer") && val && !/^-?\d+(\.\d+)?$/.test(val) && !val.startsWith("<")) {
              diags.push(warn(`'${key}' expects a number, got '${val}'`, i + 1, col, col + body.length));
            }
            if (param.type === "integer" && val && /^-?\d+\.\d+$/.test(val) && !val.startsWith("<")) {
              diags.push(warn(`'${key}' expects an integer, got '${val}'`, i + 1, col, col + body.length));
            }
            const allowed = param.values ?? (param.type === "enum" ? ci.paramValues(param) : []);
            if (allowed.length && val && !val.startsWith("<") && !allowed.some((v) => v.toLowerCase() === val.toLowerCase())) {
              diags.push(warn(`'${key}' must be one of: ${allowed.join(", ")}`, i + 1, col, col + body.length));
            }
          }
          for (const param of entry.params ?? []) {
            if (param.required && !param.names.some((n) => used.has(n.toLowerCase()))) {
              diags.push(warn(`Missing required attribute '${param.names[0]}' for ${entry.name}`, i + 1, col, col + body.length));
            }
          }
        }
      }

      // Inline conditions in skill DSL use ?Condition{...}; validate them independently from mechanic attributes.
      const inlineCondition = /\?([A-Za-z][A-Za-z0-9_]*)\{([^{}]*)\}/.exec(body);
      if (inlineCondition) {
        const condition = ci.condition(inlineCondition[1]);
        if (!condition) {
          diags.push(warn(`Unknown condition '${inlineCondition[1]}'`, i + 1, col, col + body.length));
        } else if (condition.params?.length) {
          const used = new Set<string>();
          const params = ci.paramsFor("condition", condition);
          for (const pair of splitArgs(inlineCondition[2])) {
            const eq = pair.indexOf("=");
            if (eq < 0) continue;
            const key = pair.slice(0, eq).trim().toLowerCase();
            const val = pair.slice(eq + 1).trim();
            if (!key) continue;
            used.add(key);
            const param = params.find((p) => p.names.some((n) => n.toLowerCase() === key));
            if (!param) {
              diags.push(info(`Unrecognized attribute '${key}' for condition ${condition.name}; verify version or addon`, i + 1, col, col + body.length));
              continue;
            }
            if (param.type === "boolean" && val && !/^(true|false)$/i.test(val) && !val.startsWith("<")) {
              diags.push(warn(`'${key}' expects true/false, got '${val}'`, i + 1, col, col + body.length));
            }
            if ((param.type === "number" || param.type === "integer") && val && !/^-?\d+(\.\d+)?$/.test(val) && !val.startsWith("<")) {
              diags.push(warn(`'${key}' expects a number, got '${val}'`, i + 1, col, col + body.length));
            }
          }
          for (const param of condition.params) {
            if (param.required && !param.names.some((n) => used.has(n.toLowerCase()))) {
              diags.push(warn(`Missing required attribute '${param.names[0]}' for condition ${condition.name}`, i + 1, col, col + body.length));
            }
          }
        }
      }

      // Targeter attributes live inside the @Targeter{...} expression and use a separate catalog.
      const targeterCall = /@([A-Za-z][A-Za-z0-9_]*)\{([^{}]*)\}/.exec(body);
      if (targeterCall) {
        const targeter = ci.targeter(targeterCall[1]);
        if (targeter) {
          const used = new Set<string>();
          const params = ci.paramsFor("targeter", targeter);
          for (const pair of splitArgs(targeterCall[2])) {
            const eq = pair.indexOf("=");
            if (eq < 0) continue;
            const key = pair.slice(0, eq).trim().toLowerCase();
            const val = pair.slice(eq + 1).trim();
            if (!key) continue;
            used.add(key);
            const param = params.find((p) => p.names.some((n) => n.toLowerCase() === key));
            if (!param) {
              // Informational only: third-party extensions and version-specific attributes may be valid.
              if (targeter.params?.length) diags.push(info(`Unrecognized attribute '${key}' for @${targeter.name}; verify version or addon`, i + 1, col, col + body.length));
              continue;
            }
            if (param.type === "boolean" && val && !/^(true|false)$/i.test(val) && !val.startsWith("<")) {
              diags.push(warn(`'${key}' expects true/false, got '${val}'`, i + 1, col, col + body.length));
            }
            if ((param.type === "number" || param.type === "integer") && val && !/^-?\d+(\.\d+)?$/.test(val) && !val.startsWith("<")) {
              diags.push(warn(`'${key}' expects a number, got '${val}'`, i + 1, col, col + body.length));
            }
          }
          for (const param of targeter.params ?? []) {
            if (param.required && !param.names.some((n) => used.has(n.toLowerCase()))) {
              diags.push(warn(`Missing required attribute '${param.names[0]}' for @${targeter.name}`, i + 1, col, col + body.length));
            }
          }
        }
      }
    }

    // Conditions are also used in mob-level Conditions/TargetConditions sections, not only inline skill syntax.
    if (["conditions", "targetconditions", "triggerconditions"].includes(section)) {
      const conditionMatch = /^([A-Za-z][A-Za-z0-9_]*)/.exec(body);
      if (conditionMatch) {
        const condition = ci.condition(conditionMatch[1]);
        if (!condition) {
          diags.push(warn(`Unknown condition '${conditionMatch[1]}'`, i + 1, col, col + conditionMatch[1].length));
        } else {
          const conditionBlock = /\{([^{}]*)\}/.exec(body);
          if (conditionBlock) {
            const used = new Set<string>();
            for (const pair of splitArgs(conditionBlock[1])) {
              const eq = pair.indexOf("=");
              if (eq < 0) continue;
              const key = pair.slice(0, eq).trim().toLowerCase();
              const val = pair.slice(eq + 1).trim();
              if (!key) continue;
              used.add(key);
              const param = ci.paramsFor("condition", condition).find((p) => p.names.some((n) => n.toLowerCase() === key));
              if (!param) {
                if (condition.params?.length) diags.push(info(`Unrecognized attribute '${key}' for condition ${condition.name}; verify version or addon`, i + 1, col, col + body.length));
                continue;
              }
              if (param.type === "boolean" && val && !/^(true|false)$/i.test(val) && !val.startsWith("<")) {
                diags.push(warn(`'${key}' expects true/false, got '${val}'`, i + 1, col, col + body.length));
              }
              if ((param.type === "number" || param.type === "integer") && val && !/^-?\d+(\.\d+)?$/.test(val) && !val.startsWith("<")) {
                diags.push(warn(`'${key}' expects a number, got '${val}'`, i + 1, col, col + body.length));
              }
            }
            for (const param of condition.params ?? []) {
              if (param.required && !param.names.some((n) => used.has(n.toLowerCase()))) {
                diags.push(warn(`Missing required attribute '${param.names[0]}' for condition ${condition.name}`, i + 1, col, col + body.length));
              }
            }
          }
        }
      }
    }
    if (section === "aigoalselectors" && !ci.aiGoals.some((g) => body.toLowerCase().startsWith(g.toLowerCase()))) {
      diags.push(info(`Unrecognized AI goal '${body.split(" ")[0]}'`, i + 1, col, col + body.length));
    }
    if (section === "aitargetselectors" && !ci.aiTargets.some((g) => body.toLowerCase().startsWith(g.toLowerCase()))) {
      diags.push(info(`Unrecognized AI target '${body.split(" ")[0]}'`, i + 1, col, col + body.length));
    }
  }
  return diags;
}

function splitArgs(inner: string): string[] {
  const out: string[] = [];
  let depth = 0, cur = "", quote: string | null = null, escaped = false;
  for (const c of inner) {
    if (quote) {
      cur += c;
      if (escaped) escaped = false;
      else if (c === "\\") escaped = true;
      else if (c === quote) quote = null;
      continue;
    }
    if (c === '"' || c === "'") { quote = c; cur += c; continue; }
    if (c === "{" || c === "[") depth++;
    if (c === "}" || c === "]") depth = Math.max(0, depth - 1);
    if (c === ";" && depth === 0) {
      out.push(cur);
      cur = "";
    } else cur += c;
  }
  if (cur) out.push(cur);
  return out;
}

function warn(message: string, line: number, startCol: number, endCol: number): Diagnostic {
  return { severity: monaco.MarkerSeverity.Warning, message, startLineNumber: line, startColumn: startCol, endLineNumber: line, endColumn: endCol };
}
function info(message: string, line: number, startCol: number, endCol: number): Diagnostic {
  return { severity: monaco.MarkerSeverity.Info, message, startLineNumber: line, startColumn: startCol, endLineNumber: line, endColumn: endCol };
}
