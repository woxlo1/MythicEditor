import * as monaco from "monaco-editor/esm/vs/editor/editor.api";
import { LANGUAGE_ID } from "./language";
import { parseContext, type EditorKind } from "./context";
import { CatalogIndex, NameIndex } from "./catalogIndex";
import type { CatalogEntry, CatalogParam } from "../types";

const VALUE_TYPE_ENUM_KEY: Record<string, string> = {
  particle: "particle", potion: "potion", material: "material", sound: "sound",
  entitytype: "entitytype", color: "" // color uses #RRGGBB, no fixed list
};

function snippetForParams(name: string, entry: CatalogEntry | undefined): string {
  if (!entry || !entry.params || entry.params.length === 0) return name;
  const mainNames = entry.main && entry.main.length ? entry.main : entry.params.slice(0, 2).flatMap((p) => [p.names[0]]);
  const chosen = entry.params.filter((p) => mainNames.includes(p.names[0]) || p.required);
  if (chosen.length === 0) return name;
  let i = 1;
  const parts = chosen.map((p) => `${p.names[0]}=\${${i++}:${p.default || (p.type === "boolean" ? "false" : "")}}`);
  return `${name}{${parts.join(";")}}$0`;
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

export function registerProviders(kind: EditorKind, catalogIndex: () => CatalogIndex | null, names: NameIndex) {
  const disposables: monaco.IDisposable[] = [];

  disposables.push(
    monaco.languages.registerCompletionItemProvider(LANGUAGE_ID, {
      triggerCharacters: ["@", "~", "{", ";", "=", "<", ".", ":", " "],
      provideCompletionItems(model, position) {
        const ci = catalogIndex();
        if (!ci) return { suggestions: [] };
        const text = model.getValue();
        const offset = model.getOffsetAt(position);
        const ctx = parseContext(text, offset, kind);
        const word = model.getWordUntilPosition(position);
        const range = new monaco.Range(position.lineNumber, word.startColumn, position.lineNumber, word.endColumn);
        const CK = kindOfCompletion(monaco);
        const SNIPPET = monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet;

        const suggestions: monaco.languages.CompletionItem[] = [];

        const pushEntries = (entries: CatalogEntry[], iconKind: monaco.languages.CompletionItemKind, snippet: boolean) => {
          for (const e of entries) {
            suggestions.push({
              label: { label: e.name, description: e.cat ?? e.source },
              kind: iconKind,
              insertText: snippet ? snippetForParams(e.name, e) : e.name,
              insertTextRules: snippet ? SNIPPET : undefined,
              documentation: docFor(e),
              detail: e.desc,
              range,
              sortText: e.source === "bundled" ? "0" + e.name : "1" + e.name
            });
            for (const alias of e.aliases) {
              suggestions.push({
                label: { label: alias, description: `alias of ${e.name}` },
                kind: iconKind,
                insertText: snippet ? snippetForParams(e.name, e) : e.name,
                insertTextRules: snippet ? SNIPPET : undefined,
                documentation: docFor(e),
                filterText: alias,
                range,
                sortText: "2" + alias
              });
            }
          }
        };

        switch (ctx.area) {
          case "topKey": {
            const keyNames = kind === "mob" ? ci.mobKeys : ci.skillKeys;
            for (const k of keyNames) {
              suggestions.push({ label: k, kind: CK.Field, insertText: k + ": ", range, sortText: "0" + k });
            }
            break;
          }
          case "mechanicName": {
            pushEntries(ci.mechanics, CK.Function, true);
            break;
          }
          case "targeter": {
            for (const t of ci.targeters) {
              const snippet = t.params && t.params.length ? `${t.name}{${t.main?.[0] ?? t.params[0].names[0]}=\${1:${t.params[0].default}}}` : t.name;
              suggestions.push({
                label: { label: "@" + t.name, description: t.cat },
                kind: CK.Reference,
                insertText: snippet,
                insertTextRules: SNIPPET,
                documentation: docFor(t),
                range,
                sortText: "0" + t.name
              });
            }
            break;
          }
          case "trigger": {
            for (const t of ci.triggers) {
              const insertText = (t as any).arg ? `${t.name}:\${1:${(t as any).arg}}` : t.name;
              suggestions.push({
                label: { label: "~" + t.name },
                kind: CK.Event,
                insertText,
                insertTextRules: SNIPPET,
                documentation: { value: t.desc },
                range,
                sortText: "0" + t.name
              });
            }
            break;
          }
          case "conditionsListMechanic": {
            pushEntries(ci.conditions, CK.Operator, true);
            break;
          }
          case "mechanicParamName": {
            const entry = ci.mechanic(ctx.mechanic) ?? ci.condition(ctx.mechanic);
            if (entry?.params) {
              for (const p of entry.params) {
                for (const n of p.names) {
                  suggestions.push({
                    label: { label: n, description: p.type + (p.required ? " (required)" : "") },
                    kind: CK.Property,
                    insertText: `${n}=`,
                    documentation: { value: `*${p.type}*${p.default ? ` — default \`${p.default}\`` : ""}\n\n${p.desc}` },
                    range,
                    sortText: p.required ? "0" + n : "1" + n
                  });
                }
              }
            }
            break;
          }
          case "mechanicParamValue": {
            const entry = ci.mechanic(ctx.mechanic) ?? ci.condition(ctx.mechanic);
            const param = entry?.params?.find((p: CatalogParam) => p.names.some((n) => n.toLowerCase() === ctx.param));
            if (param) {
              if (param.type === "boolean") {
                for (const v of ["true", "false"]) suggestions.push({ label: v, kind: CK.Value, insertText: v, range });
              } else if (param.type === "enum") {
                for (const v of ci.paramValues(param)) suggestions.push({ label: v, kind: CK.EnumMember, insertText: v, range });
              } else if (param.type === "skill" || param.type === "skilllist") {
                for (const v of names.skills) suggestions.push({ label: v, kind: CK.Class, insertText: v, range });
              } else if (param.type === "mob") {
                for (const v of names.mobs) suggestions.push({ label: v, kind: CK.Class, insertText: v, range });
              } else if (VALUE_TYPE_ENUM_KEY[param.type] !== undefined) {
                for (const v of ci.enumValues(VALUE_TYPE_ENUM_KEY[param.type])) suggestions.push({ label: v, kind: CK.EnumMember, insertText: v, range });
              }
            }
            break;
          }
          case "mobOptionKey": {
            for (const k of ci.mobOptions) suggestions.push({ label: k, kind: CK.Property, insertText: k + ": ", range });
            break;
          }
          case "mobOptionValue": {
            for (const v of ["true", "false"]) suggestions.push({ label: v, kind: CK.Value, insertText: v, range });
            break;
          }
          case "aiGoal": {
            for (const g of ci.aiGoals) suggestions.push({ label: g, kind: CK.Function, insertText: g, range });
            break;
          }
          case "aiTarget": {
            for (const g of ci.aiTargets) suggestions.push({ label: g, kind: CK.Function, insertText: g, range });
            break;
          }
          case "placeholder": {
            for (const p of ci.catalog.placeholders) {
              suggestions.push({ label: p.name, kind: CK.Variable, insertText: p.name + ">", documentation: { value: p.desc }, range });
            }
            break;
          }
          case "topKeyValue": {
            if (ctx.key === "Type") {
              for (const v of ci.enumValues("entitytype")) suggestions.push({ label: v, kind: CK.EnumMember, insertText: v, range });
              for (const v of names.mobs) suggestions.push({ label: v, kind: CK.Class, detail: "extend this mob", insertText: v, range });
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
  for (let i = 1; i < lines.length; i++) {
    const raw = lines[i];
    const indentMatch = /^(\s*)/.exec(raw)!;
    const indent = indentMatch[1].length;
    const trimmed = raw.trim();
    if (trimmed === "" || trimmed.startsWith("#")) continue;
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
        if (entry?.params) {
          for (const pair of splitArgs(paramBlock[1])) {
            const eq = pair.indexOf("=");
            if (eq < 0) continue;
            const key = pair.slice(0, eq).trim().toLowerCase();
            const val = pair.slice(eq + 1).trim();
            const param = entry.params.find((p) => p.names.some((n) => n.toLowerCase() === key));
            if (!param) continue;
            if (param.type === "boolean" && val && !/^(true|false)$/i.test(val) && !val.startsWith("<")) {
              diags.push(warn(`'${key}' expects true/false, got '${val}'`, i + 1, col, col + body.length));
            }
            if ((param.type === "number" || param.type === "integer") && val && !/^-?\d+(\.\d+)?$/.test(val) && !val.startsWith("<")) {
              diags.push(warn(`'${key}' expects a number, got '${val}'`, i + 1, col, col + body.length));
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
  let depth = 0, cur = "";
  for (const c of inner) {
    if (c === "{" || c === "[") depth++;
    if (c === "}" || c === "]") depth--;
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
