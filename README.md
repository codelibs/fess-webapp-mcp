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
- **Every non-notification request now needs request-metadata headers and a `params._meta` object.** `MCP-Protocol-Version` and `Mcp-Method` are always required, plus `Mcp-Name` for `tools/call`, `prompts/get`, and `resources/read`. Every header must exactly match the corresponding body value. See [Required Headers and `_meta`](#required-headers-and-_meta). A request that omits one of these, or where a header disagrees with the body, is rejected with HTTP 400 and JSON-RPC `-32020` before the method is even dispatched.
- **`get_index_stats` is gated by a permission by default** (`mcp.tools.index_stats.permissions=Radmin-api`). Because the default `mcp.auth.mode=none` never grants any permission to any caller, **this tool and the `fess://index/stats` resource are effectively unavailable out of the box** for every caller — hidden from `tools/list`/`resources/list`, and refused (indistinguishably from "does not exist") by `tools/call`/`resources/read`. See [Get Index Stats and the permission gate](#get-index-stats-and-the-permission-gate).
- **A per-caller rate limit is enabled by default** (`mcp.rate.limit.per.minute=60`) on `tools/call` and `completion/complete`. A caller that exceeds it gets HTTP 429 with a `Retry-After` header.

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
- **Origin Validation**: Rejects a present, disallowed `Origin` header (browser CSRF/DNS-rebinding defence)
- **Rate Limiting**: Per-caller fixed-window limit on `tools/call` / `completion/complete`
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

A `Mcp-Name` value that is not pure ASCII cannot be carried in an HTTP header field directly; encode it as `=?base64?<base64-of-utf8-bytes>?=` and this server will decode it before comparing it against the body.

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

`content` stays Markdown-style text even though `structuredContent` is also present — see [Deviations From the Specification](#deviations-from-the-specification) item 2. `tools/call` is **not** a `CacheableResult`: it never carries `ttlMs` or `cacheScope`. `structuredContent` only ever carries the fields Fess actually populated (see the `search` tool's `outputSchema` above): a field that is genuinely absent from the underlying document is omitted, not filled in with an empty string or `null`.

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

Read a specific resource by URI. Requires the `Mcp-Name` header to equal `params.uri`. Not cached by default (`mcp.cache.read.ttl.ms` defaults to `0`).

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
| `offset` | integer | No | Alias for start position |
| `num` | integer | No | Number of results to return (default: `mcp.default.page.size`, 3) |
| `sort` | string | No | Sort order (e.g., "score.desc", "last_modified.desc") |
| `fields.label` | array | No | Specific labels to filter by |
| `lang` | string | No | Language filter |
| `as` | object | No | Advanced search conditions, keyed by condition name |
| `ex_q` | array of string | No | Extra queries |
| `sdh` | string | No | Similar document hash |

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

Unlike a strict per-code HTTP status mapping, the HTTP status for a given JSON-RPC code depends on *where* it is raised: for example `-32602` is HTTP 400 for a malformed `params._meta`, but HTTP 200 (with a JSON-RPC error body) for an unknown/unauthorized tool, prompt, or resource, or an invalid `cursor`.

### Error Codes

| Code | Message | Description |
|------|---------|--------------|
| -32700 | Parse error | Invalid JSON was received; also covers a JSON array request body — `Json.parseObject` rejects the array shape before `McpRequest.parse` ever runs, so a batch request never reaches Request-object validation |
| -32600 | Invalid Request | The JSON sent is not a valid Request object (e.g. an explicit `"id": null`) |
| -32601 | Method not found | The method does not exist, including the retired `initialize` and `ping` |
| -32602 | Invalid params | Invalid method parameter(s); also covers an unknown, gated, or otherwise unusable tool/prompt/resource, and an inbound `cursor` (this server never issues one) |
| -32603 | Internal error | Internal JSON-RPC error; also used for a rate-limit refusal (HTTP 429, with a `retryAfterSeconds` in `error.data` and an HTTP `Retry-After` header) |
| -32020 | HeaderMismatch | A required MCP request-metadata header (`MCP-Protocol-Version`, `Mcp-Method`, `Mcp-Name`) is missing, or disagrees with the request body |
| -32021 | MissingRequiredClientCapability | The request needs a client capability the client did not declare. **Defined by this server but never emitted** — nothing in this plugin currently requires an optional client capability. |
| -32022 | UnsupportedProtocolVersion | The client's declared protocol version is not `2026-07-28`; `error.data` carries both `supported` and `requested` |

`-32002` (`Resource not found`, used by earlier protocol revisions) no longer exists as a distinct code: a not-found resource, prompt, or tool is `-32602` in `2026-07-28`.

## OAuth 2.1 Setup

Setting `mcp.auth.mode=oauth` turns on RFC 6750 Bearer-JWT verification against an external authorization server, per RFC 9728 (OAuth 2.0 Protected Resource Metadata) and the MCP Authorization specification. This mode is a pure resource-server token *verifier*: it never issues tokens itself.

1. **`mcp.oauth.issuer`** (required) — the authorization server's issuer URL. Without this, `oauth` mode is not usable and every request falls back to `none`-mode behaviour (logged once at ERROR on startup).
2. **`mcp.oauth.jwks.uri`** — the authorization server's JWKS endpoint. Signatures are verified RS256-only; the fetched key set is cached for `mcp.oauth.jwks.cache.seconds` (default 300).
3. **`mcp.oauth.audience`** (optional) — overrides the canonical resource URI this server checks a token's `aud` claim against (RFC 8707), instead of deriving `<scheme>://<host>[:port]/mcp` from the request (honouring `X-Forwarded-*` only from a proxy listed in Fess's own `rate.limit.trusted.proxies`). **If set, it must end in `/mcp`** — this server's protected-resource metadata endpoint only serves the well-known path for that exact resource path, so an audience ending anywhere else makes `oauth` mode unusable (falls back to `none`, logged at ERROR).
4. **`mcp.oauth.required.scopes`** (optional) — a comma-separated list of scopes a token's `scope` claim must contain, in full, before the caller is admitted. **Write this using the authorization server's own leaf scopes** — the literal scopes an issued token actually carries. This server performs a plain subset check with no scope-hierarchy resolution: if your authorization server treats (say) `fess:admin` as implying `fess:search`, list `fess:search` explicitly too, because this server will not expand it for you.
5. **Permission mapping** — how a verified token's claims become Fess's own encoded permission strings (e.g. `Radmin-api`, `Rguest`), which drive both search-result role filtering and the `get_index_stats` gate. Two independent, additive sources:
   - **`mcp.oauth.permission.claim`** — the name of a JWT claim (a JSON array, or a space-delimited string) whose values are already Fess-encoded permissions.
   - **`mcp.oauth.scope.permission.map`** — a comma-separated list of `scope=permission` pairs, e.g. `fess:search=Rguest,fess:search=1guest,fess:admin=Radmin-api`. A scope may appear in more than one pair; every mapped permission accumulates.

   If neither source contributes anything for a given token, the caller falls back to Fess's configured guest role list, exactly as an anonymous `none`-mode caller would.

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

The following system properties can be configured in Fess. **Boolean keys accept only `true`, matched case-insensitively** (so `True`/`TRUE` also work) — any other value, including `1`, `yes`, or an empty string, is treated as `false` (`FessProp#getSystemPropertyAsBoolean` is `Constants.TRUE.equalsIgnoreCase(...)`) — so `mcp.enabled=1` silently disables the endpoint rather than enabling it.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.enabled` | `true` | Enables the `/mcp` endpoint. When `false` (or unparseable-as-true), every request gets HTTP 503. |
| `mcp.auth.mode` | `none` | `none` (no authentication — the default, kept for backward compatibility), `fess_token` (Bearer + Fess access token), or `oauth` (Bearer JWT, RFC 9728). An unusable `oauth` configuration falls back to `none`. |
| `mcp.allowed.origins` | *(blank)* | Comma-separated additional allowed `Origin` values, beyond the server's own origin. A present, disallowed `Origin` is rejected with HTTP 403; an absent `Origin` header is always allowed (non-browser clients rarely send one). |
| `mcp.request.max.bytes` | `1048576` | Maximum accepted request body size, in bytes. Larger bodies get HTTP 413. |
| `mcp.rate.limit.per.minute` | `60` | Per-caller limit on `tools/call` / `completion/complete` calls per rolling one-minute window. Keyed on the authenticated subject, or the client IP when unauthenticated. `0` or negative disables the limiter. |
| `mcp.tools.index_stats.permissions` | `Radmin-api` | Comma-separated encoded Fess permissions required to use `get_index_stats` / read `fess://index/stats`. Blank disables the gate. |
| `mcp.cache.discover.ttl.ms` | `3600000` | `ttlMs` reported by `server/discover`. |
| `mcp.cache.list.ttl.ms` | `3600000` | `ttlMs` reported by `tools/list`, `resources/list`, `resources/templates/list`, and `prompts/list`. |
| `mcp.cache.read.ttl.ms` | `0` | `ttlMs` reported by `resources/read` (not cached by default). |
| `mcp.oauth.issuer` | *(blank)* | The authorization server's issuer URL. Required for `oauth` mode to be usable. |
| `mcp.oauth.audience` | *(blank)* | Overrides the canonical resource URI used for `aud` validation and `resource_metadata`. If set, must end in `/mcp`. |
| `mcp.oauth.jwks.uri` | *(blank)* | The authorization server's JWKS endpoint (RS256 signature verification). |
| `mcp.oauth.jwks.cache.seconds` | `300` | How long a fetched JWKS is cached before refreshing. |
| `mcp.oauth.required.scopes` | *(blank)* | Comma-separated leaf scopes a token's `scope` claim must fully contain. Blank means no scope is required. |
| `mcp.oauth.permission.claim` | *(blank)* | JWT claim name whose values are already-encoded Fess permissions. |
| `mcp.oauth.scope.permission.map` | *(blank)* | Comma-separated `scope=permission` pairs mapping token scopes to encoded Fess permissions. |
| `mcp.content.max.length` | `10000` | Maximum length of search/get_document result content in characters (a `...` suffix is appended when truncated). |
| `mcp.highlight.fragment.size` | `500` | Size of highlight fragments in characters. |
| `mcp.highlight.num.of.fragments` | `3` | Number of highlight fragments per search result. |
| `mcp.default.page.size` | `3` | Default number of search results when `num` is not supplied. |

## Deviations From the Specification

These are deliberate, reviewed choices, not oversights:

1. **`mcp.auth.mode` defaults to `none`.** The MCP Streamable HTTP transport's Security Considerations say a server SHOULD authenticate every connection. This server does not, by default — kept for backward compatibility, so an existing Fess deployment keeps working unmodified after upgrading this plugin. The endpoint logs a one-time WARN at startup whenever it resolves to unauthenticated behaviour (including an unrecognised `mcp.auth.mode` value, or an `oauth` configuration that turned out not to be usable).
2. **`search`, `suggest`, and `get_document` keep Markdown text blocks, not serialized JSON.** The spec SHOULDs that a tool result's `content` text block, when `structuredContent` is also present, carry the same information serialized as JSON. These three tools keep their pre-existing human-readable Markdown text instead, so clients already parsing today's `content` blocks are not broken by this migration. **`get_index_stats` is the exception, and is already spec-compliant on this point**: its `content[0].text` has always been `JsonXContent.contentBuilder().map(stats).toString()` — the same data `structuredContent` carries (minus nulls), serialized as JSON, not Markdown.
3. **`isError: true` results carry no `structuredContent`.** Verified correct against the MCP schema: both `content` and `structuredContent` are optional on `CallToolResult`, and there is nothing structured to report for a failure.
4. **No automatic scope-hierarchy resolution.** `mcp.oauth.required.scopes` must be written using the authorization server's own leaf scopes — see [OAuth 2.1 Setup](#oauth-21-setup).
5. **`mcp.oauth.audience`, when set, must end in `/mcp`**, or `oauth` mode is treated as unusable and falls back to `none` (logged at ERROR on startup).
6. **`oauth` mode with an unset `mcp.oauth.issuer` falls back to `none`.** RFC 9728 requires a protected-resource metadata document's `authorization_servers` to be non-empty; serving one with none would be worse than not enabling authorization at all, so this server refuses to try rather than serving a broken document.
7. **`get_index_stats` is gated by default** (`mcp.tools.index_stats.permissions=Radmin-api`), so it is unavailable in the default `none` mode. This tool bypasses Fess's normal role-based search filtering and exposes the index name, document count, and JVM heap — administrative information, not a search result. See [Get Index Stats and the permission gate](#get-index-stats-and-the-permission-gate).
8. **The rate limiter is per-principal fairness, not a flood defence.** It has no cross-key cap: `MAX_TRACKED_KEYS` (10,000 distinct keys) is a *sweep trigger* that prompts the limiter to evict stale-window entries once the tracked-key count crosses it, not a hard ceiling — a flood from more distinct keys than that within one window is not throttled by this mechanism at all. For IP-level flood defence, enable Fess's own `rate.limit.*` filter alongside this plugin.
9. **Boolean config keys accept only `true` (case-insensitive), not `1`/`yes`/etc.** `getSystemPropertyAsBoolean` is a case-insensitive `equalsIgnoreCase` check against the string `"true"`; every other value, including `"1"` or `"yes"`, is `false` — see the Configuration table's warning.
10. **Large JWTs and Tomcat's `maxHttpHeaderSize`.** A `Bearer` JWT carrying many claims (especially a large `mcp.oauth.permission.claim` array) can exceed Tomcat's default `maxHttpHeaderSize` (8 KB). `oauth`-mode deployments issuing larger tokens should raise this Tomcat setting.

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
