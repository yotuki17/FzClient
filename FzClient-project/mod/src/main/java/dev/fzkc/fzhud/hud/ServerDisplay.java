package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ChoiceSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import java.util.Objects;

/** Shows the server you're on, e.g. "[MC.Hypixel.Net]". */
public class ServerDisplay extends HudElement {
    private final ChoiceSetting mode;
    private final BoolSetting brackets, hideSingle, showBg;
    private final ColorSetting color;
    private String last = "";

    public ServerDisplay(float defX, float defY) {
        super("server", "Server Address", "接続中のサーバーを表示", defX, defY, LEFT);
        mode = add(new ChoiceSetting("mode", "表示する内容", 0, "アドレス", "サーバーリストの名前", "両方"));
        brackets = add(new BoolSetting("brackets", "[ ] で囲む", true));
        hideSingle = add(new BoolSetting("hidesingle", "シングルプレイでは隠す", false));
        showBg = add(new BoolSetting("background", "背景を表示", false));
        color = add(new ColorSetting("color", "文字の色", 0xFF5CE1E6));
        withStyle();
    }

    // the text only changes with the server or the settings: remember it instead of building it twice a frame
    private ServerData textSd;
    private String textIp, textName, textOut;
    private int textMode = -1;
    private boolean textBrackets, textSingle, textPreview;

    private String text(boolean preview) {
        Minecraft mc = Minecraft.getMinecraft();
        ServerData sd = mc.getCurrentServerData();
        boolean single = mc.isSingleplayer();
        String ip = sd == null ? null : sd.serverIP, name = sd == null ? null : sd.serverName;
        int mode = this.mode.get();
        boolean br = brackets.get();
        if (textOut != null && sd == textSd
                && Objects.equals(ip, textIp)
                && Objects.equals(name, textName)
                && mode == textMode
                && br == textBrackets
                && single == textSingle
                && preview == textPreview) {
            return textOut;
        }
        textSd = sd;
        textIp = ip;
        textName = name;
        textMode = mode;
        textBrackets = br;
        textSingle = single;
        textPreview = preview;
        textOut = build(mc, preview);
        return textOut;
    }

    private String build(Minecraft mc, boolean preview) {
        String s;
        ServerData sd = mc.getCurrentServerData();
        if (mc.isSingleplayer()) {
            s = "Singleplayer";
        } else if (sd != null) {
            String ip = sd.serverIP == null ? "" : sd.serverIP;
            String name = sd.serverName == null ? ip : sd.serverName;
            switch (mode.get()) {
                case 1: s = name; break;
                case 2: s = name.equalsIgnoreCase(ip) ? ip : name + " (" + ip + ")"; break;
                default: s = ip; break;
            }
        } else {
            s = preview ? "MC.Hypixel.Net" : "";
        }
        if (s.isEmpty()) return s;
        return brackets.get() ? "[" + s + "]" : s;
    }

    @Override
    public boolean active() {
        Minecraft mc = Minecraft.getMinecraft();
        if (hideSingle.get() && mc.isSingleplayer()) return false;
        return !text(false).isEmpty();
    }

    @Override public float width() { return Math.max(40, R.width(last) + (showBg.get() ? 12 : 2)); }
    @Override public float height() { return showBg.get() ? 18 : 10; }

    @Override
    public void draw(float alpha, boolean preview) {
        last = text(preview);
        if (last.isEmpty()) return;
        if (showBg.get()) {
            panel(0, 0, width(), height(), alpha);
            R.text(last, 6, 5, R.alpha(color.get(), alpha));
        } else {
            R.text(last, 1, 1, R.alpha(color.get(), alpha));
        }
    }
}
