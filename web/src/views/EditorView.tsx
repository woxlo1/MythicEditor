import { h, Fragment } from "preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { Api } from "../api";
import type { EntryFull, Kind } from "../types";
import { ApiError, ConflictInfo } from "../types";
import { CatalogIndex, NameIndex } from "../monaco/catalogIndex";
import { pushToast } from "../lib/toast";
import type * as monaco from "monaco-editor/esm/vs/editor/editor.api";

// Monaco is ~2 MB: it lives in its own chunk that is only fetched when an entry is opened (and prefetched by App
// shortly after start-up), so the dashboard / lists / graph appear quickly even through a slow tunnel.
type MonacoModule = typeof import("../monaco/MonacoEditor");
let monacoModule: MonacoModule | null = null;
let monacoLoading: Promise<MonacoModule> | null = null;

export function loadMonaco(): Promise<MonacoModule> {
  if (monacoModule) return Promise.resolve(monacoModule);
  if (!monacoLoading) {
    monacoLoading = import("../monaco/MonacoEditor").then(
      (m) => (monacoModule = m),
      (e) => {
        monacoLoading = null; // allow a retry after a network error
        throw e;
      }
    );
  }
  return monacoLoading;
}

const REFERENCE_URL = "https://git.mythiccraft.io/mythiccraft/MythicMobs/-/wikis/home";

type Theme = "dark" | "light";

function loadTheme(): Theme {
  try {
    return localStorage.getItem("mythiceditor.theme") === "light" ? "light" : "dark";
  } catch {
    return "dark";
  }
}

