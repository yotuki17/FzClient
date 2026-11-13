# リリースの出し方（自動アップデート）

更新は GitHub の公開リポジトリ **yotuki17/FzClient** の Releases から配られます
（リポジトリを変えるときは `launcher/src/core/updater.js` の `REPO` と `launcher/package.json` の `build.publish` を両方直す）。

## 出し方
1. 手で書いてあるバージョン表記を直す（リポジトリ直下の `CLAUDE.md` のチェックリスト）
   - `launcher/package.json` の `"version"`、`mod/gradle.properties` の `version`
   - ランチャーのお知らせ（`launcher/renderer/index.html` の `news-h` と一覧）
   - サイトの `Latest vX.Y.Z`（リポジトリ直下の `docs/index.html` と `FzClient-project/website/index.html`）
2. commit して main に push
3. `vX.Y.Z` のタグを push する

タグを push すると `.github/workflows/release.yml` が動き、次をやります：
- `mod`: MOD をタグのバージョンでビルドして、次の 2 つに渡す
- `windows`: Windows 版ランチャーをビルドする（`npm run dist`、fzclient.jar / fzclient.json もここで作る）
- `mac`: Mac（Apple Silicon）版ランチャーをビルドする（`npm run dist:mac`、macos-latest、Apple の署名なし＝ad-hoc 署名）
- `publish`: 全部そろってから Release を 1 回だけ作って、次の 10 個を上げる：
  `FzClient-Launcher-Setup.exe` / `.exe.blockmap` / `latest.yml` / `fzclient.jar` / `fzclient.json` /
  `FzClient-Launcher-mac-arm64.dmg` / `.dmg.blockmap` / `FzClient-Launcher-mac-arm64.zip` / `.zip.blockmap` / `latest-mac.yml`

「Latest」になったリリースが使われます。タグを push した時点でみんなのランチャーに届くので、push する前に
commit の中身とバージョンを必ず確認する。

## ユーザー側の動き
- ランチャー起動時: MOD の新しい版があれば裏でダウンロード（「FzClient を ○○ に更新しました」）。
  プレイを押した時にも確認します（オフラインなら今の版でそのまま起動）。
- ランチャーの新しい版は裏でダウンロードされ、右下に「v1.0.1 に更新できます — クリックで再起動」が出ます。
  押さなくても、ランチャーを閉じた時に自動でインストールされます。
- Mac: MOD は同じく自動で更新されます。ランチャーは Apple の署名がないので自分では入れ替えられず、
  「v1.0.1 が出ています — クリックでダウンロード」が出て、押すと dmg のダウンロードが開きます。
  ゲームはインテル版 Java 8 を Rosetta 2 で動かします（LWJGL 2 に Apple Silicon 版がないため）。

## 失敗したとき
- GitHub の Actions タブで `Release` の実行を開いて、赤くなった手順のログを見る。
- `publish` より前で失敗したなら Release はまだ出ていない。直せる原因なら「Re-run failed jobs」でやり直せる。
- 同じバージョンを出し直すときは、Release とタグを消してから、もう一度タグを push する
  （すでにその版に更新した人には、同じ番号だと届かないので、基本は次の番号で出す）。
