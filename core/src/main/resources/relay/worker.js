// MythicEditor relay.
//
//   browser  --HTTPS-->  this Worker  --(Durable Object)-->  WebSocket  -->  the Minecraft server's plugin
//
// The plugin connects OUT to /_relay/connect (so no port has to be opened on the game server). Every other request
// is packed into a JSON frame, sent over that socket, answered by the plugin and unpacked again.
//
// Several Minecraft servers can share this one Worker:
//   * the default server ("main") uses  relay.secret = RELAY_SECRET  and plain links  /editor/<ticket>
//   * any other server picks a server-id (relay.server-id) and uses the secret that
//       node make-secret.js <RELAY_SECRET> <server-id>
//     prints. Its links look like  /s/<server-id>/editor/<ticket>; the Worker remembers the choice in a cookie.
//
// Required Worker secret/variable:  RELAY_SECRET   (the owner's secret; friends' secrets are derived from it)
// Required binding:                 RELAY          (Durable Object, class "Relay", one object per server-id)

const REQUEST_TIMEOUT_MS = 30000;
const MAX_REQUEST_BYTES = 4 * 1024 * 1024;

// Only these request headers are passed to the plugin. x-forwarded-* are set here, never taken from the browser.
const FORWARD_REQUEST_HEADERS = [
  "cookie", "content-type", "accept", "accept-encoding", "if-none-match", "x-csrf-token", "origin",
];
// Never copied from the plugin's answer (the runtime sets them itself).
const DROP_RESPONSE_HEADERS = new Set(["connection", "keep-alive", "transfer-encoding", "content-length", "upgrade"]);

const MAIN = "main";
const ID_RE = /^[a-z0-9][a-z0-9-]{1,30}$/;
const ROUTE_COOKIE = "me_srv";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/_relay/connect") return connect(request, env, url);
    if (url.pathname.startsWith("/_relay/")) return plain(404, "Not found.");

    // Which server is this browser talking to?
    let id = MAIN;
    let setCookie = null;
    let target = url;

    const m = url.pathname.match(/^\/s\/([^/]+)(\/.*)?$/);
    if (m) {
      const sid = m[1];
      if (!ID_RE.test(sid) || sid === MAIN) return plain(404, "Unknown server.");
      const rest = m[2] || "/";
      setCookie = routeCookie(sid);
      if (!rest.startsWith("/editor/")) {
        // Not a login link: just remember the server and go to the normal address.
        return new Response(null, { status: 302, headers: { Location: rest + url.search, "Set-Cookie": setCookie, "Cache-Control": "no-store" } });
      }
      id = sid;
      target = new URL(url);
      target.pathname = rest;
    } else if (url.pathname.startsWith("/editor/")) {
      id = MAIN; // a plain login link always means the default server
      setCookie = routeCookie("");
    } else {
      const c = readCookie(request, ROUTE_COOKIE);
      if (c && c !== MAIN && ID_RE.test(c)) id = c;
    }

    const stub = env.RELAY.get(env.RELAY.idFromName(id));
    const res = await stub.fetch(target === url ? request : new Request(target.toString(), request));
    if (!setCookie) return res;
    const headers = new Headers(res.headers);
    headers.append("Set-Cookie", setCookie);
    return new Response(res.body, { status: res.status, headers, encodeBody: "manual" });
  },
};

async function connect(request, env, url) {
  if (!env.RELAY_SECRET) return plain(503, "RELAY_SECRET is not set on this Worker.");
  if (request.headers.get("Upgrade") !== "websocket") return plain(426, "WebSocket required.");
  const given = request.headers.get("Authorization") || "";
  const idParam = url.searchParams.get("id");
  let id = MAIN;
  let expected;
  if (!idParam || idParam === MAIN) {
    expected = "Bearer " + env.RELAY_SECRET;
  } else {
    if (!ID_RE.test(idParam)) return plain(400, "Bad server id.");
    id = idParam;
    expected = "Bearer " + (await serverSecret(env.RELAY_SECRET, id));
  }
  if (!(await safeEqual(given, expected))) return plain(401, "Unauthorized.");
  return env.RELAY.get(env.RELAY.idFromName(id)).fetch(request);
}

