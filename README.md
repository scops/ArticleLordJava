# ArticleLord MCP (Java)

Java port of the ArticleLord MCP server using the MCP Java SDK.

## Requirements

- Java 17+
- Node.js + npm (for Playwright MCP via `npx`)
- Access to Anthropic API and SMTP credentials

## Configuration

Set the same environment variables as the Python version (case-insensitive):

- `ANTHROPIC_API_KEY`
- `LLM_MODEL` (optional, default: `claude-3-5-sonnet-20241022`)
- `SMTP_HOST`
- `SMTP_PORT` (optional, default: `2525`)
- `SMTP_USERNAME`
- `SMTP_PASSWORD`
- `EMAIL_FROM`

A `.env` file in the repo root or `java/` directory is also supported.

## Run

From `java/`:

```bash
mvn -q -DskipTests package
java -jar target/articlelord-mcp-java-1.0.0.jar
```

The server runs over stdio for MCP clients.

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
        "/home/user/Proyectos/ArticleLord/java/target/articlelord-mcp-java-1.0.0.jar"
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
				"cd /home/user/Proyectos/ArticleLord/java/ && java -jar target/articlelord-mcp-java-1.0.0.jar"
			]
		}
```


Make sure the jar exists (`mvn -q -DskipTests package`) and your environment variables are set where Claude Desktop can read them.

## Use with Codex CLI

Create or update your MCP config (for example `~/.config/codex/config.json`) with:
(Change paths accordingly)
```json
{
  "mcpServers": {
    "articlelord-java": {
      "command": "java",
      "args": [
        "-jar",
        "/home/user/Proyectos/ArticleLord/java/target/articlelord-mcp-java-1.0.0.jar"
      ]
    }
  }
}
```

If you prefer a relative path, run Codex from the repo root and use `java/target/articlelord-mcp-java-1.0.0.jar`.
