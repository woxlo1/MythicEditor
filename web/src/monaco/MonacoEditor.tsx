import { h } from "preact";
import { useEffect, useRef } from "preact/hooks";
import * as monaco from "monaco-editor/esm/vs/editor/editor.api";
import "./setup";
import { ensureLanguageRegistered, LANGUAGE_ID } from "./language";
import { registerProviders, computeDiagnostics } from "./providers";
import { CatalogIndex, NameIndex } from "./catalogIndex";
import type { EditorKind } from "./context";

export interface MonacoEditorProps {
  value: string;
  kind: EditorKind;
  catalogIndex: CatalogIndex | null;
  names: NameIndex;
  onChange: (value: string) => void;
  onGotoDefinition: (word: string) => void;
  readOnly?: boolean;
  onReady?: (editor: monaco.editor.IStandaloneCodeEditor) => void;
}

/** Thin Preact wrapper around a single Monaco editor instance bound to the "mythicskill" language. */
/** Switches the editor colour theme (EditorView does not import monaco, so this module is the only thing that loads it). */
export function setEditorTheme(theme: "dark" | "light") {
  monaco.editor.setTheme(theme === "light" ? "mythiceditor-light" : "mythiceditor-dark");
}

export function MonacoEditor(props: MonacoEditorProps) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const modelRef = useRef<monaco.editor.ITextModel | null>(null);
  const propsRef = useRef(props);
  propsRef.current = props;

  useEffect(() => {
    ensureLanguageRegistered();
    if (!hostRef.current) return;

    const model = monaco.editor.createModel(props.value, LANGUAGE_ID);
    modelRef.current = model;
    const editor = monaco.editor.create(hostRef.current, {
      model,
      theme: "mythiceditor-dark",
      automaticLayout: true,
      minimap: { enabled: false },
      fontSize: 13,
      fontFamily: "JetBrains Mono, Fira Code, Consolas, monospace",
      tabSize: 2,
      insertSpaces: true,
      wordWrap: "off",
      renderWhitespace: "selection",
      scrollBeyondLastLine: false,
      readOnly: !!props.readOnly,
      glyphMargin: false,
      folding: true,
      showFoldingControls: "always",
      lineNumbersMinChars: 3,
      quickSuggestions: { other: true, comments: false, strings: true },
      suggestOnTriggerCharacters: true,
      tabCompletion: "on",
      snippetSuggestions: "inline"
    });
    editorRef.current = editor;
    propsRef.current.onReady?.(editor);

    const changeSub = model.onDidChangeContent(() => {
      propsRef.current.onChange(model.getValue());
      runDiagnostics();
    });

    const providers = registerProviders(props.kind, () => propsRef.current.catalogIndex, props.names);

    editor.addAction({
      id: "mythiceditor.gotoDefinition",
      label: "Go to Definition",
      keybindings: [monaco.KeyCode.F12],
      run(ed) {
        const pos = ed.getPosition();
        if (!pos) return;
        const word = ed.getModel()?.getWordAtPosition(pos);
        if (word) propsRef.current.onGotoDefinition(word.word);
      }
    });
    const mouseSub = editor.onMouseDown((e) => {
      if ((e.event.ctrlKey || e.event.metaKey) && e.target.position) {
        const word = model.getWordAtPosition(e.target.position);
        if (word) propsRef.current.onGotoDefinition(word.word);
      }
    });

    function runDiagnostics() {
      const ci = propsRef.current.catalogIndex;
      if (!ci) return;
      const diags = computeDiagnostics(model.getValue(), propsRef.current.kind, ci, propsRef.current.names);
      monaco.editor.setModelMarkers(
        model,
        "mythiceditor",
        diags.map((d) => ({ ...d, source: "MythicEditor" }))
      );
    }
    runDiagnostics();
    (editor as any).__runDiagnostics = runDiagnostics;

    return () => {
      changeSub.dispose();
      mouseSub.dispose();
      providers.dispose();
      editor.dispose();
      model.dispose();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [hostRef.current]);

  // external value changes (e.g. from the Visual Editor, or after Save/Reload) update the model without
  // recreating the editor, so cursor position and undo history are preserved for user-driven edits.
  useEffect(() => {
    const model = modelRef.current;
    if (!model) return;
    if (model.getValue() !== props.value) {
      const editor = editorRef.current;
      const pos = editor?.getPosition() ?? null;
      model.setValue(props.value);
      if (pos) editor?.setPosition(pos);
    }
  }, [props.value]);

  useEffect(() => {
    editorRef.current?.updateOptions({ readOnly: !!props.readOnly });
  }, [props.readOnly]);

  useEffect(() => {
    if (editorRef.current && (editorRef.current as any).__runDiagnostics) {
      (editorRef.current as any).__runDiagnostics();
    }
  }, [props.catalogIndex, props.names.skills, props.names.mobs]);

  return <div ref={hostRef} class="monaco-host" />;
}

export function triggerFormat(editor: monaco.editor.IStandaloneCodeEditor | null) {
  editor?.getAction("editor.action.formatDocument")?.run();
}
