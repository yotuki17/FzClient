package dev.fzkc.fzhud.util;

import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Dev check (-Dfzclient.perftest): splits a frame into its big parts for Perf. */
public class PerfProbe {
    private static void mark(String ended) {
        Perf.end(ended);
        Perf.begin();
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void tickStart(TickEvent.ClientTickEvent e) { if (e.phase == TickEvent.Phase.START) mark("A-between"); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void tickEndFirst(TickEvent.ClientTickEvent e) { if (e.phase == TickEvent.Phase.END) mark("B-vanillaTick"); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void tickEnd(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        mark("C-modTick");
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        String opt = System.getProperty("fzclient.perftest");
        if (opt.contains("nocos")) dev.fzkc.fzhud.Modules.cosmetics.enabled = false;
        dev.fzkc.fzhud.Modules.optSky.value = !opt.contains("nosky");
        dev.fzkc.fzhud.Modules.optFont.value = !opt.contains("nofont");
        if (++ticks == 200 && mc.theWorld == null && System.getProperty("fzclient.costest") == null) {
            java.io.File[] dirs = new java.io.File(mc.mcDataDir, "saves").listFiles();
            if (dirs != null) for (java.io.File d : dirs) if (new java.io.File(d, "level.dat").isFile()) { mc.launchIntegratedServer(d.getName(), d.getName(), null); break; }
        }
        if (mc.thePlayer != null && mc.thePlayer.getHealth() <= 0f && ticks % 40 == 0) { mc.thePlayer.respawnPlayer(); mc.displayGuiScreen(null); }
    }
    private int ticks;
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void renderStart(TickEvent.RenderTickEvent e) { if (e.phase == TickEvent.Phase.START) mark("A-between"); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void renderStartLast(TickEvent.RenderTickEvent e) { if (e.phase == TickEvent.Phase.START) mark("D-modRenderStart"); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void worldLast(RenderWorldLastEvent e) { mark("E-world"); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void worldLastEnd(RenderWorldLastEvent e) { mark("F-modWorldLast"); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void overlayPre(RenderGameOverlayEvent.Pre e) { if (e.type == RenderGameOverlayEvent.ElementType.ALL) mark("G-hand"); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void overlayPost(RenderGameOverlayEvent.Post e) { if (e.type == RenderGameOverlayEvent.ElementType.ALL) mark("H-vanillaOverlay"); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void overlayPostEnd(RenderGameOverlayEvent.Post e) { if (e.type == RenderGameOverlayEvent.ElementType.ALL) mark("I-modOverlay"); }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void renderEnd(TickEvent.RenderTickEvent e) { if (e.phase == TickEvent.Phase.END) mark("J-screen"); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void renderEndLast(TickEvent.RenderTickEvent e) { if (e.phase == TickEvent.Phase.END) mark("K-modRenderEnd"); }
}
