MCP WebApp Plugin for Fess
[![Java CI with Maven](https://github.com/codelibs/fess-webapp-mcp/actions/workflows/maven.yml/badge.svg)](https://github.com/codelibs/fess-webapp-mcp/actions/workflows/maven.yml)
==========================

## Overview

This plugin transforms Fess (Enterprise Search Server) into a Model Context Protocol (MCP) server, enabling JSON-RPC 2.0 based interactions with Fess's search capabilities. The MCP API provides a standardized interface for executing search operations, retrieving index statistics, and accessing system information.

This plugin implements **MCP protocol revision `2026-07-28`** exclusively.

## Note

**This project is a work in progress. Features, APIs, and documentation may evolve as development continues. Contributions and feedback are welcome.**

## Breaking Changes (MCP 2026-07-28)

Older MCP clients built against `2024-11-05` (or any earlier revision) will not work against this endpoint at all — there is no legacy handshake and no version negotiation. Specifically:

- **`initialize` is gone.** Calling it returns HTTP 404 with JSON-RPC `-32601` (`Method not found`); the error message and `data.supportedVersions` name the one version this server speaks. Use [`server/discover`](#1-serverdiscover) instead — it does not negotiate a protocol version, since there is only one.
- **`ping` is gone.** There is no liveness-check method any more.
- **JSON-RPC batching is gone.** A JSON array request body is rejected outright with HTTP 400 — batching was removed from the JSON-RPC layer in `2025-06-18`, and this server never re-added it as an extension.
- **Every non-notification request now needs request-metadata headers and a `params._meta` object.** `MCP-Protocol-Version` and `Mcp-Method` are always required, plus `Mcp-Name` for `tools/call`, `prompts/get`, and `resources/read`. Every header must exactly match the corresponding body value. See [Required Headers and `_meta`](#required-headers-and-_meta). A request that omits one of these, or where a header disagrees with the body, is rejected with HTTP 400 and JSON-RPC `-32020` before the method is even dispatched. The two retired methods above are the deliberate exception: they are answered with `-32601` *before* header validation runs, because a client old enough to call them cannot send `Mcp-Method` (the header did not exist before this revision) and would otherwise be told to add a header instead of that the method is gone.
- **`get_index_stats` is gated by a permission by default** (`mcp.tools.index_stats.permissions=Radmin-api`). Because the default `mcp.auth.mode=none` never grants any permission to any caller, **this tool and the `fess://index/stats` resource are effectively unavailable out of the box** for every caller — hidden from `tools/list`/`resources/list`, and refused (indistinguishably from "does not exist") by `tools/call`/`resources/read`. See [Get Index Stats and the permission gate](#get-index-stats-and-the-permission-gate).
- **A per-caller rate limit is enabled by default** (`mcp.rate.limit.per.minute=60`) on `tools/call`, `completion/complete`, and `resources/read`. A caller that exceeds it gets HTTP 429 with a `Retry-After` header. `resources/read` is included even though the spec names only the first two: reading `fess://document/<id>` makes the identical backend document fetch the rate-limited `get_document` tool makes, so leaving it out left `Mcp-Method: resources/read` as an unmetered channel that simply bypassed the limit on `tools/call`.
- **An inbound `cursor` is now rejected.** The previous implementation accepted a `cursor` on the list methods and silently ignored it, returning page 1 to a client that believed it was paging forward. `tools/list`, `resources/list`, `resources/templates/list`, and `prompts/list` now answer a non-null `cursor` with `-32602` (HTTP 200) — this server returns every item in a single page and never issues a `nextCursor`, so any inbound cursor is necessarily stale. An explicit JSON `"cursor": null` is treated as absent and accepted, since several mainstream serializers emit one for an unset optional field.
- **`search` with `num` &le; 0 now returns the default page size, not the maximum.** `{"num": 0}` and `{"num": -1}` (and their string forms) used to yield `paging.search.page.max.size` — the largest page the server will emit, 100 by default — which is the opposite of what a client computing a page size and reaching zero intends. They now fall back to `mcp.default.page.size` (3), matching what an unparseable `num` and the `suggest` tool already did. `num` greater than the maximum is still clamped to the maximum.
- **`search`'s `offset` argument now actually works.** It has always been advertised as an alias of `start` in the tool's `inputSchema`, but nothing read it as one: Fess consumes `SearchRequestParams#getOffset()` only as a rank-fusion window shift, which a stock single-searcher install never reaches, so a client paginating with `offset` was served page 1 forever. `offset` now sets the start position when `start` is absent. When both are sent, **`start` wins** — including when `start` itself is unparseable or negative, so the alias never silently repairs a broken `start` and pages from somewhere the caller did not ask for.
- **`tools/call` no longer requires `params.arguments`.** The normative schema declares it optional (`CallToolRequestParams.arguments?`), and a tool with no declared parameters — `get_index_stats` — is normally called without it, so a conformant client could not reach that tool at all. An absent (or non-object) `arguments` is now treated as `{}`. No required-argument check is weakened: `tools/call {"name": "search"}` still fails with `-32602`, but now reports the real cause (`Missing required parameter: q`) instead of `Missing required parameter: arguments`.
- **`mcp.allowed.origins` is now the complete `Origin` allowlist.** It used to list origins accepted *in addition to* the server's own, which was derived from the request. That derivation compared a caller-supplied `Origin` against a caller-supplied `Host` — a tautology a DNS-rebinding attacker satisfies by sending both — so it is gone. With the default blank value, **every present `Origin` is now rejected with HTTP 403**, including the server's own; a browser-based MCP client must be named explicitly. Clients that send no `Origin` at all (CLI bridges, stdio proxies, `curl`) are unaffected.

Notifications (a JSON-RPC request with no `id`) are unaffected by any of the above: they are still accepted with HTTP 202 and no body, and they do not require the metadata headers or `_meta`.

## Download

See [Maven Repository](https://maven.codelibs.org/release/org/codelibs/fess/fess-webapp-mcp/).

## Requirements

- Fess 15.8 or later
- Java 21 or later

## Installation

1. Download the plugin JAR from the Maven Repository
2. Place it in your Fess plugin directory
3. Restart Fess

For detailed instructions, see the [Plugin Administration Guide](https://fess.codelibs.org/15.8/admin/plugin-guide.html).

## Features

- **MCP Protocol Support**: Implements MCP protocol revision `2026-07-28` (Streamable HTTP transport, single JSON request/response per call — no batching, no SSE-only stateful session)
- **Search Tools**: Execute full-text search queries with advanced filtering
- **Suggest Tool**: Autocomplete/suggestion queries via Fess suggest engine
- **Get Document Tool**: Retrieve individual documents by ID
- **Index Statistics**: Retrieve index and system information, gated behind a permission by default
- **Structured Tool Output**: Every tool declares an `outputSchema` and returns a matching `structuredContent`. For `search`, `suggest`, and `get_document` this sits alongside the existing Markdown `content` text block; `get_index_stats`'s `content` text block is itself the same data serialized as JSON (see [Deviations From the Specification](#deviations-from-the-specification) item 2)
- **Resources**: Access to Fess index statistics and configuration
- **Resource Templates**: Parameterized URI templates (RFC 6570) for dynamic resource access
- **Prompts**: Pre-defined search templates for common use cases
- **Completion**: Argument autocomplete using Fess suggest for prompt arguments
- **Origin Validation**: Rejects any present `Origin` header that is not in the configured allowlist (browser CSRF/DNS-rebinding defence)
- **Rate Limiting**: Per-caller fixed-window limit on `tools/call`, `completion/complete`, and `resources/read` — every method that reaches a Fess backend
- **Three Authorization Modes**: `none` (default, unauthenticated), `fess_token` (Fess access tokens), `oauth` (RFC 6750 Bearer JWT + RFC 9728 protected-resource metadata)
- **Extensible Architecture**: Easy to add new tools and capabilities

## API Endpoint

The MCP API is available at:

```
POST http://<fess-server>:<port>/mcp
```

All non-notification requests must be sent as JSON-RPC 2.0 formatted POST requests, carrying the headers and `_meta` described below. `GET`/`DELETE` are not implemented on this endpoint (legacy Streamable HTTP session semantics); they are rejected with HTTP 405.

When `mcp.auth.mode=oauth`, this plugin also serves an RFC 9728 OAuth 2.0 Protected Resource Metadata document at `/.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp` — see [OAuth 2.1 Setup](#oauth-21-setup).

## Required Headers and `_meta`

Every request that is not a notification (i.e. carries a JSON-RPC `id`) must include:

| Header | Required for | Must equal |
|--------|--------------|------------|
| `MCP-Protocol-Version` | every method | `params._meta["io.modelcontextprotocol/protocolVersion"]` |
| `Mcp-Method` | every method | the JSON-RPC `method` field |
| `Mcp-Name` | `tools/call`, `prompts/get` | `params.name` |
| `Mcp-Name` | `resources/read` | `params.uri` |

and a `params._meta` object with:

| `_meta` key | Required | Meaning |
|-------------|----------|---------|
| `io.modelcontextprotocol/protocolVersion` | Yes | Must be `2026-07-28` — any other value is rejected with `-32022` (`UnsupportedProtocolVersion`), but only after header *presence* has already been checked |
| `io.modelcontextprotocol/clientCapabilities` | Yes | An object; may be `{}` |
| `io.modelcontextprotocol/clientInfo` | No | Client identity, echoed nowhere by this server today |

Presence of the required headers is checked before they are compared against the body, and both checks happen before the protocol version is validated — a request with no headers at all against an unsupported body version still gets `-32020`, not `-32022`.

A `Mcp-Name` value that is not pure ASCII cannot be carried in an HTTP header field directly; encode it as `=?base64?<base64-of-utf8-bytes>?=` and this server will decode it before comparing it against the body. A value that carries the sentinel's `=?base64?` prefix and `?=` suffix but cannot be decoded as one — including the ten-character `=?base64?=`, where the two delimiters overlap — is treated as malformed, not as a plain header value, and is rejected with HTTP 400 and `-32020`.

`Content-Type` and `Accept` are not validated by this server (send `application/json` and `application/json, text/event-stream` respectively as a matter of good practice, matching the transport spec).

## Available Methods

### 1. server/discover

Replaces the retired `initialize` handshake. This server speaks exactly one protocol revision, so unlike `initialize` there is no version negotiation. The result is a `CacheableResult` (`ttlMs` + `cacheScope`), and it is always `cacheScope: "public"` and unauthenticated — it must never mention a permission-gated primitive (see [Get Index Stats and the permission gate](#get-index-stats-and-the-permission-gate)).

**Request:**
```bash
curl -sS -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: server/discover' \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "server/discover",
    "params": {
      "_meta": {
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientCapabilities": {}
      }
    }
  }'
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "supportedVersions": ["2026-07-28"],
    "capabilities": {
      "tools": {},
      "resources": {},
      "prompts": {},
      "completions": {}
    },
    "instructions": "Fess Enterprise Search Server. Use the 'search' tool to perform full-text search with Lucene-like query syntax (AND default, OR explicit, quotes for phrase, - for exclusion). Use 'suggest' for query autocomplete.",
    "ttlMs": 3600000,
    "cacheScope": "public",
    "resultType": "complete",
    "_meta": {
      "io.modelcontextprotocol/serverInfo": {
        "name": "fess-mcp-server",
        "version": "unknown"
      }
    }
  }
}
```

`resultType` and `_meta["io.modelcontextprotocol/serverInfo"]` are stamped onto *every* successful result by the response writer, not just this one — they are omitted from the remaining examples below for brevity, but are always present. `serverInfo.version` currently reports `"unknown"`: the shipped plugin JAR's manifest does not carry an `Implementation-Version` entry for `Package#getImplementationVersion()` to read.

`ttlMs` for this method is controlled by `mcp.cache.discover.ttl.ms` (default `3600000`, i.e. one hour).

### 2. tools/list

List the tools available to the caller. A tool gated by a permission the caller does not hold (see [get_index_stats](#get-index-stats-and-the-permission-gate)) is silently omitted from the list.

**Request:**
```bash
curl -sS -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/list' \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/list",
    "params": {
      "_meta": {
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientCapabilities": {}
      }
    }
  }'
```

**Response** (as seen by a caller authorized for `get_index_stats`; under the default `mcp.auth.mode=none` configuration `get_index_stats` is omitted for everyone — see below):
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "search",
        "description": "Search documents via Fess. Query syntax is similar to Lucene: multiple terms are combined with AND by default, use OR explicitly for OR search (e.g., \"term1 OR term2\"), use quotes for phrase search, use - for exclusion.",
        "inputSchema": {
          "type": "object",
          "properties": {
            "q": { "type": "string", "description": "query string" },
            "start": { "type": "integer", "description": "start position" },
            "offset": { "type": "integer", "description": "offset (alias of start)" },
            "num": { "type": "integer", "description": "number of results" },
            "sort": { "type": "string", "description": "sort order" },
            "fields": {
              "type": "object",
              "description": "field filters, keyed by field name, e.g. {\"label\": [\"label1\"]}",
              "properties": { "label": { "type": "array", "description": "labels to return" } }
            },
            "lang": { "type": "string", "description": "language" },
            "as": { "type": "object", "description": "advanced search conditions, keyed by condition name, e.g. {\"sitesearch\": [\"example.com\"]}" },
            "ex_q": { "type": "array", "description": "extra queries", "items": { "type": "string" } },
            "sdh": { "type": "string", "description": "similar document hash" }
          },
          "required": ["q"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "hits": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "title": { "type": "string" },
                  "url": { "type": "string" },
                  "score": { "type": "number" },
                  "content_description": { "type": "string" }
                },
                "required": [],
                "additionalProperties": false
              }
            }
          },
          "required": ["hits"],
          "additionalProperties": false
        },
        "annotations": {
          "title": "Search Documents",
          "readOnlyHint": true,
          "destructiveHint": false,
          "openWorldHint": false
        }
      },
      {
        "name": "get_index_stats",
        "description": "Get index statistics and information",
        "inputSchema": { "type": "object", "properties": {} },
        "outputSchema": {
          "type": "object",
          "properties": {
            "index": {
              "type": "object",
              "properties": {
                "index_name": { "type": "string" },
                "document_count": { "type": "integer" },
                "error": { "type": "string" }
              },
              "required": ["document_count"],
              "additionalProperties": false
            },
            "config": {
              "type": "object",
              "properties": { "max_page_size": { "type": "integer" } },
              "required": ["max_page_size"],
              "additionalProperties": false
            },
            "system": {
              "type": "object",
              "properties": {
                "memory": {
                  "type": "object",
                  "properties": {
                    "total_bytes": { "type": "integer" },
                    "free_bytes": { "type": "integer" },
                    "used_bytes": { "type": "integer" },
                    "max_bytes": { "type": "integer" }
                  },
                  "required": ["total_bytes", "free_bytes", "used_bytes", "max_bytes"],
                  "additionalProperties": false
                }
              },
              "required": ["memory"],
              "additionalProperties": false
            }
          },
          "required": ["index", "config", "system"],
          "additionalProperties": false
        },
        "annotations": {
          "title": "Get Index Statistics",
          "readOnlyHint": true,
          "destructiveHint": false,
          "openWorldHint": false
        }
      },
      {
        "name": "suggest",
        "description": "Get autocomplete suggestions for a search query prefix",
        "inputSchema": {
          "type": "object",
          "properties": {
            "q": { "type": "string", "description": "query prefix for autocomplete" },
            "num": { "type": "integer", "description": "number of suggestions" }
          },
          "required": ["q"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "suggestions": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": { "text": { "type": "string" } },
                "required": ["text"],
                "additionalProperties": false
              }
            }
          },
          "required": ["suggestions"],
          "additionalProperties": false
        },
        "annotations": {
          "title": "Suggest",
          "readOnlyHint": true,
          "destructiveHint": false,
          "openWorldHint": false
        }
      },
      {
        "name": "get_document",
        "description": "Retrieve a document by its document ID",
        "inputSchema": {
          "type": "object",
          "properties": { "doc_id": { "type": "string", "description": "document ID to retrieve" } },
          "required": ["doc_id"]
        },
        "outputSchema": {
          "type": "object",
          "properties": {
            "doc_id": { "type": "string" },
            "title": { "type": "string" },
            "url": { "type": "string" },
            "content": { "type": "string" }
          },
          "required": ["doc_id", "title", "url", "content"],
          "additionalProperties": false
        },
        "annotations": {
          "title": "Get Document",
          "readOnlyHint": true,
          "destructiveHint": false,
          "openWorldHint": false
        }
      }
    ],
    "ttlMs": 3600000,
    "cacheScope": "private"
  }
}
```

No tool declares `idempotentHint`. `cacheScope` is `"public"` only while `mcp.auth.mode=none`; it drops to `"private"` for any other mode, because which tools appear then depends on the caller's authorization. `ttlMs` is controlled by `mcp.cache.list.ttl.ms` (default `3600000`), shared with `resources/list`, `resources/templates/list`, and `prompts/list`.

### 3. tools/call

Execute a specific tool. Requires the `Mcp-Name` header to equal `params.name`.

**Request (Search):**
```bash
curl -sS -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/call' \
  -H 'Mcp-Name: search' \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "search",
      "arguments": { "q": "elasticsearch", "num": 10, "start": 0 },
      "_meta": {
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientCapabilities": {}
      }
    }
  }'
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "**Title**: Introduction to Elasticsearch\n**URL**: https://example.com/elasticsearch-intro\n**Score**: 1.234\n\nElasticsearch is a distributed, RESTful search and analytics engine..."
      }
    ],
    "structuredContent": {
      "hits": [
        { "title": "Introduction to Elasticsearch", "url": "https://example.com/elasticsearch-intro", "score": 1.234, "content_description": "Elasticsearch is a distributed, RESTful search and analytics engine..." }
      ]
    }
  }
}
```

`content` stays Markdown-style text even though `structuredContent` is also present — see [Deviations From the Specification](#deviations-from-the-specification) item 2. `tools/call` is **not** a `CacheableResult`: it never carries `ttlMs` or `cacheScope`.

**How an absent field is reported differs by tool, and `search` is the strict one.** For `search`, `structuredContent` only ever carries the fields Fess actually populated (see that tool's `outputSchema` above): a `title`, `url`, `score`, or `content_description` that is genuinely absent from the underlying document is omitted from the `hits[]` entry, not filled in with an empty string or `null` — which is why that schema's `items.required` is empty. `get_document` does the opposite, and its `outputSchema` says so by marking all four fields required: it resolves `title`, `url`, and `content` with a `""` fallback, so an absent field arrives as an empty string rather than being omitted. (A field present in the document but mapped to a null value becomes the literal string `"null"` there, since the fallback is applied before the value is stringified.) `get_index_stats` strips nulls from its stats map before serializing it.

**Request (Suggest):**
```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "suggest",
    "arguments": { "q": "machine", "num": 5 },
    "_meta": { "io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {} }
  }
}
```
(headers: `MCP-Protocol-Version: 2026-07-28`, `Mcp-Method: tools/call`, `Mcp-Name: suggest`)

**Request (Get Document):**
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "tools/call",
  "params": {
    "name": "get_document",
    "arguments": { "doc_id": "abc123" },
    "_meta": { "io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {} }
  }
}
```
(headers: `MCP-Protocol-Version: 2026-07-28`, `Mcp-Method: tools/call`, `Mcp-Name: get_document`)

