package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.integration.NowPlaying;
import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ChoiceSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.config.SliderSetting;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;

/**
 * Now playing (song, artist, playing / paused): Spotify, YouTube Music, Apple Music or any app (see {@link NowPlaying}).
 * Still "spotify" inside (its saved position and settings stay).
 */
public class SpotifyHud extends HudElement {
    private final SliderSetting maxWidth;
    private final ChoiceSetting source;
    private final BoolSetting showArtist, hideWhenClosed, hideWhenPaused, albumArt, progressBar;
    private final Anim barAnim = new Anim(0f, 6f);
    private final ColorSetting titleColor, artistColor, iconColor;
    private final Anim slide = new Anim(1f, 8f);
    private int lastSong = -1;
    private float lastW = 120;
    private String fitSongIn = "", fitByIn = "", fitSong = "", fitBy = "";
    private float fitMax = -1;

    public SpotifyHud(float defX, float defY) {
        super("spotify", "Music", "再生中の曲（Spotify・YouTube Music・Apple Music など／Windows）", defX, defY, LEFT, false);
        withBackground();
        source = add(new ChoiceSetting("source", "音楽アプリ", NowPlaying.SPOTIFY, NowPlaying.NAMES));
        instance = this;
        maxWidth = add(new SliderSetting("maxwidth", "最大の幅", 80, 300, 10, 160, ""));
        showArtist = add(new BoolSetting("artist", "アーティスト名を表示", true));
        albumArt = add(new BoolSetting("albumart", "曲のジャケット画像を表示", true));
        progressBar = add(new BoolSetting("progress", "曲の進み具合のバーを表示", true));
        hideWhenClosed = add(new BoolSetting("hideclosed", "音楽アプリを開いていない時は隠す", true));
        hideWhenPaused = add(new BoolSetting("hidepaused", "一時停止中は隠す", false));
        titleColor = add(new ColorSetting("titlecolor", "曲名の色", 0xFFFFFFFF));
        artistColor = add(new ColorSetting("artistcolor", "アーティストの色", 0xFF9DB4B8));
        iconColor = add(new ColorSetting("iconcolor", "アイコンの色", 0xFF1ED760));
    }

    @Override
    public boolean active() {
        NowPlaying.source = source.get();
        NowPlaying.keepAlive();
        if (hideWhenClosed.get() && !NowPlaying.running()) return false;
        return !hideWhenPaused.get() || NowPlaying.playing();
    }

    private static SpotifyHud instance;

    /** Every tick: the music keys follow the setting even while the HUD is hidden or off. */
    public static void sync() {
        if (instance != null) NowPlaying.source = instance.source.get();
    }

    @Override public float width() { return lastW; }
    @Override public float height() { return (showArtist.get() ? 30 : 20) + (progressBar.get() ? 4 : 0); }

