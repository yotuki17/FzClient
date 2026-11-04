# FzClient のサーバー（Cloudflare Worker）

- Worker 名：`fzclient-users`
- アドレス：`https://fzclient-users.fuzuki-yozakura.workers.dev`
- データベース：D1 `fzclient-users`（Worker では `DB` という名前でつながっている）
- コード：`server/worker.js`（`schema.sql` は最初の users 表。コスメ用の表は Worker が自動で作る）

## 窓口
| 道 | 中身 |
|---|---|
| POST `/hello` `{uuid}` | 「今 FzClient を使っている」と記録 |
| POST `/check` `{uuids:[...]}` | その中で 15 分以内に記録がある人を返す |
| POST `/bye` `{uuid}` | 記録を消す（設定で隠したとき） |
| POST `/cos/set` `{name, serverId, data, cape?}` | 着ているコスメを保存。Mojang の hasJoined で本人確認。cape は自作マントの PNG（base64） |
| POST `/cos/get` `{uuids:[...]}` | コスメを返す |
| GET `/cos/cape/<sha1>` | 自作マントの画像 |
| POST `/cos/admin/remove` `{uuid}` + ヘッダー `x-admin-key` | 自作マントを消す（Secret `ADMIN_KEY` が必要） |
| GET `/cf/v1/...` | CurseForge API の中継（Secret `CF_API_KEY` が必要） |

## Secret（Settings → Variables and Secrets）
- `CF_API_KEY`：CurseForge のキー。入れ直すときは鉛筆 → **Rotate** → 値 → **Deploy**（Save version では反映されない）。
- `ADMIN_KEY`：任意。自作マントの削除用の合言葉。

## コードを更新する
1. `server/worker.js` をメモ帳で開いて全部コピー。
2. Cloudflare で fzclient-users → **Edit code** → Ctrl+A → Ctrl+V。
3. 右上の青い **Deploy**。

## 確認の仕方
- `/cos/get` に `{"uuids":[]}` を POST して `{"cosmetics":{}}` が返れば OK。
- `/cf/v1/mods/search?gameId=432` が `no key` なら CurseForge のキー未設定。
