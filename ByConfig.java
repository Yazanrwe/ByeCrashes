package dev.byecrashes;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ByConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ByConfig instance = new ByConfig();

    // "en" or "ar". In-game screens do not shape Arabic glyphs, the crash dialog and the report file do.
    public String language = "en";
    public boolean recoverFromCrashes = true;
    // this many failures inside persistentWindowMs = the error is not going away: back to the title screen
    public int persistentThreshold = 6;
    public int persistentWindowMs = 3000;
    public int maxRecoveriesPerSession = 2000;
    public boolean notifyInChat = true;
    public boolean fatalDialog = true;
    public boolean memoryWarning = true;
    public int memoryWarnPercent = 85;

    public static ByConfig get() {
        return instance;
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("byecrashes.json");
    }

    public static void load() {
        Path p = path();
        ByConfig loaded = null;
        if (Files.isRegularFile(p)) {
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                loaded = GSON.fromJson(r, ByConfig.class);
            } catch (IOException | RuntimeException e) {
                System.err.println("[ByeCrashes] config unreadable, using defaults: " + e);
            }
        }
        instance = loaded != null ? loaded : new ByConfig();
        instance.clamp();
        try {
            Files.writeString(p, GSON.toJson(instance), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[ByeCrashes] config not saved: " + e);
        }
    }

    private void clamp() {
        if (language == null) language = "en";
        persistentThreshold = Math.max(2, Math.min(60, persistentThreshold));
        persistentWindowMs = Math.max(500, Math.min(30000, persistentWindowMs));
        maxRecoveriesPerSession = Math.max(1, maxRecoveriesPerSession);
        memoryWarnPercent = Math.max(50, Math.min(99, memoryWarnPercent));
    }
}
