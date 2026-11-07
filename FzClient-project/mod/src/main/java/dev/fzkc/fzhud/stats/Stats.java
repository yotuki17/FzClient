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

    private Object lastWorld;
    private int askIn = -1, ticks;
    private long askedAt;

    public static void init() {
        importMellowKeys();
    }

    /** Writes the stats history now (game closing). */
    public static void saveHistory() { History.save(); }

    public static boolean onHypixel() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getCurrentServerData() == null || mc.getCurrentServerData().serverIP == null) return false;
        String ip = mc.getCurrentServerData().serverIP.toLowerCase(Locale.ROOT);
        return ip.contains("hypixel.net") || ip.contains("hypixel.io");
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
        // developer check without a window: -Dfzclient.statstest=<name> prints that player's stats (they land in the log)
        if (ticks == 300 && System.getProperty("fzclient.statstest") != null) {
            for (String period : new String[]{"total", "daily"}) {
                for (Game g : new Game[]{Game.BEDWARS, Game.DUELS, Game.TNT_TAG}) {
                    StatsCommand.check(System.getProperty("fzclient.statstest"), g, History.Period.byName(period));
                }
            }
        }
        if (mc.theWorld != lastWorld) {
            lastWorld = mc.theWorld;
            current = null;
            lobby = true;
            askIn = mc.theWorld != null && Modules.stats.enabled && onHypixel() ? 25 : -1;
        }
        if (askIn > 0 && --askIn == 0 && mc.thePlayer != null) {
            askedAt = System.currentTimeMillis();
            mc.thePlayer.sendChatMessage("/locraw");
        }
        if (++ticks % 20 != 0 || mc.thePlayer == null || mc.thePlayer.sendQueue == null || !tabActive()) return;
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
        if (System.currentTimeMillis() - askedAt > 15_000L) return;   // someone typed /locraw themselves: show it
        event.setCanceled(true);
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
                || Modules.statsXadiaKey.get().isEmpty();
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
