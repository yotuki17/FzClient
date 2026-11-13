package dev.fzkc.fzhud.util;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Dev check (-Dfzclient.uitest=itempos, namehider or pv): joins the first saved world, switches the feature on with a
 * few settings and saves pictures (screenshots/ip_*.png, nh_*.png, pv_*.png). Not registered without the option.
 */
public class DevTest {
    public static final String MODE = System.getProperty("fzclient.uitest");
    private static final Logger LOG = LogManager.getLogger("FzClient-DevTest");
    private int ticks, worldAt;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        int t = ++ticks;
        if (t == 200 && mc.theWorld == null) {
            java.io.File[] dirs = new java.io.File(mc.mcDataDir, "saves").listFiles();
            if (dirs != null) {
                for (java.io.File d : dirs) {
                    if (!new java.io.File(d, "level.dat").isFile()) continue;
                    mc.launchIntegratedServer(d.getName(), d.getName(), null);
                    break;
                }
            }
        }
        if (mc.thePlayer == null || mc.theWorld == null) return;
        if (worldAt == 0) worldAt = t;
        int w = t - worldAt;
        if (mc.thePlayer.getHealth() <= 0f && w % 40 == 5) {
            mc.thePlayer.respawnPlayer();
            mc.displayGuiScreen(null);
        }
        if ("itempos".equals(MODE)) itemPos(mc, w);
        else if ("namehider".equals(MODE)) nameHider(mc, w);
        else if ("pv".equals(MODE)) pv(mc, w);
        else if ("denick".equals(MODE)) denick(w);
        else if ("denickshare".equals(MODE)) denickShare(w);
        else if ("settings".equals(MODE)) settings(mc, w);
    }

    private dev.fzkc.fzhud.gui.SettingsScreen set;
    private int setTheme, setType;

    /** The settings windows: crosshair rows, the type tiles, the colour window, inline choices, in dark and white. */
    private void settings(Minecraft mc, int w) {
        int base = 40;
        for (int pass = 0; pass < 2; pass++, base += 120) {
            if (w == base) {
                if (pass == 0) setTheme = Modules.uiTheme.index;
                Modules.uiTheme.index = pass;
                dev.fzkc.fzhud.gui.Themes.apply(pass);
                set = dev.fzkc.fzhud.gui.SettingsScreen.devOpen("crosshair", null);
                mc.displayGuiScreen(set);
            }
            String p = pass == 0 ? "dark_" : "white_";
            if (w == base + 15) shot(mc, "set_" + p + "rows");
            if (w == base + 20) set.devPopup("type");
            if (w == base + 35) shot(mc, "set_" + p + "type");
            if (w == base + 40) set.devPopup("color");
            if (w == base + 55) shot(mc, "set_" + p + "color");
            if (w == base + 60) {
                set = dev.fzkc.fzhud.gui.SettingsScreen.devOpen("fullbright", null);
                mc.displayGuiScreen(set);
            }
            if (w == base + 75) shot(mc, "set_" + p + "inline");
        }
        if (w == 300) {
            Modules.uiTheme.index = setTheme;
            dev.fzkc.fzhud.gui.Themes.apply(setTheme);
            mc.displayGuiScreen(null);
        }
        // every crosshair type in the world, at its real size
        dev.fzkc.fzhud.config.ChoiceSetting type = null;
        for (dev.fzkc.fzhud.hud.HudElement e : dev.fzkc.fzhud.FzHud.elements) {
            if (e instanceof dev.fzkc.fzhud.hud.Crosshair) type = ((dev.fzkc.fzhud.hud.Crosshair) e).typeSetting();
        }
        if (type != null && w >= 310 && w < 310 + type.options.length * 6) {
            int i = (w - 310) / 6;
            if ((w - 310) % 6 == 0) {
                if (i == 0) setType = type.index;
                type.index = i;
            }
            if ((w - 310) % 6 == 4) shot(mc, "xh_" + i);
        }
        if (type != null && w == 310 + type.options.length * 6) type.index = setType;
        // the crosshair editor, then the see-through (inverted) look in the world
        int e0 = 310 + (type == null ? 0 : type.options.length * 6) + 10;
        dev.fzkc.fzhud.hud.Crosshair ch = null;
        for (dev.fzkc.fzhud.hud.HudElement e : dev.fzkc.fzhud.FzHud.elements) {
            if (e instanceof dev.fzkc.fzhud.hud.Crosshair) ch = (dev.fzkc.fzhud.hud.Crosshair) e;
        }
        if (ch == null || type == null) return;
        dev.fzkc.fzhud.config.BoolSetting inv = null;
        dev.fzkc.fzhud.config.SliderSetting op = null;
        for (dev.fzkc.fzhud.config.Setting s : ch.settings) {
            if (s.key.equals("invert")) inv = (dev.fzkc.fzhud.config.BoolSetting) s;
            if (s.key.equals("opacity")) op = (dev.fzkc.fzhud.config.SliderSetting) s;
        }
        if (w == e0) mc.displayGuiScreen(new dev.fzkc.fzhud.gui.CrosshairEditor(ch.customGrid(), ch, null));
        if (w == e0 + 15) shot(mc, "set_editor");
        if (w == e0 + 20) {
            mc.displayGuiScreen(null);
            setType = type.index;
            type.index = dev.fzkc.fzhud.hud.Crosshair.CUSTOM;
        }
        if (w == e0 + 26) shot(mc, "xh_custom");
        if (w == e0 + 30 && inv != null) inv.value = true;
        if (w == e0 + 36) shot(mc, "xh_inv_custom");
        if (w == e0 + 40) type.index = 1;
        if (w == e0 + 46) shot(mc, "xh_inv_cross");
        if (w == e0 + 50 && op != null) op.set(50);
        if (w == e0 + 56) shot(mc, "xh_inv_cross_50");
        if (w == e0 + 60 && inv != null) inv.value = false;
        if (w == e0 + 66) shot(mc, "xh_cross_50");
        if (w == e0 + 70) {
            if (op != null) op.set(100);
            type.index = setType;
        }
    }

    private dev.fzkc.fzhud.gui.StatsScreen pv;
    private int pvStyle, pvLang;
    private double pvSize;
    private static final String[] PV_STEPS = {"bw", "bw_solo", "duels", "duels_list", "duels_uhc", "tnttag",
            "bowspleef", "mm", "bw_daily", "classic", "flat", "size70", "size130", "jp"};
    /** Fzkc's real UUID (looked up at the start), so the made-up numbers come with the real skin. */
    private volatile java.util.UUID pvId;

    /**
     * The /pv window: loading, every game and mode with made-up numbers, the mode list, the three looks, the smallest
     * and biggest menu size, Japanese; then the real numbers (fetched while the made-up ones were shown) and a name
     * nobody has.
     */
    private void pv(Minecraft mc, int w) {
        if (w == 40) {
            pvStyle = Modules.uiStyle.index;
            pvSize = Modules.uiSize.value;
            pvLang = Modules.langChoice.index;
            new Thread(() -> pvId = dev.fzkc.fzhud.stats.StatsCommand.lookup("Fzkc"), "FzClient-DevTest-Lookup").start();
        }
        if (w == 60) {
            pv = new dev.fzkc.fzhud.gui.StatsScreen("Fzkc", dev.fzkc.fzhud.stats.Game.BEDWARS, null, dev.fzkc.fzhud.stats.History.Period.TOTAL);
            mc.displayGuiScreen(pv);
        }
        if (w == 64) shot(mc, "pv_loading");
        int start = 100, every = 25;
        if (w >= start && w < start + PV_STEPS.length * every && pv != null) {
            String step = PV_STEPS[(w - start) / every];
            int d = (w - start) % every;
            if (d == 0) pvStep(step);
            if (d == 20) shot(mc, "pv_" + step);
        }
        int end = start + PV_STEPS.length * every;
        if (w == end) {
            // the first window left its answer out for the made-up numbers; the stats cache still has it
            pvLook(pvStyle, pvSize, 1);
            pv = new dev.fzkc.fzhud.gui.StatsScreen("Fzkc", dev.fzkc.fzhud.stats.Game.BEDWARS, null, dev.fzkc.fzhud.stats.History.Period.TOTAL);
            mc.displayGuiScreen(pv);
        }
        if (w == end + 100) shot(mc, "pv_real");
        if (w == end + 101) pv.devShow(null, dev.fzkc.fzhud.stats.Game.DUELS, null, dev.fzkc.fzhud.stats.History.Period.TOTAL, false);
        if (w == end + 121) shot(mc, "pv_real_duels");
        if (w == end + 125) {
            pv = new dev.fzkc.fzhud.gui.StatsScreen("qzxvnobody9921", dev.fzkc.fzhud.stats.Game.BEDWARS, null, dev.fzkc.fzhud.stats.History.Period.TOTAL);
            mc.displayGuiScreen(pv);
        }
        if (w == end + 205) shot(mc, "pv_error");
        if (w == end + 210) {
            mc.displayGuiScreen(null);
            pvLook(pvStyle, pvSize, pvLang);
            dev.fzkc.fzhud.Config.save();
            LOG.info("pv done");
        }
    }

    /** Denick: one name per nick and one nick per name, the newest answer winning (see Denick.resolve). */
    private void denick(int w) {
        if (w == 40) dev.fzkc.fzhud.stats.Denick.devResolve("NickA", "Steve", "test");
        if (w == 42) dev.fzkc.fzhud.stats.Denick.devResolve("NickB", "Steve", "test");   // NickA loses Steve
        if (w == 44) dev.fzkc.fzhud.stats.Denick.devResolve("NickB", "Alex", "test");    // NickB: Steve -> Alex
        if (w == 46) dev.fzkc.fzhud.stats.Denick.devResolve("nickc", "alex", "test");    // NickB loses Alex (any case)
        if (w == 48) dev.fzkc.fzhud.stats.Denick.devResolve("NickC", "Alex", "test");    // nothing new
        if (w == 50) {
            String r = "A=" + dn("NickA") + " B=" + dn("NickB") + " C=" + dn("NickC");
            LOG.info("denick " + r + ("A=null B=null C=alex".equals(r) ? " OK" : " WRONG"));
        }
        // shared answers count by when they were found
        if (w == 60) dev.fzkc.fzhud.stats.Denick.devResolve("NickD", "Dan", "test");
        if (w == 62) dev.fzkc.fzhud.stats.Denick.devShared("NickD", "Bob", null, "skin", 60_000);    // older: no
        if (w == 64) dev.fzkc.fzhud.stats.Denick.devShared("NickE", "Dan", null, "skin", 60_000);    // Dan known later at D: no
        if (w == 66) dev.fzkc.fzhud.stats.Denick.devShared("NickE", "Dan", null, "numbers", 0);      // newer: D loses Dan
        if (w == 68) dev.fzkc.fzhud.stats.Denick.devShared("NickE", null, "alex", "moved", 0);      // another name left: no
        if (w == 70) dev.fzkc.fzhud.stats.Denick.devShared("NickE", null, "dan", "moved", 0);       // Dan left E
        if (w == 72) dev.fzkc.fzhud.stats.Denick.devResolve("NickF", "Fred", "test");
        if (w == 74) dev.fzkc.fzhud.stats.Denick.devForget("NickF");
        if (w == 76) dev.fzkc.fzhud.stats.Denick.devShared("NickF", "Fred", null, "skin", 30_000);   // before the reset: no
        if (w == 78) dev.fzkc.fzhud.stats.Denick.devShared("NickG", "Gus", null, "skin", 0);
        if (w == 80) dev.fzkc.fzhud.stats.Denick.devResolve("NickG", "Gus", "test");                 // own check agrees
        if (w == 82) dev.fzkc.fzhud.stats.Denick.devShared("NickH", "Hal", null, "skin", 0);
        if (w == 84) dev.fzkc.fzhud.stats.Denick.devShared("NickH", "Ian", null, "skin", 0);         // newer shared replaces
        if (w == 90) {
            String r = "D=" + dn("NickD") + " E=" + dn("NickE") + " F=" + dn("NickF") + " G=" + dn("NickG") + sh("NickG")
                    + " H=" + dn("NickH") + sh("NickH");
            LOG.info("denick shared " + r + ("D=null E=null F=null G=Gus(own) H=Ian(shared)".equals(r) ? " OK" : " WRONG"));
        }
    }

    private static String sh(String nick) {
        dev.fzkc.fzhud.stats.Denick.Real r = dev.fzkc.fzhud.stats.Denick.real(nick);
        return r == null ? "" : r.shared ? "(shared)" : "(own)";
    }

    /** Sharing against a local test server (-Dfzclient.endpoint): sends a find, then asks about it. */
    private void denickShare(int w) {
        if (w == 40) {
            dev.fzkc.fzhud.stats.Denick.devShare();
            dev.fzkc.fzhud.stats.Denick.devResolve("NickT", "Steve", "skin");
            dev.fzkc.fzhud.stats.Denick.devResolve("NickU", "Alex", "party");   // never sent
        }
        if (w == 300) dev.fzkc.fzhud.stats.Denick.devShare("NickT", "NickU", "NickV");
    }

    private static String dn(String nick) {
        dev.fzkc.fzhud.stats.Denick.Real r = dev.fzkc.fzhud.stats.Denick.real(nick);
        return r == null ? "null" : r.name;
    }

    private static void pvLook(int style, double size, int lang) {
        Modules.uiStyle.index = style;
        Modules.uiSize.set(size);
        Modules.langChoice.index = lang;
    }

    private void pvStep(String step) {
        dev.fzkc.fzhud.stats.Game g = dev.fzkc.fzhud.stats.Game.BEDWARS;
        dev.fzkc.fzhud.stats.Game.Mode m = null;
        dev.fzkc.fzhud.stats.History.Period p = dev.fzkc.fzhud.stats.History.Period.TOTAL;
        boolean list = false;
        java.util.UUID id = pvId != null ? pvId : new java.util.UUID(1L, 1L);
        dev.fzkc.fzhud.stats.PlayerData d = dev.fzkc.fzhud.stats.PlayerData.sample(id, "Fzkc");
        pvLook(pvStyle, pvSize, 1);
        switch (step) {
            case "bw_solo": m = g.mode("solo"); break;
            case "duels": g = dev.fzkc.fzhud.stats.Game.DUELS; break;
            case "duels_list": g = dev.fzkc.fzhud.stats.Game.DUELS; list = true; break;
            case "duels_uhc": g = dev.fzkc.fzhud.stats.Game.DUELS; m = g.mode("uhc"); break;
            case "tnttag": g = dev.fzkc.fzhud.stats.Game.TNT_TAG; break;
            case "bowspleef": g = dev.fzkc.fzhud.stats.Game.BOW_SPLEEF; break;
            case "mm": g = dev.fzkc.fzhud.stats.Game.MURDER; break;
            case "bw_daily": p = dev.fzkc.fzhud.stats.History.Period.DAILY; break;
            case "classic": Modules.uiStyle.index = 1; break;
            case "flat": Modules.uiStyle.index = 2; break;
            case "size70": Modules.uiSize.set(70); break;
            case "size130": Modules.uiSize.set(130); break;
            case "jp": Modules.langChoice.index = 0; break;
            default: break;
        }
        pv.devShow(d, g, m, p, list);
    }

    private static void shot(Minecraft mc, String name) {
        net.minecraft.util.ScreenShotHelper.saveScreenshot(mc.mcDataDir, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        LOG.info("shot " + name);
    }

    /** Puts an item into the hotbar on the server's side (single player), so using it works like the real thing. */
    private static void give(final Minecraft mc, final int slot, final ItemStack stack) {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        final String name = mc.thePlayer.getName();
        server.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                EntityPlayerMP p = server.getConfigurationManager().getPlayerByUsername(name);
                if (p == null) return;
                p.inventory.setInventorySlotContents(slot, stack);
                p.inventoryContainer.detectAndSendChanges();
            }
        });
    }

    private static void use(Minecraft mc, boolean down) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), down);
    }

    private static void set(boolean on, double x, double y, double z, double scale) {
        Modules.itemPos.enabled = on;
        Modules.itemPosX.set(x);
        Modules.itemPosY.set(y);
        Modules.itemPosZ.set(z);
        Modules.itemPosScale.set(scale);
    }

    /** off, then three settings: the sword, the sword while blocking, a block, an apple while eating. */
    private void itemPos(Minecraft mc, int w) {
        if (w == 60) {
            give(mc, 0, new ItemStack(Items.diamond_sword));
            give(mc, 1, new ItemStack(Blocks.wool, 64, 14));
            give(mc, 2, new ItemStack(Items.golden_apple, 8));
            mc.gameSettings.thirdPersonView = 0;
            mc.gameSettings.hideGUI = false;
            mc.thePlayer.rotationPitch = 0f;
        }
        String[] names = {"off", "a", "b", "c"};
        double[][] sets = {{0, 0, 0, 100}, {-0.2, -0.1, 0.3, 60}, {0.3, 0.2, -0.2, 140}, {0, -0.16, 0, 40}};
        for (int i = 0; i < names.length; i++) {
            int s = 100 + i * 130, d = w - s;
            if (d == 0) {
                set(i > 0, sets[i][0], sets[i][1], sets[i][2], sets[i][3]);
                use(mc, false);
                mc.thePlayer.inventory.currentItem = 0;
            }
            if (d == 30) shot(mc, "ip_" + names[i] + "_sword");
            if (d == 35) use(mc, true);
            if (d == 55) shot(mc, "ip_" + names[i] + "_block");
            if (d == 58) use(mc, false);
            if (d == 60) mc.thePlayer.inventory.currentItem = 1;
            if (d == 85) shot(mc, "ip_" + names[i] + "_wool");
            if (d == 88) mc.thePlayer.inventory.currentItem = 2;
            if (d == 100) use(mc, true);
            if (d == 116) shot(mc, "ip_" + names[i] + "_eat");
            if (d == 120) use(mc, false);
        }
        int end = 100 + names.length * 130;
        if (w == end) {
            set(true, -0.2, -0.1, 0.3, 60);
            mc.thePlayer.inventory.currentItem = 0;
            mc.displayGuiScreen(new dev.fzkc.fzhud.gui.SettingsScreen(Modules.itemPos, null));
        }
        if (w == end + 40) shot(mc, "ip_settings");
        if (w == end + 45) {
            mc.displayGuiScreen(null);
            set(false, 0, 0, 0, 100);
            dev.fzkc.fzhud.Config.save();
            LOG.info("itempos done");
        }
    }

    /** The chat without and with the hider, your own name tag in third person, the settings page, the skin. */
    private void nameHider(Minecraft mc, int w) {
        net.minecraft.client.gui.FontRenderer fr = mc.fontRendererObj;
        String me = mc.thePlayer.getName();
        if (w == 60) {
            Modules.nameHider.enabled = false;
            mc.gameSettings.thirdPersonView = 0;
            mc.thePlayer.sendChatMessage("hi " + me + ", " + me.toLowerCase() + " and " + me + "123 x" + me + " " + me + "!");
            LOG.info("off: width(" + me + ") = " + fr.getStringWidth(me) + ", width(You) = " + fr.getStringWidth("You"));
        }
        if (w == 90) shot(mc, "nh_off");
        if (w == 100) {
            Modules.nameHider.enabled = true;
            Modules.nameHiderName.value = "You";
            Modules.nameHiderExtra.value = "Notch, jeb_";
            mc.thePlayer.sendChatMessage("extra: Notch jeb_ jeb_x NOTCH");
        }
        if (w == 130) {
            shot(mc, "nh_on");
            LOG.info("on: width(" + me + ") = " + fr.getStringWidth(me) + ", width(You) = " + fr.getStringWidth("You")
                    + ", width(x" + me + ") = " + fr.getStringWidth("x" + me) + ", R.measure(" + me + ") = " + dev.fzkc.fzhud.render.R.measure(me));
            LOG.info("skin before: " + mc.thePlayer.getLocationSkin() + " " + mc.thePlayer.getSkinType());
            Modules.nameHiderSkin.value = true;
        }
        if (w == 135) {
            LOG.info("skin hidden: " + mc.thePlayer.getLocationSkin() + " " + mc.thePlayer.getSkinType());
            Modules.nametags.enabled = true;
            Modules.tagSelf.value = true;
            mc.gameSettings.thirdPersonView = 2;
            mc.thePlayer.rotationPitch = -35f;   // the camera goes up, over the grass
        }
        if (w == 170) shot(mc, "nh_f5");
        if (w == 175) {
            mc.gameSettings.thirdPersonView = 0;
            mc.thePlayer.rotationPitch = 0f;
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindPlayerList.getKeyCode(), true);
        }
        if (w == 195) {
            shot(mc, "nh_tab");
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindPlayerList.getKeyCode(), false);
        }
        if (w == 200) mc.displayGuiScreen(new dev.fzkc.fzhud.gui.SettingsScreen(Modules.nameHider, null));
        if (w == 240) shot(mc, "nh_settings");
        if (w == 245) {
            mc.displayGuiScreen(null);
            Modules.nameHider.enabled = false;
            Modules.nameHiderName.value = "You";
            Modules.nameHiderExtra.value = "";
            Modules.nameHiderSkin.value = false;
            Modules.nametags.enabled = false;
            Modules.tagSelf.value = false;
            dev.fzkc.fzhud.Config.save();
            LOG.info("namehider done");
        }
    }
}
