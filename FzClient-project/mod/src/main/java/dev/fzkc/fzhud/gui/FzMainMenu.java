package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.feature.Branding;
import dev.fzkc.fzhud.feature.SplashAnim;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.Icons;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiOptions;
import net.minecraft.client.gui.GuiSelectWorld;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.client.GuiModList;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * FzClient's own title screen: a slowly turning sepia panorama with dust in the light, the bird logo,
 * and big buttons (singleplayer, multiplayer, FzClient settings, options, mods, quit).
 */
public class FzMainMenu extends FzScreen {
    private static final ResourceLocation BIRD = new ResourceLocation("fzclient", "textures/bird.png");   // 256x146
    private static final ResourceLocation[] SPLASH = {
            new ResourceLocation("fzclient", "textures/splash_bird.png"),
            new ResourceLocation("fzclient", "textures/splash_glow.png"),
            new ResourceLocation("fzclient", "textures/splash_text.png"),
            new ResourceLocation("fzclient", "textures/splash_sub.png"),
    };
    /** The loading-screen → title-screen transition plays once per game start. */
    private static boolean introPlayed;
    private static final float INTRO_LEN = 1.15f;
    private static final ResourceLocation TITLE = new ResourceLocation("fzclient", "textures/title.png"); // 512x191

    private static final int CYAN = 0x5CE1E6, MAGENTA = 0xE83ECE;
    private static final int PARTICLES = 70;
    private static final float[] PX = new float[PARTICLES], PY = new float[PARTICLES], PS = new float[PARTICLES],
            PV = new float[PARTICLES], PP = new float[PARTICLES];

    static {
        Random r = new Random(1234);
        for (int i = 0; i < PARTICLES; i++) {
            PX[i] = r.nextFloat();
            PY[i] = r.nextFloat();
            PS[i] = 0.6f + r.nextFloat() * 1.4f;   // size
            PV[i] = 4f + r.nextFloat() * 10f;      // rise speed (virtual px / s)
            PP[i] = r.nextFloat() * 6.28f;         // twinkle phase
        }
    }

    private final Anim open = new Anim(0f, 5f);
    private final Map<String, Anim> hover = new HashMap<>();
    private final long start = System.currentTimeMillis();
    private float mxs, mys;
    /** Plays the intro (continuing the loading screen) instead of the normal fade-in. */
    private final boolean intro;
    /** Intro clock: advanced per frame with a capped step, so a hitch while the game finishes loading
     *  (first frames of the title screen) doesn't make the transition jump to its end. */
    private float introT = -1f;
    private long lastFrame;

    /** Your own skin's face for the footer (falls back to Steve/Alex until it has downloaded). */
    private static ResourceLocation skin;
    private static boolean skinRequested;

    // ---------------------------------------------------------------- favourite servers (left side)

    private static final ResourceLocation UNKNOWN_SERVER = new ResourceLocation("textures/misc/unknown_server.png");
    private static final float FAV_W = 156, FAV_H = 30;
    private java.util.List<net.minecraft.client.multiplayer.ServerData> favs = new java.util.ArrayList<>();
    private final net.minecraft.client.network.OldServerPinger pinger = new net.minecraft.client.network.OldServerPinger();

    private float favX() { return 14; }
    private float favY() { return Math.max(logoY() + 10, vh * 0.30f); }

    // ---- the FzClient shortcuts on the right: the same rows as the favourites, mirrored (header, size, spacing)
    private static final String[][] QUICK = {
            // id, icon, title, subtitle
            {"quick_fz", "menu_fz", "設定・MOD", "FzClient の機能"},
            {"quick_cos", "menu_account", "コスメティック", "マント・オーラ"},
            // (the account switcher is the face + name in the bottom left: the third row is the gallery)
    };
    private static final int[] QUICK_TINT = {CYAN, MAGENTA};
    private static final int GALLERY_TINT = 0xFFD84A;
    private float quickX() { return vw - favX() - FAV_W; }
    private float quickY(int i) { return favRowY(i); }

    private void drawQuick(int mx, int my, float o) {
        String head = "FzClient";
        float hx = quickX() + FAV_W - 2 - R.width(head);
        R.text(head, hx, favY(), R.alpha(Ui.accent, o));
        headLine(quickX() + 2, hx - 6, favY() + 4, true, Ui.accent, o);
        for (int i = 0; i < QUICK.length; i++) quickCard(i, mx, my, o);
        galleryCard(mx, my, o);
    }

    // ---- the gallery: the newest screenshots / clips under the shortcuts (clips and the big view: the launcher's overlay)
    private static final float THUMB_GAP = 4;
    private float galY() { return quickY(QUICK.length); }
    private float thumbW() { return (FAV_W - 8 - THUMB_GAP * (dev.fzkc.fzhud.util.RecentShots.MAX - 1)) / dev.fzkc.fzhud.util.RecentShots.MAX; }
    private float thumbH() { return thumbW() * 9f / 16f; }
    private float thumbX(int k) { return quickX() + 4 + k * (thumbW() + THUMB_GAP); }
    private float thumbY() { return galY() + FAV_H - 2; }
    /** The pictures only when they fit above the footer. */
    private boolean galThumbs() { return thumbY() + thumbH() + 4 <= vh - 30; }
    private float galH() { return galThumbs() ? FAV_H - 2 + thumbH() + 4 : FAV_H; }
    private int recentAsk;

