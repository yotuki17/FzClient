package dev.fzkc.fzhud.stats;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.fzkc.fzhud.Config;
import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.config.TextSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Knows which Hypixel game you are in (asks the server with /locraw after each world change and hides the
 * answer) and keeps the stats and tags of the players in your tab list loaded, so they are ready when Tab opens.
 */
public final class Stats {
    /** The game you are in, or null (another server, or a game FzClient has no stats for). */
    public static volatile Game current;
    /** True in a lobby (the stats shown there are for the lobby's game). */
    public static volatile boolean lobby = true;

    private Object lastWorld, lastHandler;
    private int askIn = -1, ticks, retries;
    private long askedAt;
    private boolean answered;
    private long worldAt, appliedAt;
    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger("FzClient-Stats");
    /** Set by /refresh: ask /locraw again on the next tick. */
    private static volatile boolean askAgain;

    /**
     * Fixes an overlay that shows nothing or errors: forgets errors, pauses and old answers, fetches everyone in
     * the tab list again and asks the server again which game this is. Used by /refresh and after a reconnect.
     */
    public static void refresh() {
        StatsApi.refresh();
        Tags.refresh();
        Sessions.refresh();
        PingHistory.refresh();
        askAgain = true;
    }

    public static void init() {
        importMellowKeys();
        ModApiLocation.init();
    }

    /** Writes the stats history now (game closing). */
    public static void saveHistory() { History.save(); }

