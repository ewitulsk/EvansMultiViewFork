package fr.zeffut.multiview.mixin.testing;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logical in-game mouse state without calling OS cursor capture/position APIs. */
@Mixin(MouseHandler.class)
public abstract class MixinHiddenMouse {
    @Shadow private boolean mouseGrabbed;

    @Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
    private void multiview$virtualGrab(CallbackInfo ci) {
        if (Boolean.getBoolean("multiview.hiddenClient")) {
            this.mouseGrabbed = true;
            ci.cancel();
        }
    }

    @Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
    private void multiview$virtualRelease(CallbackInfo ci) {
        if (Boolean.getBoolean("multiview.hiddenClient")) {
            this.mouseGrabbed = false;
            ci.cancel();
        }
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void multiview$scriptedLookOnly(CallbackInfo ci) {
        if (Boolean.getBoolean("multiview.hiddenClient")) {
            ci.cancel();
        }
    }
}