**Response when the document is not found:**
```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "result": {
    "content": [{ "type": "text", "text": "Document not found: abc123" }],
    "isError": true
  }
}
```

`isError: true` results never carry `structuredContent` — see [Deviations From the Specification](#deviations-from-the-specification) item 3.

**Request (Get Index Stats)** — requires the caller to hold `mcp.tools.index_stats.permissions` (default `Radmin-api`); see [Get Index Stats and the permission gate](#get-index-stats-and-the-permission-gate):
```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "method": "tools/call",
  "params": {
    "name": "get_index_stats",
    "arguments": {},
    "_meta": { "io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {} }
  }
}
```
(headers: `MCP-Protocol-Version: 2026-07-28`, `Mcp-Method: tools/call`, `Mcp-Name: get_index_stats`)

**Unauthorized/unknown tool** (identical response for a tool that does not exist and one the caller is not authorized for — an unauthorized caller cannot distinguish the two):
```json
{
  "jsonrpc": "2.0",
  "id": 6,
  "error": { "code": -32602, "message": "Unknown tool: get_index_stats" }
}
```
(HTTP 200)

#### Unexpected tool failures carry a correlation id, not a message

An **unexpected** failure inside a tool never reaches the caller as text. It arrives as an `isError: true` result at HTTP 200 whose single content block is exactly:

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [{ "type": "text", "text": "Error: Tool execution failed (error_code:5f0a1c8e-...)" }],
    "isError": true
  }
}
```

A tool that reports a server-side failure itself gets the same treatment through the error channel instead: `-32603` with the message `Tool execution failed (error_code:<uuid>)`. Either way the real exception message and stack trace go to the Fess log at **WARN** under that same `error_code`, and **that log line is the only way to diagnose the call** — the response deliberately says nothing else. Grep the Fess log for the uuid a client reports.

This is not defensiveness for its own sake: these messages routinely embed text this plugin never wrote. Fess's own `InvalidQueryException` carries the fully serialized OpenSearch query DSL, *including* the role and permission filter terms already merged into it, and a caller can provoke it with nothing but an out-of-range `start`. A fresh uuid per failure is what makes a user report ("I got `error_code:X`") pinpoint one log line.

**Caller-directed errors are not redacted.** An error describing what is wrong with the caller's own request — JSON-RPC `-32700`, `-32600`, `-32601`, or `-32602` — passes through verbatim, because those messages are written by this plugin and name nothing but the offending argument: `Unknown tool: get_index_stats`, `Missing required parameter: doc_id`, `Invalid type for parameter: q (expected a string)`. The split is fail-closed: any other code, including a new one added later, is redacted until someone decides otherwise. The `Document not found: abc123` result shown above is unaffected too — that is not a failure at all, but a normal `isError: true` result the tool builds itself.

### 4. resources/list

List available resources. `fess://index/stats` is omitted for a caller not authorized for `get_index_stats` (same gate, same permission).

