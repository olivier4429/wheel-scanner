package com.wheelscanner.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;

/**
 * Configuration centrale. Valeurs lues depuis application.properties.
 * Priorite : variable d environnement > fichier externe > classpath > defaut.
 */
public final class AppConfig {

    private static final Properties props = new Properties();
    private static String loadedFrom = "defaut";

    static {
        loadProperties();
    }

    private AppConfig() {}

    private static void loadProperties() {
        Path external = Path.of("application.properties");
        if (Files.isRegularFile(external)) {
            try (InputStream is = Files.newInputStream(external)) {
                props.load(is);
                loadedFrom = "./application.properties (externe, a cote du jar)";
                return;
            } catch (IOException e) {
                System.err.println("Erreur lecture ./application.properties : " + e.getMessage());
            }
        }
        try (InputStream is = AppConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (is != null) {
                props.load(is);
                loadedFrom = "application.properties (classpath / jar)";
                return;
            }
        } catch (IOException e) {
            System.err.println("Erreur lecture classpath : " + e.getMessage());
        }
        loadedFrom = "aucune (valeurs par defaut)";
        System.err.println("Aucun fichier application.properties trouve.");
    }

    private static String get(String key, String defaultValue) {
        String envKey = key.toUpperCase().replace('.', '_');
        String envValue = System.getenv(envKey);
        if (envValue != null && !envValue.isBlank()) {
            return envValue.trim();
        }
        String v = props.getProperty(key);
        if (v == null || v.isBlank()) {
            // alias eventuels
            if ("telegram.bot.token".equals(key)) {
                v = props.getProperty("TELEGRAM_BOT_TOKEN");
            } else if ("telegram.chat.id".equals(key)) {
                v = props.getProperty("TELEGRAM_CHAT_ID");
            } else if ("groq.api.key".equals(key)) {
                v = props.getProperty("GROQ_API_KEY");
            }
        }
        if (v == null || v.isBlank()) {
            return defaultValue;
        }
        return v.trim();
    }

    private static double getDouble(String key, double defaultValue) {
        try {
            return Double.parseDouble(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static long getLong(String key, long defaultValue) {
        try {
            return Long.parseLong(get(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
    }

    private static String[] loadWatchlist() {
        String raw = get("watchlist",
                "F,NIO,SOFI,AAL,PLUG,NOK,MARA,RIVN,STLA,LCID,PCG,WBD,INTC,SNAP");
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    public static final String[] WATCHLIST = loadWatchlist();

    public static final int MIN_DTE = getInt("min.dte", 25);
    public static final int MAX_DTE = getInt("max.dte", 50);
    public static final double MIN_DELTA = getDouble("min.delta", 0.18);
    public static final double MAX_DELTA = getDouble("max.delta", 0.32);
    public static final long MIN_OPEN_INTEREST = getLong("min.open.interest", 50);
    public static final double MAX_SPREAD_PCT = getDouble("max.spread.pct", 0.35);
    public static final double MIN_ANNUALIZED_YIELD = getDouble("min.annualized.yield", 0.12);

    public static final int MIN_LEAP_DTE = getInt("min.leap.dte", 300);
    public static final double MIN_LEAP_DELTA = getDouble("min.leap.delta", 0.70);
    public static final double MAX_LEAP_DELTA = getDouble("max.leap.delta", 0.85);

    public static final String GROQ_API_KEY = get("groq.api.key", "");
    public static final String GROQ_URL = "https://api.groq.com/openai/v1/chat/completions";
    public static final String GROQ_MODEL = get("groq.model", "llama-3.1-8b-instant");
    public static final int MAX_AI_CALLS_PER_RUN = getInt("max.ai.calls", 8);

    public static final String TELEGRAM_BOT_TOKEN = get("telegram.bot.token", "");
    public static final String TELEGRAM_CHAT_ID = get("telegram.chat.id", "");

    public static final boolean DEBUG = getBoolean("debug", false);

    /** Affiche d ou vient la config et si Telegram/Groq sont presents (sans reveler les secrets). */
    public static void printLoadStatus() {
        System.out.println("Config chargee depuis : " + loadedFrom);
        boolean tgOk = TELEGRAM_BOT_TOKEN != null && !TELEGRAM_BOT_TOKEN.isBlank()
                && !TELEGRAM_BOT_TOKEN.contains("xxxx")
                && TELEGRAM_CHAT_ID != null && !TELEGRAM_CHAT_ID.isBlank()
                && !TELEGRAM_CHAT_ID.contains("xxxx");
        boolean groqOk = GROQ_API_KEY != null && !GROQ_API_KEY.isBlank()
                && !GROQ_API_KEY.contains("xxxx");
        System.out.println("Telegram : " + (tgOk ? "OK (token + chat_id presents)" : "NON configure ou placeholder xxxx"));
        System.out.println("Groq     : " + (groqOk ? "OK (cle presente)" : "NON configure ou placeholder xxxx"));
        if (!tgOk) {
            System.out.println("  -> Verifie les cles exactes : telegram.bot.token et telegram.chat.id");
            System.out.println("  -> Puis : mvn clean package   (sinon le jar garde l ancienne conf)");
            System.out.println("  -> Ou place application.properties a cote du jar et relance");
        }
    }
}
