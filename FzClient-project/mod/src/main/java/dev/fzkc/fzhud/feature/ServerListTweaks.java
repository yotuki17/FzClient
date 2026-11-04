package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Favorites;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.ServerSelectionList;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Mouse;

import java.lang.reflect.Field;

/**
 * Multiplayer list extras:
 *  - a ☆ on every saved server: click it to make it a favourite (shown on the FzClient title screen)
 *  - drag a server up / down to change the order (saved to servers.dat like vanilla's arrows)
 */
public final class ServerListTweaks {
    private static Field selectorField, clickYField;
    private static boolean failed;

    private int dragFrom = -1, pressY;
    private boolean dragging;
    private int mouseY;

    private static ServerSelectionList selector(GuiMultiplayer gui) {
        if (failed) return null;
        try {
            if (selectorField == null) {
                selectorField = field(GuiMultiplayer.class, "serverListSelector", "field_146803_h");
                clickYField = field(net.minecraft.client.gui.GuiSlot.class, "initialClickY", "field_148157_o");
            }
            return (ServerSelectionList) selectorField.get(gui);
        } catch (Throwable t) {
            failed = true;
            System.err.println("[FzClient] server list tweaks unavailable: " + t);
            return null;
        }
    }

    private static Field field(Class<?> c, String... names) throws NoSuchFieldException {
        for (String n : names) {
            try {
                Field f = c.getDeclaredField(n);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(names[0]);
    }

    // ---------------------------------------------------------------- geometry (same maths as GuiSlot)

    private static int rowX(ServerSelectionList s) {
        return s.left + s.width / 2 - s.getListWidth() / 2 + 2;
    }

    private static int rowY(ServerSelectionList s, int index) {
        return s.top + 4 - s.getAmountScrolled() + s.headerPadding + index * s.slotHeight;
    }

    /** Star position for a row: under the ping bars on the right. */
    private static int starX(ServerSelectionList s) {
        return rowX(s) + s.getListWidth() - 14;
    }

    private static boolean onStar(ServerSelectionList s, int index, int mx, int my) {
        int x = starX(s), y = rowY(s, index) + 12;
        return mx >= x - 2 && mx <= x + 10 && my >= y - 2 && my <= y + 10;
    }

    private static boolean visible(ServerSelectionList s, int y) {
        return y + 9 > s.top && y < s.bottom;
    }

    // ---------------------------------------------------------------- input

    @SubscribeEvent
    public void onMouse(GuiScreenEvent.MouseInputEvent.Pre event) {
        if (!(event.gui instanceof GuiMultiplayer)) return;
        GuiMultiplayer gui = (GuiMultiplayer) event.gui;
        ServerSelectionList s = selector(gui);
        if (s == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        int mouseX = Mouse.getEventX() * gui.width / mc.displayWidth;
        mouseY = gui.height - Mouse.getEventY() * gui.height / mc.displayHeight - 1;
        ServerList list = gui.getServerList();
        int count = list.countServers();
        int button = Mouse.getEventButton();

        if (button == 0 && Mouse.getEventButtonState()) {
            int idx = s.getSlotIndexFromScreenCoords(mouseX, mouseY);
            if (idx >= 0 && idx < count && mouseY >= s.top && mouseY < s.bottom) {
                if (onStar(s, idx, mouseX, mouseY)) {
                    boolean now = Favorites.toggle(list.getServerData(idx));
                    mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.create(
                            new net.minecraft.util.ResourceLocation("gui.button.press"), now ? 1.3f : 0.9f));
                    event.setCanceled(true);
                    return;
                }
                dragFrom = idx;
                pressY = mouseY;
                dragging = false;
            }
            return;
        }
        if (button == -1 && dragFrom >= 0 && Mouse.isButtonDown(0)) {
            if (!dragging && Math.abs(mouseY - pressY) > 5) {
                dragging = true;
                try {
                    clickYField.setInt(s, -2); // stop the list from scrolling with the drag
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        if (button == 0) {
            if (dragging && dragFrom < count) {
                int to = dropIndex(s, count);
                if (to != dragFrom) {
                    for (int i = dragFrom; i < to; i++) list.swapServers(i, i + 1);
                    for (int i = dragFrom; i > to; i--) list.swapServers(i, i - 1);
                    list.saveServerList();
                    s.func_148195_a(list); // rebuild the rows from the new order
                    gui.selectServer(to);
                }
                event.setCanceled(true);
            }
            dragFrom = -1;
            dragging = false;
        }
    }

    private int dropIndex(ServerSelectionList s, int count) {
        int rel = mouseY - rowY(s, 0);
        int i = Math.round(rel / (float) s.slotHeight - 0.5f);
        return Math.max(0, Math.min(count - 1, i));
    }

    // ---------------------------------------------------------------- drawing

    @SubscribeEvent
    public void onDraw(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!(event.gui instanceof GuiMultiplayer)) return;
        GuiMultiplayer gui = (GuiMultiplayer) event.gui;
        ServerSelectionList s = selector(gui);
        if (s == null) return;
        ServerList list = gui.getServerList();
        int count = list.countServers();
        int mx = event.mouseX, my = event.mouseY;
        GlStateManager.enableBlend();

        // stars
        String tip = null;
        for (int i = 0; i < count; i++) {
            int y = rowY(s, i) + 12;
            if (!visible(s, y)) continue;
            boolean fav = Favorites.is(list.getServerData(i));
            boolean over = !dragging && onStar(s, i, mx, my);
            int color = fav ? 0xFFFFD84A : (over ? 0xFFE6F7F8 : 0x60FFFFFF);
            R.text(fav ? "★" : "☆", starX(s), y, color);
            if (over) tip = fav ? "お気に入りから外す" : "お気に入りに追加（タイトル画面に表示）";
        }

        // drag: where it will land + the server being moved
        if (dragging && dragFrom >= 0 && dragFrom < count) {
            int to = dropIndex(s, count);
            int lineY = to > dragFrom ? rowY(s, to) + s.slotHeight - 3 : rowY(s, to) - 2;
            int x = rowX(s);
            if (lineY > s.top - 2 && lineY < s.bottom + 2) {
                R.rect(x - 2, lineY, s.getListWidth(), 2, 0xFF5CE1E6);
            }
            ServerData sd = list.getServerData(dragFrom);
            String name = sd.serverName;
            float w = R.width(name) + 10;
            R.rounded(mx + 8, my - 6, w, 14, 3f, 0xE0101A1E);
            R.text(name, mx + 13, my - 3, 0xFFFFFFFF);
        } else if (tip != null) {
            String t = Lang.t(tip);
            float w = R.width(t) + 8;
            R.rounded(mx + 8, my - 14, w, 13, 3f, 0xF0101A1E);
            R.text(t, mx + 12, my - 11, 0xFFE6F7F8);
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    @SubscribeEvent
    public void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
        if (event.gui instanceof GuiMultiplayer) {
            dragFrom = -1;
            dragging = false;
        }
    }
}
