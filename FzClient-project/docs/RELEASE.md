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
- MOD をタグのバージョンでビルドして `launcher/resources/fzclient.jar` に入れる
- ランチャーをビルドする（`npm run dist`）
- Release を作って、次の 5 つを上げる：
  `FzClient-Launcher-Setup.exe` / `FzClient-Launcher-Setup.exe.blockmap` / `latest.yml` / `fzclient.jar` / `fzclient.json`

「Latest」になったリリースが使われます。タグを push した時点でみんなのランチャーに届くので、push する前に
commit の中身とバージョンを必ず確認する。

## ユーザー側の動き
- ランチャー起動時: MOD の新しい版があれば裏でダウンロード（「FzClient を ○○ に更新しました」）。
  プレイを押した時にも確認します（オフラインなら今の版でそのまま起動）。
- ランチャーの新しい版は裏でダウンロードされ、右下に「v1.0.1 に更新できます — クリックで再起動」が出ます。
  押さなくても、ランチャーを閉じた時に自動でインストールされます。

## 失敗したとき
- GitHub の Actions タブで `Release` の実行を開いて、赤くなった手順のログを見る。
- 同じバージョンを出し直すときは、Release とタグを消してから、もう一度タグを push する
  （すでにその版に更新した人には、同じ番号だと届かないので、基本は次の番号で出す）。
