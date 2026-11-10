package dev.fzkc.fzhud;

import dev.fzkc.fzhud.chat.Translator;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ChoiceSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.config.HeaderSetting;
import dev.fzkc.fzhud.config.Module;
import dev.fzkc.fzhud.config.Module.Category;
import dev.fzkc.fzhud.config.SliderSetting;
import dev.fzkc.fzhud.config.TextSetting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** All non-HUD features and their settings. HUD elements register themselves in FzHud.elements. */
public final class Modules {
    private Modules() {}

    // ---- Toggle Sprint / Sneak
    public static final Module sprint = new Module("togglesprint", "Toggle Sprint", Category.PVP,
            "ダッシュ・しゃがみ切替", true);
    public static final BoolSetting sprintToggle = sprint.add(new BoolSetting("sprint", "ダッシュを切り替え式にする", true));
    public static final BoolSetting sneakToggle = sprint.add(new BoolSetting("sneak", "しゃがみ切替を切り替え式にする", true));

    // ---- Fullbright
    public static final Module fullbright = new Module("fullbright", "Fullbright", Category.VISUAL,
            "暗い場所でも明るく見える", true);
    /** 0 = raise the gamma (plain game), 1 = act as if Night Vision were on (works with shader packs too). */
    public static final ChoiceSetting fullbrightMode = fullbright.add(new ChoiceSetting("mode", "明るくする方法", 0,
            "ガンマ（影MODなし向け）", "暗視の効果（影MODでも効く）"));
    public static final SliderSetting brightness = fullbright.add(new SliderSetting("level", "明るさ（ガンマの時）", 1, 100, 1, 100, "x"));
    public static final SliderSetting gamma = fullbright.add(new SliderSetting("gamma", "ガンマ", 0, 1000, 10, 100, "%"));

    // ---- TNT Timer
    public static final Module tnt = new Module("tnttimer", "TNT Timer", Category.VISUAL,
            "TNTの残り時間を表示", true);
    public static final SliderSetting tntDecimals = tnt.add(new SliderSetting("decimals", "小数の桁数", 0, 2, 1, 2, ""));
    public static final SliderSetting tntScale = tnt.add(new SliderSetting("scale", "大きさ", 0.5, 2.0, 0.1, 1.0, "x"));
    public static final BoolSetting tntColorByTime = tnt.add(new BoolSetting("timecolor", "残り時間で色を変える", true));
    public static final ColorSetting tntColor = tnt.add(new ColorSetting("color", "文字の色", 0xFFFFFFFF));
    public static final SliderSetting tntBgOpacity = tnt.add(new SliderSetting("bgopacity", "背景の濃さ", 0, 100, 5, 50, "%"));
    public static final BoolSetting tntShadow = tnt.add(new BoolSetting("shadow", "文字に影をつける", true));
    public static final SliderSetting tntRange = tnt.add(new SliderSetting("range", "表示する距離", 8, 128, 8, 64, "m"));

    // ---- Animations
    public static final Module animations = new Module("animations", "Animations", Category.PVP,
            "1.7風の動きとしゃがみ", true);
    public static final BoolSetting animBlockHit = animations.add(new BoolSetting("blockhit", "ブロックヒット（ガード中も腕振り）", true));
    public static final BoolSetting rightPriority = animations.add(new BoolSetting("rightprio", "左クリック中も右クリックを優先", true));
    public static final BoolSetting animBlockHitBlock = animations.add(new BoolSetting("blockhitblock", "ガード中にブロックを叩くと腕を振る", true));
    public static final BoolSetting animBlockPose = animations.add(new BoolSetting("blockpose", "ガード中の腕の形を補正（三人称）", true));
    public static final SliderSetting animBlockDepth = animations.add(new SliderSetting("blockdepth", "ガードの深さ（三人称）", 0.0, 1.0, 0.05, 0.7, ""));
    public static final SliderSetting animOtherSwing = animations.add(new SliderSetting("otherswing", "他プレイヤーの腕振りの大きさ", 0.1, 1.0, 0.05, 0.3, "x"));
    public static final BoolSetting animHitColor = animations.add(new BoolSetting("hitcolor", "殴られた時に色を付ける（オフで赤くならない）", true));
    public static final ColorSetting animHitColorRgb = animations.add(new ColorSetting("hitcolorrgb", "殴られた時の色", 0xFFFF0000));
    public static final SliderSetting animHitColorAlpha = animations.add(new SliderSetting("hitcoloralpha", "殴られた時の色の濃さ", 0.05, 1.0, 0.05, 0.3, ""));
    public static final BoolSetting animEat = animations.add(new BoolSetting("eat", "食べる・飲む時も腕を振る", true));
    public static final BoolSetting animBow = animations.add(new BoolSetting("bow", "弓を引く時も腕を振る", true));
    public static final SliderSetting animSwingSpeed = animations.add(new SliderSetting("swingspeed", "腕振りの速さ", 0.5, 2.0, 0.05, 1.0, "x"));
    public static final BoolSetting smoothSneak = animations.add(new BoolSetting("smoothsneak", "なめらかにしゃがむ", true));
    public static final SliderSetting sneakDepth = animations.add(new SliderSetting("sneakdepth", "しゃがみの深さ", 0.0, 0.40, 0.01, 0.08, " ブロック"));
    public static final SliderSetting sneakSpeed = animations.add(new SliderSetting("sneakspeed", "しゃがみの速さ", 2, 40, 1, 14, ""));

    // ---- Item Physics
    public static final Module itemPhysics = new Module("itemphysics", "Item Physics", Category.VISUAL,
            "落ちたアイテムを平らに", true);
    public static final SliderSetting physicsSpin = itemPhysics.add(new SliderSetting("spin", "落下中の回転の速さ", 0.0, 3.0, 0.1, 1.0, "x"));

    // ---- Nametags
    public static final Module nametags = new Module("nametags", "Nametags", Category.VISUAL,
            "ネームタグの見た目を変更", false);
    public static final ColorSetting tagBgColor = nametags.add(new ColorSetting("bgcolor", "背景の色", 0xFF000000));
    public static final SliderSetting tagOpacity = nametags.add(new SliderSetting("opacity", "背景の濃さ", 0, 100, 1, 33, "%"));
    public static final SliderSetting tagRadius = nametags.add(new SliderSetting("radius", "角の丸さ（0で四角）", 0, 5, 0.5, 3, ""));
    public static final BoolSetting tagBorder = nametags.add(new BoolSetting("border", "枠線を表示", false));
    public static final ColorSetting tagBorderColor = nametags.add(new ColorSetting("bordercolor", "枠線の色", 0xFF5CE1E6));
    public static final SliderSetting tagBorderAlpha = nametags.add(new SliderSetting("borderalpha", "枠線の濃さ", 0, 1, 0.05, 0.8, ""));
    public static final SliderSetting tagBorderWidth = nametags.add(new SliderSetting("borderwidth", "枠線の太さ", 0.5, 4, 0.25, 1, ""));
    public static final BoolSetting tagShadow = nametags.add(new BoolSetting("shadow", "文字に影をつける", false));
    public static final BoolSetting tagSelf = nametags.add(new BoolSetting("self", "F5で自分の名前も表示", false));
    public static final BoolSetting tagFzIcon = nametags.add(new BoolSetting("fzicon", "FzClient ユーザーの名前にアイコン（自分は F5 で見える）", true));

    // ---- Saturation
    public static final Module saturation = new Module("saturation", "Saturation", Category.HUD,
            "隠し満腹度を表示", true);
    public static final SliderSetting satOpacity = saturation.add(new SliderSetting("opacity", "枠の濃さ", 10, 100, 1, 90, "%"));
    public static final ColorSetting satColor = saturation.add(new ColorSetting("colorhex", "枠の色", 0xFFFFD84A));

    // ---- Motion Blur
    public static final Module motionBlur = new Module("motionblur", "Motion Blur", Category.VISUAL,
            "画面に残像をつける", true);
    public static final SliderSetting blurStrength = motionBlur.add(new SliderSetting("strength", "強さ", 10, 90, 1, 50, "%"));

