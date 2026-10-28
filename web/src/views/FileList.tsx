import { h } from "preact";
import { useEffect, useState } from "preact/hooks";
import { Api } from "../api";
import type { FileSummary } from "../types";
import { pushToast } from "../lib/toast";

/** Every YAML file the editor knows about; clicking one opens the whole file in the editor. */
export function FileList(props: { refreshToken: number; onOpen: (file: string) => void }) {
  const [q, setQ] = useState("");
  const [kind, setKind] = useState("");
  const [items, setItems] = useState<FileSummary[]>([]);
  const [loading, setLoading] = useState(false);

  async function load() {
    setLoading(true);
    try {
      const res = await Api.files();
      setItems(res.items);
    } catch (e: any) {
      pushToast("error", e?.message ?? "ファイル一覧を読み込めませんでした");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [props.refreshToken]);

  const needle = q.trim().toLowerCase();
  const shown = items.filter((f) => (!kind || f.kind === kind) && (!needle || f.file.toLowerCase().includes(needle)));

  return (
    <div class="list-panel">
      <div class="list-toolbar">
        <input type="search" placeholder="ファイルを検索…" value={q} onInput={(e) => setQ((e.target as HTMLInputElement).value)} />
        <select value={kind} onChange={(e) => setKind((e.target as HTMLSelectElement).value)}>
          <option value="">All kinds</option>
          <option value="skill">Skills</option>
          <option value="mob">Mobs</option>
          <option value="item">Items</option>
          <option value="droptable">Droptables</option>
        </select>
      </div>
      <div class="entry-table">
        <table class="entries">
          <thead>
            <tr>
              <th>File</th>
              <th>Kind</th>
              <th>Entries</th>
              <th>Status</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((f) => (
              <tr
                key={f.file}
                onClick={() => (f.error ? pushToast("error", `${f.file}: ${f.error}`) : props.onOpen(f.file))}
                style={f.error ? { opacity: 0.6 } : undefined}
              >
                <td style={{ fontFamily: "var(--mono)" }}>{f.file}</td>
                <td style={{ color: "var(--text-2)" }}>{f.kind}</td>
                <td>{f.entries}</td>
                <td>
                  {f.error && <span class="tag" title={f.error}>開けません: {f.error}</span>}
                  {!f.error && f.problems.length > 0 && <span class="tag" title={f.problems.join("\n")}>注意 {f.problems.length}</span>}
                </td>
              </tr>
            ))}
            {!loading && shown.length === 0 && (
              <tr><td colSpan={4} style={{ color: "var(--text-2)", padding: 20 }}>No files found.</td></tr>
            )}
          </tbody>
        </table>
      </div>
      <div style={{ padding: "6px 10px", color: "var(--text-2)", fontSize: 12 }}>{shown.length} / {items.length} files</div>
    </div>
  );
}
