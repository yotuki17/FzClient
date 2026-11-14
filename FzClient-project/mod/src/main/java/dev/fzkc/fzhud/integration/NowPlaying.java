package dev.fzkc.fzhud.integration;

/**
 * The music app the music HUD and the music keys work with: Spotify (read from its window, see {@link Spotify}),
 * or YouTube Music / Apple Music / any app through Windows' media controls (see {@link MediaSession}).
 */
public final class NowPlaying {
    private NowPlaying() {}

    public static final int SPOTIFY = 0, YOUTUBE_MUSIC = 1, APPLE_MUSIC = 2, ANY = 3;
    /** Names in the setting, in this order. */
    public static final String[] NAMES = {"Spotify", "YouTube Music", "Apple Music", "自動（再生中のアプリ）"};
    // YouTube Music: its desktop apps, or YouTube Music in a browser (only media with an album, so videos don't count)

    /** Set from the HUD's setting (it is read every frame the HUD is drawn, and when the game starts). */
    public static volatile int source = SPOTIFY;

    private static boolean spotify() { return source == SPOTIFY; }

    private static int filter() {
        switch (source) {
            case YOUTUBE_MUSIC: return MediaSession.YOUTUBE_MUSIC;
            case APPLE_MUSIC: return MediaSession.APPLE_MUSIC;
            default: return MediaSession.ANY;
        }
    }

    public static void keepAlive() {
        if (spotify()) Spotify.keepAlive();
        else MediaSession.keepAlive(filter());
    }

    public static boolean running() { return spotify() ? Spotify.running : MediaSession.running; }
    public static boolean playing() { return spotify() ? Spotify.playing : MediaSession.playing; }
    public static String title() { return spotify() ? Spotify.title : MediaSession.title; }
    public static String artist() { return spotify() ? Spotify.artist : MediaSession.artist; }
    /** Changes whenever the song (or the app) changes. */
    public static int songId() { return (spotify() ? Spotify.songId : MediaSession.songId) * 4 + source; }

    /** What the HUD says when the app isn't open. */
    public static String appName() {
        return source == ANY ? "音楽アプリ" : NAMES[source];
    }

    /** 0..1 through the song, or -1 when unknown. */
    public static float progress(long lengthMs) {
        if (spotify()) return SpotifyTimeline.progress(Spotify.songId, Spotify.playing, lengthMs);
        MediaSession.keepAlive(filter());
        return MediaSession.progress();
    }

    public static void playPause() { if (spotify()) Spotify.playPause(); else MediaSession.command(filter(), 'P'); }
    public static void next() { if (spotify()) Spotify.next(); else MediaSession.command(filter(), 'N'); }
    public static void previous() { if (spotify()) Spotify.previous(); else MediaSession.command(filter(), 'B'); }

    /** Client tick: the music keys (still named Spotify in the controls list, so nobody loses their key). */
    public static void tick() {
        dev.fzkc.fzhud.hud.SpotifyHud.sync();
        while (Spotify.keyPlay.isPressed()) playPause();
        while (Spotify.keyNext.isPressed()) next();
        while (Spotify.keyPrev.isPressed()) previous();
    }
}
