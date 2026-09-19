package fr.zeffut.multiview.mixin.testing;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import com.mojang.renderpearl.api.device.GpuBackend;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opt-in test process only. Visibility/focus hints are set before any game window exists. */
@Mixin(Window.class)
public abstract class MixinHiddenWindow {

    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true)
    private static boolean multiview$neverFullscreen(boolean fullscreen) {
        if (!Boolean.getBoolean("multiview.hiddenClient")) {
            return fullscreen;
        }
        return false;
    }

    @WrapOperation(method = "createWindow", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J", remap = false))
    private long multiview$hiddenWindowFlags(GpuBackend instance, String title, int width, int height, long flags, Operation<Long> original) {
        if (Boolean.getBoolean("multiview.hiddenClient")) {
            flags |= SDLVideo.SDL_WINDOW_HIDDEN;
        }
        return original.call(instance, title, width, height, flags);
    }

    @Inject(method = "setFullscreen", at = @At("HEAD"), cancellable = true)
    private void multiview$noFullscreenChanges(boolean fullscreen, CallbackInfo ci) {
        if (Boolean.getBoolean("multiview.hiddenClient")) {
            ci.cancel();
        }
    }

    @Inject(method = "updateFullscreenIfChanged", at = @At("HEAD"), cancellable = true)
    private void multiview$noFullscreenUpdates(CallbackInfo ci) {
        if (Boolean.getBoolean("multiview.hiddenClient")) {
            ci.cancel();
        }
    }

    @Inject(method = "changeFullscreenVideoMode", at = @At("HEAD"), cancellable = true)
    private void multiview$noVideoModeChanges(CallbackInfo ci) {
        if (Boolean.getBoolean("multiview.hiddenClient")) {
            ci.cancel();
        }
    }
}