    // ---- No loading screen when switching servers / lobbies
    public static final Module noLoading = new Module("noloading", "Fast World Swap", Category.SERVER,
            "ワールド移動の読込画面を省略", true);
    public static final BoolSetting noLoadingTerrain = noLoading.add(new BoolSetting("terrain", "「地形をダウンロード中」の画面を出さない", true));
    public static final BoolSetting noLoadingFlash = noLoading.add(new BoolSetting("flash", "切り替え時の一瞬の暗転を出さない", true));

    // ---- Time changer
    public static final Module timeChanger = new Module("timechanger", "Time Changer", Category.VISUAL,
            "時間・天気を自分だけ変更", false);
    public static final ChoiceSetting timeMode = timeChanger.add(new ChoiceSetting("mode", "モード", 0, "時刻を固定", "速く・遅く進める", "現実の時刻に合わせる"));
    public static final SliderSetting timeHour = timeChanger.add(new SliderSetting("hour", "時刻（固定モード）", 0, 24, 0.25, 12, "時"));
    public static final SliderSetting timeSpeed = timeChanger.add(new SliderSetting("speed", "進む速さ（速く・遅くモード）", 0, 20, 0.25, 2, "x"));
    public static final BoolSetting timeNoRain = timeChanger.add(new BoolSetting("norain", "雨・雷を止める", false));

    // ---- Borderless fullscreen (F11)
    public static final Module borderless = new Module("borderless", "Borderless", Category.UTILITY,
            "F11を枠なし全画面に", true);

    // ---- Hotbar scroll limits
    public static final Module hotbar = new Module("hotbarscroll", "Hotbar Scroll", Category.PVP,
            "ホットバーの範囲を制限", false);
    public static final SliderSetting hotbarLeft = hotbar.add(new SliderSetting("left", "左端のスロット", 1, 9, 1, 1, ""));
    public static final SliderSetting hotbarRight = hotbar.add(new SliderSetting("right", "右端のスロット", 1, 9, 1, 9, ""));
    public static final BoolSetting hotbarWrap = hotbar.add(new BoolSetting("wrap", "端まで行ったら反対側にループ", false));
    public static final BoolSetting hotbarInvert = hotbar.add(new BoolSetting("invert", "ホイールの向きを逆にする", false));

    // ---- GUI scale beyond vanilla's Large (x3)
    public static final Module guiScale = new Module("guiscale", "GUI Scale", Category.UTILITY,
            "GUIを×4以上にできる", false);
    public static final SliderSetting guiScaleValue = guiScale.add(new SliderSetting("value", "GUIの大きさ", 1, 6, 1, 4, "x"));

    // ---- Zoom (OptiFine / Lunar style)
    public static final Module zoom = new Module("zoom", "Zoom", Category.PVP,
            "キーを押してズーム", true);
    public static final SliderSetting zoomLevel = zoom.add(new SliderSetting("level", "ズーム倍率", 2, 10, 0.5, 4, "x"));
    public static final BoolSetting zoomScroll = zoom.add(new BoolSetting("scroll", "ズーム中にホイールで倍率を変える", true));
    public static final BoolSetting zoomSmooth = zoom.add(new BoolSetting("smooth", "なめらかにズーム", true));
    public static final SliderSetting zoomSpeed = zoom.add(new SliderSetting("speed", "ズームの速さ", 4, 40, 1, 16, ""));
    public static final BoolSetting zoomSens = zoom.add(new BoolSetting("sens", "ズーム中は感度を倍率に合わせて下げる", true));
    public static final BoolSetting zoomCinematic = zoom.add(new BoolSetting("cinematic", "ズーム中はシネマティックカメラ", false));
    public static final BoolSetting zoomToggle = zoom.add(new BoolSetting("toggle", "押すたびにON/OFF（トグル）", false));

    // ---- 3D skin layers (the outer layer of skins as little cubes)
    public static final Module skinLayers = new Module("skinlayers", "3D Skin Layers", Category.VISUAL,
            "スキンの上のレイヤー（帽子・上着・袖・ズボン）を立体にする", true);
    public static final BoolSetting skinLayersFlatSemi = skinLayers.add(new BoolSetting("flatsemi", "半透明のところは3Dにしない", true));
    public static final SliderSetting skinLayersHead = skinLayers.add(new SliderSetting("headsize", "頭の立体の大きさ（出っぱり）", 0.25, 2.0, 0.05, 1.0, "px"));
    public static final SliderSetting skinLayersBody = skinLayers.add(new SliderSetting("bodysize", "体・腕・足の立体の大きさ（出っぱり）", 0.25, 2.0, 0.05, 1.0, "px"));
    public static final BoolSetting skinLayersOthers = skinLayers.add(new BoolSetting("others", "ほかのプレイヤーも3Dにする", true));
    public static final SliderSetting skinLayersRange = skinLayers.add(new SliderSetting("range", "3Dにする距離", 4, 64, 4, 24, "m"));
    public static final BoolSetting skinLayersPreview = skinLayers.add(new BoolSetting("preview", "Cosmetics と /pv のプレビューも3Dにする", true));

    // ---- Hitbox
    public static final Module hitbox = new Module("hitbox", "Hitbox", Category.PVP,
            "当たり判定の枠を表示", false);
    public static final BoolSetting hitboxPlayersOnly = hitbox.add(new BoolSetting("playersonly", "プレイヤーだけ表示", true));
    public static final BoolSetting hitboxMargin = hitbox.add(new BoolSetting("margin", "攻撃の判定の余白（0.1）を含める", true));
    public static final ColorSetting hitboxColor = hitbox.add(new ColorSetting("color", "枠の色", 0xFFFFFFFF));
    public static final SliderSetting hitboxAlpha = hitbox.add(new SliderSetting("alpha", "枠の濃さ", 0.1, 1, 0.05, 0.8, ""));
    public static final SliderSetting hitboxWidth = hitbox.add(new SliderSetting("width", "枠の太さ", 1, 6, 0.5, 2, ""));
    public static final SliderSetting hitboxRange = hitbox.add(new SliderSetting("range", "表示する距離", 8, 64, 4, 32, "m"));
    public static final BoolSetting hitboxSelf = hitbox.add(new BoolSetting("self", "三人称で自分も表示", false));
    public static final BoolSetting hitboxLines = hitbox.add(new BoolSetting("lines", "目の高さと向きの線", false));

    // ---- Cosmetics (other FzClient users see them too). Not in the MODS list: opened from the title screen and
    //      the Right Shift menu (CosmeticsScreen); the settings only live here so they are saved with the rest.
    public static final Module cosmetics = new Module("cosmetics", "Cosmetics", Category.VISUAL,
            "マント・オーラ・ヘイロー。ほかの FzClient ユーザーにも見えます", true).alwaysOn();
    public static final ChoiceSetting cosCape = cosmetics.add(new ChoiceSetting("cape", "マント", 0,
            "なし", "FzClient", "オーロラ", "ミッドナイト", "サクラ", "オーシャン", "エンバー", "ギャラクシー", "ネオン", "ロイヤル"));
    public static final ChoiceSetting cosAura = cosmetics.add(new ChoiceSetting("aura", "オーラ", 0, "なし", "ハート", "炎", "魔法", "音符", "雪", "虹", "星", "花びら", "泡", "ソウル",
            "エンダー", "ホタル", "落ち葉", "電撃", "ギャラクシー", "ラブ", "シャドウ", "エメラルド"));
    public static final ChoiceSetting cosHalo = cosmetics.add(new ChoiceSetting("halo", "ヘイロー", 0, "なし", "ゴールド", "虹", "ダーク", "アイス"));
    public static final ChoiceSetting cosPet = cosmetics.add(new ChoiceSetting("pet", "ペット", 0, "なし", "子犬", "子猫", "ヒヨコ", "子ブタ", "子ヒツジ", "スライム", "ブレイズ", "ガスト", "スノーゴーレム", "クリーパー",
            "ウーパールーパー", "金のウーパー", "青のウーパー", "オウム", "青いオウム",
            "茶色のウーパー", "水色のウーパー", "緑のオウム", "黄色いオウム", "灰色のオウム"));
    /** Every cape (Mojang, OptiFine, FzClient) as soft cloth that waves, see CapeCloth. */
    public static final BoolSetting cosCapeSmooth = cosmetics.add(new BoolSetting("capesmooth", "マントを滑らかに揺らす", true));
    public static final BoolSetting cosOthers = cosmetics.add(new BoolSetting("others", "ほかの FzClient ユーザーのコスメを表示", true));

