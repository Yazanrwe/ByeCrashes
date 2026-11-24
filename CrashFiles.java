package dev.byecrashes;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

final class CrashFiles {
    private CrashFiles() {}

    /** Writes crash-reports/byecrashes-yyyyMMdd-HHmmss.txt. Null when the write fails. */
    static Path write(CrashAnalyzer.Diagnosis d, Throwable t) {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("crash-reports");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path f = dir.resolve("byecrashes-" + stamp + ".txt");
            StringWriter sw = new StringWriter();
            if (t != null) t.printStackTrace(new PrintWriter(sw));
            Files.writeString(f, d.fullText() + "\n---- stack trace ----\n" + sw, StandardCharsets.UTF_8);
            return f;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
