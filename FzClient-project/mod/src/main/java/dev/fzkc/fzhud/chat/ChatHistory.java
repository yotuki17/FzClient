package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.util.Lang;

import dev.fzkc.fzhud.Modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chat and command history that survives:
 *  - going back to the main menu / switching servers (vanilla wipes both)
 *  - restarting the game (only the sent messages for the ↑ key)
 *  - the vanilla 100-line limit: the whole session stays in the chat box to scroll back through
 * Also writes every chat line to a text file per day, and /fzhistory lists past commands to click on.
 */
public final class ChatHistory {

    private static final Charset UTF8 = StandardCharsets.UTF_8;
    private static final int MAX_SENT = 300;

    /** Used by MixinMinecraft: whether the main menu may wipe the chat. */
    public static boolean keepOnMenu() {
        return Modules.chatHistoryEnabled() && Modules.historyKeep.get();
    }

    private static File dir() {
        return new File(Minecraft.getMinecraft().mcDataDir, "fzclient");
    }

    private static File logDir() {
        return new File(Minecraft.getMinecraft().mcDataDir, "logs/fzclient-chat");
    }

    // ============================================================ scrollback

    /**
     * Used by MixinGuiNewChat: how many chat lines the chat box keeps while the game runs (vanilla 100), so you can
     * scroll far back through everything said in this session. Nothing of it is kept for the next launch.
     */
    public static int maxLines() {
        return Modules.chatHistoryEnabled() ? Math.max(100, (int) Modules.historyScroll.get()) : 100;
    }

    // ============================================================ sent messages (Up key) for the next launch

