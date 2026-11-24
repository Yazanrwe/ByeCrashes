package dev.byecrashes;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ByeCrashesClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("ByeCrashes");

    private static KeyBinding showKey;
    private static int ticks = 0;

    @Override
    public void onInitializeClient() {
        ByConfig.load();

        showKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.byecrashes.last", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_END, "key.categories.byecrashes"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (showKey.wasPressed()) {
                CrashAnalyzer.Diagnosis d = RenderGuard.last();
                if (d != null && mc.currentScreen == null) {
                    mc.setScreen(new DiagnosisScreen(d, null));
                } else if (d == null && mc.inGameHud != null) {
                    mc.inGameHud.setOverlayMessage(Text.literal("ByeCrashes: " + L.t("no crash intercepted this session", "ما اعترضت أي كراش بهذي الجلسة")), false);
                }
            }
            if (++ticks % 100 == 0) MemoryGuard.check(mc);
        });

        LOGGER.info("ByeCrashes ready: frame guard {}", ByConfig.get().recoverFromCrashes ? "on" : "off");
    }
}