    // ---- Party detector (waiting lobbies)
    public static final Module partyDetect = new Module("partydetect", "Party Detector", Category.SERVER,
            "待機ロビーで同時に入ってきた人たち（パーティっぽい）をチャットで知らせる", true);
    public static final SliderSetting partyDetectGap = partyDetect.add(new SliderSetting("gap2", "入ってくる時の間隔（これより近いと同時）", 20, 500, 10, 100, "ms"));
    public static final SliderSetting partyDetectQuitGap = partyDetect.add(new SliderSetting("quitgap", "抜ける時の間隔（これより近いと同時）", 20, 1000, 10, 400, "ms"));
    public static final SliderSetting partyDetectMin = partyDetect.add(new SliderSetting("min", "知らせる人数", 2, 4, 1, 2, "人以上"));
    public static final BoolSetting partyDetectSound = partyDetect.add(new BoolSetting("sound", "見つけた時に音を鳴らす", true));

    // ---- Overlay: player stats in the tab list and chat, /fzstats, tags, denick (id kept as "playerstats" for saved settings)
    public static final Module stats = new Module("playerstats", "Overlay", Category.PVP,
            "タブとチャットに統計とタグを表示・/pv で詳しい統計・デニック", true);
    public static final dev.fzkc.fzhud.config.PageSetting statsPageTab = stats.add(new dev.fzkc.fzhud.config.PageSetting("p_tab", "タブリスト"));
    public static final HeaderSetting statsHeadTab = stats.add(new HeaderSetting("h_tab", "表示"));
    public static final BoolSetting statsTab = stats.add(new BoolSetting("tab", "タブに統計を表示（Bed Wars・Duels・TNT Tag・Bow Spleef・Murder Mystery）", true));
    public static final BoolSetting statsTabLobby = stats.add(new BoolSetting("lobby", "ロビーでも統計を表示", false));
    public static final HeaderSetting statsHeadLook = stats.add(new HeaderSetting("h_look", "見た目"));
    /** 0 = like the normal tab list, 1 = shaded (column titles, shaded rows), 2 = clean (column titles, no stripes). */
    public static final ChoiceSetting statsTabStyle = stats.add(new ChoiceSetting("tabstyle", "タブの見た目", 1,
            "いつもの", "影つき（shaded）", "すっきり（clean）"));
    public static final BoolSetting statsRankColors = stats.add(new BoolSetting("rankcolors2", "試合中の名前をランクの色にする（オフ＝チームの色）", false));
    public static final BoolSetting statsTagsInTab = stats.add(new BoolSetting("tabtags", "タブにタグを表示", true));
    public static final BoolSetting statsStar = stats.add(new BoolSetting("star", "Bed Wars：名前の前に星を表示", true));
    public static final dev.fzkc.fzhud.config.PageSetting statsPageCols = stats.add(new dev.fzkc.fzhud.config.PageSetting("p_cols", "タブの列"));
    /** [game][column]: which stat each tab list column shows (0 = none, else index + 1 into Game.stats()). */
    public static final ChoiceSetting[][] statsColumns = statsColumns();

    private static ChoiceSetting[][] statsColumns() {
        dev.fzkc.fzhud.stats.Game[] games = dev.fzkc.fzhud.stats.Game.values();
        ChoiceSetting[][] out = new ChoiceSetting[games.length][];
        for (dev.fzkc.fzhud.stats.Game g : games) {
            dev.fzkc.fzhud.stats.Game.Stat[] all = g.stats();
            String[] options = new String[all.length + 1];
            options[0] = "なし";
            for (int i = 0; i < all.length; i++) options[i + 1] = all[i].label;
            stats.add(new HeaderSetting("h_cols_" + g.key, g.label + " のタブの列（クリックで切り替え・右クリックで戻る）"));
            int[] def = g.defaultColumns();
            out[g.ordinal()] = new ChoiceSetting[dev.fzkc.fzhud.stats.Game.TAB_COLUMNS];
            for (int c = 0; c < dev.fzkc.fzhud.stats.Game.TAB_COLUMNS; c++) {
                int d = c < def.length ? def[c] + 1 : 0;
                out[g.ordinal()][c] = stats.add(new ChoiceSetting("col_" + g.key + "_" + c, "列 " + (c + 1), d, options));
            }
        }
        return out;
    }
    public static final dev.fzkc.fzhud.config.PageSetting statsPageData = stats.add(new dev.fzkc.fzhud.config.PageSetting("p_data", "データ・API"));
    public static final HeaderSetting statsHeadSource = stats.add(new HeaderSetting("h_source", "統計の取得元（使えない時は Bordic）"));
    /** Index into StatsApi.Source: 0 Hypixel API, 1 Bordic, 2 Bedlify, 3 Urchin. */
    public static final ChoiceSetting statsSource = stats.add(new ChoiceSetting("source", "試合中", 0,
            "Hypixel API（キー必要）", "Bordic", "Bedlify", "Urchin（Coral キー）"));
    public static final ChoiceSetting statsLobbySource = stats.add(new ChoiceSetting("lobbysource", "ロビー（チャット・タブ）", 1,
            "Hypixel API（キー必要）", "Bordic", "Bedlify", "Urchin（Coral キー）"));
    public static final BoolSetting statsBordicFresh = stats.add(new BoolSetting("bordicfresh", "Bordic のデータが30分より古い時は Hypixel API / Urchin のキーで取り直す", true));
    public static final HeaderSetting statsHeadKeys = stats.add(new HeaderSetting("h_keys", "キー（空なら Mellow の設定から自動で読み込み）"));
    public static final TextSetting statsHypixelKey = stats.add(new TextSetting("hypixelkey", "Hypixel API キー", "", 64).secret());
    public static final TextSetting denickAuroraKey = stats.add(new TextSetting("aurorakey", "Aurora API キー（Bed Wars の 過去24時間・7日・30日とデニック）", "", 128).secret());
    public static final HeaderSetting statsHeadPv = stats.add(new HeaderSetting("h_pv", "/pv コマンド"));
    /** 0 = window, 1 = chat. */
    public static final ChoiceSetting statsPvMode = stats.add(new ChoiceSetting("pvmode", "/pv の表示", 0, "画面", "チャット"));
    public static final HeaderSetting statsHeadData = stats.add(new HeaderSetting("h_data", "記録"));
    public static final BoolSetting statsHistory = stats.add(new BoolSetting("history", "過去24時間・7日・30日の統計のために記録する（この PC だけ）", true));
    public static final dev.fzkc.fzhud.config.PageSetting statsPageChat = stats.add(new dev.fzkc.fzhud.config.PageSetting("p_chat", "チャット"));
    public static final HeaderSetting statsHeadChat = stats.add(new HeaderSetting("h_chat", "チャット"));
    public static final BoolSetting statsChat = stats.add(new BoolSetting("chat", "発言した人の統計をメッセージの前に付ける（待機ロビー・試合中は全員、ロビーは自分の名前を出した人だけ）", true));
    public static final BoolSetting statsChatParty = stats.add(new BoolSetting("chatparty", "パーティチャットの発言には毎回統計を付ける（ロビーでも）", true));
    public static final BoolSetting statsChatInvite = stats.add(new BoolSetting("chatinvite", "パーティに招待してきた人の統計をチャットに出す（Mellow と同じ）", true));
    public static final BoolSetting statsChatStart = stats.add(new BoolSetting("chatstart", "試合が始まったら全員の統計をチャットに出す", true));
    public static final HeaderSetting statsHeadWarn = stats.add(new HeaderSetting("h_warn", "警告"));
    public static final BoolSetting statsChatWarn = stats.add(new BoolSetting("chatwarn", "タグ付きの人が発言したら警告", true));
    public static final BoolSetting statsChatWarnSound = stats.add(new BoolSetting("chatwarnsound", "警告の時に音を鳴らす", true));
    public static final dev.fzkc.fzhud.config.PageSetting statsPageTags = stats.add(new dev.fzkc.fzhud.config.PageSetting("p_tags", "タグ"));
    public static final HeaderSetting statsHeadWatcher = stats.add(new HeaderSetting("h_watcher", "Watcher"));
    public static final BoolSetting statsWatcher = stats.add(new BoolSetting("watcher", "Watcher のタグを使う", false));
    public static final TextSetting statsWatcherKey = stats.add(new TextSetting("watcherkey", "Watcher API キー", "", 128).secret());
    public static final HeaderSetting statsHeadXadia = stats.add(new HeaderSetting("h_xadia", "Xadia"));
    public static final BoolSetting statsXadia = stats.add(new BoolSetting("xadia", "Xadia のタグを使う", false));
    public static final TextSetting statsXadiaKey = stats.add(new TextSetting("xadiakey", "Xadia API キー", "", 128).secret());
    public static final BoolSetting statsXadiaVerified = stats.add(new BoolSetting("xadiaverified", "Xadia は確認済みのタグだけ", false));
    public static final HeaderSetting statsHeadCoral = stats.add(new HeaderSetting("h_coral", "Coral（Urchin）"));
    public static final BoolSetting statsCoral = stats.add(new BoolSetting("coral", "Coral（Urchin）のタグを使う", false));
    public static final TextSetting statsCoralKey = stats.add(new TextSetting("coralkey", "Coral（Urchin）API キー（タグと Bed Wars の過去24時間・7日・30日）", "", 128).secret());
    public static final dev.fzkc.fzhud.config.PageSetting statsPageDenick = stats.add(new dev.fzkc.fzhud.config.PageSetting("p_denick", "デニック"));
    public static final HeaderSetting denickHead = stats.add(new HeaderSetting("h_denick", "スキンで調べる"));
    public static final BoolSetting denickSkin = stats.add(new BoolSetting("denickskin", "スキンで調べる（キー不要・本人が今そのスキンなら確定）", true));
    public static final HeaderSetting denickHeadNum = stats.add(new HeaderSetting("h_denicknum", "数字で調べる"));
    public static final BoolSetting denickNumbers = stats.add(new BoolSetting("denicknumbers", "Bed Wars のファイナル・ベッドの数字で調べる（Aurora キーが必要）", true));
    public static final SliderSetting denickMinFinals = stats.add(new SliderSetting("denickmin", "数字で調べるファイナル数の下限", 1000, 30000, 500, 15000, ""));
    public static final HeaderSetting denickHeadTeam = stats.add(new HeaderSetting("h_denickteam", "味方を消去法で調べる"));
    public static final BoolSetting denickTeam = stats.add(new BoolSetting("denickteam", "パーティメンバーのうちタブに本名がいない人と、味方のニックを突き合わせる", true));
    public static final BoolSetting denickAskParty = stats.add(new BoolSetting("denickaskparty", "パーティが分からない時は /pl を自動で送る（結果は表示しない）", true));
    public static final HeaderSetting denickHeadShow = stats.add(new HeaderSetting("h_denickshow", "表示"));
    public static final BoolSetting denickShowReal = stats.add(new BoolSetting("denickshowreal", "見破ったニックはタブとネームタグで本名にする（後ろにニックを小さく）", true));

