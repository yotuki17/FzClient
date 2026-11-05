package dev.fzkc.fzhud;

import dev.fzkc.fzhud.chat.JapaneseInput;
import dev.fzkc.fzhud.chat.NativeIme;
import dev.fzkc.fzhud.feature.Gameplay;
import dev.fzkc.fzhud.feature.Hitboxes;
import dev.fzkc.fzhud.feature.MotionBlur;
import dev.fzkc.fzhud.feature.Performance;
import dev.fzkc.fzhud.feature.RawInput;
import dev.fzkc.fzhud.feature.Nametags;
import dev.fzkc.fzhud.feature.TntTimer;
import dev.fzkc.fzhud.integration.DiscordRpc;
import dev.fzkc.fzhud.integration.DiscordVoice;

import dev.fzkc.fzhud.chat.ChatHistory;
import dev.fzkc.fzhud.chat.Translator;
import dev.fzkc.fzhud.chat.PartyRelay;
import dev.fzkc.fzhud.chat.CommandSuggest;
import dev.fzkc.fzhud.feature.Branding;
import dev.fzkc.fzhud.feature.Extras;
import dev.fzkc.fzhud.feature.Utilities;
import dev.fzkc.fzhud.integration.Spotify;
import dev.fzkc.fzhud.util.HudValues;

import dev.fzkc.fzhud.gui.EditScreen;
import dev.fzkc.fzhud.gui.Decoration;
import dev.fzkc.fzhud.gui.Glass;
import dev.fzkc.fzhud.gui.MenuScreen;
import dev.fzkc.fzhud.hud.ArmorStatus;
import dev.fzkc.fzhud.hud.ClockSpeed;
import dev.fzkc.fzhud.hud.Compass;
import dev.fzkc.fzhud.hud.Coordinates;
import dev.fzkc.fzhud.hud.CpsPanel;
import dev.fzkc.fzhud.hud.Crosshair;
import dev.fzkc.fzhud.hud.DiscordVcHud;
import dev.fzkc.fzhud.hud.HudElement;
import dev.fzkc.fzhud.hud.ItemCounter;
import dev.fzkc.fzhud.hud.Keystrokes;
import dev.fzkc.fzhud.hud.Potions;
import dev.fzkc.fzhud.hud.ServerDisplay;
import dev.fzkc.fzhud.hud.SpotifyHud;
import dev.fzkc.fzhud.hud.ScoreboardHud;
import dev.fzkc.fzhud.hud.StatPanel;
import dev.fzkc.fzhud.hud.StatusPanel;
import dev.fzkc.fzhud.hud.TargetHud;
import dev.fzkc.fzhud.hud.TabList;
import dev.fzkc.fzhud.util.Tracker;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.world.World;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraft.client.gui.GuiChat;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraft.client.gui.GuiDownloadTerrain;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.FoodStats;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.client.GuiIngameForge;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