/** hex(HMAC-SHA256(key = RELAY_SECRET, message = "relay:" + id)) - same as make-secret.js. */
async function serverSecret(master, id) {
  const enc = new TextEncoder();
  const key = await crypto.subtle.importKey("raw", enc.encode(master), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = new Uint8Array(await crypto.subtle.sign("HMAC", key, enc.encode("relay:" + id)));
  return [...sig].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function routeCookie(id) {
  const base = ROUTE_COOKIE + "=" + id + "; Path=/; Secure; HttpOnly; SameSite=Lax";
  return id ? base + "; Max-Age=2592000" : base + "; Max-Age=0";
}

function readCookie(request, name) {
  for (const part of (request.headers.get("Cookie") || "").split(";")) {
    const i = part.indexOf("=");
    if (i > 0 && part.slice(0, i).trim() === name) return part.slice(i + 1).trim();
  }
  return null;
}

export class Relay {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    /** request id -> { resolve, conn, timer }   (only lives while the request is waiting, so no storage needed) */
    this.pending = new Map();
    // Keep-alive pings from the plugin are answered without waking the object up.
    this.state.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request) {
    if (request.headers.get("Upgrade") === "websocket") return this.accept();
    return this.proxy(request);
  }

  accept() {
    // Only one plugin connection at a time: a new one replaces the old one.
    for (const old of this.state.getWebSockets()) {
      try { old.close(1000, "replaced"); } catch (_) { /* already closed */ }
    }
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    this.state.acceptWebSocket(server, [crypto.randomUUID()]);
    return new Response(null, { status: 101, webSocket: client });
  }

  currentSocket() {
    const all = this.state.getWebSockets();
    return all.length ? all[all.length - 1] : null;
  }

  async proxy(request) {
    const ws = this.currentSocket();
    if (!ws) return offlinePage();

    const declared = parseInt(request.headers.get("content-length") || "0", 10);
    if (declared > MAX_REQUEST_BYTES) return plain(413, "Request too large.");
    let body = "";
    if (request.method !== "GET" && request.method !== "HEAD") {
      const buf = new Uint8Array(await request.arrayBuffer());
      if (buf.length > MAX_REQUEST_BYTES) return plain(413, "Request too large.");
      body = bytesToBase64(buf);
    }

    const headers = {};
    for (const name of FORWARD_REQUEST_HEADERS) {
      const v = request.headers.get(name);
      if (v !== null) headers[name] = v;
    }
    headers["x-forwarded-for"] = request.headers.get("cf-connecting-ip") || "";
    headers["x-forwarded-proto"] = "https";

    const url = new URL(request.url);
    const id = crypto.randomUUID();
    const conn = this.state.getTags(ws)[0];

    const answer = new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve({ error: "timeout" });
      }, REQUEST_TIMEOUT_MS);
      this.pending.set(id, { resolve, conn, timer });
    });

    try {
      ws.send(JSON.stringify({ t: "req", id, method: request.method, path: url.pathname + url.search, headers, body }));
    } catch (e) {
      this.settle(id, { error: "send_failed" });
    }

    const res = await answer;
    if (res.error === "timeout") return plain(504, "The Minecraft server did not answer in time.");
    if (res.error) return offlinePage();
    return toResponse(res, request.method === "HEAD");
  }

  settle(id, value) {
    const p = this.pending.get(id);
    if (!p) return;
    clearTimeout(p.timer);
    this.pending.delete(id);
    p.resolve(value);
  }

  failConnection(ws) {
    const conn = this.state.getTags(ws)[0];
    for (const [id, p] of [...this.pending]) {
      if (p.conn === conn) this.settle(id, { error: "disconnected" });
    }
  }

  async webSocketMessage(ws, message) {
    if (typeof message !== "string") return;
    let msg;
    try { msg = JSON.parse(message); } catch (_) { return; }
    if (!msg || msg.t !== "res" || typeof msg.id !== "string") return;
    const p = this.pending.get(msg.id);
    // A socket may only answer requests that were sent to it.
    if (!p || p.conn !== this.state.getTags(ws)[0]) return;
    this.settle(msg.id, msg);
  }

  async webSocketClose(ws, code) {
    this.failConnection(ws);
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, "closing"); } catch (_) { /* already closed */ }
  }

  async webSocketError(ws) {
    this.failConnection(ws);
  }
}

// ---- helpers ---------------------------------------------------------------------------------------------------

function toResponse(msg, isHead) {
  const status = Number.isInteger(msg.status) && msg.status >= 200 && msg.status <= 599 ? msg.status : 502;
  const headers = new Headers();
  for (const [name, values] of Object.entries(msg.headers || {})) {
    if (DROP_RESPONSE_HEADERS.has(name.toLowerCase())) continue;
    for (const v of Array.isArray(values) ? values : [values]) {
      try { headers.append(name, String(v)); } catch (_) { /* invalid header, skip */ }
    }
  }
  const noBody = isHead || status === 204 || status === 304 || !msg.body;
  // encodeBody "manual": the plugin already gzip-compressed the body and says so in Content-Encoding. Without this the
  // runtime would assume the body is plain and compress it a second time.
  return new Response(noBody ? null : base64ToBytes(msg.body), { status, headers, encodeBody: "manual" });
}

function offlinePage() {
  const html = `<!doctype html><html lang="ja"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>MythicEditor</title>
<body style="font-family:system-ui,sans-serif;background:#1e1e1e;color:#ddd;display:grid;place-items:center;min-height:100vh;margin:0">
<div style="max-width:30rem;padding:1.5rem"><h1 style="font-size:1.2rem">Minecraftサーバーに接続できません</h1>
<p>サーバーが停止しているか、MythicEditorプラグインがまだ中継に接続していません。</p>
<p>サーバー起動後、ゲーム内で <code>/mythiceditor</code> を実行して新しいリンクを開いてください。</p></div></body></html>`;
  return new Response(html, {
    status: 503,
    headers: { "content-type": "text/html; charset=utf-8", "cache-control": "no-store", "retry-after": "10" },
  });
}

function plain(status, text) {
  return new Response(text, { status, headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" } });
}

async function safeEqual(a, b) {
  const enc = new TextEncoder();
  const [ha, hb] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  const x = new Uint8Array(ha), y = new Uint8Array(hb);
  let diff = 0;
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i];
  return diff === 0;
}

function bytesToBase64(bytes) {
  let s = "";
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

function base64ToBytes(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