    // ---- Drop protection (only resources and blocks can be dropped)
    public static final Module dropLock = new Module("droplock", "Drop Protection", Category.PVP,
            "鉄・金・エメラルド・ダイヤ・ブロック以外を捨てないようにする", true);
    public static final BoolSetting dropLockBedwars = dropLock.add(new BoolSetting("bedwars", "Bed Wars の時だけ（スコアボードで判定）", true));
    public static final HeaderSetting dropLockHead = dropLock.add(new HeaderSetting("h_allow", "捨ててもいいもの"));
    public static final BoolSetting dropLockIron = dropLock.add(new BoolSetting("iron", "鉄インゴット", true));
    public static final BoolSetting dropLockGold = dropLock.add(new BoolSetting("gold", "金インゴット", true));
    public static final BoolSetting dropLockEmerald = dropLock.add(new BoolSetting("emerald", "エメラルド", true));
    public static final BoolSetting dropLockDiamond = dropLock.add(new BoolSetting("diamond", "ダイヤモンド", true));
    public static final BoolSetting dropLockBlocks = dropLock.add(new BoolSetting("blocks", "ブロック（羊毛・粘土・エンドストーンなど）", true));
    public static final HeaderSetting dropLockHead2 = dropLock.add(new HeaderSetting("h_more", "そのほか"));
    public static final BoolSetting dropLockAlt = dropLock.add(new BoolSetting("alt", "Alt を押している間は何でも捨てられる", true));
    public static final BoolSetting dropLockNotice = dropLock.add(new BoolSetting("notice", "止めた時に画面に知らせる", true));

    // ---- Raw mouse input (Windows)
    public static final Module rawInput = new Module("rawinput", "Raw Mouse Input", Category.PVP,
            "Windowsのマウス加速を通さず、マウスの生の動きで視点を動かす（Windowsのみ）", false);

    // ---- Menu look & layout (id stays "glassui" so saved settings keep working)
    public static final Module glassUi = new Module("glassui", "UI", Category.UTILITY,
            "メニューの見た目・色・配置を調整する", true).alwaysOn();
    /** One choice that sets the style, colours and roundness together; changing a single setting afterwards is fine. */
    public static final ChoiceSetting uiTheme = glassUi.add(new ChoiceSetting("theme", "テーマ", 0,
            "カスタム", "ダーク", "ホワイト", "カジュアル", "ネオン", "サクラ", "オーシャン", "ミッドナイト"));
    public static final HeaderSetting uiHeadStyle = glassUi.add(new HeaderSetting("h_style", "スタイル"));
    /** 0 = liquid glass, 1 = classic (dark flat), 2 = dashboard (light, coloured tiles). */
    public static final ChoiceSetting uiStyle = glassUi.add(new ChoiceSetting("style", "スタイル", 0, "リキッドグラス", "クラシック", "ダッシュボード"));
    /** The title screen draws its own buttons; this makes them follow the liquid glass style too. */
    public static final BoolSetting uiMenuGlass = glassUi.add(new BoolSetting("menuglass", "タイトル画面もリキッドグラスにする", true));
    /** Off = the game's own screens (multiplayer, options, Esc menu...) follow the style too, see VanillaUi. */
    public static final BoolSetting uiVanillaScreens = glassUi.add(new BoolSetting("vanillascreens", "マルチプレイ・設定・Esc メニューなどをバニラの表示にする", false));
    public static final BoolSetting glassBlur = glassUi.add(new BoolSetting("blur", "ゲーム画面をぼかす", true));
    public static final SliderSetting glassBlurAmount = glassUi.add(new SliderSetting("bluramount", "ぼかしの強さ", 2, 20, 1, 8, ""));
    public static final SliderSetting glassTint = glassUi.add(new SliderSetting("tint", "ガラスの明るさ", 0, 100, 5, 50, "%"));
    public static final SliderSetting uiRadius = glassUi.add(new SliderSetting("radius", "角の丸み", 0, 200, 10, 100, "%"));

    public static final HeaderSetting uiHeadColor = glassUi.add(new HeaderSetting("h_color", "色"));
    public static final ColorSetting uiAccent = glassUi.add(new ColorSetting("accent", "アクセント色", 0xFF5CE1E6));
    public static final ColorSetting uiPanelColor = glassUi.add(new ColorSetting("panelcolor", "パネルの色", 0xFF0C1215));
    public static final SliderSetting uiPanelOpacity = glassUi.add(new SliderSetting("panelopacity", "パネルの濃さ", 20, 100, 5, 100, "%"));
    public static final ColorSetting uiTextColor = glassUi.add(new ColorSetting("textcolor", "文字の色", 0xFFFFFFFF));
    public static final SliderSetting uiBackdrop = glassUi.add(new SliderSetting("backdrop", "後ろの画面の暗さ", 0, 100, 5, 60, "%"));

