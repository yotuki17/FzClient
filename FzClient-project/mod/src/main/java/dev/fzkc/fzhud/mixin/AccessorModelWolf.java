package dev.fzkc.fzhud.mixin;

import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.model.ModelWolf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The wolf's mane and tail (not public): the puppy pet poses them itself (PetFx). */
@Mixin(ModelWolf.class)
public interface AccessorModelWolf {
    @Accessor("wolfMane")
    ModelRenderer fzhud$getMane();

    @Accessor("wolfTail")
    ModelRenderer fzhud$getTail();
}
