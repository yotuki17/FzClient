package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import net.java.games.input.Controller;
import net.java.games.input.ControllerEnvironment;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MouseHelper;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

/**
 * Raw mouse input: the camera is moved by the mouse's own movement counts instead of the Windows cursor,
 * so "Enhance pointer precision" (mouse acceleration) and the pointer speed slider no longer change how
 * far the view turns. Reads the mouse through JInput (bundled with Minecraft 1.8.9) and swaps in a
 * MouseHelper that reports those counts instead of LWJGL's cursor deltas.
 * <p>
 * Windows only. If JInput can't start (missing natives, no mouse found) the feature turns itself off and
 * the normal mouse keeps working.
 */
public final class RawInput {
    private RawInput() {}

    private static volatile boolean ready;
    private static volatile boolean failed;
    private static boolean started;
    private static final List<net.java.games.input.Mouse> mice = new ArrayList<net.java.games.input.Mouse>();

    public static boolean supported() {
        String os = System.getProperty("os.name", "");
        return os.toLowerCase().startsWith("windows");
    }

    /** Call once per client tick: installs / removes the raw MouseHelper to match the module switch. */
    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        boolean want = Modules.rawInput.enabled && supported() && !failed;
        if (want && !started) {
            started = true;
            startAsync();
        }
        if (want && ready) {
            // only replace the vanilla helper - if another mod installed its own, leave it alone
            if (mc.mouseHelper != null && mc.mouseHelper.getClass() == MouseHelper.class) {
                mc.mouseHelper = new RawMouseHelper();
            }
        } else if (mc.mouseHelper instanceof RawMouseHelper) {
            mc.mouseHelper = new MouseHelper();
        }
    }

    /** JInput's first start can take a moment, so it happens off the game thread. */
    private static void startAsync() {
        Thread t = new Thread("FzClient-RawInput-Init") {
            @Override
            public void run() {
                try {
                    Controller[] all = ControllerEnvironment.getDefaultEnvironment().getControllers();
                    List<net.java.games.input.Mouse> found = new ArrayList<net.java.games.input.Mouse>();
                    for (Controller c : all) {
                        if (c.getType() == Controller.Type.MOUSE && c instanceof net.java.games.input.Mouse) {
                            found.add((net.java.games.input.Mouse) c);
                        }
                    }
                    if (found.isEmpty()) throw new IllegalStateException("no mouse found");
                    synchronized (mice) {
                        mice.clear();
                        mice.addAll(found);
                    }
                    ready = true;
                    System.out.println("[FzClient] Raw mouse input ready (" + found.size() + " device(s))");
                } catch (Throwable e) {
                    failed = true;
                    System.err.println("[FzClient] Raw mouse input unavailable: " + e);
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /**
     * Reads the movement since the last call. Several entries can describe the same physical mouse,
     * so the one that moved the most is used instead of adding them up. {dx, dy} in mouse counts.
     */
    private static int[] poll() {
        float bestX = 0f, bestY = 0f, best = -1f;
        synchronized (mice) {
            for (int i = mice.size() - 1; i >= 0; i--) {
                net.java.games.input.Mouse m = mice.get(i);
                if (!m.poll()) {          // device went away (unplugged)
                    mice.remove(i);
                    continue;
                }
                float x = m.getX().getPollData(), y = m.getY().getPollData();
                float mag = Math.abs(x) + Math.abs(y);
                if (mag > best) {
                    best = mag;
                    bestX = x;
                    bestY = y;
                }
            }
            if (mice.isEmpty()) ready = false;   // fall back to the normal mouse until JInput is restarted
        }
        return new int[] {Math.round(bestX), Math.round(bestY)};
    }

    /** Vanilla camera input, but fed with raw counts. */
    static final class RawMouseHelper extends MouseHelper {
        @Override
        public void mouseXYChange() {
            if (!ready || !Modules.rawInput.enabled) {
                super.mouseXYChange();
                return;
            }
            try {
                int[] d = poll();
                Mouse.getDX();   // keep LWJGL's own counters from piling up
                Mouse.getDY();
                this.deltaX = d[0];
                this.deltaY = -d[1];   // JInput: down is positive; the game wants up positive
            } catch (Throwable e) {
                failed = true;
                ready = false;
                System.err.println("[FzClient] Raw mouse input stopped: " + e);
                super.mouseXYChange();
            }
        }

        @Override
        public void grabMouseCursor() {
            super.grabMouseCursor();
            // movement made while a menu was open must not turn the camera when the game gets the mouse back
            if (ready) {
                try { poll(); } catch (Throwable ignored) { }
            }
        }
    }
}
