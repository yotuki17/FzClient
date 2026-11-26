package dev.fzkc.fzhud.util;

import java.lang.reflect.Method;

/**
 * Lines drawn in the world (RenderWorldLastEvent) while OptiFine shaders are on. At that point no shader program
 * fits untextured lines, so the shader pack throws them away and nothing shows. OptiFine draws leads (also plain
 * coloured geometry) with its "basic" program: the same is used here. Without OptiFine or shaders nothing happens.
 */
public final class ShaderLines {
    private ShaderLines() {}

    private static boolean looked;
    private static Method isShaders, begin, end;

    /** Returns true when the matching {@link #end()} must be called. */
    public static boolean begin() {
        if (!looked) {
            looked = true;
            try {
                isShaders = Class.forName("Config").getMethod("isShaders");
                Class<?> s;
                try {
                    s = Class.forName("net.optifine.shaders.Shaders");
                } catch (ClassNotFoundException old) {
                    s = Class.forName("shadersmod.client.Shaders");   // OptiFine L5 and older
                }
                begin = s.getMethod("beginLeash");
                end = s.getMethod("endLeash");
            } catch (Throwable t) {
                isShaders = null; // no OptiFine, or one without these
            }
        }
        if (isShaders == null) return false;
        try {
            if (!(Boolean) isShaders.invoke(null)) return false;
            begin.invoke(null);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void end() {
        try {
            end.invoke(null);
        } catch (Throwable t) {
            // nothing to undo
        }
    }
}
