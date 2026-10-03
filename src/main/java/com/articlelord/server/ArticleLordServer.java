package com.articlelord.server;

import com.articlelord.config.Settings;
import com.articlelord.email.EmailSender;
import com.articlelord.playwright.PlaywrightMcpClient;
import com.articlelord.summarizer.ContentSummarizer;
import com.articlelord.summarizer.ContentSummarizer.SummarizationError;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitFormRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * MCP server for ArticleLord (MCP Java SDK v2, protocol 2025-11-25).
 */
public final class ArticleLordServer {
    private static final Logger logger = LoggerFactory.getLogger(ArticleLordServer.class);
    private static final McpJsonMapper JSON_MAPPER = McpJsonDefaults.getMapper();
    private static final String VERSION = "2.0.0";
    private static final int DEFAULT_MAX_TOKENS = 8192;

    private static final String INSTRUCTIONS = """
            ArticleLord is a web article processing server. It can open web pages, \
            extract their content, generate summaries using an LLM, and send those \
            summaries via email. Use 'read_and_send' for the full workflow, or call \
            individual tools (open_webpage, close_popups, read_page_content, \
            summarize_content, send_email) for step-by-step control.""";

    private static final String CLOSE_POPUPS_JS = """
            () => {
                const selectors = [
                    "button[aria-label*='close']",
                    "button[aria-label*='dismiss']",
                    ".modal-close",
                    ".popup-close",
                    "[data-dismiss='modal']"
                ];

                let closed = 0;
                for (const selector of selectors) {
                    const elements = document.querySelectorAll(selector);
                    elements.forEach(el => {
                        if (el.offsetParent !== null) {
                            el.click();
                            closed++;
                        }
                    });
                }
                return closed;
            }
            """;

    private static final String READ_CONTENT_JS = """
            () => {
                const title = document.title;
                const mainSelectors = ['main', 'article', '[role="main"]', '.content', '#content'];
                let mainContent = '';

                for (const selector of mainSelectors) {
                    const element = document.querySelector(selector);
                    if (element) {
                        mainContent = element.innerText;
                        break;
                    }
                }

                if (!mainContent) {
                    mainContent = document.body.innerText;
                }

                mainContent = mainContent.replace(/\\s+/g, ' ').trim();

                return {
                    title: title,
                    content: mainContent,
                    url: window.location.href
                };
            }
            """;

    private final PlaywrightMcpClient playwrightClient;
    private final ContentSummarizer summarizer;
    private final EmailSender emailSender;

    private ArticleLordServer(PlaywrightMcpClient playwrightClient,
                              ContentSummarizer summarizer,
                              EmailSender emailSender) {
        this.playwrightClient = playwrightClient;
        this.summarizer = summarizer;
        this.emailSender = emailSender;
    }

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.load();
        PlaywrightMcpClient playwrightClient = new PlaywrightMcpClient(settings);
        ArticleLordServer app = new ArticleLordServer(
                playwrightClient, new ContentSummarizer(settings), new EmailSender(settings));

        // Lifecycle management: Playwright MCP lives as long as the server does
        logger.info("Starting Playwright MCP server...");
        try {
            playwrightClient.start();
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Could not start Playwright MCP. Make sure Node.js and npm (npx) are installed: https://nodejs.org/", ex);
        }
        logger.info("Playwright MCP server started successfully");

