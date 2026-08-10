# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a Maven-based Java plugin for Fess that implements a Model Context Protocol (MCP) server, protocol revision **`2026-07-28`**, over JSON-RPC 2.0. The plugin enables MCP clients to search Fess, retrieve documents, get autocomplete suggestions, and (behind a permission gate) read index statistics.

## Development Commands

### Build and Package
```bash
mvn clean package
```

### Run Tests
```bash
mvn test
```

### Run a Single Test
```bash
mvn test -Dtest=McpApiManagerHttpTest
mvn test -Dtest=McpApiManagerHttpTest#testSuccessPathWritesResultWithMatchingIdAndServerInfo
```

### Code Formatting
```bash
mvn formatter:format && mvn license:format
```

## Architecture

### Core Components

- **`McpApiManager`** (`src/main/java/.../api/mcp/McpApiManager.java`): the HTTP boundary. Extends `BaseApiManager` and handles `/mcp/*`. Owns transport concerns only — enablement/method checks, body-size enforcement, Origin validation, authentication dispatch, JSON-RPC envelope parsing, request-metadata header validation, protocol-version negotiation, and rate-limit enforcement — before handing a validated `McpCallContext` to `McpDispatcher`. No longer a god class: the per-method `handle*` logic that used to live here now lives one handler per class under `mcp.handler`.
- **`McpMetadataApiManager`** (`src/main/java/.../api/mcp/McpMetadataApiManager.java`): a second, independent `WebApiManager` serving the RFC 9728 OAuth 2.0 Protected Resource Metadata document at `/.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp`, active only when `mcp.auth.mode=oauth` is usable.
- **`McpApiException`** (`src/main/java/.../exception/McpApiException.java`): the legacy exception type `McpTool#call` implementations still throw; `ToolsCallHandler` bridges it into the go-forward `McpError` contract.
- **`ErrorCode`** (`src/main/java/.../mcp/ErrorCode.java`): the JSON-RPC error codes this server emits (`-32700`, `-32600`, `-32601`, `-32602`, `-32603`, `-32020`, `-32021`, `-32022`). Deliberately carries no HTTP status — the same code can map to different statuses depending on where it is raised (see `McpError`).

### Package Layout

- **`mcp.protocol`** — the JSON-RPC/MCP envelope and dispatch machinery: `McpRequest` (parsed envelope), `McpRequestMeta` (`params._meta`), `McpCallContext` (per-call context: request, meta, params, principal), `McpDispatcher` (routes by method name to a handler), `McpError` (HTTP status + `ErrorCode` + message + optional data), `McpResponseWriter` (writes the response, stamps `resultType`/`_meta.serverInfo`, never calls `sendError`), and `HeaderValidator` (checks `MCP-Protocol-Version`/`Mcp-Method`/`Mcp-Name` against the body).
- **`mcp.handler`** — one `McpMethodHandler` implementation per JSON-RPC method: `DiscoverHandler` (`server/discover`), `ToolsListHandler`, `ToolsCallHandler`, `ResourcesListHandler`, `ResourcesReadHandler`, `ResourceTemplatesListHandler`, `PromptsListHandler`, `PromptsGetHandler`, `CompletionHandler`. Six of the nine (all but `tools/call`, `prompts/get`, `completion/complete`) extend `AbstractCacheableHandler`, which centralises TTL resolution (hot-reloadable system property, clamped to `>= 0`) and inbound-`cursor` rejection (this server never paginates).
- **`mcp.tool`** — one `McpTool` implementation per tool: `SearchTool`, `IndexStatsTool`, `SuggestTool`, `GetDocumentTool`, plus the shared `DocumentFormatter` (content truncation). Each tool owns its name, description, `inputSchema`, `outputSchema`, annotations, required permissions, and `call` behaviour — no separate schema/behaviour split.
- **`mcp.auth`** — `McpAuthenticator` (the `authenticate(request, response)` strategy interface) with three implementations selected by `mcp.auth.mode`: `NoneAuthenticator`, `FessTokenAuthenticator`, `OAuthResourceServerAuthenticator`. Plus `McpPrincipal` (resolved caller: subject, scopes, permissions), `PermissionGate` (the single "may this caller use a gated primitive" check, shared by four handlers), `CanonicalResourceUri` (the RFC 8707 resource URI / RFC 9728 well-known URL derivation, shared by the authenticator and the metadata manager), and `ProtectedResourceMetadata` (the RFC 9728 document model).
- **`mcp.json`** — `Json`, a minimal parse/write seam so the rest of the plugin does not depend on a particular JSON library directly.
- Top-level `mcp` package: `McpConstants` (protocol version, header/`_meta` key names), `OriginValidator` (the Origin-header MUST), `RateLimiter` (fixed-window, per-key).

### MCP Protocol Methods

The dispatcher (`McpDispatcher`, constructed from nine handlers in `McpApiManager`) routes these JSON-RPC methods; every other method name, including the retired `initialize` and `ping`, is `-32601 Method not found`:

- `server/discover` — replaces `initialize`; no version negotiation (this server speaks exactly one revision); unauthenticated, always `cacheScope: "public"`.
- `tools/list` — lists `search`, `get_index_stats` (permission-gated, see below), `suggest`, `get_document`, each with `inputSchema`, `outputSchema`, and annotations.
- `tools/call` — executes a tool by name; requires `Mcp-Name` to match `params.name`.
- `resources/list` — lists `fess://index/stats` when the caller is authorized for it.
- `resources/read` — reads `fess://index/stats` or a `fess://document/{doc_id}` URI; requires `Mcp-Name` to match `params.uri`.
- `resources/templates/list` — the one published RFC 6570 template, `fess://document/{doc_id}`.
- `prompts/list` — `basic_search`, `advanced_search`.
- `prompts/get` — substitutes prompt arguments; requires `Mcp-Name` to match `params.name`.
- `completion/complete` — argument autocomplete via Fess suggest (prompt `query` args) or a static enum (`advanced_search.sort`); rate-limited like `tools/call`.

`initialize` and `ping` no longer exist as handlers at all: `initialize` gets a dedicated `-32601` message naming the supported version (legacy clients have no fall-forward mechanism), and a bare JSON array body (batching) is rejected with HTTP 400 before the method is even looked at.

### Search Integration

Search functionality integrates with Fess through `SearchHelper` and `SearchRenderData`. Query syntax is similar to Lucene (AND default, OR explicit, phrase search with quotes, exclusion with `-`). `SearchTool#buildRequestParams` returns `SearchRequestType.JSON`, which is what makes Fess's `RoleQueryHelper` apply role-based search filtering to MCP results.

### Configuration

Every configuration key is a Fess system property, read through `ComponentUtil.getFessConfig()`. **Boolean keys (`mcp.enabled`) accept only `true`, matched case-insensitively** — `getSystemPropertyAsBoolean` is `Constants.TRUE.equalsIgnoreCase(...)`, so `True`/`TRUE` also work but anything else, including `"1"`, is `false`. The full key reference — including the three `mcp.auth.mode` values, the `mcp.oauth.*` family, the per-method `mcp.cache.*.ttl.ms` keys, and the pre-existing `mcp.content.max.length` / `mcp.highlight.*` / `mcp.default.page.size` — is documented in `README.md`'s Configuration table, not duplicated here.

## Key Dependencies

- Fess search engine framework (provided scope)
- OpenSearch for search operations
- LastaFlute web framework
- Jakarta EE APIs (Servlet, Annotation)
- Nimbus JOSE+JWT / `oauth2-oidc-sdk` (transitively provided by the `fess` dependency), used by `OAuthResourceServerAuthenticator` for JWT verification
