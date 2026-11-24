package dev.byecrashes;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Path;
import java.util.ArrayDeque;

/**
 * Wraps the client frame (render + tick). Stage 1: swallow, log, tell the player. Stage 2: the same error keeps coming,
 * so leave the world and show the diagnosis on the title screen. Stage 3: still failing after that, let the game crash.
 * Not intercepted: out-of-memory and other VM errors, the F3+C manual crash, crashes on other threads, native JVM crashes.
 */
public final class RenderGuard {
    private static final long ESCALATION_COOLDOWN_NS = 15_000_000_000L;
    private static final long NOTIFY_COOLDOWN_NS = 5_000_000_000L;

    private static final ArrayDeque<Long> recent = new ArrayDeque<>();
    private static long lastEscalation = 0L;
    private static long lastNotify = 0L;
    private static int total = 0;
    private static volatile CrashAnalyzer.Diagnosis last;

    private RenderGuard() {}

    public static CrashAnalyzer.Diagnosis last() {
        return last;
    }

    /** True when the error was absorbed, false when the caller must rethrow it. */
    public static boolean handle(MinecraftClient mc, Throwable t) {
        ByConfig cfg = ByConfig.get();
        if (!cfg.recoverFromCrashes) return false;
        if (t instanceof VirtualMachineError && !(t instanceof StackOverflowError)) return false;
        if (isManual(t)) return false;
        try {
            long now = System.nanoTime();
            long windowNs = cfg.persistentWindowMs * 1_000_000L;
            recent.addLast(now);
            while (!recent.isEmpty() && now - recent.peekFirst() > windowNs) recent.removeFirst();
            if (++total > cfg.maxRecoveriesPerSession) return false;

            boolean firstInWindow = recent.size() == 1;
            boolean persistent = recent.size() >= cfg.persistentThreshold;

            if (firstInWindow || persistent) {
                CrashAnalyzer.Diagnosis d = CrashAnalyzer.analyze(t);
                last = d;
                ByeCrashesClient.LOGGER.error("[ByeCrashes] intercepted: {}", d.shortLine(), t);
                if (!persistent && now - lastNotify > NOTIFY_COOLDOWN_NS) {
                    lastNotify = now;
                    notifyChat(mc, d, CrashFiles.write(d, t));
                }
            }

            if (!persistent) {
                restoreRenderState(mc);
                return true;
            }

            if (lastEscalation != 0L && now - lastEscalation < ESCALATION_COOLDOWN_NS) return false;
            lastEscalation = now;
            recent.clear();
            if (mc.world == null) return false;
            CrashAnalyzer.Diagnosis d = last;
            CrashFiles.write(d, t);
            mc.disconnect();
            mc.setScreen(new DiagnosisScreen(d, new TitleScreen()));
            restoreRenderState(mc);
            return true;
        } catch (Throwable inner) {
            return false;
        }
    }

    private static boolean isManual(Throwable t) {
        Throwable c = t;
        for (int i = 0; c != null && i < 16; i++, c = c.getCause()) {
            String m = c.getMessage();
            if (m != null && m.contains("Manually triggered debug crash")) return true;
        }
        return false;
    }

    private static void notifyChat(MinecraftClient mc, CrashAnalyzer.Diagnosis d, Path file) {
        if (!ByConfig.get().notifyInChat || mc.inGameHud == null) return;
        String tail = file == null ? "" : " (" + file.getFileName() + ")";
        mc.inGameHud.getChatHud().addMessage(
                Text.literal("[ByeCrashes] " + L.t("Prevented a crash. ", "منعت كراش. ") + d.shortLine() + tail).formatted(Formatting.RED));
    }

    private static void restoreRenderState(MinecraftClient mc) {
        try {
            MatrixStack stack = RenderSystem.getModelViewStack();
            while (!stack.isEmpty()) stack.pop();
            RenderSystem.applyModelViewMatrix();
            mc.getFramebuffer().beginWrite(true);
        } catch (Throwable ignored) {
            // best effort
        }
    }
}
