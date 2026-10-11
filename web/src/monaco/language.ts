import * as monaco from "monaco-editor/esm/vs/editor/editor.api";

export const LANGUAGE_ID = "mythicskill";

let registered = false;

/** Registers the "mythicskill" Monarch language once per page (safe to call repeatedly). */
export function ensureLanguageRegistered() {
  if (registered) return;
  registered = true;

  monaco.languages.register({ id: LANGUAGE_ID, extensions: [".yml", ".yaml"] });

  monaco.languages.setLanguageConfiguration(LANGUAGE_ID, {
    comments: { lineComment: "#" },
    brackets: [["{", "}"], ["[", "]"], ["(", ")"]],
    autoClosingPairs: [
      { open: "{", close: "}" },
      { open: "[", close: "]" },
      { open: "\"", close: "\"" },
      { open: "'", close: "'" }
    ],
    surroundingPairs: [
      { open: "{", close: "}" },
      { open: "\"", close: "\"" },
      { open: "'", close: "'" }
    ]
  });

  monaco.languages.setMonarchTokensProvider(LANGUAGE_ID, {
    defaultToken: "",
    tokenizer: {
      root: [
        [/^\S[^:#\n]*(?=:)/, "mm-key"],
        [/^\s{2}[A-Za-z0-9_]+(?=:)/, "mm-key"],
        [/#.*$/, "mm-comment"],
        [/@[A-Za-z][A-Za-z0-9_]*/, "mm-targeter"],
        [/~[A-Za-z][A-Za-z0-9_]*(:[A-Za-z0-9_]*)?/, "mm-trigger"],
        [/<[a-zA-Z0-9_.]+>/, "mm-placeholder"],
        [/[A-Za-z][A-Za-z0-9_]*(?=\s*\{)/, "mm-mechanic"],
        [/\b(true|false)\b/, "keyword.mm"],
        [/-?\d+(\.\d+)?/, "number"],
        [/"([^"\\]|\\.)*"/, "string"],
        [/'([^'\\]|'')*'/, "string"],
        [/[{}[\]()]/, "delimiter.bracket"],
        [/[=;,]/, "delimiter"],
        [/-(?=\s)/, "delimiter.dash"]
      ]
    }
  });

  monaco.editor.defineTheme("mythiceditor-dark", {
    base: "vs-dark",
    inherit: true,
    rules: [
      { token: "mm-key", foreground: "7fb3ff", fontStyle: "bold" },
      { token: "mm-mechanic", foreground: "f0b64c", fontStyle: "bold" },
      { token: "mm-targeter", foreground: "7fd97f" },
      { token: "mm-trigger", foreground: "d992ff" },
      { token: "mm-placeholder", foreground: "6de0d0" },
      { token: "mm-comment", foreground: "6d6f75", fontStyle: "italic" },
      { token: "keyword.mm", foreground: "ff9d7f" }
    ],
    colors: {
      "editor.background": "#1e1f22",
      "editor.lineHighlightBackground": "#26272b",
      "editorLineNumber.foreground": "#55565c",
      "editorGutter.background": "#1e1f22"
    }
  });

  monaco.editor.defineTheme("mythiceditor-light", {
    base: "vs",
    inherit: true,
    rules: [
      { token: "mm-key", foreground: "1a1a9c", fontStyle: "bold" },
      { token: "mm-mechanic", foreground: "9a5b00", fontStyle: "bold" },
      { token: "mm-targeter", foreground: "17702a" },
      { token: "mm-trigger", foreground: "8a2be2" },
      { token: "mm-placeholder", foreground: "007c7c" },
      { token: "mm-comment", foreground: "8a8a8a", fontStyle: "italic" },
      { token: "keyword.mm", foreground: "c2410c" }
    ],
    colors: {
      "editor.background": "#ffffff",
      "editorLineNumber.foreground": "#9a9a9a",
      "editorGutter.background": "#fafafa"
    }
  });
}
