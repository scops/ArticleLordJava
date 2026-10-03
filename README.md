# ArticleLord MCP (Java)

Java port of the [ArticleLord](https://github.com/scops/article-lord) MCP server using the MCP Java SDK.

Same tools, same prompts, same behaviour as the Python version: two implementations of
one protocol. MCP defines the contract on the wire; each SDK is just one way to speak it.

## Protocol version

| | Python ArticleLord | ArticleLord Java |
|---|---|---|
| SDK | `mcp` 2.3 (`MCPServer`) | `io.modelcontextprotocol.sdk:mcp` 2.0.1 |
| Spec | **2026-07-28** (and older, "dual era") | **2025-11-25** (latest the Java SDK supports) |
| Asking the user for the recipient | `Resolve` + `Elicit` (the call returns, the client asks, the call is retried) | `elicitation/create` sent back to the client in the middle of the tool call |

As of v2.0.1 the Java SDK does not implement 2026-07-28 yet. Hosts and clients that speak
2026-07-28 still work with this server: they first try `server/discover`, see a pre-2026
server, and fall back to the `initialize` handshake, negotiating `2025-11-25`. Version
negotiation is part of the protocol, so the Python client talks to the Java server
without either side knowing what language the other is written in.

## Requirements

- Java 21+
- Maven 3.6.3+
- Node.js + npm (for Playwright MCP via `npx`)
- Access to Anthropic API and SMTP credentials

## Configuration

Set the same environment variables as the Python version (case-insensitive):

- `ANTHROPIC_API_KEY`
- `LLM_MODEL` (optional, default: `claude-sonnet-5-5`)
- `LLM_EFFORT` (optional, default: `medium`; `low | medium | high | xhigh | max`, empty for Haiku 4.5)
- `LLM_FALLBACKS` (optional, default: `true`; server-side refusal fallback, Sonnet 5.5 / Opus 5.x / Fable 5.1 only)
- `SMTP_HOST`
- `SMTP_PORT` (optional, default: `2525`)
- `SMTP_USERNAME`
- `SMTP_PASSWORD`
- `EMAIL_FROM`
- `PLAYWRIGHT_HEADLESS` (optional, default: `false`; set to `true` on machines without a display)

A `.env` file in the working directory or its parent is also supported (see `env.example`).

Optional Playwright MCP overrides:

- `PLAYWRIGHT_MCP_COMMAND` (full command or absolute path to the MCP runner)
- `PLAYWRIGHT_MCP_ARGS` (extra args to append)

## Run

```bash
mvn -q -DskipTests package
java -jar target/articlelord-mcp-java.jar
```

The server runs over stdio for MCP clients. Playwright MCP is started together with the
server and stopped when it exits.

If `read_and_send` is called without `to_email`, the server asks the user for it through
elicitation. Clients without elicitation support get an error asking them to pass `to_email`.

## Recommended (plug-and-play)

Use the wrapper script so the MCP client does not depend on a working directory and `.env` is always loaded.

```bash
/home/user/Proyectos/ArticleLord/scripts/run_java_mcp.sh
```

Point all MCP configs to the script:

```json
{
  "mcpServers": {
    "articlelord-java": {
      "command": "/home/user/Proyectos/ArticleLord/scripts/run_java_mcp.sh",
      "args": []
    }
  }
}
```

## Use with Claude Desktop

Add a server entry to your Claude Desktop config (`claude_desktop_config.json`):
(Change paths accordingly)
```json
{
  "mcpServers": {
    "articlelord-java": {
      "command": "java",
      "args": [
        "-jar",
        "/home/user/Proyectos/ArticleLordJava/target/articlelord-mcp-java.jar"
      ]
    }
  }
}
```

Or if you are working on wsl
(Change paths accordingly)
```json
"articlelord-java": {
		  "command": "wsl",
		  "args": [
			  "bash", "-lc",
				"cd /home/user/Proyectos/ArticleLordJava && java -jar target/articlelord-mcp-java.jar"
			]
		}
```


Make sure the jar exists (`mvn -q -DskipTests package`) and your environment variables are set where Claude Desktop can read them.

## Use with Codex CLI

Create or update your MCP config (for example `~/.codex/config.toml`) with:
(Change paths accordingly)
```toml
[mcp_servers.articlelord-java]
command = "java"
args = ["-jar", "/home/user/Proyectos/ArticleLordJava/target/articlelord-mcp-java.jar"]
startup_timeout_ms = 60000
```

If you prefer a relative path, run Codex from the repo root and use `target/articlelord-mcp-java.jar`.
