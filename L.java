package dev.byecrashes;

final class L {
    private L() {}

    static boolean ar() {
        return "ar".equalsIgnoreCase(ByConfig.get().language);
    }

    static String t(String en, String ar) {
        return ar() ? ar : en;
    }
}
