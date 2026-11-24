package dev.byecrashes.mixin;

import dev.byecrashes.FatalHandler;
import net.minecraft.util.crash.CrashReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CrashReport.class)
public abstract class CrashReportMixin {
    // asString() is the first thing the vanilla crash path calls, before the report is written and the JVM exits.
    @Inject(method = "asString", at = @At("HEAD"), require = 0)
    private void byecrashes$onFatal(CallbackInfoReturnable<String> cir) {
        FatalHandler.onCrashReport(((CrashReport) (Object) this).getCause());
    }
}
