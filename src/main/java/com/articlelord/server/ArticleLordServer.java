package com.articlelord.server;

import com.articlelord.config.Settings;
import com.articlelord.email.EmailSender;
import com.articlelord.playwright.PlaywrightMcpClient;
import com.articlelord.summarizer.ContentSummarizer;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

public final class ArticleLordServer {
    private static final McpJsonMapper JSON_MAPPER = McpJsonMapper.getDefault();

    public static void main(String[] args) throws Exception {
        Settings settings = Settings.load();
        PlaywrightMcpClient playwrightClient = new PlaywrightMcpClient();
        ContentSummarizer summarizer = new ContentSummarizer(settings);
        EmailSender emailSender = new EmailSender(settings);

        StdioServerTransportProvider transportProvider = new StdioServerTransportProvider(JSON_MAPPER);

        Tool openWebpageTool = Tool.builder()
                .name("open_webpage")
                .description("Open a webpage using Playwright.")
                .inputSchema(JSON_MAPPER, schemaOpenWebpage())
                .build();

        Tool closePopupsTool = Tool.builder()
                .name("close_popups")
                .description("Attempt to close common popup elements on the page.")
                .inputSchema(JSON_MAPPER, schemaNoArgs())
                .build();

        Tool readPageContentTool = Tool.builder()
                .name("read_page_content")
                .description("Read and extract the main content from the current page.")
                .inputSchema(JSON_MAPPER, schemaNoArgs())
                .build();

        Tool summarizeTool = Tool.builder()
                .name("summarize_content")
                .description("Summarize web content using Claude.")
                .inputSchema(JSON_MAPPER, schemaSummarize())
                .build();

        Tool sendEmailTool = Tool.builder()
                .name("send_email")
                .description("Send an email with content.")
                .inputSchema(JSON_MAPPER, schemaSendEmail())
                .build();

        Tool readAndSendTool = Tool.builder()
                .name("read_and_send")
                .description("Open a URL, summarize it, and send the summary via email.")
                .inputSchema(JSON_MAPPER, schemaReadAndSend())
                .build();

        Prompt summarizationGuide = new Prompt(
                "summarization_guide",
                "Prompt template for high-quality web content summarization.",
                List.of()
        );

        Prompt popupClosingGuide = new Prompt(
                "popup_closing_guide",
                "Prompt template for intelligently handling popups.",
                List.of()
        );

        McpSyncServer server = McpServer.sync(transportProvider)
                .serverInfo("ArticleLord", "1.0.0")
                .capabilities(ServerCapabilities.builder().tools(false).prompts(false).build())
                .toolCall(openWebpageTool, (exchange, request) -> openWebpage(playwrightClient, request))
                .toolCall(closePopupsTool, (exchange, request) -> closePopups(playwrightClient))
                .toolCall(readPageContentTool, (exchange, request) -> readPageContent(playwrightClient))
                .toolCall(summarizeTool, (exchange, request) -> summarizeContent(summarizer, request))
                .toolCall(sendEmailTool, (exchange, request) -> sendEmail(emailSender, request))
                .toolCall(readAndSendTool, (exchange, request) -> readAndSend(playwrightClient, summarizer, emailSender, request))
                .prompts(
                        new McpServerFeatures.SyncPromptSpecification(summarizationGuide,
                                (exchange, promptRequest) -> buildPromptResult(summarizationGuide(),
                                        "Summarization guide prompt")),
                        new McpServerFeatures.SyncPromptSpecification(popupClosingGuide,
                                (exchange, promptRequest) -> buildPromptResult(popupClosingGuide(),
                                        "Popup closing guide prompt"))
                )
                .build();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                playwrightClient.close();
            } catch (Exception ignored) {
                // Best-effort cleanup.
            }
            server.closeGracefully();
        }));

        new CountDownLatch(1).await();
    }

    private static CallToolResult openWebpage(PlaywrightMcpClient playwrightClient, CallToolRequest request) {
        String url = getStringArg(request.arguments(), "url");
        try {
            Map<String, Object> navigateResult = playwrightClient.navigate(url);
            Map<String, Object> snapshot = playwrightClient.snapshot();

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "success");
            payload.put("url", url);
            payload.put("navigate", navigateResult);
            payload.put("snapshot", snapshot);

            return success(payload);
        } catch (Exception ex) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "error");
            payload.put("message", ex.getMessage());
            return failure(payload, ex.getMessage());
        }
    }

    private static CallToolResult closePopups(PlaywrightMcpClient playwrightClient) {
        String jsCode = """
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

        try {
            Map<String, Object> evalResult = playwrightClient.evaluate(jsCode);
            int closed = extractInt(evalResult.get("result"));

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "success");
            payload.put("popups_closed", closed);
            payload.put("message", "Attempted to close common popups");

            return success(payload);
        } catch (Exception ex) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "warning");
            payload.put("message", "Popup closing attempted but encountered error: " + ex.getMessage());
            return success(payload);
        }
    }

    private static CallToolResult readPageContent(PlaywrightMcpClient playwrightClient) {
        String jsCode = """
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

                    mainContent = mainContent.replace(/\s+/g, ' ').trim();

                    return {
                        title: title,
                        content: mainContent,
                        url: window.location.href
                    };
                }
                """;

        try {
            Map<String, Object> evalResult = playwrightClient.evaluate(jsCode);
            Object result = evalResult.containsKey("result") ? evalResult.get("result") : evalResult;

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "success");
            payload.put("data", result);

            return success(payload);
        } catch (Exception ex) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "error");
            payload.put("message", ex.getMessage());
            return failure(payload, ex.getMessage());
        }
    }

    private static CallToolResult summarizeContent(ContentSummarizer summarizer, CallToolRequest request) {
        String content = getStringArg(request.arguments(), "content");
        String title = getOptionalStringArg(request.arguments(), "title");
        int maxTokens = getOptionalIntArg(request.arguments(), "max_tokens", 1024);

        try {
            String summary = summarizer.summarize(content, title, maxTokens);
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "success");
            payload.put("summary", summary);
            return success(payload);
        } catch (Exception ex) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "error");
            payload.put("message", ex.getMessage());
            return failure(payload, ex.getMessage());
        }
    }

    private static CallToolResult sendEmail(EmailSender emailSender, CallToolRequest request) {
        String toEmail = getStringArg(request.arguments(), "to_email");
        String subject = getStringArg(request.arguments(), "subject");
        String body = getStringArg(request.arguments(), "body");
        String bodyHtml = getOptionalStringArg(request.arguments(), "body_html");

        Map<String, Object> result = emailSender.sendEmail(toEmail, subject, body, bodyHtml);
        return success(result);
    }

    private static CallToolResult readAndSend(PlaywrightMcpClient playwrightClient,
                                              ContentSummarizer summarizer,
                                              EmailSender emailSender,
                                              CallToolRequest request) {
        String url = getStringArg(request.arguments(), "url");
        String toEmail = getStringArg(request.arguments(), "to_email");
        boolean closePopupsFirst = getOptionalBooleanArg(request.arguments(), "close_popups_first", true);
        String emailSubject = getOptionalStringArg(request.arguments(), "email_subject");

        List<Map<String, Object>> workflowSteps = new ArrayList<>();

        try {
            Map<String, Object> openResult = asStructured(openWebpage(playwrightClient, new CallToolRequest("open_webpage",
                    Map.of("url", url))));
            workflowSteps.add(step("open_webpage", openResult));

            if (!"success".equals(openResult.get("status"))) {
                return failure(workflowError("Failed to open webpage", workflowSteps), "Failed to open webpage");
            }

            if (closePopupsFirst) {
                sleep(2000);
                Map<String, Object> popupResult = asStructured(closePopups(playwrightClient));
                workflowSteps.add(step("close_popups", popupResult));
                sleep(1000);
            }

            Map<String, Object> contentResult = asStructured(readPageContent(playwrightClient));
            workflowSteps.add(step("read_content", contentResult));

            if (!"success".equals(contentResult.get("status"))) {
                return failure(workflowError("Failed to read page content", workflowSteps),
                        "Failed to read page content");
            }

            Object data = contentResult.get("data");
            Map<String, Object> dataMap = data instanceof Map<?, ?> map ? toStringObjectMap(map) : Map.of();
            String pageTitle = dataMap.getOrDefault("title", "").toString();
            String pageContent = dataMap.getOrDefault("content", "").toString();

            CallToolRequest summarizeRequest = new CallToolRequest("summarize_content",
                    Map.of("content", pageContent, "title", pageTitle));
            Map<String, Object> summaryResult = asStructured(summarizeContent(summarizer, summarizeRequest));
            workflowSteps.add(step("summarize", summaryResult));

            if (!"success".equals(summaryResult.get("status"))) {
                return failure(workflowError("Failed to summarize content", workflowSteps),
                        "Failed to summarize content");
            }

            String summaryText = summaryResult.getOrDefault("summary", "").toString();
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

            CallToolRequest emailRequest = new CallToolRequest("send_email",
                    Map.of("to_email", toEmail, "subject", subject, "body", emailBody));
            Map<String, Object> emailResult = asStructured(sendEmail(emailSender, emailRequest));
            workflowSteps.add(step("send_email", emailResult));

            if (!"success".equals(emailResult.get("status"))) {
                return failure(workflowError("Failed to send email", workflowSteps), "Failed to send email");
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "success");
            payload.put("message", "Successfully processed " + url + " and sent summary to " + toEmail);
            payload.put("summary", summaryText);
            payload.put("workflow_steps", workflowSteps);
            return success(payload);
        } catch (Exception ex) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("status", "error");
            payload.put("message", ex.getMessage());
            payload.put("workflow_steps", workflowSteps);
            return failure(payload, ex.getMessage());
        }
    }

    private static GetPromptResult buildPromptResult(String text, String description) {
        List<PromptMessage> messages = List.of(new PromptMessage(Role.USER, new TextContent(text)));
        return new GetPromptResult(description, messages);
    }

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

    private static Map<String, Object> asStructured(CallToolResult result) {
        if (result.structuredContent() instanceof Map<?, ?> map) {
            return toStringObjectMap(map);
        }
        return Map.of();
    }

    private static Map<String, Object> workflowError(String message, List<Map<String, Object>> workflowSteps) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("status", "error");
        payload.put("message", message);
        payload.put("workflow_steps", workflowSteps);
        return payload;
    }

    private static Map<String, Object> step(String name, Map<String, Object> result) {
        Map<String, Object> step = new HashMap<>();
        step.put("step", name);
        step.put("result", result);
        return step;
    }

    private static CallToolResult success(Map<String, Object> payload) {
        return CallToolResult.builder()
                .structuredContent(payload)
                .build();
    }

    private static CallToolResult failure(Map<String, Object> payload, String message) {
        return CallToolResult.builder()
                .structuredContent(payload)
                .addTextContent(message)
                .isError(true)
                .build();
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

    private static int extractInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
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

    private static String schemaOpenWebpage() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "url": {
                      "type": "string",
                      "description": "The URL to open"
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
                    "content": {"type": "string", "description": "The content to summarize"},
                    "title": {"type": "string", "description": "Optional page title"},
                    "max_tokens": {"type": "integer", "description": "Maximum tokens for summary"}
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
                    "subject": {"type": "string", "description": "Email subject"},
                    "body": {"type": "string", "description": "Plain text email body"},
                    "body_html": {"type": "string", "description": "Optional HTML email body"}
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
                    "url": {"type": "string", "description": "URL to read"},
                    "to_email": {"type": "string", "description": "Email address to send summary to"},
                    "close_popups_first": {"type": "boolean", "description": "Whether to attempt closing popups"},
                    "email_subject": {"type": "string", "description": "Optional custom email subject"}
                  },
                  "required": ["url", "to_email"],
                  "additionalProperties": false
                }
                """;
    }
}