    public static boolean onHypixel() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getCurrentServerData() == null || mc.getCurrentServerData().serverIP == null) return false;
        String ip = mc.getCurrentServerData().serverIP.toLowerCase(Locale.ROOT);
        // hypixel.net, and proxies / boosters such as Overlag (mc.hypixel.fast, booster.hypixel.fast)
        if (ip.contains("hypixel")) return true;
        // any other address that leads to Hypixel: the server says so in its brand
        String brand = mc.thePlayer == null ? null : mc.thePlayer.getClientBrand();
        return brand != null && brand.toLowerCase(Locale.ROOT).contains("hypixel");
    }

    /** Stats are shown in the tab list right now. */
    public static boolean tabActive() {
        return Modules.stats.enabled && Modules.statsTab.get() && current != null && onHypixel()
                && (!lobby || Modules.statsTabLobby.get());
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        History.tick();
        net.minecraft.client.gui.GuiScreen pv = StatsCommand.pendingScreen;
        if (pv != null && mc.currentScreen == null) {
            StatsCommand.pendingScreen = null;
            mc.displayGuiScreen(pv);
        }
        // developer check without a window: -Dfzclient.statstest=<name> prints that player's stats (they land in the log)
        if (ticks == 300 && System.getProperty("fzclient.statstest") != null) {
            for (String period : new String[]{"total", "daily"}) {
                for (Game g : new Game[]{Game.BEDWARS, Game.DUELS, Game.TNT_TAG}) {
                    StatsCommand.check(System.getProperty("fzclient.statstest"), g, History.Period.byName(period));
                }
            }
        }
        // developer check of the /pv window: -Dfzclient.pvtest=<name>[,<game>,<mode>] opens it twice (Bed Wars
        // overall, then the given game and mode) and saves a screenshot of each
        String pvtest = System.getProperty("fzclient.pvtest");
        if (pvtest != null && (ticks == 200 || ticks == 330)) {
            String[] a = pvtest.split(",");
            Game g = ticks == 330 && a.length > 1 ? Game.byName(a[1]) : Game.BEDWARS;
            Game.Mode m = ticks == 330 && a.length > 2 && g != null ? g.mode(a[2]) : null;
            mc.displayGuiScreen(new dev.fzkc.fzhud.gui.StatsScreen(a[0], g == null ? Game.BEDWARS : g, m, History.Period.TOTAL));
        }
        if (pvtest != null && (ticks == 320 || ticks == 560)) {
            net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.mcDataDir, "pvtest" + ticks + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        }
        // a new connection (reconnect, or joining again after being kicked): start from fresh answers
        Object handler = mc.getNetHandler();
        if (handler != null && handler != lastHandler) {
            if (lastHandler != null) refresh();
            lastHandler = handler;
        }
        if (mc.theWorld != lastWorld) {
            lastWorld = mc.theWorld;
            current = null;
            lobby = true;
            Denick.worldChanged();
            retries = 0;
            worldAt = System.currentTimeMillis();
            askIn = mc.theWorld != null && Modules.stats.enabled && onHypixel() ? 25 : -1;
            // the Hypixel Mod API's location can arrive just before the world does
            if (ModApiLocation.at > worldAt - 3000) appliedAt = 0;
        }
        long locAt = ModApiLocation.at;
        if (locAt > 0 && locAt != appliedAt && mc.theWorld != null && onHypixel()) {
            appliedAt = locAt;
            String type = ModApiLocation.type, mode = ModApiLocation.mode;
            lobby = ModApiLocation.lobbyName != null || mode == null || "lobby".equalsIgnoreCase(mode);
            current = game(type, mode);
            answered = true;
            askIn = -1;   // no /locraw needed
            if (mc.thePlayer != null && Modules.statsHistory.get()) StatsApi.get(mc.thePlayer.getUniqueID(), mc.thePlayer.getName());
        }
        if (askAgain) {
            askAgain = false;
            if (mc.theWorld != null && Modules.stats.enabled && onHypixel()) {
                retries = 0;
                if (askIn <= 0) askIn = 5;   // a world that just loaded asks by itself
            }
        }
        if (askIn > 0 && --askIn == 0 && mc.thePlayer != null) {
            askedAt = System.currentTimeMillis();
            answered = false;
            LOG.info("Asking /locraw");
            mc.thePlayer.sendChatMessage("/locraw");
        }
        // no answer (the server was busy or said "too fast"): ask again, twice at most
        if (askIn <= 0 && !answered && askedAt > 0 && retries < 2 && mc.thePlayer != null && Modules.stats.enabled && onHypixel()
                && System.currentTimeMillis() - askedAt > 5000) {
            retries++;
            askIn = 1;
            askedAt = 0;
        }
        if (++ticks % 20 != 0 || mc.thePlayer == null || mc.thePlayer.sendQueue == null) return;
        Denick.tick();
        if (!tabActive()) return;
        // keep everyone in the tab list loaded (once a second; cached results make this cheap)
        List<UUID> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (NetworkPlayerInfo info : mc.thePlayer.sendQueue.getPlayerInfoMap()) {
            UUID id = info.getGameProfile().getId();
            if (id == null || id.version() != 4) continue;   // NPCs and server-made entries
            ids.add(id);
            names.add(info.getGameProfile().getName());
            if (ids.size() >= 100) break;
        }
        StatsApi.prefetch(ids, names);
        Tags.prefetch(ids);
    }

    /** Catches the answer to our /locraw (a line of JSON) before it reaches the chat. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.type == 2) return;
        String s = event.message.getUnformattedText();
        if (s == null || !s.startsWith("{\"server\":")) return;
        LOG.info("locraw answer: " + s + (System.currentTimeMillis() - askedAt > 15_000L ? " (not ours)" : ""));
        if (System.currentTimeMillis() - askedAt > 15_000L) return;   // someone typed /locraw themselves: show it
        event.setCanceled(true);
        answered = true;
        try {
            JsonObject o = new JsonParser().parse(s).getAsJsonObject();
            String type = PlayerData.str(o, "gametype"), mode = PlayerData.str(o, "mode");
            lobby = PlayerData.str(o, "lobbyname") != null || mode == null || "lobby".equalsIgnoreCase(mode);
            current = game(type, mode);
        } catch (Exception ignored) {
        }
        // your own numbers are snapshotted on every world change, so today's stats are as exact as possible
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null && Modules.statsHistory.get()) StatsApi.get(mc.thePlayer.getUniqueID(), mc.thePlayer.getName());
    }

    private static Game game(String type, String mode) {
        if (type == null) return null;
        switch (type) {
            case "BEDWARS": return Game.BEDWARS;
            case "DUELS": return Game.DUELS;
            case "MURDER_MYSTERY": return Game.MURDER;
            case "TNTGAMES":
                if ("BOWSPLEEF".equalsIgnoreCase(mode)) return Game.BOW_SPLEEF;
                if ("TNTAG".equalsIgnoreCase(mode)) return Game.TNT_TAG;
                return Game.TNT_TAG;   // the TNT Games lobby: TNT Tag is the one most people play
            default: return null;
        }
    }

    // ------------------------------------------------------------------ keys from Mellow

    /**
     * When a key is still empty, takes it from Mellow's settings (this PC, same game folder), so players who
     * already set Mellow up do not have to paste their keys again.
     */
    private static void importMellowKeys() {
        boolean any = Modules.statsHypixelKey.get().isEmpty() || Modules.statsWatcherKey.get().isEmpty()
                || Modules.statsXadiaKey.get().isEmpty() || Modules.statsCoralKey.get().isEmpty() || Modules.denickAuroraKey.get().isEmpty();
        if (!any) return;
        File dir = new File(Minecraft.getMinecraft().mcDataDir, "OneConfig");
        File[] tries = {new File(dir, "profiles/Default Profile/mellow.json"), new File(dir, "config/mellow.json")};
        boolean changed = false;
        for (File f : tries) {
            if (!f.isFile()) continue;
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                JsonObject o = new JsonParser().parse(r).getAsJsonObject();
                changed |= take(Modules.statsHypixelKey, PlayerData.str(o, "hypixelApiKey"));
                if (take(Modules.statsWatcherKey, PlayerData.str(o, "watcherKey"))) {
                    Modules.statsWatcher.value = true;
                    changed = true;
                }
                if (take(Modules.statsXadiaKey, PlayerData.str(o, "xadiaKey"))) {
                    Modules.statsXadia.value = true;
                    changed = true;
                }
                changed |= take(Modules.denickAuroraKey, PlayerData.str(o, "auroraApiKey"));
                // Mellow calls the Coral key "urchinKey" (Coral is Urchin's tag list)
                if (take(Modules.statsCoralKey, PlayerData.str(o, "urchinKey"))) {
                    Modules.statsCoral.value = true;
                    changed = true;
                }
            } catch (Exception ignored) {
            }
        }
        if (changed) Config.save();
    }

    private static boolean take(TextSetting into, String key) {
        if (key == null || !into.get().trim().isEmpty()) return false;
        key = key.trim();
        if (key.isEmpty() || key.length() > into.maxLength) return false;
        into.value = key;
        return true;
    }
}
