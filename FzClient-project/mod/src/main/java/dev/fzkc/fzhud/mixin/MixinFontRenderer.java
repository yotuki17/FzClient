package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.util.FontCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.resources.IResourceManager;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Optimization "文字の描画を使い回す" (see FontCache). The game draws a text in renderStringAtPos, letter by letter
 * from the pen position; here that drawing is recorded once (with the pen at 0,0) and replayed moved to where the
 * pen is. The game's own memory of the colour and the bound texture is put right after each replay, because a
 * display list changes them behind its back.
 * <p>
 * Name Hider (see NameHider): a text with your name in it is drawn, and measured, with the other name.
 */
@Mixin(FontRenderer.class)
public abstract class MixinFontRenderer {
    @Shadow protected float posX;
    @Shadow protected float posY;
    @Shadow private float red;
    @Shadow private float blue;
    @Shadow private float green;
    @Shadow private float alpha;
    @Shadow private boolean unicodeFlag;

    @Shadow private void renderStringAtPos(String text, boolean shadow) {}

    @Unique private final FontCache fzhud$cache = new FontCache();
    @Unique private boolean fzhud$inside;
    @Unique private boolean fzhud$renamed;

    @Inject(require = 0, method = "renderStringAtPos", at = @At("HEAD"), cancellable = true)
    private void fzhud$cached(String text, boolean shadow, CallbackInfo ci) {
        if (fzhud$inside || text == null || text.isEmpty()) return;
        if (!fzhud$renamed) {
            String shown = dev.fzkc.fzhud.feature.NameHider.apply(text);
            if (shown != text) {
                // Name Hider: draw the text with the other name instead (what is kept below is that text then)
                fzhud$renamed = true;
                try {
                    renderStringAtPos(shown, shadow);
                } finally {
                    fzhud$renamed = false;
                }
                ci.cancel();
                return;
            }
        }
        if (!FontCache.on()) return;
        if (!Minecraft.getMinecraft().isCallingFromMinecraftThread()) return;   // the loading screen draws on its own thread
        if (FontCache.shadersWorld()) return;   // OptiFine shaders: world texts the normal way
        FontCache c = fzhud$cache;
        FontCache.Entry e = c.find(text, shadow, unicodeFlag, red, green, blue, alpha);
        float x = posX, y = posY;
        if (e != null && e.list != 0) {
            GL11.glPushMatrix();
            GL11.glTranslatef(x, y, 0f);
            GL11.glCallList(e.list);
            GL11.glPopMatrix();
            posX = x + e.width;
            GlStateManager.resetColor();
            if (e.tex >= 0) GlStateManager.bindTexture(e.tex);
            ci.cancel();
            return;
        }
        if (e == null) {
            // first time: drawn the normal way (that also loads the letter pictures it needs)
            if (text.indexOf("\u00a7k") < 0 && text.indexOf("\u00a7K") < 0) c.note(text, shadow, unicodeFlag, red, green, blue, alpha);
            return;
        }
        // second time: record it. Not while someone else is recording a list of their own.
        if (GL11.glGetInteger(GL11.GL_LIST_INDEX) != 0) return;
        int list = GL11.glGenLists(1);
        if (list == 0) return;
        int before = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager.bindTexture(0);   // so the font's own bind is part of the recording
        GL11.glPushMatrix();
        GL11.glTranslatef(x, y, 0f);
        posX = 0f;
        posY = 0f;
        GL11.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
        fzhud$inside = true;
        try {
            renderStringAtPos(text, shadow);
        } finally {
            fzhud$inside = false;
            GL11.glEndList();
            GL11.glPopMatrix();
        }
        float w = posX;
        posX = x + w;
        posY = y;
        int after = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (after == 0) {
            GlStateManager.bindTexture(before);
            e.tex = -1;
        } else {
            e.tex = after;
        }
        GlStateManager.resetColor();
        e.width = w;
        e.list = list;
        ci.cancel();
    }

    /** Name Hider: the width is that of the text as it is drawn. */
    @ModifyVariable(require = 0, method = "getStringWidth", at = @At("HEAD"), argsOnly = true)
    private String fzhud$hiddenNameWidth(String text) {
        return dev.fzkc.fzhud.feature.NameHider.apply(text);
    }

    @Inject(require = 0, method = "onResourceManagerReload", at = @At("HEAD"))
    private void fzhud$reload(IResourceManager manager, CallbackInfo ci) {
        if (Minecraft.getMinecraft().isCallingFromMinecraftThread()) fzhud$cache.clear();
    }
}