    public static final HeaderSetting uiHeadLayout = glassUi.add(new HeaderSetting("h_layout", "配置"));
    public static final ChoiceSetting uiPosition = glassUi.add(new ChoiceSetting("position", "メニューの位置", 0, "中央", "左寄せ", "右寄せ"));
    public static final ChoiceSetting uiSidebarSide = glassUi.add(new ChoiceSetting("sidebar", "カテゴリの位置", 0, "左", "右", "上（横並び）"));
    public static final SliderSetting uiSize = glassUi.add(new SliderSetting("size", "メニューの大きさ", 70, 130, 5, 100, "%"));
    public static final ChoiceSetting uiColumns = glassUi.add(new ChoiceSetting("columns", "カードの列数", 0, "自動", "1", "2", "3", "4"));
    public static final SliderSetting uiCardHeight = glassUi.add(new SliderSetting("cardheight", "カードの高さ", 48, 84, 2, 60, ""));
    public static final SliderSetting uiCardGap = glassUi.add(new SliderSetting("cardgap", "カードの間隔", 2, 16, 1, 8, ""));

    public static final HeaderSetting uiHeadLogo = glassUi.add(new HeaderSetting("h_logo", "ロゴ"));
    /** Where the bird + FzClient logo goes in the corner: every screen (also pause menu / inventory), FzClient's menus only, nowhere. */
    public static final ChoiceSetting uiLogoPlace = glassUi.add(new ChoiceSetting("logoplace", "ロゴを出す場所", 0,
            "すべての画面", "FzClient のメニューだけ", "出さない"));
    public static final ColorSetting uiLogoColor = glassUi.add(new ColorSetting("logocolor", "ロゴの文字色", 0xFFFFFFFF));
    public static final ColorSetting uiLogoBirdColor = glassUi.add(new ColorSetting("logobird", "鳥の色（白 = 元の色）", 0xFFFFFFFF));
    public static final SliderSetting uiLogoSize = glassUi.add(new SliderSetting("logosize", "ロゴの大きさ", 50, 200, 10, 100, "%"));

    // ---- Hit Delay Fix (1.8.9 ignores clicks for 10 ticks after a missed swing)
    public static final Module hitDelayFix = new Module("hitdelayfix", "Hitreg", Category.PVP,
            "hitregを良くする", true).alwaysOn();

    // ---- the launcher's keys (overlay, recording): which of Ctrl (1), Shift (2), Alt (4) go with each key.
    //      The keys themselves are Minecraft key bindings (Controls, options.txt); set in the Alt+X overlay.
    public static final Module launcherKeys = new Module("launcherkeys", "Launcher keys", Category.UTILITY,
            "オーバーレイと録画のキーの Ctrl / Shift / Alt", true).alwaysOn();
    private static final String[] KEY_MODS = {"0", "1", "2", "3", "4", "5", "6", "7"};
    public static final ChoiceSetting keyModsOverlay = launcherKeys.add(new ChoiceSetting("overlay", "オーバーレイ", 4, KEY_MODS));
    public static final ChoiceSetting keyModsRecord = launcherKeys.add(new ChoiceSetting("record", "録画", 0, KEY_MODS));
    public static final ChoiceSetting keyModsSave = launcherKeys.add(new ChoiceSetting("save", "直前を保存", 0, KEY_MODS));

    // ---- Block outline
    public static final Module blockOutline = new Module("blockoutline", "Block Outline", Category.VISUAL,
            "ブロック枠の色・太さ", false);
    public static final ColorSetting outlineColor = blockOutline.add(new ColorSetting("color", "枠の色", 0xFF5CE1E6));
    public static final SliderSetting outlineAlpha = blockOutline.add(new SliderSetting("alpha", "枠の濃さ", 0.1, 1, 0.05, 0.8, ""));
    public static final SliderSetting outlineWidth = blockOutline.add(new SliderSetting("width", "枠の太さ", 1, 10, 0.5, 2.5, ""));
    public static final BoolSetting outlineFill = blockOutline.add(new BoolSetting("fill", "中を塗りつぶす", false));
    public static final ColorSetting outlineFillColor = blockOutline.add(new ColorSetting("fillcolor", "塗りつぶしの色", 0xFF5CE1E6));
    public static final SliderSetting outlineFillAlpha = blockOutline.add(new SliderSetting("fillalpha", "塗りつぶしの濃さ", 0.05, 0.8, 0.05, 0.2, ""));
    public static final BoolSetting outlineThrough = blockOutline.add(new BoolSetting("through", "壁越しにも枠を見せる", false));

    // ---- Overlays: hurt cam, fire
    public static final Module overlays = new Module("overlays", "Hurt Cam / Low Fire", Category.PVP,
            "被ダメ時の揺れ・FOV・炎", true);
    public static final SliderSetting hurtCam = overlays.add(new SliderSetting("hurtcam", "ダメージ時の画面の揺れ", 0, 1, 0.05, 0.0, "x"));
    public static final ChoiceSetting fireMode = overlays.add(new ChoiceSetting("fire", "炎の表示", 1, "そのまま", "低くする", "消す"));
    public static final BoolSetting hidePumpkin = overlays.add(new BoolSetting("pumpkin", "カボチャをかぶった時の視界のフタを消す", false));
    public static final BoolSetting hideBossbar = overlays.add(new BoolSetting("bossbar", "上のボスバー（Hypixelの広告）を消す", false));
    public static final SliderSetting fireHeight = overlays.add(new SliderSetting("fireheight", "炎を下げる量", 0.05, 0.5, 0.05, 0.3, ""));
    public static final SliderSetting kbFov = overlays.add(new SliderSetting("kbfov", "被弾時のFOV（＋広げる/－狭める/0オフ）", -30, 30, 1, 10, "°"));
    public static final SliderSetting kbFovTime = overlays.add(new SliderSetting("kbfovtime", "広げている時間", 0.2, 1.5, 0.05, 0.5, "秒"));

    // ---- Height Limit (learned per game and map from "Build height limit reached!")
    public static final Module heightLimit = new Module("heightlimit", "Height Limit", Category.SERVER,
            "高さ上限までのブロック数を表示（一度上限に当たるとマップごとに覚える）", true);
    public static final BoolSetting heightLimitText = heightLimit.add(new BoolSetting("text", "照準の下に「上限まで」を表示", true));
    public static final BoolSetting heightLimitAlways = heightLimit.add(new BoolSetting("always", "遠くてもいつも表示", false));
    public static final BoolSetting heightLimitPlane = heightLimit.add(new BoolSetting("plane", "近づいたら上限の高さに面を表示", true));
    public static final SliderSetting heightLimitWarn = heightLimit.add(new SliderSetting("warn", "表示し始める距離", 1, 30, 1, 8, " ブロック"));
    public static final ColorSetting heightLimitColor = heightLimit.add(new ColorSetting("color", "面の色", 0xFFFF5555));

    // ---- Auto GG (Hypixel: every game with an end summary — Bed Wars, Duels, Sky Wars...)
    public static final Module autoGG = new Module("autogg", "Auto GG", Category.SERVER,
            "ゲーム終了時に自動でgg", false);
    public static final TextSetting autoGGText = autoGG.add(new TextSetting("text", "送る言葉", "gg", 40));
    public static final SliderSetting autoGGDelay = autoGG.add(new SliderSetting("delay", "送るまでの時間", 0, 5, 0.05, 1, "秒"));
    public static final BoolSetting autoGG2 = autoGG.add(new BoolSetting("second", "2つ目も送る", false));
    public static final TextSetting autoGGText2 = autoGG.add(new TextSetting("text2", "2つ目の言葉", "gg wp", 40));
    public static final SliderSetting autoGGDelay2 = autoGG.add(new SliderSetting("delay2", "1つ目から2つ目までの時間", 0, 5, 0.05, 0.5, "秒"));
    public static final BoolSetting autoGGHypixelOnly = autoGG.add(new BoolSetting("hypixel", "Hypixelでだけ動かす", true));

    // ---- Background FPS limit
    public static final Module bgFps = new Module("bgfps", "Unfocused FPS", Category.UTILITY,
            "非アクティブ時にFPSを下げる", true);
    public static final SliderSetting bgFpsLimit = bgFps.add(new SliderSetting("limit", "その間のFPS上限", 5, 60, 5, 15, " FPS"));