    private void galleryCard(int mx, int my, float o) {
        java.util.List<dev.fzkc.fzhud.util.RecentShots.Item> items = dev.fzkc.fzhud.util.RecentShots.items();
        float y = galY(), gh = galH();
        float h = anim("quick_gal").update(Ui.inside(mx, my, quickX(), y, FAV_W, gh) ? 1 : 0);
        float x = quickX() - h * 2f, dx = x - quickX();
        card(x, y, FAV_W, gh, h, 0xFF000000 | GALLERY_TINT, o);
        R.rounded(x + 4, y + 4, 22, 22, 11f, R.alpha(0x38000000 | GALLERY_TINT, o));
        Icons.draw("screenshot", x + 8, y + 8, 14, R.alpha(0xFF000000 | GALLERY_TINT, o));
        R.text("ギャラリー", x + 31, y + 5, R.alpha(Ui.text, o));
        String sub = items.isEmpty() ? "F2 でスクショ・Alt+X で録画" : "スクショ・クリップ";
        R.text(Ui.trim(dev.fzkc.fzhud.util.Lang.t(sub), FAV_W - 48), x + 31, y + 16, R.alpha(Ui.MUTED, o));
        R.text("›", x + FAV_W - 10 + h * 2f, y + 11, R.alpha(R.lerpColor(0xFF5A666A, 0xFF000000 | GALLERY_TINT, h), o));
        if (!galThumbs()) return;
        float tw = thumbW(), th = thumbH(), ty = thumbY();
        for (int k = 0; k < dev.fzkc.fzhud.util.RecentShots.MAX; k++) {
            float tx = thumbX(k) + dx;
            dev.fzkc.fzhud.util.RecentShots.Item it = k < items.size() ? items.get(k) : null;
            float th2 = anim("gal" + k).update(it != null && Ui.inside(mx, my, thumbX(k), ty, tw, th) ? 1 : 0);
            R.rounded(tx, ty, tw, th, 3f, R.alpha(0x70000000, o));
            if (it != null && it.tex != null) image(it.tex, tx, ty, tw, th, 0xFFFFFF, o);
            if (it != null && it.clip) {
                // a play mark in the middle
                float cx = tx + tw / 2f, cy = ty + th / 2f;
                R.rounded(cx - 6, cy - 6, 12, 12, 6f, R.alpha(0xA0000000, o));
                R.triangleRight(cx + 0.5f, cy, 3f, R.alpha(0xFFFFFFFF, o));
            }
            R.roundedOutline(tx, ty, tw, th, 3f, 1f, R.alpha(R.lerpColor(0x30FFFFFF, 0xFF000000 | GALLERY_TINT, th2), o));
        }
    }

    /** A click on the gallery card: a picture opens big in the launcher's overlay (or the file itself without it). */
    private boolean clickGallery(int mx, int my) {
        if (!Ui.inside(mx, my, quickX() - 2, galY(), FAV_W + 2, galH())) return false;
        java.util.List<dev.fzkc.fzhud.util.RecentShots.Item> items = dev.fzkc.fzhud.util.RecentShots.items();
        boolean link = dev.fzkc.fzhud.feature.LauncherLink.connected();
        if (galThumbs()) {
            for (int k = 0; k < items.size() && k < dev.fzkc.fzhud.util.RecentShots.MAX; k++) {
                if (!Ui.inside(mx, my, thumbX(k), thumbY(), thumbW(), thumbH())) continue;
                if (link) dev.fzkc.fzhud.feature.LauncherLink.openGallery(items.get(k).file);
                else openFile(new java.io.File(new java.io.File(mc.mcDataDir, "screenshots"), items.get(k).file));
                return true;
            }
        }
        if (link) dev.fzkc.fzhud.feature.LauncherLink.openGallery(null);
        else openFile(new java.io.File(mc.mcDataDir, "screenshots"));
        return true;
    }

    private static void openFile(java.io.File f) {
        if (f.isDirectory() || !f.getParentFile().exists()) f.mkdirs();
        try {
            Runtime.getRuntime().exec(new String[] {"explorer.exe", f.getAbsolutePath()});
        } catch (Exception e) {
            org.lwjgl.Sys.openURL(f.toURI().toString());
        }
    }

    private void quickCard(int i, int mx, int my, float o) {
        String[] q = QUICK[i];
        int tint = QUICK_TINT[i];
        float y = quickY(i);
        float h = anim(q[0]).update(Ui.inside(mx, my, quickX(), y, FAV_W, FAV_H) ? 1 : 0);
        float x = quickX() - h * 2f;   // slides a little towards the middle, like the favourites do
        card(x, y, FAV_W, FAV_H, h, 0xFF000000 | tint, o);
        // icon in a round badge where the favourites have their server icon
        R.rounded(x + 4, y + 4, 22, 22, 11f, R.alpha(0x38000000 | tint, o));
        Icons.draw(q[1], x + 8, y + 8, 14, R.alpha(0xFF000000 | tint, o));
        R.text(q[2], x + 31, y + 5, R.alpha(Ui.text, o));
        R.text(Ui.trim(dev.fzkc.fzhud.util.Lang.t(q[3]), FAV_W - 48), x + 31, y + 16, R.alpha(Ui.MUTED, o));
        R.text("›", x + FAV_W - 10 + h * 2f, y + 11, R.alpha(R.lerpColor(0xFF5A666A, 0xFF000000 | tint, h), o));
    }

    /** Title screen buttons in liquid glass, when that style is picked and switched on for the title screen. */
    private static boolean glass() {
        return Glass.on() && dev.fzkc.fzhud.Modules.uiMenuGlass.get();
    }

