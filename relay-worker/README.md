# MythicEditor 中継Worker (relay-worker)

## いちばん簡単: プラグインに任せる (wrangler不要)

**Workerのコードはプラグイン(jar)に入っています。** Cloudflareの「APIトークン」を1回渡すと、プラグインが起動時に
自分でWorkerをあなたのCloudflareアカウントへデプロイし、合言葉も自動で作ります。

1. Cloudflareダッシュボード → 右上アイコン → プロフィール → **APIトークン** → トークンを作成 →
   テンプレート **「Cloudflare Workersを編集」** を使用 → アカウントリソースは自分のアカウント、
   ゾーンリソースは「すべてのゾーン」 → 作成してトークンをコピー(表示は1回だけ)。
2. `plugins/MythicEditor/config.yml`:
   ```yaml
   relay:
     enabled: true
     api-token: "コピーしたトークン"
   ```
   `url` と `secret` は空のままで構いません。
3. サーバーを再起動。コンソールに `Relay: Worker is ready at https://....workers.dev` → `Relay: connected` と出ます。
   URLと合言葉は `plugins/MythicEditor/relay.json` に保存されます。
4. **`api-token` の行は削除してください**(トークンは起動時の1回しか使わず、保存もしません)。
   以後はトークンなしで `relay.json` から自動で繋がります。
5. プラグインを更新してWorkerの中身が変わったときは、トークンを一時的に戻して再起動すると、Workerも自動で更新されます。

- すでにこのフォルダで `wrangler deploy` 済みでも、そのまま使えます(config.yml の `url` / `secret` を書く従来の方法)。
- 前提: Cloudflareアカウントに workers.dev のサブドメインがあること(Workers & Pages を一度開いていれば作成済みです)。
- アカウントが複数見えるトークンなら `relay.account-id` も指定します。Workerの名前は `relay.worker-name`(既定 mythiceditor)。

---

## 手動でデプロイする場合 (wrangler)

ブラウザ → **このWorker** → (WebSocket) → Minecraftサーバーのプラグイン、という経路でエディタを開きます。
プラグインから Cloudflare へ「外向き」に接続するので、サーバー側のポート開放やトンネルは不要です。

## 初回だけ必要な作業 (wrangler でデプロイ)

中継には Durable Object が必要で、Durable Object のクラスは **Cloudflareの画面にコードを貼るだけでは作れません**
(`wrangler deploy` が必要です)。一度デプロイしてしまえば、以後のコード修正は画面への貼り替えでも大丈夫です。

1. Node.js (LTS) を入れる: https://nodejs.org
2. この `relay-worker` フォルダでターミナル(PowerShell など)を開く
3. ログイン(ブラウザが開きます):
   ```
   npx wrangler login
   ```
4. デプロイ(既存の `mythiceditor` Worker を置き換えます。URLは今までと同じ):
   ```
   npx wrangler deploy
   ```
5. 合言葉(RELAY_SECRET)を登録。長いランダム文字列を自分で決めて貼り付けます:
   ```
   npx wrangler secret put RELAY_SECRET
   ```
   ランダム文字列の作り方 (PowerShell):
   ```
   -join ((48..57)+(97..122) | Get-Random -Count 40 | % {[char]$_})
   ```
   (画面から設定する場合: Workers & Pages → mythiceditor → 設定 → 変数とシークレット → 種類「シークレット」/ 名前 `RELAY_SECRET`)

## プラグイン側 (plugins/MythicEditor/config.yml)

```yaml
web:
  bind-address: "127.0.0.1"   # 中継だけで使うなら外部公開しない
relay:
  enabled: true
  url: "https://mythiceditor.mythic-editor.workers.dev"
  secret: "さっき登録したRELAY_SECRETと同じ文字列"
```

保存後 `/mythiceditor reload`(または再起動)。コンソールに `Relay: connected to wss://...` と出れば成功です。
ゲーム内で `/mythiceditor` を実行すると、Workerのアドレスのリンクが出ます。

## 動作の確認ポイント

| 状態 | 結果 |
|---|---|
| プラグイン未接続 | ブラウザに「Minecraftサーバーに接続できません」(503) |
| 合言葉が違う | プラグインのコンソールに接続失敗の警告(自動で再試行) |
| 接続中 | `/mythiceditor` のリンクでエディタが開く |

## 1つのWorkerで複数のサーバーを使う (友達のサーバーなど)

友達側に wrangler も Node も要りません。あなたのサーバーの `config.yml` に友達の名前を書くだけです。

1. あなたの `config.yml`(Workerを持っている側):
   ```yaml
   relay:
     friends: ["friend"]      # 友達サーバーの名前(小文字英数字と -、2〜31文字、main は不可)
   ```
   サーバーを再起動すると、コンソールに友達用の設定4行が表示されます。
2. その4行を友達に送り、友達の `config.yml` に貼ってもらいます(友達はjarを入れるだけ):
   ```yaml
   relay:
     enabled: true
     url: "https://....workers.dev"
     secret: "友達専用の合言葉"
     server-id: "friend"
   ```
3. 友達が `/mythiceditor` を実行すると、`https://.../s/friend/editor/...` というリンクが出ます。

- Workerが古い場合(複数サーバー対応の前のもの)は、あなたの側で一度だけ更新が必要です。`relay.api-token` を書いて再起動すれば、プラグインが自動で更新します。
- サーバーごとに別々に同時接続でき、奪い合いは起きません。
- 友達は自分専用の合言葉しか持たず、あなたの `RELAY_SECRET` や他の人の合言葉は分かりません。
- 締め出すには、`friends` から名前を消して再起動し、新しい名前で作り直してください(古い名前の合言葉は使えなくなります)。
- ブラウザは最後に開いたリンクのサーバーに切り替わります。2つのサーバーを同時に別タブで使うなら、ブラウザを分けてください。
- 友達の通信もあなたのWorkerを通ります。信頼できる相手に限ってください。

## セキュリティ

- `/_relay/connect` は `RELAY_SECRET` が一致しないと接続できません。**合言葉は他人に教えないでください。**
- ブラウザからの要求は、プラグインのHTTPサーバーに 127.0.0.1 経由でそのまま渡されます。ログイン(ワンタイムリンク→Cookie)、
  権限、CSRF、Origin、レート制限は直接アクセス時と同じ検査が効きます。
- Workerが中継するのは Cookie / Content-Type / Accept / Accept-Encoding / If-None-Match / X-CSRF-Token / Origin だけです。
  接続元IPは Cloudflare の値でWorker側が上書きします(ブラウザが偽装した X-Forwarded-For は使われません)。
- 同じ名前(server-id)のサーバーは同時に1本だけです。同じ合言葉で別のサーバーが繋ぐと、前の接続は切られます。
