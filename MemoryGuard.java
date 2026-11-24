package dev.byecrashes;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.Locale;

/** Warns before an OutOfMemoryError: old-generation usage measured right after the last collection. */
final class MemoryGuard {
    private static final long COOLDOWN_NS = 120_000_000_000L;
    private static long lastWarn = 0L;

    private MemoryGuard() {}

    static void check(MinecraftClient mc) {
        ByConfig cfg = ByConfig.get();
        if (!cfg.memoryWarning || mc.player == null || mc.inGameHud == null) return;
        try {
            int pct = oldGenPercentAfterGc();
            if (pct < cfg.memoryWarnPercent) return;
            long now = System.nanoTime();
            if (lastWarn != 0L && now - lastWarn < COOLDOWN_NS) return;
            lastWarn = now;
            long maxMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
            mc.inGameHud.getChatHud().addMessage(Text.literal("[ByeCrashes] "
                    + L.t("Memory is nearly full (" + pct + "% after cleanup, limit " + maxMb + " MB). Raise -Xmx or lower render distance before it crashes.",
                    "الذاكرة قربت تخلص (" + pct + "% بعد التنظيف، الحد " + maxMb + " MB). زد -Xmx أو قلل مسافة الرسم قبل ما يكرّش.")
            ).formatted(Formatting.YELLOW));
        } catch (Throwable ignored) {
            // management beans unavailable
        }
    }

    private static int oldGenPercentAfterGc() {
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() != MemoryType.HEAP) continue;
            String n = p.getName().toLowerCase(Locale.ROOT);
            if (!(n.contains("old") || n.contains("tenured"))) continue;
            MemoryUsage u = p.getCollectionUsage();
            if (u == null || u.getMax() <= 0) continue;
            return (int) (u.getUsed() * 100L / u.getMax());
        }
        return 0;
    }
}