        McpSyncServer server = McpServer.sync(new StdioServerTransportProvider(JSON_MAPPER))
                .serverInfo("ArticleLord", VERSION)
                .instructions(INSTRUCTIONS)
                .capabilities(ServerCapabilities.builder().tools(false).prompts(false).build())
                .toolCall(tool("open_webpage", """
                                Open a webpage in the browser and return an accessibility snapshot of its contents.

                                Use this as the first step before reading or interacting with a page. \
                                The returned snapshot contains the page's accessibility tree, which you can \
                                inspect to understand the page structure. Returns 'status', the requested 'url' \
                                and a 'snapshot' of the page.""", schemaOpenWebpage()),
                        (exchange, request) -> toResult(app.openWebpage(getStringArg(request.arguments(), "url"))))
                .toolCall(tool("close_popups", """
                                Dismiss common popup overlays (cookie banners, modals, newsletter prompts) on the current page.

                                Call this after opening a page if popups are blocking the main content. \
                                It uses heuristic CSS selectors, so not all popups will be caught. Returns \
                                'status', the number of 'popups_closed' and a 'message'.""", schemaNoArgs()),
                        (exchange, request) -> toResult(app.closePopups()))
                .toolCall(tool("read_page_content", """
                                Extract the main text content and title from the currently open page.

                                The tool looks for semantic content containers (main, article, [role='main']) \
                                and falls back to the full body text. A page must already be open via \
                                open_webpage before calling this. Returns 'status' and 'data' with 'title', \
                                'content' and 'url'.""", schemaNoArgs()),
                        (exchange, request) -> toResult(app.readPageContent()))
                .toolCall(tool("summarize_content", """
                                Generate an LLM-powered summary of the provided text content.

                                Use this after extracting page content with read_page_content, or pass any \
                                text you want summarized. The summary is generated in Spanish. Returns \
                                'status' and the generated 'summary' text.""", schemaSummarize()),
                        (exchange, request) -> toResult(app.summarizeContent(
                                getStringArg(request.arguments(), "content"),
                                getOptionalStringArg(request.arguments(), "title"),
                                getOptionalIntArg(request.arguments(), "max_tokens", DEFAULT_MAX_TOKENS))))
                .toolCall(tool("send_email", """
                                Send an email via the configured SMTP server.

                                Returns 'status' and a confirmation 'message'.""", schemaSendEmail()),
                        (exchange, request) -> toResult(app.sendEmail(
                                getStringArg(request.arguments(), "to_email"),
                                getStringArg(request.arguments(), "subject"),
                                getStringArg(request.arguments(), "body"),
                                getOptionalStringArg(request.arguments(), "body_html"))))
                .toolCall(tool("read_and_send", """
                                Full pipeline: open a URL, extract its content, summarize it, and email the summary.

                                This orchestrates open_webpage -> close_popups -> read_page_content -> \
                                summarize_content -> send_email in sequence. Use the individual tools \
                                instead if you need finer control over any step.

                                IMPORTANT - Elicitation step: If `to_email` is not provided, this tool will \
                                trigger an interactive elicitation that prompts the user to enter the \
                                recipient email address before proceeding. The agent must be prepared to \
                                handle this user-facing prompt and wait for the user's response. The user \
                                may accept (providing an email), decline, or cancel the request entirely.

                                Returns 'status', the generated 'summary' and a 'workflow_steps' log.""",
                                schemaReadAndSend()),
                        app::readAndSend)
                .prompts(
                        prompt("summarization_guide",
                                "Prompt template for high-quality web content summarization.",
                                summarizationGuide()),
                        prompt("popup_closing_guide",
                                "Prompt template for intelligently handling popups.",
                                popupClosingGuide()))
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                playwrightClient.close();
                logger.info("Playwright MCP server stopped");
            } catch (Exception ignored) {
                // Best-effort cleanup.
            }
            server.closeGracefully();
        }));

        new CountDownLatch(1).await();
    }

    // Internal functions (not tools) - can be called by other functions

    private Map<String, Object> openWebpage(String url) {
        try {
            playwrightClient.navigate(url);
            String snapshot = playwrightClient.snapshot();

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "success");
            payload.put("url", url);
            payload.put("snapshot", snapshot);
            return payload;
        } catch (IllegalStateException ex) {
            logger.error("Playwright browser failed to navigate to {}", url, ex);
            return error("The browser could not be started or has become unresponsive. "
                    + "Ensure the Playwright MCP subprocess is running and reachable.");
        } catch (Exception ex) {
            logger.error("Unexpected error navigating to {}", url, ex);
            return error("Failed to open the page at the requested URL. "
                    + "The page may be unreachable, or a network issue occurred.");
        }
    }

    private Map<String, Object> closePopups() {
        try {
            Object result = playwrightClient.evaluate(CLOSE_POPUPS_JS);

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "success");
            payload.put("popups_closed", result instanceof Number number ? number.intValue() : 0);
            payload.put("message", "Attempted to close common popups");
            return payload;
        } catch (Exception ex) {
            logger.error("Error while attempting to close popups", ex);
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "warning");
            payload.put("message", "Could not close popups — the page may not have any, "
                    + "or the browser session may be unavailable.");
            return payload;
        }
    }

    private Map<String, Object> readPageContent() {
        try {
            Object result = playwrightClient.evaluate(READ_CONTENT_JS);

            Map<String, Object> data;
            if (result instanceof Map<?, ?> map) {
                data = toStringObjectMap(map);
            } else {
                data = new HashMap<>();
                data.put("title", "");
                data.put("content", String.valueOf(result));
                data.put("url", "");
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "success");
            payload.put("data", data);
            return payload;
        } catch (Exception ex) {
            logger.error("Error extracting page content", ex);
            return error("Failed to extract content from the current page. "
                    + "Make sure a page has been opened first with open_webpage.");
        }
    }

    private Map<String, Object> summarizeContent(String content, String title, int maxTokens) {
        try {
            String summary = summarizer.summarize(content, title, maxTokens);

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "success");
            payload.put("summary", summary);
            return payload;
        } catch (SummarizationError ex) {
            logger.warn("Summarization produced no usable output: {}", ex.getMessage());
            return error(ex.getMessage());
        } catch (UnauthorizedException | PermissionDeniedException ex) {
            logger.error("Anthropic API authentication failed", ex);
            return error("The summarization service could not authenticate. "
                    + "Check that the Anthropic API key is valid and has sufficient permissions.");
        } catch (AnthropicServiceException ex) {
            logger.error("Anthropic API request failed", ex);
            return error("The summarization service returned an error. "
                    + "This may be due to rate limits, content length, or a temporary outage.");
        } catch (Exception ex) {
            logger.error("Unexpected error during summarization", ex);
            return error("An unexpected error occurred while generating the summary.");
        }
    }

    private Map<String, Object> sendEmail(String toEmail, String subject, String body, String bodyHtml) {
        try {
            return emailSender.sendEmail(toEmail, subject, body, bodyHtml);
        } catch (Exception ex) {
            logger.error("Failed to send email to {}", toEmail, ex);
            return error("Could not send the email. "
                    + "Verify SMTP configuration and that the mail server is reachable.");
        }
    }

    /**
     * Asks the user for the recipient address through MCP elicitation.
     *
     * <p>With the 2025-11-25 protocol the server can send an {@code elicitation/create}
     * request back to the client in the middle of a tool call and wait for the answer.
     * (The 2026-07-28 spec drops that back channel; the Python version uses
     * {@code Resolve} + {@code Elicit} instead.)
     *
     * @return the email, or {@code null} if a cancellation payload was put in {@code outcome}
     */
    private static String askRecipient(McpSyncServerExchange exchange, Map<String, Object> outcome) {
        if (exchange.getClientCapabilities() == null || exchange.getClientCapabilities().elicitation() == null) {
            outcome.putAll(error("No recipient given and this client does not support elicitation. "
                    + "Call read_and_send again with the 'to_email' argument."));
            return null;
        }

        Map<String, Object> requestedSchema = Map.of(
                "type", "object",
                "properties", Map.of("email", Map.of(
                        "type", "string",
                        "format", "email",
                        "title", "Email",
                        "description", "Recipient email address")),
                "required", List.of("email"));

        ElicitResult answer = exchange.createElicitation(ElicitFormRequest.builder(
                "What email address should the article summary be sent to?", requestedSchema).build());

        switch (answer.action()) {
            case ACCEPT -> {
                Object email = answer.content() != null ? answer.content().get("email") : null;
                if (email != null && !email.toString().isBlank()) {
                    return email.toString().trim();
                }
                outcome.put("status", "cancelled");
                outcome.put("message", "The user did not provide an email address.");
            }
            case DECLINE -> {
                outcome.put("status", "cancelled");
                outcome.put("message", "The user declined to provide an email address.");
            }
            default -> {
                outcome.put("status", "cancelled");
                outcome.put("message", "The user cancelled the request.");
            }
        }
        return null;
    }

    private CallToolResult readAndSend(McpSyncServerExchange exchange, CallToolRequest request) {
        String url = getStringArg(request.arguments(), "url");
        String toEmail = getOptionalStringArg(request.arguments(), "to_email");
        boolean closePopupsFirst = getOptionalBooleanArg(request.arguments(), "close_popups_first", true);
        String emailSubject = getOptionalStringArg(request.arguments(), "email_subject");

        List<Map<String, Object>> workflowSteps = new ArrayList<>();

        try {
            // Recipient: from `to_email` or, if missing, from the user's answer
            if (toEmail == null || toEmail.isBlank()) {
                Map<String, Object> outcome = new HashMap<>();
                toEmail = askRecipient(exchange, outcome);
                if (toEmail == null) {
                    return toResult(outcome);
                }
            }

            // Step 1: Open webpage
            Map<String, Object> openResult = openWebpage(url);
            workflowSteps.add(step("open_webpage", openResult));

            if (!"success".equals(openResult.get("status"))) {
                return toResult(workflowError(
                        "Failed to open the webpage. Check the URL and try again.", workflowSteps));
            }

            // Step 2: Close popups if requested
            if (closePopupsFirst) {
                sleep(2000);
                workflowSteps.add(step("close_popups", closePopups()));
                sleep(1000);
            }

            // Step 3: Read content
            Map<String, Object> contentResult = readPageContent();
            workflowSteps.add(step("read_content", contentResult));

            if (!"success".equals(contentResult.get("status"))) {
                return toResult(workflowError(
                        "Could not extract content from the page after opening it.", workflowSteps));
            }

            Map<?, ?> pageData = contentResult.get("data") instanceof Map<?, ?> map ? map : Map.of();
            String pageTitle = String.valueOf(pageData.get("title") == null ? "" : pageData.get("title"));
            String pageContent = String.valueOf(pageData.get("content") == null ? "" : pageData.get("content"));

            // Step 4: Summarize
            Map<String, Object> summaryResult = summarizeContent(pageContent, pageTitle, DEFAULT_MAX_TOKENS);
            workflowSteps.add(step("summarize", summaryResult));

            if (!"success".equals(summaryResult.get("status"))) {
                return toResult(workflowError("Content was extracted but summarization failed.", workflowSteps));
            }

            String summaryText = String.valueOf(summaryResult.get("summary"));

            // Step 5: Send email
            String subject = emailSubject != null && !emailSubject.isBlank()
                    ? emailSubject
                    : "Summary: " + (pageTitle.isBlank() ? url : pageTitle);

            String emailBody = """
                    Article Summary
                    ================

                    Original URL: %s
                    Title: %s

                    Summary:
                    %s

                    ---
                    Sent by ArticleLord MCP Server
                    """.formatted(url, pageTitle, summaryText);

            Map<String, Object> emailResult = sendEmail(toEmail, subject, emailBody, null);
            workflowSteps.add(step("send_email", emailResult));

            if (!"success".equals(emailResult.get("status"))) {
                Map<String, Object> payload = workflowError(
                        "The article was summarized successfully but the email could not be sent. "
                                + "The summary is still available in the workflow_steps.", workflowSteps);
                payload.put("summary", summaryText);
                return toResult(payload);
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "success");
            payload.put("message", "Successfully processed " + url + " and sent summary to " + toEmail);
            payload.put("summary", summaryText);
            payload.put("workflow_steps", workflowSteps);
            return toResult(payload);
        } catch (Exception ex) {
            logger.error("Unexpected error in read_and_send workflow for {}", url, ex);
            return toResult(workflowError("An unexpected error interrupted the workflow.", workflowSteps));
        }
    }

    // Prompts for guiding the model

    private static String summarizationGuide() {
        return """
                You are an expert at summarizing web articles and content.

                When summarizing, follow these guidelines:
                1. Extract the main topic and key points
                2. Identify important facts, statistics, or quotes
                3. Note any actionable insights or conclusions
                4. Organize information logically with clear sections
                5. Keep the summary concise but comprehensive
                6. Maintain objectivity and accuracy
                7. Highlight any unique or surprising information

                Structure your summary with:
                - Brief overview (2-3 sentences)
                - Key points (bullet points)
                - Important details (if applicable)
                - Conclusion or takeaway

                Adapt the level of detail based on the content type and complexity.
                """;
    }

    private static String popupClosingGuide() {
        return """
                You are helping to close popup dialogs and overlays on web pages.

                Common popup patterns to look for:
                - Cookie consent banners (look for "Accept", "Aceptar", "OK")
                - Newsletter signup forms (look for "Close", "No thanks", "Maybe later")
                - App download prompts (look for "Continue in browser", "Dismiss")
                - Survey requests (look for "Close", "Not now")
                - Age verification (look for appropriate age confirmation)

                Guidelines:
                1. Prefer "Accept" for cookie banners to avoid repeated prompts
                2. Use "Close" or "Dismiss" for promotional popups
                3. Look for X buttons or close icons
                4. Check for overlay backgrounds that can be clicked
                5. Avoid buttons that might navigate away from the content
                6. Be cautious with buttons that might subscribe or sign up

                Always prioritize maintaining access to the main content.
                """;
    }

    // MCP plumbing

    private static Tool tool(String name, String description, String inputSchema) {
        return Tool.builder(name, JSON_MAPPER, inputSchema)
                .description(description)
                .build();
    }

    private static McpServerFeatures.SyncPromptSpecification prompt(String name, String description, String text) {
        Prompt prompt = Prompt.builder(name).description(description).build();
        GetPromptResult result = GetPromptResult.builder(
                        List.of(PromptMessage.builder(Role.USER, new TextContent(text)).build()))
                .description(description)
                .build();
        return new McpServerFeatures.SyncPromptSpecification(prompt, (exchange, request) -> result);
    }

    /**
     * Wraps a payload as structured content plus its JSON text, so hosts that only
     * read {@code content} still see the result. Errors set {@code isError}.
     */
    private static CallToolResult toResult(Map<String, Object> payload) {
        String json;
        try {
            json = JSON_MAPPER.writeValueAsString(payload);
        } catch (IOException ex) {
            json = String.valueOf(payload);
        }
        return CallToolResult.builder()
                .structuredContent(payload)
                .addTextContent(json)
                .isError("error".equals(payload.get("status")))
                .build();
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "error");
        payload.put("message", message);
        return payload;
    }

    private static Map<String, Object> workflowError(String message, List<Map<String, Object>> workflowSteps) {
        Map<String, Object> payload = error(message);
        payload.put("workflow_steps", workflowSteps);
        return payload;
    }

    private static Map<String, Object> step(String name, Map<String, Object> result) {
        Map<String, Object> step = new HashMap<>();
        step.put("step", name);
        step.put("result", result);
        return step;
    }

    private static String getStringArg(Map<String, Object> args, String key) {
        Object value = args != null ? args.get(key) : null;
        if (value == null) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return value.toString();
    }

    private static String getOptionalStringArg(Map<String, Object> args, String key) {
        Object value = args != null ? args.get(key) : null;
        return value == null ? null : value.toString();
    }

    private static int getOptionalIntArg(Map<String, Object> args, String key, int fallback) {
        Object value = args != null ? args.get(key) : null;
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean getOptionalBooleanArg(Map<String, Object> args, String key, boolean fallback) {
        Object value = args != null ? args.get(key) : null;
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text && !text.isBlank()) {
            return Boolean.parseBoolean(text);
        }
        return fallback;
    }

    private static Map<String, Object> toStringObjectMap(Map<?, ?> map) {
        Map<String, Object> converted = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                converted.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return converted;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // Input schemas (JSON Schema 2020-12, validated by the SDK)

    private static String schemaOpenWebpage() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "url": {
                      "type": "string",
                      "description": "The full URL to open (must include the scheme, e.g. https://)"
                    }
                  },
                  "required": ["url"],
                  "additionalProperties": false
                }
                """;
    }

    private static String schemaNoArgs() {
        return """
                {
                  "type": "object",
                  "properties": {},
                  "additionalProperties": false
                }
                """;
    }

    private static String schemaSummarize() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "content": {"type": "string", "description": "The text content to summarize"},
                    "title": {"type": "string", "description": "Optional title to give the LLM additional context"},
                    "max_tokens": {
                      "type": "integer",
                      "description": "Token budget for the response, reasoning included (default 8192)",
                      "default": 8192
                    }
                  },
                  "required": ["content"],
                  "additionalProperties": false
                }
                """;
    }

    private static String schemaSendEmail() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "to_email": {"type": "string", "description": "Recipient email address"},
                    "subject": {"type": "string", "description": "Email subject line"},
                    "body": {"type": "string", "description": "Plain-text email body"},
                    "body_html": {"type": "string", "description": "Optional HTML version of the email body"}
                  },
                  "required": ["to_email", "subject", "body"],
                  "additionalProperties": false
                }
                """;
    }

    private static String schemaReadAndSend() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "url": {"type": "string", "description": "The article URL to process"},
                    "to_email": {
                      "type": "string",
                      "description": "Recipient email address for the summary. If omitted, the tool will interactively ask the user for it via an elicitation prompt"
                    },
                    "close_popups_first": {
                      "type": "boolean",
                      "description": "Whether to try dismissing popups before reading (default true)",
                      "default": true
                    },
                    "email_subject": {
                      "type": "string",
                      "description": "Custom email subject; defaults to 'Summary: <page title>'"
                    }
                  },
                  "required": ["url"],
                  "additionalProperties": false
                }
                """;
    }
}
