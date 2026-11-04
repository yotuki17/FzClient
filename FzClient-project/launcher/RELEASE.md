# 自動アップデートの出し方

更新は GitHub の公開リポジトリ **yotuki17/FzClient** の Releases から配られます
（変えるときは `src/core/updater.js` の `REPO` と `package.json` の `build.publish` を両方直す）。

## 最初に1回だけ
1. GitHub で **Public** のリポジトリ `FzClient` を作る（中身は空でOK）。

## MOD だけ更新するとき（ランチャーはそのまま）
1. 新しい `fzclient-1.0.0.jar` を `resources/fzclient.jar` に上書き
2. `npm run dist`
3. GitHub → Releases → **Draft a new release** → タグは何でもOK（例 `mod-20261101`）
4. `dist/fzclient.jar` と `dist/fzclient.json` の **2つ** をアップロードして Publish
   - 「Latest」になっているリリースが使われます
   - ランチャー更新用のファイル（下の3つ）も一緒に入れておくと、ランチャーの更新も止まりません

## ランチャーも更新するとき
1. `package.json` の `"version"` を上げる（例 1.0.0 → 1.0.1）。**上げないと更新されません**
2. `npm run dist`
3. 新しいリリース（タグ `v1.0.1`）に `dist/` の次の5つをアップロード:
   - `FzClient-Launcher-Setup.exe`（1.0.3 から名前にバージョンが入りません）
   - `FzClient-Launcher-Setup.exe.blockmap`
   - `latest.yml`
   - `fzclient.jar`
   - `fzclient.json`

## ユーザー側の動き
- ランチャー起動時: MOD の新しい版があれば裏でダウンロード（「FzClient を ○○ に更新しました」）。
  プレイを押した時にも確認します（オフラインなら今の版でそのまま起動）。
- ランチャーの新しい版は裏でダウンロードされ、右下に「v1.0.1 に更新できます — クリックで再起動」が出ます。
  押さなくても、ランチャーを閉じた時に自動でインストールされます。

## 同じタグに上げ直すとき
ファイル名が固定なので、リリースの Edit で古い5つを🗑で消してから、新しい5つを上げ直す（exe と blockmap・latest.yml は必ずセットで）。
