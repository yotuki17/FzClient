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
        addCpu(right);
        if (Modules.debugSimple.get()) {
            left = simpleLeft(left);
            right = simpleRight(right);
        }
        left = pick(left, true);
        right = pick(right, false);
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

    private static String cpu;

    /** OptiFine puts its Native / GC lines where the game shows the processor: the CPU line comes back here. */
    private static void addCpu(List<List<String>> right) {
        for (List<String> g : right) {
            for (String l : g) if (strip(l).startsWith("CPU:")) return;
        }
        if (cpu == null) {
            try {
                cpu = "CPU: " + net.minecraft.client.renderer.OpenGlHelper.getCpu();   // "32x AMD Ryzen ..." already
            } catch (Throwable t) {
                cpu = "CPU: " + Runtime.getRuntime().availableProcessors() + "x";
            }
        }
        List<String> one = new ArrayList<String>();
        one.add(cpu);
        int at = right.size();
        for (int i = 0; i < right.size(); i++) {
            if (strip(right.get(i).get(0)).startsWith("Display:")) {
                at = i;
                break;
            }
        }
        right.add(at, one);
    }

    /** Only the kinds of lines switched on in "表示する行". */
    private static List<List<String>> pick(List<List<String>> in, boolean leftSide) {
        List<List<String>> out = new ArrayList<List<String>>();
        for (List<String> g : in) {
            String first = strip(g.get(0));
            List<String> keep = new ArrayList<String>();
            for (String l : g) {
                if ((leftSide ? leftKind(strip(l)) : rightKind(strip(l), first)).get()) keep.add(l);
            }
            if (!keep.isEmpty()) out.add(keep);
        }
        return out;
    }

    private static dev.fzkc.fzhud.config.BoolSetting leftKind(String p) {
        if (p.startsWith("Minecraft ")) return Modules.debugLVersion;
        if (FPS.matcher(p).find()) return Modules.debugLFps;
        if (p.startsWith("C:") || p.startsWith("E:") || p.startsWith("P:") || p.startsWith("MultiplayerChunkCache")
                || p.startsWith("Client Chunk")) return Modules.debugLRender;
        if (p.startsWith("XYZ:")) return Modules.debugLXyz;
        if (p.startsWith("Block:")) return Modules.debugLBlock;
        if (p.startsWith("Chunk")) return Modules.debugLChunk;
        if (p.startsWith("Facing:")) return Modules.debugLFacing;
        if (p.startsWith("Biome:")) return Modules.debugLBiome;
        if (p.startsWith("Light:")) return Modules.debugLLight;
        if (p.startsWith("Local Difficulty")) return Modules.debugLDifficulty;
        if (p.startsWith("Shader") || p.startsWith("[Shaders]")) return Modules.debugLShader;
        return Modules.debugLOther;
    }

    /** first = the first line of the panel: the GPU lines under "Display:", the states under the block id. */
    private static dev.fzkc.fzhud.config.BoolSetting rightKind(String p, String first) {
        if (BLOCK_ID.matcher(first).matches()) return Modules.debugRTarget;
        if (first.startsWith("Display:")) return Modules.debugRDisplay;
        if (p.startsWith("Java:")) return Modules.debugRJava;
        if (p.startsWith("Mem:") || p.startsWith("Allocated:")) return Modules.debugRMem;
        if (p.startsWith("Native:") || p.startsWith("GC:")) return Modules.debugRNative;
        if (p.startsWith("CPU:")) return Modules.debugRCpu;
        if (p.startsWith("MCP ") || p.startsWith("Powered by Forge") || p.startsWith("Optifine ")
                || p.contains(" mods loaded")) return Modules.debugRMods;
        return Modules.debugROther;
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
        int bg = R.alpha(0xFF000000 | (R.solid(Modules.debugBgColor.get()) & 0xFFFFFF), (float) (Modules.debugBg.get() / 100.0));
        boolean shadow = Modules.debugShadow.get();
        int style = Modules.debugStyle.get();
        float radius = (float) Modules.debugRadius.get();
        float y = 2;
        for (List<String> g : groups) {
            int width = 0;
            for (String l : g) width = Math.max(width, fr.getStringWidth(l));
            float bw = width + 8, bh = g.size() * 10 + 5;
            float x = alignRight ? edge - bw : edge;
            if (style == 0) {
                R.rounded(x, y, bw, bh, radius, bg);
            } else if (style == 1) {
                // a strip behind each line, as the game draws it
                float ly = y + 2;
                for (String l : g) {
                    float lw = fr.getStringWidth(l) + 4;
                    float lx = alignRight ? x + bw - 2 - lw : x + 2;
                    R.rounded(lx, ly, lw, 10, Math.min(radius, 4f), bg);
                    ly += 10;
                }
            }
            GlStateManager.enableBlend();
            GlStateManager.enableTexture2D();
            float ty = y + 3;
            for (String l : g) {
                float tx = alignRight ? x + bw - 4 - fr.getStringWidth(l) : x + 4;
                line(fr, l, tx, ty, shadow);
                ty += 10;
            }
            y += bh + (style == 0 ? 3 : 0);
        }
    }

    private static int opaque(int argb) {
        return 0xFF000000 | (R.solid(argb) & 0xFFFFFF);
    }

    /** One line: "Name: value" with the name in its colour (lines that bring their own colours stay). */
    private static void line(FontRenderer fr, String l, float x, float y, boolean shadow) {
        int text = opaque(Modules.debugTextColor.get());
        if (!Modules.debugColor.get() || l.indexOf('§') >= 0) {
            fr.drawString(l, x, y, text, shadow);
            return;
        }
        Matcher fps = FPS.matcher(l);
        if (fps.find() && Modules.debugFpsColor.get()) {
            int n = Integer.parseInt(fps.group(1));
            int c = n >= 120 ? 0xFF6EE36E : n >= 60 ? 0xFFF0D060 : 0xFFFF6B6B;
            String num = fps.group(1);
            x = fr.drawString(num, x, y, c, shadow);
            fr.drawString(l.substring(num.length()), x, y, text, shadow);
            return;
        }
        int colon = l.indexOf(": ");
        if (colon > 0 && colon <= 24) {
            String name = l.substring(0, colon + 1);
            // the accent picked in the settings, worked out here (Ui.accent is whatever the last menu left there)
            int nameColor = opaque(Modules.debugNameAccent.get() ? Modules.uiAccent.get() : Modules.debugNameColor.get());
            x = fr.drawString(name, x, y, nameColor, shadow);
            fr.drawString(l.substring(colon + 1), x, y, text, shadow);
            return;
        }
        fr.drawString(l, x, y, text, shadow);
    }
}
