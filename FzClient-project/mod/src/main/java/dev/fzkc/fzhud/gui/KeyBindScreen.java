package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.feature.KeyCombos;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiListExtended;
import net.minecraft.client.gui.GuiOptionButton;
import net.minecraft.client.gui.GuiOptionSlider;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.EnumChatFormatting;
import org.lwjgl.input.Keyboard;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The game's Controls screen with two keys per action, each of which can be a combination with Ctrl / Shift / Alt
 * (see KeyCombos). Opened instead of the game's own (FzHud.onGuiOpen); it looks like the other game screens.
 * Click a key, then press the key (or the combination, or a mouse button). A modifier on its own is bound when it
 * is let go without another key. Esc clears the key.
 */
public class KeyBindScreen extends GuiScreen {
    private static final GameSettings.Options[] OPTIONS = {GameSettings.Options.INVERT_MOUSE,
            GameSettings.Options.SENSITIVITY, GameSettings.Options.TOUCHSCREEN};
    private static final int KEY_W = 75, RESET_W = 46, GAP = 4;

    private final GuiScreen parent;
    private KeyList list;
    private GuiButton resetAll;

    /** The key being set: the action and which of its two keys, or null. */
    private KeyBinding editing;
    private int editSlot;
    /** The last modifier pressed while setting (bound on its own if let go before any other key). */
    private int pendingModifier;