    private static boolean restored;
    private static long nextSave;
    private static int savedHash;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI == null) return;
        // restore once the main menu has done its own chat wipe
        if (!restored && (mc.currentScreen instanceof GuiMainMenu || mc.currentScreen instanceof dev.fzkc.fzhud.gui.FzMainMenu)) {
            restored = true;
            restore();
            Runtime.getRuntime().addShutdownHook(new Thread(ChatHistory::save, "FzClient-ChatSave"));
        }
        long now = System.currentTimeMillis();
        if (restored && now > nextSave) {
            nextSave = now + 60000L;
            int h = mc.ingameGUI.getChatGUI().getSentMessages().hashCode();
            if (h != savedHash) {
                savedHash = h;
                final JsonObject data = snapshot();
                IO.execute(() -> write(data));
            }
        }
    }

    /** Current state as JSON (called on the game thread). */
    private static JsonObject snapshot() {
        JsonObject root = new JsonObject();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI == null) return root;
        GuiNewChat chat = mc.ingameGUI.getChatGUI();
        JsonArray sent = new JsonArray();
        List<String> s = new ArrayList<>(chat.getSentMessages());
        for (int i = Math.max(0, s.size() - MAX_SENT); i < s.size(); i++) sent.add(new com.google.gson.JsonPrimitive(s.get(i)));
        root.add("sent", sent);
        return root;
    }

    private static synchronized void write(JsonObject data) {
        try {
            File d = dir();
            d.mkdirs();
            File tmp = new File(d, "history.json.tmp"), f = new File(d, "history.json");
            Writer w = new OutputStreamWriter(Files.newOutputStream(tmp.toPath()), UTF8);
            w.write(data.toString());
            w.close();
            if (f.exists()) f.delete();
            tmp.renameTo(f);
        } catch (Throwable ignored) {
        }
    }

    private static void save() {
        try {
            if (!Modules.chatHistoryEnabled() || !Modules.historySent.get()) return;
            write(snapshot());
        } catch (Throwable ignored) {
        }
    }

    private static void restore() {
        if (!Modules.chatHistoryEnabled() || !Modules.historySent.get()) return;
        File f = new File(dir(), "history.json");
        if (!f.exists()) return;
        try {
            Reader r = new InputStreamReader(Files.newInputStream(f.toPath()), UTF8);
            JsonObject root;
            try {
                root = new JsonParser().parse(r).getAsJsonObject();
            } finally {
                r.close();
            }
            GuiNewChat chat = Minecraft.getMinecraft().ingameGUI.getChatGUI();
            if (root.has("sent") && chat.getSentMessages().isEmpty()) {
                for (JsonElement e : root.getAsJsonArray("sent")) chat.addToSentMessages(e.getAsString());
            }
        } catch (Throwable ignored) {
        }
    }

    // ============================================================ daily log files

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FzClient-ChatLog");
        t.setDaemon(true);
        return t;
    });
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd");
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss");

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.isCanceled() || event.type == 2 || event.message == null) return;
        if (!Modules.chatHistoryEnabled() || !Modules.historyLog.get()) return;
        String text = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (text == null || text.trim().isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        ServerData sd = mc.getCurrentServerData();
        final String where = mc.isSingleplayer() ? "singleplayer" : sd != null && sd.serverIP != null ? sd.serverIP : "?";
        final Date now = new Date();
        final String line = "[" + TIME.format(now) + "] [" + where + "] " + text;
        final String day = DAY.format(now);
        LOG.add(new String[]{day, line});
        IO.execute(ChatHistory::writeLog);
    }

    /** Chat lines waiting to be written: {day, line}. */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String[]> LOG = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Writes every waiting line, opening the day's file once for a whole burst of chat instead of once per line
     * (runs on the single log thread, so only one of these runs at a time).
     */
    private static void writeLog() {
        String[] e;
        while ((e = LOG.poll()) != null) {
            String day = e[0];
            Writer w = null;
            try {
                File d = logDir();
                d.mkdirs();
                w = new OutputStreamWriter(new FileOutputStream(new File(d, day + ".log"), true), UTF8);
                while (true) {
                    w.write(e[1]);
                    w.write(System.lineSeparator());
                    String[] next = LOG.peek();
                    if (next == null || !next[0].equals(day)) break;
                    e = LOG.poll();
                }
            } catch (Throwable ignored) {
            } finally {
                if (w != null) try { w.close(); } catch (Throwable ignored) {}
            }
        }
    }

    // ============================================================ commands

    private abstract static class ClientCommand extends CommandBase {
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }
    }

    /** /fzhistory [chat] : the last 15 commands (or chat messages) you sent; click one to put it back in the chat box. */
    public static class HistoryCommand extends ClientCommand {
        @Override public String getCommandName() { return "fzhistory"; }
        @Override public String getCommandUsage(ICommandSender sender) { return "/fzhistory [chat]"; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            final boolean chatMode = args.length > 0 && args[0].equalsIgnoreCase("chat");
            // run after this command itself has been added to the history
            Minecraft.getMinecraft().addScheduledTask(() -> list(chatMode));
        }

        private static void list(boolean chatMode) {
            GuiNewChat chat = Minecraft.getMinecraft().ingameGUI.getChatGUI();
            List<String> sent = chat.getSentMessages();
            List<String> pick = new ArrayList<>();
            for (int i = sent.size() - 1; i >= 0 && pick.size() < 15; i--) {
                String s = sent.get(i);
                if (s.startsWith("/fzhistory")) continue;
                if (chatMode != s.startsWith("/") && !pick.contains(s)) pick.add(s);
            }
            chat.printChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] " + EnumChatFormatting.GRAY
                    + (chatMode ? Lang.t("最近送ったメッセージ") : Lang.t("最近使ったコマンド")) + Lang.t("（クリックでチャット欄に入れる）")));
            if (pick.isEmpty()) {
                chat.printChatMessage(new ChatComponentText(EnumChatFormatting.DARK_GRAY + Lang.t("  まだありません")));
                return;
            }
            for (int i = pick.size() - 1; i >= 0; i--) {
                String s = pick.get(i);
                ChatComponentText line = new ChatComponentText(EnumChatFormatting.DARK_GRAY + "  ▸ " + EnumChatFormatting.WHITE + s);
                line.setChatStyle(new ChatStyle()
                        .setChatClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, s))
                        .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ChatComponentText(Lang.t("クリックでチャット欄に入れる")))));
                chat.printChatMessage(line);
            }
        }
    }

    /** /chatlog : opens the folder with the daily chat logs. */
    public static class LogCommand extends ClientCommand {
        @Override public String getCommandName() { return "chatlog"; }
        @Override public String getCommandUsage(ICommandSender sender) { return "/chatlog"; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            final File d = logDir();
            d.mkdirs();
            ChatComponentText msg = new ChatComponentText(EnumChatFormatting.AQUA + "[FzClient] " + EnumChatFormatting.GRAY + Lang.t("チャットのログ: "));
            ChatComponentText open = new ChatComponentText(EnumChatFormatting.AQUA + Lang.t("[フォルダを開く]"));
            open.setChatStyle(new ChatStyle().setChatClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, d.getAbsolutePath())));
            msg.appendSibling(open);
            Minecraft.getMinecraft().ingameGUI.getChatGUI().printChatMessage(msg);
        }
    }
}
