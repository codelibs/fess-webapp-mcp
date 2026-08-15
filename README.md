MCP WebApp Plugin for Fess
[![Java CI with Maven](https://github.com/codelibs/fess-webapp-mcp/actions/workflows/maven.yml/badge.svg)](https://github.com/codelibs/fess-webapp-mcp/actions/workflows/maven.yml)
==========================

Turn a [Fess](https://fess.codelibs.org/) server into a [Model Context Protocol](https://modelcontextprotocol.io/) (MCP)
server, so that AI agents and LLM applications can search your indexed content, fetch whole documents,
and get query suggestions through one standard interface.

Install the plugin, restart Fess, and a `/mcp` endpoint appears alongside the search UI. Search results
are produced by the same Fess search pipeline the web UI uses, so role-based access control still applies:
an MCP caller sees only what its credential is allowed to see.

> **Before you install: check your MCP client.**
> This plugin implements MCP protocol revision **`2026-07-28`** and nothing else. There is no legacy
> `initialize` handshake and no version negotiation, so a client built against an older revision cannot
> connect at all. See [Client compatibility](#client-compatibility) before deploying.

> **Status: work in progress.** Features, defaults, and documentation may change as the MCP specification
> and the client ecosystem evolve. Contributions and feedback are welcome.

## Table of Contents

**Using the plugin**

- [Requirements](#requirements)
- [Installation](#installation)
- [Client compatibility](#client-compatibility)
- [Quick start](#quick-start)
- [What the server exposes](#what-the-server-exposes)
- [Search query syntax](#search-query-syntax)
- [Reading a search result](#reading-a-search-result)
- [Securing the endpoint](#securing-the-endpoint)
- [Configuration reference](#configuration-reference)
- [Troubleshooting](#troubleshooting)

**Protocol reference**

- [Transport and endpoints](#transport-and-endpoints)
- [Required headers and `params._meta`](#required-headers-and-params_meta)
- [Methods](#methods)
- [Tool reference](#tool-reference)
- [Error model](#error-model)
- [OAuth 2.1 reference](#oauth-21-reference)
- [Deviations from the specification](#deviations-from-the-specification)
- [Migrating from an earlier MCP revision](#migrating-from-an-earlier-mcp-revision)

**Project**

- [Development](#development)
- [Contributing](#contributing)
- [License](#license)

---

# Using the plugin

## Requirements

- Fess 15.8 or later
- Java 21 or later

## Installation

1. Download the plugin JAR from the [Maven repository](https://maven.codelibs.org/release/org/codelibs/fess/fess-webapp-mcp/).
2. Place it in your Fess plugin directory (`WEB-INF/plugin`), or install it from **Administration > Plugin** in the Fess admin UI.
3. Restart Fess.

See the [Plugin Administration Guide](https://fess.codelibs.org/15.8/admin/plugin-guide.html) for details.

After the restart, `POST /mcp` is live. With the shipped defaults it is **unauthenticated** — read
[Securing the endpoint](#securing-the-endpoint) before exposing it beyond a trusted network.

## Client compatibility

MCP revision `2026-07-28` replaced the `initialize` handshake with `server/discover` and made per-request
metadata headers mandatory. A client that speaks an older revision fails on its very first call, with this
server's own diagnostic:

```json
{"jsonrpc":"2.0","id":0,"error":{"code":-32601,
 "message":"initialize was removed in MCP 2026-07-28; this server speaks 2026-07-28",
 "data":{"supportedVersions":["2026-07-28"]}}}
```

At the time of writing:

| Client / SDK | Newest protocol revision | Connects directly |
|---|---|---|
| MCP **Python** SDK 2.0 | `2026-07-28` | Yes — use `ClientSession.discover()`; there is no `initialize` to call |
| MCP **TypeScript** SDK 1.30 | `2025-11-25` | No |
| `mcp-remote` | (TypeScript SDK 1.x) | No |
| Clients built on the TypeScript SDK 1.x | (TypeScript SDK 1.x) | No |

`mcp-remote` — the most common local-to-remote bridge for desktop MCP clients — is built on the TypeScript
SDK 1.x generation and still performs the legacy `initialize` handshake, so it cannot bridge to this endpoint.
The SDK generation that adds `2026-07-28` support ships under new package names rather than as a drop-in
upgrade, so this is not something a version bump of `mcp-remote` fixes today.

Until your client's SDK catches up, you have two options:

- Drive the endpoint directly over HTTP (see [Quick start](#quick-start)) from your own agent code.
- Put a bridge in front of it that speaks the older revision to the client and `2026-07-28` to this endpoint,
  translating `initialize` into [`server/discover`](#serverdiscover) and adding the request-metadata headers
  and `params._meta` this revision requires.

## Quick start

The examples below assume a default install at `http://localhost:8080` with `mcp.auth.mode=none`.

**1. Discover the server.** This replaces `initialize`.

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

**2. Search.** Every request carries an `MCP-Protocol-Version` and `Mcp-Method` header, plus `Mcp-Name` for
`tools/call`; each must match the corresponding value in the body.

```bash
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
      "_meta": {
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientCapabilities": {}
      }
    }
  }'
```

**3. Fetch one of the hits in full**, using the `doc_id` the search returned:

```bash
curl -sS -X POST http://localhost:8080/mcp \
  -H 'Content-Type: application/json' \
  -H 'MCP-Protocol-Version: 2026-07-28' \
  -H 'Mcp-Method: tools/call' \
  -H 'Mcp-Name: get_document' \
  -d '{
    "jsonrpc": "2.0",
    "id": 3,
    "method": "tools/call",
    "params": {
      "name": "get_document",
      "arguments": { "doc_id": "d82177b8ab2749909afbfd6f3a54dc57" },
      "_meta": {
        "io.modelcontextprotocol/protocolVersion": "2026-07-28",
        "io.modelcontextprotocol/clientCapabilities": {}
      }
    }
  }'
```

The same three calls in Python, without an SDK:

```python
import json
import requests

URL = "http://localhost:8080/mcp"
VERSION = "2026-07-28"
META = {
    "io.modelcontextprotocol/protocolVersion": VERSION,
    "io.modelcontextprotocol/clientCapabilities": {},
}


def call_tool(name, arguments, request_id=1):
    body = {
        "jsonrpc": "2.0",
        "id": request_id,
        "method": "tools/call",
        "params": {"name": name, "arguments": arguments, "_meta": META},
    }
    headers = {
        "Content-Type": "application/json",
        "MCP-Protocol-Version": VERSION,
        "Mcp-Method": "tools/call",
        "Mcp-Name": name,
    }
    return requests.post(URL, headers=headers, data=json.dumps(body)).json()


result = call_tool("search", {"q": "machine learning", "num": 5})
print(json.dumps(result, indent=2))
```

## What the server exposes

### Tools

| Tool | What it does | Availability |
|------|--------------|--------------|
| `search` | Full-text search with Lucene-like syntax, filters, sorting, and paging. Each hit carries the `doc_id` the other tools take. | Always |
| `suggest` | Autocomplete candidates for a query prefix, from the Fess suggest engine. | Always |
| `get_document` | Retrieve one document by `doc_id`, including its (bounded) text content. | Always |
| `get_index_stats` | Index name, document count, page-size limit, and JVM heap usage. | **Permission-gated; unavailable with the default settings** — see [The `get_index_stats` gate](#the-get_index_stats-gate) |

Every tool declares both an `inputSchema` and an `outputSchema`, and returns `structuredContent` matching the
latter alongside a human-readable `content` text block. All four are read-only: none of them writes to Fess.

Full argument and result tables are in the [Tool reference](#tool-reference).

### Resources and resource templates

| URI | Contents |
|-----|----------|
| `fess://index/stats` | The same data as `get_index_stats`, as JSON. Gated by the same permission. |
| `fess://document/{doc_id}` | A resource template (RFC 6570). Reading a concrete `fess://document/<id>` performs the same fetch as `get_document`. |

### Prompts and completion

Two prompts are published for clients that offer prompt pickers:

| Prompt | Arguments |
|--------|-----------|
| `basic_search` | `query` (required) |
| `advanced_search` | `query` (required), `sort`, `num` |

`completion/complete` fills in prompt arguments: `query` values come from the Fess suggest engine, and
`advanced_search.sort` is completed from the accepted sort orders.

## Search query syntax

The `search` tool accepts Lucene-like syntax:

| Syntax | Meaning | Example |
|--------|---------|---------|
| `term1 term2` | AND (the default) | `machine learning` |
| `term1 OR term2` | OR | `cat OR dog` |
| `"phrase"` | Phrase match | `"machine learning"` |
| `-term` | Exclude | `python -java` |

Beyond the query string, `search` accepts label and language filters, Fess's advanced search conditions
(`sitesearch`, `filetype`, `timestamp`, and so on), and sorting. See the [`search` tool](#search) reference.

## Reading a search result

A `search` result carries more than the hits, and a client that ignores the rest will misreport what it found.

```json
{
  "hits": [ { "doc_id": "...", "title": "...", "url": "...", "score": 1.234, "content_description": "..." } ],
  "total": 128,
  "total_relation": "EQUAL_TO",
  "has_more": true,
  "collapsed": false,
  "partial": false
}
```

- **`total` is how many documents matched**, and `total_relation` says whether that number is exact
  (`EQUAL_TO`) or a floor the search engine stopped counting at (`GREATER_THAN_OR_EQUAL_TO`).
- **`has_more`** says whether another page exists after this one. Page with `start` (or `offset`).
- **`collapsed` says you may not be able to reach `total`.** Fess's `result.collapsed` setting — `true` in
  the shipped `system.properties` — folds near-duplicate results *after* the count is taken, so with it on,
  fewer than `total` items are obtainable however far you page. Measured on a corpus of 30 near-identical
  documents: `total` is 30 with `total_relation` `EQUAL_TO`, 29 items come back, and `start=29` yields an
  empty array. Fess's own `/api/v2/search` reports the same split. Set `result.collapsed=false` if you need
  the two numbers to agree.
- **`partial` says the search did not complete.** A search that timed out, or that never reached a working
  search engine at all, still returns an ordinary short or empty result with no error. Without checking this
  flag, an agent reports "no documents matched" when the index is simply down.

Two more things worth knowing when consuming results:

- **A `doc_id` is the handle for everything else.** It appears on every hit, and is what `get_document` and
  `fess://document/{doc_id}` take.
- **`get_document` truncates long content, and says so.** `content` is bounded by `mcp.content.max.length`
  (10000 characters by default); `truncated` is `true` when the document was longer and `content_length`
  gives its untruncated length, so a client can report "showing 10000 of 20957 characters". Do not read the
  trailing `...` as the signal — it is indistinguishable from a document that genuinely ends in one. Raise
  `mcp.content.max.length` to return whole documents; there is no way to fetch the remainder in a second call.

## Securing the endpoint

### Authentication modes

`mcp.auth.mode` selects how a caller is identified. Whatever the mode, an authenticated caller's Fess
permissions drive role-based filtering of search results, exactly as they do in the web UI.

| Mode | Credential | Notes |
|------|-----------|-------|
| `none` (default) | none | Every caller is anonymous and gets Fess's configured guest roles. Kept as the default for backward compatibility. |
| `fess_token` | `Authorization: Bearer <fess-access-token>` | Uses Fess's own access tokens, managed under **Administration > Access Token**. |
| `oauth` | `Authorization: Bearer <JWT>` | RFC 6750 bearer JWT verified against an external authorization server, with RFC 9728 protected-resource metadata. See [OAuth 2.1 reference](#oauth-21-reference). |

**The default is unauthenticated.** The MCP transport specification says a server SHOULD authenticate every
connection; this one does not out of the box, so that an existing Fess deployment keeps working after the
plugin is installed. The endpoint logs a WARN whenever it resolves to unauthenticated behaviour. For any
deployment reachable beyond a trusted network, set `mcp.auth.mode` to `fess_token` or `oauth`.

An `oauth` configuration that is missing a required key **falls back to `none` rather than failing hard**,
which means the endpoint silently starts serving anonymous callers. That fallback is logged at ERROR naming
the missing keys. Because Fess re-reads its properties on a live server, this can happen minutes after a
configuration edit, without a restart — see [When the OAuth configuration is not usable](#when-the-oauth-configuration-is-not-usable).

### The `get_index_stats` gate

`get_index_stats` and `fess://index/stats` expose the index name, document count, and JVM heap. That is
administrative information, and reading it bypasses the role-based filtering that applies to search, so it is
gated behind `mcp.tools.index_stats.permissions` (default `Radmin-api`).

Because `mcp.auth.mode=none` never resolves any permission for any caller, **the default configuration
disables this tool and resource for everyone.** They are hidden from `tools/list` and `resources/list`, and a
direct call is refused with the same error a non-existent tool would get, so an unauthorized caller cannot
tell "you may not use this" from "this does not exist".

To make it usable, either:

- switch to `mcp.auth.mode=fess_token` or `oauth` and grant the configured permission to the token or scope; or
- remove the gate entirely with a blank value:

  ```
  mcp.tools.index_stats.permissions=
  ```

If you take the first route, **grant a searchable role alongside it.** A principal's permissions *are* its
role filter, and Fess adds the guest roles only for a principal that has none of its own. A token carrying
only `Radmin-api` therefore unlocks `get_index_stats` and gets **zero hits from `search`**, because no
document carries `Radmin-api` as a role. Grant both (for example `Radmin-api,Rguest`), or set
`role.search.default.permissions`.

### Browser clients: Origin allowlist and CORS

`mcp.allowed.origins` is the complete allowlist of browser origins. It is **blank by default, which rejects
every request that carries an `Origin` header** with HTTP 403 — including one from the Fess host itself. A
browser-based MCP client must be named explicitly:

```
mcp.allowed.origins=https://app.example.com
```

Requests with no `Origin` at all — CLI tools, agent runtimes, `curl` — are unaffected. The server's own
origin is deliberately not implied: it could only be derived from the caller-supplied `Host` header, which is
exactly what a DNS-rebinding attacker controls.

Each entry must be a full `scheme://host[:port]`. An entry without a scheme (`fess.example.com`) or a
wildcard (`*`) does not parse as an origin, is dropped, and matches nothing — so `*` behaves like a blank
value and rejects everything.

**Listing an origin is necessary but not sufficient for a cross-origin browser client.** None of
`MCP-Protocol-Version`, `Mcp-Method`, or `Mcp-Name` is CORS-safelisted, so the browser preflights; Fess's
`DefaultCorsHandler` returns `api.cors.allow.headers` verbatim rather than echoing the requested headers, so
the browser blocks the real request before this plugin ever sees it. Extend the Fess setting too:

```
api.cors.allow.headers=Origin, Content-Type, Accept, Authorization, X-Requested-With, X-Fess-CSRF-Token, MCP-Protocol-Version, Mcp-Method, Mcp-Name
```

Same-origin browser clients do not preflight and are unaffected.

### Rate limiting

`mcp.rate.limit.per.minute` (default `60`) caps how many `tools/call`, `completion/complete`, and
`resources/read` calls one caller may make per minute — every method that reaches a Fess backend. Exceeding
it returns HTTP 429 with a `Retry-After` header. Set it to `0` to disable.

The limit is keyed on the authenticated subject, or on the client IP when there is none. **Behind a reverse
proxy, set Fess's `rate.limit.trusted.proxies`**: without it, `X-Forwarded-For` is ignored and every caller
behind the proxy shares one bucket. This limiter is per-caller fairness, not a flood defence — see
[deviation 8](#deviations-from-the-specification).

### Request size

`mcp.request.max.bytes` (default `1048576`) bounds the request body; a larger one gets HTTP 413 without ever
being buffered or decoded. There is no "unlimited" sentinel — `0` or a negative value rejects *every* body,
and `2147483647` removes the bound altogether. Leave it at a real byte count.

## Configuration reference

Every key below is a **Fess system property**. Put it in `WEB-INF/conf/system.properties`, or pass it as
`-Dfess.system.<key>` — note the `fess.system.` prefix, not `fess.`.

> `fess_config.properties` and `-Dfess.config.*` are a different channel and have **no effect** on any key
> below. A key placed there is silently ignored, with no error and no log line.

> **Boolean keys accept only `true`, matched case-insensitively.** Any other value — including `1`, `yes`, or
> an empty string — is `false`. So `mcp.enabled=1` silently *disables* the endpoint.

> Values are read live: an edit takes effect within a few seconds, without a restart.

| Property | Default | Description |
|----------|---------|-------------|
| `mcp.enabled` | `true` | Enables the `/mcp` endpoint. When `false`, every request gets HTTP 503. |
| `mcp.auth.mode` | `none` | `none`, `fess_token`, or `oauth`. An unusable `oauth` configuration falls back to `none`. |
| `mcp.allowed.origins` | *(blank)* | Comma-separated allowlist of `Origin` values, as full `scheme://host[:port]`. Blank rejects every present `Origin`; an absent `Origin` is always allowed. A host containing an underscore cannot be expressed here, because `java.net.URI` does not accept one. |
| `mcp.request.max.bytes` | `1048576` | Maximum request body size in bytes; larger bodies get HTTP 413. `0` or negative rejects every body; `2147483647` removes the bound. `Content-Length` is deliberately ignored — it is caller-supplied and absent for a chunked body. |
| `mcp.rate.limit.per.minute` | `60` | Per-caller limit on `tools/call`, `completion/complete`, and `resources/read`, per fixed one-minute window. `0` or negative disables it. |
| `mcp.tools.index_stats.permissions` | `Radmin-api` | Comma-separated encoded Fess permissions required for `get_index_stats` / `fess://index/stats`. Blank disables the gate. |
| `mcp.content.max.length` | `10000` | Maximum characters of document content returned by `search` and `get_document`; a `...` suffix is appended when truncated. |
| `mcp.highlight.fragment.size` | `500` | Highlight fragment size, in characters. |
| `mcp.highlight.num.of.fragments` | `3` | Highlight fragments per search result. |
| `mcp.default.page.size` | `3` | Default `num` for `search` when the caller does not supply one. |
| `mcp.cache.discover.ttl.ms` | `3600000` | `ttlMs` reported by `server/discover`. |
| `mcp.cache.list.ttl.ms` | `3600000` | `ttlMs` reported by `tools/list`, `resources/list`, `resources/templates/list`, and `prompts/list`. |
| `mcp.cache.read.ttl.ms` | `0` | `ttlMs` reported by `resources/read` (not cached by default). |
| `mcp.oauth.issuer` | *(blank)* | The authorization server's issuer URL. Required for `oauth` mode. |
| `mcp.oauth.audience` | *(blank)* | The canonical resource URI checked against a token's `aud` claim, and served as `resource` in the protected-resource metadata. Required for `oauth` mode, never derived from the request, and **must end in `/mcp`**. |
| `mcp.oauth.jwks.uri` | *(blank)* | The authorization server's JWKS endpoint (RS256 only). Required for `oauth` mode. |
| `mcp.oauth.jwks.cache.seconds` | `300` | How long a fetched JWKS is cached. **Values below 60 are raised to 60**, logged once at WARN. |
| `mcp.oauth.required.scopes` | *(blank)* | Comma-separated leaf scopes a token must carry in full, read from `scope` or, failing that, `scp`. Blank requires none. |
| `mcp.oauth.permission.claim` | *(blank)* | JWT claim name whose values are already-encoded Fess permissions. |
| `mcp.oauth.scope.permission.map` | *(blank)* | Comma-separated `scope=permission` pairs mapping token scopes to encoded Fess permissions. |

## Troubleshooting

**Every request fails with HTTP 400 and code `-32020`.** A required metadata header is missing, duplicated, or
disagrees with the body. All of `MCP-Protocol-Version` and `Mcp-Method` are required on every non-notification
request, plus `Mcp-Name` on `tools/call`, `prompts/get`, and `resources/read`. See
[Required headers and `params._meta`](#required-headers-and-params_meta).

**The first call fails with `-32601` mentioning `initialize`.** Your client speaks an older MCP revision. See
[Client compatibility](#client-compatibility).

**Every request gets HTTP 403.** The request carries an `Origin` header that is not in `mcp.allowed.origins`,
which is blank by default. See [Browser clients](#browser-clients-origin-allowlist-and-cors).

**Every request gets HTTP 503.** `mcp.enabled` is not `true` — check for a value like `1` or `yes`, which
count as `false`.

**`tools/list` does not include `get_index_stats`.** Expected with the default settings. See
[The `get_index_stats` gate](#the-get_index_stats-gate).

**`search` returns zero hits for a token that works elsewhere.** The token's permissions are also its role
filter. A credential carrying only `Radmin-api` matches no documents; grant a searchable role too.

**`search` returns fewer items than `total`.** Result collapsing. Check the `collapsed` flag, and see
[Reading a search result](#reading-a-search-result).

**`search` returns zero hits and the index is fine.** Check `partial`: a value of `true` means the search did
not complete, not that nothing matched.

**A tool failed with `Tool execution failed (error_code:<uuid>)`.** That correlation id is deliberate — the
real message is only in the Fess log. Grep the log for the uuid; the entry is at WARN. See
[Tool errors](#tool-errors).

**OAuth mode stopped requiring credentials.** The configuration became unusable and fell back to `none`. Look
for the ERROR log line naming the missing keys, and see
[When the OAuth configuration is not usable](#when-the-oauth-configuration-is-not-usable).

---

# Protocol reference

## Transport and endpoints

```
POST /mcp
```

Streamable HTTP, one JSON request and one JSON response per call. There is no batching and no SSE-only
stateful session. `GET` and `DELETE` (legacy Streamable HTTP session semantics) are not implemented and are
rejected with HTTP 405.

A **notification** — a JSON-RPC request with no `id` — is accepted with HTTP 202 and an empty body. Notifications
do not require the metadata headers or `params._meta`.

When `mcp.auth.mode=oauth` is usable, the plugin additionally serves an RFC 9728 Protected Resource Metadata
document at `/.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp`. Both
return HTTP 404 in any other mode.

`Content-Type` and `Accept` are not validated by this server; send `application/json` and
`application/json, text/event-stream` respectively as a matter of good practice.

## Required headers and `params._meta`

Every request carrying a JSON-RPC `id` must include these headers:

| Header | Required for | Must equal |
|--------|--------------|------------|
| `MCP-Protocol-Version` | every method | `params._meta["io.modelcontextprotocol/protocolVersion"]` |
| `Mcp-Method` | every method | the JSON-RPC `method` field |
| `Mcp-Name` | `tools/call`, `prompts/get` | `params.name` |
| `Mcp-Name` | `resources/read` | `params.uri` |

and a `params._meta` object:

| `_meta` key | Required | Meaning |
|-------------|----------|---------|
| `io.modelcontextprotocol/protocolVersion` | Yes | Must be `2026-07-28`; any other value is `-32022` (`UnsupportedProtocolVersion`) |
| `io.modelcontextprotocol/clientCapabilities` | Yes | An object; may be `{}` |
| `io.modelcontextprotocol/clientInfo` | No | Client identity; this server does not echo it anywhere today |

Validation order is: header *presence*, then header-versus-body agreement, then protocol version. A request
with no headers at all and an unsupported body version therefore gets `-32020`, not `-32022`.

A required header sent **more than once** is also rejected with `-32020`. The specification does not ask for
this — it names missing, mismatched, and malformed as the failure conditions — but with repeats allowed, which
value the server compares against the body is a servlet-container detail, and a proxy that appends rather than
replaces could turn a mismatch into a match.

A **non-ASCII `Mcp-Name`** cannot be carried in an HTTP header field directly. Encode it as
`=?base64?<base64-of-utf8-bytes>?=` and this server decodes it before comparing against the body. A value that
carries the sentinel's `=?base64?` prefix and `?=` suffix but cannot be decoded as one — including the
ten-character `=?base64?=`, where the delimiters overlap — is treated as malformed rather than as a plain
value, and rejected with `-32020`.

## Methods

Nine methods are routed. Every other method name, including the retired `initialize` and `ping`, is `-32601`.

`resultType` and `_meta["io.modelcontextprotocol/serverInfo"]` are stamped onto **every** successful result by
the response writer; they are shown once below and omitted from the rest for brevity. `serverInfo.version`
comes from the plugin JAR's `Implementation-Version` manifest entry, so it reports the plugin version
(for example `15.8.0`), falling back to `"unknown"` only when loaded from somewhere without that manifest
entry, such as an exploded build directory.

Results marked as cacheable carry `ttlMs` and `cacheScope`. `cacheScope` is `"public"` only when the result
cannot vary by caller.

### `server/discover`

Replaces `initialize`. There is no version negotiation, because this server speaks exactly one revision.
Always `cacheScope: "public"`: the result is identity-independent, and must never mention a permission-gated
primitive, so one cached copy is correct for every caller.

Identity-independent is not the same as unauthenticated. `server/discover` is dispatched after
authentication like any other method, so under `fess_token` or `oauth` an unauthenticated call gets the same
401 with a `WWW-Authenticate` challenge as anything else — which is how a client discovers where to
authenticate.

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "supportedVersions": ["2026-07-28"],
    "capabilities": { "tools": {}, "resources": {}, "prompts": {}, "completions": {} },
    "instructions": "Fess Enterprise Search Server. Use the 'search' tool to perform full-text search with Lucene-like query syntax (AND default, OR explicit, quotes for phrase, - for exclusion). Use 'suggest' for query autocomplete.",
    "ttlMs": 3600000,
    "cacheScope": "public",
    "resultType": "complete",
    "_meta": {
      "io.modelcontextprotocol/serverInfo": { "name": "fess-mcp-server", "version": "15.8.0" }
    }
  }
}
```

TTL: `mcp.cache.discover.ttl.ms`.

### `tools/list`

Lists the tools available to the caller. A tool gated by a permission the caller does not hold is silently
omitted. No tool declares `idempotentHint`.

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "tools": [
      {
        "name": "search",
        "description": "Search documents via Fess. Query syntax is similar to Lucene: multiple terms are combined with AND by default, use OR explicitly for OR search (e.g., \"term1 OR term2\"), use quotes for phrase search, use - for exclusion.",
        "inputSchema": { "type": "object", "properties": { "q": { "type": "string", "description": "query string" }, "...": {} }, "required": ["q"] },
        "outputSchema": { "type": "object", "properties": { "hits": {}, "total": {}, "total_relation": {}, "has_more": {}, "collapsed": {}, "partial": {} }, "required": ["hits", "total", "has_more", "collapsed", "partial"], "additionalProperties": false },
        "annotations": { "title": "Search Documents", "readOnlyHint": true, "destructiveHint": false, "openWorldHint": false }
      }
    ],
    "ttlMs": 3600000,
    "cacheScope": "private"
  }
}
```

The full schemas are in the [Tool reference](#tool-reference). `cacheScope` is `"public"` only while
`mcp.auth.mode=none`; under any other mode it drops to `"private"`, because which tools appear then depends on
the caller. TTL: `mcp.cache.list.ttl.ms`, shared with the other list methods.

### `tools/call`

Executes a tool. Requires `Mcp-Name` to equal `params.name`. Not a cacheable result: it never carries `ttlMs`
or `cacheScope`.

`params.arguments` is optional, per the normative schema (`CallToolRequestParams.arguments?`); an absent or
non-object value is treated as `{}`. Required-argument checks are unaffected — `tools/call {"name": "search"}`
still fails with `-32602 Missing required parameter: q`.

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "**Title**: Introduction to Elasticsearch\n**URL**: https://example.com/elasticsearch-intro\n**Doc ID**: d82177b8ab2749909afbfd6f3a54dc57\n**Score**: 1.234\n\nElasticsearch is a distributed, RESTful search and analytics engine..."
      }
    ],
    "structuredContent": {
      "hits": [
        {
          "doc_id": "d82177b8ab2749909afbfd6f3a54dc57",
          "title": "Introduction to Elasticsearch",
          "url": "https://example.com/elasticsearch-intro",
          "score": 1.234,
          "content_description": "Elasticsearch is a distributed, RESTful search and analytics engine..."
        }
      ],
      "total": 128,
      "total_relation": "EQUAL_TO",
      "has_more": true,
      "collapsed": false,
      "partial": false
    }
  }
}
```

A tool that fails in an expected way returns an ordinary result with `isError: true`:

```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "result": { "content": [{ "type": "text", "text": "Document not found: abc123" }], "isError": true }
}
```

Calling an unknown tool — or one the caller is not authorized for — returns the identical error either way, at
HTTP 200:

```json
{ "jsonrpc": "2.0", "id": 6, "error": { "code": -32602, "message": "Unknown tool: get_index_stats" } }
```

**How an absent field is reported differs by tool.** `search` is the strict one: a `doc_id`, `title`, `url`,
`score`, or `content_description` genuinely absent from the underlying document is omitted from the `hits[]`
entry rather than filled in with `null` or `""` — which is why that schema's `items.required` is empty.
`content_description` carries the same digest the text block shows: highlight markup stripped, falling back to
truncated raw content when Fess's highlighter produced no fragment (a phrase query does this routinely), and
omitted entirely when there was neither. `get_document` does the opposite, and marks all four fields required:
it resolves `title`, `url`, and `content` with a `""` fallback, so an absent field arrives as an empty string.
(A field present but mapped to a null value becomes the literal string `"null"` there, since the fallback is
applied before the value is stringified.) `get_index_stats` strips nulls from its stats map before serializing.

### `resources/list`

Lists readable resources. `fess://index/stats` is omitted for a caller not authorized for `get_index_stats`;
an unauthorized or default-configuration caller gets `"resources": []`.

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

`cacheScope` follows the same `none` → `"public"`, otherwise `"private"` rule as `tools/list`.

### `resources/read`

Reads one resource. Requires `Mcp-Name` to equal `params.uri`. Rate-limited like `tools/call`, because both
accepted URI shapes reach a Fess backend: `fess://document/<id>` performs the same document fetch as
`get_document`, and `fess://index/stats` runs a live cluster and JVM stats collection.

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

A `uri` matching neither shape, and `fess://index/stats` read by an unauthorized caller, both get the same
`-32602 Resource not found: <uri>` at HTTP 200. TTL: `mcp.cache.read.ttl.ms`, `0` by default.

### `resources/templates/list`

Lists RFC 6570 URI templates. Always `cacheScope: "public"`, with no auth-mode-dependent rule.

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

### `prompts/list`

Always `cacheScope: "public"`.

```json
{
  "jsonrpc": "2.0",
  "id": 10,
  "result": {
    "prompts": [
      {
        "name": "basic_search",
        "description": "Perform a basic search with a query string",
        "arguments": [{ "name": "query", "description": "The search query", "required": true }]
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

### `prompts/get`

Substitutes prompt arguments. Requires `Mcp-Name` to equal `params.name`. Not a cacheable result.

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

### `completion/complete`

Argument autocomplete. Not a cacheable result. Rate-limited like `tools/call`. The `values` array is capped at
100 entries.

The completion source depends on `ref.type` and the argument name:

- `ref.type == "ref/prompt"`:
  - `basic_search.query`, `advanced_search.query` — candidates from the Fess suggest engine.
  - `advanced_search.sort` — prefix-filtered from a static enum: `score.desc`, `score.asc`,
    `last_modified.desc`, `last_modified.asc`, `create_timestamp.desc`, `create_timestamp.asc`.
  - `advanced_search.num` — no completions.
- `ref.type == "ref/resource"` — no completions; there is no source for `doc_id` completion.
- Any other `ref.type` — no completions.

```json
{
  "jsonrpc": "2.0",
  "id": 12,
  "result": {
    "completion": { "values": ["machine learning", "machine translation"], "total": 2, "hasMore": false }
  }
}
```

## Tool reference

### `search`

**Arguments**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `q` | string | Yes | Query string |
| `start` | integer | No | Start position for paging (default `0`) |
| `offset` | integer | No | Alias for `start`, used only when `start` is absent. When both are sent, **`start` wins** — including when `start` is itself unparseable or negative, so the alias never silently repairs a broken `start` and pages from somewhere the caller did not ask for. |
| `num` | integer | No | Results per page. Default `mcp.default.page.size` (3). A value above `paging.search.page.max.size` is clamped to that maximum; **zero or negative falls back to the default**, not the maximum. |
| `sort` | string | No | `<field>.asc` / `<field>.desc`. The advertised `inputSchema` description lists the fields this deployment accepts, so a client need not guess: Fess ships `score`, `filename`, `created`, `content_length`, `last_modified`, `timestamp`, `click_count`, `favorite_count`, and `query.additional.sort.fields` extends the list. An unaccepted field is rejected with `-32602` naming it. |
| `fields` | object | No | Field filters keyed by field name, e.g. `{"label": ["label1"]}` |
| `lang` | string | No | Language filter |
| `as` | object | No | Advanced search conditions keyed by condition name (`q`, `epq`, `oq`, `nq`, `filetype`, `sitesearch`, `timestamp`, `occt`), each an array of strings. Combined with `q`, never instead of it — see the note below. |
| `ex_q` | array of string | No | Extra queries |
| `sdh` | string | No | Similar document hash |

**Result** (`structuredContent`)

| Field | Type | Always present | Description |
|-------|------|----------------|-------------|
| `hits` | array | Yes | Each entry may carry `doc_id`, `title`, `url`, `score`, `content_description`; none is guaranteed |
| `total` | integer | Yes | Number of matching documents |
| `total_relation` | string | No | `EQUAL_TO` when `total` is exact, `GREATER_THAN_OR_EQUAL_TO` when the engine stopped counting. Omitted rather than fabricated when Fess did not supply one. |
| `has_more` | boolean | Yes | Whether a page exists after this one |
| `collapsed` | boolean | Yes | Whether near-duplicate results were folded, so fewer than `total` items are obtainable |
| `partial` | boolean | Yes | Whether the search did not complete, so these results are not the whole answer |

> **`as` narrows `q`, it does not replace it.** Fess builds the query from the advanced conditions *instead
> of* the plain query string as soon as one of them is query-bearing, so `q` is folded into `as.q` before the
> search runs. `q=zebrafish` with `as={"filetype":["html"]}` returns HTML documents matching *zebrafish*.
> (Before 15.8 it returned every HTML document, discarding `q` with no error.) If you send `as.q` as well,
> both are kept.

### `suggest`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `q` | string | Yes | Query prefix to autocomplete |
| `num` | integer | No | Number of suggestions (default 10), capped by `paging.search.page.max.size` |

Returns `{"suggestions": [{"text": "..."}]}`.

### `get_document`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `doc_id` | string | Yes | Document ID, as carried by every `search` hit |

Returns `doc_id`, `title`, `url`, `content`, `truncated`, and `content_length` — all required. See
[Reading a search result](#reading-a-search-result) for what `truncated` and `content_length` mean.

### `get_index_stats`

Takes no arguments. Returns `index` (`index_name`, `document_count`, and `error` when stats collection
failed), `config` (`max_page_size`), and `system` (`memory` with `total_bytes`, `free_bytes`, `used_bytes`,
`max_bytes`). Gated — see [The `get_index_stats` gate](#the-get_index_stats-gate).

### Argument type checking

An argument's **top-level** JSON type is enforced: `q`, `sort`, and `sdh` must be strings, `fields` and `as`
objects, `ex_q` an array. A mismatch is `-32602` naming the argument and the expected type, for example
`Invalid type for parameter: q (expected a string)`. The same holds for `q` on `suggest` and `doc_id` on
`get_document`.

`start`, `offset`, `num`, and `lang` are deliberate exceptions: their accessors accept a numeric string (or,
for `lang`, any value) by design, so rejecting one would be a behaviour change rather than a fix.

Inside `fields`, `as`, and `ex_q`, each value must be an array, and `fields`/`ex_q` elements must be strings;
a mismatch is `-32602` naming the path, for example `Invalid type for parameter: as.q (expected an array)`.

Formats, ranges, and enums are **not** validated — see [deviation 12](#deviations-from-the-specification).

## Error model

Errors are standard JSON-RPC 2.0:

```json
{ "jsonrpc": "2.0", "id": 1, "error": { "code": -32601, "message": "Unknown method: invalid_method" } }
```

The HTTP status for a given JSON-RPC code depends on *where* the error is raised, not on the code alone. For
example `-32602` is HTTP 400 for a malformed `params._meta`, but HTTP 200 for an unknown or unauthorized tool,
a wrong-typed tool argument, or an inbound `cursor`.

### Error codes

| Code | Name | Description |
|------|------|-------------|
| -32700 | Parse error | Invalid JSON. Also covers a JSON array body: the array shape is rejected before Request-object validation, so a batch request never reaches it. |
| -32600 | Invalid Request | Not a valid Request object (for example an explicit `"id": null`) |
| -32601 | Method not found | Unknown method, including the retired `initialize` and `ping` |
| -32602 | Invalid params | Invalid parameters; also an unknown, gated, or otherwise unusable tool/prompt/resource, a tool argument whose JSON type disagrees with `inputSchema`, and a non-null inbound `cursor` |
| -32603 | Internal error | Internal JSON-RPC error; also a tool reporting a server-side failure, whose message is always the fixed `Tool execution failed (error_code:<uuid>)` |
| -32000 | RateLimited | `mcp.rate.limit.per.minute` exceeded. Always paired with HTTP 429, a `Retry-After` header, and `data.retryAfterSeconds`. This number sits in the range the MCP schema marks implementation-defined (`-32000`–`-32019`), which receivers are told not to give cross-implementation meaning — **key off the HTTP status and `Retry-After`, not the code.** |
| -32020 | HeaderMismatch | A required metadata header is missing, duplicated, or disagrees with the body. A missing or incomplete `params._meta` is `-32602` instead: the specification scopes this code to the header layer. |
| -32021 | MissingRequiredClientCapability | Defined by this server but **never emitted** — nothing here currently requires an optional client capability. |
| -32022 | UnsupportedProtocolVersion | The declared protocol version is not `2026-07-28`; `error.data` carries both `supported` and `requested`. |

`-32002` (`Resource not found`, used by earlier revisions) no longer exists as a distinct code: a not-found
resource, prompt, or tool is `-32602` in `2026-07-28`.

### Tool errors

An **unexpected** failure inside a tool never reaches the caller as text. It arrives as an `isError: true`
result at HTTP 200 whose single content block is exactly:

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

A tool reporting a server-side failure through the error channel gets the same treatment: `-32603` with the
message `Tool execution failed (error_code:<uuid>)`. Either way the real exception and stack trace go to the
Fess log at **WARN** under that uuid, and **that log line is the only way to diagnose the call.**

This is not defensiveness for its own sake. Fess's `InvalidQueryException` carries the fully serialized
OpenSearch query DSL, *including* the role and permission filter terms already merged into it, and a caller
can provoke it with nothing but an out-of-range `start`. A fresh uuid per failure is what makes a user report
("I got `error_code:X`") pinpoint one log line.

**A rejected query is not an unexpected failure.** Input the *caller* wrote — an unparseable query string, a
sort field that does not exist, a `start` past the ceiling — is answered as `-32602` with a message you can
act on:

```json
{ "jsonrpc": "2.0", "id": 3,
  "error": { "code": -32602, "message": "The specified sort nope.asc is unsupported." } }
```

The text comes from Fess's own end-user message bundle — the same strings the search UI shows — never from
`getMessage()`, so the DSL-bearing case above resolves to the deliberately uninformative
`Could not process the specified query.` These are logged at **DEBUG**, not WARN: the stack trace is not
evidence of a server fault, and logging one per request would let any caller drive an unbounded volume of it
by sending `q=foo AND`.

**Caller-directed errors are not redacted.** `-32700`, `-32600`, `-32601`, and `-32602` pass through verbatim,
because those messages are written by this plugin and name nothing but the offending argument:
`Unknown tool: get_index_stats`, `Missing required parameter: doc_id`,
`Invalid type for parameter: q (expected a string)`. The split is fail-closed: any other code, including one
added later, is redacted until someone decides otherwise. A normal `isError: true` result a tool builds itself
— `Document not found: abc123` — is not a failure at all and is unaffected.

## OAuth 2.1 reference

`mcp.auth.mode=oauth` turns on RFC 6750 bearer-JWT verification against an external authorization server, per
RFC 9728 (OAuth 2.0 Protected Resource Metadata) and the MCP Authorization specification. This mode is a pure
resource-server token *verifier*: it never issues tokens.

**Required settings**

1. **`mcp.oauth.issuer`** — the authorization server's issuer URL.
2. **`mcp.oauth.audience`** — the canonical resource URI checked against a token's `aud` claim (RFC 8707), and
   served as the `resource` field of the metadata document. **It must end in `/mcp`.** Example:
   `https://fess.example.com/mcp`. This server never derives it from the request: deriving a
   security-critical resource identifier from a caller-controlled `Host` header would let an attacker holding
   a token legitimately minted by the *same* issuer for a *different* resource simply send that resource's
   hostname and be admitted — the confused-deputy case RFC 8707 audience binding exists to prevent.
3. **`mcp.oauth.jwks.uri`** — the authorization server's JWKS endpoint. Signatures are verified **RS256 only**;
   the fetched key set is cached for `mcp.oauth.jwks.cache.seconds` (default 300, values below 60 raised to 60).

**Optional settings**

4. **`mcp.oauth.required.scopes`** — scopes a token must carry in full. Read from the token's `scope` claim
   (RFC 9068) or, when absent, its `scp` claim — **Microsoft Entra ID and Okta use `scp` and never emit
   `scope`** — accepting either a space-delimited string (Entra ID) or a JSON array (Okta). Write these using
   the authorization server's own **leaf** scopes: this server performs a plain subset check with no
   hierarchy resolution, so if your authorization server treats `fess:admin` as implying `fess:search`, list
   `fess:search` explicitly too.
5. **Permission mapping** — how a verified token's claims become Fess's encoded permission strings (for
   example `Radmin-api`, `Rguest`), which drive both search-result role filtering and the `get_index_stats`
   gate. Two independent, additive sources:
   - **`mcp.oauth.permission.claim`** — a JWT claim name (a JSON array, or a space-delimited string) whose
     values are already Fess-encoded permissions.
   - **`mcp.oauth.scope.permission.map`** — comma-separated `scope=permission` pairs, keyed on the scopes
     resolved above, e.g. `fess:search=Rguest,fess:search=1guest,fess:admin=Radmin-api`. A scope may appear in
     more than one pair; every mapped permission accumulates.

   If neither source contributes anything, the caller falls back to Fess's configured guest roles, exactly as
   an anonymous `none`-mode caller would.

### Accepted token `typ`

Access tokens are accepted with a `typ` header of `at+jwt` or `application/at+jwt` (RFC 9068 §4), with
`typ: JWT`, or with no `typ` at all — matched case-insensitively. Any other `typ` is rejected. `typ: JWT` is
what Entra ID, Auth0, Okta, and a default-configured Keycloak actually emit for access tokens, so accepting
only `at+jwt` is not an option.

Widening this does not weaken anything: an OIDC ID Token carries `typ: JWT` or no `typ` at all, both already
accepted. What keeps an ID Token out is the issuer and audience checks — an ID Token's `aud` is the client's
`client_id`, never this server's canonical resource URI, so it fails RFC 8707 audience binding whatever its
`typ` says.

### Protected Resource Metadata

When `oauth` mode is usable, the plugin serves an RFC 9728 document at both
`/.well-known/oauth-protected-resource` and `/.well-known/oauth-protected-resource/mcp`:

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

`scopes_supported` is `mcp.oauth.required.scopes`, parsed the same way it is enforced, minus `offline_access`
(this server never issues refresh tokens, so it never advertises that scope even if one is listed by mistake).

A rejected request carries the metadata URL in its `WWW-Authenticate` challenge:

```
WWW-Authenticate: Bearer realm="fess-mcp", error="insufficient_scope", error_description="The token is missing a required scope.", scope="fess:search", resource_metadata="https://fess.example.com/.well-known/oauth-protected-resource/mcp"
```

### Deployments under a context path

`mcp.oauth.audience` must *end in* `/mcp`, but it may carry leading path segments. If Fess runs under a
context path (`FESS_CONTEXT_PATH` / `-Dfess.context.path`) or behind a reverse proxy mounting it at a subpath,
the correct audience is `https://fess.example.com/api/mcp`, and the metadata document is served at
`https://fess.example.com/api/.well-known/oauth-protected-resource/mcp`.

That URL is built under the application's own prefix rather than the host-rooted form RFC 9728 §3.1 specifies
(`https://fess.example.com/.well-known/oauth-protected-resource/api/mcp`). The deviation is forced: the §3.1
URL lies outside the servlet context and Fess cannot serve it under a context path at all. The two forms are
identical at the root. Clients that follow the `resource_metadata` URL advertised in the `WWW-Authenticate`
challenge — the discovery flow both RFC 9728 and the MCP Authorization specification prescribe — are
unaffected; a client that constructs the §3.1 URL itself gets a 404 under a subpath deployment.

### When the OAuth configuration is not usable

`mcp.auth.mode=oauth` with an unset `mcp.oauth.issuer`, `mcp.oauth.audience`, or `mcp.oauth.jwks.uri` — or an
audience that does not end in `/mcp` — is **not** a hard failure. The endpoint falls back to `none`-mode
behaviour and serves `/mcp` anonymously. That fallback is reported at ERROR naming all three keys, and the
accompanying `mcp.auth.mode=none` WARN says every caller is now anonymous.

"Anonymously" describes how roles are resolved, and it is not the whole story for a client that keeps sending
the credential it was configured with. In `none` mode this plugin does not own role resolution, so Fess's
`RoleQueryHelper` runs and hands the request to `AccessTokenService`, which reads the raw `Authorization`
header and interprets it as a **Fess access token**. An OAuth bearer JWT matches no Fess token and is rejected
(`Invalid token: ...`), and an `Authorization: Basic ...` header is rejected earlier still
(`Invalid format: ...`). So `search` and `get_document` fail with `isError: true` for precisely the clients
that were working before the fallback; a client must drop its `Authorization` header to actually be served
anonymously. This is long-standing Fess behaviour rather than something this revision introduced, but it is
what the fallback means in practice.

**This is not a startup-only check.** Every request re-reads `mcp.auth.mode` and the `mcp.oauth.*` keys, and
Fess re-reads its properties file within about five seconds of an edit, so `/mcp` can flip between "credential
required" and "anonymous for everyone" on a live server with no restart. A transition is logged when it
happens — once per transition, not once per request, because this is an unauthenticated endpoint and a
per-request WARN would be a disk-filling amplifier a caller controls. A fix taking effect is logged at INFO.

"OAuth was configured but rejected" is tracked as a state distinct from "the operator chose `none`", so an
operator who meant to enable OAuth and got the configuration wrong is told so, rather than seeing the message
a deliberately unauthenticated deployment sees.

### Large tokens and `maxHttpHeaderSize`

A bearer JWT carrying many claims — especially a large `mcp.oauth.permission.claim` array — can exceed
Tomcat's default `maxHttpHeaderSize` of 8 KB. Deployments issuing larger tokens should raise that setting.

## Deviations from the specification

These are deliberate, reviewed choices, not oversights:

1. **`mcp.auth.mode` defaults to `none`.** The Streamable HTTP transport's Security Considerations say a
   server SHOULD authenticate every connection. This one does not by default, so that an existing Fess
   deployment keeps working unmodified after the plugin is installed. The endpoint logs a WARN whenever it
   resolves to unauthenticated behaviour — including an unrecognised `mcp.auth.mode` value or an unusable
   `oauth` configuration — at startup and on any later transition.
2. **`search`, `suggest`, and `get_document` keep Markdown text blocks, not serialized JSON.** The
   specification SHOULDs that a tool result's `content` text block, when `structuredContent` is also present,
   carry the same information serialized as JSON. These three keep their pre-existing human-readable Markdown
   instead, so clients already parsing today's `content` blocks are not broken. **`get_index_stats` is the
   exception and is already compliant on this point**: its `content[0].text` has always been the same data
   `structuredContent` carries (minus nulls), serialized as JSON.
3. **`isError: true` results carry no `structuredContent`.** Verified against the MCP schema:
   `structuredContent` is optional on `CallToolResult`, and there is nothing structured to report for a
   failure. `content` is *not* optional, so an error result still carries its text block, and every tool here
   provides one.
4. **No automatic scope-hierarchy resolution.** `mcp.oauth.required.scopes` must be written using the
   authorization server's own leaf scopes.
5. **`mcp.oauth.audience` is required and must end in `/mcp`.** It is never derived from the request; an unset
   or wrongly-shaped audience makes `oauth` mode unusable and falls back to `none`. Leading path segments
   *are* supported, so `https://fess.example.com/api/mcp` is correct for a context-path deployment.
6. **`oauth` mode with an unset `mcp.oauth.issuer` or `mcp.oauth.jwks.uri` falls back to `none`.** RFC 9728
   requires a metadata document's `authorization_servers` to be non-empty, and serving one with none would be
   worse than not enabling authorization at all. An unset JWKS endpoint would otherwise let `oauth` mode be
   selected but fail every request with a misleading "invalid token" 401, since the missing endpoint only
   surfaces once a token-bearing request reaches JWKS resolution.
7. **`get_index_stats` is gated by default**, so it is unavailable in the default `none` mode. It bypasses
   Fess's role-based search filtering and exposes the index name, document count, and JVM heap —
   administrative information, not a search result.
8. **The rate limiter is per-principal fairness, not a flood defence.** It has no cross-key cap: the
   10,000-key `MAX_TRACKED_KEYS` figure is a *sweep trigger* that prompts eviction of stale-window entries,
   not a hard ceiling — a flood from more distinct keys than that within one window is not throttled by this
   mechanism at all. For IP-level flood defence, enable Fess's own `rate.limit.*` filter alongside it.

   The window is fixed rather than rolling, which is the same deliberate simplification: a caller can burst up
   to 2× `mcp.rate.limit.per.minute` across a window boundary, and a refused caller is always told to wait the
   full 60 seconds because the limiter tracks whole minutes and cannot report a shorter remainder.

   The unauthenticated key is the client IP as Fess's `RateLimitHelper` resolves it, which is an honest trade
   rather than a clean win. Using `getRemoteAddr()` directly is not viable: behind nginx or Apache that is the
   proxy's address for *every* caller, and Fess ships no `RemoteIpValve`, so that collapse is the default
   deployment — one caller spending the budget would 429 every other client of the same instance. Delegating
   to `RateLimitHelper` means `X-Forwarded-For`/`X-Real-IP` are honoured, but **only** from a peer listed in
   Fess's `rate.limit.trusted.proxies` (default `127.0.0.1,::1`). The residual weakness: where a trusted proxy
   *is* configured, the first `X-Forwarded-For` element is used, and a client can forge it if that proxy
   appends to the header rather than replacing it. Fess's own rate-limit filter accepts the same trade-off,
   and it is the better default — the alternative is a limiter that is not merely bypassable but actively
   harmful to innocent clients, who all share a bucket they cannot influence.

   A token with no (or a blank) `sub` claim is authenticated but has no subject to key on, so it falls back to
   the client IP and shares one bucket with every other such caller behind the same peer.
9. **Boolean config keys accept only `true` (case-insensitive)**, not `1` or `yes`. Fess's
   `getSystemPropertyAsBoolean` is a case-insensitive comparison against the string `"true"`.
10. **`resources/read` is rate-limited**, though the specification names only `tools/call` and
    `completion/complete`. Reading `fess://document/<id>` makes the identical backend document fetch the
    rate-limited `get_document` tool makes, so leaving it out left `Mcp-Method: resources/read` as an
    unmetered channel that simply bypassed the limit on `tools/call`.
11. **The protected-resource metadata URL is built under the application's prefix**, not host-rooted as
    RFC 9728 §3.1 specifies. The deviation is forced — the §3.1 URL is above the servlet context and Fess
    cannot serve it at all — and the two forms coincide at the root, so only subpath deployments see any
    difference.
12. **Tool inputs are type-checked, not schema-validated.** `inputSchema` is advertised for clients to
    validate against; the server checks that required arguments are present and that each declared argument
    has the right top-level JSON type, but does not validate nested element types, formats, or ranges. There
    is no JSON Schema validator on the plugin's classpath, and the plugin ships as a single JAR bundling no
    dependencies of its own, so adding one is a packaging decision rather than a code change. Container shape
    *is* checked, because those were the cases that reached a raw `ClassCastException` or
    `ArrayStoreException` inside the search and came back as a redacted correlation id.

## Migrating from an earlier MCP revision

A client built against `2024-11-05` or any revision before `2026-07-28` cannot connect to this endpoint at
all. The changes that matter, in the order a client hits them:

| Change | What it means for a client |
|--------|----------------------------|
| **`initialize` is gone** | Returns HTTP 404 with `-32601`; the message and `data.supportedVersions` name the one version this server speaks. Use [`server/discover`](#serverdiscover), which does not negotiate a version because there is only one. |
| **`ping` is gone** | Returns HTTP 404 with `-32601`. There is no liveness-check method any more, and no `data.supportedVersions`, because there is no replacement to fall forward to. |
| **JSON-RPC batching is gone** | A JSON array request body is rejected with HTTP 400. Batching was removed from the JSON-RPC layer in `2025-06-18`, and this server never re-added it as an extension. |
| **Request-metadata headers and `params._meta` are mandatory** | See [Required headers and `params._meta`](#required-headers-and-params_meta). Missing or mismatched headers are HTTP 400 with `-32020`, before dispatch. |
| **An inbound `cursor` is rejected** | The list methods answer a non-null `cursor` with `-32602`. This server returns every item in one page and never issues a `nextCursor`, so any inbound cursor is necessarily stale. An explicit `"cursor": null` is treated as absent, since several mainstream serializers emit one for an unset optional field. |
| **`get_index_stats` is gated by default** | Unavailable to every caller under the default `mcp.auth.mode=none`. See [The `get_index_stats` gate](#the-get_index_stats-gate). |
| **A per-caller rate limit is on by default** | 60 calls/minute on `tools/call`, `completion/complete`, and `resources/read`; HTTP 429 with `Retry-After` beyond that. |
| **`mcp.allowed.origins` is now the complete allowlist** | With the default blank value, **every present `Origin` is rejected with HTTP 403**, including the server's own. Clients that send no `Origin` are unaffected. |

Two retired methods are answered *before* header validation runs, deliberately: a client old enough to call
`initialize` or `ping` cannot send `Mcp-Method` (the header did not exist before this revision) and would
otherwise be told to add a header rather than that the method is gone.

Behaviour changes for clients that already speak `2026-07-28` but ran against an earlier build of this plugin:

- **`search` with `num` ≤ 0 returns the default page size, not the maximum.** `{"num": 0}` and `{"num": -1}`
  used to yield `paging.search.page.max.size` (100 by default), the opposite of what a client computing a page
  size and reaching zero intends. They now fall back to `mcp.default.page.size` (3), matching what an
  unparseable `num` and the `suggest` tool already did. A `num` above the maximum is still clamped.
- **`search`'s `offset` argument now works.** It was always advertised as an alias of `start`, but nothing
  read it as one, so a client paginating with `offset` was served page 1 forever. It now sets the start
  position when `start` is absent.
- **`tools/call` no longer requires `params.arguments`.** The normative schema declares it optional, and
  `get_index_stats` takes no arguments, so a conformant client could not reach that tool at all.
- **`as` no longer discards `q`.** See the note under [`search`](#search).

Notifications (a JSON-RPC request with no `id`) are unaffected by any of the above: they are still accepted
with HTTP 202 and no body, and require neither the metadata headers nor `_meta`.

---

# Project

## Development

```bash
# Build
mvn clean package

# Test
mvn test

# Format (run before committing)
mvn formatter:format && mvn license:format
```

Architecture notes for contributors are in [CLAUDE.md](CLAUDE.md).

## Contributing

Issues and pull requests are welcome at
[github.com/codelibs/fess-webapp-mcp](https://github.com/codelibs/fess-webapp-mcp). For questions and bug
reports, please use [GitHub Issues](https://github.com/codelibs/fess-webapp-mcp/issues).

## License

Apache License 2.0. See [LICENSE](LICENSE).
