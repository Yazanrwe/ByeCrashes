package dev.byecrashes;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a Throwable to a cause: a mod, the Java version, memory, graphics.
 * Evidence order: explicit names in the error text, then the first mod frame from the top of the root cause's stack.
 * Frame to mod: ModContainer.findPath on the class file, plus Mixin handler method prefixes (modid$name).
 * The result is "most likely", not proof: a mod frame can be a caller of someone else's bug.
 */
public final class CrashAnalyzer {

    public record Suspect(String id, String name, String version, String role) {
        public String label() {
            return name + " [" + id + " " + version + "]";
        }
    }

    public record Diagnosis(String kind, String headline, String exception, String where,
                            List<Suspect> suspects, String advice, String environment) {
        public String shortLine() {
            return headline;
        }

        public String fullText() {
            StringBuilder sb = new StringBuilder();
            sb.append("ByeCrashes\n=========\n");
            sb.append(headline).append("\n\n");
            sb.append(L.t("Exception: ", "الاستثناء: ")).append(exception).append('\n');
            sb.append(L.t("Thrown at: ", "مكان الخطأ: ")).append(where).append("\n\n");
            if (suspects.isEmpty()) {
                sb.append(L.t("Suspects: no mod found in the stack trace\n", "المشتبه بهم: ما لقيت مود في مسار الخطأ\n"));
            } else {
                sb.append(L.t("Suspects:\n", "المشتبه بهم:\n"));
                int i = 1;
                for (Suspect s : suspects) {
                    sb.append("  ").append(i++).append(". ").append(s.label()).append(" - ").append(s.role()).append('\n');
                }
            }
            sb.append('\n').append(L.t("What to do: ", "الحل: ")).append(advice).append("\n\n");
            sb.append(environment).append('\n');
            return sb.toString();
        }
    }

    private enum Kind { JAVA_VERSION, MEMORY, MIXIN, LINKAGE, GRAPHICS, STACK, MOD, UNKNOWN }

    private static final class Hit {
        final ModContainer mod;
        final StackTraceElement first;
        int frames;

        Hit(ModContainer mod, StackTraceElement first) {
            this.mod = mod;
            this.first = first;
        }
    }

    private static final Set<String> CORE = Set.of("minecraft", "java", "fabricloader", "mixinextras", "byecrashes");
    private static final Set<String> NOT_MOD_TOKENS = Set.of("lambda", "access", "static", "handler", "redirect", "wrapped", "modify");
    private static final Pattern MIXIN_HANDLER = Pattern.compile("^(?:handler\\$[a-z]{3}\\d{3}\\$)?([a-z0-9_\\-]+)\\$.+");
    private static final Pattern FROM_MOD = Pattern.compile("from mod ([A-Za-z0-9_\\-]+)");
    private static final Pattern MIXIN_CFG = Pattern.compile("([A-Za-z0-9_\\-]+)[A-Za-z0-9_\\-.]*\\.mixins\\.json");
    private static final Pattern CLASS_VER = Pattern.compile("class file version (\\d+)");
    private static final Pattern CLASS_IN_MSG = Pattern.compile("^([\\w$./]+) has been compiled");

    private static final Map<String, Optional<ModContainer>> CLASS_CACHE = new HashMap<>();

    private CrashAnalyzer() {}

    public static Diagnosis analyze(Throwable crash) {
        try {
            return analyzeUnsafe(crash);
        } catch (Throwable e) {
            return new Diagnosis("UNKNOWN",
                    L.t("Cause: could not analyze the crash", "السبب: ما قدرت أحلل الكراش"),
                    String.valueOf(crash), "?", List.of(),
                    L.t("Read logs/latest.log and the crash report.", "اقرأ logs/latest.log وتقرير الكراش."),
                    "");
        }
    }

