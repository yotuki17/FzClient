package dev.fzkc.fzhud.chat;

import dev.fzkc.fzhud.util.Lang;

import dev.fzkc.fzhud.Modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Chat translator.
 *  - incoming: a clickable [訳] after foreign messages, or automatic translation (shown on the next line)
 *  - outgoing: /tr [lang] text  translates and sends it
 * Requests run on a background thread; the game never waits for the network.
 */
public final class Translator {

    // language list shared by the settings (index -> codes)
    public static final String[] LANG_NAMES = {"日本語", "English", "한국어", "中文", "Español", "Português", "Русский", "Deutsch", "Français"};
    private static final String[] GOOGLE = {"ja", "en", "ko", "zh-CN", "es", "pt", "ru", "de", "fr"};
    private static final String[] DEEPL = {"JA", "EN-US", "KO", "ZH", "ES", "PT-BR", "RU", "DE", "FR"};

    /** One worker, small queue: if chat floods, extra requests are simply dropped. */
    private static final ThreadPoolExecutor POOL = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> {
                Thread t = new Thread(r, "FzClient-Translator");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.DiscardPolicy());

    /** Recent results, so the same line is never sent twice. */
    private static final Map<String, String> CACHE = new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 300; }
    };
    /** Messages waiting for their [訳] button to be clicked. */
    private static final Map<Integer, String[]> PENDING = new LinkedHashMap<Integer, String[]>(64, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, String[]> e) { return size() > 200; }
    };
    private static int nextId = 1;

    // ============================================================ incoming

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onChat(ClientChatReceivedEvent event) {
        if (!Modules.translatorEnabled() || event.isCanceled() || event.type == 2 || event.message == null) return;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (plain == null) return;

        // "[MVP+] Name: hello" -> prefix "[MVP+] Name: ", body "hello"
        String prefix = "", body = plain.trim();
        int colon = plain.indexOf(": ");
        if (colon > 0 && colon < 70) {
            prefix = plain.substring(0, colon + 2);
            body = plain.substring(colon + 2).trim();
        } else if (Modules.trScope.get() == 0) {
            return; // only player chat
        }
        if (!worthTranslating(body)) return;

        if (Modules.trMode.get() == 1) {
            translateAsync(body, target(), prefix, false);
        } else {
            int id;
            synchronized (PENDING) {
                id = nextId++;
                PENDING.put(id, new String[]{prefix, body});
            }
            ChatComponentText button = new ChatComponentText(Lang.t(" [訳]"));
            ChatStyle style = new ChatStyle().setColor(EnumChatFormatting.AQUA)
                    .setChatClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/fztranslate " + id))
                    .setChatHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                            new ChatComponentText(Lang.t("クリックで") + Lang.t(LANG_NAMES[Modules.trTarget.get()]) + Lang.t("に翻訳"))));
            button.setChatStyle(style);
            IChatComponent msg = event.message.createCopy();
            msg.appendSibling(button);
            event.message = msg;
        }
    }

    /** Skips empty / symbol-only lines and text that is already in the target language. */
    private static boolean worthTranslating(String s) {
        if (s.length() < 2) return false;
        int letters = 0, kana = 0, hangul = 0, han = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) letters++;
            if (c >= 0x3040 && c <= 0x30FF) kana++;
            else if (c >= 0xAC00 && c <= 0xD7AF) hangul++;
            else if (c >= 0x4E00 && c <= 0x9FFF) han++;
        }
        if (letters < 2) return false;
        switch (Modules.trTarget.get()) {
            case 0: return kana == 0 && han * 2 < letters;   // Japanese
            case 2: return hangul * 2 < letters;              // Korean
            case 3: return han * 2 < letters || kana > 0;     // Chinese
            default: return true;                             // let the service detect it
        }
    }

    private static String target() {
        return code(Modules.trTarget.get());
    }

    private static String code(int index) {
        index = Math.max(0, Math.min(LANG_NAMES.length - 1, index));
        return Modules.trProvider.get() == 1 ? DEEPL[index] : GOOGLE[index];
    }

    /** Language code typed in /tr (en, ja, ko, zh, es, pt, ru, de, fr) -> list index, or -1. */
    private static int parseLang(String s) {
        String l = s.toLowerCase();
        switch (l) {
            case "zh":
            case "cn":
                return 3;
            case "jp":
                return 0;
            case "kr":
                return 2;
        }
        for (int i = 0; i < GOOGLE.length; i++) {
            if (GOOGLE[i].equalsIgnoreCase(l) || DEEPL[i].equalsIgnoreCase(l)) return i;
        }
        return -1;
    }

    private static void translateAsync(final String text, final String lang, final String prefix, final boolean manual) {
        final String key = Modules.trProvider.get() + "|" + lang + "|" + text;
        String cached;
        synchronized (CACHE) {
            cached = CACHE.get(key);
        }
        if (cached != null) {
            show(prefix, cached);
            return;
        }
        POOL.execute(() -> {
            try {
                String result = request(text, lang);
                if (result == null) {
                    if (manual) error(Lang.t("同じ言語なので翻訳しませんでした"));
                    return;
                }
                synchronized (CACHE) {
                    CACHE.put(key, result);
                }
                show(prefix, result);
            } catch (Exception e) {
                if (manual) error(Lang.t("翻訳できませんでした: ") + e.getMessage());
            }
        });
    }

    private static void show(final String prefix, final String translated) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null) return;
            ChatComponentText line = new ChatComponentText(EnumChatFormatting.AQUA + Lang.t("[訳] ")
                    + EnumChatFormatting.GRAY + prefix + EnumChatFormatting.WHITE + translated);
            mc.ingameGUI.getChatGUI().printChatMessage(line);
        });
    }

    private static void error(final String msg) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null) {
                mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(EnumChatFormatting.RED + Lang.t("[翻訳] ") + msg));
            }
        });
    }

    // ============================================================ services

    /** Returns the translation, or null when the text is already in the target language. */
    private static String request(String text, String lang) throws Exception {
        if (Modules.trProvider.get() == 1) return deepl(text, lang);
        return google(text, lang);
    }

    private static String google(String text, String lang) throws Exception {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&dt=t&tl="
                + URLEncoder.encode(lang, "UTF-8") + "&q=" + URLEncoder.encode(text, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0");
        String body = read(c);
        JsonArray root = new JsonParser().parse(body).getAsJsonArray();
        if (root.size() > 2 && root.get(2).isJsonPrimitive()) {
            String src = root.get(2).getAsString();
            if (lang.toLowerCase().startsWith(src.toLowerCase())) return null;
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement seg : root.get(0).getAsJsonArray()) {
            JsonArray a = seg.getAsJsonArray();
            if (a.size() > 0 && a.get(0).isJsonPrimitive()) sb.append(a.get(0).getAsString());
        }
        return sb.toString().trim();
    }

    private static String deepl(String text, String lang) throws Exception {
        String key = Modules.trDeeplKey.get().trim();
        if (key.isEmpty()) throw new IllegalStateException(Lang.t("DeepLのAPIキーが設定されていません"));
        String host = key.endsWith(":fx") ? "https://api-free.deepl.com" : "https://api.deepl.com";
        HttpURLConnection c = (HttpURLConnection) new URL(host + "/v2/translate").openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "DeepL-Auth-Key " + key);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] form = ("text=" + URLEncoder.encode(text, "UTF-8") + "&target_lang=" + lang).getBytes(StandardCharsets.UTF_8);
        OutputStream out = c.getOutputStream();
        out.write(form);
        out.close();
        JsonObject root = new JsonParser().parse(read(c)).getAsJsonObject();
        JsonObject t = root.getAsJsonArray("translations").get(0).getAsJsonObject();
        String src = t.has("detected_source_language") ? t.get("detected_source_language").getAsString() : "";
        if (!src.isEmpty() && lang.toUpperCase().startsWith(src.toUpperCase())) return null;
        return t.get("text").getAsString().trim();
    }

    private static String read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        if (code == 403 || code == 401) throw new IllegalStateException(Lang.t("APIキーが正しくありません (") + code + ")");
        if (code == 456) throw new IllegalStateException(Lang.t("DeepLの今月の上限に達しました"));
        if (code == 429) throw new IllegalStateException(Lang.t("翻訳の回数が多すぎます。少し待ってください"));
        if (code != 200) throw new IllegalStateException("HTTP " + code);
        InputStream in = c.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int n;
        while ((n = in.read(b)) > 0) buf.write(b, 0, n);
        in.close();
        return buf.toString("UTF-8");
    }

    // ============================================================ commands

    private abstract static class ClientCommand extends CommandBase {
        @Override public int getRequiredPermissionLevel() { return 0; }
        @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }
    }

    /** Clicked from a [訳] button. */
    public static class ButtonCommand extends ClientCommand {
        @Override public String getCommandName() { return "fztranslate"; }
        @Override public String getCommandUsage(ICommandSender sender) { return "/fztranslate <id>"; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (args.length < 1) return;
            String[] entry;
            try {
                synchronized (PENDING) {
                    entry = PENDING.get(Integer.parseInt(args[0]));
                }
            } catch (NumberFormatException e) {
                return;
            }
            if (entry == null) {
                error(Lang.t("古いメッセージなので翻訳できません"));
                return;
            }
            translateAsync(entry[1], target(), entry[0], true);
        }
    }

    /** /tr [lang] text : translate your own message and send it. */
    public static class SendCommand extends ClientCommand {
        @Override public String getCommandName() { return "tr"; }
        @Override public String getCommandUsage(ICommandSender sender) { return Lang.t("/tr [en|ja|ko|zh|...] メッセージ"); }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            if (!Modules.translatorEnabled()) {
                error(Lang.t("Chat の「翻訳」がOFFです（右シフト → チャット → Chat）"));
                return;
            }
            if (args.length == 0) {
                error(Lang.t("使い方: /tr [言語] メッセージ   例: /tr en こんにちは"));
                return;
            }
            int lang = parseLang(args[0]);
            int from = 0;
            if (lang >= 0 && args.length > 1) from = 1;
            else lang = Modules.trSendLang.get();
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < args.length; i++) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(args[i]);
            }
            final String text = sb.toString();
            final String code = code(lang);
            POOL.execute(() -> {
                try {
                    String result = request(text, code);
                    final String out = result == null ? text : result;
                    Minecraft.getMinecraft().addScheduledTask(() -> {
                        Minecraft mc = Minecraft.getMinecraft();
                        if (mc.thePlayer == null) return;
                        String msg = out.length() > 100 ? out.substring(0, 100) : out;
                        mc.thePlayer.sendChatMessage(msg);
                    });
                } catch (Exception e) {
                    error(Lang.t("翻訳できませんでした: ") + e.getMessage());
                }
            });
        }
    }
}
