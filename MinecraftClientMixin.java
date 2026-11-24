package dev.byecrashes.mixin;

import dev.byecrashes.RenderGuard;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Shadow
    protected abstract void render(boolean tick);

    // The single render(Z) call inside MinecraftClient.run: one failed frame no longer ends the game.
    @Redirect(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;render(Z)V"), require = 0)
    private void byecrashes$guardedRender(MinecraftClient self, boolean tick) {
        try {
            this.render(tick);
        } catch (Throwable t) {
            if (!RenderGuard.handle(self, t)) throw t;
        }
    }
}
