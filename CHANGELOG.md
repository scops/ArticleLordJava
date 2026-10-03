# Changelog

All notable changes to ArticleLord Java will be documented in this file.

## [2.0.0] - 2026-10-03

### Changed
- Migrated to the MCP Java SDK v2 (`io.modelcontextprotocol.sdk:mcp` 2.0.1, Jackson 3), protocol 2025-11-25. The Java SDK does not support the 2026-07-28 spec yet; 2026 clients negotiate 2025-11-25 through the `initialize` fallback
- `read_and_send`: `to_email` is now optional; when missing, the recipient is asked for with elicitation (`elicitation/create`)
- Playwright MCP is started when the server starts (instead of on the first call) and stopped on shutdown
- Summarizer uses the official Anthropic Java SDK (`anthropic-java` 2.68) instead of hand-written HTTP calls
- Default model `claude-sonnet-5-5` with `effort: medium` and server-side refusal fallback (`LLM_EFFORT`, `LLM_FALLBACKS`)
- `summarize_content` default `max_tokens` raised to 8192 (the budget now includes the model's reasoning)
- Tool descriptions and input schemas aligned with the Python version; the SDK now validates tool arguments against them
- Tool results carry the JSON payload as text content as well as structured content
- Error messages returned to the model no longer include internal exception details (they go to the log)
- `PLAYWRIGHT_MCP_COMMAND` / `PLAYWRIGHT_MCP_ARGS` are also read from `.env`
- Dependencies bumped: Java 21, `angus-mail` 2.0.5 (replaces `com.sun.mail:jakarta.mail`), `slf4j-simple` 2.0.20, `maven-shade-plugin` 3.6.2
- Jar renamed to `target/articlelord-mcp-java.jar` (no version in the name, so client configs survive upgrades)

### Added
- Server `instructions`
- `PLAYWRIGHT_HEADLESS` setting

### Fixed
- Summaries are read from the text blocks of the response (with adaptive thinking the first block can be a thinking block); refusals and empty truncated answers return a clear error
- `close_popups` and `read_page_content` now parse Playwright's `browser_evaluate` output correctly
- Shaded jar keeps `META-INF/services`, so the MCP SDK finds its JSON mapper

## [1.0.0]

### Added
- Initial Java port of ArticleLord on the MCP Java SDK 0.17