/** Browser download of plain text as a .yml file (UTF-8, LF line endings). */
function downloadYml(filenameBase: string, text: string) {
  const safe = filenameBase.replace(/[^A-Za-z0-9_.\-]/g, "_") || "entry";
  const name = /\.ya?ml$/i.test(safe) ? safe : safe + ".yml";
  const body = text.replace(/\r\n/g, "\n").endsWith("\n") ? text.replace(/\r\n/g, "\n") : text.replace(/\r\n/g, "\n") + "\n";
  const blob = new Blob([body], { type: "text/yaml;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export function EditorView(props: {
  kind: Kind;
  id: string;
  canEdit: boolean;
  catalogIndex: CatalogIndex | null;
  names: NameIndex;
  onGotoEntry: (kind: Kind, id: string) => void;
  onDeleted: () => void;
  onRenamed: (newId: string) => void;
  autosave: boolean;
  autosaveDelayMs: number;
}) {
  const [entry, setEntry] = useState<EntryFull | null>(null);
  const [text, setText] = useState("");
  const [loading, setLoading] = useState(true);
  const [saveState, setSaveState] = useState<"saved" | "dirty" | "saving" | "error">("saved");
  const [conflict, setConflict] = useState<ConflictInfo | null>(null);
  const [showDelete, setShowDelete] = useState(false);
  const [showDuplicate, setShowDuplicate] = useState(false);
  const [showHelp, setShowHelp] = useState(false);
  const [showNew, setShowNew] = useState<Kind | null>(null);
  const [newMenu, setNewMenu] = useState(false);
  const [theme, setTheme] = useState<Theme>(loadTheme);
  const editorRef = useRef<monaco.editor.IStandaloneCodeEditor | null>(null);
  const [mod, setMod] = useState<MonacoModule | null>(monacoModule);
  const [monacoError, setMonacoError] = useState(false);

  useEffect(() => {
    if (mod) return;
    let alive = true;
    loadMonaco().then(
      (m) => alive && setMod(m),
      () => alive && setMonacoError(true)
    );
    return () => {
      alive = false;
    };
  }, [mod]);
  const dirtyRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Api.get(props.kind, props.id)
      .then((e) => {
        if (cancelled) return;
        setEntry(e);
        setText(e.text);
        setSaveState("saved");
        dirtyRef.current = false;
      })
      .catch((e: any) => pushToast("error", e?.body?.message ?? e?.message ?? "Failed to load entry"))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.kind, props.id]);

  useEffect(() => {
    mod?.setEditorTheme(theme);
    try {
      localStorage.setItem("mythiceditor.theme", theme);
    } catch {
      /* storage may be unavailable */
    }
  }, [theme, loading, mod]);

  function updateText(next: string) {
    setText(next);
    dirtyRef.current = true;
    setSaveState("dirty");
  }

  async function save(force = false) {
    if (!entry) return;
    setSaveState("saving");
    try {
      const saved = await Api.update(props.kind, entry.id, text, entry.hash, force);
      setEntry(saved);
      setText(saved.text);
      dirtyRef.current = false;
      setSaveState("saved");
      setConflict(null);
      if (saved.id !== entry.id) props.onRenamed(saved.id);
      if (saved.warnings.length) saved.warnings.forEach((w) => pushToast("info", w, 8000));
      pushToast("success", `Saved ${saved.id}`, 2000);
    } catch (e: any) {
      if (e instanceof ApiError && e.body.code === "conflict") {
        setConflict(e.body as unknown as ConflictInfo);
        setSaveState("error");
        return;
      }
      setSaveState("error");
      pushToast("error", e?.body?.message ?? e?.message ?? "Save failed");
    }
  }

  // autosave
  useEffect(() => {
    if (!props.autosave || saveState !== "dirty") return;
    const t = setTimeout(() => save(false), props.autosaveDelayMs);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [text, props.autosave, saveState]);

  // Ctrl+S
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
  }, [text, entry, props.canEdit]);

  // warn on browser close with unsaved changes
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

  // ---- toolbar actions ------------------------------------------------------------------------------

  /** Clean: format the document, then strip trailing whitespace and extra blank lines at the end. */
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
    if (v !== model.getValue()) {
      ed.executeEdits("clean", [{ range: model.getFullModelRange(), text: v }]);
    }
    ed.focus();
  }

  function undo() {
    const ed = editorRef.current;
    if (!ed) return;
    ed.focus();
    ed.trigger("toolbar", "undo", null);
  }

  function download() {
    if (!entry) return;
    downloadYml(entry.id, editorRef.current?.getValue() ?? text);
  }

  function requestNew(kind: Kind) {
    setNewMenu(false);
    if (dirtyRef.current && !window.confirm("未保存の変更があります。破棄して新規作成しますか？")) return;
    setShowNew(kind);
  }

  function onEditorReady(ed: monaco.editor.IStandaloneCodeEditor) {
    editorRef.current = ed;
  }

  async function gotoDefinition(word: string) {
    for (const k of ["skill", "mob"] as Kind[]) {
      if (k === "skill" && !props.names.skills.includes(word)) continue;
      if (k === "mob" && !props.names.mobs.includes(word)) continue;
      props.onGotoEntry(k, word);
      return;
    }
    const def = await Api.definition(props.kind === "mob" ? "skill" : props.kind, word).catch(() => null);
    if (def?.found) {
      props.onGotoEntry("skill", word);
    } else {
      pushToast("info", `No definition found for '${word}'`);
    }
  }

  async function doDelete() {
    if (!entry) return;
    try {
      await Api.remove(props.kind, entry.id, entry.hash, false);
      pushToast("success", `Deleted ${entry.id}`);
      props.onDeleted();
    } catch (e: any) {
      pushToast("error", e?.body?.message ?? e?.message ?? "Delete failed");
    }
  }

  if (loading) return <div class="center-screen">Loading…</div>;
  if (!entry) return <div class="center-screen">Not found.</div>;

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
          {editable && (
            <span class="mg-split">
              <button class="mg-btn" onClick={() => requestNew(props.kind)}>New</button>
              <button class="mg-btn mg-caret" onClick={() => setNewMenu((v) => !v)} aria-label="New menu">▾</button>
              {newMenu && (
                <div class="mg-menu" onMouseLeave={() => setNewMenu(false)}>
                  <button onClick={() => requestNew("skill")}>New Skill</button>
                  <button onClick={() => requestNew("mob")}>New Mob</button>
                </div>
              )}
            </span>
          )}
        </div>
        <div class="mg-group">
          <button class="mg-btn" onClick={download} title={`${entry.id}.yml としてダウンロード`}>Download</button>
        </div>
        <div class="mg-group">
          <button class="mg-btn" onClick={() => setShowHelp(true)}>Help</button>
        </div>
        <div class="mg-group">
          <a class="mg-btn" href={REFERENCE_URL} target="_blank" rel="noopener noreferrer" title="MythicMobs Wiki を新しいタブで開く">Reference</a>
        </div>
        <div class="mg-spacer" />
        {editable && (
          <div class="mg-group">
            <button class="mg-btn" onClick={() => setShowDuplicate(true)}>Duplicate</button>
            <button class="mg-btn danger" onClick={() => setShowDelete(true)}>Delete</button>
          </div>
        )}
        <span class="mg-meta">
          <span class="entry-id">{entry.id}</span>
          <span class="entry-file">{entry.file}:{entry.line}</span>
          <span class={"save-state " + saveState}>
            {saveState === "saved" && "Saved"}
            {saveState === "dirty" && "Unsaved changes"}
            {saveState === "saving" && "Saving…"}
            {saveState === "error" && "Save failed"}
          </span>
        </span>
        <button class="mg-btn mg-icon" title="エディターのテーマ切替 (ダーク/ライト)" onClick={() => setTheme(theme === "dark" ? "light" : "dark")}>💡</button>
      </div>

      {entry.warnings.length > 0 && (
        <div style={{ background: "#4a3a1a", color: "#e0b341", padding: "6px 12px", fontSize: 12 }}>
          {entry.warnings.join(" / ")}
        </div>
      )}

      <div class="editor-body">
        <div class="editor-main">
          {!mod ? (
            <div class="center-screen">{monacoError ? "エディターの読み込みに失敗しました。ページを再読み込みしてください。" : "エディターを読み込み中…"}</div>
          ) : (
          <mod.MonacoEditor
            value={text}
            kind={props.kind}
            catalogIndex={props.catalogIndex}
            names={props.names}
            readOnly={!editable}
            onChange={(t) => updateText(t)}
            onGotoDefinition={gotoDefinition}
            onReady={onEditorReady}
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
            setEntry({ ...entry, text: conflict.server.text, hash: conflict.server.hash });
            setConflict(null);
            setSaveState("saved");
            dirtyRef.current = false;
            pushToast("info", "Reloaded the server version. Your local edits were discarded.");
          }}
          onOverwrite={() => save(true)}
          onCancel={() => setConflict(null)}
        />
      )}
      {showDelete && (
        <ConfirmModal
          title={`Delete ${entry.id}?`}
          body="This moves the current file to the backups folder before removing the entry; it can be restored from there."
          confirmLabel="Delete"
          danger
          onCancel={() => setShowDelete(false)}
          onConfirm={() => {
            setShowDelete(false);
            doDelete();
          }}
        />
      )}
      {showDuplicate && (
        <DuplicateModal
          kind={props.kind}
          sourceId={entry.id}
          onClose={() => setShowDuplicate(false)}
          onDuplicated={(id) => {
            setShowDuplicate(false);
            props.onGotoEntry(props.kind, id);
          }}
        />
      )}
      {showNew && (
        <NewModal
          kind={showNew}
          onClose={() => setShowNew(null)}
          onCreated={(k, id) => {
            setShowNew(null);
            dirtyRef.current = false;
            props.onGotoEntry(k, id);
          }}
        />
      )}
      {showHelp && <HelpModal onClose={() => setShowHelp(false)} />}
    </div>
  );
}