    // ---- Performance (draw less of what is far away)
    public static final Module performance = new Module("performance", "Performance", Category.UTILITY,
            "遠くの物・壁の向こうの物を描かずに軽くする（見え方が少し変わる）", false);
    /** Title screen / menus without a world: Minecraft itself caps these at 30 FPS. Always applies (not only with Performance on). */
    public static final SliderSetting perfTitleFps = performance.add(new SliderSetting("titlefps", "タイトル画面のFPS上限（常に有効・260で無制限）", 30, 260, 10, 120, " FPS"));
    public static final SliderSetting perfItems = performance.add(new SliderSetting("items", "アイテム・経験値の描画距離", 4, 32, 2, 12, "m"));
    public static final SliderSetting perfMobs = performance.add(new SliderSetting("mobs", "モブ・アーマースタンドの描画距離", 8, 64, 4, 32, "m"));
    public static final SliderSetting perfFrames = performance.add(new SliderSetting("frames", "額縁・絵画の描画距離", 4, 64, 4, 24, "m"));
    public static final BoolSetting perfCull = performance.add(new BoolSetting("cull", "壁の向こうのモブ・アイテムを描かない", false));
    public static final BoolSetting perfNoWeather = performance.add(new BoolSetting("noweather", "雨・雪を描かない", false));
    public static final BoolSetting perfNoClouds = performance.add(new BoolSetting("noclouds", "雲を描かない", false));
    public static final SliderSetting perfMenuFps = performance.add(new SliderSetting("menufps", "メニュー中のFPS上限（240で無制限）", 15, 240, 5, 240, " FPS"));

    // ---- Optimization in the style of Patcher: small things OptiFine and FzClientOpt don't touch, so all three
    //      work together. The defaults keep the picture exactly as before; only the GL error checks are skipped.
    public static final Module optimize = new Module("optimize", "Optimization", Category.UTILITY,
            "見た目を変えずに軽くする（OptiFine と一緒に使える）", true);
    public static final BoolSetting optHolograms = optimize.add(new BoolSetting("holograms", "ホログラム（浮いている文字）を軽く描く", true));
    public static final BoolSetting optGlErrors = optimize.add(new BoolSetting("glerrors", "GL エラーの確認を省く（毎フレームの待ちが減る）", true));
    public static final SliderSetting optTileDist = optimize.add(new SliderSetting("tiledist", "チェスト・看板・頭などの描画距離（64 = いつも通り）", 8, 64, 4, 64, "m"));
    public static final SliderSetting optParticles = optimize.add(new SliderSetting("maxparticles", "パーティクルの上限（4000 = いつも通り）", 200, 4000, 200, 4000, ""));
    public static final BoolSetting optNoParticles = optimize.add(new BoolSetting("noparticles", "パーティクルを完全に消す", false));
    public static final BoolSetting optNoGlint = optimize.add(new BoolSetting("noglint", "エンチャントの光沢を描かない", false));

    // ---- Bug fixes in the style of Patcher
    public static final Module fixes = new Module("fixes", "Bug Fixes", Category.UTILITY,
            "1.8.9 のバグを直す", true);
    public static final BoolSetting fixInventory = fixes.add(new BoolSetting("inventory", "ポーション効果中にインベントリが右にずれるのを直す", true));
    public static final BoolSetting fixMouseDelay = fixes.add(new BoolSetting("mousedelay", "マウスの遅れを直す（狙った所にちゃんと当たる）", true));
    public static final BoolSetting fixResourcePack = fixes.add(new BoolSetting("resourcepack", "サーバーのリソースパックを使った攻撃を防ぐ", true));

    // ---- Branding (window title, icon, loading logo, main menu)
    public static final Module branding = new Module("branding", "FzClient Branding", Category.UTILITY,
            "名前・アイコン・タイトル画面", true);
    public static final TextSetting brandTitle = branding.add(new TextSetting("title", "ウィンドウの名前", "FzClient 1.8.9", 60));
    public static final BoolSetting fzUsersShowMe = branding.add(new BoolSetting("showme", "自分が FzClient を使っていることをほかの FzClient ユーザーに見せる", true));
    public static final BoolSetting fzUsersOthers = branding.add(new BoolSetting("others", "ほかの FzClient ユーザーの名前の横にもロゴを出す", true));
    public static final BoolSetting brandSplash = branding.add(new BoolSetting("splash", "起動ロゴと色をFzClientに（次回起動）", true));
    public static final BoolSetting brandIcon = branding.add(new BoolSetting("icon", "ウィンドウアイコンを変える（次回起動）", true));
    public static final BoolSetting brandCustomMenu = branding.add(new BoolSetting("custommenu", "オリジナルのタイトル画面（動く背景）", true));
    public static final BoolSetting brandMenuTitle = branding.add(new BoolSetting("menutitle", "バニラ画面の題字をFzClientに", true));
    public static final ColorSetting brandMenuColor = branding.add(new ColorSetting("menucolor", "バニラのタイトル画面の文字の色", 0xFF5CE1E6));
    public static final BoolSetting brandLine = branding.add(new BoolSetting("brandline", "タイトル画面にバージョンを表示", true));

    // ---- Reconnect
    public static final Module reconnect = new Module("reconnect", "Reconnect", Category.SERVER,
            "切断時の再接続ボタン", true);
    public static final BoolSetting reconnectAuto = reconnect.add(new BoolSetting("auto", "自動で再接続する", false));
    public static final SliderSetting reconnectDelay = reconnect.add(new SliderSetting("delay", "自動再接続までの秒数", 1, 30, 1, 5, "秒"));

    // ---- Screenshot helper
    public static final Module screenshot = new Module("screenshot", "Screenshot Helper", Category.UTILITY,
            "スクショを保存＆コピー", true);
    public static final BoolSetting screenshotCopy = screenshot.add(new BoolSetting("copy", "クリップボードにコピーする", true));
    public static final BoolSetting screenshotAsync = screenshot.add(new BoolSetting("async", "裏で保存して、撮る瞬間のカクつきをなくす", true));
    public static final BoolSetting screenshotPreview = screenshot.add(new BoolSetting("preview", "撮ったスクショのプレビューを出す", true));
    public static final SliderSetting screenshotPreviewTime = screenshot.add(new SliderSetting("previewtime", "プレビューを出す秒数", 2, 15, 1, 5, "秒"));

    // ---- Low HP warning
    public static final Module lowHp = new Module("lowhp", "Low HP Warning", Category.PVP,
            "低体力で画面が赤くなる", false);
    public static final SliderSetting lowHpHearts = lowHp.add(new SliderSetting("hearts", "ハートがいくつ以下で出すか", 1, 10, 0.5, 4, "個"));
    public static final BoolSetting lowHpPulse = lowHp.add(new BoolSetting("pulse", "ドクドク点滅させる", true));

    // ---- Hit particles
    public static final Module particles = new Module("particles", "Particles", Category.PVP,
            "殴った時の粒子を増やす", false);
    public static final SliderSetting particleMult = particles.add(new SliderSetting("multiplier", "量", 1, 5, 1, 2, "x"));
    public static final BoolSetting particleSharp = particles.add(new BoolSetting("sharp", "毎回ダメージ増加（青い）パーティクル", true));
    public static final BoolSetting particleCrit = particles.add(new BoolSetting("crit", "毎回クリティカルのパーティクル", false));

    // ---- Death coordinates
    public static final Module deathCoords = new Module("deathcoords", "Death Coordinates", Category.UTILITY,
            "死亡座標をチャットに表示", true);

    // ---- Discord Rich Presence
    public static final Module discord = new Module("discord", "Discord", Category.UTILITY,
            "Discordにプレイ中表示", true);
    public static final BoolSetting discordServer = discord.add(new BoolSetting("server", "サーバーのアドレスを表示", true));
    public static final BoolSetting discordMode = discord.add(new BoolSetting("mode", "スコアボードの題名（BED WARSなど）を表示", true));

    // ---- Chat (everything chat related in one place)
    public static final Module chat = new Module("chat", "Chat", Category.VISUAL,
            "チャットの便利機能", true);

