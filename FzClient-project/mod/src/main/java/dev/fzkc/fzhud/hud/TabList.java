package dev.fzkc.fzhud.hud;

import com.google.common.collect.ComparisonChain;
import dev.fzkc.fzhud.FzHud;
import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.SliderSetting;
import dev.fzkc.fzhud.config.TextSetting;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.WorldSettings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Compact tab list: player heads, names and numeric ping, in a themed panel. */
public class TabList extends HudElement {
    private static final float PAD = 5f, ROW = 10f, HEAD = 8f, COL_GAP = 8f;
    /** FzClient bird shown next to your own name (128x71, transparent). */
    private static final net.minecraft.util.ResourceLocation ICON =
            new net.minecraft.util.ResourceLocation("fzclient", "textures/tab_icon.png");
    private static final float ICON_H = 6f, ICON_W = ICON_H * 128f / 71f, ICON_GAP = 2f;
    private static final int MAX_PLAYERS = 80;
    private static Field headerField, footerField;
    private float lastW = 120f, lastH = 40f;

    private final BoolSetting heads, fzIcon, pingNumbers, stripes, showHeader, showFooter, blockAds;
    public final BoolSetting toggleMode;
    private final TextSetting customHeader, customFooter, blockWords;
    private final SliderSetting rowsPerColumn, nameGapSetting, statGapSetting;

    public TabList(float defX, float defY) {
        super("tab", "Tab List", "Tabキーのプレイヤー一覧", defX, defY, LEFT);
        withBackground();
        heads = add(new BoolSetting("heads", "頭のアイコンを表示", true));
        fzIcon = add(new BoolSetting("fzicon", "FzClient ユーザー（自分・ほかの人）の名前の横にアイコンを表示", true));
        pingNumbers = add(new BoolSetting("ping", "Pingを数字で表示", true));
        stripes = add(new BoolSetting("stripes", "行を縞模様にする", true));
        rowsPerColumn = add(new SliderSetting("rows", "1列の行数", 10, 40, 1, 20, ""));
        nameGapSetting = add(new SliderSetting("namegap", "名前と統計の間（統計がある時）", 0, 20, 1, 3, ""));
        statGapSetting = add(new SliderSetting("statgap", "統計どうしの間", 0, 20, 1, 4, ""));
        toggleMode = add(new BoolSetting("toggle", "Tabキーで開閉（押しっぱなし不要・アニメなし）", false));
        showHeader = add(new BoolSetting("header", "サーバーのヘッダーを表示", true));
        showFooter = add(new BoolSetting("footer", "サーバーのフッターを表示", true));
        customHeader = add(new TextSetting("customheader", "追加ヘッダー（| で改行、& で色）", "", 120));
        customFooter = add(new TextSetting("customfooter", "追加フッター（| で改行、& で色）", "", 120));
        blockAds = add(new BoolSetting("blockads", "広告の行を隠す", true));
        blockWords = add(new TextSetting("blockwords", "隠す言葉（; で区切る）", "hypixel.net;Ranks, Boosters", 160));
    }

    @Override public float width() { return lastW; }
    @Override public float height() { return lastH; }

    @Override
    public boolean active() {
        return FzHud.tabActive;
    }

    private static final Comparator<NetworkPlayerInfo> ORDER = (a, b) -> {
        ScorePlayerTeam ta = a.getPlayerTeam(), tb = b.getPlayerTeam();
        return ComparisonChain.start()
                .compareTrueFirst(a.getGameType() != WorldSettings.GameType.SPECTATOR,
                        b.getGameType() != WorldSettings.GameType.SPECTATOR)
                .compare(ta != null ? ta.getRegisteredName() : "", tb != null ? tb.getRegisteredName() : "")
                .compare(a.getGameProfile().getName(), b.getGameProfile().getName())
                .result();
    };

    private static void findFields() {
        if (headerField != null || footerField != null) return;
        headerField = field("header", "field_175256_i");
        footerField = field("footer", "field_175255_h");
        if (headerField == null || footerField == null) {
            // fall back to declaration order: footer, then header
            List<Field> comps = new ArrayList<>();
            for (Field f : GuiPlayerTabOverlay.class.getDeclaredFields()) {
                if (f.getType() == IChatComponent.class) {
                    f.setAccessible(true);
                    comps.add(f);
                }
            }
            if (comps.size() >= 2) {
                footerField = comps.get(0);
                headerField = comps.get(1);
            }
        }
    }

