package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.feature.FastStart;
import net.minecraft.client.renderer.BlockModelShapes;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.resources.model.IBakedModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.util.IRegistry;
import net.minecraft.util.RegistrySimple;
import net.minecraftforge.client.model.ModelLoader;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Optimization "起動を速くする" ({@link FastStart}): the models of the first round stay empty. */
@Mixin(ModelManager.class)
public abstract class MixinModelManager {
    @Shadow private IRegistry<ModelResourceLocation, IBakedModel> modelRegistry;
    @Shadow private IBakedModel defaultModel;
    @Shadow @Final private TextureMap texMap;
    @Shadow @Final private BlockModelShapes modelProvider;

    @Inject(require = 0, method = "onResourceManagerReload", at = @At("HEAD"), cancellable = true)
    private void fzhud$leaveOutFirst(IResourceManager manager, CallbackInfo ci) {
        if (!FastStart.skipModels()) return;
        // as the game does. It also wakes up Forge's ModelLoaderRegistry, whose loaders register themselves as reload
        // listeners: during the second round that would break the reload (and the game would drop all resource packs)
        new ModelLoader(manager, texMap, modelProvider);
        // the block texture still gets its first stitch (always without textures in this round), with Forge's events
        texMap.loadSprites(manager, map -> { });
        List<List<BakedQuad>> faces = new ArrayList<>();
        for (int i = 0; i < 6; i++) faces.add(Collections.<BakedQuad>emptyList());
        // stands in for every model until the real ones come: never null, draws nothing
        defaultModel = new SimpleBakedModel(Collections.<BakedQuad>emptyList(), faces, false, false, texMap.getMissingSprite(), ItemCameraTransforms.DEFAULT);
        modelRegistry = new RegistrySimple<>();
        modelProvider.reloadModels();
        ci.cancel();
    }

    @Inject(require = 0, method = "onResourceManagerReload", at = @At("RETURN"))
    private void fzhud$loaded(IResourceManager manager, CallbackInfo ci) {
        FastStart.modelsLoaded();   // not reached when the first round was left out
    }
}
