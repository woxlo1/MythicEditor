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
// Security headers that must be enforced by the Worker (same policy as the plugin).
const PLUGIN_CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; worker-src 'self' blob:; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";
const FORCE_RESPONSE_HEADERS = {
  "content-security-policy": PLUGIN_CSP,
  "x-frame-options": "DENY",
  "x-content-type-options": "nosniff",
};

const MAIN = "main";
const ID_RE = /^[a-z0-9][a-z0-9-]{1,30}$/;
const ROUTE_COOKIE = "me_srv";
const SESSION_COOKIE_DEFAULT = "mythiceditor_sid";

function sessionCookieNameFor(id) {
  if (!id || id === MAIN) return SESSION_COOKIE_DEFAULT;
  if (!ID_RE.test(id) || id === MAIN) return SESSION_COOKIE_DEFAULT;
  return SESSION_COOKIE_DEFAULT + "_" + id;
}

function filterRequestCookieHeader(cookieHeader, allowedCookieName) {
  if (!cookieHeader || !allowedCookieName) return cookieHeader || "";
  const parts = cookieHeader.split(";");
  const kept = [];
  for (let i = 0; i < parts.length; i++) {
    const part = parts[i];
    const idx = part.indexOf("=");
    if (idx <= 0) continue;
    const name = part.slice(0, idx).trim();
    // Only forward the session cookie for the target server; drop me_srv and others (including other servers' session cookies)
    if (name === allowedCookieName) {
      kept.push(part.trim());
    }
  }
  return kept.join("; ");
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/_relay/connect") return connect(request, env, url);
    if (url.pathname.startsWith("/_relay/")) return plain(404, "Not found.");

    // Routing switch for /editor/* and /s/* must only be accepted on document navigations (Sec-Fetch-Dest: document).
    // fetch() based switches are rejected with 400. If header is missing (old browsers), we choose to reject for safety.
    if (url.pathname.startsWith("/editor/") || /^\/s\/[^/]+(\/.*)?$/.test(url.pathname)) {
      const dest = request.headers.get("sec-fetch-dest");
      if (dest !== "document") {
        return plain(400, "Bad request.");
      }
    }

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
  if (env.RELAY_SECRET.length < 16) return plain(503, "RELAY_SECRET is too short.");
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
    // Simple per-IP rate limit (memory only). Entries cleaned up periodically.
    this.rateMap = new Map();
    // Keep-alive pings from the plugin are answered without waking the object up.
    this.state.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request) {
    if (request.headers.get("Upgrade") === "websocket") return this.accept();
    // Rate limit per cf-connecting-ip (120 req/min). If over limit, return 429.
    const ip = request.headers.get("cf-connecting-ip") || "unknown";
    const now = Date.now();
    // cleanup
    for (const [k, v] of this.rateMap) {
      if (now - v.windowStart > 60000) this.rateMap.delete(k);
    }
    const entry = this.rateMap.get(ip) || { windowStart: now, count: 0 };
    if (now - entry.windowStart > 60000) {
      entry.windowStart = now;
      entry.count = 0;
    }
    entry.count++;
    this.rateMap.set(ip, entry);
    if (entry.count > 120) {
      return new Response(JSON.stringify({ error: { code: "rate_limited", message: "Too many requests" } }), {
        status: 429,
        headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", "retry-after": "60" },
      });
    }
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
    // Determine target server id from route cookie/path context - we need to know it; reconstruct from request URL path? 
    // But Durable Object is per server id; the fetch comes in via stub for specific id. We don't know id here directly,
    // but we can infer from cookie if present. Also main uses no suffix.
    let targetId = null;
    // Try to read route cookie
    const route = readCookie(request, ROUTE_COOKIE);
    if (route && route !== MAIN && ID_RE.test(route)) targetId = route;
    // else main

    const allowedCookie = sessionCookieNameFor(targetId || MAIN);
    for (const name of FORWARD_REQUEST_HEADERS) {
      const v = request.headers.get(name);
      if (v !== null) {
        if (name === "cookie") {
          const filtered = filterRequestCookieHeader(v, allowedCookie);
          if (filtered) headers[name] = filtered;
          // drop others entirely
        } else {
          headers[name] = v;
        }
      }
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
    return toResponse(res, request.method === "HEAD", request);
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

function toResponse(msg, isHead, request) {
  const status = Number.isInteger(msg.status) && msg.status >= 200 && msg.status <= 599 ? msg.status : 502;
  const headers = new Headers();
  // Determine allowed session cookie name from context? We don't have request here; but we need to filter Set-Cookie.
  // Best effort: try to read from msg context is not available - to be safe, drop Set-Cookie unless it matches known patterns
  // and we don't know target; but DO is per-server, harder to tell. However per requirement (c): "Worker  Set-Cookie 、"
  // We need to know which server this DO belongs to? Not directly passed. As a safe approach, drop all Set-Cookie from plugin
  // except if we can determine - but easier to track via a different mechanism; alternatively, look up route cookie isn't possible here.
  // But the DO is obtained by idFromName(id) in fetch; in our proxy call the DO instance is tied to specific id? Wait - in the default worker fetch, we get stub = env.RELAY.get(env.RELAY.idFromName(id)) and call stub.fetch with modified request.
  // But inside DO.fetch we don't know which id it is. Alternative: pass id via header we add? Let us check - in default fetch we pass original request or new Request(target,...request) which copies headers; we could add a header.
  // But we need to modify. Easier to handle in default fetch level? Or just be conservative: only allow Set-Cookie if it matches mythiceditor_sid or mythiceditor_sid_<id> where id matches route cookie? We can try to read route cookie from the request that came into DO? The DO.fetch receives the Request object passed from stub.fetch - we passed it through; original request had cookies. Also we can look up route cookie again.
  // But in the current flow, in default worker we call stub.fetch(target === url ? request : new Request(target.toString(), request)) - so headers preserved. Yes.
  // So in toResponse we don't have request; better to filter in DO.proxy after getting response? Or change approach.
  // Alternatively, move filtering to where we have context - in proxy, after getting res from plugin via the frame? Wait - the plugin sends response back through WebSocket frame to DO; DO receives it as msg in webSocketMessage and calls settle, then proxy awaits answer and calls toResponse(res,...). So we have 'request' in proxy scope.
  
  const route = readCookie(request, ROUTE_COOKIE);
  let targetId = (route && route !== MAIN && ID_RE.test(route)) ? route : MAIN;
  const allowedCookie = sessionCookieNameFor(targetId);

  for (const [name, values] of Object.entries(msg.headers || {})) {
    const lower = name.toLowerCase();
    if (DROP_RESPONSE_HEADERS.has(lower)) continue;
    if (lower === "set-cookie") {
      // Filter: only keep cookies for target server session cookie name
      for (const v of Array.isArray(values) ? values : [values]) {
        try {
          const strv = String(v);
          // Check cookie name
          const idx = strv.indexOf("=");
          if (idx > 0) {
            const cname = strv.slice(0, idx).trim();
            if (cname === allowedCookie) {
              headers.append(name, strv);
            }
            // else drop
          }
        } catch (_) { /* skip */ }
      }
      continue;
    }
    for (const v of Array.isArray(values) ? values : [values]) {
      try { headers.append(name, String(v)); } catch (_) { /* invalid header, skip */ }
    }
  }
  // Enforce security headers (overwrite)
  headers.set("Content-Security-Policy", PLUGIN_CSP);
  headers.set("X-Frame-Options", "DENY");
  headers.set("X-Content-Type-Options", "nosniff");
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