**Response** (authorized caller):
```json
{
  "jsonrpc": "2.0",
  "id": 7,
  "result": {
    "resources": [
      {
        "uri": "fess://index/stats",
        "name": "Index Statistics",
        "description": "Fess index statistics and configuration information",
        "mimeType": "application/json"
      }
    ],
    "ttlMs": 3600000,
    "cacheScope": "private"
  }
}
```
An unauthorized (or default-configuration, `mcp.auth.mode=none`) caller gets `"resources": []`. `cacheScope` follows the same `none` → `"public"`, otherwise `"private"` rule as `tools/list`.

### 5. resources/read

Read a specific resource by URI. Requires the `Mcp-Name` header to equal `params.uri`. Not cached by default (`mcp.cache.read.ttl.ms` defaults to `0`). This method is rate-limited (see [Configuration](#configuration)), the same as `tools/call`: both shapes below reach a Fess backend — `fess://document/<id>` makes the same document fetch `get_document` does, and `fess://index/stats` runs a live cluster/JVM stats collection.

**Request:**
```bash
curl -sS -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: resources/read' \
  -H 'Mcp-Name: fess://index/stats' \
  -d '{
    "jsonrpc": "2.0",
    "id": 8,
    "method": "resources/read",
    "params": {
      "uri": "fess://index/stats",
      "_meta": { "io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {} }
    }
  }'
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 8,
  "result": {
    "contents": [
      {
        "uri": "fess://index/stats",
        "mimeType": "application/json",
        "text": "{\"index\":{\"index_name\":\"fess.search\",\"document_count\":1234},\"config\":{\"max_page_size\":100},\"system\":{\"memory\":{\"total_bytes\":1073741824,\"free_bytes\":536870912,\"used_bytes\":536870912,\"max_bytes\":2147483648}}}"
      }
    ],
    "ttlMs": 0,
    "cacheScope": "private"
  }
}
```

`fess://document/{doc_id}` (the resource-template shape from `resources/templates/list`) is also accepted here, e.g. `uri: "fess://document/abc123"`; a `uri` matching neither shape, or `fess://index/stats` read by an unauthorized caller, gets the same `-32602 Resource not found: <uri>` (HTTP 200) regardless of which of the two applies.

### 6. resources/templates/list

List parameterized resource templates (RFC 6570 URI templates). Always `cacheScope: "public"`, with no auth-mode-dependent rule (unlike `tools/list` and `resources/list`).

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 9,
  "result": {
    "resourceTemplates": [
      {
        "uriTemplate": "fess://document/{doc_id}",
        "name": "Document by ID",
        "description": "Retrieve a Fess document by its document ID",
        "mimeType": "application/json"
      }
    ],
    "ttlMs": 3600000,
    "cacheScope": "public"
  }
}
```

### 7. prompts/list

List available prompts. Always `cacheScope: "public"`.

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "result": {
    "prompts": [
      {
        "name": "basic_search",
        "description": "Perform a basic search with a query string",
        "arguments": [
          { "name": "query", "description": "The search query", "required": true }
        ]
      },
      {
        "name": "advanced_search",
        "description": "Perform an advanced search with filters and sorting",
        "arguments": [
          { "name": "query", "description": "The search query", "required": true },
          { "name": "sort", "description": "Sort order (e.g., 'score.desc', 'last_modified.desc')", "required": false },
          { "name": "num", "description": "Number of results to return", "required": false }
        ]
      }
    ],
    "ttlMs": 3600000,
    "cacheScope": "public"
  }
}
```

### 8. prompts/get

Get a prompt with arguments substituted. Requires the `Mcp-Name` header to equal `params.name`. Not a `CacheableResult`.

**Request:**
```bash
curl -sS -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: prompts/get' \
  -H 'Mcp-Name: basic_search' \
  -d '{
    "jsonrpc": "2.0",
    "id": 11,
    "method": "prompts/get",
    "params": {
      "name": "basic_search",
      "arguments": { "query": "machine learning" },
      "_meta": { "io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {} }
    }
  }'
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 11,
  "result": {
    "messages": [
      { "role": "user", "content": { "type": "text", "text": "Please search for: machine learning" } }
    ]
  }
}
```

### 9. completion/complete

Request argument autocomplete. Not a `CacheableResult`. The completion source depends on `ref.type` and the argument name:

- `ref.type == "ref/prompt"` — dispatched by argument name:
  - `basic_search.query`, `advanced_search.query`: candidates come from the Fess suggest engine.
  - `advanced_search.sort`: candidates are prefix-filtered from a static enum (`score.desc`, `score.asc`, `last_modified.desc`, `last_modified.asc`, `create_timestamp.desc`, `create_timestamp.asc`).
  - `advanced_search.num`: no completions are returned.
- `ref.type == "ref/resource"`: no completions are returned (there is no source for `doc_id` completion).
- Any other `ref.type`: no completions are returned.

The `values` array is capped at 100 entries. This method is rate-limited (see [Configuration](#configuration)), the same as `tools/call`.

**Request:**
```bash
curl -sS -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: completion/complete' \
  -d '{
    "jsonrpc": "2.0",
    "id": 12,
    "method": "completion/complete",
    "params": {
      "ref": { "type": "ref/prompt", "name": "basic_search" },
      "argument": { "name": "query", "value": "mach" },
      "_meta": { "io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {} }
    }
  }'
```

**Response:**
```json
{
  "jsonrpc": "2.0",
  "id": 12,
  "result": {
    "completion": {
      "values": ["machine learning", "machine translation"],
      "total": 2,
      "hasMore": false
    }
  }
}
```

## Search Tool Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `q` | string | Yes | Query string for full-text search |
| `start` | integer | No | Start position for pagination (default: 0) |
| `offset` | integer | No | Alias for `start`. Used only when `start` is absent; if both are sent, `start` wins (even when `start` is itself unparseable or negative). |
| `num` | integer | No | Number of results to return (default: `mcp.default.page.size`, 3). Greater than `paging.search.page.max.size` is clamped to that maximum; **zero or negative falls back to the default**, not the maximum. |
| `sort` | string | No | Sort order (e.g., "score.desc", "last_modified.desc") |
| `fields` | object | No | Field filters keyed by field name, e.g. `{"label": ["label1"]}` |
| `lang` | string | No | Language filter |
| `as` | object | No | Advanced search conditions, keyed by condition name |
| `ex_q` | array of string | No | Extra queries |
| `sdh` | string | No | Similar document hash |

> Note: an argument's **top-level** JSON type is enforced — `q`, `sort`, and `sdh` must be strings, `fields` and `as` objects, `ex_q` an array — and a mismatch is rejected with `-32602` naming the argument and the expected type, e.g. `Invalid type for parameter: q (expected a string)`. The same holds for `q` on `suggest` and `doc_id` on `get_document`. `start`, `offset`, `num`, and `lang` are deliberate exceptions: their accessors accept a numeric string (or, for `lang`, any value) by design, so rejecting one would be a behaviour change rather than a fix. What is *inside* `fields`, `as`, and `ex_q` — including `fields.label` — is not validated; see [Deviations From the Specification](#deviations-from-the-specification) item 12.

## Suggest Tool Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `q` | string | Yes | Query prefix for autocomplete |
| `num` | integer | No | Number of suggestions (default: 10) |

> Note: `num` is capped by Fess's `paging.search.page.max.size` configuration. Requests exceeding this upper bound are clamped to the configured maximum.

## Get Document Tool Parameters

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `doc_id` | string | Yes | Document ID to retrieve |

## Get Index Stats and the Permission Gate

`get_index_stats` (and the equivalent `fess://index/stats` resource) exposes the index name, document count, and JVM heap usage. Since `2026-07-28` it is gated behind `mcp.tools.index_stats.permissions` (default `Radmin-api`): reading it bypasses Fess's normal role-based search filtering, so it is treated as an administrative capability rather than a search primitive open to every caller.

Because the default `mcp.auth.mode=none` never resolves any permission for any caller, **the default configuration disables this tool and resource for everyone**, not just anonymous users — there is no way for a `none`-mode caller to ever hold `Radmin-api`. To restore the pre-`2026-07-28` open behaviour, set:

```
mcp.tools.index_stats.permissions=
```

(a blank value disables the gate entirely). To keep it gated but usable, switch to `mcp.auth.mode=fess_token` or `mcp.auth.mode=oauth` and grant the configured permission to the credential/token/scope in question.

An unauthorized caller cannot distinguish "this tool doesn't exist" from "you may not use this tool": both `tools/call` and `resources/read` answer with the identical `-32602` error a genuinely-unknown tool or resource would get.

### Query Syntax

The search tool supports Lucene-like query syntax:

| Syntax | Description | Example |
|--------|-------------|---------|
| `term1 term2` | AND search (default) | `machine learning` |
| `term1 OR term2` | OR search | `cat OR dog` |
| `"phrase"` | Phrase search | `"machine learning"` |
| `-term` | Exclude term | `python -java` |

## Usage Examples

### Using curl

```bash
# server/discover
curl -sS -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: server/discover' \
  -d '{
    "jsonrpc": "2.0",
    "id": 1,
    "method": "server/discover",
    "params": { "_meta": { "io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {} } }
  }'

# Search documents
curl -sS -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/call' \
  -H 'Mcp-Name: search' \
  -d '{
    "jsonrpc": "2.0",
    "id": 2,
    "method": "tools/call",
    "params": {
      "name": "search",
      "arguments": { "q": "machine learning", "num": 5 },
      "_meta": { "io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {} }
    }
  }'

# Get index statistics (requires mcp.auth.mode=fess_token or oauth, and the configured permission)
curl -sS -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <fess-access-token>' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/call' \
  -H 'Mcp-Name: get_index_stats' \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "get_index_stats",
      "arguments": {},
      "_meta": { "io.modelcontextprotocol/protocolVersion": "2026-07-28", "io.modelcontextprotocol/clientCapabilities": {} }
    }
  }'
```

### Using Python

```python
import requests
import json

url = "http://localhost:8080/mcp"
headers = {
    "Content-Type": "application/json",
    "MCP-Protocol-Version": "2026-07-28",
    "Mcp-Method": "tools/call",
    "Mcp-Name": "search",
}

search_request = {
    "jsonrpc": "2.0",
    "id": 1,
    "method": "tools/call",
    "params": {
        "name": "search",
        "arguments": {"q": "elasticsearch", "num": 10},
        "_meta": {
            "io.modelcontextprotocol/protocolVersion": "2026-07-28",
            "io.modelcontextprotocol/clientCapabilities": {},
        },
    },
}

response = requests.post(url, headers=headers, data=json.dumps(search_request))
result = response.json()
print(json.dumps(result, indent=2))
```

### Using with an MCP Client

```json
{
  "mcpServers": {
    "fess": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "http://localhost:8080/mcp"]
    }
  }
}
```

**`mcp-remote` does not currently speak `2026-07-28`.** As of this writing, `mcp-remote` (the most common local-to-remote MCP bridge for desktop clients) is built against the `1.x` generation of the TypeScript SDK, which still performs the legacy `initialize` handshake — it will fail against this endpoint, since `initialize` now returns `-32601`. The MCP SDKs that add `2026-07-28` support (TypeScript/Python SDK v2, in beta as of this writing) ship under new, separate package names rather than as a drop-in upgrade to `mcp-remote`'s dependency. Until a `2026-07-28`-aware bridge is available, drive this endpoint directly with `curl`/`requests` as shown above, or with an MCP client whose own HTTP transport has been updated for `2026-07-28`.

## Error Handling

The API returns standard JSON-RPC 2.0 error responses:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "error": {
    "code": -32601,
    "message": "Unknown method: invalid_method"
  }
}
```

Unlike a strict per-code HTTP status mapping, the HTTP status for a given JSON-RPC code depends on *where* it is raised: for example `-32602` is HTTP 400 for a malformed `params._meta`, but HTTP 200 (with a JSON-RPC error body) for an unknown/unauthorized tool, prompt, or resource, for a wrong-typed tool argument, or for an inbound `cursor`.

An error raised by a tool is a special case: only caller-directed codes carry their real message, and anything else is replaced with a correlation id you must look up in the Fess log. See [Unexpected tool failures carry a correlation id, not a message](#unexpected-tool-failures-carry-a-correlation-id-not-a-message).

### Error Codes

| Code | Message | Description |
|------|---------|--------------|
| -32700 | Parse error | Invalid JSON was received; also covers a JSON array request body — `Json.parseObject` rejects the array shape before `McpRequest.parse` ever runs, so a batch request never reaches Request-object validation |
| -32600 | Invalid Request | The JSON sent is not a valid Request object (e.g. an explicit `"id": null`) |
| -32601 | Method not found | The method does not exist, including the retired `initialize` and `ping` |
| -32602 | Invalid params | Invalid method parameter(s), including a tool argument whose JSON type disagrees with the tool's `inputSchema`; also covers an unknown, gated, or otherwise unusable tool/prompt/resource, and a non-null inbound `cursor` (this server never issues one; an explicit `null` is treated as absent) |
| -32603 | Internal error | Internal JSON-RPC error; also used for a rate-limit refusal (HTTP 429, with a `retryAfterSeconds` in `error.data` and an HTTP `Retry-After` header), and for a tool reporting a server-side failure, whose message is always the fixed `Tool execution failed (error_code:<uuid>)` |
| -32020 | HeaderMismatch | A required MCP request-metadata header (`MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name`) is missing, or disagrees with the request body |
| -32021 | MissingRequiredClientCapability | The request needs a client capability the client did not declare. **Defined by this server but never emitted** — nothing in this plugin currently requires an optional client capability. |
| -32022 | UnsupportedProtocolVersion | The client's declared protocol version is not `2026-07-28`; `error.data` carries both `supported` and `requested` |

`-32002` (`Resource not found`, used by earlier protocol revisions) no longer exists as a distinct code: a not-found resource, prompt, or tool is `-32602` in `2026-07-28`.

## OAuth 2.1 Setup

Setting `mcp.auth.mode=oauth` turns on RFC 6750 Bearer-JWT verification against an external authorization server, per RFC 9728 (OAuth 2.0 Protected Resource Metadata) and the MCP Authorization specification. This mode is a pure resource-server token *verifier*: it never issues tokens itself.

1. **`mcp.oauth.issuer`** (required) — the authorization server's issuer URL. Without this, `oauth` mode is not usable and every request falls back to `none`-mode behaviour (reported at ERROR — see [When the configuration is not usable](#when-the-configuration-is-not-usable)).
2. **`mcp.oauth.audience`** (required) — the canonical resource URI this server checks a token's `aud` claim against (RFC 8707), and the value served as the `resource` field of the RFC 9728 protected-resource metadata document (see below). **This server never derives it from the request** (e.g. from the `Host` header): an unset audience makes `oauth` mode unusable, the same as an unset issuer, falling back to `none`-mode behaviour. Deriving a security-critical resource identifier from a caller-controlled `Host` header would let an attacker holding a token legitimately minted by the *same* issuer for a *different* resource simply send that resource's hostname and be admitted — the confused-deputy case RFC 8707 audience binding exists to prevent, so this server requires an explicit, operator-pinned value instead. **It must end in `/mcp`** — this server's protected-resource metadata endpoint only serves the well-known path for a resource path ending in that segment, so an audience ending anywhere else also makes `oauth` mode unusable. Example: `https://fess.example.com/mcp`.
3. **`mcp.oauth.jwks.uri`** (required) — the authorization server's JWKS endpoint. Signatures are verified RS256-only; the fetched key set is cached for `mcp.oauth.jwks.cache.seconds` (default 300). A configured cache lifetime below 60 seconds is raised to 60 — see the settings table below. Without this, `oauth` mode is not usable and falls back to `none`-mode behaviour — a deployment that instead let this mode be selected with no JWKS endpoint configured would 401 every request with a misleading "invalid token" message, since the failure only surfaces once a token-bearing request actually reaches JWKS resolution.
4. **`mcp.oauth.required.scopes`** (optional) — a comma-separated list of scopes a token's `scope` claim must contain, in full, before the caller is admitted. **Write this using the authorization server's own leaf scopes** — the literal scopes an issued token actually carries. This server performs a plain subset check with no scope-hierarchy resolution: if your authorization server treats (say) `fess:admin` as implying `fess:search`, list `fess:search` explicitly too, because this server will not expand it for you.
5. **Permission mapping** — how a verified token's claims become Fess's own encoded permission strings (e.g. `Radmin-api`, `Rguest`), which drive both search-result role filtering and the `get_index_stats` gate. Two independent, additive sources:
   - **`mcp.oauth.permission.claim`** — the name of a JWT claim (a JSON array, or a space-delimited string) whose values are already Fess-encoded permissions.
   - **`mcp.oauth.scope.permission.map`** — a comma-separated list of `scope=permission` pairs, e.g. `fess:search=Rguest,fess:search=1guest,fess:admin=Radmin-api`. A scope may appear in more than one pair; every mapped permission accumulates.

   If neither source contributes anything for a given token, the caller falls back to Fess's configured guest role list, exactly as an anonymous `none`-mode caller would.

### When the configuration is not usable

`mcp.auth.mode=oauth` with an unset `mcp.oauth.issuer`, `mcp.oauth.audience`, or `mcp.oauth.jwks.uri` — or an audience that does not end in `/mcp` — is **not** a hard failure: the endpoint falls back to `none`-mode behaviour and serves `/mcp` anonymously. That fallback is reported at ERROR, naming all three keys, and the accompanying `mcp.auth.mode=none` WARN says every caller is now anonymous.

"Anonymously" describes how roles are resolved, and it is not the whole story for a client that keeps sending the credential it was configured with. In `none` mode this plugin does not own role resolution, so Fess's own `RoleQueryHelper` runs and hands the request to `AccessTokenService`, which reads the raw `Authorization` header and interprets it as a **Fess access token**. An OAuth bearer JWT therefore does not match any Fess token and is rejected (`Invalid token: ...`), and an `Authorization: Basic ...` header is rejected earlier still (`Invalid format: ...`) — so `search` and `get_document` fail with `isError: true` for precisely the clients that were working before the fallback. A client must drop its `Authorization` header to actually be served anonymously. This is long-standing Fess behaviour rather than something this revision introduced, but it is what the fallback means in practice.

**This is not a startup-only check.** Every request re-reads `mcp.auth.mode` and the `mcp.oauth.*` keys, and Fess re-reads its properties file within about five seconds of an edit, so `/mcp` can flip between "credential required" and "anonymous for everyone" on a live server with no restart. The startup log line therefore is not the whole story: a transition is logged when it happens — once per transition, not once per request, because this is an unauthenticated endpoint and a per-request WARN would be a disk-filling amplifier a caller controls. Going the other way (a fix taking effect) is logged at INFO.

"OAuth was configured but rejected" is tracked as a state distinct from "the operator chose `none`", so an operator who meant to enable OAuth and got the configuration wrong is told so, rather than seeing the same message a deliberately unauthenticated deployment sees. That also means flipping between `none` and a typo'd mode string is correctly *not* logged: both are the same posture.

### Deployments under a context path

`mcp.oauth.audience` must *end in* `/mcp`, but it may carry leading path segments: if Fess runs under a context path (`FESS_CONTEXT_PATH` / `-Dfess.context.path`) or behind a reverse proxy mounting it at a subpath, the correct audience is `https://fess.example.com/api/mcp`, and the protected-resource metadata document is served at `https://fess.example.com/api/.well-known/oauth-protected-resource/mcp`.

That URL is built under the application's own prefix rather than the host-rooted form RFC 9728 §3.1 specifies (`https://fess.example.com/.well-known/oauth-protected-resource/api/mcp`). The deviation is forced: the §3.1 URL lies outside the servlet context and Fess cannot serve it under a context path at all. The two forms are identical when Fess is deployed at the root. Clients that follow the `resource_metadata` URL advertised in the `WWW-Authenticate` challenge — the discovery flow RFC 9728 and the MCP Authorization specification both prescribe — are unaffected; a client that instead constructs the §3.1 URL itself will get a 404 under a subpath deployment. See [Deviations From the Specification](#deviations-from-the-specification) item 11.

### Accepted token `typ`

Access tokens are accepted with a `typ` header of `at+jwt` or `application/at+jwt` (RFC 9068 §4), with `typ: JWT`, or with no `typ` at all — matched case-insensitively. Any other `typ` is rejected. The two RFC 9068 values were previously rejected; `typ: JWT` is what Entra ID, Auth0, Okta, and a default-configured Keycloak actually emit for access tokens, which is why accepting only `at+jwt` is not an option.

Widening this does not weaken anything. An OIDC ID Token carries `typ: JWT` or no `typ` at all — both already accepted before — so adding two values no ID Token uses changes nothing about that exposure. What keeps an ID Token out is the issuer and audience checks: an ID Token's `aud` is the client's `client_id`, never this server's canonical resource URI, so it fails RFC 8707 audience binding whatever its `typ` says.

### Protected Resource Metadata

When `oauth` mode is usable, this plugin serves an RFC 9728 document at both `/.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp` (both return HTTP 404 otherwise):

```bash
curl -sS http://localhost:8080/.well-known/oauth-protected-resource/mcp
```
```json
{
  "resource": "https://fess.example.com/mcp",
  "authorization_servers": ["https://auth.example.com/"],
  "scopes_supported": ["fess:search"],
  "bearer_methods_supported": ["header"]
}
```

`scopes_supported` is `mcp.oauth.required.scopes`, parsed the same way it is enforced, minus `offline_access` (this server never issues refresh tokens, so it never advertises that scope even if accidentally listed). A rejected request also carries the metadata URL in its `WWW-Authenticate` challenge, e.g.:

```
WWW-Authenticate: Bearer realm="fess-mcp", error="insufficient_scope", error_description="The token is missing a required scope.", scope="fess:search", resource_metadata="https://fess.example.com/.well-known/oauth-protected-resource/mcp"
```

## Configuration

The following system properties can be configured in Fess. They are read through `FessProp#getSystemProperty`, so they belong in **`WEB-INF/conf/system.properties`** (or, as a JVM argument, **`-Dfess.system.<key>`** — note the `fess.system.` prefix, not `fess.`). `fess_config.properties` and `-Dfess.config.*` are a different channel and have **no effect** on any key below; a key put there is silently ignored, with no error and no log line. **Boolean keys accept only `true`, matched case-insensitively** (so `True`/`TRUE` also work) — any other value, including `1`, `yes`, or an empty string, is treated as `false` (`FessProp#getSystemPropertyAsBoolean` is `Constants.TRUE.equalsIgnoreCase(...)`) — so `mcp.enabled=1` silently disables the endpoint rather than enabling it.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.enabled` | `true` | Enables the `/mcp` endpoint. When `false` (or unparseable-as-true), every request gets HTTP 503. |
| `mcp.auth.mode` | `none` | `none` (no authentication — the default, kept for backward compatibility), `fess_token` (Bearer + Fess access token), or `oauth` (Bearer JWT, RFC 9728). An unusable `oauth` configuration falls back to `none`. |
| `mcp.allowed.origins` | *(blank)* | Comma-separated allowed `Origin` values — the complete allowlist. A present `Origin` that is not listed is rejected with HTTP 403; an absent `Origin` header is always allowed (non-browser clients rarely send one). **Blank means no browser origin is allowed, including the server's own**: a browser-based MCP client must be listed explicitly (e.g. `https://fess.example.com`), even when it is served from the Fess host itself. The server's own origin is deliberately not implied, because it could only be derived from the caller-supplied `Host` header — a DNS-rebinding attacker simply sends a matching `Host`/`Origin` pair, which is the attack this check exists to stop. Listing an origin here is necessary but **not sufficient** for a cross-origin browser client — see [Browser Clients and CORS](#browser-clients-and-cors). |
| `mcp.request.max.bytes` | `1048576` | Maximum accepted request body size, in bytes. Larger bodies get HTTP 413. The limit bounds the read itself — at most `max + 1` bytes are ever buffered, which is all it takes to prove a body is over — so an oversized body is never read into memory and never decoded. `Content-Length` is deliberately ignored: it is caller-supplied and absent entirely for a chunked body. There is no "unlimited" sentinel: `0` or a negative value rejects **every** body, and `2147483647` (`Integer.MAX_VALUE`) removes the bound altogether, restoring an unbounded read of an attacker-chosen body size before authentication and before rate limiting. Leave it at a real byte count. |
| `mcp.rate.limit.per.minute` | `60` | Per-caller limit on `tools/call`, `completion/complete`, and `resources/read` calls per **fixed** one-minute window (`System.currentTimeMillis() / 60_000`, not a rolling window — so a caller can burst up to 2× the limit across a window boundary). Keyed on the authenticated subject; when unauthenticated, on the client IP as resolved by Fess's `RateLimitHelper#getClientIp`, which honours `X-Forwarded-For`/`X-Real-IP` **only** when the peer is listed in Fess's `rate.limit.trusted.proxies` (default `127.0.0.1,::1`) and returns `getRemoteAddr()` otherwise. **Behind a reverse proxy, set `rate.limit.trusted.proxies`** — see [Deviations From the Specification](#deviations-from-the-specification) item 8 for why, and for the residual trade-off. `0` or negative disables the limiter. A token with no (or a blank) `sub` claim is authenticated but has no subject to key on, so it falls back to the client IP and therefore shares one bucket with every other such caller behind the same peer. |
| `mcp.tools.index_stats.permissions` | `Radmin-api` | Comma-separated encoded Fess permissions required to use `get_index_stats` / read `fess://index/stats`. Blank disables the gate. |
| `mcp.cache.discover.ttl.ms` | `3600000` | `ttlMs` reported by `server/discover`. |
| `mcp.cache.list.ttl.ms` | `3600000` | `ttlMs` reported by `tools/list`, `resources/list`, `resources/templates/list`, and `prompts/list`. |
| `mcp.cache.read.ttl.ms` | `0` | `ttlMs` reported by `resources/read` (not cached by default). |
| `mcp.oauth.issuer` | *(blank)* | The authorization server's issuer URL. Required for `oauth` mode to be usable. |
| `mcp.oauth.audience` | *(blank)* | The canonical resource URI used for `aud` validation and `resource_metadata`. **Required for `oauth` mode to be usable** — never derived from the request. Must *end in* `/mcp`; leading path segments are supported, so `https://host/api/mcp` is correct under a context path — see [Deployments under a context path](#deployments-under-a-context-path). |
| `mcp.oauth.jwks.uri` | *(blank)* | The authorization server's JWKS endpoint (RS256 signature verification). Required for `oauth` mode to be usable. |
| `mcp.oauth.jwks.cache.seconds` | `300` | How long a fetched JWKS is cached before refreshing. **Values below 60 are raised to 60** (logged once at WARN with both the configured and effective value). 60 seconds is the smallest lifetime the underlying JWKS source can be built with: it must accommodate both a 30-second refresh-ahead window and a 30-second refresh timeout, and must be strictly longer than the 30-second rate-limiter interval. Before this clamp, a smaller value made the JWKS source fail to build and every token-bearing request return a misleading `invalid_token` 401, permanently. |
| `mcp.oauth.required.scopes` | *(blank)* | Comma-separated leaf scopes a token's `scope` claim must fully contain. Blank means no scope is required. |
| `mcp.oauth.permission.claim` | *(blank)* | JWT claim name whose values are already-encoded Fess permissions. |
| `mcp.oauth.scope.permission.map` | *(blank)* | Comma-separated `scope=permission` pairs mapping token scopes to encoded Fess permissions. |
| `mcp.content.max.length` | `10000` | Maximum length of search/get_document result content in characters (a `...` suffix is appended when truncated). |
| `mcp.highlight.fragment.size` | `500` | Size of highlight fragments in characters. |
| `mcp.highlight.num.of.fragments` | `3` | Number of highlight fragments per search result. |
| `mcp.default.page.size` | `3` | Default number of search results when `num` is not supplied. |

### Browser Clients and CORS

Every MCP request carries `MCP-Protocol-Version` and `Mcp-Method` (and `Mcp-Name` for some methods). None of the three is CORS-safelisted, so a **cross-origin** browser client always preflights. Fess's shipped `api.cors.allow.headers` is `Origin, Content-Type, Accept, Authorization, X-Requested-With, X-Fess-CSRF-Token`, and Fess's `DefaultCorsHandler` returns that list **verbatim** — it does not echo `Access-Control-Request-Headers`. The browser therefore blocks the real request before this plugin's `Origin` check ever runs, and `mcp.allowed.origins` has no effect for that client.

The fix is operator-side: extend Fess's `api.cors.allow.headers` with the three MCP headers, alongside adding the client to `mcp.allowed.origins`.

```
api.cors.allow.headers=Origin, Content-Type, Accept, Authorization, X-Requested-With, X-Fess-CSRF-Token, MCP-Protocol-Version, Mcp-Method, Mcp-Name
```

A **same-origin** browser client does not preflight and is unaffected, as is every non-browser client.

## Deviations From the Specification

These are deliberate, reviewed choices, not oversights:

1. **`mcp.auth.mode` defaults to `none`.** The MCP Streamable HTTP transport's Security Considerations say a server SHOULD authenticate every connection. This server does not, by default — kept for backward compatibility, so an existing Fess deployment keeps working unmodified after upgrading this plugin. The endpoint logs a WARN whenever it resolves to unauthenticated behaviour (including an unrecognised `mcp.auth.mode` value, or an `oauth` configuration that turned out not to be usable): at startup, and again on any later transition into or out of that posture, since the mode is re-resolved per request and Fess's properties are live. See [When the configuration is not usable](#when-the-configuration-is-not-usable).
2. **`search`, `suggest`, and `get_document` keep Markdown text blocks, not serialized JSON.** The spec SHOULDs that a tool result's `content` text block, when `structuredContent` is also present, carry the same information serialized as JSON. These three tools keep their pre-existing human-readable Markdown text instead, so clients already parsing today's `content` blocks are not broken by this migration. **`get_index_stats` is the exception, and is already spec-compliant on this point**: its `content[0].text` has always been `JsonXContent.contentBuilder().map(stats).toString()` — the same data `structuredContent` carries (minus nulls), serialized as JSON, not Markdown.
3. **`isError: true` results carry no `structuredContent`.** Verified correct against the MCP schema: both `content` and `structuredContent` are optional on `CallToolResult`, and there is nothing structured to report for a failure.
4. **No automatic scope-hierarchy resolution.** `mcp.oauth.required.scopes` must be written using the authorization server's own leaf scopes — see [OAuth 2.1 Setup](#oauth-21-setup).
5. **`mcp.oauth.audience` is required for `oauth` mode, and must end in `/mcp`.** It is never derived from the request. An unset audience, or one not ending in `/mcp`, makes `oauth` mode unusable and falls back to `none` (reported at ERROR — at startup and on any later transition; see [When the configuration is not usable](#when-the-configuration-is-not-usable)). Leading path segments *are* supported: `https://fess.example.com/api/mcp` is correct and usable for a context-path deployment — see item 11. This server used to derive the audience from the request's `Host` header (honouring `X-Forwarded-Host` only from a trusted proxy) when `mcp.oauth.audience` was unset; that derivation let an attacker holding a token legitimately minted by the same issuer for a *different* resource simply send that resource's hostname as `Host` and be admitted — RFC 8707 audience binding exists precisely to prevent this confused-deputy case, so it is now a hard requirement rather than an optional override.
6. **`oauth` mode with an unset `mcp.oauth.issuer` or `mcp.oauth.jwks.uri` falls back to `none`.** RFC 9728 requires a protected-resource metadata document's `authorization_servers` to be non-empty; serving one with none would be worse than not enabling authorization at all, so this server refuses to try rather than serving a broken document. An unset `mcp.oauth.jwks.uri` would otherwise let `oauth` mode be selected but fail every request with a misleading "invalid token" 401, since the missing endpoint only surfaces once a token-bearing request reaches JWKS resolution.
7. **`get_index_stats` is gated by default** (`mcp.tools.index_stats.permissions=Radmin-api`), so it is unavailable in the default `none` mode. This tool bypasses Fess's normal role-based search filtering and exposes the index name, document count, and JVM heap — administrative information, not a search result. See [Get Index Stats and the permission gate](#get-index-stats-and-the-permission-gate).
8. **The rate limiter is per-principal fairness, not a flood defence.** It has no cross-key cap: `MAX_TRACKED_KEYS` (10,000 distinct keys) is a *sweep trigger* that prompts the limiter to evict stale-window entries once the tracked-key count crosses it, not a hard ceiling — a flood from more distinct keys than that within one window is not throttled by this mechanism at all. For IP-level flood defence, enable Fess's own `rate.limit.*` filter alongside this plugin.

   The window is fixed rather than rolling, which is the same deliberate simplification: a caller can burst up to 2× `mcp.rate.limit.per.minute` across a window boundary, and a refused caller is always told to wait the full 60 seconds because the limiter tracks whole minutes and cannot report a shorter, exact remainder.

   The unauthenticated key is the client IP as Fess's own `RateLimitHelper` resolves it, which is an honest trade rather than a clean win. Using `getRemoteAddr()` directly is not viable: behind nginx or Apache that is the proxy's address for *every* caller, and Fess ships no `RemoteIpValve`, so that collapse is the default deployment — one caller spending the budget would 429 every other client of the same instance. Delegating to `RateLimitHelper` means `X-Forwarded-For`/`X-Real-IP` are honoured, but **only** from a peer listed in Fess's `rate.limit.trusted.proxies` (default `127.0.0.1,::1`). The residual weakness: where a trusted proxy *is* configured, the first `X-Forwarded-For` element is used, and a client can forge it if that proxy appends to the header rather than replacing it — giving a determined attacker unlimited keys. Fess's own rate-limit filter accepts the same trade-off, and it is the better default: the alternative is a limiter that is not merely bypassable but actively harmful to innocent clients, who all share a bucket they cannot influence.
9. **Boolean config keys accept only `true` (case-insensitive), not `1`/`yes`/etc.** `getSystemPropertyAsBoolean` is a case-insensitive `equalsIgnoreCase` check against the string `"true"`; every other value, including `"1"` or `"yes"`, is `false` — see the Configuration table's warning.
10. **Large JWTs and Tomcat's `maxHttpHeaderSize`.** A `Bearer` JWT carrying many claims (especially a large `mcp.oauth.permission.claim` array) can exceed Tomcat's default `maxHttpHeaderSize` (8 KB). `oauth`-mode deployments issuing larger tokens should raise this Tomcat setting.
11. **The protected-resource metadata URL is built under the application's prefix, not host-rooted as RFC 9728 §3.1 specifies.** Under a context path the audience `https://host/api/mcp` yields `https://host/api/.well-known/oauth-protected-resource/mcp`, not §3.1's `https://host/.well-known/oauth-protected-resource/api/mcp`. The deviation is forced — the §3.1 URL is above the servlet context and Fess cannot serve it at all — and the two forms coincide at the root, so only subpath deployments see any difference. Clients that follow the advertised `resource_metadata` URL are unaffected; one that constructs the §3.1 URL itself gets a 404. See [Deployments under a context path](#deployments-under-a-context-path).
12. **Tool inputs are type-checked, not schema-validated.** `inputSchema` is advertised for clients to validate against; the server checks that required arguments are present and that each declared argument has the right top-level JSON type (`-32602` otherwise), but does not validate nested element types, formats, or ranges. There is no JSON Schema validator on the plugin's classpath, and the plugin ships as a single JAR that bundles no dependencies of its own, so adding one is a packaging decision rather than a code change. A wrong *nested* type (an element inside `fields`, `as`, or `ex_q`) still fails inside the search and is reported as a redacted `isError: true` result.

## Development

### Building from Source

```bash
mvn clean package
```

### Running Tests

```bash
mvn test
```

### Code Formatting

```bash
mvn formatter:format && mvn license:format
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

Apache License 2.0

## Support

For issues and questions, please use the [GitHub Issues](https://github.com/codelibs/fess-webapp-mcp/issues).