    public KeyBindScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        buttonList.clear();
        for (int i = 0; i < OPTIONS.length; i++) {
            GameSettings.Options o = OPTIONS[i];
            int x = width / 2 - 155 + i % 2 * 160, y = 18 + 24 * (i >> 1);
            if (o.getEnumFloat()) buttonList.add(new GuiOptionSlider(o.returnEnumOrdinal(), x, y, o));
            else buttonList.add(new GuiOptionButton(o.returnEnumOrdinal(), x, y, o, mc.gameSettings.getKeyBinding(o)));
        }
        buttonList.add(new GuiButton(200, width / 2 - 155, height - 29, 150, 20, I18n.format("gui.done")));
        buttonList.add(resetAll = new GuiButton(201, width / 2 - 155 + 160, height - 29, 150, 20, I18n.format("controls.resetAll")));
        list = new KeyList(mc);
    }

    @Override
    protected void actionPerformed(GuiButton b) throws IOException {
        if (b.id == 200) {
            mc.displayGuiScreen(parent);
        } else if (b.id == 201) {
            for (KeyBinding kb : mc.gameSettings.keyBindings) KeyCombos.reset(kb);
        } else if (b.id < 100 && b instanceof GuiOptionButton) {
            mc.gameSettings.setOptionValue(((GuiOptionButton) b).returnEnumOptions(), 1);
            b.displayString = mc.gameSettings.getKeyBinding(GameSettings.Options.getEnumOptions(b.id));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        list.drawScreen(mouseX, mouseY, partialTicks);
        drawCenteredString(fontRendererObj, I18n.format("controls.title"), width / 2, 8, 0xFFFFFF);
        String hint = editing != null
                ? Lang.t("キーを押してください（Ctrl・Shift・Alt と組み合わせ可・マウスのボタンも可・Esc で解除）")
                : Lang.t("キーをクリックして設定・1 つの操作に 2 つまで（例: Alt + X）");
        drawCenteredString(fontRendererObj, hint, width / 2, height - 42, editing != null ? 0xFFFF55 : 0xA0A0A0);
        boolean anyCustom = false;
        for (KeyBinding kb : mc.gameSettings.keyBindings) {
            if (!KeyCombos.isDefault(kb)) {
                anyCustom = true;
                break;
            }
        }
        resetAll.enabled = anyCustom;
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        list.handleMouseInput();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (editing != null) {
            assign(button - 100, KeyCombos.held(0));
            return;
        }
        if (button != 0 || !list.mouseClicked(mouseX, mouseY, button)) super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state != 0 || !list.mouseReleased(mouseX, mouseY, state)) super.mouseReleased(mouseX, mouseY, state);
    }

    @Override
    public void handleKeyboardInput() throws IOException {
        if (editing == null) {
            super.handleKeyboardInput();
            return;
        }
        int key = Keyboard.getEventKey() == 0 ? Keyboard.getEventCharacter() + 256 : Keyboard.getEventKey();
        boolean down = Keyboard.getEventKeyState();
        if (down) {
            if (key == Keyboard.KEY_ESCAPE) assign(0, 0);
            else if (KeyCombos.isModifier(key)) pendingModifier = key;
            else assign(key, KeyCombos.held(key));
        } else if (key == pendingModifier) {
            assign(key, KeyCombos.held(key));   // a modifier on its own (e.g. sneak on Shift)
        }
    }

    private void start(KeyBinding kb, int slot) {
        editing = kb;
        editSlot = slot;
        pendingModifier = 0;
    }

    private void assign(int key, int mods) {
        KeyBinding kb = editing;
        editing = null;
        pendingModifier = 0;
        if (kb != null) KeyCombos.set(kb, editSlot, key, mods);
    }

    /** The text on a key button: the key, "> ... <" while it is being set, red when another action has it too. */
    private String label(KeyBinding kb, int slot) {
        if (editing == kb && editSlot == slot) {
            String pending = KeyCombos.modsName(KeyCombos.held(0)) + "...";
            return EnumChatFormatting.WHITE + "> " + EnumChatFormatting.YELLOW + pending + EnumChatFormatting.WHITE + " <";
        }
        int key = KeyCombos.key(kb, slot), mods = KeyCombos.mods(kb, slot);
        String s = KeyCombos.name(key, mods);
        if (key != 0 && taken(kb, slot, key, mods)) s = EnumChatFormatting.RED + s;
        return s;
    }

    private boolean taken(KeyBinding self, int selfSlot, int key, int mods) {
        for (KeyBinding kb : mc.gameSettings.keyBindings) {
            for (int s = 0; s < 2; s++) {
                if (kb == self && s == selfSlot) continue;
                if (KeyCombos.key(kb, s) == key && KeyCombos.mods(kb, s) == mods) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ the list

    private class KeyList extends GuiListExtended {
        private final List<IGuiListEntry> rows = new ArrayList<IGuiListEntry>();
        private final int listW;

        KeyList(Minecraft mc) {
            super(mc, KeyBindScreen.this.width, KeyBindScreen.this.height, 63, KeyBindScreen.this.height - 48, 20);
            KeyBinding[] all = Arrays.copyOf(mc.gameSettings.keyBindings, mc.gameSettings.keyBindings.length);
            Arrays.sort(all);
            String category = null;
            int labelW = 0;
            for (KeyBinding kb : all) {
                if (!kb.getKeyCategory().equals(category)) {
                    category = kb.getKeyCategory();
                    rows.add(new Category(I18n.format(category)));
                }
                rows.add(new Key(kb));
                labelW = Math.max(labelW, mc.fontRendererObj.getStringWidth(I18n.format(kb.getKeyDescription())));
            }
            int buttons = KEY_W * 2 + RESET_W + GAP * 2;
            listW = Math.max(300, Math.min(KeyBindScreen.this.width - 30, labelW + 16 + buttons));
        }

        @Override
        protected int getSize() {
            return rows.size();
        }

        @Override
        public IGuiListEntry getListEntry(int i) {
            return rows.get(i);
        }

        @Override
        public int getListWidth() {
            return listW;
        }

        @Override
        protected int getScrollBarX() {
            return KeyBindScreen.this.width / 2 + listW / 2 + 4;
        }
    }

    private class Category implements GuiListExtended.IGuiListEntry {
        private final String name;

        Category(String name) {
            this.name = name;
        }

        @Override
        public void drawEntry(int slot, int x, int y, int w, int h, int mouseX, int mouseY, boolean selected) {
            fontRendererObj.drawString(name, KeyBindScreen.this.width / 2 - fontRendererObj.getStringWidth(name) / 2,
                    y + h - fontRendererObj.FONT_HEIGHT - 1, 0xFFFFFF);
        }

        @Override
        public boolean mousePressed(int slot, int x, int y, int button, int relX, int relY) {
            return false;
        }

        @Override
        public void mouseReleased(int slot, int x, int y, int button, int relX, int relY) {}

        @Override
        public void setSelected(int slot, int x, int y) {}
    }

    private class Key implements GuiListExtended.IGuiListEntry {
        private final KeyBinding kb;
        private final String name;
        private final GuiButton first = new GuiButton(0, 0, 0, KEY_W, 18, "");
        private final GuiButton second = new GuiButton(0, 0, 0, KEY_W, 18, "");
        private final GuiButton reset = new GuiButton(0, 0, 0, RESET_W, 18, I18n.format("controls.reset"));

        Key(KeyBinding kb) {
            this.kb = kb;
            this.name = I18n.format(kb.getKeyDescription());
        }

        @Override
        public void drawEntry(int slot, int x, int y, int w, int h, int mouseX, int mouseY, boolean selected) {
            int bx = x + w - (KEY_W * 2 + RESET_W + GAP * 2) - 4;
            String shown = name;
            int room = bx - x - 6;
            if (fontRendererObj.getStringWidth(shown) > room) shown = fontRendererObj.trimStringToWidth(shown, room - 6) + "…";
            fontRendererObj.drawString(shown, x + 2, y + h / 2 - fontRendererObj.FONT_HEIGHT / 2, 0xFFFFFF);
            first.xPosition = bx;
            second.xPosition = bx + KEY_W + GAP;
            reset.xPosition = bx + (KEY_W + GAP) * 2;
            first.yPosition = second.yPosition = reset.yPosition = y;
            first.displayString = label(kb, 0);
            second.displayString = label(kb, 1);
            reset.enabled = !KeyCombos.isDefault(kb);
            first.drawButton(mc, mouseX, mouseY);
            second.drawButton(mc, mouseX, mouseY);
            reset.drawButton(mc, mouseX, mouseY);
        }

        @Override
        public boolean mousePressed(int slot, int x, int y, int button, int relX, int relY) {
            if (first.mousePressed(mc, x, y)) {
                first.playPressSound(mc.getSoundHandler());
                start(kb, 0);
                return true;
            }
            if (second.mousePressed(mc, x, y)) {
                second.playPressSound(mc.getSoundHandler());
                start(kb, 1);
                return true;
            }
            if (reset.mousePressed(mc, x, y)) {
                reset.playPressSound(mc.getSoundHandler());
                KeyCombos.reset(kb);
                return true;
            }
            return false;
        }

        @Override
        public void mouseReleased(int slot, int x, int y, int button, int relX, int relY) {
            first.mouseReleased(x, y);
            second.mouseReleased(x, y);
            reset.mouseReleased(x, y);
        }

        @Override
        public void setSelected(int slot, int x, int y) {}
    }
}