    private static synchronized Diagnosis analyzeUnsafe(Throwable crash) {
        List<Throwable> chain = chain(crash);
        Throwable root = chain.get(chain.size() - 1);
        Map<String, Hit> hits = collectHits(chain);
        Hit primary = pickPrimary(hits);
        ModContainer named = namedMod(chain);
        ModContainer lead = named != null ? named : (primary != null ? primary.mod : null);
        String leadLabel = lead == null ? null : label(lead);
        Kind kind = classify(chain, lead != null);

        List<Suspect> suspects = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (named != null) {
            seen.add(id(named));
            suspects.add(suspect(named, L.t("named in the error text", "مذكور في نص الخطأ")));
        }
        if (primary != null && seen.add(id(primary.mod))) {
            suspects.add(suspect(primary.mod, L.t("first mod in the call chain, at ", "أول مود في مسار الاستدعاء، عند ") + shortFrame(primary.first)));
        }
        int extra = 0;
        for (Hit h : hits.values()) {
            if (extra >= 4) break;
            String hid = id(h.mod);
            if (CORE.contains(hid) || isInfra(hid) || !seen.add(hid)) continue;
            suspects.add(suspect(h.mod, L.t("also in the stack (" + h.frames + " frames)", "موجود بالمسار أيضًا (" + h.frames + " إطار)")));
            extra++;
        }

        String modPart = leadLabel == null ? "" : L.t(" - mod " + leadLabel, " - المود " + leadLabel);
        int cur = Runtime.version().feature();
        long maxMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        String headline;
        String advice;

        switch (kind) {
            case JAVA_VERSION -> {
                Throwable uc = find(chain, UnsupportedClassVersionError.class);
                if (uc != null) {
                    Matcher m = CLASS_VER.matcher(msg(uc));
                    int need = m.find() ? Integer.parseInt(m.group(1)) - 44 : -1;
                    String needEn = need > 0 ? " (needs Java " + need + ", the game runs on Java " + cur + ")" : "";
                    String needAr = need > 0 ? " (يحتاج Java " + need + " واللعبة تشتغل على Java " + cur + ")" : "";
                    headline = L.t("Cause: Java version too old" + modPart + needEn,
                            "السبب: نسخة جافا قديمة" + modPart + needAr);
                    advice = L.t("Install Java " + (need > 0 ? need : 21) + " or newer and select it in your launcher, or use an older version of the mod.",
                            "ثبّت Java " + (need > 0 ? need : 21) + " أو أحدث واختره في اللانشر، أو استخدم نسخة أقدم من المود.");
                } else {
                    headline = L.t("Cause: Java version/module problem (Java " + cur + ")" + modPart,
                            "السبب: مشكلة في نسخة جافا أو وصول الموديولات (Java " + cur + ")" + modPart);
                    advice = L.t("Use the Java version your Minecraft expects (Java 17 is the safe choice for 1.20.1) and update or remove the suspect.",
                            "استخدم نسخة جافا اللي يتوقعها ماينكرافت (Java 17 هي الأضمن لـ 1.20.1) وحدّث المود المشتبه فيه أو احذفه.");
                }
            }
            case MEMORY -> {
                headline = L.t("Cause: out of memory (the game ran out of RAM)", "السبب: نفدت الذاكرة (الرام)");
                advice = L.t("Give the game more RAM in your launcher (JVM argument like -Xmx4G), lower render distance, remove heavy mods or shaders. Current limit: " + maxMb + " MB.",
                        "زد الرام في اللانشر (وسيط JVM مثل -Xmx4G)، قلل مسافة الرسم، أو احذف المودات والشيدرز الثقيلة. الحد الحالي: " + maxMb + " MB.");
            }
            case MIXIN -> {
                headline = L.t("Cause: a mod failed to patch the game (Mixin error)" + modPart,
                        "السبب: مود فشل في تعديل اللعبة (خطأ Mixin)" + modPart);
                advice = L.t("Update that mod to the build made for this Minecraft version, or remove it. If two mods clash, remove one of the other suspects.",
                        "حدّث المود لنسخة تناسب هذه النسخة من ماينكرافت أو احذفه. لو في تعارض بين مودين احذف واحد من المشتبه بهم الباقين.");
            }
            case LINKAGE -> {
                headline = L.t("Cause: mod incompatibility (" + simple(root) + ")" + modPart,
                        "السبب: عدم توافق مود (" + simple(root) + ")" + modPart);
                advice = L.t("A mod was built for a different Minecraft or mod version, or a required library mod is missing. Update or downgrade the suspect and its dependencies, and check the Fabric API version.",
                        "المود مبني لنسخة ماينكرافت أو مود ثاني مختلفة، أو ناقص مود مكتبة مطلوب. حدّث أو نزّل نسخة المود المشتبه فيه ومكتباته، وتأكد من نسخة Fabric API.");
            }
            case GRAPHICS -> {
                headline = L.t("Cause: graphics / OpenGL / native library problem" + modPart,
                        "السبب: مشكلة في الرسوميات / OpenGL / مكتبة native" + modPart);
                advice = L.t("Update your GPU driver and make sure Minecraft runs on the dedicated GPU. If a rendering mod or shader is a suspect, disable it.",
                        "حدّث تعريف كرت الشاشة وتأكد إن ماينكرافت يشتغل على الكرت القوي. ولو مود رسوميات أو شيدرز ضمن المشتبه بهم عطّله.");
            }
            case STACK -> {
                headline = L.t("Cause: infinite recursion (StackOverflowError)" + modPart,
                        "السبب: استدعاء ذاتي لا نهائي (StackOverflowError)" + modPart);
                advice = L.t("Update or remove the suspect, something calls itself forever.",
                        "حدّث المود المشتبه فيه أو احذفه، فيه شي يستدعي نفسه بلا نهاية.");
            }
            case MOD -> {
                headline = L.t("Cause: mod " + leadLabel, "السبب: المود " + leadLabel);
                advice = L.t("Update the mod. If it still crashes, remove it or send the report file to its author.",
                        "حدّث المود، ولو ما زال يكرّش احذفه أو أرسل ملف التقرير لصاحب المود.");
            }
            default -> {
                headline = L.t("Cause: no mod found in the crash (vanilla, Java, GPU driver, a resource pack, or a trace without mod frames)",
                        "السبب: ما لقيت مود في الكراش (ماينكرافت نفسه، جافا، تعريف الكرت، ريسورس باك، أو مسار بدون إطارات مودات)");
                advice = L.t("Check the Java version and GPU driver, disable resource packs and shaders, then remove half of the mods at a time to find the cause.",
                        "تأكد من نسخة جافا وتعريف الكرت، عطّل الريسورس باكات والشيدرز، ثم احذف نص المودات كل مرة لتحديد المسبب.");
            }
        }

        StackTraceElement[] st = root.getStackTrace();
        String where = st.length > 0 ? st[0].toString() : "unknown";
        String exc = root.getClass().getName() + (msg(root).isEmpty() ? "" : ": " + trunc(msg(root), 220));
        return new Diagnosis(kind.name(), headline, exc, where, suspects, advice, environment());
    }

