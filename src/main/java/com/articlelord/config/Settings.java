package com.articlelord.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class Settings {
    private final String anthropicApiKey;
    private final String llmModel;
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUsername;
    private final String smtpPassword;
    private final String emailFrom;
    private final String llmEffort;
    private final boolean llmFallbacks;
    private final boolean playwrightHeadless;
    private final String playwrightMcpCommand;
    private final String playwrightMcpArgs;

    private Settings(String anthropicApiKey,
                     String llmModel,
                     String smtpHost,
                     int smtpPort,
                     String smtpUsername,
                     String smtpPassword,
                     String emailFrom,
                     String llmEffort,
                     boolean llmFallbacks,
                     boolean playwrightHeadless,
                     String playwrightMcpCommand,
                     String playwrightMcpArgs) {
        this.anthropicApiKey = anthropicApiKey;
        this.llmModel = llmModel;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUsername = smtpUsername;
        this.smtpPassword = smtpPassword;
        this.emailFrom = emailFrom;
        this.llmEffort = llmEffort;
        this.llmFallbacks = llmFallbacks;
        this.playwrightHeadless = playwrightHeadless;
        this.playwrightMcpCommand = playwrightMcpCommand;
        this.playwrightMcpArgs = playwrightMcpArgs;
    }

    public static Settings load() {
        Map<String, String> env = new HashMap<>(System.getenv());
        Map<String, String> dotEnv = readDotEnv();
        dotEnv.forEach(env::putIfAbsent);

        String anthropicApiKey = require(env, "ANTHROPIC_API_KEY");
        String llmModel = get(env, "LLM_MODEL", "claude-sonnet-5-5");
        // low | medium | high | xhigh | max; empty for Haiku 4.5
        String llmEffort = get(env, "LLM_EFFORT", "medium").trim();
        // Server-side refusal fallback (Sonnet 5.5 / Opus 5.x / Fable 5.1)
        boolean llmFallbacks = parseBoolean(get(env, "LLM_FALLBACKS", "true"));
        String smtpHost = require(env, "SMTP_HOST");
        int smtpPort = parseInt(get(env, "SMTP_PORT", "2525"), 2525);
        String smtpUsername = require(env, "SMTP_USERNAME");
        String smtpPassword = require(env, "SMTP_PASSWORD");
        String emailFrom = require(env, "EMAIL_FROM");
        // Set to true in Docker or any machine without a display
        boolean playwrightHeadless = parseBoolean(get(env, "PLAYWRIGHT_HEADLESS", "false"));
        String playwrightMcpCommand = get(env, "PLAYWRIGHT_MCP_COMMAND", null);
        String playwrightMcpArgs = get(env, "PLAYWRIGHT_MCP_ARGS", null);

        return new Settings(
                anthropicApiKey,
                llmModel,
                smtpHost,
                smtpPort,
                smtpUsername,
                smtpPassword,
                emailFrom,
                llmEffort,
                llmFallbacks,
                playwrightHeadless,
                playwrightMcpCommand,
                playwrightMcpArgs
        );
    }

    private static Map<String, String> readDotEnv() {
        Map<String, String> values = new HashMap<>();
        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            envPath = Path.of("..", ".env");
        }
        if (!Files.exists(envPath)) {
            return Map.of();
        }

        try {
            for (String line : Files.readAllLines(envPath)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int idx = trimmed.indexOf('=');
                if (idx <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, idx).trim();
                String value = trimmed.substring(idx + 1).trim();
                if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                    value = value.substring(1, value.length() - 1);
                }
                values.putIfAbsent(key, value);
            }
        } catch (IOException ignored) {
            return Map.of();
        }
        return values;
    }

    private static String require(Map<String, String> env, String key) {
        String value = get(env, key, null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required setting: " + key);
        }
        return value.trim();
    }

    private static String get(Map<String, String> env, String key, String fallback) {
        if (env.containsKey(key)) {
            return env.get(key);
        }
        String upper = key.toUpperCase();
        if (env.containsKey(upper)) {
            return env.get(upper);
        }
        String lower = key.toLowerCase();
        if (env.containsKey(lower)) {
            return env.get(lower);
        }
        return fallback;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes");
    }

    public String getAnthropicApiKey() {
        return anthropicApiKey;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public String getSmtpHost() {
        return smtpHost;
    }

    public int getSmtpPort() {
        return smtpPort;
    }

    public String getSmtpUsername() {
        return smtpUsername;
    }

    public String getSmtpPassword() {
        return smtpPassword;
    }

    public String getEmailFrom() {
        return emailFrom;
    }

    public String getLlmEffort() {
        return llmEffort;
    }

    public boolean isLlmFallbacks() {
        return llmFallbacks;
    }

    public boolean isPlaywrightHeadless() {
        return playwrightHeadless;
    }

    public String getPlaywrightMcpCommand() {
        return playwrightMcpCommand;
    }

    public String getPlaywrightMcpArgs() {
        return playwrightMcpArgs;
    }
}
