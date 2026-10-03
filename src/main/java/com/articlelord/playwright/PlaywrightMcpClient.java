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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        Map<String, String> env = new HashMap<>();

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
            Path npx = findNativeNpx();
            command = npx.toString();
            args = new ArrayList<>(List.of("-y", "@playwright/mcp@latest"));
            // npx is a "#!/usr/bin/env node" script: node must be on the child's PATH
            String path = System.getenv("PATH");
            env.put("PATH", npx.getParent() + (path == null || path.isBlank() ? "" : File.pathSeparator + path));
        }

        if (settings.isPlaywrightHeadless() && !args.contains("--headless")) {
            args.add("--headless");
        }

        return ServerParameters.builder(command).args(args).env(env).build();
    }

    /**
     * Finds a Linux npx, skipping the Windows one that WSL exposes under /mnt/.
     *
     * <p>MCP hosts on Windows launch the server with {@code wsl bash -lc ...}: a
     * non-interactive shell that does not source ~/.bashrc, so nvm is not loaded and
     * the first npx on PATH is the Windows one. That npx starts cmd.exe with a UNC
     * working directory and never answers, blocking the server startup.
     */
    private static Path findNativeNpx() {
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank() || dir.startsWith("/mnt/")) {
                    continue;
                }
                Path candidate = Path.of(dir, "npx");
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            }
        }

        Path nvmVersions = Path.of(System.getProperty("user.home"), ".nvm", "versions", "node");
        if (Files.isDirectory(nvmVersions)) {
            try (Stream<Path> versions = Files.list(nvmVersions)) {
                Optional<Path> latest = versions
                        .map(version -> version.resolve("bin").resolve("npx"))
                        .filter(Files::isExecutable)
                        .max(Comparator.comparing(PlaywrightMcpClient::nodeVersion));
                if (latest.isPresent()) {
                    return latest.get();
                }
            } catch (IOException ex) {
                // Fall through to the error below
            }
        }

        throw new IllegalStateException("No Linux npx found on PATH or in ~/.nvm. Install Node.js "
                + "or set PLAYWRIGHT_MCP_COMMAND to the full path of the Playwright MCP runner.");
    }

    // ~/.nvm/versions/node/v24.19.0/bin/npx -> [24, 19, 0] packed for ordering
    private static long nodeVersion(Path npx) {
        String[] parts = npx.getParent().getParent().getFileName().toString().replaceFirst("^v", "").split("\\.");
        long packed = 0;
        for (int i = 0; i < 3; i++) {
            long part = 0;
            if (i < parts.length) {
                try {
                    part = Long.parseLong(parts[i]);
                } catch (NumberFormatException ex) {
                    part = 0;
                }
            }
            packed = packed * 100_000 + part;
        }
        return packed;
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
