# 方針・決めたこと

## 入れない機能
- **チートになるもの**：QUICK MATHS の自動回答、チャットの自動返信、透明のプレイヤーを見えるようにするもの（クロスヘアの色変化も含む）。
- **他人の有料・独自のコード**：FeatherOpt（fzclientopt.jar）の再配布、Rago Optimizer の中身の解析。最適化は一般に知られた設定と、MIT ライセンスのツール（NVIDIA Profile Inspector、PresentMon）だけで作った。

## プライバシー・安全
- FzClient ユーザー判定のサーバーには UUID と最終時刻しか置かない。
- コスメの保存は Mojang の本人確認を通す（他人の装備を書き換えられない）。
- API キーなどはランチャーや MOD に入れず、Worker の Secret に置く。
- 最適化は変える前の値を保存して、いつでも元に戻せるようにする。

## バージョンの付け方
- 言われない限りバージョンは上げずに作り直す。リリースのときだけ上げる。
- MOD のバージョンは `gradle.properties` だけ。ランチャーは `package.json`。

## 未解決・やり残し
- CurseForge：今の API キーは検索だけ 403。CurseForge に検索の許可を申請するか、Project ID を貼って入れる方式に変える案あり。
- 自作マントを「自分だけに見せる」スイッチ（提案のみ）。
- 紹介サイトの GitHub Pages 公開（手順は README）。
