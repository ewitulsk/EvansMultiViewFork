package fr.zeffut.multiview.mixin.testing;

import fr.zeffut.multiview.testing.HiddenClientScenario;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Drives the scripted hidden-client scenario each tick and validates the window contract each frame. */
@Mixin(Minecraft.class)
public abstract class MixinHiddenClient {

    @Inject(method = "tick", at = @At("TAIL"))
    private void multiview$hiddenClientTick(CallbackInfo ci) {
        HiddenClientScenario.tick();
    }

    @Inject(method = "renderFrame", at = @At("TAIL"))
    private void multiview$hiddenClientFrame(boolean tick, CallbackInfo ci) {
        HiddenClientScenario.frame();
    }
}
