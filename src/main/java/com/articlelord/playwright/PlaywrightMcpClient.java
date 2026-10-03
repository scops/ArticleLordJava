package com.articlelord.playwright;

import com.articlelord.config.Settings;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Client to interact with Playwright MCP over stdio.
 *
 * <p>ArticleLord launches Playwright MCP as a subprocess and talks to it with the
 * MCP SDK client. This makes it a true "MCP of MCPs" orchestrator: an MCP server
 * for its host and, at the same time, an MCP client of Playwright MCP.
 */
public final class PlaywrightMcpClient implements AutoCloseable {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(60);

    // Playwright MCP answers browser_evaluate with markdown like
    // "### Result\n<json>\n### Ran Playwright code\n...". We keep only the JSON part.
    private static final Pattern RESULT_PATTERN = Pattern.compile("### Result\\s*\\n(.*?)(?:\\n### |\\z)", Pattern.DOTALL);

    private final Settings settings;
    private final McpJsonMapper jsonMapper;

    private McpSyncClient client;

    public PlaywrightMcpClient(Settings settings) {
        this.settings = settings;
        this.jsonMapper = McpJsonDefaults.getMapper();
    }

    /**
     * Launches Playwright MCP and runs the initialize handshake.
     *
     * <p>Called once when the server starts, so every tool call reuses the same
     * browser; {@link #close()} stops the subprocess on shutdown.
     */
    public synchronized void start() {
        if (client != null) {
            return;
        }

        StdioClientTransport transport = new StdioClientTransport(createServerParameters(), jsonMapper);
        McpSyncClient newClient = McpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(INIT_TIMEOUT)
                .build();
        newClient.initialize();
        this.client = newClient;
    }

    public String navigate(String url) {
        return callTool("browser_navigate", Map.of("url", url));
    }

    public String snapshot() {
        return callTool("browser_snapshot", Map.of());
    }

    public String click(String element, String ref) {
        return callTool("browser_click", Map.of("element", element, "ref", ref));
    }

    /**
     * Evaluates JavaScript on the page.
     *
     * @return the value returned by the function, decoded from JSON when possible
     */
    public Object evaluate(String function) {
        String text = callTool("browser_evaluate", Map.of("function", function));
        Matcher matcher = RESULT_PATTERN.matcher(text);
        String raw = matcher.find() ? matcher.group(1).trim() : text;
        try {
            return jsonMapper.readValue(raw, Object.class);
        } catch (IOException | RuntimeException ex) {
            return raw;
        }
    }

    private synchronized String callTool(String name, Map<String, Object> args) {
        if (client == null) {
            throw new IllegalStateException("Playwright MCP is not running");
        }

        CallToolResult result = client.callTool(new CallToolRequest(name, args));
        String text = result.content() == null ? "" : result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(content -> ((TextContent) content).text())
                .collect(Collectors.joining("\n"));

        if (Boolean.TRUE.equals(result.isError())) {
            throw new IllegalStateException("Playwright MCP error: " + text);
        }

        return text;
    }

    @Override
    public synchronized void close() {
        if (client == null) {
            return;
        }
        try {
            client.closeGracefully();
        } finally {
            client = null;
        }
    }

    private ServerParameters createServerParameters() {
        String override = settings.getPlaywrightMcpCommand();
        List<String> args;
        String command;

        if (override != null && !override.isBlank()) {
            List<String> tokens = splitCommand(override);
            command = tokens.isEmpty() ? override : tokens.get(0);
            args = new ArrayList<>(tokens.subList(Math.min(1, tokens.size()), tokens.size()));
            String extraArgs = settings.getPlaywrightMcpArgs();
            if (extraArgs != null && !extraArgs.isBlank()) {
                args.addAll(splitCommand(extraArgs));
            }
        } else if (System.getProperty("os.name").toLowerCase().contains("win")) {
            command = "cmd.exe";
            args = new ArrayList<>(List.of("/c", "npx.cmd", "-y", "@playwright/mcp@latest"));
        } else {
            command = "npx";
            args = new ArrayList<>(List.of("-y", "@playwright/mcp@latest"));
        }

        if (settings.isPlaywrightHeadless() && !args.contains("--headless")) {
            args.add("--headless");
        }

        return ServerParameters.builder(command).args(args).build();
    }

    private static List<String> splitCommand(String raw) {
        // Minimal whitespace splitter with double-quote support for env-provided commands.
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                continue;
            }
            if (!inQuotes && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }
}
