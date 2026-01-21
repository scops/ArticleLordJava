package com.articlelord.playwright;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PlaywrightMcpClient implements AutoCloseable {
    private static final String ENV_MCP_COMMAND = "PLAYWRIGHT_MCP_COMMAND";
    private static final String ENV_MCP_ARGS = "PLAYWRIGHT_MCP_ARGS";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(15);

    private final McpJsonMapper jsonMapper;

    private StdioClientTransport transport;
    private McpSyncClient client;
    private boolean started;

    public PlaywrightMcpClient() {
        this.jsonMapper = McpJsonMapper.getDefault();
    }

    public synchronized void start() {
        if (started) {
            return;
        }

        ServerParameters params = createServerParameters();
        this.transport = new StdioClientTransport(params, jsonMapper);
        this.client = McpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(INIT_TIMEOUT)
                .build();
        this.started = true;
    }

    public Map<String, Object> navigate(String url) {
        return callTool("browser_navigate", Map.of("url", url));
    }

    public Map<String, Object> snapshot() {
        return callTool("browser_snapshot", Map.of());
    }

    public Map<String, Object> evaluate(String function) {
        return callTool("browser_evaluate", Map.of("function", function));
    }

    public Map<String, Object> click(String element, String ref) {
        Map<String, Object> args = new HashMap<>();
        args.put("element", element);
        args.put("ref", ref);
        return callTool("browser_click", args);
    }

    private synchronized Map<String, Object> callTool(String name, Map<String, Object> args) {
        start();
        CallToolResult result = client.callTool(new CallToolRequest(name, args));
        Object structured = result.structuredContent();
        if (structured instanceof Map<?, ?> structuredMap) {
            Map<String, Object> output = new HashMap<>();
            for (Map.Entry<?, ?> entry : structuredMap.entrySet()) {
                if (entry.getKey() != null) {
                    output.put(entry.getKey().toString(), entry.getValue());
                }
            }
            return output;
        }

        if (structured != null) {
            return Map.of("result", structured);
        }

        if (result.content() != null && !result.content().isEmpty()) {
            return Map.of("result", result.content());
        }

        return Map.of();
    }

    @Override
    public synchronized void close() {
        if (!started) {
            return;
        }
        try {
            if (client != null) {
                client.closeGracefully();
            }
        } finally {
            if (transport != null) {
                transport.closeGracefully().block(Duration.ofSeconds(5));
            }
            started = false;
        }
    }

    private ServerParameters createServerParameters() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String override = getEnv(ENV_MCP_COMMAND);
        if (override != null && !override.isBlank()) {
            List<String> tokens = splitCommand(override);
            String command = tokens.isEmpty() ? override : tokens.get(0);
            List<String> args = tokens.size() > 1 ? tokens.subList(1, tokens.size()) : List.of();
            String extraArgs = getEnv(ENV_MCP_ARGS);
            if (extraArgs != null && !extraArgs.isBlank()) {
                args = mergeArgs(args, splitCommand(extraArgs));
            }
            return ServerParameters.builder(command)
                    .args(args)
                    .build();
        }

        if (isWindows) {
            return ServerParameters.builder("cmd.exe")
                    .args("/c", "npx.cmd", "-y", "@playwright/mcp@latest")
                    .build();
        }
        return ServerParameters.builder("npx")
                .args("-y", "@playwright/mcp@latest")
                .build();
    }

    private static String getEnv(String key) {
        String value = System.getenv(key);
        return value != null ? value.trim() : null;
    }

    private static List<String> mergeArgs(List<String> base, List<String> extra) {
        if (extra.isEmpty()) {
            return base;
        }
        List<String> merged = new java.util.ArrayList<>(base.size() + extra.size());
        merged.addAll(base);
        merged.addAll(extra);
        return merged;
    }

    private static List<String> splitCommand(String raw) {
        // Minimal whitespace splitter with double-quote support for env-provided commands.
        List<String> tokens = new java.util.ArrayList<>();
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