    private static Field field(String... names) {
        for (String n : names) {
            try {
                Field f = GuiPlayerTabOverlay.class.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static List<String> text(Field f, GuiPlayerTabOverlay tab) {
        List<String> out = new ArrayList<>();
        if (f == null || tab == null) return out;
        try {
            Object o = f.get(tab);
            if (o instanceof IChatComponent) {
                String s = ((IChatComponent) o).getFormattedText();
                for (String line : s.split("\n")) {
                    String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line);
                    if (plain != null && !plain.trim().isEmpty()) out.add(line);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Drops lines containing any of the blocked words (colour codes ignored, case-insensitive). */
    private List<String> filter(List<String> lines) {
        if (!blockAds.get()) return lines;
        String[] words = blockWords.get().toLowerCase().split(";");
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line);
            String low = plain == null ? line.toLowerCase() : plain.toLowerCase();
            boolean blocked = false;
            for (String w : words) {
                String t = w.trim();
                if (!t.isEmpty() && low.contains(t)) {
                    blocked = true;
                    break;
                }
            }
            if (!blocked) out.add(line);
        }
        return out;
    }

    private static List<String> custom(TextSetting t) {
        List<String> out = new ArrayList<>();
        if (t.get().trim().isEmpty()) return out;
        Collections.addAll(out, t.formatted().split("\\|"));
        return out;
    }

    /** Built header/footer lines, rebuilt only when their inputs change (not every frame). */
    private static final class Lines {
        Object src;
        boolean show, block;
        String words, custom;
        List<String> out = Collections.emptyList();
    }

    private final Lines headerCache = new Lines(), footerCache = new Lines();

    private List<String> lines(Lines c, Field f, GuiPlayerTabOverlay tab, boolean show, TextSetting cust) {
        Object src = null;
        if (show && f != null && tab != null) {
            try {
                src = f.get(tab);
            } catch (Exception ignored) {
            }
        }
        boolean block = blockAds.get();
        String words = blockWords.get(), custom = cust.get();
        if (src != c.src || show != c.show || block != c.block || !words.equals(c.words) || !custom.equals(c.custom)) {
            c.src = src;
            c.show = show;
            c.block = block;
            c.words = words;
            c.custom = custom;
            List<String> out = show ? filter(text(f, tab)) : new ArrayList<>();
            out.addAll(custom(cust));
            c.out = out;
        }
        return c.out;
    }

    // ---- players, names and pings: sorted and measured at most once per tick (the list only changes when
    //      packets arrive), not every frame while the tab list is open
    private final List<NetworkPlayerInfo> players = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private int[] pingMs = new int[0];
    private String[] pingText = new String[0];
    private boolean[] isMe = new boolean[0];
    private float nameW, pingW;
    private int builtTick = Integer.MIN_VALUE, builtWidthGen = Integer.MIN_VALUE;
    private boolean builtIcon, builtPing, builtEnglish;

    // ---- stats columns (Player Stats): column titles, one cell per player and column, column widths
    private float statGap() { return (float) statGapSetting.get(); }
    private float nameGap() { return (float) nameGapSetting.get(); }
    private boolean statsOn;
    private String[] statHeads = new String[0];
    private String[][] statCells = new String[0][];
    private float[] statW = new float[0];
    private float statsW;
    // tag badges (after the stats) and health (before the ping), one per player
    private String[] tagText = new String[0], hpText = new String[0];
    private float tagW, hpW;

    /** Total width of the stats columns (0 when they are off). */
    private float statsWidth() { return statsOn ? statsW : 0f; }

    /** Style of the stats tab list: 0 normal, 1 shaded, 2 clean (column titles in 1 and 2). */
    private int statsStyle() { return statsOn ? dev.fzkc.fzhud.Modules.statsTabStyle.get() : 0; }

    /**
     * The name shown for one player when stats are on: tag badges and the Bed Wars star in front, and in a game
     * the rank colour instead of the team colour (the team letter in front stays as it is).
     */
    private static String statsName(NetworkPlayerInfo info, String shown, dev.fzkc.fzhud.stats.PlayerData d,
                                     java.util.List<dev.fzkc.fzhud.stats.Tags.Tag> tags, dev.fzkc.fzhud.stats.Game g) {
        StringBuilder sb = new StringBuilder();
        if (d != null && !d.nicked) {
            String star = g.tabPrefix(d.of(g));
            if (!star.isEmpty()) sb.append(star).append(' ');
            ScorePlayerTeam team = info.getPlayerTeam();
            if (!dev.fzkc.fzhud.stats.Stats.lobby && dev.fzkc.fzhud.Modules.statsRankColors.get()
                    && info.getDisplayName() == null && team != null) {
                shown = team.getColorPrefix() + d.rankColor + info.getGameProfile().getName() + team.getColorSuffix();
            }
        }
        return sb.append(shown).toString();
    }

    private void buildStats(int n) {
        dev.fzkc.fzhud.stats.Game g = dev.fzkc.fzhud.stats.Stats.current;
        statHeads = g.tabHeadsPublic();
        int cols = statHeads.length;
        if (statCells.length < n || (statCells.length > 0 && statCells[0].length != cols)) statCells = new String[Math.max(n, 1)][cols];
        statW = new float[cols];
        for (int c = 0; c < cols; c++) statW[c] = R.width(statHeads[c]);
        boolean tagsOn = dev.fzkc.fzhud.Modules.statsTagsInTab.get();
        tagW = 0;
        for (int i = 0; i < n; i++) {
            NetworkPlayerInfo info = players.get(i);
            java.util.UUID id = info.getGameProfile().getId();
            String[] row = statCells[i];
            tagText[i] = "";
            java.util.Arrays.fill(row, "");
            if (id == null) continue;
            String realSuffix = "";
            if (id.version() == 1) {
                // a Hypixel nick: the real player's stats once denicked, "NICK" until then
                dev.fzkc.fzhud.stats.Denick.Real real = dev.fzkc.fzhud.stats.Denick.real(info.getGameProfile().getName());
                if (real == null || real.id == null) {
                    names.set(i, names.get(i) + (real == null ? "" : " §8(§a" + real.name + "§8)"));
                    if (cols > 0) row[0] = "§c§lNICK";
                    for (int c = 0; c < cols; c++) statW[c] = Math.max(statW[c], R.width(row[c]));
                    continue;
                }
                realSuffix = " §8(§a" + real.name + "§8)";
                id = real.id;
            } else if (id.version() != 4) {
                continue;   // NPCs: nothing to look up
            }
            dev.fzkc.fzhud.stats.PlayerData d = dev.fzkc.fzhud.stats.StatsApi.get(id, info.getGameProfile().getName());
            java.util.List<dev.fzkc.fzhud.stats.Tags.Tag> tags = tagsOn
                    ? dev.fzkc.fzhud.stats.Tags.get(id) : java.util.Collections.<dev.fzkc.fzhud.stats.Tags.Tag>emptyList();
            names.set(i, statsName(info, names.get(i), d, tags, g) + realSuffix);
            if (!tags.isEmpty()) {
                StringBuilder tb = new StringBuilder();
                for (dev.fzkc.fzhud.stats.Tags.Tag t : tags) tb.append(t.badge);
                tagText[i] = tb.toString();
                tagW = Math.max(tagW, R.width(tagText[i]));
            }
            if (d == null) {
                if (cols > 0) row[0] = "§8…";
            } else if (d.nicked) {
                if (cols > 0) row[0] = "§c§lNICK";
            } else {
                java.util.Map<String, Long> c = d.of(g);
                if (c.isEmpty()) {
                    if (cols > 0) row[0] = "§8-";
                } else {
                    String[] v = g.tabPublic(c);
                    System.arraycopy(v, 0, row, 0, Math.min(cols, v.length));
                }
            }
            for (int c = 0; c < cols; c++) statW[c] = Math.max(statW[c], R.width(row[c]));
        }
        float w = 0;
        for (float x : statW) w += x + statGap();
        statsW = w;
    }

    private void build(Minecraft mc, GuiPlayerTabOverlay overlay) {
        boolean icon = fzIcon.get(), ping = pingNumbers.get(), english = dev.fzkc.fzhud.util.Lang.english();
        boolean stats = dev.fzkc.fzhud.stats.Stats.tabActive();
        if (builtTick == dev.fzkc.fzhud.util.Stamp.ticks && builtIcon == icon && builtPing == ping
                && builtEnglish == english && builtWidthGen == R.widthGen && statsOn == stats) {
            return;
        }
        statsOn = stats;
        players.clear();
        players.addAll(mc.thePlayer.sendQueue.getPlayerInfoMap());
        players.sort(ORDER);
        while (players.size() > MAX_PLAYERS) players.remove(players.size() - 1);

        int n = players.size();
        if (pingMs.length < n) {
            pingMs = new int[n];
            pingText = new String[n];
            isMe = new boolean[n];
            tagText = new String[n];
            hpText = new String[n];
        }
        tagW = 0;
        float hw = 0;
        net.minecraft.scoreboard.Scoreboard board = mc.theWorld == null ? null : mc.theWorld.getScoreboard();
        net.minecraft.scoreboard.ScoreObjective hpObj = board == null ? null : board.getObjectiveInDisplaySlot(0);
        int myPing = dev.fzkc.fzhud.util.PingMeter.get();
        names.clear();
        float nw = 0, pw = 0;
        java.util.UUID me = mc.thePlayer.getUniqueID();
        for (int i = 0; i < n; i++) {
            NetworkPlayerInfo info = players.get(i);
            String s = overlay.getPlayerName(info);
            if (info.getGameType() == WorldSettings.GameType.SPECTATOR) s = EnumChatFormatting.ITALIC + s;
            names.add(s);
            isMe[i] = me.equals(info.getGameProfile().getId()) || dev.fzkc.fzhud.integration.FzUsers.uses(info.getGameProfile().getId());
            float iw = icon && isMe[i] ? ICON_W + ICON_GAP : 0f;
            nw = Math.max(nw, iw + R.width(trimEnd(s)));
            tagText[i] = "";
            // Hypixel sends 1 ms for everyone now: your own ping is measured by FzClient, other players' comes
            // from Aurora's ping history (their recent daily average)
            java.util.UUID pid = info.getGameProfile().getId();
            boolean self = me.equals(pid);
            int real = info.getResponseTime();
            if (self && myPing >= 0) pingMs[i] = myPing;
            else if (real > 1) pingMs[i] = real;
            else pingMs[i] = ping && !self ? dev.fzkc.fzhud.stats.PingHistory.get(pid) : -1;
            pingText[i] = pingMs[i] <= 1 ? "" : dev.fzkc.fzhud.util.Fmt.num(pingMs[i]);
            if (ping) pw = Math.max(pw, R.width(pingText[i]) * 0.5f);
            hpText[i] = health(mc, board, hpObj, info);
            hw = Math.max(hw, R.width(hpText[i]));
        }
        if (statsOn) {
            buildStats(n);
            nw = 0;
            for (int i = 0; i < n; i++) {
                float iw = icon && isMe[i] ? ICON_W + ICON_GAP : 0f;
                nw = Math.max(nw, iw + R.width(trimEnd(names.get(i))));
            }
        }
        hpW = hw;
        nameW = nw;
        pingW = ping ? pw : 0;
        builtTick = dev.fzkc.fzhud.util.Stamp.ticks;
        builtWidthGen = R.widthGen;
        builtIcon = icon;
        builtPing = ping;
        builtEnglish = english;
    }

    private static String trimEnd(String s) {
        int e = s.length();
        while (e > 0 && s.charAt(e - 1) == ' ') e--;
        return s.substring(0, e);
    }

    /** Health from the tab list score (Bed Wars and others), else from the player in the world during a game. */
    private static String health(Minecraft mc, net.minecraft.scoreboard.Scoreboard board,
                                 net.minecraft.scoreboard.ScoreObjective obj, NetworkPlayerInfo info) {
        String name = info.getGameProfile().getName();
        int hp = -1;
        if (obj != null && board != null && board.entityHasObjective(name, obj)) {
            hp = board.getValueFromObjective(name, obj).getScorePoints();
        } else if (mc.theWorld != null && !dev.fzkc.fzhud.stats.Stats.lobby
                && info.getGameType() != WorldSettings.GameType.SPECTATOR) {
            net.minecraft.entity.player.EntityPlayer p = mc.theWorld.getPlayerEntityByName(name);
            if (p != null && !p.isDead) hp = (int) Math.ceil(p.getHealth());
        }
        if (hp < 0) return "";
        String c = hp >= 15 ? "§a" : hp >= 8 ? "§e" : "§c";
        return c + hp;
    }

    private static int pingColor(int ms) {
        if (ms < 0) return R.MUTED;
        if (ms < 100) return 0xFF55FF55;
        if (ms < 200) return 0xFFC6F35A;
        if (ms < 300) return 0xFFFFAA00;
        return 0xFFFF5555;
    }

    @Override
    public void draw(float alpha, boolean preview) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.thePlayer.sendQueue == null) return;
        findFields();
        GuiPlayerTabOverlay overlay = mc.ingameGUI.getTabList();

        build(mc, overlay);
        List<NetworkPlayerInfo> players = this.players;

        List<String> header = lines(headerCache, headerField, overlay, showHeader.get(), customHeader);
        List<String> footer = lines(footerCache, footerField, overlay, showFooter.get(), customFooter);
        if (preview && header.isEmpty() && footer.isEmpty()) {
            if (showHeader.get()) header = Arrays.asList("§bYou are playing on §e§lMC.HYPIXEL.NET", "§7Lobby");
            if (showFooter.get()) footer = Arrays.asList("§aRanks, Boosters & MORE!", "§c§lSTORE.HYPIXEL.NET");
        }

        int n = players.size();
        int maxRows = (int) rowsPerColumn.get();
        int cols = Math.max(1, (n + maxRows - 1) / maxRows);
        int rows = n == 0 ? 0 : (n + cols - 1) / cols;

        List<String> names = this.names;
        float nameW = this.nameW, pingW = this.pingW;
        float headW = heads.get() ? HEAD + 3 : 0;
        float statsW = statsWidth();
        int style = statsStyle();
        float titleRow = style != 0 && n > 0 ? ROW + 2 : 0;   // column titles above the players
        float tagW = statsW > 0 ? this.tagW : 0, hpW = this.hpW;
        boolean pingOn = pingNumbers.get() && pingW > 0;
        float colW = headW + nameW + (statsW > 0 ? nameGap() + statsW - statGap() : 0) + (tagW > 0 ? nameGap() + tagW : 0)
                + (hpW > 0 ? nameGap() + hpW : 0) + (pingOn ? 4 + pingW : 0);
        float bodyW = cols * colW + (cols - 1) * COL_GAP;
        float w = bodyW;
        for (String s : header) w = Math.max(w, R.width(s));
        for (String s : footer) w = Math.max(w, R.width(s));
        w += PAD * 2;
        float h = PAD + header.size() * ROW + (header.isEmpty() ? 0 : 3) + titleRow + rows * ROW
                + (footer.isEmpty() ? 0 : 3) + footer.size() * ROW + PAD - 1;
        lastW = w;
        lastH = h;

        bg(0, 0, w, h, alpha);
        float y = PAD;
        for (String s : header) {
            R.textCentered(s, w / 2f, y, R.alpha(0xFFFFFFFF, alpha));
            y += ROW;
        }
        if (!header.isEmpty()) y += 3;

        float bodyX = (w - bodyW) / 2f;
        // where the stats columns start inside a column of players
        float statX = headW + nameW + nameGap();
        // health: right aligned, just before the ping
        float hpRight = colW - (pingOn ? 4 + pingW : 0);
        if (titleRow > 0) {
            // column titles; shaded: on a darker band, clean: with a thin line under them
            R.batchBegin();
            for (int col = 0; col < cols; col++) {
                float x = bodyX + col * (colW + COL_GAP);
                if (style == 1) R.batchRect(x - 2, y - 1, colW + 4, ROW, R.alpha(0x30000000, alpha));
                else R.batchRect(x - 2, y + ROW, colW + 4, 0.5f, R.alpha(0x40FFFFFF, alpha));
            }
            R.batchEnd();
            for (int col = 0; col < cols; col++) {
                float x = bodyX + col * (colW + COL_GAP);
                R.text("§7" + dev.fzkc.fzhud.util.Lang.t("名前"), x + headW, y, R.alpha(0xFFFFFFFF, alpha));
                float sx = x + statX;
                for (int c = 0; c < statHeads.length; c++) {
                    R.textCentered("§7" + statHeads[c], sx + statW[c] / 2f, y, R.alpha(0xFFFFFFFF, alpha));
                    sx += statW[c] + statGap();
                }
                if (hpW > 0) R.textCentered("§7HP", x + hpRight - hpW / 2f, y, R.alpha(0xFFFFFFFF, alpha));
            }
            y += titleRow;
        }
        // row stripes all in one draw (a stripe never overlaps another row's head or text); the clean style has none
        boolean striped = style == 1 || (style == 0 && stripes.get());
        if (striped) {
            R.batchBegin();
            for (int i = 0; i < n; i++) {
                int col = i / rows, row = i % rows;
                if (row % 2 != (style == 1 ? 1 : 0)) continue;
                float x = bodyX + col * (colW + COL_GAP);
                R.batchRect(x - 2, y + row * ROW - 1, colW + 4, ROW,
                        R.alpha(style == 1 ? 0x22000000 : 0x14FFFFFF, alpha));
            }
            R.batchEnd();
        }
        for (int i = 0; i < n; i++) {
            int col = i / rows, row = i % rows;
            float x = bodyX + col * (colW + COL_GAP);
            float ry = y + row * ROW;
            NetworkPlayerInfo info = players.get(i);

            if (heads.get() && info.getLocationSkin() != null) {
                mc.getTextureManager().bindTexture(info.getLocationSkin());
                GlStateManager.enableBlend();
                GlStateManager.color(1f, 1f, 1f, alpha);
                Gui.drawScaledCustomSizeModalRect((int) x, (int) ry, 8, 8, 8, 8, 8, 8, 64f, 64f);
                Gui.drawScaledCustomSizeModalRect((int) x, (int) ry, 40, 8, 8, 8, 8, 8, 64f, 64f);
                GlStateManager.color(1f, 1f, 1f, 1f);
            }
            float nx = x + headW;
            if (fzIcon.get() && isMe[i]) {
                // FzClient bird in front of your own name
                mc.getTextureManager().bindTexture(ICON);
                org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
                org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GlStateManager.color(1f, 1f, 1f, alpha);
                GlStateManager.pushMatrix();
                GlStateManager.translate(nx, ry + (8f - ICON_H) / 2f, 0);
                GlStateManager.scale(ICON_W / 128f, ICON_H / 71f, 1f);
                Gui.drawModalRectWithCustomSizedTexture(0, 0, 0, 0, 128, 71, 128, 71);
                GlStateManager.popMatrix();
                GlStateManager.color(1f, 1f, 1f, 1f);
                nx += ICON_W + ICON_GAP;
            }
            R.text(names.get(i), nx, ry, R.alpha(0xFFFFFFFF, alpha));
            if (statsW > 0 && i < statCells.length) {
                float sx = x + statX;
                String[] cells = statCells[i];
                for (int c = 0; c < statHeads.length && c < cells.length; c++) {
                    if (!cells[c].isEmpty()) R.textCentered(cells[c], sx + statW[c] / 2f, ry, R.alpha(0xFFFFFFFF, alpha));
                    sx += statW[c] + statGap();
                }
                if (tagW > 0 && i < tagText.length && !tagText[i].isEmpty()) {
                    R.text(tagText[i], sx - statGap() + nameGap(), ry, R.alpha(0xFFFFFFFF, alpha));
                }
            }
            if (hpW > 0 && !hpText[i].isEmpty()) {
                R.text(hpText[i], x + hpRight - R.width(hpText[i]), ry, R.alpha(0xFFFFFFFF, alpha));
            }
            if (pingOn && !pingText[i].isEmpty()) {
                int ms = pingMs[i];
                String ping = pingText[i];
                // half-size ping, right aligned and vertically centred in the row
                GlStateManager.pushMatrix();
                GlStateManager.translate(x + colW - R.width(ping) * 0.5f, ry + 2f, 0);
                GlStateManager.scale(0.5f, 0.5f, 1f);
                R.text(ping, 0, 0, R.alpha(pingColor(ms), alpha));
                GlStateManager.popMatrix();
            }

        }
        y += rows * ROW;

        if (!footer.isEmpty()) y += 3;
        for (String s : footer) {
            R.textCentered(s, w / 2f, y, R.alpha(0xFFFFFFFF, alpha));
            y += ROW;
        }
    }
}
