# FzClient プロジェクト一式

Minecraft 1.8.9 Forge 用の PvP クライアント **FzClient** と、その周りのもの全部です。
これまでの作業（会話）で作った・直したソースと設定、手順をまとめています。

```
FzClient-project/
├─ mod/        FzClient 本体（Forge 1.8.9 MOD、Java）
├─ launcher/   FzClient Launcher（Electron、Windows 用インストーラー）
├─ server/     FzClient のサーバー（Cloudflare Worker + D1）
├─ website/    紹介サイト（GitHub Pages にそのまま置ける）
└─ docs/       作業の記録・手順書
```

詳しい内容は次を見てください。

| ファイル | 中身 |
|---|---|
| [docs/HISTORY.md](docs/HISTORY.md) | これまでに作った機能・直したこと（会話の記録をまとめたもの） |
| [docs/RELEASE.md](docs/RELEASE.md) | リリースの出し方、よくあるつまずき |
| [docs/SERVER.md](docs/SERVER.md) | Cloudflare Worker の作り・設定・更新の仕方 |
| [docs/DECISIONS.md](docs/DECISIONS.md) | 方針（入れなかった機能とその理由など） |

## ビルド

### MOD（mod/）
- JDK 17 で `build.bat`（または `gradlew build`）。できた jar が `build/libs/fzclient.jar`。
- バージョンは `gradle.properties` の `version` だけを書き換える（mcmod.info に入り、メニューの表示もそこから読む）。

### ランチャー（launcher/）
- Node.js を入れて `npm install` → `npm run dist`。
- `dist/` に `FzClient-Launcher-Setup.exe`、`.blockmap`、`latest.yml`、`fzclient.jar`、`fzclient.json` ができる。
- 中に入れる MOD は `resources/fzclient.jar` を差し替える。

### サイト（website/）
- そのまま GitHub Pages に置ける（リポジトリの `docs/` フォルダに入れて、Settings → Pages → main / docs）。

## 注意
- この zip の `mod/` は作業用にこちらで持っていた写しです。PC 側（`Downloads\FzHUD-source\fzhud`）で手で直した部分があれば、そちらが正です。
- API キーや秘密の値（CurseForge の API キー、ADMIN_KEY など）は入っていません。Cloudflare の Secret にだけ入っています。
- `launcher/resources/fzclient.jar` は入れた時点の MOD（コスメティック入り）です。
