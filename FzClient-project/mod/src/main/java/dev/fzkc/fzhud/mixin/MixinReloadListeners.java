package dev.fzkc.fzhud.mixin;

import net.minecraft.client.resources.IResourceManagerReloadListener;
import net.minecraft.client.resources.SimpleReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A reload listener that is added while the listeners are being called broke the whole reload, and the game then
 * dropped every resource pack. With optimization "起動を速くする" some classes of other mods may wake up in the reload
 * that used to wake up before it. A new listener is called when it is added, so nothing is missed here.
 */
@Mixin(SimpleReloadableResourceManager.class)
public abstract class MixinReloadListeners {

    @Redirect(require = 0, method = "notifyReloadListeners", at = @At(value = "INVOKE", remap = false, target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    private Iterator<IResourceManagerReloadListener> fzhud$copy(List<IResourceManagerReloadListener> listeners) {
        return new ArrayList<>(listeners).iterator();
    }
}
