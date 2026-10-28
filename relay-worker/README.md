# MythicEditor Relay Worker (`relay-worker`)

## Easiest option: Let the plugin handle everything (no Wrangler required)

**The Worker code is bundled inside the plugin JAR.** Provide a Cloudflare API token once, and the plugin will deploy the Worker to your Cloudflare account on startup and automatically generate a shared secret.

1. Open the Cloudflare dashboard. Click your profile icon in the top-right corner, go to **My Profile → API Tokens**, and create a token using the **Edit Cloudflare Workers** template. Select your own account under Account Resources and **All zones** under Zone Resources. Create the token and copy it. The token will only be displayed once.
2. Edit `plugins/MythicEditor/config.yml`:

   ```yaml
   relay:
     enabled: true
     api-token: "YOUR_COPIED_TOKEN"
   ```

   You can leave `url` and `secret` empty.

3. Restart your server. The console should display messages similar to:

   ```
   Relay: Worker is ready at https://....workers.dev
   Relay: connected
   ```

   The URL and shared secret are saved in `plugins/MythicEditor/relay.json`.

4. **Remove the `api-token` line from `config.yml`.** The token is only needed for the initial deployment and is not required for subsequent connections. The plugin does not retain the token after deployment.

   After that, the plugin will automatically connect using the credentials stored in `relay.json`.

5. If a plugin update changes the Worker code, temporarily add the API token back to `config.yml` and restart the server to redeploy the updated Worker.

- If you have already deployed the Worker using `wrangler deploy` from this directory, you can continue using the existing configuration method by setting `url` and `secret` in `config.yml`.
- Your Cloudflare account must have a `workers.dev` subdomain. You can create one by opening **Workers & Pages** in the Cloudflare dashboard.
- If your API token can access multiple accounts, specify `relay.account-id` as well. The Worker name is configured using `relay.worker-name` and defaults to `mythiceditor`.

---

## Manual deployment using Wrangler

The request flow is:

**Browser → Cloudflare Worker → Minecraft server plugin**

The plugin establishes an outbound connection to Cloudflare, so you do not need to open ports on your Minecraft server or configure a tunnel.

## One-time setup (deploying with Wrangler)

A Durable Object is required for the relay. **You cannot create the Durable Object class simply by pasting code into the Cloudflare dashboard.** You must deploy it using `wrangler deploy` at least once. After the initial deployment, you can update the Worker code by pasting the updated code into the dashboard if necessary.

1. Install Node.js (LTS): https://nodejs.org
2. Open a terminal, such as PowerShell, in the `relay-worker` directory.
3. Log in to Cloudflare. A browser window will open:

   ```bash
   npx wrangler login
   ```

4. Deploy the Worker. This replaces the existing `mythiceditor` Worker while keeping its URL unchanged:

   ```bash
   npx wrangler deploy
   ```

5. Configure the shared secret (`RELAY_SECRET`). Generate a long, random string and register it as a secret:

   ```bash
   npx wrangler secret put RELAY_SECRET
   ```

   To generate a random string in PowerShell:

   ```powershell
   -join ((48..57)+(97..122) | Get-Random -Count 40 | % {[char]$_})
   ```

   Alternatively, configure it in the Cloudflare dashboard under **Workers & Pages → mythiceditor → Settings → Variables and Secrets**. Select **Secret** as the type and enter `RELAY_SECRET` as the name.

## Plugin configuration (`plugins/MythicEditor/config.yml`)

```yaml
web:
  bind-address: "127.0.0.1"   # Keep the HTTP server private when using the relay exclusively.

relay:
  enabled: true
  url: "https://mythiceditor.mythic-editor.workers.dev"
  secret: "THE_SAME_VALUE_AS_RELAY_SECRET"
```

After saving the configuration, run `/mythiceditor reload` or restart the server.

If the console displays a message similar to:

```
Relay: connected to wss://...
```

the connection has been established successfully.

Running `/mythiceditor` in-game displays a link to the editor through the Worker.

## Troubleshooting

| State | Expected result |
|---|---|
| Plugin is not connected | The browser displays "Cannot connect to the Minecraft server" (503). |
| Incorrect shared secret | The plugin logs a connection failure warning and retries automatically. |
| Connected | The link provided by `/mythiceditor` opens the editor. |

## Using one Worker for multiple servers (friends and server IDs)

Your friends do not need Wrangler or Node.js. If you operate the Worker, you only need to configure their server IDs in your own `config.yml`.

### 1. Configure the allowed server IDs

In your `config.yml` on the server hosting the Worker:

```yaml
relay:
  friends: ["friend"]      # Friend's server ID: lowercase letters, digits, and hyphens; 2–31 characters. "main" is not allowed.
```

Restart your server. The console will display four lines of configuration for your friend's server.

### 2. Send the configuration to your friend

Send those four configuration lines to your friend. They can paste them into their own `config.yml`. Your friend only needs to install the plugin JAR.

```yaml
relay:
  enabled: true
  url: "https://....workers.dev"
  secret: "FRIEND_SPECIFIC_SECRET"
  server-id: "friend"
```

### 3. Open the editor

When your friend runs `/mythiceditor`, the plugin provides a link similar to:

`https://.../s/friend/editor/...`

- **Updating an older Worker:** If your Worker predates multi-server support, update it once. Add `relay.api-token` and restart the server to let the plugin deploy the updated Worker automatically.
- **Independent connections:** Each server has its own connection, so servers do not compete for the same connection slot.
- **Separate shared secrets:** Your friend only receives their own secret. They do not need to know your `RELAY_SECRET` or any other server's secret.
- **Revoking access:** Remove the server ID from `friends` and restart the server. To reuse the same friend, configure a new server ID; the old secret will no longer be accepted.
- **Browser routing:** The browser switches to the server associated with the most recently opened link. To use two servers simultaneously in separate tabs, use separate browsers.
- **Trust requirement:** Your friends' traffic also passes through your Worker. Only share access with people you trust.
- **Shared-origin limits:** All servers share the same Worker origin. While session cookies and routing are separated per server, full isolation is not possible with a single origin; true separation requires per-server hostnames (subdomains). Treat friends mode as sharing a trust boundary.

---

## Security

- `/_relay/connect` rejects connections unless the supplied `RELAY_SECRET` matches the configured secret. **Never share your secret with anyone you do not trust.**
- Browser requests are forwarded to the plugin's HTTP server through `127.0.0.1`. The same authentication, permissions, CSRF, Origin, and rate-limit checks used for direct access also apply.
- The Worker forwards only the following request headers: `Cookie`, `Content-Type`, `Accept`, `Accept-Encoding`, `If-None-Match`, `X-CSRF-Token`, and `Origin`. Only the target server's session cookie is forwarded; other cookies are stripped.
- The Worker overwrites the client IP using Cloudflare's connection metadata. Client-supplied `X-Forwarded-For` values are not trusted.
- Only one active connection is allowed per server ID. If another server connects using the same ID and secret, the previous connection is disconnected.
- The Worker enforces per-IP rate limiting and sets security headers (`Content-Security-Policy`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`).
- Routing switches via `/_relay/*` and `/s/*`/`/editor/*` require `Sec-Fetch-Dest: document` (navigation); fetch-based switches are rejected. If `RELAY_SECRET` is shorter than 16 characters, `/_relay/connect` is rejected.
- **Future improvement:** Subdomain-based separation (separate hostnames per server) would provide true origin isolation; consider this if stronger separation is required. Current same-origin sharing requires trusting all participants.