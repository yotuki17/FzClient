package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.util.Fmt;
import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Tracker;

import java.text.SimpleDateFormat;
import java.util.Date;

/** "[03 : 28 : 00]" clock and "[0.4 m/s]" speed, right aligned. */
public class ClockSpeed extends HudElement {
    private final SimpleDateFormat fmt24 = new SimpleDateFormat("HH' : 'mm' : 'ss");
    private final SimpleDateFormat fmt12 = new SimpleDateFormat("hh' : 'mm' : 'ss a", java.util.Locale.JAPANESE);
    private final SimpleDateFormat fmt12en = new SimpleDateFormat("hh' : 'mm' : 'ss a", java.util.Locale.ENGLISH);
    private final BoolSetting showClock, showSpeed, h24;
    private final ColorSetting color;

    public ClockSpeed(float defX, float defY) {
        super("clock", "Clock / Speed", "時計と移動速度", defX, defY);
        showClock = add(new BoolSetting("clock", "時計を表示", true));
        h24 = add(new BoolSetting("24h", "24時間表記", true));
        showSpeed = add(new BoolSetting("speed", "速度を表示", true));
        color = add(new ColorSetting("color", "文字の色", 0xFF5CE1E6));
    }

    private long clockSec = -1;
    private boolean clock24, clockEn;
    private String clockText = "";
    private long speedKey = Long.MIN_VALUE;
    private String speedText = "";

    private String clock() {
        long sec = System.currentTimeMillis() / 1000L;
        boolean is24 = h24.get();
        if (sec != clockSec || is24 != clock24 || dev.fzkc.fzhud.util.Lang.english() != clockEn) {
            clockEn = dev.fzkc.fzhud.util.Lang.english();
            clockSec = sec;
            clock24 = is24;
            clockText = "[" + (is24 ? fmt24 : dev.fzkc.fzhud.util.Lang.english() ? fmt12en : fmt12).format(new Date(sec * 1000L)) + "]";
        }
        return clockText;
    }

    private String speed() {
        long key = Math.round(Tracker.speed * 10.0);
        if (key != speedKey) {
            speedKey = key;
            speedText = Fmt.append(new StringBuilder(14).append('['), key / 10.0, 1).append(" m/s]").toString();
        }
        return speedText;
    }

    @Override public float width() { return 100f; }
    @Override public float height() { return (showClock.get() && showSpeed.get()) ? 21f : 10f; }

    @Override
    public void draw(float alpha, boolean preview) {
        int c = R.alpha(color.get(), alpha);
        float y = 0;
        if (showClock.get()) {
            String clock = clock();
            R.text(clock, width() - R.width(clock), y, c);
            y += 11;
        }
        if (showSpeed.get()) {
            String speed = speed();
            R.text(speed, width() - R.width(speed), y, c);
        }
    }
}
