package dev.fzkc.fzhud.chat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Party Relay: every party chat message ("Party > [MVP+] Name: hello") is also posted to a Discord channel through
 * a webhook. A webhook belongs to ONE channel, so "which channel" is simply which webhook URL you paste in.
 * <p>
 * - Sending happens on a background thread, one message at a time, so the game never waits for the network.
 * - Mentions in the text (@everyone, @someone) are switched off, so a party member can't ping your server.
 * - Only real Discord webhook addresses are accepted.
 * - The webhook URL works like a password: anyone who has it can post in that channel. It is saved in your own
 *   config only; don't put it in shared preset codes or screenshots.
 * - /fzrelay test sends a test line so you can check the URL.
 */
public class PartyRelay {
    private static final Charset UTF8 = StandardCharsets.UTF_8;
    /** "Party > [MVP+] Name: text" (the rank in front of the name is optional). */
    private static final Pattern PARTY = Pattern.compile("^Party > (?:\\[[^\\]]+\\] )?(\\w{1,16}): (.+)$");
    private static final Pattern URL_OK = Pattern.compile(
            "^https://(?:(?:canary|ptb)\\.)?discord(?:app)?\\.com/api/(?:v\\d+/)?webhooks/\\d+/[\\w-]+$");

    /** Messages waiting to be sent: {display name, text}. Oldest are dropped if Discord is slow. */
    private static final LinkedBlockingQueue<String[]> QUEUE = new LinkedBlockingQueue<>(50);
    private static Thread worker;

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        if (!Modules.partyRelayEnabled() || event.type == 2) return;
        String plain = EnumChatFormatting.getTextWithoutFormattingCodes(event.message.getUnformattedText());
        if (plain == null) return;
        Matcher m = PARTY.matcher(dev.fzkc.fzhud.stats.ChatStats.unprefixed(plain.trim()));
        if (!m.matches()) return;
        String name = m.group(1), text = m.group(2);
        if (!Modules.relaySelf.get()) {
            String me = Minecraft.getMinecraft().getSession() == null ? "" : Minecraft.getMinecraft().getSession().getUsername();
            if (name.equalsIgnoreCase(me)) return;
        }
        enqueue(name, text);
    }

    private static boolean validUrl(String url) {
        return url != null && URL_OK.matcher(url.trim()).matches();
    }

    private static synchronized void enqueue(String name, String text) {
        if (!validUrl(Modules.relayUrl.get())) return;
        if (!QUEUE.offer(new String[]{name, text})) {
            QUEUE.poll(); // full: forget the oldest one
            QUEUE.offer(new String[]{name, text});
        }
        if (worker == null || !worker.isAlive()) {
            worker = new Thread(PartyRelay::run, "FzClient-PartyRelay");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static void run() {
        while (true) {
            try {
                String[] msg = QUEUE.take();
                String url = Modules.relayUrl.get().trim();
                if (!validUrl(url)) continue;
                int code = post(url, msg[0], msg[1]);
                if (code == 400) {
                    // Discord refuses some names (anything containing "discord", "clyde"...): send under a fixed name
                    code = post(url, "Minecraft Party", "**" + msg[0] + "**: " + msg[1]);
                }
                // Discord allows roughly 5 posts per 2 seconds on a webhook: stay well under it
                Thread.sleep(code == 429 ? 3000L : 600L);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** Posts one message; returns the HTTP status (204 = sent). */
    private static int post(String url, String username, String text) throws Exception {
        if (text.length() > 1900) text = text.substring(0, 1900) + "...";
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("content", text);
        JsonObject allowed = new JsonObject();
        allowed.add("parse", new JsonArray()); // no @everyone / @role / @user pings
        body.add("allowed_mentions", allowed);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        c.setRequestProperty("User-Agent", "FzClient/1.0 (party relay)");
        byte[] data = body.toString().getBytes(UTF8);
        try (OutputStream out = c.getOutputStream()) {
            out.write(data);
        }
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) in.close();
        return code;
    }

    // ---- /fzrelay test -----------------------------------------------------------------------------

    private static void chat(String msg) {
        final Minecraft mc = Minecraft.getMinecraft();
        final String text = EnumChatFormatting.AQUA + "[FzClient] " + EnumChatFormatting.GRAY + msg;
        mc.addScheduledTask(() -> {
            if (mc.ingameGUI != null) mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(text));
        });
    }

    public static class TestCommand extends CommandBase {
        @Override public String getCommandName() { return "fzrelay"; }

        @Override public String getCommandUsage(ICommandSender sender) { return "/fzrelay test"; }

        @Override public int getRequiredPermissionLevel() { return 0; }

        @Override public boolean canCommandSenderUseCommand(ICommandSender sender) { return true; }

        @Override
        public void processCommand(ICommandSender sender, String[] args) {
            final String url = Modules.relayUrl.get().trim();
            if (!validUrl(url)) {
                chat(dev.fzkc.fzhud.util.Lang.t("Webhook URL が正しくありません（設定の Party Relay に、DiscordのWebhook URLを入れてください）"));
                return;
            }
            chat(dev.fzkc.fzhud.util.Lang.t("テストメッセージを送っています..."));
            Thread t = new Thread(() -> {
                try {
                    int code = post(url, "FzClient", dev.fzkc.fzhud.util.Lang.t("FzClient の Party Relay のテストです。これが見えれば設定は成功です。"));
                    chat(code == 204 || code == 200 ? dev.fzkc.fzhud.util.Lang.t("送れました。Discordのチャンネルを確認してください")
                            : dev.fzkc.fzhud.util.Lang.f("送れませんでした（HTTP {0}）。URLが正しいか、Webhookが消されていないか確認してください", code));
                } catch (Throwable e) {
                    chat(dev.fzkc.fzhud.util.Lang.t("送れませんでした: ") + e);
                }
            }, "FzClient-PartyRelayTest");
            t.setDaemon(true);
            t.start();
        }
    }
}
