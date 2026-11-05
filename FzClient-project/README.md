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

### リリース
- ふだんは手でビルドしません。`vX.Y.Z` のタグを push すると、GitHub Actions が MOD とランチャーを
  そのバージョンでビルドして Release を出します（[docs/RELEASE.md](docs/RELEASE.md)）。

### MOD（mod/）
- JDK 17 で `build.bat`（または `gradlew build`）。できた jar が `build/libs/fzclient-<バージョン>.jar`。
- バージョンは `gradle.properties` の `version`（mcmod.info に入り、メニューの表示もそこから読む）。リリースではタグの番号が使われます。

### ランチャー（launcher/）
- Node.js を入れて `npm install` → `npm run dist`。
- `dist/` に `FzClient-Launcher-Setup.exe`、`.blockmap`、`latest.yml`、`fzclient.jar`、`fzclient.json` ができる。
- 中に入れる MOD は `resources/fzclient.jar`（リリースでは CI がその時の MOD で置き換えます）。

### サイト（website/）
- 公開されているのはリポジトリ直下の `docs/`（GitHub Pages: main / docs）。`website/` はその元で、直すときは両方そろえる。

## 注意
- API キーや秘密の値（CurseForge の API キー、ADMIN_KEY など）は入っていません。Cloudflare の Secret にだけ入っています。
