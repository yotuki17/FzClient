# FzClient（Minecraft 1.8.9 Forge MOD）

FzClient 本体です。HUD・PvP 向けの機能・メニュー・コスメティックなどが入っています。

## 使い方
- **右Shift** で FzClient のメニュー（HUD の配置・各機能の設定）
- 設定は `.minecraft/config/fzclient.json` に保存（昔の `fzhud.json` は、`fzclient.json` が無いときに一度だけ読み込みます）

## ビルド方法（Windows）
1. JDK 17 を入れる（例: Adoptium Temurin 17）
2. このフォルダの `build.bat` をダブルクリック（または `gradlew build`）
3. `build\libs\fzclient-<バージョン>.jar` ができる

ふだんは手でビルドしなくて大丈夫です。`v1.0.15` のようなタグを push すると、GitHub Actions が MOD とランチャーを
ビルドしてリリースまで出します（[docs/RELEASE.md](../docs/RELEASE.md)）。

軽量化 MOD の FzClientOpt（FeatherOpt, LGPL）はランチャーが一緒に入れます。
