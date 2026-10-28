import { h } from "preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { Api } from "../api";
import type { EntrySummary, FileFull, Kind } from "../types";
import { ApiError, ConflictInfo } from "../types";
import { CatalogIndex, NameIndex } from "../monaco/catalogIndex";
import { pushToast } from "../lib/toast";
import { ConflictModal, ConfirmModal, Theme, downloadYml, loadMonaco, loadTheme } from "./EditorView";
import type * as monaco from "monaco-editor/esm/vs/editor/editor.api";

type MonacoModule = Awaited<ReturnType<typeof loadMonaco>>;

/**
 * Edits one complete YAML file: every entry, comment and blank line in it, in a single Monaco buffer.
 * (EditorView, in contrast, only loads and saves one Skill/Mob block of a file.)
 * The server keeps the safety net: conflict detection against the file hash, YAML validation, backup,
 * atomic write and the same restricted-mechanic check as for entries.
 */
export function FileEditorView(props: {
  path: string;
  /** 1-based line to scroll to once the file is open (e.g. the entry the user came from) */
  line?: number;
  canEdit: boolean;
  catalogIndex: CatalogIndex | null;
  names: NameIndex;
  autosave: boolean;
  autosaveDelayMs: number;
  onOpenFile: (file: string, line: number) => void;
}) {
  const [doc, setDoc] = useState<FileFull | null>(null);
  const [text, setText] = useState("");
  const [loading, setLoading] = useState(true);
  const [saveState, setSaveState] = useState<"saved" | "dirty" | "saving" | "error">("saved");
  const [conflict, setConflict] = useState<ConflictInfo | null>(null);
  const [confirmReload, setConfirmReload] = useState(false);
  const [entries, setEntries] = useState<EntrySummary[]>([]);
  const [theme, setTheme] = useState<Theme>(loadTheme);
  const [mod, setMod] = useState<MonacoModule | null>(null);
  const [monacoError, setMonacoError] = useState(false);
  const [editorReady, setEditorReady] = useState(false);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const dirtyRef = useRef(false);
  const scrolledRef = useRef(false);

  useEffect(() => {
    let alive = true;
    loadMonaco().then(
      (m) => alive && setMod(m),
      () => alive && setMonacoError(true)
    );
    return () => {
      alive = false;
    };
  }, []);

  async function loadEntries(file: string, kind: Kind) {
    try {
      const res = await Api.list(kind, "", file, 1000, 0);
      setEntries(res.items);
    } catch {
      /* the outline is a convenience only */
    }
  }

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    scrolledRef.current = false;
    Api.file(props.path)
      .then((f) => {
        if (cancelled) return;
        setDoc(f);
        setText(f.text);
        setSaveState("saved");
        dirtyRef.current = false;
        loadEntries(f.file, f.kind);
      })
      .catch((e: any) => pushToast("error", e?.body?.message ?? e?.message ?? "ファイルを読み込めませんでした"))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [props.path]);

  useEffect(() => {
    mod?.setEditorTheme(theme);
    try {
      localStorage.setItem("mythiceditor.theme", theme);
    } catch {
      /* storage may be unavailable */
    }
  }, [theme, loading, mod, editorReady]);

  // scroll to the requested line once, after both the file and the editor are there
  useEffect(() => {
    if (!editorReady || loading || !doc || scrolledRef.current) return;
    scrolledRef.current = true;
    jumpTo(props.line ?? 1);
  }, [editorReady, loading, doc]);

  function jumpTo(line: number) {
    const ed = editorRef.current;
    if (!ed) return;
    const n = Math.max(1, Math.min(line, ed.getModel()?.getLineCount() ?? 1));
    ed.revealLineInCenter(n);
    ed.setPosition({ lineNumber: n, column: 1 });
    ed.focus();
  }

  function updateText(next: string) {
    setText(next);
    dirtyRef.current = true;
    setSaveState("dirty");
  }

  async function save(force = false) {
    if (!doc) return;
    setSaveState("saving");
    try {
      const saved = await Api.saveFile(doc.file, text, doc.hash, force);
      setDoc(saved);
      // the server normalises the end of the file (exactly one trailing newline); show what is really on disk
      setText(saved.text);
      dirtyRef.current = false;
      setSaveState("saved");
      setConflict(null);
      saved.warnings.forEach((w) => pushToast("info", w, 8000));
      pushToast("success", `Saved ${saved.file}`, 2000);
      loadEntries(saved.file, saved.kind);
    } catch (e: any) {
      if (e instanceof ApiError && e.body.code === "conflict") {
        setConflict(e.body as unknown as ConflictInfo);
        setSaveState("error");
        return;
      }
      setSaveState("error");
      pushToast("error", e?.body?.message ?? e?.message ?? "保存に失敗しました");
    }
  }

  useEffect(() => {
    if (!props.autosave || saveState !== "dirty") return;
    const t = setTimeout(() => save(false), props.autosaveDelayMs);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, props.autosave, saveState]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === "s") {
        e.preventDefault();
        if (props.canEdit) save(false);
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, doc, props.canEdit]);

  useEffect(() => {
    function onBeforeUnload(e: BeforeUnloadEvent) {
      if (dirtyRef.current) {
        e.preventDefault();
        e.returnValue = "";
      }
    }
    window.addEventListener("beforeunload", onBeforeUnload);
    return () => window.removeEventListener("beforeunload", onBeforeUnload);
  }, []);

  async function clean() {
    const ed = editorRef.current;
    if (!ed || !props.canEdit) return;
    try {
      await ed.getAction("editor.action.formatDocument")?.run();
    } catch {
      /* formatter is best-effort */
    }
    const model = ed.getModel();
    if (!model) return;
    let v = model.getValue().replace(/[ \t]+$/gm, "").replace(/\n{3,}/g, "\n\n").replace(/\n+$/, "");
    v += "\n";
    if (v !== model.getValue()) ed.executeEdits("clean", [{ range: model.getFullModelRange(), text: v }]);
    ed.focus();
  }

  function undo() {
    const ed = editorRef.current;
    if (!ed) return;
    ed.focus();
    ed.trigger("toolbar", "undo", null);
  }

  function reloadFromServer() {
    setConfirmReload(false);
    Api.file(props.path)
      .then((f) => {
        setDoc(f);
        setText(f.text);
        setSaveState("saved");
        dirtyRef.current = false;
        loadEntries(f.file, f.kind);
        pushToast("info", "サーバー上の内容を読み込みました");
      })
      .catch((e: any) => pushToast("error", e?.body?.message ?? e?.message ?? "再読み込みに失敗しました"));
  }

  async function gotoDefinition(word: string) {
    const kinds: string[] = ["skill", "mob"];
    if (doc && !kinds.includes(doc.kind)) kinds.push(doc.kind);
    for (const k of kinds) {
      const def = await Api.definition(k, word).catch(() => null);
      if (!def?.found || !def.file) continue;
      if (def.file === props.path) {
        jumpTo(def.line ?? 1);
      } else {
        if (dirtyRef.current && !window.confirm("未保存の変更があります。破棄して別のファイルを開きますか？")) return;
        dirtyRef.current = false;
        props.onOpenFile(def.file, def.line ?? 1);
      }
      return;
    }
    pushToast("info", `No definition found for '${word}'`);
  }

  if (loading) return <div class="center-screen">Loading…</div>;
  if (!doc) return <div class="center-screen">Not found.</div>;

  const editable = props.canEdit;

  return (
    <div class={"editor-shell mg " + theme}>
      <div class="mg-toolbar">
        <div class="mg-group">
          {editable && (
            <button class="mg-btn" onClick={() => save(false)} disabled={saveState === "saving"} title="Ctrl+S">Save</button>
          )}
        </div>
        <div class="mg-group">
          {editable && <button class="mg-btn" onClick={clean} title="整形（インデント統一・行末空白削除）">Clean</button>}
          {editable && <button class="mg-btn" onClick={undo} title="Ctrl+Z">Undo</button>}
        </div>
        <div class="mg-group">
          <button
            class="mg-btn"
            onClick={() => (dirtyRef.current ? setConfirmReload(true) : reloadFromServer())}
            title="サーバー上の最新の内容を読み込み直します"
          >
            Reload
          </button>
          <button class="mg-btn" onClick={() => downloadYml(doc.file.split("/").pop() ?? "file", editorRef.current?.getValue() ?? text)} title="現在のエディターの内容をダウンロード (未保存の編集も含む)">
            Download
          </button>
        </div>
        {entries.length > 0 && (
          <div class="mg-group">
            <select
              class="mg-select"
              title="このファイル内のエントリへ移動"
              value=""
              onChange={(e) => {
                const line = Number((e.target as HTMLSelectElement).value);
                if (line) jumpTo(line);
                (e.target as HTMLSelectElement).value = "";
              }}
            >
              <option value="">エントリへ移動 ({entries.length})</option>
              {entries.map((it) => (
                <option key={it.id} value={it.line}>{it.id}</option>
              ))}
            </select>
          </div>
        )}
        <div class="mg-spacer" />
        <span class="mg-meta">
          <span class="entry-id">{doc.file}</span>
          <span class="entry-file">{doc.kind} · {doc.entries} entries</span>
          <span class={"save-state " + saveState}>
            {saveState === "saved" && "Saved"}
            {saveState === "dirty" && "Unsaved changes"}
            {saveState === "saving" && "Saving…"}
            {saveState === "error" && "Save failed"}
          </span>
        </span>
        <button class="mg-btn mg-icon" title="エディターのテーマ切替 (ダーク/ライト)" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}>💡</button>
      </div>

      {doc.warnings.length > 0 && (
        <div style={{ background: "#4a3a1a", color: "#e0b341", padding: "6px 12px", fontSize: 12 }}>{doc.warnings.join(" / ")}</div>
      )}

      <div class="editor-body">
        <div class="editor-main">
          {!mod ? (
            <div class="center-screen">{monacoError ? "エディターの読み込みに失敗しました。ページを再読み込みしてください。" : "エディターを読み込み中…"}</div>
          ) : (
            <mod.MonacoEditor
              value={text}
              kind={doc.kind}
              catalogIndex={props.catalogIndex}
              names={props.names}
              readOnly={!editable}
              onChange={(t) => updateText(t)}
              onGotoDefinition={gotoDefinition}
              onReady={(ed) => {
                editorRef.current = ed;
                setEditorReady(true);
              }}
            />
          )}
        </div>
      </div>

      {conflict && (
        <ConflictModal
          conflict={conflict}
          myText={text}
          onReload={() => {
            setText(conflict.server.text);
            setDoc({ ...doc, text: conflict.server.text, hash: conflict.server.hash });
            setConflict(null);
            setSaveState("saved");
            dirtyRef.current = false;
            pushToast("info", "Reloaded the server version. Your local edits were discarded.");
          }}
          onOverwrite={() => save(true)}
          onCancel={() => setConflict(null)}
        />
      )}
      {confirmReload && (
        <ConfirmModal
          title="サーバーの内容を読み込み直しますか？"
          body="未保存の変更は破棄されます。"
          confirmLabel="Reload"
          danger
          onCancel={() => setConfirmReload(false)}
          onConfirm={reloadFromServer}
        />
      )}
    </div>
  );
}
