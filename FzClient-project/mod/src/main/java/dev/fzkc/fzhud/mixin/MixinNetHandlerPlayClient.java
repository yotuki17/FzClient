package dev.fzkc.fzhud.mixin;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.play.client.C19PacketResourcePackStatus;
import net.minecraft.network.play.server.S48PacketResourcePackSend;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.URI;

/**
 * Bug fix "リソースパックを使った攻撃": a server can send a "level://../../" resource pack address and learn from the
 * answer which files exist on your PC. Only http(s) packs and real world packs (level://<world>/resources.zip)
 * are accepted; anything else is answered "download failed".
 */
@Mixin(NetHandlerPlayClient.class)
public abstract class MixinNetHandlerPlayClient {

    @Shadow
    @Final
    private NetworkManager netManager;

    @Inject(require = 0, method = "handleResourcePack", at = @At("HEAD"), cancellable = true)
    private void fzhud$safeResourcePack(S48PacketResourcePackSend packet, CallbackInfo ci) {
        if (!Modules.fixes.enabled || !Modules.fixResourcePack.get()) return;
        String url = packet.getURL();
        boolean ok;
        try {
            String scheme = new URI(url).getScheme();
            boolean level = "level".equals(scheme);
            ok = "http".equals(scheme) || "https".equals(scheme)
                    || (level && !url.contains("..") && url.endsWith("/resources.zip"));
        } catch (Exception e) {
            ok = false;
        }
        if (ok) return;
        netManager.sendPacket(new C19PacketResourcePackStatus(packet.getHash(), C19PacketResourcePackStatus.Action.FAILED_DOWNLOAD));
        ci.cancel();
    }
}
