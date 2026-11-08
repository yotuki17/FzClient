package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiUtilRenderComponents;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.MathHelper;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FzClient's chat box. It keeps Minecraft's own message lists (so every other feature and mod that reads them
 * still works) and only changes how they are drawn:
 * <ul>
 *   <li>tabs: すべて / パーティ / ギルド / 個人 (Hypixel prefixes), chosen above the chat input;</li>
 *   <li>chat heads: the sender's skin face in front of the first line of a player message;</li>
 *   <li>look: background colour and strength, one rounded panel or a strip per line, line height, text shadow.</li>
 * </ul>
 * Installed once in place of the vanilla chat (see {@link #install}); with "FzClient のチャット表示" off it
 * behaves exactly like the vanilla one.
 */
public class FzChat extends GuiNewChat {
    public static final String[] TABS = {"すべて", "パーティ", "ギルド", "個人"};
    public static final int ALL = 0, PARTY = 1, GUILD = 2, PM = 3;

    /** Selected tab (kept while the game runs). */
    public static int tab = ALL;
    /** Tabs that got a message since they were last looked at. */
    public static final boolean[] unread = new boolean[TABS.length];

    private final Minecraft mc;

    public FzChat(Minecraft mc) {
        super(mc);
        this.mc = mc;
    }

    // ------------------------------------------------------------------ install

    /** Puts an FzChat in place of the vanilla chat box, keeping its messages. Call on the game thread. */
    @SuppressWarnings("unchecked")
    public static void install() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI == null || mc.ingameGUI.getChatGUI() instanceof FzChat) return;
        try {
            GuiNewChat old = mc.ingameGUI.getChatGUI();
            FzChat chat = new FzChat(mc);
            chat.sentList().addAll(old.getSentMessages());
            chat.all().addAll((List<ChatLine>) F_CHAT.get(old));
            chat.drawn().addAll((List<ChatLine>) F_DRAWN.get(old));
            Field f = field(GuiIngame.class, "persistantChatGUI", "field_73840_e");
            f.set(mc.ingameGUI, chat);
        } catch (Throwable t) {
            System.err.println("[FzClient] Chat box not replaced: " + t);
        }
    }

    // ------------------------------------------------------------------ vanilla internals

    private static final Field F_SENT, F_CHAT, F_DRAWN, F_SCROLL, F_SCROLLED;

    static {
        Field a = null, b = null, c = null, d = null, e = null;
        try {
            a = field(GuiNewChat.class, "sentMessages", "field_146248_g");
            b = field(GuiNewChat.class, "chatLines", "field_146252_h");
            c = field(GuiNewChat.class, "drawnChatLines", "field_146253_i");
            d = field(GuiNewChat.class, "scrollPos", "field_146250_j");
            e = field(GuiNewChat.class, "isScrolled", "field_146251_k");
        } catch (Throwable t) {
            System.err.println("[FzClient] Chat fields not found: " + t);
        }
        F_SENT = a;
        F_CHAT = b;
        F_DRAWN = c;
        F_SCROLL = d;
        F_SCROLLED = e;
    }

    private static Field field(Class<?> owner, String... names) throws NoSuchFieldException {
        for (String n : names) {
            try {
                Field f = owner.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(names[0]);
    }

    @SuppressWarnings("unchecked")
    private List<String> sentList() {
        try { return (List<String>) F_SENT.get(this); } catch (Exception e) { return new ArrayList<String>(); }
    }

    @SuppressWarnings("unchecked")
    private List<ChatLine> all() {
        try { return (List<ChatLine>) F_CHAT.get(this); } catch (Exception e) { return new ArrayList<ChatLine>(); }
    }

    @SuppressWarnings("unchecked")
    private List<ChatLine> drawn() {
        try { return (List<ChatLine>) F_DRAWN.get(this); } catch (Exception e) { return new ArrayList<ChatLine>(); }
    }

    private int scrollPos() {
        try { return F_SCROLL.getInt(this); } catch (Exception e) { return 0; }
    }

    private void setScrollPos(int v) {
        try { F_SCROLL.setInt(this, v); } catch (Exception ignored) { }
    }

    private boolean scrolled() {
        try { return F_SCROLLED.getBoolean(this); } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------------ settings

    private static boolean custom() { return Modules.chat.enabled && Modules.chatCustom.get() && F_DRAWN != null; }

    private static boolean heads() { return custom() && Modules.chatHeads.get(); }

    /** Height of one line in chat pixels (vanilla 9). */
    private static int lineH() { return custom() ? (int) Modules.chatLineHeight.get() : 9; }

    private static int pad() { return heads() ? 10 : 0; }

    // ------------------------------------------------------------------ which message each line belongs to

    private static final class Msg {
        final ChatLine full;
        final int cat;
        final String sender;
        NetworkPlayerInfo player;
        long nextLookup;

        Msg(ChatLine full, int cat, String sender) {
            this.full = full;
            this.cat = cat;
            this.sender = sender;
        }
    }

    private static final class Piece {
        final Msg msg;
        final boolean first;   // top line of its message (gets the head)

        Piece(Msg msg, boolean first) {
            this.msg = msg;
            this.first = first;
        }
    }

    private final Map<ChatLine, Msg> msgs = new IdentityHashMap<ChatLine, Msg>();
    private final Map<ChatLine, Piece> pieces = new IdentityHashMap<ChatLine, Piece>();
    private List<ChatLine> visible = new ArrayList<ChatLine>();
    private Object keyTop, keyAllTop;
    private int keySize = -1, keyAllSize = -1, keyTab = -1;
    private boolean keyHeads, mapped;

    private static final Pattern SENDER = Pattern.compile(
            "^(?:(?:Party|P|Guild|G|Officer|O|Co-op|CO) > |From |To )?(?:\\[[^\\]]{1,24}\\] )*([A-Za-z0-9_]{2,16})(?: \\[[^\\]]{1,10}\\])?: ");
    private static final Pattern VANILLA = Pattern.compile("^<([A-Za-z0-9_]{2,16})> ");

    /** "[RANK] Name [TAG]: " - only real player messages count, not system lines that mention a party or guild. */
    private static final String WHO = "(?:\\[[^\\]]{1,24}\\] )?[A-Za-z0-9_]{1,16}(?: \\[[^\\]]{1,10}\\])?: ";
    private static final Pattern PARTY_MSG = Pattern.compile("^Party > " + WHO);
    private static final Pattern GUILD_MSG = Pattern.compile("^(?:Guild|Officer) > " + WHO);
    private static final Pattern PM_MSG = Pattern.compile("^(?:From|To) " + WHO);

    private static int classify(String plain) {
        if (PARTY_MSG.matcher(plain).find()) return PARTY;   // Party > [MVP++] Name: text
        if (GUILD_MSG.matcher(plain).find()) return GUILD;   // Guild > [MVP++] Name [GM]: text
        if (PM_MSG.matcher(plain).find()) return PM;         // From [VIP] Name: text / To Name: text
        return ALL;
    }

    private static String sender(String plain) {
        Matcher m = SENDER.matcher(plain);
        if (m.find()) return m.group(1);
        m = VANILLA.matcher(plain);
        return m.find() ? m.group(1) : null;
    }

    /** Rebuilds the line -> message map and the visible list when the chat or the tab changed. */
    private void sync() {
        boolean h = heads();
        if (h != keyHeads) {
            keyHeads = h;
            refreshChat();   // the wrap width changes with the heads
        }
        List<ChatLine> drawn = drawn(), all = all();
        Object top = drawn.isEmpty() ? null : drawn.get(0), allTop = all.isEmpty() ? null : all.get(0);
        boolean changed = top != keyTop || drawn.size() != keySize || allTop != keyAllTop || all.size() != keyAllSize;
        if (changed) {
            keyTop = top;
            keySize = drawn.size();
            keyAllTop = allTop;
            keyAllSize = all.size();
            remap(drawn, all);
        }
        if (changed || tab != keyTab) {
            keyTab = tab;
            List<ChatLine> v = new ArrayList<ChatLine>();
            for (ChatLine l : drawn) {
                Piece p = pieces.get(l);
                if (tab == ALL || (p != null && p.msg != null && p.msg.cat == tab)) v.add(l);
            }
            visible = v;
        }
        unread[tab] = false;
    }

    private Msg msgOf(ChatLine full) {
        Msg m = msgs.get(full);
        if (m == null) {
            String plain = EnumChatFormatting.getTextWithoutFormattingCodes(full.getChatComponent().getUnformattedText());
            m = new Msg(full, classify(plain), sender(plain));
            if (mapped && m.cat != ALL && m.cat != tab) unread[m.cat] = true;
        }
        return m;
    }

    private void remap(List<ChatLine> drawn, List<ChatLine> all) {
        // The chat keeps the whole session (thousands of lines): when only new lines came in on top, map just
        // those instead of re-wrapping every message. A full rebuild only after a refresh (all lines are new
        // objects then) or once enough removed lines are left in the maps.
        boolean known = !drawn.isEmpty() && !all.isEmpty() && pieces.containsKey(drawn.get(drawn.size() - 1))
                && msgs.containsKey(all.get(all.size() - 1));
        boolean full = !known || msgs.size() > all.size() + 300 || pieces.size() > drawn.size() + 600;
        if (!full) {
            for (ChatLine l : all) {
                if (msgs.containsKey(l)) break;
                msgs.put(l, msgOf(l));
            }
            int newDrawn = 0;
            while (newDrawn < drawn.size() && !pieces.containsKey(drawn.get(newDrawn))) newDrawn++;
            mapPieces(drawn.subList(0, newDrawn), all);
            return;
        }
        Map<ChatLine, Msg> keep = new IdentityHashMap<ChatLine, Msg>();
        for (ChatLine l : all) keep.put(l, msgOf(l));
        msgs.clear();
        msgs.putAll(keep);
        mapped = true;
        pieces.clear();
        mapPieces(drawn, all);
    }

    /** Drawn lines are newest first; each message's pieces sit together, its top line last. */
    private void mapPieces(List<ChatLine> drawn, List<ChatLine> all) {
        int width = MathHelper.floor_float(getChatWidth() / getChatScale());
        int p = 0, left = 0;
        Msg cur = null;
        for (ChatLine line : drawn) {
            if (left == 0) {
                cur = null;
                for (int q = p; q < all.size() && q < p + 8; q++) {
                    ChatLine full = all.get(q);
                    if (full.getUpdatedCounter() == line.getUpdatedCounter() && full.getChatLineID() == line.getChatLineID()) {
                        cur = msgs.get(full);
                        p = q + 1;
                        try {
                            left = Math.max(1, GuiUtilRenderComponents.splitText(full.getChatComponent(), width, mc.fontRendererObj, false, false).size());
                        } catch (Throwable t) {
                            left = 1;
                        }
                        break;
                    }
                }
                if (cur == null) left = 1;
            }
            pieces.put(line, new Piece(cur, left == 1));
            left--;
        }
    }

    // ------------------------------------------------------------------ vanilla overrides

    @Override
    public int getChatWidth() {
        int w = super.getChatWidth();
        return heads() ? w - Math.round(10 * getChatScale()) : w;   // room for the head in front of each line
    }

    @Override
    public int getLineCount() {
        return custom() ? getChatHeight() / lineH() : super.getLineCount();
    }

    @Override
    public void drawChat(int updateCounter) {
        if (!custom()) {
            super.drawChat(updateCounter);
            return;
        }
        if (mc.gameSettings.chatVisibility == EntityPlayer.EnumChatVisibility.HIDDEN) return;
        sync();
        List<ChatLine> lines = visible;
        int lineCount = getLineCount();
        boolean open = getChatOpen();
        int scroll = Math.max(0, Math.min(scrollPos(), lines.size() - lineCount));
        if (scroll != scrollPos()) setScrollPos(scroll);
        if (lines.isEmpty()) return;

        float opacity = mc.gameSettings.chatOpacity * 0.9f + 0.1f;
        float scale = getChatScale();
        int pad = pad(), lh = lineH();
        int width = MathHelper.ceiling_float_int(getChatWidth() / scale) + pad;
        int n = Math.min(lineCount, lines.size() - scroll);
        int[] alpha = new int[n];
        int maxA = 0, topRow = -1, shown = 0;
        for (int i = 0; i < n; i++) {
            ChatLine line = lines.get(i + scroll);
            int age = updateCounter - line.getUpdatedCounter();
            if (age >= 200 && !open) continue;
            double d = MathHelper.clamp_double((1.0 - age / 200.0) * 10.0, 0.0, 1.0);
            int a = open ? 255 : (int) (255.0 * d * d);
            a = (int) (a * opacity);
            alpha[i] = a;
            shown++;
            if (a > 3) {
                maxA = Math.max(maxA, a);
                topRow = i;
            }
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(2f, 20f, 0f);
        GlStateManager.scale(scale, scale, 1f);

        int bg = R.solid(Modules.chatBgColor.get()) & 0xFFFFFF;
        float bgK = Modules.chatBgOpacity.get() / 100f;
        if (Modules.chatBgStyle.get() == 1) {
            if (topRow >= 0) {
                int a = Math.min(255, (int) (maxA * bgK));
                if (a > 0) R.rounded(0, -(topRow + 1) * lh, width + 4, (topRow + 1) * lh, Modules.chatRadius.get(), (a << 24) | bg);
            }
        } else {
            for (int i = 0; i < n; i++) {
                int a = Math.min(255, (int) (alpha[i] * bgK));
                if (alpha[i] > 3 && a > 0) Gui.drawRect(0, -(i + 1) * lh, width + 4, -i * lh, (a << 24) | bg);
            }
        }

        FontRenderer fr = mc.fontRendererObj;
        boolean shadow = Modules.chatShadow.get();
        int textY = (lh - 8 + 1) / 2;   // vanilla: 1 px below the top of a 9 px line
        for (int i = 0; i < n; i++) {
            int a = alpha[i];
            if (a <= 3) continue;
            ChatLine line = lines.get(i + scroll);
            int y = -(i + 1) * lh + textY;
            if (pad > 0) {
                Piece p = pieces.get(line);
                if (p != null && p.first && p.msg != null) head(p.msg, 0, y, a);
            }
            GlStateManager.enableBlend();
            String s = line.getChatComponent().getFormattedText();
            if (shadow) fr.drawStringWithShadow(s, pad, y, 0xFFFFFF + (a << 24));
            else fr.drawString(s, pad, y, 0xFFFFFF + (a << 24));
            GlStateManager.disableAlpha();
            GlStateManager.disableBlend();
        }

        if (open) {   // scroll bar, as vanilla
            GlStateManager.translate(-3f, 0f, 0f);
            int total = lines.size() * lh, view = shown * lh;
            if (total != view && total > 0) {
                int y0 = scroll * view / lines.size();
                int barH = view * view / total;
                int a = y0 > 0 ? 170 : 96;
                int c = scrolled() ? 0xCC3333 : 0x3333AA;
                Gui.drawRect(0, -y0, 2, -y0 - barH, c + (a << 24));
                Gui.drawRect(2, -y0, 1, -y0 - barH, 0xCCCCCC + (a << 24));
            }
        }
        GlStateManager.popMatrix();
        GlStateManager.enableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** The sender's face (only for players the server lists in the tab list). */
    private void head(Msg m, int x, int y, int alpha) {
        if (m.sender == null) return;
        if (m.player == null) {
            long now = System.currentTimeMillis();
            if (now < m.nextLookup || mc.getNetHandler() == null) return;
            m.nextLookup = now + 2000L;
            m.player = mc.getNetHandler().getPlayerInfo(m.sender);
            if (m.player == null) return;
        }
        mc.getTextureManager().bindTexture(m.player.getLocationSkin());
        GlStateManager.enableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, alpha / 255f);
        Gui.drawScaledCustomSizeModalRect(x, y, 8, 8, 8, 8, 8, 8, 64, 64);
        Gui.drawScaledCustomSizeModalRect(x, y, 40, 8, 8, 8, 8, 8, 64, 64);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    @Override
    public void scroll(int amount) {
        super.scroll(amount);
        if (custom()) {
            int max = Math.max(0, visible.size() - getLineCount());
            if (scrollPos() > max) setScrollPos(max);
        }
    }

    @Override
    public IChatComponent getChatComponent(int mouseX, int mouseY) {
        if (!custom()) return super.getChatComponent(mouseX, mouseY);
        ChatLine line = lineAt(mouseX, mouseY);
        if (line == null) return null;
        int x = hitX - pad();
        if (x < 0) return null;
        // measure the same text that is drawn (style codes included, e.g. bold, and the colour codes inside a
        // piece such as the stats in front of a message), so the click lands on the piece under the mouse
        StringBuilder drawn = new StringBuilder();
        for (IChatComponent c : line.getChatComponent()) {
            drawn.append(c.getChatStyle().getFormattingCode()).append(c.getUnformattedTextForChat()).append(EnumChatFormatting.RESET);
            if (mc.fontRendererObj.getStringWidth(drawn.toString()) > x) return c instanceof ChatComponentText ? c : null;
        }
        return null;
    }

    private int hitX;

    /** The drawn line under the mouse (Mouse.getX/getY coordinates), or null. */
    private ChatLine lineAt(int mouseX, int mouseY) {
        if (!getChatOpen()) return null;
        sync();
        int sf = new ScaledResolution(mc).getScaleFactor();
        float scale = getChatScale();
        int x = MathHelper.floor_float((mouseX / sf - 3) / scale);
        int y = MathHelper.floor_float((mouseY / sf - 27) / scale);
        if (x < 0 || y < 0) return null;
        int lh = lineH();
        int rows = Math.min(getLineCount(), visible.size());
        if (x > MathHelper.floor_float(getChatWidth() / scale) + pad() || y >= lh * rows) return null;
        int idx = y / lh + scrollPos();
        if (idx < 0 || idx >= visible.size()) return null;
        hitX = x;
        return visible.get(idx);
    }

    /** Plain text of the whole message under the mouse (for Ctrl+click copy), or null. */
    public String messageAt(int mouseX, int mouseY) {
        ChatLine line = lineAt(mouseX, mouseY);
        if (line == null) return null;
        Piece p = pieces.get(line);
        ChatLine src = p != null && p.msg != null ? p.msg.full : line;
        return EnumChatFormatting.getTextWithoutFormattingCodes(src.getChatComponent().getUnformattedText());
    }

    public static boolean active() {
        return custom();
    }
}
