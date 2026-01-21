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

    private Settings(String anthropicApiKey,
                     String llmModel,
                     String smtpHost,
                     int smtpPort,
                     String smtpUsername,
                     String smtpPassword,
                     String emailFrom) {
        this.anthropicApiKey = anthropicApiKey;
        this.llmModel = llmModel;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUsername = smtpUsername;
        this.smtpPassword = smtpPassword;
        this.emailFrom = emailFrom;
    }

    public static Settings load() {
        Map<String, String> env = new HashMap<>(System.getenv());
        Map<String, String> dotEnv = readDotEnv();
        dotEnv.forEach(env::putIfAbsent);

        String anthropicApiKey = require(env, "ANTHROPIC_API_KEY");
        String llmModel = get(env, "LLM_MODEL", "claude-3-5-sonnet-20241022");
        String smtpHost = require(env, "SMTP_HOST");
        int smtpPort = parseInt(get(env, "SMTP_PORT", "2525"), 2525);
        String smtpUsername = require(env, "SMTP_USERNAME");
        String smtpPassword = require(env, "SMTP_PASSWORD");
        String emailFrom = require(env, "EMAIL_FROM");

        return new Settings(
                anthropicApiKey,
                llmModel,
                smtpHost,
                smtpPort,
                smtpUsername,
                smtpPassword,
                emailFrom
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
}