// ---- modals -------------------------------------------------------------------------------------------

function ConflictModal(props: { conflict: ConflictInfo; myText: string; onReload: () => void; onOverwrite: () => void; onCancel: () => void }) {
  return (
    <div class="modal-backdrop">
      <div class="modal" style={{ width: 560 }}>
        <h2>File changed externally</h2>
        <p style={{ color: "var(--text-1)" }}>{props.conflict.message}</p>
        <div class="diff">
          <strong>Server version ({props.conflict.server.file}):</strong>
          <pre>{props.conflict.server.text}</pre>
        </div>
        <div class="actions">
          <button onClick={props.onCancel}>Cancel</button>
          <button onClick={props.onReload}>Reload server version</button>
          <button class="primary" onClick={props.onOverwrite}>Overwrite with mine</button>
        </div>
      </div>
    </div>
  );
}

function ConfirmModal(props: { title: string; body: string; confirmLabel: string; danger?: boolean; onConfirm: () => void; onCancel: () => void }) {
  return (
    <div class="modal-backdrop" onClick={(e) => e.target === e.currentTarget && props.onCancel()}>
      <div class="modal">
        <h2>{props.title}</h2>
        <p style={{ color: "var(--text-1)" }}>{props.body}</p>
        <div class="actions">
          <button onClick={props.onCancel}>Cancel</button>
          <button class={props.danger ? "danger" : "primary"} onClick={props.onConfirm}>{props.confirmLabel}</button>
        </div>
      </div>
    </div>
  );
}

