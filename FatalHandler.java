package dev.byecrashes;

import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.ComponentOrientation;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Real crashes only: the game is about to exit, so the diagnosis goes to a file, stderr and a native dialog.
 * Nothing in here may throw into the vanilla crash path.
 */
public final class FatalHandler {
    private static Throwable last;

    private FatalHandler() {}

    public static synchronized void onCrashReport(Throwable cause) {
        if (cause == null || cause == last) return;
        last = cause;
        try {
            CrashAnalyzer.Diagnosis d = CrashAnalyzer.analyze(cause);
            Path file = CrashFiles.write(d, cause);
            System.err.println("[ByeCrashes] " + d.shortLine());
            if (file != null) System.err.println("[ByeCrashes] details: " + file);
            if (ByConfig.get().fatalDialog) showDialog(d, file);
        } catch (Throwable ignored) {
            // never break the crash path
        }
    }

    private static void showDialog(CrashAnalyzer.Diagnosis d, Path file) {
        try {
            if (GraphicsEnvironment.isHeadless()) return;
            // AWT next to GLFW on macOS needs the first thread, so the dialog is skipped there
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) return;

            String text = d.fullText() + (file == null ? "" : "\n" + L.t("Saved: ", "انحفظ في: ") + file);
            SwingUtilities.invokeAndWait(() -> {
                JTextArea area = new JTextArea(text);
                area.setEditable(false);
                area.setLineWrap(true);
                area.setWrapStyleWord(true);
                if (L.ar()) area.applyComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
                JScrollPane pane = new JScrollPane(area);
                pane.setPreferredSize(new Dimension(660, 400));
                Object[] options = {L.t("Copy report", "نسخ التقرير"), L.t("Close", "إغلاق")};
                int choice = JOptionPane.showOptionDialog(null, pane, "ByeCrashes",
                        JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, options, options[1]);
                if (choice == 0) {
                    try {
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
                    } catch (Throwable ignored) {
                        // clipboard unavailable
                    }
                }
            });
        } catch (Throwable ignored) {
            // no display, no dialog
        }
    }
}
