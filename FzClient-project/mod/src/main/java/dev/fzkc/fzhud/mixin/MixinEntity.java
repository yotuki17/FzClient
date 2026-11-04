package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.feature.Extras;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Zoom: lower the mouse-look speed by the zoom factor so aiming stays precise. */
@Mixin(Entity.class)
public abstract class MixinEntity {

    @ModifyVariable(require = 0, method = "setAngles", at = @At("HEAD"), ordinal = 0, argsOnly = true)
    private float fzhud$zoomYaw(float yaw) {
        return fzhud$self() ? yaw * Extras.sensitivity() : yaw;
    }

    @ModifyVariable(require = 0, method = "setAngles", at = @At("HEAD"), ordinal = 1, argsOnly = true)
    private float fzhud$zoomPitch(float pitch) {
        return fzhud$self() ? pitch * Extras.sensitivity() : pitch;
    }

    private boolean fzhud$self() {
        return Extras.zoomFov < 1F && (Object) this == Minecraft.getMinecraft().thePlayer;
    }
}
