package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * F3 Screen: the debug text (F3) in a few tidy panels instead of a grey strip behind every line. Item names get the
 * accent colour, the frame rate is coloured by how good it is, and the technical lines can be left out. The text is
 * whatever Minecraft, Forge, OptiFine and other mods put in F3 (RenderGameOverlayEvent.Text, read last), so nothing
 * is lost; only the drawing is ours.
 */
public final class DebugScreen {
    /** Left-side lines that only matter for debugging the game itself ("よく使う行だけ" leaves them out). */
    private static final String[] TECH_LEFT = {"Minecraft ", "C:", "E:", "P:", "Client Chunk", "MultiplayerChunkCache",
            "Chunk:", "Chunk-relative", "Local Difficulty", "Shader", "[Shaders]"};
    private static final Pattern FPS = Pattern.compile("^(\\d+)(/\\d+)? fps");
    /** "minecraft:stone" heading the looked-at block on the right side. */
    private static final Pattern BLOCK_ID = Pattern.compile("^[a-z0-9_.-]+:[A-Za-z0-9_/.-]+$");   // 1.8.9 mods keep capitals

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onText(RenderGameOverlayEvent.Text event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!Modules.debugScreen.enabled || !mc.gameSettings.showDebugInfo || event.isCanceled()) return;
        List<List<String>> left = groups(event.left), right = groups(event.right);
        if (Modules.debugSimple.get()) {
            left = simpleLeft(left);
            right = simpleRight(right);
        }
        if (!Modules.debugRight.get()) right.clear();
        event.setCanceled(true);   // Forge draws nothing; the panels below take its place

        float s = (float) (Modules.debugScale.get() / 100.0);
        float w = event.resolution.getScaledWidth() / s;
        GlStateManager.pushMatrix();
        GlStateManager.scale(s, s, 1f);
        draw(mc.fontRendererObj, left, 2, false);
        draw(mc.fontRendererObj, right, w - 2, true);
        GlStateManager.popMatrix();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** The lines split where Minecraft leaves an empty line, empty groups dropped. */
    private static List<List<String>> groups(List<String> lines) {
        List<List<String>> out = new ArrayList<List<String>>();
        List<String> cur = new ArrayList<String>();
        for (String l : lines) {
            if (l == null || l.trim().isEmpty()) {
                if (!cur.isEmpty()) out.add(cur);
                cur = new ArrayList<String>();
            } else {
                cur.add(l);
            }
        }
        if (!cur.isEmpty()) out.add(cur);
        return out;
    }

    private static List<List<String>> simpleLeft(List<List<String>> in) {
        List<List<String>> out = new ArrayList<List<String>>();
        for (List<String> g : in) {
            List<String> keep = new ArrayList<String>();
            for (String l : g) {
                String p = strip(l);
                boolean tech = false;
                for (String t : TECH_LEFT) {
                    if (p.startsWith(t)) {
                        tech = true;
                        break;
                    }
                }
                if (!tech) keep.add(l);
            }
            if (!keep.isEmpty()) out.add(keep);
        }
        return out;
    }

    /** Memory, and the block you look at (the group that starts with its id). */
    private static List<List<String>> simpleRight(List<List<String>> in) {
        List<List<String>> out = new ArrayList<List<String>>();
        for (List<String> g : in) {
            if (BLOCK_ID.matcher(strip(g.get(0))).matches()) {
                out.add(g);
                continue;
            }
            for (String l : g) {
                if (strip(l).startsWith("Mem:")) {
                    List<String> one = new ArrayList<String>();
                    one.add(l);
                    out.add(0, one);
                }
            }
        }
        return out;
    }

    private static String strip(String s) {
        return s.replaceAll("§.", "");
    }

    private static void draw(FontRenderer fr, List<List<String>> groups, float edge, boolean alignRight) {
        if (groups.isEmpty()) return;
        int bg = R.alpha(0xFF0B0F14, (float) (Modules.debugBg.get() / 100.0));
        boolean shadow = Modules.debugShadow.get();
        float y = 2;
        for (List<String> g : groups) {
            int width = 0;
            for (String l : g) width = Math.max(width, fr.getStringWidth(l));
            float bw = width + 8, bh = g.size() * 10 + 5;
            float x = alignRight ? edge - bw : edge;
            R.rounded(x, y, bw, bh, 3f, bg);
            GlStateManager.enableBlend();
            GlStateManager.enableTexture2D();
            float ty = y + 3;
            for (String l : g) {
                float tx = alignRight ? x + bw - 4 - fr.getStringWidth(l) : x + 4;
                line(fr, l, tx, ty, shadow);
                ty += 10;
            }
            y += bh + 3;
        }
    }

    /** One line: "Name: value" with the name in the accent colour (lines that bring their own colours stay). */
    private static void line(FontRenderer fr, String l, float x, float y, boolean shadow) {
        int white = 0xFFFFFFFF;
        if (!Modules.debugColor.get() || l.indexOf('§') >= 0) {
            fr.drawString(l, x, y, 0xFFE0E0E0, shadow);
            return;
        }
        Matcher fps = FPS.matcher(l);
        if (fps.find()) {
            int n = Integer.parseInt(fps.group(1));
            int c = n >= 120 ? 0xFF6EE36E : n >= 60 ? 0xFFF0D060 : 0xFFFF6B6B;
            String num = fps.group(1);
            x = fr.drawString(num, x, y, c, shadow);
            fr.drawString(l.substring(num.length()), x, y, white, shadow);
            return;
        }
        int colon = l.indexOf(": ");
        if (colon > 0 && colon <= 24) {
            String name = l.substring(0, colon + 1);
            // the accent picked in the settings, worked out here (Ui.accent is whatever the last menu left there)
            int accent = 0xFF000000 | (R.solid(Modules.uiAccent.get()) & 0xFFFFFF);
            x = fr.drawString(name, x, y, accent, shadow);
            fr.drawString(l.substring(colon + 1), x, y, white, shadow);
            return;
        }
        fr.drawString(l, x, y, white, shadow);
    }
}