    /** A dark card (favourites, shortcuts, small buttons) or a glass one; edge colour shows on hover. */
    private static void card(float x, float y, float w, float hgt, float hover, int edge, float o) {
        if (glass()) {
            Glass.surface(x, y, w, hgt, Ui.rad(6f), o, hover, 0f, 0.7f + hover * 0.3f);
            if (hover > 0.01f) R.roundedOutline(x, y, w, hgt, Ui.rad(6f), 1f, R.alpha(R.alpha(edge, hover * 0.8f), o));
            return;
        }
        R.rounded(x + 1, y + 2, w, hgt, 5f, R.alpha(0x50000000, o));   // soft shadow
        R.rounded(x, y, w, hgt, 5f, R.alpha(R.lerpColor(0xA80A0F12, 0xDC121C21, hover), o));
        R.rect(x + 5, y + 0.5f, w - 10, 0.5f, R.alpha(0x26FFFFFF, o));   // light on the top edge
        R.roundedOutline(x, y, w, hgt, 5f, 1f, R.alpha(R.lerpColor(0x22FFFFFF, edge, hover * 0.85f), o));
        // the card's colour as a thin stripe on its left, brighter on hover
        float sh = Math.min(hgt - 12, 18);
        R.rounded(x + 1.5f, y + (Math.min(hgt, FAV_H) - sh) / 2f, 1.5f, sh, 0.75f, R.alpha(R.alpha(edge, 0.35f + 0.65f * hover), o));
    }

    /** A thin line beside a list's header, fading out away from the text. */
    private static void headLine(float x0, float x1, float y, boolean fadeLeft, int argb, float o) {
        if (x1 - x0 < 4) return;
        int c = R.alpha(R.alpha(argb, 0.55f), o), z = c & 0x00FFFFFF;
        if (fadeLeft) Ui.gradient(x0, y, x1 - x0, 0.75f, z, c, z, c);
        else Ui.gradient(x0, y, x1 - x0, 0.75f, c, z, c, z);
    }

    private float favRowY(int i) { return favY() + 16 + i * (FAV_H + 5); }
    private int favCount() { return Math.min(favs.size(), Math.max(1, (int) ((vh - 40 - favY() - 16) / (FAV_H + 5)))); }

    @Override
    public void initGui() {
        super.initGui();
        favs = dev.fzkc.fzhud.util.Favorites.servers(); // fresh from servers.dat (also after starring in the list)
        favLine = new String[0];
        favLinePop = new String[0];
        favLinePing = new long[0];
        for (net.minecraft.client.multiplayer.ServerData sd : favs) {
            sd.pingToServer = -2L; // "checking"
            sd.populationInfo = "";
        }
        favTries = new int[favs.size()];
        favAt = new long[favs.size()];
        // right after the game starts another mod (Mellow: its replay recorder sees every connection) is not ready
        // yet and the first pings failed in it (NullPointerException: every favourite "Offline" until the
        // multiplayer screen had been opened). So the first ping of a game waits a moment, and a server that could
        // not be reached is asked again (3 times in all).
        long first = System.currentTimeMillis() + (pingedOnce ? 0 : 2500);
        pingedOnce = true;
        java.util.Arrays.fill(favAt, first);
    }

    private static boolean pingedOnce;
    private int[] favTries = new int[0];
    private long[] favAt = new long[0];

    private static boolean unreachable(net.minecraft.client.multiplayer.ServerData sd) {
        return sd.pingToServer == -1L && sd.serverMOTD != null && sd.serverMOTD.contains("Can't connect");
    }

