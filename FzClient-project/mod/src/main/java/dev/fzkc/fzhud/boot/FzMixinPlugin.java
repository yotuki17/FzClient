package dev.fzkc.fzhud.boot;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Applies FzClient's mixins only when the FzClient Launcher started the game (see LaunchGuard). Without it the
 * game stays exactly vanilla as far as FzClient is concerned.
 * <p>
 * preApply / postApply are written twice: Mixin 0.7 (bundled) passes its own relocated ClassNode, Mixin 0.8 (when
 * another mod brings it) the plain ASM one. The JVM calls whichever matches the Mixin that is running.
 */
public class FzMixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;   // the refmap named in mixins.fzhud.json
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return LaunchGuard.ok();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    // Mixin 0.7
    @Override
    public void preApply(String targetClassName, org.spongepowered.asm.lib.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, org.spongepowered.asm.lib.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    // Mixin 0.8
    public void preApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    public void postApply(String targetClassName, org.objectweb.asm.tree.ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
