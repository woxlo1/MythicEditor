import { h, Fragment } from "preact";
import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { Api, setCsrfToken, onUnauthorizedHandler } from "./api";
import type { Kind, SessionInfo } from "./types";
import { CatalogIndex, NameIndex } from "./monaco/catalogIndex";
import { Dashboard } from "./views/Dashboard";
import { EntityList } from "./views/EntityList";
import { EditorView, loadMonaco } from "./views/EditorView";
import { GraphView } from "./views/GraphView";
import { Toasts } from "./components/Toasts";
import { pushToast } from "./lib/toast";

type View = "dashboard" | "skills" | "mobs" | "graph";

interface OpenEntry {
  kind: Kind;
  id: string;
}

export function App() {
  const [session, setSession] = useState<SessionInfo | null>(null);
  const [authError, setAuthError] = useState(false);
  const [view, setView] = useState<View>("dashboard");
  const [open, setOpen] = useState<OpenEntry | null>(null);
  const [catalogIndex, setCatalogIndex] = useState<CatalogIndex | null>(null);
  const [refreshToken, setRefreshToken] = useState(0);
  const namesRef = useRef(new NameIndex());
  const lastSeqRef = useRef(0);
  const [autosave, setAutosave] = useState(false);
  const [autosaveDelay, setAutosaveDelay] = useState(3000);

  useEffect(() => {
    onUnauthorizedHandler(() => setAuthError(true));
    (async () => {
      try {
        const s = await Api.session();
        setSession(s);
        setCsrfToken(s.csrf);
        const [catalog, status] = await Promise.all([Api.catalog(), Api.status()]);
        setCatalogIndex(new CatalogIndex(catalog));
        setAutosave(status.autosaveDefault);
        setAutosaveDelay(status.autosaveDelayMs);
        await refreshNames();
      } catch {
        setAuthError(true);
      }
    })();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function refreshNames() {
    try {
      const [skills, mobs] = await Promise.all([Api.list("skill", "", "", 5000, 0), Api.list("mob", "", "", 5000, 0)]);
      namesRef.current.setSkills(skills.items.map((i) => i.id));
      namesRef.current.setMobs(mobs.items.map((i) => i.id));
      setRefreshToken((t) => t + 1);
    } catch {
      // best-effort; completion just falls back to fewer suggestions
    }
  }

  // poll for changes (own edits + external file changes) to keep lists/completion fresh
  useEffect(() => {
    if (!session) return;
    const t = setInterval(async () => {
      if (document.hidden) return; // nobody is looking: do not spend tunnel bandwidth
      try {
        const res = await Api.changes(lastSeqRef.current, 50);
        if (res.items.length > 0) {
          lastSeqRef.current = res.lastSeq;
          await refreshNames();
        }
      } catch {
        // transient network error; try again next tick
      }
    }, 6000);
    return () => clearInterval(t);
  }, [session]);

  // fetch the editor chunk in the background so the first click on an entry is instant
  useEffect(() => {
    if (!session) return;
    const t = setTimeout(() => {
      loadMonaco().catch(() => {});
    }, 1500);
    return () => clearTimeout(t);
  }, [session]);

  function gotoEntry(kind: Kind, id: string) {
    setOpen({ kind, id });
    setView(kind === "mob" ? "mobs" : "skills");
  }

  if (authError) {
    return (
      <div class="center-screen">
        <h2>セッションが無効です</h2>
        <p>Minecraft内で <code>/mythiceditor</code> を実行して新しいリンクを取得してください。</p>
      </div>
    );
  }
  if (!session || !catalogIndex) {
    return <div class="center-screen">Loading MythicEditor…</div>;
  }

  return (
    <div class="app-shell">
      <TopBar session={session} onLogout={async () => { await Api.logout(); location.reload(); }} />
      <div class="body-row">
        <div class="sidebar">
          <nav>
            <button class={view === "dashboard" ? "active" : ""} onClick={() => setView("dashboard")}>📊 Dashboard</button>
            <button class={view === "skills" ? "active" : ""} onClick={() => setView("skills")}>📜 Skills</button>
            <button class={view === "mobs" ? "active" : ""} onClick={() => setView("mobs")}>👹 Mobs</button>
            <button class={view === "graph" ? "active" : ""} onClick={() => setView("graph")}>🕸 Graph</button>
          </nav>
          <div class="section-label">Editor</div>
          <div style={{ padding: "0 12px 12px", display: "flex", flexDirection: "column", gap: 6 }}>
            <label style={{ display: "flex", alignItems: "center", gap: 6, fontSize: 12, color: "var(--text-1)" }}>
              <input type="checkbox" checked={autosave} onChange={(e) => setAutosave((e.target as HTMLInputElement).checked)} />
              Autosave
            </label>
          </div>
          {open && (
            <Fragment>
              <div class="section-label">Currently Open</div>
              <div style={{ padding: "0 12px 12px", fontFamily: "var(--mono)", fontSize: 12, color: "var(--accent)" }}>{open.kind}: {open.id}</div>
            </Fragment>
          )}
        </div>
        <div class="main">
          {view === "dashboard" && <Dashboard onOpenEntry={(k, id) => gotoEntry(k, id)} onNavigate={(v) => setView(v as View)} />}
          {view === "skills" && !open && (
            <EntityList kind="skill" canEdit={session.permissions.edit} refreshToken={refreshToken} onOpen={(id) => setOpen({ kind: "skill", id })} />
          )}
          {view === "mobs" && !open && (
            <EntityList kind="mob" canEdit={session.permissions.edit} refreshToken={refreshToken} onOpen={(id) => setOpen({ kind: "mob", id })} />
          )}
          {open && (view === "skills" || view === "mobs") && (
            <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
              <div style={{ padding: "6px 12px", borderBottom: "1px solid var(--border)" }}>
                <button class="ghost" onClick={() => setOpen(null)}>← Back to list</button>
              </div>
              <div style={{ flex: 1, minHeight: 0 }}>
                <EditorView
                  key={open.kind + ":" + open.id}
                  kind={open.kind}
                  id={open.id}
                  canEdit={session.permissions.edit}
                  catalogIndex={catalogIndex}
                  names={namesRef.current}
                  autosave={autosave}
                  autosaveDelayMs={autosaveDelay}
                  onGotoEntry={gotoEntry}
                  onRenamed={(newId) => setOpen({ kind: open.kind, id: newId })}
                  onDeleted={() => {
                    setOpen(null);
                    refreshNames();
                  }}
                />
              </div>
            </div>
          )}
          {view === "graph" && (
            <div style={{ display: "flex", flexDirection: "column", height: "100%" }}>
              <div style={{ padding: "6px 12px", borderBottom: "1px solid var(--border)", color: "var(--text-2)", fontSize: 12 }}>
                Skill / Mob relationship graph — click a node to open it, drag to rearrange, scroll to zoom.
              </div>
              <GraphView onSelect={(k, id) => gotoEntry(k as Kind, id)} />
            </div>
          )}
        </div>
      </div>
      <Toasts />
    </div>
  );
}

function TopBar(props: { session: SessionInfo; onLogout: () => void }) {
  return (
    <div class="topbar">
      <span class="brand">MythicEditor</span>
      <span style={{ color: "var(--text-2)" }}>{props.session.playerName}</span>
      <div class="spacer" />
      {!props.session.permissions.edit && <span style={{ color: "var(--yellow)", fontSize: 12 }}>閲覧のみ (編集権限なし)</span>}
      <button class="ghost" onClick={props.onLogout}>Logout</button>
    </div>
  );
}
