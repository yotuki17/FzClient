# これまでの作業記録

会話の中で作った・直したことを、分野ごとにまとめたものです。

## MOD（FzClient 本体）

### メニュー・UI
- 「Liquid Glass UI」を「UI」に改名。見た目を3種類から選べる：リキッドグラス / クラシック / ダッシュボード。
- カテゴリの位置を 左 / 右 / 上（横並び）から選べる。アクセント色・角丸なども変更可。
- 「Client Settings」はカテゴリ一覧から分けて、上の細いボタンにした。押すと全部まとめた1ページが開く。
- 画面の隅のロゴ（全 GUI 共通）の場所・文字色・鳥の色・大きさを設定できる。
- ダッシュボード表示：色付きタイル、有効率のドーナツ、「最近の変更」一覧（config/fzclient-recent.json）。
- タッチ音は実際にボタンに当たったときだけ鳴る。
- 「Serever」の誤字修正、English ボタンの枠ずれ修正。
- バージョンは mcmod.info から読む（gradle.properties だけ変えれば良い）。

### タイトル画面
- オリジナルのタイトル画面。左にお気に入りサーバー、右に **FzClient** と **Cosmetics** のカード。
- 大きいボタン（Singleplayer / Multiplayer）の下は Options と Mods の2つ。
- 左下のアカウントを押すと切り替え画面（Microsoft のデバイスコードログイン、オフライン、Windows の暗号化で保存）。
- タイトル画面の FPS 上限（初期 120）。

### Game menu（Esc）
- 「FzClient Settings / Cosmetics」の段を追加。並び：Back to Game → Achievements/Statistics → Open to LAN → FzClient Settings/Cosmetics → Options/Mod Options → Disconnect。
- 右シフトの FzClient メニューは元の「MODS / Edit HUD」の2枚のまま。

### チャット（Chatting 風）
- タブ：All / Party / Guild / DMs。決まった形の行だけ振り分ける（`Party > ...`、`Guild|Officer > ...`、`From|To ...`）。
- タブで送信先も変わる：Party → /pc、Guild → /gc、DMs → /r。
- チャットヘッド、背景色・透明度・形・角丸、行の高さ、文字の影、ショートカット（例 gg=Good game!）。
- コマンド候補：スペースの後にサブコマンド（説明付き）と名前（フレンド・ギルド・パーティ・DM・タブの人）を表示。名前はチャットから覚える（config/fzclient-names.json）。

### Bed Wars 向け
- Drop Protection：鉄・金・エメラルド・ダイヤ・ブロック以外を捨てない（Bed Wars のみ、Alt で解除）。
- Party Detector：待機ロビーで同時に入ってきた人数から「2Qパーティの可能性」を知らせる。抜けも同じ人数で減らす。HUD に「パーティ: 2Q ×1」/「FREE」。
  - 入る間隔 100ms、抜ける間隔 400ms。自分が入った直後 2 秒は無視。
  - ソロ・デュエル（スコアボードの Mode やロビー定員 2）では表示しない。上限 4Q（モードが分かればチーム人数まで）。

### HUD・見た目
- Fullbright に「暗視の効果」モード（影 MOD でも効く）。
- クロスヘア：一人称以外では消す。透明のプレイヤーに合わせても色を変えない。
- ターゲット HUD：実際にダメージが入ったときだけ（NPC は出ない）。弓が当たったときも出る。
- Ping：Hypixel はタブの ping をほぼ更新しないので、5 秒ごとにサーバーへ自分で ping を測る（PingMeter）。
- Discord のステータスの下に「Website」ボタン。

### FzClient ユーザー同士
- FzClient を使っている人のネームタグとタブに鳥のマーク（サーバーに UUID と最終時刻だけ保存）。

### コスメティック
- マント：FzClient / オーロラ / ミッドナイト / サクラ / 自作。オーラ：ハート・炎・魔法・音符・雪・虹（ドット絵、影 MOD でも見える自前描画）。
- 翼と頭のアクセは作ったが、要望で削除。
- 自作マントは `config/fzclient-cape.png`。どんな画像でも自動でマントの形にする（2:1 はそのまま、46×22 は OptiFine 形式として変換、それ以外は背中に切り抜いて貼る）。
- サーバー経由でほかの FzClient ユーザーにも見える。保存のときは Mojang の本人確認（session join）を通す。透明のプレイヤーには描かない。
- 開く場所：タイトル画面の右のカード、Game menu のボタン。左にキャラのプレビュー（ドラッグで回転）。

## ランチャー
- MOD の自動更新（GitHub Releases の fzclient.json / fzclient.jar）、ランチャー自体の自動更新（electron-updater）。
- MOD ページ：Modrinth と CurseForge から検索・インストール・自動更新。Forge 1.8.9 で動かないもの（全バージョン対応とうたうだけのものも）は出さない。
  - CurseForge は Worker 経由（キーは Worker の Secret）。ただし今のキーは検索 API だけ断られる。
- スクリーンショットのギャラリー。
- スキン：3D プレビュー、ライブラリ（PNG / ドラッグ / プレイヤー名から追加）、Microsoft アカウントのスキン変更、マントの切り替え。
- 最適化：Windows（マウス加速・ゲームモード・録画オフ・高性能 GPU・電源プラン・コアパーキング・マルチメディア設定・前面優先）、ネットワーク（通信制限・LAN の省電力）、NVIDIA（FzClient 専用プロファイル）、Java、ゲーム優先度、FPS 計測（PresentMon）。変える前の値を保存して元に戻せる。
  - 直したバグ：PowerShell の `rv` が Remove-Variable の別名で読み取りが空になっていた／日本語 powercfg の全角コロン／LAN 省電力はドライバーの PnPCapabilities で設定／Add-Content の文字化け／New-Item -Force でキーを作り直していた。
- インストーラー名は 1.0.3 から `FzClient-Launcher-Setup.exe` に固定。

## サーバー（Cloudflare Worker）
- `/hello` `/check` `/bye`：FzClient ユーザーの判定。
- `/cos/*`：コスメティックの保存と取得、自作マントの画像。
- `/cf/*`：CurseForge API の中継。

## 紹介サイト
- 英語のランディングページ（website/）。ダウンロードボタンは GitHub の最新リリースの exe を直接落とす。
