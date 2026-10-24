# Upgrading

Notes for operators and client authors moving to a newer version of the MCP WebApp plugin or of Fess.

## Upgrading Fess

**When upgrading Fess, upgrade this plugin before starting the new version.** A plugin JAR built for an older
Fess must not stay in `WEB-INF/plugin`: the 15.8 JAR, for example, relied on a library the 15.9 war no longer
ships, so Fess 15.9 fails to initialize at startup — every page and API, not only `/mcp`, answers 404. Run
`bin/fess-setup upgrade plugins` after copying the plugin directory over, as the
[upgrade guide](https://fess.codelibs.org/15.9/install/upgrade.html) describes.

## Migrating from an earlier MCP revision

A client built against `2025-11-25` or `2025-06-18` connects as a [legacy client](README.md#legacy-clients) and needs
none of what follows. A client built against `2025-03-26` or earlier cannot connect. For a client moving to
`2026-07-28`, the changes that matter, in the order it hits them:

| Change | What it means for a client |
|--------|----------------------------|
| **`initialize` is gone** | Opens a legacy session when `mcp.legacy.protocol.enabled` is on. Otherwise returns HTTP 404 with `-32601`; the message and `data.supportedVersions` name the one version this server speaks. Use [`server/discover`](README.md#serverdiscover), which does not negotiate a version because there is only one. |
| **`ping` is gone** | Answered only for a legacy client. Otherwise returns HTTP 404 with `-32601`. There is no liveness-check method any more, and no `data.supportedVersions`, because there is no replacement to fall forward to. |
| **JSON-RPC batching is gone** | A JSON array request body is rejected with HTTP 400. Batching was removed from the JSON-RPC layer in `2025-06-18`, and this server never re-added it as an extension. |
| **Request-metadata headers and `params._meta` are mandatory** | See [Required headers and `params._meta`](README.md#required-headers-and-params_meta). Missing or mismatched headers are HTTP 400 with `-32020`, before dispatch. |
| **An inbound `cursor` is rejected** | The list methods answer a non-null `cursor` with `-32602`. This server returns every item in one page and never issues a `nextCursor`, so any inbound cursor is necessarily stale. An explicit `"cursor": null` is treated as absent, since several mainstream serializers emit one for an unset optional field. |
| **`get_index_stats` is gated by default** | Unavailable to every caller under the default `mcp.auth.mode=none`. See [The `get_index_stats` gate](README.md#the-get_index_stats-gate). |
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
- **`as` no longer discards `q`.** See the note under [`search`](README.md#search).

Notifications (a JSON-RPC request with no `id`) are unaffected by any of the above: they are still accepted
with HTTP 202 and no body, and require neither the metadata headers nor `_meta`.