    /** Sends the pings that are due (on a thread: looking up the address and connecting take a while). */
    private void pingDue() {
        long now = System.currentTimeMillis();
        for (int i = 0; i < favs.size() && i < favTries.length; i++) {
            final net.minecraft.client.multiplayer.ServerData sd = favs.get(i);
            boolean due = favTries[i] == 0 ? now >= favAt[i] : favTries[i] < 3 && unreachable(sd) && now - favAt[i] > 3000;
            if (!due) continue;
            favTries[i]++;
            favAt[i] = now;
            Thread t = new Thread(() -> {
                try {
                    pinger.ping(sd);
                } catch (Throwable e) {
                    sd.pingToServer = -1L;
                    sd.serverMOTD = "Can't connect";
                }
            }, "FzClient-FavPing");
            t.setDaemon(true);
            t.start();
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        pingDue();
        // the newest screenshots / clips: now, and every 5 s (a new one, the launcher connecting a moment later)
        if (recentAsk++ % 100 == 0) dev.fzkc.fzhud.util.RecentShots.refresh();
        try {
            pinger.pingPendingNetworks();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onGuiClosed() {
        try {
            pinger.clearPendingNetworks();
        } catch (Throwable ignored) {
        }
        super.onGuiClosed();
    }

    private void drawFavourites(int mx, int my, float o) {
        float x = favX(), y = favY();
        String head = "★ お気に入り";
        R.text(head, x + 2, y, R.alpha(0xFFFFD84A, o));
        headLine(x + 2 + R.width(head) + 6, x + FAV_W - 2, y + 4, false, 0xFFFFD84A, o);
        if (favs.isEmpty()) {
            if (glass()) Glass.surface(x, y + 14, FAV_W, 30, Ui.rad(6f), o, 0f, 0f, 0.6f);
            else R.rounded(x, y + 14, FAV_W, 30, 4f, R.alpha(0x90101A1E, o));
            R.text(Ui.trim("マルチプレイの一覧で ☆ を", FAV_W - 12), x + 6, y + 19, R.alpha(Ui.MUTED, o));
            R.text(Ui.trim("押すとここに出ます", FAV_W - 12), x + 6, y + 30, R.alpha(Ui.MUTED, o));
            return;
        }
        int count = favCount();
        if (favAnims.length < count) {
            Anim[] grown = java.util.Arrays.copyOf(favAnims, count);
            for (int i = favAnims.length; i < count; i++) grown[i] = new Anim(0f, 14f);
            favAnims = grown;
        }
        for (int i = 0; i < count; i++) {
            net.minecraft.client.multiplayer.ServerData sd = favs.get(i);
            float ry = favRowY(i);
            float h = favAnims[i].update(Ui.inside(mx, my, x, ry, FAV_W, FAV_H) ? 1 : 0);
            card(x + h * 2, ry, FAV_W, FAV_H, h, 0xFFFFD84A, o);
            // icon
            ResourceLocation icon = dev.fzkc.fzhud.util.Favorites.icon(sd);
            image(icon != null ? icon : UNKNOWN_SERVER, x + h * 2 + 4, ry + 4, 22, 22, 0xFFFFFF, o);
            float tx = x + h * 2 + 31;
            R.text(Ui.trim(sd.serverName, FAV_W - 40), tx, ry + 5, R.alpha(Ui.text, o));
            String line;
            int lc;
            if (sd.pingToServer == -2L) {
                line = "確認中…";
                lc = Ui.MUTED;
            } else if (sd.pingToServer < 0) {
                line = "オフライン";
                lc = 0xFFFF7A7A;
            } else {
                line = statusLine(i, sd);
                lc = sd.pingToServer < 100 ? 0xFF55FF55 : sd.pingToServer < 200 ? 0xFFC6F35A : 0xFFFFAA00;
            }
            R.text(Ui.trim(line, FAV_W - 40), tx, ry + 16, R.alpha(lc, o));
        }
    }

    /** Hover animation per favourite row. */
    private Anim[] favAnims = new Anim[0];
    /** "12,345 / 100,000  42ms" per row, rebuilt only when the ping result changes. */
    private String[] favLine = new String[0], favLinePop = new String[0];
    private long[] favLinePing = new long[0];

    private String statusLine(int i, net.minecraft.client.multiplayer.ServerData sd) {
        if (favLine.length <= i) {
            int n = Math.max(i + 1, favs.size());
            favLine = java.util.Arrays.copyOf(favLine, n);
            favLinePop = java.util.Arrays.copyOf(favLinePop, n);
            favLinePing = java.util.Arrays.copyOf(favLinePing, n);
        }
        if (favLine[i] == null || favLinePing[i] != sd.pingToServer || !Objects.equals(favLinePop[i], sd.populationInfo)) {
            String pop = net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(sd.populationInfo);
            favLine[i] = (pop == null || pop.isEmpty() ? "" : pop.replace("/", " / ") + "  ") + sd.pingToServer + "ms";
            favLinePing[i] = sd.pingToServer;
            favLinePop[i] = sd.populationInfo;
        }
        return favLine[i];
    }

    private boolean clickFavourite(int mx, int my) {
        for (int i = 0; i < favCount(); i++) {
            if (Ui.inside(mx, my, favX(), favRowY(i), FAV_W, FAV_H)) {

                net.minecraft.client.multiplayer.ServerData sd = favs.get(i);

                if (sd == null) {
                    return true;
                }

                mc.displayGuiScreen(
                        new net.minecraft.client.multiplayer.GuiConnecting(
                                new GuiMultiplayer(this),
                                mc,
                                sd
                        )
                );

                return true;
            }
        }
        return false;
    }

    public FzMainMenu() {
        intro = !introPlayed && Branding.on("splash");
        introPlayed = true;
    }

    private Anim anim(String key) {
        Anim a = hover.get(key);
        if (a == null) {
            a = new Anim(0f, 14f);
            hover.put(key, a);
        }
        return a;
    }

    // ---------------------------------------------------------------- layout

    private static final float BW = 264, BH = 24, GAP = 6, SBH = 22;

    private float colX() { return vw / 2f - BW / 2f; }
    private float logoY() { return vh * 0.11f; }
    private float buttonsY() { return Math.max(logoY() + 128, vh * 0.48f); }
    private float rowY(int i) { return buttonsY() + i * (BH + GAP); }
    private float smallY() { return rowY(2) + 4; }
    /** Two small buttons under the big ones, lined up with their edges. */
    private float smallW() { return (BW - GAP) / 2f; }
    private float smallX(int i) { return colX() + i * (smallW() + GAP); }
    private float quitX() { return vw - 30; }
    private float quitY() { return 10; }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void render(int mx, int my, float pt) {
        float t = (System.currentTimeMillis() - start) / 1000f;
        long now = System.nanoTime();
        if (intro) {
            if (introT < 0f) introT = 0f;
            else introT += Math.min(1f / 30f, (now - lastFrame) / 1e9f);
        }
        lastFrame = now;
        float it = intro ? introT : 99f;
        boolean inIntro = it < INTRO_LEN;
        // during the intro the buttons wait until the logo has started moving
        float o = open.update(it > 0.45f ? 1f : 0f);
        // smoothed mouse for a little parallax
        mxs += (mx - mxs) * 0.08f;
        mys += (my - mys) * 0.08f;

        drawBackground(t);

        // logo: the bird gently floats, the script title under it
        float cx = vw / 2f;
        float ly = logoY() + MathHelper.sin(t * 1.3f) * 2.5f + (intro ? 0f : (1f - o) * 12f);
        float bw = 92, bh = bw * 146f / 256f;
        if (!inIntro) image(BIRD, cx - bw / 2f, ly, bw, bh, 0xFFFFFF, intro ? 1f : o);
        float tw = 170, th = tw * 191f / 512f;
        image(TITLE, cx - tw / 2f + 1, ly + bh + 2 + 2, tw, th, 0x000000, o * 0.45f);   // shadow
        image(TITLE, cx - tw / 2f, ly + bh + 2, tw, th, 0xE6F7F8, o);
        String sub = "PVP CLIENT  ·  1.8.9";
        float sw = R.width(sub), sy = ly + bh + th + 2;
        R.textCentered(sub, cx, sy, R.alpha(Ui.accent, o));
        headLine(cx - sw / 2f - 46, cx - sw / 2f - 8, sy + 4, true, Ui.accent, o);
        headLine(cx + sw / 2f + 8, cx + sw / 2f + 46, sy + 4, false, Ui.accent, o);

        // main buttons
        bigButton("single", "menu_single", "シングルプレイ", rowY(0), true, mx, my, o);
        bigButton("multi", "menu_multi", "マルチプレイ", rowY(1), false, mx, my, o);
        smallButton("options", "menu_options", "設定", 0, mx, my, o);
        smallButton("mods", "menu_mods", "Mods", 1, mx, my, o);

        drawQuick(mx, my, o);
        drawFavourites(mx, my, o);

        // quit (top right)
        float qh = anim("quit").update(Ui.inside(mx, my, quitX(), quitY(), 20, 20) ? 1 : 0);
        R.rounded(quitX(), quitY(), 20, 20, 10f, R.alpha(R.lerpColor(0x90141D21, 0xD0E5484D, qh), o));
        Icons.draw("menu_quit", quitX() + 4, quitY() + 4, 12, R.alpha(0xFFFFFFFF, o));

        // language switch (left of quit)
        Ui.langButton(quitX() - 6 - Ui.langW(), quitY(), anim("lang").update(Ui.inside(mx, my, quitX() - 6 - Ui.langW(), quitY(), Ui.langW(), 20) ? 1 : 0), o);

        // footer: a dark strip with a thin light line on top
        R.rect(0, vh - 28, vw, 28, R.alpha(0x70000000, o));
        Ui.gradient(0, vh - 28, vw / 2f, 0.5f, 0x00FFFFFF, R.alpha(0x30FFFFFF, o), 0x00FFFFFF, R.alpha(0x30FFFFFF, o));
        Ui.gradient(vw / 2f, vh - 28, vw / 2f, 0.5f, R.alpha(0x30FFFFFF, o), 0x00FFFFFF, R.alpha(0x30FFFFFF, o), 0x00FFFFFF);
        String name = mc.getSession() == null ? "" : mc.getSession().getUsername();
        // face + name: opens the account switcher
        float ah = anim("account").update(Ui.inside(mx, my, accountX(), accountY(), accountW(name), 19) ? 1 : 0);
        if (ah > 0.01f) {
            R.rounded(accountX(), accountY(), accountW(name), 19, Ui.rad(5f), R.alpha(Ui.accentA((int) (0x30 * ah)), o));
            R.roundedOutline(accountX(), accountY(), accountW(name), 19, Ui.rad(5f), 1f, R.alpha(Ui.accentA((int) (0x90 * ah)), o));
        }
        drawFace(vh - 21, o);
        R.text(name, 28, vh - 17, R.alpha(R.lerpColor(0xFFC9D6D9, 0xFFFFFFFF, ah), o));
        if (ah > 0.01f) {
            String hint = dev.fzkc.fzhud.util.Lang.t("アカウントを切り替え");
            R.text(hint, accountX() + accountW(name) + 6, vh - 17, R.alpha(Ui.MUTED, o * ah));
        }
        if (Branding.on("brandline")) {
            String ver = "FzClient " + Branding.VERSION + "  ·  Minecraft 1.8.9";
            R.text(ver, vw - 10 - R.width(ver), vh - 17, R.alpha(0xFF6F8589, o));
        }

        if (inIntro) drawIntro(it, cx - bw / 2f, ly, bw, bh);
        else if (intro && it < 99f) SplashAnim.release();
    }

    /**
     * Continues the loading screen: the splash colour fades away, the title / subtitle / progress line fade
     * out, and the bird flies from its loading-screen spot to its place on the title screen.
     */
    private void drawIntro(float it, float bx, float by, float bw, float bh) {
        float side = Math.min(vw, vh), k = side / 512f, ox = (vw - side) / 2f, oy = (vh - side) / 2f;

        float bgA = 1f - SplashAnim.outCubic(SplashAnim.seg(it, 0.25f, 0.8f));
        if (bgA > 0.003f) R.rect(0, 0, vw, vh, R.alpha(0xFF0B1416, bgA));

        // the parts that leave: drift up a little while fading
        float out = SplashAnim.outCubic(SplashAnim.seg(it, 0f, 0.4f));
        part(SplashAnim.GLOW, ox, oy, k, -6f * out, 0.6f * (1f - out));
        part(SplashAnim.TEXT, ox, oy, k, -10f * out, 1f - out);
        part(SplashAnim.SUB, ox, oy, k, -8f * out, 1f - out);
        float barA = 1f - out;
        if (barA > 0.003f) {
            float x = ox + SplashAnim.BAR_X * k, y = oy + SplashAnim.BAR_Y * k - 6f * out;
            float w = SplashAnim.BAR_W * k, h = Math.max(0.5f, SplashAnim.BAR_H * k);
            R.rect(x, y, w, h, R.alpha(Ui.accent, barA * 0.9f));
        }

        // the bird: splash rectangle -> menu rectangle
        float[] r = SplashAnim.RECT[SplashAnim.BIRD];
        float m = SplashAnim.inOutCubic(SplashAnim.seg(it, 0.12f, 0.9f));
        float x0 = ox + r[0] * k, y0 = oy + r[1] * k, w0 = (r[2] - r[0]) * k, h0 = (r[3] - r[1]) * k;
        image(BIRD, x0 + (bx - x0) * m, y0 + (by - y0) * m, w0 + (bw - w0) * m, h0 + (bh - h0) * m, 0xFFFFFF, 1f);
    }

    private float accountX() { return 6; }
    private float accountY() { return vh - 24; }
    private float accountW(String name) { return 28 - 6 + R.width(name) + 6; }

    /** The session the profile below belongs to. */
    private static net.minecraft.util.Session profileOf;
    private static com.mojang.authlib.GameProfile sessionProfile;

    /** The player's head (face + hat layer) from their skin, with a thin frame. */
    private void drawFace(float y, float alpha) {
        // Session.getProfile() parses the UUID text and builds a new profile on every call: do it once per session
        net.minecraft.util.Session session = mc.getSession();
        if (session != profileOf) {
            profileOf = session;
            sessionProfile = null;
            skin = null;              // another account: load its skin
            skinRequested = false;
            try {
                sessionProfile = session.getProfile();
            } catch (Throwable ignored) {
            }
        }
        com.mojang.authlib.GameProfile profile = sessionProfile;
        if (!skinRequested && profile != null && profile.getId() != null) {
            skinRequested = true;
            try {
                mc.getSkinManager().loadProfileTextures(profile, (type, loc, texture) -> {
                    if (type == com.mojang.authlib.minecraft.MinecraftProfileTexture.Type.SKIN) skin = loc;
                }, false);
            } catch (Throwable ignored) {
            }
        }
        ResourceLocation tex = skin != null ? skin
                : profile != null && profile.getId() != null
                ? net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkin(profile.getId())
                : net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkinLegacy();

        R.rect((float) 10 - 1, y - 1, (float) 13 + 2, (float) 13 + 2, R.alpha(0x60000000, alpha));
        mc.getTextureManager().bindTexture(tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, alpha);
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) 10, y, 0);
        GlStateManager.scale((float) 13 / 8f, (float) 13 / 8f, 1f);
        Gui.drawScaledCustomSizeModalRect(0, 0, 8, 8, 8, 8, 8, 8, 64, 64);   // face
        Gui.drawScaledCustomSizeModalRect(0, 0, 40, 8, 8, 8, 8, 8, 64, 64);  // hat layer
        GlStateManager.popMatrix();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private void part(int i, float ox, float oy, float k, float dy, float alpha) {
        if (alpha <= 0.003f) return;
        float[] r = SplashAnim.RECT[i];
        image(SPLASH[i], ox + r[0] * k, oy + (r[1] + dy) * k, (r[2] - r[0]) * k, (r[3] - r[1]) * k, 0xFFFFFF, alpha);
    }

    /** The background: a slowly turning panorama (sepia), shaded so the lists and buttons stay readable. */
    private void drawBackground(float t) {
        drawPanorama(t);

        // shade: a little everywhere, more at the sides (the lists) and the bottom (the footer)
        R.rect(0, 0, vw, vh, 0x3C000000);
        Ui.gradient(0, 0, vw * 0.34f, vh, 0xB0000000, 0x00000000, 0xB0000000, 0x00000000);
        Ui.gradient(vw * 0.66f, 0, vw * 0.34f + 1, vh, 0x00000000, 0xB0000000, 0x00000000, 0xB0000000);
        Ui.gradient(0, 0, vw, vh * 0.22f, 0x80000000, 0x80000000, 0x00000000, 0x00000000);
        Ui.gradient(0, vh * 0.55f, vw, vh * 0.45f + 1, 0x00000000, 0x00000000, 0xD0000000, 0xD0000000);

        // warm light behind the logo
        blob(vw / 2f, logoY() + 52, vh * 0.42f, 0xFFC27A, 0x26);

        // dust in the light: rising slowly and twinkling (all in one draw call)
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glBegin(GL11.GL_QUADS);
        float span = vh + 20;
        int acc = Ui.accent & 0xFFFFFF;
        for (int i = 0; i < PARTICLES; i++) {
            float x = PX[i] * vw + MathHelper.sin(t * 0.4f + PP[i]) * 6f;
            float y = ((PY[i] * span - t * PV[i] * 0.6f) % span + span) % span - 10;
            float tw = 0.3f + 0.7f * (0.5f + 0.5f * MathHelper.sin(t * 2.2f + PP[i] * 3f));
            int col = i % 4 == 0 ? acc : i % 4 == 1 ? 0xFFFFFF : 0xFFE2B4;
            GL11.glColor4f(((col >> 16) & 0xFF) / 255f, ((col >> 8) & 0xFF) / 255f, (col & 0xFF) / 255f, tw * 0.55f);
            float s = PS[i] * 0.8f;
            GL11.glVertex2f(x, y);
            GL11.glVertex2f(x, y + s);
            GL11.glVertex2f(x + s, y + s);
            GL11.glVertex2f(x + s, y);
        }
        GL11.glEnd();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.resetColor();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static final ResourceLocation[] PANO = new ResourceLocation[6];

    static {
        for (int i = 0; i < 6; i++) PANO[i] = new ResourceLocation("fzclient", "textures/panorama/panorama_" + i + ".jpg");
    }

    /**
     * The six pictures on the inside of a cube around the camera (the usual title-screen panorama layout:
     * 0 front, 1 right, 2 back, 3 left, 4 up, 5 down), turning slowly, with a touch of mouse parallax.
     */
    private void drawPanorama(float t) {
        float yaw = t * 1.8f + (mxs / Math.max(1, vw) - 0.5f) * 8f;
        float pitch = 8f + MathHelper.sin(t * 0.07f) * 3f + (mys / Math.max(1, vh) - 0.5f) * 4f;
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        org.lwjgl.util.glu.Project.gluPerspective(85f, mc.displayWidth / (float) Math.max(1, mc.displayHeight), 0.05f, 10f);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.rotate(180f, 1f, 0f, 0f);
        GlStateManager.rotate(pitch, 1f, 0f, 0f);
        GlStateManager.rotate(yaw, 0f, 1f, 0f);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
        for (int k = 0; k < 6; k++) {
            mc.getTextureManager().bindTexture(PANO[k]);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            // no seams where two pictures meet
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            float[] f = FACES[k];
            GL11.glBegin(GL11.GL_QUADS);
            for (int v = 0; v < 4; v++) {
                GL11.glTexCoord2f(UV[v * 2], UV[v * 2 + 1]);
                GL11.glVertex3f(f[v * 3], f[v * 3 + 1], f[v * 3 + 2]);
            }
            GL11.glEnd();
        }
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
    }

    /** Texture corners for the four points of every face. */
    private static final float[] UV = {0, 0, 0, 1, 1, 1, 1, 0};
    /** The four points of each face (x, y, z). */
    private static final float[][] FACES = {
            {-1, -1, 1, -1, 1, 1, 1, 1, 1, 1, -1, 1},
            {1, -1, 1, 1, 1, 1, 1, 1, -1, 1, -1, -1},
            {1, -1, -1, 1, 1, -1, -1, 1, -1, -1, -1, -1},
            {-1, -1, -1, -1, 1, -1, -1, 1, 1, -1, -1, 1},
            {-1, -1, -1, -1, -1, 1, 1, -1, 1, 1, -1, -1},
            {-1, 1, 1, -1, 1, -1, 1, 1, -1, 1, 1, 1},
    };

    /** The 41 points around a blob (same values the drawing used to compute every frame). */
    private static final float[] CIRCLE_COS = new float[41], CIRCLE_SIN = new float[41];

    static {
        for (int i = 0; i <= 40; i++) {
            double a = i * Math.PI * 2 / 40;
            CIRCLE_COS[i] = (float) Math.cos(a);
            CIRCLE_SIN[i] = (float) Math.sin(a);
        }
    }

    /** Radial blob: colour in the middle fading out to transparent. */
    private static void blob(float cx, float cy, float radius, int rgb, int alpha) {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(770, 1, 1, 0); // additive: blobs glow where they overlap
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        float r = ((rgb >> 16) & 0xFF) / 255f, g = ((rgb >> 8) & 0xFF) / 255f, b = (rgb & 0xFF) / 255f;
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glColor4f(r, g, b, alpha / 255f);
        GL11.glVertex2f(cx, cy);
        GL11.glColor4f(r, g, b, 0f);
        for (int i = 0; i <= 40; i++) {
            GL11.glVertex2f(cx + CIRCLE_COS[i] * radius, cy + CIRCLE_SIN[i] * radius);
        }
        GL11.glEnd();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableCull();
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.resetColor();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private void image(ResourceLocation tex, float x, float y, float w, float h, int rgb, float alpha) {
        mc.getTextureManager().bindTexture(tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, alpha);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(w / 256f, h / 256f, 1f);
        Gui.drawModalRectWithCustomSizedTexture(0, 0, 0, 0, 256, 256, 256, 256);
        GlStateManager.popMatrix();
        GlStateManager.enableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private void bigButton(String id, String icon, String label, float y, boolean primary, int mx, int my, float o) {
        float x = colX();
        boolean over = Ui.inside(mx, my, x, y, BW, BH);
        float h = anim(id).update(over ? 1 : 0);
        float lift = h * 1.5f;
        if (glass()) {
            // primary = the cyan-tinted piece of glass, like the primary buttons in the FzClient menus
            Glass.surface(x, y - lift, BW, BH, Ui.rad(Math.min(BH / 2f, 9f)), o, primary ? 0.5f + h * 0.5f : h,
                    primary ? 0.85f + h * 0.15f : h * 0.22f, 1f);
            Icons.draw(icon, x + 10, y - lift + 6, 12, R.alpha(primary ? 0xFFFFFFFF : R.lerpColor(0xFFC9D6D9, Ui.accent, h), o));
            R.textCentered(label, x + BW / 2f, y - lift + 8, R.alpha(Ui.text, o));
        } else if (primary) {
            int base = Ui.accent | 0xFF000000;
            if (h > 0.01f) blob(x + BW / 2f, y - lift + BH / 2f, BW * 0.42f, base & 0xFFFFFF, (int) (0x30 * h));   // glow
            R.rounded(x + 1, y - lift + 2, BW, BH, 4f, R.alpha(0x60000000, o));
            R.rounded(x, y - lift, BW, BH, 4f, R.alpha(R.lerpColor(R.lerpColor(base, 0xFF000000, 0.12f), R.lerpColor(base, 0xFFFFFFFF, 0.2f), h), o));
            // a lighter top half: the button looks lit from above
            R.rounded(x + 1, y - lift + 1, BW - 2, BH / 2f - 1, 3f, R.alpha(0x22FFFFFF, o));
            R.roundedOutline(x, y - lift, BW, BH, 4f, 1f, R.alpha(R.lerpColor(0x50FFFFFF, 0xFFFFFFFF, h), o));
            Icons.draw(icon, x + 10, y - lift + 6, 12, R.alpha(0xFF071012, o));
            boolean shadow = R.noShadow;
            R.noShadow = true;   // dark text on the light button: a shadow would look doubled
            R.textCentered(label, x + BW / 2f, y - lift + 8, R.alpha(0xFF071012, o));
            R.noShadow = shadow;
        } else {
            R.rounded(x + 1, y - lift + 2, BW, BH, 4f, R.alpha(0x50000000, o));
            R.rounded(x, y - lift, BW, BH, 4f, R.alpha(R.lerpColor(0xA80A0F12, 0xDC121C21, h), o));
            R.rect(x + 6, y - lift + 0.5f, BW - 12, 0.5f, R.alpha(0x26FFFFFF, o));
            R.roundedOutline(x, y - lift, BW, BH, 4f, 1f, R.alpha(R.lerpColor(0x30FFFFFF, Ui.accentA(0xB0), h), o));
            // on hover a line in the accent colour grows from the middle of the bottom edge
            if (h > 0.01f) {
                float lw = BW * 0.55f * h;
                R.rect(x + BW / 2f - lw / 2f, y - lift + BH - 1.5f, lw, 1f, R.alpha(Ui.accent, o * h));
            }
            Icons.draw(icon, x + 10, y - lift + 6, 12, R.alpha(R.lerpColor(0xFF9DB4B8, Ui.accent, h), o));
            R.textCentered(label, x + BW / 2f, y - lift + 8, R.alpha(Ui.text, o));
        }
    }

    private void smallButton(String id, String icon, String label, int i, int mx, int my, float o) {
        float x = smallX(i), y = smallY(), w = smallW();
        boolean over = Ui.inside(mx, my, x, y, w, SBH);
        float h = anim(id).update(over ? 1 : 0);
        if (glass()) Glass.surface(x, y, w, SBH, Ui.rad(Math.min(SBH / 2f, 9f)), o, h, h * 0.22f, 1f);
        else {
            R.rounded(x + 1, y + 2, w, SBH, 4f, R.alpha(0x50000000, o));
            R.rounded(x, y, w, SBH, 4f, R.alpha(R.lerpColor(0xA80A0F12, 0xDC121C21, h), o));
            R.rect(x + 5, y + 0.5f, w - 10, 0.5f, R.alpha(0x26FFFFFF, o));
            R.roundedOutline(x, y, w, SBH, 4f, 1f, R.alpha(R.lerpColor(0x22FFFFFF, Ui.accentA(0xB0), h), o));
            if (h > 0.01f) {
                float lw = w * 0.5f * h;
                R.rect(x + w / 2f - lw / 2f, y + SBH - 1.5f, lw, 1f, R.alpha(Ui.accent, o * h));
            }
        }
        float tw = 11 + 4 + R.width(label);
        float sx = x + (w - tw) / 2f;
        Icons.draw(icon, sx, y + 5.5f, 11, R.alpha(R.lerpColor(0xFF9DB4B8, Ui.accent, h), o));
        R.text(label, sx + 15, y + 7, R.alpha(Ui.text, o));
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void click(int mx, int my, int button) {
        if (button != 0) return;
        if (clickFavourite(mx, my)) return;
        if (Ui.inside(mx, my, colX(), rowY(0), BW, BH)) {
            mc.displayGuiScreen(new GuiSelectWorld(this));
        } else if (Ui.inside(mx, my, colX(), rowY(1), BW, BH)) {
            mc.displayGuiScreen(new GuiMultiplayer(this));
        } else if (Ui.inside(mx, my, quickX() - 2, quickY(0), FAV_W + 2, FAV_H)) {
            mc.displayGuiScreen(new ModsScreen(this));
        } else if (Ui.inside(mx, my, quickX() - 2, quickY(1), FAV_W + 2, FAV_H)) {
            mc.displayGuiScreen(new CosmeticsScreen(this));
        } else if (clickGallery(mx, my)) {
            return;
        } else if (Ui.inside(mx, my, smallX(0), smallY(), smallW(), SBH)) {
            mc.displayGuiScreen(new GuiOptions(this, mc.gameSettings));
        } else if (Ui.inside(mx, my, smallX(1), smallY(), smallW(), SBH)) {
            mc.displayGuiScreen(new GuiModList(this));
        } else if (Ui.inside(mx, my, quitX() - 6 - Ui.langW(), quitY(), Ui.langW(), 20)) {
            dev.fzkc.fzhud.util.Lang.toggle();
        } else if (Ui.inside(mx, my, quitX(), quitY(), 20, 20)) {
            mc.shutdown();
        } else if (mc.getSession() != null && Ui.inside(mx, my, accountX(), accountY(), accountW(mc.getSession().getUsername()), 19)) {
            mc.displayGuiScreen(new AccountScreen(this));
        }
    }

    @Override
    protected void key(char c, int keyCode) {
        // Esc does nothing on the title screen
    }
}