    public static final dev.fzkc.fzhud.config.PageSetting chatPage_look = chat.add(new dev.fzkc.fzhud.config.PageSetting("p_look", "表示"));
    public static final HeaderSetting chatHead1 = chat.add(new HeaderSetting("h_smooth", "なめらか表示"));
    public static final BoolSetting smoothChatOn = chat.add(new BoolSetting("smooth", "新しいチャットを下からスッと出す", true));
    public static final SliderSetting chatSpeed = chat.add(new SliderSetting("smoothspeed", "その速さ", 2, 40, 1, 14, ""));

    public static final HeaderSetting chatHead2 = chat.add(new HeaderSetting("h_tweaks", "表示・操作"));
    public static final BoolSetting chatTimestamps = chat.add(new BoolSetting("timestamps", "時刻を表示 [12:34]", false));
    public static final BoolSetting chatSeconds = chat.add(new BoolSetting("seconds", "時刻に秒も付ける", false));
    public static final BoolSetting chatCompact = chat.add(new BoolSetting("compact", "同じメッセージが続いたら1行にまとめる (x2)", true));
    public static final BoolSetting chatCopy = chat.add(new BoolSetting("copy", "Ctrl+クリックでメッセージをコピー", true));

    public static final HeaderSetting chatHeadLook = chat.add(new HeaderSetting("h_look", "見た目・タブ・チャットヘッド"));
    public static final BoolSetting chatCustom = chat.add(new BoolSetting("custom", "FzClient のチャット表示を使う（OFF = いつもの表示）", true));
    public static final BoolSetting chatTabs = chat.add(new BoolSetting("tabs", "チャットを開いた時にタブを出す（すべて / パーティ / ギルド / 個人）", true));
    public static final BoolSetting chatTabSend = chat.add(new BoolSetting("tabsend", "タブで送り先も変える（パーティ = /pc、ギルド = /gc、個人 = /r）", true));
    public static final BoolSetting chatHeads = chat.add(new BoolSetting("heads", "発言の左にスキンの顔を出す", true));
    public static final ColorSetting chatBgColor = chat.add(new ColorSetting("bgcolor", "背景の色", 0xFF000000));
    public static final SliderSetting chatBgOpacity = chat.add(new SliderSetting("bgopacity", "背景の濃さ", 0, 100, 5, 50, "%"));
    public static final ChoiceSetting chatBgStyle = chat.add(new ChoiceSetting("bgstyle", "背景の形", 0, "行ごと", "まとめて1枚"));
    public static final SliderSetting chatRadius = chat.add(new SliderSetting("radius", "角の丸み（まとめて1枚の時）", 0, 8, 1, 3, ""));
    public static final SliderSetting chatLineHeight = chat.add(new SliderSetting("lineheight", "行の高さ", 9, 14, 1, 9, ""));
    public static final BoolSetting chatShadow = chat.add(new BoolSetting("shadow", "文字に影を付ける", true));

    public static final dev.fzkc.fzhud.config.PageSetting chatPage_short = chat.add(new dev.fzkc.fzhud.config.PageSetting("p_short", "略語"));
    public static final HeaderSetting chatHeadShort = chat.add(new HeaderSetting("h_short", "ショートカット（「略語=送る文」で書く。略語だけ打って Enter で置き換え）"));
    public static final BoolSetting chatShortcutsOn = chat.add(new BoolSetting("shortcuts", "ショートカットを使う", true));
    public static final TextSetting[] chatShortcuts = {
            chat.add(new TextSetting("short1", "ショートカット 1", "gg=Good game!", 120)),
            chat.add(new TextSetting("short2", "ショートカット 2", "gl=Good luck, have fun!", 120)),
            chat.add(new TextSetting("short3", "ショートカット 3", "", 120)),
            chat.add(new TextSetting("short4", "ショートカット 4", "", 120)),
            chat.add(new TextSetting("short5", "ショートカット 5", "", 120)),
            chat.add(new TextSetting("short6", "ショートカット 6", "", 120)),
    };

    public static final dev.fzkc.fzhud.config.PageSetting chatPage_sound = chat.add(new dev.fzkc.fzhud.config.PageSetting("p_sound", "通知"));
    public static final HeaderSetting chatHeadSound = chat.add(new HeaderSetting("h_sound", "通知音"));
    public static final BoolSetting chatPartySound = chat.add(new BoolSetting("partysound", "パーティチャットで音を鳴らす", true));
    public static final BoolSetting chatMentionSound = chat.add(new BoolSetting("mentionsound", "自分の名前が呼ばれたら音を鳴らす", true));
    public static final TextSetting chatMentionWords = chat.add(new TextSetting("mentionwords", "名前以外に反応する言葉（, 区切り）", "", 80));
    public static final BoolSetting chatMentionMark = chat.add(new BoolSetting("mentionmark", "呼ばれたメッセージの頭に @ を付ける", true));
    public static final ChoiceSetting chatSoundType = chat.add(new ChoiceSetting("soundtype", "音の種類", 0, "ピコン", "ポロン（経験値）", "ハープ"));
    public static final SliderSetting chatSoundVolume = chat.add(new SliderSetting("soundvolume", "音量", 0.1, 1.0, 0.05, 0.7, ""));
    public static final BoolSetting chatInviteSound = chat.add(new BoolSetting("invitesound", "パーティ招待・フレンド申請が来たら音を鳴らす", true));
    public static final HeaderSetting chatHeadPopup = chat.add(new HeaderSetting("h_popup", "招待のポップアップ（Y で受ける・N で断る）"));
    public static final BoolSetting chatPopupParty = chat.add(new BoolSetting("popupparty", "パーティ招待をポップアップで出す", true));
    public static final BoolSetting chatPopupFriend = chat.add(new BoolSetting("popupfriend", "フレンド申請をポップアップで出す", true));
    public static final ChoiceSetting chatPopupPos = chat.add(new ChoiceSetting("popuppos", "場所", 0, "上の真ん中", "右上", "左上"));
    public static final SliderSetting chatPopupTime = chat.add(new SliderSetting("popuptime", "出しておく時間", 5, 60, 1, 15, "秒"));
    public static final dev.fzkc.fzhud.config.PageSetting chatPage_bw = chat.add(new dev.fzkc.fzhud.config.PageSetting("p_bw", "Bed Wars"));
    public static final HeaderSetting bwHeadVoid = chat.add(new HeaderSetting("h_void", "void（奈落）に落ちた時"));
    /** 0 = off, 1 = everyone, 2 = teammates, 3 = enemies. */
    public static final ChoiceSetting bwVoidSound = chat.add(new ChoiceSetting("voidsound", "落ちた時に音を鳴らす", 1, "オフ", "全員", "味方だけ", "敵だけ"));
    public static final BoolSetting bwVoidParty = chat.add(new BoolSetting("voidparty", "味方が落ちたらパーティチャットに送る", false));
    public static final TextSetting bwVoidText = chat.add(new TextSetting("voidtext", "送る文（{name} = 落ちた人の名前）", "{name} fell into the void!", 80));
    public static final BoolSetting bwVoidPartySelf = chat.add(new BoolSetting("voidpartyself", "自分が落ちた時も送る", false));

    public static final dev.fzkc.fzhud.config.PageSetting chatPage_relay = chat.add(new dev.fzkc.fzhud.config.PageSetting("p_relay", "転送"));
    public static final HeaderSetting chatHeadRelay = chat.add(new HeaderSetting("h_relay", "Discordへ転送（/fzrelay test でテスト）"));
    public static final BoolSetting relayOn = chat.add(new BoolSetting("relay", "パーティチャットを送る", false));
    public static final TextSetting relayUrl = chat.add(new TextSetting("relayurl", "Webhook URL（人に見せない）", "", 200));
    public static final BoolSetting relaySelf = chat.add(new BoolSetting("relayself", "自分の発言も送る", true));

