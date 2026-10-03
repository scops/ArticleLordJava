package com.articlelord.summarizer;

import com.articlelord.config.Settings;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;

import java.util.stream.Collectors;

public final class ContentSummarizer {
    // Server-side refusal fallback ("default" routes by refusal category, no model list to maintain)
    private static final String FALLBACK_BETA = "server-side-fallback-2026-07-01";

    private final Settings settings;
    private final AnthropicClient client;

    public ContentSummarizer(Settings settings) {
        this.settings = settings;
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(settings.getAnthropicApiKey())
                .build();
    }

    /**
     * Summarizes web content in Spanish.
     *
     * @param maxTokens token budget for the response (includes the model's reasoning)
     * @throws SummarizationError when the model answers without a usable summary
     */
    public String summarize(String content, String title, int maxTokens) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(settings.getLlmModel())
                .maxTokens(maxTokens)
                .addUserMessage(buildPrompt(content, title));

        // Effort controls reasoning depth (not supported on Haiku 4.5: leave LLM_EFFORT empty)
        if (!settings.getLlmEffort().isEmpty()) {
            params.outputConfig(BetaOutputConfig.builder()
                    .effort(BetaOutputConfig.Effort.of(settings.getLlmEffort()))
                    .build());
        }

        if (settings.isLlmFallbacks()) {
            params.addBeta(FALLBACK_BETA)
                    .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }

        BetaMessage message = client.beta().messages().create(params.build());
        BetaStopReason stopReason = message.stopReason().orElse(null);

        if (BetaStopReason.REFUSAL.equals(stopReason)) {
            throw new SummarizationError("The model declined to summarize this content.");
        }

        // With adaptive thinking the first block may be a (hidden) thinking block
        String summary = message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .collect(Collectors.joining());

        if (BetaStopReason.MAX_TOKENS.equals(stopReason) && summary.isEmpty()) {
            throw new SummarizationError("The token budget ran out before the summary was written.");
        }

        return summary;
    }

    private String buildPrompt(String content, String title) {
        StringBuilder builder = new StringBuilder();
        if (title != null && !title.isBlank()) {
            builder.append("Título: ").append(title).append("\n\n");
        }

        builder.append("Eres un experto en resumir artículos y contenido web.\n\n")
                .append("Por favor, proporciona un resumen claro, conciso e informativo del siguiente contenido EN ESPAÑOL.\n\n")
                .append("Enfócate en:\n")
                .append("- Los puntos principales\n")
                .append("- Las conclusiones clave\n")
                .append("- La información importante\n\n")
                .append("Estructura el resumen con secciones claras si el contenido cubre múltiples temas.\n\n")
                .append("Contenido:\n")
                .append(content)
                .append("\n\n")
                .append("IMPORTANTE: Tu respuesta debe estar completamente en español.\n\n")
                .append("Resumen:");

        return builder.toString();
    }

    /** The model answered but did not produce a usable summary. */
    public static final class SummarizationError extends RuntimeException {
        public SummarizationError(String message) {
            super(message);
        }
    }
}
