package com.articlelord.summarizer;

import com.articlelord.config.Settings;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.TypeRef;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ContentSummarizer {
    private static final URI API_URI = URI.create("https://api.anthropic.com/v1/messages");
    private static final String API_VERSION = "2023-06-01";

    private final Settings settings;
    private final HttpClient httpClient;
    private final McpJsonMapper jsonMapper;

    public ContentSummarizer(Settings settings) {
        this.settings = settings;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .build();
        this.jsonMapper = McpJsonMapper.getDefault();
    }

    public String summarize(String content, String title, int maxTokens) {
        String prompt = buildPrompt(content, title);
        String payload = buildPayload(prompt, maxTokens);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(API_URI)
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("x-api-key", settings.getAnthropicApiKey())
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Anthropic API error: " + response.statusCode() + " " + response.body());
            }

            Map<String, Object> responseJson = jsonMapper.readValue(response.body(), new TypeRef<>() {});
            if (responseJson.containsKey("error")) {
                throw new IllegalStateException("Anthropic API error: " + responseJson.get("error"));
            }

            return extractText(responseJson);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to summarize content: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Summarization interrupted", ex);
        }
    }

    private String buildPayload(String prompt, int maxTokens) {
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", List.of(Map.of("type", "text", "text", prompt)));

        Map<String, Object> payload = new HashMap<>();
        payload.put("model", settings.getLlmModel());
        payload.put("max_tokens", maxTokens);
        payload.put("messages", List.of(message));

        try {
            return jsonMapper.writeValueAsString(payload);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize Anthropic request payload", ex);
        }
    }

    private String extractText(Map<String, Object> responseJson) {
        Object content = responseJson.get("content");
        if (content instanceof List<?> contentList && !contentList.isEmpty()) {
            Object first = contentList.get(0);
            if (first instanceof Map<?, ?> firstMap) {
                Object text = firstMap.get("text");
                if (text instanceof String textValue) {
                    return textValue;
                }
            }
        }
        throw new IllegalStateException("Unexpected Anthropic response format: " + responseJson);
    }

    private String buildPrompt(String content, String title) {
        StringBuilder builder = new StringBuilder();
        if (title != null && !title.isBlank()) {
            builder.append("Titulo: ").append(title).append("\n\n");
        }

        builder.append("Eres un experto en resumir articulos y contenido web.\n\n")
                .append("Por favor, proporciona un resumen claro, conciso e informativo del siguiente contenido EN ESPANOL.\n\n")
                .append("Enfocate en:\n")
                .append("- Los puntos principales\n")
                .append("- Las conclusiones clave\n")
                .append("- La informacion importante\n\n")
                .append("Estructura el resumen con secciones claras si el contenido cubre multiples temas.\n\n")
                .append("Contenido:\n")
                .append(content)
                .append("\n\n")
                .append("IMPORTANTE: Tu respuesta debe estar completamente en espanol.\n\n")
                .append("Resumen:");

        return builder.toString();
    }
}