    public static final dev.fzkc.fzhud.config.PageSetting chatPage_tr = chat.add(new dev.fzkc.fzhud.config.PageSetting("p_tr", "翻訳"));
    public static final HeaderSetting chatHead3 = chat.add(new HeaderSetting("h_translate", "翻訳（/tr en こんにちは で翻訳して送信）"));
    public static final BoolSetting translatorOn = chat.add(new BoolSetting("translate", "外国語のチャットを翻訳する", false));
    public static final ChoiceSetting trMode = chat.add(new ChoiceSetting("trmode", "翻訳のしかた", 0, "[訳] ボタンを押した時だけ", "自動で翻訳して下に表示"));
    public static final ChoiceSetting trScope = chat.add(new ChoiceSetting("trscope", "対象", 0, "プレイヤーのチャットだけ", "すべてのメッセージ"));
    public static final ChoiceSetting trTarget = chat.add(new ChoiceSetting("trtarget", "翻訳先の言語", 0, Translator.LANG_NAMES));
    public static final ChoiceSetting trSendLang = chat.add(new ChoiceSetting("trsendlang", "/tr で送る時の言語（省略時）", 1, Translator.LANG_NAMES));
    public static final ChoiceSetting trProvider = chat.add(new ChoiceSetting("trprovider", "翻訳サービス", 0, "Google（キー不要）", "DeepL（APIキーが必要）"));
    public static final TextSetting trDeeplKey = chat.add(new TextSetting("deeplkey", "DeepLのAPIキー", "", 80));

    public static final dev.fzkc.fzhud.config.PageSetting chatPage_history = chat.add(new dev.fzkc.fzhud.config.PageSetting("p_history", "履歴"));
    public static final HeaderSetting chatHead4 = chat.add(new HeaderSetting("h_history", "履歴（/fzhistory でコマンド履歴、/chatlog でログ）"));
    public static final BoolSetting historyOn = chat.add(new BoolSetting("history", "チャットとコマンドの履歴を残す", true));
    public static final BoolSetting historyKeep = chat.add(new BoolSetting("keep", "メニューやサーバー移動でチャットを消さない", true));
    public static final BoolSetting historySent = chat.add(new BoolSetting("sent", "送信履歴（↑キー）を次回に引き継ぐ", true));
    public static final SliderSetting historyScroll = chat.add(new SliderSetting("scrollback", "さかのぼれるチャットの行数（ゲーム中ずっと）", 500, 20000, 500, 10000, "行"));
    public static final BoolSetting historyLog = chat.add(new BoolSetting("log", "チャットを日付別ファイルに保存", true));

    public static final dev.fzkc.fzhud.config.PageSetting chatPage_input = chat.add(new dev.fzkc.fzhud.config.PageSetting("p_input", "入力"));
    public static final HeaderSetting chatHead5 = chat.add(new HeaderSetting("h_ime", "日本語入力"));
    public static final BoolSetting imeOn = chat.add(new BoolSetting("ime", "チャットで日本語（IME）を打てるようにする", true));
    public static final ChoiceSetting imeMode = chat.add(new ChoiceSetting("imemode", "入力のしかた", 0, "チャットに直接打つ", "別ウィンドウの入力欄"));
    public static final BoolSetting imeCommandAscii = chat.add(new BoolSetting("cmdascii", "「/」でチャットを開いた時は英数入力にする", true));
    public static final BoolSetting imeAuto = chat.add(new BoolSetting("imeauto", "[別窓] チャットを開いたら入力欄を出す", true));
    public static final BoolSetting imeSendDirect = chat.add(new BoolSetting("imesend", "[別窓] Enterで直接送信（OFFは入力のみ）", true));

    public static final HeaderSetting chatHeadCmd = chat.add(new HeaderSetting("h_cmd", "コマンド候補"));
    public static final BoolSetting cmdSuggestOn = chat.add(new BoolSetting("cmdsuggest", "コマンドの候補を出す（Tab決定・↑↓選択）", true));
    public static final BoolSetting cmdHypixel = chat.add(new BoolSetting("cmdhypixel", "Hypixelの主なコマンドも候補に入れる", true));
    public static final SliderSetting cmdRows = chat.add(new SliderSetting("cmdrows", "候補の最大数", 3, 12, 1, 8, "個"));

    /** Each chat feature is on when the Chat module and its own switch are both on. */
    public static boolean smoothChatEnabled() { return chat.enabled && smoothChatOn.get(); }
    public static boolean chatTweaksEnabled() { return chat.enabled; }
    public static boolean translatorEnabled() { return chat.enabled && translatorOn.get(); }
    public static boolean chatHistoryEnabled() { return chat.enabled && historyOn.get(); }
    public static boolean imeEnabled() { return chat.enabled && imeOn.get(); }
    public static boolean commandSuggestEnabled() { return chat.enabled && cmdSuggestOn.get(); }
    public static boolean partyRelayEnabled() { return chat.enabled && relayOn.get(); }

    // ---- Language
    public static final Module language = new Module("language", "Language", Category.UTILITY,
            "表示言語を切り替え", true).alwaysOn();
    public static final ChoiceSetting langChoice = language.add(new ChoiceSetting("lang", "言語 / Language", 0, "日本語", "English"));

    // ---- Theme (chroma speed etc.)
    public static final Module theme = new Module("theme", "Chroma", Category.VISUAL,
            "クロマ（虹色）の流れる速さ", true).alwaysOn();
    public static final SliderSetting chromaSpeed = theme.add(new SliderSetting("speed", "1周にかかる秒数", 0.5, 10, 0.5, 3, "秒"));

    // ---- Menu click sound
    public static final Module menuSound = new Module("menusound", "Menu Sound", Category.UTILITY,
            "メニューのクリック音", true);
    public static final ChoiceSetting menuSoundType = menuSound.add(new ChoiceSetting("type", "音の種類", 0, "ボタン", "ポコッ", "ピコン", "カチッ", "決定"));
    public static final BoolSetting menuSoundCancel = menuSound.add(new BoolSetting("cancel", "キャンセル音（戻る・閉じる・OFFにする時）", true));
    public static final SliderSetting menuSoundPitch = menuSound.add(new SliderSetting("pitch", "音の高さ", 0.5, 2.0, 0.05, 1.0, "x"));

    /**
     * Modules that belong to the client itself (look, window, language ...). The menu lists them under
     * "クライアント設定" instead of their own category. Add an id here to move another module there.
     */
    public static final List<String> CLIENT_SETTINGS = Arrays.asList(
            "branding", "theme", "borderless", "screenshot", "guiscale", "menusound", "language", "glassui");

    public static boolean isClientSetting(Module m) {
        return CLIENT_SETTINGS.contains(m.id);
    }

    private static Module clientPage;

    /**
     * One page with every client setting: each client module's name (with its switch) followed by its settings.
     * The settings are the modules' own objects, so changes here are the same as on their own pages. Not saved
     * as a module of its own.
     */
    public static Module clientPage() {
        if (clientPage == null) {
            Module page = new Module("clientsettings", "Client Settings", Category.UTILITY, "クライアント全体の設定", true).alwaysOn();
            // most used first: the look of the menus, language, then the rest
            String[] order = {"glassui", "language", "theme", "menusound", "branding", "borderless", "guiscale", "screenshot"};
            List<String> ids = new ArrayList<String>(Arrays.asList(order));
            for (String id : CLIENT_SETTINGS) if (!ids.contains(id)) ids.add(id);
            for (String id : ids) {
                for (Module m : FEATURES) {
                    if (!m.id.equals(id) || hiddenInMenu(m)) continue;
                    page.add(new dev.fzkc.fzhud.config.ModuleHeaderSetting(m));
                    for (dev.fzkc.fzhud.config.Setting st : m.settings) page.add(st);
                }
            }
            clientPage = page;
        }
        return clientPage;
    }

    /** Always on and nothing to set, so the menus don't list it. */
    public static boolean hiddenInMenu(Module m) {
        return m == hitDelayFix || m == cosmetics || m == launcherKeys;
    }

    public static final List<Module> FEATURES = new ArrayList<Module>(Arrays.asList(
            sprint, fullbright, tnt, animations, itemPhysics, nametags, saturation, motionBlur, guiScale, borderless, noLoading, timeChanger, hotbar, zoom, rawInput, dropLock, partyDetect, stats, cosmetics, skinLayers, hitbox, hitDelayFix, blockOutline, launcherKeys, overlays, heightLimit, autoGG, bgFps, performance, optimize, fixes, chat, discord, reconnect, screenshot, lowHp, particles, deathCoords, branding, menuSound, language, glassUi, theme));

}