    // ---------- classification ----------

    private static Kind classify(List<Throwable> chain, boolean hasLead) {
        for (Throwable x : chain) {
            if (x instanceof UnsupportedClassVersionError) return Kind.JAVA_VERSION;
            if (x instanceof OutOfMemoryError) return Kind.MEMORY;
        }
        for (Throwable x : chain) {
            String n = x.getClass().getName();
            String m = msg(x);
            if (n.contains("Mixin") || n.contains("InvalidInjection") || m.contains("Mixin apply") || m.contains(".mixins.json")) {
                return Kind.MIXIN;
            }
        }
        for (Throwable x : chain) {
            String n = x.getClass().getName();
            String m = msg(x);
            boolean moduleAccess = n.endsWith("InaccessibleObjectException")
                    || ((x instanceof IllegalAccessError || x instanceof IllegalAccessException)
                    && (m.contains("module") || m.contains("add-opens")));
            boolean javaApiMissing = x instanceof LinkageError
                    && (m.startsWith("java/") || m.startsWith("java.") || m.startsWith("jdk/") || m.startsWith("sun/"));
            if (moduleAccess || javaApiMissing) return Kind.JAVA_VERSION;
        }
        for (Throwable x : chain) {
            String m = msg(x).toLowerCase(Locale.ROOT);
            if (x instanceof UnsatisfiedLinkError || m.contains("glfw") || m.contains("opengl") || m.contains("lwjgl")) {
                return Kind.GRAPHICS;
            }
        }
        for (Throwable x : chain) {
            if (x instanceof LinkageError) return Kind.LINKAGE;
        }
        for (Throwable x : chain) {
            if (x instanceof StackOverflowError) return Kind.STACK;
        }
        return hasLead ? Kind.MOD : Kind.UNKNOWN;
    }

    // ---------- evidence ----------