function DuplicateModal(props: { kind: Kind; sourceId: string; onClose: () => void; onDuplicated: (id: string) => void }) {
  const [id, setId] = useState(props.sourceId + "Copy");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function submit(e: Event) {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      const d = await Api.duplicate(props.kind, props.sourceId, id, null);
      props.onDuplicated(d.id);
    } catch (e: any) {
      setErr(e?.body?.message ?? e?.message ?? "Failed to duplicate");
    } finally {
      setBusy(false);
    }
  }
  return (
    <div class="modal-backdrop" onClick={(e) => e.target === e.currentTarget && props.onClose()}>
      <div class="modal">
        <h2>Duplicate {props.sourceId}</h2>
        <form onSubmit={submit}>
          <div class="row">
            <label>New name</label>
            <input type="text" autofocus value={id} onInput={(e) => setId((e.target as HTMLInputElement).value)} />
          </div>
          {err && <div style={{ color: "var(--red)", fontSize: 12 }}>{err}</div>}
          <div class="actions">
            <button type="button" class="ghost" onClick={props.onClose}>Cancel</button>
            <button type="submit" class="primary" disabled={busy || !id}>{busy ? "Duplicating…" : "Duplicate"}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

function NewModal(props: { kind: Kind; onClose: () => void; onCreated: (kind: Kind, id: string) => void }) {
  const [id, setId] = useState("");
  const [file, setFile] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  async function submit(e: Event) {
    e.preventDefault();
    if (!/^[A-Za-z0-9_][A-Za-z0-9_.\-]{0,63}$/.test(id)) {
      setErr("Use letters, digits, '_', '-' or '.' (max 64 characters), no spaces.");
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      const created = await Api.create(props.kind, id, file.trim() || null, null as any);
      pushToast("success", `Created ${created.id}`, 2000);
      props.onCreated(props.kind, created.id);
    } catch (e: any) {
      setErr(e?.body?.message ?? e?.message ?? "Failed to create");
    } finally {
      setBusy(false);
    }
  }
  return (
    <div class="modal-backdrop" onClick={(e) => e.target === e.currentTarget && props.onClose()}>
      <div class="modal">
        <h2>New {props.kind === "skill" ? "Skill" : "Mob"}</h2>
        <form onSubmit={submit}>
          <div class="row">
            <label>Name</label>
            <input type="text" autofocus value={id} onInput={(e) => setId((e.target as HTMLInputElement).value)} placeholder={props.kind === "skill" ? "FireBall" : "ExampleBoss"} />
          </div>
          <div class="row">
            <label>File (任意 / 例: Skills/MySkills.yml)</label>
            <input type="text" value={file} onInput={(e) => setFile((e.target as HTMLInputElement).value)} />
          </div>
          {err && <div style={{ color: "var(--red)", fontSize: 12 }}>{err}</div>}
          <div class="actions">
            <button type="button" class="ghost" onClick={props.onClose}>Cancel</button>
            <button type="submit" class="primary" disabled={busy || !id}>{busy ? "Creating…" : "Create"}</button>
          </div>
        </form>
      </div>
    </div>
  );
}

function HelpModal(props: { onClose: () => void }) {
  return (
    <div class="modal-backdrop" onClick={(e) => e.target === e.currentTarget && props.onClose()}>
      <div class="modal" style={{ width: 520 }}>
        <h2>Help</h2>
        <table class="help-table">
          <tbody>
            <tr><td><b>Save</b></td><td>編集内容をサーバーのymlへ保存します (Ctrl+S)</td></tr>
            <tr><td><b>Clean</b></td><td>インデント整形と行末の空白削除を行います</td></tr>
            <tr><td><b>Undo</b></td><td>直前の編集を元に戻します (Ctrl+Z / Ctrl+Y でやり直し)</td></tr>
            <tr><td><b>New</b></td><td>新しいSkill / Mobを作成します (▾で種類を選択)</td></tr>
            <tr><td><b>Download</b></td><td>現在のエディターの内容を <code>名前.yml</code> としてダウンロードします (未保存の編集も含む)</td></tr>
            <tr><td><b>Reference</b></td><td>MythicMobs公式Wikiを新しいタブで開きます</td></tr>
            <tr><td><b>💡</b></td><td>ダーク / ライトテーマの切替</td></tr>
          </tbody>
        </table>
        <p style={{ color: "var(--text-2)", fontSize: 12 }}>
          Ctrl+Space: 補完 · F12 / Ctrl+クリック: 定義へ移動 · Ctrl+F: 検索 · Alt+↑/↓: 行の移動 · Ctrl+/: コメント切替
        </p>
        <div class="actions"><button class="primary" onClick={props.onClose}>Close</button></div>
      </div>
    </div>
  );
}

