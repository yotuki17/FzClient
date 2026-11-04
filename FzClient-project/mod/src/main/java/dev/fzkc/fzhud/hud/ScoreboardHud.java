package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.EnumChatFormatting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Sidebar scoreboard in a themed panel: spaced title, no red score numbers. */
public class ScoreboardHud extends HudElement {
    private static final float PAD = 6f, LINE = 10f, TITLE_H = 16f;
    private float lastW = 100f, lastH = 60f;

    private final BoolSetting hideNumbers, spacedTitle, hideAds;

    public ScoreboardHud(float defX, float defY) {
        super("scoreboard", "Scoreboard", "右側のスコアボード", defX, defY, RIGHT);
        hideNumbers = add(new BoolSetting("hidenumbers", "赤い数字を隠す", true));
        spacedTitle = add(new BoolSetting("spaced", "タイトルの字間をあける", true));
        hideAds = add(new BoolSetting("hideads", "www.hypixel.net の行を隠す", true));
        withStyle();
    }

    @Override public float width() { return lastW; }
    @Override public float height() { return lastH; }

    @Override
    public boolean active() {
        return objective() != null;
    }

    private static ScoreObjective objective() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null) return null;
        Scoreboard sb = mc.theWorld.getScoreboard();
        ScoreObjective objective = null;
        ScorePlayerTeam team = sb.getPlayersTeam(mc.thePlayer.getName());
        if (team != null) {
            int slot = team.getChatFormat().getColorIndex();
            if (slot >= 0) objective = sb.getObjectiveInDisplaySlot(3 + slot);
        }
        return objective != null ? objective : sb.getObjectiveInDisplaySlot(1);
    }

    private final List<Integer> points = new ArrayList<>();

    private List<String> cached;
    private long cachedAt = Long.MIN_VALUE;
    private boolean cachedAds;

    /**
     * First entry is the title, the rest are lines top to bottom. Scores only change when packets are handled
     * (once per tick), so the sorted lines are built once per tick instead of every frame.
     */
    private List<String> lines() {
        Minecraft mc = Minecraft.getMinecraft();
        long now = mc.theWorld == null ? Long.MIN_VALUE : mc.theWorld.getTotalWorldTime();
        boolean ads = hideAds.get();
        if (now != Long.MIN_VALUE && now == cachedAt && ads == cachedAds) return cached;
        cachedAt = now;
        cachedAds = ads;
        cached = buildLines();
        return cached;
    }

    private List<String> buildLines() {
        points.clear();
        ScoreObjective obj = objective();
        if (obj == null) return null;
        Scoreboard sb = obj.getScoreboard();
        Collection<Score> sorted = sb.getSortedScores(obj);
        List<Score> list = new ArrayList<>();
        for (Score s : sorted) {
            if (s.getPlayerName() != null && !s.getPlayerName().startsWith("#")) list.add(s);
        }
        if (list.size() > 15) list = list.subList(list.size() - 15, list.size());
        List<String> out = new ArrayList<>();
        out.add(obj.getDisplayName());
        for (int i = list.size() - 1; i >= 0; i--) {
            String name = list.get(i).getPlayerName();
            String line = ScorePlayerTeam.formatPlayerName(sb.getPlayersTeam(name), name);
            if (hideAds.get()) {
                String plain = EnumChatFormatting.getTextWithoutFormattingCodes(line);
                if (plain != null && plain.toLowerCase().contains("hypixel.net")) continue;
            }
            out.add(line);
            points.add(list.get(i).getScorePoints());
        }
        return out;
    }

    private float spacedWidth(String plain) {
        if (!spacedTitle.get()) return R.width("§l" + plain);
        float w = 0;
        for (int i = 0; i < plain.length(); i++) w += R.width(String.valueOf(plain.charAt(i))) + 2.5f;
        return Math.max(0, w - 1.5f);
    }

    private static final List<String> PREVIEW = Arrays.asList("BED WARS", "§7" + "09/01/26  §8L5B", "",
            "Level: §b344✦", "", "Tokens: §a6,549,001", "", "§ewww.hypixel.net");

    // ---- layout of the current lines: measured when the lines (at most once per tick), the settings, the
    //      language or the font change, instead of every frame
    private List<String> layoutLines;
    private boolean layoutNums, layoutSpaced, layoutEnglish;
    private int layoutWidthGen = Integer.MIN_VALUE;
    private float layoutW, layoutH, layoutTitleX;
    /** Title characters as drawn ("§l" + character) and how far each one moves the pen. */
    private String[] titleChars = new String[0];
    private float[] titleSteps = new float[0];
    /** Red score numbers and where they start. */
    private String[] pointTexts = new String[0];
    private float[] pointX = new float[0];

    private void layout(List<String> lines) {
        boolean nums = !hideNumbers.get() && points.size() == lines.size() - 1;
        boolean spaced = spacedTitle.get(), english = dev.fzkc.fzhud.util.Lang.english();
        if (lines == layoutLines && nums == layoutNums && spaced == layoutSpaced && english == layoutEnglish
                && layoutWidthGen == R.widthGen) {
            return;
        }
        String title = EnumChatFormatting.getTextWithoutFormattingCodes(lines.get(0));
        if (title == null) title = "";
        title = title.trim();

        float w = spacedWidth(title) + 4;
        String[] pts = new String[Math.max(0, lines.size() - 1)];
        for (int i = 1; i < lines.size(); i++) {
            float lw = R.width(lines.get(i));
            if (nums) {
                pts[i - 1] = String.valueOf(points.get(i - 1));
                lw += 8 + R.width(pts[i - 1]);
            }
            w = Math.max(w, lw);
        }
        w += PAD * 2;
        float h = PAD + TITLE_H + (lines.size() - 1) * LINE + PAD - 2;

        String[] chars = new String[title.length()];
        float[] steps = new float[title.length()];
        for (int i = 0; i < title.length(); i++) {
            String c = String.valueOf(title.charAt(i));
            chars[i] = "§l" + c;
            steps[i] = R.width(c) + 2.5f;
        }
        float[] px = new float[pts.length];
        if (nums) for (int i = 0; i < pts.length; i++) px[i] = w - PAD - R.width(pts[i]);
        float titleX = (w - spacedWidth(title)) / 2f;

        layoutW = w;
        layoutH = h;
        layoutTitleX = titleX;
        titleChars = chars;
        titleSteps = steps;
        pointTexts = pts;
        pointX = px;
        // measuring above may have refreshed the width memory: take its generation last
        layoutWidthGen = R.widthGen;
        layoutLines = lines;
        layoutNums = nums;
        layoutSpaced = spaced;
        layoutEnglish = english;
    }

    @Override
    public void draw(float alpha, boolean preview) {
        List<String> lines = lines();
        if (lines == null) {
            if (!preview) return;
            lines = PREVIEW;
        }
        layout(lines);
        float w = layoutW, h = layoutH;
        boolean nums = layoutNums;
        lastW = w;
        lastH = h;

        panel(0, 0, w, h, alpha);

        // title: bold, letter-spaced, centred
        float tx = layoutTitleX;
        for (int i = 0; i < titleChars.length; i++) {
            R.text(titleChars[i], tx, PAD, R.alpha(0xFFFFFFFF, alpha));
            tx += titleSteps[i];
        }

        float y = PAD + TITLE_H;
        for (int i = 1; i < lines.size(); i++) {
            R.text(lines.get(i), PAD, y, R.alpha(0xFFFFFFFF, alpha));
            if (nums) R.text(pointTexts[i - 1], pointX[i - 1], y, R.alpha(0xFFFF5555, alpha));
            y += LINE;
        }
    }
}