@Mod(modid = "fzclient", name = "FzClient", useMetadata = true, clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class FzHud {
    public static final List<HudElement> elements = new ArrayList<HudElement>();
    public static float partialTicks;
    /** Set while the tab key is held (our tab list replaces the vanilla one). */
    public static boolean tabActive;
    /** Tab list open state when "toggle" mode is on. */
    public static boolean tabToggled;

    private static final ScoreboardHud scoreboard = new ScoreboardHud(0.995f, 0.30f);
    private static final TabList tab = new TabList(0.06f, 0.0f);
    private static final Crosshair crosshair = new Crosshair();

    private final KeyBinding editKey = new KeyBinding("FzClient メニュー", Keyboard.KEY_RSHIFT, "FzClient");
    private final KeyBinding toggleSneakKey = new KeyBinding("Toggle Sneak", Keyboard.KEY_NONE, "FzClient");
    private final KeyBinding imeKey = new KeyBinding("\u65e5\u672c\u8a9e\u5165\u529b\u6b04\u3092\u958b\u304f", Keyboard.KEY_F7, "FzClient");
    private boolean imePending;
    private double customTime = -1;
    private long lastTimeNanos;
    private World lastWorld;
    private final Anim chatSlide = new Anim(0f, 14f);

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        dev.fzkc.fzhud.account.Accounts.init();   // remember the launcher's account before anything switches
        elements.add(new Compass(0.5f, 0.07f));
        elements.add(new Keystrokes(0.86f, 0.005f));
        elements.add(new StatPanel("ping", "Ping", "サーバーとの通信の遅さ", 0.955f, 0.005f, 64f, Tracker::ping));
        elements.add(new StatPanel("fps", "FPS", "1秒あたりのフレーム数", 0.955f, 0.06f, 64f, Tracker::fps));
        elements.add(new StatPanel("tps", "TPS", "サーバーの処理速度（推定）", 0.955f, 0.115f, 64f, Tracker::tps));
        elements.add(new CpsPanel(0.86f, 0.13f));
        StatPanel parties = new StatPanel("parties", "Parties", "待機ロビーで見つけたパーティの数（2Q・3Q・4Q…）",
                0.86f, 0.30f, 90f, dev.fzkc.fzhud.chat.PartyDetector::hudText);
        parties.showWhen = dev.fzkc.fzhud.chat.PartyDetector::hudVisible;
        elements.add(parties);
        elements.add(new StatusPanel(0.91f, 0.18f));
        elements.add(new ClockSpeed(0.95f, 0.225f));
        elements.add(new Potions(0.01f, 0.46f));
        elements.add(new ArmorStatus(0.995f, 0.72f));
        elements.add(new ServerDisplay(0.002f, 0.975f));
        elements.add(new Coordinates(0.002f, 0.30f));
        elements.add(new SpotifyHud(0.002f, 0.90f));
        elements.add(new ItemCounter(0.995f, 0.55f));
        elements.add(new TargetHud(0.54f, 0.55f));
        elements.add(new DiscordVcHud(0.002f, 0.60f));
        StatPanel reach = new StatPanel("reach", "Reach Display", "最後に殴った時の距離", 0.5f, 0.62f, 40f, HudValues::reach, false);
        HudValues.reachUnit = reach.add(new dev.fzkc.fzhud.config.BoolSetting("unit", "「blocks」の文字を表示", true));
        HudValues.reachHideIdle = reach.add(new dev.fzkc.fzhud.config.BoolSetting("hideidle", "殴っていない時（--）は隠す", false));
        reach.showWhen = () -> !HudValues.reachHideIdle.get() || HudValues.reachFresh();
        elements.add(reach);
        elements.add(new StatPanel("combo", "Combo Counter", "相手に連続で当てた回数", 0.5f, 0.68f, 64f, HudValues::combo, false));
        elements.add(new StatPanel("memory", "Memory", "Minecraftのメモリ使用量", 0.955f, 0.28f, 64f, HudValues::memory, false));
        elements.add(scoreboard);
        elements.add(tab);
        elements.add(crosshair);
        Config.load();
        Branding.loaded = true;

        // caches of text / widths / translations rebuild after a resource pack, language or font change
        net.minecraft.client.resources.IResourceManager rm = Minecraft.getMinecraft().getResourceManager();
        if (rm instanceof net.minecraft.client.resources.IReloadableResourceManager) {
            ((net.minecraft.client.resources.IReloadableResourceManager) rm).registerReloadListener(m -> dev.fzkc.fzhud.util.Stamp.resources++);
        }

        ClientRegistry.registerKeyBinding(editKey);
        ClientRegistry.registerKeyBinding(toggleSneakKey);
        ClientRegistry.registerKeyBinding(imeKey);
        ClientRegistry.registerKeyBinding(Extras.zoomKey);
        ClientRegistry.registerKeyBinding(dev.fzkc.fzhud.feature.LauncherLink.KEY_OVERLAY);
        ClientRegistry.registerKeyBinding(dev.fzkc.fzhud.feature.LauncherLink.KEY_RECORD);
        ClientRegistry.registerKeyBinding(dev.fzkc.fzhud.feature.LauncherLink.KEY_SAVE);
        dev.fzkc.fzhud.feature.LauncherLink.start();   // the launcher's overlay and recorder
        ClientRegistry.registerKeyBinding(Spotify.keyPlay);
        ClientRegistry.registerKeyBinding(Spotify.keyNext);
        ClientRegistry.registerKeyBinding(Spotify.keyPrev);
        MinecraftForge.EVENT_BUS.register(new Translator());
        MinecraftForge.EVENT_BUS.register(new dev.fzkc.fzhud.chat.ChatAlerts());
        MinecraftForge.EVENT_BUS.register(new dev.fzkc.fzhud.chat.ChatExtras());
        MinecraftForge.EVENT_BUS.register(new dev.fzkc.fzhud.chat.PartyDetector());   // groups joining a waiting lobby together   // FzClient chat box, tabs, shortcuts
        ChatHistory chatHistory = new ChatHistory();
        MinecraftForge.EVENT_BUS.register(chatHistory);
        FMLCommonHandler.instance().bus().register(chatHistory);
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new ChatHistory.HistoryCommand());
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new ChatHistory.LogCommand());
        Utilities utilities = new Utilities();
        MinecraftForge.EVENT_BUS.register(utilities);
        FMLCommonHandler.instance().bus().register(utilities);
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new Translator.ButtonCommand());
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new Translator.SendCommand());
        MinecraftForge.EVENT_BUS.register(new dev.fzkc.fzhud.feature.ServerListTweaks());
        MinecraftForge.EVENT_BUS.register(new PartyRelay());
        MinecraftForge.EVENT_BUS.register(new CommandSuggest());
        MinecraftForge.EVENT_BUS.register(new dev.fzkc.fzhud.chat.KnownNames());   // names for the command suggestions
        MinecraftForge.EVENT_BUS.register(new dev.fzkc.fzhud.cosmetic.Cosmetics());   // capes and auras (sync)
        MinecraftForge.EVENT_BUS.register(new dev.fzkc.fzhud.cosmetic.AuraFx());      // aura sprites, drawn after the world
        MinecraftForge.EVENT_BUS.register(new dev.fzkc.fzhud.gui.PauseButtons());     // FzClient Settings / Cosmetics in the game menu
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new PartyRelay.TestCommand());
        net.minecraftforge.client.ClientCommandHandler.instance.registerCommand(new DiscordVoice.CodeCommand());
        Extras extras = new Extras();
        MinecraftForge.EVENT_BUS.register(extras);
        FMLCommonHandler.instance().bus().register(extras);
        MinecraftForge.EVENT_BUS.register(this);
        FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void onOverlayPre(RenderGameOverlayEvent.Pre event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (event.type == RenderGameOverlayEvent.ElementType.ALL) {
            // hide the vanilla sidebar while ours is on
            GuiIngameForge.renderObjective = !scoreboard.enabled;
            tabActive = tab.enabled && tab.toggleMode.get() && tabToggled;
        } else if (event.type == RenderGameOverlayEvent.ElementType.PLAYER_LIST) {
            if (tab.enabled) {
                event.setCanceled(true);
                // in toggle mode, holding the key doesn't open it - only the toggle state counts
                if (!tab.toggleMode.get()) tabActive = true;
            }
        } else if (event.type == RenderGameOverlayEvent.ElementType.CHAT && event instanceof RenderGameOverlayEvent.Chat) {
            // smooth chat: new lines slide up into place
            float off = chatSlide.update(0f);
            chatSlide.speed = Modules.chatSpeed.get();
            if (Modules.smoothChatEnabled() && off > 0.5f) ((RenderGameOverlayEvent.Chat) event).posY += Math.round(off);
        } else if (event.type == RenderGameOverlayEvent.ElementType.CROSSHAIRS) {
            if (!crosshair.enabled || mc.playerController.isSpectator()) return;
            event.setCanceled(true);
            if (mc.gameSettings.showDebugInfo && !mc.thePlayer.hasReducedDebug() && !mc.gameSettings.reducedDebugInfo) return;
            ScaledResolution sr = event.resolution;
            GlStateManager.pushMatrix();
            GlStateManager.translate(crosshair.screenX(sr.getScaledWidth()), crosshair.screenY(sr.getScaledHeight()), 0f);
            GlStateManager.scale(crosshair.scale(), crosshair.scale(), 1f);
            crosshair.draw(1f, false);
            GlStateManager.popMatrix();
            GlStateManager.enableBlend();
        }
    }

    @SubscribeEvent
    public void onOverlay(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.showDebugInfo || mc.currentScreen instanceof EditScreen) return;
        partialTicks = event.partialTicks;
        ScaledResolution sr = event.resolution;
        int sw = sr.getScaledWidth(), sh = sr.getScaledHeight();

        for (HudElement e : elements) {
            if (!e.drawnInHudPass()) continue;
            float target = e.enabled && e.active() ? 1f : 0f;
            if (e == tab && tab.toggleMode.get()) e.shown.snap(target); // no animation in toggle mode
            float a = e.shown.update(target);
            if (a < 0.01f) continue;
            GlStateManager.pushMatrix();
            // fade + slide down slightly when appearing
            GlStateManager.translate(e.screenX(sw), e.screenY(sh) - (1f - a) * 6f, 0f);
            GlStateManager.scale(e.scale(), e.scale(), 1f);
            e.draw(a, false);
            GlStateManager.popMatrix();
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        dev.fzkc.fzhud.util.Stamp.ticks++;
        Minecraft mc = Minecraft.getMinecraft();
        borderlessAtStart(mc);
        Tracker.tick();
        Gameplay.tick(toggleSneakKey);
        TntTimer.tick();
        Spotify.tick();
        dev.fzkc.fzhud.integration.FzUsers.tick();   // who else uses FzClient (logo next to their names)
        Branding.tick();
        DiscordRpc.tick();
        R.chromaSeconds = Modules.chromaSpeed.get();
        if (tab.enabled && tab.toggleMode.get() && mc.thePlayer != null) {
            while (mc.gameSettings.keyBindPlayerList.isPressed()) {
                if (mc.currentScreen == null) tabToggled = !tabToggled;
            }
        } else {
            tabToggled = false;
        }
        MotionBlur.tick();
        Performance.tick();
        dev.fzkc.fzhud.feature.LauncherLink.tick(mc);
        RawInput.tick();
        Glass.tick();
        DiscordVoice.tick();
        if (mc.theWorld != lastWorld) {
            lastWorld = mc.theWorld;
            // replay the intro animation on every world change
            for (HudElement e : elements) e.shown.snap(0f);
        }
        // Japanese input box
        if (Modules.imeEnabled()) {
            if (imePending && mc.currentScreen instanceof GuiChat) {
                imePending = false;
                JapaneseInput.open(JapaneseInput.chatText((GuiChat) mc.currentScreen));
            }
            if (imeKey.isPressed() && mc.currentScreen == null && mc.thePlayer != null) {
                mc.displayGuiScreen(new GuiChat());
                imePending = true;
            }
        }
        if (editKey.isPressed() && mc.currentScreen == null) {
            mc.displayGuiScreen(new MenuScreen());
        }
    }

    private boolean borderlessChecked;

    /**
     * Borderless used to apply only when F11 was pressed: a game started in fullscreen (the launcher's setting)
     * stayed in exclusive fullscreen. That also froze recordings (window capture only gets new frames from a
     * window Windows draws itself) and hid the mouse in menus. On the first tick such a game is switched to
     * borderless: our toggleFullscreen goes fullscreen -> window, and the second call window -> borderless.
     */
    private void borderlessAtStart(Minecraft mc) {
        if (borderlessChecked) return;
        borderlessChecked = true;
        if (!Modules.borderless.enabled || !org.lwjgl.opengl.Display.isFullscreen()) return;
        mc.toggleFullscreen();
        mc.toggleFullscreen();
    }

    /**
     * Alt+X while any screen is open (title screen, inventory, chest menus, pause menu, chat...): key bindings
     * only work in the game itself, so the key is caught here before the screen sees it.
     */
    @SubscribeEvent
    public void onOverlayKey(net.minecraftforge.client.event.GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!Keyboard.getEventKeyState()) return;
        // binding a key in the controls: Alt+X belongs to that
        if (dev.fzkc.fzhud.feature.LauncherLink.screenKey(Keyboard.getEventKey(), event.gui instanceof net.minecraft.client.gui.GuiControls)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChat(ClientChatReceivedEvent event) {
        if (event.isCanceled() || event.type == 2) return;
        if (Modules.smoothChatEnabled()) chatSlide.snap(9f);
    }

    /**
     * Runs last (LOWEST) so other mods' nametag handlers go first; if one of them already
     * cancelled the vanilla tag we stay out of the way. After drawing ours we still fire the
     * Post event, so mods that draw extra things there keep working.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onNameTag(RenderLivingEvent.Specials.Pre event) {
        if (!Modules.nametags.enabled || !(event.entity instanceof EntityPlayer)) return;
        event.setCanceled(true);
        Nametags.render((EntityPlayer) event.entity, event.x, event.y, event.z);
        MinecraftForge.EVENT_BUS.post(new RenderLivingEvent.Specials.Post(event.entity, event.renderer, event.x, event.y, event.z));
    }

    @SubscribeEvent
    public void onOverlayPost(RenderGameOverlayEvent.Post event) {
        if (event.type != RenderGameOverlayEvent.ElementType.FOOD || !Modules.saturation.enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        FoodStats food = mc.thePlayer.getFoodStats();
        float sat = food.getSaturationLevel();
        if (sat <= 0f) return;
        int w = event.resolution.getScaledWidth(), h = event.resolution.getScaledHeight();
        int top = h - GuiIngameForge.right_height + 10;
        // golden outline around each shank that is backed by saturation (all pieces in one draw)
        int c = R.alpha(Modules.satColor.get(), Modules.satOpacity.get() / 100f);
        R.batchBegin();
        for (int i = 0; i < 10; i++) {
            float v = sat - i * 2;
            if (v <= 0) break;
            int x = w / 2 + 91 - i * 8 - 9;
            if (v >= 2f) {
                R.batchRect(x, top, 9, 1, c);
                R.batchRect(x, top + 8, 9, 1, c);
                R.batchRect(x, top, 1, 9, c);
                R.batchRect(x + 8, top, 1, 9, c);
            } else {
                R.batchRect(x + 4, top, 5, 1, c);
                R.batchRect(x + 4, top + 8, 5, 1, c);
                R.batchRect(x + 8, top, 1, 9, c);
            }
        }
        R.batchEnd();
        GlStateManager.enableBlend();
    }

    @SubscribeEvent
    public void onWorldRender(RenderWorldLastEvent event) {
        if (Modules.tnt.enabled) TntTimer.render(event.partialTicks);
        if (Modules.hitbox.enabled) Hitboxes.render(event.partialTicks);
    }

    /** Skip the "Downloading terrain" screen; the player can't move until its chunk arrives anyway. */
    @SubscribeEvent
    public void onGuiOpen(GuiOpenEvent event) {
        if (event.gui instanceof net.minecraft.client.gui.GuiMainMenu) {
            Branding.patchBrandings();
            // FzClient's own title screen instead of the vanilla one
            if (Branding.on("custommenu")) event.gui = new dev.fzkc.fzhud.gui.FzMainMenu();
        }
        if (event.gui instanceof GuiDownloadTerrain && Modules.noLoading.enabled && Modules.noLoadingTerrain.get()) {
            event.gui = null;
        }
        // Japanese input: open the box together with the chat, close it when the chat closes
        if (event.gui instanceof GuiChat) {
            if (JapaneseInput.skipAutoOpen) JapaneseInput.skipAutoOpen = false;
            else if (Modules.imeEnabled() && Modules.imeAuto.get() && !NativeIme.inChatMode()) imePending = true;
        } else if (JapaneseInput.isOpen()) {
            JapaneseInput.close();
        }
    }

    /** Draws the Japanese text being converted on top of the chat / sign / anvil screen. */
    @SubscribeEvent
    public void onDrawScreen(GuiScreenEvent.DrawScreenEvent.Post event) {
        NativeIme.draw(event.gui);
        Decoration.draw(event.gui);   // bird + logo on the pause menu / inventory
    }

    /** In the chat, the IME key (F7 by default) re-opens the Japanese box. */
    @SubscribeEvent
    public void onGuiKey(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!Modules.imeEnabled() || !(event.gui instanceof GuiChat)) return;
        if (Keyboard.getEventKeyState() && imeKey.getKeyCode() > 0 && Keyboard.getEventKey() == imeKey.getKeyCode()) {
            JapaneseInput.open(JapaneseInput.chatText((GuiChat) event.gui));
            event.setCanceled(true);
        }
    }

    /** Time changer: overwrite the client-side time (and weather) right before each frame. */
    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Performance.afterFrame();
            dev.fzkc.fzhud.util.FrameStats.frame();
            dev.fzkc.fzhud.util.Win32Window.update(org.lwjgl.opengl.Display.isActive());
            dev.fzkc.fzhud.feature.LauncherLink.frame();
            return;
        }
        if (event.phase != TickEvent.Phase.START) return;
        NativeIme.frame();
        Minecraft mc = Minecraft.getMinecraft();
        long now = System.nanoTime();
        double dt = lastTimeNanos == 0 ? 0 : Math.min(0.25, (now - lastTimeNanos) / 1.0e9);
        lastTimeNanos = now;
        if (mc.theWorld == null || !Modules.timeChanger.enabled) {
            customTime = -1;
            return;
        }
        double ticks;
        switch (Modules.timeMode.get()) {
            case 1:
                if (customTime < 0) customTime = mc.theWorld.getWorldTime() % 24000L;
                customTime = (customTime + dt * 20.0 * Modules.timeSpeed.get()) % 24000.0;
                ticks = customTime;
                break;
            case 2: {
                // local clock time without building a Calendar every frame
                long ms = System.currentTimeMillis();
                ms += LOCAL_ZONE.getOffset(ms);
                double h = (((ms % 86400000L) + 86400000L) % 86400000L) / 3600000.0;
                ticks = hourToTicks(h);
                break;
            }
            default:
                ticks = hourToTicks(Modules.timeHour.get());
        }
        mc.theWorld.setWorldTime((long) ticks);
        if (Modules.timeNoRain.get()) {
            mc.theWorld.setRainStrength(0f);
            mc.theWorld.setThunderStrength(0f);
        }
    }

    /** The PC's time zone (TimeZone.getDefault() makes a copy on every call; Java fixes it at start-up anyway). */
    private static final java.util.TimeZone LOCAL_ZONE = java.util.TimeZone.getDefault();

    /** 6:00 is tick 0 in Minecraft. */
    private static double hourToTicks(double hour) {
        return ((hour * 1000.0 - 6000.0) % 24000.0 + 24000.0) % 24000.0;
    }

    @SubscribeEvent(receiveCanceled = true)
    public void onMouse(MouseEvent event) {
        // 1.7 block-hit look: swing as soon as attack is clicked while blocking (even if another mod ate the click)
        if (event.buttonstate && event.button >= 0
                && event.button - 100 == Minecraft.getMinecraft().gameSettings.keyBindAttack.getKeyCode()) {
            Gameplay.onAttackPressed();
        }
        if (event.isCanceled()) return;
        if (event.buttonstate && (event.button == 0 || event.button == 1)) Tracker.click(event.button);
        if (event.dwheel != 0 && Modules.hotbar.enabled) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer == null || mc.currentScreen != null || mc.thePlayer.isSpectator()) return;
            int left = (int) Modules.hotbarLeft.get() - 1;
            int right = (int) Modules.hotbarRight.get() - 1;
            if (left > right) {
                int t = left;
                left = right;
                right = t;
            }
            // vanilla: wheel up moves one slot to the left
            int dir = event.dwheel > 0 ? -1 : 1;
            if (Modules.hotbarInvert.get()) dir = -dir;
            int slot = mc.thePlayer.inventory.currentItem + dir;
            if (slot < left) slot = Modules.hotbarWrap.get() ? right : left;
            if (slot > right) slot = Modules.hotbarWrap.get() ? left : right;
            mc.thePlayer.inventory.currentItem = slot;
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onConnect(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        Tracker.resetTps();
        try {
            Channel ch = event.manager.channel();
            ChannelPipeline pipe = ch.pipeline();
            if (pipe.get("fzhud_tps") == null && pipe.get("packet_handler") != null) {
                pipe.addBefore("packet_handler", "fzhud_tps", new Tracker.TpsHandler());
            }
        } catch (Exception ex) {
            System.err.println("[FzClient] TPS tracking unavailable: " + ex);
        }
    }

    @SubscribeEvent
    public void onDisconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        Tracker.resetTps();
        Gameplay.onDisconnect();
        tabToggled = false;
    }
}