    @Override
    public void draw(float alpha, boolean preview) {
        NowPlaying.source = source.get();
        NowPlaying.keepAlive();
        String song = NowPlaying.title(), by = NowPlaying.artist();
        boolean on = NowPlaying.playing();
        if (song.isEmpty()) {
            song = preview ? "Song Title" : (NowPlaying.running() ? dev.fzkc.fzhud.util.Lang.t(NowPlaying.appName())
                    : dev.fzkc.fzhud.util.Lang.t(NowPlaying.appName()) + dev.fzkc.fzhud.util.Lang.t("が開いていません"));
            by = preview ? "Artist" : "";
            on = preview;
        }
        if (NowPlaying.songId() != lastSong) {
            lastSong = NowPlaying.songId();
            slide.snap(0f);
        }
        float s = slide.update(1f);

        float max = maxWidth.get();
        if (!song.equals(fitSongIn) || !by.equals(fitByIn) || max != fitMax) {
            fitSongIn = song;
            fitByIn = by;
            fitMax = max;
            fitSong = fit(song, max);
            fitBy = fit(by, max);
        }
        String t = fitSong, a = fitBy;
        // cover art (looked up by song name); while it loads / if none is found the ♪ icon is shown
        net.minecraft.util.ResourceLocation art = null;
        // only with an artist: without one the search can't run and the last song's cover would stay
        if (albumArt.get() && !NowPlaying.title().isEmpty() && !NowPlaying.artist().isEmpty()) {
            dev.fzkc.fzhud.integration.AlbumArt.want(NowPlaying.artist(), NowPlaying.title());
            art = dev.fzkc.fzhud.integration.AlbumArt.current();
        }
        float artSize = height() - 6;
        float tx = art != null ? 3 + artSize + 6 : 22;
        float textW = Math.max(R.width(t), showArtist.get() ? R.width(a) : 0);
        lastW = tx + textW + 8;
        bg(0, 0, lastW, height(), alpha);

        float cy = height() / 2f;
        if (art != null) {
            Minecraft mc = Minecraft.getMinecraft();
            mc.getTextureManager().bindTexture(art);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
            org.lwjgl.opengl.GL11.glTexParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER, org.lwjgl.opengl.GL11.GL_LINEAR);
            net.minecraft.client.renderer.GlStateManager.enableBlend();
            net.minecraft.client.renderer.GlStateManager.color(1f, 1f, 1f, alpha * s);
            net.minecraft.client.gui.Gui.drawScaledCustomSizeModalRect(3, 3, 0, 0, 64, 64, (int) artSize, (int) artSize, 64, 64);
            net.minecraft.client.renderer.GlStateManager.color(1f, 1f, 1f, 1f);
            if (!on) {
                // paused: darken the cover and show ‖ on it
                R.rect(3, 3, artSize, artSize, R.alpha(0x99000000, alpha));
                int pc = R.alpha(0xFFFFFFFF, alpha);
                R.rect(3 + artSize / 2f - 3, cy - 4, 2, 8, pc);
                R.rect(3 + artSize / 2f + 1, cy - 4, 2, 8, pc);
            }
        } else {
            // icon: ♪ while playing, ‖ while paused
            int ic = R.alpha(on ? iconColor.get() : 0xFF9DB4B8, alpha);
            if (on) {
                R.text("♪", 7, cy - 4, ic);
            } else {
                R.rect(7, cy - 4, 2, 8, ic);
                R.rect(11, cy - 4, 2, 8, ic);
            }
        }

        float off = (1f - s) * 6f;
        int ta = R.alpha(titleColor.get(), alpha * s), aa = R.alpha(artistColor.get(), alpha * s);
        if (showArtist.get()) {
            R.text(t, tx, 6 + off, ta);
            R.text(a, tx, 17 + off, aa);
        } else {
            R.text(t, tx, 6 + off, ta);
        }

        // how far into the song: a thin bar under the text
        if (progressBar.get()) {
            float p = preview && NowPlaying.title().isEmpty() ? 0.4f
                    : NowPlaying.progress(dev.fzkc.fzhud.integration.AlbumArt.currentLengthMs());
            float barY = height() - 6, bw = lastW - tx - 8;
            if (p >= 0f) {
                // ease only small steps (a new song / seeking jumps straight there)
                float shown = Math.abs(p - barAnim.value) > 0.05f ? p : barAnim.update(p);
                barAnim.value = shown;
                R.rounded(tx, barY, bw, 2f, 1f, R.alpha(0x40FFFFFF, alpha));
                if (shown > 0.001f) R.rounded(tx, barY, Math.max(2f, bw * shown), 2f, 1f, R.alpha(iconColor.get(), alpha));
            } else {
                R.rounded(tx, barY, bw, 2f, 1f, R.alpha(0x20FFFFFF, alpha));
            }
        }
    }

    /** Cuts the text to the width with "..." (results are cheap: only long titles get trimmed). */
    private static String fit(String s, float max) {
        if (s == null) return "";
        if (R.width(s) <= max) return s;
        int ell = R.width("...");
        int end = s.length();
        while (end > 0 && R.width(s.substring(0, end)) + ell > max) end--;
        return s.substring(0, end) + "...";
    }
}
