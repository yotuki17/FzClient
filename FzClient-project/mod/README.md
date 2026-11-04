# FzHUD (Minecraft 1.8.9 Forge)

オリジナルのPvP向けHUD MOD。Featherのコードは一切使っていません。

## 入っているもの
- コンパス（上部、なめらかスクロール）
- キーストローク WASD + スペース（押すとふわっと色が変わる）
- Ping / FPS / TPS パネル
- CPS（左 | 右、クリックでパルス）
- 移動状態（Flying / Sprinting / Sneaking ...、切り替わりでスライド）
- 時計 / 速度 [m/s]
- ポーション効果（スライドイン、切れそうになると点滅）
- ワールドに入るたびにフェードイン

## 使い方
- **右Shift** で編集画面
  - ドラッグ：移動（画面端・中央にスナップ）
  - 右クリック：ON/OFF
  - マウスホイール：サイズ変更
  - R：全部リセット / Esc：保存して閉じる
- 設定は `.minecraft/config/fzhud.json` に保存

## ビルド方法（Windows）
1. JDK 17以上を入れる（例: Adoptium Temurin 17 / 21）
2. このフォルダの `build.bat` をダブルクリック
3. できた `build\libs\fzhud-1.0.0.jar` を Forge 1.8.9 の `mods` フォルダへ

FeatherOpt-1.0.0-SNAPSHOT.jar（LGPL）も同じ `mods` フォルダに入れればFeatherの軽量化も一緒に使えます。