    private static Map<String, Hit> collectHits(List<Throwable> chain) {
        Map<String, Hit> hits = new LinkedHashMap<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            for (StackTraceElement f : chain.get(i).getStackTrace()) {
                Optional<ModContainer> mc = modOfFrame(f);
                if (mc.isEmpty()) continue;
                Hit h = hits.computeIfAbsent(id(mc.get()), k -> new Hit(mc.get(), f));
                h.frames++;
            }
        }
        return hits;
    }

    private static Hit pickPrimary(Map<String, Hit> hits) {
        for (Hit h : hits.values()) {
            String hid = id(h.mod);
            if (!CORE.contains(hid) && !isInfra(hid)) return h;
        }
        for (Hit h : hits.values()) {
            if (!CORE.contains(id(h.mod))) return h;
        }
        return null;
    }

    private static ModContainer namedMod(List<Throwable> chain) {
        FabricLoader fl = FabricLoader.getInstance();
        for (Throwable x : chain) {
            String m = msg(x);
            if (m.isEmpty()) continue;
            Matcher fm = FROM_MOD.matcher(m);
            if (fm.find()) {
                Optional<ModContainer> c = fl.getModContainer(fm.group(1));
                if (c.isPresent() && !CORE.contains(id(c.get()))) return c.get();
            }
            Matcher cm = MIXIN_CFG.matcher(m);
            if (cm.find()) {
                String token = cm.group(1);
                for (ModContainer c : fl.getAllMods()) {
                    String cid = id(c);
                    if (!CORE.contains(cid) && !isInfra(cid) && (token.equals(cid) || token.startsWith(cid))) return c;
                }
            }
            if (x instanceof UnsupportedClassVersionError) {
                Matcher im = CLASS_IN_MSG.matcher(m);
                if (im.find()) {
                    Optional<ModContainer> c = modOfClass(im.group(1).replace('/', '.'));
                    if (c.isPresent() && !CORE.contains(id(c.get()))) return c.get();
                }
            }
        }
        return null;
    }

    private static Optional<ModContainer> modOfFrame(StackTraceElement f) {
        Matcher m = MIXIN_HANDLER.matcher(f.getMethodName());
        if (m.matches() && !NOT_MOD_TOKENS.contains(m.group(1))) {
            Optional<ModContainer> byMixin = FabricLoader.getInstance().getModContainer(m.group(1));
            if (byMixin.isPresent()) return byMixin;
        }
        return modOfClass(f.getClassName());
    }

    private static Optional<ModContainer> modOfClass(String className) {
        String c = className;
        int lam = c.indexOf("$$Lambda");
        if (lam > 0) c = c.substring(0, lam);
        if (c.startsWith("java.") || c.startsWith("javax.") || c.startsWith("jdk.") || c.startsWith("sun.") || c.startsWith("com.sun.")) {
            return Optional.empty();
        }
        Optional<ModContainer> cached = CLASS_CACHE.get(c);
        if (cached != null) return cached;
        String path = c.replace('.', '/') + ".class";
        Optional<ModContainer> found = Optional.empty();
        Collection<ModContainer> mods = FabricLoader.getInstance().getAllMods();
        for (ModContainer mod : mods) {
            try {
                if (mod.findPath(path).isPresent()) {
                    found = Optional.of(mod);
                    break;
                }
            } catch (RuntimeException ignored) {
                // a container that cannot be read is skipped
            }
        }
        CLASS_CACHE.put(c, found);
        return found;
    }

    // ---------- helpers ----------

    private static List<Throwable> chain(Throwable t) {
        List<Throwable> out = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable c = t;
        while (c != null && seen.add(c) && out.size() < 24) {
            out.add(c);
            c = c.getCause();
        }
        if (out.isEmpty()) out.add(new Throwable("unknown"));
        return out;
    }

    private static Throwable find(List<Throwable> chain, Class<? extends Throwable> type) {
        for (Throwable x : chain) {
            if (type.isInstance(x)) return x;
        }
        return null;
    }

    private static String msg(Throwable t) {
        String m = t.getMessage();
        return m == null ? "" : m;
    }

    private static String simple(Throwable t) {
        return t.getClass().getSimpleName();
    }

    private static String trunc(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static String id(ModContainer m) {
        return m.getMetadata().getId();
    }

    private static String label(ModContainer m) {
        return m.getMetadata().getName() + " [" + id(m) + " " + m.getMetadata().getVersion().getFriendlyString() + "]";
    }

    private static Suspect suspect(ModContainer m, String role) {
        return new Suspect(id(m), m.getMetadata().getName(), m.getMetadata().getVersion().getFriendlyString(), role);
    }

    private static boolean isInfra(String id) {
        return id.equals("fabric-api") || id.startsWith("fabric-") || id.startsWith("fabric_");
    }

    private static String shortFrame(StackTraceElement f) {
        String cn = f.getClassName();
        int dot = cn.lastIndexOf('.');
        return (dot >= 0 ? cn.substring(dot + 1) : cn) + "." + f.getMethodName() + ":" + f.getLineNumber();
    }

    private static String environment() {
        FabricLoader fl = FabricLoader.getInstance();
        String mc = fl.getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
        long maxMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        return "Minecraft " + mc + " | Java " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ") | "
                + L.t("RAM limit ", "حد الرام ") + maxMb + " MB | " + fl.getAllMods().size() + L.t(" mods", " مود");
    }
}
